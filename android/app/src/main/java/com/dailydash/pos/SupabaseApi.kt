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
            put("action", "staff_login"); put("method", "pin"); put("staff_id", staffId)
            put("secret", pin); put("device_code", DEVICE_CODE)
        })

    suspend fun staffPasswordLogin(username: String, password: String): Result<StaffSession> =
        staffLogin(JSONObject().apply {
            put("action", "staff_login"); put("method", "password"); put("username", username)
            put("secret", password); put("device_code", DEVICE_CODE)
        })

    private suspend fun staffLogin(payload: JSONObject): Result<StaffSession> = withContext(Dispatchers.IO) {
        runCatching {
            val data = postJson(ADMIN_URL, payload)
            StaffSession(data.getString("session_token"), data.getJSONObject("staff").toStaff())
        }
    }

    suspend fun staffLogout(token: String) = withContext(Dispatchers.IO) {
        runCatching { postJson(ADMIN_URL, JSONObject().put("action","staff_logout").put("session_token",token)) }
    }

    suspend fun loadProducts(): Result<List<Product>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open("$SUPABASE_URL/rest/v1/rpc/dailydash_get_pos_products", "POST")
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray()) }
            val body = readResponse(connection)
            if (connection.responseCode !in 200..299) error("Cloud menu error: $body")
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                Product(
                    id=p.getString("id"), name=p.getString("name"), category=p.getString("category"),
                    price=p.getInt("price"), allowUpsize=p.optBoolean("allow_upsize"),
                    upsizePrice=p.optInt("upsize_price",10), available=p.optBoolean("available",true),
                    sortOrder=p.optInt("sort_order"), imageUrl=p.optString("image_url").takeIf { it.isNotBlank() && it!="null" }
                )
            }
        }
    }

    suspend fun createOrder(cart: List<CartLine>, tender: String, cashReceived: Int, staffToken: String): Result<CloudOrderResult> = withContext(Dispatchers.IO) {
        runCatching {
            val items=JSONArray()
            cart.forEach { line -> items.put(JSONObject().put("product_id",line.product.id).put("quantity",line.quantity).put("upsized",line.upsized)) }
            val payload=JSONObject().put("p_items",items).put("p_tender",tender)
                .put("p_cash_received",cashReceived).put("p_device_code",DEVICE_CODE).put("p_staff_session",staffToken)
            val c=open("$SUPABASE_URL/rest/v1/rpc/dailydash_create_order_v2","POST")
            c.doOutput=true; c.outputStream.use { it.write(payload.toString().toByteArray()) }
            val body=readResponse(c)
            if(c.responseCode !in 200..299) error(runCatching { JSONObject(body).optString("message") }.getOrDefault(body))
            val o=JSONObject(body)
            CloudOrderResult(o.getString("id"),o.getString("order_no"),o.getInt("total"),o.getInt("cash_received"),o.getInt("change_amount"),o.getString("tender"),o.optString("created_at"))
        }
    }

    private fun JSONObject.toStaff() = StaffMember(
        getString("id"), optString("staff_code"), optString("display_name"), optString("role"), optString("avatar_url").takeIf { it.isNotBlank() && it!="null" }
    )

    private fun postJson(url:String,payload:JSONObject):JSONObject{
        val c=open(url,"POST"); c.doOutput=true; c.outputStream.use { it.write(payload.toString().toByteArray()) }
        val body=readResponse(c)
        if(c.responseCode !in 200..299) error(runCatching { JSONObject(body).optString("error") }.getOrDefault(body))
        return JSONObject(body)
    }

    private fun open(url:String,method:String)=(URL(url).openConnection() as HttpURLConnection).apply{
        requestMethod=method; connectTimeout=10000; readTimeout=15000
        setRequestProperty("apikey",PUBLISHABLE_KEY); setRequestProperty("Authorization","Bearer $PUBLISHABLE_KEY")
        setRequestProperty("Content-Type","application/json"); setRequestProperty("Accept","application/json")
    }
    private fun readResponse(c:HttpURLConnection):String{
        val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
        return if(stream==null)"" else BufferedReader(InputStreamReader(stream)).use{it.readText()}
    }
}
