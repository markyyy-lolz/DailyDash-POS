package com.dailydash.pos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PosStore(context: Context) {
    private val prefs = context.getSharedPreferences("dailydash_pos", Context.MODE_PRIVATE)

    fun saveOrder(result: CloudOrderResult, items: Int): OrderRecord {
        val order = OrderRecord(
            id = result.remoteId,
            orderNo = result.orderNo,
            timestamp = System.currentTimeMillis(),
            total = result.total,
            tender = result.tender,
            cashReceived = result.cashReceived,
            items = items
        )
        val arr = JSONArray(prefs.getString("orders", "[]"))
        arr.put(JSONObject().apply {
            put("id", order.id)
            put("orderNo", order.orderNo)
            put("timestamp", order.timestamp)
            put("total", order.total)
            put("tender", order.tender)
            put("cashReceived", order.cashReceived)
            put("items", order.items)
        })
        prefs.edit().putString("orders", arr.toString()).apply()
        return order
    }

    fun orders(): List<OrderRecord> {
        val arr = JSONArray(prefs.getString("orders", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OrderRecord(
                id = o.optString("id"),
                orderNo = o.optString("orderNo", o.optString("id", "DailyDash")),
                timestamp = o.getLong("timestamp"),
                total = o.getInt("total"),
                tender = o.getString("tender"),
                cashReceived = o.optInt("cashReceived", 0),
                items = o.optInt("items", 0)
            )
        }.sortedByDescending { it.timestamp }
    }

    fun newPendingSale(
        lines: List<CartLine>,
        payments: List<PaymentPart>,
        discountCode: String?,
        customerName: String?,
        notes: String?
    ): PendingSale = PendingSale(
        localId = "offline-" + UUID.randomUUID().toString(),
        lines = lines.map { it.copy() },
        payments = payments.map { it.copy() },
        discountCode = discountCode,
        customerName = customerName,
        notes = notes
    )

    fun savePendingSale(sale: PendingSale) {
        val arr = JSONArray(prefs.getString("pending_sales", "[]"))
        arr.put(pendingToJson(sale))
        prefs.edit().putString("pending_sales", arr.toString()).apply()
    }

    fun deletePendingSale(localId: String) {
        val current = JSONArray(prefs.getString("pending_sales", "[]"))
        val out = JSONArray()
        for (i in 0 until current.length()) {
            val row = current.getJSONObject(i)
            if (row.optString("localId") != localId) out.put(row)
        }
        prefs.edit().putString("pending_sales", out.toString()).apply()
    }

    fun pendingSales(): List<PendingSale> {
        val arr = JSONArray(prefs.getString("pending_sales", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { pendingFromJson(arr.getJSONObject(i)) }.getOrNull()
        }.sortedBy { it.createdAt }
    }

    private fun pendingToJson(sale: PendingSale): JSONObject = JSONObject().apply {
        put("localId", sale.localId)
        put("discountCode", sale.discountCode ?: JSONObject.NULL)
        put("customerName", sale.customerName ?: JSONObject.NULL)
        put("notes", sale.notes ?: JSONObject.NULL)
        put("createdAt", sale.createdAt)

        put("payments", JSONArray().apply {
            sale.payments.forEach { p ->
                put(JSONObject().apply {
                    put("method", p.method)
                    put("amount", p.amount)
                    put("referenceNo", p.referenceNo ?: JSONObject.NULL)
                })
            }
        })

        put("lines", JSONArray().apply {
            sale.lines.forEach { line ->
                put(JSONObject().apply {
                    put("quantity", line.quantity)
                    put("upsized", line.upsized)
                    put("product", JSONObject().apply {
                        put("id", line.product.id)
                        put("name", line.product.name)
                        put("category", line.product.category)
                        put("price", line.product.price)
                        put("allowUpsize", line.product.allowUpsize)
                        put("upsizePrice", line.product.upsizePrice)
                        put("available", line.product.available)
                        put("sortOrder", line.product.sortOrder)
                        put("imageUrl", line.product.imageUrl ?: JSONObject.NULL)
                    })
                })
            }
        })
    }

    private fun pendingFromJson(o: JSONObject): PendingSale {
        val linesJson = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until linesJson.length()).map { i ->
            val row = linesJson.getJSONObject(i)
            val p = row.getJSONObject("product")
            CartLine(
                product = Product(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    category = p.getString("category"),
                    price = p.getInt("price"),
                    allowUpsize = p.optBoolean("allowUpsize"),
                    upsizePrice = p.optInt("upsizePrice", 10),
                    available = p.optBoolean("available", true),
                    sortOrder = p.optInt("sortOrder"),
                    imageUrl = p.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" }
                ),
                quantity = row.optInt("quantity", 1),
                upsized = row.optBoolean("upsized", false)
            )
        }

        val paymentsJson = o.optJSONArray("payments") ?: JSONArray()
        val payments = (0 until paymentsJson.length()).map { i ->
            val p = paymentsJson.getJSONObject(i)
            PaymentPart(
                method = p.getString("method"),
                amount = p.getInt("amount"),
                referenceNo = p.optString("referenceNo").takeIf { it.isNotBlank() && it != "null" }
            )
        }

        return PendingSale(
            localId = o.getString("localId"),
            lines = lines,
            payments = payments,
            discountCode = o.optString("discountCode").takeIf { it.isNotBlank() && it != "null" },
            customerName = o.optString("customerName").takeIf { it.isNotBlank() && it != "null" },
            notes = o.optString("notes").takeIf { it.isNotBlank() && it != "null" },
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
