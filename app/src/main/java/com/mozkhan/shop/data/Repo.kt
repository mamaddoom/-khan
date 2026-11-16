package com.mozkhan.shop.data

import androidx.room.withTransaction
import com.mozkhan.shop.sync.CloudSync
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Product-code helpers: codes are stored with Latin digits, trimmed, compared case-insensitively. */
object Codes {
    fun normalize(raw: String): String = Fmt.normalize(raw).trim().replace(" ", "").lowercase()
    fun numericPart(code: String): Int? = code.takeLastWhile { it.isDigit() }.toIntOrNull()
    fun next(codes: Collection<String>): Int = maxOf(1000, codes.mapNotNull { numericPart(it) }.maxOrNull() ?: 1000) + 1
}

/** Repairs data written by older versions or by other phones: blank and colliding product codes. */
object LegacyUpgrade {
    /**
     * Pure planning step: product id -> new code for every live product whose code is blank or collides with
     * another live product. The winner of a collision is always the smallest id, so every phone picks the same
     * loser; new numbers start after the highest numeric code ever used (deleted rows included), so a renumbered
     * product can never collide again.
     */
    fun planCodes(rows: List<CodeRow>): LinkedHashMap<String, String> {
        var next = Codes.next(rows.map { it.code })
        val out = LinkedHashMap<String, String>()
        val live = rows.filter { !it.deleted }
        for (r in live.filter { it.code.isBlank() }.sortedBy { it.id }) out[r.id] = (next++).toString()
        val seen = HashSet<String>()
        for (r in live.filter { it.code.isNotBlank() }.sortedBy { it.id }) {
            if (!seen.add(Codes.normalize(r.code))) out[r.id] = (next++).toString()
        }
        return out
    }

    /** Returns ids whose code changed (the caller re-uploads them). Deterministic, so every phone converges. */
    suspend fun ensureCodes(db: AppDb): List<String> {
        val plan = planCodes(db.products().allCodes())
        if (plan.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        db.withTransaction { plan.forEach { (id, code) -> db.products().setCode(id, code, now) } }
        return plan.keys.toList()
    }
}

/**
 * Pure cart operations (the ViewModel only holds the list). Keeping them here makes them unit-testable and
 * guarantees one line per key, which the LazyColumn of the cart relies on (duplicate keys crash it).
 */
object CartOps {
    fun add(cart: List<CartLine>, line: CartLine): List<CartLine> {
        val i = cart.indexOfFirst { it.key == line.key }
        return if (i >= 0) cart.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + line.qty) } else cart + line
    }

    fun setQty(cart: List<CartLine>, key: String, qty: Double): List<CartLine> =
        if (qty <= 0 || qty.isNaN()) cart.filterNot { it.key == key } else cart.map { if (it.key == key) it.copy(qty = qty) else it }

    /** New price for one line; when another line already has that product/price, the two are merged. */
    fun setPrice(cart: List<CartLine>, key: String, price: Long): List<CartLine> {
        val line = cart.firstOrNull { it.key == key } ?: return cart
        val changed = line.copy(price = price.coerceAtLeast(0))
        if (changed.key == key) return cart.map { if (it.key == key) changed else it }
        val rest = cart.filterNot { it.key == key }
        val j = rest.indexOfFirst { it.key == changed.key }
        return if (j >= 0) rest.toMutableList().also { it[j] = it[j].copy(qty = it[j].qty + changed.qty) }
        else cart.map { if (it.key == key) changed else it }
    }

    /** What is left of [cart] after the lines in [sold] were checked out (items scanned meanwhile are kept). */
    fun subtract(cart: List<CartLine>, sold: List<CartLine>): List<CartLine> {
        val soldQty = HashMap<String, Double>()
        sold.forEach { soldQty[it.key] = (soldQty[it.key] ?: 0.0) + it.qty }
        return cart.mapNotNull { l ->
            val s = soldQty[l.key] ?: return@mapNotNull l
            val left = l.qty - s
            if (left > 1e-9) l.copy(qty = left) else null
        }
    }
}

