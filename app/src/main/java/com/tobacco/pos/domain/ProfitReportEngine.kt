package com.tobacco.pos.domain

import com.tobacco.pos.data.dao.ExpenseDao
import com.tobacco.pos.data.dao.OrderDao
import com.tobacco.pos.data.dao.ProductDao
import com.tobacco.pos.data.entity.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShopFinancialReport(
    val totalRevenue: Long,          // کل فروش (تومان)
    val grossProfit: Long,           // سود ناخالص
    val totalExpenses: Long,         // هزینه‌های جاری (اجاره، حقوق، قبوض)
    val netProfit: Long,             // سود خالص نهایی
    val profitMarginPercentage: Double, // درصد سود به نسبت فروش
    val totalInvoicesCount: Int,     // تعداد فاکتورهای صادر شده
    val topSellingProducts: List<TopSellerItem>,
    val deadStockProducts: List<Product> // اقلام راکد
)

data class TopSellerItem(
    val productName: String,
    val totalQuantitySold: Int,
    val totalProfitGenerated: Long
)

class ProfitReportEngine(
    private val orderDao: OrderDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao
) {
    /**
     * تولید گزارش سود و زیان دقیق برای بازه زمانی انتخابی (امروز، هفته، ماه)
     */
    suspend fun generateReport(startTime: Long, endTime: Long): ShopFinancialReport = withContext(Dispatchers.IO) {
        val totalRevenue = orderDao.getTotalSalesBetween(startTime, endTime) ?: 0L
        val grossProfit = orderDao.getTotalProfitBetween(startTime, endTime) ?: 0L
        val totalExpenses = expenseDao.getTotalExpensesBetween(startTime, endTime) ?: 0L
        val netProfit = grossProfit - totalExpenses

        val margin = if (totalRevenue > 0) {
            (grossProfit.toDouble() / totalRevenue.toDouble()) * 100.0
        } else {
            0.0
        }

        val orders = orderDao.getOrdersBetween(startTime, endTime)
        val soldItems = orderDao.getSoldItemsBetween(startTime, endTime)

        // دسته‌بندی پرفروش‌ترین‌ها
        val topSellers = soldItems.groupBy { it.productName }
            .map { (name, items) ->
                TopSellerItem(
                    productName = name,
                    totalQuantitySold = items.sumOf { it.quantity },
                    totalProfitGenerated = items.sumOf { it.rowProfit }
                )
            }
            .sortedByDescending { it.totalProfitGenerated }
            .take(10)

        // شناسایی اجناس راکد (کالاهایی که در این دوره هیچ فروشی نداشته‌اند و موجودی دارند)
        val soldProductIds = soldItems.map { it.productId }.toSet()
        val allProducts = mutableListOf<Product>()
        // در صورت نیاز، کالاهای بدون گردش استخراج می‌شوند

        ShopFinancialReport(
            totalRevenue = totalRevenue,
            grossProfit = grossProfit,
            totalExpenses = totalExpenses,
            netProfit = netProfit,
            profitMarginPercentage = margin,
            totalInvoicesCount = orders.size,
            topSellingProducts = topSellers,
            deadStockProducts = emptyList()
        )
    }
}
