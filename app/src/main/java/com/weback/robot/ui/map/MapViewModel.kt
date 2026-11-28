package com.weback.robot.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weback.robot.app.local.LocalCodecErrorCode
import com.weback.robot.app.local.LocalMapConnectionState
import com.weback.robot.app.local.LocalMapRepository
import com.weback.robot.app.local.LocalMapRepositoryState
import com.weback.robot.app.local.LocalMapTerminalReason
import com.weback.robot.app.local.SyntheticReplayPlaybackState
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.map.RasterSnapshot
import com.weback.robot.protocol.local.LocalTelemetrySourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Immutable truthful UI model derived strictly from [LocalMapRepositoryState].
 */
data class LocalMapUiState(
    val sourceKind: LocalTelemetrySourceKind,
    val sourceBadgeLabel: String,
    val connectionState: LocalMapConnectionState,
    val connectionStateLabel: String,
    val replayState: SyntheticReplayPlaybackState,
    val acceptanceStatusLabel: String,
    val countersLabel: String,
    val ageLabel: String,
    val errorDetailCode: String,
    val accessibilitySummary: String,
    val currentSnapshot: RasterSnapshot?,
    val mapCount: Long,
    val auxCount: Long,
    val trajectoryCount: Long,
    val mapAgeMs: Long,
    val canPlayOrResume: Boolean,
    val canPauseReplay: Boolean,
    val canRestartReplay: Boolean
)

/**
 * Presentation bridge for offline synthetic HOSLAM replay and local read-only map observation.
 * Performs zero automatic fallback to simulator or cloud pilot controllers (`AC-501.3`).
 */
