package com.mozkhan.shop

import com.mozkhan.shop.data.CartLine
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali
import com.mozkhan.shop.util.Period
import com.mozkhan.shop.util.Ranges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CoreTest {
    @Test fun jalaliKnownDates() {
        assertEquals(Jalali.Date(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))
        assertEquals(Jalali.Date(1404, 1, 1), Jalali.fromGregorian(2025, 3, 21))
        assertEquals(Jalali.Date(1402, 12, 29), Jalali.fromGregorian(2024, 3, 19))
        assertEquals(Jalali.Date(1399, 12, 30), Jalali.fromGregorian(2021, 3, 20)) // leap year
    }

    @Test fun jalaliRoundTrip() {
        val cal = Calendar.getInstance().apply { set(2000, 0, 1) }
        repeat(9000) {
            val j = Jalali.fromGregorian(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val g = Jalali.toGregorian(j.y, j.m, j.d)
            assertEquals(Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)), g)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    @Test fun leapYears() {
        assertTrue(Jalali.isLeap(1399)); assertTrue(Jalali.isLeap(1403))
        assertTrue(!Jalali.isLeap(1400)); assertTrue(!Jalali.isLeap(1404))
        assertEquals(31, Jalali.monthLength(1404, 1)); assertEquals(30, Jalali.monthLength(1404, 8))
        assertEquals(29, Jalali.monthLength(1404, 12)); assertEquals(30, Jalali.monthLength(1403, 12))
    }

    @Test fun moneyFormatting() {
        assertEquals("۱٬۲۳۴٬۵۶۷", Fmt.group(1234567))
        assertEquals("۱٬۲۳۴٬۵۶۷ تومان", Fmt.money(1234567, Currency.TOMAN))
        assertEquals("۱۲٬۳۴۵٬۶۷۰ ریال", Fmt.money(1234567, Currency.RIAL))
        assertEquals(1234567L, Fmt.parseMoney("۱٬۲۳۴٬۵۶۷", Currency.TOMAN))
        assertEquals(1234567L, Fmt.parseMoney("12345670", Currency.RIAL))
        assertEquals("-۵٬۰۰۰", Fmt.group(-5000))
    }

    @Test fun cartMath() {
        val piece = ProductWithStock("1", "بهمن", "626", "سیگار", Measure.PIECE, 90_000, 100_000, 0.0, false, false, 10.0)
        val l1 = CartLine(piece, 3.0, 100_000)
        assertEquals(300_000L, l1.total); assertEquals(270_000L, l1.cost)
        val coffee = ProductWithStock("2", "قهوه", null, "قهوه", Measure.WEIGHT, 800_000, 1_000_000, 0.0, false, false, 5000.0)
        val l2 = CartLine(coffee, 250.0, 1_000_000) // 250 g at 1,000,000 / kg
        assertEquals(250_000L, l2.total); assertEquals(200_000L, l2.cost)
    }

    @Test fun cartonSaleMath() {
        // pack 100,000 (buy 90,000); carton of 10 sells 950,000 (buy 850,000)
        val p = ProductWithStock("1", "بهمن", "626", "سیگار", Measure.PIECE, 90_000, 100_000, 0.0, false, false, 50.0,
            boxBarcode = "627", boxSize = 10, boxBuyPrice = 850_000, boxSellPrice = 950_000)
        val l = CartLine(p, 2.0, 950_000, box = true)
        assertEquals(20.0, l.pieces, 0.0)
        assertEquals(1_900_000L, l.total)
        assertEquals(1_700_000L, l.cost)
        assertTrue(l.key != CartLine(p, 2.0, 950_000, false).key)
    }

    @Test fun reportDiscountAllocation() {
        val rows = listOf(
            com.mozkhan.shop.data.ItemRow("a", "الف", "سیگار", "SHOP", 1.0, false, 600.0, 400.0, "s1", 0L, 900, 1000),
            com.mozkhan.shop.data.ItemRow("b", "ب", "فندک", "SHOP", 1.0, false, 400.0, 300.0, "s1", 0L, 900, 1000),
        )
        val all = com.mozkhan.shop.util.ReportCalc.totals(rows)
        assertEquals(900L, all.revenue); assertEquals(700L, all.cost); assertEquals(1, all.count)
        val cig = com.mozkhan.shop.util.ReportCalc.totals(com.mozkhan.shop.util.ReportCalc.filter(rows, "سیگار"))
        assertEquals(540L, cig.revenue)
    }

    @Test fun persianSuggestions() {
        val r = com.mozkhan.shop.ml.Suggest.from(listOf("WINSTON", "LIGHTS", "20 FILTER CIGARETTES"), emptyList())
        assertEquals("سیگار", r.category)
        assertTrue(r.names.first().contains("وینستون") && r.names.first().contains("لایت"))
        val l = com.mozkhan.shop.ml.Suggest.from(listOf("ZIPPO"), listOf("Lighter"))
        assertEquals("فندک", l.category)
    }

    @Test fun productCodes() {
        assertEquals(1001, com.mozkhan.shop.data.Codes.next(emptyList()))
        assertEquals(1006, com.mozkhan.shop.data.Codes.next(listOf("1005", "", "A-0999")))
        assertEquals("1234", com.mozkhan.shop.data.Codes.normalize(" ۱۲۳۴ "))
        assertEquals("ab12", com.mozkhan.shop.data.Codes.normalize("AB 12"))
    }

    @Test fun cafeRecipeCostAndVolume() {
        val milk = ProductWithStock("m", "شیر", null, "مواد کافه", Measure.VOLUME, 40_000, 0, 0.0, false, false, 5000.0, kind = "INGREDIENT")
        assertEquals(40.0, milk.baseCost, 0.0001)            // 40,000 per litre = 40 per ml
        val cup = ProductWithStock("c", "لیوان", null, "مواد کافه", Measure.PIECE, 1_500, 0, 0.0, false, false, 100.0, kind = "INGREDIENT")
        val latte = 200 * milk.baseCost + 1 * cup.baseCost  // 200 ml milk + 1 cup
        assertEquals(9_500.0, latte, 0.0001)
        assertEquals(200_000L, milk.stockValue)
    }

    @Test fun rangesAreOrdered() {
        for (p in Period.entries) for (o in -3..0) {
            val r = Ranges.of(p, o)
            assertTrue("$p $o", r.from < r.to)
            if (o < 0) assertTrue(Ranges.of(p, o).to < Ranges.of(p, o + 1).from + 1)
        }
        val m = Ranges.of(Period.MONTH, 0)
        val days = ((m.to - m.from) / 86_400_000.0).let { Math.round(it) }
        assertTrue(days in 28..31)
    }
}
