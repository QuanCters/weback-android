package com.weback.robot.security

import com.weback.robot.domain.pilot.PilotEndpointConfig
import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class PilotIpAddressIsolationTest {

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun ipv4MappedIpv6(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(
            "mapped.invalid",
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(),
                a.toByte(), b.toByte(), c.toByte(), d.toByte()
            )
        )

    private fun rawIpv6(vararg bytes: Int): InetAddress {
        require(bytes.size == 16)
        return InetAddress.getByAddress("v6.invalid", bytes.map { it.toByte() }.toByteArray())
    }

    @Test
    fun testRejectsAllIpv4AndIpv6SpecialUseRangesIncludingUlaCgnatAndMappedIpv6() {
        val specialUseAddresses = listOf(
            ipv4(0, 0, 0, 0),             // unspecified
            ipv4(0, 10, 0, 1),            // 0.0.0.0/8
            ipv4(10, 0, 0, 1),            // RFC1918
            ipv4(100, 64, 0, 1),          // CGNAT 100.64.0.0/10
            ipv4(100, 127, 255, 254),     // CGNAT upper bound
            ipv4(127, 0, 0, 1),           // loopback
            ipv4(169, 254, 169, 254),     // metadata / link-local
            ipv4(172, 16, 0, 1),          // RFC1918
            ipv4(172, 31, 255, 254),      // RFC1918
            ipv4(192, 0, 0, 1),           // IETF protocol
            ipv4(192, 0, 2, 1),           // TEST-NET-1
            ipv4(192, 88, 99, 1),         // 6to4 relay
            ipv4(192, 168, 1, 1),         // RFC1918
            ipv4(198, 18, 0, 1),          // benchmarking
            ipv4(198, 51, 100, 1),        // TEST-NET-2
            ipv4(203, 0, 113, 1),         // TEST-NET-3
            ipv4(224, 0, 0, 1),           // multicast
            ipv4(240, 0, 0, 1),           // reserved
            ipv4(255, 255, 255, 255),     // limited broadcast
            // IPv4-mapped IPv6
            ipv4MappedIpv6(127, 0, 0, 1),
            ipv4MappedIpv6(10, 0, 0, 1),
            ipv4MappedIpv6(100, 64, 0, 1),
            ipv4MappedIpv6(169, 254, 169, 254),
            ipv4MappedIpv6(192, 168, 1, 1),
            ipv4MappedIpv6(0, 0, 0, 0),
            // IPv6 special-use
            rawIpv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),          // ::
            rawIpv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),          // ::1
            rawIpv6(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),       // ULA fc00::1
            rawIpv6(0xfd, 0, 0x0e, 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54), // AWS ULA fd00:ec2::254
            rawIpv6(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),    // link-local
            rawIpv6(0xfe, 0xc0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),    // deprecated site-local
            rawIpv6(0xff, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),    // multicast
            rawIpv6(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // NAT64
            rawIpv6(0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),       // discard 100::/64
            rawIpv6(0x20, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),    // Teredo 2001::/32
            rawIpv6(0x20, 0x01, 0x00, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // benchmarking
            rawIpv6(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1), // documentation
            rawIpv6(0x20, 0x02, 0x7f, 0x00, 0, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1) // 6to4
        )

        for (addr in specialUseAddresses) {
            assertFalse("Expected rejection for ${addr.hostAddress}", IpAddressPolicy.isGloballyRoutableUnicast(addr))
        }

        val publicAddresses = listOf(
            ipv4(1, 1, 1, 1),
            ipv4(93, 184, 216, 34),
            ipv4(100, 63, 255, 254),
            ipv4(100, 128, 0, 1),
            ipv4(172, 32, 0, 1),
            rawIpv6(0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)
        )
        for (addr in publicAddresses) {
            assertTrue("Expected acceptance for ${addr.hostAddress}", IpAddressPolicy.isGloballyRoutableUnicast(addr))
        }

        val policy = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig("https://user.grit-cloud.com:443")
        )
        assertFalse(policy.validateResolvedAddresses("user.grit-cloud.com", emptyList()))
        assertFalse(policy.validateResolvedAddresses("user.grit-cloud.com", listOf(ipv4(93, 184, 216, 34), ipv4(10, 0, 0, 1))))
        assertTrue(policy.validateResolvedAddresses("user.grit-cloud.com", listOf(ipv4(93, 184, 216, 34))))
    }

    @Test
    fun testInjectedDnsFixtureBlocksNonGlobalDestinationBeforeSocketConnect() {
        val policy = AuthorizedPilotEndpointTrustPolicy(
            endpointConfig = PilotEndpointConfig("https://user.grit-cloud.com:443")
        )
        val injectedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(ipv4(169, 254, 169, 254))
        }
        val client = PilotClientFactory.buildPilotOkHttpClient(
            trustPolicy = policy,
            baseDns = injectedDns
        )

        var failed = false
        try {
            client.newCall(
                Request.Builder().url("https://user.grit-cloud.com/prod/oauth").build()
            ).execute()
        } catch (e: UnknownHostException) {
            failed = true
        } catch (e: IOException) {
            failed = true
        }
        assertTrue("Expected injected special-use DNS fixture to fail closed before connect", failed)
    }
}
