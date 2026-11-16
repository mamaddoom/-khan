package com.mozkhan.shop.util

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

enum class Currency(val fa: String, val factor: Int) {
    TOMAN("تومان", 1), RIAL("ریال", 10)
}

object Fmt {
    private val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') fa[c - '0'] else c)
        return sb.toString()
    }

    /** Converts Persian / Arabic-Indic digits and separators to ASCII so user input always parses. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            in '۰'..'۹' -> sb.append('0' + (c - '۰'))
            in '٠'..'٩' -> sb.append('0' + (c - '٠'))
            '٫', '،' -> sb.append('.')
            '٬', ',' -> {}
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun group(v: Long): String {
        val neg = v < 0
        val s = abs(v).toString()
        val sb = StringBuilder()
        for ((i, c) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append('٬')
            sb.append(c)
        }
        return digits((if (neg) "-" else "") + sb)
    }

    /** value in TOMAN -> "۱۲٬۰۰۰ تومان" (or in Rial) */
    fun money(toman: Long, cur: Currency, withUnit: Boolean = true): String {
        val shown = group(toman * cur.factor)
        return if (withUnit) "$shown ${cur.fa}" else shown
    }

    /** Short form for charts / tiles: ۱٫۲ م  (million) */
    fun moneyShort(toman: Long, cur: Currency): String {
        val v = toman.toDouble() * cur.factor
        val a = abs(v)
        val s = when {
            a >= 1_000_000_000 -> String.format(Locale.US, "%.1f میلیارد", v / 1_000_000_000)
            a >= 1_000_000 -> String.format(Locale.US, "%.1f میلیون", v / 1_000_000)
            a >= 1_000 -> String.format(Locale.US, "%.0f هزار", v / 1_000)
            else -> String.format(Locale.US, "%.0f", v)
        }
        return digits(s.replace(".0 ", " "))
    }

    /** Parse what the user typed in the DISPLAY currency into TOMAN (at most 15 digits, so it can never overflow). */
    fun parseMoney(text: String, cur: Currency): Long {
        val n = normalize(text).filter { it.isDigit() }.take(MONEY_DIGITS)
        if (n.isEmpty()) return 0
        val v = n.toLongOrNull() ?: return 0
        return v / cur.factor
    }

    const val MONEY_DIGITS = 15

    /**
     * Text shown in a money field. What the user typed is kept as long as it still means the current value:
     * in RIAL mode "1" or "15" round down to 0 / 1 toman, and re-formatting from toman would erase the digit
     * being typed (the field could never get past one digit). A value set from outside (chips, restock) wins.
     */
    fun moneyInput(typed: String, toman: Long, cur: Currency): String {
        val d = normalize(typed).filter { it.isDigit() }.take(MONEY_DIGITS)
        if (d.isNotEmpty() && parseMoney(d, cur) == toman) return group(d.toLong())
        return if (toman == 0L) "" else group(toman * cur.factor)
    }

    fun inputMoney(toman: Long, cur: Currency): String =
        if (toman == 0L) "" else (toman * cur.factor).toString()

    fun parseDouble(text: String): Double = normalize(text).toDoubleOrNull() ?: 0.0

    fun percent(p: Double): String = digits(String.format(Locale.US, "%.1f%%", p)).replace(".0%", "%")

    /** Quantity of a base unit: pieces, grams (kg when large) or millilitres (litres when large). */
    fun qtyOf(q: Double, unit: com.mozkhan.shop.data.Measure): String = when (unit) {
        com.mozkhan.shop.data.Measure.PIECE -> qty(q, false)
        com.mozkhan.shop.data.Measure.WEIGHT -> qty(q, true)
        com.mozkhan.shop.data.Measure.VOLUME ->
            if (q >= 1000) digits(String.format(Locale.US, "%.2f", q / 1000).trimEnd('0').trimEnd('.')) + " لیتر"
            else digits(Math.round(q).toString()) + " میلی‌لیتر"
    }

    fun unitLabel(unit: com.mozkhan.shop.data.Measure): String = when (unit) {
        com.mozkhan.shop.data.Measure.PIECE -> "عدد"
        com.mozkhan.shop.data.Measure.WEIGHT -> "گرم"
        com.mozkhan.shop.data.Measure.VOLUME -> "میلی‌لیتر"
    }

    fun qty(q: Double, weight: Boolean): String {
        if (weight) {
            return if (q >= 1000) digits(String.format(Locale.US, "%.2f", q / 1000).trimEnd('0').trimEnd('.')) + " کیلو"
            else digits(Math.round(q).toString()) + " گرم"
        }
        return digits(if (q == Math.floor(q)) q.toLong().toString() else String.format(Locale.US, "%.1f", q))
    }
}

/** Rounding of computed prices (percentage changes, profit chips) to steps that make sense for the amount. */
object Prices {
    fun round(v: Double): Long {
        if (v.isNaN() || v <= 0.0) return 0
        val step = when {
            v >= 10_000 -> 100L
            v >= 1_000 -> 10L
            else -> 1L
        }
        return (Math.round(v / step) * step).coerceAtLeast(if (v >= 0.5) 1L else 0L)
    }
}

/**
 * The camera sees a held barcode many times per second. A code is accepted only when it differs from the last
 * one or has been out of sight for [windowMs]; every sighting of the same code extends the window, so holding a
 * pack in front of the camera never adds it twice.
 */
class ScanDebouncer(private val windowMs: Long = 1500) {
    private var last = ""
    private var lastTs = 0L

    @Synchronized
    fun accept(code: String, now: Long): Boolean {
        val repeat = code == last && now - lastTs < windowMs
        last = code; lastTs = now
        return !repeat
    }

