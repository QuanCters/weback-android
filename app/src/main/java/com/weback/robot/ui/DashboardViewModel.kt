package com.weback.robot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.core.MonotonicClock
import com.weback.robot.core.SystemMonotonicClock
import com.weback.robot.domain.control.RobotStateMachine
import com.weback.robot.domain.control.TransitionDecision
import com.weback.robot.domain.model.CommandExecutionState
import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.ControlAvailability
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.protocol.RobotTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Unidirectional user intents accepted by [DashboardViewModel].
 */
sealed interface DashboardIntent {
    data object Clean : DashboardIntent
    data object Pause : DashboardIntent
    data object Resume : DashboardIntent
    data object Dock : DashboardIntent
    data object DismissCommandFeedback : DashboardIntent
}

/**
 * Immutable UI state preserving FCT-001 telemetry fields while adding FCT-002 control lifecycle.
 */
data class RobotUiState(
    val robotName: String = "WeBack Simulator Robot",
    val batteryPercentage: Int = 100,
    val mode: RobotMode = RobotMode.DOCK,
    val isDocked: Boolean = true,
    val isDustbinClean: Boolean = true,
    val isSimulator: Boolean = true,
    val appVersion: String = "v0.1.0-alpha (FCT-001 Foundation)",
    val errorCode: Int = 0,
    val isFault: Boolean = false,
    val statusSummary: String = "Simulator Active (Offline)",
    val commandState: CommandExecutionState = CommandExecutionState.Idle,
    val busyConflictNotice: String? = null,
    val controls: ControlAvailability = ControlAvailability.from(RobotState(), CommandExecutionState.Idle)
)

/**
 * Single-writer MVI ViewModel synchronizing telemetry emissions, atomic Pending command admission,
 * bounded timeouts, cancellation cleanup, and identity-correlated settlement.
 */
