package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

@Composable
fun ProductsScreen(vm: MainVm, onEdit: (String) -> Unit, onNew: () -> Unit, canManage: Boolean = true) {
    val cur by vm.currency.collectAsState()
    val products by vm.products.collectAsState()
    val cats by vm.categories.collectAsState()
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    var onlyLow by remember { mutableStateOf(false) }
    var onlyWeight by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var priceDialog by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    val q = Fmt.normalize(query).trim()
    val list = products.filter {
        (cat == null || it.category == cat) && (!onlyLow || it.low) && (!onlyWeight || it.isWeight) &&
            (q.isEmpty() || it.name.contains(q, true) || it.barcode?.contains(q) == true || it.boxBarcode?.contains(q) == true || it.code.contains(q, true))
    }
    val usedCats = products.map { it.category }.distinct()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selecting = false; selected = emptySet() }) { Icon(Icons.Filled.Close, "لغو") }
                    Text(Fmt.digits("${selected.size} کالا انتخاب شد"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { selected = list.map { it.id }.toSet() }) { Text("همه") }
                    TextButton(enabled = selected.isNotEmpty(), onClick = { priceDialog = true }) { Text("تغییر قیمت") }
                }
            } else {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("کالاها", style = MaterialTheme.typography.headlineSmall)
                        Text(Fmt.digits("${products.size} کالا"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "بیشتر") }
                        DropdownMenu(menu, { menu = false }) {
                            if (canManage) DropdownMenuItem(text = { Text("انتخاب چند کالا و تغییر قیمت") }, onClick = { menu = false; selecting = true })
                            if (canManage) DropdownMenuItem(text = { Text("تغییر قیمت گروهی (همه / یک دسته)") }, onClick = { menu = false; selected = emptySet(); priceDialog = true })
                            DropdownMenuItem(text = { Text(if (onlyLow) "نمایش همه" else "فقط کمبود موجودی") }, onClick = { menu = false; onlyLow = !onlyLow })
                        }
                    }
                }
            }
            AppField(query, { query = it }, "جستجو", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                leading = { Icon(Icons.Filled.Search, null) },
                trailing = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "پاک کردن") } })
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(cat == null && !onlyWeight, { cat = null; onlyWeight = false }, label = { Text("همه") }) }
                item { FilterChip(onlyWeight, { onlyWeight = !onlyWeight }, label = { Text("وزنی") }) }
                items(cats.filter { it in usedCats }) { c -> FilterChip(cat == c, { cat = if (cat == c) null else c }, label = { Text(c) }) }
            }
            if (list.isEmpty()) {
                EmptyState(
                    Icons.Filled.Inventory2,
                    if (products.isEmpty()) "هنوز کالایی تعریف نکرده‌اید" else "نتیجه‌ای پیدا نشد",
                    "با اسکن بارکد یا عکس کالا، خیلی سریع کالای جدید اضافه کنید.",
                    Modifier.weight(1f),
                )
            } else LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    AppCard(onClick = { if (selecting) selected = if (p.id in selected) selected - p.id else selected + p.id else onEdit(p.id) }) {
                        Row(Modifier.fillMaxWidth().padding(start = if (selecting) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selecting) Checkbox(p.id in selected, { selected = if (it) selected + p.id else selected - p.id })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    p.category + (p.barcode?.let { " • " + Fmt.digits(it) } ?: " • بدون بارکد") + if (p.hasBox) " • باکس" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (p.trackStock) Pill(
                                        "موجودی " + Fmt.qty(p.stock, p.isWeight),
                                        if (p.low || p.stock <= 0) MaterialTheme.extra.warn else MaterialTheme.colorScheme.primary,
                                    ) else Pill("بدون موجودی", MaterialTheme.colorScheme.outline)
                                    if (p.sellPrice > 0 && p.buyPrice > 0) {
                                        val m = (p.sellPrice - p.buyPrice) * 100.0 / p.buyPrice
                                        Pill("سود " + Fmt.percent(m), if (m >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss)
                                    }
                                }
                                if (p.isWeight) Text(
                                    "خرید " + Fmt.money(p.buyPrice, cur, false) + " • فروش " + Fmt.money(p.sellPrice, cur, false) + " (هر کیلو)",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.money(p.sellPrice, cur, false), style = MaterialTheme.typography.titleSmall)
                                Text(cur.fa + if (p.isWeight) " /کیلو" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!selecting) IconButton(onClick = { vm.favorite(p.id, !p.favorite) }) {
                                Icon(
                                    if (p.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "علاقه‌مندی",
                                    tint = if (p.favorite) MaterialTheme.extra.warn else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!selecting && canManage) ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("کالای جدید") },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }

    if (priceDialog) PriceChangeDialog(
        cats = usedCats, cur = cur, selectedCount = selected.size,
        targets = { c -> products.filter { if (selected.isNotEmpty()) it.id in selected else (c == null || it.category == c) } },
        onDismiss = { priceDialog = false },
    ) { category, percent, amount, field ->
        vm.bulkChange(if (selected.isNotEmpty()) selected else null, if (selected.isEmpty()) category else null, percent, amount, field)
        selecting = false; selected = emptySet()
    }
}

/** Raise / lower prices by a percentage or a fixed amount for the selected products (or a whole category / everything). */
@Composable
private fun PriceChangeDialog(
    cats: List<String>, cur: Currency, selectedCount: Int,
    targets: (String?) -> List<com.mozkhan.shop.data.ProductWithStock>,
    onDismiss: () -> Unit, onApply: (String?, Double?, Long?, String) -> Unit,
) {
    var byPercent by remember { mutableStateOf(true) }
    var neg by remember { mutableStateOf(false) }
    var pct by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(0L) }
    var cat by remember { mutableStateOf<String?>(null) }
    var field by remember { mutableStateOf("sell") }
    val valid = if (byPercent) Fmt.parseDouble(pct) > 0 else amount > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedCount > 0) "تغییر قیمت ${Fmt.digits(selectedCount.toString())} کالای انتخابی" else "تغییر قیمت گروهی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(field == "sell", { field = "sell" }, label = { Text("قیمت فروش") })
                    FilterChip(field == "buy", { field = "buy" }, label = { Text("قیمت خرید") })
                    FilterChip(field == "both", { field = "both" }, label = { Text("هر دو") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(byPercent, { byPercent = true }, label = { Text("درصدی") })
                    FilterChip(!byPercent, { byPercent = false }, label = { Text("مبلغ ثابت") })
                    FilterChip(!neg, { neg = false }, label = { Text("افزایش") })
                    FilterChip(neg, { neg = true }, label = { Text("کاهش") })
                }
                if (byPercent) NumberField("درصد تغییر", pct, { pct = it }, Modifier.fillMaxWidth(), suffix = "٪")
                else MoneyField("مبلغ تغییر (هر واحد؛ برای وزنی هر کیلو)", amount, cur, { amount = it }, Modifier.fillMaxWidth())
                if (selectedCount == 0) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(cat == null, { cat = null }, label = { Text("همه کالاها") }) }
                    items(cats) { c -> FilterChip(cat == c, { cat = c }, label = { Text(c) }) }
                }
                // preview: how many goods change, and a few of them before -> after
                val sign = if (neg) -1 else 1
                val pctV = if (byPercent) sign * Fmt.parseDouble(pct) else null
                val amtV = if (!byPercent) sign * amount else null
                val affected = targets(cat)
                if (valid) {
                    val shownField = if (field == "buy") "خرید" else "فروش"
                    Text(
                        Fmt.digits("${affected.size} کالا تغییر می‌کند") + " (نمونه، قیمت $shownField):",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                    affected.take(5).forEach { p ->
                        val old = if (field == "buy") p.buyPrice else p.sellPrice
                        val new = com.mozkhan.shop.util.Prices.change(old, pctV, amtV)
                        Text(
                            p.name + ": " + Fmt.money(old, cur, false) + " ← " + Fmt.money(new, cur),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (affected.size > 5) Text(Fmt.digits("و ${affected.size - 5} کالای دیگر…"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (byPercent) "قیمت‌های بالای ۱۰ هزار تومان به نزدیک‌ترین صد، و قیمت‌های کمتر به نزدیک‌ترین ده یا یک گرد می‌شوند. قیمت باکس هم همین تغییر را می‌گیرد."
                    else "برای قیمت باکس، مبلغ در تعداد داخل باکس ضرب می‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val sign = if (neg) -1 else 1
                if (byPercent) onApply(cat, sign * Fmt.parseDouble(pct), null, field) else onApply(cat, null, sign * amount, field)
                onDismiss()
            }) { Text("اعمال") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}
