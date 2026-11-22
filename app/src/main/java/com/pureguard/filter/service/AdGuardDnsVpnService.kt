package com.pureguard.filter.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pureguard.filter.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Ultra-Lightweight Split-Tunnel DNS-Only VPN Service.
 *
 * Routes ONLY DNS queries (port 53 to 94.140.14.15 / 94.140.15.16) to AdGuard Family Protection DNS:
 * - Blocks all ads (Taboola, Outbrain, Google Ads, immodest book/clothing banners)
 * - Blocks adult/porn domains at the network level
 * - Enforces Strict SafeSearch on Google, YouTube, Bing
 * - 99.99% of internet traffic (video, web, downloads) bypasses the VPN completely -> 0% overheating, <0.1% battery!
 */
class AdGuardDnsVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        startDnsOnlyVpn()
        return START_STICKY
    }

    private fun startDnsOnlyVpn() {
        if (vpnInterface != null) return

        try {
            val builder = Builder()
                .setSession("Kodeshield – מגן הקודש (סינון פרסומות ו-DNS)")
                .setMtu(1500)
                .addAddress("10.215.173.1", 30)
                .addDnsServer(ADGUARD_FAMILY_DNS_1)
                .addDnsServer(ADGUARD_FAMILY_DNS_2)
                // Split-tunnel: route ONLY the two DNS server IPs through the tunnel!
                // All regular web/video traffic goes directly over Wi-Fi/Cellular with zero CPU overhead.
                .addRoute(ADGUARD_FAMILY_DNS_1, 32)
                .addRoute(ADGUARD_FAMILY_DNS_2, 32)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.w(TAG, "Failed to establish DNS VPN interface")
                isRunning = false
                return
            }

            isRunning = true
            PreferencesManager(applicationContext).isAutoDnsVpnEnabled = true
            Log.i(TAG, "Kodeshield Split-Tunnel AdGuard Family DNS VPN started.")

            val tunFd = vpnInterface ?: return
            serviceScope.launch(Dispatchers.IO) {
                runDnsForwardingLoop(tunFd)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting DNS VPN: ${e.message}")
            stopVpn()
        }
    }

    private fun runDnsForwardingLoop(tunFd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(tunFd.fileDescriptor)
        val outputStream = FileOutputStream(tunFd.fileDescriptor)
        val packetBuffer = ByteArray(1500)
        val dnsServerAddr = try {
            InetAddress.getByName(ADGUARD_FAMILY_DNS_1)
        } catch (e: Exception) {
            return
        }

        try {
            while (serviceScope.isActive && vpnInterface != null) {
                val length = try {
                    inputStream.read(packetBuffer)
                } catch (_: Throwable) {
                    break
                }
                if (length <= 0) continue

                // Check IPv4 packet (version == 4) and UDP protocol (17)
                val version = (packetBuffer[0].toInt() ushr 4) and 0x0F
                if (version != 4 || length < 28) continue

                val ihl = (packetBuffer[0].toInt() and 0x0F) * 4
                val protocol = packetBuffer[9].toInt() and 0xFF
                if (protocol != 17 || length < ihl + 8) continue

                val udpPayloadOffset = ihl + 8
                val udpPayloadLength = length - udpPayloadOffset
                if (udpPayloadLength <= 0 || udpPayloadLength > 1200) continue

                val requestCopy = packetBuffer.copyOf(length)
                serviceScope.launch(Dispatchers.IO) {
                    forwardSingleDnsQuery(requestCopy, ihl, udpPayloadOffset, udpPayloadLength, dnsServerAddr, outputStream)
                }
            }
        } catch (e: Throwable) {
            Log.d(TAG, "DNS loop ended: ${e.message}")
        }
    }

    private fun forwardSingleDnsQuery(
        requestPacket: ByteArray,
        ihl: Int,
        udpPayloadOffset: Int,
        udpPayloadLength: Int,
        dnsServerAddr: InetAddress,
        outputStream: FileOutputStream
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            if (!protect(socket)) {
                return
            }
            socket.soTimeout = 4000

            val outPacket = DatagramPacket(requestPacket, udpPayloadOffset, udpPayloadLength, dnsServerAddr, 53)
            socket.send(outPacket)

            val respBuf = ByteArray(1400)
            val inPacket = DatagramPacket(respBuf, respBuf.size)
            socket.receive(inPacket)
            val replyLen = inPacket.length
            if (replyLen <= 0) return

            // Build IPv4 (20 bytes) + UDP (8 bytes) + DNS response payload
            val totalLen = 28 + replyLen
            val responseIpPacket = ByteArray(totalLen)

            // IPv4 Header (20 bytes)
            responseIpPacket[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
            responseIpPacket[1] = 0x00.toByte()
            responseIpPacket[2] = ((totalLen ushr 8) and 0xFF).toByte()
            responseIpPacket[3] = (totalLen and 0xFF).toByte()
            responseIpPacket[4] = 0x00.toByte()
            responseIpPacket[5] = 0x00.toByte()
            responseIpPacket[6] = 0x40.toByte() // Don't Fragment
            responseIpPacket[7] = 0x00.toByte()
            responseIpPacket[8] = 64.toByte()   // TTL
            responseIpPacket[9] = 17.toByte()   // Protocol: UDP (17)
            responseIpPacket[10] = 0x00.toByte() // Checksum placeholder
            responseIpPacket[11] = 0x00.toByte()

            // Swap Source IP (original Dest IP 16..19) and Dest IP (original Source IP 12..15)
            System.arraycopy(requestPacket, 16, responseIpPacket, 12, 4)
            System.arraycopy(requestPacket, 12, responseIpPacket, 16, 4)

            // Compute IPv4 Header Checksum
            var sum = 0
            for (i in 0 until 20 step 2) {
                val word = ((responseIpPacket[i].toInt() and 0xFF) shl 8) or (responseIpPacket[i + 1].toInt() and 0xFF)
                sum += word
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            val checksum = sum.inv() and 0xFFFF
            responseIpPacket[10] = ((checksum ushr 8) and 0xFF).toByte()
            responseIpPacket[11] = (checksum and 0xFF).toByte()

            // UDP Header (8 bytes, starting at offset 20)
            // Swap Source Port (original Dest Port ihl+2..ihl+3) and Dest Port (original Source Port ihl..ihl+1)
            responseIpPacket[20] = requestPacket[ihl + 2]
            responseIpPacket[21] = requestPacket[ihl + 3]
            responseIpPacket[22] = requestPacket[ihl]
            responseIpPacket[23] = requestPacket[ihl + 1]

            val udpTotalLen = 8 + replyLen
            responseIpPacket[24] = ((udpTotalLen ushr 8) and 0xFF).toByte()
            responseIpPacket[25] = (udpTotalLen and 0xFF).toByte()
            // UDP Checksum 0x0000 is valid in IPv4 per RFC 768
            responseIpPacket[26] = 0x00.toByte()
            responseIpPacket[27] = 0x00.toByte()

            // Copy DNS payload
            System.arraycopy(respBuf, 0, responseIpPacket, 28, replyLen)

            synchronized(outputStream) {
                outputStream.write(responseIpPacket)
                outputStream.flush()
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Throwable) {
        } finally {
            vpnInterface = null
        }
    }

    override fun onRevoke() {
        PreferencesManager(applicationContext).isAutoDnsVpnEnabled = false
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdGuardDnsVpn"
        const val ACTION_STOP = "com.pureguard.filter.STOP_DNS_VPN"

        // Official AdGuard Family Protection DNS IPs (blocks ads + adult domains + forces SafeSearch)
        const val ADGUARD_FAMILY_DNS_1 = "94.140.14.15"
        const val ADGUARD_FAMILY_DNS_2 = "94.140.15.16"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun stop(context: Context) {
            PreferencesManager(context).isAutoDnsVpnEnabled = false
            val intent = Intent(context, AdGuardDnsVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
