package com.tobacco.pos.data.dao

import androidx.room.*
import com.tobacco.pos.data.entity.Order
import com.tobacco.pos.data.entity.OrderItem
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: Order)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItem>)

    @Query("SELECT * FROM orders ORDER BY timestamp DESC")
    fun getAllOrders(): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getOrdersBetween(startTime: Long, endTime: Long): List<Order>

    @Query("SELECT * FROM order_items WHERE orderId = :orderId")
    suspend fun getItemsForOrder(orderId: String): List<OrderItem>

    @Query("SELECT * FROM order_items WHERE orderId IN (SELECT orderId FROM orders WHERE timestamp >= :startTime AND timestamp <= :endTime)")
    suspend fun getSoldItemsBetween(startTime: Long, endTime: Long): List<OrderItem>

    @Query("SELECT SUM(finalPayable) FROM orders WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getTotalSalesBetween(startTime: Long, endTime: Long): Long?

    @Query("SELECT SUM(totalProfit) FROM orders WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getTotalProfitBetween(startTime: Long, endTime: Long): Long?
}
