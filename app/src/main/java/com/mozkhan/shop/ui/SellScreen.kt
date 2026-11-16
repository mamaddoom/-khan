package com.mozkhan.shop.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCartCheckout
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mozkhan.shop.data.CAT_COFFEE
import com.mozkhan.shop.data.CAT_COFFEE_BULK
import com.mozkhan.shop.data.KIND_DRINK
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.ml.CameraView
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellScreen(vm: MainVm, onDefine: (String) -> Unit, onDefinePreset: (String) -> Unit) {
    val ctx = LocalContext.current
    val cur by vm.currency.collectAsState()
    val cart by vm.cart.collectAsState()
    val pending by vm.pending.collectAsState()
    val visual by vm.visual.collectAsState()
    val products by vm.products.collectAsState()
    val discount by vm.discount.collectAsState()
    val customers by vm.customers.collectAsState()
    val idleSec by vm.prefs.cameraIdle.collectAsState()
    val checkingOut by vm.checkingOut.collectAsState()

    var hasCam by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    var tab by remember { mutableIntStateOf(0) }
    var cameraOn by remember { mutableStateOf(true) }
    var idleOff by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(true) }
    var visualOn by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showCheckout by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val subtotal = cart.sumOf { it.total }
    val cost = cart.sumOf { it.cost }
    val total = (subtotal - discount).coerceAtLeast(0)
    val hasPrints = remember(products) { products.any { it.hasPrints } }
    val cameraActive = cameraOn && tab == 0 && hasCam

    // battery: the camera switches itself off after a quiet period (setting), and while any sheet is open it is paused
    LaunchedEffect(cameraActive, idleSec) {
        if (cameraActive) vm.touch()
        while (cameraActive && idleSec > 0) {
            delay(5_000)
            if (vm.pending.value == null && System.currentTimeMillis() - vm.lastActivity.value > idleSec * 1000L) {
                cameraOn = false; torch = false; idleOff = true
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ------------------------------------------------------------- tabs
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(tab == 0, { tab = 0 }, label = { Text("اسکن و فروش") }, leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)) })
                FilterChip(tab == 1, { tab = 1 }, label = { Text("قهوه") }, leadingIcon = { Icon(Icons.Filled.Coffee, null, Modifier.size(18.dp)) })
            }

            // ------------------------------------------------------------- camera
            if (tab == 0) {
                if (cameraOn) {
                    Box(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(190.dp)
                            .clip(RoundedCornerShape(24.dp)).background(Color.Black),
                    ) {
                        if (hasCam) {
                            CameraView(
                                Modifier.fillMaxSize(),
                                scanBarcodes = pending == null,
                                frameIntervalMs = if (visualOn && hasPrints && pending == null) 900 else 0,
                                torch = torch,
                                onBarcode = vm::onBarcode,
                                onFrame = vm::onFrame,
                                onTorchAvailable = { hasFlash = it },
                            )
                            ScanFrame(visualOn && hasPrints)
                        } else {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("برای اسکن، دسترسی دوربین لازم است", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("اجازهٔ دوربین") }
                            }
                        }
                        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (hasFlash) CamBtn(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "چراغ", torch) { torch = !torch }
                            CamBtn(Icons.Filled.ImageSearch, "تشخیص تصویری", visualOn) { visualOn = !visualOn }
                        }
                        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { CamBtn(Icons.Filled.Close, "بستن دوربین", false) { cameraOn = false; torch = false } }
                        Text(
                            if (visualOn && hasPrints) "بارکد یا خود کالا را جلوی دوربین بگیرید" else "بارکد را جلوی دوربین بگیرید",
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (idleOff) "دوربین برای صرفه‌جویی باتری خاموش شد" else "دوربین خاموش است",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { cameraOn = true; idleOff = false; vm.touch() }, shape = CircleShape) {
                            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("روشن کردن اسکن")
                        }
                    }
                }
            }

            // ------------------------------------------------------------- list area
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == 0) {
                    if (visual.isNotEmpty() && pending == null) item(key = "visual") {
                        AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("آیا این کالا است؟", style = MaterialTheme.typography.titleSmall)
                                visual.forEach { m ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                                            .clickableRow { vm.pick(m.product, viaImage = true) }.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(m.product.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Pill(Fmt.digits("${(m.score * 100).toInt()}٪"), MaterialTheme.extra.profit)
                                    }
                                }
                            }
                        }
                    }
                    if (visualOn && !hasPrints && cameraOn) item(key = "novis") {
                        Text(
                            "برای شناسایی کالاهای بدون بارکد با دوربین، هنگام تعریف کالا «عکس مرجع» بگیرید.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item(key = "search") {
                        AppField(query, { query = it; vm.touch() }, "جستجوی کالا (نام یا بارکد)", Modifier.fillMaxWidth(),
                            leading = { Icon(Icons.Filled.Search, null) },
                            trailing = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "پاک کردن") } })
                    }
                    val q = Fmt.normalize(query).trim()
                    if (q.isNotEmpty()) {
                        // exact code / barcode first, then names that start with the query, then the rest
                        val res = products.asSequence()
                            .filter { it.name.contains(q, true) || it.barcode?.contains(q) == true || it.boxBarcode?.contains(q) == true || it.code.contains(q, true) }
                            .sortedBy { if (it.code.equals(q, true) || it.barcode == q || it.boxBarcode == q) 0 else if (it.name.startsWith(q, true)) 1 else 2 }
                            .take(8).toList()
                        if (res.isEmpty()) item(key = "nores") { Text("کالایی پیدا نشد", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(res, key = { "r" + it.id }) { p -> ProductRow(p, cur) { vm.pick(p); query = "" } }
                    } else {
                        val quick = products.filter { it.favorite || it.barcode == null }.take(20)
                        if (quick.isNotEmpty()) item(key = "quick") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("دسترسی سریع", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(quick, key = { it.id }) { p -> FilterChip(selected = false, onClick = { vm.pick(p) }, label = { Text(p.name) }) }
                                }
                            }
                        }
                    }
                } else {
                    coffeeSection(
                        title = "قهوه فله (کیلویی)", hint = "قهوهٔ فله را بر اساس وزن بفروشید؛ قیمت هر کیلو و موجودی ثبت می‌شود.",
                        list = products.filter { it.category == CAT_COFFEE_BULK }, cur = cur,
                        onTap = { vm.pick(it) }, onDefine = { onDefinePreset("coffee_bulk") }, defineLabel = "+ تعریف قهوه فله",
                    )
                    coffeeSection(
                        title = "قهوه (دانه‌ای)", hint = "فروش تکی با قیمت ثابت و بدون موجودی؛ با یک لمس به فاکتور اضافه می‌شود.",
                        list = products.filter { it.kind == KIND_DRINK || it.category == CAT_COFFEE }, cur = cur,
                        onTap = { vm.addToCart(it, 1.0); vm.touch() }, onDefine = { onDefinePreset("coffee_cup") }, defineLabel = "+ تعریف قهوه",
                    )
                }

                // cart
                item(key = "carthead") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("فاکتور", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (cart.isNotEmpty()) TextButton(onClick = vm::clearCart) { Text("خالی کردن", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (cart.isEmpty()) item(key = "empty") {
                    EmptyState(Icons.Filled.AddShoppingCart, "فاکتور خالی است", "بارکد کالا را اسکن کنید یا از جستجو و دسترسی سریع انتخاب کنید.")
                }
                items(cart, key = { it.key }) { line -> CartRow(line, cur, vm) }
            }

            // ------------------------------------------------------------- bottom checkout bar
            if (cart.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("جمع کل", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Fmt.money(total, cur), style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = { showCheckout = true }, enabled = !checkingOut, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                        Icon(Icons.Filled.ShoppingCartCheckout, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text("تسویه")
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    // ------------------------------------------------------------- confirm / unknown sheet (always visible, camera is paused meanwhile)
    pending?.let { p ->
        ModalBottomSheet(onDismissRequest = { vm.dismissPending() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (p) {
                is Pending.Found -> PendingSheet(p, cur, vm)
                is Pending.Unknown -> Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("این بارکد ثبت نشده است", style = MaterialTheme.typography.titleMedium)
                    Text(Fmt.digits(p.code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDefine(p.code); vm.dismissPending() }) { Text("تعریف کالای جدید") }
                        OutlinedButton(onClick = { vm.dismissPending() }) { Text("بستن") }
                    }
                }
            }
        }
    }

    if (showCheckout) {
        ModalBottomSheet(onDismissRequest = { showCheckout = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            CheckoutSheet(
                cur = cur, subtotal = subtotal, cost = cost, discount = discount, customers = customers,
                onDiscount = vm::setDiscount,
                onAddCustomer = { n, ph, done -> vm.addCustomer(n, ph, done) },
                busy = checkingOut,
                onConfirm = { pay, customerId ->
                    showCheckout = false
                    vm.checkout(pay, customerId) { scope.launch { snack.showSnackbar("فروش ثبت شد  ✓") } }
                },
            )
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

private fun androidx.compose.foundation.lazy.LazyListScope.coffeeSection(
    title: String, hint: String, list: List<ProductWithStock>, cur: Currency,
    onTap: (ProductWithStock) -> Unit, onDefine: () -> Unit, defineLabel: String,
) {
    item(key = "h-$title") {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDefine) { Text(defineLabel) }
        }
    }
    if (list.isEmpty()) item(key = "e-$title") {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(list.chunked(2), key = { row -> "c-$title-" + row.first().id }) { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { p ->
                AppCard(Modifier.weight(1f), onClick = { onTap(p) }) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.money(p.sellPrice, cur) + if (p.isWeight) " / کیلو" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (p.trackStock) Text("موجودی " + Fmt.qty(p.stock, p.isWeight), style = MaterialTheme.typography.labelSmall, color = if (p.low) MaterialTheme.extra.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
