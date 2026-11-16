package com.mozkhan.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Fmt

@Composable
fun MoreScreen(vm: MainVm, onGo: (String) -> Unit) {
    val cur by vm.currency.collectAsState()
    val debtors by vm.debtors.collectAsState()
    val debt = debtors.sumOf { it.debt.coerceAtLeast(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("بیشتر", style = MaterialTheme.typography.headlineSmall)
        Item("کافه", "انبار مواد، دستور ساخت و قیمت تمام‌شدهٔ نوشیدنی‌ها", Icons.Filled.Coffee, MaterialTheme.extra.profit) { onGo("cafe") }
        Item("نسیه و مشتریان", if (debt > 0) "جمع بدهی: " + Fmt.money(debt, cur) else "ثبت بدهی و دریافتی مشتریان", Icons.Filled.Group, MaterialTheme.extra.warn) { onGo("credit") }
        Item("گزارش انبار", "ارزش موجودی، ورود و خروج، کمبود و راکد", Icons.Filled.Inventory, MaterialTheme.colorScheme.primary) { onGo("inventory") }
        Item("خروج از انبار", "ضایعات، مصرف شخصی، هدیه — بدون فروش", Icons.Filled.Outbox, MaterialTheme.colorScheme.secondary) { onGo("exits") }
        Item("تنظیمات", "واحد پول، تم، صدا، Google و پشتیبان", Icons.Filled.Settings, MaterialTheme.colorScheme.onSurfaceVariant) { onGo("settings") }
    }
}

@Composable
private fun Item(title: String, sub: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, tint, size = 48)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
