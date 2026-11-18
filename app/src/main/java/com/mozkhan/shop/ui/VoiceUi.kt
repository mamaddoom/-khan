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

    fun start() {
        if (listening) return
        partial = ""; heard = null; error = null; listening = true
        scope.launch {
            session = VoiceEngine.listen(
                ctx, options(),
                onPartial = { partial = it },
                onFinal = { heard = it; listening = false; session = null },
                onError = { error = it; listening = false; session = null },
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

// ===================================================================== selling by voice
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSellSheet(vm: MainVm, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val st by VoiceEngine.state.collectAsState()
    LaunchedEffect(Unit) { VoiceEngine.refresh(ctx) }
    val ready = st is VoiceEngine.State.Installed || st is VoiceEngine.State.Ready || st is VoiceEngine.State.Loading
    val products by vm.products.collectAsState()
    val cart by vm.cart.collectAsState()
    val cur by vm.currency.collectAsState()
    val methods by vm.prefs.payMethods.collectAsState()
    val checkingOut by vm.checkingOut.collectAsState()
    // the second recognizer only knows this shop's words: product names, payment methods, numbers and commands
    val v = rememberVoiceListen { VoiceEngine.Options(maxMs = 20_000, hints = VoiceAssistant.hints(vm.products.value, vm.prefs.payMethods.value)) }
    val mic = rememberMicStarter(v)

    // start listening right away once everything is ready
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready && !autoStarted) { autoStarted = true; mic() } }

    // of all readings the engine offers, the one that makes most sense for this shop
    val heard = remember(v.heard) { v.heard?.let { h -> VoiceAssistant.pickBest(listOf(h.text) + h.alternatives, products, methods) ?: h.text } }
    val cmd = remember(heard) { heard?.let { VoiceParser.parse(it) } }
    val picks = remember(heard) { mutableStateMapOf<Int, ProductWithStock>() }
    val qtyEdit = remember(heard) { mutableStateMapOf<Int, Double>() }

    fun done(msg: String) { onMessage(msg); v.heard = null }

    ModalBottomSheet(onDismissRequest = { v.stop(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("دستیار صوتی فروش", style = MaterialTheme.typography.titleLarge)
            if (!ready) { VoiceModelCard(compact = true); return@Column }

            if (heard == null || v.listening) {
                MicPanel(v, "مثال: «دو تا بهمن و یک وینستون»، «دویست گرم قهوه»، «تخفیف ده درصد»، «ثبت کن کارتی»", mic)
                return@Column
            }

            Text("شنیدم: «$heard»", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (cmd) {
                is Cmd.Add -> {
                    val lines = cmd.items.mapIndexed { i, item ->
                        val base = VoiceAssistant.line(item, products.filter { it.kind != com.mozkhan.shop.data.KIND_INGREDIENT })
                        val l = picks[i]?.let { VoiceAssistant.resolve(item, it, base.choices, null) } ?: base
                        qtyEdit[i]?.let { l.copy(qty = it) } ?: l
                    }
                    lines.forEachIndexed { i, l -> PlanRow(l, cur, onPick = { picks[i] = it }, onQty = { qtyEdit[i] = it }) }
                    val ok = lines.filter { it.product != null }
                    val sum = ok.sumOf { l ->
                        val p = l.product!!
                        when { l.box -> l.qty * p.boxSellPrice; p.isMeasured -> l.qty * p.sellPrice / 1000.0; else -> l.qty * p.sellPrice }
                    }
                    if (ok.isNotEmpty()) SumRow("جمع این اقلام", Fmt.money(Math.round(sum), cur))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = ok.isNotEmpty(), onClick = {
                            ok.forEach { l -> vm.addToCart(l.product!!, l.qty, box = l.box) }
                            done(Fmt.digits("${ok.size} قلم به فاکتور اضافه شد"))
                        }, modifier = Modifier.weight(1f)) { Text("افزودن به فاکتور") }
                        OutlinedButton(onClick = mic) { Text("دوباره") }
                    }
                }
                is Cmd.Remove -> {
                    val m = VoiceParser.best(cmd.query, cart, { it.product.name })
                    val line = m.firstOrNull()?.item
                    Text(if (line == null) "«${cmd.query}» در فاکتور نیست" else "حذف «${line.product.name}» از فاکتور؟")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = line != null, onClick = { vm.removeLine(line!!.key); done("«${line.product.name}» حذف شد") }, modifier = Modifier.weight(1f)) { Text("حذف") }
                        OutlinedButton(onClick = mic) { Text("دوباره") }
                    }
                }
                is Cmd.Discount -> {
                    val subtotal = cart.sumOf { it.total }
                    val amount = (cmd.percent?.let { Math.round(subtotal * it / 100.0) } ?: toToman(cmd.amount ?: 0.0, cur)).coerceIn(0, subtotal)
                    Text(if (subtotal <= 0) "فاکتور خالی است" else "تخفیف " + Fmt.money(amount, cur) + (cmd.percent?.let { Fmt.digits(" (${it.toInt()}٪)") } ?: ""))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = subtotal > 0, onClick = { vm.setDiscount(amount); done("تخفیف اعمال شد") }, modifier = Modifier.weight(1f)) { Text("اعمال تخفیف") }
                        OutlinedButton(onClick = mic) { Text("دوباره") }
                    }
                }
                is Cmd.Checkout -> {
                    val m = VoiceAssistant.method(cmd.methodWords, methods) ?: methods.first { it.enabled }
                    val total = (cart.sumOf { it.total } - vm.discount.value).coerceAtLeast(0)
                    when {
                        cart.isEmpty() -> Text("فاکتور خالی است")
                        m.id == PayMethods.CREDIT -> Text("فروش نسیه مشتری لازم دارد؛ از دکمهٔ «تسویه» استفاده کنید.")
                        else -> Text("ثبت فاکتور " + Fmt.money(total, cur) + " با «" + m.name + "»؟", style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = cart.isNotEmpty() && m.id != PayMethods.CREDIT && !checkingOut, onClick = {
                            vm.checkout(m.id, null) { onMessage("فروش ثبت شد ✓ (${m.name})") }
                            v.heard = null
                        }, modifier = Modifier.weight(1f)) { Text("ثبت فروش") }
                        OutlinedButton(onClick = mic) { Text("دوباره") }
                    }
                }
                Cmd.Clear -> {
                    Text("همهٔ اقلام فاکتور پاک شود؟")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = cart.isNotEmpty(), onClick = { vm.clearCart(); done("فاکتور خالی شد") }, modifier = Modifier.weight(1f)) { Text("پاک کردن") }
                        OutlinedButton(onClick = mic) { Text("دوباره") }
                    }
                }
                else -> {
                    Text("متوجه نشدم. نام کالا و تعداد را بگویید.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = mic, modifier = Modifier.fillMaxWidth()) { Text("دوباره بگویید") }
                }
            }
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
                Text(if (p == null) "منظورتان کدام است؟" else "یا:", style = MaterialTheme.typography.labelMedium)
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

