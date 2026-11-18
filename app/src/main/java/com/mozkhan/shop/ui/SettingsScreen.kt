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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
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

    // backup / restore run in the ViewModel scope (vm.persist): leaving Settings must not leave a half-written file or restore
    val appCtx = ctx.applicationContext
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                vm.persist {
                    val json = vm.repo.exportJson()
                    withContext(Dispatchers.IO) {
                        (appCtx.contentResolver.openOutputStream(uri) ?: error("فایل باز نشد")).use { it.write(json.toByteArray()) }
                    }
                }
                toast("نسخهٔ پشتیبان ذخیره شد")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("خطا در ذخیره: ${e.message}")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                vm.persist {
                    val text = withContext(Dispatchers.IO) { appCtx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.orEmpty()
                    vm.repo.importJson(text)
                }
                toast("اطلاعات با موفقیت بازیابی شد")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(if (e.message == com.mozkhan.shop.data.BackupParser.NEWER_FILE) e.message!! else "فایل پشتیبان معتبر نیست")
            }
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(3 to "خیلی راحت‌گیر", 0 to "راحت‌گیر", 1 to "متعادل", 2 to "سخت‌گیر")) { (v, label) ->
                    FilterChip(vision == v, { prefs.setVisionLevel(v) }, label = { Text(label) })
                }
            }
            Text("اگر کالا را دیر شناخت «راحت‌گیر» یا «خیلی راحت‌گیر» (پیشنهاد سریع‌تر، با یک بار دیدن تأیید باز می‌شود)، و اگر کالای اشتباه پیشنهاد داد «سخت‌گیر» را بزنید.",style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val modelVer by com.mozkhan.shop.ml.ImageFingerprint.changes.collectAsState()
            Text(com.mozkhan.shop.ml.ImageFingerprint.status().also { modelVer.hashCode() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                com.mozkhan.shop.ml.ImageFingerprint.init(ctx, retry = true)
            }) { Text("بارگذاری دوبارهٔ مدل هوشمند / بروزرسانی وضعیت") }
            Text("خاموشی خودکار دوربین (صرفه‌جویی باتری)", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(30 to "۳۰ ثانیه", 60 to "۱ دقیقه", 180 to "۳ دقیقه", 0 to "هرگز")) { (s, label) ->
                    FilterChip(idle == s, { prefs.setCameraIdle(s) }, label = { Text(label) })
                }
            }
        }

        Group("روش‌های پرداخت") { PayMethodsSettings(prefs) }

        Group("دستیار صوتی فارسی (آفلاین)") {
            Text(
                "در صفحهٔ فروش دکمهٔ میکروفون کنار جستجو را بزنید و بگویید: «دو تا بهمن و یک وینستون»، «دویست گرم قهوه»، «تخفیف ده درصد»، «حذف بهمن»، «ثبت کن کارت به کارت». " +
                    "در تعریف کالا، میکروفون کنار نام: «بهمن کوچک، قیمت خرید نود هزار، فروش صد هزار، موجودی پنجاه، دسته سیگار». صدا از گوشی خارج نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VoiceModelCard()
            val speak by prefs.voiceSpeak.collectAsState()
            val ttsSt by com.mozkhan.shop.voice.VoiceSpeaker.status.collectAsState()
            LaunchedEffect(Unit) { com.mozkhan.shop.voice.VoiceSpeaker.init(ctx) }
            SwitchRow("پاسخ صوتی (خواندن جواب و نتیجه)", speak, prefs::setVoiceSpeak)
            val lang by prefs.voiceLang.collectAsState()
            if (speak) {
                Text("زبان پاسخ صوتی", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("auto" to "خودکار", "en" to "انگلیسی (آفلاین)", "fa" to "فارسی")) { (v, label) ->
                        FilterChip(lang == v, { prefs.setVoiceLang(v) }, label = { Text(label) })
                    }
                }
                Text(
                    when (lang) {
                        "en" -> "جواب‌ها با صدای انگلیسی خود گوشی خوانده می‌شود (بدون اینترنت، روی تقریباً همهٔ گوشی‌ها). نام کالاها خوانده نمی‌شود؛ روی صفحه هست."
                        "fa" -> "فقط فارسی: اگر صدای فارسی روی گوشی نباشد، چیزی خوانده نمی‌شود."
                        else -> "اگر خود گوشی صدای فارسی داشته باشد فارسی، وگرنه انگلیسی آفلاین."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (lang == "fa") PersianVoiceCard()
                OutlinedButton(onClick = {
                    val rev = vm.todayTotals.value.revenue
                    com.mozkhan.shop.voice.VoiceSpeaker.speak(
                        ctx, "سلام. فروش امروز " + com.mozkhan.shop.voice.Spoken.money(rev, cur),
                        "Hello. Sales today " + com.mozkhan.shop.voice.Spoken.En.money(rev, cur), true, lang,
                    )
                }) { Text("آزمایش صدا") }
                if (lang == "fa" && ttsSt != com.mozkhan.shop.voice.VoiceSpeaker.Status.READY) Text(
                    "صدای فارسی پیدا نشد؛ «خودکار» یا «انگلیسی» را انتخاب کنید.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                )
            }
            val learned by prefs.voiceLearned.collectAsState()
            Text(
                "دستیار از انتخاب‌های شما یاد می‌گیرد: وقتی از بین پیشنهادها کالای درست را بزنید، دفعهٔ بعد همان را مستقیم می‌آورد" +
                    Fmt.digits(" (${learned.size} مورد آموخته)"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (learned.isNotEmpty()) TextButton(onClick = { prefs.clearVoiceLearned() }) { Text("فراموش کردن آموخته‌ها", color = MaterialTheme.colorScheme.error) }
        }

        Group("یادآورها") {
            val remCh by prefs.remindCheques.collectAsState()
            val chDays by prefs.chequeDays.collectAsState()
            val low by prefs.lowAlert.collectAsState()
            SwitchRow("یادآوری سررسید چک", remCh, prefs::setRemindCheques)
            if (remCh) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(1 to "۱ روز قبل", 3 to "۳ روز قبل", 7 to "۷ روز قبل")) { (v, label) ->
                    FilterChip(chDays == v, { prefs.setChequeDays(v) }, label = { Text(label) })
                }
            }
            SwitchRow("هشدار کمبود موجودی", low, prefs::setLowAlert)
            Text(
                "روزی یک بار (بین ۸ صبح تا ۱۰ شب) اعلان می‌آید؛ کمبود موجودی همچنین همان لحظه‌ای که با فروش به حداقل برسد. با زدن اعلان، صفحهٔ چک‌ها یا پیشنهاد خرید باز می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!com.mozkhan.shop.util.Notifier.allowed(ctx)) {
                Text("اعلان‌های برنامه خاموش است.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn)
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                        )
                    }
                }) { Text("روشن کردن اعلان‌ها") }
            }
        }

        Group("DNS اتصال (فقط برای این برنامه)") { DnsSettings(vm) }

        Group("تنظیمات Google و همگام‌سازی چند گوشی") {
            val sha = remember { com.mozkhan.shop.util.Diag.signingSha1(ctx) }
            if (sha.isNotEmpty()) Text(
                "امضای این نصب (SHA-1) — باید در Firebase ثبت شده باشد:\n$sha",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val linkedNow = st.running && st.online == true
                IconBadge(if (linkedNow) Icons.Filled.CloudDone else Icons.Filled.CloudOff, if (linkedNow) MaterialTheme.extra.profit else MaterialTheme.colorScheme.outline, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !st.available -> "هنوز به Google/Firebase وصل نشده"
                            st.running && st.online == false -> "فعال، ولی اتصال به سرور قطع است"
                            st.running && st.online == null -> "فعال، در حال بررسی اتصال…"
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
                                .onFailure { if (it !is kotlinx.coroutines.CancellationException) toast(it.message ?: "ورود ناموفق") }
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
                if (st.pending > 0) {
                    Text(
                        "${Fmt.digits(st.pending.toString())} تغییر این گوشی هنوز به ابر نرسیده؛ با برقراری اینترنت خودکار ارسال می‌شود.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cloud && (prefs.shopCode.isNotEmpty() || st.email != null)) {
                    // repair tool: reads every collection once and uploads what the cloud lacks (costs reads, so not automatic)
                    OutlinedButton(onClick = { vm.sync.fullResync(); toast("همگام‌سازی کامل شروع شد؛ چند لحظه صبر کنید") }, modifier = Modifier.fillMaxWidth()) {
                        Text("همگام‌سازی کامل (مقایسهٔ همهٔ اطلاعات با ابر)")
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
            if (upd.available && upd.notes.isNotBlank()) Text(upd.notes, style = MaterialTheme.typography.bodySmall)
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
            val autoB by prefs.autoBackup.collectAsState()
            val lastB by prefs.lastBackup.collectAsState()
            var backingUp by remember { mutableStateOf(false) }
            SwitchRow("پشتیبان خودکار روزانه", autoB, prefs::setAutoBackup)
            Text(
                (if (android.os.Build.VERSION.SDK_INT >= 29) "هر روز یک فایل در پوشهٔ Download/MozKhan حافظهٔ گوشی ذخیره می‌شود (با حذف برنامه هم می‌ماند)."
                else "هر روز یک فایل در پوشهٔ برنامه روی حافظهٔ گوشی ذخیره می‌شود.") + " فقط ۷ فایل آخر نگه داشته می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lastB > 0) Text(
                "آخرین پشتیبان: " + Jalali.date(lastB) + " — " + Fmt.digits(prefs.lastBackupWhere),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.profit,
            )
            OutlinedButton(enabled = !backingUp, onClick = {
                backingUp = true
                // runs in the ViewModel scope (main thread; the file is written on the IO dispatcher)
                vm.launch {
                    try {
                        val where = com.mozkhan.shop.util.Daily.backup(appCtx as com.mozkhan.shop.ShopApp)
                        toast("پشتیبان ذخیره شد: $where")
                    } finally { backingUp = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (backingUp) "در حال ذخیره…" else "پشتیبان‌گیری همین حالا در حافظهٔ گوشی") }
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
