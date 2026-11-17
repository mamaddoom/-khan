package com.mozkhan.shop.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.mozkhan.shop.data.ProductWithStock
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fully on-device visual identification for products without a barcode.
 *
 *  - "E1:" prints: MobileNetV3 image embedding (MediaPipe ImageEmbedder, ~4 MB model in assets), 1 byte per
 *    dimension + a 4 byte scale. Robust to angle, background and lighting. (Legacy "E:" prints are the same format.)
 *  - "C:" / unprefixed prints: the older colour-layout descriptor. It is used only when the model could not be
 *    loaded ("colour mode"), and as low-trust suggestions for products that only have such prints.
 *
 * Scores of the two kinds are never mixed or ranked together.
 */
object ImageFingerprint {
    private const val MODEL = "mobilenet_v3_small.tflite"
    private const val EMB_DIM = 1024

    @Volatile private var embedder: ImageEmbedder? = null
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumps whenever the model state changes: screens that show [status] collect it so the text never goes stale. */
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    @Volatile private var initState = 0 // 0 not tried, 1 loading, 2 ready, 3 failed
        set(v) { field = v; _changes.value = _changes.value + 1 }

    /** Strictness 0 (lenient) .. 2 (strict); set from Settings. */
    @Volatile var level: Int = 1

    val modelReady: Boolean get() = embedder != null
    val loading: Boolean get() = initState == 1

    /** Why the model could not be loaded (shown in Settings / the product screen). */
    @Volatile var error: String? = null
        private set
    val failed: Boolean get() = initState == 3

    /** Loads the model off the main thread. Safe to call repeatedly; [retry] forces another attempt after a failure. */
    @Synchronized
    fun init(ctx: Context, retry: Boolean = false) {
        if (initState == 1 || initState == 2) return
        if (initState == 3 && !retry) return
        initState = 1
        error = null
        Thread {
            val app = ctx.applicationContext
            fun build(base: BaseOptions) = ImageEmbedder.createFromOptions(
                app,
                ImageEmbedder.ImageEmbedderOptions.builder().setBaseOptions(base).setRunningMode(RunningMode.IMAGE).setL2Normalize(true).build(),
            )
            // 1) model by asset path; 2) model read into memory (works even if the asset path cannot be memory-mapped)
            val first = runCatching { build(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build()) }
            val result = first.recoverCatching {
                val bytes = app.assets.open(MODEL).use { it.readBytes() }
                val buf = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
                build(BaseOptions.builder().setModelAssetBuffer(buf).setDelegate(Delegate.CPU).build())
            }
            result.onSuccess {
                embedder = it
                // self-test: the model must really produce a vector of the size stored prints expect
                val ok = runCatching {
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                    it.embed(BitmapImageBuilder(bmp).build()).embeddingResult().embeddings()[0].floatEmbedding().size
                }
                if (ok.getOrNull() == EMB_DIM) initState = 2
                else {
                    embedder = null
                    error = ok.exceptionOrNull()?.let { e -> describe(e) } ?: "اندازهٔ خروجی مدل ${ok.getOrNull()} است (۱۰۲۴ لازم است)"
                    initState = 3
                }
            }.onFailure {
                // report the FIRST failure with its root cause: the second attempt usually only says
                // "NoClassDefFoundError: ...Graph" because the class initialiser already failed during the first one
                error = describe(first.exceptionOrNull() ?: it)
                initState = 3
            }
        }.start()
    }

    /** "Type: message ← RootType: root message" (the root cause is what tells why a model could not start). */
    private fun describe(e: Throwable): String {
        var root = e
        val seen = HashSet<Throwable>()
        while (root.cause != null && seen.add(root)) root = root.cause!!
        val top = "${e.javaClass.simpleName}: ${e.message}"
        return if (root === e) top else "$top ← ${root.javaClass.simpleName}: ${root.message}"
    }

