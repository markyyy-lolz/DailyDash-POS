package com.dailydash.pos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class SupabaseApi {
    companion object {
        const val SUPABASE_URL = "https://cpodvrwykhkndtwcsmgp.supabase.co"
        const val PUBLISHABLE_KEY = "sb_publishable_FeiWv8Dur_Qr3d0LF4RBhw_QSObAAHj"
        const val DEVICE_CODE = "DAILYDASH-ANDROID-01"
    }

    suspend fun loadProducts(): Result<List<Product>> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = "$SUPABASE_URL/rest/v1/dailydash_products?select=id,name,category,price,allow_upsize,upsize_price,available,sort_order&order=sort_order.asc"
            val connection = open(endpoint, "GET")
            val body = readResponse(connection)
            if (connection.responseCode !in 200..299) error("Cloud menu error ${connection.responseCode}: $body")
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Product(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    category = p.getString("category"),
                    price = p.getInt("price"),
                    allowUpsize = p.optBoolean("allow_upsize", false),
                    upsizePrice = p.optInt("upsize_price", 10),
                    available = p.optBoolean("available", true),
                    sortOrder = p.optInt("sort_order", 0)
                )
            }
        }
    }

    suspend fun createOrder(cart: List<CartLine>, tender: String, cashReceived: Int): Result<CloudOrderResult> = withContext(Dispatchers.IO) {
        runCatching {
            val items = JSONArray()
            cart.forEach { line ->
                items.put(JSONObject().apply {
                    put("product_id", line.product.id)
                    put("quantity", line.quantity)
                    put("upsized", line.upsized)
                })
            }
            val payload = JSONObject().apply {
                put("p_items", items)
                put("p_tender", tender)
                put("p_cash_received", cashReceived)
                put("p_device_code", DEVICE_CODE)
            }
            val connection = open("$SUPABASE_URL/rest/v1/rpc/dailydash_create_order", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val body = readResponse(connection)
            if (connection.responseCode !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                error(if (msg.isNotBlank()) msg else "Checkout failed (${connection.responseCode})")
            }
            val o = JSONObject(body)
            CloudOrderResult(
                remoteId = o.getString("id"),
                orderNo = o.getString("order_no"),
                total = o.getInt("total"),
                cashReceived = o.getInt("cash_received"),
                changeAmount = o.getInt("change_amount"),
                tender = o.getString("tender"),
                createdAt = o.optString("created_at")
            )
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("apikey", PUBLISHABLE_KEY)
            setRequestProperty("Authorization", "Bearer $PUBLISHABLE_KEY")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }
}
