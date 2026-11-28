package com.weback.robot.security

import com.weback.robot.domain.model.AuthFailureReason
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed interface EndpointTrustDecision {
    data class Trusted(val origin: UriValidator.NormalizedOrigin) : EndpointTrustDecision
    data class Untrusted(
        val reason: AuthFailureReason,
        val safeCategory: String
    ) : EndpointTrustDecision
}

/**
 * Explicit endpoint authorization contract checked prior to credential attachment,
 * DNS resolution, and network transport I/O (`SEC-301`, `IC-303`).
 */
interface EndpointTrustPolicy {
    fun authorize(url: String): EndpointTrustDecision
    fun validateResolvedAddresses(host: String, addresses: List<InetAddress>): Boolean
    fun proxy(): Proxy?
}

/**
 * Production endpoint trust remains DISABLED throughout FCT-003 until explicit FCT-004
 * hardware/cloud approval (`SEC-301`). No speculative production allowlists are present.
 */
object ProductionEndpointTrustPolicy : EndpointTrustPolicy {
    override fun authorize(url: String): EndpointTrustDecision =
        EndpointTrustDecision.Untrusted(
            reason = AuthFailureReason.UntrustedEndpoint,
            safeCategory = "PRODUCTION_TRUST_DISABLED_FCT003"
        )

    override fun validateResolvedAddresses(host: String, addresses: List<InetAddress>): Boolean = false

    override fun proxy(): Proxy = Proxy.NO_PROXY
}

/**
 * Hermetic test-only trust policy binding clients strictly to explicitly configured
 * loopback origins, enforcing `Proxy.NO_PROXY` and denying any external DNS/IP resolution.
 */
class TestLoopbackTrustPolicy(
    allowedOrigins: Set<UriValidator.NormalizedOrigin>,
    private val allowHttpLoopback: Boolean = true
) : EndpointTrustPolicy {

    private val approvedOrigins: Set<UriValidator.NormalizedOrigin>

    init {
        require(allowedOrigins.isNotEmpty()) { "TestLoopbackTrustPolicy requires at least one loopback origin" }
        allowedOrigins.forEach { origin ->
            require(isLoopbackHost(origin.host)) {
                "TestLoopbackTrustPolicy permits loopback hosts only, got ${origin.host}"
            }
        }
        approvedOrigins = allowedOrigins.toSet()
    }

    override fun authorize(url: String): EndpointTrustDecision {
        return when (val validated = UriValidator.validateEndpoint(url, allowLoopbackCleartext = allowHttpLoopback)) {
            is UriValidator.UriValidationResult.Rejected -> {
                EndpointTrustDecision.Untrusted(
                    reason = AuthFailureReason.UntrustedEndpoint,
                    safeCategory = validated.safeCategory
                )
            }
            is UriValidator.UriValidationResult.Valid -> {
                if (validated.origin in approvedOrigins) {
                    EndpointTrustDecision.Trusted(validated.origin)
                } else {
                    EndpointTrustDecision.Untrusted(
                        reason = AuthFailureReason.UntrustedEndpoint,
                        safeCategory = "UNAPPROVED_ORIGIN"
                    )
                }
            }
        }
    }

    override fun validateResolvedAddresses(host: String, addresses: List<InetAddress>): Boolean {
        val canonicalHost = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (!isLoopbackHost(canonicalHost) || addresses.isEmpty()) return false
        return addresses.all { it.isLoopbackAddress }
    }

    override fun proxy(): Proxy = Proxy.NO_PROXY

    companion object {
        fun isLoopbackHost(host: String): Boolean {
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            return h == "127.0.0.1" || h == "localhost" || h == "::1"
        }
    }
}

/**
 * Builds hardened OkHttp clients with automatic redirects disabled, explicit proxy isolation,
 * loopback DNS enforcement, and platform standard TLS verification (no global SSL mutations).
 */
object OkHttpSecurityGuard {
    fun buildHardenedClient(
        trustPolicy: EndpointTrustPolicy,
        connectTimeoutMs: Long = 5_000L,
        readTimeoutMs: Long = 5_000L,
        writeTimeoutMs: Long = 5_000L
    ): OkHttpClient {
        val guardedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val canonical = hostname.trim().lowercase()
                if (trustPolicy === ProductionEndpointTrustPolicy) {
                    throw UnknownHostException("Production endpoint policy disabled in FCT-003")
                }
                if (trustPolicy is TestLoopbackTrustPolicy && !TestLoopbackTrustPolicy.isLoopbackHost(canonical)) {
                    throw UnknownHostException("Blocked non-loopback DNS lookup in offline test mode: $canonical")
                }
                val resolved = Dns.SYSTEM.lookup(hostname)
                if (!trustPolicy.validateResolvedAddresses(hostname, resolved)) {
                    throw UnknownHostException("Resolved addresses failed EndpointTrustPolicy validation for $canonical")
                }
                return resolved
            }
        }

        return OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(trustPolicy.proxy() ?: Proxy.NO_PROXY)
            .dns(guardedDns)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }
}
