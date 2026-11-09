package com.tobacco.pos.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class PaymentType {
    CASH,       // نقدی
    POS_CARD,   // کارتخوان
    CREDIT,     // نسیه / حساب دفتری
    SPLIT       // ترکیبی
}

enum class SaleUnit {
    PACK,       // بسته / عدد
    BOX         // باکس / کارتن
}

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey
    val orderId: String = UUID.randomUUID().toString(),
    val invoiceNumber: Long = System.currentTimeMillis() % 1000000,
    val totalAmount: Long,                    // مبلغ کل فاکتور (تومان)
    val totalProfit: Long,                    // سود ناخالص این فاکتور (تومان)
    val discount: Long = 0L,                  // تخفیف
    val finalPayable: Long,                   // مبلغ نهایی دریافتی
    val paymentType: PaymentType = PaymentType.POS_CARD,
    val customerName: String? = null,         // نام مشتری (برای نسیه)
    val cashierDevice: String = "Device-1",   // نام دستگاه ثبت‌کننده
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "order_items")
data class OrderItem(
    @PrimaryKey
    val itemId: String = UUID.randomUUID().toString(),
    val orderId: String,
    val productId: String,
    val productName: String,
    val unit: SaleUnit = SaleUnit.PACK,
    val quantity: Int,                        // تعداد پاکت یا باکس
    val unitPrice: Long,                      // قیمت فروش واحد در زمان ثبت
    val unitCost: Long,                       // بهای خرید واحد در زمان ثبت
    val rowTotal: Long,
    val rowProfit: Long                       // سود این ردیف
)
