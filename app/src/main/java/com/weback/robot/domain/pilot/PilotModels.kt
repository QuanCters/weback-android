package com.weback.robot.domain.pilot

import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.RobotMode

/**
 * Operations permitted inside an authorized read-only FCT-004 pilot session.
 */
enum class PilotOperation(val wireOpt: String) {
    LOGIN("login"),
    USER_THING_LIST_GET("user_thing_list_get"),
    WS_TELEMETRY_OBSERVE("ws_telemetry_observe")
}

enum class PilotAccountScopeType {
    DEDICATED_ISOLATED_TEST_ACCOUNT,
    PERSONAL_OR_PRODUCTION
}

data class PilotExternalApproval(
    val approvedByProductOwner: String,
    val approvedByLeadMaintainer: String,
    val externalRecordRef: String
)

data class ExpectedCandidateIdentity(
    val candidateCommitSha: String,
    val applicationId: String,
    val versionName: String,
    val apkSha256: String
)

data class PilotAccountScope(
    val scopeType: PilotAccountScopeType,
    val regionCallingCode: String,
    val authorizedAccountAlias: String
)

data class PilotTargetDevice(
    val expectedSubTypeFamily: String,
    val expectedTargetThingName: String,
    val redactedTag: String
)

data class PilotEndpointConfig(
    val approvedAuthOrigin: String,
    val approvedAuthPath: String = "/prod/oauth",
    val pinnedApiOrigin: String? = null,
    val pinnedWssOrigin: String? = null,
    val requireProvenanceVerification: Boolean = true
)

data class PilotRequestBudgets(
    val maxLoginAttempts: Int = MAX_LOGIN_ATTEMPTS,
    val maxPasswordRetries: Int = MAX_PASSWORD_RETRIES,
    val maxCaptchaAutomation: Int = MAX_CAPTCHA_AUTOMATION,
    val maxInventoryRequests: Int = MAX_INVENTORY_REQUESTS,
    val maxInitialWsConnections: Int = MAX_INITIAL_WS_CONNECTIONS,
    val maxWsReconnectAttempts: Int = MAX_WS_RECONNECT_ATTEMPTS,
    val httpCallTimeoutMs: Long = MAX_HTTP_CALL_TIMEOUT_MS,
    val observationWindowMs: Long = MAX_OBSERVATION_WINDOW_MS,
    val maxPhysicalRobotCommands: Int = MAX_PHYSICAL_ROBOT_COMMANDS
) {
    companion object {
        const val MAX_LOGIN_ATTEMPTS = 1
        const val MAX_PASSWORD_RETRIES = 0
        const val MAX_CAPTCHA_AUTOMATION = 0
        const val MAX_INVENTORY_REQUESTS = 1
        const val MAX_INITIAL_WS_CONNECTIONS = 1
        const val MAX_WS_RECONNECT_ATTEMPTS = 3
        const val MAX_HTTP_CALL_TIMEOUT_MS = 15_000L
        const val MAX_OBSERVATION_WINDOW_MS = 1_800_000L
        const val MAX_PHYSICAL_ROBOT_COMMANDS = 0
    }
}

/**
 * Immutable pilot authorization context binding candidate/APK identity, dedicated test account,
 * exact target robot, permitted read-only operations, approved endpoints, request budgets, and
 * monotonic observation deadline (`SEC-401`, `SEC-404`).
 */
data class PilotAuthorizationContext(
    val authorizationId: String,
    val candidateIdentity: ExpectedCandidateIdentity,
    val accountScope: PilotAccountScope,
    val targetDevice: PilotTargetDevice,
    val permittedOperations: Set<PilotOperation>,
    val endpointConfig: PilotEndpointConfig,
    val readOnlyMode: Boolean,
    val physicalRobotCommandsPermitted: Boolean,
    val budgets: PilotRequestBudgets,
    val authorizedAtElapsedMs: Long,
    val observationDeadlineElapsedMs: Long,
    val externalApproval: PilotExternalApproval?,
    val uiConsentCheckedOnly: Boolean = false
) {
    override fun toString(): String =
        "PilotAuthorizationContext(authorizationId='$authorizationId', readOnlyMode=$readOnlyMode, " +
            "physicalRobotCommandsPermitted=$physicalRobotCommandsPermitted, targetTag='${targetDevice.redactedTag}', " +
            "authOrigin='${endpointConfig.approvedAuthOrigin}')"
}

sealed interface PilotAbortReason {
    val safeCode: String

    data object UserEmergencyDisconnect : PilotAbortReason {
        override val safeCode: String = "USER_EMERGENCY_DISCONNECT"
    }

    data object ObservationWindowExpired : PilotAbortReason {
        override val safeCode: String = "OBSERVATION_WINDOW_EXPIRED"
    }

    data object AuthorizationExpiredOrInvalid : PilotAbortReason {
        override val safeCode: String = "AUTHORIZATION_EXPIRED_OR_INVALID"
    }

    data object UnexpectedOutboundCommandAttempt : PilotAbortReason {
        override val safeCode: String = "UNEXPECTED_OUTBOUND_COMMAND_ATTEMPT"
    }

    data object UntrustedOriginRejected : PilotAbortReason {
        override val safeCode: String = "UNTRUSTED_ORIGIN_REJECTED"
    }

    data object ChallengeRequired : PilotAbortReason {
        override val safeCode: String = "CHALLENGE_REQUIRED"
    }

    data object ProtocolMismatchAbort : PilotAbortReason {
        override val safeCode: String = "PROTOCOL_MISMATCH_ABORT"
    }

    data object TlsHandshakeFailure : PilotAbortReason {
        override val safeCode: String = "TLS_HANDSHAKE_FAILURE"
    }

    data object BudgetExhausted : PilotAbortReason {
        override val safeCode: String = "BUDGET_EXHAUSTED"
    }

    data object AuthRejected : PilotAbortReason {
        override val safeCode: String = "AUTH_REJECTED"
    }
}

data class ObservedOriginSummary(
    val apiOrigin: String?,
    val wssOrigin: String?,
    val provenanceVerified: Boolean,
    val quarantineReason: String
)

data class ReadOnlyTelemetrySnapshot(
    val thingNameRedacted: String,
    val nickname: String,
    val normalizedSubType: String,
    val isAdmin: Boolean,
    val compatibility: ControlCompatibility,
    val batteryPercentage: Int,
    val mode: RobotMode,
    val errorCode: Int
)

sealed interface PilotModeState {
    data object OfflineDefault : PilotModeState

    data class AuthorizedReady(
        val authorizationId: String,
        val remainingWindowMs: Long
    ) : PilotModeState

    data class AwaitingOriginConfirmation(
        val authorizationId: String,
        val observedOrigins: ObservedOriginSummary
    ) : PilotModeState

    data class Observing(
        val authorizationId: String,
        val remainingWindowMs: Long,
        val selectedSnapshot: ReadOnlyTelemetrySnapshot?
    ) : PilotModeState

    data class Aborted(
        val reason: PilotAbortReason,
        val safeMessage: String
    ) : PilotModeState
}

data class PilotEmergencyDisconnectResult(
    val networkRevokedImmediately: Boolean,
    val storageCleared: Boolean,
    val incompleteStorageReason: String?,
    val restoredOfflineSimulator: Boolean,
    val serverSideTokenRevocationClaimed: Boolean = false
)
