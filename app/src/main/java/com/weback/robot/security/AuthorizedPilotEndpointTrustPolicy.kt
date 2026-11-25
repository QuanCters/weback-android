package com.weback.robot.security

import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.pilot.PilotAuthorizationGate
import com.weback.robot.domain.pilot.PilotEndpointConfig
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Explicit IPv4 and IPv6 special-use address filter (`SEC-403`).
 * Inspects raw 4-byte / 16-byte addresses directly (including unwrapping IPv4-mapped IPv6 `::ffff:0:0/96`).
 * Note: Passing this filter confirms an address is outside known IANA special-use registries;
 * it does NOT by itself prove external internet routability.
 */
object IpAddressPolicy {

    fun isGloballyRoutableUnicast(address: InetAddress): Boolean {
        val raw = address.address
        return when (raw.size) {
            4 -> isGloballyRoutableIpv4(raw)
            16 -> isGloballyRoutableIpv6(raw)
            else -> false
        }
    }

    fun validateAll(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all { isGloballyRoutableUnicast(it) }

    private fun isGloballyRoutableIpv4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        val o2 = b[2].toInt() and 0xFF
        return when {
            o0 == 0 -> false                                         // 0.0.0.0/8 ("this network")
            o0 == 10 -> false                                        // 10.0.0.0/8 (RFC 1918)
            o0 == 100 && o1 in 64..127 -> false                      // 100.64.0.0/10 (CGNAT RFC 6598)
            o0 == 127 -> false                                       // 127.0.0.0/8 (loopback)
            o0 == 169 && o1 == 254 -> false                          // 169.254.0.0/16 (link-local & cloud metadata)
            o0 == 172 && o1 in 16..31 -> false                       // 172.16.0.0/12 (RFC 1918)
            o0 == 192 && o1 == 0 && o2 == 0 -> false                 // 192.0.0.0/24 (IETF protocol assignments)
            o0 == 192 && o1 == 0 && o2 == 2 -> false                 // 192.0.2.0/24 (TEST-NET-1)
            o0 == 192 && o1 == 88 && o2 == 99 -> false               // 192.88.99.0/24 (6to4 relay anycast)
            o0 == 192 && o1 == 168 -> false                          // 192.168.0.0/16 (RFC 1918)
            o0 == 198 && o1 in 18..19 -> false                       // 198.18.0.0/15 (benchmarking)
            o0 == 198 && o1 == 51 && o2 == 100 -> false              // 198.51.100.0/24 (TEST-NET-2)
            o0 == 203 && o1 == 0 && o2 == 113 -> false               // 203.0.113.0/24 (TEST-NET-3)
            o0 in 224..239 -> false                                  // 224.0.0.0/4 (multicast)
            o0 >= 240 -> false                                       // 240.0.0.0/4 (reserved & broadcast)
            else -> true
        }
    }

    private fun isGloballyRoutableIpv6(b: ByteArray): Boolean {
        // IPv4-mapped IPv6 ::ffff:0:0/96 -> validate embedded IPv4 address
        if (isIpv4MappedPrefix(b)) {
            return isGloballyRoutableIpv4(b.copyOfRange(12, 16))
        }
        // ::/96 (unspecified ::/128, ::1/128 loopback, deprecated IPv4-compatible)
        if ((0..11).all { b[it].toInt() == 0 }) return false

        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        val b2 = b[2].toInt() and 0xFF
        val b3 = b[3].toInt() and 0xFF
        val b4 = b[4].toInt() and 0xFF
        val b5 = b[5].toInt() and 0xFF

        return when {
            // 64:ff9b::/96 & 64:ff9b:1::/48 (NAT64)
            b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B -> false
            // 100::/64 (discard-only)
            b0 == 0x01 && b1 == 0x00 && b2 == 0x00 && b3 == 0x00 &&
                b4 == 0x00 && b5 == 0x00 && (b[6].toInt() == 0) && (b[7].toInt() == 0) -> false
            // 2001::/32 (Teredo)
            b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00 -> false
            // 2001:2::/48 (benchmarking)
            b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x02 && b4 == 0x00 && b5 == 0x00 -> false
            // 2001:db8::/32 (documentation)
            b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8 -> false
            // 2002::/16 (6to4)
            b0 == 0x20 && b1 == 0x02 -> false
            // fc00::/7 (ULA, including fd00::/8 & cloud metadata fd00:ec2::254)
            (b0 and 0xFE) == 0xFC -> false
            // fe80::/10 (link-local)
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> false
            // fec0::/10 (deprecated site-local)
            b0 == 0xFE && (b1 and 0xC0) == 0xC0 -> false
            // ff00::/8 (multicast)
            b0 == 0xFF -> false
            else -> true
        }
    }

