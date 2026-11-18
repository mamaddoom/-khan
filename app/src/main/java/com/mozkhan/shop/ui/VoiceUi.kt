package com.mozkhan.shop.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mozkhan.shop.data.Customer
import com.mozkhan.shop.data.KIND_INGREDIENT
import com.mozkhan.shop.data.ProductWithStock
import com.mozkhan.shop.util.Currency
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.PayMethods
import com.mozkhan.shop.voice.VoiceAssistant
import com.mozkhan.shop.voice.VoiceEngine
import com.mozkhan.shop.voice.VoiceParser
import com.mozkhan.shop.voice.VoiceParser.Cmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ===================================================================== listening state (one command at a time)
class VoiceListen(private val ctx: Context, private val scope: CoroutineScope, private val options: () -> VoiceEngine.Options) {
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf(""); private set
    var heard by mutableStateOf<VoiceEngine.Heard?>(null)
    var error by mutableStateOf<String?>(null)
    private var session: VoiceEngine.Session? = null
    private val feedback get() = (ctx as? com.mozkhan.shop.ShopApp)?.feedback

    fun start() {
        if (listening) return
        partial = ""; heard = null; error = null; listening = true
        feedback?.listening(true)
        scope.launch {
            session = VoiceEngine.listen(
                ctx, options(),
                onPartial = { partial = it },
                onFinal = { heard = it; listening = false; session = null; feedback?.listening(false) },
                onError = { error = it; listening = false; session = null; feedback?.listening(false) },
            )
            if (session == null) listening = false
        }
    }

    /** Finish now: what was said so far is the answer. */
    fun stop() { session?.stop() }
}

@Composable
fun rememberVoiceListen(options: () -> VoiceEngine.Options = { VoiceEngine.Options() }): VoiceListen {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = remember { VoiceListen(ctx.applicationContext, scope, options) }
    DisposableEffect(Unit) { onDispose { v.stop() } }
    return v
}

private fun hasMic(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Starts listening after asking for the microphone once. */
@Composable
fun rememberMicStarter(v: VoiceListen): () -> Unit {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) v.start() else v.error = "برای دستیار صوتی اجازهٔ میکروفون لازم است"
    }
    return { if (hasMic(ctx)) v.start() else ask.launch(Manifest.permission.RECORD_AUDIO) }
}

