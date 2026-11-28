package com.weback.robot.ui.pilot

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.InventoryState
import com.weback.robot.domain.pilot.ObservedOriginSummary
import com.weback.robot.domain.pilot.PilotAbortReason
import com.weback.robot.domain.pilot.PilotEmergencyDisconnectResult
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.TargetInventorySummary
import com.weback.robot.security.ProvisionedAuthorizationLoadResult
import java.util.Arrays

/**
 * Mutable character buffer for ephemeral synthetic credential entry (`SEC-406`).
 * Note: Compose [OutlinedTextField] emits JVM immutable [String] values from platform IME input
 * that cannot be deterministically zeroized in user space; this class bounds the mutable [CharArray]
 * lifetime and wipes it immediately on submit, cancel, error, and screen exit.
 */
class EphemeralPasswordBuffer(capacity: Int = 128) {
    val chars: CharArray = CharArray(capacity)
    var length: Int = 0
        private set

    fun updateFromInput(input: String) {
        val copyCount = minOf(input.length, chars.size)
        for (i in 0 until copyCount) {
            chars[i] = input[i]
        }
        if (copyCount < chars.size) {
            Arrays.fill(chars, copyCount, chars.size, '\u0000')
        }
        length = copyCount
    }

    fun asDisplayString(): String = if (length == 0) "" else String(chars, 0, length)

    fun buildAndWipeCredentials(regionCallingCode: String, account: String): AuthCredentials {
        return try {
            AuthCredentials(
                regionCallingCode = regionCallingCode,
                account = account,
                secret = chars.copyOfRange(0, length)
            )
        } finally {
            wipe()
        }
    }

    fun wipe() {
        Arrays.fill(chars, '\u0000')
        length = 0
    }
}

data class PilotSheetUiState(
    val modeState: PilotModeState,
    val authorizationId: String,
    val modeLabel: String,
    val approvedAuthOrigin: String,
    val pinnedApiOrigin: String?,
    val pinnedWssOrigin: String?,
    val quarantinedSummary: ObservedOriginSummary? = null,
    val remainingObservationMs: Long,
    val connectionState: ConnectionState,
    val inventoryState: InventoryState,
    val selectedDevice: DeviceInventoryItem?,
    val lastAbortReason: PilotAbortReason?,
    val lastDisconnectResult: PilotEmergencyDisconnectResult?,
    val isLivePilotSupported: Boolean = false,
    val isLiveWorkflowActive: Boolean = false,
    val showLiveAuthReview: Boolean = false,
    val provisionedAuthLoadResult: ProvisionedAuthorizationLoadResult? = null,
    val liveAuthValidationError: String? = null,
    val isLiveOperationInFlight: Boolean = false,
    val hasUnresolvedLiveSession: Boolean = false,
    val liveLifecycleNotice: String? = null,
    val targetInventorySummary: TargetInventorySummary? = null
)

