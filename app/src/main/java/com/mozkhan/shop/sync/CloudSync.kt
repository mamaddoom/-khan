package com.mozkhan.shop.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.FirebaseFirestoreException
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

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
 *  - stock_moves / sale_items / stock_docs are immutable & append-only  -> merged without conflicts
 *  - products / expenses / sales / customers / payments use last-writer-wins on `updatedAt`
 */
class CloudSync(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope, private val prefs: Prefs) {

    private fun hasApp(): Boolean = runCatching { FirebaseApp.getApps(ctx).isNotEmpty() }.getOrDefault(false)

    val available: Boolean get() = hasApp()

    private val _state = MutableStateFlow(SyncState(available = hasApp(), email = currentEmail()))
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val regs = mutableListOf<ListenerRegistration>()
    private var active = false

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
        _state.value = _state.value.copy(available = hasApp(), error = null)
    }.onFailure { _state.value = _state.value.copy(error = it.message) }

    /**
     * Real round trip to the server: signs in (anonymous when a shop code is used), writes and reads a test document and
     * compares the number of products in the cloud with this phone. Returns a message that says what works or what to fix.
     */
    suspend fun testConnection(): Result<String> = runCatching {
        check(hasApp()) { "ابتدا تنظیمات Firebase را ذخیره کنید" }
        withTimeout(20_000) {
            ensureAuth()
            val r = root() ?: error("کد فروشگاه یا حساب Google تنظیم نشده است")
            val ping = r.collection("meta").document("ping")
            ping.set(mapOf("ts" to System.currentTimeMillis(), "from" to android.os.Build.MODEL)).await()
            check(ping.get(Source.SERVER).await().exists()) { "نوشتن انجام شد اما خواندن از سرور ممکن نشد" }
            val cloud = r.collection("products").get(Source.SERVER).await().size()
            val local = db.products().allRaw().size
            "اتصال برقرار است ✓  کالا در ابر: $cloud • روی این گوشی: $local"
        }
    }.onSuccess { _state.value = _state.value.copy(error = null) }
        .recoverCatching { throw IllegalStateException(explain(it)) }
        .onFailure { _state.value = _state.value.copy(error = it.message) }

    // ---------------------------------------------------------------- lifecycle
    fun start() {
        if (!hasApp() || active) return
        active = true
        _state.value = _state.value.copy(running = true, email = currentEmail(), error = null)
        scope.launch(Dispatchers.IO) {
            val r = runCatching { ensureAuth(); root() }.getOrElse {
                active = false
                _state.value = _state.value.copy(running = false, error = explain(it))
                return@launch
            }
            if (r == null) {
                active = false
                _state.value = _state.value.copy(running = false, error = "کد فروشگاه یا حساب Google تنظیم نشده است")
                return@launch
            }
            // Schema gate: a phone running an OLDER app must never write into data that a newer app already upgraded.
            val meta = r.collection("meta").document("app")
            val remote = runCatching { (meta.get().await().get("schema") as? Number)?.toInt() ?: 0 }
                .onFailure { if (it is FirebaseFirestoreException && it.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) _state.value = _state.value.copy(error = explain(it)) }
                .getOrDefault(0)
            if (remote > SCHEMA) {
                active = false
                _state.value = _state.value.copy(running = false, error = "لطفاً برنامه را به‌روزرسانی کنید؛ اطلاعات ابری با نسخهٔ جدیدتری ذخیره شده است")
                return@launch
            }
            runCatching { meta.set(mapOf("schema" to maxOf(remote, SCHEMA)), SetOptions.merge()) }
            listen(r, "products") { applyProducts(it) }
            listen(r, "moves") { applyMoves(it) }
            listen(r, "docs") { applyDocs(it) }
            listen(r, "sales") { applySales(it) }
            listen(r, "items") { applyItems(it) }
            listen(r, "expenses") { applyExpenses(it) }
            listen(r, "customers") { applyCustomers(it) }
            listen(r, "payments") { applyPayments(it) }
            listen(r, "recipes") { applyRecipes(it) }
            runCatching { pushAllLocal() }.onFailure { _state.value = _state.value.copy(error = explain(it)) }
        }
    }

    companion object {
        const val SCHEMA = 3

        /** Turns Firebase exceptions into a Persian message that says what to fix. */
        fun explain(e: Throwable?): String {
            val m = e?.message.orEmpty()
            return when {
                e is kotlinx.coroutines.TimeoutCancellationException -> "پاسخی از سرور نیامد؛ اینترنت را بررسی کنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "دسترسی رد شد: در Firebase بخش Firestore ← Rules را مطابق راهنما تنظیم و Publish کنید"
                e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE -> "سرور در دسترس نیست؛ اینترنت را بررسی کنید"
                e is FirebaseFirestoreException && (e.code == FirebaseFirestoreException.Code.FAILED_PRECONDITION || e.code == FirebaseFirestoreException.Code.NOT_FOUND) ->
                    "Firestore Database هنوز ساخته نشده است (Firebase ← Build ← Firestore Database ← Create database)"
                m.contains("OPERATION_NOT_ALLOWED", true) || m.contains("sign-in provider is disabled", true) || m.contains("admin-restricted", true) ->
                    "ورود ناشناس فعال نیست: Firebase ← Authentication ← Sign-in method ← Anonymous را Enable کنید"
                m.contains("API key", true) && (m.contains("not valid", true) || m.contains("invalid", true)) -> "API key اشتباه است"
                m.contains("CONFIGURATION_NOT_FOUND", true) -> "Authentication در پروژهٔ Firebase راه‌اندازی نشده (Get started را بزنید)"
                m.contains("network", true) || m.contains("Unable to resolve host", true) -> "اینترنت برقرار نیست"
                else -> m.ifBlank { "خطای ناشناخته" }
            }
        }
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs.clear()
        active = false
        _state.value = _state.value.copy(running = false)
    }

    /** Re-uploads everything local and re-attaches the listeners. */
    fun syncNow() {
        stop(); start()
    }

    fun signOut() {
        stop()
        if (hasApp()) FirebaseAuth.getInstance().signOut()
        _state.value = _state.value.copy(email = null)
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
        _state.value = _state.value.copy(email = email, error = null)
        email
    }.onFailure { _state.value = _state.value.copy(error = it.message ?: "ورود ناموفق بود") }

    private fun listen(r: DocumentReference, name: String, apply: suspend (QuerySnapshot) -> Unit) {
        regs += r.collection(name).addSnapshotListener { snap, err ->
            if (err != null) {
                _state.value = _state.value.copy(error = explain(err))
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            scope.launch(Dispatchers.IO) {
                runCatching { apply(snap) }
                    .onSuccess { _state.value = _state.value.copy(lastSync = System.currentTimeMillis(), error = null) }
                    .onFailure { _state.value = _state.value.copy(error = explain(it)) }
            }
        }
    }

    // ---------------------------------------------------------------- push
    private fun col(name: String) = root()?.collection(name)

    fun push(p: Product) { if (active) col("products")?.document(p.id)?.set(p.toMap(), SetOptions.merge()) }
    fun push(m: StockMove) { if (active) col("moves")?.document(m.id)?.set(m.toMap(), SetOptions.merge()) }
    fun push(d: StockDoc) { if (active) col("docs")?.document(d.id)?.set(d.toMap(), SetOptions.merge()) }
    fun push(e: Expense) { if (active) col("expenses")?.document(e.id)?.set(e.toMap(), SetOptions.merge()) }
    fun push(c: Customer) { if (active) col("customers")?.document(c.id)?.set(c.toMap(), SetOptions.merge()) }
    fun push(r: RecipeItem) { if (active) col("recipes")?.document(r.id)?.set(r.toMap(), SetOptions.merge()) }
    fun push(p: CreditPayment) { if (active) col("payments")?.document(p.id)?.set(p.toMap(), SetOptions.merge()) }
    fun push(s: Sale, items: List<SaleItem>) {
        if (!active) return
        col("sales")?.document(s.id)?.set(s.toMap(), SetOptions.merge())
        items.forEach { col("items")?.document(it.id)?.set(it.toMap(), SetOptions.merge()) }
    }

    private suspend fun pushAllLocal() {
        val r = root() ?: return
        val fs = FirebaseFirestore.getInstance()
        val writes = ArrayList<Pair<DocumentReference, Map<String, Any?>>>()
        // Only upload what the cloud does not have yet, or what is NEWER here. Blindly re-uploading everything would let a
        // phone with old data overwrite newer prices / edits made on another phone (last-writer-wins on updatedAt).
        suspend fun <T> collect(name: String, local: List<T>, id: (T) -> String, ts: ((T) -> Long)?, map: (T) -> Map<String, Any?>) {
            val remote = r.collection(name).get().await().documents.associate { d -> d.id to ((d.get("updatedAt") as? Number)?.toLong() ?: 0L) }
            for (x in local) {
                val rt = remote[id(x)]
                if (rt == null || (ts != null && ts(x) > rt)) writes += r.collection(name).document(id(x)) to map(x)
            }
        }
        collect("products", db.products().allRaw(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("moves", db.stock().all(), { it.id }, null) { it.toMap() }
        collect("docs", db.stock().allDocs(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("sales", db.sales().allSales(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("items", db.sales().allItems(), { it.id }, null) { it.toMap() }
        collect("expenses", db.expenses().all(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("customers", db.credit().allCustomers(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("payments", db.credit().allPayments(), { it.id }, { it.updatedAt }) { it.toMap() }
        collect("recipes", db.recipes().all(), { it.id }, { it.updatedAt }) { it.toMap() }
        writes.chunked(400).forEach { chunk ->
            val b = fs.batch()
            chunk.forEach { (ref, data) -> b.set(ref, data, SetOptions.merge()) }
            b.commit().await()
        }
    }

    // ---------------------------------------------------------------- apply (remote -> local)
    private suspend fun applyProducts(s: QuerySnapshot) {
        val incoming = s.documents.mapNotNull { d -> runCatching { d.data?.toProduct() }.getOrNull() }
        val newer = incoming.filter { p -> (db.products().raw(p.id)?.updatedAt ?: -1) < p.updatedAt }
        if (newer.isNotEmpty()) {
            db.products().upsertAll(newer)
            com.mozkhan.shop.data.LegacyUpgrade.ensureCodes(db).forEach { id -> db.products().raw(id)?.let { push(it) } }
        }
    }

    private suspend fun applyMoves(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toMove() }.getOrNull() }
        if (list.isNotEmpty()) db.stock().insertAll(list)
    }

    private suspend fun applyDocs(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toDoc() }.getOrNull() }
        val newer = list.filter { (db.stock().doc(it.id)?.updatedAt ?: -1) < it.updatedAt }
        if (newer.isNotEmpty()) db.stock().insertDocs(newer)
    }

    private suspend fun applySales(s: QuerySnapshot) {
        for (d in s.documents) {
            val sale = runCatching { d.data?.toSale() }.getOrNull() ?: continue
            val local = db.sales().sale(sale.id)
            if (local == null || local.updatedAt < sale.updatedAt) db.sales().insertSale(sale)
        }
    }

    private suspend fun applyItems(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toItem() }.getOrNull() }
        if (list.isNotEmpty()) db.sales().insertItems(list)
    }

    private suspend fun applyExpenses(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toExpense() }.getOrNull() }
        if (list.isEmpty()) return
        val all = db.expenses().all().associateBy { it.id }
        val newer = list.filter { (all[it.id]?.updatedAt ?: -1) < it.updatedAt }
        if (newer.isNotEmpty()) db.expenses().upsertAll(newer)
    }

    private suspend fun applyCustomers(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toCustomer() }.getOrNull() }
        val newer = list.filter { (db.credit().customer(it.id)?.updatedAt ?: -1) < it.updatedAt }
        if (newer.isNotEmpty()) db.credit().upsertCustomers(newer)
    }

    private suspend fun applyRecipes(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toRecipe() }.getOrNull() }
        val newer = list.filter { (db.recipes().item(it.id)?.updatedAt ?: -1) < it.updatedAt }
        if (newer.isNotEmpty()) db.recipes().upsertAll(newer)
    }

    private suspend fun applyPayments(s: QuerySnapshot) {
        val list = s.documents.mapNotNull { d -> runCatching { d.data?.toPayment() }.getOrNull() }
        val newer = list.filter { (db.credit().payment(it.id)?.updatedAt ?: -1) < it.updatedAt }
        if (newer.isNotEmpty()) db.credit().upsertPayments(newer)
    }
}

