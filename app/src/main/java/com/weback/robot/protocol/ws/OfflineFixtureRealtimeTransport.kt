package com.weback.robot.protocol.ws

import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic in-process fixture WebSocket transport used by offline mode (`DefaultAppContainer`)
 * without live sockets or network permissions.
 */
class OfflineFixtureRealtimeTransport : RealtimeSessionTransport {

    private val lock = Any()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _robotState = MutableStateFlow(RobotState())
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    private var activeEpoch: Long = 0L
    private var generation: Long = 0L
    private var closed: Boolean = false
    private var selectedDevice: DeviceInventoryItem? = null

    override fun connect(session: SessionCredentials, epoch: Long) {
        synchronized(lock) {
            if (closed) return
            activeEpoch = epoch
            generation++
            _connectionState.value = ConnectionState.Connected(epoch, generation)
        }
    }

    override fun sendOutbound(payload: String, epoch: Long): Boolean = synchronized(lock) {
        !closed && epoch == activeEpoch && _connectionState.value is ConnectionState.Connected
    }

    override fun selectDevice(item: DeviceInventoryItem?, epoch: Long) {
        synchronized(lock) {
            if (closed) return
            if (activeEpoch != 0L && epoch < activeEpoch) return
            if (activeEpoch == 0L || epoch > activeEpoch) {
                activeEpoch = epoch
            }
            generation++
            selectedDevice = item
            _robotState.value = item?.initialState ?: RobotState()
        }
    }

    fun emitTelemetry(thingName: String, updatedState: RobotState, epoch: Long, gen: Long): Boolean {
        synchronized(lock) {
            if (closed || epoch != activeEpoch || gen != generation) return false
            val current = selectedDevice ?: return false
            if (!current.capability.compatibility.isMvpControlSupported) return false
            if (current.thingName != thingName) return false
            _robotState.value = updatedState
            return true
        }
    }

    override fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError) {
        synchronized(lock) {
            if (closed || expectedEpoch != activeEpoch) return
            activeEpoch++
            generation++
            selectedDevice = null
            _robotState.value = RobotState()
            _connectionState.value = ConnectionState.Disconnected(reason)
        }
    }

    override fun currentGeneration(): Long = synchronized(lock) { generation }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            activeEpoch++
            generation++
            selectedDevice = null
            _connectionState.value = ConnectionState.Closed
        }
    }
}
