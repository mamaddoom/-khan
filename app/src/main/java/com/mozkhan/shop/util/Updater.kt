package com.mozkhan.shop.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mozkhan.shop.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the in-app updater is doing right now. */
data class UpdateState(
    val checking: Boolean = false,
    val available: Boolean = false,
    val latestName: String = "",
    val latestCode: Long = 0,
    val apkUrl: String = "",
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val downloaded: File? = null,
    val message: String? = null,
    val checkedAt: Long = 0,
)

/**
 * In-app update from the newest GitHub Release (published by the CI build; tag = v<versionName>-<versionCode>).
 * The release must be readable without a login (public repository) — a private repository answers 404 to the phone.
 */
class Updater(private val ctx: Context, private val scope: CoroutineScope) {
    companion object {
        const val REPO = "mamaddoom/-khan"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    }

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check(silent: Boolean = false) {
        if (_state.value.checking || _state.value.downloading) return
        _state.value = _state.value.copy(checking = true, message = if (silent) _state.value.message else null)
        scope.launch(Dispatchers.IO) {
            val r = runCatching { fetchLatest() }
            r.onSuccess { (name, code, url) ->
                val newer = code > BuildConfig.VERSION_CODE
                _state.value = _state.value.copy(
                    checking = false, available = newer, latestName = name, latestCode = code, apkUrl = url,
                    checkedAt = System.currentTimeMillis(),
                    message = if (newer) null else if (silent) null else "نسخهٔ شما به‌روز است",
                )
            }.onFailure {
                _state.value = _state.value.copy(checking = false, message = if (silent) null else friendly(it))
            }
        }
    }

    private fun friendly(e: Throwable): String = when {
        e is NoRelease -> "نسخهٔ جدیدی منتشر نشده یا مخزن GitHub خصوصی است (برای به‌روزرسانی درون‌برنامه‌ای مخزن باید عمومی باشد)"
        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException -> "اتصال اینترنت برقرار نیست"
        else -> "بررسی نسخه ناموفق بود: ${e.message}"
    }

    private class NoRelease : Exception()

    private fun fetchLatest(): Triple<String, Long, String> {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MozKhan-Updater")
        try {
            if (c.responseCode == 404) throw NoRelease()
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = j.optString("tag_name")
            val code = tag.substringAfterLast('-').toLongOrNull() ?: error("برچسب نسخه نامعتبر است")
            val assets = j.optJSONArray("assets") ?: throw NoRelease()
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (url.isEmpty()) throw NoRelease()
            return Triple(tag.removePrefix("v").substringBeforeLast('-'), code, url)
        } finally {
            c.disconnect()
        }
    }

    fun download() {
        val s = _state.value
        if (s.apkUrl.isEmpty() || s.downloading) return
        _state.value = s.copy(downloading = true, progress = 0f, message = null, downloaded = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "MozKhan-update.apk")
                val c = URL(s.apkUrl).openConnection() as HttpURLConnection // follows GitHub's redirect to the CDN
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "MozKhan-Updater")
                try {
                    if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    var done = 0L
                    c.inputStream.use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0) _state.value = _state.value.copy(progress = done.toFloat() / total)
                            }
                        }
                    }
                    if (total > 0 && done != total) error("دانلود ناقص ماند")
                } finally {
                    c.disconnect()
                }
                out
            }.onSuccess {
                _state.value = _state.value.copy(downloading = false, progress = 1f, downloaded = it)
            }.onFailure {
                _state.value = _state.value.copy(downloading = false, message = "دانلود ناموفق بود: ${it.message}")
            }
        }
    }

    /** Opens the system installer. Android asks once for "install unknown apps" permission for this app. */
    fun install(): String? {
        val f = _state.value.downloaded ?: return "ابتدا نسخهٔ جدید را دانلود کنید"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "اجازهٔ «نصب برنامه‌های ناشناس» را برای MozKhan فعال کنید و دوباره «نصب» را بزنید"
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }
}
