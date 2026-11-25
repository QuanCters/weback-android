package com.weback.robot.ui

import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.protocol.RobotTransport
import com.weback.robot.protocol.SimulatorRobotTransport
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRobotTransport(initialState: RobotState) : RobotTransport {
    private val _robotState = MutableStateFlow(initialState)
    override val robotState: StateFlow<RobotState> = _robotState.asStateFlow()

    fun emitState(newState: RobotState) {
        _robotState.value = newState
    }

    override suspend fun sendCommand(command: RobotCommand): CommandResult {
        // Controllable test double adapted to typed FCT-002 transport contract
        return CommandResult.Acknowledged(_robotState.value)
    }
}

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

    @Test
    fun testTelemetryUpdatesCollectedAndEmitted() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val testDispatcherProvider = TestDispatcherProvider()
        val initial = RobotState(
            name = "Test Robot",
            batteryPercentage = 100,
            mode = RobotMode.DOCK,
            isDustbinClean = true
        )
        val fakeTransport = FakeRobotTransport(initial)
        val testScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        val viewModel = DashboardViewModel(fakeTransport, testDispatcherProvider, testScope)

        val collectedStates = mutableListOf<RobotUiState>()
        val collectionJob = launch(testDispatcher) {
            viewModel.uiState.collect { collectedStates.add(it) }
        }

        // Verify initial state collection
        assertEquals(1, collectedStates.size)
        assertEquals(100, collectedStates.last().batteryPercentage)
        assertTrue(collectedStates.last().isDocked)
        assertTrue(collectedStates.last().isDustbinClean)

        // Emit updated telemetry: battery drops to 45%, mode transitions to CLEAN, dustbin becomes full
        val updatedState = RobotState(
            name = "Test Robot",
            batteryPercentage = 45,
            mode = RobotMode.CLEAN,
            isDustbinClean = false,
            errorCode = 0
        )
        fakeTransport.emitState(updatedState)

        // Verify updated state collected and transformed
        assertEquals(2, collectedStates.size)
        val latest = collectedStates.last()
        assertEquals("Test Robot", latest.robotName)
        assertEquals(45, latest.batteryPercentage)
        assertEquals(RobotMode.CLEAN, latest.mode)
        assertFalse(latest.isDocked)
        assertFalse(latest.isDustbinClean)
        assertTrue(latest.isSimulator)

        // Verify coroutine lifecycle cleanup
        collectionJob.cancel()
        testScope.cancel()
        assertTrue(collectionJob.isCancelled)
    }
}
