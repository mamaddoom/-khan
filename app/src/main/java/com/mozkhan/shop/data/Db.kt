package com.mozkhan.shop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun fromUnit(u: Measure): String = u.name
    @TypeConverter fun toUnit(s: String): Measure = runCatching { Measure.valueOf(s) }.getOrDefault(Measure.PIECE)
}

private const val STOCK_SELECT = """
    SELECT p.id, p.name, p.barcode, p.category, p.unit, p.buyPrice, p.sellPrice, p.minStock, (p.fingerprints != '') AS hasPrints, p.favorite,
           COALESCE((SELECT SUM(m.qty) FROM stock_moves m WHERE m.productId = p.id), 0.0) AS stock,
           p.boxBarcode, p.boxSize, p.boxBuyPrice, p.boxSellPrice, p.trackStock, p.code, p.kind
    FROM products p WHERE p.deleted = 0
"""

@Dao
interface ProductDao {
    @Query("$STOCK_SELECT ORDER BY p.name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProductWithStock>>

    @Query("$STOCK_SELECT AND (p.barcode = :barcode OR p.boxBarcode = :barcode) LIMIT 1")
    suspend fun findByBarcode(barcode: String): ProductWithStock?

    @Query("$STOCK_SELECT AND p.id = :id LIMIT 1")
    suspend fun findById(id: String): ProductWithStock?

    @Query("SELECT * FROM products")
    suspend fun allRaw(): List<Product>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun raw(id: String): Product?

    @Query("SELECT * FROM products WHERE id IN (:ids)")
    suspend fun rawIn(ids: List<String>): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: Product)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(p: List<Product>)

    @Query("UPDATE products SET deleted = 1, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("UPDATE products SET favorite = :fav, updatedAt = :ts WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean, ts: Long)

    @Query("SELECT DISTINCT category FROM products WHERE deleted = 0 ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    @Query("SELECT id, fingerprints FROM products WHERE deleted = 0 AND fingerprints != ''")
    fun observePrints(): Flow<List<PrintRow>>

    @Query("SELECT id, code, deleted FROM products")
    suspend fun allCodes(): List<CodeRow>

    @Query("UPDATE products SET code = :code, updatedAt = :ts WHERE id = :id")
    suspend fun setCode(id: String, code: String, ts: Long)
}

@Dao
interface StockDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(m: StockMove)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(m: List<StockMove>)

    @Query("SELECT * FROM stock_moves")
    suspend fun all(): List<StockMove>

    @Query("SELECT * FROM stock_moves WHERE id IN (:ids)")
    suspend fun movesIn(ids: List<String>): List<StockMove>

    @Query("SELECT * FROM stock_docs WHERE id IN (:ids)")
    suspend fun docsIn(ids: List<String>): List<StockDoc>

    @Query("SELECT * FROM stock_moves WHERE ts BETWEEN :from AND :to")
    fun observeInRange(from: Long, to: Long): Flow<List<StockMove>>

    @Query("SELECT * FROM stock_moves WHERE refId = :ref")
    suspend fun movesOf(ref: String): List<StockMove>