    /** e.g. after a sheet closes: the code still in view is treated as already handled */
    @Synchronized
    fun hold(now: Long) { lastTs = now }

    val lastSeen: Long @Synchronized get() = lastTs
}

/** Jalali (Persian / Shamsi) calendar helpers, dependency-free. */
object Jalali {
    val months = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )
    private val weekdays = mapOf(
        Calendar.SATURDAY to "شنبه", Calendar.SUNDAY to "یکشنبه", Calendar.MONDAY to "دوشنبه",
        Calendar.TUESDAY to "سه‌شنبه", Calendar.WEDNESDAY to "چهارشنبه", Calendar.THURSDAY to "پنجشنبه",
        Calendar.FRIDAY to "جمعه",
    )

    data class Date(val y: Int, val m: Int, val d: Int)

    fun fromGregorian(gy: Int, gm: Int, gd: Int): Date {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31; jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30
        }
        return Date(jy, jm, jd)
    }

    fun toGregorian(jy0: Int, jm: Int, jd: Int): Triple<Int, Int, Int> {
        val jy = jy0 + 1595
        var days = -355668 + 365 * jy + (jy / 33) * 8 + ((jy % 33) + 3) / 4 + jd +
            if (jm < 7) (jm - 1) * 31 else (jm - 7) * 30 + 186
        var gy = 400 * (days / 146097)
        days %= 146097
        if (days > 36524) {
            days--
            gy += 100 * (days / 36524)
            days %= 36524
            if (days >= 365) days++
        }
        gy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            gy += (days - 1) / 365
            days = (days - 1) % 365
        }
        var gd = days + 1
        val leap = (gy % 4 == 0 && gy % 100 != 0) || gy % 400 == 0
        val sal = intArrayOf(0, 31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 0
        while (gm < 13 && gd > sal[gm]) {
            gd -= sal[gm]; gm++
        }
        return Triple(gy, gm, gd)
    }

    fun of(ts: Long): Date {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 30 Esfand exists only in leap years: it round-trips through the Gregorian calendar. */
    fun isLeap(jy: Int): Boolean {
        val g = toGregorian(jy, 12, 30)
        return fromGregorian(g.first, g.second, g.third) == Date(jy, 12, 30)
    }

    fun monthLength(jy: Int, jm: Int) = when {
        jm <= 6 -> 31
        jm <= 11 -> 30
        else -> if (isLeap(jy)) 30 else 29
    }

    fun startOfDay(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfJalali(jy: Int, jm: Int, jd: Int): Long {
        val (gy, gm, gd) = toGregorian(jy, jm, jd)
        return Calendar.getInstance().apply {
            clear(); set(gy, gm - 1, gd, 0, 0, 0)
        }.timeInMillis
    }

    fun date(ts: Long): String {
        val d = of(ts)
        return Fmt.digits("%04d/%02d/%02d".format(Locale.US, d.y, d.m, d.d))
    }

    fun time(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return Fmt.digits("%02d:%02d".format(Locale.US, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    fun dateTime(ts: Long) = "${date(ts)}  ${time(ts)}"

    fun weekday(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return weekdays[c.get(Calendar.DAY_OF_WEEK)] ?: ""
    }

    fun longDate(ts: Long): String {
        val d = of(ts)
        return Fmt.digits("${weekday(ts)} ${d.d} ${months[d.m - 1]} ${d.y}")
    }
}

/** Reporting windows. */
enum class Period(val fa: String) { DAY("روز"), WEEK("هفته"), MONTH("ماه"), YEAR("سال"), CUSTOM("بازه دلخواه") }

data class DateRange(val from: Long, val to: Long, val label: String)

object Ranges {
    /** offset 0 = current, -1 = previous, ... */
    fun of(p: Period, offset: Int, now: Long = System.currentTimeMillis()): DateRange {
        val today = Jalali.startOfDay(now)
        val cal = Calendar.getInstance()
        when (p) {
            Period.DAY -> {
                cal.timeInMillis = today; cal.add(Calendar.DAY_OF_MONTH, offset)
                val from = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val label = when (offset) { 0 -> "امروز"; -1 -> "دیروز"; else -> Jalali.longDate(from) }
                return DateRange(from, cal.timeInMillis - 1, label)
            }
            Period.WEEK -> {
                cal.timeInMillis = today
                val back = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SATURDAY + 7) % 7
                cal.add(Calendar.DAY_OF_MONTH, -back + offset * 7)
                val from = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 7)
                val last = cal.timeInMillis - 1
                val label = if (offset == 0) "این هفته" else "${Jalali.date(from)} تا ${Jalali.date(last)}"
                return DateRange(from, last, label)
            }
            Period.MONTH, Period.CUSTOM -> {
                val t = Jalali.of(now)
                var m = t.m + offset
                var y = t.y
                while (m < 1) { m += 12; y-- }
                while (m > 12) { m -= 12; y++ }
                val from = Jalali.startOfJalali(y, m, 1)
                val len = Jalali.monthLength(y, m)
                val next = Jalali.startOfJalali(y, m, 1).let {
                    cal.timeInMillis = it; cal.add(Calendar.DAY_OF_MONTH, len); cal.timeInMillis
                }
                return DateRange(from, next - 1, Fmt.digits("${Jalali.months[m - 1]} $y"))
            }
            Period.YEAR -> {
                val y = Jalali.of(now).y + offset
                val from = Jalali.startOfJalali(y, 1, 1)
                val to = Jalali.startOfJalali(y + 1, 1, 1) - 1
                return DateRange(from, to, Fmt.digits("سال $y"))
            }
        }
    }
}
