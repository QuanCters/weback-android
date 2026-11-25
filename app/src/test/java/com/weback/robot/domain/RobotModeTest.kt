package com.weback.robot.domain

import com.weback.robot.domain.model.RobotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RobotModeTest {

    @Test
    fun testAllEightRobotModesExist() {
        val expectedNames = listOf(
            "DOCK",
            "CLEAN",
            "PAUSED",
            "RETURNING_DOCK",
            "CHARGING",
            "DORMANT",
            "IDLE",
            "FAULT"
        )
        assertEquals(expectedNames, RobotMode.entries.map { it.name })
    }
}
