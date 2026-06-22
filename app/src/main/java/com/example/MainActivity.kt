package com.example

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.db.AppDatabase
import com.example.data.db.TransactionRecord
import com.example.data.db.ScripMaster
import com.example.data.model.*
import com.example.data.repository.*
import com.example.data.work.ScrapeWorker
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun NepsePillBadge(index: String, value: Double, pct: Double, status: String, symbol: String = "रु.") {
    val isPositive = pct >= 0
    val baseColor = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
    
    val isOpen = status.contains("Open", true) || status.contains("Live", true)
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(modifier = Modifier.padding(end = 8.dp), shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, baseColor.copy(0.3f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isOpen) Color(0xFF10B981).copy(alpha = alpha) else Color.Red))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = index, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(text = String.format(Locale.US, "%s%,.1f (%+.2f%%)", symbol, value, pct), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = baseColor)
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val database = remember { AppDatabase.getDatabase(context) }
                val repository = remember { PortfolioRepository(database.portfolioDao(), database.ipoMasterDao()) }
                val marketRepo = remember { MarketRepository(database.portfolioDao()) }
                val ipoRepo = remember { IpoRepository(database.portfolioDao(), database.ipoMasterDao()) }

                val portfolioVM: PortfolioViewModel = viewModel(factory = PortfolioViewModelFactory(repository))
                val marketVM: MarketViewModel = viewModel(factory = MarketViewModelFactory(marketRepo, repository))
                val ipoVM: BulkIpoViewModel = viewModel(factory = BulkIpoViewModelFactory(ipoRepo))

                PortfolioAppContent(portfolioVM, marketVM, ipoVM)
            }
        }
    }
}

enum class NavigationTab { DASHBOARD, MATRIX, DATA, MORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioAppContent(viewModel: PortfolioViewModel, marketViewModel: MarketViewModel, ipoViewModel: BulkIpoViewModel) {
    val tabs = listOf(NavigationTab.DASHBOARD, NavigationTab.MATRIX, NavigationTab.DATA, NavigationTab.MORE)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val cs = rememberCoroutineScope(); val drawerState = rememberDrawerState(DrawerValue.Closed)
    val context = LocalContext.current

    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val indices by marketViewModel.indices.collectAsStateWithLifecycle()
    val nepseStatus by viewModel.nepseStatus.collectAsStateWithLifecycle()
    val nepseIndex = remember(indices) { 
        indices.find { it.index.equals("NEPSE Index", true) } 
            ?: indices.find { it.index.contains("NEPSE", true) } 
    }
    val pendingTypeUpdate by viewModel.pendingTypeUpdate.collectAsStateWithLifecycle()
    var showReg by remember { mutableStateOf(false) }
    var currentSubView by remember { mutableStateOf<String?>(null) }
    var isUnlocked by remember { mutableStateOf(false) }

    LaunchedEffect(userProfile) { if (userProfile != null && userProfile!!.name.isEmpty()) showReg = true }

    if (userProfile?.pin != null && !isUnlocked) {
        PinEntryDialog(
            title = "Enter PIN to Unlock",
            onPinEntered = { entered ->
                if (entered == userProfile!!.pin) {
                    isUnlocked = true
                } else {
                    Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (pendingTypeUpdate != null) {
        AlertDialog(onDismissRequest = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }, title = { Text("Sync Sector?") }, text = { Text("Update Sector to '${pendingTypeUpdate!!.type}' for all '${pendingTypeUpdate!!.item}' records?") }, confirmButton = { Button(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, true) }) { Text("Yes") } }, dismissButton = { TextButton(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }) { Text("No") } })
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState(initial = null)
    
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState, gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) { 
                GlobalProfileDrawer(userProfile, symbol = userProfile?.currencySymbol ?: "रु.",
                    onSupport = { 
                        cs.launch { 
                            pagerState.animateScrollToPage(3)
                            currentSubView = "Contact"
                            drawerState.close() 
                        } 
                    },
                    onSettings = {
                        cs.launch {
                            pagerState.animateScrollToPage(3)
                            currentSubView = "Settings"
                            drawerState.close()
                        }
                    },
                    onProfile = {
                        cs.launch {
                            pagerState.animateScrollToPage(3)
                            currentSubView = "Profile"
                            drawerState.close()
                        }
                    },
                    onClose = { cs.launch { drawerState.close() } }
                ) 
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { cs.launch { drawerState.open() } }) { Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }; Column(Modifier.padding(start = 8.dp)) { Text("FinFolio Pro", fontWeight = FontWeight.Bold); Text("EXECUTIVE ANALYTICS", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) } } }, actions = { if (nepseIndex != null) NepsePillBadge(nepseIndex.index, nepseIndex.value, nepseIndex.percentChange, nepseStatus.status, symbol = userProfile?.currencySymbol ?: "रु."); IconButton(onClick = {
                viewModel.refreshLivePrices()
                marketViewModel.refreshMarketData()
                Toast.makeText(context, "Refreshing market data...", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Refresh, null) } }) },
            bottomBar = { NavigationBar { tabs.forEachIndexed { i, tab -> NavigationBarItem(selected = pagerState.currentPage == i, onClick = { cs.launch { pagerState.animateScrollToPage(i) } }, label = { Text(tab.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }) }, icon = { Icon(when(tab){ NavigationTab.DASHBOARD -> Icons.Default.Dashboard; NavigationTab.MATRIX -> Icons.Default.TableChart; NavigationTab.DATA -> Icons.AutoMirrored.Filled.Input; NavigationTab.MORE -> Icons.Default.MoreHoriz }, null) }) } } }
        ) { inner ->
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(inner), userScrollEnabled = true) { page ->
                when (tabs[page]) {
                    NavigationTab.DASHBOARD -> DashboardScreen(viewModel)
                    NavigationTab.MATRIX -> MatrixScreen(viewModel)
                    NavigationTab.DATA -> DataScreen(viewModel)
                    NavigationTab.MORE -> MoreScreen(marketViewModel, viewModel, ipoViewModel, currentSubView, { currentSubView = it })
                }
            }
        }
    }
    if (showReg) RegistrationDialog { n, e -> viewModel.registerUser(n, e); showReg = false }
}

@Composable
fun GlobalProfileDrawer(user: com.example.data.model.UserProfile?, symbol: String = "रु.", onSupport: () -> Unit, onSettings: () -> Unit, onProfile: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(0.3f)).padding(top = 40.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.padding(12.dp)) }
                Spacer(Modifier.width(16.dp)); 
                Column(Modifier.weight(1f)) { 
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column {
                            Text(user?.name ?: "Guest", fontWeight = FontWeight.Bold)
                            Text(user?.email ?: "local@finfolio.app", fontSize = 14.sp)
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(0.1f), CircleShape)) { 
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Close", tint = MaterialTheme.colorScheme.primary) 
                        }
                    }
                }
            }
        }
        DrawerItem(Icons.Default.Person, "My Profile") { onProfile() }
        DrawerItem(Icons.Default.Settings, "Settings") { onSettings() }
        DrawerItem(Icons.AutoMirrored.Filled.HelpCenter, "Support") { onSupport() }
        
        Spacer(Modifier.weight(1f))
        
        // Quick Settings Info in Drawer
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Currency: $symbol", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Format: ${user?.dateFormat ?: "AD"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        DrawerItem(Icons.AutoMirrored.Filled.ExitToApp, "Exit", MaterialTheme.colorScheme.error) {
            (context as? android.app.Activity)?.finish()
        }
        Text("Version 2.7.0", Modifier.padding(16.dp).align(Alignment.CenterHorizontally), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DrawerItem(i: androidx.compose.ui.graphics.vector.ImageVector, l: String, c: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit = {}) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(i, null, tint = c); Spacer(Modifier.width(16.dp)); Text(l, color = c) } }
}

@Composable
fun MoreScreen(marketVM: MarketViewModel, portfolioVM: PortfolioViewModel, ipoVM: BulkIpoViewModel, subView: String?, onSubViewChange: (String?) -> Unit) {
    val userProfile by portfolioVM.userProfile.collectAsStateWithLifecycle()

    when (subView) {
        "Market" -> MarketScreen(marketVM, portfolioVM) { onSubViewChange(null) }
        "BulkCheck" -> BulkIpoCheckScreen(ipoVM) { onSubViewChange(null) }
        "IpoMaster" -> IpoMasterScreen(ipoVM) { onSubViewChange(null) }
        "Settings" -> SettingsScreen(portfolioVM) { onSubViewChange(null) }
        "Scraper" -> ScraperSettingsScreen(portfolioVM) { onSubViewChange(null) }
        "Contact" -> DeveloperProfilePanel(userProfile?.name ?: "User", userProfile?.email ?: "") { onSubViewChange(null) }
        "Profile" -> UserProfileScreen(portfolioVM) { onSubViewChange(null) }
        "Calculator" -> FinanceCalculatorScreen { onSubViewChange(null) }
        else -> {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { Text("Utilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { 
                    MoreGrid(
                        { MoreCard("Markets", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF10B981)) { onSubViewChange("Market") } }, 
                        { MoreCard("Bulk IPO", Icons.AutoMirrored.Filled.FactCheck, Color(0xFFEF4444)) { onSubViewChange("BulkCheck") } }, 
                        { MoreCard("IPO Master", Icons.Default.Inventory, Color(0xFF8B5CF6)) { onSubViewChange("IpoMaster") } },
                        { MoreCard("Scrapers", Icons.Default.CloudSync, Color(0xFFEC4899)) { onSubViewChange("Scraper") } },
                        { MoreCard("Settings", Icons.Default.Settings, Color(0xFF6366F1)) { onSubViewChange("Settings") } },
                        { MoreCard("Support", Icons.Default.SupportAgent, Color(0xFF3B82F6)) { onSubViewChange("Contact") } }, 
                        { MoreCard("Calculator", Icons.Default.Calculate, Color(0xFFF59E0B)) { onSubViewChange("Calculator") } }
                    ) 
                }
            }
        }
    }
}