// ===================================================================== model setup (download / import / remove)
@Composable
fun VoiceModelCard(compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by VoiceEngine.state.collectAsState()
    LaunchedEffect(Unit) { VoiceEngine.refresh(ctx) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            VoiceEngine.importZip(ctx, uri).onSuccess { Toast.makeText(ctx, "مدل صوتی نصب شد", Toast.LENGTH_SHORT).show() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = st) {
            is VoiceEngine.State.Downloading -> {
                Text("در حال دانلود مدل فارسی… " + Fmt.digits("${(s.progress * 100).toInt()}٪"), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            }
            VoiceEngine.State.Installing -> { Text("در حال نصب مدل…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            VoiceEngine.State.Loading -> { Text("در حال آماده‌سازی دستیار…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            VoiceEngine.State.Installed, VoiceEngine.State.Ready -> {
                Text("مدل گفتار فارسی نصب است ✓ (کاملاً آفلاین)", color = MaterialTheme.extra.profit, style = MaterialTheme.typography.bodyMedium)
                if (!compact) TextButton(onClick = { VoiceEngine.delete(ctx) }) { Text("حذف مدل (آزاد کردن ${Fmt.digits("${VoiceEngine.MODEL_MB * 2}")} مگابایت)", color = MaterialTheme.colorScheme.error) }
            }
            else -> {
                if (s is VoiceEngine.State.Failed) Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(
                    "دستیار صوتی برای کار بدون اینترنت به مدل گفتار فارسی (${Fmt.digits(VoiceEngine.MODEL_MB.toString())} مگابایت) نیاز دارد؛ فقط یک بار دانلود می‌شود.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { scope.launch { VoiceEngine.download(ctx) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("دانلود مدل فارسی (${Fmt.digits(VoiceEngine.MODEL_MB.toString())} مگابایت)")
                }
                OutlinedButton(onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("نصب از فایل ${VoiceEngine.MODEL_NAME}.zip")
                }
            }
        }
    }
}

// ===================================================================== big mic with live text
@Composable
private fun MicPanel(v: VoiceListen, hint: String, onMic: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val s by pulse.animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledIconButton(
            onClick = { if (v.listening) v.stop() else onMic() },
            modifier = Modifier.size(84.dp).scale(if (v.listening) s else 1f),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (v.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            ),
        ) { Icon(if (v.listening) Icons.Filled.Stop else Icons.Filled.Mic, "میکروفون", Modifier.size(40.dp)) }
        Text(
            when {
                v.listening && v.partial.isNotBlank() -> v.partial
                v.listening -> "در حال شنیدن… بگویید"
                else -> hint
            },
            style = if (v.listening) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
            color = if (v.listening) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        v.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
    }
}

private fun toToman(v: Double, cur: Currency): Long = Math.round(v / cur.factor)

private const val SELL_HINT = "مثال‌ها: «دو تا بهمن و یک وینستون»، «دویست گرم قهوه»، «تخفیف ده درصد»، «ثبت کن کارتی»، «نسیه به نام رضایی»، " +
    "«موجودی بهمن چنده؟»، «قیمت قهوه»، «فروش امروز»، «چک‌های این هفته»، «خرید بهمن ده باکس، هر باکس هشتصد هزار»، «برگردون»"

// ===================================================================== selling by voice
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSellSheet(vm: MainVm, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val st by VoiceEngine.state.collectAsState()
    LaunchedEffect(Unit) { VoiceEngine.refresh(ctx) }
    val ready = st is VoiceEngine.State.Installed || st is VoiceEngine.State.Ready || st is VoiceEngine.State.Loading
    val products by vm.products.collectAsState()
    val allProducts by vm.allProducts.collectAsState()
    val cart by vm.cart.collectAsState()
    val cur by vm.currency.collectAsState()
    val methods by vm.prefs.payMethods.collectAsState()
    val checkingOut by vm.checkingOut.collectAsState()
    val customers by vm.customers.collectAsState()
    val debtors by vm.debtors.collectAsState()
    val cheques by vm.cheques.collectAsState()
    val today by vm.todayTotals.collectAsState()
    val learned by vm.prefs.voiceLearned.collectAsState()
    val salesOnly by vm.prefs.salesOnly.collectAsState()
    val undo by vm.voiceUndo.collectAsState()
    fun learn(spoken: String, id: String, customer: Boolean = false) {
        if (spoken.isNotBlank() && !spoken.startsWith("#")) vm.prefs.learnVoice((if (customer) "c:" else "") + VoiceAssistant.key(spoken), id)
    }

    // the second recognizer only knows this shop's words: product names, payment methods, customers, numbers and commands
    val v = rememberVoiceListen {
        VoiceEngine.Options(maxMs = 20_000, hints = VoiceAssistant.hints(vm.allProducts.value, vm.prefs.payMethods.value, vm.customers.value))
    }
    val mic = rememberMicStarter(v)

    // start listening right away once everything is ready
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready && !autoStarted) { autoStarted = true; mic() } }

    // of all readings the engine offers, the one that makes most sense for this shop
    val heard = remember(v.heard) { v.heard?.let { h -> VoiceAssistant.pickBest(listOf(h.text) + h.alternatives, allProducts, methods, learned) ?: h.text } }
    val cmd = remember(heard) { heard?.let { VoiceParser.parse(it) } }
    val picks = remember(heard) { mutableStateMapOf<Int, ProductWithStock>() }
    val qtyEdit = remember(heard) { mutableStateMapOf<Int, Double>() }
    var pickedCustomer by remember(heard) { mutableStateOf<Customer?>(null) }

    fun done(msg: String) { onMessage(msg); v.heard = null }
    val subtotal = cart.sumOf { it.total }

    ModalBottomSheet(onDismissRequest = { v.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("دستیار صوتی فروشگاه", style = MaterialTheme.typography.titleLarge)
            if (!ready) { VoiceModelCard(compact = true); return@Column }

            if (heard == null || v.listening) {
                MicPanel(v, SELL_HINT, mic)
                if (!v.listening && undo != null) TextButton(onClick = { vm.undoVoice()?.let { onMessage("«$it» برگردانده شد") } }) {
                    Text("برگرداندن آخرین کار صوتی: " + undo!!.label)
                }
                return@Column
            }

            Text("شنیدم: «$heard»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val again: @Composable () -> Unit = { OutlinedButton(onClick = mic) { Text("دوباره") } }
            when (cmd) {
                is Cmd.Add -> {
                    val sellable = products.filter { it.kind != KIND_INGREDIENT }
                    val lines = cmd.items.mapIndexed { i, item ->
                        val base = VoiceAssistant.line(item, sellable, learned)
                        val l = picks[i]?.let { VoiceAssistant.resolve(item, it, base.choices, null) } ?: base
                        qtyEdit[i]?.let { l.copy(qty = it) } ?: l
                    }
                    lines.forEachIndexed { i, l ->
                        PlanRow(l, cur, onPick = { picks[i] = it; learn(cmd.items[i].query, it.id) }, onQty = { qtyEdit[i] = it })
                    }
                    val ok = lines.filter { it.product != null }
                    val sum = ok.sumOf { l ->
                        val p = l.product!!
                        when { l.box -> l.qty * p.boxSellPrice; p.isMeasured -> l.qty * p.sellPrice / 1000.0; else -> l.qty * p.sellPrice }
                    }
                    if (ok.isNotEmpty()) SumRow("جمع این اقلام", Fmt.money(Math.round(sum), cur))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = ok.isNotEmpty(), onClick = {
                            vm.rememberVoiceUndo(Fmt.digits("افزودن ${ok.size} قلم"))
                            ok.forEach { l -> vm.addToCart(l.product!!, l.qty, box = l.box) }
                            done(
                                Fmt.digits("${ok.size} قلم به فاکتور اضافه شد"),
                            )
                        }, modifier = Modifier.weight(1f)) { Text("افزودن به فاکتور") }
                        again()
                    }
                }
                is Cmd.Remove -> {
                    val m = VoiceParser.best(cmd.query, cart, { it.product.name })
                    val line = m.firstOrNull()?.item
                    Text(if (line == null) "«${cmd.query}» در فاکتور نیست" else "حذف «${line.product.name}» از فاکتور؟")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = line != null, onClick = {
                            vm.rememberVoiceUndo("حذف " + line!!.product.name)
                            vm.removeLine(line.key); done("«${line.product.name}» حذف شد")
                        }, modifier = Modifier.weight(1f)) { Text("حذف") }
                        again()
                    }
                }
                is Cmd.Discount -> {
                    val amount = (cmd.percent?.let { Math.round(subtotal * it / 100.0) } ?: toToman(cmd.amount ?: 0.0, cur)).coerceIn(0, subtotal)
                    Text(if (subtotal <= 0) "فاکتور خالی است" else "تخفیف " + Fmt.money(amount, cur) + (cmd.percent?.let { Fmt.digits(" (${it.toInt()}٪)") } ?: ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = subtotal > 0, onClick = {
                            vm.rememberVoiceUndo("تخفیف")
                            vm.setDiscount(amount); done("تخفیف اعمال شد")
                        }, modifier = Modifier.weight(1f)) { Text("اعمال تخفیف") }
                        again()
                    }
                }
                is Cmd.Checkout -> {
                    val m = VoiceAssistant.method(cmd.methodWords, methods) ?: methods.first { it.enabled }
                    val total = (subtotal - vm.discount.value).coerceAtLeast(0)
                    if (m.id == PayMethods.CREDIT) {
                        val creditOn = methods.any { it.id == PayMethods.CREDIT && it.enabled }
                        val who = VoiceAssistant.customer(cmd.customer, customers, learned)
                        val chosen = pickedCustomer ?: who.customer
                        when {
                            cart.isEmpty() -> Text("فاکتور خالی است")
                            !creditOn -> Text("روش «نسیه» در تنظیمات روش‌های پرداخت خاموش است.")
                            else -> Text(
                                "ثبت نسیه " + Fmt.money(total, cur) + (chosen?.let { " به نام «${it.name}»" } ?: "") + "؟",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (chosen == null) Text(
                            if (cmd.customer.isBlank()) "نام مشتری را بگویید («نسیه به نام رضایی») یا انتخاب کنید:" else "«${cmd.customer}» — منظورتان کدام مشتری است؟",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (who.choices.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(who.choices, key = { it.id }) { c ->
                                FilterChip(chosen?.id == c.id, { pickedCustomer = c; learn(cmd.customer, c.id, customer = true) }, label = { Text(c.name) })
                            }
                        }
                        if (cmd.customer.isNotBlank() && chosen == null) OutlinedButton(onClick = {
                            vm.addCustomer(cmd.customer.trim(), "") { c -> pickedCustomer = c }
                        }) { Text("ثبت مشتری جدید «${cmd.customer}»") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = cart.isNotEmpty() && creditOn && chosen != null && !checkingOut, onClick = {
                                val c = chosen!!
                                vm.checkout(PayMethods.CREDIT, c.id, viaVoice = true) {
                                    onMessage("نسیه ثبت شد ✓ (${c.name})")
                                }
                                v.heard = null
                            }, modifier = Modifier.weight(1f)) { Text("ثبت نسیه") }
                            again()
                        }
                    } else {
                        if (cart.isEmpty()) Text("فاکتور خالی است")
                        else Text("ثبت فاکتور " + Fmt.money(total, cur) + " با «" + m.name + "»؟", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = cart.isNotEmpty() && !checkingOut, onClick = {
                                vm.checkout(m.id, null, viaVoice = true) {
                                    onMessage("فروش ثبت شد ✓ (${m.name})")
                                }
                                v.heard = null
                            }, modifier = Modifier.weight(1f)) { Text("ثبت فروش") }
                            again()
                        }
                    }
                }
                Cmd.Clear -> {
                    Text("همهٔ اقلام فاکتور پاک شود؟")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = cart.isNotEmpty(), onClick = {
                            vm.rememberVoiceUndo("پاک کردن فاکتور")
                            vm.clearCart(); done("فاکتور خالی شد")
                        }, modifier = Modifier.weight(1f)) { Text("پاک کردن") }
                        again()
                    }
                }
                Cmd.Undo -> {
                    val u = undo
                    if (u == null) Text("کار صوتی‌ای برای برگرداندن نیست.")
                    else {
                        Text("برگرداندن «${u.label}»؟", style = MaterialTheme.typography.titleMedium)
                        if (u.saleId != null) Text(
                            "فاکتور ثبت‌شده باطل می‌شود (موجودی برمی‌گردد) و اقلامش دوباره در فاکتور قرار می‌گیرند.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = u != null, onClick = { vm.undoVoice()?.let { done("«$it» برگردانده شد") } }, modifier = Modifier.weight(1f)) {
                            Text("برگرداندن")
                        }
                        again()
                    }
                }
                is Cmd.Query -> {
                    QueryAnswer(cmd, allProducts, learned, picks, debtors, cheques, today, cur, onLearn = { s, id -> learn(s, id) })
                    Button(onClick = mic, modifier = Modifier.fillMaxWidth()) { Text("سؤال یا دستور بعدی") }
                }
                is Cmd.Purchase -> {
                    if (salesOnly) {
                        Text("این گوشی «فقط فروش» است؛ خرید را روی گوشی اصلی ثبت کنید.", color = MaterialTheme.colorScheme.error)
                    } else {
                        val base = VoiceAssistant.line(cmd.item, allProducts, learned)
                        val l0 = picks[0]?.let { VoiceAssistant.resolve(cmd.item, it, base.choices, null) } ?: base
                        val l = qtyEdit[0]?.let { l0.copy(qty = it) } ?: l0
                        PlanRow(l, cur, onPick = { picks[0] = it; learn(cmd.item.query, it.id) }, onQty = { qtyEdit[0] = it })
                        val plan = l.product?.let { p -> purchasePlan(p, l, cmd, cur) }
                        if (plan != null) {
                            val p = l.product!!
                            SumRow("قیمت خرید هر " + (if (p.isWeight) "کیلو" else if (p.isVolume) "لیتر" else "عدد"), Fmt.money(plan.newBuy, cur))
                            if (l.box) SumRow("هر باکس", Fmt.money(plan.boxBuy ?: 0, cur))
                            SumRow("جمع خرید", Fmt.money(plan.total, cur), MaterialTheme.colorScheme.primary)
                            if (cmd.price == null) Text("قیمت گفته نشد؛ آخرین قیمت خرید در نظر گرفته شد.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn)
                            if (p.sellPrice > 0 && plan.newBuy > p.sellPrice) Text("قیمت خرید از قیمت فروش (" + Fmt.money(p.sellPrice, cur) + ") بیشتر است!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Text("پرداخت نقدی ثبت می‌شود؛ برای خرید چکی از «خرید جدید» در صفحهٔ کالا استفاده کنید.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = plan != null && plan.qtyBase > 0, onClick = {
                                val p = l.product!!
                                vm.restock(p.id, plan!!.qtyBase, plan.unitCost, plan.newBuy, null, plan.boxBuy, null, "") {
                                    onMessage("خرید «${p.name}» ثبت شد ✓")
                                }
                                v.heard = null
                            }, modifier = Modifier.weight(1f)) { Text("ثبت خرید") }
                            again()
                        }
                    }
                }
                else -> {
                    Text("متوجه نشدم. نام کالا و تعداد را بگویید، یا یک سؤال مثل «موجودی بهمن چنده؟»", color = MaterialTheme.colorScheme.error)
                    Button(onClick = mic, modifier = Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
                }
            }
        }
    }
}

