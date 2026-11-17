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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.Cheque
import com.mozkhan.shop.data.StockDoc
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali

private const val DAY = 86_400_000L

fun chequeStatusFa(s: String) = when (s) {
    "PAID" -> "پاس شد"; "BOUNCED" -> "برگشت خورد"; "CANCELLED" -> "باطل"; else -> "در انتظار"
}

/** whole days from today to the due day (negative = overdue) */
fun chequeDaysLeft(c: Cheque, now: Long = System.currentTimeMillis()): Int =
    Math.round((Jalali.startOfDay(c.dueDate) - Jalali.startOfDay(now)) / DAY.toDouble()).toInt()

fun chequeLabel(c: Cheque, cur: Currency): String =
    "چک " + Fmt.digits(c.number) + " • " + Fmt.money(c.amount, cur) + " • " + Jalali.date(c.dueDate) + (if (c.payee.isNotBlank()) " • " + c.payee else "")

private fun daysText(d: Int) = Fmt.digits(
    when {
        d < 0 -> "${-d} روز گذشته"
        d == 0 -> "سررسید امروز"
        d == 1 -> "فردا"
        else -> "$d روز مانده"
    },
)

/** Cheques given to suppliers: due dates, days left, status, and which purchases each one paid. */
@Composable
fun ChequesScreen(vm: MainVm, onBack: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val cheques by vm.cheques.collectAsState()
    val docs by vm.docs.collectAsState()
    var filter by remember { mutableStateOf("PENDING") }
    var open by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<Cheque?>(null) }
    var adding by remember { mutableStateOf(false) }

    val pending = cheques.filter { it.status == "PENDING" }
    val shown = if (filter == "PENDING") pending else cheques.filter { it.status != "PENDING" }.sortedByDescending { it.dueDate }
    val week = pending.filter { chequeDaysLeft(it) <= 7 }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column(Modifier.weight(1f)) {
                    Text("چک‌ها", style = MaterialTheme.typography.headlineSmall)
                    Text("چک‌هایی که برای خرید داده‌اید و تاریخ سررسیدشان", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "چک جدید") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("جمع چک‌های در انتظار", Fmt.moneyShort(pending.sumOf { it.amount }, cur), MaterialTheme.extra.warn, Modifier.weight(1f))
                Kpi("سررسید تا ۷ روز", Fmt.moneyShort(week.sumOf { it.amount }, cur), MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == "PENDING", { filter = "PENDING" }, label = { Text("در انتظار (${Fmt.digits(pending.size.toString())})") })
                FilterChip(filter == "DONE", { filter = "DONE" }, label = { Text("پاس‌شده / برگشتی") })
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Filled.Payments, "چکی نیست", "هنگام «خرید جدید» گزینهٔ «چکی» را بزنید، یا با + یک چک ثبت کنید.")
        }
        items(shown, key = { it.id }) { c ->
            ChequeCard(vm, c, docs.filter { it.chequeId == c.id && !it.voided }, cur, open == c.id, onToggle = { open = if (open == c.id) null else c.id }, onEdit = { edit = c })
        }
    }

    if (adding) ChequeEditDialog(null, cur, onDismiss = { adding = false }) { vm.saveCheque(it); adding = false }
    edit?.let { c -> ChequeEditDialog(c, cur, onDismiss = { edit = null }) { vm.saveCheque(it); edit = null } }
}

