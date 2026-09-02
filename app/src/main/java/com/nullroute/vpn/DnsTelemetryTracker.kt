package com.nullroute.vpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
    val allTimeTotalQueries: Long = 0,
    val allTimeCacheHits: Long = 0,
    val allTimeCacheMisses: Long = 0,
    val allTimeHitRatioPct: Double = 0.0,
    val allTimeBlockedQueries: Long = 0,
    val allTimeAllowedQueries: Long = 0,
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

    private var sharedPrefs: SharedPreferences? = null
    private var logsDir: File? = null
    private var versionLogFile: File? = null
    private const val MAX_LOG_FILES_TO_KEEP = 10

    // All-time persistent counters (preserved across app versions / restarts)
    private val allTimeTotalQueries = AtomicLong(0)
    private val allTimeBlockedQueries = AtomicLong(0)
    private val allTimeAllowedQueries = AtomicLong(0)
    private val allTimeCacheHits = AtomicLong(0)
    private val allTimeCacheMisses = AtomicLong(0)
    private val allTimeTimeouts = AtomicLong(0)
    private val allTimeNetworkErrors = AtomicLong(0)

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
    private var lastLoggedQueries: Long = -1
    private var lastLoggedTimeouts: Long = -1

    init {
        startPeriodicLogger()
    }

    @Synchronized
    fun init(context: Context) {
        if (sharedPrefs != null) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("nullroute_telemetry_prefs", Context.MODE_PRIVATE)
        sharedPrefs = prefs

        val dir = appContext.filesDir.resolve("logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logsDir = dir

        val versionName = com.nullroute.BuildConfig.VERSION_NAME
        val logFile = dir.resolve("telemetry_v${versionName}.log")
        versionLogFile = logFile

        // Clean up older versions beyond the retention limit
        cleanOldVersionLogs(dir, MAX_LOG_FILES_TO_KEEP)

        allTimeTotalQueries.set(prefs.getLong("allTimeTotalQueries", 0L))
        allTimeBlockedQueries.set(prefs.getLong("allTimeBlocked", 0L))
        allTimeAllowedQueries.set(prefs.getLong("allTimeAllowed", 0L))
        allTimeCacheHits.set(prefs.getLong("allTimeCacheHits", 0L))
        allTimeCacheMisses.set(prefs.getLong("allTimeCacheMisses", 0L))
        allTimeTimeouts.set(prefs.getLong("allTimeTimeouts", 0L))
        allTimeNetworkErrors.set(prefs.getLong("allTimeNetworkErrors", 0L))

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val nowStr = dateFormat.format(Date())

        if (!logFile.exists() || logFile.length() == 0L) {
            appendToLogFile("=================================================================")
            appendToLogFile("  NULLROUTE TELEMETRY LOG - VERSION $versionName")
            appendToLogFile("  Created: $nowStr")
            appendToLogFile("=================================================================\n")
        } else {
            appendToLogFile("\n[$nowStr] === Process Restart / Reinstall (v$versionName) ===")
        }
    }

    private fun cleanOldVersionLogs(dir: File, maxToKeep: Int) {
        try {
            val logFiles = dir.listFiles { file ->
                file.isFile && file.name.startsWith("telemetry_v") && file.name.endsWith(".log")
            } ?: return

            if (logFiles.size > maxToKeep) {
                val sorted = logFiles.sortedBy { it.lastModified() }
                val toDelete = sorted.take(logFiles.size - maxToKeep)
                toDelete.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean old version logs", e)
        }
    }

    fun getAvailableVersionLogs(): List<String> {
        val dir = logsDir ?: return emptyList()
        return try {
            val files = dir.listFiles { file ->
                file.isFile && file.name.startsWith("telemetry_v") && file.name.endsWith(".log")
            } ?: return emptyList()
            files.sortedByDescending { it.lastModified() }.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun persistAllTimeStats() {
        val prefs = sharedPrefs ?: return
        try {
            prefs.edit()
                .putLong("allTimeTotalQueries", allTimeTotalQueries.get())
                .putLong("allTimeCacheHits", allTimeCacheHits.get())
                .putLong("allTimeCacheMisses", allTimeCacheMisses.get())
                .putLong("allTimeBlocked", allTimeBlockedQueries.get())
                .putLong("allTimeAllowed", allTimeAllowedQueries.get())
                .putLong("allTimeTimeouts", allTimeTimeouts.get())
                .putLong("allTimeNetworkErrors", allTimeNetworkErrors.get())
                .apply()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Synchronized
    private fun appendToLogFile(text: String) {
        val file = versionLogFile ?: return
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            if (file.length() > 512 * 1024) {
                val lines = file.readLines()
                val keep = lines.takeLast(lines.size / 2)
                file.writeText(keep.joinToString("\n") + "\n")
            }
            file.appendText(text + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write to telemetry log file", e)
        }
    }

    @Synchronized
    fun startPeriodicLogger() {
        if (periodicScheduler != null && !periodicScheduler!!.isShutdown) return
        periodicScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                try {
                    updateSnapshot()
                    logSummaryToLogcat()
                    persistAllTimeStats()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic telemetry logging", e)
                }
            }, 60, 60, TimeUnit.SECONDS)
        }
    }

    fun recordQuery(domain: String, qType: Int) {
        val now = System.currentTimeMillis()
        totalQueries.incrementAndGet()
        allTimeTotalQueries.incrementAndGet()
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
        allTimeBlockedQueries.incrementAndGet()
    }

    fun recordAllowedQuery() {
        allowedQueries.incrementAndGet()
        allTimeAllowedQueries.incrementAndGet()
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
        allTimeCacheHits.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
        allTimeCacheMisses.incrementAndGet()
    }

    fun recordTimeout() {
        timeouts.incrementAndGet()
        allTimeTimeouts.incrementAndGet()
    }

    fun recordNetworkError() {
        networkErrors.incrementAndGet()
        allTimeNetworkErrors.incrementAndGet()
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

        val allTimeTotal = allTimeTotalQueries.get()
        val allTimeHits = allTimeCacheHits.get()
        val allTimeMisses = allTimeCacheMisses.get()
        val allTimeRatio = if (allTimeHits + allTimeMisses > 0) (allTimeHits.toDouble() / (allTimeHits + allTimeMisses) * 100.0) else 0.0

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
            allTimeTotalQueries = allTimeTotal,
            allTimeCacheHits = allTimeHits,
            allTimeCacheMisses = allTimeMisses,
            allTimeHitRatioPct = allTimeRatio,
            allTimeBlockedQueries = allTimeBlockedQueries.get(),
            allTimeAllowedQueries = allTimeAllowedQueries.get(),
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
        if (snap.totalQueries == 0L || (snap.totalQueries == lastLoggedQueries && snap.timeouts == lastLoggedTimeouts)) return
        lastLoggedQueries = snap.totalQueries
        lastLoggedTimeouts = snap.timeouts

        val summary = StringBuilder().apply {
            appendLine("================== NULLROUTE TELEMETRY STATS ==================")
            appendLine("Uptime: ${snap.uptimeSeconds}s | Session Queries: ${snap.totalQueries} | All-Time Queries: ${snap.allTimeTotalQueries}")
            appendLine("Filtering: Blocked: ${snap.blockedQueries} | Allowed: ${snap.allowedQueries} | All-Time Blocked: ${snap.allTimeBlockedQueries}")
            appendLine("Cache: Hits: ${snap.cacheHits} | Misses: ${snap.cacheMisses} | Hit Rate: ${String.format(Locale.US, "%.1f", snap.cacheHitRatioPct)}% (All-Time: ${String.format(Locale.US, "%.1f", snap.allTimeHitRatioPct)}%)")
            appendLine("QTypes: ${snap.qTypeCounts.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
            appendLine("Upstream Latency: min=${snap.minLatencyMs}ms | avg=${String.format(Locale.US, "%.1f", snap.avgLatencyMs)}ms | p95=${String.format(Locale.US, "%.1f", snap.p95LatencyMs)}ms | max=${snap.maxLatencyMs}ms")
            appendLine("Lock Contention: avgWait=${String.format(Locale.US, "%.1f", snap.avgLockWaitMs)}ms | maxWait=${snap.maxLockWaitMs}ms | Timeouts: ${snap.timeouts} | Net Errors: ${snap.networkErrors}")
            appendLine("Top Domains:")
            snap.topDomains.take(5).forEachIndexed { idx, (dom, cnt) ->
                appendLine("  ${idx + 1}. $dom ($cnt)")
            }
            append("===============================================================")
        }.toString()

        Log.i(TAG, summary)
        appendToLogFile(summary)
    }

    fun getRecentLogHistory(maxLines: Int = 40): String {
        return try {
            val file = versionLogFile ?: return "Log file not initialized."
            if (!file.exists()) return "No logs recorded yet."
            val lines = file.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "Error reading log file: ${e.message}"
        }
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
        sb.appendLine("App Version: ${com.nullroute.BuildConfig.VERSION_NAME}")
        sb.appendLine()
        sb.appendLine("[1] Current Session Metrics")
        sb.appendLine("  Session Queries           : ${snap.totalQueries}")
        sb.appendLine("  Current QPS               : ${String.format(Locale.US, "%.2f", snap.currentQps)}")
        sb.appendLine("  Peak QPS Burst            : ${String.format(Locale.US, "%.2f", snap.peakQps)}")
        sb.appendLine("  Blocked Queries           : ${snap.blockedQueries}")
        sb.appendLine("  Allowed Queries           : ${snap.allowedQueries}")
        sb.appendLine("  Session Cache Hits        : ${snap.cacheHits}")
        sb.appendLine("  Session Cache Misses      : ${snap.cacheMisses}")
        sb.appendLine("  Session Cache Hit Ratio   : ${String.format(Locale.US, "%.2f", snap.cacheHitRatioPct)}%")
        sb.appendLine()
        sb.appendLine("[2] Cumulative All-Time Metrics (Preserved Across Updates)")
        sb.appendLine("  All-Time Total Queries    : ${snap.allTimeTotalQueries}")
        sb.appendLine("  All-Time Cache Hits       : ${snap.allTimeCacheHits}")
        sb.appendLine("  All-Time Cache Misses     : ${snap.allTimeCacheMisses}")
        sb.appendLine("  All-Time Cache Hit Ratio  : ${String.format(Locale.US, "%.2f", snap.allTimeHitRatioPct)}%")
        sb.appendLine("  All-Time Blocked Queries  : ${snap.allTimeBlockedQueries}")
        sb.appendLine("  All-Time Allowed Queries  : ${snap.allTimeAllowedQueries}")
        sb.appendLine("  Current Version Log       : /data/data/com.nullroute/files/logs/telemetry_v${com.nullroute.BuildConfig.VERSION_NAME}.log")
        val availableLogs = getAvailableVersionLogs()
        if (availableLogs.isNotEmpty()) {
            sb.appendLine("  Archived Version Logs     : ${availableLogs.joinToString(", ")}")
        }
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
        sb.appendLine()
        val recentLogs = getRecentLogHistory(30)
        if (recentLogs.isNotBlank() && !recentLogs.startsWith("No logs")) {
            sb.appendLine("[7] Recent Persistent Telemetry Log History")
            sb.appendLine(recentLogs)
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