/** A purchase said by voice, converted to what the purchase record needs. [qtyBase] in pieces / grams / ml. */
internal data class PurchasePlan(val qtyBase: Double, val unitCost: Double, val newBuy: Long, val boxBuy: Long?, val total: Long)

internal fun purchasePlan(p: ProductWithStock, l: VoiceAssistant.Line, cmd: Cmd.Purchase, cur: Currency): PurchasePlan {
    val size = p.boxSize.coerceAtLeast(1)
    val qtyBase = if (l.box) l.qty * size else l.qty
    val price = cmd.price?.let { toToman(it, cur).toDouble() }
    val unitCost: Double = when {
        price == null -> if (l.box && p.boxBuyPrice > 0) p.boxBuyPrice.toDouble() / size else p.baseCost
        cmd.total -> if (qtyBase > 0) price / qtyBase else 0.0
        l.box -> price / size
        p.isMeasured -> price / 1000.0
        else -> price
    }
    val newBuy = Math.round(if (p.isMeasured) unitCost * 1000 else unitCost)
    return PurchasePlan(qtyBase, unitCost, newBuy, if (l.box) Math.round(unitCost * size) else null, Math.round(qtyBase * unitCost))
}

/** Answers a spoken question on screen. Nothing is changed. */
@Composable
private fun QueryAnswer(
    cmd: Cmd.Query, products: List<ProductWithStock>, learned: Map<String, String>, picks: MutableMap<Int, ProductWithStock>,
    debtors: List<CustomerDebt>, cheques: List<com.mozkhan.shop.data.Cheque>, today: com.mozkhan.shop.data.Totals, cur: Currency,
    onLearn: (String, String) -> Unit,
) {
    var shown: String? = null
    when (cmd.ask) {
        VoiceParser.Ask.STOCK, VoiceParser.Ask.PRICE -> {
            val item = VoiceParser.Item(VoiceParser.tokens(cmd.query), null, null)
            val base = VoiceAssistant.line(item, products, learned)
            val p = picks[0] ?: base.product
            if (p == null) {
                Text(if (base.choices.isEmpty()) "کالایی شبیه «${cmd.query}» پیدا نشد" else "منظورتان کدام است؟", style = MaterialTheme.typography.bodyMedium)
                if (base.choices.isEmpty()) Unit
            } else if (cmd.ask == VoiceParser.Ask.STOCK) {
                if (!p.trackStock) { shown = "«${p.name}» موجودی انبار ندارد (بدون شمارش)" }
                else {
                    val boxes = if (p.hasBox && p.stock >= p.boxSize) Fmt.digits(" (حدود ${(p.stock / p.boxSize).toInt()} باکس)") else ""
                    shown = "موجودی «${p.name}»: " + Fmt.qtyOf(p.stock, p.unit) + boxes + (if (p.low) " — کم است!" else "")
                }
            } else {
                val per = if (p.isWeight) " هر کیلو" else if (p.isVolume) " هر لیتر" else ""
                shown = "قیمت «${p.name}»:" + per + " " + Fmt.money(p.sellPrice, cur) + (if (p.hasBox) " • باکس " + Fmt.money(p.boxSellPrice, cur) else "")
            }
            val others = base.choices.filter { it.id != p?.id }
            if (others.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(others, key = { it.id }) { c -> FilterChip(false, { picks[0] = c; onLearn(cmd.query, c.id) }, label = { Text(c.name) }) }
            }
        }
        VoiceParser.Ask.TODAY -> {
            val profit = today.revenue - today.cost
            shown = "فروش امروز: " + Fmt.money(today.revenue, cur) + Fmt.digits(" در ${today.count} فاکتور") + " • سود " + Fmt.money(profit, cur)
        }
        VoiceParser.Ask.CHEQUES -> {
            val due = com.mozkhan.shop.util.Daily.dueCheques(cheques, 7)
            if (due.isEmpty()) { shown = "تا ۷ روز آینده چکی سررسید ندارد ✓" }
            else {
                val sum = due.sumOf { it.first.amount }
                shown = Fmt.digits("${due.size} چک تا ۷ روز آینده • جمع ") + Fmt.money(sum, cur) + "\n" +
                    due.take(5).joinToString("\n") { (c, d) -> "• " + Fmt.money(c.amount, cur) + " — " + com.mozkhan.shop.util.Daily.whenText(d) + (if (c.payee.isNotBlank()) " — " + c.payee else "") }
            }
        }
        VoiceParser.Ask.DEBT -> {
            val m = VoiceParser.best(cmd.query, debtors, { it.customer.name }, min = 0.5)
            val d = VoiceParser.sure(m) ?: m.firstOrNull()?.item
            if (d == null) { shown = "مشتری‌ای به نام «${cmd.query}» پیدا نشد" }
            else {
                shown = "بدهی «${d.customer.name}»: " + Fmt.money(d.debt.coerceAtLeast(0), cur) + (if (d.debt <= 0) " (تسویه است)" else "")
            }
        }
    }
    shown?.let {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(14.dp)) {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun PlanRow(l: VoiceAssistant.Line, cur: Currency, onPick: (ProductWithStock) -> Unit, onQty: (Double) -> Unit) {
    AppCard(container = if (l.product == null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val p = l.product
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            (if (l.box) Fmt.digits("${l.qty.toLong()} باکس") else Fmt.qtyOf(l.qty, p.unit)) + " • موجودی " + Fmt.qtyOf(p.stock, p.unit),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val step = if (p.isMeasured) 50.0 else 1.0
                    TextButton(onClick = { onQty((l.qty - step).coerceAtLeast(step)) }) { Text("−") }
                    TextButton(onClick = { onQty(l.qty + step) }) { Text("+") }
                }
            } else Text("«${l.heard}»", style = MaterialTheme.typography.titleSmall)
            l.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warn) }
            val others = l.choices.filter { it.id != p?.id }
            if (others.isNotEmpty()) {
                Text(if (p == null) "منظورتان کدام است؟ (یاد می‌گیرم)" else "یا:", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(others, key = { it.id }) { c -> FilterChip(false, { onPick(c) }, label = { Text(c.name) }) }
                }
            }
        }
    }
}

