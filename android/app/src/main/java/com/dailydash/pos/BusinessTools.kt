package com.dailydash.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val ToolBlue = Color(0xFF0866D7)
private fun toolPeso(value: Int): String =
    NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(value)

@Composable
fun PosToolsHubDialog(
    onPrinter: () -> Unit,
    onShift: () -> Unit,
    onHeldOrders: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text("POS Settings & Tools", fontWeight = FontWeight.Black, fontSize = 23.sp)
                Text("DailyDash operations center", color = Color(0xFF718096), fontSize = 12.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolCard(
                    icon = Icons.Default.Print,
                    title = "Receipt Printer",
                    subtitle = "VOZY P50 Bluetooth, auto receipt and order slip",
                    onClick = onPrinter
                )
                ToolCard(
                    icon = Icons.Default.PointOfSale,
                    title = "Cashier Shift",
                    subtitle = "Opening cash, closing cash and variance",
                    onClick = onShift
                )
                ToolCard(
                    icon = Icons.Default.PauseCircle,
                    title = "Held Orders",
                    subtitle = "Resume parked customer orders",
                    onClick = onHeldOrders
                )
                Surface(
                    color = Color(0xFFF4F8FE),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Cloud features enabled", fontWeight = FontWeight.Bold, color = Color(0xFF243B64))
                        Text(
                            "Discounts, split payments, payment references, queue numbers, ingredient inventory, refunds, expenses, audit logs and kitchen workflow are synchronized with Supabase.",
                            color = Color(0xFF718096),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun ToolCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFFFAFBFD),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5EAF1))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(Color(0xFFEAF3FF), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = ToolBlue)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color(0xFF233A5D))
                Text(subtitle, color = Color(0xFF718096), fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF97A3B4))
        }
    }
}