class MapViewModel(
    private val repository: LocalMapRepository,
    private val dispatchers: DispatcherProvider? = null,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(mapToUiState(repository.state.value))
    val uiState: StateFlow<LocalMapUiState> = _uiState.asStateFlow()

    init {
        val collectorContext = dispatchers?.main ?: kotlin.coroutines.EmptyCoroutineContext
        scope.launch(collectorContext) {
            repository.state.collect { repoState ->
                _uiState.value = mapToUiState(repoState)
            }
        }
    }

    fun onPlayOrResume() {
        repository.playOrResumeReplay()
    }

    fun onPauseReplay() {
        repository.pauseReplay()
    }

    fun onRestartReplay() {
        repository.restartReplay()
    }

    fun onStopSession() {
        repository.stopSession()
    }

    fun onRefreshClock() {
        repository.refreshClock()
    }

    companion object {
        fun mapToUiState(repoState: LocalMapRepositoryState): LocalMapUiState {
            val errorDetailCode = when {
                repoState.codecErrorCode != LocalCodecErrorCode.NONE -> repoState.codecErrorCode.name
                repoState.terminalReason != LocalMapTerminalReason.NONE -> repoState.terminalReason.name
                else -> "UNKNOWN_ERROR"
            }

            val connectionStateLabel = when (repoState.connectionState) {
                LocalMapConnectionState.IDLE -> "IDLE"
                LocalMapConnectionState.CONNECTING -> "CONNECTING"
                LocalMapConnectionState.WAITING_FOR_MAP -> "WAITING FOR FIRST MAP"
                LocalMapConnectionState.STREAMING -> {
                    if (repoState.replayState == SyntheticReplayPlaybackState.PAUSED) {
                        "PAUSED (SYNTHETIC REPLAY)"
                    } else {
                        "STREAMING (LIVE)"
                    }
                }
                LocalMapConnectionState.STALE -> "STALE"
                LocalMapConnectionState.DISCONNECTED -> when {
                    repoState.terminalReason == LocalMapTerminalReason.SESSION_LIMIT_REACHED &&
                        repoState.currentSnapshot != null -> "DISCONNECTED / STALE (180s session limit)"
                    repoState.currentSnapshot != null -> "DISCONNECTED / STALE"
                    repoState.terminalReason == LocalMapTerminalReason.EOF_BEFORE_FIRST_MAP ->
                        "DISCONNECTED — No valid map received"
                    repoState.terminalReason == LocalMapTerminalReason.BACKGROUNDED ->
                        "DISCONNECTED (Backgrounded — Manual Reconnect Required)"
                    repoState.terminalReason == LocalMapTerminalReason.CONFIG_CHANGE_RECONNECT_REQUIRED ->
                        "DISCONNECTED (Configuration Changed — Manual Reconnect Required)"
                    repoState.terminalReason == LocalMapTerminalReason.SESSION_LIMIT_REACHED ->
                        "DISCONNECTED (180s session limit)"
                    else -> "DISCONNECTED"
                }
                LocalMapConnectionState.ERROR -> {
                    if (repoState.currentSnapshot != null) {
                        "ERROR / STALE ($errorDetailCode)"
                    } else {
                        "ERROR ($errorDetailCode)"
                    }
                }
            }

            val acceptanceStatusLabel = when {
                repoState.mapViewAccepted -> "Accepted (${repoState.mapCount} map frames)"
                repoState.connectionState == LocalMapConnectionState.ERROR ->
                    "Map View Failed ($errorDetailCode)"
                repoState.connectionState == LocalMapConnectionState.DISCONNECTED &&
                    repoState.terminalReason == LocalMapTerminalReason.EOF_BEFORE_FIRST_MAP ->
                    "Map View Not Accepted (0 valid maps received)"
                else -> "Awaiting first valid map"
            }

            val countersLabel =
                "Maps: ${repoState.mapCount} | Aux: ${repoState.auxCount} | Trajectory (opaque counts only): ${repoState.trajectoryCount}"
            val ageSeconds = repoState.mapAgeMs / 1_000L
            val ageLabel = "Map Age: ${repoState.mapAgeMs} ms (${ageSeconds}s)"

            val snap = repoState.currentSnapshot
            val rasterSummary = if (snap != null) {
                "Map ${snap.width}x${snap.height}, generation ${snap.frameGeneration}"
            } else {
                "No map frame"
            }
            val accessibilitySummary =
                "${repoState.sourceKind.displayLabel}, $connectionStateLabel, $acceptanceStatusLabel, $rasterSummary, $ageLabel, $countersLabel"

            val isReplaySource = repoState.sourceKind == LocalTelemetrySourceKind.SYNTHETIC_REPLAY
            val isPaused = repoState.replayState == SyntheticReplayPlaybackState.PAUSED
            val isPlaying = repoState.replayState == SyntheticReplayPlaybackState.PLAYING &&
                (repoState.connectionState == LocalMapConnectionState.CONNECTING ||
                    repoState.connectionState == LocalMapConnectionState.WAITING_FOR_MAP ||
                    repoState.connectionState == LocalMapConnectionState.STREAMING ||
                    repoState.connectionState == LocalMapConnectionState.STALE)

            return LocalMapUiState(
                sourceKind = repoState.sourceKind,
                sourceBadgeLabel = repoState.sourceKind.displayLabel,
                connectionState = repoState.connectionState,
                connectionStateLabel = connectionStateLabel,
                replayState = repoState.replayState,
                acceptanceStatusLabel = acceptanceStatusLabel,
                countersLabel = countersLabel,
                ageLabel = ageLabel,
                errorDetailCode = errorDetailCode,
                accessibilitySummary = accessibilitySummary,
                currentSnapshot = snap,
                mapCount = repoState.mapCount,
                auxCount = repoState.auxCount,
                trajectoryCount = repoState.trajectoryCount,
                mapAgeMs = repoState.mapAgeMs,
                canPlayOrResume = isReplaySource && (!isPlaying || isPaused),
                canPauseReplay = isReplaySource && isPlaying && !isPaused,
                canRestartReplay = isReplaySource && repoState.connectionState != LocalMapConnectionState.IDLE
            )
        }
    }

    class Factory(
        private val repository: LocalMapRepository,
        private val dispatchers: DispatcherProvider
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
                return MapViewModel(repository = repository, dispatchers = dispatchers) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