@Composable
private fun ChequeCard(vm: MainVm, c: Cheque, purchases: List<StockDoc>, cur: Currency, expanded: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    val d = chequeDaysLeft(c)
    val dueColor: Color = when {
        c.status != "PENDING" -> MaterialTheme.colorScheme.onSurfaceVariant
        d < 0 -> MaterialTheme.colorScheme.error
        d <= 3 -> MaterialTheme.extra.warn
        else -> MaterialTheme.extra.profit
    }
    var askDelete by remember { mutableStateOf(false) }
    AppCard(onClick = onToggle) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("چک " + Fmt.digits(c.number) + if (c.bank.isNotBlank()) " • " + c.bank else "", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "سررسید " + Jalali.weekday(c.dueDate) + " " + Jalali.date(c.dueDate) + if (c.payee.isNotBlank()) " • در وجه " + c.payee else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.money(c.amount, cur), style = MaterialTheme.typography.titleSmall)
                    Pill(if (c.status == "PENDING") daysText(d) else chequeStatusFa(c.status), dueColor)
                }
            }
            if (expanded) {
                if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (purchases.isEmpty()) "به خریدی وصل نیست" else "خریدهای پرداخت‌شده با این چک (${Fmt.digits(purchases.size.toString())}):",
                    style = MaterialTheme.typography.labelLarge,
                )
                purchases.sortedBy { it.ts }.forEach { p ->
                    Text(
                        "• " + Jalali.date(p.ts) + " — " + Fmt.money(p.cost, cur) + if (p.note.isNotBlank()) " — " + p.note else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (purchases.isNotEmpty()) {
                    val sum = purchases.sumOf { it.cost }
                    if (sum != c.amount) Text(
                        "جمع خریدها " + Fmt.money(sum, cur) + " است؛ " + (if (sum > c.amount) "بیشتر" else "کمتر") + " از مبلغ چک",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.status != "PAID") OutlinedButton(onClick = { vm.setChequeStatus(c.id, "PAID") }) { Text("پاس شد") }
                    if (c.status != "BOUNCED") OutlinedButton(onClick = { vm.setChequeStatus(c.id, "BOUNCED") }) { Text("برگشت خورد") }
                    if (c.status != "PENDING") OutlinedButton(onClick = { vm.setChequeStatus(c.id, "PENDING") }) { Text("در انتظار") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onEdit) { Text("ویرایش") }
                    TextButton(onClick = { askDelete = true }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("حذف این چک؟") },
        text = { Text("خریدهایی که با این چک ثبت شده‌اند باقی می‌مانند.") },
        confirmButton = { TextButton(onClick = { vm.deleteCheque(c.id); askDelete = false }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("انصراف") } },
    )
}

@Composable
private fun ChequeEditDialog(c: Cheque?, cur: Currency, onDismiss: () -> Unit, onSave: (Cheque) -> Unit) {
    var number by remember { mutableStateOf(c?.number.orEmpty()) }
    var bank by remember { mutableStateOf(c?.bank.orEmpty()) }
    var payee by remember { mutableStateOf(c?.payee.orEmpty()) }
    var note by remember { mutableStateOf(c?.note.orEmpty()) }
    var amount by remember { mutableStateOf(c?.amount ?: 0L) }
    var due by remember { mutableStateOf(c?.dueDate ?: Jalali.startOfDay(System.currentTimeMillis() + 30 * DAY)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (c == null) "چک جدید" else "ویرایش چک") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(number, { number = it }, "شمارهٔ چک (صیادی یا سریال)", Modifier.fillMaxWidth())
                AppField(bank, { bank = it }, "بانک (اختیاری)", Modifier.fillMaxWidth())
                AppField(payee, { payee = it }, "در وجه / تأمین‌کننده (اختیاری)", Modifier.fillMaxWidth())
                MoneyField("مبلغ چک", amount, cur, { amount = it }, Modifier.fillMaxWidth())
                JalaliDateField("تاریخ سررسید", due, { due = it })
                AppField(note, { note = it }, "توضیحات (اختیاری)", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = number.isNotBlank() && amount > 0, onClick = {
                val base = c ?: Cheque(java.util.UUID.randomUUID().toString(), "", dueDate = due)
                onSave(base.copy(number = number.trim(), bank = bank.trim(), payee = payee.trim(), note = note.trim(), amount = amount, dueDate = due))
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

/** Jalali date entry as day / month / year (shows the weekday so a wrong date is easy to spot). */
@Composable
fun JalaliDateField(label: String, ts: Long, onChange: (Long) -> Unit) {
    val d0 = remember(ts) { Jalali.of(ts) }
    var y by remember { mutableStateOf(d0.y.toString()) }
    var m by remember { mutableStateOf(d0.m.toString()) }
    var d by remember { mutableStateOf(d0.d.toString()) }
    fun push(yy: String, mm: String, dd: String) {
        val yi = yy.toIntOrNull() ?: return
        val mi = mm.toIntOrNull()?.takeIf { it in 1..12 } ?: return
        if (yi !in 1300..1500) return
        val di = dd.toIntOrNull()?.takeIf { it in 1..Jalali.monthLength(yi, mi) } ?: return
        onChange(Jalali.startOfJalali(yi, mi, di))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label + ": " + Jalali.weekday(ts) + " " + Jalali.date(ts), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("روز", d, { d = it; push(y, m, it) }, Modifier.weight(1f))
            NumberField("ماه", m, { m = it; push(y, it, d) }, Modifier.weight(1f))
            NumberField("سال", y, { y = it; push(it, m, d) }, Modifier.weight(1.4f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "امروز", 30 to "۱ ماه", 60 to "۲ ماه", 90 to "۳ ماه").forEach { (n, t) ->
                FilterChip(false, {
                    val v = Jalali.startOfDay(System.currentTimeMillis() + n * DAY)
                    val j = Jalali.of(v)
                    y = j.y.toString(); m = j.m.toString(); d = j.d.toString()
                    onChange(v)
                }, label = { Text(t) })
            }
        }
    }
}
