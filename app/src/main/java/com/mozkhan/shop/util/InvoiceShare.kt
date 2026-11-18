package com.mozkhan.shop.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.mozkhan.shop.R
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A sale invoice to share: as A5 pages (PNG images or one PDF) drawn with the app's own Vazir font, or as plain text
 * for messengers. Everything is drawn on a 420 x 595 point A5 page; images are the same page at ~210 dpi.
 */
object InvoiceShare {
    data class Invoice(
        val shop: String,
        val sale: Sale,
        val items: List<SaleItem>,
        val customer: String?,
        val payment: String,
        val cur: Currency,
    ) {
        /** short, readable invoice number from the sale id */
        val number: String get() = sale.id.replace("-", "").take(6).uppercase()
    }

    const val PAGE_W = 420f
    const val PAGE_H = 595f
    private const val IMG_W = 1240 // A5 at ~210 dpi
    private const val M = 24f
    private const val ROW = 22f
    private const val TABLE_TOP = 150f
    private const val FIRST_ROWS_LAST = 12 // rows that still fit above the totals on the last page
    private const val ROWS_FULL = 17

    // ---------------------------------------------------------------- text
    fun text(inv: Invoice): String {
        val c = inv.cur
        val sb = StringBuilder()
        sb.append("🧾 فاکتور فروش — ").append(inv.shop).append('\n')
        sb.append("شماره: ").append(inv.number).append('\n')
        sb.append("تاریخ: ").append(Jalali.dateTime(inv.sale.ts)).append('\n')
        inv.customer?.let { sb.append("مشتری: ").append(it).append('\n') }
        if (inv.sale.voided) sb.append("⚠️ این فاکتور باطل شده است\n")
        sb.append("━━━━━━━━━━━━━━\n")
        inv.items.forEachIndexed { i, it ->
            sb.append(Fmt.digits("${i + 1}) ")).append(it.name).append('\n')
            sb.append("    ").append(qty(it)).append(" × ").append(Fmt.money(unitPrice(it), c, false))
                .append(if (it.isWeight) " (هر کیلو)" else "").append(" = ").append(Fmt.money(it.lineTotal, c, false)).append('\n')
        }
        sb.append("━━━━━━━━━━━━━━\n")
        if (inv.sale.discount > 0) {
            sb.append("جمع اقلام: ").append(Fmt.money(inv.sale.subtotal, c)).append('\n')
            sb.append("تخفیف: ").append(Fmt.money(inv.sale.discount, c)).append('\n')
        }
        sb.append("💰 مبلغ قابل پرداخت: ").append(Fmt.money(inv.sale.total, c)).append('\n')
        sb.append("پرداخت: ").append(inv.payment).append('\n')
        if (inv.sale.deviceName.isNotBlank()) sb.append("فروشنده: ").append(inv.sale.deviceName).append('\n')
        sb.append("\nاز خرید شما سپاسگزاریم 🌷")
        return sb.toString()
    }

    private fun qty(it: SaleItem) = Fmt.qty(it.qty, it.isWeight)
    /** price of one piece, or of one KILO for weighed goods */
    private fun unitPrice(it: SaleItem): Long = Math.round(if (it.isWeight) it.unitPrice * 1000 else it.unitPrice)

