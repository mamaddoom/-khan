package com.mozkhan.shop.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextDecoration
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
    val docs = allDocs.filter { it.kind != "PURCHASE" }
    var kind by remember { mutableStateOf("DAMAGE") }
    var note by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<Pair<ProductWithStock, Double>>() }
    var open by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var counted by remember { mutableStateOf(false) }
    var basis by remember { mutableStateOf("BUY") }
    var manual by remember { mutableStateOf(0L) }
    val q =Fmt.normalize(query).trim()
    val results = if (q.isEmpty()) emptyList() else products.filter { it.trackStock && (it.name.contains(q, true) || it.barcode?.contains(q) == true || it.code.contains(q, true)) }.take(6)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column {
                    Text("خروج از انبار", style = MaterialTheme.typography.headlineSmall)
                    Text("فقط کالا از انبار کم می‌شود؛ پول کم نمی‌شود مگر تیک «محاسبهٔ مبلغ» را بزنید", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        SumRow("ارزش به قیمت خرید", Fmt.money(com.mozkhan.shop.data.exitValue(lines, "BUY", 0), cur))
                        SumRow("ارزش به قیمت فروش", Fmt.money(com.mozkhan.shop.data.exitValue(lines, "SELL", 0), cur))
                        val over = lines.filter { (p, q1) -> q1 > p.stock }
                        if (over.isNotEmpty()) Text(
                            "مقدار خروج از موجودی ثبت‌شده بیشتر است: " + over.joinToString("، ") { it.first.name },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                        )
                    }
                    Row(Modifier.fillMaxWidth().clickable { counted = !counted }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(counted, { counted = it })
                        Column(Modifier.weight(1f)) {
                            Text("محاسبهٔ مبلغ خروج (از سود کم شود)")
                            Text(
                                if (counted) "ارزش این کالاها در گزارش به‌عنوان ضرر از سود خالص کم می‌شود"
                                else "فقط موجودی انبار کم می‌شود؛ مبلغی از سود و صندوق کم نمی‌شود",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (counted) {
                        Text("مبلغ خروج بر اساس", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf("BUY" to "قیمت خرید", "SELL" to "قیمت فروش", "MANUAL" to "مبلغ دستی")) { (b, label) ->
                                FilterChip(basis == b, { basis = b }, label = { Text(label) })
                            }
                        }
                        if (basis == "MANUAL") MoneyField("مبلغ خروج", manual, cur, { manual = it }, Modifier.fillMaxWidth())
                        if (lines.isNotEmpty()) SumRow(
                            "از سود کم می‌شود", Fmt.money(com.mozkhan.shop.data.exitValue(lines, basis, manual), cur), MaterialTheme.extra.loss,
                        )
                    }
                    Button(
                        enabled = !saving && lines.isNotEmpty() && lines.all { it.second > 0 } && !(counted && basis == "MANUAL" && manual <= 0),
                        onClick = {
                            if (saving) return@Button // a double tap must not take the goods out twice
                            saving = true
                            vm.createExit(kind, note, lines.toList(), counted, basis, manual) { lines.clear(); note = ""; counted = false; basis = "BUY"; manual = 0 }
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
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(d.id, expanded) { if (expanded) lines = vm.repo.exitLines(d.id) }
    AppCard(onClick = toggle) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kindFa(d.kind) + if (d.voided) " — باطل شد" else "", style = MaterialTheme.typography.titleSmall,
                        color = if (d.voided) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(
                        Jalali.dateTime(d.ts) + (if (d.note.isNotBlank()) " • ${d.note}" else "") + if (d.counted) " • از سود کم می‌شود" + when (d.priceBasis) { "SELL" -> " (قیمت فروش)"; "MANUAL" -> " (مبلغ دستی)"; else -> " (قیمت خرید)" } else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    Fmt.money(d.cost, cur), style = MaterialTheme.typography.titleSmall,
                    color = if (d.voided) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.extra.warn,
                    textDecoration = if (d.voided) TextDecoration.LineThrough else null,
                )
            }
            if (expanded) {
                lines.forEach { (n, q) -> Text("• $n × " + Fmt.digits(if (q == Math.floor(q)) q.toLong().toString() else q.toString()), style = MaterialTheme.typography.bodySmall) }
                if (!d.voided) OutlinedButton(onClick = { ask = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("ابطال خروج (برگشت کالا به انبار)", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("ابطال این سند خروج؟") },
        text = { Text("کالاهای این سند به موجودی انبار برمی‌گردند و سند «باطل شد» علامت می‌خورد. روی گوشی‌های دیگر هم اعمال می‌شود.") },
        confirmButton = { TextButton(onClick = { vm.voidExit(d.id); ask = false }) { Text("ابطال", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("انصراف") } },
    )
}