/** Merging of rows coming from a backup / another phone: newest `updatedAt` wins, ties keep the local row. */
object Merge {
    fun <T> newer(incoming: List<T>, local: Map<String, T>, id: (T) -> String, updatedAt: (T) -> Long): List<T> =
        incoming.filter { inc -> local[id(inc)]?.let { updatedAt(it) < updatedAt(inc) } ?: true }
}

/**
 * One line of the invoice being built.
 * [price] is per sold unit: per piece, per CARTON when [box], or per KG for weighed products.
 * [qty] counts sold units: pieces / cartons / GRAMS.
 * [costOverride] = cost per piece of a drink computed from its recipe (the repository recomputes it authoritatively).
 */
data class CartLine(val product: ProductWithStock, val qty: Double, val price: Long, val box: Boolean = false, val costOverride: Double? = null) {
    val mult: Int get() = if (box && !product.isWeight) product.boxSize.coerceAtLeast(1) else 1
    /** pieces (or grams) that leave the stock */
    val pieces: Double get() = qty * mult
    val unitPrice: Double get() = if (product.isWeight) price / 1000.0 else price.toDouble() / mult
    val unitCost: Double
        get() = when {
            costOverride != null -> costOverride
            product.isWeight -> product.buyPrice / 1000.0
            box && product.boxBuyPrice > 0 -> product.boxBuyPrice.toDouble() / mult
            else -> product.buyPrice.toDouble()
        }
    val total: Long get() = Math.round(pieces * unitPrice)
    val cost: Long get() = Math.round(pieces * unitCost)
    val key: String get() = product.id + "@" + price + if (box) "#box" else ""
}

class Repo(val db: AppDb, val prefs: Prefs, val sync: CloudSync) {

    // ------------------------------------------------------------ products
    val products: Flow<List<ProductWithStock>> = db.products().observeAll()
    val categories: Flow<List<String>> = db.products().observeCategories()
    val prints: Flow<List<PrintRow>> = db.products().observePrints()
    val recipeItems: Flow<List<RecipeItem>> = db.recipes().observeAll()

    suspend fun byBarcode(code: String) = db.products().findByBarcode(Fmt.normalize(code).trim())
    suspend fun byId(id: String) = db.products().findById(id)
    suspend fun raw(id: String) = db.products().raw(id)

    suspend fun nextCode(): String = Codes.next(db.products().allCodes().map { it.code }).toString()

    /** true when another (not deleted) product already uses this code */
    suspend fun codeTaken(code: String, exceptId: String?): Boolean {
        val c = Codes.normalize(code)
        return db.products().allCodes().any { !it.deleted && it.id != exceptId && Codes.normalize(it.code) == c }
    }

    suspend fun ensureCodes() {
        LegacyUpgrade.ensureCodes(db).forEach { id -> db.products().raw(id)?.let { sync.push(it) } }
    }

    /**
     * true when the product already has stock movements or sold lines. Such a product must not change its
     * Measure (piece <-> gram/ml): its stock and sales are stored in base units and would be silently rescaled.
     */
    suspend fun hasHistory(productId: String): Boolean =
        db.stock().countMoves(productId) > 0 || db.sales().countItemsOf(productId) > 0

