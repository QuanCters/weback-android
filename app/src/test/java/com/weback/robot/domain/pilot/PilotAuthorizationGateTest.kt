package com.weback.robot.domain.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotAuthorizationGateTest {

    private val runtimeIdentity = ExpectedCandidateIdentity(
        candidateCommitSha = "b0ce6ff1b3f65da67673d1e0cf7959b4266250ca",
        applicationId = "com.weback.robot",
        versionName = "0.1.0-alpha",
        apkSha256 = "3c933db2160c4407f44b3393dffdd3cf372074e5b06f8b29089ee0408e9fa90e"
    )

    private fun validContext(
        authorizationId: String = "FCT004-PILOT-20261125-01",
        candidateIdentity: ExpectedCandidateIdentity = runtimeIdentity,
        accountScope: PilotAccountScope = PilotAccountScope(
            scopeType = PilotAccountScopeType.DEDICATED_ISOLATED_TEST_ACCOUNT,
            regionCallingCode = "+1",
            authorizedAccountAlias = "pilot***@example.invalid",
            authorizedAccountIdentifier = "pilot@example.invalid"
        ),
        targetDevice: PilotTargetDevice = PilotTargetDevice(
            expectedSubTypeFamily = "LaserSlam",
            expectedTargetThingName = "laser_test_01",
            redactedTag = "LASER_TEST_01"
        ),
        permittedOperations: Set<PilotOperation> = setOf(
            PilotOperation.LOGIN,
            PilotOperation.USER_THING_LIST_GET,
            PilotOperation.WS_TELEMETRY_OBSERVE
        ),
        endpointConfig: PilotEndpointConfig = PilotEndpointConfig(
            approvedAuthOrigin = "https://user.grit-cloud.com:443",
            approvedAuthPath = "/prod/oauth",
            pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
            pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443",
            requireProvenanceVerification = true
        ),
        readOnlyMode: Boolean = true,
        physicalRobotCommandsPermitted: Boolean = false,
        budgets: PilotRequestBudgets = PilotRequestBudgets(),
        authorizedAtElapsedMs: Long = 1_000L,
        observationDeadlineElapsedMs: Long = 1_801_000L,
        externalApproval: PilotExternalApproval? = PilotExternalApproval(
            approvedByProductOwner = "PO-Human 2026-11-25",
            approvedByLeadMaintainer = "Lead-Human 2026-11-25",
            externalRecordRef = "EXT-AUTH-20261125-01"
        ),
        uiConsentCheckedOnly: Boolean = false
    ) = PilotAuthorizationContext(
        authorizationId = authorizationId,
        candidateIdentity = candidateIdentity,
        accountScope = accountScope,
        targetDevice = targetDevice,
        permittedOperations = permittedOperations,
        endpointConfig = endpointConfig,
        readOnlyMode = readOnlyMode,
        physicalRobotCommandsPermitted = physicalRobotCommandsPermitted,
        budgets = budgets,
        authorizedAtElapsedMs = authorizedAtElapsedMs,
        observationDeadlineElapsedMs = observationDeadlineElapsedMs,
        externalApproval = externalApproval,
        uiConsentCheckedOnly = uiConsentCheckedOnly
    )

    @Test
    fun testDefaultsToDisabledAndNeverRestoresAcrossRestart() {
        val gate = PilotAuthorizationGate()
        assertFalse(gate.isAuthorized)
        assertNull(gate.currentContext)
        assertEquals(
            GateCheckResult.Denied(PilotAbortReason.AuthorizationExpiredOrInvalid, "Pilot authorization is disabled"),
            gate.authorizeOperation(PilotOperation.LOGIN, nowElapsedMs = 1_500L)
        )

        val act = gate.activate(validContext(), runtimeIdentity, nowElapsedMs = 1_500L)
        assertTrue(act is GateActivationResult.Activated)
        assertTrue(gate.isAuthorized)

        // Simulated process restart constructs a new gate with zero persisted restoration
        val restartedGate = PilotAuthorizationGate()
        assertFalse(restartedGate.isAuthorized)
        assertNull(restartedGate.currentContext)
    }

    @Test
    fun testRejectsMissingExternalApprovalAndUiCheckboxSubstitution() {
        val gate = PilotAuthorizationGate()

        val noApproval = gate.activate(
            validContext(externalApproval = null),
            runtimeIdentity,
            nowElapsedMs = 1_500L
        )
        assertTrue(noApproval is GateActivationResult.Rejected)

        val checkboxOnly = gate.activate(
            validContext(uiConsentCheckedOnly = true),
            runtimeIdentity,
            nowElapsedMs = 1_500L
        )
        assertTrue(checkboxOnly is GateActivationResult.Rejected)

        val uiBypassFlag = gate.activate(
            validContext(),
            runtimeIdentity,
            nowElapsedMs = 1_500L,
            isUiCheckboxOnly = true
        )
        assertTrue(uiBypassFlag is GateActivationResult.Rejected)
        assertFalse(gate.isAuthorized)
    }

    @Test
    fun testRejectsExpiredMismatchedAndIncompleteContexts() {
        val gate = PilotAuthorizationGate()

        // Blank auth id
        assertTrue(gate.activate(validContext(authorizationId = "  "), runtimeIdentity, 1_500L) is GateActivationResult.Rejected)

        // Candidate SHA mismatch
        assertTrue(
            gate.activate(
                validContext(candidateIdentity = runtimeIdentity.copy(candidateCommitSha = "0".repeat(40))),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // APK SHA mismatch
        assertTrue(
            gate.activate(
                validContext(candidateIdentity = runtimeIdentity.copy(apkSha256 = "f".repeat(64))),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // Non-isolated account
        assertTrue(
            gate.activate(
                validContext(
                    accountScope = PilotAccountScope(
                        PilotAccountScopeType.PERSONAL_OR_PRODUCTION,
                        "+1",
                        "personal@example.invalid"
                    )
                ),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // Non-LaserSlam device family
        assertTrue(
            gate.activate(
                validContext(
                    targetDevice = PilotTargetDevice("GyroVacuum", "gyro_1", "GYRO")
                ),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // Attempt to enable physical robot commands
        assertTrue(
            gate.activate(
                validContext(physicalRobotCommandsPermitted = true),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )
        assertTrue(
            gate.activate(
                validContext(readOnlyMode = false),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // Excessive budget (> 30 min window or > 15s HTTP callTimeout or > 3 reconnects)
        assertTrue(
            gate.activate(
                validContext(budgets = PilotRequestBudgets(observationWindowMs = 1_800_001L)),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )
        assertTrue(
            gate.activate(
                validContext(budgets = PilotRequestBudgets(httpCallTimeoutMs = 15_001L)),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )
        assertTrue(
            gate.activate(
                validContext(budgets = PilotRequestBudgets(maxWsReconnectAttempts = 4)),
                runtimeIdentity,
                1_500L
            ) is GateActivationResult.Rejected
        )

        // Already expired monotonic deadline
        assertTrue(
            gate.activate(
                validContext(),
                runtimeIdentity,
                nowElapsedMs = 1_801_000L
            ) is GateActivationResult.Rejected
        )

        // Invalid pinned API / WSS origin (cleartext or non-443 port)
        assertTrue(
            gate.activate(
                validContext(
                    endpointConfig = PilotEndpointConfig(
                        approvedAuthOrigin = "https://user.grit-cloud.com:443",
                        pinnedApiOrigin = "http://api-us.grit-cloud.com:443"
                    )
                ),
                runtimeIdentity,
                nowElapsedMs = 1_500L
            ) is GateActivationResult.Rejected
        )
        assertTrue(
            gate.activate(
                validContext(
                    endpointConfig = PilotEndpointConfig(
                        approvedAuthOrigin = "https://user.grit-cloud.com:443",
                        pinnedWssOrigin = "wss://wss-us.grit-cloud.com:8443"
                    )
                ),
                runtimeIdentity,
                nowElapsedMs = 1_500L
            ) is GateActivationResult.Rejected
        )
        assertFalse(gate.isAuthorized)
    }

    @Test
    fun testEnforcesMonotonicDeadlineAndRevocation() {
        val gate = PilotAuthorizationGate()
        assertTrue(gate.activate(validContext(), runtimeIdentity, nowElapsedMs = 1_000L) is GateActivationResult.Activated)
        assertTrue(gate.authorizeOperation(PilotOperation.LOGIN, nowElapsedMs = 1_800_999L) is GateCheckResult.Allowed)

        val expired = gate.authorizeOperation(PilotOperation.USER_THING_LIST_GET, nowElapsedMs = 1_801_000L)
        assertTrue(expired is GateCheckResult.Denied)
        assertEquals(PilotAbortReason.ObservationWindowExpired, (expired as GateCheckResult.Denied).reason)
        assertFalse(gate.isAuthorized)
    }
}