    @Query("SELECT COUNT(*) FROM stock_moves WHERE productId = :productId")
    suspend fun countMoves(productId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoc(d: StockDoc)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocs(d: List<StockDoc>)

    @Query("SELECT * FROM stock_docs ORDER BY ts DESC")
    fun observeDocs(): Flow<List<StockDoc>>

    @Query("SELECT * FROM stock_docs")
    suspend fun allDocs(): List<StockDoc>

    @Query("SELECT * FROM stock_docs WHERE id = :id")
    suspend fun doc(id: String): StockDoc?

    @Query("UPDATE stock_docs SET voided = 1, updatedAt = :ts WHERE id = :id")
    suspend fun voidDoc(id: String, ts: Long)
}

@Dao
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(s: Sale)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<SaleItem>)

    @Query("SELECT * FROM sales WHERE ts BETWEEN :from AND :to ORDER BY ts DESC")
    fun observeSales(from: Long, to: Long): Flow<List<Sale>>

    @Query("SELECT * FROM sales ORDER BY ts DESC LIMIT :n")
    fun observeRecent(n: Int): Flow<List<Sale>>

    @Query("SELECT * FROM sale_items WHERE saleId = :id")
    suspend fun itemsOf(id: String): List<SaleItem>

    /** live: lines of an invoice from another phone can arrive a moment after its header */
    @Query("SELECT * FROM sale_items WHERE saleId = :id")
    fun observeItemsOf(id: String): Flow<List<SaleItem>>

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun sale(id: String): Sale?

    @Query("SELECT COUNT(*) FROM sale_items WHERE productId = :productId")
    suspend fun countItemsOf(productId: String): Int

    @Query("UPDATE sales SET voided = 1, updatedAt = :ts WHERE id = :id")
    suspend fun markVoided(id: String, ts: Long)

    @Query("SELECT * FROM sales") suspend fun allSales(): List<Sale>
    @Query("SELECT * FROM sale_items") suspend fun allItems(): List<SaleItem>
    @Query("SELECT * FROM sales WHERE id IN (:ids)") suspend fun salesIn(ids: List<String>): List<Sale>
    @Query("SELECT * FROM sale_items WHERE id IN (:ids)") suspend fun itemsIn(ids: List<String>): List<SaleItem>

    @Query(
        """SELECT COUNT(*) AS count, COALESCE(SUM(total),0) AS revenue, COALESCE(SUM(cost),0) AS cost
           FROM sales WHERE voided = 0 AND ts BETWEEN :from AND :to"""
    )
    fun observeTotals(from: Long, to: Long): Flow<Totals>

    @Query(
        """SELECT i.productId AS productId, i.name AS name, SUM(i.qty) AS qty, i.isWeight AS isWeight,
                  SUM(i.qty * i.unitPrice) AS revenue, SUM(i.qty * i.unitCost) AS cost
           FROM sale_items i JOIN sales s ON s.id = i.saleId
           WHERE s.voided = 0 AND s.ts BETWEEN :from AND :to
           GROUP BY i.productId ORDER BY revenue DESC LIMIT :limit"""
    )
    fun observeTop(from: Long, to: Long, limit: Int): Flow<List<TopProduct>>

    @Query(
        """SELECT i.productId AS productId, i.name AS name, i.category AS category, i.dept AS dept, i.qty AS qty, i.isWeight AS isWeight,
                  i.unitPrice AS unitPrice, i.unitCost AS unitCost, s.id AS saleId, s.ts AS ts,
                  s.total AS saleTotal, s.subtotal AS saleSubtotal
           FROM sale_items i JOIN sales s ON s.id = i.saleId
           WHERE s.voided = 0 AND s.ts BETWEEN :from AND :to"""
    )
    fun observeItemRows(from: Long, to: Long): Flow<List<ItemRow>>

    @Query("SELECT * FROM sales WHERE customerId = :id AND payment = 'CREDIT' ORDER BY ts DESC")
    fun observeCreditSales(id: String): Flow<List<Sale>>
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: Expense)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(e: List<Expense>)

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND ts BETWEEN :from AND :to ORDER BY ts DESC")
    fun observe(from: Long, to: Long): Flow<List<Expense>>

    @Query("SELECT COALESCE(SUM(amount),0) FROM expenses WHERE deleted = 0 AND ts BETWEEN :from AND :to")
    fun observeSum(from: Long, to: Long): Flow<Long>

    @Query("UPDATE expenses SET deleted = 1, updatedAt = :ts WHERE id = :id")
    suspend fun softDelete(id: String, ts: Long)

    @Query("SELECT * FROM expenses") suspend fun all(): List<Expense>
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun byId(id: String): Expense?
    @Query("SELECT * FROM expenses WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<Expense>
}

@Dao
interface CreditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCustomer(c: Customer)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCustomers(c: List<Customer>)
    @Query("SELECT * FROM customers WHERE deleted = 0 ORDER BY name COLLATE NOCASE") fun observeCustomers(): Flow<List<Customer>>
    @Query("SELECT * FROM customers") suspend fun allCustomers(): List<Customer>
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun customer(id: String): Customer?
    @Query("SELECT * FROM customers WHERE id IN (:ids)") suspend fun customersIn(ids: List<String>): List<Customer>

    @Query(
        """SELECT customerId AS customerId, COALESCE(SUM(total),0) AS credit, MAX(ts) AS last FROM sales
           WHERE payment = 'CREDIT' AND voided = 0 AND customerId IS NOT NULL GROUP BY customerId"""
    )
    fun observeCreditSums(): Flow<List<CreditSum>>

    @Query(
        """SELECT customerId AS customerId, COALESCE(SUM(amount),0) AS paid, MAX(ts) AS last FROM credit_payments
           WHERE deleted = 0 GROUP BY customerId"""
    )
    fun observePaidSums(): Flow<List<PaidSum>>

    @Query("SELECT COALESCE(SUM(amount),0) FROM credit_payments WHERE deleted = 0 AND ts BETWEEN :from AND :to")
    fun observePaidInRange(from: Long, to: Long): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPayment(p: CreditPayment)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPayments(p: List<CreditPayment>)
    @Query("SELECT * FROM credit_payments WHERE customerId = :id AND deleted = 0 ORDER BY ts DESC") fun observePayments(id: String): Flow<List<CreditPayment>>
    @Query("SELECT * FROM credit_payments") suspend fun allPayments(): List<CreditPayment>
    @Query("SELECT * FROM credit_payments WHERE id = :id") suspend fun payment(id: String): CreditPayment?
    @Query("SELECT * FROM credit_payments WHERE id IN (:ids)") suspend fun paymentsIn(ids: List<String>): List<CreditPayment>
    @Query("UPDATE credit_payments SET deleted = 1, updatedAt = :ts WHERE id = :id") suspend fun softDeletePayment(id: String, ts: Long)
}

