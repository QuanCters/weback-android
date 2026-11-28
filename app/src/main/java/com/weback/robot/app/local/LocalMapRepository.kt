package com.weback.robot.app.local

import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.core.MonotonicClock
import com.weback.robot.core.SystemMonotonicClock
import com.weback.robot.domain.map.HoslamFrame
import com.weback.robot.domain.map.HoslamFrameKind
import com.weback.robot.domain.map.LocalCodecError
import com.weback.robot.protocol.local.LocalStreamFailureCode
import com.weback.robot.protocol.local.LocalTelemetryEvent
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import com.weback.robot.protocol.local.LocalTelemetryStreamSource
import com.weback.robot.protocol.local.SyntheticReplayTelemetrySource
import com.weback.robot.protocol.local.codec.HoslamDecodeResult
import com.weback.robot.protocol.local.codec.HoslamFeedResult
import com.weback.robot.protocol.local.codec.HoslamFinishResult
import com.weback.robot.protocol.local.codec.HoslamFrameDecoder
import com.weback.robot.protocol.local.codec.HoslamStreamFramer
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

/**
 * Single-source-of-truth lifecycle FSM, generation fencing, monotonic timeout tracker, and
 * conflated latest-frame owner for local read-only HOSLAM telemetry (`design.md` §3.1).
 *
 * Holds zero simulator/cloud gateway references and performs zero automatic fallback (`AC-501.3`).
 */
