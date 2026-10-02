package com.dailydash.pos

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val price: Int,
    val allowUpsize: Boolean = false,
    val upsizePrice: Int = 10,
    val available: Boolean = true,
    val sortOrder: Int = 0
)

data class CartLine(
    val product: Product,
    val quantity: Int = 1,
    val upsized: Boolean = false
) {
    val unitPrice: Int get() = product.price + if (upsized) product.upsizePrice else 0
    val lineTotal: Int get() = unitPrice * quantity
}

data class CloudOrderResult(
    val remoteId: String,
    val orderNo: String,
    val total: Int,
    val cashReceived: Int,
    val changeAmount: Int,
    val tender: String,
    val createdAt: String
)

data class OrderRecord(
    val id: String,
    val orderNo: String,
    val timestamp: Long,
    val total: Int,
    val tender: String,
    val cashReceived: Int,
    val items: Int
)
