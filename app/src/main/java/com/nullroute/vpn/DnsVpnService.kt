package com.nullroute.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nullroute.data.BlocklistRepository
import com.nullroute.data.SharedPreferencesBlocklistRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "NullRouteVPN"
        private const val MIN_CACHE_TTL_MS = 60_000L     // Minimum 60 seconds TTL floor
        private const val MAX_CACHE_TTL_MS = 3600_000L   // Maximum 1 hour
        private const val QUERY_TIMEOUT_MS = 2000L      // 2 seconds before fast-fail

        // IPv6 DNS Server representation (fd00:a:b:c::1)
        private val IPV6_DNS_SERVER = byteArrayOf(
            0xfd.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x0a.toByte(),
            0x00.toByte(), 0x0b.toByte(),
            0x00.toByte(), 0x0c.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x01.toByte()
        )

        private val PRIMARY_UPSTREAM_V4 = InetAddress.getByName("8.8.8.8")
        private val SECONDARY_UPSTREAM_V4 = InetAddress.getByName("1.1.1.1")
        private val TERTIARY_UPSTREAM_V4 = InetAddress.getByName("9.9.9.9")
    }

    data class DnsCacheKey(
        val domain: String,
        val qType: Int
    )

    data class CachedDnsResponse(
        val timestampMs: Long,
        val ttlMs: Long,
        val payload: ByteArray
    )

    data class InFlightQuery(
        val clientTid: Short,
        val clientIp: ByteArray,
        val clientPort: Int,
        val domain: String,
        val qType: Int,
        val isIPv6: Boolean,
        val timestampMs: Long,
        val originalQuestionSection: ByteArray
    )

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var vpnLoopThread: Thread? = null

    @Volatile
    private var receiverThread: Thread? = null

    @Volatile
    private var cachedBlockedDomains: Set<String> = emptySet()

    private val dnsResponseCache = ConcurrentHashMap<DnsCacheKey, CachedDnsResponse>()
    private val inFlightQueries = ConcurrentHashMap<Short, InFlightQuery>()
    private val tidSequence = AtomicInteger(1)

    private val forwardExecutor = Executors.newFixedThreadPool(4)
    private var sweeperScheduler: ScheduledExecutorService? = null

    private lateinit var repository: BlocklistRepository
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var forwardingSocket: DatagramSocket? = null

    @Volatile
    private var vpnOutputStream: FileOutputStream? = null

    override fun onCreate() {
        super.onCreate()
        repository = SharedPreferencesBlocklistRepository(applicationContext)
        reloadBlockedDomainsCache()
        registerNetworkCallback()
        startSweeper()
        Log.i(TAG, "VPN Service Created with Async NAT Multiplexer")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN Service Starting")
        reloadBlockedDomainsCache()

        if (vpnLoopThread != null && vpnLoopThread!!.isAlive) {
            Log.i(TAG, "VPN loop already running, ignoring start command")
            return START_STICKY
        }

        VpnStateTracker.isRunning.value = true
        startForegroundService()

        vpnLoopThread = Thread({ runVpnLoop() }, "NullRouteVPNLoop").apply { start() }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN Service Destroyed")
        VpnStateTracker.isRunning.value = false
        unregisterNetworkCallback()
        stopSweeper()
        cleanup()
        vpnLoopThread?.interrupt()
        vpnLoopThread = null
        super.onDestroy()
    }

    private fun reloadBlockedDomainsCache() {
        try {
            cachedBlockedDomains = repository.getBlockedDomainStrings()
            Log.d(TAG, "Reloaded in-memory blocklist cache: ${cachedBlockedDomains.size} domains")
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading blocklist cache", e)
        }
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network switched/available: recreating protected upstream socket")
                    rebuildForwardingSocket()
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Network lost: resetting upstream socket")
                    rebuildForwardingSocket()
                }
            }
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignore
        }
        networkCallback = null
    }

    private fun startSweeper() {
        sweeperScheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay({
                sweepTimedOutQueries()
            }, 500, 500, TimeUnit.MILLISECONDS)
        }
    }

    private fun stopSweeper() {
        sweeperScheduler?.shutdownNow()
        sweeperScheduler = null
    }

    private fun sweepTimedOutQueries() {
        val now = System.currentTimeMillis()
        val expiredEntries = mutableListOf<Pair<Short, InFlightQuery>>()

        for ((upstreamTid, query) in inFlightQueries) {
            if (now - query.timestampMs > QUERY_TIMEOUT_MS) {
                if (inFlightQueries.remove(upstreamTid, query)) {
                    expiredEntries.add(upstreamTid to query)
                }
            }
        }

        for ((_, query) in expiredEntries) {
            DnsTelemetryTracker.recordTimeout()
            // Send synthetic SERVFAIL so the client fails fast (<2s) rather than hanging for 10s
            sendServfailFast(query)
        }
    }

    private fun sendServfailFast(query: InFlightQuery) {
        try {
            val servfailPayload = buildDnsErrorResponse(query.originalQuestionSection, query.clientTid, 2) // RCODE 2 = SERVFAIL
            val out = vpnOutputStream ?: return

            val responsePacket = if (query.isIPv6) {
                buildIpV6UdpPacket(
                    srcIp = IPV6_DNS_SERVER,
                    destIp = query.clientIp,
                    srcPort = 53,
                    destPort = query.clientPort,
                    payload = servfailPayload,
                    payloadLen = servfailPayload.size
                )
            } else {
                buildIpUdpPacket(
                    srcIp = byteArrayOf(10, 0, 0, 1),
                    destIp = query.clientIp,
                    srcPort = 53,
                    destPort = query.clientPort,
                    payload = servfailPayload,
                    payloadLen = servfailPayload.size
                )
            }

            synchronized(out) {
                out.write(responsePacket)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Synchronized
    private fun rebuildForwardingSocket() {
        try {
            forwardingSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        forwardingSocket = null
        getOrCreateForwardingSocket()
    }

    private fun getOrCreateForwardingSocket(): DatagramSocket? {
        val s1 = forwardingSocket
        if (s1 != null && !s1.isClosed) {
            return s1
        }
        return synchronized(this) {
            val s2 = forwardingSocket
            if (s2 != null && !s2.isClosed) {
                s2
            } else {
                try {
                    val newSocket = DatagramSocket() // Non-blocking dedicated read loop
                    protect(newSocket)
                    forwardingSocket = newSocket

                    // Start dedicated asynchronous receiver thread if not running
                    if (receiverThread == null || !receiverThread!!.isAlive) {
                        receiverThread = Thread({ runUpstreamReceiverLoop(newSocket) }, "NullRouteUpstreamReceiver").apply {
                            start()
                        }
                    }

                    newSocket
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating protected DatagramSocket", e)
                    null
                }
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "nullroute_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "NullRoute Filter",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("NullRoute Blocker Active")
            .setContentText("High-efficiency system blocker is protecting your focus.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun runVpnLoop() {
        try {
            val builder = Builder()
            builder.setSession("NullRouteVPN")

            // IPv4 Setup
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("10.0.0.0", 24)
            builder.addDnsServer("10.0.0.1")

            // IPv6 Setup
            builder.addAddress("fd00:a:b:c::2", 128)
            builder.addRoute("fd00:a:b:c::", 64)
            builder.addDnsServer("fd00:a:b:c::1")

            builder.setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            vpnOutputStream = outputStream

            // Initialize upstream socket and async receiver
            getOrCreateForwardingSocket()

            val buffer = ByteArray(16384)
            Log.i(TAG, "VPN Interface established, starting packet read loop")

            while (!Thread.currentThread().isInterrupted) {
                val length = inputStream.read(buffer)
                if (length < 0) {
                    Log.i(TAG, "EOF from VPN interface")
                    break
                }
                if (length == 0) continue

                try {
                    processPacket(buffer, length, outputStream)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling packet", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Loop aborted", e)
        } finally {
            Log.i(TAG, "VPN Loop exiting")
            if (Thread.currentThread() == vpnLoopThread) {
                VpnStateTracker.isRunning.value = false
                vpnLoopThread = null
            }
            cleanup()
        }
    }

    private fun runUpstreamReceiverLoop(socket: DatagramSocket) {
        val recvBuffer = ByteArray(2048)
        Log.i(TAG, "Dedicated Async Upstream Receiver started")

        while (!Thread.currentThread().isInterrupted && !socket.isClosed) {
            try {
                val packet = DatagramPacket(recvBuffer, recvBuffer.size)
                socket.receive(packet) // Blocks on dedicated daemon thread (0 lock contention!)

                val length = packet.length
                if (length < 12) continue

                val upstreamTid = getShort(recvBuffer, 0).toShort()
                val query = inFlightQueries.remove(upstreamTid) ?: continue

                val latency = System.currentTimeMillis() - query.timestampMs
                DnsTelemetryTracker.recordLatency(latency)

                val payload = ByteArray(length)
                System.arraycopy(recvBuffer, 0, payload, 0, length)

                // Parse upstream TTL dynamically (clamped between 60s and 3600s)
                val ttlMs = extractDnsTtlMs(payload, length)
                dnsResponseCache[DnsCacheKey(query.domain.lowercase(Locale.US), query.qType)] = CachedDnsResponse(
                    timestampMs = System.currentTimeMillis(),
                    ttlMs = ttlMs,
                    payload = payload
                )

                // Rewrite UpstreamTID back to ClientTID
                payload[0] = (query.clientTid.toInt() shr 8).toByte()
                payload[1] = (query.clientTid.toInt() and 0xFF).toByte()

                val out = vpnOutputStream ?: continue
                val responsePacket = if (query.isIPv6) {
                    buildIpV6UdpPacket(
                        srcIp = IPV6_DNS_SERVER,
                        destIp = query.clientIp,
                        srcPort = 53,
                        destPort = query.clientPort,
                        payload = payload,
                        payloadLen = length
                    )
                } else {
                    buildIpUdpPacket(
                        srcIp = byteArrayOf(10, 0, 0, 1),
                        destIp = query.clientIp,
                        srcPort = 53,
                        destPort = query.clientPort,
                        payload = payload,
                        payloadLen = length
                    )
                }

                synchronized(out) {
                    out.write(responsePacket)
                }
            } catch (e: Exception) {
                if (socket.isClosed || Thread.currentThread().isInterrupted) break
                Log.w(TAG, "Upstream receiver read error", e)
            }
        }
        Log.i(TAG, "Upstream receiver stopped")
    }

    private fun processPacket(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 20) return

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val isIPv4 = (versionAndIhl and 0xF0) == 0x40
        val isIPv6 = (versionAndIhl and 0xF0) == 0x60

        if (isIPv4) {
            val ihl = (versionAndIhl and 0x0F) * 4
            processIPv4Packet(buffer, length, ihl, outputStream)
        } else if (isIPv6) {
            processIPv6Packet(buffer, length, outputStream)
        }
    }

    private fun processIPv4Packet(buffer: ByteArray, length: Int, ihl: Int, outputStream: FileOutputStream) {
        if (length < ihl + 8) return

        val protocol = buffer[9].toInt() and 0xFF

        // 1. TCP Port 53 Handling: Fast Reset (TCP RST)
        if (protocol == 6) { // TCP
            val srcPort = getShort(buffer, ihl)
            val destPort = getShort(buffer, ihl + 2)
            if (destPort == 53 && length >= ihl + 20) {
                val flags = buffer[ihl + 13].toInt() and 0xFF
                if ((flags and 0x02) != 0) { // TCP SYN
                    val clientIp = ByteArray(4)
                    System.arraycopy(buffer, 12, clientIp, 0, 4)
                    val incomingSeq = getInt(buffer, ihl + 4)
                    val rstPacket = buildIpTcpRstPacket(
                        srcIp = byteArrayOf(10, 0, 0, 1),
                        destIp = clientIp,
                        srcPort = 53,
                        destPort = srcPort,
                        ackNumber = incomingSeq + 1
                    )
                    synchronized(outputStream) {
                        outputStream.write(rstPacket)
                    }
                }
            }
            return
        }

        // 2. UDP Protocol (17)
        if (protocol != 17) return

        val srcPort = getShort(buffer, ihl)
        val destPort = getShort(buffer, ihl + 2)

        if (destPort == 53) {
            val dnsOffset = ihl + 8
            val dnsLen = getShort(buffer, ihl + 4) - 8
            if (dnsLen < 12 || dnsOffset + dnsLen > length) return

            val qCount = getShort(buffer, dnsOffset + 4)
            if (qCount > 0) {
                val (domain, endOffset) = parseDomain(buffer, dnsOffset + 12)
                if (domain.isNotEmpty()) {
                    val qType = if (endOffset + 2 <= length) getShort(buffer, endOffset) else 1
                    val clientTid = getShort(buffer, dnsOffset).toShort()

                    DnsTelemetryTracker.recordQuery(domain, qType)

                    val clientIp = ByteArray(4)
                    System.arraycopy(buffer, 12, clientIp, 0, 4)

                    if (isDomainBlocked(domain)) {
                        DnsTelemetryTracker.recordBlockedQuery()
                        // Synthesize fast NXDOMAIN
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val questionSection = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, questionSection, 0, dnsQuestionLen)

                        val responseDns = buildDnsErrorResponse(questionSection, clientTid, 3) // NXDOMAIN (RCODE 3)

                        val responsePacket = buildIpUdpPacket(
                            srcIp = byteArrayOf(10, 0, 0, 1),
                            destIp = clientIp,
                            srcPort = 53,
                            destPort = srcPort,
                            payload = responseDns,
                            payloadLen = responseDns.size
                        )
                        synchronized(outputStream) {
                            outputStream.write(responsePacket)
                        }
                    } else {
                        DnsTelemetryTracker.recordAllowedQuery()

                        // Check Case-Insensitive, Type-Aware Cache: (domain.lowercase(), qType)
                        val cacheKey = DnsCacheKey(domain.lowercase(Locale.US), qType)
                        val now = System.currentTimeMillis()
                        val cached = dnsResponseCache[cacheKey]
                        if (cached != null && (now - cached.timestampMs) < cached.ttlMs) {
                            DnsTelemetryTracker.recordCacheHit()
                            val cachedPayload = cached.payload.copyOf()
                            cachedPayload[0] = (clientTid.toInt() shr 8).toByte()
                            cachedPayload[1] = (clientTid.toInt() and 0xFF).toByte()

                            // Echo back the exact question section casing requested by the client (0x20 bit matching)
                            val dnsQuestionLen = endOffset + 4 - dnsOffset
                            if (dnsQuestionLen > 12 && 12 + (dnsQuestionLen - 12) <= cachedPayload.size) {
                                System.arraycopy(buffer, dnsOffset + 12, cachedPayload, 12, dnsQuestionLen - 12)
                            }

                            val ipUdpResponse = buildIpUdpPacket(
                                srcIp = byteArrayOf(10, 0, 0, 1),
                                destIp = clientIp,
                                srcPort = 53,
                                destPort = srcPort,
                                payload = cachedPayload,
                                payloadLen = cachedPayload.size
                            )
                            synchronized(outputStream) {
                                outputStream.write(ipUdpResponse)
                            }
                            return
                        }

                        DnsTelemetryTracker.recordCacheMiss()

                        // Extract question section for fast-fail fallback
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val questionSection = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, questionSection, 0, dnsQuestionLen)

                        val query = InFlightQuery(
                            clientTid = clientTid,
                            clientIp = clientIp,
                            clientPort = srcPort,
                            domain = domain,
                            qType = qType,
                            isIPv6 = false,
                            timestampMs = now,
                            originalQuestionSection = questionSection
                        )

                        val dnsQuery = ByteArray(dnsLen)
                        System.arraycopy(buffer, dnsOffset, dnsQuery, 0, dnsLen)

                        forwardQueryAsync(query, dnsQuery, dnsLen)
                    }
                }
            }
        }
    }

    private fun processIPv6Packet(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 48) return

        val nextHeader = buffer[6].toInt() and 0xFF

        // 1. TCP Port 53 Fast-Fail
        if (nextHeader == 6) { // TCP
            val srcPort = getShort(buffer, 40)
            val destPort = getShort(buffer, 42)
            if (destPort == 53 && length >= 60) {
                val flags = buffer[53].toInt() and 0xFF
                if ((flags and 0x02) != 0) { // TCP SYN
                    val clientIp = ByteArray(16)
                    System.arraycopy(buffer, 8, clientIp, 0, 16)
                    val incomingSeq = getInt(buffer, 44)
                    val rstPacket = buildIpV6TcpRstPacket(
                        srcIp = IPV6_DNS_SERVER,
                        destIp = clientIp,
                        srcPort = 53,
                        destPort = srcPort,
                        ackNumber = incomingSeq + 1
                    )
                    synchronized(outputStream) {
                        outputStream.write(rstPacket)
                    }
                }
            }
            return
        }

        // 2. UDP Protocol (17)
        if (nextHeader != 17) return

        val srcPort = getShort(buffer, 40)
        val destPort = getShort(buffer, 42)

        if (destPort == 53) {
            val dnsOffset = 48
            val dnsLen = getShort(buffer, 44) - 8
            if (dnsLen < 12 || dnsOffset + dnsLen > length) return

            val qCount = getShort(buffer, dnsOffset + 4)
            if (qCount > 0) {
                val (domain, endOffset) = parseDomain(buffer, dnsOffset + 12)
                if (domain.isNotEmpty()) {
                    val qType = if (endOffset + 2 <= length) getShort(buffer, endOffset) else 1
                    val clientTid = getShort(buffer, dnsOffset).toShort()

                    DnsTelemetryTracker.recordQuery(domain, qType)

                    val clientIp = ByteArray(16)
                    System.arraycopy(buffer, 8, clientIp, 0, 16)

                    if (isDomainBlocked(domain)) {
                        DnsTelemetryTracker.recordBlockedQuery()
                        // Synthesize NXDOMAIN
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val questionSection = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, questionSection, 0, dnsQuestionLen)

                        val responseDns = buildDnsErrorResponse(questionSection, clientTid, 3) // NXDOMAIN

                        val responsePacket = buildIpV6UdpPacket(
                            srcIp = IPV6_DNS_SERVER,
                            destIp = clientIp,
                            srcPort = 53,
                            destPort = srcPort,
                            payload = responseDns,
                            payloadLen = responseDns.size
                        )
                        synchronized(outputStream) {
                            outputStream.write(responsePacket)
                        }
                    } else {
                        DnsTelemetryTracker.recordAllowedQuery()

                        // Check Case-Insensitive, Type-Aware Cache: (domain.lowercase(), qType)
                        val cacheKey = DnsCacheKey(domain.lowercase(Locale.US), qType)
                        val now = System.currentTimeMillis()
                        val cached = dnsResponseCache[cacheKey]
                        if (cached != null && (now - cached.timestampMs) < cached.ttlMs) {
                            DnsTelemetryTracker.recordCacheHit()
                            val cachedPayload = cached.payload.copyOf()
                            cachedPayload[0] = (clientTid.toInt() shr 8).toByte()
                            cachedPayload[1] = (clientTid.toInt() and 0xFF).toByte()

                            // Echo back the exact question section casing requested by the client (0x20 bit matching)
                            val dnsQuestionLen = endOffset + 4 - dnsOffset
                            if (dnsQuestionLen > 12 && 12 + (dnsQuestionLen - 12) <= cachedPayload.size) {
                                System.arraycopy(buffer, dnsOffset + 12, cachedPayload, 12, dnsQuestionLen - 12)
                            }

                            val ipV6Response = buildIpV6UdpPacket(
                                srcIp = IPV6_DNS_SERVER,
                                destIp = clientIp,
                                srcPort = 53,
                                destPort = srcPort,
                                payload = cachedPayload,
                                payloadLen = cachedPayload.size
                            )
                            synchronized(outputStream) {
                                outputStream.write(ipV6Response)
                            }
                            return
                        }

                        DnsTelemetryTracker.recordCacheMiss()

                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val questionSection = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, questionSection, 0, dnsQuestionLen)

                        val query = InFlightQuery(
                            clientTid = clientTid,
                            clientIp = clientIp,
                            clientPort = srcPort,
                            domain = domain,
                            qType = qType,
                            isIPv6 = true,
                            timestampMs = now,
                            originalQuestionSection = questionSection
                        )

                        val dnsQuery = ByteArray(dnsLen)
                        System.arraycopy(buffer, dnsOffset, dnsQuery, 0, dnsLen)

                        // Forward via IPv4 upstream transport (Fixes ENETUNREACH on IPv6!)
                        forwardQueryAsync(query, dnsQuery, dnsLen)
                    }
                }
            }
        }
    }

    private fun forwardQueryAsync(query: InFlightQuery, dnsQuery: ByteArray, dnsLen: Int) {
        forwardExecutor.submit {
            val socket = getOrCreateForwardingSocket()
            if (socket == null) {
                sendServfailFast(query)
                DnsTelemetryTracker.recordNetworkError()
                return@submit
            }

            val upstreamTid = allocateUpstreamTid(query)
            if (upstreamTid == (-1).toShort()) {
                sendServfailFast(query)
                DnsTelemetryTracker.recordNetworkError()
                return@submit
            }

            // Rewrite TID to upstreamTid
            dnsQuery[0] = (upstreamTid.toInt() shr 8).toByte()
            dnsQuery[1] = (upstreamTid.toInt() and 0xFF).toByte()

            var sendSuccess = false

            // Parallel Dual-Upstream Race: Send to Google (8.8.8.8) and Cloudflare (1.1.1.1) simultaneously
            try {
                val p1 = DatagramPacket(dnsQuery, dnsLen, PRIMARY_UPSTREAM_V4, 53)
                socket.send(p1)
                sendSuccess = true
            } catch (e: IOException) {
                // Secondary will attempt
            }

            try {
                val p2 = DatagramPacket(dnsQuery, dnsLen, SECONDARY_UPSTREAM_V4, 53)
                socket.send(p2)
                sendSuccess = true
            } catch (e: IOException) {
                // Ignore
            }

            if (!sendSuccess) {
                // Fallback to tertiary upstream
                try {
                    val p3 = DatagramPacket(dnsQuery, dnsLen, TERTIARY_UPSTREAM_V4, 53)
                    socket.send(p3)
                    sendSuccess = true
                } catch (e: IOException) {
                    // All upstreams failed
                }
            }

            if (!sendSuccess) {
                inFlightQueries.remove(upstreamTid)
                sendServfailFast(query)
                DnsTelemetryTracker.recordNetworkError()
            }
        }
    }

    private fun allocateUpstreamTid(query: InFlightQuery): Short {
        for (attempt in 0..200) {
            val candidate = (tidSequence.incrementAndGet() and 0x7FFF).toShort()
            if (inFlightQueries.putIfAbsent(candidate, query) == null) {
                return candidate
            }
        }
        return -1
    }

    private fun extractDnsTtlMs(payload: ByteArray, length: Int): Long {
        try {
            if (length < 12) return MIN_CACHE_TTL_MS
            val anCount = getShort(payload, 6)
            if (anCount <= 0) return MIN_CACHE_TTL_MS

            // Skip Header (12 bytes) and Question Section
            val (_, endQuestionOffset) = parseDomain(payload, 12)
            var offset = endQuestionOffset + 4 // QTYPE (2) + QCLASS (2)

            if (offset + 10 <= length) {
                // Answer format: Name (compressed/uncompressed) + Type(2) + Class(2) + TTL(4) + DataLen(2)
                if ((payload[offset].toInt() and 0xC0) == 0xC0) {
                    offset += 2 // Pointer is 2 bytes
                } else {
                    val (_, endNameOffset) = parseDomain(payload, offset)
                    offset = endNameOffset
                }

                if (offset + 8 <= length) {
                    val ttlSeconds = getInt(payload, offset + 4).toLong() and 0xFFFFFFFFL
                    val ttlMs = ttlSeconds * 1000L
                    return ttlMs.coerceIn(MIN_CACHE_TTL_MS, MAX_CACHE_TTL_MS)
                }
            }
        } catch (e: Exception) {
            // Fallback to default minimum TTL
        }
        return MIN_CACHE_TTL_MS
    }

    private fun buildDnsErrorResponse(questionSection: ByteArray, clientTid: Short, rcode: Int): ByteArray {
        val totalLen = questionSection.size
        val buf = ByteArray(totalLen)
        System.arraycopy(questionSection, 0, buf, 0, totalLen)

        // Set Client TID
        buf[0] = (clientTid.toInt() shr 8).toByte()
        buf[1] = (clientTid.toInt() and 0xFF).toByte()

        // Set Standard Response Flags: QR=1, AA=1, RD=1, RA=1, RCODE
        buf[2] = 0x81.toByte()
        buf[3] = (0x80 or (rcode and 0x0F)).toByte()

        // Zero out Answer/Authority/Additional counts
        for (i in 6..11) {
            buf[i] = 0
        }
        return buf
    }

    private fun isDomainBlocked(domain: String): Boolean {
        return cachedBlockedDomains.any { domain.equals(it, ignoreCase = true) || domain.endsWith(".$it", ignoreCase = true) }
    }

    private fun getShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun getInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun parseDomain(data: ByteArray, offset: Int): Pair<String, Int> {
        var currentOffset = offset
        val sb = StringBuilder()
        while (currentOffset < data.size) {
            val length = data[currentOffset].toInt() and 0xFF
            if (length == 0) {
                currentOffset++
                break
            }
            if ((length and 0xC0) == 0xC0) {
                currentOffset += 2 // Compressed pointer
                break
            }
            if (sb.isNotEmpty()) {
                sb.append(".")
            }
            if (currentOffset + 1 + length > data.size) {
                break
            }
            sb.append(String(data, currentOffset + 1, length, Charsets.US_ASCII))
            currentOffset += 1 + length
        }
        return Pair(sb.toString(), currentOffset)
    }

    private fun buildIpUdpPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 28 + payloadLen
        val buf = ByteArray(totalLen)

        buf[0] = 0x45.toByte()
        buf[1] = 0x00.toByte()
        buf[2] = (totalLen shr 8).toByte()
        buf[3] = (totalLen and 0xFF).toByte()
        buf[4] = 0x00.toByte()
        buf[5] = 0x00.toByte()
        buf[6] = 0x40.toByte()
        buf[7] = 0x00.toByte()
        buf[8] = 0x40.toByte()
        buf[9] = 17.toByte() // UDP

        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(destIp, 0, buf, 16, 4)

        val ipChecksum = calculateChecksum(buf, 20)
        buf[10] = (ipChecksum.toInt() shr 8).toByte()
        buf[11] = (ipChecksum.toInt() and 0xFF).toByte()

        buf[20] = (srcPort shr 8).toByte()
        buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = (destPort shr 8).toByte()
        buf[23] = (destPort and 0xFF).toByte()

        val udpLen = 8 + payloadLen
        buf[24] = (udpLen shr 8).toByte()
        buf[25] = (udpLen and 0xFF).toByte()
        buf[26] = 0x00.toByte()
        buf[27] = 0x00.toByte()

        System.arraycopy(payload, 0, buf, 28, payloadLen)
        return buf
    }

    private fun buildIpV6UdpPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        payload: ByteArray,
        payloadLen: Int
    ): ByteArray {
        val totalLen = 48 + payloadLen
        val buf = ByteArray(totalLen)

        // Version 6
        buf[0] = 0x60.toByte()
        val udpLen = 8 + payloadLen
        buf[4] = (udpLen shr 8).toByte()
        buf[5] = (udpLen and 0xFF).toByte()
        buf[6] = 17.toByte() // UDP
        buf[7] = 64.toByte() // Hop Limit

        System.arraycopy(srcIp, 0, buf, 8, 16)
        System.arraycopy(destIp, 0, buf, 24, 16)

        buf[40] = (srcPort shr 8).toByte()
        buf[41] = (srcPort and 0xFF).toByte()
        buf[42] = (destPort shr 8).toByte()
        buf[43] = (destPort and 0xFF).toByte()

        buf[44] = (udpLen shr 8).toByte()
        buf[45] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, buf, 48, payloadLen)
        return buf
    }

    private fun buildIpTcpRstPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        ackNumber: Int
    ): ByteArray {
        val totalLen = 40 // 20 bytes IP + 20 bytes TCP
        val buf = ByteArray(totalLen)

        buf[0] = 0x45.toByte()
        buf[2] = 0x00.toByte()
        buf[3] = 40.toByte()
        buf[8] = 0x40.toByte()
        buf[9] = 6.toByte() // TCP

        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(destIp, 0, buf, 16, 4)

        val ipChecksum = calculateChecksum(buf, 20)
        buf[10] = (ipChecksum.toInt() shr 8).toByte()
        buf[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // TCP Ports
        buf[20] = (srcPort shr 8).toByte()
        buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = (destPort shr 8).toByte()
        buf[23] = (destPort and 0xFF).toByte()

        // ACK Number
        buf[28] = (ackNumber shr 24).toByte()
        buf[29] = (ackNumber shr 16).toByte()
        buf[30] = (ackNumber shr 8).toByte()
        buf[31] = (ackNumber and 0xFF).toByte()

        // Header Length (5 words = 20 bytes) -> 0x50
        buf[32] = 0x50.toByte()
        // Flags: RST (0x04) | ACK (0x10) = 0x14
        buf[33] = 0x14.toByte()

        return buf
    }

    private fun buildIpV6TcpRstPacket(
        srcIp: ByteArray,
        destIp: ByteArray,
        srcPort: Int,
        destPort: Int,
        ackNumber: Int
    ): ByteArray {
        val totalLen = 60 // 40 bytes IPv6 + 20 bytes TCP
        val buf = ByteArray(totalLen)

        buf[0] = 0x60.toByte()
        buf[4] = 0x00.toByte()
        buf[5] = 20.toByte() // TCP length
        buf[6] = 6.toByte()  // Next Header: TCP
        buf[7] = 64.toByte()

        System.arraycopy(srcIp, 0, buf, 8, 16)
        System.arraycopy(destIp, 0, buf, 24, 16)

        // TCP Ports
        buf[40] = (srcPort shr 8).toByte()
        buf[41] = (srcPort and 0xFF).toByte()
        buf[42] = (destPort shr 8).toByte()
        buf[43] = (destPort and 0xFF).toByte()

        // ACK Number
        buf[48] = (ackNumber shr 24).toByte()
        buf[49] = (ackNumber shr 16).toByte()
        buf[50] = (ackNumber shr 8).toByte()
        buf[51] = (ackNumber and 0xFF).toByte()

        buf[52] = 0x50.toByte()
        buf[53] = 0x14.toByte() // RST | ACK

        return buf
    }

    private fun calculateChecksum(buf: ByteArray, length: Int): Short {
        var sum = 0
        var i = 0
        while (i < length - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < length) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv()).toShort()
    }

    private fun cleanup() {
        Log.i(TAG, "Cleaning up VPN interface & sockets...")
        try {
            forwardingSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        forwardingSocket = null

        try {
            vpnOutputStream?.close()
        } catch (e: Exception) {
            // Ignore
        }
        vpnOutputStream = null

        try {
            vpnInterface?.close()
            Log.i(TAG, "VPN interface closed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
    }
}
