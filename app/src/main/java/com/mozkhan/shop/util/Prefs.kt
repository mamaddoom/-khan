package com.mozkhan.shop.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val fa: String) { SYSTEM("خودکار"), LIGHT("روشن"), DARK("تیره") }

/** Tiny reactive wrapper over SharedPreferences (no extra dependency, instant reads). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _currency = MutableStateFlow(runCatching { Currency.valueOf(sp.getString("currency", "TOMAN")!!) }.getOrDefault(Currency.TOMAN))
    val currency: StateFlow<Currency> = _currency.asStateFlow()
    fun setCurrency(c: Currency) { sp.edit().putString("currency", c.name).apply(); _currency.value = c }

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    fun setTheme(t: ThemeMode) { sp.edit().putString("theme", t.name).apply(); _theme.value = t }

    private val _sound = MutableStateFlow(sp.getBoolean("sound", true))
    val sound: StateFlow<Boolean> = _sound.asStateFlow()
    fun setSound(v: Boolean) { sp.edit().putBoolean("sound", v).apply(); _sound.value = v }

    private val _vibrate = MutableStateFlow(sp.getBoolean("vibrate", true))
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()
    fun setVibrate(v: Boolean) { sp.edit().putBoolean("vibrate", v).apply(); _vibrate.value = v }

    private val _shop = MutableStateFlow(sp.getString("shop", "فروشگاه من")!!)
    val shopName: StateFlow<String> = _shop.asStateFlow()
    fun setShopName(v: String) { sp.edit().putString("shop", v).apply(); _shop.value = v }

    private val _sync = MutableStateFlow(sp.getBoolean("cloud_sync", false))
    val cloudSync: StateFlow<Boolean> = _sync.asStateFlow()
    fun setCloudSync(v: Boolean) { sp.edit().putBoolean("cloud_sync", v).apply(); _sync.value = v }

    /** When true, a scanned countable item is added to the invoice immediately (no confirm sheet). */
    private val _autoAdd = MutableStateFlow(sp.getBoolean("auto_add", false))
    val autoAddFlow: StateFlow<Boolean> = _autoAdd.asStateFlow()
    var autoAdd: Boolean
        get() = _autoAdd.value
        set(v) { sp.edit().putBoolean("auto_add", v).apply(); _autoAdd.value = v }

    /** Camera turns itself off after this many seconds without activity (battery). 0 = never. */
    private val _idle = MutableStateFlow(sp.getInt("camera_idle", 60))
    val cameraIdle: StateFlow<Int> = _idle.asStateFlow()
    fun setCameraIdle(v: Int) { sp.edit().putInt("camera_idle", v).apply(); _idle.value = v }

    /** DNS of this app: "" = phone default, a preset id, or "manual" (see com.mozkhan.shop.net.DnsPresets) */
    private val _dns = MutableStateFlow(sp.getString("dns_preset", "")!!)
    val dnsPreset: StateFlow<String> = _dns.asStateFlow()
    fun setDnsPreset(v: String) { sp.edit().putString("dns_preset", v).apply(); _dns.value = v }
    var dnsManual1: String
        get() = sp.getString("dns_m1", "")!!
        set(v) = sp.edit().putString("dns_m1", v.trim()).apply()
    var dnsManual2: String
        get() = sp.getString("dns_m2", "")!!
        set(v) = sp.edit().putString("dns_m2", v.trim()).apply()

    /** false (default) = DNS inside the app only, no VPN; true = also the app-only VPN tunnel (covers sign-in and updates) */
    private val _dnsVpn = MutableStateFlow(sp.getBoolean("dns_vpn", false))
    val dnsVpn: StateFlow<Boolean> = _dnsVpn.asStateFlow()
    fun setDnsVpn(v: Boolean) { sp.edit().putBoolean("dns_vpn", v).apply(); _dnsVpn.value = v }

    /** The servers the DNS tunnel should use now (empty = off / phone default). */
    fun dnsServers(): List<String> {
        val id = _dns.value
        val list = when (id) {
            "" -> emptyList()
            com.mozkhan.shop.net.DnsPresets.MANUAL -> listOf(dnsManual1, dnsManual2)
            else -> com.mozkhan.shop.net.DnsPresets.byId(id)?.let { listOf(it.primary, it.secondary) } ?: emptyList()
        }
        return list.map { it.trim() }.filter { com.mozkhan.shop.net.DnsPresets.isIpv4(it) }.distinct()
    }

    /** image-recognition strictness: 0 lenient, 1 normal, 2 strict */
    private val _vision = MutableStateFlow(sp.getInt("vision_level", 1))
    val visionLevel: StateFlow<Int> = _vision.asStateFlow()
    fun setVisionLevel(v: Int) {
        sp.edit().putInt("vision_level", v).apply(); _vision.value = v
        com.mozkhan.shop.ml.ImageFingerprint.level = v
    }

    /** highest database version this install has opened successfully (drives the pre-migration copy) */
    var dbSeenVersion: Int
        get() = sp.getInt("db_seen", 2) // installs before this field existed ran database v2
        set(v) { sp.edit().putInt("db_seen", v).apply() }

    /** "does this category need a box (carton) definition?" answers: true / false / null = not asked yet */
    fun boxAnswer(category: String): Boolean? = if (sp.contains("box_$category")) sp.getBoolean("box_$category", false) else null
    fun setBoxAnswer(category: String, v: Boolean) { sp.edit().putBoolean("box_$category", v).apply() }

    /** category of the last product defined: the next new product starts with it (shops enter goods in batches) */
    var lastCategory: String
        get() = sp.getString("last_category", "") ?: ""
        set(v) { sp.edit().putString("last_category", v).apply() }

    /** name of this phone, set by hand in Settings; printed on every invoice as the seller */
    var deviceName: String
        get() = sp.getString("device_name", "") ?: ""
        set(v) { sp.edit().putString("device_name", v.trim()).apply() }

    /** "sales only" phone: it can sell, take payments and record expenses, but not define/edit goods (kept per phone, never synced) */
    private val _salesOnly = MutableStateFlow(sp.getBoolean("sales_only", false))
    val salesOnly: StateFlow<Boolean> = _salesOnly.asStateFlow()
    fun setSalesOnly(v: Boolean) { sp.edit().putBoolean("sales_only", v).apply(); _salesOnly.value = v }

    // ---- manual categories
    private val _cats = MutableStateFlow(sp.getStringSet("custom_cats", emptySet())!!.toSet())
    val customCats: StateFlow<Set<String>> = _cats.asStateFlow()
    fun addCategory(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val s = _cats.value + n
        sp.edit().putStringSet("custom_cats", s).apply(); _cats.value = s
    }

    // ---- Google / Firebase runtime configuration
    var fbApiKey: String get() = sp.getString("fb_api", "") ?: ""; set(v) { sp.edit().putString("fb_api", v).apply() }
    var fbAppId: String get() = sp.getString("fb_app", "") ?: ""; set(v) { sp.edit().putString("fb_app", v).apply() }
    var fbProjectId: String get() = sp.getString("fb_project", "") ?: ""; set(v) { sp.edit().putString("fb_project", v).apply() }
    var fbWebClientId: String get() = sp.getString("fb_web", "") ?: ""; set(v) { sp.edit().putString("fb_web", v).apply() }

    /** Shared secret that links phones to one cloud shop without a Google account. */
    var shopCode: String get() = sp.getString("shop_code", "") ?: ""; set(v) { sp.edit().putString("shop_code", v).apply() }

    // ---- incremental sync: per (cloud folder, collection) the newest server time already applied on this phone
    fun syncCursor(key: String): Long = sp.getLong("sync_cur_$key", 0L)
    fun setSyncCursor(key: String, v: Long) { sp.edit().putLong("sync_cur_$key", v).apply() }
    /** forgets every cursor: the next start downloads each collection once in full and uploads what the cloud lacks */
    fun clearSyncCursors() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("sync_cur_") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        fun newShopCode(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = java.security.SecureRandom()
            return (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
