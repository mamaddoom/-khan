package com.mozkhan.shop.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.room.withTransaction
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.mozkhan.shop.data.AppDb
import com.mozkhan.shop.data.CreditPayment
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.Expense
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.RecipeItem
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.data.StockMove
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SyncState(
    val available: Boolean = false,
    val email: String? = null,
    val running: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
)

/**
 * Optional multi-phone sync through Firebase (Google account + Firestore).
 * Firebase can be configured either at build time (google-services.json) or at runtime from
 * Settings -> Google (API key / App id / Project id / Web client id).
 *
 * Firestore keeps its own offline cache & queue, so writes made without internet are
 * uploaded automatically once the connection returns.
 *
 * Conflict model:
 *  - stock_moves / sale_items are immutable & append-only  -> merged without conflicts
 *  - products / expenses / sales / docs / customers / payments / recipes use last-writer-wins on `updatedAt`, except that
 *    the voided / deleted flag is STICKY: once an invoice is voided (or a row deleted) on any phone it stays so, even when
 *    the other phone's clock is ahead (see [SyncRules]).
 *
 * Every write reports its failure in [SyncState.error]; errors stay visible until the thing that failed works again
 * (they are no longer wiped by the next successful snapshot of an unrelated collection).
 */
class CloudSync(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope, private val prefs: Prefs) {

    private fun hasApp(): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    val available: Boolean get() = hasApp()

    private val _state = MutableStateFlow(SyncState(available = hasApp(), email = currentEmail()))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val regs = mutableListOf<ListenerRegistration>()
    @Volatile private var active = false
    /** bumped by every start/stop: work of an older start must never register listeners or upload afterwards */
    private val gen = AtomicInteger(0)
    @Volatile private var runJob: Job? = null

    /** remote snapshots are applied one at a time, in the order they arrived */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val applyDispatcher = Dispatchers.IO.limitedParallelism(1)

    // ---------------------------------------------------------------- errors (sticky, per source)
    private val errors = ConcurrentHashMap<String, String>()

    private fun setErr(key: String, msg: String) {
        errors[key] = msg
        publishErr()
    }

    private fun clearErr(key: String) {
        if (errors.remove(key) != null) publishErr()
    }

    private fun clearErrs(prefix: String) {
        if (errors.keys.removeAll { it.startsWith(prefix) }) publishErr()
    }

    private fun publishErr() {
        val e = errors.entries.sortedBy { it.key }.firstOrNull()?.value
        _state.update { it.copy(error = e) }
    }

    private fun currentEmail(): String? =
        if (hasApp()) runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull() else null

    /**
     * Cloud folder of this shop. With a shop code (no Google account needed) every phone using the same code
     * shares one folder; otherwise it falls back to the signed-in Google user's folder (older behaviour).
     */
    private fun root(): DocumentReference? {
        if (!hasApp()) return null
        val code = prefs.shopCode.trim()
        if (code.isNotEmpty()) return FirebaseFirestore.getInstance().collection("shops").document(code)
        val u = FirebaseAuth.getInstance().currentUser ?: return null
        return FirebaseFirestore.getInstance().collection("shops").document(u.uid)
    }

