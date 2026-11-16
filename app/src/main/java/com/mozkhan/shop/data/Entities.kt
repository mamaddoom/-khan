package com.mozkhan.shop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** How a product is counted. VOLUME is used for liquid cafe ingredients (stock in ml, price per litre). */
enum class Measure(val fa: String) {
    PIECE("عدد"), WEIGHT("وزنی (گرم)"), VOLUME("حجمی (میلی‌لیتر)");
}

const val CAT_COFFEE_BULK = "قهوه فله"
const val CAT_COFFEE = "قهوه"
const val CAT_DRINK = "نوشیدنی کافه"
const val CAT_INGREDIENT = "مواد کافه"

/** Product kinds: GOODS (shop item), INGREDIENT (cafe raw material, never sold), DRINK (cafe menu item, may have a recipe). */
const val KIND_GOODS = "GOODS"
const val KIND_INGREDIENT = "INGREDIENT"
const val KIND_DRINK = "DRINK"

const val DEPT_SHOP = "SHOP"
const val DEPT_CAFE = "CAFE"

fun isCafeProduct(kind: String, category: String) = kind == KIND_DRINK || category == CAT_COFFEE || category == CAT_COFFEE_BULK

/**
 * All money values are stored in TOMAN (Long). The display unit (toman / rial) is a UI concern.
 * All ids are UUID strings so that several phones can create rows offline without collisions.
 * Prices of weighed products are per KILOGRAM, of volume products per LITRE, otherwise per piece.
 */
@Entity(tableName = "products", indices = [Index("barcode"), Index("boxBarcode"), Index("code")])
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    /** barcode of the single pack / piece */
    val barcode: String? = null,
    val category: String = "سایر",
    val unit: Measure = Measure.PIECE,
    val buyPrice: Long = 0,
    val sellPrice: Long = 0,
    val minStock: Double = 0.0,
    /** Visual fingerprints of reference photos, joined by '|' (see ImageFingerprint) */
    val fingerprints: String = "",
    val favorite: Boolean = false,
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    /** carton ("باکس") support: barcode, pieces inside, and its own (cheaper) prices */
    val boxBarcode: String? = null,
    val boxSize: Int = 0,
    val boxBuyPrice: Long = 0,
    val boxSellPrice: Long = 0,
    /** false = no inventory tracking (e.g. a cup of coffee) */
    val trackStock: Boolean = true,
    /** searchable, editable, unique product code (digits are stored as Latin digits) */
    @ColumnInfo(defaultValue = "") val code: String = "",
    @ColumnInfo(defaultValue = "GOODS") val kind: String = KIND_GOODS,
)

/**
 * Stock is NEVER stored as a number on the product. It is the sum of immutable movements.
 * Append-only rows can be merged between phones with zero conflicts.
 * qty is in pieces, GRAMS or MILLILITRES.
 * reason: PURCHASE, OPENING, SALE, RETURN, ADJUST, EXIT, CONSUME (ingredient used by a drink)
 * unitCost = cost per base unit at the time of the move (0 = unknown / legacy).
 */
@Entity(tableName = "stock_moves", indices = [Index("productId"), Index("ts")])
data class StockMove(
    @PrimaryKey val id: String,
    val productId: String,
    val qty: Double,
    val reason: String,
    val refId: String? = null,
    val ts: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val unitCost: Double = 0.0,
)

/**
 * Document for stock movements that are not sales: purchases (kind PURCHASE, note = supplier) and
 * exits without sale (DAMAGE, PERSONAL, GIFT, TRANSFER, OTHER).
 */
