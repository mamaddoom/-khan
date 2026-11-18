package com.mozkhan.shop.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * A way a sale is paid. Built-ins: CASH (always on), CARD, CREDIT (on account, needs a customer).
 * Methods the shop adds ("کارت به کارت", "دستگاه ۱"…) are stored on the sale as "P:<name>", so every phone and every
 * report can show the name even if the method was later renamed or deleted on this phone.
 */
data class PayMethod(val id: String, val name: String, val enabled: Boolean = true) {
    val builtIn: Boolean get() = id == PayMethods.CASH || id == PayMethods.CARD || id == PayMethods.CREDIT
}

object PayMethods {
    const val CASH = "CASH"
    const val CARD = "CARD"
    const val CREDIT = "CREDIT"
    const val PREFIX = "P:"

    val defaults = listOf(PayMethod(CASH, "نقد"), PayMethod(CARD, "کارت"), PayMethod(CREDIT, "نسیه"))

    fun customId(name: String) = PREFIX + name.trim()

    /** Name of a stored payment value (works for methods this phone does not know). */
    fun label(id: String, list: List<PayMethod> = defaults): String =
        list.firstOrNull { it.id == id }?.name ?: when {
            id == CARD -> "کارت"
            id == CREDIT -> "نسیه"
            id.startsWith(PREFIX) -> id.removePrefix(PREFIX)
            else -> "نقد"
        }

    fun toJson(list: List<PayMethod>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("on", it.enabled)) }
    }.toString()

    /** Tolerant: a missing / broken value gives the defaults, and the three built-ins always exist. */
    fun fromJson(s: String?): List<PayMethod> {
        val parsed = runCatching {
            val a = JSONArray(s ?: return defaults)
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { o ->
                    val id = o.optString("id"); val name = o.optString("name").trim()
                    if (id.isBlank() || name.isBlank()) null else PayMethod(id, name, o.optBoolean("on", true))
                }
            }
        }.getOrNull() ?: return defaults
        val out = parsed.distinctBy { it.id }.toMutableList()
        defaults.forEach { d -> if (out.none { it.id == d.id }) out += d }
        return out.map { if (it.id == CASH) it.copy(enabled = true) else it }
    }

    /** Validation for add / rename: a non-empty name that no other method uses. */
    fun nameError(name: String, list: List<PayMethod>, exceptId: String? = null): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "نام را وارد کنید"
            n.length > 30 -> "نام کوتاه‌تری انتخاب کنید"
            list.any { it.id != exceptId && it.name.trim() == n } -> "این نام قبلاً هست"
            else -> null
        }
    }
}
