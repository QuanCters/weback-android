package com.weback.robot.domain

import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotStateTest {

    @Test
    fun testDefaultRobotStateValues() {
        val state = RobotState()
        assertEquals("WeBack Simulator Robot", state.name)
        assertEquals(100, state.batteryPercentage)
        assertEquals(RobotMode.DOCK, state.mode)
        assertTrue(state.isDustbinClean)
        assertEquals(0, state.errorCode)
        assertTrue(state.isDocked)
    }

    @Test
    fun testIsDockedPredicate() {
        val dockedState = RobotState(mode = RobotMode.DOCK)
        assertTrue(dockedState.isDocked)

        val cleanState = RobotState(mode = RobotMode.CLEAN)
        assertFalse(cleanState.isDocked)

        val pausedState = RobotState(mode = RobotMode.PAUSED)
        assertFalse(pausedState.isDocked)

        val idleState = RobotState(mode = RobotMode.IDLE)
        assertFalse(idleState.isDocked)
    }

    @Test
    fun testDataClassCopyImmutability() {
        val original = RobotState(batteryPercentage = 100, mode = RobotMode.DOCK)
        val modified = original.copy(batteryPercentage = 75, mode = RobotMode.CLEAN)

        assertEquals(100, original.batteryPercentage)
        assertEquals(RobotMode.DOCK, original.mode)
        assertTrue(original.isDocked)

        assertEquals(75, modified.batteryPercentage)
        assertEquals(RobotMode.CLEAN, modified.mode)
        assertFalse(modified.isDocked)
    }
}
