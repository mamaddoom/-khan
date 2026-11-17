package com.mozkhan.shop.util

import com.mozkhan.shop.data.ProductWithStock

/** One line of the buying advice. [qty] = suggested amount in base units (0 for "buy less"). */
data class BuyTip(
    val p: ProductWithStock,
    val qty: Double,
    val daily: Double,
    /** days the current stock lasts at the recent selling speed; null = no sales */
    val coverDays: Double?,
    val reason: String,
)

data class BuyAdvice(val more: List<BuyTip> = emptyList(), val less: List<BuyTip> = emptyList(), val days: Int = 30)

/**
 * Buying advice from the last [days] days of sales (pure, unit-tested):
 * - BUY MORE: goods that sell and would run out within [shortDays] (or are under their minimum). Suggested amount =
 *   enough for [targetDays] at the recent speed (at least twice the minimum), minus what is in stock.
 * - BUY LESS: goods with stock that did not sell at all, or whose stock lasts longer than [longDays]: the money is
 *   sitting on the shelf, so the next order should be smaller or skipped.
 */
object BuyAdvisor {
    fun advise(
        products: List<ProductWithStock>, soldQty: Map<String, Double>, days: Int = 30,
        shortDays: Int = 14, targetDays: Int = 30, longDays: Int = 90,
    ): BuyAdvice {
        val tracked = products.filter { it.trackStock }
        val more = ArrayList<BuyTip>()
        val less = ArrayList<BuyTip>()
        for (p in tracked) {
            val sold = (soldQty[p.id] ?: 0.0).coerceAtLeast(0.0)
            val daily = sold / days
            val stock = p.stock.coerceAtLeast(0.0)
            val cover = if (daily > 0) stock / daily else null
            val underMin = p.minStock > 0 && p.stock <= p.minStock
            if ((cover != null && cover < shortDays) || underMin) {
                val target = maxOf(daily * targetDays, p.minStock * 2)
                var need = target - stock
                if (need <= 0) continue
                // pieces round up to whole items, weight/volume to 100 g / 100 ml
                need = if (p.isMeasured) Math.ceil(need / 100.0) * 100.0 else Math.ceil(need)
                val why = when {
                    cover != null && stock <= 0 -> "تمام شده؛ در $days روز ${Fmt.qtyOf(sold, p.unit)} فروش داشته"
                    cover != null -> "موجودی فقط برای حدود ${Math.max(1, cover.toInt())} روز؛ در $days روز ${Fmt.qtyOf(sold, p.unit)} فروش"
                    else -> "زیر حداقل موجودی"
                }
                more += BuyTip(p, need, daily, cover, Fmt.digits(why))
            } else if (stock > 0 && (cover == null || cover > longDays)) {
                val why = if (cover == null) "در $days روز گذشته فروشی نداشته؛ فعلاً نخرید"
                else "موجودی برای حدود ${cover.toInt()} روز کافی است؛ کمتر بخرید"
                less += BuyTip(p, 0.0, daily, cover, Fmt.digits(why))
            }
        }
        return BuyAdvice(
            more = more.sortedWith(compareBy<BuyTip> { it.coverDays ?: -1.0 }.thenByDescending { it.daily * it.p.baseCost }),
            less = less.sortedByDescending { it.p.stockValue },
            days = days,
        )
    }
}
