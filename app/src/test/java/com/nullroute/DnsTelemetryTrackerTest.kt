package com.nullroute

import com.nullroute.vpn.DnsTelemetryTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTelemetryTrackerTest {

    @Test
    fun testRecordQueriesAndCalculateMetrics() {
        DnsTelemetryTracker.recordQuery("example.com", 1) // A record
        DnsTelemetryTracker.recordQuery("example.com", 28) // AAAA record
        DnsTelemetryTracker.recordQuery("test.org", 65) // HTTPS record

        DnsTelemetryTracker.recordAllowedQuery()
        DnsTelemetryTracker.recordAllowedQuery()
        DnsTelemetryTracker.recordBlockedQuery()

        DnsTelemetryTracker.recordCacheHit()
        DnsTelemetryTracker.recordCacheMiss()

        DnsTelemetryTracker.recordLatency(20)
        DnsTelemetryTracker.recordLatency(50)
        DnsTelemetryTracker.recordLatency(10)

        DnsTelemetryTracker.recordLockWait(5)

        DnsTelemetryTracker.updateSnapshot()
        val snapshot = DnsTelemetryTracker.snapshot.value

        assertTrue(snapshot.totalQueries >= 3)
        assertEquals(1, snapshot.blockedQueries)
        assertEquals(2, snapshot.allowedQueries)
        assertEquals(1, snapshot.cacheHits)
        assertEquals(1, snapshot.cacheMisses)
        assertEquals(50.0, snapshot.cacheHitRatioPct, 0.1)

        assertTrue(snapshot.minLatencyMs <= 10)
        assertTrue(snapshot.maxLatencyMs >= 50)
        assertTrue(snapshot.avgLatencyMs in 10.0..50.0)

        val report = DnsTelemetryTracker.generateExportReport()
        assertTrue(report.contains("NULLROUTE TELEMETRY DIAGNOSTIC REPORT"))
        assertTrue(report.contains("Cache Hit Ratio"))
        assertTrue(report.contains("example.com"))
    }
}
