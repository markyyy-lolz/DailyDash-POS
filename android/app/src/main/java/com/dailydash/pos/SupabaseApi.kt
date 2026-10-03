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
        private const val ADMIN_URL = "$SUPABASE_URL/functions/v1/dailydash-admin"
    }

    suspend fun staffDirectory(): Result<List<StaffMember>> = withContext(Dispatchers.IO) {
        runCatching {
            val data = postJson(ADMIN_URL, JSONObject().put("action", "staff_directory"))
            val arr = data.optJSONArray("staff") ?: JSONArray()
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toStaff() }
        }
    }

    suspend fun staffPinLogin(staffId: String, pin: String): Result<StaffSession> =
        staffLogin(JSONObject().apply {
            put("action", "staff_login")
            put("method", "pin")
            put("staff_id", staffId)
            put("secret", pin)
            put("device_code", DEVICE_CODE)
        })

    suspend fun staffPasswordLogin(username: String, password: String): Result<StaffSession> =
        staffLogin(JSONObject().apply {
            put("action", "staff_login")
            put("method", "password")
            put("username", username)
            put("secret", password)
            put("device_code", DEVICE_CODE)
        })

    private suspend fun staffLogin(payload: JSONObject): Result<StaffSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = postJson(ADMIN_URL, payload)
                StaffSession(
                    data.getString("session_token"),
                    data.getJSONObject("staff").toStaff()
                )
            }
        }

    suspend fun staffLogout(token: String) = withContext(Dispatchers.IO) {
        runCatching {
            postJson(
                ADMIN_URL,
                JSONObject()
                    .put("action", "staff_logout")
                    .put("session_token", token)
            )
        }
    }

    suspend fun loadProducts(): Result<List<Product>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = rpc("dailydash_get_pos_products", JSONObject())
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Product(
                    id = p.getString("id"),
                    name = p.getString("name"),
                    category = p.getString("category"),
                    price = p.getInt("price"),
                    allowUpsize = p.optBoolean("allow_upsize"),
                    upsizePrice = p.optInt("upsize_price", 10),
                    available = p.optBoolean("available", true),
                    sortOrder = p.optInt("sort_order"),
                    imageUrl = p.optString("image_url")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            }
        }
    }

    suspend fun currentShift(staffToken: String): Result<ShiftInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = rpc(
                    "dailydash_current_shift",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                )
                if (body == "null" || body.isBlank()) null
                else JSONObject(body).toShift()
            }
        }

    suspend fun openShift(
        staffToken: String,
        openingCash: Int
    ): Result<ShiftInfo> = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(
                rpc(
                    "dailydash_open_shift",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                        .put("p_opening_cash", openingCash)
                )
            ).toShift()
        }
    }

    suspend fun closeShift(
        staffToken: String,
        shiftId: String,
        closingCash: Int
    ): Result<ShiftInfo> = withContext(Dispatchers.IO) {
        runCatching {
            JSONObject(
                rpc(
                    "dailydash_close_shift",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_shift_id", shiftId)
                        .put("p_closing_cash", closingCash)
                )
            ).toShift()
        }
    }

    suspend fun holdOrder(
        staffToken: String,
        cart: List<CartLine>,
        label: String,
        notes: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val items = cartJson(cart)
            val data = JSONObject(
                rpc(
                    "dailydash_hold_order",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                        .put("p_cart", items)
                        .put("p_label", label)
                        .put("p_notes", notes)
                )
            )
            data.getString("id")
        }
    }

    suspend fun heldOrders(staffToken: String): Result<List<HeldOrder>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val products = loadProducts().getOrElse { emptyList() }
                    .associateBy { it.id }
                val body = rpc(
                    "dailydash_held_orders",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                )
                val arr = JSONArray(body)
                (0 until arr.length()).mapNotNull { index ->
                    val row = arr.getJSONObject(index)
                    val cart = row.optJSONArray("cart") ?: JSONArray()
                    val lines = (0 until cart.length()).mapNotNull { i ->
                        val item = cart.getJSONObject(i)
                        val product = products[item.optString("product_id")]
                            ?: return@mapNotNull null
                        CartLine(
                            product = product,
                            quantity = item.optInt("quantity", 1).coerceAtLeast(1),
                            upsized = item.optBoolean("upsized", false)
                        )
                    }
                    if (lines.isEmpty()) null
                    else HeldOrder(
                        id = row.getString("id"),
                        label = row.optString("label", "Held Order"),
                        notes = row.optString("notes").takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        lines = lines,
                        updatedAt = row.optString("updated_at")
                    )
                }
            }
        }

    suspend fun deleteHeldOrder(
        staffToken: String,
        id: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            rpc(
                "dailydash_delete_held_order",
                JSONObject()
                    .put("p_staff_session", staffToken)
                    .put("p_id", id)
            ).trim() == "true"
        }
    }

    suspend fun createOrder(
        cart: List<CartLine>,
        tender: String,
        cashReceived: Int,
        staffToken: String
    ): Result<CloudOrderResult> {
        return createOrderAdvanced(
            cart = cart,
            payments = listOf(
                PaymentPart(
                    method = tender,
                    amount = cashReceived
                )
            ),
            staffToken = staffToken
        )
    }

    suspend fun createOrderAdvanced(
        cart: List<CartLine>,
        payments: List<PaymentPart>,
        staffToken: String,
        discountCode: String? = null,
        manualDiscountType: String? = null,
        manualDiscountValue: Int = 0,
        manualDiscountLabel: String = "Manual Discount",
        managerPin: String? = null,
        customerName: String? = null,
        notes: String? = null
    ): Result<CloudOrderResult> = withContext(Dispatchers.IO) {
        runCatching {
            val payArray = JSONArray()
            payments.forEach { pay ->
                payArray.put(
                    JSONObject()
                        .put("method", pay.method)
                        .put("amount", pay.amount)
                        .put(
                            "reference_no",
                            pay.referenceNo?.takeIf { it.isNotBlank() }
                                ?: JSONObject.NULL
                        )
                )
            }

            val payload = JSONObject()
                .put("p_items", cartJson(cart))
                .put("p_payments", payArray)
                .put("p_device_code", DEVICE_CODE)
                .put("p_staff_session", staffToken)
                .put(
                    "p_discount_code",
                    discountCode?.takeIf { it.isNotBlank() }
                        ?: JSONObject.NULL
                )
                .put(
                    "p_manager_pin",
                    managerPin?.takeIf { it.isNotBlank() }
                        ?: JSONObject.NULL
                )
                .put(
                    "p_customer_name",
                    customerName?.takeIf { it.isNotBlank() }
                        ?: JSONObject.NULL
                )
                .put(
                    "p_notes",
                    notes?.takeIf { it.isNotBlank() }
                        ?: JSONObject.NULL
                )

            if (!manualDiscountType.isNullOrBlank() && manualDiscountValue > 0) {
                payload.put(
                    "p_manual_discount",
                    JSONObject()
                        .put("type", manualDiscountType)
                        .put("value", manualDiscountValue)
                        .put("label", manualDiscountLabel)
                )
            } else {
                payload.put("p_manual_discount", JSONObject.NULL)
            }

            val o = JSONObject(rpc("dailydash_create_order_v3", payload))
            CloudOrderResult(
                remoteId = o.getString("id"),
                orderNo = o.getString("order_no"),
                total = o.getInt("total"),
                cashReceived = o.optInt("cash_received", 0),
                changeAmount = o.optInt("change_amount", 0),
                tender = o.getString("tender"),
                createdAt = o.optString("created_at"),
                queueNo = o.optInt("queue_no", 0),
                grossTotal = o.optInt("gross_total", o.getInt("total")),
                discountTotal = o.optInt("discount_total", 0)
            )
        }
    }

    suspend fun setPrepStatus(
        staffToken: String,
        orderId: String,
        status: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rpc(
                "dailydash_set_prep_status",
                JSONObject()
                    .put("p_staff_session", staffToken)
                    .put("p_order_id", orderId)
                    .put("p_status", status)
            )
            Unit
        }
    }

    private fun cartJson(cart: List<CartLine>): JSONArray {
        val items = JSONArray()
        cart.forEach { line ->
            items.put(
                JSONObject()
                    .put("product_id", line.product.id)
                    .put("quantity", line.quantity)
                    .put("upsized", line.upsized)
            )
        }
        return items
    }

    private fun JSONObject.toShift() = ShiftInfo(
        id = getString("id"),
        openingCash = optInt("opening_cash", 0),
        closingCash = if (isNull("closing_cash")) null else optInt("closing_cash"),
        expectedCash = if (isNull("expected_cash")) null else optInt("expected_cash"),
        variance = if (isNull("variance")) null else optInt("variance"),
        status = optString("status", "open"),
        openedAt = optString("opened_at"),
        closedAt = optString("closed_at").takeIf {
            it.isNotBlank() && it != "null"
        }
    )

    private fun JSONObject.toStaff() = StaffMember(
        id = getString("id"),
        staffCode = optString("staff_code"),
        displayName = optString("display_name"),
        role = optString("role"),
        avatarUrl = optString("avatar_url")
            .takeIf { it.isNotBlank() && it != "null" }
    )

    private fun postJson(
        url: String,
        payload: JSONObject
    ): JSONObject {
        val c = open(url, "POST")
        c.doOutput = true
        c.outputStream.use {
            it.write(payload.toString().toByteArray())
        }
        val body = readResponse(c)
        if (c.responseCode !in 200..299) {
            error(
                runCatching {
                    JSONObject(body).optString("error")
                }.getOrDefault(body)
            )
        }
        return JSONObject(body)
    }

    private fun rpc(
        function: String,
        payload: JSONObject
    ): String {
        val c = open(
            "$SUPABASE_URL/rest/v1/rpc/$function",
            "POST"
        )
        c.doOutput = true
        c.outputStream.use {
            it.write(payload.toString().toByteArray())
        }
        val body = readResponse(c)
        if (c.responseCode !in 200..299) {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            error(
                parsed?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error")
                    ?.takeIf { it.isNotBlank() }
                    ?: body
            )
        }
        return body
    }

    private fun open(
        url: String,
        method: String
    ) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 10000
        readTimeout = 20000
        setRequestProperty("apikey", PUBLISHABLE_KEY)
        setRequestProperty(
            "Authorization",
            "Bearer $PUBLISHABLE_KEY"
        )
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }

    private fun readResponse(
        c: HttpURLConnection
    ): String {
        val stream =
            if (c.responseCode in 200..299) c.inputStream
            else c.errorStream

        return if (stream == null) ""
        else BufferedReader(
            InputStreamReader(stream)
        ).use { it.readText() }
    }
}