    private fun isIpv4MappedPrefix(b: ByteArray): Boolean =
        (0..9).all { b[it].toInt() == 0 } &&
            (b[10].toInt() and 0xFF) == 0xFF &&
            (b[11].toInt() and 0xFF) == 0xFF
}

sealed interface OriginProvenanceResult {
    data class Verified(val provenanceRef: String) : OriginProvenanceResult
    data class Unverified(val reason: String) : OriginProvenanceResult
}

fun interface OriginProvenanceVerifier {
    fun verify(
        apiOrigin: UriValidator.NormalizedOrigin,
        wssOrigin: UriValidator.NormalizedOrigin
    ): OriginProvenanceResult
}

sealed interface DynamicOriginEvaluation {
    data class Approved(
        val apiOrigin: UriValidator.NormalizedOrigin,
        val wssOrigin: UriValidator.NormalizedOrigin
    ) : DynamicOriginEvaluation

    data class Quarantined(
        val apiOrigin: UriValidator.NormalizedOrigin,
        val wssOrigin: UriValidator.NormalizedOrigin,
        val reason: String
    ) : DynamicOriginEvaluation

    data class Rejected(val safeCategory: String) : DynamicOriginEvaluation
}

sealed interface DynamicOriginConfirmationResult {
    data class Accepted(
        val apiOrigin: UriValidator.NormalizedOrigin,
        val wssOrigin: UriValidator.NormalizedOrigin,
        val provenanceRef: String
    ) : DynamicOriginConfirmationResult

    data class Rejected(val safeCategory: String) : DynamicOriginConfirmationResult
}

/**
 * Pilot endpoint trust policy enforcing exact auth origin + `/prod/oauth`, `:443` `https`/`wss` only,
 * quarantined unpinned dynamic origin handling (requiring verified provenance + explicit authorization),
 * and explicit IPv4/IPv6 special-use address isolation (`SEC-403`).
 */
