package com.mozkhan.shop

import com.mozkhan.shop.data.CartLine
import com.mozkhan.shop.data.CartOps
import com.mozkhan.shop.data.CodeRow
import com.mozkhan.shop.data.Codes
import com.mozkhan.shop.data.LegacyUpgrade
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.Merge
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Prices
import com.mozkhan.shop.util.ReportCalc
import com.mozkhan.shop.util.ScanDebouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the bugs fixed in the review (no Android needed). */
class LogicTest {
    private fun piece(id: String, sell: Long = 100_000, buy: Long = 90_000) =
        ProductWithStock(id, "p$id", null, "سیگار", Measure.PIECE, buy, sell, 0.0, false, false, 10.0)

    // ---------------------------------------------------------------- bulk price rounding (was: always to 100)
    @Test fun priceRoundingKeepsSmallPrices() {
        assertEquals(105L, Prices.round(100 * 1.05))          // 100 toman +5% used to become 100
        assertEquals(53L, Prices.round(50 * 1.05))            // 52.5 -> 53 (never collapses to 0 / 100)
        assertEquals(1_160L, Prices.round(1_100 * 1.05))      // 1,155 -> nearest 10
        assertEquals(105_000L, Prices.round(100_000 * 1.05))  // large prices still round to 100
        assertEquals(94_500L, Prices.round(90_000 * 1.05))
        assertEquals(0L, Prices.round(-5.0))
        assertEquals(1L, Prices.round(0.6))
    }

    // ---------------------------------------------------------------- product codes (LegacyUpgrade)
    @Test fun codePlanIsDeterministicAndConverges() {
        val rows = listOf(
            CodeRow("b", "1005", false), CodeRow("a", "1005", false), // collision: "a" (smaller id) keeps it
            CodeRow("c", "", false),                                  // blank
            CodeRow("d", " ۱۰۰۵ ", false),                            // same code written with Persian digits
            CodeRow("z", "2000", true),                               // deleted: ignored, but its number is never reused
        )
        val plan = LegacyUpgrade.planCodes(rows)
        assertEquals(setOf("b", "c", "d"), plan.keys)
        assertEquals(LegacyUpgrade.planCodes(rows.reversed()), plan) // order of rows does not matter
        val after = rows.map { r -> plan[r.id]?.let { r.copy(code = it) } ?: r }
        val live = after.filter { !it.deleted }.map { Codes.normalize(it.code) }
        assertEquals(live.size, live.toSet().size)
        assertTrue(plan.values.all { it.toInt() > 2000 })
        assertEquals("1005", after.first { it.id == "a" }.code)
        assertTrue(LegacyUpgrade.planCodes(after).isEmpty()) // a second pass changes nothing
    }

    // ---------------------------------------------------------------- cart
    @Test fun settingPriceToAnExistingLineMergesInsteadOfDuplicatingKeys() {
        val p = piece("1")
        var cart = CartOps.add(emptyList(), CartLine(p, 1.0, 100_000))
        cart = CartOps.add(cart, CartLine(p, 2.0, 90_000))
        assertEquals(2, cart.size)
        cart = CartOps.setPrice(cart, cart[1].key, 100_000) // the LazyColumn would crash on two equal keys
        assertEquals(1, cart.size)
        assertEquals(3.0, cart[0].qty, 0.0)
        assertEquals(cart.size, cart.map { it.key }.toSet().size)
    }

    @Test fun checkoutRemovesOnlyWhatWasSold() {
        val a = piece("a"); val b = piece("b")
        val sold = listOf(CartLine(a, 1.0, 100_000))
        // while saving, "a" was scanned once more and "b" was added
        val now = listOf(CartLine(a, 2.0, 100_000), CartLine(b, 1.0, 100_000))
        val left = CartOps.subtract(now, sold)
        assertEquals(2, left.size)
        assertEquals(1.0, left.first { it.product.id == "a" }.qty, 0.0)
        assertTrue(CartOps.subtract(sold, sold).isEmpty())
        assertTrue(CartOps.setQty(sold, sold[0].key, 0.0).isEmpty())
    }

    @Test fun mergeKeepsNewestRow() {
        data class R(val id: String, val v: Int, val at: Long)
        val local = mapOf("1" to R("1", 1, 100), "2" to R("2", 1, 100))
        val inc = listOf(R("1", 2, 50), R("2", 2, 200), R("3", 2, 1), R("1", 3, 100))
        val out = Merge.newer(inc, local, { it.id }, { it.at })
        assertEquals(listOf(R("2", 2, 200), R("3", 2, 1)), out) // older and equal rows never replace local ones
    }

    // ---------------------------------------------------------------- scanner debounce
    @Test fun heldBarcodeIsCountedOnce() {
        val d = ScanDebouncer(1500)
        assertTrue(d.accept("626", 0))
        for (t in 100L..6000L step 150) assertFalse("t=$t", d.accept("626", t)) // still in view: never again
        assertTrue(d.accept("626", 6000 + 1600))   // left the view for > window
        assertTrue(d.accept("999", 7700))          // another product at once
        d.hold(10_000)
        assertFalse(d.accept("999", 10_500))       // sheet just closed, pack still in front of the lens
    }

    // ---------------------------------------------------------------- money input in RIAL
    @Test fun rialTypingKeepsEveryDigit() {
        var toman = 0L
        var shown = ""
        for (ch in "125000") {
            val typed = Fmt.normalize(shown + ch).filter { it.isDigit() }
            toman = Fmt.parseMoney(typed, Currency.RIAL)
            shown = Fmt.moneyInput(typed, toman, Currency.RIAL)
        }
        assertEquals(12_500L, toman)
        assertEquals("۱۲۵٬۰۰۰", shown)
        assertEquals("۱۲٬۰۰۰", Fmt.moneyInput("", 12_000, Currency.TOMAN)) // set from outside
        assertTrue(Fmt.parseMoney("9".repeat(40), Currency.TOMAN) > 0) // capped, no overflow to 0
    }

    // ---------------------------------------------------------------- purchase suggestion
    @Test fun reorderSuggestion() {
        // sold 30 in 10 days -> 3/day -> 14 days = 42, stock 2 -> buy 40
        assertEquals(40.0, ReportCalc.reorderQty(2.0, 5.0, 30.0, 10.0), 0.0)
        // nothing sold: at least twice the alarm level
        assertEquals(8.0, ReportCalc.reorderQty(2.0, 5.0, 0.0, 10.0), 0.0)
        assertEquals(0.0, ReportCalc.reorderQty(100.0, 5.0, 1.0, 10.0), 0.0)
        assertEquals(10.0, ReportCalc.reorderQty(-3.0, 5.0, 0.0, 0.0), 0.0) // negative stock counts as empty
    }
}
