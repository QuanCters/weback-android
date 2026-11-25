package com.weback.robot.domain

import com.weback.robot.domain.device.DeviceCapabilityClassifier
import com.weback.robot.domain.model.ControlCompatibility
import com.weback.robot.domain.model.DeviceCapabilityCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilityClassifierTest {

    @Test
    fun testGroundedLaserSlamPrefixesMapToMvpSupportedShadowWs() {
        val samples = listOf(
            "yg-yw10001-a1",
            "YG-YW10001-B2",
            "  xs-x8-eu  ",
            "tesvor-s6-v2",
            "xs-x8", // exact without trailing dash -> c2 appends '-'
            "okami-u100-pro",
            "hx-t800s-1"
        )
        for (subType in samples) {
            val result = DeviceCapabilityClassifier.classify(subType)
            assertEquals("Expected LASER_SLAM for $subType", DeviceCapabilityCategory.LASER_SLAM, result.category)
            assertEquals("Expected MvpSupportedShadowWs for $subType", ControlCompatibility.MvpSupportedShadowWs, result.compatibility)
            assertTrue(result.compatibility.isMvpControlSupported)
        }
    }

    @Test
    fun testGroundedExcludedAndNonLaserFamilies() {
        val cases = listOf(
            // q1 visual/gyro (distinct from P0)
            "yw-vs01-01" to Pair(DeviceCapabilityCategory.VISUAL_GYRO_MAPPED, ControlCompatibility.UnsupportedNonLaserVacuum),
            "xs-x6-eu" to Pair(DeviceCapabilityCategory.VISUAL_GYRO_MAPPED, ControlCompatibility.UnsupportedNonLaserVacuum),
            "iqlean-cr01" to Pair(DeviceCapabilityCategory.VISUAL_GYRO_MAPPED, ControlCompatibility.UnsupportedNonLaserVacuum),
            // l1/L0 gyro vacuums
            "ty-x500" to Pair(DeviceCapabilityCategory.GYRO_VACUUM, ControlCompatibility.UnsupportedNonLaserVacuum),
            "exvac-660-a" to Pair(DeviceCapabilityCategory.GYRO_VACUUM, ControlCompatibility.UnsupportedNonLaserVacuum),
            // n1 ThroughTek camera vacuums
            "xs-x5c2-1" to Pair(DeviceCapabilityCategory.THROUGHTEK_CAMERA_VACUUM, ControlCompatibility.UnsupportedCamera),
            "kt-589-cam" to Pair(DeviceCapabilityCategory.THROUGHTEK_CAMERA_VACUUM, ControlCompatibility.UnsupportedCamera),
            // Peripherals (m1, N0, Y0, v0, g1)
            "thermostat-br-1" to Pair(DeviceCapabilityCategory.PERIPHERAL, ControlCompatibility.UnsupportedPeripheral),
            "lamp-br-rgb" to Pair(DeviceCapabilityCategory.PERIPHERAL, ControlCompatibility.UnsupportedPeripheral),
            "sock-pw-1" to Pair(DeviceCapabilityCategory.PERIPHERAL, ControlCompatibility.UnsupportedPeripheral),
            "tendm-tp11-pro" to Pair(DeviceCapabilityCategory.PERIPHERAL, ControlCompatibility.UnsupportedPeripheral),
            "tx3-mini-box" to Pair(DeviceCapabilityCategory.PERIPHERAL, ControlCompatibility.UnsupportedPeripheral),
            // Legacy XMPP (H0/U0)
            "yb-r162-1" to Pair(DeviceCapabilityCategory.LEGACY_XMPP, ControlCompatibility.UnsupportedLegacyXmpp),
            "wotn-c340-x" to Pair(DeviceCapabilityCategory.LEGACY_XMPP, ControlCompatibility.UnsupportedLegacyXmpp),
            // Unknown / blank
            "unknown-future-model" to Pair(DeviceCapabilityCategory.UNKNOWN, ControlCompatibility.UnsupportedUnknown),
            "" to Pair(DeviceCapabilityCategory.UNKNOWN, ControlCompatibility.UnsupportedUnknown),
            "   " to Pair(DeviceCapabilityCategory.UNKNOWN, ControlCompatibility.UnsupportedUnknown)
        )

        for ((subType, expected) in cases) {
            val result = DeviceCapabilityClassifier.classify(subType)
            assertEquals("Category mismatch for $subType", expected.first, result.category)
            assertEquals("Compatibility mismatch for $subType", expected.second, result.compatibility)
            assertFalse("Should not allow MVP control for $subType", result.compatibility.isMvpControlSupported)
        }
    }
}
