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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.mozkhan.shop.data.AppDb
import com.mozkhan.shop.data.CreditPayment
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.Expense
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.OutboxRow
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.RecipeItem
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.data.StockMove
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SyncState(
    val available: Boolean = false,
    val email: String? = null,
    val running: Boolean = false,
    /** connected: listeners attached and the uploader is running */
    val ready: Boolean = false,
    val lastSync: Long = 0,
    /** local changes the server has not confirmed yet */
    val pending: Int = 0,
    val error: String? = null,
)

/**
 * Optional multi-phone sync through Firebase Firestore (shop code with anonymous auth, or a Google account).
 *
 * UPLOAD — durable outbox. Every local change records (collection, id) in the `outbox` table inside the same database
 * transaction as the change ([enqueue]). One uploader drains it in batches, always sending the row's CURRENT content, and
 * deletes an entry only after the server confirmed the batch. So nothing is lost when the app is killed, starts offline,
 * is not signed in yet, or Firestore rejects a write (it is retried with back-off and the reason is shown).
 * Before, a change made while sync was not connected was silently dropped.
 *
 * DOWNLOAD — incremental. Every uploaded document carries `srv` = server time of its last write. Each collection is
 * listened to with `srv > cursor` (the newest server time already applied on this phone, stored per cloud folder), so a
 * restart / pull-to-refresh only reads what changed. Before, every start re-downloaded EVERY invoice, line and stock move;
 * on the free Firebase plan that used up the daily read quota within days, and from then on new invoices simply stopped
 * arriving on the other phone. A collection without a cursor (first link, or "full sync" in Settings) is read once in
 * full and compared with this phone: whatever the cloud lacks or has older is queued for upload.
 *
 * CONFLICTS — stock moves / invoice lines are immutable (merge without conflicts); other rows use last-writer-wins on
 * `updatedAt`, except that voided / deleted is STICKY (see [SyncRules]).
 */
class CloudSync(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope, private val prefs: Prefs) {

    private fun hasApp(): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    val available: Boolean get() = hasApp()

    private val _state = MutableStateFlow(SyncState(available = hasApp(), email = currentEmail()))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** collection -> live listener */
    private val regs = ConcurrentHashMap<String, ListenerRegistration>()
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

    /** This phone belongs to a cloud folder: its changes are queued for upload, also while sync is paused or offline. */
    private fun linked(): Boolean = hasApp() &&
        (prefs.shopCode.isNotBlank() || runCatching { FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false))

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

    // ---------------------------------------------------------------- outbox (local -> cloud)
    /**
     * Queues documents for upload. Call it INSIDE the database transaction that changed them (see [OutboxRow]).
     * The uploader notices new entries by itself (Room invalidation fires after the commit).
     */
    suspend fun enqueue(coll: String, ids: Collection<String>) {
        if (ids.isEmpty() || !linked()) return
        db.outbox().add(ids.distinct().map { OutboxRow(coll = coll, docId = it) })
    }

    suspend fun enqueue(coll: String, id: String) = enqueue(coll, listOf(id))

