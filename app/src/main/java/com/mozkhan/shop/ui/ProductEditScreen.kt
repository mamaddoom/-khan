package com.mozkhan.shop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mozkhan.shop.data.CAT_COFFEE
import com.mozkhan.shop.data.CAT_COFFEE_BULK
import com.mozkhan.shop.data.Codes
import com.mozkhan.shop.data.KIND_DRINK
import com.mozkhan.shop.data.KIND_GOODS
import com.mozkhan.shop.data.Measure
import com.mozkhan.shop.data.Product
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.data.newId
import com.mozkhan.shop.ml.CameraView
import com.mozkhan.shop.ml.FrameGrabber
import com.mozkhan.shop.ml.ImageFingerprint
import com.mozkhan.shop.ml.Suggest
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.Prices
import kotlinx.coroutines.launch

/** Categories where a carton ("باکس") is the normal way of buying: the box section is on by default. */
private val boxByDefault = setOf("سیگار", "تنباکو")

@Composable
fun ProductEditScreen(
    vm: MainVm, id: String?, barcode: String?, preset: String?, mode: String?,
    onClose: () -> Unit, onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur by vm.currency.collectAsState()
    val cats by vm.categories.collectAsState()

    val coffeeBulk = preset == "coffee_bulk"
    val coffeeCup = preset == "coffee_cup"

    var loaded by remember { mutableStateOf(id == null) }
    var raw by remember { mutableStateOf<Product?>(null) }
    var live by remember { mutableStateOf<ProductWithStock?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeEdited by remember { mutableStateOf(false) }
    var barcodeText by remember { mutableStateOf(barcode.orEmpty()) }
    var hasBox by remember { mutableStateOf(false) }
    var boxCode by remember { mutableStateOf("") }
    var boxSize by remember { mutableStateOf("10") }
    var boxBuy by remember { mutableStateOf(0L) }
    var boxSell by remember { mutableStateOf(0L) }
    var category by remember {
        mutableStateOf(if (coffeeBulk) CAT_COFFEE_BULK else if (coffeeCup) CAT_COFFEE else vm.prefs.lastCategory.ifBlank { "سیگار" })
    }
    val allProducts by vm.allProducts.collectAsState()
    // a second product with the same name is almost always a mistake (the scan of a different barcode, a typo...)
    val sameName = remember(name, allProducts) {
        val n = name.trim()
        if (n.length < 2) null else allProducts.firstOrNull { it.id != id && it.name.trim().equals(n, ignoreCase = true) }
    }
    var measure by remember { mutableStateOf(if (coffeeBulk) Measure.WEIGHT else Measure.PIECE) }
    var trackStock by remember { mutableStateOf(!coffeeCup) }
    var buy by remember { mutableStateOf(0L) }
    var sell by remember { mutableStateOf(0L) }
    var minStock by remember { mutableStateOf("") }
    var stockIn by remember { mutableStateOf("") }
    var openingIsPurchase by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(coffeeCup || coffeeBulk) }
    val prints = remember { mutableStateListOf<String>() }
    val thumbs = remember { mutableStateListOf<Bitmap>() }
    val tokenCounts = remember { mutableStateMapOfTokens() }
    var fullNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameraOpen by remember { mutableStateOf(id == null && barcode == null && preset == null && mode != "restock") }
    var scanTarget by remember { mutableStateOf("pack") } // pack | box
    var testMode by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<List<ImageFingerprint.Match>>(emptyList()) }
    var hasFlash by remember { mutableStateOf(true) }
    var torch by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var askLeave by remember { mutableStateOf(false) }
    var newCat by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restockOpen by remember { mutableStateOf(false) }
    var boxAnswer by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var askLowPrice by remember { mutableStateOf(false) }
    val grabber = remember { FrameGrabber() }

    // ML Kit clients: created once per screen and closed again (they hold native resources)
    val textClient = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val labelClient = remember { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) { onDispose { runCatching { textClient.close() }; runCatching { labelClient.close() } } }

    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }

    LaunchedEffect(id, reload) {
        if (id != null) {
            val r = vm.repo.raw(id)
            val l = vm.repo.byId(id)
            live = l
            if (r != null && raw == null) {
                raw = r
                name = r.name; code = r.code; barcodeText = r.barcode.orEmpty(); category = r.category; measure = r.unit
                buy = r.buyPrice; sell = r.sellPrice; favorite = r.favorite; trackStock = r.trackStock
                hasBox = r.boxSize > 1; boxCode = r.boxBarcode.orEmpty(); boxSize = if (r.boxSize > 1) r.boxSize.toString() else "10"
                boxBuy = r.boxBuyPrice; boxSell = r.boxSellPrice
                minStock = if (r.minStock > 0) (if (r.unit != Measure.PIECE) r.minStock / 1000 else r.minStock).toString().removeSuffix(".0") else ""
                prints.clear(); prints.addAll(r.fingerprints.split('|').filter { it.isNotBlank() })
            } else if (r != null) {
                // after a restock the prices changed in the database: show them
                buy = r.buyPrice; sell = r.sellPrice; boxBuy = r.boxBuyPrice; boxSell = r.boxSellPrice
            }
            loaded = true
        } else if (!codeEdited && code.isEmpty()) code = vm.repo.nextCode()
    }
    LaunchedEffect(loaded, mode) { if (loaded && mode == "restock" && live != null) restockOpen = true }
    LaunchedEffect(Unit) { if (cameraOpen && !hasCam) permLauncher.launch(Manifest.permission.CAMERA) }
    BackHandler(enabled = dirty) { askLeave = true }

    // the question "does it need a box?" is remembered per category; cigarettes and tobacco are always yes
    LaunchedEffect(category, loaded) {
        boxAnswer = if (category in boxByDefault) true else vm.prefs.boxAnswer(category)
        if (loaded && id == null && category in boxByDefault && measure == Measure.PIECE) hasBox = true
    }

    fun onScanned(c: String) {
        scope.launch {
            val ex = vm.repo.byBarcode(c)
            if (ex != null && ex.id != id) {
                error = null
                existing = ex.id to ex.name
                cameraOpen = false
            } else {
                error = null; dirty = true
                val norm = Codes.normalize(c)
                if (scanTarget == "box") { boxCode = norm; hasBox = true } else barcodeText = norm
                vm.touch()
                cameraOpen = false
            }
        }
    }

    fun takePhoto() {
        if (prints.size >= 6) { error = "حداکثر ۶ عکس مرجع کافی است"; return }
        grabber.grab { bmp ->
            val fp = ImageFingerprint.compute(bmp)
            if (fp == null) {
                scope.launch { error = "مدل هوشمند هنوز آماده نیست؛ چند ثانیه صبر کنید و دوباره عکس بگیرید" }
                return@grab
            }
            val thumb = Bitmap.createScaledBitmap(bmp, 96, (96f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            // enrollment check: warn when the picture looks like a different product
            val other = ImageFingerprint.mostSimilarOther(bmp, vm.products.value, vm.prints.value, id)
            scope.launch {
                prints.add(fp); thumbs.add(thumb); dirty = true
                warning = if (other != null && other.score >= ImageFingerprint.sureScore)
                    "این عکس شبیه «${other.product.name}» است؛ زاویهٔ متفاوت‌تری بگیرید یا مطمئن شوید کالای درست است." else null
                runCatching {
                    val img = InputImage.fromBitmap(bmp, 0)
                    textClient.process(img).addOnSuccessListener { t ->
                        val lines = t.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                        labelClient.process(img)
                            .addOnSuccessListener { labels ->
                                val r = Suggest.from(lines, labels.filter { it.confidence >= 0.55f }.map { it.text })
                                tokenCounts.add(r.tokens); fullNames = (r.names + fullNames).distinct().take(8)
                            }
                            .addOnFailureListener { val r = Suggest.from(lines, emptyList()); tokenCounts.add(r.tokens); fullNames = (r.names + fullNames).distinct().take(8) }
                    }
                }
            }
        }
    }

    fun save(confirmedLowPrice: Boolean = false) {
        if (saving) return // a second tap while the first save is running would create a duplicate product
        val n = name.trim()
        if (n.isEmpty()) { error = "نام کالا را وارد کنید"; return }
        if (sell <= 0) { error = "قیمت فروش را وارد کنید"; return }
        if (code.isBlank()) { error = "کد کالا را وارد کنید"; return }
        val size = boxSize.toIntOrNull() ?: 0
        if (hasBox && measure == Measure.PIECE && (size < 2 || boxSell <= 0)) { error = "برای باکس، تعداد داخل باکس و قیمت فروش باکس را وارد کنید"; return }
        val boxLoss = hasBox && measure == Measure.PIECE && boxBuy > 0 && boxSell in 1 until boxBuy
        if (!confirmedLowPrice && ((buy > 0 && sell < buy) || boxLoss)) { askLowPrice = true; return }
        saving = true
        scope.launch {
            try {
                val r0 = raw
                // stock and sales are stored in base units (piece / gram / ml): switching the measure of a product
                // that already has history would silently turn e.g. 20 packs into 20 grams
                if (r0 != null && r0.unit != measure && vm.repo.hasHistory(r0.id)) {
                    error = "نحوهٔ فروش (تعدادی/وزنی) این کالا قابل تغییر نیست چون موجودی یا فروش ثبت‌شده دارد. " +
                        "اگر لازم است، این کالا را حذف و کالای جدیدی با نحوهٔ فروش درست تعریف کنید."
                    measure = r0.unit
                    return@launch
                }
                val c = Codes.normalize(code)
                if (vm.repo.codeTaken(c, id)) { error = "کد «${Fmt.digits(c)}» برای کالای دیگری ثبت شده است"; return@launch }
                val b = Codes.normalize(barcodeText).ifEmpty { null }
                val bc = (if (hasBox) Codes.normalize(boxCode).ifEmpty { null } else null)
                for (x in listOfNotNull(b, bc)) {
                    val ex = vm.repo.byBarcode(x)
                    if (ex != null && ex.id != id) { error = "بارکد ${Fmt.digits(x)} برای «${ex.name}» ثبت شده است"; return@launch }
                }
                if (b != null && b == bc) { error = "بارکد پاکت و باکس نباید یکی باشد"; return@launch }
                val factor = if (measure != Measure.PIECE) 1000.0 else 1.0
                val boxed = hasBox && measure == Measure.PIECE
                // always start from the stored row so fields this screen does not show can never be lost
                val base = raw ?: Product(id = newId(), name = n, kind = if (coffeeCup) KIND_DRINK else KIND_GOODS)
                val p = base.copy(
                    name = n, barcode = b, category = category.trim().ifEmpty { "سایر" }, unit = measure,
                    buyPrice = buy, sellPrice = sell,
                    minStock = if (trackStock) Fmt.parseDouble(minStock) * factor else 0.0,
                    fingerprints = prints.joinToString("|"), favorite = favorite,
                    boxBarcode = if (boxed) bc else null, boxSize = if (boxed) size else 0,
                    boxBuyPrice = if (boxed) boxBuy else 0, boxSellPrice = if (boxed) boxSell else 0,
                    trackStock = trackStock, code = c,
                )
                val delta = if (trackStock) Fmt.parseDouble(stockIn) * factor else 0.0
                // written directly (not fire-and-forget) so the guard stays up until the row really exists
                val isNew = raw == null
                val asPurchase = openingIsPurchase
                vm.persist {
                    if (isNew) {
                        vm.repo.saveProduct(p, if (delta != 0.0) delta else null, asPurchase)
                        if (!coffeeBulk && !coffeeCup) vm.prefs.lastCategory = p.category
                    } else {
                        vm.repo.saveProduct(p, null)
                        if (delta != 0.0) vm.repo.adjustStock(p.id, delta, "ADJUST")
                    }
                }
                dirty = false
                onClose()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // the screen was closed: the save itself still finishes (vm.persist)
            } catch (e: Exception) {
                error = "ذخیره نشد: ${e.message}"
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (dirty) askLeave = true else onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") }
            Text(
                if (id != null) "ویرایش کالا" else if (coffeeBulk) "قهوه فله جدید" else if (coffeeCup) "قهوه جدید" else "کالای جدید",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
            )
            if (id != null) IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.DeleteOutline, "حذف کالا", tint = MaterialTheme.colorScheme.error) }
        }
        if (!loaded) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // ------------------------------------------------ restock (existing products)
            live?.let { l ->
                AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(l.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "موجودی: " + Fmt.qtyOf(l.stock, l.unit) + " • خرید " + Fmt.money(l.buyPrice, cur) + " • فروش " + Fmt.money(l.sellPrice, cur),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (l.trackStock) Button(onClick = { restockOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("خرید جدید / افزایش موجودی و قیمت") }
                    }
                }
            }

            // ------------------------------------------------ smart identification
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("شناسایی هوشمند", style = MaterialTheme.typography.titleSmall)
                    if (cameraOpen) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(scanTarget == "pack", { scanTarget = "pack" }, label = { Text("بارکد پاکت / عدد") })
                            if (measure == Measure.PIECE) FilterChip(scanTarget == "box", { scanTarget = "box" }, label = { Text("بارکد باکس") })
                        }
                        Box(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                            if (hasCam) {
                                CameraView(
                                    Modifier.fillMaxSize(), scanBarcodes = true, frameIntervalMs = if (testMode) 700 else 0, torch = torch, grabber = grabber,
                                    onBarcode = { onScanned(it) },
                                    onFrame = { bmp -> if (testMode) testResults = ImageFingerprint.match(bmp, vm.products.value.filter { it.hasPrints }, vm.prints.value, 3) },
                                    onTorchAvailable = { hasFlash = it },
                                )
                                ScanFrame(false)
                                if (hasFlash) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                                    CamBtn(if (torch) Icons.Filled.AutoFixHigh else Icons.Filled.QrCodeScanner, "چراغ", torch) { torch = !torch }
                                }
                            } else TextButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.align(Alignment.Center)) { Text("اجازهٔ دوربین") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::takePhoto, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AddAPhoto, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("عکس مرجع از کالا")
                            }
                            OutlinedButton(onClick = { cameraOpen = false; testMode = false }) { Text("بستن") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("آزمایش تشخیص (نمایش امتیاز زنده)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(testMode, { testMode = it; testResults = emptyList() })
                        }
                        if (testMode) {
                            if (testResults.isEmpty()) Text("کالایی شبیه این تصویر پیدا نشد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            testResults.forEach { m ->
                                Text(
                                    m.product.name + " — " + Fmt.digits("${(m.score * 100).toInt()}٪") + if (m.legacy) " (عکس قدیمی)" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                ImageFingerprint.status(),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("بارکد خودکار خوانده می‌شود. برای کالاهای بدون بارکد، جلوی کالا را از ۳ تا ۵ زاویه عکس بگیرید. عکس‌ها ذخیره نمی‌شوند؛ فقط یک اثر انگشت کوچک.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(onClick = { cameraOpen = true; if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("اسکن بارکد / عکس کالا")
                        }
                    }
                    if (prints.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // iterate a snapshot: the list can be cleared while the row is laid out (index race)
                            val shots = thumbs.toList()
                            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(shots) { bmp ->
                                    Image(bmp.asImageBitmap(), "عکس مرجع", Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                }
                            }
                            Pill(Fmt.digits("${prints.size} عکس مرجع"), MaterialTheme.extra.profit)
                            IconButton(onClick = { prints.clear(); thumbs.clear(); dirty = true }) { Icon(Icons.Filled.DeleteOutline, "حذف عکس‌ها") }
                        }
                    }
                    warning?.let { Text(it, color = MaterialTheme.extra.warn, style = MaterialTheme.typography.bodySmall) }
                    if (tokenCounts.tokens().isNotEmpty() || fullNames.isNotEmpty()) {
                        Text("پیشنهاد از روی تصویر — هر مورد را بزنید تا به نام اضافه شود:", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(tokenCounts.tokens()) { tk ->
                                val present = name.contains(tk)
                                FilterChip(present, {
                                    if (!present) { name = (name.trim() + " " + tk).trim(); dirty = true }
                                    if (tk in listOf("سیگار", "فندک", "تنباکو", "ذغال", "قهوه", "پیپ و لوازم")) category = tk
                                }, label = { Text("+ $tk") })
                            }
                        }
                        if (fullNames.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(fullNames) { s -> FilterChip(name == s, { name = s; dirty = true }, label = { Text(s) }) }
                        }
                    }
                }
            }

            // ------------------------------------------------ basic info
            AppField(name, { name = it; dirty = true }, "نام کالا", Modifier.fillMaxWidth())
            sameName?.let { s ->
                Text(
                    "کالایی با همین نام قبلاً ثبت شده (کد " + Fmt.digits(s.code) + "). اگر همان کالاست، به جای تعریف دوباره آن را ویرایش کنید.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                )
            }
            AppField(
                code, { code = Fmt.normalize(it); codeEdited = true; dirty = true }, "کد کالا (برای جستجو)", Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number,
                trailing = {
                    IconButton(onClick = { scope.launch { code = vm.repo.nextCode(); codeEdited = true; dirty = true } }) { Icon(Icons.Filled.AutoFixHigh, "کد خودکار") }
                },
            )
            AppField(
                barcodeText, { barcodeText = Fmt.normalize(it); dirty = true }, if (measure == Measure.PIECE) "بارکد پاکت / عدد (اختیاری)" else "بارکد (اختیاری)", Modifier.fillMaxWidth(),
                keyboardType = KeyboardType.Number, placeholder = "برای کالاهای بدون بارکد خالی بگذارید",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("دسته‌بندی", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { newCat = true }) { Text("+ دستهٔ جدید") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((cats + category).distinct()) { c -> FilterChip(category == c, { category = c; dirty = true }, label = { Text(c) }) }
            }
            if (!coffeeBulk && !coffeeCup && raw?.kind != KIND_DRINK) {
                Text("نحوهٔ فروش", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(measure == Measure.PIECE, { measure = Measure.PIECE; dirty = true }, label = { Text("تعدادی") })
                    FilterChip(measure == Measure.WEIGHT, { measure = Measure.WEIGHT; hasBox = false; dirty = true }, label = { Text("وزنی (تنباکو، ذغال…)") })
                }
                raw?.let { r ->
                    if (r.unit != measure) Text(
                        "توجه: موجودی و فروش‌های قبلی به واحد قبلی ثبت شده‌اند؛ اگر این کالا سابقه داشته باشد، تغییر ذخیره نمی‌شود.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                    )
                }
            }

            // ------------------------------------------------ pricing
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val w = measure != Measure.PIECE
                    val perUnit = if (measure == Measure.VOLUME) "لیتر" else "کیلو"
                    Text(if (w) "قیمت‌گذاری (هر $perUnit)" else "قیمت‌گذاری (هر عدد)", style = MaterialTheme.typography.titleSmall)
                    MoneyField(if (w) "قیمت خرید هر $perUnit" else "قیمت خرید", buy, cur, { buy = it; dirty = true }, Modifier.fillMaxWidth())
                    MoneyField(if (w) "قیمت فروش هر $perUnit" else "قیمت فروش", sell, cur, { sell = it; dirty = true }, Modifier.fillMaxWidth())
                    if (w && (buy > 0 || sell > 0)) Text(
                        "هر ۱۰۰ " + (if (measure == Measure.VOLUME) "میلی‌لیتر" else "گرم") + ": خرید " + Fmt.money(buy / 10, cur, false) + " • فروش " + Fmt.money(sell / 10, cur) +
                            "   |   هر ۲۵۰: فروش " + Fmt.money(sell / 4, cur, false),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (buy > 0) {
                        Text("تعیین فروش با درصد سود از خرید:", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(3, 5, 8, 10, 15, 20, 30, 50)) { p ->
                                FilterChip(false, { sell = Prices.round(buy * (1 + p / 100.0)); dirty = true }, label = { Text(Fmt.digits("$p٪")) })
                            }
                        }
                    }
                    if (sell > 0) {
                        Text("تغییر سریع قیمت فروش:", style = MaterialTheme.typography.labelMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf(-5, -2, 2, 5, 10)) { p ->
                                FilterChip(false, { sell = Prices.round(sell * (1 + p / 100.0)); dirty = true },
                                    label = { Text(Fmt.digits((if (p > 0) "+" else "") + "$p٪")) })
                            }
                            items(listOf(-1000, 1000, 5000, 10000)) { a ->
                                FilterChip(false, { sell = (sell + a).coerceAtLeast(0); dirty = true },
                                    label = { Text(Fmt.digits((if (a > 0) "+" else "") + Fmt.group((a * cur.factor).toLong()))) })
                            }
                        }
                    }
                    if (buy > 0 && sell > 0) {
                        val profit = sell - buy
                        val margin = profit * 100.0 / sell
                        val markup = profit * 100.0 / buy
                        val col = if (profit >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss
                        Row(Modifier.fillMaxWidth().background(col.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(if (w) "سود هر $perUnit" else "سود هر واحد", style = MaterialTheme.typography.labelSmall)
                                Text(Fmt.money(profit, cur), color = col, style = MaterialTheme.typography.titleSmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("درصد سود از خرید", style = MaterialTheme.typography.labelSmall)
                                Text(Fmt.percent(markup), color = col, style = MaterialTheme.typography.titleSmall)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("حاشیه سود", style = MaterialTheme.typography.labelSmall)
                                Text(Fmt.percent(margin), color = col, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ carton / box
            if (measure == Measure.PIECE && !coffeeCup && raw?.kind != KIND_DRINK) {
                if (boxAnswer == null && !hasBox) {
                    AppCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("نیاز به تعریف باکس می‌باشد؟", style = MaterialTheme.typography.titleSmall)
                            Text("اگر این کالا را باکسی هم می‌خرید یا می‌فروشید، بارکد و قیمت باکس را تعریف کنید. پاسخ برای دستهٔ «$category» یادآوری می‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.prefs.setBoxAnswer(category, true); boxAnswer = true; hasBox = true; dirty = true }) { Text("بله، دارد") }
                                OutlinedButton(onClick = { vm.prefs.setBoxAnswer(category, false); boxAnswer = false }) { Text("خیر") }
                            }
                        }
                    }
                } else if (boxAnswer == true || hasBox) AppCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("باکس (کارتن)", style = MaterialTheme.typography.titleSmall)
                                Text("بارکد و قیمت جداگانه برای فروش باکسی", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(hasBox, { hasBox = it; dirty = true })
                        }
                        if (hasBox) {
                            AppField(
                                boxCode, { boxCode = Fmt.normalize(it); dirty = true }, "بارکد باکس", Modifier.fillMaxWidth(),
                                keyboardType = KeyboardType.Number,
                                trailing = { IconButton(onClick = { scanTarget = "box"; cameraOpen = true; if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }) { Icon(Icons.Filled.QrCodeScanner, "اسکن بارکد باکس") } },
                            )
                            NumberField("تعداد داخل هر باکس", boxSize, { boxSize = it.filter { c -> c.isDigit() }; dirty = true }, Modifier.fillMaxWidth(), suffix = "عدد", decimal = false)
                            val size = boxSize.toIntOrNull() ?: 0
                            MoneyField("قیمت خرید باکس", boxBuy, cur, { boxBuy = it; dirty = true }, Modifier.fillMaxWidth())
                            MoneyField("قیمت فروش باکس", boxSell, cur, { boxSell = it; dirty = true }, Modifier.fillMaxWidth())
                            if (size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (buy > 0) OutlinedButton(onClick = { boxBuy = buy * size; dirty = true }) { Text("خرید = ${Fmt.digits(size.toString())}× پاکت") }
                                    if (sell > 0) OutlinedButton(onClick = { boxSell = sell * size; dirty = true }) { Text("فروش = ${Fmt.digits(size.toString())}× پاکت") }
                                }
                                if (boxSell > 0 && sell > 0) {
                                    val per = boxSell.toDouble() / size
                                    Text(
                                        "قیمت هر عدد در فروش باکسی: " + Fmt.money(Math.round(per), cur) + if (per < sell) "  (ارزان‌تر از فروش تکی)" else "",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (boxSell > 0 && boxBuy > 0) Text("سود هر باکس: " + Fmt.money(boxSell - boxBuy, cur), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.profit)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ stock
            if (!coffeeCup) AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val unitLabel = when (measure) { Measure.WEIGHT -> "کیلوگرم"; Measure.VOLUME -> "لیتر"; else -> "عدد" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("موجودی", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("پیگیری موجودی", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                        Switch(trackStock, { trackStock = it; dirty = true })
                    }
                    if (trackStock) {
                        live?.let { Text("موجودی فعلی: " + Fmt.qtyOf(it.stock, it.unit), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (id == null) {
                            NumberField("موجودی اولیه", stockIn, { stockIn = it; dirty = true }, Modifier.fillMaxWidth(), suffix = unitLabel)
                            if (Fmt.parseDouble(stockIn) > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(openingIsPurchase, { openingIsPurchase = it; dirty = true })
                                Text("این یک خرید جدید است (در گزارش خریدها حساب شود)", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            NumberField("تعدیل موجودی با شمارش انبار (مثبت یا منفی)", stockIn, { stockIn = it.removePrefix("+"); dirty = true }, Modifier.fillMaxWidth(), suffix = unitLabel)
                            Text("برای ثبت خرید جدید از دکمهٔ «خرید جدید» بالای صفحه استفاده کنید تا قیمت خرید و گزارش هم درست شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        NumberField("هشدار کمبود موجودی در", minStock, { minStock = it; dirty = true }, Modifier.fillMaxWidth(), suffix = unitLabel)
                    } else Text("موجودی این کالا ثبت و کم نمی‌شود.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Text("قهوه دانه‌ای بدون موجودی اولیه است؛ فقط فروش تکی با قیمت ثابت. اگر دستور ساخت دارد، از بخش «کافه» تعریف کنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(14.dp),
        ) { Text(if (id == null) "ثبت کالا" else "ذخیرهٔ تغییرات", style = MaterialTheme.typography.titleSmall) }
    }

    if (restockOpen) live?.let { l ->
        RestockDialog(
            l, cur, askSell = true, cheques = vm.cheques.collectAsState().value, onDismiss = { restockOpen = false },
        ) { qty, unitCost, newBuy, newSell, boxBuyN, boxSellN, supplier, pay ->
            vm.restock(l.id, qty, unitCost, newBuy, newSell, boxBuyN, boxSellN, supplier, pay?.existingId, pay?.newCheque) { reload++ }
        }
    }
    existing?.let { (eid, ename) ->
        AlertDialog(
            onDismissRequest = { existing = null },
            title = { Text("این کالا قبلاً ثبت شده") },
            text = { Text("بارکد اسکن‌شده برای «$ename» ثبت شده است. مشخصات کالا را با فرم افزایش موجودی و قیمت جدید باز کنم؟") },
            confirmButton = { TextButton(onClick = { existing = null; onOpen(eid) }) { Text("بله، باز کن") } },
            dismissButton = { TextButton(onClick = { existing = null }) { Text("انصراف") } },
        )
    }
    if (newCat) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newCat = false },
            title = { Text("دستهٔ جدید") },
            text = { AppField(text, { text = it }, "نام دسته", Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { vm.addCategory(text); category = text.trim(); dirty = true; newCat = false }) { Text("افزودن") } },
            dismissButton = { TextButton(onClick = { newCat = false }) { Text("انصراف") } },
        )
    }
    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("حذف کالا") },
        text = { Text("«$name» حذف شود؟ سابقهٔ فروش‌های قبلی حفظ می‌شود.") },
        confirmButton = { TextButton(onClick = { id?.let { vm.deleteProduct(it) }; askDelete = false; onClose() }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("انصراف") } },
    )
    if (askLowPrice) AlertDialog(
        onDismissRequest = { askLowPrice = false },
        title = { Text("قیمت فروش کمتر از خرید") },
        text = {
            Text(
                "قیمت فروش (" + Fmt.money(sell, cur) + ") کمتر از قیمت خرید (" + Fmt.money(buy, cur) + ") است" +
                    (if (hasBox && boxBuy > 0 && boxSell in 1 until boxBuy) " یا باکس زیر قیمت خرید فروخته می‌شود" else "") +
                    "؛ با هر فروش ضرر می‌کنید. همین‌طور ذخیره شود؟",
            )
        },
        confirmButton = { TextButton(onClick = { askLowPrice = false; save(confirmedLowPrice = true) }) { Text("بله، ذخیره کن") } },
        dismissButton = { TextButton(onClick = { askLowPrice = false }) { Text("اصلاح قیمت") } },
    )
    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("خروج بدون ذخیره؟") },
        text = { Text("تغییرات ذخیره نشده از بین می‌رود.") },
        confirmButton = { TextButton(onClick = { askLeave = false; onClose() }) { Text("خروج") } },
        dismissButton = { TextButton(onClick = { askLeave = false }) { Text("ادامه ویرایش") } },
    )
}

/** Suggestion tokens collected over several photos, most frequent first (kept as observable state). */
internal class TokenBag {
    private val counts = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    fun add(tokens: List<String>) { tokens.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    fun tokens(): List<String> = counts.entries.sortedByDescending { it.value }.map { it.key }
}

internal fun mutableStateMapOfTokens() = TokenBag()
