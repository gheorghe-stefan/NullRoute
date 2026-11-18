package com.nullroute

import com.nullroute.vpn.DnsTelemetryTracker
import com.nullroute.vpn.PhysicalNetworkSelector
import com.nullroute.vpn.PhysicalNetworkSelector.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PhysicalNetworkSelectorTest {

    private val dnsRouter = InetAddress.getByName("192.168.0.1")
    private val dnsCorp = InetAddress.getByName("10.20.0.5")
    private val dnsCorp2 = InetAddress.getByName("10.20.0.6")
    private val dnsCarrier = InetAddress.getByName("212.161.168.14")
    private val dnsV6 = InetAddress.getByName("2001:4860:4860::8888")
    private val cloudflare = InetAddress.getByName("1.1.1.1")
    private val google = InetAddress.getByName("8.8.8.8")

    @Test
    fun testWifiAvailableBeforeDhcpThenLinkPropertiesPopulatesDns() {
        val selector = PhysicalNetworkSelector<Int>()

        // 1. Cellular is active first
        val r1 = selector.onAvailable(231, Transport.CELLULAR, isValidated = true, dnsServers = listOf(dnsCarrier))
        assertEquals(231, r1.activeNetwork)
        assertTrue(r1.networkChanged)
        assertTrue(r1.shouldRebuildSocket)
        assertEquals(listOf(dnsCarrier), r1.dnsServers)

        // 2. Wi-Fi connects before DHCP completes (empty DNS, not yet validated)
        val r2 = selector.onAvailable(245, Transport.WIFI, isValidated = false, dnsServers = emptyList())
        assertEquals(245, r2.activeNetwork)
        assertTrue(r2.networkChanged)
        assertTrue(r2.shouldRebuildSocket)
        assertTrue(r2.dnsServers.isEmpty())

        // 3. DHCP finishes and LinkProperties delivers 192.168.0.1 -> MUST rebuild socket!
        val r3 = selector.onLinkPropertiesChanged(245, listOf(dnsRouter))
        assertEquals(245, r3.activeNetwork)
        assertFalse(r3.networkChanged)
        assertTrue(r3.dnsChanged)
        assertTrue(r3.shouldRebuildSocket)
        assertEquals(listOf(dnsRouter), r3.dnsServers)

        // 4. Repeated identical LinkProperties updates (e.g. periodic RAs) -> no-op!
        val r4 = selector.onLinkPropertiesChanged(245, listOf(dnsRouter))
        assertFalse(r4.networkChanged)
        assertFalse(r4.dnsChanged)
        assertFalse(r4.shouldRebuildSocket)
    }

    @Test
    fun testWifiRoamingBetweenBssidsAndFallbackToCellularOnLost() {
        val selector = PhysicalNetworkSelector<Int>()

        selector.onAvailable(231, Transport.CELLULAR, isValidated = true, dnsServers = listOf(dnsCarrier))
        selector.onAvailable(244, Transport.WIFI, isValidated = true, dnsServers = listOf(dnsCorp))
        assertEquals(244, selector.getActiveNetwork())

        // New Wi-Fi 245 appears (not yet validated, no DNS yet) while 244 is still validated
        val rRoamStart = selector.onAvailable(245, Transport.WIFI, isValidated = false, dnsServers = emptyList())
        assertEquals(244, rRoamStart.activeNetwork)
        assertFalse(rRoamStart.networkChanged)

        // Old Wi-Fi 244 drops -> must immediately switch to 245 and rebuild socket
        val rOldLost = selector.onLost(244)
        assertEquals(245, rOldLost.activeNetwork)
        assertTrue(rOldLost.networkChanged)
        assertTrue(rOldLost.shouldRebuildSocket)

        // New Wi-Fi 245 drops too -> must fall back immediately to Cellular 231
        val rWifiLost = selector.onLost(245)
        assertEquals(231, rWifiLost.activeNetwork)
        assertTrue(rWifiLost.networkChanged)
        assertTrue(rWifiLost.shouldRebuildSocket)
        assertEquals(listOf(dnsCarrier), rWifiLost.dnsServers)

        // All networks lost -> null
        val rAllLost = selector.onLost(231)
        assertNull(rAllLost.activeNetwork)
        assertTrue(rAllLost.networkChanged)
    }

    @Test
    fun testCorporateDnsNotRacedAgainstPublicFallback() {
        val fallbacks = listOf(cloudflare, google)

        // Single corporate DNS -> ONLY corporate DNS returned (no 1.1.1.1 racing!)
        val singleCorp = PhysicalNetworkSelector.selectUpstreamTargets(listOf(dnsCorp), fallbacks)
        assertEquals(listOf(dnsCorp), singleCorp)

        // Two corporate DNS -> both returned
        val dualCorp = PhysicalNetworkSelector.selectUpstreamTargets(listOf(dnsCorp, dnsCorp2), fallbacks)
        assertEquals(listOf(dnsCorp, dnsCorp2), dualCorp)

        // Empty network DNS -> falls back to public resolvers
        val emptyLocal = PhysicalNetworkSelector.selectUpstreamTargets(emptyList(), fallbacks)
        assertEquals(listOf(cloudflare, google), emptyLocal)
    }

    @Test
    fun testIpv4AndIpv6DnsOrderingAndFallback() {
        val ordered = PhysicalNetworkSelector.orderDnsServers(listOf(dnsV6, dnsRouter, dnsV6, dnsCorp))
        assertEquals(listOf(dnsRouter, dnsCorp, dnsV6), ordered)

        // If IPv6-only DNS is provided, retain IPv6 rather than dropping it
        val v6Only = PhysicalNetworkSelector.selectUpstreamTargets(listOf(dnsV6), listOf(cloudflare, google))
        assertEquals(listOf(dnsV6), v6Only)
    }

    @Test
    fun testTelemetryEventRingBufferIsBounded() {
        for (i in 1..65) {
            DnsTelemetryTracker.recordEvent("event-$i")
        }
        val events = DnsTelemetryTracker.getRecentEvents()
        assertEquals(50, events.size)
        assertTrue(events.first().endsWith("event-16"))
        assertTrue(events.last().endsWith("event-65"))
    }
}
