package com.weback.robot.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weback.robot.R
import com.weback.robot.app.local.LocalMapConnectionState
import com.weback.robot.app.local.SyntheticReplayPlaybackState

@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    MapScreen(
        uiState = uiState,
        onPlayOrResume = viewModel::onPlayOrResume,
        onPauseReplay = viewModel::onPauseReplay,
        onRestartReplay = viewModel::onRestartReplay,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    uiState: LocalMapUiState,
    onPlayOrResume: () -> Unit,
    onPauseReplay: () -> Unit,
    onRestartReplay: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.local_map_viewer_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    OutlinedButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("btn_back_to_simulator")
                    ) {
                        Text(text = stringResource(id = R.string.action_back_to_simulator))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusTruthfulnessCard(uiState = uiState)

            SyntheticReplayControlsCard(
                uiState = uiState,
                onPlayOrResume = onPlayOrResume,
                onPauseReplay = onPauseReplay,
                onRestartReplay = onRestartReplay
            )

            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.local_map_canvas_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    MapCanvas(snapshot = uiState.currentSnapshot)
                }
            }

            UnsupportedCapabilitiesCard()

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusTruthfulnessCard(uiState: LocalMapUiState) {
    val containerColor = when (uiState.connectionState) {
        LocalMapConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
        LocalMapConnectionState.STALE,
        LocalMapConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_map_status_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = uiState.sourceBadgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("local_map_source_badge")
                    )
                }
                Text(
                    text = uiState.connectionStateLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("local_map_state_label")
                )
            }

            Text(
                text = uiState.acceptanceStatusLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("local_map_acceptance_status")
            )
            Text(
                text = uiState.ageLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("local_map_age_label")
            )
            Text(
                text = uiState.countersLabel,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("local_map_counters_label")
            )
            Text(
                text = uiState.accessibilitySummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag("local_map_a11y_summary")
                    .semantics { contentDescription = uiState.accessibilitySummary }
            )
        }
    }
}

@Composable
private fun SyntheticReplayControlsCard(
    uiState: LocalMapUiState,
    onPlayOrResume: () -> Unit,
    onPauseReplay: () -> Unit,
    onRestartReplay: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_replay_controls")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(id = R.string.local_replay_controls_title),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayOrResume,
                    enabled = uiState.canPlayOrResume,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_replay_play")
                ) {
                    val labelId = if (uiState.replayState == SyntheticReplayPlaybackState.PAUSED) {
                        R.string.action_replay_resume
                    } else {
                        R.string.action_replay_play
                    }
                    Text(text = stringResource(id = labelId))
                }
                OutlinedButton(
                    onClick = onPauseReplay,
                    enabled = uiState.canPauseReplay,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_replay_pause")
                ) {
                    Text(text = stringResource(id = R.string.action_replay_pause))
                }
                OutlinedButton(
                    onClick = onRestartReplay,
                    enabled = uiState.canRestartReplay,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_replay_restart")
                ) {
                    Text(text = stringResource(id = R.string.action_replay_restart))
                }
            }
        }
    }
}

@Composable
private fun UnsupportedCapabilitiesCard() {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_unsupported_capabilities_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(id = R.string.local_unsupported_section_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(id = R.string.local_unsupported_telemetry_row),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unsupported_telemetry_row")
            )
            Text(
                text = stringResource(id = R.string.local_unsupported_overlay_row),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unsupported_overlay_row")
            )
            Text(
                text = stringResource(id = R.string.local_unsupported_rooms_row),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unsupported_rooms_row")
            )
            Text(
                text = stringResource(id = R.string.local_unsupported_firmware_row),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("unsupported_firmware_row")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_local_unsupported_clean")
                ) {
                    Text(text = stringResource(id = R.string.local_unsupported_btn_clean))
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_local_unsupported_pause")
                ) {
                    Text(text = stringResource(id = R.string.local_unsupported_btn_pause))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_local_unsupported_resume")
                ) {
                    Text(text = stringResource(id = R.string.local_unsupported_btn_resume))
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_local_unsupported_dock")
                ) {
                    Text(text = stringResource(id = R.string.local_unsupported_btn_dock))
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_local_unsupported_locate")
                ) {
                    Text(text = stringResource(id = R.string.local_unsupported_btn_locate))
                }
            }
        }
    }
}
