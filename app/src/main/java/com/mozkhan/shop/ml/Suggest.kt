package com.mozkhan.shop.ml

/**
 * Turns what the camera "reads" (latin text on the pack + image labels) into Persian product names,
 * e.g.  "WINSTON" + "LIGHTS"  ->  «سیگار وینستون لایت».
 * ML Kit cannot read Persian script, so known brands/variants are mapped through a dictionary.
 */
object Suggest {
    private val brands = linkedMapOf(
        "winston" to "وینستون", "bahman" to "بهمن", "camel" to "کمل", "marlboro" to "مارلبرو", "montana" to "مونتانا",
        "kent" to "کنت", "esse" to "اسه", "monte carlo" to "مونته‌کارلو", "montecarlo" to "مونته‌کارلو", "parliament" to "پارلمنت",
        "vitoria" to "ویکتوریا", "l&m" to "ال‌اندام", "lm" to "ال‌اندام", "oris" to "اوریس", "kosar" to "کوثر", "farvardin" to "فروردین",
        "sobranie" to "ساربنی", "dunhill" to "دانهیل", "pall mall" to "پال‌مال", "lucky strike" to "لاکی استرایک", "benson" to "بنسون",
        "gauloises" to "گلوواز", "mond" to "موند", "zippo" to "زیپو", "clipper" to "کلیپر", "bic" to "بیک", "tokai" to "توکای",
        "al fakher" to "الفاخر", "alfakher" to "الفاخر", "nakhla" to "نخله", "adalya" to "آدالیا", "starbuzz" to "استارباز",
        "tangiers" to "تانگیرز", "serbetli" to "شربتلی", "fumari" to "فوماری", "nescafe" to "نسکافه", "lavazza" to "لاوازا",
        "illy" to "ایلی", "davidoff" to "دیویدوف", "gold" to "طلایی", "sheikh" to "شیخ", "sheikha" to "شیخ",
    )

    private val variants = linkedMapOf(
        "lights" to "لایت", "light" to "لایت", "red" to "قرمز", "blue" to "آبی", "silver" to "نقره‌ای", "gold" to "طلایی",
        "menthol" to "منتول", "mint" to "نعنا", "slim" to "اسلیم", "nano" to "نانو", "black" to "مشکی", "white" to "سفید",
        "super" to "سوپر", "classic" to "کلاسیک", "king" to "کینگ", "ultra" to "اولترا", "double" to "دوبل", "apple" to "سیب",
        "mixed" to "میکس", "fruit" to "میوه", "grape" to "انگور", "lemon" to "لیمو", "cola" to "کولا", "orange" to "پرتقال",
    )

    /** word/phrase hints -> category */
    private val catWords = linkedMapOf(
        "cigarette" to "سیگار", "cigarettes" to "سیگار", "filter" to "سیگار", "class a" to "سیگار", "tobacco" to "تنباکو",
        "molasses" to "تنباکو", "hookah" to "تنباکو", "shisha" to "تنباکو", "lighter" to "فندک", "zippo" to "فندک",
        "clipper" to "فندک", "coffee" to "قهوه", "nescafe" to "قهوه", "charcoal" to "ذغال", "coal" to "ذغال", "pipe" to "پیپ و لوازم",
    )

    /** ML Kit image-label -> category */
    private val labelCats = linkedMapOf(
        "lighter" to "فندک", "cigarette" to "سیگار", "tobacco" to "تنباکو", "coffee" to "قهوه", "coffee cup" to "قهوه",
        "charcoal" to "ذغال", "tobacco products" to "سیگار", "smoking pipe" to "پیپ و لوازم", "hookah" to "تنباکو",
    )

    /** [tokens] are building blocks (category, brand, variants, size) the user can tap to compose the name. */
    data class Result(val names: List<String>, val category: String?, val tokens: List<String> = emptyList())

    private val sizeRegexes = listOf(
        Regex("(\\d{1,4})\\s?(?:g|gr|gram|grams)\\b") to { n: String -> "${n} گرمی" },
        Regex("(\\d{1,3})\\s?(?:cigarettes|sticks|pcs|pieces)\\b") to { n: String -> "${n} نخ" },
        Regex("(\\d{1,4})\\s?(?:ml|cl)\\b") to { n: String -> "${n} میلی‌لیتر" },
        Regex("(\\d{1,3})\\s?(?:kg)\\b") to { n: String -> "${n} کیلویی" },
    )

    private fun toFaDigits(s: String) = s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

    fun from(lines: List<String>, labels: List<String>): Result {
        val text = lines.joinToString(" ").lowercase()
        val padded = " " + text.replace(Regex("[^a-z0-9& ]"), " ").replace(Regex("\\s+"), " ") + " "

        val brand = brands.entries.firstOrNull { padded.contains(" ${it.key} ") }?.value
        val vars = variants.entries.filter { padded.contains(" ${it.key} ") }.map { it.value }.distinct().take(2)
        var category = catWords.entries.firstOrNull { padded.contains(" ${it.key} ") }?.value
        if (category == null) category = labels.map { it.lowercase() }.firstNotNullOfOrNull { labelCats[it] }
        if (category == null && brand != null && brand in listOf("زیپو", "کلیپر", "بیک", "توکای")) category = "فندک"

        val size = sizeRegexes.firstNotNullOfOrNull { (re, fmt) -> re.find(text)?.let { fmt(toFaDigits(it.groupValues[1])) } }

        val tokens = listOfNotNull(category, brand) + vars + listOfNotNull(size)
        val names = mutableListOf<String>()
        if (brand != null) {
            names += listOfNotNull(category, brand, vars.takeIf { it.isNotEmpty() }?.joinToString(" "), size).joinToString(" ")
            names += listOfNotNull(brand, vars.takeIf { it.isNotEmpty() }?.joinToString(" ")).joinToString(" ")
        } else if (category != null) {
            names += listOfNotNull(category, size).joinToString(" ")
        }
        names += lines.map { it.trim() }.filter { it.length in 3..24 && it.any { c -> c.isLetter() } }.take(3)
        return Result(names.distinct().take(6), category, tokens.distinct())
    }
}