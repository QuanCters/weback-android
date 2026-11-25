package com.weback.robot.protocol.ws

import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.OkHttpSecurityGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Clean-room OkHttp WebSocket adapter (`REG-09`, `IC-303`, `SEC-304`).
 * - Enforces explicit [EndpointTrustPolicy] + pre-connect & in-connection session expiry checks.
 * - Attaches ONLY `"token"` and `"region"` HTTP upgrade headers (zero query tokens, disabled redirects).
 * - Fences every listener callback and reconnect timer with `(sessionEpoch, connectionGeneration)`.
 * - Enforces active selected `thing_name` AND `MvpSupportedShadowWs` capability before applying telemetry.
 */
class OkHttpWebSocketTransport(
    private val trustPolicy: EndpointTrustPolicy,
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val connectionConfig: ConnectionConfig = ConnectionConfig(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val client: OkHttpClient = OkHttpSecurityGuard.buildHardenedClient(trustPolicy),
    private val onSessionInvalidated: (Long, AuthFailureReason) -> Unit = { _, _ -> }
) : RealtimeSessionTransport {

    private val json = Json { ignoreUnknownKeys = true }
    private val commitLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _robotState = MutableStateFlow(RobotState())
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    private var activeEpoch: Long = 0L
    private var activeGeneration: Long = 0L
    private var isClosed: Boolean = false
    private var activeSession: SessionCredentials? = null
    private var activeSelectedDevice: DeviceInventoryItem? = null
    private var activeSocket: WebSocket? = null
    private var lastActivityElapsedMs: Long = 0L
    private var reconnectAttempt: Int = 0
    private var reconnectJob: Job? = null
    private var expiryWatchJob: Job? = null

    @Serializable
    private data class WsTelemetryFrame(
        @SerialName("notify_info") val notifyInfo: String? = null,
        @SerialName("thing_name") val thingName: String? = null,
        @SerialName("working_status") val workingStatus: String? = null,
        @SerialName("battery_level") val batteryLevel: Int? = null,
        @SerialName("error_info") val errorInfo: Int? = null
    )

    override fun currentGeneration(): Long = synchronized(commitLock) { activeGeneration }

    override fun selectDevice(item: DeviceInventoryItem?, epoch: Long) {
        synchronized(commitLock) {
            if (isClosed) return
            if (activeEpoch != 0L && epoch < activeEpoch) return
            if (activeEpoch == 0L || epoch > activeEpoch) {
                activeEpoch = epoch
            }
            activeGeneration++
            activeSelectedDevice = item
            _robotState.value = item?.initialState ?: RobotState()
        }
    }

    override fun connect(session: SessionCredentials, epoch: Long) {
        val nowMs = authClock.elapsedRealtimeMs()
        if (TokenExpiryPolicy.evaluateInProcess(session, nowMs) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            synchronized(commitLock) {
                if (isClosed) return
                activeEpoch = epoch
                activeGeneration++
                cancelJobsLocked()
                activeSocket?.cancel()
                activeSocket = null
                _connectionState.value = ConnectionState.Disconnected(ConnectivityError.TokenExpired)
            }
            onSessionInvalidated(epoch, AuthFailureReason.TokenExpiredOrRevoked)
            return
        }

        if (trustPolicy.authorize(session.wssUrl) !is EndpointTrustDecision.Trusted) {
            synchronized(commitLock) {
                if (isClosed) return
                activeEpoch = epoch
                activeGeneration++
                cancelJobsLocked()
                activeSocket?.cancel()
                activeSocket = null
                _connectionState.value = ConnectionState.Disconnected(ConnectivityError.UntrustedEndpoint)
            }
            return
        }

        val (gen, oldSocket) = synchronized(commitLock) {
            if (isClosed) return
            if (epoch < activeEpoch) return
            cancelJobsLocked()
            val prev = activeSocket
            activeSocket = null
            activeEpoch = epoch
            val newGen = ++activeGeneration
            activeSession = session
            reconnectAttempt = 0
            lastActivityElapsedMs = nowMs
            _connectionState.value = ConnectionState.Connecting(epoch, newGen)
            newGen to prev
        }
        oldSocket?.close(NORMAL_CLOSE_CODE, "Superseded connection")
        openWebSocketInternal(session, epoch, gen)
        scheduleSessionExpiryWatch(session, epoch, gen)
    }

    override fun sendOutbound(payload: String, epoch: Long): Boolean {
        val nowMs = authClock.elapsedRealtimeMs()
        val (socket, session, gen, expired, stalled) = synchronized(commitLock) {
            if (isClosed || epoch != activeEpoch || activeSocket == null) {
                return false
            }
            val currentSession = activeSession ?: return false
            val isExpired = TokenExpiryPolicy.evaluateInProcess(currentSession, nowMs) == LifetimeEvaluation.EXPIRED_OR_INVALID
            val isStalled = (nowMs - lastActivityElapsedMs) > connectionConfig.sendStallTimeoutMs
            SendSnapshot(activeSocket, currentSession, activeGeneration, isExpired, isStalled)
        }

        if (expired) {
            handleExpiryInvalidation(epoch, gen)
            return false
        }
        if (stalled) {
            logger.log(
                DiagnosticEvent(
                    category = "WS_SEND_STALL",
                    sessionEpoch = epoch,
                    connectionGeneration = gen,
                    errorCategory = "SEND_STALL_TIMEOUT"
                )
            )
            socket?.cancel()
            scheduleReconnectIfCurrent(session, epoch, gen, ConnectivityError.TransportTimeout)
            return false
        }
        return socket?.send(payload) ?: false
    }

    /**
     * Deterministically checks active session expiry while a socket is connected (`IC-303`).
     */
    fun enforceSessionExpiryIfNeeded(epoch: Long) {
        val nowMs = authClock.elapsedRealtimeMs()
        val (isExpired, gen) = synchronized(commitLock) {
            if (isClosed || epoch != activeEpoch) return
            val s = activeSession ?: return
            val expired = TokenExpiryPolicy.evaluateInProcess(s, nowMs) == LifetimeEvaluation.EXPIRED_OR_INVALID
            expired to activeGeneration
        }
        if (isExpired) {
            handleExpiryInvalidation(epoch, gen)
        }
    }

    override fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError) {
        val socketToClose = synchronized(commitLock) {
            if (isClosed || expectedEpoch != activeEpoch) return
            activeEpoch++
            activeGeneration++
            activeSession = null
            activeSelectedDevice = null
            _robotState.value = RobotState()
            cancelJobsLocked()
            val s = activeSocket
            activeSocket = null
            _connectionState.value = ConnectionState.Disconnected(reason)
            s
        }
        socketToClose?.close(NORMAL_CLOSE_CODE, "Session invalidated")
    }

    override fun close() {
        val socketToClose = synchronized(commitLock) {
            if (isClosed) return
            isClosed = true
            activeEpoch++
            activeGeneration++
            activeSession = null
            activeSelectedDevice = null
            cancelJobsLocked()
            val s = activeSocket
            activeSocket = null
            _connectionState.value = ConnectionState.Closed
            s
        }
        socketToClose?.close(NORMAL_CLOSE_CODE, "Client closed")
        scope.cancel()
    }

    /**
     * Deterministic entrypoint enabling generation/device ownership tests (`TEST-CONN-308`, `TEST-CONN-309`)
     * to inject parsed frames without real clock sleeps.
     */
    internal fun onInboundFrameForTest(frameEpoch: Long, frameGeneration: Long, rawText: String) {
        handleInboundMessage(frameEpoch, frameGeneration, rawText)
    }

    private fun openWebSocketInternal(session: SessionCredentials, epoch: Long, generation: Long) {
        val request = Request.Builder()
            .url(session.wssUrl)
            .addHeader("token", session.jwtToken)
            .addHeader("region", session.regionName)
            .build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val nowMs = authClock.elapsedRealtimeMs()
                val sessionExpired = synchronized(commitLock) {
                    if (isClosed || epoch != activeEpoch || generation != activeGeneration) {
                        true
                    } else {
                        lastActivityElapsedMs = nowMs
                        reconnectAttempt = 0
                        activeSocket = webSocket
                        _connectionState.value = ConnectionState.Connected(epoch, generation)
                        false
                    }
                }
                if (sessionExpired) {
                    webSocket.close(NORMAL_CLOSE_CODE, "Superseded generation")
                    return
                }
                logger.log(
                    DiagnosticEvent(
                        category = "WS_OPEN",
                        httpStatus = response.code,
                        sessionEpoch = epoch,
                        connectionGeneration = generation
                    )
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleInboundMessage(epoch, generation, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(commitLock) {
                    if (isClosed || epoch != activeEpoch || generation != activeGeneration) return
                    activeSocket = null
                    _connectionState.value = ConnectionState.Disconnected()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                val isAuthRejection = httpCode == 401 || httpCode == 403 || (httpCode != null && httpCode in 300..399)
                val err: ConnectivityError = when {
                    httpCode == 401 -> ConnectivityError.TokenExpired
                    httpCode == 403 -> ConnectivityError.AuthorizationDenied
                    httpCode != null && httpCode in 300..399 -> ConnectivityError.RedirectRejected
                    httpCode != null -> ConnectivityError.HandshakeRejected(httpCode)
                    else -> ConnectivityError.NetworkUnavailable
                }

                val stillActive = synchronized(commitLock) {
                    if (isClosed || epoch != activeEpoch || generation != activeGeneration) {
                        false
                    } else {
                        activeSocket = null
                        true
                    }
                }
                if (!stillActive) return

                logger.log(
                    DiagnosticEvent(
                        category = "WS_FAILURE",
                        httpStatus = httpCode,
                        sessionEpoch = epoch,
                        connectionGeneration = generation,
                        errorCategory = err.safeCategory
                    )
                )

                if (isAuthRejection) {
                    synchronized(commitLock) {
                        if (isClosed || epoch != activeEpoch || generation != activeGeneration) return
                        cancelJobsLocked()
                        activeGeneration++
                        _connectionState.value = ConnectionState.Disconnected(err)
                    }
                    onSessionInvalidated(epoch, connectivityErrorToAuthFailure(err))
                } else {
                    scheduleReconnectIfCurrent(session, epoch, generation, err)
                }
            }
        })

        val stale = synchronized(commitLock) {
            if (isClosed || epoch != activeEpoch || generation != activeGeneration) {
                true
            } else {
                activeSocket = ws
                false
            }
        }
        if (stale) {
            ws.cancel()
        }
    }

    private fun handleInboundMessage(frameEpoch: Long, frameGeneration: Long, rawText: String) {
        val nowMs = authClock.elapsedRealtimeMs()
        val parsed = runCatching { json.decodeFromString<WsTelemetryFrame>(rawText) }.getOrNull() ?: return
        if (parsed.notifyInfo != "thing_status_update") return

        var expiredNow = false
        synchronized(commitLock) {
            if (isClosed || frameEpoch != activeEpoch || frameGeneration != activeGeneration) return
            val s = activeSession
            if (s != null && TokenExpiryPolicy.evaluateInProcess(s, nowMs) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
                expiredNow = true
            } else {
                lastActivityElapsedMs = nowMs
                val selected = activeSelectedDevice ?: return
                if (!selected.capability.compatibility.isMvpControlSupported) return
                val frameThing = parsed.thingName?.takeIf { it.isNotBlank() } ?: return
                if (frameThing != selected.thingName) return

                val prev = _robotState.value
                _robotState.value = prev.copy(
                    name = selected.nickname,
                    batteryPercentage = (parsed.batteryLevel ?: prev.batteryPercentage).coerceIn(0, 100),
                    mode = if (parsed.workingStatus != null) {
                        OkHttpDeviceInventoryGateway.mapWorkingStatus(parsed.workingStatus)
                    } else {
                        prev.mode
                    },
                    errorCode = parsed.errorInfo ?: prev.errorCode
                )
            }
        }
        if (expiredNow) {
            handleExpiryInvalidation(frameEpoch, frameGeneration)
        }
    }

    private fun scheduleReconnectIfCurrent(
        session: SessionCredentials,
        epoch: Long,
        generation: Long,
        reason: ConnectivityError
    ) {
        val nextDelay: Long? = synchronized(commitLock) {
            if (isClosed || epoch != activeEpoch || generation != activeGeneration) return
            reconnectJob?.cancel()
            val nextAttempt = reconnectAttempt + 1
            if (nextAttempt > connectionConfig.maxReconnectAttempts) {
                _connectionState.value = ConnectionState.Disconnected(reason)
                return
            }
            reconnectAttempt = nextAttempt
            val delayMs = connectionConfig.reconnectDelayMs
            _connectionState.value = ConnectionState.Reconnecting(
                epoch = epoch,
                generation = generation,
                attempt = nextAttempt,
                retryDelayMs = delayMs,
                reason = reason
            )
            delayMs
        }
        if (nextDelay == null) return

        val job = scope.launch {
            delay(nextDelay)
            val newGen = synchronized(commitLock) {
                if (isClosed || epoch != activeEpoch || generation != activeGeneration) {
                    null
                } else {
                    val nowMs = authClock.elapsedRealtimeMs()
                    if (TokenExpiryPolicy.evaluateInProcess(session, nowMs) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
                        -1L
                    } else {
                        val updatedGen = ++activeGeneration
                        _connectionState.value = ConnectionState.Connecting(epoch, updatedGen)
                        updatedGen
                    }
                }
            } ?: return@launch

            if (newGen == -1L) {
                handleExpiryInvalidation(epoch, generation)
                return@launch
            }
            openWebSocketInternal(session, epoch, newGen)
            scheduleSessionExpiryWatch(session, epoch, newGen)
        }
        synchronized(commitLock) {
            if (!isClosed && epoch == activeEpoch && generation == activeGeneration) {
                reconnectJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun scheduleSessionExpiryWatch(session: SessionCredentials, epoch: Long, generation: Long) {
        val remainingMs = TokenExpiryPolicy.remainingLifetimeMs(session, authClock.elapsedRealtimeMs())
        val job = scope.launch {
            delay(remainingMs.coerceAtLeast(1L))
            val shouldInvalidate = synchronized(commitLock) {
                !isClosed && epoch == activeEpoch && generation == activeGeneration
            }
            if (shouldInvalidate) {
                handleExpiryInvalidation(epoch, generation)
            }
        }
        synchronized(commitLock) {
            if (!isClosed && epoch == activeEpoch && generation == activeGeneration) {
                expiryWatchJob?.cancel()
                expiryWatchJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun handleExpiryInvalidation(epoch: Long, expectedGen: Long) {
        val socketToClose = synchronized(commitLock) {
            if (isClosed || epoch != activeEpoch || expectedGen != activeGeneration) return
            cancelJobsLocked()
            activeGeneration++
            val s = activeSocket
            activeSocket = null
            activeSession = null
            _connectionState.value = ConnectionState.Disconnected(ConnectivityError.TokenExpired)
            s
        }
        socketToClose?.close(NORMAL_CLOSE_CODE, "Session expired")
        onSessionInvalidated(epoch, AuthFailureReason.TokenExpiredOrRevoked)
    }

    private fun cancelJobsLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        expiryWatchJob?.cancel()
        expiryWatchJob = null
    }

    private data class SendSnapshot(
        val socket: WebSocket?,
        val session: SessionCredentials,
        val generation: Long,
        val isExpired: Boolean,
        val isStalled: Boolean
    )

    companion object {
        private const val NORMAL_CLOSE_CODE = 1000
    }
}
