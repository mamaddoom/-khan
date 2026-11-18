package com.mozkhan.shop.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mozkhan.shop.data.Sale
import com.mozkhan.shop.util.Fmt
import com.mozkhan.shop.util.InvoiceShare
import com.mozkhan.shop.util.PayMethods
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Share one invoice: a preview of the A5 page, then «تصویر» (PNG pages), «PDF» (A5, ready to print) or «متن»
 * (plain text for messengers).
 */
@Composable
fun ShareInvoiceDialog(vm: MainVm, sale: Sale, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by remember(sale.id) { vm.repo.observeItemsOf(sale.id) }.collectAsState(initial = null)
    val customers by vm.customers.collectAsState()
    val methods by vm.prefs.payMethods.collectAsState()
    val shop by vm.prefs.shopName.collectAsState()
    val cur by vm.currency.collectAsState()
    val inv = items?.let { list ->
        InvoiceShare.Invoice(
            shop, sale, list, sale.customerId?.let { id -> customers.firstOrNull { it.id == id }?.name },
            PayMethods.label(sale.payment, methods), cur,
        )
    }
    val pages = inv?.let { InvoiceShare.paginate(it.items.size).size } ?: 1
    var page by remember { mutableStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(inv, page) {
        val i = inv ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { InvoiceShare.bitmap(ctx, i, page, 720) }
    }

    fun go(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: Exception) { Toast.makeText(ctx, "ساخت فاکتور ناموفق بود: ${e.message}", Toast.LENGTH_LONG).show() } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اشتراک فاکتور") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(InvoiceShare.PAGE_W / InvoiceShare.PAGE_H).heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val b = preview
                    if (b == null) CircularProgressIndicator()
                    else Image(b.asImageBitmap(), "پیش‌نمایش فاکتور", Modifier.fillMaxWidth())
                }
                if (pages > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = page > 0, onClick = { page-- }) { Text("صفحهٔ قبل") }
                    Text(Fmt.digits("${page + 1} از $pages"), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = page < pages - 1, onClick = { page++ }) { Text("صفحهٔ بعد") }
                }
                Text("اندازهٔ A5 — برای چاپ «PDF» و برای پیام‌رسان «تصویر» یا «متن» را بزنید.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = inv != null && !busy, onClick = {
                        go { InvoiceShare.shareFiles(ctx, InvoiceShare.images(ctx, inv!!), "image/png", "اشتراک تصویر فاکتور") }
                    }, modifier = Modifier.weight(1f)) { Text("تصویر") }
                    OutlinedButton(enabled = inv != null && !busy, onClick = {
                        go { InvoiceShare.shareFiles(ctx, listOf(InvoiceShare.pdf(ctx, inv!!)), "application/pdf", "اشتراک PDF فاکتور") }
                    }, modifier = Modifier.weight(1f)) { Text("PDF") }
                    OutlinedButton(enabled = inv != null && !busy, onClick = {
                        InvoiceShare.shareText(ctx, InvoiceShare.text(inv!!))
                    }, modifier = Modifier.weight(1f)) { Text("متن") }
                }
                if (busy) Text("در حال ساخت فاکتور…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } },
    )
}