class AuthorizedPilotEndpointTrustPolicy(
    endpointConfig: PilotEndpointConfig,
    private val allowedAuthOrigins: Set<String> = PilotAuthorizationGate.CANONICAL_AUTH_ORIGINS,
    private val allowLoopbackForHermeticTests: Boolean = false
) : EndpointTrustPolicy {

    private val lock = Any()
    private var activeEndpointConfig: PilotEndpointConfig = endpointConfig
    private var approvedAuthOrigin: UriValidator.NormalizedOrigin? = parseApprovedOrigin(endpointConfig.approvedAuthOrigin, "https")
    private var pinnedApiOrigin: UriValidator.NormalizedOrigin? = endpointConfig.pinnedApiOrigin?.let { parseApprovedOrigin(it, "https") }
    private var pinnedWssOrigin: UriValidator.NormalizedOrigin? = endpointConfig.pinnedWssOrigin?.let { parseApprovedOrigin(it, "wss") }
    private var confirmedApiOrigin: UriValidator.NormalizedOrigin? = null
    private var confirmedWssOrigin: UriValidator.NormalizedOrigin? = null

    fun updateEndpointConfig(newConfig: PilotEndpointConfig) {
        synchronized(lock) {
            activeEndpointConfig = newConfig
            approvedAuthOrigin = parseApprovedOrigin(newConfig.approvedAuthOrigin, "https")
            pinnedApiOrigin = newConfig.pinnedApiOrigin?.let { parseApprovedOrigin(it, "https") }
            pinnedWssOrigin = newConfig.pinnedWssOrigin?.let { parseApprovedOrigin(it, "wss") }
            confirmedApiOrigin = null
            confirmedWssOrigin = null
        }
    }

    fun authorizeAuthEndpoint(url: String): EndpointTrustDecision {
        val (expectedAuth, expectedPath) = synchronized(lock) {
            approvedAuthOrigin to activeEndpointConfig.approvedAuthPath
        }
        expectedAuth
            ?: return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "INVALID_AUTH_CONFIG")
        val canonicalExpected = "${expectedAuth.scheme}://${expectedAuth.host}:${expectedAuth.port}"
        if (canonicalExpected !in allowedAuthOrigins) {
            return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "UNAPPROVED_AUTH_ORIGIN")
        }

        val validated = validatePilotUri(url, expectedScheme = "https")
            ?: return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "INVALID_AUTH_URI")
        if (validated.origin != expectedAuth) {
            return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "AUTH_ORIGIN_MISMATCH")
        }
        val path = validated.normalizedUri.rawPath
        if (path != expectedPath) {
            return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "AUTH_PATH_MISMATCH")
        }
        return EndpointTrustDecision.Trusted(validated.origin)
    }

    override fun authorize(url: String): EndpointTrustDecision {
        val validated = validatePilotUri(url, expectedScheme = null)
            ?: return EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "INVALID_PILOT_URI")

        val origin = validated.origin
        val (expectedAuth, expectedPath, pinnedApi, pinnedWss, confirmedApi, confirmedWss) = synchronized(lock) {
            TrustSnapshot(
                approvedAuthOrigin,
                activeEndpointConfig.approvedAuthPath,
                pinnedApiOrigin,
                pinnedWssOrigin,
                confirmedApiOrigin,
                confirmedWssOrigin
            )
        }
        if (expectedAuth != null && origin == expectedAuth && validated.normalizedUri.rawPath == expectedPath) {
            return authorizeAuthEndpoint(url)
        }

        val isTrusted = when (origin.scheme) {
            "https" -> origin == pinnedApi || (confirmedApi != null && origin == confirmedApi)
            "wss" -> origin == pinnedWss || (confirmedWss != null && origin == confirmedWss)
            else -> false
        }
        return if (isTrusted) {
            EndpointTrustDecision.Trusted(origin)
        } else {
            EndpointTrustDecision.Untrusted(AuthFailureReason.UntrustedEndpoint, "UNAPPROVED_OR_QUARANTINED_ORIGIN")
        }
    }

    fun evaluateDynamicEndpoints(apiUrl: String, wssUrl: String): DynamicOriginEvaluation {
        val apiValid = validatePilotUri(apiUrl, expectedScheme = "https")
            ?: return DynamicOriginEvaluation.Rejected("INVALID_API_ORIGIN")
        val wssValid = validatePilotUri(wssUrl, expectedScheme = "wss")
            ?: return DynamicOriginEvaluation.Rejected("INVALID_WSS_ORIGIN")

        val (cfg, pinnedApi, pinnedWss, confirmedApi, confirmedWss) = synchronized(lock) {
            DynamicSnap(
                activeEndpointConfig,
                pinnedApiOrigin,
                pinnedWssOrigin,
                confirmedApiOrigin,
                confirmedWssOrigin
            )
        }
        val hasPinnedConfig = cfg.pinnedApiOrigin != null || cfg.pinnedWssOrigin != null
        if (hasPinnedConfig) {
            if ((cfg.pinnedApiOrigin != null && pinnedApi == null) ||
                (cfg.pinnedWssOrigin != null && pinnedWss == null)
            ) {
                return DynamicOriginEvaluation.Rejected("INVALID_PINNED_ORIGIN_CONFIG")
            }
            return if (apiValid.origin == pinnedApi && wssValid.origin == pinnedWss) {
                DynamicOriginEvaluation.Approved(apiValid.origin, wssValid.origin)
            } else {
                DynamicOriginEvaluation.Rejected("PINNED_DYNAMIC_ORIGIN_MISMATCH")
            }
        }

        if (confirmedApi == apiValid.origin && confirmedWss == wssValid.origin) {
            return DynamicOriginEvaluation.Approved(apiValid.origin, wssValid.origin)
        }

        return DynamicOriginEvaluation.Quarantined(
            apiOrigin = apiValid.origin,
            wssOrigin = wssValid.origin,
            reason = "UNPINNED_DYNAMIC_ORIGIN_REQUIRES_PROVENANCE_AND_AUTH"
        )
    }

    private data class DynamicSnap(
        val config: PilotEndpointConfig,
        val pinnedApi: UriValidator.NormalizedOrigin?,
        val pinnedWss: UriValidator.NormalizedOrigin?,
        val confirmedApi: UriValidator.NormalizedOrigin?,
        val confirmedWss: UriValidator.NormalizedOrigin?
    )

    private data class TrustSnapshot(
        val authOrigin: UriValidator.NormalizedOrigin?,
        val authPath: String,
        val pinnedApi: UriValidator.NormalizedOrigin?,
        val pinnedWss: UriValidator.NormalizedOrigin?,
        val confirmedApi: UriValidator.NormalizedOrigin?,
        val confirmedWss: UriValidator.NormalizedOrigin?
    )

    fun confirmDynamicOrigins(
        apiUrl: String,
        wssUrl: String,
        humanConfirmed: Boolean,
        provenanceVerifier: OriginProvenanceVerifier
    ): DynamicOriginConfirmationResult {
        if (!humanConfirmed) {
            return DynamicOriginConfirmationResult.Rejected("OPERATOR_CONFIRMATION_MISSING")
        }
        val apiValid = validatePilotUri(apiUrl, expectedScheme = "https")
            ?: return DynamicOriginConfirmationResult.Rejected("INVALID_API_ORIGIN")
        val wssValid = validatePilotUri(wssUrl, expectedScheme = "wss")
            ?: return DynamicOriginConfirmationResult.Rejected("INVALID_WSS_ORIGIN")

        // Human confirmation alone is never sufficient; require verified provenance (`SEC-403`)
        return when (val prov = provenanceVerifier.verify(apiValid.origin, wssValid.origin)) {
            is OriginProvenanceResult.Unverified ->
                DynamicOriginConfirmationResult.Rejected("PROVENANCE_UNVERIFIED:${prov.reason}")
            is OriginProvenanceResult.Verified -> {
                synchronized(lock) {
                    confirmedApiOrigin = apiValid.origin
                    confirmedWssOrigin = wssValid.origin
                }
                DynamicOriginConfirmationResult.Accepted(apiValid.origin, wssValid.origin, prov.provenanceRef)
            }
        }
    }

    fun clearConfirmedDynamicOrigins() {
        synchronized(lock) {
            confirmedApiOrigin = null
            confirmedWssOrigin = null
        }
    }

    override fun validateResolvedAddresses(host: String, addresses: List<InetAddress>): Boolean {
        if (addresses.isEmpty()) return false
        val canonicalHost = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (allowLoopbackForHermeticTests && TestLoopbackTrustPolicy.isLoopbackHost(canonicalHost)) {
            return addresses.all { it.isLoopbackAddress }
        }
        return IpAddressPolicy.validateAll(addresses)
    }

    fun validateSocketDestination(address: InetAddress): Boolean {
        if (allowLoopbackForHermeticTests && address.isLoopbackAddress) {
            return true
        }
        return IpAddressPolicy.isGloballyRoutableUnicast(address)
    }

    override fun proxy(): Proxy = Proxy.NO_PROXY

    private fun parseApprovedOrigin(rawOrigin: String, expectedScheme: String): UriValidator.NormalizedOrigin? {
        val res = validatePilotUri(rawOrigin, expectedScheme) ?: return null
        return res.origin
    }

    private fun validatePilotUri(rawUrl: String, expectedScheme: String?): UriValidator.UriValidationResult.Valid? {
        val result = UriValidator.validateEndpoint(rawUrl, allowLoopbackCleartext = false)
        val valid = result as? UriValidator.UriValidationResult.Valid ?: return null
        val scheme = valid.origin.scheme
        if (expectedScheme != null && scheme != expectedScheme) return null
        if (scheme != "https" && scheme != "wss") return null

        val isLoopbackAllowed = allowLoopbackForHermeticTests && TestLoopbackTrustPolicy.isLoopbackHost(valid.origin.host)
        if (!isLoopbackAllowed && valid.origin.port != 443) {
            return null
        }
        // Reject raw special-use IP literals directly in the URI host
        val host = valid.origin.host
        if (!isLoopbackAllowed && looksLikeIpLiteral(host)) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            if (!IpAddressPolicy.isGloballyRoutableUnicast(addr)) return null
        }
        return valid
    }

    private fun looksLikeIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        return host.all { it.isDigit() || it == '.' }
    }
}