@Entity(tableName = "stock_docs", indices = [Index("ts")])
data class StockDoc(
    @PrimaryKey val id: String,
    val ts: Long = System.currentTimeMillis(),
    val kind: String,
    val note: String = "",
    /** value at buy price (for purchases: what was paid) */
    val cost: Long = 0,
    @ColumnInfo(defaultValue = "0") val voided: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(tableName = "sales", indices = [Index("ts"), Index("customerId")])
data class Sale(
    @PrimaryKey val id: String,
    val ts: Long = System.currentTimeMillis(),
    val subtotal: Long,
    val discount: Long = 0,
    val total: Long,
    val cost: Long,
    val payment: String = "CASH", // CASH, CARD, CREDIT
    val note: String = "",
    val voided: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val customerId: String? = null,
)

@Entity(tableName = "sale_items", indices = [Index("saleId"), Index("productId")])
data class SaleItem(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: String,
    val name: String,
    /** pieces, or grams for weighed products (a carton sale is stored as N pieces) */
    val qty: Double,
    val isWeight: Boolean,
    /** price & cost per sold piece (or per gram) */
    @ColumnInfo(defaultValue = "0") val unitPrice: Double,
    @ColumnInfo(defaultValue = "0") val unitCost: Double,
    @ColumnInfo(defaultValue = "") val category: String = "",
    @ColumnInfo(defaultValue = "SHOP") val dept: String = DEPT_SHOP,
) {
    val lineTotal: Long get() = Math.round(qty * unitPrice)
    val lineCost: Long get() = Math.round(qty * unitCost)
}

/** One ingredient line of a drink recipe: [qty] base units (pieces / g / ml) of [ingredientId] per drink. */
@Entity(tableName = "recipe_items", indices = [Index("drinkId"), Index("ingredientId")])
data class RecipeItem(
    @PrimaryKey val id: String,
    val drinkId: String,
    val ingredientId: String,
    val qty: Double,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Entity(tableName = "expenses", indices = [Index("ts")])
data class Expense(
    @PrimaryKey val id: String,
    val title: String,
    val amount: Long,
    val ts: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "customers")
data class Customer(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String = "",
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "credit_payments", indices = [Index("customerId")])
data class CreditPayment(
    @PrimaryKey val id: String,
    val customerId: String,
    val amount: Long,
    val ts: Long = System.currentTimeMillis(),
    val note: String = "",
    val deleted: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

// ---- query projections ----
data class ProductWithStock(
    val id: String,
    val name: String,
    val barcode: String?,
    val category: String,
    val unit: Measure,
    val buyPrice: Long,
    val sellPrice: Long,
    val minStock: Double,
    val hasPrints: Boolean,
    val favorite: Boolean,
    val stock: Double,
    val boxBarcode: String? = null,
    val boxSize: Int = 0,
    val boxBuyPrice: Long = 0,
    val boxSellPrice: Long = 0,
    val trackStock: Boolean = true,
    val code: String = "",
    val kind: String = KIND_GOODS,
) {
    /** weighed products are sold per KILOGRAM but counted in grams */
    val isWeight get() = unit == Measure.WEIGHT
    val isVolume get() = unit == Measure.VOLUME
    /** price is per 1000 base units (kg / litre) */
    val isMeasured get() = unit != Measure.PIECE
    val hasBox get() = boxSize > 1 && boxSellPrice > 0
    val low get() = trackStock && stock <= minStock && minStock > 0
    /** cost / price of ONE base unit (piece, gram or ml) */
    val baseCost: Double get() = if (isMeasured) buyPrice / 1000.0 else buyPrice.toDouble()
    val baseSell: Double get() = if (isMeasured) sellPrice / 1000.0 else sellPrice.toDouble()
    /** buy value of the current stock */
    val stockValue: Long get() = if (!trackStock || stock <= 0) 0 else Math.round(stock * baseCost)
    val stockSellValue: Long get() = if (!trackStock || stock <= 0) 0 else Math.round(stock * baseSell)
}

data class Totals(val revenue: Long, val cost: Long, val count: Int)
data class TopProduct(val productId: String, val name: String, val qty: Double, val isWeight: Boolean, val revenue: Double, val cost: Double)

/** One sold line together with the sale header it belongs to (used by the filterable reports). */
data class ItemRow(
    val productId: String,
    val name: String,
    val category: String,
    val dept: String,
    val qty: Double,
    val isWeight: Boolean,
    val unitPrice: Double,
    val unitCost: Double,
    val saleId: String,
    val ts: Long,
    val saleTotal: Long,
    val saleSubtotal: Long,
)

data class CreditSum(val customerId: String, val credit: Long, val last: Long)
data class PaidSum(val customerId: String, val paid: Long, val last: Long)
data class PrintRow(val id: String, val fingerprints: String)
data class CodeRow(val id: String, val code: String, val deleted: Boolean)
