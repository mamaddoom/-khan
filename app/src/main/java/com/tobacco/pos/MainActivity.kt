package com.tobacco.pos

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.tobacco.pos.data.database.TobaccoDatabase
import com.tobacco.pos.data.entity.Order
import com.tobacco.pos.data.entity.OrderItem
import com.tobacco.pos.data.entity.PaymentType
import com.tobacco.pos.data.entity.Product
import com.tobacco.pos.domain.CartManager
import com.tobacco.pos.domain.ProfitReportEngine
import com.tobacco.pos.domain.ShopFinancialReport
import com.tobacco.pos.sync.InventoryConflictResolver
import com.tobacco.pos.sync.LocalWifiSyncManager
import com.tobacco.pos.ui.screens.ProductManagementScreen
import com.tobacco.pos.ui.screens.ReportDashboardScreen
import com.tobacco.pos.ui.screens.TobaccoPosScreen
import com.tobacco.pos.ui.theme.TobaccoPOSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var database: TobaccoDatabase
    private lateinit var cartManager: CartManager
    private lateinit var conflictResolver: InventoryConflictResolver
    private lateinit var wifiSyncManager: LocalWifiSyncManager
    private lateinit var reportEngine: ProfitReportEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = TobaccoDatabase.getDatabase(this)
        cartManager = CartManager()
        conflictResolver = InventoryConflictResolver(database.productDao())
        wifiSyncManager = LocalWifiSyncManager(this, conflictResolver)
        reportEngine = ProfitReportEngine(database.orderDao(), database.expenseDao(), database.productDao())

        // راه‌اندازی سرور سینک داخلی مغازه
        wifiSyncManager.startLocalServer()
        wifiSyncManager.startDiscovery()

        // بارگذاری اقلام پیش‌فرض دخانیات (برگرفته از بارکدهای واقعی تصویر مغازه)
        seedInitialTobaccoProducts()

        setContent {
            TobaccoPOSTheme {
                MainAppContainer()
            }
        }
    }

    private fun seedInitialTobaccoProducts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = database.productDao().getProductByBarcode("6261204002540")
            if (count == null) {
                val initialCatalog = listOf(
                    Product(
                        barcode = "6261204002540",
                        name = "بهمن قرمز",
                        brand = "بهمن",
                        category = "سیگار",
                        buyPrice = 30000L,
                        sellPrice = 35000L,
                        boxPrice = 340000L,
                        unitsPerBox = 10,
                        stockUnits = 100,
                        isFavorite = true
                    ),
                    Product(
                        barcode = "6261204002809",
                        name = "بهمن نانو سیلور",
                        brand = "بهمن",
                        category = "سیگار",
                        buyPrice = 32000L,
                        sellPrice = 38000L,
                        boxPrice = 370000L,
                        unitsPerBox = 10,
                        stockUnits = 80,
                        isFavorite = true
                    ),
                    Product(
                        barcode = "6262003401954",
                        name = "وینستون لایت (آبی)",
                        brand = "وینستون",
                        category = "سیگار",
                        buyPrice = 45000L,
                        sellPrice = 52000L,
                        boxPrice = 510000L,
                        unitsPerBox = 10,
                        stockUnits = 120,
                        isFavorite = true
                    ),
                    Product(
                        barcode = "6265826101407",
                        name = "کنت نانو ۴",
                        brand = "کنت",
                        category = "سیگار",
                        buyPrice = 42000L,
                        sellPrice = 48000L,
                        boxPrice = 470000L,
                        unitsPerBox = 10,
                        stockUnits = 70,
                        isFavorite = true
                    ),
                    Product(
                        barcode = "7622100743705",
                        name = "مارلبرو فیلیپ موریس",
                        brand = "مارلبرو",
                        category = "سیگار",
                        buyPrice = 90000L,
                        sellPrice = 110000L,
                        boxPrice = 1050000L,
                        unitsPerBox = 10,
                        stockUnits = 40,
                        isFavorite = true
                    ),
                    Product(
                        barcode = "6263900400255",
                        name = "تنباکو الشیخ ۵۰ گرمی نعناع",
                        brand = "الشیخ",
                        category = "تنباکو",
                        buyPrice = 28000L,
                        sellPrice = 35000L,
                        boxPrice = 330000L,
                        unitsPerBox = 10,
                        stockUnits = 60,
                        isFavorite = true
                    ),
                    // اقلام بدون بارکد
                    Product(
                        barcode = null,
                        sku = "SKU-CLIPPER",
                        name = "فندک کلیپر فلزی",
                        brand = "Clipper",
                        category = "فندک",
                        buyPrice = 65000L,
                        sellPrice = 85000L,
                        stockUnits = 25,
                        isBarcoded = false,
                        isFavorite = true
                    ),
                    Product(
                        barcode = null,
                        sku = "SKU-BIC",
                        name = "فندک بیک بزرگ اصل",
                        brand = "Bic",
                        category = "فندک",
                        buyPrice = 40000L,
                        sellPrice = 55000L,
                        stockUnits = 50,
                        isBarcoded = false,
                        isFavorite = true
                    ),
                    Product(
                        barcode = null,
                        sku = "SKU-CHARCOAL",
                        name = "زغال خودسوز قلیان بسته ۱۰ تایی",
                        brand = "ملزومات",
                        category = "زغال",
                        buyPrice = 15000L,
                        sellPrice = 25000L,
                        stockUnits = 90,
                        isBarcoded = false,
                        isFavorite = true
                    )
                )
                database.productDao().insertAll(initialCatalog)
            }
        }
    }

    @Composable
    private fun MainAppContainer() {
        var currentTab by remember { mutableIntStateOf(0) }
        val products by database.productDao().getAllProducts().collectAsState(initial = emptyList())
        val favorites by database.productDao().getFavoriteProducts().collectAsState(initial = emptyList())

        var reportData by remember {
            mutableStateOf(
                ShopFinancialReport(
                    totalRevenue = 0L,
                    grossProfit = 0L,
                    totalExpenses = 0L,
                    netProfit = 0L,
                    profitMarginPercentage = 0.0,
                    totalInvoicesCount = 0,
                    topSellingProducts = emptyList(),
                    deadStockProducts = emptyList()
                )
            )
        }
        var selectedPeriod by remember { mutableStateOf("امروز") }

        LaunchedEffect(selectedPeriod, currentTab) {
            if (currentTab == 2) {
                val now = System.currentTimeMillis()
                val oneDayAgo = now - 24 * 3600 * 1000
                reportData = reportEngine.generateReport(oneDayAgo, now)
            }
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                        label = { Text("صندوق") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("انبار و کالاها") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text("گزارش سود") }
                    )
                }
            }
        ) { innerPadding ->
            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (currentTab) {
                    0 -> TobaccoPosScreen(
                        cartManager = cartManager,
                        favoriteProducts = favorites,
                        onScanRequested = {
                            // در تست دمو اولین محصول بارکددار اضافه می‌شود یا اسکنر باز می‌شود
                            val demoProduct = products.firstOrNull()
                            if (demoProduct != null) {
                                cartManager.addProduct(demoProduct)
                                Toast.makeText(this@MainActivity, "اسکن شد: ${demoProduct.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onCheckoutComplete = { paymentType, customer ->
                            checkoutCart(paymentType, customer)
                        }
                    )
                    1 -> ProductManagementScreen(
                        products = products,
                        onSaveProduct = { p ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                database.productDao().insertOrUpdate(p)
                            }
                        },
                        onDeleteProduct = { id ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                database.productDao().softDelete(id)
                            }
                        }
                    )
                    2 -> ReportDashboardScreen(
                        report = reportData,
                        selectedPeriod = selectedPeriod,
                        onPeriodSelected = { selectedPeriod = it }
                    )
                }
            }
        }
    }

    private fun checkoutCart(paymentType: PaymentType, customer: String?) {
        val items = cartManager.items.value
        if (items.isEmpty()) return

        val totalAmount = cartManager.calculateTotal()
        val totalProfit = cartManager.calculateTotalProfit()

        lifecycleScope.launch(Dispatchers.IO) {
            val order = Order(
                totalAmount = totalAmount,
                totalProfit = totalProfit,
                finalPayable = totalAmount,
                paymentType = paymentType,
                customerName = customer
            )
            database.orderDao().insertOrder(order)

            val orderItems = items.map {
                OrderItem(
                    orderId = order.orderId,
                    productId = it.product.id,
                    productName = it.product.name,
                    unit = it.unit,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    unitCost = it.unitCost,
                    rowTotal = it.totalPrice,
                    rowProfit = it.totalProfit
                )
            }
            database.orderDao().insertOrderItems(orderItems)

            // کسر موجودی به شیوه تفاضلی و انتشار همگام به سایر گوشی‌ها
            for (item in items) {
                val unitsToDeduct = item.totalUnitsToDeduct
                conflictResolver.applySoldDelta(item.product.id, unitsToDeduct)
                wifiSyncManager.broadcastSaleEvent(item.product.id, unitsToDeduct)
            }

            withContext(Dispatchers.Main) {
                cartManager.clear()
                Toast.makeText(this@MainActivity, "فاکتور ثبت شد و موجودی در شبکه مغازه کسر گردید", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiSyncManager.stop()
    }
}
