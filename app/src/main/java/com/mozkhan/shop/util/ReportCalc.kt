package com.mozkhan.shop.util

import com.mozkhan.shop.data.ItemRow
import com.mozkhan.shop.data.TopProduct
import java.util.Calendar

private const val DAY_MS = 86_400_000L

/** Splits a date range into chart buckets (hours / days / Jalali months) depending on its length. */
class Bucketer(val period: Period, val from: Long, val to: Long) {
    private enum class Mode { HOURS, DAYS, MONTHS }

    private val mode: Mode
    val n: Int
    val labels: List<String>
    private val startDay = Jalali.startOfDay(from)
    private val y0: Int
    private val m0: Int

    init {
        val j0 = Jalali.of(from)
        y0 = j0.y; m0 = j0.m
        val days = ((Jalali.startOfDay(to) - startDay + DAY_MS / 2) / DAY_MS).toInt() + 1
        val m = when (period) {
            Period.DAY -> Mode.HOURS
            Period.WEEK, Period.MONTH -> Mode.DAYS
            Period.YEAR -> Mode.MONTHS
            Period.CUSTOM -> if (days <= 1) Mode.HOURS else if (days <= 62) Mode.DAYS else Mode.MONTHS
        }
        mode = m
        when (m) {
            Mode.HOURS -> { n = 12; labels = (0 until 12).map { Fmt.digits((it * 2).toString()) } }
            Mode.DAYS -> {
                n = days.coerceAtLeast(1)
                val step = if (n <= 8) 1 else if (n <= 16) 2 else 5
                labels = (0 until n).map { i ->
                    if (period == Period.WEEK) listOf("ش", "ی", "د", "س", "چ", "پ", "ج")[i.coerceAtMost(6)]
                    else {
                        val d = Jalali.of(startDay + i * DAY_MS + DAY_MS / 2).d
                        if (i % step == 0 || d == 1) Fmt.digits(d.toString()) else ""
                    }
                }
            }
            Mode.MONTHS -> {
                val j1 = Jalali.of(to)
                n = ((j1.y - y0) * 12 + (j1.m - m0) + 1).coerceIn(1, 60)
                labels = (0 until n).map { i ->
                    val mm = (m0 - 1 + i) % 12
                    Jalali.months[mm].take(3)
                }
            }
        }
    }

    fun index(ts: Long): Int = when (mode) {
        Mode.HOURS -> Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY) / 2
        Mode.DAYS -> ((Jalali.startOfDay(ts) - startDay + DAY_MS / 2) / DAY_MS).toInt()
        Mode.MONTHS -> { val j = Jalali.of(ts); (j.y - y0) * 12 + (j.m - m0) }
    }.coerceIn(0, n - 1)
}

data class Agg(val revenue: Long, val cost: Long, val count: Int) {
    val profit get() = revenue - cost
    val margin get() = if (revenue > 0) profit * 100.0 / revenue else 0.0
}

data class CatRow(val category: String, val revenue: Long, val profit: Long)

/**
 * Report maths on sold lines. The invoice-level discount is spread proportionally over its lines,
 * so every filter (category / product / date) still adds up to the real invoice totals.
 */
object ReportCalc {
    private fun factor(r: ItemRow) = if (r.saleSubtotal > 0) r.saleTotal.toDouble() / r.saleSubtotal else 1.0
    fun revenueOf(r: ItemRow): Double = r.qty * r.unitPrice * factor(r)
    fun costOf(r: ItemRow): Double = r.qty * r.unitCost

    fun filter(rows: List<ItemRow>, category: String?, dept: String? = null): List<ItemRow> =
        rows.filter { (category == null || it.category == category) && (dept == null || it.dept == dept) }

    fun totals(rows: List<ItemRow>): Agg {
        var rev = 0.0; var cost = 0.0
        val sales = HashSet<String>()
        for (r in rows) { rev += revenueOf(r); cost += costOf(r); sales.add(r.saleId) }
        return Agg(Math.round(rev), Math.round(cost), sales.size)
    }

    fun top(rows: List<ItemRow>, limit: Int): List<TopProduct> =
        rows.groupBy { it.productId }.map { (id, list) ->
            TopProduct(id, list.first().name.removeSuffix(" (باکس)"), list.sumOf { it.qty }, list.first().isWeight,
                list.sumOf { revenueOf(it) }, list.sumOf { costOf(it) })
        }.sortedByDescending { it.revenue }.take(limit)

    fun byCategory(rows: List<ItemRow>): List<CatRow> =
        rows.groupBy { it.category.ifBlank { "سایر" } }.map { (c, l) ->
            val rev = l.sumOf { revenueOf(it) }
            CatRow(c, Math.round(rev), Math.round(rev - l.sumOf { costOf(it) }))
        }.sortedByDescending { it.revenue }

    /**
     * Suggested purchase in base units for a tracked product: enough to cover [coverDays] of the recent sales
     * rate, and at least twice the low-stock alarm level. 0 when the current stock already covers it.
     */
    fun reorderQty(stock: Double, minStock: Double, soldInRange: Double, rangeDays: Double, coverDays: Int = 14): Double {
        val daily = if (rangeDays > 0) soldInRange.coerceAtLeast(0.0) / rangeDays else 0.0
        val target = maxOf(minStock * 2, daily * coverDays)
        val need = target - stock.coerceAtLeast(0.0)
        return if (need > 0) Math.ceil(need) else 0.0
    }

    /** revenue and profit per bucket */
    fun series(rows: List<ItemRow>, b: Bucketer): Pair<List<Long>, List<Long>> {
        val rev = DoubleArray(b.n)
        val prof = DoubleArray(b.n)
        for (r in rows) {
            val i = b.index(r.ts)
            val rv = revenueOf(r)
            rev[i] += rv
            prof[i] += rv - costOf(r)
        }
        return rev.map { Math.round(it) } to prof.map { Math.round(it) }
    }
}
