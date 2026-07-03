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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val dnsExecutor = Executors.newCachedThreadPool()
    private lateinit var repository: BlocklistRepository

    override fun onCreate() {
        super.onCreate()
        repository = SharedPreferencesBlocklistRepository(applicationContext)
        Log.d(TAG, "VPN Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "VPN Service Starting")
        startForegroundService()

        if (vpnThread == null || !vpnThread!!.isAlive) {
            vpnThread = Thread({ runVpnLoop() }, "NullRouteVPNThread").apply { start() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "VPN Service Destroyed")
        cleanup()
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
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun runVpnLoop() {
        try {
            val builder = Builder()
            builder.setSession("NullRouteVPN")
            builder.addAddress("10.0.0.2", 32)
            builder.addRoute("10.0.0.0", 24)
            builder.addDnsServer("10.0.0.1")

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                return
            }

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            Log.d(TAG, "VPN Interface established, starting packet read loop")

            while (!Thread.currentThread().isInterrupted) {
                val length = inputStream.read(buffer)
                if (length <= 0) break

                try {
                    processPacket(buffer, length, outputStream)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling packet", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Loop aborted due to exception", e)
        } finally {
            cleanup()
        }
    }

    private fun processPacket(buffer: ByteArray, length: Int, outputStream: FileOutputStream) {
        if (length < 20) return

        // Verify IPv4
        val versionAndIhl = buffer[0].toInt() and 0xFF
        val isIPv4 = (versionAndIhl and 0xF0) == 0x40
        if (!isIPv4) return

        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl + 8) return

        // Verify UDP Protocol (17)
        val protocol = buffer[9].toInt() and 0xFF
        if (protocol != 17) return

        // Extract Ports
        val srcPort = getShort(buffer, ihl)
        val destPort = getShort(buffer, ihl + 2)

        // Intercept DNS queries targeting standard DNS port 53
        if (destPort == 53) {
            val dnsOffset = ihl + 8
            val dnsLen = getShort(buffer, ihl + 4) - 8 // UDP Length - 8 bytes UDP Header
            if (dnsLen < 12 || dnsOffset + dnsLen > length) return

            // Parse transaction ID and domain name
            val qCount = getShort(buffer, dnsOffset + 4)

            if (qCount > 0) {
                val (domain, endOffset) = parseDomain(buffer, dnsOffset + 12)
                if (domain.isNotEmpty()) {
                    val clientIp = ByteArray(4)
                    System.arraycopy(buffer, 12, clientIp, 0, 4) // Source IP

                    if (isDomainBlocked(domain)) {
                        Log.i(TAG, "BLOCKED: $domain")
                        // Return NXDOMAIN (Name Error)
                        val dnsQuestionLen = endOffset + 4 - dnsOffset
                        val responseDns = ByteArray(dnsQuestionLen)
                        System.arraycopy(buffer, dnsOffset, responseDns, 0, dnsQuestionLen)

                        // Set Response Flag & NXDOMAIN (0x8183)
                        responseDns[2] = 0x81.toByte()
                        responseDns[3] = 0x83.toByte()
                        // Zero out answer/authority/additional records count
                        for (i in 6..11) responseDns[i] = 0

                        val responsePacket = buildIpUdpPacket(
                            srcIp = byteArrayOf(10, 0, 0, 1), // Fake DNS Server IP
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
                                Log.w(TAG, "DNS resolution failed or timed out for: $domain")
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

        // Version & IHL
        buf[0] = 0x45.toByte()
        buf[1] = 0x00.toByte()
        // Total Length
        buf[2] = (totalLen shr 8).toByte()
        buf[3] = (totalLen and 0xFF).toByte()
        // ID
        buf[4] = 0x00.toByte()
        buf[5] = 0x00.toByte()
        // Flags & Fragment Offset (Don't Fragment)
        buf[6] = 0x40.toByte()
        buf[7] = 0x00.toByte()
        // TTL
        buf[8] = 0x40.toByte()
        // Protocol (UDP = 17)
        buf[9] = 17.toByte()

        // Checksum placeholder at 10, 11

        // Source and Destination IPs
        System.arraycopy(srcIp, 0, buf, 12, 4)
        System.arraycopy(destIp, 0, buf, 16, 4)

        // Compute and insert IP Checksum
        val ipChecksum = calculateChecksum(buf, 20)
        buf[10] = (ipChecksum.toInt() shr 8).toByte()
        buf[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // UDP Ports
        buf[20] = (srcPort shr 8).toByte()
        buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = (destPort shr 8).toByte()
        buf[23] = (destPort and 0xFF).toByte()

        // UDP Length
        val udpLen = 8 + payloadLen
        buf[24] = (udpLen shr 8).toByte()
        buf[25] = (udpLen and 0xFF).toByte()

        // UDP Checksum (0 to disable)
        buf[26] = 0x00.toByte()
        buf[27] = 0x00.toByte()

        // Copy Payload
        System.arraycopy(payload, 0, buf, 28, payloadLen)

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
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // Ignore
        }
        vpnInterface = null
        vpnThread = null
    }
}
