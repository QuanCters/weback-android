package com.weback.robot.security

import com.weback.robot.domain.model.AuthFailureReason
import com.weback.robot.domain.pilot.PilotEndpointConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class AuthorizedPilotEndpointTrustPolicyTest {

    private val cnAndGlobalConfig = PilotEndpointConfig(
        approvedAuthOrigin = "https://user.grit-cloud.com:443",
        approvedAuthPath = "/prod/oauth",
        pinnedApiOrigin = "https://api-us.grit-cloud.com:443",
        pinnedWssOrigin = "wss://wss-us.grit-cloud.com:443",
        requireProvenanceVerification = true
    )

    @Test
    fun testEnforcesExactAuthOriginAndPathAndRejectsDowngrades() {
        val policy = AuthorizedPilotEndpointTrustPolicy(endpointConfig = cnAndGlobalConfig)

        assertTrue(
            policy.authorizeAuthEndpoint("https://user.grit-cloud.com/prod/oauth")
                is EndpointTrustDecision.Trusted
        )
        assertTrue(
            policy.authorizeAuthEndpoint("https://user.grit-cloud.com:443/prod/oauth")
                is EndpointTrustDecision.Trusted
        )

        // Wrong path, wrong host, wildcard, non-443 port, cleartext, userinfo, credential query
        val rejectedUrls = listOf(
            "https://user.grit-cloud.cn/prod/oauth", // does not match approvedAuthOrigin (.com)
            "https://user.grit-cloud.com/prod/other",
            "https://user.grit-cloud.com/",
            "https://sub.user.grit-cloud.com/prod/oauth",
            "https://user.grit-cloud.com:8443/prod/oauth",
            "http://user.grit-cloud.com/prod/oauth",
            "https://u:p@user.grit-cloud.com/prod/oauth",
            "https://user.grit-cloud.com/prod/oauth?token=secret"
        )
        for (url in rejectedUrls) {
            val decision = policy.authorizeAuthEndpoint(url)
            assertTrue("Expected Untrusted for $url", decision is EndpointTrustDecision.Untrusted)
            assertEquals(AuthFailureReason.UntrustedEndpoint, (decision as EndpointTrustDecision.Untrusted).reason)
        }
        assertEquals(Proxy.NO_PROXY, policy.proxy())
    }

    @Test
    fun testEnforcesPinnedDynamicOriginsAndQuarantinesUnpinnedWithoutTrustingHumanClickAlone() {
        val pinnedPolicy = AuthorizedPilotEndpointTrustPolicy(endpointConfig = cnAndGlobalConfig)
        assertTrue(pinnedPolicy.authorize("https://api-us.grit-cloud.com/api/v1") is EndpointTrustDecision.Trusted)
        assertTrue(pinnedPolicy.authorize("wss://wss-us.grit-cloud.com/ws") is EndpointTrustDecision.Trusted)
        assertTrue(pinnedPolicy.authorize("https://evil.example.invalid/api") is EndpointTrustDecision.Untrusted)
        assertTrue(pinnedPolicy.authorize("https://api-us.grit-cloud.com:8443/api") is EndpointTrustDecision.Untrusted)
        assertTrue(pinnedPolicy.authorize("ws://wss-us.grit-cloud.com/ws") is EndpointTrustDecision.Untrusted)

        val unpinnedPolicy = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = cnAndGlobalConfig.copy(pinnedApiOrigin = null, pinnedWssOrigin = null)
        )
        val quarantine = unpinnedPolicy.evaluateDynamicEndpoints(
            apiUrl = "https://dynamic-api.grit-cloud.com/api",
            wssUrl = "wss://dynamic-wss.grit-cloud.com/ws"
        )
        assertTrue(quarantine is DynamicOriginEvaluation.Quarantined)

        // Human confirmation WITHOUT verified provenance MUST be rejected
        val humanClickOnly = unpinnedPolicy.confirmDynamicOrigins(
            apiUrl = "https://dynamic-api.grit-cloud.com/api",
            wssUrl = "wss://dynamic-wss.grit-cloud.com/ws",
            humanConfirmed = true,
            provenanceVerifier = OriginProvenanceVerifier { _, _ -> OriginProvenanceResult.Unverified("NO_PINNED_PROVENANCE") }
        )
        assertTrue(humanClickOnly is DynamicOriginConfirmationResult.Rejected)

        // Verified provenance + explicit human confirmation succeeds and pins exact origins
        val verifiedAndConfirmed = unpinnedPolicy.confirmDynamicOrigins(
            apiUrl = "https://dynamic-api.grit-cloud.com/api",
            wssUrl = "wss://dynamic-wss.grit-cloud.com/ws",
            humanConfirmed = true,
            provenanceVerifier = OriginProvenanceVerifier { api, wss ->
                if (api.host == "dynamic-api.grit-cloud.com" && wss.host == "dynamic-wss.grit-cloud.com") {
                    OriginProvenanceResult.Verified("SIGNED_PILOT_MANIFEST_REF")
                } else {
                    OriginProvenanceResult.Unverified("MISMATCH")
                }
            }
        )
        assertTrue(verifiedAndConfirmed is DynamicOriginConfirmationResult.Accepted)

        // Invalid non-null pinned origins must fail closed as Rejected (never degrade to Quarantined)
        val invalidPinnedPolicy = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = cnAndGlobalConfig.copy(
                pinnedApiOrigin = "http://bad-cleartext.grit-cloud.com",
                pinnedWssOrigin = "ws://bad-cleartext.grit-cloud.com"
            )
        )
        assertTrue(
            invalidPinnedPolicy.evaluateDynamicEndpoints(
                apiUrl = "https://dynamic-api.grit-cloud.com/api",
                wssUrl = "wss://dynamic-wss.grit-cloud.com/ws"
            ) is DynamicOriginEvaluation.Rejected
        )
    }
}
