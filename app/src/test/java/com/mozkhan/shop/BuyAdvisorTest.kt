package com.mozkhan.shop

import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.BuyAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuyAdvisorTest {
    private fun p(id: String, stock: Double, min: Double = 0.0, unit: Measure = Measure.PIECE) =
        ProductWithStock(id, id, null, "سیگار", unit, 1000, 1200, min, false, false, stock)

    @Test fun fastSellerRunningOutIsBuyMoreWithEnoughForAMonth() {
        // 60 sold in 30 days = 2/day; 10 in stock = 5 days -> buy 2*30 - 10 = 50
        val a = BuyAdvisor.advise(listOf(p("a", 10.0)), mapOf("a" to 60.0))
        assertEquals(1, a.more.size)
        assertEquals(50.0, a.more[0].qty, 0.0)
        assertTrue(a.less.isEmpty())
    }

    @Test fun notSellingWithStockIsBuyLess() {
        val a = BuyAdvisor.advise(listOf(p("b", 40.0)), emptyMap())
        assertTrue(a.more.isEmpty())
        assertEquals("b", a.less.single().p.id)
    }

    @Test fun tooMuchStockIsBuyLessHealthyIsNeither() {
        // 3/30 per day = 0.1/day; 50 in stock = 500 days -> less. 30 sold & 20 in stock = 20 days -> neither
        val a = BuyAdvisor.advise(listOf(p("c", 50.0), p("d", 20.0)), mapOf("c" to 3.0, "d" to 30.0))
        assertEquals(listOf("c"), a.less.map { it.p.id })
        assertTrue(a.more.isEmpty())
    }

    @Test fun underMinimumWithoutSalesStillBuyMore() {
        val a = BuyAdvisor.advise(listOf(p("e", 1.0, min = 5.0)), emptyMap())
        assertEquals(9.0, a.more.single().qty, 0.0) // 2 x minimum - stock
    }

    @Test fun weightGoodsRoundTo100Grams() {
        // 3000 g in 30 days = 100 g/day; 250 g in stock -> 3000 - 250 = 2750 -> 2800 g
        val a = BuyAdvisor.advise(listOf(p("f", 250.0, unit = Measure.WEIGHT)), mapOf("f" to 3000.0))
        assertEquals(2800.0, a.more.single().qty, 0.0)
    }
}
