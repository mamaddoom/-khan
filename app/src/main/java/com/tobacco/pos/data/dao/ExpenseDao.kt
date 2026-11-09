package com.tobacco.pos.data.dao

import androidx.room.*
import com.tobacco.pos.data.entity.Expense
import com.tobacco.pos.data.entity.SyncEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: Expense)

    @Query("SELECT * FROM expenses ORDER BY timestamp DESC")
    fun getAllExpenses(): Flow<List<Expense>>

    @Query("SELECT SUM(amount) FROM expenses WHERE timestamp >= :startTime AND timestamp <= :endTime")
    suspend fun getTotalExpensesBetween(startTime: Long, endTime: Long): Long?
}

@Dao
interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SyncEvent)

    @Query("SELECT * FROM sync_events WHERE isSyncedLocally = 0 ORDER BY timestamp ASC")
    suspend fun getPendingLocalEvents(): List<SyncEvent>

    @Query("UPDATE sync_events SET isSyncedLocally = 1 WHERE eventId = :eventId")
    suspend fun markLocallySynced(eventId: String)
}
