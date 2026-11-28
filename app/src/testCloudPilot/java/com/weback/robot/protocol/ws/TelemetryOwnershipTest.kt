package com.weback.robot.protocol.ws

import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.TestLoopbackTrustPolicy
import com.weback.robot.security.UriValidator
import com.weback.robot.testutils.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryOwnershipTest {

    @Test
    fun testUnselectedUnknownAndUnsupportedTelemetryNeverMutatesRobotState() = runTest {
        val trustPolicy = TestLoopbackTrustPolicy(setOf(UriValidator.NormalizedOrigin("ws", "127.0.0.1", 8080)))
        val transport = OkHttpWebSocketTransport(
            trustPolicy = trustPolicy,
            authClock = AuthMonotonicClock { 1_000L },
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        )
        val selectedLaser = DeviceInventoryItem(
            thingName = "selected_laser",
            subType = "yg-yw10001-a",
            nickname = "Selected Laser",
            isAdmin = true,
            capability = DeviceCapabilityClassifier.classify("yg-yw10001-a")
        )
        val unsupportedPeripheral = DeviceInventoryItem(
            thingName = "plug_01",
            subType = "sock-pw-1",
            nickname = "Smart Plug",
            isAdmin = true,
            capability = DeviceCapabilityClassifier.classify("sock-pw-1")
        )

        // Bind epoch 1 and select laser robot
        transport.selectDevice(selectedLaser, epoch = 1L)
        val gen1 = transport.currentGeneration()

        // 1. Unselected thing_name
        transport.onInboundFrameForTest(1L, gen1, """{"notify_info":"thing_status_update","thing_name":"other_robot","working_status":"Fault","battery_level":12}""")
        // 2. Unknown empty/unregistered thing_name
        transport.onInboundFrameForTest(1L, gen1, """{"notify_info":"thing_status_update","thing_name":"unknown_thing","working_status":"AutoClean","battery_level":25}""")
        // Assert unchanged defaults
        assertEquals(100, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.DOCK, transport.robotState.value.mode)

        // 3. Valid selected laser frame DOES apply
        transport.onInboundFrameForTest(1L, gen1, """{"notify_info":"thing_status_update","thing_name":"selected_laser","working_status":"Pause","battery_level":64}""")
        assertEquals(64, transport.robotState.value.batteryPercentage)
        assertEquals(RobotMode.PAUSED, transport.robotState.value.mode)

        // 4. Selecting an unsupported peripheral prevents telemetry mutation for that peripheral
        transport.selectDevice(unsupportedPeripheral, epoch = 1L)
        val gen2 = transport.currentGeneration()
        transport.onInboundFrameForTest(1L, gen2, """{"notify_info":"thing_status_update","thing_name":"plug_01","working_status":"Fault","battery_level":10}""")
        assertEquals(100, transport.robotState.value.batteryPercentage)
        transport.close()
    }
}
