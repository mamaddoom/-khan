package com.mozkhan.shop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.CartLine
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt

@Composable
fun ScanFrame(visual: Boolean) {
    val color = if (visual) Color(0xFF22D3EE) else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * 0.62f
        val h = size.height * 0.56f
        val l = (size.width - w) / 2
        val t = (size.height - h) / 2
        val len = 26.dp.toPx()
        val sw = 3.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * len, y), sw, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * len), sw, StrokeCap.Round)
        }
        corner(l, t, 1f, 1f); corner(l + w, t, -1f, 1f); corner(l, t + h, 1f, -1f); corner(l + w, t + h, -1f, -1f)
        if (!visual) drawLine(Color(0xFFEF4444).copy(alpha = 0.85f), Offset(l + 8, t + h / 2), Offset(l + w - 8, t + h / 2), 2.dp.toPx())
    }
}

@Composable
fun CamBtn(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    // 48dp touch target (accessibility) around a 38dp visual circle
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
fun ProductRow(p: ProductWithStock, cur: Currency, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    p.category + if (p.trackStock) " • موجودی " + Fmt.qty(p.stock, p.isWeight) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.low) MaterialTheme.extra.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Fmt.money(p.sellPrice, cur) + if (p.isWeight) " / کیلو" else "", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Bottom sheet that confirms a scanned / recognised / chosen product. It never hides behind the camera. */
@Composable
fun PendingSheet(p: Pending.Found, cur: Currency, vm: MainVm) {
    val prod = p.p
    var box by remember(prod.id) { mutableStateOf(p.box && prod.hasBox) }
    var qty by remember(prod.id) { mutableStateOf(if (prod.isWeight) 100.0 else 1.0) }
    var gramText by remember(prod.id) { mutableStateOf("100") }
    val price = if (box) prod.boxSellPrice else prod.sellPrice
    val line = CartLine(prod, qty, price, box)
    val onPrimary = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (p.viaImage) "تشخیص تصویری — تأیید کنید" else if (p.box) "بارکد باکس اسکن شد" else "کالای شناخته‌شده",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                Text(prod.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    Fmt.money(price, cur) + (if (prod.isWeight) " / کیلو" else if (box) " / باکس" else "") +
                        (if (prod.trackStock) "  •  موجودی " + Fmt.qty(prod.stock, prod.isWeight) else ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::dismissPending) { Icon(Icons.Filled.Close, "رد کردن") }
        }
        if (prod.hasBox && !prod.isWeight) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!box, { box = false; qty = 1.0 }, label = { Text("پاکت / عدد") })
                FilterChip(box, { box = true; qty = 1.0 }, label = { Text("باکس (${Fmt.digits(prod.boxSize.toString())} عدد)") })
            }
            if (box) Text(
                "با فروش باکس، ${Fmt.digits((prod.boxSize * qty).toLong().toString())} عدد از موجودی کم می‌شود و قیمت باکس اعمال می‌شود.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (prod.isWeight) {
            NumberField("وزن", gramText, { gramText = it; qty = Fmt.parseDouble(it) }, Modifier.fillMaxWidth(), suffix = "گرم")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(50, 100, 250, 500, 1000)) { g ->
                    FilterChip(selected = qty == g.toDouble(), onClick = { qty = g.toDouble(); gramText = g.toString() }, label = { Text(Fmt.digits(g.toString()) + " گرم") })
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (box) "تعداد باکس" else "تعداد", Modifier.weight(1f))
                QtyStepper(qty, { qty = it.coerceAtLeast(1.0) })
            }
        }
        if (prod.trackStock && line.pieces > prod.stock) Text(
            "موجودی ثبت‌شده (${Fmt.qty(prod.stock, prod.isWeight)}) کمتر از این مقدار است؛ پس از فروش، موجودی را بررسی کنید.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
        )
        Button(
            onClick = { vm.addToCart(prod, qty, price, box); vm.dismissPending() },
            enabled = qty > 0, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(14.dp),
        ) { Text("افزودن به فاکتور  (${Fmt.money(line.total, cur)})", style = MaterialTheme.typography.titleSmall) }
    }
}

