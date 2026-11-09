package com.tobacco.pos.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,                        // عنوان هزینه: اجاره، حقوق شاگرد، قبض، ملزومات
    val amount: Long,                         // مبلغ (تومان)
    val category: String = "جاری",
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class SyncAction {
    PRODUCT_UPSERT,
    STOCK_DELTA,     // کسر تفاضلی موجودی (جلوگیری از تداخل)
    ORDER_CREATED,
    EXPENSE_CREATED
}

@Entity(tableName = "sync_events")
data class SyncEvent(
    @PrimaryKey
    val eventId: String = UUID.randomUUID().toString(),
    val action: SyncAction,
    val payloadJson: String,
    val originDeviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSyncedLocally: Boolean = false,
    val isSyncedCloud: Boolean = false
)
