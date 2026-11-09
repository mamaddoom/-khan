package com.tobacco.pos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobacco.pos.data.entity.Product
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductManagementScreen(
    products: List<Product>,
    onSaveProduct: (Product) -> Unit,
    onDeleteProduct: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedProductForEdit by remember { mutableStateOf<Product?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredList = remember(products, searchQuery) {
        if (searchQuery.isBlank()) products
        else products.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.brand.contains(searchQuery, ignoreCase = true) ||
            (it.barcode?.contains(searchQuery) == true)
        }
    }

    val persianFormat = remember { NumberFormat.getNumberInstance(Locale("fa", "IR")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مدیریت انبار و کالاها", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    selectedProductForEdit = null
                    showAddDialog = true
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "تعریف کالای جدید")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // نوار جستجو
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("جستجوی نام سیگار، تنباکو، فندک یا بارکد...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList) { product ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedProductForEdit = product
                                showAddDialog = true
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        "بارکد: ${product.barcode ?: "فاقد بارکد (کد: ${product.sku})"}",
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "دسته: ${product.category}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "خرید: ${persianFormat.format(product.buyPrice)} | فروش: ${persianFormat.format(product.sellPrice)} تومان",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // نشانگر موجودی
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "موجودی: ${persianFormat.format(product.stockUnits)}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (product.stockUnits <= product.lowStockThreshold) Color(0xFFEF5350) else Color(0xFF4CAF50),
                                    fontSize = 13.sp
                                )
                                Text(
                                    if (product.unitsPerBox > 0) "(${product.stockUnits / product.unitsPerBox} باکس)" else "",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProductEditDialog(
            initialProduct = selectedProductForEdit,
            onDismiss = { showAddDialog = false },
            onSave = {
                onSaveProduct(it)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun ProductEditDialog(
    initialProduct: Product?,
    onDismiss: () -> Unit,
    onSave: (Product) -> Unit
) {
    var name by remember { mutableStateOf(initialProduct?.name ?: "") }
    var barcode by remember { mutableStateOf(initialProduct?.barcode ?: "") }
    var category by remember { mutableStateOf(initialProduct?.category ?: "سیگار") }
    var buyPrice by remember { mutableStateOf(initialProduct?.buyPrice?.toString() ?: "") }
    var sellPrice by remember { mutableStateOf(initialProduct?.sellPrice?.toString() ?: "") }
    var boxPrice by remember { mutableStateOf(initialProduct?.boxPrice?.toString() ?: "") }
    var stock by remember { mutableStateOf(initialProduct?.stockUnits?.toString() ?: "0") }
    var isFavorite by remember { mutableStateOf(initialProduct?.isFavorite ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initialProduct == null) "تعریف کالای جدید" else "ویرایش کالا و قیمت", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام محصول (مثال: بهمن قرمز)") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("بارکد (در صورت خالی بودن خودکار تولید می‌شود)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = buyPrice,
                        onValueChange = { buyPrice = it },
                        label = { Text("قیمت خرید (ت)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = sellPrice,
                        onValueChange = { sellPrice = it },
                        label = { Text("قیمت فروش (ت)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = boxPrice,
                        onValueChange = { boxPrice = it },
                        label = { Text("قیمت باکسی") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = stock,
                        onValueChange = { stock = it },
                        label = { Text("موجودی (بسته)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isFavorite, onCheckedChange = { isFavorite = it })
                    Text("نمایش در میانبرهای سریع صندوق (اقلام بدون بارکد/پرفروش)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = (initialProduct ?: Product(name = name)).copy(
                        name = name.ifBlank { "کالای بدون نام" },
                        barcode = barcode.ifBlank { null },
                        sku = if (barcode.isBlank()) "SKU-${System.currentTimeMillis() % 10000}" else "",
                        category = category,
                        buyPrice = buyPrice.toLongOrNull() ?: 0L,
                        sellPrice = sellPrice.toLongOrNull() ?: 0L,
                        boxPrice = boxPrice.toLongOrNull() ?: 0L,
                        stockUnits = stock.toIntOrNull() ?: 0,
                        isFavorite = isFavorite,
                        isBarcoded = barcode.isNotBlank(),
                        updatedAt = System.currentTimeMillis()
                    )
                    onSave(p)
                }
            ) {
                Text("ذخیره کالا")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
