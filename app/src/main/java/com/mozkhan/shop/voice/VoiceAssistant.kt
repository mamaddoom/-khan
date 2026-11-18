package com.mozkhan.shop.voice

import com.mozkhan.shop.data.KIND_INGREDIENT
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.PayMethod
import com.mozkhan.shop.voice.VoiceParser.QUnit

/**
 * Turns a parsed command into a concrete, checkable plan against the shop's products and payment methods.
 * Nothing is changed here: the screen shows the plan and the user confirms it (a misheard word never sells anything).
 */
object VoiceAssistant {

    /** One line to add. [qty] is what addToCart expects: pieces, boxes ([box] = true), or grams / ml for measured goods. */
    data class Line(
        val heard: String,
        val product: ProductWithStock?,
        val choices: List<ProductWithStock>,
        val qty: Double,
        val box: Boolean,
        val warning: String? = null,
    )

    fun lines(items: List<VoiceParser.Item>, products: List<ProductWithStock>): List<Line> {
        val sellable = products.filter { it.kind != KIND_INGREDIENT }
        return items.map { it -> line(it, sellable) }
    }

    fun line(it: VoiceParser.Item, products: List<ProductWithStock>): Line {
        val q = it.query
        // "کد ۱۰۰۲" -> exact product code
        if (q.startsWith("#")) {
            val code = q.drop(1)
            val p = products.firstOrNull { pr -> pr.code == code }
            return resolve(it, p, if (p == null) emptyList() else listOf(p), if (p == null) "کالایی با کد $code نیست" else null)
        }
        val m = VoiceParser.best(q, products, { p -> p.name })
        val sure = VoiceParser.sure(m)
        return resolve(it, sure, m.map { x -> x.item }, if (m.isEmpty()) "کالایی شبیه «$q» پیدا نشد" else null)
    }

    /** Applies the spoken amount and unit to the product's way of selling. */
    fun resolve(it: VoiceParser.Item, p: ProductWithStock?, choices: List<ProductWithStock>, warn: String?): Line {
        val heardQty = it.qty
        if (p == null) return Line(it.query, null, choices, heardQty ?: 1.0, false, warn)
        var box = false
        var w: String? = warn
        val qty: Double = if (p.isMeasured) {
            when (it.unit) {
                QUnit.GRAM -> heardQty ?: 100.0
                QUnit.KILO, QUnit.LITER -> (heardQty ?: 1.0) * 1000
                else -> when {
                    heardQty == null -> { w = "مقدار گفته نشد؛ ۱۰۰ " + (if (p.isWeight) "گرم" else "میلی‌لیتر") + " در نظر گرفته شد"; 100.0 }
                    // "دویست قهوه" = 200 g ; "دو قهوه" = 2 kg
                    heardQty >= 20 -> heardQty
                    else -> heardQty * 1000
                }
            }
        } else {
            when (it.unit) {
                QUnit.BOX -> if (p.hasBox) { box = true; heardQty ?: 1.0 } else { w = "این کالا باکس ندارد؛ " + "تعداد عددی ثبت شد"; (heardQty ?: 1.0) * maxOf(1, p.boxSize) }
                QUnit.GRAM, QUnit.KILO, QUnit.LITER -> { w = "این کالا عددی فروخته می‌شود"; Math.max(1.0, Math.round(heardQty ?: 1.0).toDouble()) }
                else -> heardQty ?: 1.0
            }.let { Math.round(it).toDouble().coerceAtLeast(1.0) }
        }
        return Line(it.query, p, choices, qty, box, w)
    }

    /** Payment method named in "ثبت کن کارت به کارت"; null when nothing was said (the default method is used). */
    fun method(words: String, methods: List<PayMethod>): PayMethod? {
        if (words.isBlank()) return null
        val all = methods.filter { it.enabled }
        // spoken numbers in names: "دستگاه یک" == "دستگاه ۱"
        fun spoken(s: String): String {
            val t = VoiceParser.tokens(s)
            val out = ArrayList<String>()
            var i = 0
            while (i < t.size) {
                val n = VoiceParser.number(t, i)
                if (n != null) { out += n.first.toLong().toString(); i = n.second } else { out += t[i]; i++ }
            }
            return out.joinToString(" ")
        }
        val alias = mapOf("نقدی" to "نقد", "پول" to "نقد", "کارتی" to "کارت", "پوز" to "کارت", "قرض" to "نسیه", "نسیه‌ای" to "نسیه")
        val w = spoken(words)
        val exact = VoiceParser.best(w, all, { spoken(it.name) }, min = 0.6)
        VoiceParser.sure(exact)?.let { return it }
        alias[w]?.let { a -> all.firstOrNull { it.name == a }?.let { return it } }
        return exact.firstOrNull()?.item
    }

    /**
     * The speech engine gives several readings of the same sentence (and the word-list recognizer adds its own).
     * The best one is the reading that makes the most sense for THIS shop: commands with their key words, or items
     * whose names clearly match real products. Ties keep the engine's own order (most likely first).
     */
    fun pickBest(readings: List<String>, products: List<ProductWithStock>, methods: List<PayMethod>): String? {
        val list = readings.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.size <= 1) return list.firstOrNull()
        val sellable = products.filter { it.kind != KIND_INGREDIENT }
        return list.withIndex().maxByOrNull { (i, text) -> fit(text, sellable, methods) - 0.01 * i }?.value
    }

    /** How well one reading fits the shop (higher = better). */
    fun fit(text: String, products: List<ProductWithStock>, methods: List<PayMethod>): Double =
        when (val c = VoiceParser.parse(text)) {
            is VoiceParser.Cmd.Add -> c.items.sumOf { item ->
                val l = line(item, products)
                when {
                    l.product != null && l.warning == null -> 1.0 + VoiceParser.score(item.query, l.product.name) + (if (item.qty != null) 0.2 else 0.0)
                    l.product != null -> 0.8
                    l.choices.isNotEmpty() -> 0.3
                    else -> -0.7
                }
            }
            is VoiceParser.Cmd.Checkout -> 1.6 + (if (c.methodWords.isBlank() || method(c.methodWords, methods) != null) 0.3 else 0.0)
            is VoiceParser.Cmd.Remove, is VoiceParser.Cmd.Discount, VoiceParser.Cmd.Clear -> 1.6
            is VoiceParser.Cmd.Unknown -> -1.0
        }

    /** Words for the speech recognizer's word list: product names, payment methods and all command words. */
    fun hints(products: List<ProductWithStock>, methods: List<PayMethod>): List<String> =
        VoiceParser.vocabulary().toList() + products.map { it.name } + methods.map { it.name }
}
