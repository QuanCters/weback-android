package com.weback.robot.app.pilot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weback.robot.BuildConfig
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultPilotContainer
import com.weback.robot.domain.pilot.ExpectedCandidateIdentity
import com.weback.robot.domain.pilot.GateActivationResult
import com.weback.robot.domain.pilot.PilotAccountScope
import com.weback.robot.domain.pilot.PilotAccountScopeType
import com.weback.robot.domain.pilot.PilotAuthorizationContext
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotClock
import com.weback.robot.domain.pilot.PilotEndpointConfig
import com.weback.robot.domain.pilot.PilotEnvironment
import com.weback.robot.domain.pilot.PilotExternalApproval
import com.weback.robot.domain.pilot.PilotOperation
import com.weback.robot.domain.pilot.PilotRequestBudgets
import com.weback.robot.domain.pilot.PilotTargetDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Fct004CPilotAdmissionTest {

    private val runtimeId = ExpectedCandidateIdentity(
        candidateCommitSha = "bc7f6d6589b4962c092574d998f6dfce532b17c7",
        applicationId = "com.weback.robot.pilot",
        versionName = "0.1.0-alpha",
        apkSha256 = "d".repeat(64)
    )

    private fun strictFct004CContext(
        authId: String = "FCT004C-AUTH-001",
        candidateIdentity: ExpectedCandidateIdentity = runtimeId,
        accountIdentifier: String = "pilot-c@test.invalid",
        accountAlias: String = "p***@test.invalid",
        targetThingName: String = "laser_target_c1",
        redactedTag: String = "LASER_C1",
        approval: PilotExternalApproval? = PilotExternalApproval(
            approvedByProductOwner = "PO-Human",
            approvedByLeadMaintainer = "Lead-Human",
            externalRecordRef = "EXT-FCT004C-001"
        ),
        budgets: PilotRequestBudgets = PilotRequestBudgets(
            maxLoginAttempts = 1,
            maxPasswordRetries = 0,
            maxCaptchaAutomation = 0,
            maxInventoryRequests = 1,
            maxInitialWsConnections = 0,
            maxWsReconnectAttempts = 0,
            httpCallTimeoutMs = 15_000L,
            observationWindowMs = 900_000L,
            maxPhysicalRobotCommands = 0
        ),
        permittedOperations: Set<PilotOperation> = setOf(
            PilotOperation.LOGIN,
            PilotOperation.USER_THING_LIST_GET
        ),
        environment: PilotEnvironment = PilotEnvironment.LIVE_PILOT,
        uiCheckboxOnly: Boolean = false
    ) = PilotAuthorizationContext(
        authorizationId = authId,
        candidateIdentity = candidateIdentity,
        accountScope = PilotAccountScope(
            scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
            regionCallingCode = "+1",
            authorizedAccountAlias = accountAlias,
            authorizedAccountIdentifier = accountIdentifier
        ),
        targetDevice = PilotTargetDevice(
            expectedSubTypeFamily = "LaserSlam",
            expectedTargetThingName = targetThingName,
            redactedTag = redactedTag
        ),
        permittedOperations = permittedOperations,
        endpointConfig = PilotEndpointConfig(
            approvedAuthOrigin = "https://user.grit-cloud.com:443",
            approvedAuthPath = "/prod/oauth",
            pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
            pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443",
            requireProvenanceVerification = true
        ),
        readOnlyMode = true,
        physicalRobotCommandsPermitted = false,
        budgets = budgets,
        authorizedAtElapsedMs = 1_000L,
        observationDeadlineElapsedMs = 1_000L + budgets.observationWindowMs,
        externalApproval = approval,
        uiConsentCheckedOnly = uiCheckboxOnly,
        environment = environment
    )

    @Test
    fun acceptsStrictZeroWsFct004CBudgetsAndRejectsExceeding15MinOrWsMismatch() {
        val gate = PilotAuthorizationGate(clock = PilotClock { 1_500L })

        // Valid strict FCT-004C context (0 WS, <= 15 min) must activate
        val ok = gate.activate(strictFct004CContext(), runtimeId, nowElapsedMs = 1_500L)
        assertTrue("Strict zero-WS FCT-004C context must activate, got $ok", ok is GateActivationResult.Activated)

        // Strict FCT-004C context exceeding 15-minute window (900_001ms) must be rejected
        val gate2 = PilotAuthorizationGate(clock = PilotClock { 1_500L })
        val over15Min = gate2.activate(
            strictFct004CContext(
                budgets = PilotRequestBudgets(
                    maxInitialWsConnections = 0,
                    maxWsReconnectAttempts = 0,
                    observationWindowMs = 900_001L
                )
            ),
            runtimeId,
            nowElapsedMs = 1_500L
        )
        assertTrue("Strict FCT-004C window > 15 min must be rejected", over15Min is GateActivationResult.Rejected)

        // Zero initial WS with non-zero reconnects or WS_TELEMETRY_OBSERVE permitted must be rejected
        val invalidWsMix1 = gate2.activate(
            strictFct004CContext(
                budgets = PilotRequestBudgets(
                    maxInitialWsConnections = 0,
                    maxWsReconnectAttempts = 1,
                    observationWindowMs = 900_000L
                )
            ),
            runtimeId,
            nowElapsedMs = 1_500L
        )
        assertTrue(invalidWsMix1 is GateActivationResult.Rejected)

        val invalidWsMix2 = gate2.activate(
            strictFct004CContext(
                permittedOperations = setOf(
                    PilotOperation.LOGIN,
                    PilotOperation.USER_THING_LIST_GET,
                    PilotOperation.WS_TELEMETRY_OBSERVE
                )
            ),
            runtimeId,
            nowElapsedMs = 1_500L
        )
        assertTrue(invalidWsMix2 is GateActivationResult.Rejected)
    }

    @Test
    fun rejectsMissingPlaceholderIncompleteCheckboxAndMockAuthorizations() {
        val gate = PilotAuthorizationGate(clock = PilotClock { 1_500L })

        val placeholderCases = listOf(
            strictFct004CContext(authId = "FCT004C-PILOT-AUTH-PENDING"),
            strictFct004CContext(accountIdentifier = "TEST_ACCOUNT_EMAIL_PLACEHOLDER"),
            strictFct004CContext(targetThingName = "AUTHORIZED_ROBOT_THING_NAME_PLACEHOLDER"),
            strictFct004CContext(
                approval = PilotExternalApproval(
                    approvedByProductOwner = "SYNTHETIC_MOCK_WORKFLOW_ONLY",
                    approvedByLeadMaintainer = "Lead-Human",
                    externalRecordRef = "EXT-1"
                )
            ),
            strictFct004CContext(
                approval = PilotExternalApproval(
                    approvedByProductOwner = "PO-Human",
                    approvedByLeadMaintainer = "Lead-Human",
                    externalRecordRef = "EXT-AUTH-FCT004C-PENDING"
                )
            ),
            strictFct004CContext(
                candidateIdentity = runtimeId.copy(candidateCommitSha = "0".repeat(40))
            ),
            strictFct004CContext(
                candidateIdentity = runtimeId.copy(apkSha256 = "0".repeat(64))
            ),
            strictFct004CContext(approval = null),
            strictFct004CContext(uiCheckboxOnly = true),
            strictFct004CContext(environment = PilotEnvironment.OFFLINE_MOCK_FIXTURE)
        )

        placeholderCases.forEach { badCtx ->
            val res = gate.activate(badCtx, badCtx.candidateIdentity, nowElapsedMs = 1_500L)
            assertTrue("Expected rejection for invalid/placeholder/mock context $badCtx", res is GateActivationResult.Rejected)
        }
    }

    @Test
    fun offlineBuildRejectsLivePilotActivationWhilePilotBuildAcceptsValidContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DefaultPilotContainer(
            context = context,
            authClock = AuthMonotonicClock { 1_500L }
        ).use { container ->
            val ctx = strictFct004CContext(
                candidateIdentity = runtimeId.copy(
                    applicationId = "com.weback.robot.pilot",
                    versionName = BuildConfig.VERSION_NAME
                )
            )
            val result = container.pilotSessionController.activatePilot(ctx)
            if (BuildConfig.PILOT_INTERNET_CAPABLE) {
                assertTrue("Pilot build should accept valid strict FCT-004C context", result is GateActivationResult.Activated)
            } else {
                assertTrue("Offline build MUST reject LIVE_PILOT activation", result is GateActivationResult.Rejected)
            }
        }
    }
}