    /**
     * Saves a product. [openingStock] (base units) is recorded as OPENING stock, or, when [openingIsPurchase],
     * as a new purchase (document + PURCHASE move) so it appears in the purchases report.
     */
    suspend fun saveProduct(p: Product, openingStock: Double? = null, openingIsPurchase: Boolean = false): Product {
        val saved = p.copy(updatedAt = System.currentTimeMillis(), barcode = p.barcode?.let { Codes.normalize(it).ifEmpty { null } })
        var move: StockMove? = null
        var doc: StockDoc? = null
        db.withTransaction {
            db.products().upsert(saved)
            if (saved.trackStock && openingStock != null && openingStock != 0.0) {
                val unitCost = if (saved.unit == Measure.PIECE) saved.buyPrice.toDouble() else saved.buyPrice / 1000.0
                if (openingStock > 0 && openingIsPurchase) {
                    val d = StockDoc(newId(), kind = "PURCHASE", note = "", cost = Math.round(openingStock * unitCost), updatedAt = System.currentTimeMillis())
                    doc = d
                    db.stock().insertDoc(d)
                    move = StockMove(newId(), saved.id, openingStock, "PURCHASE", d.id, unitCost = unitCost)
                } else {
                    move = StockMove(newId(), saved.id, openingStock, if (openingStock > 0) "OPENING" else "ADJUST", unitCost = unitCost)
                }
                db.stock().insert(move!!)
            }
        }
        doc?.let { sync.push(it) }
        move?.let { sync.push(it) }
        sync.push(saved)
        return saved
    }

    suspend fun deleteProduct(id: String) {
        db.products().softDelete(id, System.currentTimeMillis())
        db.products().raw(id)?.let { sync.push(it) }
    }

    suspend fun toggleFavorite(id: String, fav: Boolean) {
        db.products().setFavorite(id, fav, System.currentTimeMillis())
        db.products().raw(id)?.let { sync.push(it) }
    }

    suspend fun adjustStock(productId: String, delta: Double, reason: String) {
        if (delta == 0.0) return
        val p = db.products().raw(productId)
        val m = StockMove(newId(), productId, delta, reason, unitCost = p?.let { if (it.unit == Measure.PIECE) it.buyPrice.toDouble() else it.buyPrice / 1000.0 } ?: 0.0)
        db.stock().insert(m)
        sync.push(m)
    }

    /**
     * New purchase of an existing product: prices are updated from the stored row (so no field is ever lost),
     * and a PURCHASE document + move are written with the cost actually paid.
     * [qty] is in base units, [unitCost] the price of ONE base unit actually paid (box price / pieces for a carton purchase).
     */
    suspend fun restock(
        productId: String, qty: Double, unitCost: Double,
        newBuyPrice: Long, newSellPrice: Long?, newBoxBuy: Long?, newBoxSell: Long?, supplier: String,
    ): StockDoc {
        require(qty > 0) { "مقدار خرید باید بیشتر از صفر باشد" }
        val now = System.currentTimeMillis()
        val old = db.products().raw(productId) ?: error("کالا پیدا نشد")
        val updated = old.copy(
            buyPrice = newBuyPrice,
            sellPrice = newSellPrice ?: old.sellPrice,
            boxBuyPrice = newBoxBuy ?: old.boxBuyPrice,
            boxSellPrice = newBoxSell ?: old.boxSellPrice,
            updatedAt = now,
        )
        val doc = StockDoc(newId(), now, "PURCHASE", supplier.trim(), Math.round(qty * unitCost), updatedAt = now)
        val move = StockMove(newId(), productId, qty, "PURCHASE", doc.id, now, unitCost)
        db.withTransaction {
            db.products().upsert(updated)
            db.stock().insertDoc(doc)
            db.stock().insert(move)
        }
        sync.push(updated); sync.push(doc); sync.push(move)
        return doc
    }

    /** Cancels a purchase document: reversing ADJUST moves are written and the document is flagged. */
    suspend fun voidPurchase(docId: String) {
        val d = db.stock().doc(docId) ?: return
        if (d.voided || d.kind != "PURCHASE") return
        val now = maxOf(System.currentTimeMillis(), d.updatedAt + 1)
        val rev = db.stock().movesOf(docId).filter { it.reason == "PURCHASE" }.map { StockMove(newId(), it.productId, -it.qty, "ADJUST", docId, now, it.unitCost) }
        db.withTransaction {
            db.stock().voidDoc(docId, now)
            db.stock().insertAll(rev)
        }
        sync.push(d.copy(voided = true, updatedAt = now)); rev.forEach { sync.push(it) }
    }

