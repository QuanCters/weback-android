package com.weback.robot.protocol.pilot

import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.ConnectionState
import com.weback.robot.domain.model.ConnectivityError
import com.weback.robot.domain.model.DeviceInventoryItem
import com.weback.robot.domain.model.RobotState
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.domain.pilot.ObservedOriginSummary
import com.weback.robot.protocol.auth.AuthEnvelopeConfig
import com.weback.robot.protocol.auth.AuthGateway
import com.weback.robot.protocol.auth.RegionEndpointRouter
import com.weback.robot.protocol.auth.RegionResolution
import com.weback.robot.protocol.inventory.DeviceInventoryGateway
import com.weback.robot.protocol.inventory.InventoryFetchResult
import com.weback.robot.protocol.inventory.OkHttpDeviceInventoryGateway
import com.weback.robot.protocol.ws.OkHttpWebSocketTransport
import com.weback.robot.protocol.ws.RealtimeSessionTransport
import com.weback.robot.security.AuthorizedPilotEndpointTrustPolicy
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.DynamicOriginEvaluation
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.OkHttpSecurityGuard
import com.weback.robot.security.PasswordDigestStrategy
import com.weback.robot.security.Utf8TlsDefault
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Arrays
import javax.net.ssl.SSLHandshakeException

/**
 * Capability-restricted read-only telemetry interface exposed to [PilotSessionController]
 * and UI layers. Structurally omits any outbound application-message sending methods (`SEC-402`).
 */
interface PilotReadOnlyTelemetryTransport : AutoCloseable {
    val connectionState: StateFlow<ConnectionState>
    val robotState: StateFlow<RobotState>

    fun connect(session: SessionCredentials, epoch: Long)
    fun selectDevice(item: DeviceInventoryItem?, epoch: Long)
    fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError)
    fun forceDisconnect(reason: ConnectivityError)
    fun currentGeneration(): Long
    override fun close()
}

/**
 * Structural HTTP operation allowlist validator (`SEC-402`).
 * Permits ONLY `"login"` and `"user_thing_list_get"` request envelopes; rejects `"send_to_device"`,
 * unknown operations, extra command fields, and malformed JSON prior to network dispatch.
 */
object PilotHttpOperationGuard {

    private val allowedOpts = setOf("login", "user_thing_list_get")
    private val json = Json { ignoreUnknownKeys = false }

    fun validateOperationPayload(expectedOpt: String, rawJson: String): Boolean {
        if (expectedOpt !in allowedOpts) return false
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return false
        return when (expectedOpt) {
            "login" -> {
                if (root.keys != setOf("header", "payload")) return false
                val payload = root["payload"] as? JsonObject ?: return false
                val opt = payload["opt"]?.jsonPrimitive?.contentOrNull
                opt == "login"
            }
            "user_thing_list_get" -> {
                if (root.keys != setOf("opt")) return false
                val opt = root["opt"]?.jsonPrimitive?.contentOrNull
                opt == "user_thing_list_get"
            }
            else -> false
        }
    }
}

sealed interface PilotAuthProbeOutcome {
    data class Authenticated(val session: SessionCredentials) : PilotAuthProbeOutcome
    data class QuarantinedDynamicOrigins(val summary: ObservedOriginSummary) : PilotAuthProbeOutcome
    data class Failed(
        val reason: AuthFailureReason,
        val safeMessage: String,
        val isTlsFailure: Boolean = false
    ) : PilotAuthProbeOutcome
}

/**
 * Capability-restricted auth gateway for bounded FCT-004 pilot execution (`SEC-402`, `SEC-403`, `SEC-406`).
 * - Permits ONLY `"login"` requests via [PilotHttpOperationGuard].
 * - Uses [Utf8TlsDefault] exclusively (zero MD5 fallback).
 * - Quarantines unpinned dynamic origins without exposing tokens or publishing an authenticated session.
 */
