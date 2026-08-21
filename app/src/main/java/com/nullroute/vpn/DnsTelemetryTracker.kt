package com.nullroute.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TelemetrySnapshot(
    val startTimeMs: Long = System.currentTimeMillis(),
    val uptimeSeconds: Long = 0,
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val allowedQueries: Long = 0,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    val cacheHitRatioPct: Double = 0.0,
    val currentQps: Double = 0.0,
    val peakQps: Double = 0.0,
    val timeouts: Long = 0,
    val networkErrors: Long = 0,
    val avgLatencyMs: Double = 0.0,
    val p95LatencyMs: Double = 0.0,
    val minLatencyMs: Long = 0,
    val maxLatencyMs: Long = 0,
    val avgLockWaitMs: Double = 0.0,
    val maxLockWaitMs: Long = 0,
    val qTypeCounts: Map<String, Long> = emptyMap(),
    val topDomains: List<Pair<String, Long>> = emptyMap<String, Long>().toList()
)

object DnsTelemetryTracker {

    private const val TAG = "NullRouteTelemetry"
    private const val MAX_LATENCY_SAMPLES = 500
    private const val MAX_LOCK_SAMPLES = 500
    private const val QPS_WINDOW_MS = 5000L

    private val startTimeMs = System.currentTimeMillis()
    private val totalQueries = AtomicLong(0)
    private val blockedQueries = AtomicLong(0)
    private val allowedQueries = AtomicLong(0)
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val timeouts = AtomicLong(0)
    private val networkErrors = AtomicLong(0)

    private val qTypeCounters = ConcurrentHashMap<Int, AtomicLong>()
    private val domainCounters = ConcurrentHashMap<String, AtomicLong>()

    private val latencySamples = ConcurrentLinkedDeque<Long>()
    private val lockWaitSamples = ConcurrentLinkedDeque<Long>()
    private val queryTimestamps = ConcurrentLinkedDeque<Long>()

    @Volatile
    private var peakQps: Double = 0.0

    private val _snapshot = MutableStateFlow(TelemetrySnapshot())
    val snapshot: StateFlow<TelemetrySnapshot> = _snapshot.asStateFlow()

    private var periodicScheduler: ScheduledExecutorService? = null

    init {
        startPeriodicLogger()
    }