    // ------------------------------------------------------------ cafe recipes
    /** Replaces the recipe of a drink with [lines] = (ingredientId, qty per drink in base units). */
    suspend fun saveRecipe(drinkId: String, lines: List<Pair<String, Double>>) {
        val now = System.currentTimeMillis()
        val old = db.recipes().linesFor(drinkId)
        val fresh = lines.filter { it.second > 0 }.map { RecipeItem(newId(), drinkId, it.first, it.second, false, now) }
        db.withTransaction {
            db.recipes().clearFor(drinkId, now)
            db.recipes().upsertAll(fresh)
        }
        old.forEach { sync.push(it.copy(deleted = true, updatedAt = now)) }
        fresh.forEach { sync.push(it) }
    }

    suspend fun recipeOf(drinkId: String) = db.recipes().linesFor(drinkId)

    // ------------------------------------------------------------ selling
    suspend fun checkout(lines: List<CartLine>, discount: Long, payment: String, customerId: String? = null, note: String = ""): Sale {
        require(lines.isNotEmpty()) { "سبد خالی است" }
        require(payment != "CREDIT" || customerId != null) { "برای فروش نسیه مشتری را انتخاب کنید" }
        val now = System.currentTimeMillis()
        val id = newId()

        // recipe cost is recomputed here from the database (authoritative), never trusted from the UI
        class Used(val ingredient: Product, val qty: Double)
        val drinkCost = HashMap<String, Double>()
        val drinkUse = HashMap<String, List<Used>>()
        for (l in lines.filter { it.product.kind == KIND_DRINK }) {
            if (l.product.id in drinkCost) continue
            val rec = db.recipes().linesFor(l.product.id)
            if (rec.isEmpty()) continue
            val used = rec.mapNotNull { r -> db.products().raw(r.ingredientId)?.let { Used(it, r.qty) } }
            drinkUse[l.product.id] = used
            drinkCost[l.product.id] = used.sumOf { it.qty * (if (it.ingredient.unit == Measure.PIECE) it.ingredient.buyPrice.toDouble() else it.ingredient.buyPrice / 1000.0) }
        }

        val items = lines.map {
            SaleItem(
                newId(), id, it.product.id,
                it.product.name + if (it.box) " (باکس)" else "",
                it.pieces, it.product.isWeight, it.unitPrice, drinkCost[it.product.id] ?: it.unitCost,
                it.product.category, if (isCafeProduct(it.product.kind, it.product.category)) DEPT_CAFE else DEPT_SHOP,
            )
        }
        val subtotal = items.sumOf { it.lineTotal }
        val disc = discount.coerceIn(0, subtotal)
        val sale = Sale(
            id, now, subtotal, disc, subtotal - disc, items.sumOf { it.lineCost }, payment, note,
            false, now, if (payment == "CREDIT") customerId else null, prefs.deviceName,
        )
        val moves = ArrayList<StockMove>()
        lines.forEachIndexed { i, l ->
            if (l.product.trackStock && l.product.kind != KIND_DRINK) moves += StockMove(newId(), l.product.id, -items[i].qty, "SALE", id, now, items[i].unitCost)
            drinkUse[l.product.id]?.forEach { u ->
                // a deleted ingredient still counts in the drink's cost (it was really used) but is no longer stocked
                if (u.ingredient.trackStock && !u.ingredient.deleted) {
                    val base = if (u.ingredient.unit == Measure.PIECE) u.ingredient.buyPrice.toDouble() else u.ingredient.buyPrice / 1000.0
                    moves += StockMove(newId(), u.ingredient.id, -(u.qty * l.pieces), "CONSUME", id, now, base)
                }
            }
        }
        db.withTransaction {
            db.sales().insertSale(sale)
            db.sales().insertItems(items)
            db.stock().insertAll(moves)
        }
        // invoice, lines and stock moves go up together (one atomic batch): the other phone never sees half an invoice
        sync.push(sale, items, moves)
        return sale
    }