@Composable
fun MoreGrid(vararg cards: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.asList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Box(Modifier.weight(1f)) { it() } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun MoreCard(t: String, i: androidx.compose.ui.graphics.vector.ImageVector, c: Color, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().height(90.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.5f))) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.copy(0.1f)), contentAlignment = Alignment.Center) { Icon(i, null, tint = c, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.height(4.dp)); Text(t, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MarketScreen(vm: MarketViewModel, pvm: PortfolioViewModel, onBack: () -> Unit) {
    val indices by vm.filteredIndices.collectAsStateWithLifecycle(); val changes by vm.priceChanges.collectAsStateWithLifecycle()
    val allIdx by vm.indices.collectAsStateWithLifecycle(); val visIdx by vm.visibleIndices.collectAsStateWithLifecycle()
    val items by pvm.itemMetrics.collectAsStateWithLifecycle(); val wishMovers by vm.watchlistMovers.collectAsStateWithLifecycle()
    val pSyms = remember(items) { items.filter { it.balanceQty > 0.0 }.map { it.item.uppercase() }.toSet() }
    val userProfile by pvm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    
    var showSch by remember { mutableStateOf(false) }; var showCfg by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf("Global") } // "Global" or "ScripOnly"

    var expInd by remember { mutableStateOf(false) }
    var expHol by remember { mutableStateOf(false) }
    var expWis by remember { mutableStateOf(false) }
    var expandedSectors by remember { mutableStateOf(setOf<String>()) }

    val hM = changes.filter { it.symbol in pSyms }.sortedBy { m -> items.find { it.item == m.symbol }?.type ?: "Other" }
    val hMGrouped = remember(hM, items) { hM.groupBy { m -> items.find { it.item == m.symbol }?.type ?: "Other" } }
    val wM = wishMovers.filter { it.symbol !in pSyms }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SubScreenHeader("Market Pulse", onBack) }
        item { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpandableHeader("Indices", indices.size, expInd, { expInd = !expInd }, Color(0xFF3B82F6), Modifier.weight(1f))
                IconButton(onClick = { showCfg = true }) { Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = Color.Gray) }
            }
        }
        if (expInd) {
            val rows = indices.chunked(2)
            items(rows.size) { rowIndex ->
                val row = rows[rowIndex]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        MarketIndexCard(row[0], symbol = symbol)
                    }
                    if (row.size > 1) {
                        Box(modifier = Modifier.weight(1f)) {
                            MarketIndexCard(row[1], symbol = symbol)
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item { ExpandableHeader("My Holdings", hM.size, expHol, { expHol = !expHol }, Color(0xFF10B981)) }
        if (expHol) {
            hMGrouped.forEach { (type, scrips) ->
                val isExp = expandedSectors.contains(type)
                item { 
                    SectorExpandableHeader(type, scrips.size, isExp) {
                        expandedSectors = if (isExp) expandedSectors - type else expandedSectors + type
                    }
                }
                if (isExp) {
                    val (gainers, losers) = scrips.partition { it.change >= 0 }
                    val sortedGainers = gainers.sortedByDescending { it.percentChange }
                    val sortedLosers = losers.sortedBy { it.percentChange }
                    val maxRows = maxOf(sortedGainers.size, sortedLosers.size)
                    
                    if (sortedGainers.isNotEmpty() || sortedLosers.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text("GAINERS (${sortedGainers.size})", Modifier.weight(1f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2ECE7B))
                                Text("LOSERS (${sortedLosers.size})", Modifier.weight(1f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEF4444), textAlign = TextAlign.End)
                            }
                        }
                    }

                    items(maxRows) { i ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                sortedGainers.getOrNull(i)?.let { MoverCard(it, true, symbol = symbol, compact = true) }
                            }
                            Box(Modifier.weight(1f)) {
                                sortedLosers.getOrNull(i)?.let { MoverCard(it, true, symbol = symbol, compact = true) }
                            }
                        }
                    }
                }
            }
        }
        item { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpandableHeader("Watchlist", wM.size, expWis, { expWis = !expWis }, Color(0xFFF59E0B), Modifier.weight(1f))
                IconButton(onClick = { searchMode = "ScripOnly"; showSch = true }) { Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = Color.Gray) }
            }
        }
        if (expWis) {
            items(wM) { MoverCard(it, false, symbol = symbol) }
        }
    }
    if (showSch) GlobalMarketSearchDialog(
        allScrips = vm.allScripMaster.collectAsStateWithLifecycle().value,
        indices = allIdx,
        priceChanges = changes,
        holdingSymbols = pSyms,
        showIndices = searchMode == "Global",
        onToggleWishlist = { vm.toggleWishlist(it) },
        onLocate = { category ->
            when (category) {
                "Index" -> expInd = true
                "Holding" -> expHol = true
                "Watchlist" -> expWis = true
            }
            showSch = false
        }
    ) { showSch = false }
    if (showCfg) IndicesConfigDialog(allIdx, visIdx, { vm.toggleIndexVisibility(it) }) { showCfg = false }
}

@Composable
fun ExpandableHeader(t: String, c: Int, ex: Boolean, onT: () -> Unit, clr: Color, modifier: Modifier = Modifier) { 
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable { onT() }.padding(vertical = 4.dp)) { 
        Icon(if (ex) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = clr)
        Text(t, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.width(8.dp))
        Badge(containerColor = clr.copy(0.1f)) { Text(c.toString(), color = clr, fontWeight = FontWeight.Bold) } 
    } 
}