@Dao
interface RecipeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<RecipeItem>)
    @Query("SELECT * FROM recipe_items WHERE deleted = 0") fun observeAll(): Flow<List<RecipeItem>>
    @Query("SELECT * FROM recipe_items WHERE drinkId = :id AND deleted = 0") suspend fun linesFor(id: String): List<RecipeItem>
    @Query("SELECT * FROM recipe_items") suspend fun all(): List<RecipeItem>
    @Query("SELECT * FROM recipe_items WHERE id = :id") suspend fun item(id: String): RecipeItem?
    @Query("SELECT * FROM recipe_items WHERE id IN (:ids)") suspend fun itemsIn(ids: List<String>): List<RecipeItem>
    @Query("UPDATE recipe_items SET deleted = 1, updatedAt = :ts WHERE drinkId = :id AND deleted = 0") suspend fun clearFor(id: String, ts: Long)
}

@Dao
interface OutboxDao {
    /** REPLACE on the (coll, docId) unique index: a re-marked document gets a new seq (see [OutboxRow]) */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(rows: List<OutboxRow>)
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :n") suspend fun next(n: Int): List<OutboxRow>
    @Query("DELETE FROM outbox WHERE seq IN (:seqs)") suspend fun remove(seqs: List<Long>)
    @Query("SELECT COUNT(*) FROM outbox") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM outbox") suspend fun count(): Int
}

private fun SupportSQLiteDatabase.hasColumn(table: String, col: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { c ->
        val i = c.getColumnIndex("name")
        var found = false
        while (c.moveToNext()) if (c.getString(i) == col) { found = true; break }
        found
    }

private fun SupportSQLiteDatabase.addColumn(table: String, col: String, ddl: String) {
    if (!hasColumn(table, col)) execSQL("ALTER TABLE `$table` ADD COLUMN `$col` $ddl")
}

/**
 * v2 -> v3: product codes + kinds (cafe), unit cost on stock moves, purchase documents, department on sale items,
 * recipe table. Written defensively (IF NOT EXISTS / column checks) so a slightly different v2 layout cannot crash it.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("products", "code", "TEXT NOT NULL DEFAULT ''")
        db.addColumn("products", "kind", "TEXT NOT NULL DEFAULT 'GOODS'")
        db.addColumn("stock_moves", "unitCost", "REAL NOT NULL DEFAULT 0")
        db.addColumn("stock_docs", "voided", "INTEGER NOT NULL DEFAULT 0")
        db.addColumn("stock_docs", "updatedAt", "INTEGER NOT NULL DEFAULT 0")
        db.addColumn("sale_items", "dept", "TEXT NOT NULL DEFAULT 'SHOP'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recipe_items` (`id` TEXT NOT NULL, `drinkId` TEXT NOT NULL, `ingredientId` TEXT NOT NULL, " +
                "`qty` REAL NOT NULL, `deleted` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recipe_items_drinkId` ON `recipe_items` (`drinkId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recipe_items_ingredientId` ON `recipe_items` (`ingredientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_code` ON `products` (`code`)")

        // ---- data backfill
        db.execSQL("UPDATE stock_docs SET updatedAt = ts WHERE updatedAt = 0")
        db.execSQL("UPDATE products SET kind = 'DRINK' WHERE category = '$CAT_COFFEE' AND trackStock = 0")
        db.execSQL("UPDATE sale_items SET dept = 'CAFE' WHERE category IN ('$CAT_COFFEE', '$CAT_COFFEE_BULK')")
        // sequential product codes (SQLite on API 26 has no window functions -> plain loop)
        val ids = ArrayList<String>()
        db.query("SELECT id FROM products WHERE code = '' ORDER BY deleted, name COLLATE NOCASE").use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        var next = 1001
        db.query("SELECT code FROM products WHERE code != ''").use { c ->
            while (c.moveToNext()) c.getString(0).filter { it.isDigit() }.toIntOrNull()?.let { if (it >= next) next = it + 1 }
        }
        for (id in ids) db.execSQL("UPDATE products SET code = ? WHERE id = ?", arrayOf<Any>((next++).toString(), id))
    }
}

@Database(
    entities = [
        Product::class, StockMove::class, StockDoc::class, Sale::class, SaleItem::class,
        Expense::class, Customer::class, CreditPayment::class, RecipeItem::class, OutboxRow::class, Cheque::class, CashClose::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun products(): ProductDao
    abstract fun stock(): StockDao
    abstract fun sales(): SaleDao
    abstract fun expenses(): ExpenseDao
    abstract fun credit(): CreditDao
    abstract fun recipes(): RecipeDao
    abstract fun outbox(): OutboxDao
    abstract fun cheques(): ChequeDao
    abstract fun cash(): CashDao

    companion object {
        /** must always equal the @Database version above (drives the pre-migration backup copy in ShopApp) */
        const val VERSION = 9

        fun create(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "shop.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                // Only the throw-away v1 test database may ever be dropped. Never allow this for v2 or later.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}

/** v3 -> v4: invoices remember which phone (seller) made them */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("sales", "deviceName", "TEXT NOT NULL DEFAULT ''")
    }
}