// ------------------------------------------------------------------ mappers
// Reading is tolerant: a field that an older / newer phone did not write falls back to a default instead of failing.
private fun Map<String, Any?>.s(k: String, d: String = ""): String = (this[k] as? String) ?: d
private fun Map<String, Any?>.sn(k: String): String? = this[k] as? String
private fun Map<String, Any?>.l(k: String, d: Long = 0): Long = (this[k] as? Number)?.toLong() ?: d
private fun Map<String, Any?>.dbl(k: String, d: Double = 0.0): Double = (this[k] as? Number)?.toDouble() ?: d
private fun Map<String, Any?>.b(k: String, d: Boolean = false): Boolean = (this[k] as? Boolean) ?: d

private fun Product.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "name" to name, "barcode" to barcode, "category" to category, "unit" to unit.name,
    "buyPrice" to buyPrice, "sellPrice" to sellPrice, "minStock" to minStock, "fingerprints" to fingerprints,
    "favorite" to favorite, "deleted" to deleted, "updatedAt" to updatedAt,
    "boxBarcode" to boxBarcode, "boxSize" to boxSize, "boxBuyPrice" to boxBuyPrice, "boxSellPrice" to boxSellPrice,
    "trackStock" to trackStock, "code" to code, "kind" to kind,
)

