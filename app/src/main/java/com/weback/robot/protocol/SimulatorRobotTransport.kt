package com.weback.robot.protocol

import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.core.MonotonicClock
import com.weback.robot.core.SystemMonotonicClock
import com.weback.robot.domain.control.RobotStateMachine
import com.weback.robot.domain.control.TransitionDecision
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SimulatorErrorCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic offline simulator implementing unified [stateMutex] mutation ownership,
 * generation-scoped virtual-time progression, and explicit [close] lifecycle cleanup.
 */
class SimulatorRobotTransport(
    initialState: RobotState = RobotState(),
    dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val config: SimulatorConfig = SimulatorConfig(),
    private val clock: MonotonicClock = SystemMonotonicClock
) : RobotTransport, AutoCloseable {

    private val stateMutex = Mutex()
    private val commitLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

    private val _robotState = MutableStateFlow(normalizeState(initialState))
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    private var stateGeneration: Long = 0L

    private var activeSimulationJob: Job? = null
    private var nextCommandRejection: CommandResult.Rejected? = null
    private var nextCommandException: Throwable? = null

    @Volatile
    private var closed: Boolean = false

    init {
        synchronized(commitLock) {
            val current = _robotState.value
            activeSimulationJob = launchSimulationForStateLocked(current, stateGeneration)
        }
    }

    override suspend fun sendCommand(command: RobotCommand): CommandResult {
        if (synchronized(commitLock) { closed }) {
            return CommandResult.Rejected(
                reason = CommandRejectionReason.TRANSPORT_ERROR,
                message = "Simulator transport has been closed.",
                settledAtMs = clock.elapsedMs()
            )
        }
        if (config.ackDelayMs > 0L) {
            delay(config.ackDelayMs)
        }

        // Simulator command commit point: atomic validation, close-ordering, and state update.
        return stateMutex.withLock {
            synchronized(commitLock) {
                if (closed) {
                    return@synchronized CommandResult.Rejected(
                        reason = CommandRejectionReason.TRANSPORT_ERROR,
                        message = "Simulator transport has been closed.",
                        settledAtMs = clock.elapsedMs()
                    )
                }

                nextCommandException?.let { throwable ->
                    nextCommandException = null
                    throw throwable
                }

                nextCommandRejection?.let { injected ->
                    nextCommandRejection = null
                    return@synchronized injected.copy(settledAtMs = clock.elapsedMs())
                }

                val current = _robotState.value
                when (val decision = RobotStateMachine.evaluateTransition(current, command)) {
                    is TransitionDecision.Rejected -> CommandResult.Rejected(
                        reason = decision.reason,
                        message = decision.message,
                        settledAtMs = clock.elapsedMs()
                    )
                    is TransitionDecision.Accepted -> {
                        val settledAtMs = clock.elapsedMs()
                        val updated = current.copy(mode = decision.targetMode)
                        commitStateLocked(updated)
                        CommandResult.Acknowledged(
                            resultingState = _robotState.value,
                            settledAtMs = settledAtMs
                        )
                    }
                }
            }
        }
    }

    suspend fun injectNextCommandRejection(
        reason: CommandRejectionReason,
        message: String
    ) {
        stateMutex.withLock {
            synchronized(commitLock) {
                if (closed) return@synchronized
                nextCommandRejection = CommandResult.Rejected(
                    reason = reason,
                    message = message,
                    settledAtMs = 0L
                )
            }
        }
    }

    suspend fun injectNextCommandException(throwable: Throwable) {
        stateMutex.withLock {
            synchronized(commitLock) {
                if (closed) return@synchronized
                nextCommandException = throwable
            }
        }
    }

    suspend fun injectFault(
        errorCode: Int = SimulatorErrorCodes.SYNTHETIC_INJECTED_FAULT
    ) {
        require(errorCode != 0) { "Injected fault errorCode must be nonzero" }
        stateMutex.withLock {
            synchronized(commitLock) {
                if (closed) return@synchronized
                val current = _robotState.value
                commitStateLocked(
                    current.copy(
                        mode = RobotMode.FAULT,
                        errorCode = errorCode
                    )
                )
            }
        }
    }

    fun hasActiveSimulationJob(): Boolean = synchronized(commitLock) {
        !closed && activeSimulationJob?.isActive == true
    }

    override fun close() {
        synchronized(commitLock) {
            if (closed) return
            closed = true
            stateGeneration += 1L
            activeSimulationJob?.cancel()
            activeSimulationJob = null
            nextCommandRejection = null
            nextCommandException = null
            scope.cancel()
        }
    }

    private fun commitStateLocked(candidate: RobotState) {
        if (closed) return
        val normalized = normalizeState(candidate)
        _robotState.value = normalized
        stateGeneration += 1L
        activeSimulationJob?.cancel()
        activeSimulationJob = null
        activeSimulationJob = launchSimulationForStateLocked(normalized, stateGeneration)
    }

    private fun launchSimulationForStateLocked(state: RobotState, expectedGeneration: Long): Job? {
        if (closed) return null
        return when (state.mode) {
            RobotMode.CLEAN -> scope.launch {
                while (isActive) {
                    delay(config.cleanBatteryDrainIntervalMs)
                    val shouldContinue = stateMutex.withLock {
                        synchronized(commitLock) {
                            if (closed || stateGeneration != expectedGeneration || _robotState.value.mode != RobotMode.CLEAN) {
                                return@synchronized false
                            }
                            val current = _robotState.value
                            val nextBattery = (current.batteryPercentage.toLong() - config.cleanBatteryDrainStep.toLong())
                                .coerceIn(0L, 100L)
                                .toInt()
                            when {
                                nextBattery == 0 -> {
                                    commitStateLocked(
                                        current.copy(
                                            batteryPercentage = 0,
                                            mode = RobotMode.FAULT,
                                            errorCode = SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED
                                        )
                                    )
                                    false
                                }
                                nextBattery < RobotStateMachine.MIN_CLEAN_BATTERY_PERCENT -> {
                                    commitStateLocked(
                                        current.copy(
                                            batteryPercentage = nextBattery,
                                            mode = RobotMode.RETURNING_DOCK
                                        )
                                    )
                                    false
                                }
                                else -> {
                                    _robotState.value = current.copy(batteryPercentage = nextBattery)
                                    true
                                }
                            }
                        }
                    }
                    if (!shouldContinue) break
                }
            }

            RobotMode.RETURNING_DOCK -> scope.launch {
                delay(config.returnToDockDurationMs)
                stateMutex.withLock {
                    synchronized(commitLock) {
                        if (closed || stateGeneration != expectedGeneration || _robotState.value.mode != RobotMode.RETURNING_DOCK) {
                            return@synchronized
                        }
                        val current = _robotState.value
                        commitStateLocked(current.copy(mode = RobotMode.CHARGING))
                    }
                }
            }

            RobotMode.CHARGING -> scope.launch {
                while (isActive) {
                    delay(config.chargeIntervalMs)
                    val shouldContinue = stateMutex.withLock {
                        synchronized(commitLock) {
                            if (closed || stateGeneration != expectedGeneration || _robotState.value.mode != RobotMode.CHARGING) {
                                return@synchronized false
                            }
                            val current = _robotState.value
                            val nextBattery = (current.batteryPercentage.toLong() + config.chargeStepPercent.toLong())
                                .coerceIn(0L, 100L)
                                .toInt()
                            if (nextBattery == 100) {
                                commitStateLocked(
                                    current.copy(
                                        batteryPercentage = 100,
                                        mode = RobotMode.DOCK
                                    )
                                )
                                false
                            } else {
                                _robotState.value = current.copy(batteryPercentage = nextBattery)
                                true
                            }
                        }
                    }
                    if (!shouldContinue) break
                }
            }

            RobotMode.DOCK,
            RobotMode.PAUSED,
            RobotMode.DORMANT,
            RobotMode.IDLE,
            RobotMode.FAULT -> null
        }
    }

    private fun normalizeState(state: RobotState): RobotState = when {
        state.errorCode != 0 && state.mode != RobotMode.FAULT ->
            state.copy(mode = RobotMode.FAULT)
        state.batteryPercentage == 0 && !state.isDocked && state.mode != RobotMode.FAULT ->
            state.copy(
                mode = RobotMode.FAULT,
                errorCode = SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED
            )
        else -> state
    }
}
