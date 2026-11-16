package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali

private val kinds = listOf("DAMAGE" to "ضایعات / خرابی", "PERSONAL" to "مصرف شخصی", "GIFT" to "هدیه / نمونه", "TRANSFER" to "انتقال به شعبهٔ دیگر", "OTHER" to "سایر")
private fun kindFa(k: String) = kinds.firstOrNull { it.first == k }?.second ?: k

/** خروج از انبار بدون فروش (بدون سود) — creates a signed document and lowers the stock. */
@Composable
fun ExitScreen(vm: MainVm, onBack: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val products by vm.products.collectAsState()
    val allDocs by vm.docs.collectAsState()
    val docs = allDocs.filter { it.kind != "PURCHASE" && !it.voided }
    var kind by remember { mutableStateOf("DAMAGE") }
    var note by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Pair<ProductWithStock, Double>>() }
    var open by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val q = Fmt.normalize(query).trim()
    val results = if (q.isEmpty()) emptyList() else products.filter { it.trackStock && (it.name.contains(q, true) || it.barcode?.contains(q) == true || it.code.contains(q, true)) }.take(6)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column {
                    Text("خروج از انبار", style = MaterialTheme.typography.headlineSmall)
                    Text("بدون فروش و بدون سود؛ فقط ثبت سند", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("سند جدید", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(kinds) { (k, v) -> FilterChip(kind == k, { kind = k }, label = { Text(v) }) }
                    }
                    AppField(note, { note = it }, "توضیحات سند (اختیاری)", Modifier.fillMaxWidth())
                    AppField(query, { query = it }, "افزودن کالا (نام یا بارکد)", Modifier.fillMaxWidth(), leading = { Icon(Icons.Filled.Search, null) })
                    results.forEach { p ->
                        ProductRow(p, cur) {
                            if (lines.none { it.first.id == p.id }) lines.add(p to if (p.isMeasured) 100.0 else 1.0)
                            query = ""
                        }
                    }
                    lines.forEachIndexed { i, (p, q0) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("موجودی " + Fmt.qtyOf(p.stock, p.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (p.isMeasured) {
                                NumberField(Fmt.unitLabel(p.unit), (if (q0 <= 0.0) "" else if (q0 == Math.floor(q0)) q0.toLong().toString() else q0.toString()), { v -> lines[i] = p to Fmt.parseDouble(v) }, Modifier.weight(1f), suffix = Fmt.unitLabel(p.unit))
                            } else QtyStepper(q0, { v -> lines[i] = p to v.coerceAtLeast(1.0) })
                            IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.DeleteOutline, "حذف", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (lines.isNotEmpty()) {
                        val cost = lines.sumOf { (p, q1) -> Math.round(q1 * p.baseCost) }
                        SumRow("ارزش به قیمت خرید", Fmt.money(cost, cur))
                        val over = lines.filter { (p, q1) -> q1 > p.stock }
                        if (over.isNotEmpty()) Text(
                            "مقدار خروج از موجودی ثبت‌شده بیشتر است: " + over.joinToString("، ") { it.first.name },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                        )
                    }
                    Button(
                        enabled = !saving && lines.isNotEmpty() && lines.all { it.second > 0 },
                        onClick = {
                            if (saving) return@Button // a double tap must not take the goods out twice
                            saving = true
                            vm.createExit(kind, note, lines.toList()) { lines.clear(); note = "" }
                                .invokeOnCompletion { saving = false }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ثبت سند و کسر از انبار") }
                }
            }
        }
        item { SectionTitle("اسناد ثبت‌شده") }
        if (docs.isEmpty()) item { EmptyState(Icons.Filled.Outbox, "سندی ثبت نشده", "ضایعات، مصرف شخصی و هدیه را اینجا ثبت کنید تا موجودی درست بماند.") }
        items(docs, key = { it.id }) { d -> DocRow(vm, d, cur, open == d.id) { open = if (open == d.id) null else d.id } }
    }
}

@Composable
private fun DocRow(vm: MainVm, d: StockDoc, cur: Currency, expanded: Boolean, toggle: () -> Unit) {
    var lines by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    LaunchedEffect(d.id, expanded) { if (expanded) lines = vm.repo.exitLines(d.id) }
    AppCard(onClick = toggle) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kindFa(d.kind), style = MaterialTheme.typography.titleSmall)
                    Text(Jalali.dateTime(d.ts) + if (d.note.isNotBlank()) " • ${d.note}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.money(d.cost, cur), color = MaterialTheme.extra.warn, style = MaterialTheme.typography.titleSmall)
            }
            if (expanded) lines.forEach { (n, q) -> Text("• $n × " + Fmt.digits(if (q == Math.floor(q)) q.toLong().toString() else q.toString()), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
