package com.weback.robot.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.weback.robot.R
import com.weback.robot.domain.model.CommandExecutionState
import com.weback.robot.ui.DashboardIntent
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.RobotUiState

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
    onOpenPilotSheet: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    DashboardScreen(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        modifier = modifier,
        onOpenPilotSheet = onOpenPilotSheet
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: RobotUiState,
    onIntent: (DashboardIntent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPilotSheet: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.titleLarge
                    )
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
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SimulatorBadgeSection()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HeaderSection(uiState = uiState)
                TelemetryCard(uiState = uiState)
                ControlsCard(uiState = uiState, onIntent = onIntent)
                CommandFeedbackCard(uiState = uiState, onIntent = onIntent)
                if (onOpenPilotSheet != null) {
                    OutlinedButton(
                        onClick = onOpenPilotSheet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_open_pilot_readiness")
                    ) {
                        Text(text = "Pilot Readiness (Offline / Mock)")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun HeaderSection(uiState: RobotUiState) {
    Column {
        Text(
            text = uiState.robotName,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiState.appVersion,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("app_version_text")
        )
    }
}

@Composable
fun SimulatorBadgeSection() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("simulator_badge")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.simulator_badge_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                }
                Text(
                    text = stringResource(id = R.string.simulator_badge),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = stringResource(id = R.string.simulator_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun TelemetryCard(uiState: RobotUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.telemetry_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TelemetryRow(
                label = stringResource(id = R.string.telemetry_battery),
                value = "${uiState.batteryPercentage}%",
                valueTag = "robot_battery_text"
            )

            TelemetryRow(
                label = stringResource(id = R.string.telemetry_status),
                value = if (uiState.isDocked) {
                    stringResource(id = R.string.status_docked)
                } else {
                    stringResource(id = R.string.status_undocked)
                },
                valueTag = "robot_dock_status_text"
            )

            TelemetryRow(
                label = stringResource(id = R.string.telemetry_dustbin),
                value = if (uiState.isDustbinClean) {
                    stringResource(id = R.string.status_clean)
                } else {
                    stringResource(id = R.string.status_full)
                }
            )

            TelemetryRow(
                label = stringResource(id = R.string.telemetry_mode),
                value = uiState.mode.name,
                valueTag = "robot_mode_text"
            )

            if (uiState.isFault || uiState.errorCode != 0) {
                TelemetryRow(
                    label = stringResource(id = R.string.telemetry_error),
                    value = stringResource(id = R.string.error_code_format, uiState.errorCode),
                    valueTag = "robot_error_text"
                )
            }
        }
    }
}

@Composable
fun TelemetryRow(
    label: String,
    value: String,
    valueTag: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier
        )
    }
}

@Composable
fun ControlsCard(
    uiState: RobotUiState,
    onIntent: (DashboardIntent) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.controls_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onIntent(DashboardIntent.Clean) },
                    enabled = uiState.controls.canClean,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_clean")
                ) {
                    Text(text = stringResource(id = R.string.action_clean))
                }

                FilledTonalButton(
                    onClick = { onIntent(DashboardIntent.Pause) },
                    enabled = uiState.controls.canPause,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_pause")
                ) {
                    Text(text = stringResource(id = R.string.action_pause))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { onIntent(DashboardIntent.Resume) },
                    enabled = uiState.controls.canResume,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_resume")
                ) {
                    Text(text = stringResource(id = R.string.action_resume))
                }

                OutlinedButton(
                    onClick = { onIntent(DashboardIntent.Dock) },
                    enabled = uiState.controls.canDock,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_dock")
                ) {
                    Text(text = stringResource(id = R.string.action_dock))
                }
            }
        }
    }
}

@Composable
fun CommandFeedbackCard(
    uiState: RobotUiState,
    onIntent: (DashboardIntent) -> Unit
) {
    val cmdState = uiState.commandState
    val isError = cmdState is CommandExecutionState.Rejected
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        cmdState is CommandExecutionState.Acknowledged -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        cmdState is CommandExecutionState.Acknowledged -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when (cmdState) {
        CommandExecutionState.Idle -> stringResource(id = R.string.command_status_idle)
        is CommandExecutionState.Pending -> stringResource(
            id = R.string.command_status_pending,
            cmdState.command.name,
            cmdState.commandId
        )
        is CommandExecutionState.Acknowledged -> stringResource(
            id = R.string.command_status_acknowledged,
            cmdState.command.name,
            cmdState.commandId
        )
        is CommandExecutionState.Rejected -> stringResource(
            id = R.string.command_status_rejected,
            cmdState.reason.name,
            cmdState.message
        )
    }

    val canDismiss = cmdState is CommandExecutionState.Acknowledged ||
        cmdState is CommandExecutionState.Rejected ||
        uiState.busyConflictNotice != null

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_status_banner")
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (cmdState is CommandExecutionState.Pending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = stringResource(id = R.string.command_status_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor
                    )
                }

                if (canDismiss) {
                    TextButton(
                        onClick = { onIntent(DashboardIntent.DismissCommandFeedback) },
                        modifier = Modifier.testTag("btn_dismiss_feedback")
                    ) {
                        Text(
                            text = stringResource(id = R.string.action_dismiss),
                            color = contentColor
                        )
                    }
                }
            }

            uiState.busyConflictNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("busy_conflict_banner")
                ) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}
