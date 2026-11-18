package com.mozkhan.shop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(vm: MainVm, onGo: (String) -> Unit, onNew: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val shop by vm.prefs.shopName.collectAsState()
    val t by vm.todayTotals.collectAsState()
    val exp by vm.todayExpenses.collectAsState()
    val top by vm.todayTop.collectAsState()
    val recent by vm.recent.collectAsState()
    var detail by remember { mutableStateOf<com.mozkhan.shop.data.Sale?>(null) }
    val products by vm.products.collectAsState()
    val low = remember(products) { products.filter { it.low } }
    // "today" must roll over at midnight even when the shop leaves the app open on this screen
    LaunchedEffect(Unit) {
        while (true) { vm.refreshToday(); delay(60_000) }
    }

    val profit = t.revenue - t.cost
    val net = profit - exp
    val now = System.currentTimeMillis()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                Text(shop, style = MaterialTheme.typography.headlineSmall)
                Text(Jalali.longDate(now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val ex = MaterialTheme.extra
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(ex.gradA, ex.gradB)))
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("فروش امروز", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                    Text(Fmt.money(t.revenue, cur), color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroStat("سود ناخالص", Fmt.moneyShort(profit, cur), Modifier.weight(1f))
                        HeroStat("سود خالص", Fmt.moneyShort(net, cur), Modifier.weight(1f))
                        HeroStat("تعداد فاکتور", Fmt.digits(t.count.toString()), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("فروش جدید", Icons.Filled.PointOfSale, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { onGo("sell") }
                QuickAction("کالای جدید", Icons.Filled.AddBox, MaterialTheme.colorScheme.secondary, Modifier.weight(1f)) { onNew() }
                QuickAction("گزارش‌ها", Icons.Filled.QueryStats, MaterialTheme.extra.profit, Modifier.weight(1f)) { onGo("reports") }
            }
        }
        if (low.isNotEmpty()) item {
            // opens the inventory report, which lists every low item with a purchase suggestion
            AppCard(onClick = { onGo("inventory") }, container = MaterialTheme.extra.warn.copy(alpha = 0.10f)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Warning, MaterialTheme.extra.warn)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("کمبود موجودی", style = MaterialTheme.typography.titleSmall)
                        Text(
                            low.take(3).joinToString("، ") { it.name } + if (low.size > 3) " و ${Fmt.digits((low.size - 3).toString())} مورد دیگر" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (top.isNotEmpty()) {
            item { SectionTitle("پرفروش‌های امروز") }
            item {
                AppCard { Column(Modifier.padding(vertical = 6.dp)) {
                    top.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.digits((i + 1).toString()), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                            Text(p.name, Modifier.weight(1f), maxLines = 1)
                            Text(Fmt.qty(p.qty, p.isWeight), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
            }
        }
        item { SectionTitle("آخرین فروش‌ها") }
        if (recent.isEmpty()) item {
            EmptyState(Icons.Filled.ReceiptLong, "هنوز فروشی ثبت نشده", "از بخش «فروش»، بارکد کالا را اسکن کنید و اولین فاکتور را بزنید.")
        } else items(recent, key = { it.id }) { s ->
            AppCard(onClick = { detail = s }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.ReceiptLong, MaterialTheme.colorScheme.primary, size = 40)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(Fmt.money(s.total, cur), style = MaterialTheme.typography.titleSmall)
                        Text(Jalali.dateTime(s.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.voided) Pill("ابطال", MaterialTheme.colorScheme.error)
                    else Pill("+" + Fmt.moneyShort(s.total - s.cost, cur), MaterialTheme.extra.profit)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    detail?.let { s -> SaleDetailDialog(vm, s, cur) { detail = null } }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)).padding(10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, tint)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