@Composable
fun PilotReadinessSheet(
    state: PilotSheetUiState,
    passwordBuffer: EphemeralPasswordBuffer = remember { EphemeralPasswordBuffer() },
    onSubmitCredentials: (AuthCredentials) -> Unit,
    onCancelCredentialEntry: () -> Unit,
    onRefreshInventory: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onEmergencyDisconnect: () -> Unit,
    onDismissSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onStartMockPilot: (() -> Unit)? = null,
    onStartShortMockPilot: (() -> Unit)? = null,
    onOpenLiveAuthReview: (() -> Unit)? = null,
    onCancelLiveAuthReview: (() -> Unit)? = null,
    onRefreshProvisionedAuth: (() -> Unit)? = null,
    onActivateLivePilot: ((String) -> Unit)? = null,
    onFetchTargetInventory: (() -> Unit)? = null
) {
    var regionInput by remember { mutableStateOf("") }
    var accountInput by remember { mutableStateOf("") }
    var bufferVersion by remember { mutableIntStateOf(0) }

    var expectedRecordSha256Input by remember { mutableStateOf("") }

    fun clearLiveAuthInputs() {
        expectedRecordSha256Input = ""
    }

    val isCredentialEntryVisible = state.modeState is PilotModeState.AuthorizedReady
    val shouldProtectWindow = isCredentialEntryVisible ||
        (state.isLivePilotSupported && state.showLiveAuthReview) ||
        (state.isLiveWorkflowActive && (
            state.modeState is PilotModeState.AuthenticatedInventoryReady ||
                state.modeState is PilotModeState.InventoryVerified ||
                state.isLiveOperationInFlight
            ))
    val view = LocalView.current

    // Wipe mutable credential buffer and volatile inputs automatically on error/abort/reset (`SEC-406`, `SEC-466`)
    LaunchedEffect(state.modeState) {
        if (state.modeState is PilotModeState.Aborted || state.modeState is PilotModeState.OfflineDefault) {
            passwordBuffer.wipe()
            regionInput = ""
            accountInput = ""
            bufferVersion++
        }
        if (state.modeState is PilotModeState.AuthorizedReady) {
            clearLiveAuthInputs()
        }
    }

    // Apply FLAG_SECURE screenshot prevention during sensitive authorization review and credential entry
    DisposableEffect(view, shouldProtectWindow) {
        val dialogWindow = view.findDialogWindow()
        val activityWindow = view.context.findActivity()?.window
        val targetWindows = listOfNotNull(dialogWindow, activityWindow).distinct()
        val hadSecureFlags = targetWindows.associateWith { w ->
            (w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        }
        if (shouldProtectWindow) {
            targetWindows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        onDispose {
            passwordBuffer.wipe()
            regionInput = ""
            accountInput = ""
            clearLiveAuthInputs()
            bufferVersion++
            targetWindows.forEach { w ->
                if (hadSecureFlags[w] == false) {
                    w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Read-Only Pilot Readiness",
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(
                onClick = {
                    passwordBuffer.wipe()
                    bufferVersion++
                    onDismissSheet()
                },
                modifier = Modifier.testTag("btn_pilot_close_sheet")
            ) {
                Text("Close")
            }
        }

        // Clear Offline / Mock Labeling Banner
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_mode_badge")
                .semantics(mergeDescendants = true) {}
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = state.modeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Offline simulator preserved; physical robot commands blocked in pilot mode.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (onStartMockPilot != null &&
            !state.showLiveAuthReview &&
            !state.hasUnresolvedLiveSession &&
            (state.modeState is PilotModeState.OfflineDefault || state.modeState is PilotModeState.Aborted)
        ) {
            OutlinedButton(
                onClick = onStartMockPilot,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_start_mock_pilot")
            ) {
                Text("Start Offline Mock Pilot (Synthetic Fixture)")
            }
            if (onStartShortMockPilot != null) {
                OutlinedButton(
                    onClick = onStartShortMockPilot,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_start_short_mock_pilot")
                ) {
                    Text("Start 1-Min Offline Mock Expiry Test (No Network)")
                }
            }
        }

        if (state.hasUnresolvedLiveSession &&
            (state.modeState is PilotModeState.OfflineDefault || state.modeState is PilotModeState.Aborted)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pilot_unresolved_live_notice")
            ) {
                Text(
                    text = "Live session cleanup is in progress or incomplete. Mock switching and new activation are blocked until resolved.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        state.liveLifecycleNotice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pilot_lifecycle_notice")
            ) {
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        if (state.isLivePilotSupported &&
            onOpenLiveAuthReview != null &&
            !state.showLiveAuthReview &&
            !state.hasUnresolvedLiveSession &&
            (state.modeState is PilotModeState.OfflineDefault || state.modeState is PilotModeState.Aborted)
        ) {
            Button(
                onClick = onOpenLiveAuthReview,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("btn_open_live_auth_review")
            ) {
                Text("Review Live Pilot Authorization")
            }
        }

        if (state.isLivePilotSupported && state.showLiveAuthReview && onActivateLivePilot != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_auth_review_panel")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Live Pilot Authorization Review (Externally Provisioned Record)",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Scope fields are read-only from the provisioned approval file (app-private storage). " +
                            "Strictly bounded to externally approved operations (0 WebSockets, <= 15m).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    when (val loadRes = state.provisionedAuthLoadResult) {
                        null, is ProvisionedAuthorizationLoadResult.Missing -> {
                            val msg = (loadRes as? ProvisionedAuthorizationLoadResult.Missing)?.safeMessage
                                ?: "No externally provisioned authorization record found."
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("live_auth_provisioned_status")
                            )
                        }
                        is ProvisionedAuthorizationLoadResult.Invalid -> {
                            Text(
                                text = "Provisioned Record Invalid: ${loadRes.safeMessage}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("live_auth_provisioned_status")
                            )
                        }
                        is ProvisionedAuthorizationLoadResult.Loaded -> {
                            val p = loadRes.preview
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("live_auth_provisioned_preview"),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("Authorization ID: ${p.authorizationId}", fontWeight = FontWeight.Medium)
                                Text("Approvers: PO=${p.approvedByProductOwner}, Lead=${p.approvedByLeadMaintainer}", style = MaterialTheme.typography.bodySmall)
                                Text("External Ref: ${p.externalRecordRef} (${p.approvalDate})", style = MaterialTheme.typography.bodySmall)
                                Text("Candidate Commit: ${p.candidateCommitSha.take(12)}", style = MaterialTheme.typography.bodySmall)
                                Text("Approved APK SHA-256: ${p.verifiedPilotApkSha256.take(12)}...", style = MaterialTheme.typography.bodySmall)
                                Text("Installed APK Verification: ${p.installedApkVerification.detail}", style = MaterialTheme.typography.bodySmall)
                                Text("App Identity: ${p.applicationId} v${p.versionName} (${p.versionCode})", style = MaterialTheme.typography.bodySmall)
                                Text("Account Scope: ${p.accountAliasRedacted} (${p.regionCallingCode})", style = MaterialTheme.typography.bodySmall)
                                Text("Target Scope: ${p.targetRedactedTag} (${p.targetSubTypeFamily})", style = MaterialTheme.typography.bodySmall)
                                Text("Auth Origin: ${p.approvedAuthOrigin}${p.approvedAuthPath}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Pinned API/WSS: ${p.pinnedApiOrigin ?: "UNPINNED"} / ${p.pinnedWssOrigin ?: "UNPINNED"} " +
                                        "(provRef=${p.endpointProvenanceRef ?: "NONE"})",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Permitted Ops: ${p.permittedOperations.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Budgets: 1 LOGIN, ${p.maxInventoryRequests} INVENTORY, 0 WS, 0 CMD, <= ${p.httpCallTimeoutMs / 1000}s HTTP, " +
                                        "${p.maxObservationWindowMs / 60_000L}m window",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Provisioned Digest Hint: ${p.recordSha256Prefix}...${p.recordSha256Suffix}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = expectedRecordSha256Input,
                        onValueChange = { expectedRecordSha256Input = it },
                        label = { Text("Confirm External Approval Record SHA-256 (64 hex)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("live_auth_input_record_sha256")
                    )
                    state.liveAuthValidationError?.let { errMsg ->
                        Text(
                            text = errMsg,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("live_auth_validation_error")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onActivateLivePilot(expectedRecordSha256Input) },
                            modifier = Modifier.weight(1f).testTag("btn_validate_live_auth")
                        ) {
                            Text("Verify & Activate")
                        }
                        if (onRefreshProvisionedAuth != null) {
                            OutlinedButton(
                                onClick = onRefreshProvisionedAuth,
                                modifier = Modifier.testTag("btn_refresh_provisioned_auth")
                            ) {
                                Text("Reload")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                clearLiveAuthInputs()
                                onCancelLiveAuthReview?.invoke()
                            },
                            modifier = Modifier.weight(1f).testTag("btn_cancel_live_auth")
                        ) {
                            Text("Cancel Review")
                        }
                    }
                }
            }
        }

        // Authorization Summary + Monotonic Observation Timer
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_auth_summary")
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Authorization ID: ${state.authorizationId}", fontWeight = FontWeight.Medium)
                    Text("State: ${formatModeState(state.modeState)}")
                    Text(
                        text = "External dual human approval required; UI controls cannot grant pilot authorization.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "Observation Remaining: ${formatDurationMmSs(state.remainingObservationMs)}",
                    modifier = Modifier.testTag("pilot_observation_timer"),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Endpoint Review Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_endpoint_review")
                .semantics(mergeDescendants = true) {}
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Endpoint Trust Review", fontWeight = FontWeight.Medium)
                Text("Auth: ${state.approvedAuthOrigin}")
                Text("Pinned API: ${state.pinnedApiOrigin ?: "UNPINNED (Quarantine Enforced)"}")
                Text("Pinned WSS: ${state.pinnedWssOrigin ?: "UNPINNED (Quarantine Enforced)"}")
                state.quarantinedSummary?.let { q ->
                    Text(
                        text = "Quarantined API=${q.apiOrigin ?: "NONE"}, WSS=${q.wssOrigin ?: "NONE"}, " +
                            "provenanceVerified=${q.provenanceVerified} (${q.quarantineReason})",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Human confirmation alone does NOT verify WeBack host ownership.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Ephemeral Credential Entry (explicitly separated Live vs Mock labels & actions)
        if (isCredentialEntryVisible) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isLiveWorkflowActive) {
                        Text(
                            text = "Real-Server Authentication (Ephemeral, Never Persisted)",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Real Grit Cloud auth for the externally authorized isolated test account only. " +
                                "1 login attempt max, 0 automatic retries, 0 WebSockets, 0 physical robot commands.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            text = "Synthetic Credential Entry (Offline Mock Fixture, No Network)",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "In-process synthetic fixture only. Zero external network requests.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = regionInput,
                        onValueChange = { regionInput = it },
                        label = {
                            Text(
                                if (state.isLiveWorkflowActive) {
                                    "Authorized Region Calling Code (+1 / +86)"
                                } else {
                                    "Region Code (+1 / +86)"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pilot_input_region")
                    )
                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = {
                            Text(
                                if (state.isLiveWorkflowActive) {
                                    "Authorized Isolated Test Account"
                                } else {
                                    "Synthetic Mock Account"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pilot_input_account")
                    )
                    @Suppress("UNUSED_EXPRESSION")
                    bufferVersion
                    OutlinedTextField(
                        value = passwordBuffer.asDisplayString(),
                        onValueChange = { next ->
                            passwordBuffer.updateFromInput(next)
                            bufferVersion++
                        },
                        label = {
                            Text(
                                if (state.isLiveWorkflowActive) {
                                    "Real-Server Account Password"
                                } else {
                                    "Synthetic Password"
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pilot_input_password")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val creds = passwordBuffer.buildAndWipeCredentials(regionInput, accountInput)
                                regionInput = ""
                                accountInput = ""
                                bufferVersion++
                                onSubmitCredentials(creds)
                            },
                            enabled = !state.isLiveOperationInFlight,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_pilot_submit_login")
                        ) {
                            Text(
                                if (state.isLiveWorkflowActive) {
                                    "Authenticate Live Account (1 Attempt)"
                                } else {
                                    "Submit Synthetic Sign-In"
                                }
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                passwordBuffer.wipe()
                                regionInput = ""
                                accountInput = ""
                                bufferVersion++
                                onCancelCredentialEntry()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_pilot_cancel_login")
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // Safe Authentication Result & Single Read-Only Inventory Trigger (FCT-004C live mode)
        val authReadyMode = state.modeState as? PilotModeState.AuthenticatedInventoryReady
        if (authReadyMode != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_auth_result_banner")
                    .semantics(mergeDescendants = true) {}
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Safe Authentication Result (Pre-Approved Origins)", fontWeight = FontWeight.Medium)
                    Text(authReadyMode.sanitizedAuthSummary, style = MaterialTheme.typography.bodySmall)
                    if (authReadyMode.inventoryPermitted && onFetchTargetInventory != null) {
                        Button(
                            onClick = onFetchTargetInventory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("btn_pilot_fetch_target_inventory")
                        ) {
                            Text("Fetch Target Inventory (Single Read-Only Request)")
                        }
                    } else if (!authReadyMode.inventoryPermitted) {
                        Text(
                            text = "Inventory query disabled under LOGIN-only authorization.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Authorized Target Robot Minimum Read-Only Inventory Result (Zero WebSocket)
        val targetSummary = (state.modeState as? PilotModeState.InventoryVerified)?.targetSummary
            ?: state.targetInventorySummary
        if (state.isLiveWorkflowActive && targetSummary != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("target_inventory_summary")
                    .semantics(mergeDescendants = true) {}
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Authorized Target Robot (Read-Only Inventory, Zero WS)", fontWeight = FontWeight.Medium)
                    Text("${targetSummary.nickname} [${targetSummary.thingNameRedacted}]", fontWeight = FontWeight.SemiBold)
                    Text(
                        "subType=${targetSummary.normalizedSubType} | admin=${targetSummary.isAdmin} | ${targetSummary.compatibility.name}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (targetSummary.batteryPercentage != null && targetSummary.mode != null) {
                        Text(
                            "Snapshot: Battery=${targetSummary.batteryPercentage}% | Mode=${targetSummary.mode.name} | Error=${targetSummary.errorCode ?: 0}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (!state.isLiveWorkflowActive) {
        // Read-Only Device Inventory (Mock / FCT-004A/B)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_device_list")
                .semantics(mergeDescendants = true) {}
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Read-Only Device Inventory", fontWeight = FontWeight.Medium)
                    TextButton(
                        onClick = onRefreshInventory,
                        modifier = Modifier.testTag("btn_pilot_refresh_inventory")
                    ) {
                        Text("Refresh")
                    }
                }
                when (val inv = state.inventoryState) {
                    InventoryState.Idle -> Text("Idle (No active pilot inventory)")
                    InventoryState.Loading -> Text("Loading inventory...")
                    InventoryState.Empty -> Text("No devices returned")
                    is InventoryState.Error -> Text("Inventory error: ${inv.error.safeCategory}")
                    is InventoryState.Loaded -> {
                        val snapshotTag = (state.modeState as? PilotModeState.Observing)
                            ?.selectedSnapshot?.thingNameRedacted
                        inv.items.forEachIndexed { idx, item ->
                            val redactedTag = if (item.thingName == state.selectedDevice?.thingName && snapshotTag != null) {
                                snapshotTag
                            } else {
                                "DEV_${idx + 1}_${item.capability.category.name}"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val displayNickname = if (item.nickname == item.thingName) redactedTag else item.nickname
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$displayNickname [$redactedTag]", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "subType=${item.capability.normalizedSubType} | admin=${item.isAdmin} | ${item.capability.compatibility.name}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (item.capability.compatibility.isMvpControlSupported) {
                                    OutlinedButton(
                                        onClick = { onSelectDevice(item.thingName) },
                                        modifier = Modifier.testTag("btn_observe_${item.thingName}")
                                    ) {
                                        Text("Observe")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        // Selected Robot Status & Connection State (hidden in zero-WS FCT-004C live mode)
        if (!state.isLiveWorkflowActive) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_selected_status")
                .semantics(mergeDescendants = true) {}
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Connection & Selected Robot Status", fontWeight = FontWeight.Medium)
                Text("Connection: ${formatConnectionState(state.connectionState)}")
                val snapshot = (state.modeState as? PilotModeState.Observing)?.selectedSnapshot
                if (snapshot != null) {
                    Text("Robot: ${snapshot.nickname} (${snapshot.thingNameRedacted})")
                    Text("Battery: ${snapshot.batteryPercentage}% | Mode: ${snapshot.mode.name} | Error: ${snapshot.errorCode}")
                } else {
                    Text("No active read-only telemetry observation")
                }
            }
        }
        }

        // Emergency Disconnect Result / Abort Status
        if (state.lastAbortReason != null || state.lastDisconnectResult != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pilot_disconnect_status")
                    .semantics(mergeDescendants = true) {}
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    state.lastAbortReason?.let {
                        Text("Last Abort: ${it.safeCode}", fontWeight = FontWeight.Bold)
                    }
                    state.lastDisconnectResult?.let { res ->
                        Text(
                            "Network Revoked=${res.networkRevokedImmediately}, Storage Cleared=${res.storageCleared}, " +
                                "Incomplete=${res.incompleteStorageReason ?: "NONE"}, Simulator Restored=${res.restoredOfflineSimulator}, " +
                                "ServerRevocationClaimed=${res.serverSideTokenRevocationClaimed}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Emergency Disconnect & Wipe Button
        Button(
            onClick = {
                passwordBuffer.wipe()
                regionInput = ""
                accountInput = ""
                clearLiveAuthInputs()
                bufferVersion++
                onEmergencyDisconnect()
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_pilot_emergency_disconnect")
        ) {
            Text("Emergency Disconnect & Wipe")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun formatDurationMmSs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1_000L
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%02d:%02d".format(mins, secs)
}

private fun formatModeState(mode: PilotModeState): String = when (mode) {
    PilotModeState.OfflineDefault -> "OFFLINE_DEFAULT"
    is PilotModeState.AuthorizedReady -> "AUTHORIZED_READY"
    is PilotModeState.AuthenticatedInventoryReady -> "AUTHENTICATED_INVENTORY_READY"
    is PilotModeState.InventoryVerified -> "INVENTORY_VERIFIED_READ_ONLY"
    is PilotModeState.AwaitingOriginConfirmation -> "AWAITING_ORIGIN_CONFIRMATION (QUARANTINED)"
    is PilotModeState.Observing -> "OBSERVING_READ_ONLY"
    is PilotModeState.Aborted -> "ABORTED (${mode.reason.safeCode})"
}

private fun formatConnectionState(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "Disconnected (${state.reason?.safeCategory ?: "NONE"})"
    is ConnectionState.Connecting -> "Connecting (epoch=${state.epoch}, gen=${state.generation})"
    is ConnectionState.Connected -> "Connected (epoch=${state.epoch}, gen=${state.generation})"
    is ConnectionState.Reconnecting -> "Reconnecting (attempt=${state.attempt}, delayMs=${state.retryDelayMs})"
    ConnectionState.Closed -> "Closed"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun View.findDialogWindow(): Window? {
    var current: Any? = parent
    while (current is View) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        current = current.parent
    }
    return (current as? DialogWindowProvider)?.window
}