    @Synchronized
    fun startPeriodicLogger() {
        if (periodicScheduler != null && !periodicScheduler!!.isShutdown) return
        periodicScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                try {
                    updateSnapshot()
                    logSummaryToLogcat()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic telemetry logging", e)
                }
            }, 15, 15, TimeUnit.SECONDS)
        }
    }

    fun recordQuery(domain: String, qType: Int) {
        val now = System.currentTimeMillis()
        totalQueries.incrementAndGet()
        queryTimestamps.addLast(now)
        pruneOldTimestamps(now)

        // Count QType
        qTypeCounters.computeIfAbsent(qType) { AtomicLong(0) }.incrementAndGet()

        // Count Domain
        if (domain.isNotEmpty()) {
            domainCounters.computeIfAbsent(domain) { AtomicLong(0) }.incrementAndGet()
        }

        // Calculate instant QPS and check peak
        val currentQps = calculateCurrentQps(now)
        if (currentQps > peakQps) {
            peakQps = currentQps
        }
    }

    fun recordBlockedQuery() {
        blockedQueries.incrementAndGet()
    }

    fun recordAllowedQuery() {
        allowedQueries.incrementAndGet()
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
    }

    fun recordTimeout() {
        timeouts.incrementAndGet()
    }

    fun recordNetworkError() {
        networkErrors.incrementAndGet()
    }

    fun recordLatency(latencyMs: Long) {
        latencySamples.addLast(latencyMs)
        while (latencySamples.size > MAX_LATENCY_SAMPLES) {
            latencySamples.pollFirst()
        }
    }

    fun recordLockWait(waitMs: Long) {
        lockWaitSamples.addLast(waitMs)
        while (lockWaitSamples.size > MAX_LOCK_SAMPLES) {
            lockWaitSamples.pollFirst()
        }
    }

    private fun pruneOldTimestamps(now: Long) {
        val cutoff = now - QPS_WINDOW_MS
        while (true) {
            val first = queryTimestamps.peekFirst() ?: break
            if (first < cutoff) {
                queryTimestamps.pollFirst()
            } else {
                break
            }
        }
    }

    private fun calculateCurrentQps(now: Long): Double {
        pruneOldTimestamps(now)
        val count = queryTimestamps.size
        return (count.toDouble() / (QPS_WINDOW_MS / 1000.0))
    }

    fun updateSnapshot() {
        val now = System.currentTimeMillis()
        val uptimeSec = (now - startTimeMs) / 1000
        val total = totalQueries.get()
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val hitRatio = if (hits + misses > 0) (hits.toDouble() / (hits + misses) * 100.0) else 0.0
        val curQps = calculateCurrentQps(now)

        // Latency stats
        val latencies = latencySamples.toList()
        val minLat = latencies.minOrNull() ?: 0L
        val maxLat = latencies.maxOrNull() ?: 0L
        val avgLat = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val p95Lat = if (latencies.isNotEmpty()) {
            val sorted = latencies.sorted()
            val p95Index = ((sorted.size * 0.95).toInt()).coerceIn(0, sorted.size - 1)
            sorted[p95Index].toDouble()
        } else 0.0

        // Lock wait stats
        val lockWaits = lockWaitSamples.toList()
        val avgLock = if (lockWaits.isNotEmpty()) lockWaits.average() else 0.0
        val maxLock = lockWaits.maxOrNull() ?: 0L

        // QType mapped names
        val qTypeMap = qTypeCounters.map { (qType, count) ->
            val name = formatQType(qType)
            name to count.get()
        }.toMap()

        // Top 10 domains
        val topList = domainCounters.entries
            .sortedByDescending { it.value.get() }
            .take(10)
            .map { it.key to it.value.get() }

        _snapshot.value = TelemetrySnapshot(
            startTimeMs = startTimeMs,
            uptimeSeconds = uptimeSec,
            totalQueries = total,
            blockedQueries = blockedQueries.get(),
            allowedQueries = allowedQueries.get(),
            cacheHits = hits,
            cacheMisses = misses,
            cacheHitRatioPct = hitRatio,
            currentQps = curQps,
            peakQps = peakQps,
            timeouts = timeouts.get(),
            networkErrors = networkErrors.get(),
            avgLatencyMs = avgLat,
            p95LatencyMs = p95Lat,
            minLatencyMs = minLat,
            maxLatencyMs = maxLat,
            avgLockWaitMs = avgLock,
            maxLockWaitMs = maxLock,
            qTypeCounts = qTypeMap,
            topDomains = topList
        )
    }

    private fun logSummaryToLogcat() {
        val snap = _snapshot.value
        if (snap.totalQueries == 0L) return

        Log.i(TAG, "================== NULLROUTE TELEMETRY STATS ==================")
        Log.i(TAG, "Uptime: ${snap.uptimeSeconds}s | Total Queries: ${snap.totalQueries} | Current QPS: ${String.format(Locale.US, "%.2f", snap.currentQps)} (Peak: ${String.format(Locale.US, "%.2f", snap.peakQps)})")
        Log.i(TAG, "Filtering: Blocked: ${snap.blockedQueries} | Allowed: ${snap.allowedQueries}")
        Log.i(TAG, "Cache: Hits: ${snap.cacheHits} | Misses: ${snap.cacheMisses} | Hit Rate: ${String.format(Locale.US, "%.1f", snap.cacheHitRatioPct)}%")
        Log.i(TAG, "QTypes: ${snap.qTypeCounts.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
        Log.i(TAG, "Upstream Latency: min=${snap.minLatencyMs}ms | avg=${String.format(Locale.US, "%.1f", snap.avgLatencyMs)}ms | p95=${String.format(Locale.US, "%.1f", snap.p95LatencyMs)}ms | max=${snap.maxLatencyMs}ms")
        Log.i(TAG, "Lock Contention: avgWait=${String.format(Locale.US, "%.1f", snap.avgLockWaitMs)}ms | maxWait=${snap.maxLockWaitMs}ms | Timeouts: ${snap.timeouts} | Network Errors: ${snap.networkErrors}")
        Log.i(TAG, "Top Domains:")
        snap.topDomains.take(5).forEachIndexed { idx, (dom, cnt) ->
            Log.i(TAG, "  ${idx + 1}. $dom ($cnt)")
        }
        Log.i(TAG, "===============================================================")
    }

    fun generateExportReport(): String {
        updateSnapshot()
        val snap = _snapshot.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val startDateStr = dateFormat.format(Date(snap.startTimeMs))
        val nowStr = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine("=== NULLROUTE TELEMETRY DIAGNOSTIC REPORT ===")
        sb.appendLine("Started At: $startDateStr")
        sb.appendLine("Report Time: $nowStr (Uptime: ${snap.uptimeSeconds}s)")
        sb.appendLine("App Version: 1.3.0")
        sb.appendLine()
        sb.appendLine("[1] Throughput & Load")
        sb.appendLine("  Total Queries Intercepted : ${snap.totalQueries}")
        sb.appendLine("  Current QPS               : ${String.format(Locale.US, "%.2f", snap.currentQps)}")
        sb.appendLine("  Peak QPS Burst            : ${String.format(Locale.US, "%.2f", snap.peakQps)}")
        sb.appendLine("  Blocked Queries           : ${snap.blockedQueries}")
        sb.appendLine("  Allowed Queries           : ${snap.allowedQueries}")
        sb.appendLine()
        sb.appendLine("[2] Cache Efficiency (Domain-Only)")
        sb.appendLine("  Cache Hits                : ${snap.cacheHits}")
        sb.appendLine("  Cache Misses              : ${snap.cacheMisses}")
        sb.appendLine("  Cache Hit Ratio           : ${String.format(Locale.US, "%.2f", snap.cacheHitRatioPct)}%")
        sb.appendLine()
        sb.appendLine("[3] Query Record Types Breakdown")
        snap.qTypeCounts.forEach { (type, count) ->
            sb.appendLine("  $type: $count")
        }
        sb.appendLine()
        sb.appendLine("[4] Upstream Resolution Latency")
        sb.appendLine("  Min Latency               : ${snap.minLatencyMs} ms")
        sb.appendLine("  Avg Latency               : ${String.format(Locale.US, "%.2f", snap.avgLatencyMs)} ms")
        sb.appendLine("  P95 Latency               : ${String.format(Locale.US, "%.2f", snap.p95LatencyMs)} ms")
        sb.appendLine("  Max Latency               : ${snap.maxLatencyMs} ms")
        sb.appendLine()
        sb.appendLine("[5] Concurrency & Lock Contention")
        sb.appendLine("  Avg Lock Wait Delay       : ${String.format(Locale.US, "%.2f", snap.avgLockWaitMs)} ms")
        sb.appendLine("  Max Lock Wait Delay       : ${snap.maxLockWaitMs} ms")
        sb.appendLine("  Resolution Timeouts       : ${snap.timeouts}")
        sb.appendLine("  Network Errors            : ${snap.networkErrors}")
        sb.appendLine()
        sb.appendLine("[6] Top 10 Most Queried Domains")
        snap.topDomains.forEachIndexed { i, (dom, cnt) ->
            sb.appendLine("  ${i + 1}. $dom ($cnt queries)")
        }
        sb.appendLine("=============================================")
        return sb.toString()
    }

    private fun formatQType(qType: Int): String {
        return when (qType) {
            1 -> "A(IPv4)"
            28 -> "AAAA(IPv6)"
            65 -> "HTTPS(65)"
            5 -> "CNAME"
            16 -> "TXT"
            12 -> "PTR"
            15 -> "MX"
            2 -> "NS"
            6 -> "SOA"
            else -> "TYPE_$qType"
        }
    }
}