private fun Map<String, Any?>.toProduct() = Product(
    id = s("id"), name = s("name"), barcode = sn("barcode"), category = s("category", "سایر"),
    unit = runCatching { Measure.valueOf(s("unit")) }.getOrDefault(Measure.PIECE),
    buyPrice = l("buyPrice"), sellPrice = l("sellPrice"), minStock = dbl("minStock"), fingerprints = s("fingerprints"),
    favorite = b("favorite"), deleted = b("deleted"), updatedAt = l("updatedAt"),
    boxBarcode = sn("boxBarcode"), boxSize = l("boxSize").toInt(), boxBuyPrice = l("boxBuyPrice"), boxSellPrice = l("boxSellPrice"),
    trackStock = b("trackStock", true), code = s("code"), kind = s("kind", "GOODS"),
).also { require(it.id.isNotEmpty()) }

private fun StockMove.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "productId" to productId, "qty" to qty, "reason" to reason, "refId" to refId, "ts" to ts, "unitCost" to unitCost,
)

private fun Map<String, Any?>.toMove() = StockMove(s("id"), s("productId"), dbl("qty"), s("reason"), sn("refId"), l("ts"), dbl("unitCost")).also { require(it.id.isNotEmpty()) }

private fun StockDoc.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "ts" to ts, "kind" to kind, "note" to note, "cost" to cost, "voided" to voided, "updatedAt" to updatedAt,
)

