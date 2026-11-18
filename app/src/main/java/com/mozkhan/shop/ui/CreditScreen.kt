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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.CreditPayment
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali

/** نسیه: customers, what they owe, and their payments. */
@Composable
fun CreditScreen(vm: MainVm, onBack: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val list by vm.debtors.collectAsState()
    var addOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    val totalDebt = list.sumOf { it.debt.coerceAtLeast(0) }
    val sel = list.firstOrNull { it.customer.id == selected }

    if (sel != null) {
        CustomerDetail(vm, sel, cur) { selected = null }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                    Text("نسیه", style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                AppCard(container = MaterialTheme.extra.warn.copy(alpha = 0.10f)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("جمع بدهی مشتریان", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.extra.warn)
                        Text(Fmt.money(totalDebt, cur), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.extra.warn)
                        Text(Fmt.digits("${list.count { it.debt > 0 }} مشتری بدهکار"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (list.isEmpty()) item {
                EmptyState(Icons.Filled.Group, "هنوز مشتری ثبت نشده", "هنگام تسویه با گزینهٔ «نسیه» یا از دکمهٔ پایین، مشتری اضافه کنید.")
            }
            items(list, key = { it.customer.id }) { c ->
                AppCard(onClick = { selected = c.customer.id }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.customer.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (c.last > 0) "آخرین تراکنش " + Jalali.date(c.last) else c.customer.phone.ifBlank { "بدون تراکنش" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (c.debt > 0) Pill("بدهکار " + Fmt.moneyShort(c.debt, cur), MaterialTheme.extra.warn)
                        else if (c.debt < 0) Pill("بستانکار " + Fmt.moneyShort(-c.debt, cur), MaterialTheme.extra.profit)
                        else Pill("تسویه", MaterialTheme.extra.profit)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { addOpen = true },
            icon = { Icon(Icons.Filled.PersonAdd, null) },
            text = { Text("مشتری جدید") },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
    if (addOpen) CustomerDialog({ addOpen = false }) { n, p -> vm.addCustomer(n, p) }
}

@Composable
fun CustomerDialog(onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مشتری جدید") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(name, { name = it }, "نام مشتری", Modifier.fillMaxWidth())
                AppField(phone, { phone = Fmt.normalize(it) }, "شماره تماس (اختیاری)", Modifier.fillMaxWidth(), keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !sent, onClick = { if (!sent) { sent = true; onOk(name, phone); onDismiss() } }) { Text("ثبت") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

private sealed interface Tx { val ts: Long }
private data class SaleTx(val s: Sale) : Tx { override val ts get() = s.ts }
private data class PayTx(val p: CreditPayment) : Tx { override val ts get() = p.ts }

@Composable
private fun CustomerDetail(vm: MainVm, c: CustomerDebt, cur: Currency, onBack: () -> Unit) {
    val id = c.customer.id
    val sales by remember(id) { vm.creditSales(id) }.collectAsState(emptyList())
    val pays by remember(id) { vm.payments(id) }.collectAsState(emptyList())
    var payOpen by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Sale?>(null) }
    var payAsk by remember { mutableStateOf<CreditPayment?>(null) }
    val tx = (sales.filter { !it.voided }.map { SaleTx(it) } + pays.map { PayTx(it) }).sortedByDescending { it.ts }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column(Modifier.weight(1f)) {
                    Text(c.customer.name, style = MaterialTheme.typography.headlineSmall)
                    if (c.customer.phone.isNotBlank()) Text(Fmt.digits(c.customer.phone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { delete = true }) { Icon(Icons.Filled.DeleteOutline, "حذف", tint = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            val col = if (c.debt > 0) MaterialTheme.extra.warn else MaterialTheme.extra.profit
            AppCard(container = col.copy(alpha = 0.10f)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (c.debt >= 0) "مانده بدهی" else "مانده بستانکاری", style = MaterialTheme.typography.labelLarge, color = col)
                    Text(Fmt.money(kotlin.math.abs(c.debt), cur), style = MaterialTheme.typography.headlineMedium, color = col)
                    Row {
                        Text("کل خرید نسیه: " + Fmt.moneyShort(c.credit, cur), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("پرداختی: " + Fmt.moneyShort(c.paid, cur), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(onClick = { payOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("ثبت دریافتی از مشتری") }
        }
        item { SectionTitle("تراکنش‌ها") }
        if (tx.isEmpty()) item { Text("تراکنشی ثبت نشده", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tx, key = { if (it is SaleTx) "s" + it.s.id else "p" + (it as PayTx).p.id }) { t ->
            when (t) {
                is SaleTx -> AppCard(onClick = { detail = t.s }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("خرید نسیه", style = MaterialTheme.typography.titleSmall)
                            Text(Jalali.dateTime(t.s.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("+" + Fmt.money(t.s.total, cur), color = MaterialTheme.extra.warn, style = MaterialTheme.typography.titleSmall)
                    }
                }
                is PayTx -> AppCard {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("دریافتی" + if (t.p.note.isNotBlank()) " — ${t.p.note}" else "", style = MaterialTheme.typography.titleSmall)
                            Text(Jalali.dateTime(t.p.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("−" + Fmt.money(t.p.amount, cur), color = MaterialTheme.extra.profit, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { payAsk = t.p }) { Icon(Icons.Filled.DeleteOutline, "حذف دریافتی", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (payOpen) PaymentDialog(cur, c.debt, { payOpen = false }) { amount, note -> vm.addPayment(id, amount, note) }
    detail?.let { s -> SaleLinesDialog(vm, s, cur) { detail = null } }
    payAsk?.let { p ->
        AlertDialog(
            onDismissRequest = { payAsk = null },
            title = { Text("حذف دریافتی") },
            text = { Text("دریافتی " + Fmt.money(p.amount, cur) + " (" + Jalali.dateTime(p.ts) + ") حذف شود؟ مبلغ دوباره به بدهی مشتری اضافه می‌شود.") },
            confirmButton = { TextButton(onClick = { vm.deletePayment(p); payAsk = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { payAsk = null }) { Text("انصراف") } },
        )
    }
    if (delete) AlertDialog(
        onDismissRequest = { delete = false },
        title = { Text("حذف مشتری") },
        text = { Text(if (c.debt != 0L) "این مشتری هنوز مانده‌حساب دارد. مطمئن هستید؟" else "«${c.customer.name}» حذف شود؟") },
        confirmButton = { TextButton(onClick = { vm.deleteCustomer(c.customer); delete = false; onBack() }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("انصراف") } },
    )
}

@Composable
private fun PaymentDialog(cur: Currency, debt: Long, onDismiss: () -> Unit, onOk: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت دریافتی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyField("مبلغ دریافتی", amount, cur, { amount = it }, Modifier.fillMaxWidth())
                if (debt > 0) OutlinedButton(onClick = { amount = debt }) { Text("کل بدهی (" + Fmt.moneyShort(debt, cur) + ")") }
                if (amount > 0 && amount > debt) Text(
                    "این مبلغ از بدهی فعلی بیشتر است؛ مشتری " + Fmt.money(amount - debt.coerceAtLeast(0), cur) + " بستانکار می‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                )
                AppField(note, { note = it }, "توضیح (اختیاری)", Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = amount > 0 && !sent, onClick = { if (!sent) { sent = true; onOk(amount, note); onDismiss() } }) { Text("ثبت") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
fun SaleLinesDialog(vm: MainVm, s: Sale, cur: Currency, onDismiss: () -> Unit) {
    var share by remember { mutableStateOf(false) }
    if (share) { ShareInvoiceDialog(vm, s) { share = false }; return }
    val items by remember(s.id) { vm.repo.observeItemsOf(s.id) }.collectAsState(initial = emptyList<com.mozkhan.shop.data.SaleItem>())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Jalali.dateTime(s.ts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach {
                    Row {
                        Text(it.name + " × " + Fmt.qty(it.qty, it.isWeight), Modifier.weight(1f))
                        Text(Fmt.money(it.lineTotal, cur, false))
                    }
                }
                if (s.discount > 0) SumRow("تخفیف", Fmt.money(s.discount, cur))
                SumRow("جمع", Fmt.money(s.total, cur))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
        dismissButton = { TextButton(onClick = { share = true }) { Text("اشتراک فاکتور") } },
    )
}
