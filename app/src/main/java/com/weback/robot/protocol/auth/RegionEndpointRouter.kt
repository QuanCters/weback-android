package com.weback.robot.protocol.auth

import com.weback.robot.domain.model.AccountRegionType
import com.weback.robot.domain.model.AuthFailureReason

sealed interface RegionResolution {
    data class Resolved(
        val endpointUrl: String,
        val normalizedCallingCode: String,
        val regionType: AccountRegionType
    ) : RegionResolution

    data class Rejected(
        val reason: AuthFailureReason,
        val safeMessage: String
    ) : RegionResolution
}

/**
 * Pure region-based authentication URL router (`REG-06`, `function/b.java:28,31`, `e0.java:144-149`).
 * Optional URL overrides exist strictly for loopback MockWebServer injection in automated tests.
 */
class RegionEndpointRouter(
    private val cnOverrideUrl: String? = null,
    private val globalOverrideUrl: String? = null
) {
    fun resolve(rawCallingCode: String): RegionResolution {
        val normalized = rawCallingCode.trim()
        if (!callingCodeRegex.matches(normalized)) {
            return RegionResolution.Rejected(
                reason = AuthFailureReason.ConfigurationError,
                safeMessage = "Invalid or blank region calling code"
            )
        }
        val regionType = if (normalized == "+86" || normalized == "0086") {
            AccountRegionType.CN
        } else {
            AccountRegionType.GLOBAL
        }
        val targetUrl = when (regionType) {
            AccountRegionType.CN -> cnOverrideUrl ?: CN_OAUTH_URL
            AccountRegionType.GLOBAL -> globalOverrideUrl ?: GLOBAL_OAUTH_URL
        }
        return RegionResolution.Resolved(
            endpointUrl = targetUrl,
            normalizedCallingCode = normalized,
            regionType = regionType
        )
    }

    companion object {
        const val CN_OAUTH_URL = "https://user.grit-cloud.cn/prod/oauth"
        const val GLOBAL_OAUTH_URL = "https://user.grit-cloud.com/prod/oauth"
        private val callingCodeRegex = Regex("^(\\+|00)[0-9]{1,4}$")
    }
}
