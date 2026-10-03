package com.dailydash.pos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dailydash.pos.ui.theme.DailyDashTheme
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val BrandBlue = Color(0xFF0866D7)
private val SoftBg = Color(0xFFF5F8FD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DailyDashTheme {
                val api = remember { SupabaseApi() }
                val store = remember { PosStore(this) }
                var session by remember { mutableStateOf<StaffSession?>(null) }

                if (session == null) {
                    StaffLoginScreen(api = api, onLoggedIn = { session = it })
                } else {
                    ModernPosScreen(
                        session = session!!,
                        api = api,
                        store = store,
                        onLogout = {
                            val old = session
                            session = null
                            if (old != null) {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    api.staffLogout(old.token)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun peso(value: Int): String =
    NumberFormat.getCurrencyInstance(Locale("en", "PH")).format(value)

@Composable
private fun DailyDashLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.dailydash_logo),
        contentDescription = "DailyDash",
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun StaffLoginScreen(api: SupabaseApi, onLoggedIn: (StaffSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var staff by remember { mutableStateOf<List<StaffMember>>(emptyList()) }
    var selected by remember { mutableStateOf<StaffMember?>(null) }
    var pin by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refreshStaff() {
        syncing = true
        scope.launch {
            api.staffDirectory()
                .onSuccess {
                    staff = it
                    if (selected == null || it.none { s -> s.id == selected?.id }) selected = it.firstOrNull()
                    error = null
                }
                .onFailure { error = it.message ?: "Unable to load staff." }
            syncing = false
        }
    }

    fun submitPin() {
        val member = selected ?: return
        if (pin.length !in 4..6) {
            error = "Enter your 4 to 6 digit PIN."
            return
        }
        loading = true
        error = null
        scope.launch {
            api.staffPinLogin(member.id, pin)
                .onSuccess(onLoggedIn)
                .onFailure {
                    error = it.message ?: "Login failed."
                    pin = ""
                }
            loading = false
        }
    }

    fun submitAccount() {
        if (username.isBlank() || password.length < 6) {
            error = "Enter your username and password."
            return
        }
        loading = true
        error = null
        scope.launch {
            api.staffPasswordLogin(username.trim(), password)
                .onSuccess(onLoggedIn)
                .onFailure { error = it.message ?: "Login failed." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refreshStaff() }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(SoftBg)
    ) {
        val wide = maxWidth >= 800.dp

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                LoginHero(Modifier.weight(0.95f).fillMaxHeight())
                LoginPanel(
                    modifier = Modifier.weight(1.05f).fillMaxHeight(),
                    tab = tab,
                    onTabChange = { tab = it; error = null },
                    staff = staff,
                    selected = selected,
                    onSelect = { selected = it; pin = ""; error = null },
                    pin = pin,
                    onDigit = { if (pin.length < 6) pin += it },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onClear = { pin = "" },
                    username = username,
                    onUsername = { username = it },
                    password = password,
                    onPassword = { password = it },
                    loading = loading,
                    syncing = syncing,
                    error = error,
                    onPinSubmit = ::submitPin,
                    onAccountSubmit = ::submitAccount,
                    onRefresh = ::refreshStaff
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                LoginHero(Modifier.fillMaxWidth().height(250.dp))
                LoginPanel(
                    modifier = Modifier.fillMaxWidth(),
                    tab = tab,
                    onTabChange = { tab = it; error = null },
                    staff = staff,
                    selected = selected,
                    onSelect = { selected = it; pin = ""; error = null },
                    pin = pin,
                    onDigit = { if (pin.length < 6) pin += it },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onClear = { pin = "" },
                    username = username,
                    onUsername = { username = it },
                    password = password,
                    onPassword = { password = it },
                    loading = loading,
                    syncing = syncing,
                    error = error,
                    onPinSubmit = ::submitPin,
                    onAccountSubmit = ::submitAccount,
                    onRefresh = ::refreshStaff
                )
            }
        }
    }
}

@Composable
private fun LoginHero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Brush.linearGradient(listOf(Color(0xFFEAF4FF), Color.White, Color(0xFFFFF5E6))))
            .padding(28.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            DailyDashLogo(Modifier.width(190.dp).height(120.dp))
            Column {
                Text(
                    "Welcome back to\nDailyDash POS",
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF0F2E61)
                )
                Spacer(Modifier.height(8.dp))
                Text("Good food. Better days. Smarter operations.", color = Color(0xFF64748B))
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroImage("https://foodpanda.dhmedia.io/image/fd-ph/Products/115806513.jpg?height=512&width=512")
                    HeroImage("https://foodpanda.dhmedia.io/image/fd-ph/Products/115806519.jpg?height=512&width=512")
                    HeroImage("https://foodpanda.dhmedia.io/image/fd-ph/Products/115806492.jpg?height=512&width=512")
                }
            }
            Text("Secure staff access • Inventory • Reports", color = BrandBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HeroImage(url: String) {
    Card(Modifier.size(92.dp), shape = RoundedCornerShape(24.dp)) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun LoginPanel(
    modifier: Modifier,
    tab: Int,
    onTabChange: (Int) -> Unit,
    staff: List<StaffMember>,
    selected: StaffMember?,
    onSelect: (StaffMember) -> Unit,
    pin: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    loading: Boolean,
    syncing: Boolean,
    error: String?,
    onPinSubmit: () -> Unit,
    onAccountSubmit: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(modifier = modifier, color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 30.dp)) {
            Text("Secure Login", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF102A56))
            Text("Select a staff account or sign in with credentials.", color = Color(0xFF718096))
            Spacer(Modifier.height(22.dp))

            Row(
                Modifier.fillMaxWidth().background(Color(0xFFF0F5FB), RoundedCornerShape(16.dp)).padding(4.dp)
            ) {
                LoginTab("Quick PIN Login", Icons.Default.Lock, tab == 0, Modifier.weight(1f)) { onTabChange(0) }
                LoginTab("Staff Account Login", Icons.Default.Person, tab == 1, Modifier.weight(1f)) { onTabChange(1) }
            }

            Spacer(Modifier.height(22.dp))

            if (tab == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Select your account", fontWeight = FontWeight.Bold, color = Color(0xFF243B64))
                    TextButton(onClick = onRefresh, enabled = !syncing) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (syncing) "Syncing" else "Refresh")
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(staff, key = { it.id }) { member ->
                        StaffCard(member, selected?.id == member.id) { onSelect(member) }
                    }
                }

                if (staff.isEmpty() && !syncing) {
                    Text(
                        "No active staff yet. Add staff from the DailyDash Manager dashboard.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))
                Text("Enter your PIN", fontWeight = FontWeight.Bold, color = Color(0xFF243B64))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { index ->
                        Box(
                            Modifier.size(40.dp).background(Color(0xFFF1F5FA), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (index < pin.length) "●" else "", color = BrandBlue, fontSize = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                PinPad(onDigit, onBackspace, onClear)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onPinSubmit,
                    enabled = selected != null && pin.length in 4..6 && !loading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text(if (loading) "Signing in..." else "Continue to POS", fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsername,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Staff username") },
                    leadingIcon = { Icon(Icons.Default.Person, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onAccountSubmit,
                    enabled = username.isNotBlank() && password.length >= 6 && !loading,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text(if (loading) "Signing in..." else "Sign in to DailyDash", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Text("Passwords can be assigned by a manager in Staff Management.", color = Color(0xFF718096), fontSize = 12.sp)
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LoginTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(13.dp), color = if (selected) BrandBlue else Color.Transparent) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected) Color.White else BrandBlue, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(text, color = if (selected) Color.White else Color(0xFF29496F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StaffCard(member: StaffMember, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(112.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEAF3FF) else Color(0xFFF8FAFD)),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, BrandBlue) else null
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StaffAvatar(member, 44)
            Spacer(Modifier.height(8.dp))
            Text(member.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(member.role.replaceFirstChar { it.uppercase() }, color = BrandBlue, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StaffAvatar(member: StaffMember, size: Int) {
    val initials = member.displayName.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
    if (!member.avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = member.avatarUrl,
            contentDescription = member.displayName,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(Modifier.size(size.dp).background(BrandBlue.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initials, color = BrandBlue, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun PinPad(onDigit: (String) -> Unit, onBackspace: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { n ->
                    OutlinedButton(onClick = { onDigit(n) }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) {
                        Text(n, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("Clear") }
            OutlinedButton(onClick = { onDigit("0") }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("0", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onBackspace, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(15.dp)) { Text("⌫") }
        }
    }
}

@Composable
private fun ModernPosScreen(
    session: StaffSession,
    api: SupabaseApi,
    store: PosStore,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val printerManager = remember(context) { PrinterManager(context) }

    var products by remember { mutableStateOf(MenuSeed.products) }
    var modifiers by remember { mutableStateOf(listOf<ModifierOption>()) }
    var customizingProduct by remember { mutableStateOf<Product?>(null) }
    var selectedCategory by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var cart by remember { mutableStateOf(listOf<CartLine>()) }
    var online by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var showCheckout by remember { mutableStateOf(false) }
    var showOrders by remember { mutableStateOf(false) }
    var showSettingsHub by remember { mutableStateOf(false) }
    var showPrinterSettings by remember { mutableStateOf(false) }
    var showSoftwareInfo by remember { mutableStateOf(false) }
    var showShiftDialog by remember { mutableStateOf(false) }
    var showHeldOrders by remember { mutableStateOf(false) }
    var showHoldOrder by remember { mutableStateOf(false) }
    var completedSale by remember { mutableStateOf<CompletedSale?>(null) }

    fun refresh() {
        syncing = true
        scope.launch {
            api.loadReceiptBranding()
                .onSuccess { printerManager.saveBranding(it) }

            api.deviceHeartbeat(session.token)
                .onFailure { error ->
                    if (error.message?.contains("disabled", ignoreCase = true) == true) {
                        snackbar.showSnackbar("This POS device has been disabled by the manager.")
                    }
                }

            api.loadModifiers()
                .onSuccess { modifiers = it }

            api.loadProducts()
                .onSuccess { cloud ->
                    if (cloud.isNotEmpty()) products = cloud
                    online = true
                    cart = cart.filter { line -> cloud.any { it.id == line.product.id && it.available } }

                    val pending = store.pendingSales()
                    if (pending.isNotEmpty()) {
                        var syncedCount = 0
                        pending.forEach { pendingSale ->
                            api.syncPendingSale(pendingSale, session.token)
                                .onSuccess { result ->
                                    store.saveOrder(result, pendingSale.lines.sumOf { it.quantity })
                                    store.deletePendingSale(pendingSale.localId)
                                    syncedCount++
                                }
                        }
                        if (syncedCount > 0) {
                            snackbar.showSnackbar(
                                syncedCount.toString() + " offline order(s) synced."
                            )
                        }
                    }
                }
                .onFailure {
                    online = false
                    snackbar.showSnackbar("Cloud unavailable. Showing cached menu.")
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
    val cartTotal = cart.sumOf { it.lineTotal }

    fun addCartLine(line: CartLine) {
        val signature = line.modifiers
            .sortedBy { it.option.id }
            .joinToString("|") { it.option.id + ":" + it.quantity }
        val index = cart.indexOfFirst { existing ->
            existing.product.id == line.product.id &&
                existing.upsized == line.upsized &&
                existing.modifiers
                    .sortedBy { it.option.id }
                    .joinToString("|") { it.option.id + ":" + it.quantity } == signature
        }
        cart = if (index >= 0) {
            cart.toMutableList().also { list ->
                list[index] = list[index].copy(
                    quantity = list[index].quantity + line.quantity
                )
            }
        } else {
            cart + line
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideLayout = maxWidth >= 760.dp
        val compactHeader = maxWidth < 620.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = SoftBg,
            bottomBar = {
                if (!wideLayout) {
                    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                        NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Storefront, null) }, label = { Text("POS") })
                        NavigationBarItem(selected = false, onClick = { showOrders = true }, icon = { Icon(Icons.Default.ReceiptLong, null) }, label = { Text("Orders") })
                        NavigationBarItem(selected = false, onClick = { showSettingsHub = true }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    }
                }
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (wideLayout) {
                    NavigationRail(
                        containerColor = Color.White,
                        header = {
                            DailyDashLogo(
                                Modifier
                                    .width(86.dp)
                                    .height(62.dp)
                                    .padding(horizontal = 8.dp)
                            )
                        }
                    ) {
                        NavigationRailItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Storefront, null) }, label = { Text("POS") })
                        NavigationRailItem(selected = false, onClick = { showOrders = true }, icon = { Icon(Icons.Default.ReceiptLong, null) }, label = { Text("Orders") })
                        NavigationRailItem(selected = false, onClick = { showSettingsHub = true }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = if (wideLayout) 18.dp else 12.dp)
                ) {
                    if (compactHeader) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DailyDashLogo(Modifier.width(104.dp).height(58.dp))
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { refresh() }) {
                                    Icon(if (online) Icons.Default.CloudDone else Icons.Default.CloudOff, if (online) "Online" else "Offline", tint = if (online) BrandBlue else Color(0xFF718096))
                                }
                                IconButton(onClick = { showSettingsHub = true }) {
                                    Icon(Icons.Default.Settings, "POS settings", tint = BrandBlue)
                                }
                                IconButton(onClick = onLogout) {
                                    Icon(Icons.Default.Logout, "Log out", tint = BrandBlue)
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color.White,
                                shadowElevation = 1.dp
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StaffAvatar(session.member, 30)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(session.member.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(session.member.role.replaceFirstChar { it.uppercase() }, color = Color(0xFF718096), fontSize = 10.sp)
                                    }
                                    Text(if (syncing) "Syncing…" else if (online) "Online" else "Offline", color = if (online) BrandBlue else Color(0xFF718096), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!wideLayout) {
                                DailyDashLogo(Modifier.width(118.dp).height(66.dp))
                                Spacer(Modifier.weight(1f))
                            } else {
                                Text("Point of Sale", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color(0xFF102A56))
                                Spacer(Modifier.weight(1f))
                            }
                            AssistChip(
                                onClick = { refresh() },
                                label = { Text(if (syncing) "Syncing" else if (online) "Cloud online" else "Offline") },
                                leadingIcon = { Icon(if (online) Icons.Default.CloudDone else Icons.Default.CloudOff, null, Modifier.size(17.dp)) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 2.dp) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    StaffAvatar(session.member, 32)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(session.member.displayName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(session.member.role.replaceFirstChar { it.uppercase() }, color = Color(0xFF718096), fontSize = 10.sp)
                                    }
                                    IconButton(onClick = { showSettingsHub = true }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Settings, "POS settings", tint = BrandBlue)
                                    }
                                    IconButton(onClick = onLogout, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Logout, "Log out", tint = BrandBlue)
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text("Search menu items...") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = Color.White,
                            focusedContainerColor = Color.White,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(if (wideLayout) 170.dp else 145.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(filtered, key = { it.id }) { product ->
                            ModernProductCard(product) {
                                val productModifiers = modifiers.filter { it.productId == product.id }
                                if (productModifiers.isNotEmpty() || product.allowUpsize) {
                                    customizingProduct = product
                                } else {
                                    addCartLine(CartLine(product))
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFE8F2FF)
                    ) {
                        Row(Modifier.padding(if (compactHeader) 10.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(if (compactHeader) 38.dp else 44.dp).background(BrandBlue, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(cart.sumOf { it.quantity }.toString(), color = Color.White, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cart.sumOf { it.quantity }.toString() + " item(s)", color = Color(0xFF60738F), fontSize = 11.sp)
                                Text(peso(cartTotal), color = Color(0xFF102A56), fontWeight = FontWeight.Black, fontSize = if (compactHeader) 19.sp else 22.sp)
                            }
                            Button(
                                onClick = { showCart = true },
                                enabled = cart.isNotEmpty(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                            ) { Text(if (compactHeader) "Cart" else "View Cart") }
                        }
                    }
                }
            }
        }
    }

    customizingProduct?.let { product ->
        ProductCustomizerDialog(
            product = product,
            options = modifiers.filter { it.productId == product.id },
            onAdd = { line ->
                addCartLine(line)
                customizingProduct = null
            },
            onDismiss = { customizingProduct = null }
        )
    }

    if (showCart) {
        CartDialog(
            cart = cart,
            onChange = { cart = it },
            onDismiss = { showCart = false },
            onHold = { showCart = false; showHoldOrder = true },
            onCheckout = { showCart = false; showCheckout = true }
        )
    }

    if (showCheckout) {
        AdvancedCheckoutDialog(
            grossTotal = cart.sumOf { it.lineTotal },
            itemCount = cart.sumOf { it.quantity },
            api = api,
            cart = cart,
            staffToken = session.token,
            store = store,
            allowOffline = !online,
            onOfflineSaved = {
                cart = emptyList()
                showCheckout = false
                scope.launch {
                    snackbar.showSnackbar("Order saved offline. It will sync automatically.")
                }
            },
            onSuccess = { result ->
                val soldLines = cart.map { it.copy() }
                val sale = CompletedSale(result, soldLines, session.member)
                store.saveOrder(result, soldLines.sumOf { it.quantity })
                completedSale = sale
                cart = emptyList()
                showCheckout = false
                online = true
                refresh()
                scope.launch {
                    printerManager.printAuto(sale).onFailure { error ->
                        snackbar.showSnackbar("Sale saved. Printer: " + (error.message ?: "not configured"))
                    }
                }
            },
            onFailure = { message ->
                if (message.contains("session", ignoreCase = true)) {
                    scope.launch { snackbar.showSnackbar("Staff session expired. Please log in again.") }
                    onLogout()
                } else {
                    scope.launch { snackbar.showSnackbar(message) }
                }
            },
            onDismiss = { showCheckout = false }
        )
    }

    if (showOrders) {
        LocalOrdersDialog(store.orders(), onDismiss = { showOrders = false })
    }

    completedSale?.let { sale ->
        TransactionSuccessDialog(
            sale = sale,
            printerManager = printerManager,
            onClose = { completedSale = null }
        )
    }

    if (showSettingsHub) {
        PosToolsHubDialog(
            onPrinter = {
                showSettingsHub = false
                showPrinterSettings = true
            },
            onShift = {
                showSettingsHub = false
                showShiftDialog = true
            },
            onHeldOrders = {
                showSettingsHub = false
                showHeldOrders = true
            },
            onSoftwareInfo = {
                showSettingsHub = false
                showSoftwareInfo = true
            },
            onDismiss = { showSettingsHub = false }
        )
    }

    if (showShiftDialog) {
        ShiftDialog(
            api = api,
            session = session,
            onDismiss = { showShiftDialog = false }
        )
    }

    if (showHeldOrders) {
        HeldOrdersDialog(
            api = api,
            session = session,
            onResume = { lines ->
                cart = lines
                showHeldOrders = false
            },
            onDismiss = { showHeldOrders = false }
        )
    }

    if (showHoldOrder) {
        HoldOrderDialog(
            api = api,
            session = session,
            cart = cart,
            onHeld = {
                cart = emptyList()
                showHoldOrder = false
                scope.launch { snackbar.showSnackbar("Order held successfully.") }
            },
            onDismiss = { showHoldOrder = false }
        )
    }

    if (showSoftwareInfo) {
        SoftwareInfoDialog(
            api = api,
            onDismiss = { showSoftwareInfo = false }
        )
    }

    if (showPrinterSettings) {
        PrinterSettingsDialog(
            manager = printerManager,
            onDismiss = { showPrinterSettings = false }
        )
    }
}

@Composable
private fun ModernProductCard(product: Product, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(225.dp).clickable(enabled = product.available, onClick = onAdd),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(118.dp)) {
                AsyncImage(
                    model = product.imageUrl,
                    contentDescription = product.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (!product.available) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        color = Color(0xFFD9344C),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("SOLD OUT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(11.dp)) {
                Text(product.category, color = BrandBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(product.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peso(product.price), color = Color(0xFF102A56), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(
                        onClick = onAdd,
                        enabled = product.available,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = BrandBlue)
                    ) { Icon(Icons.Default.Add, "Add") }
                }
            }
        }
    }
}

@Composable
private fun CartDialog(
    cart: List<CartLine>,
    onChange: (List<CartLine>) -> Unit,
    onDismiss: () -> Unit,
    onHold: () -> Unit,
    onCheckout: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Current Order", fontWeight = FontWeight.Black) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cart) { line ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAFE))) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(line.product.name, fontWeight = FontWeight.Bold)
                                    Text(peso(line.unitPrice) + " each", color = Color(0xFF718096), fontSize = 12.sp)
                                }
                                IconButton(onClick = { onChange(cart.filterNot { it == line }) }) {
                                    Icon(Icons.Default.Delete, "Delete")
                                }
                            }
                            if (line.product.allowUpsize) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = line.upsized,
                                        onCheckedChange = { checked ->
                                            onChange(cart.map { if (it == line) line.copy(upsized = checked) else it })
                                        }
                                    )
                                    Text("Upsize +" + peso(line.product.upsizePrice))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    if (line.quantity > 1) onChange(cart.map { if (it == line) line.copy(quantity = line.quantity - 1) else it })
                                }) { Icon(Icons.Default.Remove, "Minus") }
                                Text(line.quantity.toString(), fontWeight = FontWeight.Bold)
                                IconButton(onClick = {
                                    onChange(cart.map { if (it == line) line.copy(quantity = line.quantity + 1) else it })
                                }) { Icon(Icons.Default.Add, "Plus") }
                                Spacer(Modifier.weight(1f))
                                Text(peso(line.lineTotal), fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onCheckout, colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) {
                Text("Checkout • " + peso(cart.sumOf { it.lineTotal }))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onHold) {
                    Icon(Icons.Default.PauseCircle, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Hold")
                }
                TextButton(onClick = onDismiss) { Text("Continue") }
            }
        }
    )
}

@Composable
private fun CheckoutDialog(
    total: Int,
    itemCount: Int,
    api: SupabaseApi,
    cart: List<CartLine>,
    staffToken: String,
    onSuccess: (CloudOrderResult) -> Unit,
    onFailure: (String) -> Unit,
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
        title = { Text("Checkout", fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(itemCount.toString() + " item(s) • " + peso(total), fontSize = 22.sp, fontWeight = FontWeight.Black)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Cash","GCash","Maya")) { method ->
                        FilterChip(selected = tender == method, onClick = { tender = method }, label = { Text(method) })
                    }
                }
                if (tender == "Cash") {
                    OutlinedTextField(
                        value = cash,
                        onValueChange = { cash = it.filter(Char::isDigit) },
                        label = { Text("Cash received") },
                        prefix = { Text("₱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text("Change: " + peso(change), fontWeight = FontWeight.Bold)
                } else {
                    Text("Confirm " + tender + " payment before completing the sale.")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && (tender != "Cash" || cashValue >= total),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        api.createOrder(cart, tender, if (tender == "Cash") cashValue else total, staffToken)
                            .onSuccess(onSuccess)
                            .onFailure {
                                val message = it.message ?: "Unable to save order."
                                error = message
                                onFailure(message)
                            }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (tender == "Cash") BrandBlue else Color(0xFF17A75B))
            ) { Text(if (busy) "Saving..." else "Complete Payment") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LocalOrdersDialog(orders: List<OrderRecord>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synced Orders", fontWeight = FontWeight.Black) },
        text = {
            if (orders.isEmpty()) {
                Text("No completed orders on this device yet.")
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(orders.take(50)) { order ->
                        Card {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(order.orderNo, fontWeight = FontWeight.Bold)
                                    Text(order.items.toString() + " item(s) • " + order.tender, fontSize = 12.sp, color = Color(0xFF718096))
                                }
                                Text(peso(order.total), fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } }
    )
}


@Composable
private fun TransactionSuccessDialog(
    sale: CompletedSale,
    printerManager: PrinterManager,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var printing by remember { mutableStateOf(false) }
    var printStatus by remember { mutableStateOf<String?>(null) }
    val result = sale.result
    val totalItems = sale.lines.sumOf { it.quantity }

    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .background(Color(0xFFE8F8EF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF16A05D),
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Transaction Successful!",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF102A56)
                )
                Text(
                    result.orderNo + "  •  " + peso(result.total),
                    color = Color(0xFF6B7A90),
                    fontSize = 13.sp
                )
            }
        },
        text = {
            Column {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFF0F5FB),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(4.dp)) {
                        ReceiptTab(
                            text = "Receipt",
                            icon = Icons.Default.ReceiptLong,
                            selected = tab == 0,
                            modifier = Modifier.weight(1f)
                        ) { tab = 0 }
                        ReceiptTab(
                            text = "Order Slip",
                            icon = Icons.Default.Description,
                            selected = tab == 1,
                            modifier = Modifier.weight(1f)
                        ) { tab = 1 }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp, max = 470.dp),
                    color = Color(0xFFFAFBFD),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE4EAF2))
                ) {
                    if (tab == 0) {
                        ReceiptContent(sale)
                    } else {
                        OrderSlipContent(sale)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFEAF3FF),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null, tint = BrandBlue, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            totalItems.toString() + " item(s) recorded • Paid via " + result.tender,
                            color = Color(0xFF355477),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            printing = true
                            printStatus = "Printing receipt..."
                            scope.launch {
                                printerManager.printReceipt(sale)
                                    .onSuccess { printStatus = "Receipt printed." }
                                    .onFailure { printStatus = it.message ?: "Receipt print failed." }
                                printing = false
                            }
                        },
                        enabled = !printing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.Print, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Print Receipt")
                    }

                    OutlinedButton(
                        onClick = {
                            printing = true
                            printStatus = "Printing order slip..."
                            scope.launch {
                                printerManager.printOrderSlip(sale)
                                    .onSuccess { printStatus = "Order slip printed." }
                                    .onFailure { printStatus = it.message ?: "Order slip print failed." }
                                printing = false
                            }
                        },
                        enabled = !printing,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Icon(Icons.Default.Print, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Print Slip")
                    }
                }

                printStatus?.let {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        it,
                        color = if (it.contains("printed", ignoreCase = true)) Color(0xFF128651) else Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
            ) {
                Icon(Icons.Default.AddShoppingCart, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Order", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ReceiptTab(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) Color.White else Color.Transparent,
        shape = RoundedCornerShape(13.dp),
        shadowElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) BrandBlue else Color(0xFF718096),
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                color = if (selected) BrandBlue else Color(0xFF718096),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ReceiptContent(sale: CompletedSale) {
    val result = sale.result
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("DAILY DASH", fontWeight = FontWeight.Black, fontSize = 20.sp, color = Color(0xFF102A56))
            Text("Official POS Receipt", color = Color(0xFF718096), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Text(result.orderNo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (result.createdAt.isNotBlank()) {
                Text(result.createdAt.replace("T", " ").take(19), color = Color(0xFF718096), fontSize = 11.sp)
            }
        }

        ReceiptDivider()

        ReceiptInfoRow("Cashier", sale.staff.displayName)
        ReceiptInfoRow("Payment", result.tender)

        ReceiptDivider()

        sale.lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    line.quantity.toString() + "×",
                    modifier = Modifier.width(28.dp),
                    fontWeight = FontWeight.Bold
                )
                Column(Modifier.weight(1f)) {
                    Text(line.product.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    if (line.upsized) {
                        Text("Upsized", color = BrandBlue, fontSize = 10.sp)
                    }
                    Text(peso(line.unitPrice) + " each", color = Color(0xFF8490A2), fontSize = 10.sp)
                }
                Text(peso(line.lineTotal), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        ReceiptDivider()

        ReceiptInfoRow("TOTAL", peso(result.total), strong = true)
        ReceiptInfoRow("Cash received", peso(result.cashReceived))
        ReceiptInfoRow("Change", peso(result.changeAmount), strong = result.changeAmount > 0)

        Spacer(Modifier.height(16.dp))
        Text(
            "Thank you for choosing DailyDash!",
            modifier = Modifier.fillMaxWidth(),
            color = BrandBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OrderSlipContent(sale: CompletedSale) {
    val result = sale.result
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("ORDER SLIP", fontWeight = FontWeight.Black, fontSize = 22.sp, color = Color(0xFF102A56))
                Text("For preparation / counter", color = Color(0xFF718096), fontSize = 11.sp)
            }
            Surface(
                color = Color(0xFFE8F8EF),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "PAID",
                    color = Color(0xFF128651),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(result.orderNo, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("Cashier: " + sale.staff.displayName, color = Color(0xFF718096), fontSize = 11.sp)
        Text("Tender: " + result.tender, color = Color(0xFF718096), fontSize = 11.sp)

        ReceiptDivider()

        sale.lines.forEachIndexed { index, line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    color = BrandBlue,
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(
                        line.quantity.toString() + "×",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        line.product.name,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = Color(0xFF20395E)
                    )
                    Text(line.product.category, color = Color(0xFF718096), fontSize = 10.sp)
                    if (line.upsized) {
                        Text("• UPSIZED", color = Color(0xFFD77A00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (index != sale.lines.lastIndex) {
                HorizontalDivider(color = Color(0xFFE7EBF1))
            }
        }

        ReceiptDivider()
        Text(
            sale.lines.sumOf { it.quantity }.toString() + " total item(s)",
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4E6079)
        )
    }
}

@Composable
private fun ReceiptDivider() {
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = Color(0xFFDCE3ED))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ReceiptInfoRow(label: String, value: String, strong: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = if (strong) Color(0xFF102A56) else Color(0xFF6F7C90),
            fontSize = if (strong) 14.sp else 11.sp,
            fontWeight = if (strong) FontWeight.Black else FontWeight.Normal
        )
        Text(
            value,
            color = Color(0xFF102A56),
            fontSize = if (strong) 16.sp else 11.sp,
            fontWeight = if (strong) FontWeight.Black else FontWeight.SemiBold
        )
    }
}


@Composable
private fun PrinterSettingsDialog(
    manager: PrinterManager,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(manager.loadSettings()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshKey++
        status = if (granted) {
            "Bluetooth permission granted."
        } else {
            "Bluetooth permission is required to print."
        }
    }

    val paired = remember(refreshKey) {
        manager.pairedBluetoothPrinters()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = Color.White,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Color(0xFFE9F3FF),
                            RoundedCornerShape(15.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = BrandBlue
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        "POS Settings",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF102A56)
                    )
                    Text(
                        "Receipt printer & transaction behavior",
                        fontSize = 11.sp,
                        color = Color(0xFF718096)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    color = Color(0xFFF3F8FF),
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color(0xFFDCE9F8)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Print,
                                null,
                                tint = BrandBlue
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "VOZY P50 58mm",
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF20395E)
                            )
                            Spacer(Modifier.weight(1f))
                            Surface(
                                color = Color(0xFFE8F8EF),
                                shape = RoundedCornerShape(999.dp)
                            ) {
                                Text(
                                    "ESC/POS",
                                    color = Color(0xFF128651),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(
                                        horizontal = 9.dp,
                                        vertical = 5.dp
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Text(
                            "Bluetooth printing preset for the 58mm thermal printer. Pair the printer with Android first, then select it below.",
                            color = Color(0xFF67758A),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Text(
                    "Bluetooth printer",
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF243B64)
                )

                if (!manager.hasBluetoothPermission()) {
                    Button(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                permissionLauncher.launch(
                                    android.Manifest.permission.BLUETOOTH_CONNECT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BrandBlue
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Bluetooth, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Allow Bluetooth Printer Access")
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Icon(
                                Icons.Default.Bluetooth,
                                null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Pair Device")
                        }

                        OutlinedButton(
                            onClick = { refreshKey++ },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(13.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                null,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh")
                        }
                    }

                    if (paired.isEmpty()) {
                        Surface(
                            color = Color(0xFFFFF7E8),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                "No paired printer found. Turn on the VOZY P50, pair it in Android Bluetooth settings, then tap Refresh.",
                                modifier = Modifier.padding(13.dp),
                                color = Color(0xFF8B5B00),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    } else {
                        paired.forEach { device ->
                            val selected =
                                settings.bluetoothAddress == device.id

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        settings = settings.copy(
                                            bluetoothAddress = device.id
                                        )
                                    },
                                color = if (selected) {
                                    Color(0xFFEAF3FF)
                                } else {
                                    Color(0xFFFAFBFD)
                                },
                                shape = RoundedCornerShape(15.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) BrandBlue
                                    else Color(0xFFE3E9F1)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(
                                                if (selected) BrandBlue
                                                else Color(0xFFE9EEF5),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Print,
                                            null,
                                            tint = if (selected) Color.White
                                            else Color(0xFF627087),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            device.name,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF223A5F)
                                        )
                                        Text(
                                            device.subtitle,
                                            color = Color(0xFF7A8798),
                                            fontSize = 10.sp
                                        )
                                    }

                                    if (selected) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            null,
                                            tint = BrandBlue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE6EBF2))

                Text(
                    "After every successful sale",
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF243B64)
                )

                SettingsSwitchRow(
                    title = "Print customer receipt",
                    subtitle = "Automatically print the sales receipt.",
                    checked = settings.autoPrintReceipt,
                    onCheckedChange = {
                        settings = settings.copy(
                            autoPrintReceipt = it
                        )
                    }
                )

                SettingsSwitchRow(
                    title = "Print order slip",
                    subtitle = "Automatically print a second slip for preparation.",
                    checked = settings.autoPrintOrderSlip,
                    onCheckedChange = {
                        settings = settings.copy(
                            autoPrintOrderSlip = it
                        )
                    }
                )

                Surface(
                    color = Color(0xFFF8FAFD),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Straighten,
                            null,
                            tint = BrandBlue
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "Paper width",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "58mm / 32-character receipt layout",
                                color = Color(0xFF718096),
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            "58mm",
                            color = BrandBlue,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                Button(
                    onClick = {
                        manager.saveSettings(settings)
                        testing = true
                        status = "Sending test receipt..."
                        scope.launch {
                            manager.testPrint(settings)
                                .onSuccess {
                                    status =
                                        "Test receipt printed successfully."
                                }
                                .onFailure {
                                    status =
                                        it.message ?: "Printer test failed."
                                }
                            testing = false
                        }
                    },
                    enabled = !testing &&
                        settings.bluetoothAddress.isNotBlank() &&
                        manager.hasBluetoothPermission(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF102A56)
                    )
                ) {
                    Icon(Icons.Default.Print, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (testing) "Testing Printer..."
                        else "Save & Test Print",
                        fontWeight = FontWeight.Bold
                    )
                }

                status?.let {
                    Surface(
                        color = if (
                            it.contains(
                                "success",
                                ignoreCase = true
                            )
                        ) Color(0xFFE8F8EF)
                        else Color(0xFFF4F6F9),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(11.dp),
                            color = if (
                                it.contains(
                                    "success",
                                    ignoreCase = true
                                )
                            ) Color(0xFF128651)
                            else Color(0xFF5D6B80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    manager.saveSettings(settings)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandBlue
                )
            ) {
                Text(
                    "Save Settings",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = Color(0xFFFAFBFD),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFFE6EBF2)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF263D60)
                )
                Text(
                    subtitle,
                    color = Color(0xFF758296),
                    fontSize = 11.sp
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
