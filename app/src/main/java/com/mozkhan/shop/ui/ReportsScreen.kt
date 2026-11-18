package com.mozkhan.shop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.data.SaleItem
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali

@Composable
fun ReportsScreen(vm: MainVm) {
    val cur by vm.currency.collectAsState()
    val r by vm.report.collectAsState()

    var addExpense by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Sale?>(null) }
    var voidAsk by remember { mutableStateOf<PurchaseRow?>(null) }
    var expenseAsk by remember { mutableStateOf<com.mozkhan.shop.data.Expense?>(null) }

    val t = r.agg
    val gross = t.profit
    val net = gross - r.expenses - r.exitCost
    val filtered = r.category != null || r.dept != null
    val avg = if (t.count > 0) t.revenue / t.count else 0L

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("گزارش‌ها", style = MaterialTheme.typography.headlineSmall) }
        item { FilterBar(vm, showDept = true) }
        item {
            val shown = if (filtered) gross else net
            val col = if (shown >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss
            AppCard(container = col.copy(alpha = 0.10f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        if (filtered) "سود ناخالص " + listOfNotNull(r.category, r.dept?.let { if (it == com.mozkhan.shop.data.DEPT_CAFE) "کافه" else "فروشگاه" }).joinToString(" • ") else if (net >= 0) "سود خالص" else "زیان خالص",
                        style = MaterialTheme.typography.labelLarge, color = col,
                    )
                    Text(Fmt.money(shown, cur), style = MaterialTheme.typography.headlineMedium, color = col)
                    Text(
                        if (filtered) "هزینه‌ها و خریدها به دسته یا بخش ربط ندارند و در این نما حساب نمی‌شوند" else "سود ناخالص − هزینه‌ها" + (if (r.exitCost > 0) " − ضایعات و مصرف شخصی" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("فروش", Fmt.money(t.revenue, cur), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    Kpi("بهای تمام‌شده", Fmt.money(t.cost, cur), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("سود ناخالص", Fmt.money(gross, cur), if (gross >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss, Modifier.weight(1f))
                    Kpi("درصد سود (حاشیه)", Fmt.percent(t.margin), MaterialTheme.extra.profit, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Kpi("هزینه‌ها", if (filtered) "—" else Fmt.money(r.expenses, cur), MaterialTheme.extra.warn, Modifier.weight(1f))
                    Kpi("تعداد / میانگین فاکتور", Fmt.digits("${t.count}") + " / " + Fmt.moneyShort(avg, cur), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
            }
        }
        if (!filtered) item {
            AppCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("جریان نقدی", style = MaterialTheme.typography.titleSmall)
                    SumRow("فروش نقد و کارتی + دریافتی نسیه", Fmt.money(r.cashIn, cur))
                    SumRow("− خرید کالا و مواد", Fmt.money(r.purchases, cur), MaterialTheme.extra.warn)
                    SumRow("− هزینه‌ها", Fmt.money(r.expenses, cur), MaterialTheme.extra.warn)
                    val flow = r.cashIn - r.purchases - r.expenses
                    SumRow("خالص نقدینگی", Fmt.money(flow, cur), if (flow >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss)
                    Text("فروش نسیه تا وقتی دریافت نشده، نقدینگی حساب نمی‌شود. خرید کالا در «سود و زیان» دوباره کسر نمی‌شود (بهای کالای فروخته‌شده قبلاً کسر شده).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val byPay = r.sales.filter { !it.voided }.groupBy { it.payment }.map { (k, l) -> k to (l.size to l.sumOf { it.total }) }
            .sortedByDescending { it.second.second }
        if (!filtered && byPay.isNotEmpty()) item {
            AppCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("فروش به تفکیک روش پرداخت", style = MaterialTheme.typography.titleSmall)
                    byPay.forEach { (k, v) -> SumRow(payFa(k) + Fmt.digits(" (${v.first} فاکتور)"), Fmt.money(v.second, cur)) }
                }
            }
        }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("روند فروش و سود", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Legend(MaterialTheme.colorScheme.primary, "فروش"); Legend(MaterialTheme.extra.profit, "سود")
                    }
                    TwoBarChart(r.labels, r.rev, r.profit, MaterialTheme.colorScheme.primary, MaterialTheme.extra.profit, cur)
                }
            }
        }
        if (r.byCat.size > 1 && !filtered) item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("فروش به تفکیک دسته‌بندی", style = MaterialTheme.typography.titleSmall)
                    HBars(r.byCat.map { it.category to it.revenue }, MaterialTheme.colorScheme.primary, cur)
                    Text("سود هر دسته", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    HBars(r.byCat.map { it.category to it.profit.coerceAtLeast(0) }, MaterialTheme.extra.profit, cur)
                }
            }
        }
        if (r.top.isNotEmpty()) {
            item { SectionTitle("پرفروش‌ترین کالاها") }
            item {
                AppCard {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val max = r.top.maxOf { it.revenue }.coerceAtLeast(1.0)
                        r.top.forEachIndexed { i, p ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Fmt.digits("${i + 1}."), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                                    Text(p.name, Modifier.weight(1f), maxLines = 1)
                                    Text(Fmt.qty(p.qty, p.isWeight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
                                    Box(Modifier.fillMaxWidth((p.revenue / max).toFloat().coerceIn(0.02f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                                }
                                Row {
                                    Text("فروش " + Fmt.moneyShort(Math.round(p.revenue), cur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    Text("سود " + Fmt.moneyShort(Math.round(p.revenue - p.cost), cur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extra.profit)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("خریدهای این بازه") { Text(Fmt.money(r.purchases, cur), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.extra.warn) } }
        if (r.purchaseRows.isEmpty()) item { Text("خریدی ثبت نشده. از «خرید جدید» در صفحهٔ کالا یا انبار کافه استفاده کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(r.purchaseRows.take(40), key = { "p" + it.docId + it.ts + it.productName }) { pr ->
            AppCard {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pr.productName + " × " + Fmt.qtyOf(pr.qty, pr.unit), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(Jalali.dateTime(pr.ts) + (if (pr.supplier.isNotBlank()) " • ${pr.supplier}" else "") + (if (pr.estimated) " • تخمینی" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.money(pr.cost, cur), color = MaterialTheme.extra.warn, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { voidAsk = pr }) { Icon(Icons.Filled.DeleteOutline, "ابطال خرید", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (!filtered) {
            item {
                SectionTitle("هزینه‌ها") {
                    TextButton(onClick = { addExpense = true }) { Icon(Icons.Filled.Add, null, Modifier.padding(end = 4.dp)); Text("ثبت هزینه") }
                }
            }
            if (r.expenseList.isEmpty()) item { Text("هزینه‌ای در این بازه ثبت نشده (اجاره، قبض، حقوق، …)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(r.expenseList, key = { "e" + it.id }) { e ->
                AppCard {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall)
                            Text(Jalali.dateTime(e.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Fmt.money(e.amount, cur), color = MaterialTheme.extra.warn, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { expenseAsk = e }) { Icon(Icons.Filled.DeleteOutline, "حذف هزینه", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        item { SectionTitle("فاکتورهای این بازه") }
        if (r.sales.isEmpty()) item { EmptyState(Icons.Filled.ReceiptLong, "فاکتوری وجود ندارد", "در این بازه فروشی ثبت نشده است.") }
        items(r.sales.take(60), key = { "s" + it.id }) { s ->
            AppCard(onClick = { detail = s }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.money(s.total, cur), style = MaterialTheme.typography.titleSmall)
                        Text(Jalali.dateTime(s.ts) + " • " + payFa(s.payment), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.voided) Pill("ابطال‌شده", MaterialTheme.colorScheme.error) else Pill("+" + Fmt.moneyShort(s.total - s.cost, cur), MaterialTheme.extra.profit)
                }
            }
        }
    }

    if (addExpense) ExpenseDialog(cur, { addExpense = false }) { title, amount -> vm.addExpense(title, amount) }
    detail?.let { s -> SaleDetailDialog(vm, s, cur) { detail = null } }
    voidAsk?.let { pr ->
        AlertDialog(
            onDismissRequest = { voidAsk = null },
            title = { Text("ابطال خرید") },
            text = { Text("خرید «${pr.productName}» (" + Fmt.qtyOf(pr.qty, pr.unit) + "، " + Fmt.money(pr.cost, cur) + ") ابطال شود؟ همین مقدار از موجودی کم می‌شود.") },
            confirmButton = { TextButton(onClick = { vm.voidPurchase(pr.docId); voidAsk = null }) { Text("ابطال", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { voidAsk = null }) { Text("انصراف") } },
        )
    }
    expenseAsk?.let { e ->
        AlertDialog(
            onDismissRequest = { expenseAsk = null },
            title = { Text("حذف هزینه") },
            text = { Text("هزینهٔ «${e.title}» به مبلغ " + Fmt.money(e.amount, cur) + " حذف شود؟") },
            confirmButton = { TextButton(onClick = { vm.deleteExpense(e); expenseAsk = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { expenseAsk = null }) { Text("انصراف") } },
        )
    }
}

fun payFa(p: String) = com.mozkhan.shop.util.PayMethods.label(p)

@Composable
fun Kpi(label: String, value: String, color: Color, modifier: Modifier) {
    AppCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 2)
        }
    }
}

@Composable
private fun ExpenseDialog(cur: Currency, onDismiss: () -> Unit, onOk: (String, Long) -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(0L) }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت هزینه") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(title, { title = it }, "عنوان (اجاره، قبض، چای…)", Modifier.fillMaxWidth())
                MoneyField("مبلغ", amount, cur, { amount = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && amount > 0 && !sent, onClick = { if (!sent) { sent = true; onOk(title, amount); onDismiss() } }) { Text("ثبت") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
internal fun SaleDetailDialog(vm: MainVm, s: Sale, cur: Currency, onDismiss: () -> Unit) {
    var share by remember { mutableStateOf(false) }
    if (share) { ShareInvoiceDialog(vm, s) { share = false }; return }
    val items by remember(s.id) { vm.repo.observeItemsOf(s.id) }.collectAsState(initial = emptyList<SaleItem>())
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فاکتور " + Jalali.dateTime(s.ts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach {
                    Row {
                        Text(it.name + " × " + Fmt.qty(it.qty, it.isWeight), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(Fmt.money(it.lineTotal, cur, false), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (s.discount > 0) SumRow("تخفیف", Fmt.money(s.discount, cur))
                SumRow("جمع", Fmt.money(s.total, cur))
                SumRow("سود", Fmt.money(s.total - s.cost, cur), MaterialTheme.extra.profit)
                if (s.payment == "CREDIT") SumRow("نحوهٔ پرداخت", "نسیه")
                if (s.deviceName.isNotBlank()) SumRow("فروشنده", s.deviceName)
                if (confirm) Text("با ابطال، کالاها به موجودی برمی‌گردند. مطمئن هستید؟", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (!s.voided) TextButton(onClick = { if (confirm) { vm.voidSale(s.id); onDismiss() } else confirm = true }) {
                Text(if (confirm) "بله، ابطال شود" else "ابطال فاکتور", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { share = true }) { Text("اشتراک فاکتور") }
                TextButton(onClick = onDismiss) { Text("بستن") }
            }
        },
    )
}
