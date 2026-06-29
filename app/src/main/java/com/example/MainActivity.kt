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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.db.AppDatabase
import com.example.data.db.TransactionRecord
import com.example.data.db.ScripMaster
import com.example.data.db.IpoMaster
import com.example.data.model.*
import com.example.data.repository.*
import com.example.data.util.AppLogger
import com.example.data.work.ScrapeWorker
import com.example.ui.components.HybridIpoResultChecker
import com.example.ui.components.MigrationSafetyScreen
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
fun MarketPillBadge(index: String, value: Double, pct: Double, status: String) {
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
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (isOpen) Color(0xFF10B981).copy(alpha = alpha) else Color(0xFFEF4444)))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = index, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(text = String.format(Locale.US, "%,.1f (%+.2f%%)", value, pct), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = baseColor)
            }
        }
    }
}

/**
 * MainActivity: Primary entry point for FinFolio.
 * 
 * UI FLOW:
 * 1. App initialization via [onCreate].
 * 2. Database state check in [PortfolioAppContent] (triggers [MigrationSafetyScreen] rebranding as "Finalizing Setup" if needed).
 * 3. Main navigation using [HorizontalPager] for DASHBOARD, MATRIX, DATA, and MORE tabs.
 * 
 * UX DESIGN PRINCIPLES:
 * - Minimalist technical jargon (e.g., using "App optimization" instead of "V2 Migration").
 * - Responsive feedback via [SnackbarHost] and [Toast].
 * - Immediate calculation of holdings on data import to maintain UI snappiness.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = AppDatabase.getDatabase(this)
        AppLogger.init(database.appLogDao())

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val database = remember { AppDatabase.getDatabase(context) }
                val repository = remember { PortfolioRepository(database.portfolioDao(), database.ipoMasterDao()) }
                val marketRepo = remember { MarketRepository(database.portfolioDao()) }
                val ipoRepo = remember { IpoRepository(database.portfolioDao(), database.ipoMasterDao()) }
                val msRepo = remember { MeroShareRepository(database.portfolioDao()) }

                val portfolioVM: PortfolioViewModel = viewModel(factory = PortfolioViewModelFactory(repository))
                val marketVM: MarketViewModel = viewModel(factory = MarketViewModelFactory(marketRepo, repository))
                val ipoVM: BulkIpoViewModel = viewModel(factory = BulkIpoViewModelFactory(ipoRepo, msRepo, marketRepo))

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
    val marketStatus by viewModel.marketStatus.collectAsStateWithLifecycle()
    val primaryIndexName = userProfile?.primaryIndexName ?: "NEPSE Index"
    val primaryIndex = remember(indices, primaryIndexName) { 
        indices.find { it.index.equals(primaryIndexName, true) } 
            ?: indices.find { it.index.contains(primaryIndexName, true) }
            ?: com.example.data.repository.MarketIndex(primaryIndexName, 0.0, 0.0, 0.0)
    }
    val pendingSectorUpdate by viewModel.pendingSectorUpdate.collectAsStateWithLifecycle()
    var showReg by remember { mutableStateOf(false) }
    var currentSubView by remember { mutableStateOf<String?>(null) }
    var isUnlocked by remember { mutableStateOf(false) }

    val isMigrationRequired = false // Discarded as per user request
    var backupCompleted by remember { mutableStateOf(false) }
    var testCompleted by remember { mutableStateOf(false) }
    var migrationStatusMsg by remember { mutableStateOf("") }

    /** 
     * UI FLOW: Migration Safety / App Optimization - REMOVED
     */

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

    if (pendingSectorUpdate != null) {
        AlertDialog(onDismissRequest = { viewModel.confirmSectorUpdate(pendingSectorUpdate!!, false) }, title = { Text("Sync Sector?") }, text = { Text("Update Sector to '${pendingSectorUpdate!!.sector}' for all '${pendingSectorUpdate!!.item}' records?") }, confirmButton = { Button(onClick = { viewModel.confirmSectorUpdate(pendingSectorUpdate!!, true) }) { Text("Yes") } }, dismissButton = { TextButton(onClick = { viewModel.confirmSectorUpdate(pendingSectorUpdate!!, false) }) { Text("No") } })
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState(initial = null)
    
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState, 
        gesturesEnabled = true, // Enabled for "Slide to Close" functionality
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
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { cs.launch { drawerState.open() } }) { Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }; Column(Modifier.padding(start = 8.dp)) { Text("FinFolio Pro", fontWeight = FontWeight.Bold); Text("PortFolio Tracker", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) } } }, actions = { 
                MarketPillBadge(primaryIndex.index, primaryIndex.value, primaryIndex.percentChange, marketStatus.status)

                IconButton(onClick = {
                viewModel.refreshLivePrices()
                marketViewModel.refreshMarketData()
                ipoViewModel.refreshAllCompanies()
                Toast.makeText(context, "Refreshing all data...", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Refresh, null) } }) },
            bottomBar = { NavigationBar { tabs.forEachIndexed { i, tab -> NavigationBarItem(selected = pagerState.currentPage == i, onClick = { if (tab == NavigationTab.MORE) currentSubView = null; cs.launch { pagerState.animateScrollToPage(i) } }, label = { Text(tab.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }) }, icon = { Icon(when(tab){ NavigationTab.DASHBOARD -> Icons.Default.Dashboard; NavigationTab.MATRIX -> Icons.Default.TableChart; NavigationTab.DATA -> Icons.AutoMirrored.Filled.Input; NavigationTab.MORE -> Icons.Default.MoreHoriz }, null) }) } } }
        ) { inner ->
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(inner), userScrollEnabled = true) { page ->
                when (tabs[page]) {
                    NavigationTab.DASHBOARD -> DashboardScreen(viewModel)
                    NavigationTab.MATRIX -> MatrixScreen(viewModel)
                    NavigationTab.DATA -> DataScreen(viewModel)
                    NavigationTab.MORE -> MoreScreen(marketViewModel, viewModel, ipoViewModel, currentSubView, { currentSubView = it }, onNavigateToData = { cs.launch { pagerState.animateScrollToPage(2) } })
                }
            }
        }
    }
    if (showReg) RegistrationDialog { n, e, b -> viewModel.registerUser(n, e, b); showReg = false }
}

@Composable
fun GlobalProfileDrawer(user: com.example.data.model.UserProfile?, symbol: String = "रु.", onSupport: () -> Unit, onSettings: () -> Unit, onProfile: () -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(0.4f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape, 
                    color = MaterialTheme.colorScheme.primary, 
                    modifier = Modifier.size(56.dp),
                    shadowElevation = 4.dp
                ) { 
                    Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.padding(14.dp)) 
                }
                
                Spacer(Modifier.width(16.dp))
                
                Column(Modifier.weight(1f)) { 
                    Text(user?.name ?: "Guest User", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(user?.email ?: "local@finfolio.app", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                IconButton(
                    onClick = onClose, 
                    modifier = Modifier.size(36.dp)
                ) { 
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) 
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        DrawerItem(Icons.Default.AccountCircle, "View Profile") { onProfile() }
        DrawerItem(Icons.Default.Settings, "Settings") { onSettings() }
        DrawerItem(Icons.AutoMirrored.Filled.HelpCenter, "Get Support") { onSupport() }
        
        Spacer(Modifier.weight(1f))
        
        // Quick Settings Info in Drawer
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Currency: $symbol", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Format: ${user?.dateFormat ?: "AD"}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        DrawerItem(Icons.AutoMirrored.Filled.ExitToApp, "Exit", MaterialTheme.colorScheme.error) {
            (context as? android.app.Activity)?.finish()
        }
        Text("Version 1.1 (DB 15)", Modifier.padding(16.dp).align(Alignment.CenterHorizontally), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DrawerItem(i: androidx.compose.ui.graphics.vector.ImageVector, l: String, c: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit = {}) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(i, null, tint = c); Spacer(Modifier.width(16.dp)); Text(l, color = c) } }
}

