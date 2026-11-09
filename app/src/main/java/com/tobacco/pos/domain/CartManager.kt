package com.tobacco.pos.domain

import com.tobacco.pos.data.entity.Product
import com.tobacco.pos.data.entity.SaleUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CartItem(
    val product: Product,
    val unit: SaleUnit = SaleUnit.PACK,
    val quantity: Int = 1
) {
    val unitPrice: Long
        get() = if (unit == SaleUnit.BOX && product.boxPrice > 0) product.boxPrice else product.sellPrice

    val unitCost: Long
        get() = if (unit == SaleUnit.BOX) product.buyPrice * product.unitsPerBox else product.buyPrice

    val totalPrice: Long
        get() = unitPrice * quantity

    val totalProfit: Long
        get() = (unitPrice - unitCost) * quantity

    // کل بسته‌ها برای کسر دقیق از موجودی
    val totalUnitsToDeduct: Int
        get() = if (unit == SaleUnit.BOX) quantity * product.unitsPerBox else quantity
}

class CartManager {
    private val _items = MutableStateFlow<List<CartItem>>(emptyList())
    val items: StateFlow<List<CartItem>> = _items.asStateFlow()

    fun addProduct(product: Product, unit: SaleUnit = SaleUnit.PACK) {
        val currentList = _items.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.product.id == product.id && it.unit == unit }

        if (existingIndex >= 0) {
            val existing = currentList[existingIndex]
            currentList[existingIndex] = existing.copy(quantity = existing.quantity + 1)
        } else {
            currentList.add(CartItem(product = product, unit = unit, quantity = 1))
        }
        _items.value = currentList
    }

    fun toggleUnit(item: CartItem) {
        val currentList = _items.value.toMutableList()
        val index = currentList.indexOfFirst { it.product.id == item.product.id && it.unit == item.unit }
        if (index >= 0) {
            val newUnit = if (item.unit == SaleUnit.PACK) SaleUnit.BOX else SaleUnit.PACK
            currentList[index] = item.copy(unit = newUnit)
            _items.value = currentList
        }
    }

    fun increment(item: CartItem) {
        val currentList = _items.value.toMutableList()
        val index = currentList.indexOfFirst { it.product.id == item.product.id && it.unit == item.unit }
        if (index >= 0) {
            currentList[index] = item.copy(quantity = item.quantity + 1)
            _items.value = currentList
        }
    }

    fun decrement(item: CartItem) {
        val currentList = _items.value.toMutableList()
        val index = currentList.indexOfFirst { it.product.id == item.product.id && it.unit == item.unit }
        if (index >= 0) {
            if (item.quantity > 1) {
                currentList[index] = item.copy(quantity = item.quantity - 1)
            } else {
                currentList.removeAt(index)
            }
            _items.value = currentList
        }
    }

    fun clear() {
        _items.value = emptyList()
    }

    fun calculateTotal(): Long = _items.value.sumOf { it.totalPrice }
    fun calculateTotalProfit(): Long = _items.value.sumOf { it.totalProfit }
}
