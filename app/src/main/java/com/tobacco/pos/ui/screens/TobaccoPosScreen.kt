package com.tobacco.pos.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobacco.pos.data.entity.PaymentType
import com.tobacco.pos.data.entity.Product
import com.tobacco.pos.data.entity.SaleUnit
import com.tobacco.pos.domain.CartItem
import com.tobacco.pos.domain.CartManager
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TobaccoPosScreen(
    cartManager: CartManager,
    favoriteProducts: List<Product>,
    onScanRequested: () -> Unit,
    onCheckoutComplete: (PaymentType, String?) -> Unit
) {
    val cartItems by cartManager.items.collectAsState()
    val totalAmount = cartManager.calculateTotal()
    val totalProfit = cartManager.calculateTotalProfit()

    var showPaymentDialog by remember { mutableStateOf(false) }
    val persianFormat = remember { NumberFormat.getNumberInstance(Locale("fa", "IR")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("صندوق فروش دخانیات", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        // نشانگر وضعیت سینک مغازه
                        Badge(containerColor = Color(0xFF4CAF50)) {
                            Text("همگام با شبکه مغازه", color = Color.White, fontSize = 10.sp)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onScanRequested) {
                        Text("📷", fontSize = 22.sp)
                    }
                    IconButton(onClick = { cartManager.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "پاک کردن فاکتور")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScanRequested,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("📷 اسکن بارکد با دوربین", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        bottomBar = {
            // نوار پایین: تسویه حساب و نمایش سود
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("مبلغ قابل پرداخت:", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${persianFormat.format(totalAmount)} تومان",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("سود این فاکتور:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Text(
                                "${persianFormat.format(totalProfit)} تومان",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { if (cartItems.isNotEmpty()) showPaymentDialog = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = cartItems.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تسویه و ثبت فاکتور", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ۱. نوار اقلام دسترسی سریع (کالاهای بدون بارکد: فندک، زغال، پیپ)
            Text(
                "اقلام بدون بارکد و پرفروش:",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(favoriteProducts) { prod ->
                    Card(
                        modifier = Modifier
                            .clickable { cartManager.addProduct(prod, SaleUnit.PACK) }
                            .width(110.dp)
                            .height(75.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(prod.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${persianFormat.format(prod.sellPrice)} ت",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // ۲. لیست اقلام سبد خرید جاری
            if (cartItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = Color.Gray.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("سبد خرید خالی است", color = Color.Gray)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onScanRequested,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("📷 باز کردن دوربین و اسکن بارکد")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cartItems) { item ->
                        CartItemRow(
                            item = item,
                            persianFormat = persianFormat,
                            onToggleUnit = { cartManager.toggleUnit(item) },
                            onIncrement = { cartManager.increment(item) },
                            onDecrement = { cartManager.decrement(item) }
                        )
                    }
                }
            }
        }
    }

    // دیالوگ روش پرداخت (کارتخوان، نقد، نسیه)
    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("انتخاب نوع پرداخت", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            onCheckoutComplete(PaymentType.POS_CARD, null)
                            showPaymentDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("پرداخت با کارتخوان")
                    }

                    Button(
                        onClick = {
                            onCheckoutComplete(PaymentType.CASH, null)
                            showPaymentDialog = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("دریافت نقدی")
                    }

                    OutlinedButton(
                        onClick = {
                            onCheckoutComplete(PaymentType.CREDIT, "مشتری دفتری")
                            showPaymentDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountBox, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ثبت نسیه (حساب مشتری)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPaymentDialog = false }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
fun CartItemRow(
    item: CartItem,
    persianFormat: NumberFormat,
    onToggleUnit: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.product.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // بج تغییر حالت بسته / باکس
                    FilterChip(
                        selected = item.unit == SaleUnit.BOX,
                        onClick = onToggleUnit,
                        label = { Text(if (item.unit == SaleUnit.BOX) "باکس (۱۰تایی)" else "بسته تکی", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${persianFormat.format(item.totalPrice)} ت",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                }
            }

            // کلیدهای افزایش/کاهش تعداد
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "کم کردن")
                }
                Text(
                    persianFormat.format(item.quantity),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "اضافه کردن")
                }
            }
        }
    }
}