@Composable
fun MoreScreen(marketVM: MarketViewModel, portfolioVM: PortfolioViewModel, ipoVM: BulkIpoViewModel, subView: String?, onSubViewChange: (String?) -> Unit, onNavigateToData: () -> Unit) {
    val userProfile by portfolioVM.userProfile.collectAsStateWithLifecycle()

    when (subView) {
        "Market" -> MarketScreen(marketVM, portfolioVM) { onSubViewChange(null) }
        "BulkCheck" -> IpoCheckScreen(ipoVM, portfolioVM, onNavigateToData) { onSubViewChange(null) }
        "BulkApply" -> IpoApplyScreen(ipoVM, portfolioVM) { onSubViewChange(null) }
        "IpoMaster" -> CompaniesScreen(ipoVM) { onSubViewChange(null) }
        "Settings" -> SettingsScreen(portfolioVM, marketVM) { onSubViewChange(null) }
        "Scraper" -> ScraperSettingsScreen(portfolioVM) { onSubViewChange(null) }
        "Contact" -> DeveloperProfilePanel(userProfile?.name ?: "User", userProfile?.email ?: "") { onSubViewChange(null) }
        "Profile" -> UserProfileScreen(portfolioVM) { onSubViewChange(null) }
        "Calculator" -> FinanceCalculatorScreen { onSubViewChange(null) }
        "Vault" -> CredentialVaultScreen(ipoVM, portfolioVM) { onSubViewChange(null) }
        else -> {
            val ipos by ipoVM.ipos.collectAsStateWithLifecycle()
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { Text("Utilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { 
                    MoreGrid(
                        { MoreCard("Markets", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF10B981)) { onSubViewChange("Market") } }, 
                        { MoreCard("IPO Check", Icons.AutoMirrored.Filled.FactCheck, Color(0xFFEF4444)) { onSubViewChange("BulkCheck") } }, 
                        { MoreCard("IPO Apply", Icons.AutoMirrored.Filled.Send, Color(0xFF3B82F6)) { onSubViewChange("BulkApply") } },
                        { MoreCard("Companies (${ipos.size})", Icons.Default.Inventory, Color(0xFF8B5CF6)) { onSubViewChange("IpoMaster") } },
                        { MoreCard("Vault", Icons.Default.VpnKey, Color(0xFF6366F1)) { onSubViewChange("Vault") } },
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
    val indices by vm.filteredIndices.collectAsStateWithLifecycle()
    val changes by vm.priceChanges.collectAsStateWithLifecycle()
    val allIdx by vm.indices.collectAsStateWithLifecycle()
    val visIdx by vm.visibleIndices.collectAsStateWithLifecycle()
    val items by pvm.dashboardItemMetrics.collectAsStateWithLifecycle()
    val wishMovers by vm.watchlistMovers.collectAsStateWithLifecycle()
    val pSyms = remember(items) { items.filter { it.balanceQty > 0.0 }.map { it.item.uppercase() }.toSet() }
    val userProfile by pvm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    val cs = rememberCoroutineScope()
    val context = LocalContext.current
    
    var showSch by remember { mutableStateOf(false) }; var showCfg by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf("Global") } // "Global" or "ScripOnly"

    var expInd by remember { mutableStateOf(false) }
    var expHol by remember { mutableStateOf(false) }
    var expWis by remember { mutableStateOf(false) }
    
    var hSectorFilter by remember { mutableStateOf("All") }
    val hM = changes.filter { it.symbol in pSyms }
    val hSectorCounts = remember(hM, items) { hM.groupBy { m -> items.find { it.item == m.symbol }?.sector ?: "Other" }.mapValues { it.value.size } }
    val hSectors = remember(hM, items) { (listOf("All") + items.filter { it.balanceQty > 0.0 }.map { it.sector }.distinct().sorted()) }

    val filteredHM = remember(hM, items, hSectorFilter) {
        if (hSectorFilter == "All") hM
        else hM.filter { m -> items.find { it.item == m.symbol }?.sector == hSectorFilter }
    }
    
    val wM = wishMovers.filter { it.symbol !in pSyms }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            cs.launch {
                val csvContent = buildString {
                    append("Scrip,Sector,Qty,Previous LTP,Previous Amount,LTP,Current Amount\n")
                    items.filter { it.balanceQty > 0.0 }.forEach { met ->
                        val live = changes.find { it.symbol.equals(met.item, true) }
                        val ltp = live?.ltp ?: met.ltp
                        val prevLtp = live?.previousLtp ?: met.ltp
                        val prevAmt = String.format(Locale.US, "%.2f", met.balanceQty * prevLtp)
                        val currAmt = String.format(Locale.US, "%.2f", met.balanceQty * ltp)
                        append("${met.item},${met.sector},${met.balanceQty},${prevLtp},${prevAmt},${ltp},${currAmt}\n")
                    }
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csvContent.toByteArray()) }
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Holdings exported successfully", Toast.LENGTH_SHORT).show() }
            }
        }
    }

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
                    Box(modifier = Modifier.weight(1f)) { MarketIndexCard(row[0]) }
                    if (row.size > 1) {
                        Box(modifier = Modifier.weight(1f)) { MarketIndexCard(row[1]) }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item { 
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                ExpandableHeader("My Holdings", filteredHM.size, expHol, { expHol = !expHol }, Color(0xFF10B981))
                
                Spacer(Modifier.weight(1f))
                
                var expS by remember { mutableStateOf(false) }
                Box {
                    val currentHCount = if (hSectorFilter == "All") hM.size else hSectorCounts[hSectorFilter] ?: 0
                    TextButton(onClick = { expS = true }, modifier = Modifier.height(36.dp)) {
                        Text("$hSectorFilter ($currentHCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.FilterList, null, Modifier.size(18.dp).padding(start = 4.dp))
                    }
                    DropdownMenu(expS, { expS = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                        hSectors.forEach { s ->
                            val count = if (s == "All") hM.size else hSectorCounts[s] ?: 0
                            DropdownMenuItem(text = { Text("$s ($count)", fontSize = 12.sp) }, onClick = { hSectorFilter = s; expS = false })
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = { exportLauncher.launch("holdings_export.csv") }) { 
                    Icon(Icons.Default.FileUpload, "Export Holdings", Modifier.size(20.dp), tint = Color.Gray) 
                }
            }
        }
        if (expHol) {
            val (gainers, losers) = filteredHM.partition { it.change >= 0 }
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
        item { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExpandableHeader("Watchlist", wM.size, expWis, { expWis = !expWis }, Color(0xFFF59E0B), Modifier.weight(1f))
                IconButton(onClick = { searchMode = "ScripOnly"; showSch = true }) { Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = Color.Gray) }
            }
        }
        if (expWis) {
            val (gainers, losers) = wM.partition { it.change >= 0 }
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
                        sortedGainers.getOrNull(i)?.let { MoverCard(it, false, symbol = symbol, compact = true) }
                    }
                    Box(Modifier.weight(1f)) {
                        sortedLosers.getOrNull(i)?.let { MoverCard(it, false, symbol = symbol, compact = true) }
                    }
                }
            }
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
    if (showCfg) IndicesConfigDialog(
        all = allIdx, 
        vis = visIdx, 
        primaryIndexName = userProfile?.primaryIndexName ?: "NEPSE Index",
        onT = { vm.toggleIndexVisibility(it) },
        onSetAll = { vm.setAllIndicesVisible(it) }
    ) { showCfg = false }
}

@Composable
fun ExpandableHeader(t: String, c: Int, ex: Boolean, onT: () -> Unit, clr: Color, modifier: Modifier = Modifier) { 
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.clickable { onT() }.padding(vertical = 4.dp)) { 
        Icon(if (ex) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = clr)
        Text("$t ($c)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
    } 
}

@Composable
fun MarketIndexCard(idx: com.example.data.repository.MarketIndex) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text(idx.index, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(String.format(Locale.US, "%,.1f", idx.value), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1)
            val c = if (idx.percentChange >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Text(String.format(Locale.US, "%+.2f (%+.2f%%)", idx.change, idx.percentChange), color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
                    if (!compact) {
                        Spacer(Modifier.width(4.dp))
                        val badgeColor = if (m.source == "Scraped") Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                        val textColor = if (m.source == "Scraped") Color(0xFF2E7D32) else Color(0xFF1565C0)
                        Badge(containerColor = badgeColor) {
                            Text(if (m.source == "Scraped") "Live" else m.source, fontSize = 8.sp, color = textColor)
                        }
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
    indices: List<com.example.data.repository.MarketIndex>,
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

    val totalScripCount = remember(q, combinedScrips) {
        if (q.isBlank()) combinedScrips.size
        else combinedScrips.count { it.symbol.contains(q, true) || it.name.contains(q, true) }
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
                    placeholder = { Text("Search scrips...") },
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
                        item { 
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "SCRIPS ($totalScripCount)", 
                                fontSize = 12.sp, 
                                fontWeight = FontWeight.Bold, 
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ) 
                        }
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
fun IndicesConfigDialog(all: List<com.example.data.repository.MarketIndex>, vis: Set<String>, primaryIndexName: String, onT: (String) -> Unit, onSetAll: (Set<String>) -> Unit, onD: () -> Unit) {
    val sortedAll = remember(all, primaryIndexName) {
        val pName = primaryIndexName.ifBlank { "NEPSE Index" }
        
        // 1. Find the best match for primary
        val primary = all.find { it.index.equals(pName, true) }
            ?: all.find { it.index.contains(pName, true) }
            ?: com.example.data.repository.MarketIndex(pName, 0.0, 0.0, 0.0)

        // 2. Filter out anything that is essentially the primary
        val others = all.filter { 
            val isExactPrimary = it.index.equals(primary.index, true)
            val isSimilarToPrimary = it.index.equals(pName, true) || (pName.length > 3 && it.index.contains(pName, true))
            !isExactPrimary && !isSimilarToPrimary
        }.sortedBy { it.index }

        // 3. Combine and final deduplication by name
        (listOf(primary) + others).distinctBy { it.index.lowercase().trim() }
    }

    Dialog(onDismissRequest = onD) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Select Indices (${sortedAll.size})", fontWeight = FontWeight.Bold)
                
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSetAll(sortedAll.map { it.index }.toSet()) }, modifier = Modifier.weight(1f)) {
                        Text("Select All", fontSize = 11.sp)
                    }
                    TextButton(onClick = { onSetAll(setOf()) }, modifier = Modifier.weight(1f)) {
                        Text("Unselect All", fontSize = 11.sp)
                    }
                }

                LazyColumn(Modifier.weight(1f, false).padding(top = 4.dp).heightIn(max = 240.dp)) { 
                    items(sortedAll) { idx -> 
                        val isPrimary = idx.index.equals(sortedAll.first().index, true)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = !isPrimary) { onT(idx.index) }
                                .padding(vertical = 4.dp), 
                            verticalAlignment = Alignment.CenterVertically
                        ) { 
                            Checkbox(
                                checked = vis.contains(idx.index) || isPrimary, 
                                onCheckedChange = if (isPrimary) null else { _ -> onT(idx.index) },
                                enabled = !isPrimary
                            )
                            Text(
                                text = if (isPrimary) "${idx.index} (Primary)" else idx.index,
                                color = if (isPrimary) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        } 
                    } 
                }
                TextButton(onClick = onD, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@Composable
fun PortalIdEditDialog(
    companyName: String,
    initialId: String,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var tempId by remember { mutableStateOf(initialId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Result Portal ID") },
        text = {
            Column {
                Text("Enter the numeric ID for $companyName", fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = tempId,
                    onValueChange = { if (it.all { c -> c.isDigit() }) tempId = it },
                    label = { Text("Company ID") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                tempId.toIntOrNull()?.let { onSave(it) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}



/**
 * IpoCheckScreen: UX refined to prioritize the BOID viewing area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpoCheckScreen(vm: BulkIpoViewModel, portfolioVM: PortfolioViewModel, onNavigateToData: () -> Unit, onBack: () -> Unit) {
    val userProfile by portfolioVM.userProfile.collectAsStateWithLifecycle()
    val ipos by vm.checkIpos.collectAsStateWithLifecycle()
    val sel by vm.selectedIpo.collectAsStateWithLifecycle()
    val boids by vm.boids.collectAsStateWithLifecycle()
    val hybridBoids by vm.hybridBoids.collectAsStateWithLifecycle()
    val isC by vm.isChecking.collectAsStateWithLifecycle()
    val isHybrid by vm.isHybridChecking.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    val syncLog by vm.syncLog.collectAsStateWithLifecycle()
    val syncMsg by vm.syncMessage.collectAsStateWithLifecycle()
    
    // Auto-select the first available result IPO if the current selection is null or not in the result list
    LaunchedEffect(ipos, sel) {
        if (ipos.isNotEmpty() && (sel == null || !ipos.any { it.companyName == sel!!.companyName })) {
            vm.selectIpo(ipos.first())
        }
    }

    if (isHybrid && sel != null && hybridBoids.isNotEmpty()) {
        Dialog(
            onDismissRequest = { vm.finishHybridCheck() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.65f).padding(4.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.AutoMode, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(8.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Official CDSC Portal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        IconButton(onClick = { vm.finishHybridCheck() }, modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), CircleShape)) {
                            Icon(Icons.Default.Close, "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val portalUrl = userProfile?.scraperUrls?.get(ScraperCategory.IPO_RESULT)?.firstOrNull() ?: "https://iporesult.cdsc.com.np/"
                        
                        HybridIpoResultChecker(
                            companyName = sel!!.companyName,
                            boids = hybridBoids,
                            portalUrl = portalUrl,
                            onResultFound = { entry, msg, success -> vm.onHybridResultReceived(entry, msg, success) },
                            onComplete = { vm.finishHybridCheck() }
                        )
                    }
                }
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(syncMsg) {
        syncMsg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val filteredIpos = remember(searchQuery, ipos) {
        if (searchQuery.isBlank()) ipos
        else ipos.filter { 
            it.companyName.contains(searchQuery, true) || 
            it.scrip?.contains(searchQuery, true) == true ||
            it.resultPortalId?.toString()?.contains(searchQuery) == true
        }
    }

    val act by vm.memberActivity.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Results?") },
            text = { Text("This will clear the cached allotment status for all enabled family members for this specific company. Are you sure?") },
            confirmButton = {
                TextButton(onClick = { vm.resetAllResults(); showResetDialog = false }) {
                    Text("RESET ALL", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        SubScreenHeader(
            title = "IPO Check (${ipos.size})",
            onBack = onBack
        )
        
            ExposedDropdownMenuBox(exp, { exp = it }) {
                OutlinedTextField(
                    value = if (exp) searchQuery else (sel?.companyName ?: ""),
                    onValueChange = { searchQuery = it; exp = true },
                    label = { Text("Select Company") },
                    placeholder = { Text(sel?.companyName ?: "Search to check result...") },
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                    trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotEmpty()) { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null, Modifier.size(18.dp)) } }
                        ExposedDropdownMenuDefaults.TrailingIcon(exp)
                    }},
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(exp, { exp = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    filteredIpos.take(100).forEach { ipo ->
                        DropdownMenuItem(
                            text = { 
                                Column {
                                    Text(ipo.companyName, fontWeight = FontWeight.Bold)
                                    Row {
                                        if (!ipo.scrip.isNullOrBlank()) Text(ipo.scrip, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Allotment: ${ipo.allotmentDate ?: "N/A"}", fontSize = 10.sp)
                                    }
                                }
                            },
                            onClick = { vm.selectIpo(ipo); searchQuery = ""; exp = false }
                        )
                    }
                }
            }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.startAutoCheck() },
                modifier = Modifier.fillMaxWidth().height(40.dp),
                enabled = !isC && boids.any { !it.msPassword.isNullOrBlank() } && sel != null,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC1CC), contentColor = Color(0xFFD81B60))
            ) {
                if (isC && !isHybrid) CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFFD81B60))
                else { 
                    Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Auto Check (Vault Members)", fontSize = 12.sp, fontWeight = FontWeight.Bold) 
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text("Family Members (${boids.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showResetDialog = true }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text("RESET ALL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = { vm.toggleAllBoids(true, true) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text("ALL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                TextButton(onClick = { vm.toggleAllBoids(false, true) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text("NONE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 4.dp)) {
            item {
                InfoBanner("Check results for all members using 'Check via CDSC Portal' on each card. 'Auto Check' is available only for members with saved MeroShare credentials.")
            }
                    items(boids, key = { it.boid }) { boid -> 
                val activity = act.find { it.boid == boid.boid }
                BoidItem(
                    b = boid, 
                    isEnabled = boid.isEnabledForCheck, 
                    activity = activity, 
                    isMe = userProfile?.boid == boid.boid,
                    onToggle = { vm.toggleBoidEnabled(boid.boid, true) }, 
                    onReset = { vm.resetAllotment(boid.boid) },
                    onCheckThroughCdsc = { vm.startIndividualHybridCheck(boid) },
                    onAddTransaction = if (userProfile?.boid == boid.boid) {
                        { units ->
                            portfolioVM.setPendingTransaction(
                                TransactionRecord(
                                    date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
                                    item = sel?.scrip ?: sel?.companyName ?: "",
                                    sector = "Other",
                                    action = "Buy",
                                    qty = units.toDouble(),
                                    amount = units.toDouble() * 100.0
                                )
                            )
                            onNavigateToData()
                        }
                    } else null
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpoApplyScreen(vm: BulkIpoViewModel, portfolioVM: PortfolioViewModel, onBack: () -> Unit) {
    val userProfile by portfolioVM.userProfile.collectAsStateWithLifecycle()
    val ipos by vm.applyIpos.collectAsStateWithLifecycle()
    val sel by vm.selectedIpo.collectAsStateWithLifecycle()
    val boids by vm.verifiedApplyBoids.collectAsStateWithLifecycle()
    val isC by vm.isChecking.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    
    val act by vm.memberActivity.collectAsStateWithLifecycle()

    // Auto-select the first available Open IPO if the current selection is null or not in the apply list
    LaunchedEffect(ipos, sel) {
        if (ipos.isNotEmpty() && (sel == null || !ipos.any { it.companyName == sel!!.companyName })) {
            vm.selectIpo(ipos.first())
        }
    }

    var showApplyDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val filteredIpos = remember(searchQuery, ipos) {
        if (searchQuery.isBlank()) ipos
        else ipos.filter { it.companyName.contains(searchQuery, true) || it.scrip?.contains(searchQuery, true) == true }
    }

    val isApplyEnabled = sel != null && !sel!!.resultPortalId?.toString().isNullOrBlank()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "IPO Apply (${ipos.size})",
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { vm.refreshAllCompanies() }, enabled = !isS) {
                    if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null)
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        ExposedDropdownMenuBox(exp, { exp = it }) {
            OutlinedTextField(
                value = if (exp) searchQuery else (sel?.companyName ?: ""),
                onValueChange = { searchQuery = it; exp = true },
                label = { Text("Select Open IPO") },
                placeholder = { Text(sel?.companyName ?: "Search to apply...") },
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(exp, { exp = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                filteredIpos.forEach { ipo ->
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text(ipo.companyName, fontWeight = FontWeight.Bold)
                                Row {
                                    if (!ipo.scrip.isNullOrBlank()) Text(ipo.scrip, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Closes: ${ipo.closingDate ?: "N/A"}", fontSize = 10.sp)
                                }
                            }
                        },
                        onClick = { vm.selectIpo(ipo); searchQuery = ""; exp = false }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var showIndividualApply by remember { mutableStateOf(false) }
            
            Button(
                onClick = { showIndividualApply = true },
                modifier = Modifier.weight(1.1f).height(48.dp),
                enabled = !isC && boids.isNotEmpty() && sel != null,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Language, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Apply Through CDSC", fontSize = 10.sp, fontWeight = FontWeight.Bold, softWrap = false)
            }
            
            Button(
                onClick = { showApplyDialog = true },
                modifier = Modifier.weight(0.9f).height(48.dp),
                enabled = !isC && boids.isNotEmpty() && sel != null && isApplyEnabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
            ) {
                if (isC) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                else {
                    Icon(Icons.Default.AutoFixHigh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Auto Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold, softWrap = false)
                }
            }

            if (showIndividualApply) {
                Dialog(onDismissRequest = { showIndividualApply = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.85f).padding(4.dp), shape = RoundedCornerShape(24.dp)) {
                        Column {
                            TopAppBar(
                                title = { Text("MeroShare Web Portal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                                navigationIcon = { IconButton(onClick = { showIndividualApply = false }) { Icon(Icons.Default.Close, null) } }
                            )
                            Box(Modifier.weight(1f)) {
                                val msPortalUrl = userProfile?.scraperUrls?.get(ScraperCategory.MEROSHARE_WEB)?.firstOrNull() ?: "https://meroshare.cdsc.com.np/#/asba"
                                AndroidView(
                                    factory = { context ->
                                        android.webkit.WebView(context).apply {
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            webViewClient = android.webkit.WebViewClient()
                                            loadUrl(msPortalUrl)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showApplyDialog) {
            var units by remember { mutableStateOf("10") }
            val enabledCount = boids.count { vm.enabledBoids[it.boid] ?: it.isEnabledForApply }
            AlertDialog(
                onDismissRequest = { showApplyDialog = false },
                title = { Text("Auto IPO Apply") },
                text = {
                    Column {
                        Text("Applying for ${sel?.companyName}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = units, 
                            onValueChange = { units = it }, 
                            label = { Text("Units (Kitta)") }, 
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                        )
                        Text("Accounts Selected: $enabledCount", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                },
                confirmButton = { 
                    Button(
                        onClick = { 
                            vm.startAutoApply(units.toIntOrNull() ?: 10)
                            showApplyDialog = false 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) { Text("Proceed Apply") } 
                },
                dismissButton = { TextButton({ showApplyDialog = false }) { Text("Cancel") } }
            )
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { 
            Text("Family Members (${boids.size})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.toggleAllBoids(true, false) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text("ALL", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
                TextButton(onClick = { vm.toggleAllBoids(false, false) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                    Text("NONE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) { 
            items(boids, key = { it.boid }) { boid -> 
                BoidItem(
                    b = boid, 
                    isEnabled = boid.isEnabledForApply, 
                    activity = act.find { it.boid == boid.boid },
                    isMe = userProfile?.boid == boid.boid,
                    onToggle = { vm.toggleBoidEnabled(boid.boid, false) },
                    onMarkApplied = { vm.markAsApplied(boid.boid) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CompaniesScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val ipos by vm.ipos.collectAsStateWithLifecycle()
    val dps by vm.allDps.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    val syncLog by vm.syncLog.collectAsStateWithLifecycle()
    val syncMsg by vm.syncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    
    var ipoSearchQuery by remember { mutableStateOf("") }
    var dpSearchQuery by remember { mutableStateOf("") }

    LaunchedEffect(syncMsg) {
        syncMsg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val displayIpos = remember(ipoSearchQuery, ipos) {
        if (ipoSearchQuery.isBlank()) ipos
        else ipos.filter { 
            it.companyName.contains(ipoSearchQuery, true) || 
            it.scrip?.contains(ipoSearchQuery, true) == true
        }
    }

    val displayDps = remember(dpSearchQuery, dps) {
        if (dpSearchQuery.isBlank()) dps
        else dps.filter { it.name.contains(dpSearchQuery, true) || it.dpCode.contains(dpSearchQuery) }
    }

    // Accordion State
    var expandedTopSection by remember { mutableStateOf<String?>("IPO") } // "IPO" or "DP"
    var expandedSubSection by remember { mutableStateOf<String?>("OPEN") } // "UPCOMING", "OPEN", "CLOSED", "ALLOTTED", "PREVIOUS"

    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val weekAgo = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)) }

    val upcoming = remember(displayIpos, today) { 
        displayIpos.filter { !it.openingDate.isNullOrBlank() && it.openingDate > today }
            .sortedBy { it.openingDate }
    }

    val openIssues = remember(displayIpos, upcoming) { 
        displayIpos.filter { 
            it !in upcoming && (it.status.equals("Open", true) || it.status.equals("Ongoing", true) || it.status.equals("Applying", true)) 
        }.sortedByDescending { it.closingDate ?: "" }
    }

    val allotmentCompleted = remember(displayIpos, today, weekAgo) { 
        displayIpos.filter { 
            !it.allotmentDate.isNullOrBlank() && it.allotmentDate >= weekAgo && it.allotmentDate <= today
        }.sortedByDescending { it.closingDate ?: "" }
    }

    val previousIssues = remember(displayIpos, weekAgo) { 
        displayIpos.filter { 
            !it.allotmentDate.isNullOrBlank() && it.allotmentDate < weekAgo 
        }.sortedByDescending { it.closingDate ?: "" }
    }

    val closedIssues = remember(displayIpos, allotmentCompleted, previousIssues) { 
        displayIpos.filter { 
            it.status.equals("Closed", true) && it !in allotmentCompleted && it !in previousIssues
        }.sortedByDescending { it.closingDate ?: "" }
    }

    // Auto-locate and expand on search
    LaunchedEffect(ipoSearchQuery) {
        if (ipoSearchQuery.isNotBlank()) {
            when {
                openIssues.isNotEmpty() -> expandedSubSection = "OPEN"
                upcoming.isNotEmpty() -> expandedSubSection = "UPCOMING"
                allotmentCompleted.isNotEmpty() -> expandedSubSection = "ALLOTTED"
                closedIssues.isNotEmpty() -> expandedSubSection = "CLOSED"
                previousIssues.isNotEmpty() -> expandedSubSection = "PREVIOUS"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "Companies (${ipos.size})",
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { vm.refreshAllCompanies() }, enabled = !isS) {
                    if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null)
                }
            }
        )

        if (isS) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(syncLog, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // --- TOP LEVEL: IPO MASTER ---
            item {
                IpoSectionHeader(
                    title = "IPO Master (${displayIpos.size})", 
                    count = displayIpos.size, 
                    isExpanded = expandedTopSection == "IPO", 
                    onToggle = { expandedTopSection = if (expandedTopSection == "IPO") null else "IPO" }, 
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (expandedTopSection == "IPO") {
                item {
                    OutlinedTextField(
                        value = ipoSearchQuery,
                        onValueChange = { ipoSearchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Search issuing companies...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (ipoSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { ipoSearchQuery = "" }) { Icon(Icons.Default.Close, null) }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                item {
                    IpoSubSection("Upcoming Issues (${upcoming.size})", upcoming.size, (expandedSubSection == "UPCOMING") && upcoming.isNotEmpty(), { expandedSubSection = if (expandedSubSection == "UPCOMING") null else "UPCOMING" }, Color(0xFF3B82F6)) {
                        upcoming.forEach { ipo -> IpoMasterCard(ipo, vm, context) }
                    }
                }

                item {
                    IpoSubSection("Open Issues (${openIssues.size})", openIssues.size, (expandedSubSection == "OPEN") && openIssues.isNotEmpty(), { expandedSubSection = if (expandedSubSection == "OPEN") null else "OPEN" }, Color(0xFF10B981)) {
                        openIssues.forEach { ipo -> IpoMasterCard(ipo, vm, context) }
                    }
                }

                item {
                    IpoSubSection("Closed Issues (${closedIssues.size})", closedIssues.size, (expandedSubSection == "CLOSED") && closedIssues.isNotEmpty(), { expandedSubSection = if (expandedSubSection == "CLOSED") null else "CLOSED" }, Color(0xFFEF4444)) {
                        closedIssues.forEach { ipo -> IpoMasterCard(ipo, vm, context) }
                    }
                }

                item {
                    IpoSubSection("Allotment Completed (${allotmentCompleted.size})", allotmentCompleted.size, (expandedSubSection == "ALLOTTED") && allotmentCompleted.isNotEmpty(), { expandedSubSection = if (expandedSubSection == "ALLOTTED") null else "ALLOTTED" }, Color(0xFF8B5CF6)) {
                        allotmentCompleted.forEach { ipo -> IpoMasterCard(ipo, vm, context) }
                    }
                }

                item {
                    IpoSubSection("Previous Issues (${previousIssues.size})", previousIssues.size, (expandedSubSection == "PREVIOUS") && previousIssues.isNotEmpty(), { expandedSubSection = if (expandedSubSection == "PREVIOUS") null else "PREVIOUS" }, Color(0xFF6B7280)) {
                        previousIssues.forEach { ipo -> IpoMasterCard(ipo, vm, context) }
                    }
                }
            }

            // --- TOP LEVEL: LICENSED DPS ---
            item {
                IpoSectionHeader(
                    title = "Licensed DPs (${displayDps.size})", 
                    count = displayDps.size, 
                    isExpanded = expandedTopSection == "DP", 
                    onToggle = { expandedTopSection = if (expandedTopSection == "DP") null else "DP" }, 
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            if (expandedTopSection == "DP") {
                item {
                    OutlinedTextField(
                        value = dpSearchQuery,
                        onValueChange = { dpSearchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Search DPs (Name or Code)...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (dpSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { dpSearchQuery = "" }) { Icon(Icons.Default.Close, null) }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (displayDps.isEmpty()) {
                    item { EmptyStatePlaceholder("No DPs found. Sync Master to load data.") }
                }

                items(displayDps) { dp ->
                    DpCard(dp)
                }
            }
        }
    }
}

@Composable
fun IpoSubSection(title: String, count: Int, isExpanded: Boolean, onToggle: () -> Unit, color: Color, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Badge(containerColor = color.copy(alpha = 0.1f), contentColor = color) { Text(count.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp), 
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun BoidItem(
    b: BoidEntry, 
    isEnabled: Boolean, 
    activity: com.example.data.db.IpoMemberActivity? = null,
    isMe: Boolean = false,
    onToggle: () -> Unit, 
    onSetDefault: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    onMarkApplied: (() -> Unit)? = null,
    onCheckThroughCdsc: (() -> Unit)? = null,
    onAddTransaction: ((Int) -> Unit)? = null
) {
    val isVaultReady = !b.msUsername.isNullOrBlank() && !b.msPassword.isNullOrBlank()
    
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { 
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) { 
                Column(Modifier.weight(1f)) { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = b.name, 
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold, 
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                        )
                        if (isVaultReady) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.VerifiedUser, "Vault Ready", tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        }
                        if (isMe) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                Text("ME", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                    Text(
                        text = b.boid, 
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    ) 
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = isEnabled, 
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.scale(0.8f)
                    )
                }
            }

            // Enhanced Activity & Result Section
            if (activity != null && (activity.allotmentStatus != "NOT_CHECKED" || activity.applyStatus != "PENDING")) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Allotment Result Row
                    if (activity.allotmentStatus != "NOT_CHECKED") {
                        val isAllotted = activity.allotmentStatus == "ALLOTTED"
                        val isError = activity.allotmentStatus == "ERROR"
                        val isChecking = activity.allotmentStatus == "CHECKING"
                        
                        val statusBg = when {
                            isChecking -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            isAllotted -> Color(0xFFE8F5E9)
                            isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else -> Color(0xFFFFEBEE)
                        }
                        
                        val statusColor = when {
                            isChecking -> MaterialTheme.colorScheme.onSecondaryContainer
                            isAllotted -> Color(0xFF2E7D32)
                            isError -> MaterialTheme.colorScheme.error
                            else -> Color(0xFFC62828)
                        }

                        Surface(
                            color = statusBg,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = when {
                                            isChecking -> Icons.Default.Sync
                                            isAllotted -> Icons.Default.CheckCircle
                                            isError -> Icons.Default.Error
                                            else -> Icons.Default.Cancel
                                        },
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = when {
                                                isChecking -> "Checking Allotment..."
                                                isAllotted -> "CONGRATULATIONS! ALLOTTED"
                                                isError -> "ERROR"
                                                else -> "Sorry, NOT ALLOTTED"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = statusColor
                                        )
                                        if (isAllotted && activity.allotmentUnits > 0) {
                                            Text(
                                                text = "${activity.allotmentUnits} Units Allotted",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Black,
                                                color = statusColor
                                            )
                                        }
                                        Text(
                                            text = activity.allotmentMessage ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = statusColor.copy(alpha = 0.8f),
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (onReset != null) {
                                        var showConfirm by remember { mutableStateOf(false) }
                                        if (showConfirm) {
                                            AlertDialog(
                                                onDismissRequest = { showConfirm = false },
                                                title = { Text(if (isChecking) "Stop Checking?" else "Reset Result?") },
                                                text = { Text(if (isChecking) "Do you want to stop and reset the current checking status for ${b.name}?" else "This will clear the saved allotment result for ${b.name}. Continue?") },
                                                confirmButton = {
                                                    TextButton(onClick = { onReset(); showConfirm = false }) {
                                                        Text("RESET", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                                    }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showConfirm = false }) { Text("CANCEL") }
                                                }
                                            )
                                        }

                                        IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(32.dp)) {
                                            Icon(
                                                imageVector = if (isChecking) Icons.Default.Close else Icons.Default.Refresh, 
                                                contentDescription = "Reset", 
                                                tint = statusColor, 
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                
                                if (isAllotted && activity.allotmentUnits > 0 && onAddTransaction != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = { onAddTransaction(activity.allotmentUnits) },
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = statusColor, contentColor = Color.White),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add to Transactions", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (activity.checkedAt > 0) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Checked at: ${java.text.SimpleDateFormat("MMM dd, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(activity.checkedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusColor.copy(alpha = 0.6f),
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                }
                            }
                        }
                    }
                    
                    if (activity.applyStatus == "APPLIED") {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.FactCheck, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Applied Successfully", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else if (onCheckThroughCdsc != null && isEnabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCheckThroughCdsc,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Public, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check via CDSC Portal", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DpCard(dp: com.example.data.db.DpMaster) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(dp.dpCode.takeLast(2), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(dp.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("DP Code: ${dp.dpCode}", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CredentialVaultScreen(vm: BulkIpoViewModel, portfolioVM: PortfolioViewModel, onBack: () -> Unit) {
    val boids by vm.boids.collectAsStateWithLifecycle()
    val userProfile by portfolioVM.userProfile.collectAsStateWithLifecycle()
    val allDps by vm.allDps.collectAsStateWithLifecycle()
    val isTesting by remember { derivedStateOf { vm.isTestingLogin } }
    
    var editingBoid by remember { mutableStateOf<BoidEntry?>(null) }
    var deletingBoid by remember { mutableStateOf<BoidEntry?>(null) }
    var showA by remember { mutableStateOf(false) }
    var showP by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { 
            context.contentResolver.openInputStream(it)?.use { input ->
                val text = input.bufferedReader().readText()
                vm.addMultipleBoids(text)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            cs.launch {
                val csv = vm.exportVaultToCsv()
                context.contentResolver.openOutputStream(it)?.use { out ->
                    out.write(csv.toByteArray())
                }
                Toast.makeText(context, "Vault exported to CSV", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "Family BOIDs (${boids.size})", 
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { exportLauncher.launch("finfolio_vault_export.csv") }) {
                    Icon(Icons.Default.FileUpload, "Export Vault", tint = MaterialTheme.colorScheme.primary)
                }
            }
        )
        
        // Family Management Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Manage Members", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { fileLauncher.launch("text/*") }, modifier = Modifier.size(32.dp)) { 
                        Icon(Icons.Default.UploadFile, "Upload CSV", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) 
                    }
                    IconButton(onClick = { showP = true }, modifier = Modifier.size(32.dp)) { 
                        Icon(Icons.Default.ContentPaste, "Paste Text", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) 
                    }
                    Button(
                        onClick = { showA = true }, 
                        shape = RoundedCornerShape(8.dp), 
                        modifier = Modifier.height(32.dp), 
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { 
                        Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.Bold) 
                    }
                }
            }
        }

        InfoBanner("Credentials stored here are only used for 'Auto Check' and 'Auto Apply' features. They are stored locally on your device.")
        
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(boids, key = { it.boid }) { boid ->
                val isSet = !boid.msUsername.isNullOrBlank() && !boid.msPassword.isNullOrBlank()
                val dpCode = boid.boid.take(8).takeLast(5)
                val dpName = allDps.find { it.dpCode == dpCode }?.name ?: "Unknown DP ($dpCode)"
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(boid.name, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                    if (userProfile?.boid == boid.boid) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                            Text("ME", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        }
                                    }
                                    IconButton(onClick = { vm.setDefaultBoid(boid.boid) }, modifier = Modifier.size(24.dp).padding(start = 4.dp)) {
                                        Icon(
                                            if (boid.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                                            null,
                                            tint = if (boid.isDefault) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Text(boid.boid, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(dpName, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            
                            if (isSet) {
                                Button(
                                    onClick = { vm.testLogin(boid) },
                                    modifier = Modifier.height(30.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                    enabled = isTesting[boid.boid] != true
                                ) {
                                    if (isTesting[boid.boid] == true) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Science, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Test", fontSize = 10.sp)
                                    }
                                }
                            }

                            IconButton(onClick = { editingBoid = boid }) { Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = { deletingBoid = boid }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
                        }
                        
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            VaultToggle("Result Check", boid.isEnabledForCheck) { vm.toggleBoidEnabled(boid.boid, true) }
                            VaultToggle("IPO Apply", boid.isEnabledForApply) { vm.toggleBoidEnabled(boid.boid, false) }
                        }
                        
                        if (isSet) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), RoundedCornerShape(4.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CredentialSmallField("User", boid.msUsername ?: "")
                                    CredentialSmallField("PIN", if ((boid.msPin ?: "").isNotBlank()) "••••" else "-")
                                    CredentialSmallField("CRN", if ((boid.msCrn ?: "").isNotBlank()) "••••••••" else "-")
                                }
                                Icon(Icons.Default.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary.copy(0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingBoid != null) {
        var user by remember { mutableStateOf(editingBoid!!.msUsername ?: "") }
        var pass by remember { mutableStateOf(editingBoid!!.msPassword ?: "") }
        var pin by remember { mutableStateOf(editingBoid!!.msPin ?: "") }
        var crn by remember { mutableStateOf(editingBoid!!.msCrn ?: "") }
        
        AlertDialog(
            onDismissRequest = { editingBoid = null },
            title = { Text("Vault: ${editingBoid!!.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text("MeroShare Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(pass, { pass = it }, label = { Text("MeroShare Password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(pin, { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it }, label = { Text("Transaction PIN (4-digit)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(crn, { crn = it }, label = { Text("CRN Number") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveCredentials(editingBoid!!.boid, mapOf(
                        "username" to user,
                        "password" to pass,
                        "pin" to pin,
                        "crn" to crn
                    ))
                    editingBoid = null
                }) { Text("Save to Vault") }
            },
            dismissButton = { TextButton({ editingBoid = null }) { Text("Cancel") } }
        )
    }

    if (deletingBoid != null) {
        AlertDialog(
            onDismissRequest = { deletingBoid = null },
            title = { Text("Delete Account?") },
            text = { Text("Remove '${deletingBoid!!.name}' and all associated credentials? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { vm.removeBoid(deletingBoid!!); deletingBoid = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton({ deletingBoid = null }) { Text("Cancel") } }
        )
    }

    if (showA) AddBoidDialog({ n, b -> vm.addBoid(n, b); showA = false }, { showA = false })
    if (showP) PasteBoidDialog({ vm.addMultipleBoids(it); showP = false }, { showP = false })
}

@Composable
fun VaultToggle(label: String, checked: Boolean, onT: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Switch(checked, { onT() }, modifier = Modifier.scale(0.7f))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { onT() })
    }
}

@Composable
fun CredentialSmallField(label: String, value: String) {
    Column {
        Text(label, fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        Text(if (value.isBlank()) "-" else value, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
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
fun SettingsScreen(vm: PortfolioViewModel, mvm: MarketViewModel, onBack: () -> Unit) {
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val currentCurrency = userProfile?.currencySymbol ?: "रु."
    val currentDateFormat = userProfile?.dateFormat ?: "AD"
    val primaryIndex = userProfile?.primaryIndexName ?: "NEPSE Index"
    
    val indices by mvm.indices.collectAsStateWithLifecycle()
    val primaryIndexExists = remember(indices, primaryIndex) {
        indices.any { it.index.equals(primaryIndex, true) || it.index.contains(primaryIndex, true) }
    }

    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    
    val currencies = listOf("रु.", "$", "€", "£", "¥", "₹")
    
    val logLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            cs.launch {
                val logContent = AppLogger.exportLogs()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(logContent.toByteArray()) }
                }
                Toast.makeText(context, "Logs exported successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }


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
                        Text("Debug & Support", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("If the app is not syncing correctly, export logs and send them to the developer.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(Modifier.height(8.dp))
                        Text("⚡ Maintenance: Weekly Log Pruner is active (keeps last 7 days of LTP logs).", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)

                        Spacer(Modifier.height(16.dp))
                        
                        Button(
                            onClick = { logLauncher.launch("finfolio_debug_logs.txt") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.BugReport, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export Debug Logs")
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = { AppLogger.clearLogs(); Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Clear Internal Logs")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Financial Calculation Rates", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Configure rates used for profit and deduction calculations.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        var comm by remember(userProfile?.commissionRate) { mutableStateOf(userProfile?.commissionRate?.toString() ?: "0.0038") }
                        var flat by remember(userProfile?.flatFee) { mutableStateOf(userProfile?.flatFee?.toString() ?: "25.0") }
                        var cgt by remember(userProfile?.cgtRate) { mutableStateOf(userProfile?.cgtRate?.toString() ?: "0.075") }

                        OutlinedTextField(
                            value = comm,
                            onValueChange = { comm = it },
                            label = { Text("Broker Commission Rate (e.g., 0.0038)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = flat,
                            onValueChange = { flat = it },
                            label = { Text("Flat Transaction Fee (e.g., 25.0)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = cgt,
                            onValueChange = { cgt = it },
                            label = { Text("Capital Gains Tax Rate (e.g., 0.075)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { vm.resetFinancialRates() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Restore Defaults")
                            }

                            Button(
                                onClick = { 
                                    val c = comm.toDoubleOrNull() ?: 0.0
                                    val f = flat.toDoubleOrNull() ?: 0.0
                                    val t = cgt.toDoubleOrNull() ?: 0.0
                                    vm.updateFinancialRates(c, f, t)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Apply Rates")
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Primary Market Index", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("The main index name to track in the top bar and dashboard (e.g., NEPSE Index, NIFTY 50).", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Spacer(Modifier.height(12.dp))
                        
                        var editedName by remember(primaryIndex) { mutableStateOf(primaryIndex) }
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g., NEPSE Index") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (editedName != "NEPSE Index") {
                                        IconButton(onClick = { 
                                            editedName = "NEPSE Index"
                                            vm.updatePrimaryIndexName("NEPSE Index")
                                        }) {
                                            Icon(Icons.Default.Restore, "Restore Default", tint = Color.Gray)
                                        }
                                    }
                                    if (editedName != primaryIndex) {
                                        IconButton(onClick = { vm.updatePrimaryIndexName(editedName) }) {
                                            Icon(Icons.Default.Save, "Save", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        )
                        
                        if (!primaryIndexExists && primaryIndex != "NEPSE Index") {
                            Text(
                                "Primary Index '$primaryIndex' not found in market data sources.",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Date & Time Format", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Choose between BS (Bikram Sambat) or AD (Anno Domini) formats.", fontSize = 12.sp, color = Color.Gray)
                        
                        Spacer(Modifier.height(8.dp))
                        Text("🛡️ Verified: Conversions use Round-Trip verification logic.", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)

                        Spacer(Modifier.height(16.dp))
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("AD", "BS").forEach { format ->
                                OutlinedButton(
                                    onClick = { vm.updateAppSettings(currentCurrency, format) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = if (currentDateFormat == format) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                                ) {
                                    Text(format)
                                    if (format == "BS") {
                                        Spacer(Modifier.width(4.dp))
                                        Badge { Text("Verified", fontSize = 8.sp) }
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun IpoMasterScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val ipos by vm.ipos.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()
    val syncLog by vm.syncLog.collectAsStateWithLifecycle()
    val syncMsg by vm.syncMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(syncMsg) {
        syncMsg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val displayIpos = remember(searchQuery, ipos) {
        if (searchQuery.isBlank()) ipos
        else ipos.filter { 
            it.companyName.contains(searchQuery, true) || 
            it.scrip?.contains(searchQuery, true) == true
        }
    }


    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "IPO Master (${displayIpos.size})",
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { vm.syncIpos() }, enabled = !isS) {
                    if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null)
                }
            }
        )

        if (isS) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            Text(syncLog, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
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

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val weekAgo = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L))

        val upcoming = displayIpos.filter { !it.openingDate.isNullOrBlank() && it.openingDate > today }.sortedBy { it.openingDate }
        val currentIssues = displayIpos.filter { 
            it !in upcoming && (
                (it.status.lowercase() in listOf("open", "active", "closed", "applying") && it.allotmentDate.isNullOrBlank()) || 
                (!it.allotmentDate.isNullOrBlank() && it.allotmentDate >= weekAgo) ||
                (it.openingDate.isNullOrBlank() && !it.status.equals("Allotted", true) && it.allotmentDate.isNullOrBlank())
            )
        }.sortedByDescending { it.openingDate ?: "" }
        
        val previousIssues = displayIpos.filter { 
            it !in upcoming && it !in currentIssues
        }.sortedByDescending { it.allotmentDate ?: it.closingDate ?: "" }

        var expUpcoming by remember { mutableStateOf(true) }
        var expCurrent by remember { mutableStateOf(true) }
        var expPrevious by remember { mutableStateOf(false) }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (displayIpos.isEmpty() && !isS) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.Inventory, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(16.dp))
                            Text(if (searchQuery.isEmpty()) "No IPOs found." else "No results found for '$searchQuery'", fontWeight = FontWeight.Bold)
                            if (searchQuery.isEmpty()) {
                                Text("Click refresh to sync from configured sources.", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }

            // --- UPCOMING ---
            stickyHeader {
                IpoSectionHeader("Upcoming Issues", upcoming.size, expUpcoming, { expUpcoming = !expUpcoming }, Color(0xFF3B82F6))
            }
            if (expUpcoming) {
                items(upcoming) { ipo -> IpoMasterCard(ipo, vm, context) }
            }

            // --- CURRENT ---
            stickyHeader {
                IpoSectionHeader("Current Issues", currentIssues.size, expCurrent, { expCurrent = !expCurrent }, Color(0xFF10B981))
            }
            if (expCurrent) {
                items(currentIssues) { ipo -> IpoMasterCard(ipo, vm, context) }
            }

            // --- PREVIOUS ---
            stickyHeader {
                IpoSectionHeader("Previous Issues", previousIssues.size, expPrevious, { expPrevious = !expPrevious }, Color(0xFF6B7280))
            }
            if (expPrevious) {
                items(previousIssues) { ipo -> IpoMasterCard(ipo, vm, context) }
            }
        }
    }
}

@Composable
fun IpoSectionHeader(
    title: String,
    count: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    color: Color
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun IpoMasterCard(ipo: IpoMaster, vm: BulkIpoViewModel, context: android.content.Context) {
    val isSearchingItem = vm.searchingIpos[ipo.companyName] ?: false
    var showDatePicker by remember { mutableStateOf(false) }
    if (showDatePicker) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        
        DatePickerDialog(context, { _, y, m, d ->
            val selectedDate = String.format(Locale.US, "%d-%02d-%02d", y, m + 1, d)
            vm.updateAllotmentDate(ipo.companyName, selectedDate)
            showDatePicker = false
        }, year, month, day).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ipo.companyName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!ipo.scrip.isNullOrBlank()) {
                        Text(ipo.scrip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val statusText: String
                val statusColor: Color
                
                when {
                    ipo.status.equals("Allotted", true) -> {
                        statusText = "CHECK AVAILABLE"
                        statusColor = Color(0xFF10B981)
                    }
                    ipo.allotmentDate != null -> {
                        if (ipo.allotmentDate <= today) {
                            statusText = "ALLOTMENT COMPLETED"
                            statusColor = Color(0xFF10B981)
                        } else {
                            statusText = "ALLOTMENT PENDING"
                            statusColor = Color(0xFFF59E0B)
                        }
                    }
                    ipo.status.lowercase() == "active" || ipo.status.lowercase() == "open" -> {
                        statusText = "OPEN"
                        statusColor = Color(0xFF10B981)
                    }
                    ipo.status.lowercase() == "closed" -> {
                        statusText = "CLOSED"
                        statusColor = Color(0xFF6B7280)
                    }
                    else -> {
                        statusText = ipo.status.uppercase()
                        statusColor = MaterialTheme.colorScheme.primary
                    }
                }
                
            Badge(containerColor = statusColor.copy(alpha = 0.1f), contentColor = statusColor) {
                Text(statusText, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Opening Date", fontSize = 9.sp, color = Color.Gray)
                Text(ipo.openingDate ?: "N/A", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Closing Date", fontSize = 9.sp, color = Color.Gray)
                Text(ipo.closingDate ?: "N/A", fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Allotment Date", fontSize = 9.sp, color = Color.Gray)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showDatePicker = true }) {
                    Text(ipo.allotmentDate ?: "Set", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (ipo.allotmentDate == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Icon(Icons.Default.EditCalendar, null, modifier = Modifier.padding(start = 2.dp).size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Result Portal ID", fontSize = 9.sp, color = Color.Gray)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (ipo.resultPortalId != null) ipo.resultPortalId.toString() else "Not Mapped", 
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (ipo.resultPortalId == null) Color.Red else MaterialTheme.colorScheme.onSurface)
                        
                        Spacer(Modifier.width(8.dp))
                        if (isSearchingItem) {
                             CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.AutoFixHigh, 
                                "Auto Find", 
                                tint = MaterialTheme.colorScheme.primary, 
                                modifier = Modifier.size(18.dp).clickable { vm.discoverResultPortalId(ipo) }
                            )
                        }
                    }
                }
                
                var showIdInput by remember { mutableStateOf(false) }
                IconButton(onClick = { showIdInput = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) }
                
                if (showIdInput) {
                    PortalIdEditDialog(ipo.companyName, ipo.resultPortalId?.toString() ?: "", { vm.updateResultPortalId(ipo.companyName, it) }, { showIdInput = false })
                }
            }
        }
    }
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
    val items by vm.dashboardItemMetrics.collectAsStateWithLifecycle()
    val sectors by vm.dashboardSectorMetrics.collectAsStateWithLifecycle()
    val scope by vm.dashboardScope.collectAsStateWithLifecycle()
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ExecutiveScopeSelector(scope) { vm.setDashboardScope(it) }
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Bolt, "High Performance", tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Engine: State-Driven (V2) | High-Performance Mode", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
        }
        
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val tEval = items.sumOf { it.evaluation }
            val tInv = items.sumOf { it.netInvest }
            val tRec = items.sumOf { it.receivableAmount }
            val tGain = items.sumOf { it.netGain }
            val tProf = items.sumOf { it.profitAmount }
            val tBuy = items.sumOf { it.buyAmount }
            val tGrowth = if (tBuy > 0.0) (tGain / tBuy) * 100.0 else 0.0
            val tProfitPct = if (tInv > 0.0) (tProf / tInv) * 100.0 else 0.0
            
            item { ValuationSummaryCard(tEval, tInv, tRec, tGain, tGrowth, tProf, tProfitPct, symbol = symbol) }
            item { SectorAllocationCard(sectors, tInv, symbol) }
            item { NetGainStatisticsCard(sectors, tGain, symbol) }
            item { PerformersSection(items, symbol) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun NetGainStatisticsCard(m: List<TypeMetrics>, totalGain: Double, symbol: String = "रु.") {
    val allGainers = m.filter { it.netGain > 0 }.sortedByDescending { it.netGain }
    val tGain = allGainers.sumOf { it.netGain }
    
    val displayList = remember(allGainers) {
        if (allGainers.size > 5) {
            val top4 = allGainers.take(4)
            val others = allGainers.drop(4)
            top4 + TypeMetrics(
                sector = "OTHERS",
                itemCount = 0, buyAmount = 0.0, saleAmount = 0.0, returnsCash = 0.0, returnsQty = 0.0,
                balanceQty = 0.0, netInvest = 0.0, evaluation = 0.0, realizedGain = 0.0, unrealizedGain = 0.0,
                deductions = 0.0, netGain = others.sumOf { it.netGain }, growth = 0.0,
                receivableAmount = 0.0, profitAmount = 0.0, profitPercent = 0.0
            )
        } else allGainers
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Net Gain by Sector", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (displayList.isEmpty() || tGain <= 0.0) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("No sector-wise gains to display", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                val colors = listOf(Color(0xFF00D2C4), Color(0xFF2ECE7B), Color(0xFFFFB300), Color(0xFFEB4D4B), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            var start = -90f
                            displayList.forEachIndexed { i, met ->
                                val sweep = (met.netGain / tGain).toFloat() * 360f
                                if (sweep > 0) {
                                    drawArc(
                                        color = if (met.sector == "OTHERS") Color.Gray else colors[i % colors.size],
                                        startAngle = start,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        style = Stroke(18.dp.toPx(), cap = StrokeCap.Round)
                                    )
                                    start += sweep
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("TOTAL GAIN", fontSize = 8.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(formatCurrency(tGain, symbol), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF10B981))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayList.forEachIndexed { i, met ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (met.sector == "OTHERS") Color.Gray else colors[i % colors.size]))
                                Spacer(Modifier.width(8.dp))
                                Text(met.sector, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(String.format(Locale.US, "(%.1f%%)", (met.netGain/tGain)*100), fontSize = 8.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
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
                sector = "OTHER",
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
                                        color = if (met.sector == "OTHER") Color.Gray.copy(0.2f) else colors[i % colors.size].copy(alpha = 0.3f),
                                        startAngle = start,
                                        sweepAngle = sweep,
                                        useCenter = false,
                                        style = Stroke(24.dp.toPx(), cap = StrokeCap.Butt),
                                        topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx())
                                    )
                                    // Main Ring
                                    drawArc(
                                        color = if (met.sector == "OTHER") Color.Gray else colors[i % colors.size],
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
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(if (met.sector == "OTHER") Color.Gray else colors[i % colors.size]))
                                Spacer(Modifier.width(8.dp))
                                Text(met.sector, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
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
/**
 * MatrixScreen: High-density data table for portfolio analysis.
 * 
 * UX DESIGN:
 * - Dynamic Sector Filter: Filters the dropdown list based on [DatasetScope].
 * - Auto-Reset: Switching scope resets the filter to "All" to prevent empty view confusion.
 */
fun MatrixScreen(vm: PortfolioViewModel) {
    val context = LocalContext.current; val cs = rememberCoroutineScope()
    val scope by vm.matrixScope.collectAsStateWithLifecycle(); val filter by vm.selectedSectorFilter.collectAsStateWithLifecycle()
    val items by vm.matrixItemMetrics.collectAsStateWithLifecycle(); val dSectors by vm.matrixSectors.collectAsStateWithLifecycle()
    var showCfg by remember { mutableStateOf(false) }
    val iCols by vm.itemColumns.collectAsStateWithLifecycle()
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    
    val sectorCounts = remember(items) { items.groupBy { it.sector }.mapValues { it.value.size } }
    val currentSectorCount = if (filter == "All") items.size else sectorCounts[filter] ?: 0

    val fItems = if (filter == "All") items else items.filter { it.sector.equals(filter, true) }
    
    val exLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            cs.launch {
                val csv = buildString {
                    append("Scrip,Sector,Buy Amt,Buy Qty,Buy Count,Sale Amt,Sale Qty,Sale Count,Ret Cash,Ret Qty,Ret Count,Bal Qty,Avg CP,Avg SP,LTP,Invest,Eval,Realized,Unrealized,Deduct,Net Gain,Growth %,Receivable,Profit,Profit %\n")
                    fItems.forEach { r ->
                        append("${r.item},${r.sector},${r.buyAmount},${r.buyQty},${r.buyCount},${r.saleAmount},${r.saleQty},${r.saleCount},${r.returnsCash},${r.returnsQty},${r.returnCount},${r.balanceQty},${r.avgCp},${r.avgSp},${r.ltp},${r.netInvest},${r.evaluation},${r.realizedGain},${r.unrealizedGain},${r.deductions},${r.netGain},${r.growth},${r.receivableAmount},${r.profitAmount},${r.profitPercent}\n")
                    }
                }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.bufferedWriter().use { w -> w.write(csv) } } }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Exported Successfully", Toast.LENGTH_SHORT).show() }
            }
        }
    }

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
        Spacer(Modifier.height(8.dp)); ExecutiveScopeSelector(scope) { vm.setMatrixScope(it) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            var exp by remember { mutableStateOf(false) }
            Box { 
                Button(onClick = { exp = true }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { 
                    Text("Sector: $filter ($currentSectorCount)", fontSize = 10.sp)
                    Icon(Icons.Default.ArrowDropDown, null) 
                }
                DropdownMenu(exp, { exp = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                    DropdownMenuItem(text = { Text("All (${items.size})") }, onClick = { vm.setSelectedSectorFilter("All"); exp = false })
                    dSectors.forEach { sector ->
                        val count = sectorCounts[sector] ?: 0
                        DropdownMenuItem(text = { Text("$sector ($count)") }, onClick = { vm.setSelectedSectorFilter(sector); exp = false })
                    }
                } 
            }
            
            IconButton(onClick = { exLauncher.launch("finfolio_matrix.csv") }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Download, "Export CSV", tint = MaterialTheme.colorScheme.primary)
            }

            Button(onClick = { showCfg = true }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { 
                Icon(Icons.Default.ViewColumn, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Columns", fontSize = 10.sp) 
            }
        }
        Box(Modifier.weight(1f).padding(top = 12.dp)) { ItemMatrixTable(fItems, iCols, symbol = symbol) }
    }
    if (showCfg) ColumnConfigurationDialog(true, iCols, { vm.toggleItemColumn(it) }, { vm.setItemColumns(it) }, { showCfg = false })
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

/**
 * STRATEGY: UI Matrix Columns
 * Standard identifiers for dynamic portfolio metric display.
 */
private val itemColumnsList = listOf(
    "Buy_Amount", "Buy_Qty", "Buy_Count", "Sale_Amount", "Sale_Qty", "Sale_Count",
    "Returns_Cash", "Returns_Qty", "Return_Count", "Balance_Qty", "Avg_CP", "Avg_SP",
    "LTP", "Net_Invest", "Evaluation", "Realized_Gain", "Unrealized_Gain", "Deductions",
    "Net_Gain", "Growth", "Receivable_Amount", "Profit_Amount", "Profit_Percent"
)

@Composable
fun ColumnConfigurationDialog(isItem: Boolean, active: Set<String>, onT: (String) -> Unit, onSet: (Set<String>) -> Unit, onD: () -> Unit) {
    val opts = itemColumnsList.sorted()
    Dialog(onDismissRequest = onD) { 
        Card(shape = RoundedCornerShape(16.dp)) { 
            Column(Modifier.padding(16.dp)) { 
                Text("Columns (${active.size + 1})", fontWeight = FontWeight.Bold)
                
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSet(opts.toSet()) }, modifier = Modifier.weight(1f)) {
                        Text("Select All", fontSize = 11.sp)
                    }
                    TextButton(onClick = { onSet(emptySet()) }, modifier = Modifier.weight(1f)) {
                        Text("Unselect All", fontSize = 11.sp)
                    }
                }

                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 4.dp).heightIn(max = 240.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { 
                            Checkbox(true, { }, enabled = false)
                            Text("Scrip", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) 
                        }
                    }
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
        listOf(DatasetScope.OVERALL to "Overall", DatasetScope.PORTFOLIO to "Portfolio").forEach { (sc, l) ->
            val sel = s == sc
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent).clickable { onS(sc) }.padding(8.dp), contentAlignment = Alignment.Center) { Text(l, fontWeight = FontWeight.Bold, color = if (sel) MaterialTheme.colorScheme.primary else Color.Gray) }
        }
    }
}

@Composable
fun RegistrationDialog(onR: (String, String, String?) -> Unit) {
    var n by remember { mutableStateOf("") }; var e by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {}, 
        title = { Text("Personalize FinFolio") }, 
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { 
                OutlinedTextField(n, { n = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(e, { e = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) 
                OutlinedTextField(b, { if (it.length <= 16) b = it }, label = { Text("My BOID (Optional)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("Your BOID is used to identify you in IPO checks for easy transaction recording.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } 
        }, 
        confirmButton = { Button({ if (n.isNotBlank() && e.isNotBlank()) onR(n, e, b.ifBlank { null }) }) { Text("Register") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowEntryForm(dI: List<String>, dS: List<String>, onGetSector: suspend (String) -> String, symbol: String = "रु.", prefill: TransactionRecord? = null, onS: (TransactionRecord) -> Unit) {
    var item by remember(prefill) { mutableStateOf(prefill?.item ?: "") }
    var sector by remember(prefill) { mutableStateOf(prefill?.sector ?: "") }
    var action by remember(prefill) { mutableStateOf(prefill?.action ?: "Buy") }
    var qty by remember(prefill) { mutableStateOf(if (prefill != null && prefill.qty > 0) prefill.qty.toString() else "") }
    var amt by remember(prefill) { mutableStateOf(if (prefill != null && prefill.amount > 0) prefill.amount.toString() else "") }
    val cs = rememberCoroutineScope()

    var showAddItem by remember { mutableStateOf(false) }
    var showAddSector by remember { mutableStateOf(false) }
    
    var expI by remember { mutableStateOf(false) }
    var expS by remember { mutableStateOf(false) }

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
                                    if (suggestedSector != "Other") sector = suggestedSector
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
                                            if (suggestedSector != "Other") sector = suggestedSector
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
                    expanded = expS,
                    onExpandedChange = { expS = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = sector,
                        onValueChange = { sector = it },
                        label = { Text("Sector") },
                        modifier = Modifier.fillMaxWidth().height(60.dp).menuAnchor(),
                        shape = RoundedCornerShape(8.dp),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expS) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                    
                    val filtered = dS.filter { it.contains(sector, true) }.take(50)
                    if (filtered.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = expS,
                            onDismissRequest = { expS = false }
                        ) {
                            filtered.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s) },
                                    onClick = { sector = s; expS = false }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { showAddSector = true }, modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)) {
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
                        onS(TransactionRecord(date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), item = item, sector = sector, action = action, qty = q, amount = a)); 
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
                        if (suggestedSector != "Other") sector = suggestedSector
                    }
                    showAddItem = false 
                }) { Text("Set") } 
            },
            dismissButton = { TextButton({ showAddItem = false }) { Text("Cancel") } }
        )
    }

    if (showAddSector) {
        var newSector by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSector = false },
            title = { Text("New Sector") },
            text = { OutlinedTextField(newSector, { newSector = it }, label = { Text("Sector Name") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button({ sector = newSector; showAddSector = false }) { Text("Set") } },
            dismissButton = { TextButton({ showAddSector = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun TransactionListItem(tx: TransactionRecord, symbol: String = "रु.", onE: () -> Unit, onD: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val clr = when(tx.action){ "Buy" -> Color(0xFF2ECE7B); "Sale" -> Color(0xFFEF4444); else -> Color(0xFF3B82F6) }
            Box(Modifier.size(36.dp).clip(CircleShape).background(clr.copy(0.1f)), contentAlignment = Alignment.Center) { Text(tx.action.take(1), color = clr, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(tx.item, fontWeight = FontWeight.Bold); if (tx.isSystemAdjustment) Icon(Icons.Default.AutoFixHigh, null, Modifier.size(12.dp), Color.Gray) }; Text("${tx.date} • ${tx.sector}", fontSize = 10.sp, color = Color.Gray) }
            Column(horizontalAlignment = Alignment.End) { Text(formatCurrency(tx.amount, symbol), fontWeight = FontWeight.Bold); Text("${tx.qty} units", fontSize = 10.sp, color = Color.Gray) }
            IconButton(onClick = onE) { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
            IconButton(onClick = onD) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red) }
        }
    }
}

@Composable
fun EditTransactionDialog(r: TransactionRecord, dI: List<String>, dS: List<String>, onGetSector: suspend (String) -> String, symbol: String = "रु.", onS: (TransactionRecord) -> Unit, onD: () -> Unit) {
    var item by remember { mutableStateOf(r.item) }
    var sector by remember { mutableStateOf(r.sector) }
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
                                sector = suggestedSector
                            }
                        }
                    }
                }, dI)
                AutoCompleteTextField("Sector", sector, { sector = it }, dS)
                OutlinedTextField(qty, { if (it.isEmpty() || it.toDoubleOrNull() != null) qty = it }, label = { Text("Qty") }, modifier = Modifier.fillMaxWidth().height(60.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                OutlinedTextField(amt, { if (it.isEmpty() || it.toDoubleOrNull() != null) amt = it }, label = { Text("Amount ($symbol)") }, modifier = Modifier.fillMaxWidth().height(60.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.End) { 
                    TextButton(onD) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button({ 
                        val q = qty.toDoubleOrNull() ?: 0.0
                        val a = amt.toDoubleOrNull() ?: 0.0
                        onS(r.copy(item = item, sector = sector, qty = q, amount = a))
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

/**
 * DataScreen: Central hub for history management and CSV operations.
 * 
 * UI FLOW:
 * - Standard Import -> Populates transactions and triggers Holdings calculation.
 * - Post-Import Action -> Dynamically shows a "Transactions Loaded" or "Purchase History Loaded" 
 *   card with a recommendation to "Sync" (Meroshare alignment).
 */
@Composable
fun DataScreen(viewModel: PortfolioViewModel) {
    val context = LocalContext.current
    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()
    val dItems by viewModel.distinctItems.collectAsStateWithLifecycle()
    val itemCounts = remember(transactions) { transactions.groupBy { it.item }.mapValues { it.value.size } }
    val availableItems by viewModel.availableItems.collectAsStateWithLifecycle()
    val dSectors by viewModel.distinctSectors.collectAsStateWithLifecycle()
    
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val symbol = userProfile?.currencySymbol ?: "रु."
    
    var showImport by remember { mutableStateOf(false) }
    var csvText by remember { mutableStateOf<String?>(null) }
    var isWacc by remember { mutableStateOf(false) }
    var showMS by remember { mutableStateOf(false) }
    
    val pendingSync by viewModel.pendingPortfolioSync.collectAsStateWithLifecycle()
    val pendingTransaction by viewModel.pendingTransaction.collectAsStateWithLifecycle()
    var pDel by remember { mutableStateOf<TransactionRecord?>(null) }
    var pAdd by remember { mutableStateOf<TransactionRecord?>(null) }
    var pUpd by remember { mutableStateOf<TransactionRecord?>(null) }
    var editingRec by remember { mutableStateOf<TransactionRecord?>(null) }
    var filter by remember { mutableStateOf("All") }
    var expY by remember { mutableStateOf(setOf<String>()) }
    val cs = rememberCoroutineScope()

    val msLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) viewModel.preparePortfolioSync(t) } }
    val txLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = false; showImport = true } } }
    val waLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = true; showImport = true } } }
    val exLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { if (it != null) cs.launch { 
        val c = buildString { 
            append("Date,Item,Action,Qty,Amount,Sector\n")
            transactions.forEach { tx ->
                append("${tx.date},${tx.item},${tx.action},${tx.qty},${tx.amount},${tx.sector}\n")
            } 
        }
        withContext(Dispatchers.IO) { 
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.bufferedWriter().use { writer ->
                    writer.write(c)
                }
            } 
        }
        withContext(Dispatchers.Main) { Toast.makeText(context, "Exported Successfully", Toast.LENGTH_SHORT).show() }
    } }

    val txY = remember(transactions, filter) { (if (filter == "All") transactions else transactions.filter { it.item.equals(filter, true) }).groupBy { it.date.split("-").firstOrNull() ?: "Unknown" }.toSortedMap(compareByDescending { it }) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Record", "Advanced", "History")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary, divider = {}) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title, fontWeight = FontWeight.Bold) })
            }
        }

        Box(Modifier.weight(1f).padding(16.dp)) {
            when (selectedTab) {
                0 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("Record Transaction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        RowEntryForm(dItems, dSectors, onGetSector = { viewModel.getSectorForScrip(it) }, symbol = symbol, prefill = pendingTransaction) { 
                            pAdd = it
                            viewModel.setPendingTransaction(null)
                        }
                        
                        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)
                        
                        OutlinedButton(
                            onClick = { txLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Bulk Import from CSV", fontWeight = FontWeight.Bold)
                                Text("Required columns: Date, Item, Action, Qty, Amount, Sector", fontSize = 9.sp, fontWeight = FontWeight.Normal)
                            }
                        }

                        if (showMS) {
                            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) { 
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { 
                                    Icon(Icons.AutoMirrored.Filled.TrendingUp, null)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) { 
                                        Text(if (isWacc) "Purchase History Loaded" else "Activity History Loaded", fontWeight = FontWeight.Bold)
                                        Text("Next Step: Sync with Portfolio CSV to align non-equity items and current prices.", fontSize = 11.sp, lineHeight = 13.sp) 
                                        Text("Note: Use the Portfolio CSV export from your brokerage or the 'My Holdings' export. This matches local history with current state and fills missing LTP data.", fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                    }
                                    Button(onClick = { msLauncher.launch("*/*") }) { Text("Sync") } 
                                } 
                            }
                        }
                    }
                }
                1 -> {
                    UnifiedIOCard(waLauncher, msLauncher)
                }
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("History (${transactions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            
                            Box(Modifier.width(130.dp)) {
                                FilterDropdown(filter, availableItems, itemCounts) { filter = it }
                            }

                            IconButton(onClick = { exLauncher.launch("finfolio_export.csv") }) {
                                Icon(Icons.Default.FileUpload, "Export History", tint = Color(0xFF2E7D32))
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
    if (editingRec != null) EditTransactionDialog(editingRec!!, dItems, dSectors, onGetSector = { viewModel.getSectorForScrip(it) }, symbol = symbol, onS = { pUpd = it; editingRec = null }, onD = { editingRec = null })
    if (pendingSync != null) AlertDialog({ viewModel.cancelPortfolioSync() }, title = { Text("Align Portfolio?") }, text = { Text("This will create ${pendingSync!!.second} new history entries (System Adjustments) to match your current portfolio export. Proceed?") }, confirmButton = { Button({ viewModel.importPortfolioSync(pendingSync!!.first); viewModel.cancelPortfolioSync(); showMS = false }) { Text("Proceed") } }, dismissButton = { TextButton({ viewModel.cancelPortfolioSync() }) { Text("Cancel") } })
    if (pDel != null) AlertDialog({ pDel = null }, title = { Text("Confirm Deletion") }, text = { Text("Are you sure you want to permanently delete this transaction for '${pDel!!.item}'? This action cannot be undone.") }, confirmButton = { Button({ viewModel.deleteTransaction(pDel!!); pDel = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") } }, dismissButton = { TextButton({ pDel = null }) { Text("Cancel") } })
    if (pAdd != null) AlertDialog({ pAdd = null }, title = { Text("Confirm Record") }, text = { Text("Add this ${pAdd!!.action} transaction for '${pAdd!!.item}' to your history?") }, confirmButton = { Button({ viewModel.addTransaction(pAdd!!); pAdd = null }) { Text("Confirm") } }, dismissButton = { TextButton({ pAdd = null }) { Text("Cancel") } })
    if (pUpd != null) AlertDialog({ pUpd = null }, title = { Text("Confirm Update") }, text = { Text("Apply changes to this transaction for '${pUpd!!.item}'?") }, confirmButton = { Button({ viewModel.updateTransaction(pUpd!!); pUpd = null }) { Text("Update") } }, dismissButton = { TextButton({ pUpd = null }) { Text("Cancel") } })
}

@Composable
fun UnifiedIOCard(wa: androidx.activity.result.ActivityResultLauncher<String>, ms: androidx.activity.result.ActivityResultLauncher<String>) {
    val stepColor = Color(0xFFF59E0B) // Professional Amber/Yellow
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))) {
        Column(Modifier.padding(16.dp)) {
            Text("Advanced Data Tools", fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            
            Text("Follow these steps to align your portfolio precisely:", 
                style = MaterialTheme.typography.labelMedium, 
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp))
            
            // Step 1: WACC
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(containerColor = stepColor) { Text("STEP 1", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Text("Import WACC (Purchase History)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Button(
                    onClick = { wa.launch("*/*") }, 
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary),
                    shape = RoundedCornerShape(12.dp)
                ) { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WACC CSV", fontWeight = FontWeight.Bold)
                        Text("Required columns: Scrip, Qty, Rate, Cost", fontSize = 9.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Step 2: Portfolio Sync
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(containerColor = stepColor) { Text("STEP 2", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Text("Sync Current Holdings & LTP", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Button(
                    onClick = { ms.launch("*/*") }, 
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Portfolio CSV", fontWeight = FontWeight.Bold)
                        Text("Required columns: Scrip, LTP, Qty / Balance", fontSize = 9.sp, fontWeight = FontWeight.Normal)
                    }
                }
                
                Text(
                    "Note: You can use the Portfolio CSV export from 'My Shares' from MeroShare portfolio or the previous 'My Holdings' export from the Market Pulse screen. Use this when online updates fail or there are mismatched metrics due to missing LTP data.",
                    modifier = Modifier.padding(top = 4.dp), 
                    fontSize = 10.sp, 
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun UserProfileScreen(vm: PortfolioViewModel, onBack: () -> Unit) {
    val userProfile by vm.userProfile.collectAsStateWithLifecycle()
    var name by remember(userProfile) { mutableStateOf(userProfile?.name ?: "") }
    var email by remember(userProfile) { mutableStateOf(userProfile?.email ?: "") }
    var boid by remember(userProfile) { mutableStateOf(userProfile?.boid ?: "") }

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

                        OutlinedTextField(
                            value = boid,
                            onValueChange = { if (it.length <= 16) boid = it },
                            label = { Text("My BOID (16 Digits)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            placeholder = { Text("Enter your 16-digit BOID") },
                            supportingText = { Text("Used to identify you in IPO checks for transaction recording.") }
                        )
                        
                        Button(
                            onClick = { vm.registerUser(name, email, boid.ifBlank { null }) },
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
fun FilterDropdown(f: String, items: List<String>, itemCounts: Map<String, Int> = emptyMap(), onS: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    val totalCount = itemCounts.values.sum()
    val currentCount = if (f == "All") totalCount else itemCounts[f] ?: 0
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ exp = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) { 
            Text("Scrip: $f" + (if (currentCount > 0 || f == "All") " ($currentCount)" else ""), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null) 
        }
        DropdownMenu(exp, { exp = false }, modifier = Modifier.heightIn(max = 280.dp)) {
            DropdownMenuItem(text = { Text("All ($totalCount)") }, onClick = { onS("All"); exp = false })
            items.sorted().forEach { item ->
                val count = itemCounts[item] ?: 0
                DropdownMenuItem(text = { Text("$item ($count)") }, onClick = { onS(item); exp = false })
            }
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
fun SectorExpandableHeader(sector: String, count: Int, isExpanded: Boolean, onToggle: () -> Unit) {
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
            text = sector.uppercase(),
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

    // Checkpoint log for screen entry
    LaunchedEffect(Unit) {
        com.example.data.util.AppLogger.i("ScraperConfig", "User entered Scraper Configuration Screen")
    }

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
                val pendingUrls = remember(urls) { mutableStateListOf(*urls.toTypedArray()) }
                val hasChanges = remember(urls, pendingUrls.toList()) { urls != pendingUrls.toList() }
                
                Card(Modifier.fillMaxWidth(), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.5f))) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(text = category.displayName, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            if (hasChanges) {
                                Button(
                                    onClick = {
                                        com.example.data.util.AppLogger.i("ScraperConfig", "User applying batch changes for $category")
                                        vm.updateScraperUrls(category, pendingUrls.toList())
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Apply", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(text = category.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                        
                        pendingUrls.forEachIndexed { index, url ->
                            var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
                            var isTesting by remember { mutableStateOf(false) }

                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("${index + 1}") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { pendingUrls[index] = it },
                                    modifier = Modifier.weight(1f),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    trailingIcon = {
                                        if (isTesting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                        else IconButton(onClick = {
                                            isTesting = true
                                            cs.launch {
                                                com.example.data.util.AppLogger.d("ScraperConfig", "User triggered test for URL at index $index in $category")
                                                val res = vm.testScraperUrl(category, url)
                                                isTesting = false
                                                testResult = if (res.isSuccess) true to res.getOrThrow() else false to (res.exceptionOrNull()?.message ?: "Unknown error")
                                            }
                                        }) { Icon(Icons.Default.BugReport, null, Modifier.size(16.dp)) }
                                    }
                                )
                                if (pendingUrls.size > 1) {
                                    IconButton(onClick = {
                                        com.example.data.util.AppLogger.w("ScraperConfig", "User requested deletion of URL at index $index in $category")
                                        pendingUrls.removeAt(index)
                                    }) { Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                            
                            testResult?.let { (success, msg) ->
                                Text(msg, color = if (success) Color(0xFF2E7D32) else Color(0xFFC62828), fontSize = 9.sp, modifier = Modifier.padding(start = 32.dp, bottom = 4.dp))
                            }
                        }
                        
                        Button(
                            onClick = {
                                com.example.data.util.AppLogger.i("ScraperConfig", "User adding new fallback URL slot for $category")
                                pendingUrls.add("")
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
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(100.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Kedar Bhandari",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Lead Developer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(Modifier.padding(bottom = 24.dp), thickness = 0.5.dp)

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

            Spacer(modifier = Modifier.height(24.dp))

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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Submit Request", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FinanceCalculatorScreen(onBack: () -> Unit) {
    var expression by remember { mutableStateOf("") }
    var resultDisplay by remember { mutableStateOf("") }

    fun evaluate(expr: String): String {
        return try {
            val res = object : Any() {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < expr.length) expr[pos].toInt() else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.toInt()) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < expr.length) throw RuntimeException("Unexpected: " + ch.toChar())
                    return x
                }

                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        if (eat('+'.toInt())) x += parseTerm()
                        else if (eat('-'.toInt())) x -= parseTerm()
                        else return x
                    }
                }

                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        if (eat('*'.toInt())) x *= parseFactor()
                        else if (eat('/'.toInt())) x /= parseFactor()
                        else return x
                    }
                }

                fun parseFactor(): Double {
                    if (eat('+'.toInt())) return parseFactor()
                    if (eat('-'.toInt())) return -parseFactor()
                    var x: Double
                    val startPos = pos
                    if (eat('('.toInt())) {
                        x = parseExpression()
                        eat(')'.toInt())
                    } else if (ch >= '0'.toInt() && ch <= '9'.toInt() || ch == '.'.toInt()) {
                        while (ch >= '0'.toInt() && ch <= '9'.toInt() || ch == '.'.toInt()) nextChar()
                        x = expr.substring(startPos, pos).toDouble()
                    } else {
                        throw RuntimeException("Unexpected: " + ch.toChar())
                    }
                    return x
                }
            }.parse()
            if (res % 1.0 == 0.0) res.toLong().toString() else String.format(Locale.US, "%.2f", res)
        } catch (e: Exception) {
            "Error"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader("Calculator", onBack)
        
        Card(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .height(110.dp) // Fixed height for 2 rows to prevent keypad shifting
                    .padding(horizontal = 24.dp, vertical = 16.dp), 
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = expression.ifEmpty { "0" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (resultDisplay.isNotEmpty()) "= $resultDisplay" else "",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val buttons = listOf(
            listOf("(", ")", "C", "DEL"),
            listOf("7", "8", "9", "/"),
            listOf("4", "5", "6", "*"),
            listOf("1", "2", "3", "-"),
            listOf("0", ".", "=", "+")
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            buttons.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { label ->
                        val isOp = label in listOf("/", "*", "-", "+", "=", "(", ")")
                        val isSpec = label in listOf("C", "DEL")
                        
                        Button(
                            onClick = {
                                when (label) {
                                    "C" -> { expression = ""; resultDisplay = "" }
                                    "DEL" -> if (expression.isNotEmpty()) expression = expression.dropLast(1)
                                    "=" -> if (expression.isNotEmpty()) resultDisplay = evaluate(expression)
                                    else -> {
                                        if (resultDisplay.isNotEmpty()) {
                                            if (label in "0123456789.") {
                                                expression = label
                                            } else {
                                                expression = resultDisplay + label
                                            }
                                            resultDisplay = ""
                                        } else {
                                            expression += label
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(64.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    label == "=" -> MaterialTheme.colorScheme.primary
                                    isOp -> MaterialTheme.colorScheme.secondaryContainer
                                    isSpec -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = when {
                                    label == "=" -> MaterialTheme.colorScheme.onPrimary
                                    isOp -> MaterialTheme.colorScheme.onSecondaryContainer
                                    isSpec -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        ) {
                            Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoBanner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun EmptyStatePlaceholder(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Inbox, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
    }
}
