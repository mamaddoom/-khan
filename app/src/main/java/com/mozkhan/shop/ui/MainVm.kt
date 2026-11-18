package com.mozkhan.shop.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mozkhan.shop.ShopApp
import com.mozkhan.shop.data.CAT_INGREDIENT
import com.mozkhan.shop.data.CartLine
import com.mozkhan.shop.data.CartOps
import com.mozkhan.shop.data.CreditPayment
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.Expense
import com.mozkhan.shop.data.ItemRow
import com.mozkhan.shop.data.KIND_DRINK
import com.mozkhan.shop.data.KIND_INGREDIENT
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.RecipeItem
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.data.StockMove
import com.mozkhan.shop.data.TopProduct
import com.mozkhan.shop.data.Totals
import com.mozkhan.shop.ml.ImageFingerprint
import com.mozkhan.shop.util.Agg
import com.mozkhan.shop.util.Bucketer
import com.mozkhan.shop.util.CatRow
import com.mozkhan.shop.util.CrashLog
import com.mozkhan.shop.util.DateRange
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali
import com.mozkhan.shop.util.Period
import com.mozkhan.shop.util.Prices
import com.mozkhan.shop.util.Ranges
import com.mozkhan.shop.util.ReportCalc
import com.mozkhan.shop.util.ScanDebouncer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

sealed interface Pending {
    /** [box] = the carton barcode was scanned (sold as a carton at the carton price) */
    data class Found(val p: ProductWithStock, val viaImage: Boolean = false, val box: Boolean = false) : Pending
    data class Unknown(val code: String) : Pending
}

data class PurchaseRow(val docId: String, val ts: Long, val productName: String, val qty: Double, val unit: com.mozkhan.shop.data.Measure, val cost: Long, val supplier: String, val estimated: Boolean)

data class ReportData(
    val range: DateRange = Ranges.of(Period.MONTH, 0),
    val period: Period = Period.MONTH,
    val category: String? = null,
    val dept: String? = null,
    val agg: Agg = Agg(0, 0, 0),
    val expenses: Long = 0,
    val expenseList: List<Expense> = emptyList(),
    val top: List<TopProduct> = emptyList(),
    val byCat: List<CatRow> = emptyList(),
    val labels: List<String> = emptyList(),
    val rev: List<Long> = emptyList(),
    val profit: List<Long> = emptyList(),
    val sales: List<Sale> = emptyList(),
    // purchases & cash (only meaningful without category/department filter)
    val purchases: Long = 0,
    val purchasesEstimated: Boolean = false,
    val purchaseRows: List<PurchaseRow> = emptyList(),
    val exitCost: Long = 0,
    val cashIn: Long = 0,
)

data class InvData(
    val stockValue: Long = 0, val sellValue: Long = 0, val productCount: Int = 0, val lowCount: Int = 0, val outCount: Int = 0,
    val purchased: Long = 0, val soldOut: Long = 0, val exited: Long = 0,
    val labels: List<String> = emptyList(), val inSeries: List<Long> = emptyList(), val outSeries: List<Long> = emptyList(),
    val byCat: List<Pair<String, Long>> = emptyList(),
    val low: List<ProductWithStock> = emptyList(), val dead: List<ProductWithStock> = emptyList(),
    /** product id -> suggested purchase (base units) for the low items */
    val reorder: Map<String, Double> = emptyMap(),
    val list: List<ProductWithStock> = emptyList(),
)

data class CustomerDebt(val customer: Customer, val credit: Long, val paid: Long, val last: Long) {
    val debt get() = credit - paid
}

private val defaultCategories = listOf("سیگار", "فندک", "تنباکو", "ذغال", "پیپ و لوازم", "نوشیدنی", "سایر")

@OptIn(ExperimentalCoroutinesApi::class)
class MainVm(app: Application) : AndroidViewModel(app) {
    private val shop = app as ShopApp
    val repo = shop.repo
    val prefs = shop.prefs
    val sync = shop.sync
    val updater = shop.updater
    private val fb = shop.feedback