    /** Cancels an invoice by negating every stock move it made (goods, drinks and their ingredients alike). */
    suspend fun voidSale(id: String) {
        val s = db.sales().sale(id) ?: return
        if (s.voided) return
        // strictly newer than the stored row even when this phone's clock is behind the phone that made the invoice
        val now = maxOf(System.currentTimeMillis(), s.updatedAt + 1)
        val moves = db.stock().movesOf(id).filter { it.reason == "SALE" || it.reason == "CONSUME" }
            .map { StockMove(newId(), it.productId, -it.qty, "RETURN", id, now, it.unitCost) }
        db.withTransaction {
            db.sales().markVoided(id, now)
            db.stock().insertAll(moves)
        }
        sync.push(s.copy(voided = true, updatedAt = now), emptyList(), moves)
    }

    suspend fun itemsOf(saleId: String) = db.sales().itemsOf(saleId)
    fun observeItemsOf(saleId: String) = db.sales().observeItemsOf(saleId)
    fun recentSales(n: Int) = db.sales().observeRecent(n)
    fun sales(from: Long, to: Long) = db.sales().observeSales(from, to)
    fun totals(from: Long, to: Long) = db.sales().observeTotals(from, to)
    fun top(from: Long, to: Long, n: Int = 10) = db.sales().observeTop(from, to, n)
    fun itemRows(from: Long, to: Long) = db.sales().observeItemRows(from, to)
    fun moves(from: Long, to: Long) = db.stock().observeInRange(from, to)
    fun paidInRange(from: Long, to: Long) = db.credit().observePaidInRange(from, to)

    // ------------------------------------------------------------ stock exit without sale
    val docs = db.stock().observeDocs()

    /** Takes goods out of the warehouse without a sale. qty = base units. Counted as a loss, never as revenue. */
    suspend fun createExit(kind: String, note: String, lines: List<Pair<ProductWithStock, Double>>): StockDoc {
        require(lines.isNotEmpty()) { "کالایی انتخاب نشده" }
        val now = System.currentTimeMillis()
        val docId = newId()
        val cost = lines.sumOf { (p, q) -> Math.round(q * p.baseCost) }
        val doc = StockDoc(docId, now, kind, note.trim(), cost, updatedAt = now)
        val moves = lines.filter { it.first.trackStock && it.second > 0 }
            .map { (p, q) -> StockMove(newId(), p.id, -q, "EXIT", docId, now, p.baseCost) }
        db.withTransaction {
            db.stock().insertDoc(doc)
            db.stock().insertAll(moves)
        }
        sync.push(doc)
        moves.forEach { sync.push(it) }
        return doc
    }

    suspend fun exitLines(docId: String): List<Pair<String, Double>> =
        db.stock().movesOf(docId).filter { it.reason == "EXIT" }.map { m -> (db.products().raw(m.productId)?.name ?: "؟") to -m.qty }

    // ------------------------------------------------------------ expenses
    fun expenses(from: Long, to: Long) = db.expenses().observe(from, to)
    fun expenseSum(from: Long, to: Long) = db.expenses().observeSum(from, to)

    suspend fun addExpense(title: String, amount: Long) {
        val e = Expense(newId(), title.trim(), amount)
        db.expenses().upsert(e)
        sync.push(e)
    }

    suspend fun deleteExpense(e: Expense) {
        val ts = System.currentTimeMillis()
        db.expenses().softDelete(e.id, ts)
        sync.push(e.copy(deleted = true, updatedAt = ts))
    }

    // ------------------------------------------------------------ credit (نسیه)
    val customers = db.credit().observeCustomers()
    val creditSums = db.credit().observeCreditSums()
    val paidSums = db.credit().observePaidSums()
    fun payments(customerId: String) = db.credit().observePayments(customerId)
    fun creditSales(customerId: String) = db.sales().observeCreditSales(customerId)

