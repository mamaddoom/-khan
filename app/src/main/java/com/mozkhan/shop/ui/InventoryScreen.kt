package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

@Composable
fun InventoryScreen(vm: MainVm, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val cur by vm.currency.collectAsState()
    val d by vm.inventory.collectAsState()
    var query by remember { mutableStateOf("") }
    val q = Fmt.normalize(query).trim()
    val shown = d.list.filter { q.isEmpty() || it.name.contains(q, true) || it.barcode?.contains(q) == true || it.code.contains(q, true) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Text("گزارش انبار", style = MaterialTheme.typography.headlineSmall)
            }
        }
        item { FilterBar(vm) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("ارزش موجودی (خرید)", Fmt.money(d.stockValue, cur), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    Kpi("ارزش فروش موجودی", Fmt.money(d.sellValue, cur), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("سود بالقوهٔ انبار", Fmt.money(d.sellValue - d.stockValue, cur), MaterialTheme.extra.profit, Modifier.weight(1f))
                    Kpi("تعداد کالا", Fmt.digits(d.productCount.toString()), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("رو به اتمام", Fmt.digits(d.lowCount.toString()), MaterialTheme.extra.warn, Modifier.weight(1f))
                    Kpi("تمام‌شده", Fmt.digits(d.outCount.toString()), MaterialTheme.extra.loss, Modifier.weight(1f))
                }
            }
        }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ورود و خروج کالا (به ارزش خرید)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Legend(MaterialTheme.extra.profit, "ورود"); Legend(MaterialTheme.extra.warn, "خروج")
                    }
                    TwoBarChart(d.labels, d.inSeries, d.outSeries, MaterialTheme.extra.profit, MaterialTheme.extra.warn, cur)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kpi("خرید در بازه", Fmt.moneyShort(d.purchased, cur), MaterialTheme.extra.profit, Modifier.weight(1f))
                        Kpi("خروج با فروش", Fmt.moneyShort(d.soldOut, cur), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        Kpi("خروج بدون فروش", Fmt.moneyShort(d.exited, cur), MaterialTheme.extra.warn, Modifier.weight(1f))
                    }
                }
            }
        }
        if (d.byCat.isNotEmpty()) item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ارزش موجودی به تفکیک دسته", style = MaterialTheme.typography.titleSmall)
                    HBars(d.byCat, MaterialTheme.colorScheme.primary, cur)
                }
            }
        }
        if (d.low.isNotEmpty()) {
            item { SectionTitle("کمبود و اتمام موجودی") }
            item {
                Text(
                    "پیشنهاد خرید = فروش ۱۴ روز بر اساس سرعت فروش این بازه (حداقل دو برابر حد هشدار)، منهای موجودی فعلی.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(d.low, key = { "l" + it.id }) { p -> StockRow(p, cur, onEdit, d.reorder[p.id]) }
        }
        if (d.dead.isNotEmpty()) {
            item { SectionTitle("کالاهای راکد (در این بازه نفروخته‌اند)") }
            items(d.dead, key = { "d" + it.id }) { p -> StockRow(p, cur, onEdit) }
        }
        item { SectionTitle("همهٔ کالاها") }
        item {
            AppField(query, { query = it }, "جستجو در کالاها", Modifier.fillMaxWidth(),
                leading = { Icon(Icons.Filled.Search, null) },
                trailing = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "پاک کردن") } })
        }
        if (shown.isEmpty()) item { EmptyState(Icons.Filled.Inventory2, "کالایی نیست", "با این فیلتر کالایی پیدا نشد.") }
        items(shown.take(120), key = { "a" + it.id }) { p -> StockRow(p, cur, onEdit) }
    }
}

@Composable
private fun StockRow(p: ProductWithStock, cur: Currency, onEdit: (String) -> Unit, reorder: Double? = null) {
    AppCard(onClick = { onEdit(p.id) }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    p.category + " • خرید " + Fmt.money(p.buyPrice, cur, false) + " • فروش " + Fmt.money(p.sellPrice, cur, false) + if (p.isWeight) " (هر کیلو)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (reorder != null && reorder > 0) Text(
                    "پیشنهاد خرید: " + Fmt.qtyOf(reorder, p.unit) + (if (p.hasBox) " (≈ " + Fmt.digits(Math.ceil(reorder / p.boxSize).toLong().toString()) + " باکس)" else "") +
                        " • حدود " + Fmt.money(Math.round(reorder * p.baseCost), cur),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(
                    "موجودی " + Fmt.qtyOf(p.stock, p.unit),
                    if (p.stock <= 0 || p.low) MaterialTheme.extra.warn else MaterialTheme.colorScheme.primary,
                )
                Text(Fmt.moneyShort(p.stockValue, cur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
