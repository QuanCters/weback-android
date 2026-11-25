package com.weback.robot.domain.pilot

import com.weback.robot.domain.model.CommandRejectionReason
import com.weback.robot.domain.model.CommandResult
import com.weback.robot.domain.model.RobotCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPilotGuardTest {

    @Test
    fun testRejectsAllPhysicalRobotCommandsAndTriggersPilotAbort() {
        val abortedEvents = mutableListOf<Pair<RobotCommand, PilotAbortReason>>()
        val guard = ReadOnlyPilotGuard(
            isPilotReadOnlyActive = { true },
            onUnexpectedCommandAttempt = { cmd, reason -> abortedEvents.add(cmd to reason) }
        )

        for (command in RobotCommand.entries) {
            val result = guard.guardLiveCommand(command)
            assertTrue("Expected CommandResult.Rejected for $command", result is CommandResult.Rejected)
            assertEquals(CommandRejectionReason.TRANSPORT_REJECTED, (result as CommandResult.Rejected).reason)
        }

        assertEquals(RobotCommand.entries.size, abortedEvents.size)
        assertTrue(abortedEvents.all { it.second == PilotAbortReason.UnexpectedOutboundCommandAttempt })
    }
}