    /** One line for the UI. */
    fun status(): String = when {
        embedder != null -> "مدل هوشمند تشخیص تصویر فعال است"
        initState == 1 -> "مدل هوشمند در حال بارگذاری است…"
        initState == 3 -> "مدل هوشمند بارگذاری نشد (${error ?: "نامشخص"}). حالت رنگی ضعیف فعال است"
        else -> "مدل هوشمند هنوز شروع نشده"
    }

    // ------------------------------------------------------------------ thresholds (need field calibration)
    val showScore: Float get() = if (embedder != null) listOf(0.62f, 0.66f, 0.70f)[level.coerceIn(0, 2)] else 0.80f
    val sureScore: Float get() = if (embedder != null) listOf(0.80f, 0.84f, 0.88f)[level.coerceIn(0, 2)] else 0.93f
    val margin: Float get() = listOf(0.04f, 0.05f, 0.07f)[level.coerceIn(0, 2)]
    private const val LEGACY_SHOW = 0.80f

    // ------------------------------------------------------------------ public API
    /** Descriptor string for a reference photo. */
    fun compute(src: Bitmap): String? =
        embed(src)?.let { "E1:" + encodeEmbedding(it) } ?: if (initState == 3) "C:" + encodeColor(colorDescriptor(src)) else null

    /** [legacy] = score comes from an old colour print: shown as a hint, never accepted automatically. */
    data class Match(val product: ProductWithStock, val score: Float, val legacy: Boolean = false)

    /** Best candidates (score desc). [prints] maps product id -> its stored fingerprints. */
    fun match(frame: Bitmap, products: List<ProductWithStock>, prints: Map<String, String>, limit: Int = 3): List<Match> {
        // two views of the live frame: as enrolled (80% centre) and closer in (55%), for packs held farther from the lens
        val embs = listOfNotNull(embed(frame), embed(frame, 0.55f))
        var color: FloatArray? = null
        val out = ArrayList<Match>()
        for (p in products) {
            val vs = vectors(p.id, prints[p.id] ?: continue)
            if (embs.isNotEmpty()) {
                var best = -1f
                for (v in vs) if (v is Vec.Emb) for (e in embs) best = max(best, dot(e, v.data))
                if (best >= showScore) { out.add(Match(p, best)); continue }
                if (vs.none { it is Vec.Emb }) {
                    if (color == null) color = colorDescriptor(frame)
                    val c = vs.filterIsInstance<Vec.Col>().maxOfOrNull { dot(color!!, it.data) } ?: -1f
                    if (c >= LEGACY_SHOW) out.add(Match(p, c, legacy = true))
                }
            } else {
                if (color == null) color = colorDescriptor(frame)
                val c = vs.filterIsInstance<Vec.Col>().maxOfOrNull { dot(color!!, it.data) } ?: -1f
                if (c >= showScore) out.add(Match(p, c))
            }
        }
        return out.sortedWith(compareBy<Match> { it.legacy }.thenByDescending { it.score }).take(limit)
    }

    /** Enrollment check: the most similar OTHER product for a new reference photo (embedding only). */
    fun mostSimilarOther(frame: Bitmap, products: List<ProductWithStock>, prints: Map<String, String>, exceptId: String?): Match? {
        val emb = embed(frame) ?: return null
        var best: Match? = null
        for (p in products) {
            if (p.id == exceptId) continue
            val s = vectors(p.id, prints[p.id] ?: continue).filterIsInstance<Vec.Emb>().maxOfOrNull { dot(emb, it.data) } ?: continue
            if (best == null || s > best.score) best = Match(p, s)
        }
        return best
    }

    // ------------------------------------------------------------------ embeddings
    private fun centerCrop(src: Bitmap, frac: Float): Bitmap {
        val side = (min(src.width, src.height) * frac).toInt().coerceAtLeast(8)
        return Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    }