@Composable
fun CartRow(line: CartLine, cur: Currency, vm: MainVm) {
    val p = line.product
    var editQty by remember { mutableStateOf(false) }
    var editPrice by remember { mutableStateOf(false) }
    val over = p.trackStock && line.pieces > p.stock
    AppCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (line.box) Pill("باکس ×" + Fmt.digits(line.mult.toString()), MaterialTheme.colorScheme.secondary, Modifier.padding(end = 6.dp))
                IconButton(onClick = { vm.removeLine(line.key) }) {
                    Icon(Icons.Filled.DeleteOutline, "حذف از فاکتور", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.isWeight) {
                    OutlinedButton(onClick = { editQty = true }, shape = CircleShape) { Text(Fmt.qty(line.qty, true)) }
                } else QtyStepper(line.qty, { vm.setQty(line.key, it) })
                Spacer(Modifier.size(10.dp))
                Text(
                    Fmt.money(line.price, cur, withUnit = false) + if (p.isWeight) " /کیلو" else " ×",
                    modifier = Modifier.clickable { editPrice = true },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(Fmt.money(line.total, cur), style = MaterialTheme.typography.titleSmall)
            }
            if (over) Text("موجودی ثبت‌شده (${Fmt.qty(p.stock, p.isWeight)}) کمتر از این مقدار است", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extra.warn)
        }
    }
    if (editQty) NumberDialog("وزن (گرم)", line.qty.toLong().toString(), "گرم", { editQty = false }) { vm.setQty(line.key, it) }
    if (editPrice) NumberDialog(
        "قیمت فروش (${cur.fa})" + if (p.isWeight) " / کیلو" else if (line.box) " / باکس" else "",
        Fmt.inputMoney(line.price, cur), cur.fa, { editPrice = false }, decimal = false,
    ) { vm.setPrice(line.key, Fmt.parseMoney(it.toLong().toString(), cur)) }
}

@Composable
fun NumberDialog(title: String, initial: String, suffix: String, onDismiss: () -> Unit, decimal: Boolean = true, onOk: (Double) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField("مقدار", v, { v = it }, Modifier.fillMaxWidth(), suffix = suffix, decimal = decimal) },
        confirmButton = { TextButton(onClick = { onOk(Fmt.parseDouble(v)); onDismiss() }) { Text("تأیید") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } },
    )
}

@Composable
fun CheckoutSheet(
    cur: Currency, subtotal: Long, cost: Long, discount: Long,
    customers: List<Customer>,
    onDiscount: (Long) -> Unit,
    onAddCustomer: (String, String, (Customer) -> Unit) -> Unit,
    busy: Boolean = false,
    methods: List<com.mozkhan.shop.util.PayMethod> = com.mozkhan.shop.util.PayMethods.defaults,
    onConfirm: (String, String?) -> Unit,
) {
    val shownMethods = methods.filter { it.enabled }
    var pay by remember { mutableStateOf(shownMethods.firstOrNull()?.id ?: "CASH") }
    // local guard: two taps inside the same frame must never create two sales
    var sent by remember { mutableStateOf(false) }
    var pct by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf<String?>(null) }
    var newCustomer by remember { mutableStateOf(false) }
    val total = (subtotal - discount).coerceAtLeast(0)
    val profit = total - cost
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("تسویه فاکتور", style = MaterialTheme.typography.titleLarge)
        SumRow("جمع اقلام", Fmt.money(subtotal, cur))
        MoneyField("تخفیف", discount, cur, { onDiscount(it.coerceAtMost(subtotal)); pct = "" }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(5, 10, 15, 20).forEach { p ->
                FilterChip(selected = pct == p.toString(), onClick = { pct = p.toString(); onDiscount(subtotal * p / 100) }, label = { Text(Fmt.digits("$p٪")) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shownMethods, key = { it.id }) { m ->
                FilterChip(selected = pay == m.id, onClick = { pay = m.id }, label = { Text(m.name) })
            }
        }
        if (pay == "CREDIT") {
            Text("مشتری (بدهکار):", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(false, { newCustomer = true }, label = { Text("+ مشتری جدید") }) }
                items(customers, key = { it.id }) { c -> FilterChip(customerId == c.id, { customerId = c.id }, label = { Text(c.name) }) }
            }
        }
        SumRow("سود این فاکتور", Fmt.money(profit, cur), if (profit >= 0) MaterialTheme.extra.profit else MaterialTheme.extra.loss)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("مبلغ قابل پرداخت", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(Fmt.money(total, cur), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Button(
            onClick = { if (!sent) { sent = true; onConfirm(pay, customerId) } },
            enabled = !busy && !sent && (pay != "CREDIT" || customerId != null),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(14.dp),
        ) { Text(if (pay == "CREDIT" && customerId == null) "ابتدا مشتری را انتخاب کنید" else "تأیید و ثبت فروش", style = MaterialTheme.typography.titleSmall) }
    }
    if (newCustomer) CustomerDialog({ newCustomer = false }) { n, ph -> onAddCustomer(n, ph) { customerId = it.id } }
}

@Composable
fun SumRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = color, style = MaterialTheme.typography.titleSmall)
    }
}
