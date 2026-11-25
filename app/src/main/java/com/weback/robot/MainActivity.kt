package com.weback.robot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.weback.robot.ui.DashboardViewModel
import com.weback.robot.ui.screens.DashboardScreen
import com.weback.robot.ui.theme.WeBackTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels {
        val app = application as WeBackApplication
        DashboardViewModel.Factory(
            robotTransport = app.container.robotTransport,
            dispatchers = app.container.dispatcherProvider
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeBackTheme {
                DashboardScreen(viewModel = viewModel)
            }
        }
    }
}
