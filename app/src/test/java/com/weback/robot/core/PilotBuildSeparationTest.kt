package com.weback.robot.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.R
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotModeState
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PilotBuildSeparationTest {

    @Test
    fun testVariantIdentityLabelingAndDefaultOfflineIsolation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context = context)
        try {
            // Both builds MUST boot in OfflineDefault with zero automatic auth or persistent live auth
            assertFalse(container.pilotAuthorizationGate.isAuthorized)
            assertEquals(PilotModeState.OfflineDefault, container.pilotSessionController.pilotModeState.value)

            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertEquals("com.weback.robot.pilot", BuildConfig.APPLICATION_ID)
                assertEquals("PILOT", BuildConfig.APP_CONNECTIVITY_MODE)
                assertEquals("WeBack Robot Pilot (Read-Only)", context.getString(R.string.app_name))
            } else {
                assertEquals("com.weback.robot", BuildConfig.APPLICATION_ID)
                assertEquals("OFFLINE", BuildConfig.APP_CONNECTIVITY_MODE)
                assertEquals("WeBack Robot Control", context.getString(R.string.app_name))
            }

            // Live pilot context bound to com.weback.robot.pilot works on pilot build via RuntimeAppIdentity
            // (without self-referential APK SHA-256) and is rejected on offline debug build
            val nowMs = 10_000L
            val pilotCtx = PilotAuthorizationContext(
                authorizationId = "FCT004B-BUILD-SEP-01",
                candidateIdentity = ExpectedCandidateIdentity(
                    candidateCommitSha = "ed8b8c584fd45d9750be33945579ecbbbd93be20",
                    applicationId = "com.weback.robot.pilot",
                    versionName = BuildConfig.VERSION_NAME,
                    apkSha256 = "c".repeat(64)
                ),
                accountScope = PilotAccountScope(
                    scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
                    regionCallingCode = "+1",
                    authorizedAccountAlias = "p***@example.invalid",
                    authorizedAccountIdentifier = "pilot@example.invalid"
                ),
                targetDevice = PilotTargetDevice("LaserSlam", "laser_01", "LASER_01"),
                permittedOperations = setOf(
                    PilotOperation.LOGIN,
                    PilotOperation.USER_THING_LIST_GET,
                    PilotOperation.WS_TELEMETRY_OBSERVE
                ),
                endpointConfig = PilotEndpointConfig("https://user.grit-cloud.com:443"),
                readOnlyMode = true,
                physicalRobotCommandsPermitted = false,
                budgets = PilotRequestBudgets(),
                authorizedAtElapsedMs = nowMs,
                observationDeadlineElapsedMs = nowMs + 60_000L,
                externalApproval = PilotExternalApproval("PO", "Lead", "REF-SEP-01"),
                environment = PilotEnvironment.LIVE_PILOT
            )
            val liveActivation = DefaultAppContainer(
                context = context,
                authClock = AuthMonotonicClock { nowMs }
            ).use { seeded ->
                seeded.pilotSessionController.activatePilot(pilotCtx)
            }
            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertTrue("Expected pilot build to accept RuntimeAppIdentity", liveActivation is GateActivationResult.Activated)
            } else {
                assertTrue("Offline debug build must reject LIVE_PILOT", liveActivation is GateActivationResult.Rejected)
            }
        } finally {
            container.close()
        }
    }
}
