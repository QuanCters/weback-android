package com.weback.robot.domain

import com.weback.robot.domain.model.RobotCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class RobotCommandTest {

    @Test
    fun testCanonicalWireStatusTokens() {
        val expected = mapOf(
            RobotCommand.CLEAN to "Clean",
            RobotCommand.PAUSE to "Pause",
            RobotCommand.RESUME to "Clean",
            RobotCommand.DOCK to "GoHome"
        )

        assertEquals(4, RobotCommand.entries.size)
        for ((command, expectedToken) in expected) {
            assertEquals(
                "Wire status mismatch for $command",
                expectedToken,
                command.wireStatus
            )
        }
    }
}
