package com.mozkhan.shop.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Spoken answers through a text-to-speech engine of the phone. Most phones have no Persian voice, so every installed
 * engine is tried (the phone's default first); the free offline Persian engine (sherpa-onnx, Piper voice) can be
 * downloaded and installed from the app with [downloadEngine]. Without any Persian voice the screen still shows everything.
 */
object VoiceSpeaker {
    enum class Status { UNKNOWN, READY, NO_PERSIAN, FAILED }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var tts: TextToSpeech? = null
    private var pending: String? = null
    @Volatile private var searching = false
    /** the Persian voice is the phone's own default engine (trusted in "auto"); an extra engine is used only when chosen */
    @Volatile var fromDefault = false; private set
    private val locales = listOf(Locale("fa", "IR"), Locale("fa"), Locale("fas"))

    fun init(ctx: Context) {
        if (tts != null || searching || _status.value == Status.NO_PERSIAN) return
        searching = true
        val app = ctx.applicationContext
        // the default engine first; its list of installed engines tells which others to try
        open(app, null) { default ->
            val others = runCatching { default.engines.map { it.name } }.getOrDefault(emptyList())
                .filter { it != runCatching { default.defaultEngine }.getOrNull() }
                // the offline Persian engine first
                .sortedByDescending { it.contains("sherpa", true) || it.contains("k2fsa", true) }
            runCatching { default.shutdown() }
            tryEngines(app, others)
        }
    }

    /** Opens [engine] (null = default); [onNoPersian] gets it back when it cannot speak Persian. */
    private fun open(ctx: Context, engine: String?, onNoPersian: (TextToSpeech) -> Unit) {
        var made: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { st ->
            val t = made ?: tts
            if (st != TextToSpeech.SUCCESS || t == null) {
                if (t != null) onNoPersian(t) else { searching = false; _status.value = Status.FAILED }
                return@OnInitListener
            }
            if (persian(t)) {
                tts = t
                fromDefault = engine == null
                searching = false
                _status.value = Status.READY
                pending?.let { say(it) }
                pending = null
            } else onNoPersian(t)
        }
        made = runCatching { if (engine == null) TextToSpeech(ctx, listener) else TextToSpeech(ctx, listener, engine) }.getOrNull()
        if (made == null) { searching = false; _status.value = Status.FAILED }
    }

    private fun tryEngines(ctx: Context, names: List<String>) {
        if (names.isEmpty()) { searching = false; _status.value = Status.NO_PERSIAN; pending = null; return }
        open(ctx, names.first()) { t -> runCatching { t.shutdown() }; tryEngines(ctx, names.drop(1)) }
    }

    private fun persian(t: TextToSpeech): Boolean = locales.any { l ->
        val r = runCatching { t.setLanguage(l) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** Looks again (after a Persian engine was installed). */
    fun reset(ctx: Context) {
        runCatching { tts?.shutdown() }
        tts = null
        searching = false
        _status.value = Status.UNKNOWN
        init(ctx)
    }

    // ------------------------------------------------------------------ English (offline) for phones without Persian
    private var enTts: TextToSpeech? = null
    @Volatile private var enReady = false
    private var enPending: String? = null
    private val _english = MutableStateFlow(false)
    /** true when the phone's English voice is ready (almost every phone has it, offline) */
    val english: StateFlow<Boolean> = _english.asStateFlow()

    fun initEnglish(ctx: Context) {
        if (enTts != null) return
        var made: TextToSpeech? = null
        made = runCatching {
            TextToSpeech(ctx.applicationContext) { st ->
                val t = made ?: enTts
                if (st == TextToSpeech.SUCCESS && t != null) {
                    val r = runCatching { t.setLanguage(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                    enReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    _english.value = enReady
                    if (enReady) runCatching { t.setSpeechRate(0.95f) }
                    if (enReady) enPending?.let { runCatching { t.speak(it, TextToSpeech.QUEUE_FLUSH, null, "mozkhan-en") } }
                }
                enPending = null
            }
        }.getOrNull()
        enTts = made
    }

    /**
     * Speaks an answer. [lang]: "fa" = Persian only, "en" = English only, "auto" = Persian when this phone has a Persian
     * voice, otherwise English. Does nothing when [enabled] is false.
     */
    fun speak(ctx: Context, fa: String, en: String, enabled: Boolean, lang: String) {
        if (!enabled) return
        if (lang != "en") init(ctx)
        val persian = lang != "en" && fa.isNotBlank()
        when {
            persian && _status.value == Status.READY && (lang == "fa" || fromDefault) -> say(fa)
            persian && _status.value == Status.UNKNOWN && lang == "fa" -> pending = fa
            lang != "fa" && en.isNotBlank() -> {
                initEnglish(ctx)
                if (enReady) runCatching { enTts?.speak(en, TextToSpeech.QUEUE_FLUSH, null, "mozkhan-en") } else enPending = en
            }
        }
    }
    private fun say(text: String) {
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mozkhan") }
    }

    /** Silence: before the microphone opens (the recognizer must not hear the phone talking). */
    fun stop() { runCatching { tts?.stop() }; runCatching { enTts?.stop() } }

    // ------------------------------------------------------------------ offline Persian engine (download + install)
    private const val ENGINE_VER = "1.12.22"
    const val ENGINE_MB = 80

    data class Dl(val running: Boolean = false, val progress: Float = 0f, val error: String? = null, val file: File? = null)

    private val _dl = MutableStateFlow(Dl())
    val download: StateFlow<Dl> = _dl.asStateFlow()

    private fun engineUrl(): String {
        val abi = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "arm64-v8a" else "armeabi-v7a"
        return "https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-new/$ENGINE_VER/" +
            "sherpa-onnx-$ENGINE_VER-$abi-fa-tts-engine-vits-piper-fa_IR-amir-medium.apk"
    }

    /** Downloads the Persian speech engine (open-source sherpa-onnx, Piper «amir» voice, works offline) and opens the installer. */
    suspend fun downloadEngine(ctx: Context): String? {
        if (_dl.value.running) return null
        _dl.value = Dl(running = true)
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "tts").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "persian-voice.apk")
                val c = URL(engineUrl()).openConnection() as HttpURLConnection // follows the redirect to the CDN
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "MozKhan")
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
                                o.write(buf, 0, n); done += n
                                if (total > 0) _dl.value = _dl.value.copy(progress = done.toFloat() / total)
                            }
                        }
                    }
                    if (total > 0 && done != total) error("دانلود ناقص ماند")
                } finally { c.disconnect() }
                out
            }
        }
        return r.fold(
            onSuccess = { f -> _dl.value = Dl(file = f); installEngine(ctx) },
            onFailure = { e ->
                val msg = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "اتصال اینترنت برقرار نیست (برای دانلود ممکن است فیلترشکن لازم باشد)"
                else "دانلود ناموفق بود: ${e.message}"
                _dl.value = Dl(error = msg); msg
            },
        )
    }

    /** Opens the system installer for the downloaded engine; a message when something is still needed. */
    fun installEngine(ctx: Context): String? {
        val f = _dl.value.file?.takeIf { it.exists() } ?: return "ابتدا صدای فارسی را دانلود کنید"
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "اجازهٔ «نصب برنامه‌های ناشناس» را برای MozKhan فعال کنید و دوباره «نصب صدای فارسی» را بزنید"
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }

    /** After a successful install the file is not needed any more (80 MB). */
    fun cleanup(ctx: Context) {
        runCatching { File(ctx.cacheDir, "tts").listFiles()?.forEach { it.delete() } }
        _dl.value = Dl()
    }

    /** The phone's text-to-speech settings (to choose the Persian engine as default, optional). */
    fun settingsIntent(): Intent = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
