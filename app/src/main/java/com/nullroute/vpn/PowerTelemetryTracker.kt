package com.nullroute.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class StandbySession(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startBatteryPct: Int,
    val endBatteryPct: Int,
    val queriesDuringStandby: Long,
    val zeroReadsDuringStandby: Long,
    val packetsDuringStandby: Long
) {
    val durationSeconds: Long get() = (endTimeMs - startTimeMs).coerceAtLeast(0) / 1000
    val batteryDeltaPct: Int get() = endBatteryPct - startBatteryPct
}

object PowerTelemetryTracker {
    private const val TAG = "NullRoutePowerTracker"
    private const val MAX_SESSIONS = 10

    private val sessionHistory = ConcurrentLinkedDeque<StandbySession>()

    @Volatile
    private var screenOffTimeMs: Long = 0L
    @Volatile
    private var screenOffBatteryPct: Int = -1
    @Volatile
    private var screenOffQueries: Long = 0L
    @Volatile
    private var screenOffZeroReads: Long = 0L
    @Volatile
    private var screenOffPackets: Long = 0L
    @Volatile
    private var isScreenOff: Boolean = false

    private var powerReceiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (powerReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(ctx)
                    Intent.ACTION_SCREEN_ON -> onScreenOn(ctx)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            context.registerReceiver(receiver, filter)
            powerReceiver = receiver
            try {
                Log.i(TAG, "PowerTelemetryTracker registered")
            } catch (_: Throwable) {}
        } catch (e: Exception) {
            try {
                Log.w(TAG, "Failed to register power receiver", e)
            } catch (_: Throwable) {}
        }
    }

    fun unregister(context: Context) {
        powerReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        powerReceiver = null
    }

    fun getBatteryPercentage(context: Context): Int {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else -1
        } catch (e: Exception) {
            -1
        }
    }

    fun onScreenOff(context: Context) {
        val now = System.currentTimeMillis()
        val batteryPct = getBatteryPercentage(context)
        DnsTelemetryTracker.updateSnapshot()
        val queries = DnsTelemetryTracker.snapshot.value.totalQueries
        val zeroReads = DnsTelemetryTracker.totalZeroLengthReads.get()
        val packets = DnsTelemetryTracker.totalVpnPacketsRead.get()

        screenOffTimeMs = now
        screenOffBatteryPct = batteryPct
        screenOffQueries = queries
        screenOffZeroReads = zeroReads
        screenOffPackets = packets
        isScreenOff = true

        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
        try {
            Log.i(TAG, "[PowerTracker] Screen OFF at $timeStr | Battery: ${if (batteryPct >= 0) "$batteryPct%" else "N/A"} | Active queries so far: $queries")
        } catch (_: Throwable) {}
    }

    fun onScreenOn(context: Context) {
        if (!isScreenOff || screenOffTimeMs == 0L) return
        val now = System.currentTimeMillis()
        val batteryPct = getBatteryPercentage(context)
        DnsTelemetryTracker.updateSnapshot()
        val queries = DnsTelemetryTracker.snapshot.value.totalQueries
        val zeroReads = DnsTelemetryTracker.totalZeroLengthReads.get()
        val packets = DnsTelemetryTracker.totalVpnPacketsRead.get()

        val durationSec = (now - screenOffTimeMs) / 1000
        val deltaQueries = (queries - screenOffQueries).coerceAtLeast(0)
        val deltaZeroReads = (zeroReads - screenOffZeroReads).coerceAtLeast(0)
        val deltaPackets = (packets - screenOffPackets).coerceAtLeast(0)

        val session = StandbySession(
            startTimeMs = screenOffTimeMs,
            endTimeMs = now,
            startBatteryPct = screenOffBatteryPct,
            endBatteryPct = batteryPct,
            queriesDuringStandby = deltaQueries,
            zeroReadsDuringStandby = deltaZeroReads,
            packetsDuringStandby = deltaPackets
        )

        sessionHistory.addLast(session)
        while (sessionHistory.size > MAX_SESSIONS) {
            sessionHistory.pollFirst()
        }

        isScreenOff = false
        screenOffTimeMs = 0L

        val durationFmt = formatDuration(durationSec)
        val batteryText = if (screenOffBatteryPct >= 0 && batteryPct >= 0) {
            val delta = batteryPct - screenOffBatteryPct
            "${screenOffBatteryPct}% -> ${batteryPct}% (${if (delta >= 0) "+$delta%" else "$delta%"})"
        } else {
            "N/A"
        }

        try {
            Log.i(TAG, "[PowerTracker] Screen ON after $durationFmt | Battery: $batteryText | Queries in standby: $deltaQueries | Packets: $deltaPackets | Zero-reads: $deltaZeroReads")
        } catch (_: Throwable) {}
    }

    fun recordSessionForTest(session: StandbySession) {
        sessionHistory.addLast(session)
        while (sessionHistory.size > MAX_SESSIONS) {
            sessionHistory.pollFirst()
        }
    }

    fun clearSessionsForTest() {
        sessionHistory.clear()
        isScreenOff = false
        screenOffTimeMs = 0L
    }

    fun getStandbySessions(): List<StandbySession> {
        return sessionHistory.toList()
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "${h}h ${m}m ${s}s" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun formatStandbyReport(): String {
        val sessions = getStandbySessions()
        if (sessions.isEmpty()) return "  No standby (screen-off) sessions recorded yet."
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sessions.asReversed().forEachIndexed { index, sess ->
            val startStr = dateFormat.format(Date(sess.startTimeMs))
            val durStr = formatDuration(sess.durationSeconds)
            val battStr = if (sess.startBatteryPct >= 0 && sess.endBatteryPct >= 0) {
                "${sess.startBatteryPct}% -> ${sess.endBatteryPct}% (${sess.batteryDeltaPct}%)"
            } else "N/A"
            sb.appendLine("  #${index + 1}: $startStr (Duration: $durStr)")
            sb.appendLine("      Battery       : $battStr")
            sb.appendLine("      Queries       : ${sess.queriesDuringStandby}")
            sb.appendLine("      TUN Packets   : ${sess.packetsDuringStandby}")
            sb.appendLine("      Zero-Reads    : ${sess.zeroReadsDuringStandby} ${if (sess.zeroReadsDuringStandby > 0) "[WARNING: Non-blocking spin!]" else "[OK: Zero spin]"}")
        }
        return sb.toString().trimEnd()
    }
}
