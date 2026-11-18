package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.mozkhan.shop.util.PayMethod
import com.mozkhan.shop.util.PayMethods
import com.mozkhan.shop.util.Prefs

/** Settings block: add / rename / remove / reorder / switch off the ways a sale can be paid. */
@Composable
fun PayMethodsSettings(prefs: Prefs) {
    val list by prefs.payMethods.collectAsState()
    var newName by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<PayMethod?>(null) }
    var del by remember { mutableStateOf<PayMethod?>(null) }
    val addErr = if (newName.isBlank()) null else PayMethods.nameError(newName, list)

    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in list.indices) return
        val m = list.toMutableList(); val x = m.removeAt(i); m.add(j, x); prefs.setPayMethods(m)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "روش‌هایی که هنگام تسویه نشان داده می‌شوند، به همین ترتیب. مثلاً «کارت به کارت»، «دستگاه ۱»، «دستگاه ۲».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        list.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = if (m.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (m.id == PayMethods.CREDIT) Text("نیاز به انتخاب مشتری دارد", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { move(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "بالا") }
                IconButton(onClick = { move(i, 1) }, enabled = i < list.size - 1) { Icon(Icons.Filled.KeyboardArrowDown, "پایین") }
                if (!m.builtIn) {
                    IconButton(onClick = { edit = m }) { Icon(Icons.Filled.Edit, "ویرایش") }
                    IconButton(onClick = { del = m }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                }
                Switch(m.enabled, { on -> prefs.setPayMethods(list.map { if (it.id == m.id) it.copy(enabled = on) else it }) }, enabled = m.id != PayMethods.CASH)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppField(newName, { newName = it }, "روش جدید (مثلاً کارت به کارت)", Modifier.weight(1f))
            Button(enabled = newName.isNotBlank() && addErr == null, onClick = {
                prefs.setPayMethods(list + PayMethod(PayMethods.customId(newName), newName.trim()))
                newName = ""
            }) { Text("افزودن") }
        }
        addErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    edit?.let { m ->
        var n by remember(m.id) { mutableStateOf(m.name) }
        val err = PayMethods.nameError(n, list, exceptId = m.id)
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text("ویرایش روش پرداخت") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppField(n, { n = it }, "نام", Modifier.fillMaxWidth())
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("فروش‌های قبلی با نام قبلی در گزارش می‌مانند.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = err == null, onClick = {
                    prefs.setPayMethods(list.map { if (it.id == m.id) PayMethod(PayMethods.customId(n), n.trim(), it.enabled) else it })
                    edit = null
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("انصراف") } },
        )
    }
    del?.let { m ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("حذف «${m.name}»؟") },
            text = { Text("از فهرست تسویه حذف می‌شود؛ فروش‌های قبلی با همین نام در گزارش می‌مانند.") },
            confirmButton = { TextButton(onClick = { prefs.setPayMethods(list.filter { it.id != m.id }); del = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("انصراف") } },
        )
    }
}
