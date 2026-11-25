package com.weback.robot.domain.model

enum class ControlCompatibility {
    MvpSupportedShadowWs,
    UnsupportedPeripheral,
    UnsupportedCamera,
    UnsupportedLegacyXmpp,
    UnsupportedNonLaserVacuum,
    UnsupportedUnknown;

    val isMvpControlSupported: Boolean
        get() = this == MvpSupportedShadowWs
}

enum class DeviceCapabilityCategory {
    LASER_SLAM,
    VISUAL_GYRO_MAPPED,
    GYRO_VACUUM,
    THROUGHTEK_CAMERA_VACUUM,
    PERIPHERAL,
    LEGACY_XMPP,
    UNKNOWN
}

data class DeviceCapability(
    val normalizedSubType: String,
    val category: DeviceCapabilityCategory,
    val compatibility: ControlCompatibility,
    val unsupportedReason: String? = null
)

data class DeviceInventoryItem(
    val thingName: String,
    val subType: String,
    val nickname: String,
    val isAdmin: Boolean,
    val brandIcon: String? = null,
    val capability: DeviceCapability,
    val initialState: RobotState? = null
)

sealed interface InventoryState {
    data object Idle : InventoryState
    data object Loading : InventoryState
    data object Empty : InventoryState
    data class Loaded(
        val items: List<DeviceInventoryItem>,
        val epoch: Long
    ) : InventoryState

    data class Error(
        val error: ConnectivityError,
        val epoch: Long
    ) : InventoryState
}

sealed interface ConnectionState {
    data class Disconnected(val reason: ConnectivityError? = null) : ConnectionState
    data class Connecting(val epoch: Long, val generation: Long) : ConnectionState
    data class Connected(val epoch: Long, val generation: Long) : ConnectionState
    data class Reconnecting(
        val epoch: Long,
        val generation: Long,
        val attempt: Int,
        val retryDelayMs: Long,
        val reason: ConnectivityError?
    ) : ConnectionState

    data object Closed : ConnectionState
}

enum class RecoveryAction {
    RETRY,
    REAUTHENTICATE,
    UNSUPPORTED_INFO
}

sealed interface ConnectivityError {
    val recoveryAction: RecoveryAction
    val safeCategory: String

    data object Unauthenticated : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "UNAUTHENTICATED"
    }

    data object TokenExpired : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "TOKEN_EXPIRED"
    }

    data object AuthorizationDenied : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "AUTHORIZATION_DENIED"
    }

    data object ChallengeRequired : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "CHALLENGE_REQUIRED"
    }

    data object UntrustedEndpoint : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "UNTRUSTED_ENDPOINT"
    }

    data object RedirectRejected : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "REDIRECT_REJECTED"
    }

    data object TransportTimeout : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY
        override val safeCategory: String = "TRANSPORT_TIMEOUT"
    }

    data object NetworkUnavailable : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY
        override val safeCategory: String = "NETWORK_UNAVAILABLE"
    }

    data class HandshakeRejected(val httpCode: Int?) : ConnectivityError {
        override val recoveryAction: RecoveryAction =
            if (httpCode == 401 || httpCode == 403) RecoveryAction.REAUTHENTICATE else RecoveryAction.RETRY
        override val safeCategory: String = "HANDSHAKE_REJECTED"
    }

    data object MalformedPayload : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.RETRY
        override val safeCategory: String = "MALFORMED_PAYLOAD"
    }

    data object StorageFailure : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.REAUTHENTICATE
        override val safeCategory: String = "STORAGE_FAILURE"
    }

    data class UnsupportedDevice(val subTypePrefix: String) : ConnectivityError {
        override val recoveryAction: RecoveryAction = RecoveryAction.UNSUPPORTED_INFO
        override val safeCategory: String = "UNSUPPORTED_DEVICE"
    }
}
