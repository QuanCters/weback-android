package com.weback.robot.protocol.auth

import com.weback.robot.core.AndroidAuthMonotonicClock
import com.weback.robot.core.AuthMonotonicClock
import com.weback.robot.core.DefaultDispatcherProvider
import com.weback.robot.core.DispatcherProvider
import com.weback.robot.domain.model.AuthCredentials
import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.model.AuthResult
import com.weback.robot.domain.model.SessionCredentials
import com.weback.robot.security.DiagnosticEvent
import com.weback.robot.security.DiagnosticLogger
import com.weback.robot.security.EndpointTrustDecision
import com.weback.robot.security.EndpointTrustPolicy
import com.weback.robot.security.NoOpDiagnosticLogger
import com.weback.robot.security.OkHttpSecurityGuard
import com.weback.robot.security.PasswordDigestStrategy
import com.weback.robot.security.Utf8TlsDefault
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
import java.util.Arrays

class OkHttpAuthGateway(
    private val router: RegionEndpointRouter,
    private val trustPolicy: EndpointTrustPolicy,
    private val envelopeConfig: AuthEnvelopeConfig = AuthEnvelopeConfig(),
    private val authClock: AuthMonotonicClock = AndroidAuthMonotonicClock,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider(),
    private val digestStrategy: PasswordDigestStrategy = Utf8TlsDefault,
    private val logger: DiagnosticLogger = NoOpDiagnosticLogger,
    private val client: OkHttpClient = OkHttpSecurityGuard.buildHardenedClient(trustPolicy)
) : AuthGateway {

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
    private data class LoginResponseEnvelope(
        val code: Int,
        val data: IdentityData? = null
    ) {
        @Serializable
        data class IdentityData(
            @SerialName("api_url") val apiUrl: String? = null,
            @SerialName("wss_url") val wssUrl: String? = null,
            @SerialName("region_name") val regionName: String? = null,
            @SerialName("jwt_token") val jwtToken: String? = null,
            @SerialName("expired_time") val expiredTime: Long = 0L
        )
    }

    override suspend fun signIn(credentials: AuthCredentials): AuthResult {
        val secretChars = credentials.copySecretChars()
        if (credentials.account.isBlank() || secretChars.isEmpty()) {
            Arrays.fill(secretChars, '\u0000')
            return AuthResult.Failure(AuthFailureReason.InvalidCredentials, "Account and secret must not be blank")
        }

        val resolution = router.resolve(credentials.regionCallingCode)
        if (resolution is RegionResolution.Rejected) {
            Arrays.fill(secretChars, '\u0000')
            return AuthResult.Failure(resolution.reason, resolution.safeMessage)
        }
        val endpointUrl = (resolution as RegionResolution.Resolved).endpointUrl

        val endpointDecision = trustPolicy.authorize(endpointUrl)
        if (endpointDecision is EndpointTrustDecision.Untrusted) {
            Arrays.fill(secretChars, '\u0000')
            logger.log(
                DiagnosticEvent(
                    category = "AUTH_BLOCKED",
                    opt = "login",
                    errorCategory = endpointDecision.safeCategory
                )
            )
            return AuthResult.Failure(endpointDecision.reason, "Endpoint rejected by trust policy")
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
                            category = "AUTH_RESPONSE",
                            opt = "login",
                            httpStatus = response.code,
                            latencyMs = latency,
                            safeHostPath = request.url.encodedPath
                        )
                    )
                    when {
                        response.code in 300..399 ->
                            AuthResult.Failure(AuthFailureReason.RedirectRejected, "Redirect blocked by security policy")
                        response.code == 401 || response.code == 403 ->
                            AuthResult.Failure(AuthFailureReason.InvalidCredentials, "Authentication denied (${response.code})")
                        response.code !in 200..299 ->
                            AuthResult.Failure(
                                AuthFailureReason.ServerRejected(response.code, null, "HTTP_${response.code}"),
                                "Server rejected login request"
                            )
                        else -> parseSuccessBody(readBoundedBody(response), credentials.account)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: SocketTimeoutException) {
                AuthResult.Failure(AuthFailureReason.Timeout, "Authentication request timed out")
            } catch (_: IOException) {
                AuthResult.Failure(AuthFailureReason.NetworkUnavailable, "Authentication network error")
            } catch (_: Throwable) {
                AuthResult.Failure(AuthFailureReason.MalformedResponse, "Unexpected authentication failure")
            }
        }
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

    private fun parseSuccessBody(bodyText: String, account: String): AuthResult {
        if (bodyText.isBlank()) {
            return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Empty auth response")
        }
        val parsed = try {
            json.decodeFromString<LoginResponseEnvelope>(bodyText)
        } catch (_: Throwable) {
            return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Malformed auth response JSON")
        }

        if (parsed.code != 1) {
            return AuthResult.Failure(
                AuthFailureReason.ServerRejected(200, parsed.code, "SERVER_CODE_${parsed.code}"),
                "Server rejected credentials (code=${parsed.code})"
            )
        }

        val data = parsed.data
            ?: return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Missing auth identity data")
        val jwt = data.jwtToken?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Missing jwt_token")
        val apiUrl = data.apiUrl?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Missing api_url")
        val wssUrl = data.wssUrl?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Missing wss_url")
        val regionName = data.regionName?.takeIf { it.isNotBlank() }
            ?: return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Missing region_name")
        if (data.expiredTime <= 0L || data.expiredTime > Long.MAX_VALUE / 1_000L) {
            return AuthResult.Failure(AuthFailureReason.MalformedResponse, "Invalid expired_time")
        }

        if (trustPolicy.authorize(apiUrl) !is EndpointTrustDecision.Trusted ||
            trustPolicy.authorize(wssUrl) !is EndpointTrustDecision.Trusted
        ) {
            return AuthResult.Failure(AuthFailureReason.UntrustedEndpoint, "Returned service endpoint failed trust policy")
        }

        val alias = redactAccountAlias(account)
        return AuthResult.Success(
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

    private fun redactAccountAlias(rawAccount: String): String {
        val trimmed = rawAccount.trim()
        if (trimmed.isEmpty()) return "***"
        val atIdx = trimmed.indexOf('@')
        return if (atIdx > 1) {
            "${trimmed.first()}***${trimmed.substring(atIdx)}"
        } else {
            "${trimmed.first()}***"
        }
    }
}
