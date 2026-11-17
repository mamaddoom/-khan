package com.mozkhan.shop.net

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.random.Random

/** A DNS server choice. [group] only orders the dropdown. */
data class DnsPreset(val id: String, val name: String, val primary: String, val secondary: String, val group: String)

object DnsPresets {
    const val OFF = ""
    const val MANUAL = "manual"

    /**
     * Public lists disagree about operator resolvers; these are the most commonly published ones (Nov 2026).
     * Operator resolvers usually answer only inside that operator's own network (e.g. MCI's 10.4.4.4 on Hamrah-e Aval SIMs).
     * "Anti-sanction" resolvers (Shecan, Begzar, 403, Electro, Radar) point Google/Firebase names at their own relays.
     */
    val all = listOf(
        DnsPreset("shecan", "شکن (Shecan)", "178.22.122.100", "185.51.200.2", "ضدتحریم"),
        DnsPreset("begzar", "بگذر (Begzar)", "185.55.226.26", "185.55.225.25", "ضدتحریم"),
        DnsPreset("403", "۴۰۳ (403.online)", "10.202.10.202", "10.202.10.102", "ضدتحریم"),
        DnsPreset("electro", "الکترو (Electro)", "78.157.42.100", "78.157.42.101", "ضدتحریم"),
        DnsPreset("radar", "رادار (Radar)", "10.202.10.10", "10.202.10.11", "ضدتحریم"),
        DnsPreset("tci", "مخابرات", "217.218.127.127", "217.218.155.155", "اپراتور"),
        DnsPreset("mci", "همراه اول", "10.4.4.4", "10.4.4.5", "اپراتور"),
        DnsPreset("irancell", "ایرانسل", "78.157.42.100", "78.157.42.101", "اپراتور"),
        DnsPreset("rightel", "رایتل", "212.33.203.34", "212.33.203.35", "اپراتور"),
        DnsPreset("asiatech", "آسیاتک", "194.36.174.161", "194.36.174.162", "اپراتور"),
        DnsPreset("shatel", "شاتل", "85.15.1.14", "85.15.1.15", "اپراتور"),
        DnsPreset("google", "Google", "8.8.8.8", "8.8.4.4", "جهانی"),
        DnsPreset("cloudflare", "Cloudflare", "1.1.1.1", "1.0.0.1", "جهانی"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Valid IPv4 text only (the VPN routes exactly these addresses). */
    fun isIpv4(s: String): Boolean {
        val p = s.trim().split('.')
        return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
    }
}

/** Minimal UDP DNS client: used by "test DNS" in Settings (asks a server directly, bypassing the phone's resolver). */
object DnsClient {
    /** Returns (IPv4 addresses, milliseconds). Throws on timeout / bad answer. */
    fun resolve(server: String, name: String, timeoutMs: Int = 4000): Pair<List<String>, Long> {
        val id = Random.nextInt(0, 0xFFFF)
        val q = ByteArrayOutputStream().apply {
            write(id shr 8); write(id and 0xFF)
            write(0x01); write(0x00) // recursion desired
            write(0); write(1); repeat(6) { write(0) } // 1 question
            name.trimEnd('.').split('.').forEach { l -> write(l.length); write(l.toByteArray()) }
            write(0)
            write(0); write(1); write(0); write(1) // type A, class IN
        }.toByteArray()
        val t0 = System.currentTimeMillis()
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val ms = System.currentTimeMillis() - t0
            return parseA(buf, p.length, id) to ms
        }
    }

    /**
     * Does [ip] really serve Firebase? Opens TLS with the real host name (SNI + certificate check) and asks for "/".
     * Google answers 404; anti-sanction relays that do not carry Firebase answer 403 (seen by Firestore as
     * "permission denied"); a wrong certificate or no answer means the address is useless for Firebase.
     */
    fun checkFirebase(ip: String, host: String = "firestore.googleapis.com"): String = try {
        java.net.Socket().use { raw ->
            raw.connect(java.net.InetSocketAddress(ip, 443), 6000)
            raw.soTimeout = 6000
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                .createSocket(raw, host, 443, false) as javax.net.ssl.SSLSocket
            ssl.use { s ->
                s.startHandshake()
                if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
                    return "گواهی امنیتی معتبر نیست ✗"
                }
                s.outputStream.write("GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                s.outputStream.flush()
                val line = s.inputStream.bufferedReader().readLine().orEmpty()
                val code = line.split(' ').getOrNull(1)?.toIntOrNull()
                when (code) {
                    403 -> "این DNS به Firebase راه نمی‌دهد (403) ✗"
                    null -> "پاسخ نامفهوم ✗"
                    else -> "Firebase در دسترس است ✓"
                }
            }
        }
    } catch (e: Exception) {
        "اتصال به Firebase برقرار نشد ✗ (${e.javaClass.simpleName})"
    }

    private fun parseA(b: ByteArray, n: Int, id: Int): List<String> {
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        check(n >= 12 && u16(0) == id) { "پاسخ نامعتبر" }
        val rcode = b[3].toInt() and 0x0F
        check(rcode == 0) { if (rcode == 3) "نام پیدا نشد" else "خطای سرور DNS (کد $rcode)" }
        val qd = u16(4); val an = u16(6)
        var i = 12
        fun skipName() {
            while (i < n) {
                val len = b[i].toInt() and 0xFF
                if (len == 0) { i++; return }
                if (len and 0xC0 == 0xC0) { i += 2; return }
                i += len + 1
            }
        }
        repeat(qd) { skipName(); i += 4 }
        val out = ArrayList<String>()
        repeat(an) {
            if (i >= n) return out
            skipName()
            val type = u16(i); val len = u16(i + 8)
            i += 10
            if (type == 1 && len == 4) out += Inet4Address.getByAddress(b.copyOfRange(i, i + 4)).hostAddress.orEmpty()
            i += len
        }
        return out
    }
}

/** Starts / stops the app-only DNS tunnel ([DnsVpnService]). */
object DnsControl {
    /** null = permission already granted; otherwise launch this intent and call [start] after RESULT_OK. */
    fun permissionIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    fun start(ctx: Context) {
        if (VpnService.prepare(ctx) != null) return // the user has not allowed it (yet)
        runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java)) }
    }

    fun stop(ctx: Context) {
        runCatching { ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)) }
    }

    @Suppress("unused")
    fun isPermitted(a: Activity) = VpnService.prepare(a) == null
}
