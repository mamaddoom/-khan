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
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FilterChip
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
import com.mozkhan.shop.util.BuyTip
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

/** What to buy more of (selling fast, running out) and what to buy less of (not selling, too much stock). */
@Composable
fun BuyAdviceScreen(vm: MainVm, onBack: () -> Unit, onBuy: (productId: String) -> Unit) {
    val cur by vm.currency.collectAsState()
    val a by vm.buyAdvice.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val list = if (tab == 0) a.more else a.less

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column {
                    Text("پیشنهاد خرید", style = MaterialTheme.typography.headlineSmall)
                    Text("بر اساس فروش ${Fmt.digits(a.days.toString())} روز گذشته", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("بیشتر بخرید", Fmt.digits(a.more.size.toString()) + " کالا", MaterialTheme.extra.profit, Modifier.weight(1f))
                Kpi("کمتر بخرید", Fmt.digits(a.less.size.toString()) + " کالا • " + Fmt.moneyShort(a.less.sumOf { it.p.stockValue }, cur), MaterialTheme.extra.warn, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("بیشتر بخرید") })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("کمتر بخرید / نخرید") })
            }
        }
        if (tab == 0 && a.more.isNotEmpty()) item {
            val cost = a.more.sumOf { Math.round(it.qty * it.p.baseCost) }
            Text("هزینهٔ تقریبی همهٔ خریدهای پیشنهادی: " + Fmt.money(cost, cur), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (list.isEmpty()) item {
            EmptyState(
                Icons.Filled.ShoppingCart,
                if (tab == 0) "فعلاً کمبودی نیست" else "کالای راکدی نیست",
                if (tab == 0) "کالاهایی که زود تمام می‌شوند اینجا می‌آیند." else "کالاهایی که فروش ندارند یا موجودی زیادی دارند اینجا می‌آیند.",
            )
        }
        items(list, key = { "t$tab" + it.p.id }) { t -> TipRow(t, cur, more = tab == 0) { onBuy(t.p.id) } }
    }
}

@Composable
private fun TipRow(t: BuyTip, cur: Currency, more: Boolean, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (more) Pill("خرید " + Fmt.qtyOf(t.qty, t.p.unit), MaterialTheme.extra.profit)
                else Pill(Fmt.moneyShort(t.p.stockValue, cur), MaterialTheme.extra.warn)
            }
            Text(t.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "موجودی " + Fmt.qtyOf(t.p.stock, t.p.unit) + if (more) " • هزینهٔ تقریبی " + Fmt.money(Math.round(t.qty * t.p.baseCost), cur) else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
