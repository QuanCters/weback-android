package com.weback.robot.ui

import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @Test
    fun testInitialUiStateEmission() = runTest {
        val testDispatcherProvider = TestDispatcherProvider()
        val transport = SimulatorRobotTransport()
        val viewModel = DashboardViewModel(transport, testDispatcherProvider, backgroundScope)

        val state = viewModel.uiState.value
        assertEquals("WeBack Simulator Robot", state.robotName)
        assertEquals(100, state.batteryPercentage)
        assertEquals(RobotMode.DOCK, state.mode)
        assertTrue(state.isDocked)
        assertTrue(state.isDustbinClean)
        assertTrue(state.isSimulator)
        assertEquals("v0.1.0-alpha (FCT-001 Foundation)", state.appVersion)
    }

    @Test
    fun testCustomInitialStateEmission() = runTest {
        val testDispatcherProvider = TestDispatcherProvider()
        val customState = RobotState(
            name = "Custom Robot",
            batteryPercentage = 80,
            mode = RobotMode.CLEAN,
            isDustbinClean = false,
            errorCode = 0
        )
        val transport = SimulatorRobotTransport(initialState = customState)
        val viewModel = DashboardViewModel(transport, testDispatcherProvider, backgroundScope)

        val state = viewModel.uiState.value
        assertEquals("Custom Robot", state.robotName)
        assertEquals(80, state.batteryPercentage)
        assertEquals(RobotMode.CLEAN, state.mode)
        assertFalse(state.isDocked)
        assertFalse(state.isDustbinClean)
        assertTrue(state.isSimulator)
    }
}
