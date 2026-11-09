package com.tobacco.pos.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tobacco.pos.data.dao.ExpenseDao
import com.tobacco.pos.data.dao.OrderDao
import com.tobacco.pos.data.dao.ProductDao
import com.tobacco.pos.data.dao.SyncEventDao
import com.tobacco.pos.data.entity.*

@Database(
    entities = [
        Product::class,
        Order::class,
        OrderItem::class,
        Expense::class,
        SyncEvent::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TobaccoDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun orderDao(): OrderDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun syncEventDao(): SyncEventDao

    companion object {
        @Volatile
        private var INSTANCE: TobaccoDatabase? = null

        fun getDatabase(context: Context): TobaccoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TobaccoDatabase::class.java,
                    "tobacco_pos_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
