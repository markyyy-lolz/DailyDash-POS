package com.dailydash.pos

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
}
