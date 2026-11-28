package com.weback.robot.core

import androidx.compose.runtime.Composable

/**
 * Optional variant bridge isolating Cloud Pilot resume deadlines and readiness overlay UI out of
 * shared [com.weback.robot.MainActivity] and [OfflineAppContainer].
 * Null in offline `release` and `local` variants.
 */
interface PilotCapabilityBridge {
    suspend fun enforceDeadlinesOnResume()

    @Composable
    fun Content(
        showLocalMapViewer: Boolean,
        onDismissLocalMapViewer: () -> Unit,
        renderWorkspace: @Composable (
            onOpenPilotSheet: () -> Unit,
            pilotButtonLabel: String,
            unresolvedCleanupNotice: String?,
            hideLocalMapForPilotSheet: Boolean
        ) -> Unit
    )
}
