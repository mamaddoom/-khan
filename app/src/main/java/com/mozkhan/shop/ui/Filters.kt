package com.mozkhan.shop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali
import com.mozkhan.shop.util.Period

/** Date + category filter shared by every report. */
@Composable
fun FilterBar(vm: MainVm, showCategory: Boolean = true, showDept: Boolean = false) {
    val period by vm.period.collectAsState()
    val offset by vm.offset.collectAsState()
    val range by vm.range.collectAsState()
    val cat by vm.category.collectAsState()
    val cats by vm.categories.collectAsState()
    val dept by vm.dept.collectAsState()
    val from by vm.customFrom.collectAsState()
    val to by vm.customTo.collectAsState()
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Period.entries.toList()) { p ->
                FilterChip(period == p, { vm.period.value = p; vm.offset.value = 0 }, label = { Text(p.fa) })
            }
        }
        if (period == Period.CUSTOM) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickFrom = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)); Text("  از " + Jalali.date(from))
                }
                OutlinedButton(onClick = { pickTo = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(18.dp)); Text("  تا " + Jalali.date(to))
                }
            }
        } else AppCard {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.offset.value = offset - 1 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "قبلی") }
                Text(range.label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { vm.offset.value = offset + 1 }, enabled = offset < 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "بعدی") }
            }
        }
        if (showDept) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(dept == null, { vm.dept.value = null }, label = { Text("همه بخش‌ها") })
            FilterChip(dept == com.mozkhan.shop.data.DEPT_SHOP, { vm.dept.value = com.mozkhan.shop.data.DEPT_SHOP }, label = { Text("فروشگاه") })
            FilterChip(dept == com.mozkhan.shop.data.DEPT_CAFE, { vm.dept.value = com.mozkhan.shop.data.DEPT_CAFE }, label = { Text("کافه") })
        }
        if (showCategory) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(cat == null, { vm.category.value = null }, label = { Text("همه دسته‌ها") }) }
            items(cats) { c -> FilterChip(cat == c, { vm.category.value = if (cat == c) null else c }, label = { Text(c) }) }
        }
    }
    if (pickFrom) JalaliDateDialog("از تاریخ", from, { pickFrom = false }) { vm.customFrom.value = it }
    if (pickTo) JalaliDateDialog("تا تاریخ", to, { pickTo = false }) { vm.customTo.value = it }
}

@Composable
fun JalaliDateDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val j0 = remember(initial) { Jalali.of(initial) }
    var y by remember { mutableIntStateOf(j0.y) }
    var m by remember { mutableIntStateOf(j0.m) }
    var d by remember { mutableIntStateOf(j0.d) }
    val len = Jalali.monthLength(y, m)
    if (d > len) d = len
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("سال", Fmt.digits(y.toString()), { y-- }, { y++ })
                Stepper("ماه", Jalali.months[m - 1], { m = if (m == 1) 12 else m - 1 }, { m = if (m == 12) 1 else m + 1 })
                Stepper("روز", Fmt.digits(d.toString()), { d = if (d == 1) len else d - 1 }, { d = if (d >= len) 1 else d + 1 })
            }
        },
        confirmButton = { TextButton(onClick = { onPick(Jalali.startOfJalali(y, m, d)); onDismiss() }) { Text("تأیید") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, "بیشتر") }
        Text(value, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, "کمتر") }
    }
}

@Composable
fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
}

/** Wide bar [a] with a narrower bar [b] drawn inside it; first bucket is on the right (RTL). */
@Composable
fun TwoBarChart(labels: List<String>, a: List<Long>, b: List<Long>, colorA: Color, colorB: Color, cur: Currency) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = (a.maxOrNull() ?: 0L).coerceAtLeast(b.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column {
        Text("بیشترین: " + Fmt.moneyShort(max, cur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val n = a.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            for (g in 0..3) {
                val y = size.height * g / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            for (i in a.indices) {
                val cx = size.width - slot * (i + 0.5f)
                val h = size.height * a[i].coerceAtLeast(0) / max
                if (h > 0) drawRoundRect(colorA.copy(alpha = 0.85f), Offset(cx - bw / 2, size.height - h), Size(bw, h), CornerRadius(5.dp.toPx()))
                val ph = size.height * b.getOrElse(i) { 0L }.coerceAtLeast(0) / max
                if (ph > 0) drawRoundRect(colorB, Offset(cx - bw / 4, size.height - ph), Size(bw / 2, ph), CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false) }
        }
    }
}

/** Horizontal bars: label + value, scaled to the biggest. */
@Composable
fun HBars(rows: List<Pair<String, Long>>, color: Color, cur: Currency) {
    val max = (rows.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, v) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(Fmt.moneyShort(v, cur), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth((v.toFloat() / max).coerceIn(0.02f, 1f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
