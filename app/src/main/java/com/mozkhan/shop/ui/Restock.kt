package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

/**
 * New purchase of an existing product: how much came in, what it cost this time, and (optionally) new sell prices.
 * The purchase is recorded as a document, so it shows up in the purchases report.
 */
@Composable
fun RestockDialog(
    p: ProductWithStock, cur: Currency, askSell: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (qtyBase: Double, unitCost: Double, newBuy: Long, newSell: Long?, newBoxBuy: Long?, newBoxSell: Long?, supplier: String) -> Unit,
) {
    var byBox by remember { mutableStateOf(false) }
    var qtyText by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(p.buyPrice) }
    var sell by remember { mutableStateOf(p.sellPrice) }
    var boxSell by remember { mutableStateOf(p.boxSellPrice) }
    var supplier by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) } // a double tap must not record the purchase twice

    val size = p.boxSize.coerceAtLeast(1)
    val boxed = byBox && p.hasBox
    val qtyN = Fmt.parseDouble(qtyText)
    val qtyBase = if (boxed) qtyN * size else if (p.isMeasured) qtyN * 1000 else qtyN
    val unitCost = if (boxed) price.toDouble() / size else if (p.isMeasured) price / 1000.0 else price.toDouble()
    val total = Math.round(qtyBase * unitCost)
    val unitName = when {
        boxed -> "باکس"
        p.isWeight -> "کیلوگرم"
        p.isVolume -> "لیتر"
        else -> "عدد"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("خرید جدید / افزایش موجودی") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "کد " + Fmt.digits(p.code) + (p.barcode?.let { " • بارکد " + Fmt.digits(it) } ?: "") +
                        "\nموجودی فعلی: " + Fmt.qtyOf(p.stock, p.unit) +
                        "\nخرید فعلی: " + Fmt.money(p.buyPrice, cur) + " • فروش فعلی: " + Fmt.money(p.sellPrice, cur),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.hasBox && p.unit == Measure.PIECE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!byBox, { byBox = false; price = p.buyPrice }, label = { Text("خرید پاکت / عدد") })
                    FilterChip(byBox, { byBox = true; price = if (p.boxBuyPrice > 0) p.boxBuyPrice else p.buyPrice * size }, label = { Text("خرید باکس (${Fmt.digits(size.toString())} عدد)") })
                }
                NumberField("مقدار خریداری‌شده", qtyText, { qtyText = it }, Modifier.fillMaxWidth(), suffix = unitName)
                MoneyField(
                    "قیمت خرید جدید" + (if (boxed) " (هر باکس)" else if (p.isMeasured) " (هر " + (if (p.isWeight) "کیلو" else "لیتر") + ")" else " (هر عدد)"),
                    price, cur, { price = it }, Modifier.fillMaxWidth(),
                )
                if (askSell) {
                    MoneyField("قیمت فروش" + (if (p.isMeasured) " (هر " + (if (p.isWeight) "کیلو" else "لیتر") + ")" else " (هر عدد)"), sell, cur, { sell = it }, Modifier.fillMaxWidth())
                    if (p.hasBox && p.unit == Measure.PIECE) MoneyField("قیمت فروش باکس", boxSell, cur, { boxSell = it }, Modifier.fillMaxWidth())
                }
                AppField(supplier, { supplier = it }, "تأمین‌کننده / فروشنده (اختیاری)", Modifier.fillMaxWidth())
                if (askSell && price > 0) {
                    val newBuy = if (boxed) price.toDouble() / size else price.toDouble()
                    if (sell in 1 until Math.round(newBuy)) Text(
                        "قیمت فروش کمتر از قیمت خرید جدید است؛ با هر فروش ضرر می‌کنید.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                if (qtyBase > 0) Text(
                    "مبلغ این خرید: " + Fmt.money(total, cur) + "\nموجودی پس از خرید: " + Fmt.qtyOf(p.stock + qtyBase, p.unit),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = qtyBase > 0 && price > 0 && !sent, onClick = {
                if (sent) return@TextButton
                sent = true
                val newBuy = if (boxed) Math.round(price.toDouble() / size) else price
                onSubmit(
                    qtyBase, unitCost, newBuy,
                    if (askSell) sell else null,
                    if (boxed) price else null,
                    if (askSell && p.hasBox) boxSell else null,
                    supplier,
                )
                onDismiss()
            }) { Text("ثبت خرید") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
