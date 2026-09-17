package com.nullroute

import com.nullroute.vpn.DnsTelemetryTracker
import com.nullroute.vpn.PowerTelemetryTracker
import com.nullroute.vpn.StandbySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PowerTelemetryTrackerTest {

    @Before
    fun setup() {
        PowerTelemetryTracker.clearSessionsForTest()
    }

    @Test
    fun testStandbySessionCalculations() {
        val now = System.currentTimeMillis()
        val session = StandbySession(
            startTimeMs = now - 3600_000, // 1 hour ago
            endTimeMs = now,
            startBatteryPct = 90,
            endBatteryPct = 89,
            queriesDuringStandby = 5,
            zeroReadsDuringStandby = 0,
            packetsDuringStandby = 6
        )

        assertEquals(3600L, session.durationSeconds)
        assertEquals(-1, session.batteryDeltaPct)

        PowerTelemetryTracker.recordSessionForTest(session)
        val sessions = PowerTelemetryTracker.getStandbySessions()
        assertEquals(1, sessions.size)
        assertEquals(5L, sessions[0].queriesDuringStandby)
        assertEquals(0L, sessions[0].zeroReadsDuringStandby)

        val report = PowerTelemetryTracker.formatStandbyReport()
        assertTrue(report.contains("Duration: 1h 0m 0s"))
        assertTrue(report.contains("90% -> 89% (-1%)"))
        assertTrue(report.contains("[OK: Zero spin]"))
    }

    @Test
    fun testZeroReadsWarningInReport() {
        val now = System.currentTimeMillis()
        val session = StandbySession(
            startTimeMs = now - 60_000,
            endTimeMs = now,
            startBatteryPct = 50,
            endBatteryPct = 45,
            queriesDuringStandby = 0,
            zeroReadsDuringStandby = 120,
            packetsDuringStandby = 0
        )

        PowerTelemetryTracker.recordSessionForTest(session)
        val report = PowerTelemetryTracker.formatStandbyReport()
        assertTrue(report.contains("[WARNING: Non-blocking spin!]"))
    }

    @Test
    fun testDnsTelemetryTrackerIoCounters() {
        DnsTelemetryTracker.recordVpnPacketRead()
        DnsTelemetryTracker.recordVpnPacketRead()
        DnsTelemetryTracker.recordZeroLengthRead()
        DnsTelemetryTracker.recordVpnReadError()
        DnsTelemetryTracker.recordUpstreamPacketReceived()
        DnsTelemetryTracker.recordUpstreamReceiveError()

        assertTrue(DnsTelemetryTracker.totalVpnPacketsRead.get() >= 2)
        assertTrue(DnsTelemetryTracker.totalZeroLengthReads.get() >= 1)
        assertTrue(DnsTelemetryTracker.totalVpnReadErrors.get() >= 1)
        assertTrue(DnsTelemetryTracker.totalUpstreamPacketsReceived.get() >= 1)
        assertTrue(DnsTelemetryTracker.totalUpstreamReceiveErrors.get() >= 1)

        val exportReport = DnsTelemetryTracker.generateExportReport()
        assertTrue(exportReport.contains("[8] Standby & Power Diagnostics"))
        assertTrue(exportReport.contains("[9] VPN Loop & Low-Level I/O Metrics"))
        assertTrue(exportReport.contains("TUN Packets Processed"))
    }
}
