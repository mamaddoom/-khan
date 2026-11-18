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

    /** What was heard: the best text and other likely readings (most likely first). */
    data class Heard(val text: String, val alternatives: List<String>)

    /**
     * [continuous] = keep listening across pauses (product definition) and join the sentences, until [Session.stop],
     * a spoken stop word ("تمام" / "تأیید") or [maxMs]. Otherwise the first finished sentence is the answer.
     * [hints] = words the shop really uses (product names, numbers, commands): a second recognizer limited to them runs
     * on the same audio, which makes names much more reliable; its readings are added to [Heard.alternatives].
     */
    data class Options(
        val continuous: Boolean = false,
        val maxMs: Long = 15_000,
        val hints: Collection<String> = emptyList(),
        val stopWords: Set<String> = emptySet(),
    )

    class Session internal constructor() {
        @Volatile internal var stopRequested = false
        /** Finish now: what was said so far becomes the answer. */
        fun stop() { stopRequested = true }
    }

    private fun texts(json: String?): List<String> = runCatching {
        val o = JSONObject(json ?: "{}")
        val alts = o.optJSONArray("alternatives")
        if (alts != null) (0 until alts.length()).map { alts.getJSONObject(it).optString("text").trim() }
        else listOf(o.optString("text").trim())
    }.getOrDefault(emptyList()).filter { it.isNotEmpty() }

    private fun partial(json: String?) = runCatching { JSONObject(json ?: "{}").optString("partial").trim() }.getOrDefault("")

    /** Vosk grammar: every hint in both spellings of "ی" (the model's lexicon uses the Arabic one), plus [unk]. */
    private fun grammar(hints: Collection<String>): String? {
        val words = LinkedHashSet<String>()
        for (h in hints) {
            for (w in VoiceParser.norm(h).split(' ')) {
                if (w.isBlank() || w.length > 30) continue
                words += w
                words += w.replace('ی', 'ي')
            }
            if (words.size > 3000) break
        }
        if (words.isEmpty()) return null
        return org.json.JSONArray((words + "[unk]").toList()).toString()
    }

    /** One command (or one dictation). Callbacks arrive on the main thread. */
    @android.annotation.SuppressLint("MissingPermission") // the screens ask for RECORD_AUDIO before calling this
    suspend fun listen(
        ctx: Context,
        opt: Options,
        onPartial: (String) -> Unit,
        onFinal: (Heard) -> Unit,
        onError: (String) -> Unit,
    ): Session? {
        val m = runCatching { load(ctx) }.getOrElse { onError(it.message ?: "مدل آماده نیست"); return null }
        val session = Session()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({ decode(m, opt, session, main, onPartial, onFinal, onError) }, "voice").start()
        return session
    }

    private fun decode(
        m: Model, opt: Options, session: Session, main: android.os.Handler,
        onPartial: (String) -> Unit, onFinal: (Heard) -> Unit, onError: (String) -> Unit,
    ) {
        var free: Recognizer? = null
        var gram: Recognizer? = null
        var rec: android.media.AudioRecord? = null
        try {
            val f = Recognizer(m, RATE)
            free = f
            f.setMaxAlternatives(5)
            // allow a breath between "دو تا بهمن" and "و یک وینستون" without cutting the sentence
            runCatching { f.setEndpointerDelays(8f, if (opt.continuous) 1.2f else 0.9f, 25f) }
            val g = grammar(opt.hints)?.let { spec ->
                runCatching {
                    Recognizer(m, RATE, spec).apply { setMaxAlternatives(3); runCatching { setEndpointerDelays(8f, 0.9f, 25f) } }
                }.getOrNull()
            }
            gram = g
            val ch = android.media.AudioFormat.CHANNEL_IN_MONO
            val enc = android.media.AudioFormat.ENCODING_PCM_16BIT
            val min = android.media.AudioRecord.getMinBufferSize(RATE.toInt(), ch, enc)
            val r = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE.toInt(), ch, enc, maxOf(min, 8192) * 2)
            rec = r
            if (r.state != android.media.AudioRecord.STATE_INITIALIZED) error("میکروفون در دسترس نیست (برنامهٔ دیگری از آن استفاده می‌کند؟)")
            r.startRecording()

            val buf = ShortArray(1600) // 0.1 s
            val said = ArrayList<String>()      // continuous: finished sentences
            val gramPending = ArrayList<String>()
            val t0 = System.currentTimeMillis()
            var lastShown = ""
            var stopWordHeard = false

            fun cutAtStopWord(s: String): Pair<String, Boolean> {
                val t = VoiceParser.norm(s).split(' ').filter { it.isNotEmpty() }
                val idx = t.indexOfFirst { it in opt.stopWords }
                return if (idx < 0) s to false else t.take(idx).joinToString(" ") to true
            }

            while (!session.stopRequested && System.currentTimeMillis() - t0 < opt.maxMs) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) error("خواندن صدا از میکروفون ناموفق بود")
                if (n == 0) continue
                val endFree = f.acceptWaveForm(buf, n)
                if (g != null && g.acceptWaveForm(buf, n)) gramPending += texts(g.result)
                if (endFree) {
                    val seg = texts(f.result)
                    if (seg.isEmpty()) continue
                    if (!opt.continuous) {
                        if (g != null) gramPending += texts(g.finalResult)
                        val heard = Heard(seg.first(), (seg + gramPending).distinct())
                        main.post { onFinal(heard) }
                        return
                    }
                    val (keep, stop) = cutAtStopWord(seg.first())
                    if (keep.isNotBlank()) said += keep
                    lastShown = said.joinToString(" ")
                    val shown = lastShown
                    main.post { onPartial(shown) }
                    if (stop) { stopWordHeard = true; break }
                } else {
                    val p = partial(f.partialResult)
                    val shown = if (opt.continuous) (said + p).filter { it.isNotBlank() }.joinToString(" ") else p
                    if (shown != lastShown && shown.isNotBlank()) { lastShown = shown; main.post { onPartial(shown) } }
                }
            }

            // stopped by the user, by a stop word, or out of time: whatever is still in the recognizer counts
            val tail = if (stopWordHeard) emptyList() else texts(f.finalResult)
            if (g != null) gramPending += texts(g.finalResult)
            if (opt.continuous) {
                tail.firstOrNull()?.let { cutAtStopWord(it).first }?.takeIf { it.isNotBlank() }?.let { said += it }
                val all = said.joinToString(" ").trim()
                main.post { if (all.isEmpty()) onError("چیزی شنیده نشد؛ دوباره بگویید") else onFinal(Heard(all, listOf(all))) }
            } else {
                val best = tail.firstOrNull() ?: gramPending.firstOrNull()
                val alts = (tail + gramPending).distinct()
                main.post {
                    if (best.isNullOrBlank()) onError("چیزی شنیده نشد؛ دوباره بگویید")
                    else onFinal(Heard(best, alts))
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            main.post { onError(msg) }
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { free?.close() }
            runCatching { gram?.close() }
        }
    }
}
