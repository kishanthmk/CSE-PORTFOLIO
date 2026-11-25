package lk.cseportfolio.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import lk.cseportfolio.app.ui.*

class Nav(private val c: NavHostController) {
    fun back() { c.popBackStack() }
    fun tab(route: String) {
        c.navigate(route) { popUpTo("dashboard") { saveState = true }; launchSingleTop = true; restoreState = true }
    }
    fun go(route: String) { c.navigate(route) { launchSingleTop = true } }
    fun stock(symbol: String) = go("stock/" + Uri.encode(symbol))
    fun simulator(symbol: String = "") = go(if (symbol.isEmpty()) "sim" else "sim?symbol=" + Uri.encode(symbol))
    fun newTxn(type: String, symbol: String = "", qty: String = "", price: String = "") {
        var r = "txn?id=-1&type=$type"
        if (symbol.isNotEmpty()) r += "&symbol=" + Uri.encode(symbol)
        if (qty.isNotEmpty()) r += "&qty=$qty"
        if (price.isNotEmpty()) r += "&price=$price"
        go(r)
    }
    fun editTxn(id: Long) = go("txn?id=$id")
    fun cash(id: Long = -1) = go("cash?id=$id")
    fun market(tab: Int = 0) = go("market?tab=$tab")
    fun instrument(symbol: String) = go("instrument/" + Uri.encode(symbol))
    fun company(symbol: String) = go("company/" + Uri.encode(symbol))
    fun financials(symbol: String, key: String = "") = go("fin/" + Uri.encode(symbol) + if (key.isEmpty()) "" else "?key=" + Uri.encode(key))
}

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var unlocked by mutableStateOf(false)
    private var stoppedAt = 0L
    private val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as App).settings
        setContent {
            val s by settings.flow.collectAsStateWithLifecycle()
            AppTheme(s.theme) {
                if (s.lockEnabled && !unlocked) LockScreen { authenticate() }
                else AppRoot(vm, canLock = { canAuthenticate() })
            }
        }
        if (settings.flow.value.lockEnabled) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // lock again after a minute in the background (short trips to the file picker do not lock)
        val lock = (application as App).settings.flow.value.lockEnabled
        if (lock && unlocked && stoppedAt != 0L && System.currentTimeMillis() - stoppedAt > 60_000) { unlocked = false; authenticate() }
        stoppedAt = 0L
    }

    fun canAuthenticate(): Boolean = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!canAuthenticate()) { unlocked = true; return }   // no screen lock set up on this phone: nothing to check against
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock CSE Portfolio Pro")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("CSE Portfolio Pro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text("Your portfolio is locked.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}

private class NavTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun AppRoot(vm: MainViewModel, canLock: () -> Boolean) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val state by vm.state.collectAsStateWithLifecycle()
    val entry by controller.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "dashboard"
    val tabs = remember {
        listOf(NavTab("dashboard", "Dashboard", Icons.Filled.Dashboard), NavTab("markets", "Markets", Icons.Filled.ShowChart),
            NavTab("portfolio", "Portfolio", Icons.Filled.PieChart), NavTab("transactions", "Trades", Icons.Filled.ReceiptLong),
            NavTab("reports", "Reports", Icons.Filled.BarChart))
    }
    val topLevel = tabs.any { it.route == route }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    var fabOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (topLevel) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = { nav.tab(t.route) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                }
            }
        },
        floatingActionButton = {
            if (topLevel && state != null) Box {
                FloatingActionButton(onClick = { fabOpen = true }) { Icon(Icons.Filled.Add, contentDescription = "Add transaction") }
                DropdownMenu(expanded = fabOpen, onDismissRequest = { fabOpen = false }) {
                    DropdownMenuItem(text = { Text("Buy shares") }, onClick = { fabOpen = false; nav.newTxn("BUY") })
                    DropdownMenuItem(text = { Text("Sell shares") }, onClick = { fabOpen = false; nav.newTxn("SELL") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Bonus, rights or split") }, onClick = { fabOpen = false; nav.newTxn("BONUS") })
                    DropdownMenuItem(text = { Text("Opening balance") }, onClick = { fabOpen = false; nav.newTxn("OPENING") })
                    DropdownMenuItem(text = { Text("Deposit, withdrawal or dividend") }, onClick = { fabOpen = false; nav.cash() })
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).then(if (topLevel) Modifier else Modifier.navigationBarsPadding())) {
            if (state == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else NavHost(controller, startDestination = "dashboard") {
                composable("dashboard") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; DashboardScreen(st, vm, nav) }
                composable("portfolio") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; PortfolioScreen(st, vm, nav) }
                composable("transactions") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; TransactionsScreen(st, vm, nav) }
                composable("margin") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; MarginScreen(st, vm, nav) }
                composable("reports") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; ReportsScreen(st, vm, nav) }
                composable("stock/{symbol}") { e -> val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; StockScreen(st, vm, nav, e.arguments?.getString("symbol") ?: "") }
                composable("sim?symbol={symbol}", arguments = listOf(navArgument("symbol") { type = NavType.StringType; defaultValue = "" })) { e ->
                    val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable
                    SimulatorScreen(st, vm, nav, e.arguments?.getString("symbol") ?: "")
                }
                composable("txn?id={id}&type={type}&symbol={symbol}&qty={qty}&price={price}", arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("type") { type = NavType.StringType; defaultValue = "BUY" },
                    navArgument("symbol") { type = NavType.StringType; defaultValue = "" },
                    navArgument("qty") { type = NavType.StringType; defaultValue = "" },
                    navArgument("price") { type = NavType.StringType; defaultValue = "" })) { e ->
                    val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable
                    val a = e.arguments
                    TxnEditorScreen(st, vm, nav, a?.getLong("id") ?: -1L, a?.getString("type") ?: "BUY", a?.getString("symbol") ?: "",
                        a?.getString("qty") ?: "", a?.getString("price") ?: "")
                }
                composable("cash?id={id}", arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })) { e ->
                    val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable
                    CashEditorScreen(st, vm, nav, e.arguments?.getLong("id") ?: -1L)
                }
                composable("market?tab={tab}", arguments = listOf(navArgument("tab") { type = NavType.IntType; defaultValue = 0 })) { e ->
                    val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable
                    MarketScreen(st, vm, nav, e.arguments?.getInt("tab") ?: 0)
                }
                composable("company/{symbol}") { e -> val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; CompanyScreen(st, vm, nav, e.arguments?.getString("symbol") ?: "") }
                composable("fin/{symbol}?key={key}", arguments = listOf(navArgument("key") { type = NavType.StringType; defaultValue = "" })) { e ->
                    val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable
                    FinancialEditorScreen(st, vm, nav, e.arguments?.getString("symbol") ?: "", e.arguments?.getString("key") ?: "")
                }
                composable("markets") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; MarketsHomeScreen(st, vm, nav) }
                composable("instrument/{symbol}") { e -> val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; InstrumentScreen(st, vm, nav, e.arguments?.getString("symbol") ?: "") }
                composable("watchlist") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; WatchlistScreen(st, vm, nav) }
                composable("settings") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; SettingsScreen(st, vm, nav, canLock) }
                composable("fees") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; FeeSchedulesScreen(st, vm, nav) }
                composable("import") { val st = vm.state.collectAsStateWithLifecycle().value ?: return@composable; ImportScreen(st, vm, nav) }
                composable("audit") { AuditScreen(vm, nav) }
                composable("bin") { RecycleBinScreen(vm, nav) }
            }
        }
    }
}