@Composable
fun ShiftDialog(
    api: SupabaseApi,
    session: StaffSession,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var shift by remember { mutableStateOf<ShiftInfo?>(null) }
    var amount by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            api.currentShift(session.token)
                .onSuccess { shift = it }
                .onFailure { status = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PointOfSale, null, tint = ToolBlue)
                Spacer(Modifier.width(9.dp))
                Text("Cashier Shift", fontWeight = FontWeight.Black)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (shift == null) {
                    Surface(color = Color(0xFFF4F8FE), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("No open shift", fontWeight = FontWeight.Black, color = Color(0xFF243B64))
                            Text("Enter the cash currently inside the drawer before accepting sales.", fontSize = 11.sp, color = Color(0xFF718096))
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit) },
                        label = { Text("Opening cash") },
                        prefix = { Text("₱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            loading = true
                            scope.launch {
                                api.openShift(session.token, amount.toIntOrNull() ?: 0)
                                    .onSuccess {
                                        shift = it
                                        status = "Shift opened."
                                    }
                                    .onFailure { status = it.message }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
                    ) { Text("Open Shift") }
                } else {
                    val current = shift!!
                    Surface(color = Color(0xFFE8F8EF), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("SHIFT OPEN", color = Color(0xFF128651), fontWeight = FontWeight.Black)
                            Text("Opening cash: " + toolPeso(current.openingCash), color = Color(0xFF385A49))
                            Text("Opened: " + current.openedAt.replace("T", " ").take(19), fontSize = 11.sp, color = Color(0xFF718096))
                        }
                    }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit) },
                        label = { Text("Actual closing cash") },
                        prefix = { Text("₱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            loading = true
                            scope.launch {
                                api.closeShift(session.token, current.id, amount.toIntOrNull() ?: 0)
                                    .onSuccess {
                                        shift = null
                                        status = "Shift closed. Expected " +
                                            toolPeso(it.expectedCash ?: 0) +
                                            " • Variance " + toolPeso(it.variance ?: 0)
                                    }
                                    .onFailure { status = it.message }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF102A56))
                    ) { Text("Close Shift") }
                }

                status?.let {
                    Text(it, fontSize = 11.sp, color = Color(0xFF5F6D82))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun HoldOrderDialog(
    api: SupabaseApi,
    session: StaffSession,
    cart: List<CartLine>,
    onHeld: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(26.dp),
        title = { Text("Hold Order", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    cart.sumOf { it.quantity }.toString() + " item(s) • " +
                        toolPeso(cart.sumOf { it.lineTotal }),
                    color = Color(0xFF53647B)
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label / customer name") },
                    placeholder = { Text("e.g. Table 2, Mark") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        api.holdOrder(
                            staffToken = session.token,
                            cart = cart,
                            label = label.ifBlank { "Held Order" },
                            notes = notes
                        ).onSuccess {
                            onHeld()
                        }.onFailure {
                            error = it.message
                        }
                        busy = false
                    }
                },
                enabled = cart.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
            ) { Text(if (busy) "Saving..." else "Hold Order") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
fun HeldOrdersDialog(
    api: SupabaseApi,
    session: StaffSession,
    onResume: (List<CartLine>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var orders by remember { mutableStateOf<List<HeldOrder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        loading = true
        scope.launch {
            api.heldOrders(session.token)
                .onSuccess { orders = it; error = null }
                .onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Held Orders", fontWeight = FontWeight.Black) },
        text = {
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
                orders.isEmpty() -> Text("No held orders.")
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(orders, key = { it.id }) { held ->
                        Surface(
                            color = Color(0xFFF8FAFD),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5EAF1))
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(held.label, fontWeight = FontWeight.Black, color = Color(0xFF243B64))
                                        Text(
                                            held.lines.sumOf { it.quantity }.toString() + " item(s) • " +
                                                toolPeso(held.lines.sumOf { it.lineTotal }),
                                            fontSize = 11.sp,
                                            color = Color(0xFF718096)
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            api.deleteHeldOrder(session.token, held.id)
                                                .onSuccess { refresh() }
                                                .onFailure { error = it.message }
                                        }
                                    }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                                held.notes?.let { Text(it, fontSize = 11.sp, color = Color(0xFF6E7B8F)) }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            api.deleteHeldOrder(session.token, held.id)
                                            onResume(held.lines)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
                                ) { Text("Resume Order") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun AdvancedCheckoutDialog(
    grossTotal: Int,
    itemCount: Int,
    api: SupabaseApi,
    cart: List<CartLine>,
    staffToken: String,
    store: PosStore,
    allowOffline: Boolean,
    onOfflineSaved: () -> Unit,
    onSuccess: (CloudOrderResult) -> Unit,
    onFailure: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Cash") }
    var cash by remember(grossTotal) { mutableStateOf(grossTotal.toString()) }
    var walletMethod by remember { mutableStateOf("GCash") }
    var walletAmount by remember(grossTotal) { mutableStateOf(grossTotal.toString()) }
    var reference by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var discountCode by remember { mutableStateOf("") }
    var manualEnabled by remember { mutableStateOf(false) }
    var manualType by remember { mutableStateOf("percentage") }
    var manualValue by remember { mutableStateOf("") }
    var managerPin by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(DiscountPreview(null, 0, grossTotal)) }
    var previewing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshPreview() {
        previewing = true
        scope.launch {
            api.previewDiscount(
                staffToken = staffToken,
                subtotal = grossTotal,
                discountCode = discountCode.takeIf { it.isNotBlank() && !manualEnabled },
                manualDiscountType = manualType.takeIf { manualEnabled },
                manualDiscountValue = manualValue.toIntOrNull() ?: 0,
                manualDiscountLabel = "POS Manual Discount",
                managerPin = managerPin.takeIf { it.isNotBlank() }
            ).onSuccess {
                preview = it
                if (mode != "Cash") walletAmount = it.total.toString()
                error = null
            }.onFailure {
                error = it.message
            }
            previewing = false
        }
    }

    val finalTotal = preview.total
    val cashValue = cash.toIntOrNull() ?: 0
    val walletValue = walletAmount.toIntOrNull() ?: 0
    val splitWallet = (finalTotal - cashValue).coerceAtLeast(0)
    val paymentsValid = when (mode) {
        "Cash" -> cashValue >= finalTotal
        "GCash", "Maya" -> walletValue >= finalTotal && reference.isNotBlank()
        "Split" -> cashValue > 0 && splitWallet > 0 && reference.isNotBlank()
        else -> false
    }

    fun currentPayments(): List<PaymentPart> = when (mode) {
        "Cash" -> listOf(PaymentPart("Cash", cashValue))
        "GCash", "Maya" -> listOf(PaymentPart(mode, walletValue, reference))
        "Split" -> listOf(
            PaymentPart("Cash", cashValue),
            PaymentPart(walletMethod, splitWallet, reference)
        )
        else -> emptyList()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(28.dp),
        title = {
            Column {
                Text("Checkout", fontWeight = FontWeight.Black, fontSize = 24.sp)
                Text(itemCount.toString() + " item(s)", color = Color(0xFF718096), fontSize = 12.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 610.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Surface(color = Color(0xFFEAF3FF), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Amount Due", color = Color(0xFF60738F), fontSize = 11.sp)
                            Text(toolPeso(finalTotal), color = Color(0xFF102A56), fontSize = 25.sp, fontWeight = FontWeight.Black)
                        }
                        if (preview.discountTotal > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Discount", color = Color(0xFF718096), fontSize = 10.sp)
                                Text("-" + toolPeso(preview.discountTotal), color = Color(0xFF128651), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Cash", "GCash", "Maya", "Split")) { method ->
                        FilterChip(
                            selected = mode == method,
                            onClick = {
                                mode = method
                                if (method == "GCash" || method == "Maya") {
                                    walletMethod = method
                                    walletAmount = finalTotal.toString()
                                }
                            },
                            label = { Text(method) }
                        )
                    }
                }

                if (mode == "Cash" || mode == "Split") {
                    OutlinedTextField(
                        value = cash,
                        onValueChange = { cash = it.filter(Char::isDigit) },
                        label = { Text(if (mode == "Split") "Cash portion" else "Cash received") },
                        prefix = { Text("₱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (mode == "GCash" || mode == "Maya" || mode == "Split") {
                    if (mode == "Split") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(listOf("GCash", "Maya")) { method ->
                                FilterChip(
                                    selected = walletMethod == method,
                                    onClick = { walletMethod = method },
                                    label = { Text(method) }
                                )
                            }
                        }
                        Text(
                            walletMethod + " portion: " + toolPeso(splitWallet),
                            color = Color(0xFF243B64),
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        OutlinedTextField(
                            value = walletAmount,
                            onValueChange = { walletAmount = it.filter(Char::isDigit) },
                            label = { Text(mode + " amount") },
                            prefix = { Text("₱") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = reference,
                        onValueChange = { reference = it.take(40) },
                        label = { Text((if (mode == "Split") walletMethod else mode) + " reference no.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                Text("Discounts", fontWeight = FontWeight.Black, color = Color(0xFF243B64))
                OutlinedTextField(
                    value = discountCode,
                    onValueChange = { discountCode = it.uppercase() },
                    label = { Text("Promo / discount code") },
                    enabled = !manualEnabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = manualEnabled, onCheckedChange = {
                        manualEnabled = it
                        discountCode = ""
                    })
                    Spacer(Modifier.width(8.dp))
                    Text("Manual / Senior / PWD discount", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (manualEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = manualType == "percentage",
                            onClick = { manualType = "percentage" },
                            label = { Text("% Percentage") }
                        )
                        FilterChip(
                            selected = manualType == "fixed",
                            onClick = { manualType = "fixed" },
                            label = { Text("₱ Fixed") }
                        )
                    }
                    OutlinedTextField(
                        value = manualValue,
                        onValueChange = { manualValue = it.filter(Char::isDigit) },
                        label = { Text("Discount value") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = managerPin,
                        onValueChange = { managerPin = it.filter(Char::isDigit).take(6) },
                        label = { Text("Manager PIN approval") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedButton(
                    onClick = { refreshPreview() },
                    enabled = !previewing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LocalOffer, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (previewing) "Checking..." else "Apply / Check Discount")
                }

                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it.take(60) },
                    label = { Text("Customer name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(160) },
                    label = { Text("Order notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }

                if (allowOffline && !manualEnabled && paymentsValid) {
                    OutlinedButton(
                        onClick = {
                            val pending = store.newPendingSale(
                                lines = cart,
                                payments = currentPayments(),
                                discountCode = discountCode.takeIf { it.isNotBlank() },
                                customerName = customerName.takeIf { it.isNotBlank() },
                                notes = notes.takeIf { it.isNotBlank() }
                            )
                            store.savePendingSale(pending)
                            onOfflineSaved()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Save Order Offline")
                    }
                    Text(
                        "This order will sync automatically when the cloud connection returns.",
                        color = Color(0xFF718096),
                        fontSize = 10.sp
                    )
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && paymentsValid,
                onClick = {
                    busy = true
                    error = null
                    val payments = currentPayments()

                    scope.launch {
                        api.createOrderAdvanced(
                            cart = cart,
                            payments = payments,
                            staffToken = staffToken,
                            discountCode = discountCode.takeIf { it.isNotBlank() && !manualEnabled },
                            manualDiscountType = manualType.takeIf { manualEnabled },
                            manualDiscountValue = manualValue.toIntOrNull() ?: 0,
                            manualDiscountLabel = "POS Manual Discount",
                            managerPin = managerPin.takeIf { it.isNotBlank() },
                            customerName = customerName,
                            notes = notes
                        ).onSuccess(onSuccess)
                            .onFailure {
                                val message = it.message ?: "Unable to save order."
                                error = message
                                onFailure(message)
                            }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
            ) {
                Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Saving..." else "Complete Payment")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        }
    )
}