@Composable
fun MarketIndexCard(idx: NepseIndex, symbol: String = "रु.") {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(idx.index, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%s%,.1f", symbol, idx.value), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Text(String.format(Locale.US, "P: %,.0f", idx.previousValue), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp), maxLines = 1)
            }
            val c = if (idx.percentChange >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Text(String.format(Locale.US, "%+.2f (%+.2f%%)", idx.change, idx.percentChange), color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun MoverCard(m: ScripPriceChange, isH: Boolean, symbol: String = "रु.", compact: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.3f))
    ) {
        Row(Modifier.padding(if (compact) 10.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { 
                Row(verticalAlignment = Alignment.CenterVertically) { 
                    Text(m.symbol, fontWeight = FontWeight.ExtraBold, fontSize = if (compact) 13.sp else 14.sp)
                    if (isH && !compact) { 
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) { Text("Holding", fontSize = 8.sp) } 
                    } 
                }
                if (compact) {
                    Text(String.format(Locale.US, "LTP: %,.0f", m.ltp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(String.format(Locale.US, "LTP: %s%,.1f (Prev: %,.1f)", symbol, m.ltp, m.previousLtp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val c = if (m.change >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Column(horizontalAlignment = Alignment.End) { 
                Text(String.format(Locale.US, "%+.1f", m.change), color = c, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 13.sp)
                Text(String.format(Locale.US, "%+.2f%%", m.percentChange), color = c, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun GlobalMarketSearchDialog(
    allScrips: List<ScripMaster>,
    indices: List<NepseIndex>,
    priceChanges: List<ScripPriceChange>,
    holdingSymbols: Set<String>,
    showIndices: Boolean = true,
    onToggleWishlist: (ScripMaster) -> Unit,
    onLocate: (String) -> Unit,
    onD: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    
    val combinedScrips = remember(allScrips, priceChanges) {
        val list = allScrips.toMutableList()
        val existingSymbols = allScrips.map { it.symbol.uppercase() }.toSet()
        priceChanges.forEach { change ->
            if (change.symbol.uppercase() !in existingSymbols) {
                list.add(ScripMaster(change.symbol, change.symbol, "Other"))
            }
        }
        list.sortedBy { it.symbol }
    }

    val filteredScrips = remember(q, combinedScrips) {
        if (q.isBlank()) combinedScrips.take(20)
        else combinedScrips.filter { it.symbol.contains(q, true) || it.name.contains(q, true) }.take(50)
    }
    
    val filteredIndices = remember(q, indices, showIndices) {
        if (!showIndices || q.isBlank()) emptyList()
        else indices.filter { it.index.contains(q, true) }
    }

    Dialog(onDismissRequest = onD) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.8f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Search Market", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = q, 
                    onValueChange = { q = it }, 
                    modifier = Modifier.fillMaxWidth(), 
                    placeholder = { Text("Search scrips or indices...") },
                    singleLine = true, 
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Default.Close, null) } }
                )
                
                LazyColumn(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (filteredIndices.isNotEmpty()) {
                        item { Text("INDICES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray) }
                        items(filteredIndices) { idx ->
                            Surface(onClick = { onLocate("Index") }) {
                                MarketIndexCard(idx)
                            }
                        }
                    }
                    
                    if (filteredScrips.isNotEmpty()) {
                        item { Spacer(Modifier.height(8.dp)); Text("SCRIPS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(filteredScrips) { s ->
                            val isHolding = s.symbol.uppercase() in holdingSymbols
                            val hasLtp = priceChanges.any { it.symbol.equals(s.symbol, true) }
                            
                            Card(
                                onClick = { onLocate(if (isHolding) "Holding" else "Watchlist") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(s.symbol, fontWeight = FontWeight.Bold)
                                            if (isHolding) {
                                                Spacer(Modifier.width(8.dp))
                                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("Holding", fontSize = 8.sp) }
                                            }
                                        }
                                        Text(s.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) 
                                        if (!hasLtp) {
                                            Text("No live data available", fontSize = 11.sp, color = MaterialTheme.colorScheme.error.copy(0.7f))
                                        }
                                    }
                                    IconButton(onClick = { onToggleWishlist(s) }) { 
                                        Icon(
                                            if (s.isWishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                                            null, 
                                            tint = if (s.isWishlisted) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                        ) 
                                    }
                                }
                            }
                        }
                    }
                    
                    if (q.isNotBlank() && filteredScrips.isEmpty() && filteredIndices.isEmpty()) {
                        item { 
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.SearchOff, null, Modifier.size(48.dp), tint = Color.LightGray)
                                    Text("No results found for '$q'", color = Color.Gray)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onD, Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
fun IndicesConfigDialog(all: List<NepseIndex>, vis: Set<String>, onT: (String) -> Unit, onD: () -> Unit) {
    Dialog(onDismissRequest = onD) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Select Indices", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f, false)) { 
                    items(all) { idx -> 
                        val isNepse = idx.index.contains("NEPSE Index", true)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !isNepse) { onT(idx.index) }
                                .padding(vertical = 4.dp), 
                            verticalAlignment = Alignment.CenterVertically
                        ) { 
                            Checkbox(
                                checked = vis.contains(idx.index) || isNepse, 
                                onCheckedChange = if (isNepse) null else { _ -> onT(idx.index) },
                                enabled = !isNepse
                            )
                            Text(idx.index, color = if (isNepse) Color.Gray else Color.Unspecified) 
                        } 
                    } 
                }
                TextButton(onClick = onD, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkIpoCheckScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val ipos by vm.ipos.collectAsStateWithLifecycle()
    val sel by vm.selectedIpo.collectAsStateWithLifecycle()
    val boids by vm.boids.collectAsStateWithLifecycle()
    val res by vm.results.collectAsStateWithLifecycle()
    val isC by vm.isChecking.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    
    var showA by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf(false) }

    val filteredIpos = remember(searchQuery, ipos) {
        if (searchQuery.isBlank()) ipos
        else ipos.filter { 
            it.companyName.contains(searchQuery, true) || 
            it.companyCode?.contains(searchQuery, true) == true ||
            it.cdscCompanyId?.toString()?.contains(searchQuery) == true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "Bulk IPO Result",
            onBack = onBack,
            trailingIcon = {
                Row {
                    if (res.isNotEmpty()) {
                        IconButton(onClick = { vm.clearResults() }) {
                            Icon(Icons.Default.ClearAll, "Clear")
                        }
                    }
                    IconButton(onClick = { vm.syncIpos() }, enabled = !isS) { 
                        if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null) 
                    }
                }
            }
        )
        
        Spacer(Modifier.height(16.dp))
        
        ExposedDropdownMenuBox(exp, { exp = it }) {
            OutlinedTextField(
                value = searchQuery.ifBlank { sel?.companyName ?: "Select IPO to check" },
                onValueChange = { searchQuery = it; exp = true },
                label = { Text("Select Company") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            
            ExposedDropdownMenu(exp, { exp = false }, modifier = Modifier.fillMaxWidth()) {
                if (filteredIpos.isNotEmpty()) {
                    filteredIpos.take(15).forEach { ipo ->
                        DropdownMenuItem(
                            text = { 
                                Column {
                                    Text(ipo.companyName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!ipo.companyCode.isNullOrBlank()) {
                                            Text(ipo.companyCode, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text("ID: ${ipo.cdscCompanyId}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            onClick = { 
                                vm.selectIpo(ipo)
                                searchQuery = ""
                                exp = false 
                            }
                        )
                    }
                } else {
                    DropdownMenuItem(
                        text = { Text("No IPOs found. Click Refresh to sync.", color = Color.Gray) },
                        onClick = { vm.syncIpos(); exp = false }
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text("Family BOIDs (${boids.size})", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var showP by remember { mutableStateOf(false) }
                val context = LocalContext.current
                val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let { 
                        context.contentResolver.openInputStream(it)?.use { input ->
                            val text = input.bufferedReader().readText()
                            vm.addMultipleBoids(text)
                        }
                    }
                }
                
                IconButton(onClick = { fileLauncher.launch("text/*") }) { Icon(Icons.Default.UploadFile, "Upload") }
                IconButton(onClick = { showP = true }) { Icon(Icons.Default.ContentPaste, "Paste") }
                Button(onClick = { showA = true }, shape = RoundedCornerShape(8.dp)) { 
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Text("Add") 
                }
                
                if (showP) PasteBoidDialog({ vm.addMultipleBoids(it); showP = false }, { showP = false })
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) { 
            if (res.isNotEmpty()) {
                items(res) { IpoResultItem(it) } 
            } else {
                items(boids) { BoidItem(it) { vm.removeBoid(it) } } 
            }
        }

        Button(
            onClick = { vm.startBulkCheck() }, 
            Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp), 
            enabled = !isC && boids.isNotEmpty() && sel != null, 
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isC) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp)) 
            else Text("Check Results", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
    if (showA) AddBoidDialog({ n, b -> vm.addBoid(n, b); showA = false }, { showA = false })
}

@Composable
fun IpoResultItem(r: BulkIpoResult) {
    val isSuccess = r.result?.success == true
    val containerColor = when {
        r.isChecking -> MaterialTheme.colorScheme.surface
        isSuccess -> Color(0xFFE8F5E9)
        r.result != null -> Color(0xFFFFEBEE)
        r.error != null -> Color(0xFFFFEBEE)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (r.result != null) BorderStroke(1.dp, if (isSuccess) Color(0xFF2E7D32).copy(0.3f) else Color(0xFFC62828).copy(0.3f)) else null
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { 
                Text(r.boidEntry.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(r.boidEntry.boid, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) 
            }
            
            Box(contentAlignment = Alignment.CenterEnd) {
                if (r.isChecking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (r.result != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isSuccess) "ALLOTTED" else "NOT ALLOTTED",
                            color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                        if (isSuccess) {
                            val unitsMatch = """(\d+)\s+units""".toRegex().find(r.result.message)
                            val units = unitsMatch?.groupValues?.get(1) ?: "Check"
                            Text("$units Units", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        } else {
                            Text(r.result.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else if (r.error != null) {
                    Text(r.error, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BoidItem(b: BoidEntry, onR: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(b.name, fontWeight = FontWeight.Bold); Text(b.boid, fontSize = 10.sp) }; IconButton(onClick = onR) { Icon(Icons.Default.Delete, null, tint = Color.Red) } } }
}

@Composable
fun PasteBoidDialog(onA: (String) -> Unit, onD: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onD,
        title = { Text("Paste Accounts") },
        text = {
            Column {
                Text(
                    "Format: Name, BOID (one per line)", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Accounts Data") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text("Ram Prasad, 1301020000000001\nShyam Lal, 1301020000000002") },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = { Button(onClick = { onA(text) }, enabled = text.isNotBlank()) { Text("Import List") } },
        dismissButton = { TextButton(onD) { Text("Cancel") } }
    )
}

fun formatCurrency(amount: Double, symbol: String): String {
    return String.format(Locale.US, "%s%,.0f", symbol, amount)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: PortfolioViewModel, onBack: () -> Unit) {
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val currentCurrency = userProfile?.currencySymbol ?: "रु."
    val currentDateFormat = userProfile?.dateFormat ?: "AD"
    
    val currencies = listOf("रु.", "$", "€", "£", "¥", "₹")
    
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader("Settings", onBack)
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Regional Preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Currency Symbol", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Select your preferred currency symbol to be used throughout the app.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            currencies.forEach { symbol ->
                                FilterChip(
                                    selected = currentCurrency == symbol,
                                    onClick = { vm.updateAppSettings(symbol, currentDateFormat) },
                                    label = { Text(symbol, fontWeight = FontWeight.Bold) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }
            
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Date & Time Format", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Choose between BS (Bikram Sambat) or AD (Anno Domini) formats.", fontSize = 12.sp, color = Color.Gray)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("AD", "BS").forEach { format ->
                                OutlinedButton(
                                    onClick = { /* To be implemented later */ },
                                    modifier = Modifier.weight(1f),
                                    enabled = false, // Placeholder
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (currentDateFormat == format) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(format)
                                    if (format == "BS") {
                                        Spacer(Modifier.width(4.dp))
                                        Badge { Text("Soon", fontSize = 8.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val hasPin = userProfile?.pin != null
                        var showSetPin by remember { mutableStateOf(false) }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("App PIN Lock", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text("Secure access to FinFolio with a 4-digit PIN.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = hasPin, onCheckedChange = { 
                                if (it) showSetPin = true else vm.updatePin(null)
                            })
                        }
                        
                        if (hasPin) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showSetPin = true }) {
                                Text("Change PIN", fontSize = 14.sp)
                            }
                        }

                        if (showSetPin) {
                            PinEntryDialog(
                                title = "Set 4-Digit PIN",
                                onPinEntered = { 
                                    vm.updatePin(it)
                                    showSetPin = false
                                },
                                onDismiss = { showSetPin = false }
                            )
                        }
                    }
                }
            }

            item {
                Text("System", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                
                var showFlushAlert by remember { mutableStateOf(false) }

                if (showFlushAlert) {
                    AlertDialog(
                        onDismissRequest = { showFlushAlert = false },
                        title = { Text("Flush App Data?") },
                        text = { Text("This will permanently delete all transactions, history, prices, BOIDs, and custom scraper settings. This action cannot be undone.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    vm.flushAllData()
                                    showFlushAlert = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Flush Everything") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFlushAlert = false }) { Text("Cancel") }
                        }
                    )
                }

                Card(
                    onClick = { showFlushAlert = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(0.1f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(0.2f))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Flush All Data", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text("Reset app to factory state (Wipe everything)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IpoMasterScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val ipos by vm.ipos.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    val syncMsg by vm.syncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showAddManual by remember { mutableStateOf(false) }

    LaunchedEffect(syncMsg) {
        syncMsg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val filteredIpos = remember(searchQuery, ipos) {
        if (searchQuery.isBlank()) ipos
        else ipos.filter { 
            it.companyName.contains(searchQuery, true) || 
            it.companyCode?.contains(searchQuery, true) == true ||
            it.cdscCompanyId?.toString()?.contains(searchQuery) == true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "IPO Master List",
            onBack = onBack,
            trailingIcon = {
                Row {
                    IconButton(onClick = { showAddManual = true }) { Icon(Icons.Default.Add, null) }
                    IconButton(onClick = { vm.syncIpos() }, enabled = !isS) {
                        if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, null)
                    }
                }
            }
        )

        if (isS) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text("Search companies...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filteredIpos.isEmpty() && !isS) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(if (searchQuery.isEmpty()) Icons.Default.Inventory else Icons.Default.SearchOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(16.dp))
                            if (searchQuery.isEmpty()) {
                                Text("No IPOs found in master list.", fontWeight = FontWeight.Bold)
                                Text("Try syncing from online sources.", fontSize = 12.sp, color = Color.Gray)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { vm.syncIpos() }) {
                                    Icon(Icons.Default.Refresh, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Sync Now")
                                }
                            } else {
                                Text("No results found for '$searchQuery'", fontWeight = FontWeight.Bold)
                                Text("Try a different company name or symbol.", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            items(filteredIpos) { ipo ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ipo.companyName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ipo.companyCode ?: "NO SYMBOL", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(8.dp))
                                Text("ID: ${ipo.cdscCompanyId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        
                        val statusColor = when (ipo.status.lowercase()) {
                            "active", "open" -> Color(0xFF10B981)
                            "allotted", "closed" -> Color(0xFF6B7280)
                            "pipeline" -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.primary
                        }

                        IconButton(onClick = { vm.toggleIpoActive(ipo) }) {
                            Icon(
                                if (ipo.isActive) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                null,
                                tint = if (ipo.isActive) MaterialTheme.colorScheme.primary else Color.Gray
                            )
                        }

                        Badge(
                            containerColor = statusColor.copy(alpha = 0.1f),
                            contentColor = statusColor,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(ipo.status.uppercase(), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showAddManual) {
        AddManualIpoDialog(
            onDismiss = { showAddManual = false },
            onAdd = { n, c, id ->
                vm.addManualIpo(n, c, id)
                showAddManual = false
            }
        )
    }
}

@Composable
fun AddManualIpoDialog(onDismiss: () -> Unit, onAdd: (String, String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var idText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual IPO") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Company Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(code, { code = it }, label = { Text("Symbol/Code") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    idText, 
                    { if (it.all { c -> c.isDigit() }) idText = it }, 
                    label = { Text("CDSC Company ID") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, code, idText.toIntOrNull() ?: 0) },
                enabled = name.isNotBlank() && idText.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddBoidDialog(onA: (String, String) -> Unit, onD: () -> Unit) {
    var n by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onD, 
        title = { Text("Add Family BOID") }, 
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { 
                OutlinedTextField(n, { n = it }, label = { Text("Holder Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = b, 
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 16) b = it }, 
                    label = { Text("16-digit BOID") }, 
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("${b.length}/16 digits") },
                    singleLine = true
                )
            } 
        }, 
        confirmButton = { 
            Button(
                onClick = { onA(n, b) },
                enabled = n.isNotBlank() && b.length == 16
            ) { Text("Add Account") } 
        }, 
        dismissButton = { TextButton(onD) { Text("Cancel") } }
    )
}

@Composable
fun DashboardScreen(vm: PortfolioViewModel) {
    val items by vm.itemMetrics.collectAsStateWithLifecycle()
    val types by vm.typeMetrics.collectAsStateWithLifecycle()
    val scope by vm.datasetScope.collectAsStateWithLifecycle()
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ExecutiveScopeSelector(scope) { vm.setDatasetScope(it) } }
        val tEval = items.sumOf { it.evaluation }
        val tInv = items.sumOf { it.netInvest }
        val tRec = items.sumOf { it.receivableAmount }
        val tGain = items.sumOf { it.netGain }
        val tProf = items.sumOf { it.profitAmount }
        val tBuy = items.sumOf { it.buyAmount }
        val tGrowth = if (tBuy > 0.0) (tGain / tBuy) * 100.0 else 0.0
        val tProfitPct = if (tInv > 0.0) (tProf / tInv) * 100.0 else 0.0
        item { ValuationSummaryCard(tEval, tInv, tRec, tGain, tGrowth, tProf, tProfitPct, symbol = symbol) }
        item { SectorAllocationCard(types, tInv, symbol) }
        item { PerformersSection(items, symbol) }
    }
}

@Composable
fun ValuationSummaryCard(
    portfolio: Double,
    invest: Double,
    receivable: Double,
    netGain: Double,
    growthPct: Double,
    profit: Double,
    profitPct: Double,
    compact: Boolean = false,
    symbol: String = "रु."
) {
    val c = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF1D4ED8)))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 16.dp else 24.dp)) {
        Column(Modifier.background(c).padding(if (compact) 16.dp else 24.dp)) {
            Text("Portfolio Value", color = Color.White.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(formatCurrency(portfolio, symbol), fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(0.2f))
            
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    MetricItem(Modifier.weight(1f), "INVEST", invest, symbol = symbol)
                    MetricItem(Modifier.weight(1f), "RECEIVABLE", receivable, horizontalAlignment = Alignment.End, symbol = symbol)
                }
                Row(Modifier.fillMaxWidth()) {
                    MetricItem(Modifier.weight(1f), "NET GAIN", netGain, growthPct, isGain = true, symbol = symbol)
                    MetricItem(Modifier.weight(1f), "PROFIT", profit, profitPct, isGain = true, horizontalAlignment = Alignment.End, symbol = symbol)
                }
            }
        }
    }
}

@Composable
fun MetricItem(
    modifier: Modifier,
    label: String,
    value: Double,
    percent: Double? = null,
    isGain: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    symbol: String = "रु."
) {
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Text(label, color = Color.White.copy(0.7f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (isGain) {
                if (value >= 0) Color(0xFF4ADE80) else Color(0xFFF87171)
            } else Color.White
            Text(formatCurrency(value, symbol), color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (percent != null) {
                Text(
                    text = String.format(Locale.US, " (%+.1f%%)", percent),
                    color = color.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
    }
}

@Composable
fun SectorAllocationCard(m: List<TypeMetrics>, t: Double, symbol: String = "रु.") {
    val sorted = remember(m) { m.sortedByDescending { it.netInvest } }
    val displayList = remember(sorted) {
        if (sorted.size > 5) {
            val top4 = sorted.take(4)
            val others = sorted.drop(4)
            top4 + TypeMetrics(
                type = "OTHER",
                itemCount = others.sumOf { it.itemCount },
                buyAmount = 0.0, saleAmount = 0.0, returnsCash = 0.0, returnsQty = 0.0,
                balanceQty = 0.0, netInvest = others.sumOf { it.netInvest },
                evaluation = 0.0, realizedGain = 0.0, unrealizedGain = 0.0,
                deductions = 0.0, netGain = 0.0, growth = 0.0,
                receivableAmount = 0.0, profitAmount = 0.0, profitPercent = 0.0
            )
        } else sorted
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sector Allocation", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (m.isEmpty() || t == 0.0) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("No data available", color = Color.Gray)
                }
            } else {
                val colors = listOf(Color(0xFF00D2C4), Color(0xFF2ECE7B), Color(0xFFFFB300), Color(0xFFEB4D4B), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            var start = -90f
                            displayList.forEachIndexed { i, met ->
                                val sweep = (met.netInvest / t).toFloat() * 360f
                                if (sweep > 0) {
                                    // 3D Shadow effect
                                    drawArc(
                                        color = if (met.type == "OTHER") Color.Gray.copy(0.2f) else colors[i % colors.size].copy(alpha = 0.3f),
                                        startAngle = start,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        style = Stroke(24.dp.toPx(), cap = StrokeCap.Butt),
                                        topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx())
                                    )
                                    // Main Ring
                                    drawArc(
                                        color = if (met.type == "OTHER") Color.Gray else colors[i % colors.size],
                                        startAngle = start,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        style = Stroke(20.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                    start += sweep
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("INVESTED", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(formatCurrency(t, symbol), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayList.forEachIndexed { i, met ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (met.type == "OTHER") Color.Gray else colors[i % colors.size]))
                                Spacer(Modifier.width(8.dp))
                                Text(met.type, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(String.format(Locale.US, "(%.1f%%)", (met.netInvest/t)*100), fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PerformersSection(m: List<ItemMetrics>, symbol: String = "रु.") {
    val gainers = m.filter { it.netGain > 0 }.sortedByDescending { it.netGain }.take(2)
    val losers = m.filter { it.netGain < 0 }.sortedBy { it.netGain }.take(2)

    if (gainers.isEmpty() && losers.isEmpty()) return

    Column {
        Text("Market Performers", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOP GAINERS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2ECE7B))
                gainers.forEach { PerformerCard(it, symbol) }
                if (gainers.isEmpty()) Text("No gainers", fontSize = 10.sp, color = Color.Gray)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOP LOSERS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEF4444))
                losers.forEach { PerformerCard(it, symbol) }
                if (losers.isEmpty()) Text("No losers", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun PerformerCard(met: ItemMetrics, symbol: String = "रु.") {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(met.item, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                val color = if (met.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
                Text(
                    text = String.format(Locale.US, "%+.1f%%", met.growth),
                    color = color,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = formatCurrency(met.netGain, symbol),
                color = if (met.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun MatrixScreen(vm: PortfolioViewModel) {
    val scope by vm.datasetScope.collectAsStateWithLifecycle(); val filter by vm.selectedTypeFilter.collectAsStateWithLifecycle()
    val items by vm.itemMetrics.collectAsStateWithLifecycle(); val dTypes by vm.distinctTypes.collectAsStateWithLifecycle()
    var showCfg by remember { mutableStateOf(false) }
    val iCols by vm.itemColumns.collectAsStateWithLifecycle()
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    
    val fItems = if (filter == "All") items else items.filter { it.type.equals(filter, true) }
    
    val tEval = fItems.sumOf { it.evaluation }
    val tInv = fItems.sumOf { it.netInvest }
    val tRec = fItems.sumOf { it.receivableAmount }
    val tGain = fItems.sumOf { it.netGain }
    val tProf = fItems.sumOf { it.profitAmount }
    val tBuy = fItems.sumOf { it.buyAmount }

    val tGrowth = if (tBuy > 0.0) (tGain / tBuy) * 100.0 else 0.0
    val tProfitPct = if (tInv > 0.0) (tProf / tInv) * 100.0 else 0.0

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ValuationSummaryCard(tEval, tInv, tRec, tGain, tGrowth, tProf, tProfitPct, true, symbol = symbol)
        Spacer(Modifier.height(8.dp)); ExecutiveScopeSelector(scope) { vm.setDatasetScope(it) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            var exp by remember { mutableStateOf(false) }
            Box { 
                Button(onClick = { exp = true }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { 
                    Text("Sector: $filter", fontSize = 10.sp)
                    Icon(Icons.Default.ArrowDropDown, null) 
                }
                DropdownMenu(exp, { exp = false }) { 
                    DropdownMenuItem(text = { Text("All") }, onClick = { vm.setSelectedTypeFilter("All"); exp = false })
                    // Sort items between All and Other
                    val sortedTypes = dTypes.filter { it.lowercase() != "other" }.sorted()
                    val hasOther = dTypes.any { it.lowercase() == "other" }
                    
                    sortedTypes.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { vm.setSelectedTypeFilter(it); exp = false }) }
                    if (hasOther) {
                        DropdownMenuItem(text = { Text("Other") }, onClick = { vm.setSelectedTypeFilter("Other"); exp = false })
                    }
                } 
            }
            
            Text("${fItems.size} Scrips", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

            Button(onClick = { showCfg = true }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { 
                Icon(Icons.Default.ViewColumn, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Columns", fontSize = 10.sp) 
            }
        }
        Box(Modifier.weight(1f).padding(top = 12.dp)) { ItemMatrixTable(fItems, iCols, symbol = symbol) }
    }
    if (showCfg) ColumnConfigurationDialog(true, iCols, { vm.toggleItemColumn(it) }, { showCfg = false })
}

@Composable
fun ItemMatrixTable(items: List<ItemMetrics>, cols: Set<String>, symbol: String = "रु.") {
    val scroll = rememberScrollState(); val w = 90.dp
    Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp)) { 
            MatrixCellText("Scrip", FontWeight.Bold, 80.dp)
            Row(Modifier.horizontalScroll(scroll)) { 
                if (cols.contains("Buy_Amount")) MatrixCellText("Buy Amt", FontWeight.Bold, w)
                if (cols.contains("Buy_Qty")) MatrixCellText("Buy Qty", FontWeight.Bold, w)
                if (cols.contains("Buy_Count")) MatrixCellText("Buy Count", FontWeight.Bold, w)
                if (cols.contains("Sale_Amount")) MatrixCellText("Sale Amt", FontWeight.Bold, w)
                if (cols.contains("Sale_Qty")) MatrixCellText("Sale Qty", FontWeight.Bold, w)
                if (cols.contains("Sale_Count")) MatrixCellText("Sale Count", FontWeight.Bold, w)
                if (cols.contains("Returns_Cash")) MatrixCellText("Ret Cash", FontWeight.Bold, w)
                if (cols.contains("Returns_Qty")) MatrixCellText("Ret Qty", FontWeight.Bold, w)
                if (cols.contains("Return_Count")) MatrixCellText("Ret Count", FontWeight.Bold, w)
                if (cols.contains("Balance_Qty")) MatrixCellText("Bal Qty", FontWeight.Bold, w)
                if (cols.contains("Avg_CP")) MatrixCellText("Avg CP", FontWeight.Bold, w)
                if (cols.contains("Avg_SP")) MatrixCellText("Avg SP", FontWeight.Bold, w)
                if (cols.contains("LTP")) MatrixCellText("LTP", FontWeight.Bold, w)
                if (cols.contains("Net_Invest")) MatrixCellText("Invest", FontWeight.Bold, w)
                if (cols.contains("Evaluation")) MatrixCellText("Eval", FontWeight.Bold, w)
                if (cols.contains("Realized_Gain")) MatrixCellText("Realized", FontWeight.Bold, w)
                if (cols.contains("Unrealized_Gain")) MatrixCellText("Unrealized", FontWeight.Bold, w)
                if (cols.contains("Deductions")) MatrixCellText("Deduct", FontWeight.Bold, w)
                if (cols.contains("Net_Gain")) MatrixCellText("Net Gain", FontWeight.Bold, w)
                if (cols.contains("Growth")) MatrixCellText("Growth %", FontWeight.Bold, w)
                if (cols.contains("Receivable_Amount")) MatrixCellText("Receivable", FontWeight.Bold, w)
                if (cols.contains("Profit_Amount")) MatrixCellText("Profit", FontWeight.Bold, w)
                if (cols.contains("Profit_Percent")) MatrixCellText("Profit %", FontWeight.Bold, w)
            } 
        }
        LazyColumn(Modifier.weight(1f)) { 
            items(items) { r -> 
                Row(Modifier.padding(vertical = 8.dp)) { 
                    MatrixCellText(r.item, FontWeight.Bold, 80.dp, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(scroll)) { 
                        if (cols.contains("Buy_Amount")) MatrixCellText(formatCurrency(r.buyAmount, symbol), width = w)
                        if (cols.contains("Buy_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.buyQty), width = w)
                        if (cols.contains("Buy_Count")) MatrixCellText(r.buyCount.toString(), width = w)
                        if (cols.contains("Sale_Amount")) MatrixCellText(formatCurrency(r.saleAmount, symbol), width = w)
                        if (cols.contains("Sale_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.saleQty), width = w)
                        if (cols.contains("Sale_Count")) MatrixCellText(r.saleCount.toString(), width = w)
                        if (cols.contains("Returns_Cash")) MatrixCellText(formatCurrency(r.returnsCash, symbol), width = w)
                        if (cols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.returnsQty), width = w)
                        if (cols.contains("Return_Count")) MatrixCellText(r.returnCount.toString(), width = w)
                        if (cols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.balanceQty), width = w)
                        if (cols.contains("Avg_CP")) MatrixCellText(String.format(Locale.US, "%s%,.1f", symbol, r.avgCp), width = w)
                        if (cols.contains("Avg_SP")) MatrixCellText(String.format(Locale.US, "%s%,.1f", symbol, r.avgSp), width = w)
                        if (cols.contains("LTP")) MatrixCellText(String.format(Locale.US, "%s%,.1f", symbol, r.ltp), width = w)
                        if (cols.contains("Net_Invest")) MatrixCellText(formatCurrency(r.netInvest, symbol), width = w)
                        if (cols.contains("Evaluation")) MatrixCellText(formatCurrency(r.evaluation, symbol), width = w)
                        if (cols.contains("Realized_Gain")) MatrixCellText(formatCurrency(r.realizedGain, symbol), width = w, color = if (r.realizedGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Unrealized_Gain")) MatrixCellText(formatCurrency(r.unrealizedGain, symbol), width = w, color = if (r.unrealizedGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Deductions")) MatrixCellText(formatCurrency(r.deductions, symbol), width = w, color = Color.Gray)
                        if (cols.contains("Net_Gain")) MatrixCellText(formatCurrency(r.netGain, symbol), width = w, color = if (r.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%.1f%%", r.growth), width = w, color = if (r.growth >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Receivable_Amount")) MatrixCellText(formatCurrency(r.receivableAmount, symbol), width = w)
                        if (cols.contains("Profit_Amount")) MatrixCellText(formatCurrency(r.profitAmount, symbol), width = w, color = if (r.profitAmount >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%.1f%%", r.profitPercent), width = w, color = if (r.profitPercent >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    } 
                } 
            }
        }
        if (items.isNotEmpty()) {
            val tBuyAmt = items.sumOf { it.buyAmount }
            val tNetInv = items.sumOf { it.netInvest }
            val tNetGain = items.sumOf { it.netGain }
            val tProfAmt = items.sumOf { it.profitAmount }
            val tGrowth = if (tBuyAmt > 0) (tNetGain / tBuyAmt) * 100.0 else 0.0
            val tProfPct = if (tNetInv > 0) (tProfAmt / tNetInv) * 100.0 else 0.0

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 12.dp)) {
                MatrixCellText("TOTAL", FontWeight.ExtraBold, 80.dp, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(scroll)) {
                    if (cols.contains("Buy_Amount")) MatrixCellText(formatCurrency(tBuyAmt, symbol), FontWeight.Bold, w)
                    if (cols.contains("Buy_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", items.sumOf { it.buyQty }), FontWeight.Bold, w)
                    if (cols.contains("Buy_Count")) MatrixCellText(items.sumOf { it.buyCount }.toString(), FontWeight.Bold, w)
                    if (cols.contains("Sale_Amount")) MatrixCellText(formatCurrency(items.sumOf { it.saleAmount }, symbol), FontWeight.Bold, w)
                    if (cols.contains("Sale_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", items.sumOf { it.saleQty }), FontWeight.Bold, w)
                    if (cols.contains("Sale_Count")) MatrixCellText(items.sumOf { it.saleCount }.toString(), FontWeight.Bold, w)
                    if (cols.contains("Returns_Cash")) MatrixCellText(formatCurrency(items.sumOf { it.returnsCash }, symbol), FontWeight.Bold, w)
                    if (cols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", items.sumOf { it.returnsQty }), FontWeight.Bold, w)
                    if (cols.contains("Return_Count")) MatrixCellText(items.sumOf { it.returnCount }.toString(), FontWeight.Bold, w)
                    if (cols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", items.sumOf { it.balanceQty }), FontWeight.Bold, w)
                    if (cols.contains("Avg_CP")) MatrixCellText("-", FontWeight.Bold, w)
                    if (cols.contains("Avg_SP")) MatrixCellText("-", FontWeight.Bold, w)
                    if (cols.contains("LTP")) MatrixCellText("-", FontWeight.Bold, w)
                    if (cols.contains("Net_Invest")) MatrixCellText(formatCurrency(tNetInv, symbol), FontWeight.Bold, w)
                    if (cols.contains("Evaluation")) MatrixCellText(formatCurrency(items.sumOf { it.evaluation }, symbol), FontWeight.Bold, w)
                    if (cols.contains("Realized_Gain")) MatrixCellText(formatCurrency(items.sumOf { it.realizedGain }, symbol), FontWeight.Bold, w, color = if (items.sumOf { it.realizedGain } >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    if (cols.contains("Unrealized_Gain")) MatrixCellText(formatCurrency(items.sumOf { it.unrealizedGain }, symbol), FontWeight.Bold, w, color = if (items.sumOf { it.unrealizedGain } >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    if (cols.contains("Deductions")) MatrixCellText(formatCurrency(items.sumOf { it.deductions }, symbol), FontWeight.Bold, w, color = Color.Gray)
                    if (cols.contains("Net_Gain")) MatrixCellText(formatCurrency(tNetGain, symbol), FontWeight.Bold, w, color = if (tNetGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    if (cols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%.1f%%", tGrowth), FontWeight.Bold, w, color = if (tGrowth >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    if (cols.contains("Receivable_Amount")) MatrixCellText(formatCurrency(items.sumOf { it.receivableAmount }, symbol), FontWeight.Bold, w)
                    if (cols.contains("Profit_Amount")) MatrixCellText(formatCurrency(tProfAmt, symbol), FontWeight.Bold, w, color = if (tProfAmt >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    if (cols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%.1f%%", tProfPct), FontWeight.Bold, w, color = if (tProfPct >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                }
            }
        }
    }
}

@Composable
fun ColumnConfigurationDialog(isItem: Boolean, active: Set<String>, onT: (String) -> Unit, onD: () -> Unit) {
    val opts = listOf(
        "Buy_Amount", "Buy_Qty", "Buy_Count", "Sale_Amount", "Sale_Qty", "Sale_Count",
        "Returns_Cash", "Returns_Qty", "Return_Count", "Balance_Qty", "Avg_CP", "Avg_SP",
        "LTP", "Net_Invest", "Evaluation", "Realized_Gain", "Unrealized_Gain", "Deductions",
        "Net_Gain", "Growth", "Receivable_Amount", "Profit_Amount", "Profit_Percent"
    ).sorted()
    Dialog(onDismissRequest = onD) { 
        Card(shape = RoundedCornerShape(16.dp)) { 
            Column(Modifier.padding(16.dp)) { 
                Text("Configure Columns", fontWeight = FontWeight.Bold)
                
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { opts.forEach { if (!active.contains(it)) onT(it) } }, modifier = Modifier.weight(1f)) {
                        Text("Select All", fontSize = 11.sp)
                    }
                    TextButton(onClick = { opts.forEach { if (active.contains(it)) onT(it) } }, modifier = Modifier.weight(1f)) {
                        Text("Unselect All", fontSize = 11.sp)
                    }
                }

                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 4.dp)) {
                    items(opts) { c -> 
                        Row(Modifier.fillMaxWidth().clickable { onT(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { 
                            Checkbox(active.contains(c), { onT(c) })
                            Text(c.replace("_", " "), fontSize = 13.sp) 
                        } 
                    } 
                }
                TextButton(onD, Modifier.align(Alignment.End)) { Text("Done") } 
            } 
        } 
    }
}

@Composable
fun MatrixCellText(text: String, fontWeight: FontWeight = FontWeight.Normal, width: androidx.compose.ui.unit.Dp, marginStart: androidx.compose.ui.unit.Dp = 0.dp, color: Color = Color.Unspecified) {
    androidx.compose.material3.Text(text = text, fontSize = 11.sp, fontWeight = fontWeight, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = marginStart).width(width).padding(horizontal = 4.dp), textAlign = TextAlign.Start)
}

@Composable
fun ExecutiveScopeSelector(s: DatasetScope, onS: (DatasetScope) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
        listOf(DatasetScope.OVERALL to "Overall", DatasetScope.MEROSHARE to "Portfolio").forEach { (sc, l) ->
            val sel = s == sc
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { onS(sc) }.padding(8.dp), contentAlignment = Alignment.Center) { Text(l, fontWeight = FontWeight.Bold, color = if (sel) MaterialTheme.colorScheme.primary else Color.Gray) }
        }
    }
}

@Composable
fun RegistrationDialog(onR: (String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = {}, title = { Text("Personalize") }, text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Full Name") }); OutlinedTextField(e, { e = it }, label = { Text("Email") }) } }, confirmButton = { Button({ if (n.isNotBlank() && e.isNotBlank()) onR(n, e) }) { Text("Register") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEntryForm(dI: List<String>, dT: List<String>, onGetSector: suspend (String) -> String, symbol: String = "रु.", onS: (TransactionRecord) -> Unit) {
    var item by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("Buy") }
    var qty by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    val cs = rememberCoroutineScope()

    var showAddItem by remember { mutableStateOf(false) }
    var showAddType by remember { mutableStateOf(false) }
    
    var expI by remember { mutableStateOf(false) }
    var expT by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expI,
                    onExpandedChange = { expI = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = item,
                        onValueChange = { 
                            item = it.uppercase()
                            if (item.length >= 2) {
                                cs.launch {
                                    val suggestedSector = onGetSector(item)
                                    if (suggestedSector != "Other") type = suggestedSector
                                }
                            }
                        },
                        label = { Text("Scrip") },
                        modifier = Modifier.fillMaxWidth().height(60.dp).menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expI) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    
                    val filtered = dI.filter { it.contains(item, true) }.take(50)
                    if (filtered.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expI,
                            onDismissRequest = { expI = false }
                        ) {
                            filtered.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = { 
                                        item = s
                                        cs.launch {
                                            val suggestedSector = onGetSector(item)
                                            if (suggestedSector != "Other") type = suggestedSector
                                        }
                                        expI = false 
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { showAddItem = true }, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
                    Icon(Icons.Default.Add, "Add Scrip", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expT,
                    onExpandedChange = { expT = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Sector") },
                        modifier = Modifier.fillMaxWidth().height(60.dp).menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expT) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    
                    val filtered = dT.filter { it.contains(type, true) }.take(50)
                    if (filtered.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expT,
                            onDismissRequest = { expT = false }
                        ) {
                            filtered.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = { type = s; expT = false }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { showAddType = true }, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
                    Icon(Icons.Default.Add, "Add Sector", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var ex by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) { 
                    OutlinedTextField(
                        value = action,
                        onValueChange = {},
                        label = { Text("Action") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(60.dp).onFocusChanged { if (it.isFocused) ex = true },
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.clickable { ex = true }) }
                    )
                    DropdownMenu(expanded = ex, onDismissRequest = { ex = false }) { 
                        listOf("Buy", "Sale", "Returns").forEach { a -> 
                            DropdownMenuItem(text = { Text(a) }, onClick = { action = a; ex = false }) 
                        } 
                    } 
                    // Transparent overlay to ensure clicks everywhere in the box open the menu
                    Box(Modifier.matchParentSize().clickable { ex = true })
                }
                OutlinedTextField(
                    value = qty, 
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) qty = it }, 
                    label = { Text("Qty") }, 
                    modifier = Modifier.weight(1f).height(60.dp), 
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), 
                    shape = RoundedCornerShape(8.dp)
                )
            }
            OutlinedTextField(
                value = amt, 
                onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amt = it }, 
                label = { Text("Amount ($symbol)") }, 
                modifier = Modifier.fillMaxWidth().height(60.dp), 
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), 
                shape = RoundedCornerShape(8.dp)
            )
            Button(
                onClick = { 
                    val q = qty.toDoubleOrNull() ?: 0.0; 
                    val a = amt.toDoubleOrNull() ?: 0.0; 
                    if (item.isNotBlank()) { 
                        onS(TransactionRecord(date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), item = item, type = type, action = action, qty = q, amount = a)); 
                        item = ""; qty = ""; amt = "" 
                    } 
                }, 
                Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(12.dp)
            ) { 
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Record Transaction", fontWeight = FontWeight.Bold) 
            }
        }
    }

    if (showAddItem) {
        var newScrip by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddItem = false },
            title = { Text("New Scrip") },
            text = { OutlinedTextField(newScrip, { newScrip = it.uppercase() }, label = { Text("Symbol") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { 
                Button({ 
                    item = newScrip
                    cs.launch {
                        val suggestedSector = onGetSector(item)
                        if (suggestedSector != "Other") type = suggestedSector
                    }
                    showAddItem = false 
                }) { Text("Set") } 
            },
            dismissButton = { TextButton({ showAddItem = false }) { Text("Cancel") } }
        )
    }

    if (showAddType) {
        var newType by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddType = false },
            title = { Text("New Sector") },
            text = { OutlinedTextField(newType, { newType = it }, label = { Text("Sector Name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button({ type = newType; showAddType = false }) { Text("Set") } },
            dismissButton = { TextButton({ showAddType = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun TransactionListItem(tx: TransactionRecord, symbol: String = "रु.", onE: () -> Unit, onD: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val clr = when(tx.action){ "Buy" -> Color(0xFF2ECE7B); "Sale" -> Color(0xFFEF4444); else -> Color(0xFF3B82F6) }
            Box(Modifier.size(36.dp).clip(CircleShape).background(clr.copy(0.1f)), contentAlignment = Alignment.Center) { Text(tx.action.take(1), color = clr, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(tx.item, fontWeight = FontWeight.Bold); if (tx.isSystemAdjustment) Icon(Icons.Default.AutoFixHigh, null, Modifier.size(12.dp), Color.Gray) }; Text("${tx.date} • ${tx.type}", fontSize = 10.sp, color = Color.Gray) }
            Column(horizontalAlignment = Alignment.End) { Text(formatCurrency(tx.amount, symbol), fontWeight = FontWeight.Bold); Text("${tx.qty} units", fontSize = 10.sp, color = Color.Gray) }
            IconButton(onClick = onE) { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
            IconButton(onClick = onD) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red) }
        }
    }
}

@Composable
fun EditTransactionDialog(r: TransactionRecord, dI: List<String>, dT: List<String>, onGetSector: suspend (String) -> String, symbol: String = "रु.", onS: (TransactionRecord) -> Unit, onD: () -> Unit) {
    var item by remember { mutableStateOf(r.item) }
    var type by remember { mutableStateOf(r.type) }
    var qty by remember { mutableStateOf(r.qty.toString()) }
    var amt by remember { mutableStateOf(r.amount.toString()) }
    val cs = rememberCoroutineScope()
    Dialog(onD) { 
        Card(Modifier.padding(16.dp), shape = RoundedCornerShape(12.dp)) { 
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { 
                Text("Edit Transaction", fontWeight = FontWeight.Bold)
                AutoCompleteTextField("Item", item, { 
                    item = it.uppercase()
                    if (item.length >= 2) {
                        cs.launch {
                            val suggestedSector = onGetSector(item)
                            if (suggestedSector != "Other") {
                                type = suggestedSector
                            }
                        }
                    }
                }, dI)
                AutoCompleteTextField("Sector", type, { type = it }, dT)
                OutlinedTextField(qty, { if (it.isEmpty() || it.toDoubleOrNull() != null) qty = it }, label = { Text("Qty") }, modifier = Modifier.fillMaxWidth().height(60.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                OutlinedTextField(amt, { if (it.isEmpty() || it.toDoubleOrNull() != null) amt = it }, label = { Text("Amount ($symbol)") }, modifier = Modifier.fillMaxWidth().height(60.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End) { 
                    TextButton(onD) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button({ 
                        val q = qty.toDoubleOrNull() ?: 0.0
                        val a = amt.toDoubleOrNull() ?: 0.0
                        onS(r.copy(item = item, type = type, qty = q, amount = a)) 
                    }) { Text("Save") } 
                } 
            } 
        } 
    }
}

@Composable
fun AutoCompleteTextField(l: String, v: String, onV: (String) -> Unit, sug: List<String>) {
    var ex by remember { mutableStateOf(false) }
    val fil = remember(v, sug) { 
        if (v.isEmpty()) sug.take(50) 
        else sug.filter { it.contains(v, true) }.take(100)
    }
    Box { 
        OutlinedTextField(
            value = v, 
            onValueChange = onV, 
            label = { Text(l) }, 
            modifier = Modifier.fillMaxWidth().height(60.dp).onFocusChanged { if (it.isFocused) ex = true }, 
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )
        DropdownMenu(
            expanded = ex && fil.isNotEmpty(), 
            onDismissRequest = { ex = false },
            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 250.dp),
            properties = androidx.compose.ui.window.PopupProperties(focusable = false)
        ) { 
            fil.forEach { s -> 
                DropdownMenuItem(
                    text = { Text(s) }, 
                    onClick = { onV(s); ex = false }
                ) 
            } 
        } 
    }
}

@Composable
fun DataScreen(viewModel: PortfolioViewModel) {
    val context = LocalContext.current
    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val dItems by viewModel.distinctItems.collectAsStateWithLifecycle()
    val dTypes by viewModel.distinctTypes.collectAsStateWithLifecycle()
    
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    
    var showImport by remember { mutableStateOf(false) }
    var csvText by remember { mutableStateOf<String?>(null) }
    var isWacc by remember { mutableStateOf(false) }
    var showMS by remember { mutableStateOf(false) }
    
    val pendingMS by viewModel.pendingMeroshareImport.collectAsStateWithLifecycle()
    var pDel by remember { mutableStateOf<TransactionRecord?>(null) }
    var pAdd by remember { mutableStateOf<TransactionRecord?>(null) }
    var pUpd by remember { mutableStateOf<TransactionRecord?>(null) }
    var editingRec by remember { mutableStateOf<TransactionRecord?>(null) }
    var filter by remember { mutableStateOf("All") }
    var expY by remember { mutableStateOf(setOf<String>()) }
    val cs = rememberCoroutineScope()

    val msLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) viewModel.prepareMeroshareImport(t) } }
    val txLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = false; showImport = true } } }
    val waLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = true; showImport = true } } }
    val exLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { if (it != null) cs.launch { 
        val allMetrics = FinancialEngines.computeItemMetrics(transactions, viewModel.allExternalLtps.value)
        val c = buildString { 
            append("Date,Item,Action,Qty,Amount,Type,Prev LTP,LTP\n")
            transactions.forEach { tx ->
                val metric = allMetrics.find { it.item.equals(tx.item, true) }
                val ltp = metric?.ltp ?: 0.0
                val prevLtp = metric?.prevLtp ?: 0.0
                append("${tx.date},${tx.item},${tx.action},${tx.qty},${tx.amount},${tx.type},$prevLtp,$ltp\n")
            } 
        }
        withContext(Dispatchers.IO) { 
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.bufferedWriter().use { writer ->
                    writer.write(c)
                }
            } 
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, "Exported with LTP", Toast.LENGTH_SHORT).show() } 
    } }

    val txY = remember(transactions, filter) { (if (filter == "All") transactions else transactions.filter { it.item.equals(filter, true) }).groupBy { it.date.split("-").firstOrNull() ?: "Unknown" }.toSortedMap(compareByDescending { it }) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Record", "Import/Export", "History")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary, divider = {}) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, fontWeight = FontWeight.Bold) })
            }
        }

        Box(Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Record Transaction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        RowEntryForm(dItems, dTypes, onGetSector = { viewModel.getSectorForScrip(it) }, symbol = symbol) { pAdd = it }
                        if (showMS) {
                            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) { 
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { 
                                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) { 
                                        Text("WACC Loaded", fontWeight = FontWeight.Bold)
                                        Text("Import Portfolio CSV to sync prices and quantities.", fontSize = 11.sp) 
                                    }
                                    Button(onClick = { msLauncher.launch("*/*") }) { Text("Import") } 
                                } 
                            }
                        }
                    }
                }
                1 -> {
                    UnifiedIOCard(txLauncher, waLauncher, msLauncher, exLauncher)
                }
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("History (${transactions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Box(Modifier.width(150.dp)) {
                                FilterDropdown(filter, dItems) { filter = it }
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            txY.forEach { (y, list) ->
                                item { YearHeader(y, list.size, expY.contains(y)) { expY = if (expY.contains(y)) expY - y else expY + y } }
                                if (expY.contains(y)) items(list) { TransactionListItem(it, symbol = symbol, onE = { editingRec = it }, onD = { pDel = it }) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showImport) AlertDialog({ showImport = false }, title = { Text("Import Mode") }, text = { Text("Append or Overwrite existing transactions?") }, confirmButton = { Button({ viewModel.importTransactions(csvText!!, false, isWacc); showImport = false; showMS = true }) { Text("Append") } }, dismissButton = { TextButton({ viewModel.importTransactions(csvText!!, true, isWacc); showImport = false; showMS = true }) { Text("Overwrite") } })
    if (editingRec != null) EditTransactionDialog(editingRec!!, dItems, dTypes, onGetSector = { viewModel.getSectorForScrip(it) }, symbol = symbol, onS = { pUpd = it; editingRec = null }, onD = { editingRec = null })
    if (pendingMS != null) AlertDialog({ viewModel.cancelMeroshareImport() }, title = { Text("Align Portfolio?") }, text = { Text("This will create ${pendingMS!!.second} new history entries (System Adjustments) to match your current Meroshare holdings. Proceed?") }, confirmButton = { Button({ viewModel.importMeroshare(pendingMS!!.first); viewModel.cancelMeroshareImport(); showMS = false }) { Text("Proceed") } }, dismissButton = { TextButton({ viewModel.cancelMeroshareImport() }) { Text("Cancel") } })
    if (pDel != null) AlertDialog({ pDel = null }, title = { Text("Confirm Deletion") }, text = { Text("Are you sure you want to permanently delete this transaction for '${pDel!!.item}'? This action cannot be undone.") }, confirmButton = { Button({ viewModel.deleteTransaction(pDel!!); pDel = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } }, dismissButton = { TextButton({ pDel = null }) { Text("Cancel") } })
    if (pAdd != null) AlertDialog({ pAdd = null }, title = { Text("Confirm Record") }, text = { Text("Add this ${pAdd!!.action} transaction for '${pAdd!!.item}' to your history?") }, confirmButton = { Button({ viewModel.addTransaction(pAdd!!); pAdd = null }) { Text("Confirm") } }, dismissButton = { TextButton({ pAdd = null }) { Text("Cancel") } })
    if (pUpd != null) AlertDialog({ pUpd = null }, title = { Text("Confirm Update") }, text = { Text("Apply changes to this transaction for '${pUpd!!.item}'?") }, confirmButton = { Button({ viewModel.updateTransaction(pUpd!!); pUpd = null }) { Text("Update") } }, dismissButton = { TextButton({ pUpd = null }) { Text("Cancel") } })
}

@Composable
fun UnifiedIOCard(tx: androidx.activity.result.ActivityResultLauncher<String>, wa: androidx.activity.result.ActivityResultLauncher<String>, ms: androidx.activity.result.ActivityResultLauncher<String>, ex: androidx.activity.result.ActivityResultLauncher<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))) {
        Column(Modifier.padding(16.dp)) {
            Text("Import/Export Management", fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            
            Text("Import Options", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            
            Button(onClick = { tx.launch("*/*") }, Modifier.fillMaxWidth()) { 
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Standard Transaction CSV")
                    Text("Fields: Date, Item, Action, Qty, Amount, Type, Prev LTP, LTP", fontSize = 9.sp, fontWeight = FontWeight.Normal)
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ wa.launch("*/*") }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary), shape = RoundedCornerShape(8.dp)) { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WACC CSV", maxLines = 1)
                        Text("Scrip, Qty, Rate, Cost", fontSize = 8.sp, fontWeight = FontWeight.Normal, maxLines = 1)
                    }
                }
                Button({ ms.launch("*/*") }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary), shape = RoundedCornerShape(8.dp)) { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Portfolio CSV", maxLines = 1)
                        Text("Scrip, Prev, LTP, Bal", fontSize = 8.sp, fontWeight = FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            
            Text("Note: Portfolio CSV is optional for standard import but mandatory after WACC import to sync current holdings.", 
                modifier = Modifier.padding(top = 12.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            
            Text("Export Options", style = MaterialTheme.typography.labelMedium, color = Color(0xFF2E7D32))
            Spacer(Modifier.height(8.dp))
            
            Button({ ex.launch("finfolio_export.csv") }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(Color(0xFF2E7D32))) { 
                Text("Export History with LTP") 
            }
        }
    }
}

@Composable
fun UserProfileScreen(vm: PortfolioViewModel, onBack: () -> Unit) {
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    var name by remember(userProfile) { mutableStateOf(userProfile?.name ?: "") }
    var email by remember(userProfile) { mutableStateOf(userProfile?.email ?: "") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader("My Profile", onBack)
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Personal Identity", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email Address") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Button(
                            onClick = { vm.registerUser(name, email) },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Save Changes")
                        }
                    }
                }
            }
            
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(0.1f))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Text("This identity data is used for local personalization and as your reply-to address when contacting support.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun PinEntryDialog(
    title: String,
    onPinEntered: (String) -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    var pin by remember { mutableStateOf("") }
    val error = remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { onDismiss?.invoke() }) {
        Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(8.dp)) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { index ->
                        val char = pin.getOrNull(index)
                        Box(
                            Modifier.size(20.dp).clip(CircleShape)
                                .background(if (char != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, if (error.value) Color.Red else Color.Transparent, CircleShape)
                        )
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                
                // Numeric Keypad
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "DEL")
                keys.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key ->
                            IconButton(
                                onClick = {
                                    when (key) {
                                        "C" -> pin = ""
                                        "DEL" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        else -> if (pin.length < 4) {
                                            pin += key
                                            if (pin.length == 4) {
                                                onPinEntered(pin)
                                                pin = "" // Reset for next use
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Text(key, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun FilterDropdown(f: String, items: List<String>, onS: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ exp = true }, Modifier.fillMaxWidth()) { Text("Scrip: $f"); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(exp, { exp = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onS("All"); exp = false })
            items.sorted().forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onS(it); exp = false }) }
        }
    }
}

@Composable
fun YearHeader(y: String, c: Int, ex: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(0.3f)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (ex) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null); Text("Year $y", fontWeight = FontWeight.Bold) }
            Badge { Text("$c tx") }
        }
    }
}

@Composable
fun SubScreenHeader(title: String, onBack: () -> Unit, trailingIcon: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (trailingIcon != null) {
            Spacer(Modifier.weight(1f))
            trailingIcon()
        }
    }
}

@Composable
fun SectorExpandableHeader(type: String, count: Int, isExpanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = type.uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.width(8.dp))
        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            Text(count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ScraperSettingsScreen(vm: PortfolioViewModel, onBack: () -> Unit) {
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val currentScrapers = userProfile?.scraperUrls ?: emptyMap()
    val cs = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Restore Defaults?") },
            text = { Text("This will immediately reset all custom scraper URLs to their factory settings. Priority lists will be restored.") },
            confirmButton = {
                Button(onClick = {
                    vm.resetAllScraperUrls()
                    showResetDialog = false
                }) { Text("Confirm Restore") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "Scraper Configuration",
            onBack = onBack,
            trailingIcon = {
                TextButton(onClick = { showResetDialog = true }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restore All", fontSize = 12.sp)
                }
            }
        )
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(0.1f)), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(0.2f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text("App tries URLs in the order listed. If the first fails, it falls back to the next.", 
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            items(ScraperCategory.values()) { category ->
                val urls = currentScrapers[category] ?: emptyList()
                
                Card(Modifier.fillMaxWidth(), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.5f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text(text = category.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(text = category.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        
                        urls.forEachIndexed { index, url ->
                            var editedUrl by remember(url) { mutableStateOf(url) }
                            var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
                            var isTesting by remember { mutableStateOf(false) }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("${index + 1}") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = editedUrl,
                                    onValueChange = { editedUrl = it },
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    trailingIcon = {
                                        if (isTesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        else IconButton(onClick = {
                                            isTesting = true
                                            cs.launch {
                                                val res = vm.testScraperUrl(category, editedUrl)
                                                isTesting = false
                                                testResult = if (res.isSuccess) true to res.getOrThrow() else false to (res.exceptionOrNull()?.message ?: "Unknown error")
                                            }
                                        }) { Icon(Icons.Default.BugReport, null, Modifier.size(16.dp)) }
                                    }
                                )
                                IconButton(onClick = {
                                    val newUrls = urls.toMutableList()
                                    newUrls.removeAt(index)
                                    vm.updateScraperUrls(category, newUrls)
                                }) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                            }
                            
                            if (editedUrl != url) {
                                TextButton(onClick = {
                                    val newUrls = urls.toMutableList()
                                    newUrls[index] = editedUrl
                                    vm.updateScraperUrls(category, newUrls)
                                }, modifier = Modifier.align(Alignment.End)) { Text("Save Change", fontSize = 10.sp) }
                            }

                            testResult?.let { (success, msg) ->
                                Text(msg, color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 9.sp, modifier = Modifier.padding(start = 32.dp, bottom = 4.dp))
                            }
                        }
                        
                        Button(
                            onClick = {
                                val newUrls = urls.toMutableList() + ""
                                vm.updateScraperUrls(category, newUrls)
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add URL / Fallback", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun DeveloperProfilePanel(
    userName: String,
    userEmail: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var customMessage by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        SubScreenHeader("Support", onBack)
        
        Column(Modifier.weight(1f).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = "Kedar Bhandari",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Lead Developer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "GitHub", 
                            fontSize = 11.sp, 
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bkedarnp"))) }
                        )
                        Text(
                            "Email", 
                            fontSize = 11.sp, 
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:bkedarnp@gmail.com"))) }
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(bottom = 16.dp), thickness = 0.5.dp)

            Text(
                text = "Direct Support",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = customMessage,
                onValueChange = { customMessage = it },
                label = { Text("Message", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf("bkedarnp@gmail.com"))
                        putExtra(Intent.EXTRA_SUBJECT, "[FinFolio Support] $subject")
                        val body = "User: $userName ($userEmail)\n\nMessage:\n$customMessage\n\n---\nSystem: Android ${android.os.Build.VERSION.RELEASE}"
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    context.startActivity(Intent.createChooser(intent, "Choose Email Client"))
                },
                enabled = customMessage.isNotBlank() && subject.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Submit", fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun FinanceCalculatorScreen(onBack: () -> Unit) {
    var display by remember { mutableStateOf("0") }
    var operator by remember { mutableStateOf<String?>(null) }
    var operand1 by remember { mutableDoubleStateOf(0.0) }
    var isNewNumber by remember { mutableStateOf(true) }

    fun onDigit(digit: String) {
        if (isNewNumber) {
            display = digit
            isNewNumber = false
        } else {
            if (display == "0") display = digit else display += digit
        }
    }

    fun onOperator(op: String) {
        val current = display.toDoubleOrNull() ?: 0.0
        if (operator != null && !isNewNumber) {
            val result = when (operator) {
                "+" -> operand1 + current
                "-" -> operand1 - current
                "*" -> operand1 * current
                "/" -> if (current != 0.0) operand1 / current else 0.0
                else -> current
            }
            operand1 = result
            display = if (result % 1.0 == 0.0) result.toInt().toString() else String.format(Locale.US, "%.2f", result)
        } else {
            operand1 = current
        }
        operator = op
        isNewNumber = true
    }

    fun onEquals() {
        val current = display.toDoubleOrNull() ?: 0.0
        if (operator != null) {
            val result = when (operator) {
                "+" -> operand1 + current
                "-" -> operand1 - current
                "*" -> operand1 * current
                "/" -> if (current != 0.0) operand1 / current else 0.0
                else -> current
            }
            display = if (result % 1.0 == 0.0) result.toLong().toString() else String.format(Locale.US, "%.2f", result)
            operator = null
            isNewNumber = true
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader("Calculator", onBack)
        
        Card(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = display,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val buttons = listOf(
            listOf("C", "DEL", "/", "*"),
            listOf("7", "8", "9", "-"),
            listOf("4", "5", "6", "+"),
            listOf("1", "2", "3", "="),
            listOf("0", ".")
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { label ->
                        val isOp = label in listOf("/", "*", "-", "+", "=")
                        val isSpec = label in listOf("C", "DEL")
                        val weight = if (label == "0") 2f else 1f
                        
                        Button(
                            onClick = {
                                when {
                                    label in "0123456789" -> onDigit(label)
                                    label == "." -> if (!display.contains(".")) display += "."
                                    label == "C" -> { display = "0"; operator = null; operand1 = 0.0; isNewNumber = true }
                                    label == "DEL" -> if (display.length > 1) display = display.dropLast(1) else display = "0"
                                    label == "=" -> onEquals()
                                    else -> onOperator(label)
                                }
                            },
                            modifier = Modifier.weight(weight).height(64.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    isOp -> MaterialTheme.colorScheme.primary
                                    isSpec -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = when {
                                    isOp -> MaterialTheme.colorScheme.onPrimary
                                    isSpec -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        ) {
                            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (row.size < 4 && row.contains("0")) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
