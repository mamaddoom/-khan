package com.mozkhan.shop.net

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * "DNS of this app only": a VPN that carries NOTHING but DNS queries of this app to the chosen servers.
 *
 * Only the two DNS server addresses are routed into the tunnel and only this app is inside it, so all other traffic
 * (and every other app) keeps using the normal network. Android hands this app's name lookups (Firebase, updates…) to
 * the VPN's DNS servers; each query packet read from the tunnel is forwarded over a protected UDP socket to that server
 * and the answer is written back as an IPv4/UDP packet.
 *
 * Android allows one VPN at a time: turning this on stops another VPN app, and turning another VPN on stops this one.
 */
class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.mozkhan.shop.DNS_STOP"
        private val _status = MutableStateFlow<String?>(null)
        /** null = off; otherwise a short text for Settings */
        val status: StateFlow<String?> = _status.asStateFlow()
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val servers = Prefs(this).dnsServers()
        if (servers.isEmpty()) {
            shutdown(); stopSelf()
            return START_NOT_STICKY
        }
        shutdown()
        try {
            val b = Builder().setSession("MozKhan DNS").addAddress("10.111.222.1", 32).setMtu(1500).setBlocking(true)
            servers.forEach { b.addDnsServer(it); b.addRoute(it, 32) }
            b.addAllowedApplication(packageName)
            val fd = b.establish() ?: error("اجازهٔ VPN داده نشده است")
            tun = fd
            reader = Thread({ loop(fd) }, "dns-tunnel").apply { isDaemon = true; start() }
            _status.value = "فعال: " + servers.joinToString(" ، ")
        } catch (e: Throwable) {
            _status.value = null
            shutdown(); stopSelf()
        }
        return START_STICKY
    }

    override fun onRevoke() { // another VPN took over, or the user switched it off in system settings
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun shutdown() {
        reader?.interrupt(); reader = null
        runCatching { tun?.close() }; tun = null
        _status.value = null
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted && tun === fd) {
            val n = try { input.read(buf) } catch (e: Throwable) { break }
            if (n <= 0) continue
            val pkt = buf.copyOf(n)
            pool.execute { runCatching { answer(pkt, output) } }
        }
    }

    /** IPv4 + UDP to port 53 only; anything else is dropped (nothing else is routed here anyway). */
    private fun answer(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4 || p[9].toInt() != 17) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p.size < ihl + 8) return
        fun u16(i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
        val srcPort = u16(ihl); val dstPort = u16(ihl + 2)
        if (dstPort != 53) return
        val udpLen = u16(ihl + 4)
        val query = p.copyOfRange(ihl + 8, minOf(p.size, ihl + udpLen))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))

        val reply = DatagramSocket().use { s ->
            protect(s) // this socket must bypass the tunnel, or the query would loop back into it
            s.soTimeout = 5000
            s.send(DatagramPacket(query, query.size, dst, 53))
            val rb = ByteArray(4096)
            val rp = DatagramPacket(rb, rb.size)
            s.receive(rp)
            rb.copyOf(rp.length)
        }

        // IPv4 header (20 bytes) + UDP header (8 bytes) back to the asking socket
        val total = 28 + reply.size
        val r = ByteArray(total)
        r[0] = 0x45; r[8] = 64; r[9] = 17
        r[2] = (total shr 8).toByte(); r[3] = total.toByte()
        System.arraycopy(p, 16, r, 12, 4) // src = the DNS server
        System.arraycopy(p, 12, r, 16, 4) // dst = this phone
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cks = sum.inv() and 0xFFFF
        r[10] = (cks shr 8).toByte(); r[11] = cks.toByte()
        r[20] = 0; r[21] = 53
        r[22] = (srcPort shr 8).toByte(); r[23] = srcPort.toByte()
        val ul = 8 + reply.size
        r[24] = (ul shr 8).toByte(); r[25] = ul.toByte()
        // UDP checksum 0 = "not computed" (allowed for IPv4)
        System.arraycopy(reply, 0, r, 28, reply.size)
        synchronized(out) { out.write(r) }
    }
}