// ===================================================================== defining a product by voice
/** words that end a dictation ("…موجودی پنجاه، تمام") */
private val DEFINE_STOP = setOf("تمام", "تموم", "تایید", "کافیه", "کافی", "بسه")

/** The fields of the dialog. Money in TOMAN, amounts as typed text. */
private data class DefFields(
    val name: String = "", val buy: Long = 0, val sell: Long = 0, val stock: String = "", val min: String = "", val cat: String = "",
)

/**
 * Dictation of "نام … قیمت خرید … فروش … موجودی … دسته …". Pauses do NOT end it: it keeps listening (up to 2 minutes)
 * and fills the fields live, until «تمام شد» is tapped or "تمام / تأیید" is said. While speaking, "پاک کن" / "برگردون"
 * removes the last part said and "همه رو پاک کن" starts over. Every field can also be corrected by hand: a field typed
 * by hand is never overwritten by what is heard afterwards. The dialog only closes with «تأیید» or «انصراف».
 */
@Composable
fun VoiceDefineDialog(cur: Currency, onDismiss: () -> Unit, onApply: (VoiceParser.Definition) -> Unit) {
    val ctx = LocalContext.current
    val st by VoiceEngine.state.collectAsState()
    LaunchedEffect(Unit) { VoiceEngine.refresh(ctx) }
    val ready = st is VoiceEngine.State.Installed || st is VoiceEngine.State.Ready || st is VoiceEngine.State.Loading
    val v = rememberVoiceListen { VoiceEngine.Options(continuous = true, maxMs = 120_000, stopWords = DEFINE_STOP) }
    val mic = rememberMicStarter(v)
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready && !started) { started = true; mic() } }

    // everything said so far (several dictations can be joined with «ادامه»)
    var said by remember { mutableStateOf("") }
    var appending by remember { mutableStateOf(false) }
    var applyWhenDone by remember { mutableStateOf(false) }
    // a finished dictation is merged into [said] once; until then its text is used directly, so the fields never go
    // blank for a moment between "stopped listening" and "merged" (that gap made «تأیید» apply empty fields)
    LaunchedEffect(v.heard) {
        val h = v.heard ?: return@LaunchedEffect
        said = VoiceParser.applyEdits(if (appending) "$said ${h.text}" else h.text)
        appending = false
        v.heard = null
    }
    val prefix = if (appending) "$said " else ""
    val live = when {
        v.listening -> VoiceParser.applyEdits(prefix + v.partial)
        v.heard != null -> VoiceParser.applyEdits(prefix + v.heard!!.text)
        else -> said
    }
    val d = remember(live) { if (live.isBlank()) null else VoiceParser.parseDefinition(live) }

    // what is shown / applied: the heard values, except fields corrected by hand (computed fresh, never stale)
    var manual by remember { mutableStateOf(DefFields()) }
    val touched = remember { mutableStateMapOf<String, Boolean>() }
    fun toman(x: Double?, rial: Boolean): Long = x?.let { Math.round(if (rial) it / 10 else it / cur.factor) } ?: 0
    fun hand(k: String) = touched[k] == true
    val f = DefFields(
        name = if (hand("name")) manual.name else d?.name.orEmpty(),
        buy = if (hand("buy")) manual.buy else toman(d?.buy, d?.rial == true),
        sell = if (hand("sell")) manual.sell else toman(d?.sell, d?.rial == true),
        stock = if (hand("stock")) manual.stock else d?.stock?.let { plain(it) }.orEmpty(),
        min = if (hand("min")) manual.min else d?.minStock?.let { plain(it) }.orEmpty(),
        cat = if (hand("cat")) manual.cat else d?.category.orEmpty(),
    )
    val usable = f.name.isNotBlank() || f.buy > 0 || f.sell > 0

    fun apply() {
        if (!usable) return
        onApply(
            VoiceParser.Definition(
                f.name.trim(),
                f.buy.takeIf { it > 0 }?.let { it.toDouble() * cur.factor },
                f.sell.takeIf { it > 0 }?.let { it.toDouble() * cur.factor },
                f.stock.toDoubleOrNull(), f.min.toDoubleOrNull(), f.cat.trim().ifBlank { null },
                rial = false,
            ),
        )
        onDismiss()
    }
    // «تأیید» while still listening: finish the dictation, wait until its last words are merged, then apply
    LaunchedEffect(applyWhenDone, v.listening, v.heard, said) {
        if (applyWhenDone && !v.listening && v.heard == null) { applyWhenDone = false; apply() }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = { Text("تعریف کالا با صدا") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!ready) { VoiceModelCard(compact = true); return@Column }
                MicPanel(
                    v,
                    if (said.isBlank()) "بگویید: «بهمن کوچک، قیمت خرید نود هزار، فروش صد هزار، موجودی پنجاه، دسته سیگار» و در آخر «تمام». " +
                        "اشتباه شد؟ بگویید «پاک کن» یا «برگردون»."
                    else "برای اضافه کردن «ادامه» را بزنید؛ هر خانه را هم می‌توانید با دست درست کنید",
                    onMic = { appending = said.isNotBlank(); mic() },
                )
                if (v.listening) {
                    OutlinedButton(onClick = { v.stop() }, modifier = Modifier.fillMaxWidth()) { Text("تمام شد") }
                }
                if (d != null || touched.isNotEmpty()) DefinitionEditor(f, cur, onChange = { key, nf -> touched[key] = true; manual = nf })
                if (!v.listening && (d != null || touched.isNotEmpty())) Text(
                    "خانه‌ای که با دست عوض کنید دیگر با صدا تغییر نمی‌کند. فقط خانه‌های پر در فرم کالا نوشته می‌شوند.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = usable && !applyWhenDone, onClick = { if (v.listening) { applyWhenDone = true; v.stop() } else apply() }) {
                Text("تأیید")
            }
        },
        dismissButton = {
            Row {
                if (ready && !v.listening && said.isNotBlank()) {
                    TextButton(onClick = { appending = true; mic() }) { Text("ادامه") }
                    TextButton(onClick = { said = ""; appending = false; touched.clear(); manual = DefFields(); mic() }) { Text("از اول") }
                }
                TextButton(onClick = { v.stop(); onDismiss() }) { Text("انصراف") }
            }
        },
    )
}

/** The heard values as editable fields (each one can be fixed or cleared by hand). */
@Composable
private fun DefinitionEditor(f: DefFields, cur: Currency, onChange: (String, DefFields) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppField(f.name, { onChange("name", f.copy(name = it)) }, "نام کالا", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MoneyField("قیمت خرید", f.buy, cur, { onChange("buy", f.copy(buy = it)) }, Modifier.weight(1f), suffix = false)
            MoneyField("قیمت فروش", f.sell, cur, { onChange("sell", f.copy(sell = it)) }, Modifier.weight(1f), suffix = false)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumberField("موجودی", f.stock, { onChange("stock", f.copy(stock = it)) }, Modifier.weight(1f))
            NumberField("حداقل", f.min, { onChange("min", f.copy(min = it)) }, Modifier.weight(1f))
        }
        AppField(f.cat, { onChange("cat", f.copy(cat = it)) }, "دسته", Modifier.fillMaxWidth())
        Text("قیمت‌ها به " + cur.fa, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small round mic button for toolbars and fields. */
@Composable
fun MicButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.IconButton(onClick = onClick) { Icon(Icons.Filled.Mic, "دستیار صوتی", tint = MaterialTheme.colorScheme.primary) }
    }
}

private fun plain(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else x.toString()