    suspend fun addCustomer(name: String, phone: String = ""): Customer {
        val c = Customer(newId(), name.trim(), phone.trim())
        db.credit().upsertCustomer(c)
        sync.push(c)
        return c
    }

    suspend fun deleteCustomer(c: Customer) {
        val d = c.copy(deleted = true, updatedAt = System.currentTimeMillis())
        db.credit().upsertCustomer(d)
        sync.push(d)
    }

    suspend fun addPayment(customerId: String, amount: Long, note: String = "") {
        val p = CreditPayment(newId(), customerId, amount, note = note.trim())
        db.credit().upsertPayment(p)
        sync.push(p)
    }

    suspend fun deletePayment(id: String) {
        db.credit().softDeletePayment(id, System.currentTimeMillis())
        db.credit().payment(id)?.let { sync.push(it) }
    }

    // ------------------------------------------------------------ backup (JSON, versioned, tolerant)
    /** Builds the backup off the main thread (a large shop has tens of thousands of rows). */
    suspend fun exportJson(): String = withContext(Dispatchers.Default) {
        val o = JSONObject()
        o.put("version", BACKUP_VERSION)
        o.put("exportedAt", System.currentTimeMillis())
        o.put("shopCode", prefs.shopCode) // keeps the cloud link recoverable: restoring this file on a new phone brings the code back
        o.put("products", JSONArray().apply {
            db.products().allRaw().forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("barcode", it.barcode ?: JSONObject.NULL)
                    .put("category", it.category).put("unit", it.unit.name).put("buyPrice", it.buyPrice)
                    .put("sellPrice", it.sellPrice).put("minStock", it.minStock).put("fingerprints", it.fingerprints)
                    .put("favorite", it.favorite).put("deleted", it.deleted).put("updatedAt", it.updatedAt)
                    .put("boxBarcode", it.boxBarcode ?: JSONObject.NULL).put("boxSize", it.boxSize)
                    .put("boxBuyPrice", it.boxBuyPrice).put("boxSellPrice", it.boxSellPrice).put("trackStock", it.trackStock)
                    .put("code", it.code).put("kind", it.kind))
            }
        })
        o.put("moves", JSONArray().apply {
            db.stock().all().forEach {
                put(JSONObject().put("id", it.id).put("productId", it.productId).put("qty", it.qty)
                    .put("reason", it.reason).put("refId", it.refId ?: JSONObject.NULL).put("ts", it.ts).put("unitCost", it.unitCost))
            }
        })
        o.put("docs", JSONArray().apply {
            db.stock().allDocs().forEach {
                put(JSONObject().put("id", it.id).put("ts", it.ts).put("kind", it.kind).put("note", it.note).put("cost", it.cost)
                    .put("voided", it.voided).put("updatedAt", it.updatedAt))
            }
        })
        o.put("sales", JSONArray().apply {
            db.sales().allSales().forEach {
                put(JSONObject().put("id", it.id).put("ts", it.ts).put("subtotal", it.subtotal).put("discount", it.discount)
                    .put("total", it.total).put("cost", it.cost).put("payment", it.payment).put("note", it.note)
                    .put("voided", it.voided).put("updatedAt", it.updatedAt).put("customerId", it.customerId ?: JSONObject.NULL).put("deviceName", it.deviceName))
            }
        })
        o.put("items", JSONArray().apply {
            db.sales().allItems().forEach {
                put(JSONObject().put("id", it.id).put("saleId", it.saleId).put("productId", it.productId)
                    .put("name", it.name).put("qty", it.qty).put("isWeight", it.isWeight)
                    .put("unitPrice", it.unitPrice).put("unitCost", it.unitCost).put("category", it.category).put("dept", it.dept))
            }
        })
        o.put("expenses", JSONArray().apply {
            db.expenses().all().forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("amount", it.amount).put("ts", it.ts)
                    .put("deleted", it.deleted).put("updatedAt", it.updatedAt))
            }
        })
        o.put("customers", JSONArray().apply {
            db.credit().allCustomers().forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone)
                    .put("deleted", it.deleted).put("updatedAt", it.updatedAt))
            }
        })
        o.put("payments", JSONArray().apply {
            db.credit().allPayments().forEach {
                put(JSONObject().put("id", it.id).put("customerId", it.customerId).put("amount", it.amount).put("ts", it.ts)
                    .put("note", it.note).put("deleted", it.deleted).put("updatedAt", it.updatedAt))
            }
        })
        o.put("recipes", JSONArray().apply {
            db.recipes().all().forEach {
                put(JSONObject().put("id", it.id).put("drinkId", it.drinkId).put("ingredientId", it.ingredientId)
                    .put("qty", it.qty).put("deleted", it.deleted).put("updatedAt", it.updatedAt))
            }
        })
        o.toString()
    }

    /**
     * Merges a backup into the database. Accepts files of every older version; refuses newer ones.
     * Mutable rows (products, sales, documents, expenses, customers, payments, recipes) are only replaced when the
     * backup's copy is NEWER than the local one, so restoring an old file can never un-void a sale or roll back
     * a price. Append-only rows (stock moves, sale lines) are simply added.
     */
    suspend fun importJson(text: String) {
        val b = withContext(Dispatchers.Default) { BackupParser.parse(text) }
        // a phone without a shop code gets the one stored in the backup (an existing code is never replaced)
        runCatching { JSONObject(text).optString("shopCode", "") }.getOrDefault("").takeIf { it.length >= 8 && prefs.shopCode.isBlank() }?.let { prefs.shopCode = it }
        db.withTransaction {
            val p = Merge.newer(b.products, db.products().allRaw().associateBy { it.id }, { it.id }, { it.updatedAt })
            val d = Merge.newer(b.docs, db.stock().allDocs().associateBy { it.id }, { it.id }, { it.updatedAt })
            val s = Merge.newer(b.sales, db.sales().allSales().associateBy { it.id }, { it.id }, { it.updatedAt })
            val e = Merge.newer(b.expenses, db.expenses().all().associateBy { it.id }, { it.id }, { it.updatedAt })
            val c = Merge.newer(b.customers, db.credit().allCustomers().associateBy { it.id }, { it.id }, { it.updatedAt })
            val pay = Merge.newer(b.payments, db.credit().allPayments().associateBy { it.id }, { it.id }, { it.updatedAt })
            val r = Merge.newer(b.recipes, db.recipes().all().associateBy { it.id }, { it.id }, { it.updatedAt })
            db.products().upsertAll(p)
            db.stock().insertAll(b.moves)
            db.stock().insertDocs(d)
            s.forEach { db.sales().insertSale(it) }
            db.sales().insertItems(b.items)
            db.expenses().upsertAll(e)
            db.credit().upsertCustomers(c)
            db.credit().upsertPayments(pay)
            db.recipes().upsertAll(r)
        }
        // old files have no codes / kinds: repair, then re-upload what changed
        ensureCodes()
        // restored rows exist only on this phone: upload what the cloud lacks (no-op while sync is off)
        sync.syncNow()
    }

    companion object { const val BACKUP_VERSION = 3 }
}

