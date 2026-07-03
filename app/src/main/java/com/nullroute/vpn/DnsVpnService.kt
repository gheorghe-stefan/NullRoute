package com.nullroute.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nullroute.data.BlocklistRepository
import com.nullroute.data.SharedPreferencesBlocklistRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "NullRouteVPN"

        // IPv6 DNS Server address representation (fd00:a:b:c::1)
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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    
    @Volatile
    private var activeThread: Thread? = null
    
    private val dnsExecutor = Executors.newCachedThreadPool()
    private lateinit var repository: BlocklistRepository

    override fun onCreate() {
        super.onCreate()
        repository = SharedPreferencesBlocklistRepository(applicationContext)
        Log.i(TAG, "VPN Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN Service Starting")
        
        // Thread-safe prevention of duplicate loop/interface initializations
        if (activeThread != null && activeThread!!.isAlive) {
            Log.i(TAG, "VPN Thread already running, ignoring duplicate start command")
            return START_STICKY
        }

        VpnStateTracker.isRunning.value = true
        startForegroundService()

        activeThread = Thread({ runVpnLoop() }, "NullRouteVPNThread").apply { start() }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN Service Destroyed")
        VpnStateTracker.isRunning.value = false
        cleanup()
        activeThread?.interrupt()
        activeThread = null
        super.onDestroy()
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
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("NullRoute Blocker Active")
            .setContentText("System-wide website blocker is protecting your focus.")
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

            // IPv6 Setup (Prevents IPv6 network bypasses)
            builder.addAddress("fd00:a:b:c::2", 128)
            builder.addRoute("fd00:a:b:c::", 64)
            builder.addDnsServer("fd00:a:b:c::1")

            // Explicitly configure MTU to prevent network engine crashes
            builder.setMtu(1500)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            Log.i(TAG, "VPN Interface established, starting packet read loop")

            while (!Thread.currentThread().isInterrupted) {
                val length = inputStream.read(buffer)
                if (length < 0) {
                    Log.i(TAG, "EOF from VPN interface (read returned $length)")
                    break
                }
                if (length == 0) {
                    continue
                }

                try {
                    processPacket(buffer, length, outputStream)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling packet", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Loop aborted due to exception", e)
        } finally {
            Log.i(TAG, "VPN Loop thread exiting. Thread: " + Thread.currentThread().name)
            if (Thread.currentThread() == activeThread) {
                Log.i(TAG, "Exiting thread is active thread, resetting isRunning state flow to false")
                VpnStateTracker.isRunning.value = false
                activeThread = null
            } else {
                Log.i(TAG, "Exiting thread is NOT active thread (old thread), ignoring state flow reset")
            }
            cleanup()
        }
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

        // Verify UDP Protocol (17)
        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return

        // Extract Ports
        val srcPort = getShort(buffer, ihl)
        val destPort = getShort(buffer, ihl + 2)

        if (destPort == 53) {
            val dnsOffset = ihl + 8
            val dnsLen = getShort(buffer, ihl + 4) - 8 // UDP Length - 8 bytes UDP Header
            if (dnsLen < 12 || dnsOffset + dnsLen > length) return

            val qCount = getShort(buffer, dnsOffset + 4)
            if (qCount > 0) {
                val (domain, endOffset) = parseDomain(buffer, dnsOffset + 12)
                if (domain.isNotEmpty()) {
                    val clientIp = ByteArray(4)
                    System.arraycopy(buffer, 12, clientIp, 0, 4) // Source IP

                    Log.i(TAG, "IPv4 DNS Lookup: $domain")

                    if (isDomainBlocked(domain)) {
                        Log.i(TAG, "BLOCKED (IPv4): $domain")
                        // Return NXDOMAIN (Name Error)
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val responseDns = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, responseDns, 0, dnsQuestionLen)

                        // Set Response Flag & NXDOMAIN (0x8183)
                        responseDns[2] = 0x81.toByte()
                        responseDns[3] = 0x83.toByte()
                        for (i in 6..11) responseDns[i] = 0

                        val responsePacket = buildIpUdpPacket(
                            srcIp = byteArrayOf(10, 0, 0, 1),
                            destIp = clientIp,
                            srcPort = 53,
                            destPort = srcPort,
                            payload = responseDns,
                            payloadLen = dnsQuestionLen
                        )
                        synchronized(outputStream) {
                            outputStream.write(responsePacket)
                        }
                    } else {
                        // Forward query to Google Public DNS
                        dnsExecutor.submit {
                            try {
                                val dnsQuery = ByteArray(dnsLen)
                                System.arraycopy(buffer, dnsOffset, dnsQuery, 0, dnsLen)

                                DatagramSocket().use { socket ->
                                    socket.soTimeout = 2500
                                    val forwardPacket = DatagramPacket(
                                        dnsQuery,
                                        dnsLen,
                                        InetAddress.getByName("8.8.8.8"),
                                        53
                                    )
                                    socket.send(forwardPacket)

                                    val responseBuf = ByteArray(2048)
                                    val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
                                    socket.receive(receivePacket)

                                    val ipUdpResponse = buildIpUdpPacket(
                                        srcIp = byteArrayOf(10, 0, 0, 1),
                                        destIp = clientIp,
                                        srcPort = 53,
                                        destPort = srcPort,
                                        payload = responseBuf,
                                        payloadLen = receivePacket.length
                                    )

                                    synchronized(outputStream) {
                                        outputStream.write(ipUdpResponse)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "DNS IPv4 resolution failed/timeout for: $domain")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun processIPv6Packet(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 48) return

        // Verify UDP Protocol (17) in IPv6 nextHeader field (offset 6)
        val nextHeader = buffer[6].toInt() and 0xFF
        if (nextHeader != 17) return

        // Extract Ports (IPv6 Header is fixed 40 bytes, UDP starts at 40)
        val srcPort = getShort(buffer, 40)
        val destPort = getShort(buffer, 42)

        if (destPort == 53) {
            val dnsOffset = 48
            val dnsLen = getShort(buffer, 44) - 8 // UDP payload length
            if (dnsLen < 12 || dnsOffset + dnsLen > length) return

            val qCount = getShort(buffer, dnsOffset + 4)
            if (qCount > 0) {
                val (domain, endOffset) = parseDomain(buffer, dnsOffset + 12)
                if (domain.isNotEmpty()) {
                    val clientIp = ByteArray(16)
                    System.arraycopy(buffer, 8, clientIp, 0, 16) // Source IP is at offset 8 to 23

                    Log.i(TAG, "IPv6 DNS Lookup: $domain")

                    if (isDomainBlocked(domain)) {
                        Log.i(TAG, "BLOCKED (IPv6): $domain")
                        // Return NXDOMAIN
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val responseDns = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, responseDns, 0, dnsQuestionLen)

                        responseDns[2] = 0x81.toByte()
                        responseDns[3] = 0x83.toByte()
                        for (i in 6..11) responseDns[i] = 0

                        val responsePacket = buildIpV6UdpPacket(
                            srcIp = IPV6_DNS_SERVER,
                            destIp = clientIp,
                            srcPort = 53,
                            destPort = srcPort,
                            payload = responseDns,
                            payloadLen = dnsQuestionLen
                        )
                        synchronized(outputStream) {
                            outputStream.write(responsePacket)
                        }
                    } else {
                        // Forward query to Google Public IPv6 DNS: [2001:4860:4860::8888]
                        dnsExecutor.submit {
                            try {
                                val dnsQuery = ByteArray(dnsLen)
                                System.arraycopy(buffer, dnsOffset, dnsQuery, 0, dnsLen)

                                DatagramSocket().use { socket ->
                                    socket.soTimeout = 2500
                                    val forwardPacket = DatagramPacket(
                                        dnsQuery,
                                        dnsLen,
                                        InetAddress.getByName("2001:4860:4860::8888"),
                                        53
                                    )
                                    socket.send(forwardPacket)

                                    val responseBuf = ByteArray(2048)
                                    val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
                                    socket.receive(receivePacket)

                                    val ipV6Response = buildIpV6UdpPacket(
                                        srcIp = IPV6_DNS_SERVER,
                                        destIp = clientIp,
                                        srcPort = 53,
                                        destPort = srcPort,
                                        payload = responseBuf,
                                        payloadLen = receivePacket.length
                                    )

                                    synchronized(outputStream) {
                                        outputStream.write(ipV6Response)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "DNS IPv6 resolution failed/timeout for: $domain")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isDomainBlocked(domain: String): Boolean {
        val blockedSet = repository.getAllBlockedDomains()
        return blockedSet.any { domain.equals(it, ignoreCase = true) || domain.endsWith(".$it", ignoreCase = true) }
    }

    private fun getShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
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
        buf[9] = 17.toByte()

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

        // Version (6), Traffic Class (0), Flow Label (0) -> 0x60000000
        buf[0] = 0x60.toByte()
        buf[1] = 0x00.toByte()
        buf[2] = 0x00.toByte()
        buf[3] = 0x00.toByte()

        // Payload Length (UDP Header + DNS Payload)
        val udpLen = 8 + payloadLen
        buf[4] = (udpLen shr 8).toByte()
        buf[5] = (udpLen and 0xFF).toByte()

        // Next Header (UDP = 17)
        buf[6] = 17.toByte()

        // Hop Limit (64)
        buf[7] = 64.toByte()

        // Source and Destination IPv6 addresses (16 bytes each)
        System.arraycopy(srcIp, 0, buf, 8, 16)
        System.arraycopy(destIp, 0, buf, 24, 16)

        // UDP Ports
        buf[40] = (srcPort shr 8).toByte()
        buf[41] = (srcPort and 0xFF).toByte()
        buf[42] = (destPort shr 8).toByte()
        buf[43] = (destPort and 0xFF).toByte()

        // UDP Length
        buf[44] = (udpLen shr 8).toByte()
        buf[45] = (udpLen and 0xFF).toByte()

        // UDP Checksum (0 to disable)
        buf[46] = 0x00.toByte()
        buf[47] = 0x00.toByte()

        // Copy Payload
        System.arraycopy(payload, 0, buf, 48, payloadLen)

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
        Log.i(TAG, "Cleaning up VPN interface...")
        try {
            vpnInterface?.close()
            Log.i(TAG, "VPN interface closed successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
    }
}
