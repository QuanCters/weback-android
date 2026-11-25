package com.weback.robot.domain

import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SimulatorErrorCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotStateInvariantsTest {

    @Test
    fun testIsDockedAcrossAllEightModes() {
        val expectedDockedModes = setOf(RobotMode.DOCK, RobotMode.CHARGING)
        for (mode in RobotMode.entries) {
            val state = RobotState(mode = mode)
            assertEquals(
                "isDocked invariant violated for mode=$mode",
                mode in expectedDockedModes,
                state.isDocked
            )
        }
    }

    @Test
    fun testIsFaultConsistency() {
        for (mode in RobotMode.entries) {
            val cleanErr = RobotState(mode = mode, errorCode = 0)
            assertEquals(mode == RobotMode.FAULT, cleanErr.isFault)

            val nonZeroErr = RobotState(mode = mode, errorCode = SimulatorErrorCodes.SYNTHETIC_INJECTED_FAULT)
            assertTrue("errorCode != 0 must always surface isFault=true for mode=$mode", nonZeroErr.isFault)
        }
        assertFalse(RobotState(mode = RobotMode.DOCK, errorCode = 0).isFault)
        assertEquals(0, SimulatorErrorCodes.NONE)
        assertEquals(9001, SimulatorErrorCodes.SYNTHETIC_BATTERY_DEPLETED)
        assertEquals(9002, SimulatorErrorCodes.SYNTHETIC_INJECTED_FAULT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBatteryBelowZeroThrows() {
        RobotState(batteryPercentage = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBatteryAboveHundredThrows() {
        RobotState(batteryPercentage = 101)
    }

    @Test
    fun testBoundaryZeroAndHundredValid() {
        assertEquals(0, RobotState(batteryPercentage = 0).batteryPercentage)
        assertEquals(100, RobotState(batteryPercentage = 100).batteryPercentage)
    }
}
