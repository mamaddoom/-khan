package com.mozkhan.shop

import com.mozkhan.shop.data.Cheque
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.Repo
import com.mozkhan.shop.ui.expectedCash
import com.mozkhan.shop.ui.purchasePlan
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Daily
import com.mozkhan.shop.util.Jalali
import com.mozkhan.shop.util.Prices
import com.mozkhan.shop.voice.Spoken
import com.mozkhan.shop.voice.VoiceAssistant
import com.mozkhan.shop.voice.VoiceParser
import com.mozkhan.shop.voice.VoiceParser.Ask
import com.mozkhan.shop.voice.VoiceParser.Cmd
import com.mozkhan.shop.voice.VoiceParser.QUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice questions / purchases / credit / undo, learned corrections, spoken numbers, price preview, reminders, cash close. */
class AssistantV2Test {
    private fun p(id: String, name: String, unit: Measure = Measure.PIECE, buy: Long = 90_000, sell: Long = 100_000, stock: Double = 10.0, min: Double = 0.0, boxSize: Int = 0, boxBuy: Long = 0, boxSell: Long = 0) =
        ProductWithStock(id, name, null, "سیگار", unit, buy, sell, min, false, false, stock, boxSize = boxSize, boxBuyPrice = boxBuy, boxSellPrice = boxSell)

    private val bahman = p("b", "بهمن کوچک", boxSize = 10, boxBuy = 850_000, boxSell = 950_000)
    private val marlboro = p("m", "مارلبرو قرمز")
    private val malboro2 = p("m2", "مارلبرو سفید")
    private val coffee = p("c", "قهوه اسپرسو", unit = Measure.WEIGHT, buy = 1_000_000, sell = 1_500_000, stock = 3000.0)
    private val all = listOf(bahman, marlboro, malboro2, coffee)

    // ---------------------------------------------------------------- parser
    @Test fun questions() {
        assertEquals(Cmd.Query(Ask.STOCK, "بهمن"), VoiceParser.parse("موجودی بهمن چنده"))
        assertEquals(Cmd.Query(Ask.STOCK, "بهمن"), VoiceParser.parse("چند تا بهمن داریم"))
        assertEquals(Cmd.Query(Ask.PRICE, "قهوه"), VoiceParser.parse("قیمت قهوه"))
        assertEquals(Cmd.Query(Ask.PRICE, "بهمن"), VoiceParser.parse("بهمن چنده"))
        assertEquals(Cmd.Query(Ask.TODAY), VoiceParser.parse("فروش امروز چقدر شد"))
        assertEquals(Cmd.Query(Ask.CHEQUES), VoiceParser.parse("چک‌های این هفته"))
        assertEquals(Cmd.Query(Ask.DEBT, "رضایی"), VoiceParser.parse("بدهی آقای رضایی"))
        // ordinary commands are not mistaken for questions
        assertTrue(VoiceParser.parse("دو تا بهمن و یک وینستون") is Cmd.Add)
        assertTrue(VoiceParser.parse("ثبت کن کارت به کارت") is Cmd.Checkout)
        assertTrue(VoiceParser.parse("حساب کن") is Cmd.Checkout)
    }

    @Test fun purchases() {
        val a = VoiceParser.parse("خرید بهمن ده باکس هر باکس هشتصد هزار") as Cmd.Purchase
        assertEquals("بهمن", a.item.query); assertEquals(10.0, a.item.qty!!, 0.0); assertEquals(QUnit.BOX, a.item.unit)
        assertEquals(800_000.0, a.price!!, 0.0); assertFalse(a.total)
        val b = VoiceParser.parse("خرید ده تا بهمن به قیمت نود هزار") as Cmd.Purchase
        assertEquals("بهمن", b.item.query); assertEquals(90_000.0, b.price!!, 0.0)
        val c = VoiceParser.parse("خرید دو کیلو قهوه جمعا سه میلیون") as Cmd.Purchase
        assertEquals(QUnit.KILO, c.item.unit); assertEquals(3_000_000.0, c.price!!, 0.0); assertTrue(c.total)
        val d = VoiceParser.parse("خرید بهمن پنج تا") as Cmd.Purchase
        assertNull(d.price)
    }

    @Test fun creditWithCustomerAndUndo() {
        assertEquals(Cmd.Checkout("نسیه", "رضایی"), VoiceParser.parse("نسیه به نام رضایی"))
        assertEquals(Cmd.Checkout("نسیه", "علی محمدی"), VoiceParser.parse("ثبت کن نسیه آقای علی محمدی"))
        assertEquals(Cmd.Checkout("نسیه", ""), VoiceParser.parse("نسیه"))
        assertEquals(Cmd.Undo, VoiceParser.parse("برگردون"))
        assertEquals(Cmd.Undo, VoiceParser.parse("لغو آخرین کار"))
        assertEquals(Cmd.Clear, VoiceParser.parse("فاکتور رو خالی کن"))
    }

    // ---------------------------------------------------------------- learning from corrections
    @Test fun learnedCorrectionWins() {
        val item = VoiceParser.Item(listOf("مالبرو"), 1.0, null)
        // two similar names: no clear winner without help
        assertNull(VoiceAssistant.line(item, all).product)
        val learned = mapOf(VoiceAssistant.key("مالبرو") to "m2")
        val l = VoiceAssistant.line(item, all, learned)
        assertEquals("m2", l.product?.id)
        assertTrue(l.choices.any { it.id == "m" })
        // spelling variants of the same sound share the memory
        assertEquals(VoiceAssistant.key("سدف"), VoiceAssistant.key("صدف"))
    }

