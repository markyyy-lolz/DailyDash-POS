package com.dailydash.pos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    onSoftwareInfo: () -> Unit,
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
                ToolCard(
                    icon = Icons.Default.Info,
                    title = "Software Information",
                    subtitle = "Version, creator credits and project details",
                    onClick = onSoftwareInfo
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

    var orderType by remember { mutableStateOf("Takeout") }
    var tableNo by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf<CustomerLoyalty?>(null) }
    var customerLookupBusy by remember { mutableStateOf(false) }
    var redeemPointsText by remember { mutableStateOf("0") }
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

    val requestedRedeem = redeemPointsText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val maxRedeem = minOf(customer?.pointsBalance ?: 0, preview.total)
    val redeemPoints = requestedRedeem.coerceAtMost(maxRedeem)
    val loyaltyDiscount = redeemPoints
    val finalTotal = (preview.total - loyaltyDiscount).coerceAtLeast(0)

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
                val due = (it.total - redeemPoints).coerceAtLeast(0)
                if (mode != "Cash") walletAmount = due.toString()
                if ((cash.toIntOrNull() ?: 0) < due) cash = due.toString()
                error = null
            }.onFailure {
                error = it.message
            }
            previewing = false
        }
    }

    fun lookupCustomer() {
        val phone = customerPhone.trim()
        if (phone.filter(Char::isDigit).length < 7) {
            error = "Enter a valid customer phone number."
            return
        }
        customerLookupBusy = true
        scope.launch {
            api.lookupCustomer(staffToken, phone)
                .onSuccess { found ->
                    customer = found
                    if (found != null) {
                        customerName = found.name
                        error = null
                    } else {
                        error = "New customer — enter a name and the account will be created after payment."
                    }
                }
                .onFailure { error = it.message }
            customerLookupBusy = false
        }
    }

    val cashValue = cash.toIntOrNull() ?: 0
    val walletValue = walletAmount.toIntOrNull() ?: 0
    val splitWallet = (finalTotal - cashValue).coerceAtLeast(0)
    val loyaltyValid =
        redeemPoints == 0 ||
            (customer != null && redeemPoints >= 10 && redeemPoints <= (customer?.pointsBalance ?: 0))
    val orderTypeValid = orderType != "Dine-in" || tableNo.isNotBlank()

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
                Text(
                    itemCount.toString() + " item(s) • DailyDash POS v2.1",
                    color = Color(0xFF718096),
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 650.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Surface(color = Color(0xFFEAF3FF), shape = RoundedCornerShape(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Amount Due", color = Color(0xFF60738F), fontSize = 11.sp)
                            Text(
                                toolPeso(finalTotal),
                                color = Color(0xFF102A56),
                                fontSize = 25.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                        if (preview.discountTotal > 0 || loyaltyDiscount > 0) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (preview.discountTotal > 0) {
                                    Text(
                                        "Promo: -" + toolPeso(preview.discountTotal),
                                        color = Color(0xFF128651),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (loyaltyDiscount > 0) {
                                    Text(
                                        "Points: -" + toolPeso(loyaltyDiscount),
                                        color = Color(0xFF7B4BC4),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Order Type", fontWeight = FontWeight.Black, color = Color(0xFF243B64))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Dine-in", "Takeout", "Pickup", "Delivery")) { type ->
                        FilterChip(
                            selected = orderType == type,
                            onClick = { orderType = type },
                            label = { Text(type) }
                        )
                    }
                }

                if (orderType == "Dine-in") {
                    OutlinedTextField(
                        value = tableNo,
                        onValueChange = { tableNo = it.take(30) },
                        label = { Text("Table number / name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                Text("Customer & Loyalty", fontWeight = FontWeight.Black, color = Color(0xFF243B64))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customerPhone,
                        onValueChange = {
                            customerPhone = it.take(30)
                            customer = null
                            redeemPointsText = "0"
                        },
                        label = { Text("Phone number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { lookupCustomer() },
                        enabled = !customerLookupBusy,
                        modifier = Modifier.align(Alignment.CenterVertically),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF102A56))
                    ) {
                        Text(if (customerLookupBusy) "..." else "Find")
                    }
                }

                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it.take(60) },
                    label = { Text("Customer name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                customer?.let { member ->
                    Surface(
                        color = Color(0xFFF3ECFF),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        member.name,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF4F2C79)
                                    )
                                    Text(
                                        member.tier + " Member • " +
                                            member.pointsBalance.toString() + " pts",
                                        color = Color(0xFF73549A),
                                        fontSize = 11.sp
                                    )
                                }
                                Text(
                                    toolPeso(member.lifetimeSpend),
                                    color = Color(0xFF73549A),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = redeemPointsText,
                                onValueChange = {
                                    redeemPointsText = it.filter(Char::isDigit).take(6)
                                },
                                label = { Text("Redeem points (min 10)") },
                                supportingText = {
                                    Text(
                                        "Available: " + member.pointsBalance +
                                            " pts • 1 point = ₱1"
                                    )
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                HorizontalDivider()

                Text("Payment", fontWeight = FontWeight.Black, color = Color(0xFF243B64))
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
                        label = {
                            Text(if (mode == "Split") "Cash portion" else "Cash received")
                        },
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
                        label = {
                            Text(
                                (if (mode == "Split") walletMethod else mode) +
                                    " reference no."
                            )
                        },
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
                    Switch(
                        checked = manualEnabled,
                        onCheckedChange = {
                            manualEnabled = it
                            discountCode = ""
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Manual / Senior / PWD discount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
                        onValueChange = {
                            managerPin = it.filter(Char::isDigit).take(6)
                        },
                        label = { Text("Manager PIN approval") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword
                        ),
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
                    value = notes,
                    onValueChange = { notes = it.take(160) },
                    label = { Text("Order notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Text(
                        it,
                        color = if (it.startsWith("New customer"))
                            Color(0xFF6B5B00) else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp
                    )
                }

                if (allowOffline && !manualEnabled && paymentsValid && redeemPoints == 0) {
                    OutlinedButton(
                        onClick = {
                            val pending = store.newPendingSale(
                                lines = cart,
                                payments = currentPayments(),
                                discountCode = discountCode.takeIf { it.isNotBlank() },
                                customerName = customerName.takeIf { it.isNotBlank() },
                                customerPhone = customerPhone.takeIf { it.isNotBlank() },
                                redeemPoints = 0,
                                orderType = orderType,
                                tableNo = tableNo.takeIf { it.isNotBlank() },
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
                        "The order will sync automatically when the cloud returns. " +
                            "Loyalty redemption is only available online.",
                        color = Color(0xFF718096),
                        fontSize = 10.sp
                    )
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && paymentsValid && loyaltyValid && orderTypeValid,
                onClick = {
                    busy = true
                    error = null
                    val payments = currentPayments()

                    scope.launch {
                        api.createOrderAdvanced(
                            cart = cart,
                            payments = payments,
                            staffToken = staffToken,
                            discountCode = discountCode.takeIf {
                                it.isNotBlank() && !manualEnabled
                            },
                            manualDiscountType = manualType.takeIf { manualEnabled },
                            manualDiscountValue = manualValue.toIntOrNull() ?: 0,
                            manualDiscountLabel = "POS Manual Discount",
                            managerPin = managerPin.takeIf { it.isNotBlank() },
                            customerName = customerName,
                            customerPhone = customerPhone,
                            redeemPoints = redeemPoints,
                            orderType = orderType,
                            tableNo = tableNo,
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


@Composable
fun SoftwareInfoDialog(
    api: SupabaseApi,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var release by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var checking by remember { mutableStateOf(true) }
    var updateError by remember { mutableStateOf<String?>(null) }

    fun checkUpdate() {
        checking = true
        updateError = null
        scope.launch {
            api.latestAppRelease()
                .onSuccess { release = it }
                .onFailure { updateError = it.message }
            checking = false
        }
    }

    LaunchedEffect(Unit) { checkUpdate() }

    val updateAvailable = (release?.versionCode ?: 0) > SupabaseApi.APP_VERSION_CODE

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = Color.White,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .background(Color(0xFFEAF3FF), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = ToolBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "DailyDash POS",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF102A56)
                )
                Text(
                    "Commercial Suite • Version " + SupabaseApi.APP_VERSION,
                    color = Color(0xFF718096),
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color(0xFF0B66D4),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            "CREATOR & LEAD DEVELOPER",
                            color = Color(0xFFBFDFFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Mark Reymuel Pascual",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Project Creator • System Designer • Lead Developer",
                            color = Color(0xFFE6F1FF),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Surface(
                    color = if (updateAvailable) Color(0xFFFFF5E8) else Color(0xFFE8F8EF),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (updateAvailable) Color(0xFFFFD89C) else Color(0xFFC8EBD8)
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (updateAvailable) Icons.Default.SystemUpdate
                                else Icons.Default.Verified,
                                null,
                                tint = if (updateAvailable) Color(0xFFC16A00)
                                else Color(0xFF128651)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        checking -> "Checking for updates..."
                                        updateAvailable ->
                                            "Update available: v" + (release?.versionName ?: "")
                                        else -> "You're up to date"
                                    },
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFF263D60)
                                )
                                Text(
                                    "Current build: " + SupabaseApi.APP_VERSION +
                                        " (" + SupabaseApi.APP_VERSION_CODE + ")",
                                    fontSize = 10.sp,
                                    color = Color(0xFF718096)
                                )
                            }
                        }

                        release?.changelog?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(9.dp))
                            Text(
                                "What's new",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF425670),
                                fontSize = 11.sp
                            )
                            Text(
                                it,
                                color = Color(0xFF718096),
                                fontSize = 10.sp,
                                lineHeight = 15.sp
                            )
                        }

                        updateError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { checkUpdate() },
                                enabled = !checking,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Check")
                            }

                            release?.updateUrl?.takeIf { it.isNotBlank() }?.let { url ->
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(url)
                                                )
                                            )
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate,
                                        null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (updateAvailable) "Update" else "Release")
                                }
                            }
                        }
                    }
                }

                CreditRow(
                    icon = Icons.Default.Lightbulb,
                    title = "Original Concept & Project Direction",
                    subtitle = "DailyDash POS concept, workflow planning and overall project direction"
                )
                CreditRow(
                    icon = Icons.Default.Palette,
                    title = "UI / UX Design",
                    subtitle = "POS interface direction, responsive layouts and DailyDash visual experience"
                )
                CreditRow(
                    icon = Icons.Default.Android,
                    title = "Android POS Development",
                    subtitle = "Checkout, modifiers, loyalty, offline queue, shifts and transaction workflow"
                )
                CreditRow(
                    icon = Icons.Default.Language,
                    title = "Web Manager & Operations Center",
                    subtitle = "Dashboard, inventory, reports, loyalty, devices, KDS and customer display"
                )
                CreditRow(
                    icon = Icons.Default.Storage,
                    title = "Backend & Database Integration",
                    subtitle = "Supabase database architecture, secure RPC workflows, audit logs and synchronization"
                )
                CreditRow(
                    icon = Icons.Default.Print,
                    title = "Receipt Printing Integration",
                    subtitle = "Bluetooth ESC/POS support for VOZY P50 58mm receipt and order-slip printing"
                )
                CreditRow(
                    icon = Icons.Default.Inventory2,
                    title = "Business Systems Architecture",
                    subtitle = "Inventory, recipes, waste, suppliers, discounts, refunds, loyalty and analytics"
                )

                Surface(
                    color = Color(0xFFF5F8FC),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Repository",
                            color = Color(0xFF718096),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "github.com/markyyy-lolz/DailyDash-POS",
                            color = Color(0xFF20395E),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Built for DailyDash operations with Android, Jetpack Compose, Supabase and ESC/POS.",
                            color = Color(0xFF718096),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Text(
                    "DailyDash POS • 2026",
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF8A96A8),
                    fontSize = 10.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ToolBlue)
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CreditRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Surface(
        color = Color(0xFFFAFBFD),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFFE5EAF1)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFEAF3FF), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = ToolBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color(0xFF233A5D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    subtitle,
                    color = Color(0xFF718096),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