/**
 * Dictation of "نام … قیمت خرید … فروش … موجودی … دسته …". Pauses do NOT end it: it keeps listening (up to 2 minutes)
 * and fills the fields live, until «تمام شد» is tapped or "تمام / تأیید" is said. The dialog only closes with
 * «تأیید» or «انصراف» (a tap outside does nothing), and «ادامه» adds more to what was already said.
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
    LaunchedEffect(v.heard) {
        v.heard?.let { h -> said = if (appending) (said + " " + h.text).trim() else h.text; appending = false }
    }
    val live = if (v.listening) (if (appending) "$said ${v.partial}" else v.partial).trim() else said
    val d = remember(live) { if (live.isBlank()) null else VoiceParser.parseDefinition(live) }
    val usable = d != null && (d.name.isNotBlank() || d.buy != null || d.sell != null)

    fun apply() { if (d != null) { onApply(d); onDismiss() } }
    // «تأیید» while still listening: finish the dictation first, then apply what was understood
    LaunchedEffect(v.listening, said) { if (applyWhenDone && !v.listening) { applyWhenDone = false; apply() } }

    AlertDialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = { Text("تعریف کالا با صدا") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!ready) { VoiceModelCard(compact = true); return@Column }
                MicPanel(
                    v,
                    if (said.isBlank()) "بگویید: «بهمن کوچک، قیمت خرید نود هزار، فروش صد هزار، موجودی پنجاه، دسته سیگار» و در آخر «تمام»"
                    else "برای اضافه کردن «ادامه» را بزنید",
                    onMic = { appending = said.isNotBlank(); mic() },
                )
                if (v.listening) {
                    OutlinedButton(onClick = { v.stop() }, modifier = Modifier.fillMaxWidth()) { Text("تمام شد") }
                }
                if (d != null) DefinitionCard(d, cur)
                if (d != null && !v.listening) Text(
                    "فقط بخش‌هایی که گفته شده در فرم پر می‌شوند؛ بعد می‌توانید ویرایش کنید.",
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
                    TextButton(onClick = { said = ""; appending = false; mic() }) { Text("از اول") }
                }
                TextButton(onClick = { v.stop(); onDismiss() }) { Text("انصراف") }
            }
        },
    )
}

@Composable
private fun DefinitionCard(d: VoiceParser.Definition, cur: Currency) {
    fun money(x: Double?) = x?.let { Fmt.money(Math.round(if (d.rial) it / 10 else it / cur.factor), cur) } ?: "—"
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SumRow("نام", d.name.ifBlank { "—" })
            SumRow("قیمت خرید", money(d.buy))
            SumRow("قیمت فروش", money(d.sell))
            SumRow("موجودی", d.stock?.let { Fmt.digits(plain(it)) } ?: "—")
            if (d.minStock != null) SumRow("حداقل موجودی", Fmt.digits(plain(d.minStock)))
            SumRow("دسته", d.category ?: "—")
        }
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
