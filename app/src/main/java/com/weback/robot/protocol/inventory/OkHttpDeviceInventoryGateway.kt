package com.weback.robot.protocol.inventory

import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.auth.LifetimeEvaluation
import com.weback.robot.domain.auth.TokenExpiryPolicy
import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotMode
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.OkHttpSecurityGuard
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

class OkHttpDeviceInventoryGateway(
    private val trustPolicy: EndpointTrustPolicy,
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val client: OkHttpClient = OkHttpSecurityGuard.buildHardenedClient(trustPolicy)
) : DeviceInventoryGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class InventoryRequest(
        val opt: String = "user_thing_list_get"
    )

    @Serializable
    private data class InventoryResponseEnvelope(
        val code: Int,
        val data: InventoryData? = null
    ) {
        @Serializable
        data class InventoryData(
            @SerialName("thing_list") val thingList: List<ThingDto>? = null
        )

        @Serializable
        data class ThingDto(
            @SerialName("thing_name") val thingName: String? = null,
            @SerialName("sub_type") val subType: String? = null,
            @SerialName("thing_nickname") val thingNickname: String? = null,
            @SerialName("is_admin") val isAdmin: Boolean = false,
            @SerialName("brand_icon") val brandIcon: String? = null,
            @SerialName("thing_status") val thingStatus: ThingStatusDto? = null
        )

        @Serializable
        data class ThingStatusDto(
            val connected: Boolean? = null,
            @SerialName("working_status") val workingStatus: String? = null,
            @SerialName("battery_level") val batteryLevel: Int? = null,
            @SerialName("error_info") val errorInfo: Int? = null
        )
    }

    override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult {
        // Immediate pre-send expiry & endpoint trust enforcement (IC-301 / SEC-301)
        if (TokenExpiryPolicy.evaluateInProcess(session, authClock.elapsedRealtimeMs()) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
            return InventoryFetchResult.Failure(ConnectivityError.TokenExpired)
        }
        if (trustPolicy.authorize(session.apiUrl) !is EndpointTrustDecision.Trusted) {
            return InventoryFetchResult.Failure(ConnectivityError.UntrustedEndpoint)
        }

        val bodyText = json.encodeToString(InventoryRequest())
        val startedMs = authClock.elapsedRealtimeMs()

        return withContext(dispatchers.io) {
            try {
                val request = Request.Builder()
                    .url(session.apiUrl)
                    .addHeader("token", session.jwtToken)
                    .addHeader("region", session.regionName)
                    .post(bodyText.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = (authClock.elapsedRealtimeMs() - startedMs).coerceAtLeast(0L)
                    logger.log(
                        DiagnosticEvent(
                            category = "INVENTORY_RESPONSE",
                            opt = "user_thing_list_get",
                            httpStatus = response.code,
                            latencyMs = latency,
                            safeHostPath = request.url.encodedPath
                        )
                    )

                    // Post-response expiry check in case token expired while HTTP request was in flight
                    if (TokenExpiryPolicy.evaluateInProcess(session, authClock.elapsedRealtimeMs()) == LifetimeEvaluation.EXPIRED_OR_INVALID) {
                        return@withContext InventoryFetchResult.Failure(ConnectivityError.TokenExpired)
                    }

                    when {
                        response.code in 300..399 ->
                            InventoryFetchResult.Failure(ConnectivityError.RedirectRejected)
                        response.code == 401 ->
                            InventoryFetchResult.Failure(ConnectivityError.TokenExpired)
                        response.code == 403 ->
                            InventoryFetchResult.Failure(ConnectivityError.AuthorizationDenied)
                        response.code !in 200..299 ->
                            InventoryFetchResult.Failure(ConnectivityError.HandshakeRejected(response.code))
                        else -> parseBody(response.body?.string().orEmpty())
                    }
                }
            } catch (_: SocketTimeoutException) {
                InventoryFetchResult.Failure(ConnectivityError.TransportTimeout)
            } catch (_: IOException) {
                InventoryFetchResult.Failure(ConnectivityError.NetworkUnavailable)
            } catch (_: Throwable) {
                InventoryFetchResult.Failure(ConnectivityError.MalformedPayload)
            }
        }
    }

    private fun parseBody(rawBody: String): InventoryFetchResult {
        if (rawBody.isBlank()) return InventoryFetchResult.Failure(ConnectivityError.MalformedPayload)
        val parsed = try {
            json.decodeFromString<InventoryResponseEnvelope>(rawBody)
        } catch (_: Throwable) {
            return InventoryFetchResult.Failure(ConnectivityError.MalformedPayload)
        }

        if (parsed.code != 1 || parsed.data?.thingList == null) {
            return InventoryFetchResult.Failure(ConnectivityError.MalformedPayload)
        }

        val items = mutableListOf<DeviceInventoryItem>()
        for (dto in parsed.data.thingList) {
            val thingName = dto.thingName?.takeIf { it.isNotBlank() }
                ?: return InventoryFetchResult.Failure(ConnectivityError.MalformedPayload)
            val subType = dto.subType.orEmpty()
            val nickname = dto.thingNickname?.takeIf { it.isNotBlank() } ?: thingName
            val capability = DeviceCapabilityClassifier.classify(subType)
            val initialState = dto.thingStatus?.let { status ->
                RobotState(
                    name = nickname,
                    batteryPercentage = (status.batteryLevel ?: 100).coerceIn(0, 100),
                    mode = mapWorkingStatus(status.workingStatus),
                    isDustbinClean = true,
                    errorCode = status.errorInfo ?: 0
                )
            }
            items.add(
                DeviceInventoryItem(
                    thingName = thingName,
                    subType = subType,
                    nickname = nickname,
                    isAdmin = dto.isAdmin,
                    brandIcon = dto.brandIcon,
                    capability = capability,
                    initialState = initialState
                )
            )
        }
        return InventoryFetchResult.Success(items)
    }

    companion object {
        internal fun mapWorkingStatus(rawStatus: String?): RobotMode = when (rawStatus?.trim()?.lowercase()) {
            "autoclean", "clean", "cleaning", "spotclean", "edgeclean", "mopping" -> RobotMode.CLEAN
            "pause", "paused" -> RobotMode.PAUSED
            "back", "docking", "returning", "returning_dock" -> RobotMode.RETURNING_DOCK
            "charging", "charge" -> RobotMode.CHARGING
            "dock", "standby_dock" -> RobotMode.DOCK
            "sleep", "dormant" -> RobotMode.DORMANT
            "fault", "error" -> RobotMode.FAULT
            else -> RobotMode.IDLE
        }
    }
}
