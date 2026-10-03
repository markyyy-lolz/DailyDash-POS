package com.dailydash.pos

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val price: Int,
    val allowUpsize: Boolean = false,
    val upsizePrice: Int = 10,
    val available: Boolean = true,
    val sortOrder: Int = 0,
    val imageUrl: String? = null
)

data class CartLine(
    val product: Product,
    val quantity: Int = 1,
    val upsized: Boolean = false
) {
    val unitPrice: Int get() = product.price + if (upsized) product.upsizePrice else 0
    val lineTotal: Int get() = unitPrice * quantity
}

data class StaffMember(
    val id: String,
    val staffCode: String,
    val displayName: String,
    val role: String,
    val avatarUrl: String? = null
)

data class StaffSession(
    val token: String,
    val member: StaffMember
)

data class PaymentPart(
    val method: String,
    val amount: Int,
    val referenceNo: String? = null
)

data class ShiftInfo(
    val id: String,
    val openingCash: Int,
    val closingCash: Int? = null,
    val expectedCash: Int? = null,
    val variance: Int? = null,
    val status: String = "open",
    val openedAt: String = "",
    val closedAt: String? = null
)

data class HeldOrder(
    val id: String,
    val label: String,
    val notes: String?,
    val lines: List<CartLine>,
    val updatedAt: String
)

data class CloudOrderResult(
    val remoteId: String,
    val orderNo: String,
    val total: Int,
    val cashReceived: Int,
    val changeAmount: Int,
    val tender: String,
    val createdAt: String,
    val queueNo: Int = 0,
    val grossTotal: Int = total,
    val discountTotal: Int = 0
)

data class CompletedSale(
    val result: CloudOrderResult,
    val lines: List<CartLine>,
    val staff: StaffMember
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
