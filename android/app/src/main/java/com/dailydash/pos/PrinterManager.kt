package com.dailydash.pos

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

data class PrinterSettings(
    val bluetoothAddress: String = "",
    val paperWidth: Int = 58,
    val autoPrintReceipt: Boolean = true,
    val autoPrintOrderSlip: Boolean = true
)

data class PrinterDevice(
    val id: String,
    val name: String,
    val subtitle: String
)

class PrinterManager(private val context: Context) {
    companion object {
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val prefs =
        context.getSharedPreferences("dailydash_printer", Context.MODE_PRIVATE)

    fun loadSettings(): PrinterSettings = PrinterSettings(
        bluetoothAddress = prefs.getString("bt_address", "") ?: "",
        paperWidth = prefs.getInt("paper_width", 58),
        autoPrintReceipt = prefs.getBoolean("auto_receipt", true),
        autoPrintOrderSlip = prefs.getBoolean("auto_slip", true)
    )

    fun saveSettings(value: PrinterSettings) {
        prefs.edit()
            .putString("bt_address", value.bluetoothAddress.trim())
            .putInt("paper_width", 58)
            .putBoolean("auto_receipt", value.autoPrintReceipt)
            .putBoolean("auto_slip", value.autoPrintOrderSlip)
            .apply()
    }

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    fun pairedBluetoothPrinters(): List<PrinterDevice> {
        if (!hasBluetoothPermission()) return emptyList()

        return try {
            val manager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: return emptyList()

            adapter.bondedDevices
                .sortedBy { it.name ?: it.address }
                .map {
                    PrinterDevice(
                        id = it.address,
                        name = it.name ?: "Bluetooth printer",
                        subtitle = it.address
                    )
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun configuredPrinterName(): String? {
        val selected = loadSettings().bluetoothAddress
        if (selected.isBlank() || !hasBluetoothPermission()) return null
        return pairedBluetoothPrinters().firstOrNull { it.id == selected }?.name
    }

    suspend fun testPrint(
        settings: PrinterSettings = loadSettings()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ByteArrayOutputStream()
            reset(out)
            center(out)
            bold(out, true)
            doubleHeight(out, true)
            text(out, "DAILY DASH\n")
            doubleHeight(out, false)
            bold(out, false)
            text(out, "VOZY P50 - 58MM TEST\n")
            text(out, "Bluetooth ESC/POS\n")
            text(out, "Printer connection OK\n")
            feed(out, 5)
            sendBluetooth(settings, out.toByteArray())
        }
    }

    suspend fun printReceipt(
        sale: CompletedSale,
        settings: PrinterSettings = loadSettings()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { sendBluetooth(settings, buildReceipt(sale, settings)) }
    }

    suspend fun printOrderSlip(
        sale: CompletedSale,
        settings: PrinterSettings = loadSettings()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { sendBluetooth(settings, buildOrderSlip(sale, settings)) }
    }

    suspend fun printAuto(sale: CompletedSale): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = loadSettings()

                if (settings.bluetoothAddress.isBlank()) {
                    error("No Bluetooth printer selected.")
                }

                if (settings.autoPrintReceipt) {
                    sendBluetooth(settings, buildReceipt(sale, settings))
                }
                if (settings.autoPrintOrderSlip) {
                    sendBluetooth(settings, buildOrderSlip(sale, settings))
                }
            }
        }

    private fun buildReceipt(
        sale: CompletedSale,
        settings: PrinterSettings
    ): ByteArray {
        val width = 32
        val out = ByteArrayOutputStream()

        reset(out)
        center(out)
        bold(out, true)
        doubleHeight(out, true)
        text(out, "DAILY DASH\n")
        doubleHeight(out, false)
        bold(out, false)
        text(out, "OFFICIAL POS RECEIPT\n")
        text(out, sale.result.orderNo + "\n")
        if (sale.result.createdAt.isNotBlank()) {
            text(
                out,
                sale.result.createdAt
                    .replace("T", " ")
                    .take(19) + "\n"
            )
        }

        left(out)
        text(out, divider(width) + "\n")
        text(out, fitPair("Cashier", sale.staff.displayName, width) + "\n")
        text(out, fitPair("Payment", sale.result.tender, width) + "\n")
        text(out, divider(width) + "\n")

        sale.lines.forEach { line ->
            val label =
                line.quantity.toString() + "x " + line.product.name +
                    if (line.upsized) " (UPSIZE)" else ""

            wrap(label, width).forEach {
                text(out, it + "\n")
            }

            text(
                out,
                fitPair(
                    "  PHP " + line.unitPrice + " ea",
                    "PHP " + line.lineTotal,
                    width
                ) + "\n"
            )
        }

        text(out, divider(width) + "\n")
        bold(out, true)
        text(
            out,
            fitPair("TOTAL", "PHP " + sale.result.total, width) + "\n"
        )
        bold(out, false)

        if (sale.result.tender == "Cash") {
            text(
                out,
                fitPair(
                    "Cash received",
                    "PHP " + sale.result.cashReceived,
                    width
                ) + "\n"
            )
            text(
                out,
                fitPair(
                    "Change",
                    "PHP " + sale.result.changeAmount,
                    width
                ) + "\n"
            )
        }

        text(out, divider(width) + "\n")
        center(out)
        text(out, "Thank you for choosing\n")
        bold(out, true)
        text(out, "DailyDash!\n")
        bold(out, false)
        feed(out, 5)

        return out.toByteArray()
    }

    private fun buildOrderSlip(
        sale: CompletedSale,
        settings: PrinterSettings
    ): ByteArray {
        val width = 32
        val out = ByteArrayOutputStream()

        reset(out)
        center(out)
        bold(out, true)
        doubleHeight(out, true)
        text(out, "ORDER SLIP\n")
        doubleHeight(out, false)
        text(out, sale.result.orderNo + "\n")
        bold(out, false)
        text(out, "PAID - " + sale.result.tender + "\n")

        left(out)
        text(out, "Cashier: " + sale.staff.displayName + "\n")
        text(out, divider(width) + "\n")

        sale.lines.forEach { line ->
            bold(out, true)
            wrap(
                line.quantity.toString() + "x " + line.product.name,
                width
            ).forEach {
                text(out, it + "\n")
            }
            bold(out, false)

            if (line.upsized) {
                text(out, "   *** UPSIZED ***\n")
            }
            text(out, "   " + line.product.category + "\n")
            text(out, "\n")
        }

        text(out, divider(width) + "\n")
        bold(out, true)
        text(
            out,
            "TOTAL ITEMS: " +
                sale.lines.sumOf { it.quantity } +
                "\n"
        )
        bold(out, false)
        feed(out, 6)

        return out.toByteArray()
    }

    private fun sendBluetooth(
        settings: PrinterSettings,
        bytes: ByteArray
    ) {
        if (!hasBluetoothPermission()) {
            error("Bluetooth permission is required.")
        }

        val address = settings.bluetoothAddress.trim()
        if (address.isBlank()) {
            error("Select your VOZY P50 in Settings first.")
        }

        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
            ?: error("Bluetooth is not available on this device.")

        if (!adapter.isEnabled) {
            error("Turn Bluetooth on before printing.")
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Throwable) {
            error("Saved Bluetooth printer is invalid. Select it again in Settings.")
        }

        var firstError: Throwable? = null

        try {
            device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                socket.connect()
                socket.outputStream.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
            }
            return
        } catch (error: Throwable) {
            firstError = error
        }

        try {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                socket.connect()
                socket.outputStream.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
            }
            return
        } catch (error: Throwable) {
            throw IOException(
                "Could not connect to the Bluetooth printer. Make sure the VOZY P50 is powered on and paired.",
                error.takeIf { it.message != null } ?: firstError
            )
        }
    }

    private fun reset(out: ByteArrayOutputStream) =
        out.write(byteArrayOf(0x1B, 0x40))

    private fun left(out: ByteArrayOutputStream) =
        out.write(byteArrayOf(0x1B, 0x61, 0x00))

    private fun center(out: ByteArrayOutputStream) =
        out.write(byteArrayOf(0x1B, 0x61, 0x01))

    private fun bold(out: ByteArrayOutputStream, enabled: Boolean) =
        out.write(
            byteArrayOf(
                0x1B,
                0x45,
                (if (enabled) 0x01 else 0x00).toByte()
            )
        )

    private fun doubleHeight(
        out: ByteArrayOutputStream,
        enabled: Boolean
    ) =
        out.write(
            byteArrayOf(
                0x1D,
                0x21,
                (if (enabled) 0x11 else 0x00).toByte()
            )
        )

    private fun feed(
        out: ByteArrayOutputStream,
        lines: Int
    ) = repeat(lines) { text(out, "\n") }

    private fun text(
        out: ByteArrayOutputStream,
        value: String
    ) = out.write(value.toByteArray(Charsets.US_ASCII))

    private fun divider(width: Int) = "-".repeat(width)

    private fun fitPair(
        left: String,
        right: String,
        width: Int
    ): String {
        if (left.length + right.length + 1 <= width) {
            return left +
                " ".repeat(width - left.length - right.length) +
                right
        }

        return (
            left.take((width - right.length - 1).coerceAtLeast(1)) +
                " " +
                right
            ).take(width)
    }

    private fun wrap(
        value: String,
        width: Int
    ): List<String> {
        if (value.length <= width) return listOf(value)

        val words = value.split(" ")
        val lines = mutableListOf<String>()
        var current = ""

        words.forEach { word ->
            val next =
                if (current.isEmpty()) word
                else current + " " + word

            if (next.length > width && current.isNotEmpty()) {
                lines += current
                current = word
            } else {
                current = next
            }
        }

        if (current.isNotEmpty()) {
            lines += current
        }

        return lines
    }
}
