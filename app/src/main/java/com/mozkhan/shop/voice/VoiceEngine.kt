package com.mozkhan.shop.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Offline Persian speech recognition with Vosk (Kaldi) and its small Persian model
 * (vosk-model-small-fa-0.42, Apache-2.0, about 53 MB). Nothing is sent anywhere: audio never leaves the phone.
 *
 * The model is not inside the APK (every update would cost everyone 53 MB more): it is downloaded once, checked
 * against its SHA-256, and unpacked to files/voice/fa. Shops without good internet can pick the same zip from a file.
 */
object VoiceEngine {
    const val MODEL_NAME = "vosk-model-small-fa-0.42"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val MODEL_SHA256 = "977cb5faa538f3a835ccfd35f5f6d8284b5c450b89c700b9bd4736b66536ad46"
    const val MODEL_MB = 53
    private const val RATE = 16000f

    sealed class State {
        object Missing : State()
        data class Downloading(val progress: Float) : State()
        object Installing : State()
        object Installed : State()
        object Loading : State()
        object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var model: Model? = null
    private val lock = Mutex()

    private fun dir(ctx: Context) = File(ctx.filesDir, "voice/fa")
    private fun valid(d: File) = File(d, "am/final.mdl").isFile && File(d, "conf/model.conf").isFile
    fun installed(ctx: Context) = valid(dir(ctx))

    /** Updates [state] from what is on disk (call when a screen opens). */
    fun refresh(ctx: Context) {
        val s = _state.value
        if (s is State.Downloading || s is State.Installing || s is State.Loading) return
        _state.value = when {
            model != null -> State.Ready
            installed(ctx) -> State.Installed
            s is State.Failed -> s
            else -> State.Missing
        }
    }

    // ------------------------------------------------------------------ install
    suspend fun download(ctx: Context): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val zip = File(ctx.cacheDir, "$MODEL_NAME.zip.part")
                _state.value = State.Downloading(0f)
                val c = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                }
                try {
                    if (c.responseCode !in 200..299) error("سرور مدل پاسخ ${c.responseCode} داد")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: (MODEL_MB * 1_000_000L)
                    val md = MessageDigest.getInstance("SHA-256")
                    c.inputStream.use { input ->
                        zip.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading((done.toFloat() / total).coerceIn(0f, 1f)) }
                            }
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    if (hex != MODEL_SHA256) { zip.delete(); error("فایل دانلودشده سالم نیست؛ دوباره امتحان کنید") }
                } finally { c.disconnect() }
                install(ctx) { zip.inputStream() }
                zip.delete()
                Unit
            }.onFailure { _state.value = State.Failed(it.message ?: "دانلود مدل ناموفق بود") }
        }
    }

    /** The same zip (or another Vosk Persian model zip) picked from a file. */
    suspend fun importZip(ctx: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                install(ctx) { ctx.contentResolver.openInputStream(uri) ?: error("فایل باز نشد") }
            }.onFailure { _state.value = State.Failed(it.message ?: "نصب مدل ناموفق بود") }
        }
    }

    /** Unpacks into a temp folder (stripping the zip's top folder), checks it, then swaps it in. */
    private fun install(ctx: Context, open: () -> InputStream) {
        _state.value = State.Installing
        unload()
        val target = dir(ctx)
        val tmp = File(ctx.filesDir, "voice/tmp").apply { deleteRecursively(); mkdirs() }
        val root = tmp.canonicalPath + File.separator
        ZipInputStream(open().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val rel = e.name.replace('\\', '/').substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(tmp, rel)
                if (!f.canonicalPath.startsWith(root)) error("فایل zip نامعتبر است") // zip-slip guard
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        if (!valid(tmp)) { tmp.deleteRecursively(); error("این فایل مدل صوتی Vosk نیست") }
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!tmp.renameTo(target)) { tmp.copyRecursively(target, overwrite = true); tmp.deleteRecursively() }
        _state.value = State.Installed
    }

    fun delete(ctx: Context) {
        unload()
        dir(ctx).deleteRecursively()
        _state.value = State.Missing
    }

    private fun unload() {
        runCatching { model?.close() }
        model = null
    }

    // ------------------------------------------------------------------ recognition
    /** Loads the model once (a few seconds on first use). */
    suspend fun load(ctx: Context): Model = withContext(Dispatchers.IO) {
        lock.withLock {
            model ?: run {
                if (!installed(ctx)) error("مدل صوتی نصب نشده است")
                _state.value = State.Loading
                runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }
                runCatching { Model(dir(ctx).absolutePath) }
                    .onSuccess { model = it; _state.value = State.Ready }
                    .onFailure { _state.value = State.Failed("بارگذاری مدل ناموفق: ${it.message}") }
                    .getOrThrow()
            }
        }
    }

    /** One spoken command. Callbacks arrive on the main thread. Call [Session.stop] to finish early. */
    class Session internal constructor(private val service: SpeechService, private val rec: Recognizer) {
        @Volatile internal var closed = false
        fun stop() { if (!closed) runCatching { service.stop() } }
        internal fun close() {
            if (closed) return
            closed = true
            // stop() interrupts AND joins the recording thread; only then may the recorder and recognizer be released
            runCatching { service.stop() }
            runCatching { service.shutdown() }
            runCatching { rec.close() }
        }
    }

    suspend fun listen(
        ctx: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): Session? {
        val m = runCatching { load(ctx) }.getOrElse { onError(it.message ?: "مدل آماده نیست"); return null }
        return withContext(Dispatchers.Main) {
            runCatching {
                val rec = Recognizer(m, RATE)
                val svc = SpeechService(rec, RATE)
                val s = Session(svc, rec)
                var last = ""
                var delivered = false
                fun text(json: String?, key: String) = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("").trim()
                fun finish(t: String) {
                    if (delivered) return
                    delivered = true
                    s.close()
                    if (t.isBlank()) onError("چیزی شنیده نشد؛ دوباره بگویید") else onFinal(t)
                }
                svc.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val t = text(hypothesis, "partial")
                        if (t.isNotEmpty()) { last = t; onPartial(t) }
                    }
                    // end of the utterance (short silence): one command = one sentence
                    override fun onResult(hypothesis: String?) {
                        val t = text(hypothesis, "text")
                        if (t.isNotEmpty()) finish(t)
                    }
                    override fun onFinalResult(hypothesis: String?) = finish(text(hypothesis, "text").ifEmpty { last })
                    override fun onError(exception: Exception?) {
                        if (!delivered) { delivered = true; s.close(); onError("خطای میکروفون: ${exception?.message ?: ""}") }
                    }
                    override fun onTimeout() = finish(last)
                }, 12_000)
                s
            }.getOrElse { onError("میکروفون باز نشد: ${it.message}"); null }
        }
    }
}