/** v4 -> v5: durable upload queue for cloud sync (see [OutboxRow]) */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `coll` TEXT NOT NULL, `docId` TEXT NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_coll_docId` ON `outbox` (`coll`, `docId`)")
    }
}

/** v5 -> v6: an exit document may (optionally) count as a loss in the reports; older exits do not */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("stock_docs", "counted", "INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6 -> v7: cheques given to suppliers, and the cheque a purchase document was paid with */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cheques` (`id` TEXT NOT NULL, `number` TEXT NOT NULL, `bank` TEXT NOT NULL, `amount` INTEGER NOT NULL, " +
                "`dueDate` INTEGER NOT NULL, `payee` TEXT NOT NULL, `note` TEXT NOT NULL, `status` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cheques_dueDate` ON `cheques` (`dueDate`)")
        db.addColumn("stock_docs", "chequeId", "TEXT")
    }
}

@Dao
interface ChequeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: Cheque)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<Cheque>)
    @Query("SELECT * FROM cheques WHERE deleted = 0 ORDER BY dueDate") fun observe(): Flow<List<Cheque>>
    @Query("SELECT * FROM cheques") suspend fun all(): List<Cheque>
    @Query("SELECT * FROM cheques WHERE id = :id") suspend fun get(id: String): Cheque?
    @Query("SELECT * FROM cheques WHERE id IN (:ids)") suspend fun chequesIn(ids: List<String>): List<Cheque>
}

/** v7 -> v8: end-of-day cash counts */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cash_closes` (`id` TEXT NOT NULL, `day` INTEGER NOT NULL, `opening` INTEGER NOT NULL, " +
                "`cashSales` INTEGER NOT NULL, `received` INTEGER NOT NULL, `expenses` INTEGER NOT NULL, `expected` INTEGER NOT NULL, " +
                "`counted` INTEGER NOT NULL, `note` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cash_closes_day` ON `cash_closes` (`day`)")
    }
}

@Dao
interface CashDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CashClose)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(list: List<CashClose>)
    @Query("SELECT * FROM cash_closes WHERE deleted = 0 ORDER BY day DESC, ts DESC") fun observe(): Flow<List<CashClose>>
    @Query("SELECT * FROM cash_closes") suspend fun all(): List<CashClose>
    @Query("SELECT * FROM cash_closes WHERE id = :id") suspend fun get(id: String): CashClose?
    @Query("SELECT * FROM cash_closes WHERE id IN (:ids)") suspend fun closesIn(ids: List<String>): List<CashClose>
}

/** v8 -> v9: how a counted exit was valued (buy price, sell price or a manual amount) */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumn("stock_docs", "priceBasis", "TEXT NOT NULL DEFAULT 'BUY'")
    }
}