    @Test fun customerByVoice() {
        val cs = listOf(Customer("1", "رضایی"), Customer("2", "رضوی"), Customer("3", "احمدی"))
        assertEquals("3", VoiceAssistant.customer("احمدی", cs).customer?.id)
        assertEquals("2", VoiceAssistant.customer("رزوی", cs, mapOf("c:" + VoiceAssistant.key("رزوی") to "2")).customer?.id)
        assertNull(VoiceAssistant.customer("", cs).customer)
        assertEquals(3, VoiceAssistant.customer("", cs).choices.size)
    }

    // ---------------------------------------------------------------- voice purchase -> purchase record
    @Test fun purchasePlanByBoxPieceAndWeight() {
        val boxCmd = VoiceParser.parse("خرید بهمن ده باکس هر باکس هشتصد هزار") as Cmd.Purchase
        val boxLine = VoiceAssistant.line(boxCmd.item, all)
        assertTrue(boxLine.box)
        val a = purchasePlan(bahman, boxLine, boxCmd, Currency.TOMAN)
        assertEquals(100.0, a.qtyBase, 0.0)
        assertEquals(80_000.0, a.unitCost, 0.001)
        assertEquals(80_000L, a.newBuy); assertEquals(800_000L, a.boxBuy); assertEquals(8_000_000L, a.total)

        val wCmd = VoiceParser.parse("خرید دو کیلو قهوه جمعا سه میلیون") as Cmd.Purchase
        val w = purchasePlan(coffee, VoiceAssistant.line(wCmd.item, all), wCmd, Currency.TOMAN)
        assertEquals(2000.0, w.qtyBase, 0.0); assertEquals(1_500_000L, w.newBuy); assertEquals(3_000_000L, w.total); assertNull(w.boxBuy)

        // no price said: the last buy price
        val nCmd = VoiceParser.parse("خرید مارلبرو قرمز پنج تا") as Cmd.Purchase
        val n = purchasePlan(marlboro, VoiceAssistant.line(nCmd.item, all), nCmd, Currency.TOMAN)
        assertEquals(5.0, n.qtyBase, 0.0); assertEquals(90_000L, n.newBuy); assertEquals(450_000L, n.total)
    }

    // ---------------------------------------------------------------- spoken numbers
    @Test fun spokenNumbers() {
        assertEquals("صفر", Spoken.number(0))
        assertEquals("بیست و پنج", Spoken.number(25))
        assertEquals("صد و بیست هزار", Spoken.number(120_000))
        assertEquals("یک میلیون و دویست و پنجاه هزار", Spoken.number(1_250_000))
        assertEquals("هزار و پانصد", Spoken.number(1500))
        assertEquals("سه هزار و چهارصد و پنج", Spoken.number(3405))
        assertEquals("یک و نیم کیلو", Spoken.qty(1500.0, Measure.WEIGHT))
        assertEquals("دوازده عدد", Spoken.qty(12.0, Measure.PIECE))
        assertEquals("صد و بیست هزار تومان", Spoken.money(120_000, Currency.TOMAN))
        // every spoken number is understood back by the parser
        for (n in listOf(7L, 19L, 105L, 999L, 12_345L, 250_000L, 3_200_000L)) {
            assertEquals(n.toDouble(), VoiceParser.number(VoiceParser.tokens(Spoken.number(n)), 0)!!.first, 0.0)
        }
    }

    // ---------------------------------------------------------------- price preview = what is applied
    @Test fun groupPriceChange() {
        assertEquals(115_000L, Prices.change(100_000, 15.0, null))
        assertEquals(0L, Prices.change(0, 15.0, null)) // unset stays unset
        assertEquals(105_000L, Prices.change(100_000, null, 5_000))
        assertEquals(150_000L, Prices.change(100_000, null, 5_000, 10)) // box: amount x pieces
        assertEquals(0L, Prices.change(3_000, null, -5_000)) // never negative
    }

    // ---------------------------------------------------------------- reminders
    @Test fun dueChequesAndLowStock() {
        val now = Jalali.startOfDay(System.currentTimeMillis()) + 10 * 3_600_000L
        val day = 86_400_000L
        fun ch(id: String, inDays: Int, status: String = "PENDING") = Cheque(id, id, amount = 1000, dueDate = Jalali.startOfDay(now) + inDays * day, status = status)
        val list = listOf(ch("late", -2), ch("today", 0), ch("in3", 3), ch("in5", 5), ch("paid", 1, "PAID"))
        assertEquals(listOf("late", "today", "in3"), Daily.dueCheques(list, 3, now).map { it.first.id })
        assertEquals(listOf(-2, 0, 3), Daily.dueCheques(list, 3, now).map { it.second })
        assertEquals("فردا", Daily.whenText(1))

        val low = Daily.lowList(listOf(p("a", "a", stock = 2.0, min = 5.0), p("b", "b", stock = 50.0, min = 5.0), p("c", "c", stock = 0.0)))
        assertEquals(listOf("a"), low.map { it.id })
    }

    // ---------------------------------------------------------------- cash close
    @Test fun expectedCashInDrawer() {
        assertEquals(1_000_000L + 3_000_000 + 200_000 - 500_000, expectedCash(1_000_000, Repo.CashDay(3_000_000, 200_000, 500_000)))
        val c = com.mozkhan.shop.data.CashClose("x", 0, expected = 1000, counted = 900)
        assertEquals(-100L, c.diff)
    }
}
