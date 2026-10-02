package com.dailydash.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailydash.pos.ui.theme.DailyDashTheme
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DailyDashTheme {
                PosScreen(
                    store = remember { PosStore(this) },
                    api = remember { SupabaseApi() }
                )
            }
        }
    }
}

private fun peso(value: Int): String =
    NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(value)

@Composable
private fun PosScreen(store: PosStore, api: SupabaseApi) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var products by remember { mutableStateOf(MenuSeed.products) }
    var selectedCategory by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var cart by remember { mutableStateOf(listOf<CartLine>()) }
    var online by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var showCheckout by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    fun refresh() {
        syncing = true
        scope.launch {
            api.loadProducts()
                .onSuccess {
                    if (it.isNotEmpty()) products = it
                    online = true
                }
                .onFailure {
                    online = false
                    snackbar.showSnackbar("Cloud unavailable. Using offline menu.")
                }
            syncing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val categories = listOf("All") + products.map { it.category }.distinct()
    val filtered = products.filter {
        (selectedCategory == "All" || it.category == selectedCategory) &&
            (search.isBlank() || it.name.contains(search, ignoreCase = true))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "DAILYDASH",
                            fontWeight = FontWeight.Black,
                            fontSize = 30.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Android Cloud POS", color = Color(0xFF667085))
                    }

                    AssistChip(
                        onClick = { refresh() },
                        label = { Text(if (syncing) "Syncing..." else if (online) "Cloud online" else "Offline menu") },
                        leadingIcon = {
                            Icon(
                                if (online) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { refresh() }, enabled = !syncing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    OutlinedButton(onClick = { showHistory = true }) {
                        Icon(Icons.Default.History, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Orders")
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search menu") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp)
                )

                Spacer(Modifier.height(10.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categories) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filtered, key = { it.id }) { product ->
                        ProductCard(product) {
                            val index = cart.indexOfFirst {
                                it.product.id == product.id && !it.upsized
                            }
                            cart = if (index >= 0) {
                                cart.toMutableList().also { list ->
                                    list[index] = list[index].copy(
                                        quantity = list[index].quantity + 1
                                    )
                                }
                            } else {
                                cart + CartLine(product)
                            }
                        }
                    }
                }
            }

            CartPanel(
                cart = cart,
                onChange = { cart = it },
                onCheckout = { showCheckout = true }
            )
        }
    }

    if (showCheckout) {
        CheckoutDialog(
            total = cart.sumOf { it.lineTotal },
            itemCount = cart.sumOf { it.quantity },
            api = api,
            cart = cart,
            onSuccess = { result ->
                store.saveOrder(result, cart.sumOf { it.quantity })
                cart = emptyList()
                showCheckout = false
                online = true
                scope.launch {
                    snackbar.showSnackbar(result.orderNo + " completed • " + peso(result.total))
                }
            },
            onFailure = {
                online = false
            },
            onDismiss = { showCheckout = false }
        )
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Synced Orders") },
            text = {
                val orders = store.orders()
                if (orders.isEmpty()) {
                    Text("No completed orders on this device yet.")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(orders.take(50)) { order ->
                            Card {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(order.orderNo, fontWeight = FontWeight.Bold)
                                        Text(
                                            order.items.toString() + " item(s) • " + order.tender,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Text(peso(order.total), fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showHistory = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ProductCard(product: Product, onAdd: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clickable(enabled = product.available) { onAdd() },
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    product.category,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(product.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (!product.available) {
                    Text(
                        "SOLD OUT",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                } else if (product.allowUpsize) {
                    Text("Upsize +" + peso(product.upsizePrice), fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(peso(product.price), fontWeight = FontWeight.Black, fontSize = 22.sp)
                FilledIconButton(
                    onClick = onAdd,
                    enabled = product.available
                ) {
                    Icon(Icons.Default.Add, contentDescription = if (product.available) "Add" else "Sold out")
                }
            }
        }
    }
}

@Composable
private fun CartPanel(
    cart: List<CartLine>,
    onChange: (List<CartLine>) -> Unit,
    onCheckout: () -> Unit
) {
    val total = cart.sumOf { it.lineTotal }

    Surface(
        modifier = Modifier
            .width(390.dp)
            .fillMaxHeight(),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Current Order", fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text(cart.sumOf { it.quantity }.toString() + " item(s)", color = Color(0xFF667085))
            Spacer(Modifier.height(12.dp))

            if (cart.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Tap a menu item to start an order.", color = Color(0xFF98A2B3))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(cart) { line ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(line.product.name, fontWeight = FontWeight.Bold)
                                        Text(peso(line.unitPrice) + " each", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = { onChange(cart.filterNot { it == line }) }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }

                                if (line.product.allowUpsize) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = line.upsized,
                                            onCheckedChange = { checked ->
                                                onChange(
                                                    cart.map {
                                                        if (it == line) line.copy(upsized = checked) else it
                                                    }
                                                )
                                            }
                                        )
                                        Text("Upsize +" + peso(line.product.upsizePrice))
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (line.quantity > 1) {
                                                onChange(
                                                    cart.map {
                                                        if (it == line) line.copy(quantity = line.quantity - 1) else it
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Minus")
                                    }

                                    Text(line.quantity.toString(), fontWeight = FontWeight.Bold)

                                    IconButton(
                                        onClick = {
                                            onChange(
                                                cart.map {
                                                    if (it == line) line.copy(quantity = line.quantity + 1) else it
                                                }
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Plus")
                                    }

                                    Spacer(Modifier.weight(1f))
                                    Text(peso(line.lineTotal), fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TOTAL", fontWeight = FontWeight.Bold)
                Text(
                    peso(total),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onCheckout,
                enabled = cart.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("CHECKOUT", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CheckoutDialog(
    total: Int,
    itemCount: Int,
    api: SupabaseApi,
    cart: List<CartLine>,
    onSuccess: (CloudOrderResult) -> Unit,
    onFailure: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tender by remember { mutableStateOf("Cash") }
    var cash by remember(total) { mutableStateOf(total.toString()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val cashValue = cash.toIntOrNull() ?: 0
    val change = (cashValue - total).coerceAtLeast(0)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Checkout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    itemCount.toString() + " item(s) • " + peso(total),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Cash", "GCash", "Maya")) { method ->
                        FilterChip(
                            selected = tender == method,
                            onClick = { tender = method },
                            label = { Text(method) }
                        )
                    }
                }

                if (tender == "Cash") {
                    OutlinedTextField(
                        value = cash,
                        onValueChange = { cash = it.filter(Char::isDigit) },
                        label = { Text("Cash received") },
                        prefix = { Text("₱") },
                        singleLine = true
                    )
                    Text("Change: " + peso(change), fontWeight = FontWeight.Bold)
                } else {
                    Text("Confirm " + tender + " payment before completing.")
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (tender != "Cash" || cashValue >= total),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        api.createOrder(
                            cart,
                            tender,
                            if (tender == "Cash") cashValue else total
                        ).onSuccess {
                            onSuccess(it)
                        }.onFailure {
                            error = it.message ?: "Unable to save order."
                            onFailure()
                        }
                        busy = false
                    }
                }
            ) {
                Text(if (busy) "Saving..." else "Complete")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