class LocalMapRepository(
    private val streamSource: LocalTelemetryStreamSource,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val diagnosticLogger: LocalDiagnosticSink = LocalDiagnosticLogger(),
    private val framerFactory: () -> HoslamStreamFramer = { HoslamStreamFramer() },
    private val decoderFactory: () -> HoslamFrameDecoder = { HoslamFrameDecoder() }
) : AutoCloseable {

    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.default)

    private val _state = MutableStateFlow(
        LocalMapRepositoryState(sourceKind = streamSource.sourceKind)
    )
    val state: StateFlow<LocalMapRepositoryState> = _state.asStateFlow()

    private var activeGenerationId: Long = 0L
    private var frameGenerationCounter: Long = 0L
    private var sessionStartedElapsedMs: Long = 0L
    private var partialFrameStartedElapsedMs: Long? = null
    private var activeFramer: HoslamStreamFramer = framerFactory()
    private var activeDecoder: HoslamFrameDecoder = decoderFactory()

    private var collectionJob: Job? = null
    private var firstMapJob: Job? = null
    private var staleJob: Job? = null
    private var inFrameJob: Job? = null
    private var sessionCapJob: Job? = null

    private var closed: Boolean = false

    val diagnosticSink: LocalDiagnosticSink
        get() = diagnosticLogger

    fun startSession() {
        val startedGen: Long
        synchronized(stateLock) {
            if (closed) return
            startedGen = ++activeGenerationId
            cancelAllJobsLocked()
            activeFramer = framerFactory()
            activeDecoder = decoderFactory()
            sessionStartedElapsedMs = clock.elapsedMs()
            partialFrameStartedElapsedMs = null
            if (streamSource is SyntheticReplayTelemetrySource) {
                streamSource.resetPauseForReplay()
            }
            val nextReplay = if (streamSource.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                SyntheticReplayPlaybackState.PLAYING
            } else {
                SyntheticReplayPlaybackState.IDLE
            }
            val next = LocalMapRepositoryState(
                sourceKind = streamSource.sourceKind,
                connectionState = LocalMapConnectionState.CONNECTING,
                replayState = nextReplay,
                sessionGeneration = startedGen
            )
            _state.value = next
            logLocked(LocalDiagnosticCategory.SESSION_START, next)

            sessionCapJob = scope.launch {
                waitAndFireSessionCap(startedGen, sessionStartedElapsedMs)
            }
            collectionJob = scope.launch {
                streamSource.openStream().collect { event ->
                    handleStreamEvent(startedGen, event)
                }
            }
        }
    }

    fun playOrResumeReplay() {
        val shouldStart = synchronized(stateLock) {
            if (closed || streamSource.sourceKind != LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                return
            }
            val current = _state.value
            if (current.replayState == SyntheticReplayPlaybackState.PAUSED &&
                current.connectionState.isActiveSessionState()
            ) {
                (streamSource as? SyntheticReplayTelemetrySource)?.resume()
                val resumed = current.copy(replayState = SyntheticReplayPlaybackState.PLAYING)
                _state.value = resumed
                logLocked(LocalDiagnosticCategory.REPLAY_RESUMED, resumed)
                false
            } else {
                true
            }
        }
        if (shouldStart) {
            startSession()
        }
    }

    fun pauseReplay() {
        synchronized(stateLock) {
            if (closed || streamSource.sourceKind != LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                return
            }
            val current = _state.value
            if (current.replayState == SyntheticReplayPlaybackState.PLAYING &&
                current.connectionState.isActiveSessionState()
            ) {
                (streamSource as? SyntheticReplayTelemetrySource)?.pause()
                val paused = current.copy(
                    replayState = SyntheticReplayPlaybackState.PAUSED,
                    mapAgeMs = currentMapAgeMsLocked(current, clock.elapsedMs())
                )
                _state.value = paused
                logLocked(LocalDiagnosticCategory.REPLAY_PAUSED, paused)
            }
        }
    }

    fun restartReplay() {
        synchronized(stateLock) {
            if (closed || streamSource.sourceKind != LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                return
            }
            logLocked(LocalDiagnosticCategory.REPLAY_RESTARTED, _state.value)
        }
        startSession()
    }

    fun stopSession() {
        synchronized(stateLock) {
            if (closed) return
            transitionToIdleLocked(LocalMapTerminalReason.USER_STOPPED)
        }
    }

    fun onLifecycleBackground() {
        synchronized(stateLock) {
            if (closed) return
            if (streamSource.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                // Offline synthetic replay preserves its frame across lifecycle pauses; pause active emission safely.
                val current = _state.value
                if (current.replayState == SyntheticReplayPlaybackState.PLAYING &&
                    current.connectionState.isActiveSessionState()
                ) {
                    (streamSource as? SyntheticReplayTelemetrySource)?.pause()
                    val paused = current.copy(
                        replayState = SyntheticReplayPlaybackState.PAUSED,
                        mapAgeMs = currentMapAgeMsLocked(current, clock.elapsedMs())
                    )
                    _state.value = paused
                    logLocked(LocalDiagnosticCategory.REPLAY_PAUSED, paused)
                }
                return
            }
            disconnectAndClearSnapshotLocked(LocalMapTerminalReason.BACKGROUNDED)
        }
    }

    fun onConfigurationChanged() {
        synchronized(stateLock) {
            if (closed) return
            if (streamSource.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                // Synthetic replay preserves snapshot and counters across Activity.recreate() (design.md §3.3).
                return
            }
            disconnectAndClearSnapshotLocked(LocalMapTerminalReason.CONFIG_CHANGE_RECONNECT_REQUIRED)
        }
    }

    fun refreshClock() {
        synchronized(stateLock) {
            if (closed) return
            val current = _state.value
            val nowMs = clock.elapsedMs()
            val lastMapMs = current.lastValidMapElapsedMs ?: return
            val ageMs = (nowMs - lastMapMs).coerceAtLeast(0L)
            if (current.connectionState == LocalMapConnectionState.STREAMING && ageMs > STALE_THRESHOLD_MS) {
                enterStaleLocked(current.sessionGeneration, ageMs)
            } else {
                _state.value = current.copy(mapAgeMs = ageMs)
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            transitionToIdleLocked(LocalMapTerminalReason.USER_STOPPED)
            streamSource.close()
        }
        scope.cancel()
    }

    private fun handleStreamEvent(expectedGen: Long, event: LocalTelemetryEvent) {
        synchronized(stateLock) {
            if (closed || expectedGen != activeGenerationId) return
            val nowMs = clock.elapsedMs()
            when (event) {
                is LocalTelemetryEvent.Connected -> {
                    val current = _state.value
                    if (current.connectionState == LocalMapConnectionState.CONNECTING) {
                        val waiting = current.copy(
                            connectionState = LocalMapConnectionState.WAITING_FOR_MAP,
                            terminalReason = LocalMapTerminalReason.NONE
                        )
                        _state.value = waiting
                        logLocked(LocalDiagnosticCategory.SOURCE_CONNECTED, waiting)
                        firstMapJob?.cancel()
                        firstMapJob = scope.launch {
                            waitAndFireFirstMapTimeout(expectedGen, nowMs)
                        }
                    }
                }

                is LocalTelemetryEvent.BytesChunk -> {
                    handleBytesChunkLocked(expectedGen, event.copyBytes(), nowMs)
                }

                LocalTelemetryEvent.EndOfStream -> {
                    handleEndOfStreamLocked(expectedGen, nowMs)
                }

                is LocalTelemetryEvent.StreamFailure -> {
                    handleStreamFailureLocked(event.errorCode, nowMs)
                }
            }
        }
    }

    private fun handleBytesChunkLocked(expectedGen: Long, bytes: ByteArray, nowMs: Long) {
        val currentBefore = _state.value
        if (!currentBefore.connectionState.isActiveSessionState()) return

        when (val feedResult = activeFramer.feed(bytes)) {
            is HoslamFeedResult.Frames -> {
                for (rawFrame in feedResult.frames) {
                    if (!processDecodedFrameLocked(expectedGen, rawFrame, nowMs)) {
                        return
                    }
                }
                evaluatePartialFrameDeadlineLocked(expectedGen, nowMs)
                val updated = _state.value
                updated.lastValidMapElapsedMs?.let { lastMap ->
                    _state.value = updated.copy(mapAgeMs = (nowMs - lastMap).coerceAtLeast(0L))
                }
            }

            is HoslamFeedResult.Failed -> {
                for (rawFrame in feedResult.validFramesBeforeFailure) {
                    if (!processDecodedFrameLocked(expectedGen, rawFrame, nowMs)) {
                        return
                    }
                }
                transitionToCodecErrorLocked(feedResult.error, nowMs)
            }
        }
    }

    private fun processDecodedFrameLocked(
        expectedGen: Long,
        rawFrame: com.weback.robot.domain.map.RawHoslamFrame,
        nowMs: Long
    ): Boolean {
        val current = _state.value
        val candidateGeneration = frameGenerationCounter + 1L
        return when (val decoded = activeDecoder.decode(rawFrame, candidateGeneration)) {
            is HoslamDecodeResult.Failed -> {
                transitionToCodecErrorLocked(decoded.error, nowMs)
                false
            }

            is HoslamDecodeResult.Decoded -> {
                when (val frame = decoded.frame) {
                    is HoslamFrame.Map -> {
                        frameGenerationCounter = candidateGeneration
                        firstMapJob?.cancel()
                        firstMapJob = null
                        partialFrameStartedElapsedMs = null
                        inFrameJob?.cancel()
                        inFrameJob = null

                        val wasStale = current.connectionState == LocalMapConnectionState.STALE
                        val nextState = current.copy(
                            connectionState = LocalMapConnectionState.STREAMING,
                            terminalReason = LocalMapTerminalReason.NONE,
                            codecError = null,
                            codecErrorCode = LocalCodecErrorCode.NONE,
                            currentSnapshot = frame.snapshot,
                            frameGeneration = frame.snapshot.frameGeneration,
                            mapCount = current.mapCount + 1L,
                            lastValidMapElapsedMs = nowMs,
                            mapAgeMs = 0L,
                            mapViewAccepted = true
                        )
                        _state.value = nextState
                        if (wasStale) {
                            logLocked(LocalDiagnosticCategory.STALE_RECOVERED, nextState)
                        }
                        logLocked(
                            category = LocalDiagnosticCategory.MAP_DECODED,
                            snapshot = nextState,
                            kind = HoslamFrameKind.MAP,
                            offset = frame.frameStartOffset,
                            width = frame.snapshot.width,
                            height = frame.snapshot.height
                        )
                        staleJob?.cancel()
                        staleJob = scope.launch {
                            monitorStaleness(expectedGen)
                        }
                    }

                    is HoslamFrame.Aux -> {
                        // Increment auxCount ONLY; MUST NOT update lastValidMapElapsedMs or clear STALE (M11b).
                        val ageMs = currentMapAgeMsLocked(current, nowMs)
                        val nextState = current.copy(
                            auxCount = current.auxCount + 1L,
                            mapAgeMs = ageMs
                        )
                        _state.value = nextState
                        logLocked(
                            category = LocalDiagnosticCategory.AUX_COUNTED,
                            snapshot = nextState,
                            kind = HoslamFrameKind.AUX,
                            offset = frame.frameStartOffset
                        )
                    }

                    is HoslamFrame.Trajectory -> {
                        // Increment trajectoryCount ONLY; MUST NOT update lastValidMapElapsedMs or clear STALE (M11b).
                        val ageMs = currentMapAgeMsLocked(current, nowMs)
                        val nextState = current.copy(
                            trajectoryCount = current.trajectoryCount + 1L,
                            mapAgeMs = ageMs
                        )
                        _state.value = nextState
                        logLocked(
                            category = LocalDiagnosticCategory.TRAJ_COUNTED,
                            snapshot = nextState,
                            kind = HoslamFrameKind.TRAJECTORY,
                            offset = frame.frameStartOffset
                        )
                    }
                }
                true
            }
        }
    }

    private fun evaluatePartialFrameDeadlineLocked(expectedGen: Long, nowMs: Long) {
        val current = _state.value
        if (!current.connectionState.isActiveSessionState()) return

        val hasPartialBytes = activeFramer.bufferedHeaderByteCount > 0 ||
            activeFramer.activeFrameBufferCapacity > 0
        if (!hasPartialBytes) {
            partialFrameStartedElapsedMs = null
            inFrameJob?.cancel()
            inFrameJob = null
        } else if (partialFrameStartedElapsedMs == null) {
            val startedAt = nowMs
            partialFrameStartedElapsedMs = startedAt
            inFrameJob?.cancel()
            inFrameJob = scope.launch {
                waitAndFireInFrameTimeout(expectedGen, startedAt)
            }
        }
        // Intentionally NEVER resets partialFrameStartedElapsedMs on additional trickle chunks.
    }

    private fun handleEndOfStreamLocked(expectedGen: Long, nowMs: Long) {
        if (expectedGen != activeGenerationId) return
        val current = _state.value
        if (!current.connectionState.isActiveSessionState()) return

        cancelWatchdogJobsLocked()
        when (val finishResult = activeFramer.finish()) {
            is HoslamFinishResult.Failed -> {
                transitionToCodecErrorLocked(finishResult.error, nowMs)
            }

            is HoslamFinishResult.EndOfStream -> {
                val hasAcceptedMap = current.mapCount > 0L && current.currentSnapshot != null
                val reason = if (hasAcceptedMap) {
                    LocalMapTerminalReason.REMOTE_EOF
                } else {
                    LocalMapTerminalReason.EOF_BEFORE_FIRST_MAP
                }
                val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                    if (hasAcceptedMap) {
                        SyntheticReplayPlaybackState.COMPLETED
                    } else {
                        SyntheticReplayPlaybackState.FAILED
                    }
                } else {
                    SyntheticReplayPlaybackState.IDLE
                }
                val next = current.copy(
                    connectionState = LocalMapConnectionState.DISCONNECTED,
                    terminalReason = reason,
                    replayState = replayState,
                    currentSnapshot = if (hasAcceptedMap) current.currentSnapshot else null,
                    mapAgeMs = currentMapAgeMsLocked(current, nowMs),
                    mapViewAccepted = hasAcceptedMap
                )
                _state.value = next
                logLocked(LocalDiagnosticCategory.SESSION_DISCONNECTED, next)
            }
        }
    }

    private fun handleStreamFailureLocked(errorCode: LocalStreamFailureCode, nowMs: Long) {
        val current = _state.value
        if (!current.connectionState.isActiveSessionState() &&
            current.connectionState != LocalMapConnectionState.CONNECTING
        ) {
            return
        }
        cancelAllJobsLocked()
        val reason = when (errorCode) {
            LocalStreamFailureCode.ASSET_LOAD_FAILED -> LocalMapTerminalReason.ASSET_LOAD_FAILED
            LocalStreamFailureCode.CONNECT_TIMEOUT -> LocalMapTerminalReason.FIRST_MAP_TIMEOUT
            LocalStreamFailureCode.TRUST_REJECTED,
            LocalStreamFailureCode.IO_FAILURE -> LocalMapTerminalReason.STREAM_IO_ERROR
        }
        val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
            SyntheticReplayPlaybackState.FAILED
        } else {
            SyntheticReplayPlaybackState.IDLE
        }
        val next = current.copy(
            connectionState = LocalMapConnectionState.ERROR,
            terminalReason = reason,
            replayState = replayState,
            mapAgeMs = currentMapAgeMsLocked(current, nowMs)
        )
        _state.value = next
        logLocked(LocalDiagnosticCategory.STREAM_ERROR, next, streamErrorCode = errorCode)
    }

    private fun transitionToCodecErrorLocked(error: LocalCodecError, nowMs: Long) {
        cancelAllJobsLocked()
        val current = _state.value
        val hasPriorMap = current.mapCount > 0L && current.currentSnapshot != null
        val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
            SyntheticReplayPlaybackState.FAILED
        } else {
            SyntheticReplayPlaybackState.IDLE
        }
        val next = current.copy(
            connectionState = LocalMapConnectionState.ERROR,
            terminalReason = LocalMapTerminalReason.CODEC_ERROR,
            codecError = error,
            codecErrorCode = error.toCode(),
            replayState = replayState,
            currentSnapshot = if (hasPriorMap) current.currentSnapshot else null,
            mapAgeMs = currentMapAgeMsLocked(current, nowMs),
            mapViewAccepted = hasPriorMap
        )
        _state.value = next
        logLocked(
            category = LocalDiagnosticCategory.CODEC_ERROR,
            snapshot = next,
            offset = error.frameStartOffset
        )
    }

    private fun transitionToIdleLocked(reason: LocalMapTerminalReason) {
        activeGenerationId++
        cancelAllJobsLocked()
        if (streamSource.sourceKind != LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
            streamSource.close()
        } else {
            (streamSource as? SyntheticReplayTelemetrySource)?.resetPauseForReplay()
        }
        partialFrameStartedElapsedMs = null
        activeFramer = framerFactory()
        val next = LocalMapRepositoryState(
            sourceKind = streamSource.sourceKind,
            connectionState = LocalMapConnectionState.IDLE,
            terminalReason = reason,
            replayState = SyntheticReplayPlaybackState.IDLE,
            sessionGeneration = activeGenerationId
        )
        _state.value = next
        logLocked(LocalDiagnosticCategory.SESSION_STOPPED, next)
    }

    private fun disconnectAndClearSnapshotLocked(reason: LocalMapTerminalReason) {
        activeGenerationId++
        cancelAllJobsLocked()
        if (streamSource.sourceKind != LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
            streamSource.close()
        }
        partialFrameStartedElapsedMs = null
        val current = _state.value
        val next = current.copy(
            connectionState = LocalMapConnectionState.DISCONNECTED,
            terminalReason = reason,
            replayState = SyntheticReplayPlaybackState.IDLE,
            currentSnapshot = null,
            lastValidMapElapsedMs = null,
            mapAgeMs = 0L,
            mapViewAccepted = false,
            sessionGeneration = activeGenerationId
        )
        _state.value = next
        logLocked(LocalDiagnosticCategory.SESSION_DISCONNECTED, next)
    }

    private fun enterStaleLocked(expectedGen: Long, ageMs: Long) {
        if (closed || expectedGen != activeGenerationId) return
        val current = _state.value
        if (current.connectionState != LocalMapConnectionState.STREAMING) return
        val next = current.copy(
            connectionState = LocalMapConnectionState.STALE,
            mapAgeMs = ageMs
        )
        _state.value = next
        logLocked(LocalDiagnosticCategory.STALE_ENTERED, next)
    }

    private suspend fun monitorStaleness(expectedGen: Long) {
        while (scope.isActive) {
            val waitMs = synchronized(stateLock) {
                if (closed || expectedGen != activeGenerationId) return
                val current = _state.value
                if (current.connectionState != LocalMapConnectionState.STREAMING) return
                val lastMapMs = current.lastValidMapElapsedMs ?: return
                val ageMs = (clock.elapsedMs() - lastMapMs).coerceAtLeast(0L)
                if (ageMs > STALE_THRESHOLD_MS) {
                    enterStaleLocked(expectedGen, ageMs)
                    return
                }
                (STALE_THRESHOLD_MS + 1L - ageMs).coerceAtLeast(1L)
            }
            delay(waitMs)
        }
    }

    private suspend fun waitAndFireFirstMapTimeout(expectedGen: Long, startedAtMs: Long) {
        while (scope.isActive) {
            val waitMs = synchronized(stateLock) {
                if (closed || expectedGen != activeGenerationId) return
                val current = _state.value
                if (current.connectionState != LocalMapConnectionState.WAITING_FOR_MAP) return
                val elapsed = (clock.elapsedMs() - startedAtMs).coerceAtLeast(0L)
                if (elapsed >= FIRST_MAP_TIMEOUT_MS) {
                    cancelAllJobsLocked()
                    val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                        SyntheticReplayPlaybackState.FAILED
                    } else {
                        SyntheticReplayPlaybackState.IDLE
                    }
                    val next = current.copy(
                        connectionState = LocalMapConnectionState.ERROR,
                        terminalReason = LocalMapTerminalReason.FIRST_MAP_TIMEOUT,
                        replayState = replayState,
                        currentSnapshot = null,
                        mapViewAccepted = false
                    )
                    _state.value = next
                    logLocked(LocalDiagnosticCategory.STREAM_ERROR, next)
                    return
                }
                (FIRST_MAP_TIMEOUT_MS - elapsed).coerceAtLeast(1L)
            }
            delay(waitMs)
        }
    }

    private suspend fun waitAndFireInFrameTimeout(expectedGen: Long, firstByteAtMs: Long) {
        while (scope.isActive) {
            val waitMs = synchronized(stateLock) {
                if (closed || expectedGen != activeGenerationId) return
                val current = _state.value
                if (!current.connectionState.isActiveSessionState()) return
                if (partialFrameStartedElapsedMs != firstByteAtMs) return
                val elapsed = (clock.elapsedMs() - firstByteAtMs).coerceAtLeast(0L)
                if (elapsed >= IN_FRAME_TIMEOUT_MS) {
                    cancelAllJobsLocked()
                    val hasPriorMap = current.mapCount > 0L && current.currentSnapshot != null
                    val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                        SyntheticReplayPlaybackState.FAILED
                    } else {
                        SyntheticReplayPlaybackState.IDLE
                    }
                    val next = current.copy(
                        connectionState = LocalMapConnectionState.ERROR,
                        terminalReason = LocalMapTerminalReason.IN_FRAME_TIMEOUT,
                        replayState = replayState,
                        currentSnapshot = if (hasPriorMap) current.currentSnapshot else null,
                        mapAgeMs = currentMapAgeMsLocked(current, clock.elapsedMs()),
                        mapViewAccepted = hasPriorMap
                    )
                    _state.value = next
                    logLocked(LocalDiagnosticCategory.STREAM_ERROR, next)
                    return
                }
                (IN_FRAME_TIMEOUT_MS - elapsed).coerceAtLeast(1L)
            }
            delay(waitMs)
        }
    }

    private suspend fun waitAndFireSessionCap(expectedGen: Long, startedAtMs: Long) {
        while (scope.isActive) {
            val waitMs = synchronized(stateLock) {
                if (closed || expectedGen != activeGenerationId) return
                val current = _state.value
                if (!current.connectionState.isActiveSessionState() &&
                    current.connectionState != LocalMapConnectionState.CONNECTING
                ) {
                    return
                }
                val elapsed = (clock.elapsedMs() - startedAtMs).coerceAtLeast(0L)
                if (elapsed >= SESSION_CAP_MS) {
                    // If an Error already committed, Error wins (design.md §3.3).
                    if (current.connectionState == LocalMapConnectionState.ERROR) return
                    cancelAllJobsLocked()
                    val hasPriorMap = current.mapCount > 0L && current.currentSnapshot != null
                    val replayState = if (current.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY) {
                        if (hasPriorMap) SyntheticReplayPlaybackState.COMPLETED else SyntheticReplayPlaybackState.FAILED
                    } else {
                        SyntheticReplayPlaybackState.IDLE
                    }
                    val next = current.copy(
                        connectionState = LocalMapConnectionState.DISCONNECTED,
                        terminalReason = LocalMapTerminalReason.SESSION_LIMIT_REACHED,
                        replayState = replayState,
                        currentSnapshot = if (hasPriorMap) current.currentSnapshot else null,
                        mapAgeMs = currentMapAgeMsLocked(current, clock.elapsedMs()),
                        mapViewAccepted = hasPriorMap
                    )
                    _state.value = next
                    logLocked(LocalDiagnosticCategory.SESSION_DISCONNECTED, next)
                    return
                }
                (SESSION_CAP_MS - elapsed).coerceAtLeast(1L)
            }
            delay(waitMs)
        }
    }

    private fun currentMapAgeMsLocked(current: LocalMapRepositoryState, nowMs: Long): Long {
        val lastMapMs = current.lastValidMapElapsedMs ?: return 0L
        return (nowMs - lastMapMs).coerceAtLeast(0L)
    }

    private fun cancelWatchdogJobsLocked() {
        firstMapJob?.cancel()
        firstMapJob = null
        staleJob?.cancel()
        staleJob = null
        inFrameJob?.cancel()
        inFrameJob = null
        sessionCapJob?.cancel()
        sessionCapJob = null
    }

    private fun cancelAllJobsLocked() {
        collectionJob?.cancel()
        collectionJob = null
        cancelWatchdogJobsLocked()
    }

    private fun logLocked(
        category: LocalDiagnosticCategory,
        snapshot: LocalMapRepositoryState,
        streamErrorCode: LocalStreamFailureCode? = null,
        kind: HoslamFrameKind? = null,
        offset: Long? = null,
        width: Int? = null,
        height: Int? = null
    ) {
        val elapsedSession = (clock.elapsedMs() - sessionStartedElapsedMs).coerceAtLeast(0L)
        diagnosticLogger.log(
            LocalDiagnosticEvent(
                category = category,
                connectionState = snapshot.connectionState,
                codecErrorCode = snapshot.codecErrorCode,
                streamErrorCode = streamErrorCode,
                terminalReason = snapshot.terminalReason,
                frameKind = kind,
                frameStartOffset = offset,
                width = width,
                height = height,
                mapCount = snapshot.mapCount,
                auxCount = snapshot.auxCount,
                trajCount = snapshot.trajectoryCount,
                generationId = snapshot.sessionGeneration,
                elapsedSessionMs = elapsedSession
            )
        )
    }

    private fun LocalMapConnectionState.isActiveSessionState(): Boolean =
        this == LocalMapConnectionState.CONNECTING ||
            this == LocalMapConnectionState.WAITING_FOR_MAP ||
            this == LocalMapConnectionState.STREAMING ||
            this == LocalMapConnectionState.STALE

    companion object {
        const val FIRST_MAP_TIMEOUT_MS: Long = 30_000L
        const val STALE_THRESHOLD_MS: Long = 10_000L
        const val IN_FRAME_TIMEOUT_MS: Long = 15_000L
        const val SESSION_CAP_MS: Long = 180_000L
    }
}