/** Parsed content of a backup file (pure: no database, unit-testable). */
class Backup(
    val products: List<Product>, val moves: List<StockMove>, val docs: List<StockDoc>, val sales: List<Sale>,
    val items: List<SaleItem>, val expenses: List<Expense>, val customers: List<Customer>,
    val payments: List<CreditPayment>, val recipes: List<RecipeItem>,
)

object BackupParser {
    const val NEWER_FILE = "این فایل با نسخهٔ جدیدتری از برنامه ساخته شده؛ لطفاً برنامه را به‌روزرسانی کنید"

    /**
     * Missing timestamps never fall back to 1970: `ts` defaults to the row's `updatedAt`, then to the moment the
     * backup was exported, then to now; a missing `updatedAt` defaults to `ts`.
     */
    fun parse(text: String, now: Long = System.currentTimeMillis()): Backup {
        val o = JSONObject(text)
        val v = o.optInt("version", 1)
        require(v <= Repo.BACKUP_VERSION) { NEWER_FILE }
        val exported = o.optLong("exportedAt", 0L).takeIf { it > 0 } ?: now
        fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
        fun JSONObject.pos(k: String): Long? = optLong(k, 0L).takeIf { it > 0 }
        fun JSONObject.ts(): Long = pos("ts") ?: pos("updatedAt") ?: exported
        fun JSONObject.upd(): Long = pos("updatedAt") ?: ts()
        fun arr(k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

        val products = arr("products").map {
            Product(it.getString("id"), it.getString("name"), it.str("barcode"), it.optString("category", "سایر"),
                runCatching { Measure.valueOf(it.getString("unit")) }.getOrDefault(Measure.PIECE),
                it.optLong("buyPrice"), it.optLong("sellPrice"), it.optDouble("minStock", 0.0),
                it.optString("fingerprints", ""), it.optBoolean("favorite"), it.optBoolean("deleted"), it.pos("updatedAt") ?: exported,
                it.str("boxBarcode"), it.optInt("boxSize", 0), it.optLong("boxBuyPrice", 0), it.optLong("boxSellPrice", 0),
                it.optBoolean("trackStock", true), it.optString("code", ""), it.optString("kind", KIND_GOODS))
        }
        val moves = arr("moves").map {
            StockMove(it.getString("id"), it.getString("productId"), it.getDouble("qty"), it.getString("reason"), it.str("refId"), it.ts(), it.optDouble("unitCost", 0.0))
        }
        val docs = arr("docs").map {
            StockDoc(it.getString("id"), it.ts(), it.getString("kind"), it.optString("note", ""), it.optLong("cost"), it.optBoolean("voided"), it.upd())
        }
        val sales = arr("sales").map {
            Sale(it.getString("id"), it.ts(), it.optLong("subtotal"), it.optLong("discount"), it.optLong("total"),
                it.optLong("cost"), it.optString("payment", "CASH"), it.optString("note", ""), it.optBoolean("voided"), it.upd(),
                it.str("customerId"), it.optString("deviceName", ""))
        }
        val items = arr("items").map {
            SaleItem(it.getString("id"), it.getString("saleId"), it.getString("productId"), it.optString("name", ""),
                it.getDouble("qty"), it.optBoolean("isWeight"), it.optDouble("unitPrice", 0.0), it.optDouble("unitCost", 0.0),
                it.optString("category", ""), it.optString("dept", DEPT_SHOP))
        }
        val expenses = arr("expenses").map {
            Expense(it.getString("id"), it.optString("title", ""), it.optLong("amount"), it.ts(), it.optBoolean("deleted"), it.upd())
        }
        val customers = arr("customers").map { Customer(it.getString("id"), it.optString("name", ""), it.optString("phone", ""), it.optBoolean("deleted"), it.pos("updatedAt") ?: exported) }
        val payments = arr("payments").map {
            CreditPayment(it.getString("id"), it.getString("customerId"), it.optLong("amount"), it.ts(), it.optString("note", ""), it.optBoolean("deleted"), it.upd())
        }
        val recipes = arr("recipes").map { RecipeItem(it.getString("id"), it.getString("drinkId"), it.getString("ingredientId"), it.optDouble("qty", 0.0), it.optBoolean("deleted"), it.pos("updatedAt") ?: exported) }
        return Backup(products, moves, docs, sales, items, expenses, customers, payments, recipes)
    }
}
