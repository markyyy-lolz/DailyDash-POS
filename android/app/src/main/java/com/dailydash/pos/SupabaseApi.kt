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
        const val DEVICE_NAME = "DailyDash Main POS"
        const val APP_VERSION = "2.1.0"
        const val APP_VERSION_CODE = 7
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
            put("device_name", DEVICE_NAME)
            put("app_version", APP_VERSION)
        })

    suspend fun staffPasswordLogin(username: String, password: String): Result<StaffSession> =
        staffLogin(JSONObject().apply {
            put("action", "staff_login")
            put("method", "password")
            put("username", username)
            put("secret", password)
            put("device_code", DEVICE_CODE)
            put("device_name", DEVICE_NAME)
            put("app_version", APP_VERSION)
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

    suspend fun deviceHeartbeat(staffToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                rpc(
                    "dailydash_device_heartbeat",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                        .put("p_display_name", DEVICE_NAME)
                        .put("p_app_version", APP_VERSION)
                )
                Unit
            }
        }

    suspend fun latestAppRelease(): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val data = postJson(ADMIN_URL, JSONObject().put("action", "app_update"))
            val o = data.optJSONObject("release") ?: return@runCatching null
            if (!o.has("version_code")) return@runCatching null
            AppReleaseInfo(
                versionCode = o.optInt("version_code", 0),
                versionName = o.optString("version_name"),
                changelog = o.optString("changelog"),
                updateUrl = o.optString("update_url").takeIf {
                    it.isNotBlank() && it != "null"
                },
                required = o.optBoolean("required", false),
                publishedAt = o.optString("published_at")
            )
        }
    }

    suspend fun loadReceiptBranding(): Result<ReceiptBranding> = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(rpc("dailydash_pos_receipt_settings", JSONObject()))
            ReceiptBranding(
                storeName = o.optString("store_name", "DailyDash"),
                branchName = o.optString("branch_name", "DailyDash - Paombong"),
                address = o.optString("receipt_address", "Paombong, Bulacan"),
                phone = o.optString("receipt_phone", ""),
                footer = o.optString("receipt_footer", "Thank you for choosing DailyDash!")
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

    suspend fun loadModifiers(): Result<List<ModifierOption>> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONArray(rpc("dailydash_get_pos_modifiers", JSONObject()))
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ModifierOption(
                    id = m.getString("id"),
                    productId = m.getString("product_id"),
                    groupName = m.getString("group_name"),
                    groupType = m.optString("group_type", "multi"),
                    name = m.getString("name"),
                    priceDelta = m.optInt("price_delta", 0),
                    isDefault = m.optBoolean("is_default", false),
                    required = m.optBoolean("required", false),
                    maxSelect = m.optInt("max_select", 1),
                    sortOrder = m.optInt("sort_order", 0)
                )
            }
        }
    }

    suspend fun lookupCustomer(
        staffToken: String,
        phone: String
    ): Result<CustomerLoyalty?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = rpc(
                "dailydash_customer_lookup",
                JSONObject()
                    .put("p_staff_session", staffToken)
                    .put("p_phone", phone)
            )
            if (body.isBlank() || body.trim() == "null") return@runCatching null
            val o = JSONObject(body)
            CustomerLoyalty(
                id = o.getString("id"),
                name = o.optString("name"),
                phone = o.optString("phone"),
                pointsBalance = o.optInt("points_balance", 0),
                lifetimePoints = o.optInt("lifetime_points", 0),
                lifetimeSpend = o.optInt("lifetime_spend", 0),
                tier = o.optString("tier", "Member"),
                birthday = o.optString("birthday").takeIf {
                    it.isNotBlank() && it != "null"
                }
            )
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
            val data = JSONObject(
                rpc(
                    "dailydash_hold_order",
                    JSONObject()
                        .put("p_staff_session", staffToken)
                        .put("p_device_code", DEVICE_CODE)
                        .put("p_cart", cartJson(cart))
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
                val products = loadProducts().getOrElse { emptyList() }.associateBy { it.id }
                val modifiers = loadModifiers().getOrElse { emptyList() }.associateBy { it.id }
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
                        val selected = mutableListOf<SelectedModifier>()
                        val modArray = item.optJSONArray("modifiers") ?: JSONArray()
                        for (mIndex in 0 until modArray.length()) {
                            val m = modArray.getJSONObject(mIndex)
                            val option = modifiers[m.optString("id")] ?: continue
                            selected += SelectedModifier(
                                option = option,
                                quantity = m.optInt("quantity", 1).coerceIn(1, 10)
                            )
                        }
                        CartLine(
                            product = product,
                            quantity = item.optInt("quantity", 1).coerceAtLeast(1),
                            upsized = item.optBoolean("upsized", false),
                            modifiers = selected
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

    suspend fun previewDiscount(
        staffToken: String,
        subtotal: Int,
        discountCode: String? = null,
        manualDiscountType: String? = null,
        manualDiscountValue: Int = 0,
        manualDiscountLabel: String = "Manual Discount",
        managerPin: String? = null
    ): Result<DiscountPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("p_staff_session", staffToken)
                .put("p_subtotal", subtotal)
                .put(
                    "p_discount_code",
                    discountCode?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_manager_pin",
                    managerPin?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
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

            val o = JSONObject(rpc("dailydash_preview_discount", payload))
            DiscountPreview(
                label = o.optString("label").takeIf { it.isNotBlank() && it != "null" },
                discountTotal = o.optInt("discount_total", 0),
                total = o.optInt("total", subtotal)
            )
        }
    }

    suspend fun createOrder(
        cart: List<CartLine>,
        tender: String,
        cashReceived: Int,
        staffToken: String
    ): Result<CloudOrderResult> = createOrderAdvanced(
        cart = cart,
        payments = listOf(PaymentPart(tender, cashReceived)),
        staffToken = staffToken
    )

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
        customerPhone: String? = null,
        redeemPoints: Int = 0,
        orderType: String = "Takeout",
        tableNo: String? = null,
        notes: String? = null,
        clientRef: String? = null
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
                    "p_client_ref",
                    clientRef?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_discount_code",
                    discountCode?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_manager_pin",
                    managerPin?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_customer_name",
                    customerName?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_customer_phone",
                    customerPhone?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put("p_redeem_points", redeemPoints.coerceAtLeast(0))
                .put("p_order_type", orderType)
                .put(
                    "p_table_no",
                    tableNo?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
                )
                .put(
                    "p_notes",
                    notes?.takeIf { it.isNotBlank() } ?: JSONObject.NULL
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

            val o = JSONObject(rpc("dailydash_create_order_v5", payload))
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
                discountTotal = o.optInt("discount_total", 0),
                orderType = o.optString("order_type", orderType),
                tableNo = o.optString("table_no").takeIf {
                    it.isNotBlank() && it != "null"
                },
                loyaltyRedeemed = o.optInt("loyalty_redeemed", 0),
                loyaltyEarned = o.optInt("loyalty_earned", 0),
                loyaltyBalance = o.optInt("loyalty_balance", 0)
            )
        }
    }

    suspend fun syncPendingSale(
        sale: PendingSale,
        staffToken: String
    ): Result<CloudOrderResult> = createOrderAdvanced(
        cart = sale.lines,
        payments = sale.payments,
        staffToken = staffToken,
        discountCode = sale.discountCode,
        customerName = sale.customerName,
        customerPhone = sale.customerPhone,
        redeemPoints = sale.redeemPoints,
        orderType = sale.orderType,
        tableNo = sale.tableNo,
        notes = sale.notes,
        clientRef = sale.localId
    )

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
            val modifiers = JSONArray()
            line.modifiers.forEach { selected ->
                modifiers.put(
                    JSONObject()
                        .put("id", selected.option.id)
                        .put("quantity", selected.quantity)
                )
            }
            items.put(
                JSONObject()
                    .put("product_id", line.product.id)
                    .put("quantity", line.quantity)
                    .put("upsized", line.upsized)
                    .put("modifiers", modifiers)
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
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            error(
                parsed?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: body
            )
        }
        return JSONObject(body)
    }

    private fun rpc(
        function: String,
        payload: JSONObject
    ): String {
        val c = open("$SUPABASE_URL/rest/v1/rpc/$function", "POST")
        c.doOutput = true
        c.outputStream.use {
            it.write(payload.toString().toByteArray())
        }
        val body = readResponse(c)
        if (c.responseCode !in 200..299) {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            error(
                parsed?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: parsed?.optString("error")?.takeIf { it.isNotBlank() }
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
        setRequestProperty("Authorization", "Bearer $PUBLISHABLE_KEY")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
    }

    private fun readResponse(c: HttpURLConnection): String {
        val stream =
            if (c.responseCode in 200..299) c.inputStream
            else c.errorStream

        return if (stream == null) ""
        else BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }
}
