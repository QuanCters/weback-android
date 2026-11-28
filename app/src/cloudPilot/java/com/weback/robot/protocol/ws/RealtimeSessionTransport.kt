package com.weback.robot.protocol.ws

import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import kotlinx.coroutines.flow.StateFlow

/**
 * Epoch- and generation-fenced real-time WebSocket session abstraction (`IC-303`, `SEC-304`).
 */
interface RealtimeSessionTransport : AutoCloseable {
    val connectionState: StateFlow<ConnectionState>
    val robotState: StateFlow<RobotState>

    fun connect(session: SessionCredentials, epoch: Long)
    fun sendOutbound(payload: String, epoch: Long): Boolean
    fun selectDevice(item: DeviceInventoryItem?, epoch: Long)
    fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError)
    fun currentGeneration(): Long
    override fun close()
}

internal fun connectivityErrorToAuthFailure(error: ConnectivityError): AuthFailureReason = when (error) {
    ConnectivityError.Unauthenticated,
    ConnectivityError.TokenExpired,
    ConnectivityError.AuthorizationDenied -> AuthFailureReason.TokenExpiredOrRevoked
    ConnectivityError.ChallengeRequired -> AuthFailureReason.ChallengeRequired
    ConnectivityError.UntrustedEndpoint -> AuthFailureReason.UntrustedEndpoint
    ConnectivityError.RedirectRejected -> AuthFailureReason.RedirectRejected
    ConnectivityError.StorageFailure -> AuthFailureReason.StorageFailure
    ConnectivityError.NetworkUnavailable -> AuthFailureReason.NetworkUnavailable
    ConnectivityError.TransportTimeout -> AuthFailureReason.Timeout
    ConnectivityError.MalformedPayload,
    is ConnectivityError.UnsupportedDevice -> AuthFailureReason.MalformedResponse
    is ConnectivityError.HandshakeRejected ->
        if (error.httpCode == 401 || error.httpCode == 403) AuthFailureReason.InvalidCredentials
        else AuthFailureReason.ServerRejected(error.httpCode, null, "WS_${error.httpCode}")
}