class PilotCapabilityRestrictedAuthGateway(
    private val router: RegionEndpointRouter,
    private val trustPolicy: EndpointTrustPolicy,
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val envelopeConfig: AuthEnvelopeConfig = AuthEnvelopeConfig(),
    private val digestStrategy: PasswordDigestStrategy = Utf8TlsDefault,
    baseClient: OkHttpClient = OkHttpSecurityGuard.buildHardenedClient(trustPolicy),
    private val pilotEndpointTrustPolicy: AuthorizedPilotEndpointTrustPolicy? = trustPolicy as? AuthorizedPilotEndpointTrustPolicy
) : AuthGateway {

    private val client: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(
            baseClient.callTimeoutMillis.takeIf { it in 1..15_000 }?.toLong() ?: 15_000L,
            TimeUnit.MILLISECONDS
        )
        .build()

    private val lock = Any()
    private var lastQuarantinedObservation: ObservedOriginSummary? = null
    private var lastFailureIsTls: Boolean = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class LoginRequestEnvelope(
        val header: Header,
        val payload: Payload
    ) {
        @Serializable
        data class Header(
            @SerialName("api_version") val apiVersion: String,
            @SerialName("app_name") val appName: String,
            @SerialName("calling_code") val callingCode: String,
            val account: String,
            @SerialName("client_id") val clientId: String,
            val language: String
        )

        @Serializable
        data class Payload(
            val opt: String,
            val pwd: String
        )
    }

    @Serializable
    private data class LoginResponseProbe(
        val code: Int? = null,
        val msg: String? = null,
        val captcha: Boolean? = null,
        val data: IdentityProbe? = null
    ) {
        @Serializable
        data class IdentityProbe(
            @SerialName("api_url") val apiUrl: String? = null,
            @SerialName("wss_url") val wssUrl: String? = null,
            @SerialName("region_name") val regionName: String? = null,
            @SerialName("jwt_token") val jwtToken: String? = null,
            @SerialName("expired_time") val expiredTime: Long = 0L
        )
    }

    fun consumeLastQuarantinedObservation(): ObservedOriginSummary? = synchronized(lock) {
        lastQuarantinedObservation.also { lastQuarantinedObservation = null }
    }

    fun consumeLastTlsFailureFlag(): Boolean = synchronized(lock) {
        lastFailureIsTls.also { lastFailureIsTls = false }
    }

    override suspend fun signIn(credentials: AuthCredentials): AuthResult {
        return when (val outcome = probeLogin(credentials)) {
            is PilotAuthProbeOutcome.Authenticated -> AuthResult.Success(outcome.session)
            is PilotAuthProbeOutcome.QuarantinedDynamicOrigins -> {
                synchronized(lock) {
                    lastQuarantinedObservation = outcome.summary
                }
                AuthResult.Failure(
                    AuthFailureReason.UntrustedEndpoint,
                    "Dynamic origin quarantined pending provenance verification"
                )
            }
            is PilotAuthProbeOutcome.Failed -> {
                if (outcome.isTlsFailure) {
                    synchronized(lock) { lastFailureIsTls = true }
                }
                AuthResult.Failure(outcome.reason, outcome.safeMessage)
            }
        }
    }

    suspend fun probeLogin(credentials: AuthCredentials): PilotAuthProbeOutcome {
        synchronized(lock) {
            lastQuarantinedObservation = null
            lastFailureIsTls = false
        }
        val secretChars = credentials.copySecretChars()
        if (credentials.account.isBlank() || secretChars.isEmpty()) {
            Arrays.fill(secretChars, '\u0000')
            return PilotAuthProbeOutcome.Failed(AuthFailureReason.InvalidCredentials, "Account and secret must not be blank")
        }

        val resolution = router.resolve(credentials.regionCallingCode)
        if (resolution is RegionResolution.Rejected) {
            Arrays.fill(secretChars, '\u0000')
            return PilotAuthProbeOutcome.Failed(resolution.reason, resolution.safeMessage)
        }
        val endpointUrl = (resolution as RegionResolution.Resolved).endpointUrl

        val authDecision = if (trustPolicy is AuthorizedPilotEndpointTrustPolicy) {
            trustPolicy.authorizeAuthEndpoint(endpointUrl)
        } else {
            trustPolicy.authorize(endpointUrl)
        }
        if (authDecision is EndpointTrustDecision.Untrusted) {
            Arrays.fill(secretChars, '\u0000')
            logger.log(DiagnosticEvent(category = "PILOT_AUTH_BLOCKED", opt = "login", errorCategory = authDecision.safeCategory))
            return PilotAuthProbeOutcome.Failed(authDecision.reason, "Auth endpoint rejected by pilot trust policy")
        }

        val requestJson = try {
            val encodedSecret = digestStrategy.encodeForTlsPayload(secretChars)
            val envelope = LoginRequestEnvelope(
                header = LoginRequestEnvelope.Header(
                    apiVersion = envelopeConfig.protocolApiVersion,
                    appName = envelopeConfig.appName,
                    callingCode = resolution.normalizedCallingCode,
                    account = credentials.account.trim(),
                    clientId = envelopeConfig.clientId,
                    language = envelopeConfig.languageTag
                ),
                payload = LoginRequestEnvelope.Payload(
                    opt = "login",
                    pwd = encodedSecret
                )
            )
            json.encodeToString(envelope)
        } finally {
            Arrays.fill(secretChars, '\u0000')
        }

        if (!PilotHttpOperationGuard.validateOperationPayload("login", requestJson)) {
            return PilotAuthProbeOutcome.Failed(AuthFailureReason.ConfigurationError, "Blocked non-login payload")
        }

        val startedMs = authClock.elapsedRealtimeMs()
        return withContext(dispatchers.io) {
            try {
                val request = Request.Builder()
                    .url(endpointUrl)
                    .post(requestJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = (authClock.elapsedRealtimeMs() - startedMs).coerceAtLeast(0L)
                    logger.log(
                        DiagnosticEvent(
                            category = "PILOT_AUTH_RESPONSE",
                            opt = "login",
                            httpStatus = response.code,
                            latencyMs = latency,
                            safeHostPath = request.url.encodedPath
                        )
                    )
                    when {
                        response.code in 300..399 ->
                            PilotAuthProbeOutcome.Failed(AuthFailureReason.RedirectRejected, "Redirect blocked by pilot policy")
                        response.code == 401 || response.code == 403 ->
                            PilotAuthProbeOutcome.Failed(AuthFailureReason.InvalidCredentials, "Server denied credentials (${response.code})")
                        response.code !in 200..299 ->
                            PilotAuthProbeOutcome.Failed(
                                AuthFailureReason.ServerRejected(response.code, null, "HTTP_${response.code}"),
                                "Server rejected login (${response.code})"
                            )
                        else -> evaluateProbeBody(readBoundedBody(response), credentials.account)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: SSLHandshakeException) {
                PilotAuthProbeOutcome.Failed(AuthFailureReason.UntrustedEndpoint, "TLS handshake failed", isTlsFailure = true)
            } catch (_: SocketTimeoutException) {
                PilotAuthProbeOutcome.Failed(AuthFailureReason.Timeout, "Authentication request timed out")
            } catch (_: IOException) {
                PilotAuthProbeOutcome.Failed(AuthFailureReason.NetworkUnavailable, "Authentication network error")
            } catch (_: Throwable) {
                PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Unexpected auth probe failure")
            }
        }
    }

    private fun evaluateProbeBody(rawBody: String, account: String): PilotAuthProbeOutcome {
        if (rawBody.isBlank()) {
            return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Empty auth response")
        }
        val parsed = runCatching { json.decodeFromString<LoginResponseProbe>(rawBody) }.getOrNull()
            ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Malformed auth JSON")

        val msgLower = parsed.msg?.lowercase().orEmpty()
        val isCaptcha = parsed.captcha == true ||
            msgLower.contains("captcha") ||
            msgLower.contains("get_image_captcha") ||
            msgLower.contains("get_verify_code_captcha")
        if (isCaptcha) {
            return PilotAuthProbeOutcome.Failed(
                AuthFailureReason.ChallengeRequired,
                "Server requested verification challenge; automated bypass prohibited"
            )
        }

        val code = parsed.code ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing response code")
        if (code != 1) {
            return PilotAuthProbeOutcome.Failed(
                AuthFailureReason.ServerRejected(200, code, "SERVER_CODE_$code"),
                "Observed server auth rejection (code=$code; UTF-8 non-MD5)"
            )
        }

        val data = parsed.data ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing identity data")
        val jwt = data.jwtToken?.takeIf { it.isNotBlank() }
            ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing jwt_token")
        val apiUrl = data.apiUrl?.takeIf { it.isNotBlank() }
            ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing api_url")
        val wssUrl = data.wssUrl?.takeIf { it.isNotBlank() }
            ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing wss_url")
        val regionName = data.regionName?.takeIf { it.isNotBlank() }
            ?: return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Missing region_name")
        if (data.expiredTime <= 0L || data.expiredTime > Long.MAX_VALUE / 1_000L) {
            return PilotAuthProbeOutcome.Failed(AuthFailureReason.MalformedResponse, "Invalid expired_time")
        }

        // Check explicit trust policy first; if unapproved, evaluate via pilot quarantine policy
        val directlyTrusted = trustPolicy.authorize(apiUrl) is EndpointTrustDecision.Trusted &&
            trustPolicy.authorize(wssUrl) is EndpointTrustDecision.Trusted
        if (!directlyTrusted) {
            val pilotTrust = pilotEndpointTrustPolicy
            if (pilotTrust != null) {
                when (val dyn = pilotTrust.evaluateDynamicEndpoints(apiUrl, wssUrl)) {
                    is DynamicOriginEvaluation.Rejected -> {
                        return PilotAuthProbeOutcome.Failed(AuthFailureReason.UntrustedEndpoint, "Dynamic endpoint rejected (${dyn.safeCategory})")
                    }
                    is DynamicOriginEvaluation.Quarantined -> {
                        val apiOriginStr = "${dyn.apiOrigin.scheme}://${dyn.apiOrigin.host}:${dyn.apiOrigin.port}"
                        val wssOriginStr = "${dyn.wssOrigin.scheme}://${dyn.wssOrigin.host}:${dyn.wssOrigin.port}"
                        return PilotAuthProbeOutcome.QuarantinedDynamicOrigins(
                            ObservedOriginSummary(
                                apiOrigin = apiOriginStr,
                                wssOrigin = wssOriginStr,
                                provenanceVerified = false,
                                quarantineReason = dyn.reason
                            )
                        )
                    }
                    is DynamicOriginEvaluation.Approved -> Unit
                }
            } else {
                return PilotAuthProbeOutcome.Failed(AuthFailureReason.UntrustedEndpoint, "Returned endpoint failed trust policy")
            }
        }

        val trimmedAccount = account.trim()
        val atIdx = trimmedAccount.indexOf('@')
        val alias = when {
            trimmedAccount.isEmpty() -> "***"
            atIdx > 1 -> "${trimmedAccount.first()}***${trimmedAccount.substring(atIdx)}"
            else -> "${trimmedAccount.first()}***"
        }

        return PilotAuthProbeOutcome.Authenticated(
            SessionCredentials(
                jwtToken = jwt,
                apiUrl = apiUrl,
                wssUrl = wssUrl,
                regionName = regionName,
                expiresInSeconds = data.expiredTime,
                issuedAtElapsedMs = authClock.elapsedRealtimeMs(),
                priorElapsedMs = 0L,
                accountAliasRedacted = alias
            )
        )
    }

    private fun readBoundedBody(response: okhttp3.Response): String {
        val peek = response.peekBody(MAX_RESPONSE_BYTES + 1L)
        val bytes = peek.bytes()
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw IllegalArgumentException("Response body exceeds bounded limit")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1_048_576
    }
}

/**
 * Capability-restricted inventory gateway permitting ONLY `"user_thing_list_get"` (`SEC-402`).
 */
class PilotCapabilityRestrictedInventoryGateway(
    private val delegate: OkHttpDeviceInventoryGateway
) : DeviceInventoryGateway {

    override suspend fun fetchThings(session: SessionCredentials): InventoryFetchResult {
        val canonicalPayload = """{"opt":"user_thing_list_get"}"""
        if (!PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", canonicalPayload)) {
            return InventoryFetchResult.Failure(ConnectivityError.AuthorizationDenied)
        }
        return delegate.fetchThings(session)
    }

    suspend fun executeRawPayload(session: SessionCredentials, rawJson: String): InventoryFetchResult {
        if (!PilotHttpOperationGuard.validateOperationPayload("user_thing_list_get", rawJson)) {
            return InventoryFetchResult.Failure(ConnectivityError.AuthorizationDenied)
        }
        return delegate.fetchThings(session)
    }
}

/**
 * Capability-restricted WebSocket transport boundary (`SEC-402`).
 * Blocks ALL outbound application-level text and binary frames while preserving RFC 6455
 * control frames (Ping/Pong/Close) handled by OkHttp internally.
 */
class PilotReadOnlyWebSocketTransport(
    private val delegate: OkHttpWebSocketTransport,
    private val onUnexpectedOutboundAttempt: (String) -> Unit = {}
) : PilotReadOnlyTelemetryTransport, RealtimeSessionTransport {

    override val connectionState: StateFlow<ConnectionState> = delegate.connectionState
    override val robotState: StateFlow<RobotState> = delegate.robotState

    override fun connect(session: SessionCredentials, epoch: Long) {
        delegate.connect(session, epoch)
    }

    override fun selectDevice(item: DeviceInventoryItem?, epoch: Long) {
        delegate.selectDevice(item, epoch)
    }

    override fun invalidateForEpoch(expectedEpoch: Long, reason: ConnectivityError) {
        delegate.invalidateForEpoch(expectedEpoch, reason)
    }

    override fun forceDisconnect(reason: ConnectivityError) {
        delegate.forceInvalidate(reason)
    }

    override fun currentGeneration(): Long = delegate.currentGeneration()

    override fun sendOutbound(payload: String, epoch: Long): Boolean {
        onUnexpectedOutboundAttempt("TEXT_FRAME_BLOCKED")
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendBinaryOutbound(bytes: ByteArray, epoch: Long): Boolean {
        onUnexpectedOutboundAttempt("BINARY_FRAME_BLOCKED")
        return false
    }

    override fun close() {
        delegate.close()
    }
}
