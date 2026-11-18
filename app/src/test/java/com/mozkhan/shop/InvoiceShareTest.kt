package com.mozkhan.shop

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import com.mozkhan.shop.data.exitValue
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.InvoiceShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Invoice sharing (text, A5 pages) and the value of stock exits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvoiceShareTest {
    private fun item(i: Int, name: String, qty: Double, price: Double, weight: Boolean = false) =
        SaleItem("i$i", "s1", "p$i", name, qty, weight, price, price * 0.8)

    private fun invoice(n: Int, voided: Boolean = false): InvoiceShare.Invoice {
        val base = listOf(
            item(0, "بهمن کوچک", 2.0, 100_000.0), item(1, "مارلبرو قرمز", 1.0, 185_000.0),
            item(2, "قهوه اسپرسو", 250.0, 1500.0, weight = true), item(3, "فندک کلیپر", 3.0, 45_000.0),
        )
        val items = (0 until n).map { base[it % base.size].copy(id = "i$it") }
        val sub = items.sumOf { it.lineTotal }
        val sale = Sale("a1b2c3d4-0000", 1_731_900_000_000, sub, 20_000, sub - 20_000, 0, "CREDIT", voided = voided, customerId = "c1", deviceName = "صندوق ۱")
        return InvoiceShare.Invoice("دخانیات و کافه موزخان", sale, items, "آقای رضایی", "نسیه", Currency.TOMAN)
    }

    @Test fun textInvoice() {
        val t = InvoiceShare.text(invoice(3))
        assertTrue(t.contains("دخانیات و کافه موزخان"))
        assertTrue(t.contains("مشتری: آقای رضایی"))
        assertTrue(t.contains("مارلبرو قرمز"))
        assertTrue(t.contains("تخفیف"))
        assertTrue(t.contains("مبلغ قابل پرداخت"))
    }

    @Test fun pagination() {
        assertEquals(listOf(0 until 5), InvoiceShare.paginate(5))
        assertEquals(1, InvoiceShare.paginate(12).size)
        val p = InvoiceShare.paginate(40)
        assertEquals(40, p.sumOf { it.count() })
        assertTrue(p.last().count() <= 12)
        assertTrue(p.all { it.count() <= 17 })
        assertEquals(listOf(10, 10, 10), InvoiceShare.paginate(30).map { it.count() })
        assertTrue(InvoiceShare.paginate(13).all { it.count() >= 6 })
        assertEquals((0 until 40).toList(), p.flatMap { it.toList() })
    }

    @Test fun a5Image() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val b = InvoiceShare.bitmap(ctx, invoice(6))
        assertEquals(1240, b.width)
        assertEquals(Math.round(1240 * 595f / 420f), b.height)
        // a look at the result while developing: build/invoice-sample.png
        runCatching { File("build/invoice-sample.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        runCatching {
            val v = InvoiceShare.bitmap(ctx, invoice(30, voided = true), 1)
            File("build/invoice-sample-2.png").outputStream().use { v.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun exitValueByBasis() {
        val p = ProductWithStock("p", "x", null, "c", Measure.PIECE, 90_000, 100_000, 0.0, false, false, 10.0)
        val lines = listOf(p to 3.0)
        assertEquals(270_000L, exitValue(lines, "BUY", 0))
        assertEquals(300_000L, exitValue(lines, "SELL", 0))
        assertEquals(50_000L, exitValue(lines, "MANUAL", 50_000))
    }
}