    /** Firestore rules need *some* auth; with a shop code we use silent anonymous auth (no Google, no prompt). */
    private suspend fun ensureAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null && prefs.shopCode.isNotBlank()) auth.signInAnonymously().await()
    }

    /** Joins (or creates, when [code] is blank) a shop code. Returns the code. */
    fun useShopCode(code: String?): String {
        val c = code?.trim().orEmpty().ifEmpty { Prefs.newShopCode() }
        require(c.length >= 8 && c.all { it.isLetterOrDigit() }) { "کد باید حداقل ۸ حرف/عدد انگلیسی باشد" }
        stop()
        prefs.shopCode = c
        return c
    }

    fun leaveShopCode() {
        stop()
        prefs.shopCode = ""
    }

    /** Initialises Firebase from values typed in Settings (used when google-services.json is absent). */
    fun configureFromPrefs(): Result<Unit> = runCatching {
        if (hasApp()) return@runCatching
        val key = prefs.fbApiKey.trim()
        val app = prefs.fbAppId.trim()
        val project = prefs.fbProjectId.trim()
        require(key.isNotEmpty() && app.isNotEmpty() && project.isNotEmpty()) { "API key، App ID و Project ID را وارد کنید" }
        FirebaseApp.initializeApp(
            ctx,
            FirebaseOptions.Builder().setApiKey(key).setApplicationId(app).setProjectId(project).build(),
        )
        _state.update { it.copy(available = hasApp()) }
        clearErr(K_CONFIG)
    }.onFailure { setErr(K_CONFIG, it.message ?: "تنظیمات Firebase نامعتبر است") }

    /**
     * Real round trip to the server: signs in (anonymous when a shop code is used), writes and reads a test document and
     * compares the number of products / invoices in the cloud with this phone. Returns a message that says what works or what to fix.
     * Runs in the app-wide scope: leaving the Settings screen must not cancel the test (that produced "The coroutine scope left the composition").
     */
    suspend fun testConnection(): Result<String> = scope.async { testConnectionInner() }.await()

    private suspend fun testConnectionInner(): Result<String> = runCatching {
        check(hasApp()) { "ابتدا تنظیمات Firebase را ذخیره کنید" }
        withTimeout(20_000) {
            ensureAuth()
            val r = root() ?: error("کد فروشگاه یا حساب Google تنظیم نشده است")
            val ping = r.collection("meta").document("ping")
            ping.set(mapOf("ts" to System.currentTimeMillis(), "from" to android.os.Build.MODEL)).await()
            check(ping.get(Source.SERVER).await().exists()) { "نوشتن انجام شد اما خواندن از سرور ممکن نشد" }
            // a test WRITE into the invoice collection: rules that only allow some collections show up here
            r.collection("sales").document("_ping").set(mapOf("ts" to System.currentTimeMillis())).await()
            r.collection("sales").document("_ping").delete().await()
            // count queries: a few reads instead of downloading whole collections (free daily quota)
            val cloud = r.collection("products").count().get(AggregateSource.SERVER).await().count
            val cloudSales = r.collection("sales").count().get(AggregateSource.SERVER).await().count
            val local = db.products().allRaw().size
            val localSales = db.sales().allSales().size
            "اتصال برقرار است ✓  کالا در ابر: $cloud • روی این گوشی: $local  |  فاکتور در ابر: $cloudSales • روی این گوشی: $localSales"
        }
    }.onSuccess { clearErr(K_TEST); clearErr(K_START) }
        .recoverCatching { throw IllegalStateException(explain(it)) }
        .onFailure { setErr(K_TEST, it.message ?: "خطای ناشناخته") }

    // ---------------------------------------------------------------- lifecycle
    fun start() {
        if (!hasApp() || active) return
        active = true
        val g = gen.incrementAndGet()
        errors.keys.removeAll { it != K_CONFIG } // a fresh start re-checks everything
        _state.update { it.copy(running = true, email = currentEmail()) }
        publishErr()
        runJob = scope.launch(Dispatchers.IO) {
            val r = runCatching { ensureAuth(); root() }.getOrElse {
                failStart(g, explain(it))
                return@launch
            }
            if (r == null) {
                failStart(g, "کد فروشگاه یا حساب Google تنظیم نشده است")
                return@launch
            }
            // Schema gate: a phone running an OLDER app must never write into data that a newer app already upgraded.
            val meta = r.collection("meta").document("app")
            val remote = runCatching { (meta.get().await().get("schema") as? Number)?.toInt() ?: 0 }
                .onFailure { if (it is FirebaseFirestoreException && it.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) setErr(K_START, explain(it)) }
                .getOrDefault(0)
            if (remote > SCHEMA) {
                failStart(g, "لطفاً برنامه را به‌روزرسانی کنید؛ اطلاعات ابری با نسخهٔ جدیدتری ذخیره شده است")
                return@launch
            }
            if (gen.get() != g) return@launch
            if (remote < SCHEMA) write(meta, mapOf("schema" to SCHEMA))
            val first = HashMap<String, CompletableDeferred<Map<String, RemoteMeta>>>()
            synchronized(regs) {
                if (gen.get() != g) return@launch
                for (c in COLLECTIONS) {
                    val d = CompletableDeferred<Map<String, RemoteMeta>>()
                    first[c] = d
                    listen(r, c, g, d)
                }
            }
            runCatching { pushAllLocal(r, g, first) }.onFailure {
                if (it !is kotlinx.coroutines.CancellationException) setErr(K_PUSH, "ارسال اطلاعات این گوشی ناموفق: " + explain(it))
            }
        }
    }

    private fun failStart(g: Int, msg: String) {
        if (gen.get() != g) return
        active = false
        _state.update { it.copy(running = false) }
        setErr(K_START, msg)
    }

    companion object {
        const val SCHEMA = 3

        internal val COLLECTIONS = listOf("products", "moves", "docs", "sales", "items", "expenses", "customers", "payments", "recipes")

        // error keys: sorted, the first one is shown
        private const val K_CONFIG = "0config"
        private const val K_TEST = "0test"
        private const val K_SIGNIN = "0signin"
        private const val K_START = "1start"
        private const val K_LISTEN = "2listen:"
        private const val K_WRITE = "3write"
        private const val K_PUSH = "4push"
        private const val K_APPLY = "5apply:"

        /** Turns Firebase exceptions into a Persian message that says what to fix. */
        fun explain(e: Throwable?): String {
            val m = e?.message.orEmpty()
            return when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "پاسخی از سرور نیامد؛ اینترنت را بررسی کنید"
                e is kotlinx.coroutines.CancellationException -> "عملیات نیمه‌کاره ماند؛ دوباره امتحان کنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "دسترسی رد شد: در Firebase بخش Firestore ← Rules را مطابق راهنما تنظیم و Publish کنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED ->
                    "سهمیهٔ روزانهٔ Firebase تمام شده است؛ فردا خودکار ادامه می‌یابد (یا در Firebase طرح Blaze را فعال کنید)"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE -> "سرور در دسترس نیست؛ اینترنت را بررسی کنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "ورود به Firebase انجام نشده است؛ «آزمایش اتصال» را بزنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.INVALID_ARGUMENT -> "سرور دادهٔ ارسالی را نپذیرفت: $m"
                e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION || e.code == FirebaseFirestoreException.Code.NOT_FOUND) ->
                    "Firestore Database هنوز ساخته نشده است (Firebase ← Build ← Firestore Database ← Create database)"
                m.contains("OPERATION_NOT_ALLOWED", true) || m.contains("sign-in provider is disabled", true) || m.contains("admin-restricted", true) ->
                    "ورود ناشناس فعال نیست: Firebase ← Authentication ← Sign-in method ← Anonymous را Enable کنید"
                m.contains("API key", true) && (m.contains("not valid", true) || m.contains("invalid", true)) -> "API key اشتباه است"
                m.contains("CONFIGURATION_NOT_FOUND", true) -> "Authentication در پروژهٔ Firebase راه‌اندازی نشده (Get started را بزنید)"
                m.contains("network", true) || m.contains("Unable to resolve host", true) -> "اینترنت برقرار نیست"
                else -> m.ifBlank { e?.javaClass?.simpleName ?: "خطای ناشناخته" }
            }
        }
    }

    fun stop() {
        gen.incrementAndGet()
        runJob?.cancel()
        runJob = null
        synchronized(regs) {
            regs.forEach { it.remove() }
            regs.clear()
        }
        active = false
        _state.update { it.copy(running = false) }
    }

    /** Re-uploads everything local and re-attaches the listeners. Does nothing while sync is switched off. */
    fun syncNow() {
        if (!prefs.cloudSync.value) return
        stop(); start()
    }

    /**
     * Pull-to-refresh: restarts sync and waits (at most [timeoutMs]) until this phone's missing rows were uploaded.
     * Returns a message for the user. Runs in the app scope so leaving the screen cannot cancel it.
     */
    suspend fun refresh(timeoutMs: Long = 25_000): String = scope.async {
        when {
            !hasApp() -> "همگام‌سازی ابری تنظیم نشده است (تنظیمات ← Google)"
            !prefs.cloudSync.value -> "همگام‌سازی خاموش است (تنظیمات ← همگام‌سازی خودکار)"
            else -> {
                syncNow()
                val job = runJob
                val done = job == null || withTimeoutOrNull(timeoutMs) { job.join(); true } == true
                val err = _state.value.error
                when {
                    err != null -> "همگام‌سازی ناموفق: $err"
                    !done -> "همگام‌سازی در پس‌زمینه ادامه دارد (اینترنت کند یا قطع است)"
                    else -> "همگام‌سازی انجام شد"
                }
            }
        }
    }.await()

    fun signOut() {
        stop()
        if (hasApp()) FirebaseAuth.getInstance().signOut()
        _state.update { it.copy(email = null) }
    }

    suspend fun signIn(activity: Activity): Result<String> = runCatching {
        check(hasApp()) { "ابتدا تنظیمات Google (Firebase) را ذخیره کنید" }
        val clientId = prefs.fbWebClientId.trim().ifEmpty {
            val id = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
            if (id != 0) ctx.getString(id) else ""
        }
        check(clientId.isNotEmpty()) { "Web client ID وارد نشده است" }
        val opt = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .build()
        val req = GetCredentialRequest.Builder().addCredentialOption(opt).build()
        val res = CredentialManager.create(activity).getCredential(activity, req)
        val token = GoogleIdTokenCredential.createFrom(res.credential.data).idToken
        val auth = FirebaseAuth.getInstance()
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        val email = auth.currentUser?.email.orEmpty()
        _state.update { it.copy(email = email) }
        clearErr(K_SIGNIN)
        email
    }.onFailure { if (it !is kotlinx.coroutines.CancellationException) setErr(K_SIGNIN, it.message ?: "ورود ناموفق بود") }

    /**
     * Listens to one collection. Only the CHANGED documents of each snapshot are applied (the first snapshot contains
     * every document). The first snapshot confirmed by the server also tells [pushAllLocal] what the cloud already has,
     * so the collection is not downloaded a second time just to compare.
     */
    private fun listen(r: DocumentReference, name: String, g: Int, first: CompletableDeferred<Map<String, RemoteMeta>>) {
        regs += r.collection(name).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (gen.get() != g) return@addSnapshotListener
            if (err != null) {
                // Firestore ends a listener after an error: keep the message until the next (re)start
                setErr(K_LISTEN + name, "دریافت «${fa(name)}» متوقف شد: " + explain(err))
                first.completeExceptionally(err)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            if (!snap.metadata.isFromCache && !first.isCompleted) {
                first.complete(snap.documents.associate { d -> d.id to RemoteMeta.of(d) })
            }
            val changed = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
            if (changed.isEmpty()) return@addSnapshotListener
            scope.launch(applyDispatcher) {
                if (gen.get() != g) return@launch
                runCatching { applyRemote(name, changed) }
                    .onSuccess {
                        clearErr(K_APPLY + name)
                        _state.update { it.copy(lastSync = System.currentTimeMillis()) }
                    }
                    .onFailure { setErr(K_APPLY + name, "ذخیرهٔ «${fa(name)}» دریافتی ناموفق: " + explain(it)) }
            }
        }
    }

    private fun fa(name: String) = when (name) {
        "products" -> "کالاها"; "moves" -> "حرکت انبار"; "docs" -> "اسناد انبار"; "sales" -> "فاکتورها"; "items" -> "اقلام فاکتور"
        "expenses" -> "هزینه‌ها"; "customers" -> "مشتریان"; "payments" -> "دریافتی‌ها"; "recipes" -> "دستور ساخت"; else -> name
    }

    // ---------------------------------------------------------------- push
    private fun col(name: String) = root()?.collection(name)

    /** One Firestore write; a rejection (rules, quota, invalid data) is shown instead of being lost silently. */
    private fun write(ref: DocumentReference, data: Map<String, Any?>) {
        runCatching {
            ref.set(data, SetOptions.merge()).addOnFailureListener { e ->
                setErr(K_WRITE, "ارسال به ابر ناموفق (${fa(ref.parent.id)}): " + explain(e))
            }
        }.onFailure { setErr(K_WRITE, "ارسال به ابر ناموفق: " + explain(it)) }
    }

    /** Several writes that belong together (an invoice, its lines and its stock moves) go up atomically. */
    private fun writeAll(list: List<Pair<DocumentReference, Map<String, Any?>>>) {
        if (list.isEmpty()) return
        runCatching {
            val fs = FirebaseFirestore.getInstance()
            list.chunked(400).forEach { chunk ->
                val b = fs.batch()
                chunk.forEach { (ref, data) -> b.set(ref, data, SetOptions.merge()) }
                b.commit().addOnFailureListener { e -> setErr(K_WRITE, "ارسال به ابر ناموفق (${fa(chunk.first().first.parent.id)}): " + explain(e)) }
            }
        }.onFailure { setErr(K_WRITE, "ارسال به ابر ناموفق: " + explain(it)) }
    }

    fun push(p: Product) { if (active) col("products")?.document(p.id)?.let { write(it, p.toMap()) } }
    fun push(m: StockMove) { if (active) col("moves")?.document(m.id)?.let { write(it, m.toMap()) } }
    fun push(d: StockDoc) { if (active) col("docs")?.document(d.id)?.let { write(it, d.toMap()) } }
    fun push(e: Expense) { if (active) col("expenses")?.document(e.id)?.let { write(it, e.toMap()) } }
    fun push(c: Customer) { if (active) col("customers")?.document(c.id)?.let { write(it, c.toMap()) } }
    fun push(r: RecipeItem) { if (active) col("recipes")?.document(r.id)?.let { write(it, r.toMap()) } }
    fun push(p: CreditPayment) { if (active) col("payments")?.document(p.id)?.let { write(it, p.toMap()) } }

    /** An invoice together with its lines and stock moves, in one atomic batch. */
    fun push(s: Sale, items: List<SaleItem>, moves: List<StockMove> = emptyList()) {
        if (!active) return
        val r = root() ?: return
        val list = ArrayList<Pair<DocumentReference, Map<String, Any?>>>()
        list += r.collection("sales").document(s.id) to s.toMap()
        items.forEach { list += r.collection("items").document(it.id) to it.toMap() }
        moves.forEach { list += r.collection("moves").document(it.id) to it.toMap() }
        writeAll(list)
    }

    /**
     * Uploads every row the cloud does not have yet, or has in an OLDER version. Blindly re-uploading everything would let
     * a phone with old data overwrite newer prices / edits made on another phone (last-writer-wins on updatedAt).
     * Each collection is handled on its own: one failing collection no longer stops the others.
     */
    private suspend fun pushAllLocal(r: DocumentReference, g: Int, first: Map<String, CompletableDeferred<Map<String, RemoteMeta>>>) {
        val fs = FirebaseFirestore.getInstance()
        var failed = false
        suspend fun <T> collect(name: String, local: List<T>, id: (T) -> String, ts: ((T) -> Long)?, flag: ((T) -> Boolean)?, map: (T) -> Map<String, Any?>) {
            val remote = try {
                first.getValue(name).await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failed = true
                setErr(K_PUSH, "ارسال «${fa(name)}» ناموفق: " + explain(e))
                return
            }
            if (gen.get() != g) throw kotlinx.coroutines.CancellationException("stopped")
            val writes = local.filter { x ->
                SyncRules.shouldUpload(ts?.invoke(x), flag?.invoke(x) ?: false, remote[id(x)])
            }.map { x -> r.collection(name).document(id(x)) to map(x) }
            for (chunk in writes.chunked(400)) {
                if (gen.get() != g) throw kotlinx.coroutines.CancellationException("stopped")
                val b = fs.batch()
                chunk.forEach { (ref, data) -> b.set(ref, data, SetOptions.merge()) }
                try {
                    b.commit().await()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    failed = true
                    setErr(K_PUSH, "ارسال «${fa(name)}» ناموفق: " + explain(e))
                }
            }
        }
        collect("products", db.products().allRaw(), { it.id }, { it.updatedAt }, { it.deleted }) { it.toMap() }
        collect("docs", db.stock().allDocs(), { it.id }, { it.updatedAt }, { it.voided }) { it.toMap() }
        collect("sales", db.sales().allSales(), { it.id }, { it.updatedAt }, { it.voided }) { it.toMap() }
        collect("items", db.sales().allItems(), { it.id }, null, null) { it.toMap() }
        collect("moves", db.stock().all(), { it.id }, null, null) { it.toMap() }
        collect("expenses", db.expenses().all(), { it.id }, { it.updatedAt }, { it.deleted }) { it.toMap() }
        collect("customers", db.credit().allCustomers(), { it.id }, { it.updatedAt }, { it.deleted }) { it.toMap() }
        collect("payments", db.credit().allPayments(), { it.id }, { it.updatedAt }, { it.deleted }) { it.toMap() }
        collect("recipes", db.recipes().all(), { it.id }, { it.updatedAt }, { it.deleted }) { it.toMap() }
        if (!failed && gen.get() == g) {
            clearErr(K_PUSH)
            clearErr(K_WRITE) // everything this phone has is now in the cloud
            _state.update { it.copy(lastSync = System.currentTimeMillis()) }
        }
    }

    // ---------------------------------------------------------------- apply (remote -> local)
    private suspend fun applyRemote(name: String, docs: List<DocumentSnapshot>) {
        fun <T> parse(f: Map<String, Any?>.() -> T): List<T> = docs.mapNotNull { d -> runCatching { d.data?.f() }.getOrNull() }
        when (name) {
            "products" -> applyProducts(parse { toProduct() })
            "moves" -> parse { toMove() }.takeIf { it.isNotEmpty() }?.let { db.stock().insertAll(it) }
            "docs" -> applyLww(parse { toDoc() }, { db.stock().doc(it) }, { it.id }, { it.updatedAt }, { it.voided },
                { d, f, t -> d.copy(voided = f, updatedAt = t) }, { db.stock().insertDocs(it) }, { push(it) })
            "sales" -> applyLww(parse { toSale() }, { db.sales().sale(it) }, { it.id }, { it.updatedAt }, { it.voided },
                { s, f, t -> s.copy(voided = f, updatedAt = t) }, { l -> l.forEach { db.sales().insertSale(it) } }, { pushSaleHeader(it) })
            "items" -> parse { toItem() }.takeIf { it.isNotEmpty() }?.let { db.sales().insertItems(it) }
            "expenses" -> applyLww(parse { toExpense() }, { id -> db.expenses().byId(id) }, { it.id }, { it.updatedAt }, { it.deleted },
                { e, f, t -> e.copy(deleted = f, updatedAt = t) }, { db.expenses().upsertAll(it) }, { push(it) })
            "customers" -> applyLww(parse { toCustomer() }, { db.credit().customer(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { c, f, t -> c.copy(deleted = f, updatedAt = t) }, { db.credit().upsertCustomers(it) }, { push(it) })
            "payments" -> applyLww(parse { toPayment() }, { db.credit().payment(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { p, f, t -> p.copy(deleted = f, updatedAt = t) }, { db.credit().upsertPayments(it) }, { push(it) })
            "recipes" -> applyLww(parse { toRecipe() }, { db.recipes().item(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { x, f, t -> x.copy(deleted = f, updatedAt = t) }, { db.recipes().upsertAll(it) }, { push(it) })
        }
    }

    private fun pushSaleHeader(s: Sale) { if (active) col("sales")?.document(s.id)?.let { write(it, s.toMap()) } }

    private suspend fun <T> applyLww(
        incoming: List<T>, local: suspend (String) -> T?, id: (T) -> String, ts: (T) -> Long, flag: (T) -> Boolean,
        withFlag: (T, Boolean, Long) -> T, store: suspend (List<T>) -> Unit, upload: (T) -> Unit,
    ) {
        if (incoming.isEmpty()) return
        val keep = ArrayList<T>()
        val back = ArrayList<T>()
        db.withTransaction {
            for (x in incoming) {
                val m = SyncRules.merge(local(id(x)), x, ts, flag, withFlag)
                m.store?.let { keep += it }
                m.uploadBack?.let { back += it }
            }
            if (keep.isNotEmpty()) store(keep)
        }
        back.forEach(upload)
    }

    private suspend fun applyProducts(incoming: List<Product>) {
        if (incoming.isEmpty()) return
        applyLww(incoming, { db.products().raw(it) }, { it.id }, { it.updatedAt }, { it.deleted },
            { p, f, t -> p.copy(deleted = f, updatedAt = t) }, { db.products().upsertAll(it) }, { push(it) })
        com.mozkhan.shop.data.LegacyUpgrade.ensureCodes(db).forEach { id -> db.products().raw(id)?.let { push(it) } }
    }
}

/** What the cloud holds for one document: enough to decide whether this phone's copy must be uploaded. */
internal data class RemoteMeta(val updatedAt: Long, val flag: Boolean) {
    companion object {
        fun of(d: DocumentSnapshot) = RemoteMeta(
            (d.get("updatedAt") as? Number)?.toLong() ?: 0L,
            (d.get("voided") as? Boolean) == true || (d.get("deleted") as? Boolean) == true,
        )
    }
}

/** Pure merge rules (unit-tested). */
internal object SyncRules {
    data class Merged<T>(val store: T?, val uploadBack: T?)

    /**
     * Remote row [remote] arrives while this phone has [local]. Newest `updatedAt` wins, but the flag (voided / deleted)
     * can only go from false to true. [store] = what to write locally (null: nothing to do);
     * [uploadBack] = this phone knows the row is voided/deleted but the cloud does not yet: send it up.
     */
    fun <T> merge(local: T?, remote: T, ts: (T) -> Long, flag: (T) -> Boolean, withFlag: (T, Boolean, Long) -> T): Merged<T> {
        if (local == null) return Merged(remote, null)
        val winner = if (ts(remote) > ts(local)) remote else local
        val f = flag(local) || flag(remote)
        val t = maxOf(ts(local), ts(remote))
        val out = if (flag(winner) == f && ts(winner) == t) winner else withFlag(winner, f, t)
        return Merged(if (out != local) out else null, if (f && !flag(remote)) out else null)
    }

    /**
     * Must this phone's row be uploaded? Yes when the cloud lacks it, has an older version, or does not know yet that it is
     * voided/deleted. Never when the cloud already has it voided/deleted and this phone does not (the listener fixes this
     * phone instead): uploading would bring a cancelled invoice back to life.
     * [localTs] null = immutable row (stock move / invoice line): only existence matters.
     */
    fun shouldUpload(localTs: Long?, localFlag: Boolean, remote: RemoteMeta?): Boolean {
        if (remote == null) return true
        if (localTs == null) return false
        if (remote.flag && !localFlag) return false
        return localTs > remote.updatedAt || (localFlag && !remote.flag)
    }
}

// ------------------------------------------------------------------ mappers
// Reading is tolerant: a field that an older / newer phone did not write falls back to a default instead of failing.
private fun Map<String, Any?>.s(k: String, d: String = ""): String = (this[k] as? String) ?: d
private fun Map<String, Any?>.sn(k: String): String? = this[k] as? String
private fun Map<String, Any?>.l(k: String, d: Long = 0): Long = (this[k] as? Number)?.toLong() ?: d
private fun Map<String, Any?>.dbl(k: String, d: Double = 0.0): Double = (this[k] as? Number)?.toDouble() ?: d
private fun Map<String, Any?>.b(k: String, d: Boolean = false): Boolean = (this[k] as? Boolean) ?: d

internal fun Product.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "barcode" to barcode, "category" to category, "unit" to unit.name,
    "buyPrice" to buyPrice, "sellPrice" to sellPrice, "minStock" to minStock, "fingerprints" to fingerprints,
    "favorite" to favorite, "deleted" to deleted, "updatedAt" to updatedAt,
    "boxBarcode" to boxBarcode, "boxSize" to boxSize, "boxBuyPrice" to boxBuyPrice, "boxSellPrice" to boxSellPrice,
    "trackStock" to trackStock, "code" to code, "kind" to kind,
)

internal fun Map<String, Any?>.toProduct() = Product(
    id = s("id"), name = s("name"), barcode = sn("barcode"), category = s("category", "سایر"),
    unit = runCatching { Measure.valueOf(s("unit")) }.getOrDefault(Measure.PIECE),
    buyPrice = l("buyPrice"), sellPrice = l("sellPrice"), minStock = dbl("minStock"), fingerprints = s("fingerprints"),
    favorite = b("favorite"), deleted = b("deleted"), updatedAt = l("updatedAt"),
    boxBarcode = sn("boxBarcode"), boxSize = l("boxSize").toInt(), boxBuyPrice = l("boxBuyPrice"), boxSellPrice = l("boxSellPrice"),
    trackStock = b("trackStock", true), code = s("code"), kind = s("kind", "GOODS"),
).also { require(it.id.isNotEmpty()) }

internal fun StockMove.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "productId" to productId, "qty" to qty, "reason" to reason, "refId" to refId, "ts" to ts, "unitCost" to unitCost,
)

internal fun Map<String, Any?>.toMove() = StockMove(s("id"), s("productId"), dbl("qty"), s("reason"), sn("refId"), l("ts"), dbl("unitCost")).also { require(it.id.isNotEmpty()) }

internal fun StockDoc.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "ts" to ts, "kind" to kind, "note" to note, "cost" to cost, "voided" to voided, "updatedAt" to updatedAt,
)

internal fun Map<String, Any?>.toDoc() = StockDoc(s("id"), l("ts"), s("kind"), s("note"), l("cost"), b("voided"), l("updatedAt", l("ts"))).also { require(it.id.isNotEmpty()) }

internal fun Sale.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "ts" to ts, "subtotal" to subtotal, "discount" to discount, "total" to total, "cost" to cost,
    "payment" to payment, "note" to note, "voided" to voided, "updatedAt" to updatedAt, "customerId" to customerId, "deviceName" to deviceName,
)

internal fun Map<String, Any?>.toSale() = Sale(
    id = s("id"), ts = l("ts"), subtotal = l("subtotal"), discount = l("discount"), total = l("total"), cost = l("cost"),
    payment = s("payment", "CASH"), note = s("note"), voided = b("voided"), updatedAt = l("updatedAt", l("ts")), customerId = sn("customerId"), deviceName = s("deviceName"),
).also { require(it.id.isNotEmpty() && it.ts > 0) }

internal fun SaleItem.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "saleId" to saleId, "productId" to productId, "name" to name, "qty" to qty,
    "isWeight" to isWeight, "unitPrice" to unitPrice, "unitCost" to unitCost, "category" to category, "dept" to dept,
)