    /**
     * Real round trip to the server: signs in (anonymous when a shop code is used), writes and reads a test document and
     * compares the number of products / invoices in the cloud with this phone. Returns a message that says what works or what to fix.
     * Runs in the app-wide scope: leaving the Settings screen must not cancel the test.
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
            r.collection(SALES).document("_ping").set(mapOf("ts" to System.currentTimeMillis())).await()
            r.collection(SALES).document("_ping").delete().await()
            // count queries: a few reads instead of downloading whole collections (free daily quota)
            val cloud = r.collection(PRODUCTS).count().get(AggregateSource.SERVER).await().count
            val cloudSales = r.collection(SALES).count().get(AggregateSource.SERVER).await().count
            val local = db.products().allRaw().size
            val localSales = db.sales().allSales().size
            val queued = db.outbox().count()
            "اتصال برقرار است ✓  کالا در ابر: $cloud • روی این گوشی: $local  |  فاکتور در ابر: $cloudSales • روی این گوشی: $localSales" +
                if (queued > 0) "  |  در صف ارسال: $queued" else ""
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
        _state.update { it.copy(running = true, ready = false, email = currentEmail()) }
        publishErr()
        runJob = scope.launch(Dispatchers.IO) {
            val r = connect(g) ?: return@launch
            if (gen.get() != g) return@launch
            // the server's clock: the cursor of a collection read in full starts there (phone clocks can be wrong)
            val serverNow = if (COLLECTIONS.any { prefs.syncCursor(cursorKey(r, it)) == 0L }) serverTime(r) else 0L
            synchronized(regs) {
                if (gen.get() != g) return@launch
                for (c in COLLECTIONS) listen(r, c, g, serverNow, 0)
            }
            _state.update { it.copy(ready = true, pending = db.outbox().count(), email = currentEmail()) }
            upload(r, g)
        }
    }

    /** Signs in and passes the schema gate. Network problems are retried with back-off (a phone that starts offline syncs later by itself). */
    private suspend fun connect(g: Int): DocumentReference? {
        var wait = 5_000L
        while (gen.get() == g) {
            try {
                withTimeout(30_000) { ensureAuth() }
                val r = root()
                if (r == null) {
                    failStart(g, "کد فروشگاه یا حساب Google تنظیم نشده است")
                    return null
                }
                // Schema gate: a phone running an OLDER app must never write into data that a newer app already upgraded.
                val meta = r.collection("meta").document("app")
                val remote = withTimeout(30_000) { (meta.get().await().get("schema") as? Number)?.toInt() ?: 0 }
                if (remote > SCHEMA) {
                    failStart(g, "لطفاً برنامه را به‌روزرسانی کنید؛ اطلاعات ابری با نسخهٔ جدیدتری ذخیره شده است")
                    return null
                }
                if (remote < SCHEMA) withTimeout(30_000) { meta.set(mapOf("schema" to SCHEMA), SetOptions.merge()).await() }
                clearErr(K_START)
                return r
            } catch (e: Throwable) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (gen.get() != g) return null
                setErr(K_START, explain(e))
                delay(wait)
                wait = minOf(wait * 2, 300_000L)
            }
        }
        return null
    }

    private suspend fun serverTime(r: DocumentReference): Long = try {
        withTimeout(20_000) {
            val d = r.collection("meta").document("clock")
            d.set(mapOf("t" to FieldValue.serverTimestamp())).await()
            d.get(Source.SERVER).await().getTimestamp("t")?.toDate()?.time ?: 0L
        }
    } catch (e: Throwable) {
        if (e is CancellationException && e !is TimeoutCancellationException) throw e
        0L
    }

    private fun failStart(g: Int, msg: String) {
        if (gen.get() != g) return
        active = false
        _state.update { it.copy(running = false, ready = false) }
        setErr(K_START, msg)
    }

    fun stop() {
        gen.incrementAndGet()
        runJob?.cancel()
        runJob = null
        synchronized(regs) {
            regs.values.forEach { it.remove() }
            regs.clear()
        }
        active = false
        _state.update { it.copy(running = false, ready = false) }
    }

    /** Reconnects (cheap: only what changed since the last time is read). Does nothing while sync is switched off. */
    fun syncNow() {
        if (!prefs.cloudSync.value) return
        stop(); start()
    }

    /**
     * Forgets what was already downloaded: every collection is read once in full, compared with this phone, and whatever
     * the cloud lacks or has older is uploaded. For repairs after restoring a backup or when the phones disagree.
     */
    fun fullResync() {
        prefs.clearSyncCursors()
        syncNow()
    }

    /**
     * Pull-to-refresh: (re)connects when needed and waits (at most [timeoutMs]) until every local change was confirmed by
     * the server. Returns a message for the user. Runs in the app scope so leaving the screen cannot cancel it.
     */
    suspend fun refresh(timeoutMs: Long = 25_000): String = scope.async {
        when {
            !hasApp() -> "همگام‌سازی ابری تنظیم نشده است (تنظیمات ← Google)"
            !prefs.cloudSync.value -> "همگام‌سازی خاموش است (تنظیمات ← همگام‌سازی خودکار)"
            else -> {
                if (!active || errors.keys.any { it == K_START || it.startsWith(K_LISTEN) }) { stop(); start() }
                val done = withTimeoutOrNull(timeoutMs) { state.first { it.ready && it.pending == 0 } } != null
                val s = _state.value
                when {
                    s.error != null -> "همگام‌سازی ناموفق: ${s.error}"
                    !done && s.pending > 0 -> "${s.pending} مورد هنوز ارسال نشده؛ در پس‌زمینه ادامه دارد (اینترنت کند یا قطع است)"
                    !done -> "اتصال به سرور برقرار نشد؛ در پس‌زمینه دوباره تلاش می‌شود"
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
    }.onFailure { if (it !is CancellationException) setErr(K_SIGNIN, it.message ?: "ورود ناموفق بود") }

    // ---------------------------------------------------------------- download (cloud -> local)
    private fun cursorKey(r: DocumentReference, coll: String) = r.id + "_" + coll

    /**
     * Listens to one collection: in full when it has no cursor yet, otherwise only to documents written after the cursor.
     * Only CHANGED documents of each snapshot are applied. The cursor moves on only after the documents were stored, so a
     * failure re-reads them next time instead of skipping them. A listener that Firestore ends with an error (quota,
     * permission, network) is re-attached with back-off.
     */
    private fun listen(r: DocumentReference, name: String, g: Int, serverNow: Long, attempt: Int) {
        val key = cursorKey(r, name)
        val cur = prefs.syncCursor(key)
        val full = cur == 0L
        val q: Query = if (full) r.collection(name)
        else r.collection(name).whereGreaterThan("srv", Timestamp(Date(cur - CURSOR_MARGIN_MS)))
        var reconciled = false
        regs[name] = q.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (gen.get() != g) return@addSnapshotListener
            if (err != null) {
                setErr(K_LISTEN + name, "دریافت «${fa(name)}» متوقف شد: " + explain(err))
                regs.remove(name)?.remove()
                val wait = minOf(30_000L shl attempt.coerceAtMost(4), 600_000L)
                scope.launch {
                    delay(wait)
                    synchronized(regs) { if (gen.get() == g && active && !regs.containsKey(name)) listen(r, name, g, serverNow, attempt + 1) }
                }
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            val fromServer = !snap.metadata.isFromCache
            if (fromServer) clearErr(K_LISTEN + name)
            val changed = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document }
            var newest = 0L
            if (fromServer) for (d in changed) {
                if (d.metadata.hasPendingWrites()) continue
                val t = d.getTimestamp("srv")?.toDate()?.time ?: continue
                if (t > newest) newest = t
            }
            // first complete answer of a full read: compare with this phone and queue what the cloud lacks
            val remote = if (full && fromServer && !reconciled) {
                reconciled = true
                snap.documents.associate { d -> d.id to RemoteMeta.of(d) }
            } else null
            if (changed.isEmpty() && remote == null) return@addSnapshotListener
            scope.launch(applyDispatcher) {
                if (gen.get() != g) return@launch
                try {
                    if (changed.isNotEmpty()) applyRemote(name, changed)
                    if (remote != null) reconcile(name, remote)
                    clearErr(K_APPLY + name)
                    val next = maxOf(newest, if (remote != null) serverNow else 0L)
                    if (next > prefs.syncCursor(key)) prefs.setSyncCursor(key, next)
                    if (fromServer) _state.update { it.copy(lastSync = System.currentTimeMillis()) }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    setErr(K_APPLY + name, "ذخیرهٔ «${fa(name)}» دریافتی ناموفق: " + explain(e))
                }
            }
        }
    }

    private fun fa(name: String) = when (name) {
        PRODUCTS -> "کالاها"; MOVES -> "حرکت انبار"; DOCS -> "اسناد انبار"; SALES -> "فاکتورها"; ITEMS -> "اقلام فاکتور"
        EXPENSES -> "هزینه‌ها"; CUSTOMERS -> "مشتریان"; PAYMENTS -> "دریافتی‌ها"; RECIPES -> "دستور ساخت"; else -> name
    }

    /** Queues every local row that the cloud lacks, has in an older version, or does not know to be voided/deleted yet. */
    private suspend fun reconcile(name: String, remote: Map<String, RemoteMeta>) {
        fun <T> pick(list: List<T>, id: (T) -> String, ts: ((T) -> Long)?, flag: ((T) -> Boolean)?) =
            list.filter { SyncRules.shouldUpload(ts?.invoke(it), flag?.invoke(it) ?: false, remote[id(it)]) }.map(id)
        val ids = when (name) {
            PRODUCTS -> pick(db.products().allRaw(), { it.id }, { it.updatedAt }, { it.deleted })
            DOCS -> pick(db.stock().allDocs(), { it.id }, { it.updatedAt }, { it.voided })
            SALES -> pick(db.sales().allSales(), { it.id }, { it.updatedAt }, { it.voided })
            ITEMS -> pick(db.sales().allItems(), { it.id }, null, null)
            MOVES -> pick(db.stock().all(), { it.id }, null, null)
            EXPENSES -> pick(db.expenses().all(), { it.id }, { it.updatedAt }, { it.deleted })
            CUSTOMERS -> pick(db.credit().allCustomers(), { it.id }, { it.updatedAt }, { it.deleted })
            PAYMENTS -> pick(db.credit().allPayments(), { it.id }, { it.updatedAt }, { it.deleted })
            RECIPES -> pick(db.recipes().all(), { it.id }, { it.updatedAt }, { it.deleted })
            else -> emptyList()
        }
        enqueue(name, ids)
    }

    private suspend fun applyRemote(name: String, docs: List<DocumentSnapshot>) {
        fun <T> parse(f: Map<String, Any?>.() -> T): List<T> = docs.mapNotNull { d -> runCatching { d.data?.f() }.getOrNull() }
        when (name) {
            PRODUCTS -> applyProducts(parse { toProduct() })
            MOVES -> parse { toMove() }.takeIf { it.isNotEmpty() }?.let { db.stock().insertAll(it) }
            DOCS -> applyLww(DOCS, parse { toDoc() }, { db.stock().doc(it) }, { it.id }, { it.updatedAt }, { it.voided },
                { d, f, t -> d.copy(voided = f, updatedAt = t) }, { db.stock().insertDocs(it) })
            SALES -> applyLww(SALES, parse { toSale() }, { db.sales().sale(it) }, { it.id }, { it.updatedAt }, { it.voided },
                { s, f, t -> s.copy(voided = f, updatedAt = t) }, { l -> l.forEach { db.sales().insertSale(it) } })
            ITEMS -> parse { toItem() }.takeIf { it.isNotEmpty() }?.let { db.sales().insertItems(it) }
            EXPENSES -> applyLww(EXPENSES, parse { toExpense() }, { id -> db.expenses().byId(id) }, { it.id }, { it.updatedAt }, { it.deleted },
                { e, f, t -> e.copy(deleted = f, updatedAt = t) }, { db.expenses().upsertAll(it) })
            CUSTOMERS -> applyLww(CUSTOMERS, parse { toCustomer() }, { db.credit().customer(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { c, f, t -> c.copy(deleted = f, updatedAt = t) }, { db.credit().upsertCustomers(it) })
            PAYMENTS -> applyLww(PAYMENTS, parse { toPayment() }, { db.credit().payment(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { p, f, t -> p.copy(deleted = f, updatedAt = t) }, { db.credit().upsertPayments(it) })
            RECIPES -> applyLww(RECIPES, parse { toRecipe() }, { db.recipes().item(it) }, { it.id }, { it.updatedAt }, { it.deleted },
                { x, f, t -> x.copy(deleted = f, updatedAt = t) }, { db.recipes().upsertAll(it) })
        }
    }

    private suspend fun <T> applyLww(
        coll: String, incoming: List<T>, local: suspend (String) -> T?, id: (T) -> String, ts: (T) -> Long, flag: (T) -> Boolean,
        withFlag: (T, Boolean, Long) -> T, store: suspend (List<T>) -> Unit,
    ) {
        if (incoming.isEmpty()) return
        db.withTransaction {
            val keep = ArrayList<T>()
            val back = ArrayList<String>()
            for (x in incoming) {
                val m = SyncRules.merge(local(id(x)), x, ts, flag, withFlag)
                m.store?.let { keep += it }
                m.uploadBack?.let { back += id(it) }
            }
            if (keep.isNotEmpty()) store(keep)
            // this phone knows the row is voided/deleted but the cloud does not yet
            enqueue(coll, back)
        }
    }

    private suspend fun applyProducts(incoming: List<Product>) {
        if (incoming.isEmpty()) return
        applyLww(PRODUCTS, incoming, { db.products().raw(it) }, { it.id }, { it.updatedAt }, { it.deleted },
            { p, f, t -> p.copy(deleted = f, updatedAt = t) }, { db.products().upsertAll(it) })
        enqueue(PRODUCTS, com.mozkhan.shop.data.LegacyUpgrade.ensureCodes(db))
    }

    // ---------------------------------------------------------------- upload (outbox -> cloud)
    /** Drains the outbox for as long as this start lives; wakes up whenever the outbox changes. */
    private suspend fun upload(r: DocumentReference, g: Int) = coroutineScope {
        val signal = Channel<Unit>(Channel.CONFLATED)
        launch {
            db.outbox().observeCount().collect { n ->
                _state.update { it.copy(pending = n) }
                if (n > 0) signal.trySend(Unit)
            }
        }
        var wait = 3_000L
        while (gen.get() == g) {
            signal.receive()
            while (gen.get() == g) {
                val rows = db.outbox().next(BATCH)
                if (rows.isEmpty()) {
                    clearErr(K_WRITE)
                    break
                }
                val ok = try {
                    send(r, rows)
                    true
                } catch (e: Throwable) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    setErr(K_WRITE, "ارسال به ابر ناموفق (${fa(rows.first().coll)}): " + explain(e))
                    false
                }
                if (ok) {
                    db.outbox().remove(rows.map { it.seq })
                    wait = 3_000L
                    clearErr(K_WRITE)
                    _state.update { it.copy(lastSync = System.currentTimeMillis()) }
                } else {
                    delay(wait)
                    wait = minOf(wait * 2, 300_000L)
                }
            }
        }
    }

    /** One atomic batch with the CURRENT local content of [rows] (an invoice, its lines and stock moves usually travel together). */
    private suspend fun send(r: DocumentReference, rows: List<OutboxRow>) {
        val b = FirebaseFirestore.getInstance().batch()
        var n = 0
        for ((coll, list) in rows.groupBy { it.coll }) {
            val data = load(coll, list.map { it.docId })
            for (row in list) {
                val m = data[row.docId] ?: continue // no longer on this phone: nothing to send
                b.set(r.collection(coll).document(row.docId), m + ("srv" to FieldValue.serverTimestamp()), SetOptions.merge())
                n++
            }
        }
        if (n == 0) return
        val task = b.commit()
        // Offline, Firestore keeps the batch in its own queue and sends it when the connection returns: wait for that
        // same batch (instead of queueing it again and again). A rejection (rules, quota, invalid data) throws.
        while (withTimeoutOrNull(60_000) { task.await(); true } == null) Unit
    }

    private suspend fun load(coll: String, ids: List<String>): Map<String, Map<String, Any?>> = when (coll) {
        PRODUCTS -> db.products().rawIn(ids).associate { it.id to it.toMap() }
        MOVES -> db.stock().movesIn(ids).associate { it.id to it.toMap() }
        DOCS -> db.stock().docsIn(ids).associate { it.id to it.toMap() }
        SALES -> db.sales().salesIn(ids).associate { it.id to it.toMap() }
        ITEMS -> db.sales().itemsIn(ids).associate { it.id to it.toMap() }
        EXPENSES -> db.expenses().byIds(ids).associate { it.id to it.toMap() }
        CUSTOMERS -> db.credit().customersIn(ids).associate { it.id to it.toMap() }
        PAYMENTS -> db.credit().paymentsIn(ids).associate { it.id to it.toMap() }
        RECIPES -> db.recipes().itemsIn(ids).associate { it.id to it.toMap() }
        else -> emptyMap()
    }

    companion object {
        /** 4: documents carry `srv` (server write time) for incremental download; older apps must update */
        const val SCHEMA = 4

        const val PRODUCTS = "products"
        const val MOVES = "moves"
        const val DOCS = "docs"
        const val SALES = "sales"
        const val ITEMS = "items"
        const val EXPENSES = "expenses"
        const val CUSTOMERS = "customers"
        const val PAYMENTS = "payments"
        const val RECIPES = "recipes"

        internal val COLLECTIONS = listOf(PRODUCTS, CUSTOMERS, SALES, ITEMS, MOVES, DOCS, EXPENSES, PAYMENTS, RECIPES)

        /** documents per Firestore batch (the hard limit is 500) */
        private const val BATCH = 400

        /** an incremental listener re-reads this much before its cursor (cheap insurance against edge cases) */
        private const val CURSOR_MARGIN_MS = 10 * 60_000L

        // error keys: sorted, the first one is shown
        private const val K_CONFIG = "0config"
        private const val K_TEST = "0test"
        private const val K_SIGNIN = "0signin"
        private const val K_START = "1start"
        private const val K_LISTEN = "2listen:"
        private const val K_WRITE = "3write"
        private const val K_APPLY = "5apply:"

        /** Turns Firebase exceptions into a Persian message that says what to fix. */
        fun explain(e: Throwable?): String {
            val m = e?.message.orEmpty()
            return when {
                e is TimeoutCancellationException -> "پاسخی از سرور نیامد؛ اینترنت را بررسی کنید"
                e is CancellationException -> "عملیات نیمه‌کاره ماند؛ دوباره امتحان کنید"
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