    // ---------------------------------------------------------------- pages
    /** Items split over A5 pages: full pages first; the last page keeps room for the totals. */
    fun paginate(n: Int): List<IntRange> {
        if (n <= FIRST_ROWS_LAST) return listOf(0 until n)
        // fewest pages that fit, rows spread evenly (no page with a single lonely row)
        val k = 1 + (n - FIRST_ROWS_LAST + ROWS_FULL - 1) / ROWS_FULL
        val last = minOf(FIRST_ROWS_LAST, (n + k - 1) / k)
        val rest = n - last
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 0 until k - 1) {
            val take = (rest - start + (k - 1 - i) - 1) / (k - 1 - i)
            out += start until start + take
            start += take
        }
        out += start until n
        return out
    }

    private class Paints(ctx: Context) {
        val regular: Typeface = ResourcesCompat.getFont(ctx, R.font.vazir_regular) ?: Typeface.DEFAULT
        val medium: Typeface = ResourcesCompat.getFont(ctx, R.font.vazir_medium) ?: Typeface.DEFAULT
        val bold: Typeface = ResourcesCompat.getFont(ctx, R.font.vazir_bold) ?: Typeface.DEFAULT_BOLD
        fun text(tf: Typeface, size: Float, color: Int, align: Paint.Align = Paint.Align.RIGHT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; this.color = color; textAlign = align }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    }

    private const val INDIGO = 0xFF4F46E5.toInt()
    private const val INDIGO_DARK = 0xFF3730A3.toInt()
    private const val INK = 0xFF0F172A.toInt()
    private const val MUTED = 0xFF5B6478.toInt()
    private const val LINE = 0xFFE2E6F0.toInt()
    private const val ZEBRA = 0xFFF6F7FB.toInt()
    private const val TINT = 0xFFE0E7FF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Draws page [page] (of [pages]) on a 420 x 595 canvas. */
    private fun draw(cv: Canvas, p: Paints, inv: Invoice, rows: IntRange, page: Int, pages: Int) {
        val c = inv.cur
        val right = PAGE_W - M
        cv.drawColor(WHITE)

        // header band
        p.fill.shader = LinearGradient(0f, 0f, PAGE_W, 96f, INDIGO_DARK, INDIGO, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, PAGE_W, 96f, p.fill)
        p.fill.shader = null
        cv.drawText(fit(inv.shop, p.text(p.bold, 21f, WHITE), 230f), right, 44f, p.text(p.bold, 21f, WHITE))
        cv.drawText("فاکتور فروش", right, 68f, p.text(p.medium, 11f, 0xDDFFFFFF.toInt()))
        val leftW = p.text(p.regular, 9.5f, WHITE, Paint.Align.LEFT)
        cv.drawText("شمارهٔ فاکتور: " + inv.number, M, 40f, leftW)
        cv.drawText(Jalali.dateTime(inv.sale.ts), M, 58f, leftW)
        if (pages > 1) cv.drawText(Fmt.digits("صفحهٔ ${page + 1} از $pages"), M, 76f, leftW)

        // info pills (right to left)
        var x = right
        fun pill(label: String) {
            val tp = p.text(p.medium, 9f, INK)
            val w = tp.measureText(label) + 20f
            p.fill.color = TINT
            cv.drawRoundRect(RectF(x - w, 106f, x, 128f), 11f, 11f, p.fill)
            cv.drawText(label, x - 10f, 121f, tp)
            x -= w + 8f
        }
        inv.customer?.let { pill("مشتری: $it") }
        pill("پرداخت: " + inv.payment)
        if (inv.sale.deviceName.isNotBlank()) pill("فروشنده: " + inv.sale.deviceName)

        // table: [name | qty | unit price | amount] from right to left
        val colName = right - 8f
        val colQty = 200f
        val colUnit = 132f
        val colAmt = M + 8f
        p.fill.color = INDIGO
        cv.drawRoundRect(RectF(M, TABLE_TOP - 18f, right, TABLE_TOP + 6f), 8f, 8f, p.fill)
        val head = p.text(p.bold, 9.5f, WHITE)
        cv.drawText("کالا", colName, TABLE_TOP - 2f, head)
        cv.drawText("تعداد", colQty, TABLE_TOP - 2f, p.text(p.bold, 9.5f, WHITE, Paint.Align.CENTER))
        cv.drawText("فی (" + c.fa + ")", colUnit, TABLE_TOP - 2f, p.text(p.bold, 9.5f, WHITE, Paint.Align.CENTER))
        cv.drawText("مبلغ", colAmt, TABLE_TOP - 2f, p.text(p.bold, 9.5f, WHITE, Paint.Align.LEFT))

        var y = TABLE_TOP + 6f
        val nameP = p.text(p.medium, 9.5f, INK)
        val numC = p.text(p.regular, 9.5f, INK, Paint.Align.CENTER)
        val numL = p.text(p.medium, 9.5f, INK, Paint.Align.LEFT)
        val idxP = p.text(p.regular, 8f, MUTED)
        for (i in rows) {
            val it = inv.items[i]
            if ((i - rows.first) % 2 == 1) { p.fill.color = ZEBRA; cv.drawRect(M, y, right, y + ROW, p.fill) }
            val base = y + 15f
            cv.drawText(Fmt.digits("${i + 1}"), colName, base, idxP)
            cv.drawText(fit(it.name, nameP, colName - 14f - (colQty + 30f)), colName - 14f, base, nameP)
            cv.drawText(qty(it), colQty, base, numC)
            cv.drawText((if (it.isWeight) "کیلویی " else "") + Fmt.money(unitPrice(it), c, false), colUnit, base, numC)
            cv.drawText(Fmt.money(it.lineTotal, c, false), colAmt, base, numL)
            y += ROW
        }
        p.fill.color = LINE
        cv.drawRect(M, y, right, y + 1f, p.fill)

        val last = page == pages - 1
        if (last) {
            // totals card on the left
            val top = y + 14f
            val boxL = M
            val boxR = M + 210f
            val lines = ArrayList<Pair<String, String>>()
            if (inv.sale.discount > 0) {
                lines += "جمع اقلام" to Fmt.money(inv.sale.subtotal, c)
                lines += "تخفیف" to Fmt.money(inv.sale.discount, c)
            }
            val h = 18f * lines.size + 44f
            p.fill.color = TINT
            cv.drawRoundRect(RectF(boxL, top, boxR, top + h), 12f, 12f, p.fill)
            var ly = top + 18f
            val lab = p.text(p.regular, 9.5f, MUTED)
            val valP = p.text(p.medium, 9.5f, INK, Paint.Align.LEFT)
            for ((a, b) in lines) { cv.drawText(a, boxR - 12f, ly, lab); cv.drawText(b, boxL + 12f, ly, valP); ly += 18f }
            cv.drawText("مبلغ قابل پرداخت", boxR - 12f, ly + 10f, p.text(p.bold, 10.5f, INDIGO_DARK))
            cv.drawText(Fmt.money(inv.sale.total, c), boxL + 12f, ly + 10f, p.text(p.bold, 13f, INDIGO_DARK, Paint.Align.LEFT))
            // item count on the right
            cv.drawText(Fmt.digits("${inv.items.size} قلم کالا"), right, top + 18f, p.text(p.regular, 9.5f, MUTED))
        }

        // footer
        p.fill.color = LINE
        cv.drawRect(M, PAGE_H - 46f, right, PAGE_H - 45f, p.fill)
        cv.drawText("از خرید شما سپاسگزاریم", PAGE_W / 2, PAGE_H - 26f, p.text(p.medium, 10f, INK, Paint.Align.CENTER))
        cv.drawText("MozKhan", M, PAGE_H - 26f, p.text(p.regular, 7.5f, MUTED, Paint.Align.LEFT))

        if (inv.sale.voided) {
            cv.save()
            cv.rotate(-28f, PAGE_W / 2, PAGE_H / 2)
            cv.drawText("باطل شده", PAGE_W / 2, PAGE_H / 2, p.text(p.bold, 64f, 0x33DC2626, Paint.Align.CENTER))
            cv.restore()
        }
    }

    private fun fit(s: String, tp: TextPaint, w: Float): String = TextUtils.ellipsize(s, tp, w, TextUtils.TruncateAt.END).toString()

    /** Page [page] as a bitmap [width] pixels wide (A5 proportions). */
    fun bitmap(ctx: Context, inv: Invoice, page: Int = 0, width: Int = IMG_W): Bitmap {
        val ranges = paginate(inv.items.size)
        val scale = width / PAGE_W
        val bmp = Bitmap.createBitmap(width, Math.round(PAGE_H * scale), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.scale(scale, scale)
        draw(cv, Paints(ctx), inv, ranges[page.coerceIn(ranges.indices)], page, ranges.size)
        return bmp
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }

    suspend fun images(ctx: Context, inv: Invoice): List<File> = withContext(Dispatchers.Default) {
        val d = dir(ctx)
        paginate(inv.items.size).indices.map { i ->
            val b = bitmap(ctx, inv, i)
            val f = File(d, "invoice-${inv.number}" + (if (i > 0) "-${i + 1}" else "") + ".png")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
            f
        }
    }

    suspend fun pdf(ctx: Context, inv: Invoice): File = withContext(Dispatchers.Default) {
        val ranges = paginate(inv.items.size)
        val doc = PdfDocument()
        val p = Paints(ctx)
        ranges.forEachIndexed { i, r ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W.toInt(), PAGE_H.toInt(), i + 1).create())
            draw(page.canvas, p, inv, r, i, ranges.size)
            doc.finishPage(page)
        }
        val f = File(dir(ctx), "invoice-${inv.number}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        f
    }

    // ---------------------------------------------------------------- sharing
    fun shareFiles(ctx: Context, files: List<File>, mime: String, title: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = mime
        send.clipData = ClipData.newRawUri(title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "اشتراک فاکتور").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