private fun Map<String, Any?>.toDoc() = StockDoc(s("id"), l("ts"), s("kind"), s("note"), l("cost"), b("voided"), l("updatedAt", l("ts"))).also { require(it.id.isNotEmpty()) }

private fun Sale.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "ts" to ts, "subtotal" to subtotal, "discount" to discount, "total" to total, "cost" to cost,
    "payment" to payment, "note" to note, "voided" to voided, "updatedAt" to updatedAt, "customerId" to customerId,
)

private fun Map<String, Any?>.toSale() = Sale(
    id = s("id"), ts = l("ts"), subtotal = l("subtotal"), discount = l("discount"), total = l("total"), cost = l("cost"),
    payment = s("payment", "CASH"), note = s("note"), voided = b("voided"), updatedAt = l("updatedAt"), customerId = sn("customerId"),
).also { require(it.id.isNotEmpty()) }

private fun SaleItem.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "saleId" to saleId, "productId" to productId, "name" to name, "qty" to qty,
    "isWeight" to isWeight, "unitPrice" to unitPrice, "unitCost" to unitCost, "category" to category, "dept" to dept,
)

private fun Map<String, Any?>.toItem() = SaleItem(
    s("id"), s("saleId"), s("productId"), s("name"), dbl("qty"), b("isWeight"), dbl("unitPrice"), dbl("unitCost"), s("category"), s("dept", "SHOP"),
).also { require(it.id.isNotEmpty()) }

private fun RecipeItem.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "drinkId" to drinkId, "ingredientId" to ingredientId, "qty" to qty, "deleted" to deleted, "updatedAt" to updatedAt,
)

private fun Map<String, Any?>.toRecipe() = RecipeItem(s("id"), s("drinkId"), s("ingredientId"), dbl("qty"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

private fun Expense.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "title" to title, "amount" to amount, "ts" to ts, "deleted" to deleted, "updatedAt" to updatedAt,
)

private fun Map<String, Any?>.toExpense() = Expense(s("id"), s("title"), l("amount"), l("ts"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

private fun Customer.toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name, "phone" to phone, "deleted" to deleted, "updatedAt" to updatedAt)

private fun Map<String, Any?>.toCustomer() = Customer(s("id"), s("name"), s("phone"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }

private fun CreditPayment.toMap(): Map<String, Any?> = mapOf(
    "id" to id, "customerId" to customerId, "amount" to amount, "ts" to ts, "note" to note, "deleted" to deleted, "updatedAt" to updatedAt,
)

private fun Map<String, Any?>.toPayment() = CreditPayment(s("id"), s("customerId"), l("amount"), l("ts"), s("note"), b("deleted"), l("updatedAt")).also { require(it.id.isNotEmpty()) }