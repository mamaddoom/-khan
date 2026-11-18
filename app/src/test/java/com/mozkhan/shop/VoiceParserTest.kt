package com.mozkhan.shop

import com.mozkhan.shop.voice.VoiceParser
import com.mozkhan.shop.voice.VoiceParser.Cmd
import com.mozkhan.shop.voice.VoiceParser.QUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceParserTest {
    private fun num(s: String) = VoiceParser.number(VoiceParser.tokens(s), 0)?.first

    @Test fun numbers() {
        assertEquals(150_000.0, num("صد و پنجاه هزار")!!, 0.0)
        assertEquals(1_200_000.0, num("یک میلیون و دویست هزار")!!, 0.0)
        assertEquals(1.5, num("یک و نیم")!!, 0.0)
        assertEquals(25.0, num("بیست و پنج")!!, 0.0)
        assertEquals(90_000.0, num("نود هزار")!!, 0.0)
        assertEquals(250.0, num("۲۵۰")!!, 0.0)
        assertEquals(1000.0, num("هزار")!!, 0.0)
        assertNull(num("بهمن"))
    }

    private fun add(s: String): List<VoiceParser.Item> = (VoiceParser.parse(s) as Cmd.Add).items

    @Test fun severalItemsWithQuantities() {
        val l = add("دو تا بهمن و یک وینستون")
        assertEquals(listOf("بهمن", "وینستون"), l.map { it.query })
        assertEquals(listOf(2.0, 1.0), l.map { it.qty })
    }

    @Test fun quantityAfterTheName() {
        val l = add("بهمن سه تا و وینستون دو تا")
        assertEquals(listOf("بهمن" to 3.0, "وینستون" to 2.0), l.map { it.query to it.qty })
    }

    @Test fun numbersWithoutAndStartNewItems() {
        val l = add("دو بهمن سه وینستون")
        assertEquals(listOf("بهمن" to 2.0, "وینستون" to 3.0), l.map { it.query to it.qty })
    }

    @Test fun weightsBoxesAndGluedClassifier() {
        add("دویست گرم قهوه").single().let { assertEquals(200.0, it.qty!!, 0.0); assertEquals(QUnit.GRAM, it.unit); assertEquals("قهوه", it.query) }
        add("یک کیلو و نیم قهوه").single().let { assertEquals(1.5, it.qty!!, 0.0); assertEquals(QUnit.KILO, it.unit) }
        add("یه باکس مارلبرو").single().let { assertEquals(1.0, it.qty!!, 0.0); assertEquals(QUnit.BOX, it.unit); assertEquals("مارلبرو", it.query) }
        add("دوتا بهمن").single().let { assertEquals(2.0, it.qty!!, 0.0); assertEquals("بهمن", it.query) }
    }

    @Test fun arabicLettersAndPersianDigitsAreFolded() {
        add("۲ تا وينستون لايت").single().let { assertEquals(2.0, it.qty!!, 0.0); assertEquals("وینستون لایت", it.query) }
    }

    @Test fun productCode() {
        assertEquals("#1002", add("کد هزار و دو").single().query)
    }

    @Test fun commands() {
        assertEquals(Cmd.Discount(10.0, null), VoiceParser.parse("تخفیف ده درصد"))
        assertEquals(Cmd.Discount(null, 50_000.0), VoiceParser.parse("تخفیف پنجاه هزار"))
        assertEquals(Cmd.Checkout("کارت به کارت"), VoiceParser.parse("ثبت کن کارت به کارت"))
        assertEquals(Cmd.Checkout(""), VoiceParser.parse("حساب کن"))
        assertEquals(Cmd.Remove("بهمن"), VoiceParser.parse("حذف بهمن"))
        assertEquals(Cmd.Clear, VoiceParser.parse("فاکتور رو خالی کن"))
        assertTrue(VoiceParser.parse("") is Cmd.Unknown)
    }

    @Test fun definition() {
        val d = VoiceParser.parseDefinition("بهمن کوچک قیمت خرید نود هزار فروش صد هزار تومان موجودی پنجاه دسته سیگار")
        assertEquals("بهمن کوچک", d.name)
        assertEquals(90_000.0, d.buy!!, 0.0)
        assertEquals(100_000.0, d.sell!!, 0.0)
        assertEquals(50.0, d.stock!!, 0.0)
        assertEquals("سیگار", d.category)
        assertEquals(false, d.rial)
    }

    @Test fun fuzzyMatching() {
        val names = listOf("بهمن کوچک", "بهمن بزرگ", "وینستون لایت", "مارلبرو قرمز", "قهوه اسپرسو")
        assertEquals("بهمن کوچک", VoiceParser.sure(VoiceParser.best("بهمن کوچیک", names, { it })))
        assertEquals("وینستون لایت", VoiceParser.sure(VoiceParser.best("وینستون", names, { it })))
        assertEquals("مارلبرو قرمز", VoiceParser.sure(VoiceParser.best("مالبرو", names, { it })))
        // "بهمن" alone fits two products equally: no automatic choice, both offered
        val m = VoiceParser.best("بهمن", names, { it })
        assertNull(VoiceParser.sure(m))
        assertTrue(m.take(2).map { it.item }.containsAll(listOf("بهمن کوچک", "بهمن بزرگ")))
    }
}
