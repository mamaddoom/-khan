package com.mozkhan.shop.voice

/**
 * Understands short Persian shop commands (pure Kotlin, unit-tested; no Android types).
 *
 *  - Selling:   "دو تا بهمن و یک وینستون"، "بهمن سه تا"، "دویست گرم قهوه"، "یک کیلو و نیم قهوه"، "یه باکس مارلبرو"، "کد هزار و دو"
 *  - Commands:  "حذف بهمن"، "تخفیف ده درصد"، "تخفیف پنجاه هزار"، "ثبت کن کارت به کارت"، "حساب کن نقدی"، "فاکتور رو خالی کن"
 *  - Defining:  "بهمن کوچک قیمت خرید نود هزار فروش صد هزار موجودی پنجاه دسته سیگار"
 *
 * Speech engines write Persian in different ways (Arabic ي / ك, half-spaces, Persian or Latin digits);
 * [norm] folds all of them to one form before anything is compared.
 */
object VoiceParser {

    // ------------------------------------------------------------------ text normalisation
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'ي', 'ى', 'ئ' -> 'ی'
                    'ك' -> 'ک'
                    'ة' -> 'ه'
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ؤ' -> 'و'
                    '‌', '‍', '_', '-', '،', ',', '.', '؛', ';', '!', '?', '؟', ':' -> ' '
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> if (c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ') null else c.lowercaseChar()
                } ?: continue,
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (t in norm(s).split(' ')) {
            if (t.isEmpty()) continue
            // "دوتا" / "سه‌تا" (classifier glued to the number)
            if (t.length > 2 && t.endsWith("تا") && isNumberWord(t.dropLast(2))) { out += t.dropLast(2); out += "تا"; continue }
            out += t
        }
        return out
    }

    // ------------------------------------------------------------------ numbers
    private val ones = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "یکی" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "چار" to 4, "پنج" to 5, "شش" to 6, "شیش" to 6,
        "هفت" to 7, "هشت" to 8, "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14, "چارده" to 14,
        "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16, "هفده" to 17, "هیفده" to 17, "هجده" to 18, "هیجده" to 18, "نوزده" to 19,
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50, "پنجا" to 50, "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90,
        "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400, "پانصد" to 500, "پونصد" to 500, "ششصد" to 600,
        "شیشصد" to 600, "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900,
    )
    private val scales = mapOf("هزار" to 1_000L, "میلیون" to 1_000_000L, "ملیون" to 1_000_000L, "میلیارد" to 1_000_000_000L)

    fun isNumberWord(t: String) = t in ones || t in scales || t == "نیم" || t.toDoubleOrNull() != null

    /** A number starting at [i]: (value, index after it). "صد و پنجاه هزار" = 150000, "یک و نیم" = 1.5, "2.5" = 2.5 */
    fun number(t: List<String>, i: Int): Pair<Double, Int>? {
        if (i >= t.size) return null
        if (t[i] == "نیم") return 0.5 to i + 1
        var total = 0.0
        var cur = 0.0
        var j = i
        var any = false
        while (j < t.size) {
            val w = t[j]
            val digit = w.toDoubleOrNull()
            when {
                digit != null -> { cur += digit; any = true; j++ }
                w in ones -> { cur += ones.getValue(w); any = true; j++ }
                w in scales && any -> { total += (if (cur == 0.0) 1.0 else cur) * scales.getValue(w); cur = 0.0; j++ }
                w in scales && !any -> { total += scales.getValue(w).toDouble(); any = true; j++ } // "هزار تومن"
                // "و" only continues the number when another number word follows
                w == "و" && any && j + 1 < t.size && (t[j + 1] in ones || t[j + 1].toDoubleOrNull() != null) -> j++
                w == "و" && any && j + 1 < t.size && t[j + 1] == "نیم" -> { cur += 0.5; j += 2 }
                else -> break
            }
        }
        return if (any) (total + cur) to j else null
    }

    // ------------------------------------------------------------------ units
    enum class QUnit { PIECE, BOX, GRAM, KILO, LITER }

    private val unitWords = mapOf(
        "تا" to QUnit.PIECE, "عدد" to QUnit.PIECE, "دونه" to QUnit.PIECE, "دانه" to QUnit.PIECE, "پاکت" to QUnit.PIECE, "بسته" to QUnit.PIECE, "نخ" to QUnit.PIECE,
        "باکس" to QUnit.BOX, "کارتن" to QUnit.BOX, "جعبه" to QUnit.BOX,
        "گرم" to QUnit.GRAM, "گرمی" to QUnit.GRAM, "گیرم" to QUnit.GRAM,
        "کیلو" to QUnit.KILO, "کیلوگرم" to QUnit.KILO, "کیلویی" to QUnit.KILO, "کیلوگرمی" to QUnit.KILO,
        "لیتر" to QUnit.LITER, "لیتری" to QUnit.LITER,
    )

    /** words that carry no meaning for the shop ("please add …") */
    private val filler = setOf(
        "لطفا", "لطفاً", "بده", "بدید", "بدین", "بزن", "بزار", "بذار", "اضافه", "کن", "بکن", "کنید", "هم", "رو", "را", "از", "به", "برای",
        "میخوام", "می", "خوام", "خواهم", "یدونه", "دونه", "عدد", "تا", "با", "اون", "این", "آقا", "اقا",
    )

    // ------------------------------------------------------------------ commands
    data class Item(val words: List<String>, val qty: Double?, val unit: QUnit?) {
        val query: String get() = words.joinToString(" ")
    }

    sealed class Cmd {
        data class Add(val items: List<Item>) : Cmd()
        data class Remove(val query: String) : Cmd()
        /** method = the spoken words after "ثبت / حساب" (matched against the payment methods by the caller) */
        data class Checkout(val methodWords: String) : Cmd()
        data class Discount(val percent: Double?, val amount: Double?) : Cmd()
        object Clear : Cmd()
        data class Unknown(val text: String) : Cmd()
    }

    private val checkoutWords = setOf("ثبت", "تسویه", "حساب", "پرداخت", "ببند", "بستن", "تمام", "تموم")
    private val removeWords = setOf("حذف", "بردار", "پاک", "کم")
    private val clearPhrases = listOf("خالی", "همه رو پاک", "همه را پاک", "لغو فاکتور", "فاکتور رو پاک", "فاکتور را پاک", "از اول")

    fun parse(text: String): Cmd {
        val t = tokens(text)
        if (t.isEmpty()) return Cmd.Unknown(text)
        val joined = t.joinToString(" ")

        if (clearPhrases.any { joined.contains(it) }) return Cmd.Clear

        val di = t.indexOf("تخفیف")
        if (di >= 0) {
            val n = number(t, di + 1) ?: t.indices.firstNotNullOfOrNull { k -> if (k != di) number(t, k) else null }
            if (n != null) {
                val pct = t.drop(n.second).take(2).any { it == "درصد" || it == "درصدی" || it == "%" }
                return if (pct) Cmd.Discount(n.first, null) else Cmd.Discount(null, n.first)
            }
        }

        if (t[0] in removeWords || (t.size > 1 && t.last() in setOf("حذف", "بردار") )) {
            val rest = t.filter { it !in removeWords && it !in filler && it != "کن" }
            if (rest.isNotEmpty()) return Cmd.Remove(rest.joinToString(" "))
        }

        val ci = t.indexOfFirst { it in checkoutWords }
        if (ci >= 0 && (ci == 0 || t.size <= 4)) {
            val drop = setOf("کن", "بکن", "فاکتور", "رو", "را", "با", "لطفا", "شه", "بشه", "روش")
            val words = t.filterIndexed { k, w -> k != ci && w !in drop && w !in checkoutWords }
            return Cmd.Checkout(words.joinToString(" "))
        }

        val items = items(t)
        return if (items.isEmpty()) Cmd.Unknown(text) else Cmd.Add(items)
    }

    /** Splits "دو بهمن و سه وینستون" / "بهمن دو تا، وینستون سه تا" into items. */
    fun items(t: List<String>): List<Item> {
        val out = ArrayList<Item>()
        var words = ArrayList<String>()
        var qty: Double? = null
        var unit: QUnit? = null
        fun push() {
            if (words.isNotEmpty()) out += Item(words.toList(), qty, unit)
            words = ArrayList(); qty = null; unit = null
        }
        var i = 0
        while (i < t.size) {
            val w = t[i]
            // "کد هزار و دو" -> the product code as digits
            if (w == "کد") {
                val n = number(t, i + 1)
                if (n != null) {
                    if (words.isNotEmpty()) push()
                    words.add("#" + n.first.toLong()); i = n.second
                    continue
                }
            }
            val n = number(t, i)
            if (n != null) {
                // a number after a finished item starts the next one
                if (words.isNotEmpty() && qty != null) push()
                i = n.second
                var q = n.first
                var u: QUnit? = null
                if (i < t.size && t[i] in unitWords) { u = unitWords.getValue(t[i]); i++ }
                // "یک کیلو و نیم"
                if (i + 1 < t.size && t[i] == "و" && t[i + 1] == "نیم") { q += 0.5; i += 2 }
                qty = q; unit = u ?: unit
                continue
            }
            if (w in unitWords && unitWords.getValue(w) != QUnit.PIECE) { unit = unitWords.getValue(w); i++; continue } // "باکس بهمن"
            if (w == "و") { if (words.isNotEmpty()) push(); i++; continue }
            if (w !in filler) words.add(w)
            i++
        }
        push()
        return out
    }

    // ------------------------------------------------------------------ product definition
    data class Definition(
        val name: String, val buy: Double?, val sell: Double?, val stock: Double?, val minStock: Double?, val category: String?,
        /** prices were said in Rial ("ریال") */
        val rial: Boolean,
    )

    private val keyBuy = setOf("خرید")
    private val keySell = setOf("فروش")
    private val keyStock = setOf("موجودی", "تعداد")
    private val keyMin = setOf("حداقل")
    private val keyCat = setOf("دسته", "دسته‌بندی", "دستهبندی", "گروه")
    private val defFiller = setOf("قیمت", "کالا", "کالای", "محصول", "جدید", "تعریف", "اسم", "نام", "به", "تومان", "تومن", "ریال", "قیمتش", "با")

    fun parseDefinition(text: String): Definition {
        val t = tokens(text)
        val name = ArrayList<String>()
        val cat = ArrayList<String>()
        var buy: Double? = null; var sell: Double? = null; var stock: Double? = null; var min: Double? = null
        var mode = "name"
        var i = 0
        while (i < t.size) {
            val w = t[i]
            when (w) {
                in keyBuy -> { mode = "buy"; i++; continue }
                in keySell -> { mode = "sell"; i++; continue }
                in keyStock -> { mode = "stock"; i++; continue }
                in keyMin -> { mode = "min"; i++; continue }
                in keyCat -> { mode = "cat"; i++; continue }
            }
            if (mode in setOf("buy", "sell", "stock", "min")) {
                val n = number(t, i)
                if (n != null) {
                    when (mode) { "buy" -> buy = n.first; "sell" -> sell = n.first; "stock" -> stock = n.first; else -> min = n.first }
                    i = n.second
                    while (i < t.size && (t[i] in defFiller || t[i] in unitWords)) i++
                    mode = "after"
                    continue
                }
            }
            when (mode) {
                "name" -> if (w !in defFiller) name += w
                "cat" -> if (w !in defFiller) cat += w
            }
            i++
        }
        return Definition(
            name.joinToString(" "), buy, sell, stock, min, cat.joinToString(" ").ifBlank { null },
            rial = t.contains("ریال"),
        )
    }

    /** Every word the selling commands use (numbers, units, commands), for the speech recognizer's word list. */
    fun vocabulary(): Set<String> =
        ones.keys + scales.keys + unitWords.keys + filler + checkoutWords + removeWords +
            setOf("نیم", "و", "تخفیف", "درصد", "درصدی", "فاکتور", "خالی", "کد", "همه", "پاک", "لغو", "اول", "کن", "بکن", "کارت", "نقد", "نقدی", "نسیه")

    // ------------------------------------------------------------------ fuzzy matching
    /**
     * Spelling-independent form for comparing names: letters that sound the same in Persian are folded together
     * (the speech model may write "صدف" as "سدف", "ذرت" as "زرت", "طلایی" as "تلایی").
     */
    fun sound(s: String): String = norm(s).map {
        when (it) {
            'ث', 'ص' -> 'س'
            'ذ', 'ض', 'ظ' -> 'ز'
            'ط' -> 'ت'
            'ح' -> 'ه'
            'غ' -> 'ق'
            'ع', 'ء' -> 'ا'
            else -> it
        }
    }.joinToString("").replace("اا", "ا")

    /** 0..1 similarity of two short strings (Levenshtein on normalised text without spaces). */
    fun similarity(a: String, b: String): Double {
        val x = sound(a).replace(" ", "")
        val y = sound(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val d = levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /**
     * How well a spoken [query] names a product called [name]: the whole name, the name contained in the query (or the
     * reverse), and word-by-word similarity (spoken "بهمن کوچیک" vs "بهمن کوچک") all count.
     */
    fun score(query: String, name: String): Double {
        val q = sound(query); val n = sound(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val qs = q.replace(" ", ""); val ns = n.replace(" ", "")
        val whole = similarity(qs, ns)
        val contain = if (ns.contains(qs) || qs.contains(ns)) 0.75 + 0.2 * minOf(qs.length, ns.length) / maxOf(qs.length, ns.length) else 0.0
        val qw = q.split(' '); val nw = n.split(' ')
        val perWord = qw.map { a -> nw.maxOf { b -> similarity(a, b) } }.average()
        val cover = nw.map { b -> qw.maxOf { a -> similarity(a, b) } }.average()
        // saying only part of a long name ("مارلبرو" for "مارلبرو قرمز") is normal: what was said weighs more than what was left out
        val words = 0.75 * perWord + 0.25 * cover
        return maxOf(whole, contain, words)
    }

    data class Match<T>(val item: T, val score: Double)

    /** Best matches (score desc) above [min]. */
    fun <T> best(query: String, all: List<T>, name: (T) -> String, min: Double = 0.55, limit: Int = 4): List<Match<T>> =
        all.asSequence().map { Match(it, score(query, name(it))) }.filter { it.score >= min }
            .sortedByDescending { it.score }.take(limit).toList()

    /** A clear winner: high enough and not too close to the second best. */
    fun <T> sure(m: List<Match<T>>): T? {
        val a = m.firstOrNull() ?: return null
        val b = m.getOrNull(1)
        return if (a.score >= 0.72 && (b == null || a.score - b.score >= 0.08)) a.item else null
    }
}
