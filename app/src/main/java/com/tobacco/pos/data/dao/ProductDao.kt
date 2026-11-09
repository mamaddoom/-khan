package com.tobacco.pos.data.dao

import androidx.room.*
import com.tobacco.pos.data.entity.Product
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE isDeleted = 0 ORDER BY name ASC")
    fun getAllProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE barcode = :barcode AND isDeleted = 0 LIMIT 1")
    suspend fun getProductByBarcode(barcode: String): Product?

    @Query("SELECT * FROM products WHERE id = :id AND isDeleted = 0 LIMIT 1")
    suspend fun getProductById(id: String): Product?

    @Query("SELECT * FROM products WHERE isFavorite = 1 AND isDeleted = 0 ORDER BY name ASC")
    fun getFavoriteProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE stockUnits <= lowStockThreshold AND isDeleted = 0")
    fun getLowStockProducts(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE isDeleted = 0 AND (name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' OR barcode LIKE '%' || :query || '%')")
    fun searchProducts(query: String): Flow<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(product: Product)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Query("UPDATE products SET stockUnits = stockUnits + :delta, updatedAt = :timestamp WHERE id = :productId")
    suspend fun applyStockDelta(productId: String, delta: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE products SET isDeleted = 1, updatedAt = :timestamp WHERE id = :productId")
    suspend fun softDelete(productId: String, timestamp: Long = System.currentTimeMillis())
}