    @Synchronized
    private fun embed(src: Bitmap, frac: Float = 0.8f): FloatArray? {
        val e = embedder ?: return null
        return runCatching {
            val crop = centerCrop(src, frac)
            val res = e.embed(BitmapImageBuilder(crop).build())
            if (crop !== src) crop.recycle()
            normalize(res.embeddingResult().embeddings()[0].floatEmbedding().copyOf())
        }.getOrNull()
    }

    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= n
        return v
    }

    private fun encodeEmbedding(v: FloatArray): String {
        var mx = 0f
        for (x in v) mx = max(mx, abs(x))
        mx = mx.coerceAtLeast(1e-6f)
        val buf = ByteBuffer.allocate(4 + v.size)
        buf.putFloat(mx)
        for (x in v) buf.put((x / mx * 127f).toInt().coerceIn(-127, 127).toByte())
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /** Rejects prints of another dimension (a different model) instead of comparing nonsense. */
    private fun decodeEmbedding(s: String): FloatArray? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        if (b.size != 4 + EMB_DIM) return null
        val mx = ByteBuffer.wrap(b).getFloat()
        normalize(FloatArray(EMB_DIM) { b[4 + it] / 127f * mx })
    }.getOrNull()

    // ------------------------------------------------------------------ colour fallback
    private const val GRID = 8
    private const val HUE_BINS = 16
    private const val DIM = GRID * GRID * 3 + HUE_BINS + 4

    private fun colorDescriptor(src: Bitmap): FloatArray {
        val crop = centerCrop(src, 0.7f)
        val small = Bitmap.createScaledBitmap(crop, 32, 32, true)
        if (crop !== src) crop.recycle()
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        small.recycle()
        val out = FloatArray(DIM)
        val cell = 32 / GRID
        val cnt = FloatArray(GRID * GRID)
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val gi = ((i / 32) / cell) * GRID + (i % 32) / cell
            out[gi * 3] += Color.red(c) / 255f
            out[gi * 3 + 1] += Color.green(c) / 255f
            out[gi * 3 + 2] += Color.blue(c) / 255f
            cnt[gi] += 1f
            Color.colorToHSV(c, hsv)
            val hb = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            out[GRID * GRID * 3 + hb] += hsv[1] * hsv[2]
            out[GRID * GRID * 3 + HUE_BINS + (hsv[2] * 4).toInt().coerceIn(0, 3)] += 1f
        }
        for (g in 0 until GRID * GRID) for (k in 0..2) out[g * 3 + k] /= max(cnt[g], 1f)
        val hs = (0 until HUE_BINS).sumOf { out[GRID * GRID * 3 + it].toDouble() }.toFloat().coerceAtLeast(1e-3f)
        for (i in 0 until HUE_BINS) out[GRID * GRID * 3 + i] /= hs
        for (i in 0..3) out[GRID * GRID * 3 + HUE_BINS + i] /= px.size
        var mean = 0f
        for (v in out) mean += v
        mean /= out.size
        for (i in out.indices) out[i] -= mean
        return normalize(out)
    }

    private fun encodeColor(v: FloatArray): String {
        val b = ByteArray(v.size) { (v[it] * 127f * 3f).toInt().coerceIn(-127, 127).toByte() }
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun decodeColor(s: String): FloatArray? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        if (b.size != DIM) return null
        normalize(FloatArray(DIM) { b[it] / (127f * 3f) })
    }.getOrNull()

    // ------------------------------------------------------------------ matching helpers
    private sealed interface Vec {
        class Emb(val data: FloatArray) : Vec
        class Col(val data: FloatArray) : Vec
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d
    }

    private val cache = object : LinkedHashMap<String, List<Vec>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Vec>>?) = size > 300
    }

    @Synchronized
    private fun vectors(id: String, prints: String): List<Vec> = cache.getOrPut(id + ":" + prints.hashCode()) {
        prints.split('|').filter { it.isNotBlank() }.mapNotNull { s ->
            when {
                s.startsWith("E1:") -> decodeEmbedding(s.substring(3))?.let { Vec.Emb(it) }
                s.startsWith("E:") -> decodeEmbedding(s.substring(2))?.let { Vec.Emb(it) }
                s.startsWith("C:") -> decodeColor(s.substring(2))?.let { Vec.Col(it) }
                else -> decodeColor(s)?.let { Vec.Col(it) }
            }
        }
    }
}
