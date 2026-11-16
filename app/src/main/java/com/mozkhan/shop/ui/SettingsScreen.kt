package com.mozkhan.shop.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Jalali
import com.mozkhan.shop.util.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainVm, activity: Activity, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    val cur by prefs.currency.collectAsState()
    val theme by prefs.theme.collectAsState()
    val sound by prefs.sound.collectAsState()
    val vib by prefs.vibrate.collectAsState()
    val auto by prefs.autoAddFlow.collectAsState()
    val idle by prefs.cameraIdle.collectAsState()
    val shop by prefs.shopName.collectAsState()
    val cloud by prefs.cloudSync.collectAsState()
    val st by vm.sync.state.collectAsState()
    var busy by remember { mutableStateOf(false) }

    var apiKey by remember { mutableStateOf(prefs.fbApiKey) }
    var appId by remember { mutableStateOf(prefs.fbAppId) }
    var projectId by remember { mutableStateOf(prefs.fbProjectId) }
    var webId by remember { mutableStateOf(prefs.fbWebClientId) }
    var shopCode by remember { mutableStateOf(prefs.shopCode) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = vm.repo.exportJson()
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }.onSuccess { toast("نسخهٔ پشتیبان ذخیره شد") }.onFailure { toast("خطا در ذخیره: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.orEmpty()
                vm.repo.importJson(text)
            }.onSuccess { toast("اطلاعات با موفقیت بازیابی شد") }
                .onFailure { toast(if (it.message == com.mozkhan.shop.data.BackupParser.NEWER_FILE) it.message!! else "فایل پشتیبان معتبر نیست") }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
            Text("تنظیمات", style = MaterialTheme.typography.headlineSmall)
        }

        Group("نام این گوشی (فروشنده)") {
            var devName by remember { mutableStateOf(prefs.deviceName) }
            OutlinedTextField(
                value = devName, onValueChange = { devName = it; prefs.deviceName = it }, singleLine = true,
                label = { Text("مثلاً: صندوق ۱ / علی") }, modifier = Modifier.fillMaxWidth(),
            )
            Text("این نام روی هر فاکتوری که با این گوشی ثبت شود به‌عنوان فروشنده ذخیره و نمایش داده می‌شود و با همگام‌سازی روی همهٔ گوشی‌ها دیده می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Group("نقش این گوشی") {
            val salesOnly by prefs.salesOnly.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!salesOnly, { prefs.setSalesOnly(false) }, label = { Text("گوشی اصلی") })
                FilterChip(salesOnly, { prefs.setSalesOnly(true) }, label = { Text("فقط فروش") })
            }
            Text(
                if (salesOnly) "این گوشی فقط می‌تواند بفروشد، دریافتی نسیه و هزینه ثبت کند. تعریف و ویرایش کالا، خرید جدید، کافه و خروج از انبار بسته است."
                else "گوشی اصلی همهٔ کارها را انجام می‌دهد. گوشی‌های فروش را روی «فقط فروش» بگذارید تا کالای تکراری ساخته نشود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Group("فروشگاه") {
            AppField(shop, prefs::setShopName, "نام فروشگاه", Modifier.fillMaxWidth())
        }

        Group("نمایش") {
            Text("واحد پول", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Currency.entries.forEach { c -> FilterChip(cur == c, { prefs.setCurrency(c) }, label = { Text(c.fa) }) }
            }
            Text("قیمت‌ها داخل برنامه به تومان ذخیره می‌شوند؛ با تغییر واحد فقط نحوهٔ نمایش و ورود عدد عوض می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("تم", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> FilterChip(theme == m, { prefs.setTheme(m) }, label = { Text(m.fa) }) }
            }
        }

        Group("اسکن، صدا و باتری") {
            SwitchRow("بوق فروشگاهی هنگام اسکن", sound, prefs::setSound)
            SwitchRow("لرزش", vib, prefs::setVibrate)
            SwitchRow("افزودن خودکار کالا به فاکتور (بدون تأیید)", auto) { prefs.autoAdd = it }
            val vision by prefs.visionLevel.collectAsState()
            Text("حساسیت تشخیص تصویری کالا", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "راحت‌گیر", 1 to "متعادل", 2 to "سخت‌گیر").forEach { (v, label) ->
                    FilterChip(vision == v, { prefs.setVisionLevel(v) }, label = { Text(label) })
                }
            }
            Text("اگر کالا را دیر شناخت «راحت‌گیر»، و اگر کالای اشتباه پیشنهاد داد «سخت‌گیر» را بزنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var modelTick by remember { mutableStateOf(0) }
            Text(com.mozkhan.shop.ml.ImageFingerprint.status().also { modelTick.hashCode() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                com.mozkhan.shop.ml.ImageFingerprint.init(ctx, retry = true); modelTick++
            }) { Text("بارگذاری دوبارهٔ مدل هوشمند / بروزرسانی وضعیت") }
            Text("خاموشی خودکار دوربین (صرفه‌جویی باتری)", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(30 to "۳۰ ثانیه", 60 to "۱ دقیقه", 180 to "۳ دقیقه", 0 to "هرگز")) { (s, label) ->
                    FilterChip(idle == s, { prefs.setCameraIdle(s) }, label = { Text(label) })
                }
            }
        }

        Group("تنظیمات Google و همگام‌سازی چند گوشی") {
            val sha = remember { com.mozkhan.shop.util.Diag.signingSha1(ctx) }
            if (sha.isNotEmpty()) Text(
                "امضای این نصب (SHA-1) — باید در Firebase ثبت شده باشد:\n$sha",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBadge(if (st.running) Icons.Filled.CloudDone else Icons.Filled.CloudOff, if (st.running) MaterialTheme.extra.profit else MaterialTheme.colorScheme.outline, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !st.available -> "هنوز به Google/Firebase وصل نشده"
                            st.running -> "فعال و متصل"
                            st.email != null || prefs.shopCode.isNotEmpty() -> "متصل شده، همگام‌سازی خاموش است"
                            else -> "کد فروشگاه یا حساب Google تنظیم نشده"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        st.email ?: if (st.lastSync > 0) "آخرین همگام‌سازی " + Jalali.time(st.lastSync) else "داده‌ها فعلاً فقط روی همین گوشی است",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!st.available) {
                Text(
                    "۱) در console.firebase.google.com یک پروژه بسازید و یک اپ اندروید با پکیج com.mozkhan.shop اضافه کنید.\n" +
                        "۲) Authentication ← Sign-in method ← Anonymous را فعال کنید (برای ورود با Google هم، Google و SHA-1). Firestore را بسازید و در Rules فقط کاربران واردشده را مجاز کنید: allow read, write: if request.auth != null;\n" +
                        "۳) مقادیر زیر را از Project settings وارد و ذخیره کنید.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppField(apiKey, { apiKey = it.trim() }, "API key (Web API Key)", Modifier.fillMaxWidth())
                AppField(appId, { appId = it.trim() }, "App ID (1:123:android:abc…)", Modifier.fillMaxWidth())
                AppField(projectId, { projectId = it.trim() }, "Project ID", Modifier.fillMaxWidth())
                AppField(webId, { webId = it.trim() }, "Web client ID (فقط برای ورود Google؛ اختیاری)", Modifier.fillMaxWidth())
                Button(
                    enabled = apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank(),
                    onClick = {
                        prefs.fbApiKey = apiKey; prefs.fbAppId = appId; prefs.fbProjectId = projectId; prefs.fbWebClientId = webId
                        vm.sync.configureFromPrefs()
                            .onSuccess { toast("ذخیره شد؛ حالا کد فروشگاه بسازید یا وارد کنید") }
                            .onFailure { toast(it.message ?: "تنظیمات نامعتبر است") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ذخیرهٔ تنظیمات Firebase") }
            } else {
                // ---- sync WITHOUT a Google account: a shared shop code + silent anonymous auth
                Text("همگام‌سازی با کد فروشگاه (بدون نیاز به حساب Google)", style = MaterialTheme.typography.labelLarge)
                AppField(shopCode, { shopCode = it.trim() }, "کد فروشگاه", Modifier.fillMaxWidth())
                Text(
                    "روی گوشی اصلی «ساخت کد جدید» را بزنید، همان کد را در گوشی‌های دیگر وارد کنید. کد را به کسی ندهید؛ هرکس کد را داشته باشد به اطلاعات فروشگاه دسترسی دارد.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runCatching { vm.sync.useShopCode(null) }
                            .onSuccess { shopCode = it; prefs.setCloudSync(true); vm.sync.start(); toast("کد ساخته شد؛ آن را یادداشت کنید") }
                            .onFailure { toast(it.message ?: "خطا") }
                    }, modifier = Modifier.weight(1f)) { Text("ساخت کد جدید") }
                    OutlinedButton(enabled = shopCode.isNotBlank() && shopCode != prefs.shopCode, onClick = {
                        runCatching { vm.sync.useShopCode(shopCode) }
                            .onSuccess { prefs.setCloudSync(true); vm.sync.start(); toast("متصل شد") }
                            .onFailure { toast(it.message ?: "کد نامعتبر") }
                    }, modifier = Modifier.weight(1f)) { Text("اتصال با این کد") }
                }
                if (prefs.shopCode.isNotEmpty()) {
                    // the code can always be read back from any connected phone; copy it, send it to yourself, keep it safe
                    Text("کد فعلی این فروشگاه:", style = MaterialTheme.typography.labelLarge)
                    Text(prefs.shopCode, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("shop code", prefs.shopCode))
                            toast("کد کپی شد")
                        }, modifier = Modifier.weight(1f)) { Text("کپی کد") }
                        OutlinedButton(onClick = {
                            ctx.startActivity(
                                android.content.Intent.createChooser(
                                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_TEXT, "کد فروشگاه MozKhan: ${prefs.shopCode}"),
                                    "ارسال کد",
                                ),
                            )
                        }, modifier = Modifier.weight(1f)) { Text("ارسال کد") }
                    }
                    Text(
                        "اگر کد گم شد: از «تنظیمات» هر گوشی‌ای که هنوز وصل است می‌توانید دوباره آن را ببینید. فایل «ذخیرهٔ پشتیبان» هم کد را نگه می‌دارد و با «بازیابی» روی گوشی جدید برمی‌گردد. اطلاعات فروش هیچ‌وقت با گم شدن کد پاک نمی‌شود؛ روی خود گوشی می‌ماند.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("همگام‌سازی خودکار", cloud) { on ->
                        prefs.setCloudSync(on)
                        if (on) vm.sync.start() else vm.sync.stop()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.sync.syncNow(); toast("همگام‌سازی شروع شد") }, enabled = cloud, modifier = Modifier.weight(1f)) { Text("همگام‌سازی اکنون") }
                        OutlinedButton(onClick = {
                            // disconnecting forgets the code on this phone: put it on the clipboard first so it can be re-entered
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("shop code", prefs.shopCode))
                            toast("کد قبلی در کلیپ‌بورد ذخیره شد؛ برای اتصال دوباره همان را وارد کنید")
                            prefs.setCloudSync(false); vm.sync.leaveShopCode(); shopCode = ""
                        }, modifier = Modifier.weight(1f)) { Text("قطع اتصال") }
                    }
                    OutlinedButton(enabled = !busy, onClick = {
                        busy = true; testMsg = "در حال آزمایش اتصال…"
                        scope.launch {
                            val r = vm.sync.testConnection()
                            busy = false
                            testMsg = r.getOrElse { it.message ?: "ناموفق" }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("آزمایش اتصال به ابر") }
                    testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("اتصال برقرار")) MaterialTheme.extra.profit else MaterialTheme.colorScheme.onSurface) }
                }
                Text("— یا (اختیاری) با حساب Google —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (prefs.shopCode.isNotEmpty()) {
                    // shop code is active: Google sign-in is not needed
                } else if (st.email == null) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val r = vm.sync.signIn(activity)
                            busy = false
                            r.onSuccess { prefs.setCloudSync(true); vm.sync.start(); toast("وارد شدید: $it") }
                                .onFailure { toast(it.message ?: "ورود ناموفق") }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("ورود با حساب Google") }
                } else {
                    SwitchRow("همگام‌سازی خودکار", cloud) { on ->
                        prefs.setCloudSync(on)
                        if (on) vm.sync.start() else vm.sync.stop()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.sync.syncNow(); toast("همگام‌سازی شروع شد") }, enabled = cloud, modifier = Modifier.weight(1f)) { Text("همگام‌سازی اکنون") }
                        OutlinedButton(onClick = { prefs.setCloudSync(false); vm.sync.signOut() }, modifier = Modifier.weight(1f)) { Text("خروج از حساب") }
                    }
                }
                st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("بدون اینترنت هم می‌توانید کار کنید؛ تغییرات پس از اتصال خودکار ارسال می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Group("به‌روزرسانی برنامه") {
            val upd by vm.updater.state.collectAsState()
            Text("نسخهٔ نصب‌شده: ${Fmt.digits(com.mozkhan.shop.BuildConfig.VERSION_NAME)}", style = MaterialTheme.typography.titleSmall)
            if (upd.available) Text("نسخهٔ جدید: ${Fmt.digits(upd.latestName)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            if (upd.downloading) Text("در حال دانلود… ${Fmt.digits("${(upd.progress * 100).toInt()}٪")}", style = MaterialTheme.typography.bodySmall)
            upd.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !upd.checking && !upd.downloading, onClick = { vm.updater.check() }, modifier = Modifier.weight(1f)) {
                    Text(if (upd.checking) "در حال بررسی…" else "بررسی نسخهٔ جدید")
                }
                if (upd.available) Button(enabled = !upd.downloading, onClick = {
                    if (upd.downloaded != null) vm.updater.install()?.let { toast(it) } else vm.updater.download()
                }, modifier = Modifier.weight(1f)) { Text(if (upd.downloaded != null) "نصب" else "دانلود و نصب") }
            }
        }

        Group("پشتیبان‌گیری") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportLauncher.launch("mozkhan-backup-" + Jalali.date(System.currentTimeMillis()).replace('/', '-') + ".json") }, modifier = Modifier.weight(1f)) { Text("ذخیرهٔ پشتیبان") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("بازیابی") }
            }
        }
        Text("MozKhan • نسخهٔ ${Fmt.digits(com.mozkhan.shop.BuildConfig.VERSION_NAME)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
