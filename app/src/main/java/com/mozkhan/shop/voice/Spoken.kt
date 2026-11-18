package com.mozkhan.shop.voice

import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.util.Currency

/**
 * Text written for the ear (spoken answers). Speech engines read "۱۲۰٬۰۰۰" badly or digit by digit, so every number
 * is written out in Persian words: 120000 -> "صد و بیست هزار". Pure Kotlin, unit-tested.
 */
object Spoken {
    private val ones = listOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val teens = listOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
    private val tens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = listOf("", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")

    private fun small(n: Int): List<String> {
        val out = ArrayList<String>()
        if (n >= 100) out += hundreds[n / 100]
        val r = n % 100
        when {
            r >= 20 -> { out += tens[r / 10]; if (r % 10 > 0) out += ones[r % 10] }
            r >= 10 -> out += teens[r - 10]
            r > 0 -> out += ones[r]
        }
        return out
    }

    fun number(n: Long): String {
        if (n == 0L) return "صفر"
        if (n < 0) return "منفی " + number(-n)
        val parts = ArrayList<String>()
        var rest = n
        for ((v, name) in listOf(1_000_000_000_000L to "هزار میلیارد", 1_000_000_000L to "میلیارد", 1_000_000L to "میلیون", 1_000L to "هزار")) {
            val k = rest / v
            if (k > 0) {
                parts += if (k == 1L && v == 1_000L) name else number(k) + " " + name
                rest %= v
            }
        }
        if (rest > 0) parts += small(rest.toInt()).joinToString(" و ")
        return parts.joinToString(" و ")
    }

    /** "یک و نیم"، "دو ممیز دو پنج" is never needed in a shop: halves are said, other fractions are rounded to tenths. */
    fun decimal(x: Double): String {
        val whole = Math.floor(x).toLong()
        val frac = x - whole
        return when {
            frac < 0.05 -> number(whole)
            Math.abs(frac - 0.5) < 0.05 -> if (whole == 0L) "نیم" else number(whole) + " و نیم"
            else -> number(whole) + " ممیز " + number(Math.round(frac * 10))
        }
    }

    fun money(toman: Long, cur: Currency): String = number(toman * cur.factor) + " " + cur.fa

    /** A stock amount in base units: pieces, grams (kilos when large) or millilitres (litres when large). */
    fun qty(q: Double, unit: Measure): String = when (unit) {
        Measure.PIECE -> decimal(q) + " عدد"
        Measure.WEIGHT -> if (q >= 1000) decimal(q / 1000) + " کیلو" else number(Math.round(q)) + " گرم"
        Measure.VOLUME -> if (q >= 1000) decimal(q / 1000) + " لیتر" else number(Math.round(q)) + " میلی لیتر"
    }
}
