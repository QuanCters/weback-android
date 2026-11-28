package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.map.MapScreen
import com.weback.robot.ui.map.MapViewModel
import com.weback.robot.ui.screens.DashboardScreen
import com.weback.robot.ui.theme.WeBackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels {
        val app = application as WeBackApplication
        DashboardViewModel.Factory(
            robotTransport = app.container.robotTransport,
            dispatchers = app.container.dispatcherProvider,
            clock = app.container.clock,
            appVersion = getString(R.string.app_version)
        )
    }

    private val mapViewModel: MapViewModel by viewModels {
        val app = application as WeBackApplication
        MapViewModel.Factory(
            repository = app.container.localMapRepository,
            dispatchers = app.container.dispatcherProvider
        )
    }

    override fun onResume() {
        super.onResume()
        val bridge = (application as? WeBackApplication)?.container?.pilotCapabilityBridge ?: return
        lifecycleScope.launch {
            bridge.enforceDeadlinesOnResume()
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as? WeBackApplication ?: return
        if (isChangingConfigurations) {
            app.container.localMapRepository.onConfigurationChanged()
        } else {
            app.container.localMapRepository.onLifecycleBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as WeBackApplication
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            @Suppress("UNUSED_VARIABLE")
            val mapUiState by mapViewModel.uiState.collectAsStateWithLifecycle()
            var showLocalMapViewer by rememberSaveable { mutableStateOf(false) }

            val renderWorkspace: @Composable (
                onOpenPilotSheet: (() -> Unit)?,
                pilotButtonLabel: String,
                unresolvedCleanupNotice: String?,
                hideLocalMapForPilotSheet: Boolean
            ) -> Unit = { onOpenPilotSheet, pilotButtonLabel, unresolvedCleanupNotice, hideLocalMapForPilotSheet ->
                BackHandler(enabled = showLocalMapViewer && !hideLocalMapForPilotSheet) {
                    showLocalMapViewer = false
                }

                if (showLocalMapViewer && !hideLocalMapForPilotSheet) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        MapScreen(
                            viewModel = mapViewModel,
                            onNavigateBack = { showLocalMapViewer = false }
                        )
                    }
                } else {
                    DashboardScreen(
                        uiState = uiState,
                        onIntent = viewModel::onIntent,
                        onOpenPilotSheet = onOpenPilotSheet,
                        pilotButtonLabel = pilotButtonLabel,
                        unresolvedCleanupNotice = unresolvedCleanupNotice,
                        onOpenLocalMap = { showLocalMapViewer = true }
                    )
                }
            }

            val bridge = app.container.pilotCapabilityBridge
            if (bridge != null) {
                bridge.Content(
                    showLocalMapViewer = showLocalMapViewer,
                    onDismissLocalMapViewer = { showLocalMapViewer = false }
                ) { onOpenPilotSheet, pilotButtonLabel, unresolvedCleanupNotice, hideLocalMapForPilotSheet ->
                    renderWorkspace(
                        onOpenPilotSheet,
                        pilotButtonLabel,
                        unresolvedCleanupNotice,
                        hideLocalMapForPilotSheet
                    )
                }
            } else {
                WeBackTheme {
                    Box(modifier = Modifier.fillMaxSize()) {
                        renderWorkspace(
                            null,
                            "",
                            null,
                            false
                        )
                    }
                }
            }
        }
    }
}
