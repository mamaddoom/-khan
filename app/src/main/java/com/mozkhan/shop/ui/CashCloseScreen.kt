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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.CashClose
import com.mozkhan.shop.data.Repo
import com.mozkhan.shop.data.newId
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali

private const val DAY = 86_400_000L

/** Expected cash in the drawer: what it started with, plus cash sales and debts collected, minus expenses paid from it. */
fun expectedCash(opening: Long, d: Repo.CashDay): Long = opening + d.cashSales + d.received - d.expenses

/**
 * End-of-day cash count. Shows what SHOULD be in the drawer for the day, the user types what IS there, and the
 * difference (shortage / surplus) is recorded. Closes sync between phones and stay in the history below.
 */
@Composable
fun CashCloseScreen(vm: MainVm, onBack: () -> Unit) {
    val cur by vm.currency.collectAsState()
    val closes by vm.closes.collectAsState()
    val today = Jalali.startOfDay(System.currentTimeMillis())
    var day by remember { mutableStateOf(today) }
    var data by remember { mutableStateOf<Repo.CashDay?>(null) }
    var opening by remember { mutableStateOf(vm.prefs.cashFloat) }
    var counted by remember { mutableStateOf(0L) }
    var keep by remember { mutableStateOf(0L) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<CashClose?>(null) }
    // figures of the chosen day (sales from every phone; they keep arriving through sync)
    val totals by vm.todayTotals.collectAsState()
    LaunchedEffect(day, totals, closes.size) { data = vm.cashDay(day) }

    val d = data
    val expected = d?.let { expectedCash(opening, it) } ?: 0L
    val diff = counted - expected
    val already = closes.filter { it.day == day }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
                Column(Modifier.weight(1f)) {
                    Text("بستن صندوق", style = MaterialTheme.typography.headlineSmall)
                    Text("پول نقد داخل صندوق را بشمارید و با حساب برنامه مقایسه کنید", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(day == today, { day = today; saved = false }, label = { Text("امروز") })
                FilterChip(day == today - DAY, { day = today - DAY; saved = false }, label = { Text("دیروز") })
                Text(Jalali.date(day), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (already.isNotEmpty() && !saved) item {
            Text(
                "این روز قبلاً بسته شده (" + already.joinToString("، ") { it.deviceName.ifBlank { "گوشی بی‌نام" } } + ")؛ ثبت دوباره یک شمارش جدید اضافه می‌کند.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
            )
        }
        item {
            AppCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField("موجودی اول روز صندوق", opening, cur, { opening = it }, Modifier.fillMaxWidth())
                    if (d == null) Text("در حال محاسبه…") else {
                        SumRow("+ فروش نقدی", Fmt.money(d.cashSales, cur))
                        SumRow("+ دریافتی از نسیه", Fmt.money(d.received, cur))
                        SumRow("− هزینه‌ها", Fmt.money(d.expenses, cur))
                        HorizontalDivider()
                        SumRow("باید در صندوق باشد", Fmt.money(expected, cur), MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "فروش کارتی و روش‌های غیرنقدی حساب نمی‌شوند. هزینه‌ها و دریافتی نسیه نقدی فرض شده‌اند. فروش همهٔ گوشی‌های همگام شامل است.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            AppCard {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField("پول شمرده‌شده در صندوق", counted, cur, { counted = it; saved = false }, Modifier.fillMaxWidth())
                    if (counted > 0 && d != null) {
                        val (label, color) = when {
                            diff == 0L -> "صندوق درست است ✓" to MaterialTheme.extra.profit
                            diff < 0 -> "کسری: " + Fmt.money(-diff, cur) to MaterialTheme.extra.loss
                            else -> "اضافه: " + Fmt.money(diff, cur) to MaterialTheme.extra.warn
                        }
                        Text(label, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                    }
                    MoneyField("می‌ماند در صندوق برای فردا (اختیاری)", keep, cur, { keep = it }, Modifier.fillMaxWidth())
                    AppField(note, { note = it }, "توضیح (اختیاری)", Modifier.fillMaxWidth())
                    Button(
                        enabled = d != null && counted > 0 && !saved,
                        onClick = {
                            val dd = d ?: return@Button
                            vm.saveClose(
                                CashClose(
                                    newId(), day, opening, dd.cashSales, dd.received, dd.expenses, expected, counted, note.trim(), vm.prefs.deviceName,
                                ),
                            ) { saved = true }
                            vm.prefs.cashFloat = keep
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (saved) "ثبت شد ✓" else "بستن صندوق") }
                }
            }
        }
        item { SectionTitle("صندوق‌های بسته‌شده") }
        if (closes.isEmpty()) item { EmptyState(Icons.Filled.PointOfSale, "هنوز صندوقی بسته نشده", "آخر هر روز پول را بشمارید و «بستن صندوق» را بزنید.") }
        items(closes.take(60), key = { it.id }) { c ->
            AppCard {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Jalali.date(c.day) + (if (c.deviceName.isNotBlank()) " • " + c.deviceName else ""), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "باید: " + Fmt.money(c.expected, cur) + " • شمرده: " + Fmt.money(c.counted, cur),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall)
                    }
                    val color = when { c.diff == 0L -> MaterialTheme.extra.profit; c.diff < 0 -> MaterialTheme.extra.loss; else -> MaterialTheme.extra.warn }
                    Text(
                        when { c.diff == 0L -> "درست"; c.diff < 0 -> "کسری " + Fmt.money(-c.diff, cur); else -> "اضافه " + Fmt.money(c.diff, cur) },
                        color = color, style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = { del = c }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.outline) }
                }
            }
        }
    }

    del?.let { c ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("حذف این شمارش؟") },
            text = { Text("بستن صندوق " + Jalali.date(c.day) + " از تاریخچه حذف می‌شود (روی همهٔ گوشی‌ها).") },
            confirmButton = { TextButton(onClick = { vm.deleteClose(c.id); del = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("انصراف") } },
        )
    }
}