internal fun Map<String, Any?>.toItem() = SaleItem(
    s("id"), s("saleId"), s("productId"), s("name"), dbl("qty"), b("isWeight"), dbl("unitPrice"), dbl("unitCost"), s("category"), s("dept", "SHOP"),
).also { require(it.id.isNotEmpty() && it.saleId.isNotEmpty()) }

internal fun RecipeItem.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "drinkId" to drinkId, "ingredientId" to ingredientId, "qty" to qty, "deleted" to deleted, "updatedAt" to updatedAt,
)

internal fun Map<String, Any?>.toRecipe() = RecipeItem(s("id"), s("drinkId"), s("ingredientId"), dbl("qty"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

internal fun Expense.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "title" to title, "amount" to amount, "ts" to ts, "deleted" to deleted, "updatedAt" to updatedAt,
)

internal fun Map<String, Any?>.toExpense() = Expense(s("id"), s("title"), l("amount"), l("ts"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

internal fun Customer.toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name, "phone" to phone, "deleted" to deleted, "updatedAt" to updatedAt)

internal fun Map<String, Any?>.toCustomer() = Customer(s("id"), s("name"), s("phone"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

internal fun CreditPayment.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "customerId" to customerId, "amount" to amount, "ts" to ts, "note" to note, "deleted" to deleted, "updatedAt" to updatedAt,
)

internal fun Map<String, Any?>.toPayment() = CreditPayment(s("id"), s("customerId"), l("amount"), l("ts"), s("note"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }
