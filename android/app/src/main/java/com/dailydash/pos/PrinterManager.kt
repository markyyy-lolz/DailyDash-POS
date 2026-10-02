package com.dailydash.pos

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

data class PrinterSettings(
    val connectionType: String = "NETWORK",
    val bluetoothAddress: String = "",
    val networkHost: String = "",
    val networkPort: Int = 9100,
    val usbDeviceId: Int = -1,
    val paperWidth: Int = 80,
    val autoPrintReceipt: Boolean = true,
    val autoPrintOrderSlip: Boolean = true,
    val autoCut: Boolean = true,
    val openCashDrawer: Boolean = false
)

data class PrinterDevice(val id: String, val name: String, val subtitle: String)

class PrinterManager(private val context: Context) {
    companion object {
        const val USB_PERMISSION_ACTION = "com.dailydash.pos.USB_PERMISSION"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val prefs = context.getSharedPreferences("dailydash_printer", Context.MODE_PRIVATE)
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun loadSettings(): PrinterSettings = PrinterSettings(
        connectionType = prefs.getString("type", "NETWORK") ?: "NETWORK",
        bluetoothAddress = prefs.getString("bt_address", "") ?: "",
        networkHost = prefs.getString("net_host", "") ?: "",
        networkPort = prefs.getInt("net_port", 9100),
        usbDeviceId = prefs.getInt("usb_id", -1),
        paperWidth = prefs.getInt("paper_width", 80),
        autoPrintReceipt = prefs.getBoolean("auto_receipt", true),
        autoPrintOrderSlip = prefs.getBoolean("auto_slip", true),
        autoCut = prefs.getBoolean("auto_cut", true),
        openCashDrawer = prefs.getBoolean("drawer", false)
    )

    fun saveSettings(value: PrinterSettings) {
        prefs.edit()
            .putString("type", value.connectionType)
            .putString("bt_address", value.bluetoothAddress.trim())
            .putString("net_host", value.networkHost.trim())
            .putInt("net_port", value.networkPort.coerceIn(1, 65535))
            .putInt("usb_id", value.usbDeviceId)
            .putInt("paper_width", if (value.paperWidth == 58) 58 else 80)
            .putBoolean("auto_receipt", value.autoPrintReceipt)
            .putBoolean("auto_slip", value.autoPrintOrderSlip)
            .putBoolean("auto_cut", value.autoCut)
            .putBoolean("drawer", value.openCashDrawer)
            .apply()
    }

    fun pairedBluetoothPrinters(): List<PrinterDevice> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return emptyList()

            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: return emptyList()
            adapter.bondedDevices.sortedBy { it.name ?: it.address }.map {
                PrinterDevice(it.address, it.name ?: "Bluetooth printer", it.address)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun usbPrinters(): List<PrinterDevice> =
        usbManager.deviceList.values.sortedBy { it.productName ?: it.deviceName }.map { device ->
            PrinterDevice(
                device.deviceId.toString(),
                device.productName ?: device.manufacturerName ?: "USB Printer",
                "VID " + device.vendorId + " • PID " + device.productId
            )
        }

    fun hasUsbPermission(deviceId: Int): Boolean =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }?.let(usbManager::hasPermission) == true

    fun requestUsbPermission(activity: Activity, deviceId: Int): Result<Unit> = runCatching {
        val device = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
            ?: error("USB printer is not connected.")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            0,
            Intent(USB_PERMISSION_ACTION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    suspend fun testPrint(settings: PrinterSettings): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ByteArrayOutputStream()
            init(out)
            center(out)
            bold(out, true)
            text(out, "DAILY DASH\n")
            bold(out, false)
            text(out, "Printer Test\n")
            text(out, "Connection: " + settings.connectionType + "\n")
            text(out, "Paper: " + settings.paperWidth + "mm\n")
            text(out, "Status: OK\n")
            feed(out, 4)
            if (settings.autoCut) cut(out)
            send(settings, out.toByteArray())
        }
    }

    suspend fun printReceipt(sale: CompletedSale, settings: PrinterSettings = loadSettings()): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching { send(settings, buildReceipt(sale, settings)) } }

    suspend fun printOrderSlip(sale: CompletedSale, settings: PrinterSettings = loadSettings()): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching { send(settings, buildOrderSlip(sale, settings)) } }

    suspend fun printAuto(sale: CompletedSale): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = loadSettings()
            if (!settings.autoPrintReceipt && !settings.autoPrintOrderSlip) return@runCatching
            if (settings.openCashDrawer && sale.result.tender == "Cash") {
                send(settings, byteArrayOf(0x1B, 0x70, 0x00, 0x3C, 0x7F))
            }
            if (settings.autoPrintReceipt) send(settings, buildReceipt(sale, settings))
            if (settings.autoPrintOrderSlip) send(settings, buildOrderSlip(sale, settings))
        }
    }

    private fun buildReceipt(sale: CompletedSale, settings: PrinterSettings): ByteArray {
        val width = if (settings.paperWidth == 58) 32 else 48
        val out = ByteArrayOutputStream()
        init(out); center(out); bold(out, true); doubleHeight(out, true)
        text(out, "DAILY DASH\n")
        doubleHeight(out, false); bold(out, false)
        text(out, "OFFICIAL POS RECEIPT\n")
        text(out, sale.result.orderNo + "\n")
        if (sale.result.createdAt.isNotBlank()) {
            text(out, sale.result.createdAt.replace("T", " ").take(19) + "\n")
        }
        left(out)
        text(out, divider(width) + "\n")
        text(out, fitPair("Cashier", sale.staff.displayName, width) + "\n")
        text(out, fitPair("Payment", sale.result.tender, width) + "\n")
        text(out, divider(width) + "\n")
        sale.lines.forEach { line ->
            val label = line.quantity.toString() + "x " + line.product.name + if (line.upsized) " (UPSIZE)" else ""
            wrap(label, width).forEach { text(out, it + "\n") }
            text(out, fitPair("  PHP " + line.unitPrice + " ea", "PHP " + line.lineTotal, width) + "\n")
        }
        text(out, divider(width) + "\n")
        bold(out, true)
        text(out, fitPair("TOTAL", "PHP " + sale.result.total, width) + "\n")
        bold(out, false)
        text(out, fitPair("Cash received", "PHP " + sale.result.cashReceived, width) + "\n")
        text(out, fitPair("Change", "PHP " + sale.result.changeAmount, width) + "\n")
        text(out, divider(width) + "\n")
        center(out)
        text(out, "Thank you for choosing DailyDash!\n")
        feed(out, 4)
        if (settings.autoCut) cut(out)
        return out.toByteArray()
    }

    private fun buildOrderSlip(sale: CompletedSale, settings: PrinterSettings): ByteArray {
        val width = if (settings.paperWidth == 58) 32 else 48
        val out = ByteArrayOutputStream()
        init(out); center(out); bold(out, true); doubleHeight(out, true)
        text(out, "ORDER SLIP\n")
        doubleHeight(out, false)
        text(out, sale.result.orderNo + "\n")
        bold(out, false)
        text(out, "PAID • " + sale.result.tender + "\n")
        left(out)
        text(out, "Cashier: " + sale.staff.displayName + "\n")
        text(out, divider(width) + "\n")
        sale.lines.forEach { line ->
            bold(out, true)
            text(out, line.quantity.toString() + "x " + line.product.name + "\n")
            bold(out, false)
            if (line.upsized) text(out, "   *** UPSIZED ***\n")
            text(out, "   " + line.product.category + "\n")
        }
        text(out, divider(width) + "\n")
        bold(out, true)
        text(out, "TOTAL ITEMS: " + sale.lines.sumOf { it.quantity } + "\n")
        bold(out, false)
        feed(out, 5)
        if (settings.autoCut) cut(out)
        return out.toByteArray()
    }

    private fun send(settings: PrinterSettings, bytes: ByteArray) {
        when (settings.connectionType.uppercase()) {
            "BLUETOOTH" -> sendBluetooth(settings.bluetoothAddress, bytes)
            "USB" -> sendUsb(settings.usbDeviceId, bytes)
            else -> sendNetwork(settings.networkHost, settings.networkPort, bytes)
        }
    }

    private fun sendNetwork(host: String, port: Int, bytes: ByteArray) {
        if (host.isBlank()) error("Set the printer IP address in Printer Settings.")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), 5000)
            socket.soTimeout = 5000
            socket.getOutputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    private fun sendBluetooth(address: String, bytes: ByteArray) {
        if (address.isBlank()) error("Select a paired Bluetooth printer in Printer Settings.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) error("Bluetooth permission is required.")

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = manager.adapter ?: error("Bluetooth is not available.")
        val device = adapter.getRemoteDevice(address.trim())
        adapter.cancelDiscovery()
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            socket.outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    private fun sendUsb(deviceId: Int, bytes: ByteArray) {
        val device: UsbDevice = usbManager.deviceList.values.firstOrNull {
            if (deviceId >= 0) it.deviceId == deviceId else true
        } ?: error("No USB printer is connected.")

        if (!usbManager.hasPermission(device)) {
            error("USB permission is required. Open Printer Settings and tap Grant USB Permission.")
        }

        var targetInterface: android.hardware.usb.UsbInterface? = null
        var outEndpoint: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT
                ) {
                    targetInterface = intf
                    outEndpoint = endpoint
                    break
                }
            }
            if (outEndpoint != null) break
        }

        val intf = targetInterface ?: error("No compatible USB printer interface found.")
        val endpoint = outEndpoint ?: error("No compatible USB printer output endpoint found.")
        val connection = usbManager.openDevice(device) ?: error("Unable to open USB printer.")
        connection.use {
            if (!it.claimInterface(intf, true)) error("Unable to claim USB printer.")
            val written = it.bulkTransfer(endpoint, bytes, bytes.size, 8000)
            it.releaseInterface(intf)
            if (written < 0) error("USB print failed.")
        }
    }

    private fun init(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x40))
    private fun left(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x61, 0x00))
    private fun center(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1B, 0x61, 0x01))
    private fun bold(out: ByteArrayOutputStream, enabled: Boolean) =
        out.write(byteArrayOf(0x1B, 0x45, if (enabled) 0x01 else 0x00))
    private fun doubleHeight(out: ByteArrayOutputStream, enabled: Boolean) =
        out.write(byteArrayOf(0x1D, 0x21, if (enabled) 0x11 else 0x00))
    private fun cut(out: ByteArrayOutputStream) = out.write(byteArrayOf(0x1D, 0x56, 0x00))
    private fun feed(out: ByteArrayOutputStream, lines: Int) = repeat(lines) { text(out, "\n") }
    private fun text(out: ByteArrayOutputStream, value: String) = out.write(value.toByteArray(Charsets.US_ASCII))

    private fun divider(width: Int) = "-".repeat(width)

    private fun fitPair(left: String, right: String, width: Int): String {
        if (left.length + right.length + 1 <= width) {
            return left + " ".repeat(width - left.length - right.length) + right
        }
        return (left.take((width - right.length - 1).coerceAtLeast(1)) + " " + right).take(width)
    }

    private fun wrap(text: String, width: Int): List<String> {
        if (text.length <= width) return listOf(text)
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val next = if (current.isEmpty()) word else current + " " + word
            if (next.length > width && current.isNotEmpty()) {
                lines += current
                current = word
            } else current = next
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
