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

data class ModifierOption(
    val id: String,
    val productId: String,
    val groupName: String,
    val groupType: String,
    val name: String,
    val priceDelta: Int = 0,
    val isDefault: Boolean = false,
    val required: Boolean = false,
    val maxSelect: Int = 1,
    val sortOrder: Int = 0
)

data class SelectedModifier(
    val option: ModifierOption,
    val quantity: Int = 1
) {
    val lineDelta: Int get() = option.priceDelta * quantity
}

data class CartLine(
    val product: Product,
    val quantity: Int = 1,
    val upsized: Boolean = false,
    val modifiers: List<SelectedModifier> = emptyList()
) {
    val modifierTotal: Int get() = modifiers.sumOf { it.lineDelta }
    val unitPrice: Int get() =
        product.price + (if (upsized) product.upsizePrice else 0) + modifierTotal
    val lineTotal: Int get() = unitPrice * quantity
    val modifierLabel: String
        get() = modifiers.joinToString(", ") {
            if (it.quantity > 1) it.quantity.toString() + "× " + it.option.name
            else it.option.name
        }
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

data class DiscountPreview(
    val label: String?,
    val discountTotal: Int,
    val total: Int
)

data class CustomerLoyalty(
    val id: String,
    val name: String,
    val phone: String,
    val pointsBalance: Int,
    val lifetimePoints: Int = 0,
    val lifetimeSpend: Int = 0,
    val tier: String = "Member",
    val birthday: String? = null
)

data class AppReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val updateUrl: String?,
    val required: Boolean,
    val publishedAt: String
)

data class ReceiptBranding(
    val storeName: String = "DailyDash",
    val branchName: String = "DailyDash - Paombong",
    val address: String = "Paombong, Bulacan",
    val phone: String = "",
    val footer: String = "Thank you for choosing DailyDash!"
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
    val discountTotal: Int = 0,
    val orderType: String = "Takeout",
    val tableNo: String? = null,
    val loyaltyRedeemed: Int = 0,
    val loyaltyEarned: Int = 0,
    val loyaltyBalance: Int = 0
)

data class CompletedSale(
    val result: CloudOrderResult,
    val lines: List<CartLine>,
    val staff: StaffMember
)

data class PendingSale(
    val localId: String,
    val lines: List<CartLine>,
    val payments: List<PaymentPart>,
    val discountCode: String? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val redeemPoints: Int = 0,
    val orderType: String = "Takeout",
    val tableNo: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
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
