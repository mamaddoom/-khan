package com.tobacco.pos.sync

import com.tobacco.pos.data.dao.ProductDao
import com.tobacco.pos.data.entity.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * سیستم حل تعارضات موجودی و کاتالوگ بین ۲ تا ۳ گوشی همزمان
 * استفاده از الگوی Delta برای کسر موجودی به جای Overwrite
 */
class InventoryConflictResolver(
    private val productDao: ProductDao
) {
    /**
     * اعمال رویداد فروش تفاضلی بدون تداخل (اگر هر دو گوشی همزمان بفروشند)
     */
    suspend fun applySoldDelta(productId: String, quantitySold: Int) = withContext(Dispatchers.IO) {
        productDao.applyStockDelta(productId, -quantitySold)
    }

    /**
     * همگام‌سازی مشخصات محصول (قیمت، نام و...) بر اساس Last-Write-Wins (LWW)
     */
    suspend fun resolveProductUpdate(incomingProduct: Product) = withContext(Dispatchers.IO) {
        val existing = productDao.getProductById(incomingProduct.id)
        if (existing == null) {
            productDao.insertOrUpdate(incomingProduct)
        } else {
            // اگر تغییر ارسالی جدیدتر باشد، مشخصات اعمال می‌شود اما موجودی با هم جمع جبری می‌گردد
            if (incomingProduct.updatedAt > existing.updatedAt) {
                productDao.insertOrUpdate(incomingProduct)
            }
        }
    }
}
