package com.tobacco.pos.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * دیتابیس کالاها - پشتیبانی از اقلام بارکددار (سیگار، تنباکو) و بدون بارکد (فندک فله، زغال، پیپ)
 */
@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"], unique = false),
        Index(value = ["category"]),
        Index(value = ["isFavorite"])
    ]
)
data class Product(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val barcode: String? = null,              // بارکد میله‌ای EAN-13 یا null برای کالای بدون بارکد
    val sku: String = "",                     // کد داخلی منحصر‌به‌فرد مغازه
    val name: String,                         // نام کالا: مثلا بهمن قرمز، وینستون لایت
    val brand: String = "",                   // برند: Bahman, Winston, Kent, Al-Sheikh
    val category: String = "سیگار",           // سیگار، تنباکو، فندک، زغال، ملزومات
    val buyPrice: Long = 0L,                  // قیمت خرید بر اساس هر بسته (تومان)
    val sellPrice: Long = 0L,                 // قیمت فروش تکی هر بسته (تومان)
    val boxPrice: Long = 0L,                  // قیمت فروش عمده/باکسی (تومان)
    val unitsPerBox: Int = 10,                // تعداد بسته در هر باکس (مثلا ۱۰ پاکت)
    val stockUnits: Int = 0,                  // موجودی کل فروشگاه بر اساس بسته/عدد
    val lowStockThreshold: Int = 5,           // حداقل موجودی برای هشدار کسری
    val isBarcoded: Boolean = true,           // آیا بارکد فیزیکی دارد؟
    val isFavorite: Boolean = false,          // نمایش در دکمه‌های سریع صندوق
    val visualFeatureTag: String? = null,     // برچسب ویژگی ظاهری برای تشخیص بدون بارکد
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false            // Soft-delete برای جلوگیری از تداخل سینک
)