class DashboardViewModel(
    private val robotTransport: RobotTransport,
    private val dispatchers: DispatcherProvider,
    coroutineScope: CoroutineScope? = null,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
) : ViewModel() {

    init {
        require(commandTimeoutMs > 0L) { "commandTimeoutMs must be > 0, got $commandTimeoutMs" }
    }

    private val scope = coroutineScope ?: viewModelScope
    private val stateLock = Any()

    private var nextCommandId: Long = 1L
    private var telemetryVersion: Long = 0L
    private var committedRobotState: RobotState = robotTransport.robotState.value
    private var activePendingSnapshot: PendingSnapshot? = null

    private data class PendingSnapshot(
        val commandId: Long,
        val submittedState: RobotState,
        val submittedTelemetryVersion: Long
    )

    private val _uiState = MutableStateFlow(
        buildUiState(
            robotState = committedRobotState,
            commandState = CommandExecutionState.Idle,
            busyConflictNotice = null
        )
    )
    val uiState: StateFlow<RobotUiState> = _uiState.asStateFlow()

    init {
        scope.launch(dispatchers.main) {
            robotTransport.robotState.collect { newState ->
                synchronized(stateLock) {
                    if (newState != committedRobotState) {
                        telemetryVersion += 1L
                    }
                    committedRobotState = newState
                    val current = _uiState.value
                    _uiState.value = buildUiState(
                        robotState = newState,
                        commandState = current.commandState,
                        busyConflictNotice = current.busyConflictNotice
                    )
                }
            }
        }
    }

    fun onIntent(intent: DashboardIntent) {
        val command = when (intent) {
            DashboardIntent.Clean -> RobotCommand.CLEAN
            DashboardIntent.Pause -> RobotCommand.PAUSE
            DashboardIntent.Resume -> RobotCommand.RESUME
            DashboardIntent.Dock -> RobotCommand.DOCK
            DashboardIntent.DismissCommandFeedback -> {
                handleDismissFeedback()
                return
            }
        }

        val pendingCommandId: Long? = synchronized(stateLock) {
            if (!scope.isActive) {
                return@synchronized null
            }
            val latestTransport = robotTransport.robotState.value
            if (latestTransport != committedRobotState) {
                telemetryVersion += 1L
                committedRobotState = latestTransport
            }
            val current = _uiState.value
            val currentCmd = current.commandState
            if (currentCmd is CommandExecutionState.Pending) {
                if (currentCmd.command != command) {
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = currentCmd,
                        busyConflictNotice = "${CommandRejectionReason.COMMAND_IN_FLIGHT.name}: ${currentCmd.command.name} is still pending; ${command.name} blocked."
                    )
                }
                return@synchronized null
            }

            val cmdId = nextCommandId++
            val nowMs = clock.elapsedMs()
            when (val decision = RobotStateMachine.evaluateTransition(committedRobotState, command)) {
                is TransitionDecision.Rejected -> {
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = CommandExecutionState.Rejected(
                            command = command,
                            commandId = cmdId,
                            reason = decision.reason,
                            message = decision.message,
                            settledAtMs = nowMs
                        ),
                        busyConflictNotice = null
                    )
                    null
                }
                is TransitionDecision.Accepted -> {
                    activePendingSnapshot = PendingSnapshot(
                        commandId = cmdId,
                        submittedState = committedRobotState,
                        submittedTelemetryVersion = telemetryVersion
                    )
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = CommandExecutionState.Pending(
                            command = command,
                            commandId = cmdId,
                            submittedAtMs = nowMs
                        ),
                        busyConflictNotice = null
                    )
                    cmdId
                }
            }
        }

        if (pendingCommandId != null) {
            dispatchPendingCommand(command, pendingCommandId)
        }
    }

    private fun handleDismissFeedback() {
        synchronized(stateLock) {
            val current = _uiState.value
            when (current.commandState) {
                is CommandExecutionState.Pending -> {
                    // Dismissing feedback while Pending clears only transient conflict notice without releasing lock.
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = current.commandState,
                        busyConflictNotice = null
                    )
                }
                is CommandExecutionState.Acknowledged,
                is CommandExecutionState.Rejected -> {
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = CommandExecutionState.Idle,
                        busyConflictNotice = null
                    )
                }
                CommandExecutionState.Idle -> {
                    if (current.busyConflictNotice != null) {
                        _uiState.value = buildUiState(
                            robotState = committedRobotState,
                            commandState = CommandExecutionState.Idle,
                            busyConflictNotice = null
                        )
                    }
                }
            }
        }
    }

    private fun dispatchPendingCommand(command: RobotCommand, commandId: Long) {
        val job = scope.launch(dispatchers.main) {
            try {
                val result = withTimeout(commandTimeoutMs) {
                    robotTransport.sendCommand(command)
                }
                settleCommandResult(command, commandId, result)
            } catch (timeout: TimeoutCancellationException) {
                if (!coroutineContext.isActive) {
                    cleanupCancelledPending(commandId)
                    throw timeout
                }
                settleRejection(
                    command = command,
                    commandId = commandId,
                    reason = CommandRejectionReason.TIMEOUT,
                    message = timeout.message?.takeIf { it.isNotBlank() }
                        ?: "Command ${command.name} timed out after ${commandTimeoutMs}ms."
                )
            } catch (ce: CancellationException) {
                cleanupCancelledPending(commandId)
                throw ce
            } catch (throwable: Throwable) {
                settleRejection(
                    command = command,
                    commandId = commandId,
                    reason = CommandRejectionReason.TRANSPORT_ERROR,
                    message = throwable.message?.takeIf { it.isNotBlank() }
                        ?: "Unexpected transport failure: ${throwable::class.simpleName}"
                )
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cleanupCancelledPending(commandId)
            }
        }
    }

    internal fun settleCommandResult(
        command: RobotCommand,
        commandId: Long,
        result: CommandResult
    ) {
        synchronized(stateLock) {
            val currentCmd = _uiState.value.commandState
            if (currentCmd !is CommandExecutionState.Pending || currentCmd.commandId != commandId) {
                return
            }
            val snapshot = activePendingSnapshot
            activePendingSnapshot = null

            when (result) {
                is CommandResult.Acknowledged -> {
                    val latestTransport = robotTransport.robotState.value
                    val telemetryMovedForward = snapshot != null &&
                        (telemetryVersion > snapshot.submittedTelemetryVersion || latestTransport != snapshot.submittedState)
                    committedRobotState = if (telemetryMovedForward) {
                        latestTransport
                    } else {
                        result.resultingState
                    }
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = CommandExecutionState.Acknowledged(
                            command = command,
                            commandId = commandId,
                            settledAtMs = clock.elapsedMs()
                        ),
                        busyConflictNotice = null
                    )
                }
                is CommandResult.Rejected -> {
                    committedRobotState = robotTransport.robotState.value
                    _uiState.value = buildUiState(
                        robotState = committedRobotState,
                        commandState = CommandExecutionState.Rejected(
                            command = command,
                            commandId = commandId,
                            reason = result.reason,
                            message = result.message,
                            settledAtMs = clock.elapsedMs()
                        ),
                        busyConflictNotice = null
                    )
                }
            }
        }
    }

    private fun settleRejection(
        command: RobotCommand,
        commandId: Long,
        reason: CommandRejectionReason,
        message: String
    ) {
        synchronized(stateLock) {
            val currentCmd = _uiState.value.commandState
            if (currentCmd !is CommandExecutionState.Pending || currentCmd.commandId != commandId) {
                return
            }
            activePendingSnapshot = null
            committedRobotState = robotTransport.robotState.value
            _uiState.value = buildUiState(
                robotState = committedRobotState,
                commandState = CommandExecutionState.Rejected(
                    command = command,
                    commandId = commandId,
                    reason = reason,
                    message = message,
                    settledAtMs = clock.elapsedMs()
                ),
                busyConflictNotice = null
            )
        }
    }

    private fun cleanupCancelledPending(commandId: Long) {
        synchronized(stateLock) {
            val currentCmd = _uiState.value.commandState
            if (currentCmd !is CommandExecutionState.Pending || currentCmd.commandId != commandId) {
                return
            }
            activePendingSnapshot = null
            committedRobotState = robotTransport.robotState.value
            _uiState.value = buildUiState(
                robotState = committedRobotState,
                commandState = CommandExecutionState.Idle,
                busyConflictNotice = null
            )
        }
    }

    private fun buildUiState(
        robotState: RobotState,
        commandState: CommandExecutionState,
        busyConflictNotice: String?
    ): RobotUiState {
        val summary = if (robotState.isFault) {
            "Simulator Fault (Error ${robotState.errorCode}) — Offline"
        } else {
            "Simulator Active (Offline)"
        }
        return RobotUiState(
            robotName = robotState.name,
            batteryPercentage = robotState.batteryPercentage,
            mode = robotState.mode,
            isDocked = robotState.isDocked,
            isDustbinClean = robotState.isDustbinClean,
            isSimulator = true,
            appVersion = "v0.1.0-alpha (FCT-001 Foundation)",
            errorCode = robotState.errorCode,
            isFault = robotState.isFault,
            statusSummary = summary,
            commandState = commandState,
            busyConflictNotice = busyConflictNotice,
            controls = ControlAvailability.from(robotState, commandState)
        )
    }

    companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 5_000L
    }

    class Factory(
        private val robotTransport: RobotTransport,
        private val dispatchers: DispatcherProvider,
        private val clock: MonotonicClock = SystemMonotonicClock,
        private val commandTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(
                robotTransport = robotTransport,
                dispatchers = dispatchers,
                clock = clock,
                commandTimeoutMs = commandTimeoutMs
            ) as T
        }
    }
}
