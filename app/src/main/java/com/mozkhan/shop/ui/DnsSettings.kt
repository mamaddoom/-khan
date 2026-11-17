package com.mozkhan.shop.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.net.DnsClient
import com.mozkhan.shop.net.DnsControl
import com.mozkhan.shop.net.DnsPresets
import com.mozkhan.shop.net.DnsVpnService
import com.mozkhan.shop.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings block: which DNS server this app (only this app) uses to find Firebase and the update server. */
@Composable
fun DnsSettings(vm: MainVm) {
    val ctx = LocalContext.current
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    val preset by prefs.dnsPreset.collectAsState()
    val status by DnsVpnService.status.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var m1 by remember { mutableStateOf(prefs.dnsManual1) }
    var m2 by remember { mutableStateOf(prefs.dnsManual2) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun applyNow() {
        if (prefs.dnsServers().isEmpty()) {
            DnsControl.stop(ctx)
            msg = "DNS پیش‌فرض گوشی استفاده می‌شود"
        } else {
            DnsControl.start(ctx)
            msg = null
        }
        // reconnect so Firebase looks its servers up again through the new DNS
        scope.launch { delay(1500); vm.sync.syncNow() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) applyNow()
        else msg = "بدون اجازهٔ VPN نمی‌توان DNS برنامه را عوض کرد"
    }

    fun choose(id: String) {
        prefs.setDnsPreset(id)
        menu = false
        if (prefs.dnsServers().isEmpty()) { applyNow(); return }
        val ask = DnsControl.permissionIntent(ctx)
        if (ask != null) permission.launch(ask) else applyNow()
    }

    val label = when (preset) {
        DnsPresets.OFF -> "خودکار (DNS خود گوشی)"
        DnsPresets.MANUAL -> "دستی"
        else -> DnsPresets.byId(preset)?.let { "${it.name}  —  ${it.primary}" } ?: "خودکار (DNS خود گوشی)"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "سرور DNS که برنامه با آن آدرس Firebase (همگام‌سازی) و به‌روزرسانی را پیدا می‌کند. فقط روی همین برنامه اثر دارد، نه کل گوشی.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(label, Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("خودکار (DNS خود گوشی)") }, onClick = { choose(DnsPresets.OFF) })
                var group = ""
                DnsPresets.all.forEach { p ->
                    if (p.group != group) {
                        group = p.group
                        HorizontalDivider()
                        Text("  " + group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(p.name)
                                Text(p.primary + "  ،  " + p.secondary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { choose(p.id) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("تنظیم دستی…") }, onClick = { prefs.setDnsPreset(DnsPresets.MANUAL); menu = false })
            }
        }

        if (preset == DnsPresets.MANUAL) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(m1, { m1 = it }, Modifier.weight(1f), label = { Text("DNS اول") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = m1.isNotBlank() && !DnsPresets.isIpv4(m1))
                OutlinedTextField(m2, { m2 = it }, Modifier.weight(1f), label = { Text("DNS دوم") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = m2.isNotBlank() && !DnsPresets.isIpv4(m2))
            }
            Button(
                enabled = DnsPresets.isIpv4(m1) && (m2.isBlank() || DnsPresets.isIpv4(m2)),
                onClick = { prefs.dnsManual1 = m1; prefs.dnsManual2 = m2; choose(DnsPresets.MANUAL) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ذخیره و اعمال DNS دستی") }
        }

        Text(
            status ?: if (prefs.dnsServers().isEmpty()) "وضعیت: DNS خود گوشی" else "وضعیت: خاموش (اجازهٔ VPN لازم است؛ دوباره انتخاب کنید)",
            style = MaterialTheme.typography.bodySmall,
            color = if (status != null) MaterialTheme.extra.profit else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = {
                val servers = prefs.dnsServers()
                msg = "در حال آزمایش…"
                scope.launch {
                    msg = withContext(Dispatchers.IO) {
                        if (servers.isEmpty()) {
                            runCatching {
                                val t0 = System.currentTimeMillis()
                                val ip = java.net.InetAddress.getByName("firestore.googleapis.com").hostAddress
                                "DNS گوشی: firestore.googleapis.com ← $ip (${Fmt.digits((System.currentTimeMillis() - t0).toString())} میلی‌ثانیه)"
                            }.getOrElse { "DNS گوشی جواب نداد: ${it.message}" }
                        } else servers.joinToString("\n") { s ->
                            runCatching {
                                val (ips, ms) = DnsClient.resolve(s, "firestore.googleapis.com")
                                "$s ✓  ${ips.firstOrNull() ?: "بدون آدرس"}  (${Fmt.digits(ms.toString())} میلی‌ثانیه)"
                            }.getOrElse { "$s ✗  ${it.message ?: "بدون پاسخ"}" }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("آزمایش DNS (پیدا کردن سرور Firebase)") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text(
            "نکته: اندروید هم‌زمان فقط یک VPN را اجازه می‌دهد. با روشن کردن این گزینه، فیلترشکن دیگر قطع می‌شود؛ اگر با فیلترشکن کار می‌کنید «خودکار» را انتخاب کنید. DNSهای اپراتور معمولاً فقط روی سیم‌کارت همان اپراتور جواب می‌دهند.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
