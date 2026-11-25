package com.weback.robot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.protocol.RobotTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RobotUiState(
    val robotName: String = "WeBack Simulator Robot",
    val batteryPercentage: Int = 100,
    val mode: RobotMode = RobotMode.DOCK,
    val isDocked: Boolean = true,
    val isDustbinClean: Boolean = true,
    val isSimulator: Boolean = true,
    val appVersion: String = "v0.1.0-alpha (FCT-001 Foundation)"
)

class DashboardViewModel(
    private val robotTransport: RobotTransport,
    private val dispatchers: DispatcherProvider,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    val uiState: StateFlow<RobotUiState> = robotTransport.robotState
        .map { state ->
            RobotUiState(
                robotName = state.name,
                batteryPercentage = state.batteryPercentage,
                mode = state.mode,
                isDocked = state.isDocked,
                isDustbinClean = state.isDustbinClean,
                isSimulator = true,
                appVersion = "v0.1.0-alpha (FCT-001 Foundation)"
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = RobotUiState(
                robotName = robotTransport.robotState.value.name,
                batteryPercentage = robotTransport.robotState.value.batteryPercentage,
                mode = robotTransport.robotState.value.mode,
                isDocked = robotTransport.robotState.value.isDocked,
                isDustbinClean = robotTransport.robotState.value.isDustbinClean,
                isSimulator = true,
                appVersion = "v0.1.0-alpha (FCT-001 Foundation)"
            )
        )

    class Factory(
        private val robotTransport: RobotTransport,
        private val dispatchers: DispatcherProvider
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(robotTransport, dispatchers) as T
        }
    }
}