    // ---- errors never crash the app: they are logged and shown as a message
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    fun clearError() { _error.value = null }
    private val handler = CoroutineExceptionHandler { _, e ->
        CrashLog.record(getApplication(), e, "NON-FATAL")
        _error.value = e.message ?: "خطای غیرمنتظره"
    }
    fun launch(block: suspend () -> Unit) = viewModelScope.launch(handler) { block() }

    /**
     * Runs a multi-step write in the ViewModel scope and waits for it. A screen's own coroutine scope is cancelled as soon
     * as the screen leaves the composition ("The coroutine scope left the composition"): a save started there could stop
     * half way (e.g. a drink saved without its recipe). Here only the waiting is cancelled, never the write.
     */
    suspend fun <T> persist(block: suspend () -> T): T = viewModelScope.async { block() }.await()

    val currency = prefs.currency

    /** every non-deleted product, cafe ingredients included */
    val allProducts: StateFlow<List<ProductWithStock>> =
        repo.products.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** shop + cafe menu items (never ingredients) */
    val products: StateFlow<List<ProductWithStock>> =
        allProducts.map { l -> l.filter { it.kind != KIND_INGREDIENT } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val ingredients: StateFlow<List<ProductWithStock>> =
        allProducts.map { l -> l.filter { it.kind == KIND_INGREDIENT } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * product id -> stored reference fingerprints (kept out of the product list so stock changes stay cheap).
     * EAGERLY: no screen collects this flow, the camera callbacks only read `.value`. With WhileSubscribed it never
     * started, stayed an empty map, and image recognition could never match anything. [products] is read the same way.
     */
    val prints: StateFlow<Map<String, String>> =
        repo.prints.map { l -> l.associate { it.id to it.fingerprints } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val recipes: StateFlow<List<RecipeItem>> =
        repo.recipeItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** drink id -> cost of one drink computed from its recipe (live, never stored) */
    val recipeCosts: StateFlow<Map<String, Double>> = combine(allProducts, recipes) { prods, recs ->
        val byId = prods.associateBy { it.id }
        recs.groupBy { it.drinkId }.mapValues { (_, l) -> l.sumOf { r -> r.qty * (byId[r.ingredientId]?.baseCost ?: 0.0) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** every category: used by products + manually added + defaults (ingredient category stays inside the cafe) */
    val categories: StateFlow<List<String>> =
        combine(repo.categories, prefs.customCats) { used, custom -> (defaultCategories + used + custom).distinct().filter { it != CAT_INGREDIENT } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), defaultCategories)

    fun addCategory(name: String) = prefs.addCategory(name)

    // ------------------------------------------------------------------ cart
    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart = _cart.asStateFlow()
    private val _discount = MutableStateFlow(0L)
    val discount = _discount.asStateFlow()

    private val _activity = MutableStateFlow(System.currentTimeMillis())
    /** last time the user did something at the register (camera auto-off uses it) */
    val lastActivity = _activity.asStateFlow()
    fun touch() { _activity.value = System.currentTimeMillis() }

    fun addToCart(p: ProductWithStock, qty: Double = 1.0, price: Long? = null, box: Boolean = false) {
        if (qty <= 0 || qty.isNaN()) return
        val unitPrice = price ?: if (box) p.boxSellPrice else p.sellPrice
        val line = CartLine(p, qty, unitPrice, box, if (p.kind == KIND_DRINK) recipeCosts.value[p.id] else null)
        _cart.value = CartOps.add(_cart.value, line)
        touch()
    }

    fun setQty(key: String, qty: Double) {
        _cart.value = CartOps.setQty(_cart.value, key, qty)
        touch()
    }

    fun setPrice(key: String, newPrice: Long) {
        _cart.value = CartOps.setPrice(_cart.value, key, newPrice)
        touch()
    }

    fun removeLine(key: String) = setQty(key, 0.0)
    fun setDiscount(v: Long) { _discount.value = v.coerceAtLeast(0) }
    fun clearCart() { _cart.value = emptyList(); _discount.value = 0 }

    /** true while a sale is being written: the confirm button is disabled and further calls are ignored */
    private val _checkingOut = MutableStateFlow(false)
    val checkingOut = _checkingOut.asStateFlow()

    fun checkout(payment: String, customerId: String?, viaVoice: Boolean = false, onDone: (Sale) -> Unit) {
        if (_checkingOut.value) return // a second tap while the first sale is still being saved
        val lines = _cart.value
        if (lines.isEmpty()) return
        val discountBefore = _discount.value
        _checkingOut.value = true
        viewModelScope.launch(handler) {
            try {
                val sale = repo.checkout(lines, _discount.value, payment, customerId)
                fb.success()
                // only what was sold leaves the cart: an item scanned while saving stays
                _cart.value = CartOps.subtract(_cart.value, lines)
                _discount.value = 0
                if (viaVoice) _voiceUndo.value = VoiceUndo("ثبت فاکتور " + money(sale.total), lines, discountBefore, sale.id)
                onDone(sale)
                lowAfterSale(lines)
            } finally {
                _checkingOut.value = false
            }
        }
    }

    /** Goods that fell to their minimum with THIS sale get a notification right away (once per product per day). */
    private suspend fun lowAfterSale(lines: List<CartLine>) {
        if (!prefs.lowAlert.value) return
        val crossed = lines.map { it.product }.distinctBy { it.id }.filter { it.trackStock && it.minStock > 0 && it.stock > it.minStock }
            .mapNotNull { repo.byId(it.id) }.filter { it.low }
        if (crossed.isNotEmpty()) com.mozkhan.shop.util.Daily.lowNotice(getApplication(), crossed)
    }

    // ------------------------------------------------------------------ voice: undo the last voice action
    /** what the cart looked like before the last voice action; [saleId] = that action recorded a sale (undo voids it) */
    data class VoiceUndo(val label: String, val cart: List<CartLine>, val discount: Long, val saleId: String? = null)
    private val _voiceUndo = MutableStateFlow<VoiceUndo?>(null)
    val voiceUndo = _voiceUndo.asStateFlow()
    fun rememberVoiceUndo(label: String) { _voiceUndo.value = VoiceUndo(label, _cart.value, _discount.value) }

    /** Puts the cart back as it was (and voids the sale when the last voice action recorded one). */
    fun undoVoice(): String? {
        val u = _voiceUndo.value ?: return null
        _voiceUndo.value = null
        if (u.saleId != null) launch { repo.voidSale(u.saleId) }
        _cart.value = u.cart
        _discount.value = u.discount
        touch()
        return u.label
    }

    // ------------------------------------------------------------------ end-of-day cash count
    val closes: StateFlow<List<com.mozkhan.shop.data.CashClose>> = repo.closes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    suspend fun cashDay(day: Long) = repo.cashDay(day)
    fun saveClose(c: com.mozkhan.shop.data.CashClose, onDone: () -> Unit = {}) = launch { repo.saveClose(c); fb.success(); onDone() }
    fun deleteClose(id: String) = launch { repo.deleteClose(id) }

    // ------------------------------------------------------------------ scanning
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending = _pending.asStateFlow()
    private val _visual = MutableStateFlow<List<ImageFingerprint.Match>>(emptyList())
    val visual = _visual.asStateFlow()
    private val scans = ScanDebouncer(1500)
    @Volatile private var lastVisualHit = 0L
    @Volatile private var candidateId: String? = null
    @Volatile private var candidateHits = 0
    private val muted = ConcurrentHashMap<String, Long>() // productId -> until (ms), avoids re-popping the same item

    fun onBarcode(code: String) {
        if (_pending.value != null) return
        val now = System.currentTimeMillis()
        // a pack held in front of the camera is read many times: count it once until it leaves the view
        if (!scans.accept(code, now)) return
        touch()
        launch {
            val p = repo.byBarcode(code)
            if (p == null || p.kind == KIND_INGREDIENT) { fb.error(); _pending.value = Pending.Unknown(code) }
            else {
                fb.success()
                val box = p.hasBox && p.boxBarcode == code && p.barcode != code
                if (!p.isWeight && prefs.autoAdd) addToCart(p, 1.0, box = box)
                else _pending.value = Pending.Found(p, box = box)
            }
        }
    }

    /**
     * Live frames while visual mode is on. The confirm sheet opens automatically only when the SAME product wins
     * two frames in a row with a clear margin over every other product; weaker matches are shown as suggestions.
     */
    fun onFrame(bmp: Bitmap) {
        if (_pending.value != null) return
        val now = System.currentTimeMillis()
        if (now - scans.lastSeen < 1500) return // a barcode was just seen: no need to guess from the picture
        val printMap = prints.value
        val list = products.value.filter { it.hasPrints }
        if (list.isEmpty()) return
        val m = ImageFingerprint.match(bmp, list, printMap).filter { (muted[it.product.id] ?: 0L) < now }
        if (m.isEmpty()) {
            candidateId = null; candidateHits = 0
            if (now - lastVisualHit > 2500) _visual.value = emptyList()
            return
        }
        lastVisualHit = now
        val top = m[0]
        val second = m.getOrNull(1)
        val sure = !top.legacy && top.score >= ImageFingerprint.sureScore &&
            (second == null || second.legacy || top.score - second.score >= ImageFingerprint.margin)
        if (sure) {
            if (candidateId == top.product.id) candidateHits++ else { candidateId = top.product.id; candidateHits = 1 }
            if (candidateHits >= ImageFingerprint.sureFrames) {
                candidateId = null; candidateHits = 0
                fb.success()
                pick(top.product, viaImage = true)
                return
            }
        } else { candidateId = null; candidateHits = 0 }
        _visual.value = m
    }

    fun pick(p: ProductWithStock, viaImage: Boolean = false, box: Boolean = false) {
        fb.tick()
        touch()
        _pending.value = Pending.Found(p, viaImage, box)
        _visual.value = emptyList()
    }

    fun dismissPending() {
        (_pending.value as? Pending.Found)?.let { muted[it.p.id] = System.currentTimeMillis() + 4000 }
        _pending.value = null
        // the pack that was just handled is usually still in front of the lens: don't pop it up again at once
        scans.hold(System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ products
    fun saveProduct(p: Product, opening: Double?, openingIsPurchase: Boolean = false, onDone: () -> Unit = {}) = launch {
        repo.saveProduct(p, opening, openingIsPurchase); onDone()
    }

    fun deleteProduct(id: String) = launch { repo.deleteProduct(id) }
    fun favorite(id: String, v: Boolean) = launch { repo.toggleFavorite(id, v) }

    fun restock(
        productId: String, qty: Double, unitCost: Double, newBuy: Long, newSell: Long?, newBoxBuy: Long?, newBoxSell: Long?,
        supplier: String, chequeId: String? = null, newCheque: com.mozkhan.shop.data.Cheque? = null, onDone: () -> Unit = {},
    ) = launch {
        repo.restock(productId, qty, unitCost, newBuy, newSell, newBoxBuy, newBoxSell, supplier, chequeId, newCheque)
        fb.success(); onDone()
    }

    fun voidPurchase(docId: String) = launch { repo.voidPurchase(docId) }
    fun voidExit(docId: String) = launch { repo.voidExit(docId) }

    // ------------------------------------------------------------------ cheques given to suppliers
    val cheques: StateFlow<List<com.mozkhan.shop.data.Cheque>> = repo.cheques.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun saveCheque(c: com.mozkhan.shop.data.Cheque) = launch { repo.saveCheque(c) }
    fun setChequeStatus(id: String, status: String) = launch { repo.setChequeStatus(id, status) }
    fun deleteCheque(id: String) = launch { repo.deleteCheque(id) }

    // ------------------------------------------------------------------ buying advice (last 30 days of sales)
    val buyAdvice: StateFlow<com.mozkhan.shop.util.BuyAdvice> =
        combine(repo.itemRows(System.currentTimeMillis() - 30L * 86_400_000L, Long.MAX_VALUE), products) { rows, prods ->
            val sold = HashMap<String, Double>()
            rows.forEach { sold[it.productId] = (sold[it.productId] ?: 0.0) + it.qty }
            com.mozkhan.shop.util.BuyAdvisor.advise(prods, sold)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.mozkhan.shop.util.BuyAdvice())

    fun saveDrink(p: Product, lines: List<Pair<String, Double>>, onDone: () -> Unit = {}) = launch {
        repo.saveProduct(p)
        repo.saveRecipe(p.id, lines)
        onDone()
    }

    /**
     * Price change for chosen products (ids), or a whole category, or everything.
     * Either a percentage (percent != null) or a fixed amount in toman (positive or negative).
     * field: "sell" | "buy" | "both". Carton prices follow the same rule.
     * Every product is re-read from the database, so no other field can be lost.
     */
    fun bulkChange(ids: Set<String>?, category: String?, percent: Double?, amount: Long?, field: String) = launch {
        fun adj(v: Long, mult: Int = 1): Long = Prices.change(v, percent, amount, mult)
        products.value.filter { (ids == null || it.id in ids) && (category == null || it.category == category) }.forEach { w ->
            val p = repo.raw(w.id) ?: return@forEach
            val b = p.boxSize.coerceAtLeast(1)
            repo.saveProduct(
                p.copy(
                    sellPrice = if (field != "buy") adj(p.sellPrice) else p.sellPrice,
                    buyPrice = if (field != "sell") adj(p.buyPrice) else p.buyPrice,
                    boxSellPrice = if (field != "buy") adj(p.boxSellPrice, b) else p.boxSellPrice,
                    boxBuyPrice = if (field != "sell") adj(p.boxBuyPrice, b) else p.boxBuyPrice,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ report filters (shared by Reports & Inventory)
    val period = MutableStateFlow(Period.MONTH)
    val offset = MutableStateFlow(0)
    val customFrom = MutableStateFlow(Jalali.startOfDay(System.currentTimeMillis()) - 29 * 86_400_000L)
    val customTo = MutableStateFlow(Jalali.startOfDay(System.currentTimeMillis()))
    val category = MutableStateFlow<String?>(null)
    /** null = everything, otherwise DEPT_SHOP / DEPT_CAFE */
    val dept = MutableStateFlow<String?>(null)

    /**
     * Start of the current day. It ticks over at midnight, so "today / this week / this month" really move on in a register
     * that stays open for days (before, the report kept the range of the day the app was opened and new invoices
     * - this phone's and the synced ones - fell outside it).
     */
    private val dayStart = MutableStateFlow(Jalali.startOfDay(System.currentTimeMillis()))

    init {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                dayStart.value = Jalali.startOfDay(System.currentTimeMillis())
            }
        }
    }

    val range: StateFlow<DateRange> = combine(period, offset, customFrom, customTo, dayStart) { p, o, f, t, _ ->
        if (p == Period.CUSTOM) {
            val a = minOf(f, t)
            val b = maxOf(f, t)
            DateRange(a, b + 86_400_000L - 1, Jalali.date(a) + " تا " + Jalali.date(b))
        } else Ranges.of(p, o)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Ranges.of(Period.MONTH, 0))

    private val rowsAll: StateFlow<List<ItemRow>> = range.flatMapLatest { repo.itemRows(it.from, it.to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val salesInRange = range.flatMapLatest { repo.sales(it.from, it.to) }
    private val expensesInRange = range.flatMapLatest { repo.expenses(it.from, it.to) }
    private val movesInRange = range.flatMapLatest { repo.moves(it.from, it.to) }
    private val paidInRange = range.flatMapLatest { repo.paidInRange(it.from, it.to) }

    private val params = combine(range, period, category, dept) { r, p, c, d -> Params(r, p, c, d) }
    private data class Params(val r: DateRange, val p: Period, val cat: String?, val dept: String?)

    private val base: StateFlow<ReportData> = combine(params, rowsAll, salesInRange, expensesInRange) { pr, rows, sales, exps ->
        val rr = ReportCalc.filter(rows, pr.cat, pr.dept)
        val b = Bucketer(pr.p, pr.r.from, pr.r.to)
        val (rev, prof) = ReportCalc.series(rr, b)
        val ids = rr.map { it.saleId }.toHashSet()
        val unfiltered = pr.cat == null && pr.dept == null
        ReportData(
            range = pr.r, period = pr.p, category = pr.cat, dept = pr.dept,
            agg = ReportCalc.totals(rr),
            expenses = if (unfiltered) exps.sumOf { it.amount } else 0,
            expenseList = if (unfiltered) exps else emptyList(),
            top = ReportCalc.top(rr, 8),
            byCat = ReportCalc.byCategory(ReportCalc.filter(rows, null, pr.dept)),
            labels = b.labels, rev = rev, profit = prof,
            sales = if (unfiltered) sales else sales.filter { it.id in ids },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportData())

    /** purchases, losses (exits) and cash movements of the range */
    private data class Extras(val purchases: Long, val estimated: Boolean, val rows: List<PurchaseRow>, val exitCost: Long, val cashIn: Long)

    private val extras = combine(movesInRange, repo.docs, allProducts, params, paidInRange) { moves, docs, prods, pr, paid ->
        val byId = prods.associateBy { it.id }
        val docMap = docs.associateBy { it.id }
        var estimated = false
        val rows = moves.filter { it.reason == "PURCHASE" && docMap[it.refId]?.voided != true }.mapNotNull { m ->
            val p = byId[m.productId] ?: return@mapNotNull null
            if (pr.cat != null && p.category != pr.cat) return@mapNotNull null
            val est = m.unitCost <= 0.0
            if (est) estimated = true
            val cost = Math.round(m.qty * (if (est) p.baseCost else m.unitCost))
            PurchaseRow(m.refId ?: m.id, m.ts, p.name, m.qty, p.unit, cost, docMap[m.refId]?.note.orEmpty(), est)
        }.sortedByDescending { it.ts }
        // only exits marked "count the value" lower the profit; others just move goods
        val exit = docs.filter { it.kind != "PURCHASE" && !it.voided && it.counted && it.ts in pr.r.from..pr.r.to }.sumOf { it.cost }
        Extras(rows.sumOf { it.cost }, estimated, rows, exit, paid)
    }

    val report: StateFlow<ReportData> = combine(base, extras) { b, x ->
        val cash = b.sales.filter { !it.voided && it.payment != "CREDIT" }.sumOf { it.total }
        b.copy(
            purchases = x.purchases, purchasesEstimated = x.estimated, purchaseRows = x.rows,
            exitCost = if (b.category == null && b.dept == null) x.exitCost else 0,
            cashIn = cash + x.cashIn,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportData())

    // ------------------------------------------------------------------ inventory report (shop goods)
    val inventory: StateFlow<InvData> = combine(products, movesInRange, rowsAll, params) { prods, moves, rows, pr ->
        buildInventory(prods, moves, rows, pr.r, pr.p, pr.cat)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InvData())

    private fun buildInventory(prods: List<ProductWithStock>, moves: List<StockMove>, rows: List<ItemRow>, r: DateRange, p: Period, cat: String?): InvData {
        val tracked = prods.filter { it.trackStock && (cat == null || it.category == cat) }
        val byId = tracked.associateBy { it.id }
        val b = Bucketer(p, r.from, r.to)
        val ins = DoubleArray(b.n)
        val outs = DoubleArray(b.n)
        var purchased = 0.0; var sold = 0.0; var exited = 0.0
        for (m in moves) {
            val pr = byId[m.productId] ?: continue
            val v = Math.abs(m.qty) * (if (m.unitCost > 0) m.unitCost else pr.baseCost)
            val i = b.index(m.ts)
            when {
                // a voided invoice gives its goods back: it cancels the sale instead of counting as a new arrival
                m.reason == "RETURN" -> { sold -= v; outs[i] -= v }
                // the reversal of a voided purchase (ADJUST pointing at the purchase document) cancels that purchase
                m.reason == "ADJUST" && m.refId != null && m.qty < 0 -> { purchased -= v; ins[i] -= v }
                // the goods of a voided exit document come back: they cancel that exit
                m.reason == "EXIT" && m.qty > 0 -> { exited -= v; outs[i] -= v }
                m.qty > 0 -> { ins[i] += v; if (m.reason == "PURCHASE") purchased += v }
                else -> {
                    outs[i] += v
                    if (m.reason == "SALE" || m.reason == "CONSUME") sold += v else if (m.reason == "EXIT") exited += v
                }
            }
        }
        val soldIds = rows.map { it.productId }.toHashSet()
        // sales speed of the elapsed part of the range drives the purchase suggestion of low items
        val soldQty = HashMap<String, Double>()
        rows.forEach { soldQty[it.productId] = (soldQty[it.productId] ?: 0.0) + it.qty }
        val elapsedDays = ((minOf(r.to, System.currentTimeMillis()) - r.from) / 86_400_000.0).coerceAtLeast(1.0)
        val lowList = tracked.filter { it.low || (it.stock <= 0) }.sortedBy { it.stock }.take(30)
        val reorder = lowList.associate { it.id to ReportCalc.reorderQty(it.stock, it.minStock, soldQty[it.id] ?: 0.0, elapsedDays) }
            .filterValues { it > 0 }
        return InvData(
            stockValue = tracked.sumOf { it.stockValue }, sellValue = tracked.sumOf { it.stockSellValue },
            productCount = tracked.size, lowCount = tracked.count { it.low }, outCount = tracked.count { it.stock <= 0 },
            purchased = Math.round(purchased.coerceAtLeast(0.0)), soldOut = Math.round(sold.coerceAtLeast(0.0)), exited = Math.round(exited.coerceAtLeast(0.0)),
            labels = b.labels, inSeries = ins.map { Math.round(it.coerceAtLeast(0.0)) }, outSeries = outs.map { Math.round(it.coerceAtLeast(0.0)) },
            byCat = tracked.groupBy { it.category }.map { (c, l) -> c to l.sumOf { it.stockValue } }.sortedByDescending { it.second },
            low = lowList, reorder = reorder,
            dead = tracked.filter { it.stock > 0 && it.id !in soldIds }.sortedByDescending { it.stockValue }.take(30),
            list = tracked.sortedByDescending { it.stockValue },
        )
    }

    // ------------------------------------------------------------------ stock exit documents
    val docs: StateFlow<List<StockDoc>> = repo.docs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createExit(
        kind: String, note: String, lines: List<Pair<ProductWithStock, Double>>, counted: Boolean, basis: String = "BUY", manual: Long = 0,
        onDone: () -> Unit,
    ) = launch {
        repo.createExit(kind, note, lines, counted, basis, manual); fb.success(); onDone()
    }

    // ------------------------------------------------------------------ credit
    val customers = repo.customers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debtors: StateFlow<List<CustomerDebt>> =
        combine(repo.customers, repo.creditSums, repo.paidSums) { cs, credit, paid ->
            val cm = credit.associateBy { it.customerId }
            val pm = paid.associateBy { it.customerId }
            cs.map { c ->
                CustomerDebt(c, cm[c.id]?.credit ?: 0, pm[c.id]?.paid ?: 0, maxOf(cm[c.id]?.last ?: 0, pm[c.id]?.last ?: 0))
            }.sortedWith(compareByDescending<CustomerDebt> { it.debt > 0 }.thenByDescending { it.debt }.thenBy { it.customer.name })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCustomer(name: String, phone: String, onDone: (Customer) -> Unit = {}) = launch { onDone(repo.addCustomer(name, phone)) }
    fun deleteCustomer(c: Customer) = launch { repo.deleteCustomer(c) }
    fun addPayment(customerId: String, amount: Long, note: String) = launch { repo.addPayment(customerId, amount, note) }
    fun deletePayment(p: CreditPayment) = launch { repo.deletePayment(p.id) }
    fun payments(customerId: String) = repo.payments(customerId)
    fun creditSales(customerId: String) = repo.creditSales(customerId)

    // ------------------------------------------------------------------ home
    private val today = dayStart
    val todayTotals: StateFlow<Totals> = today.flatMapLatest { repo.totals(it, it + 86_400_000L - 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Totals(0, 0, 0))
    val todayExpenses: StateFlow<Long> = today.flatMapLatest { repo.expenseSum(it, it + 86_400_000L - 1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val todayTop: StateFlow<List<TopProduct>> = today.flatMapLatest { repo.top(it, it + 86_400_000L - 1, 5) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: StateFlow<List<Sale>> = repo.recentSales(6)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refreshToday() { dayStart.value = Jalali.startOfDay(System.currentTimeMillis()) }

    fun voidSale(id: String) = launch { repo.voidSale(id) }
    fun addExpense(title: String, amount: Long) = launch { repo.addExpense(title, amount) }
    fun deleteExpense(e: Expense) = launch { repo.deleteExpense(e) }

    fun money(v: Long) = Fmt.money(v, currency.value)
}
