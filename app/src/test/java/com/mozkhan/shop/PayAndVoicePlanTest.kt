package com.mozkhan.shop

import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.PayMethod
import com.mozkhan.shop.util.PayMethods
import com.mozkhan.shop.voice.VoiceAssistant
import com.mozkhan.shop.voice.VoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) // org.json lives in the Android framework
@Config(sdk = [34])
class PayAndVoicePlanTest {
    private val methods = PayMethods.defaults + PayMethod(PayMethods.customId("کارت به کارت"), "کارت به کارت") +
        PayMethod(PayMethods.customId("دستگاه ۱"), "دستگاه ۱") + PayMethod(PayMethods.customId("دستگاه ۲"), "دستگاه ۲")

    @Test fun payMethodsRoundTripAndKeepBuiltIns() {
        val back = PayMethods.fromJson(PayMethods.toJson(methods.drop(1))) // CASH missing on purpose
        assertTrue(back.any { it.id == PayMethods.CASH && it.enabled })
        assertEquals(6, back.size)
        assertEquals("دستگاه ۲", PayMethods.label("P:دستگاه ۲"))
        assertEquals("نسیه", PayMethods.label("CREDIT"))
        assertEquals(PayMethods.defaults, PayMethods.fromJson("not json"))
        assertEquals("این نام قبلاً هست", PayMethods.nameError("کارت", methods))
    }

    @Test fun spokenPaymentMethod() {
        assertEquals("دستگاه ۱", VoiceAssistant.method("دستگاه یک", methods)?.name)
        assertEquals("دستگاه ۲", VoiceAssistant.method("دستگاه دو", methods)?.name)
        assertEquals("کارت به کارت", VoiceAssistant.method("کارت به کارت", methods)?.name)
        assertEquals("نقد", VoiceAssistant.method("نقدی", methods)?.name)
        assertNull(VoiceAssistant.method("", methods))
    }

    private fun p(id: String, name: String, unit: Measure = Measure.PIECE) =
        ProductWithStock(id, name, null, "سیگار", unit, 1000, 1200, 0.0, false, false, 10.0)

    @Test fun planUsesTheProductsWayOfSelling() {
        val prods = listOf(p("1", "بهمن کوچک"), p("2", "قهوه اسپرسو", Measure.WEIGHT))
        fun plan(s: String) = VoiceAssistant.lines((VoiceParser.parse(s) as VoiceParser.Cmd.Add).items, prods)
        plan("دو تا بهمن کوچک").single().let { assertEquals("1", it.product?.id); assertEquals(2.0, it.qty, 0.0) }
        plan("نیم کیلو قهوه").single().let { assertEquals("2", it.product?.id); assertEquals(500.0, it.qty, 0.0) }
        plan("دویست گرم قهوه").single().let { assertEquals(200.0, it.qty, 0.0) }
        plan("دو قهوه").single().let { assertEquals(2000.0, it.qty, 0.0) }
        plan("سه تا پفک").single().let { assertNull(it.product); assertTrue(it.warning!!.contains("پیدا نشد")) }
    }

    @Test fun bestReadingIsTheOneThatFitsTheShop() {
        val prods = listOf(p("1", "بهمن کوچک"), p("2", "وینستون لایت"), p("3", "مارلبرو قرمز"))
        // the engine's first guess is nonsense for this shop; a later reading names real products
        val best = VoiceAssistant.pickBest(listOf("دو تا بهمن کوچه", "دو تا بهمن کوچک و یک وینستون", "دوتا به من"), prods, methods)
        assertEquals("دو تا بهمن کوچک و یک وینستون", best)
        // a clear command beats a garbled item list
        assertEquals("ثبت کن کارتی", VoiceAssistant.pickBest(listOf("سبد کن کارتی", "ثبت کن کارتی"), prods, methods))
        // with nothing better, the engine's own order is kept
        assertEquals("یک", VoiceAssistant.pickBest(listOf("یک", "یک"), prods, methods))
    }

    @Test fun soundAlikeLettersMatch() {
        val prods = listOf(p("1", "صدف"), p("2", "ذرت مکزیکی"), p("3", "طلایی"))
        fun first(s: String) = VoiceAssistant.lines((VoiceParser.parse(s) as VoiceParser.Cmd.Add).items, prods).single().product?.id
        assertEquals("1", first("دو تا سدف"))
        assertEquals("2", first("یک زرت مکزیکی"))
        assertEquals("3", first("سه تا تلایی"))
    }
}