class PilotActiveCallRegistry {
    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())

    fun register(call: Call) {
        activeCalls.add(call)
    }

    fun unregister(call: Call) {
        activeCalls.remove(call)
    }

    fun cancelAll() {
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
    }
}

/**
 * Builds hardened pilot [OkHttpClient] instances (`SEC-403`, `SEC-404`):
 * - Standard platform TLS & hostname checks (zero custom SSL overrides)
 * - `followRedirects = false`, `followSslRedirects = false`, `retryOnConnectionFailure = false`
 * - `Proxy.NO_PROXY`
 * - `callTimeout <= 15_000ms`
 * - Resolved DNS AND connected socket destination IP validation
 */
object PilotClientFactory {

    fun buildPilotOkHttpClient(
        trustPolicy: AuthorizedPilotEndpointTrustPolicy,
        baseDns: Dns = Dns.SYSTEM,
        callTimeoutMs: Long = 15_000L,
        activeCallRegistry: PilotActiveCallRegistry? = null
    ): OkHttpClient {
        val boundedCallTimeout = callTimeoutMs.coerceIn(1L, 15_000L)
        val guardedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val resolved = baseDns.lookup(hostname)
                if (!trustPolicy.validateResolvedAddresses(hostname, resolved)) {
                    throw UnknownHostException("Resolved addresses failed pilot IP isolation policy for $hostname")
                }
                return resolved
            }
        }

        val eventListenerFactory = EventListener.Factory {
            object : EventListener() {
                override fun callStart(call: Call) {
                    activeCallRegistry?.register(call)
                }

                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    val dest = inetSocketAddress.address
                    if (dest == null || !trustPolicy.validateSocketDestination(dest)) {
                        call.cancel()
                        throw IOException("Blocked non-global socket destination in pilot mode")
                    }
                }

                override fun callEnd(call: Call) {
                    activeCallRegistry?.unregister(call)
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    activeCallRegistry?.unregister(call)
                }
            }
        }

        return OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(trustPolicy.proxy())
            .dns(guardedDns)
            .eventListenerFactory(eventListenerFactory)
            .callTimeout(boundedCallTimeout, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(5_000L, boundedCallTimeout), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(10_000L, boundedCallTimeout), TimeUnit.MILLISECONDS)
            .writeTimeout(minOf(10_000L, boundedCallTimeout), TimeUnit.MILLISECONDS)
            .build()
    }
}
