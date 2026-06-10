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
fun NepsePillBadge(index: String, value: Double, pct: Double) {
    val isPositive = pct >= 0
    val baseColor = if (isPositive) Color(0xFF10B981) else Color(0xFFEF4444)
    Surface(modifier = Modifier.padding(end = 8.dp), shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, baseColor.copy(0.3f))) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(baseColor))
            Column(horizontalAlignment = Alignment.End) {
                Text(text = index, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(text = String.format(Locale.US, "%,.1f (%+.2f%%)", value, pct), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = baseColor)
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
                val repository = remember { PortfolioRepository(database.portfolioDao()) }
                val marketRepo = remember { MarketRepository(database.portfolioDao()) }
                val ipoRepo = remember { IpoRepository(database.portfolioDao()) }

                val portfolioVM: PortfolioViewModel = viewModel(factory = PortfolioViewModelFactory(repository))
                val marketVM: MarketViewModel = viewModel(factory = MarketViewModelFactory(marketRepo))
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
    val nepseIndex = remember(indices) { indices.find { it.index.contains("NEPSE Index", true) } }
    val pendingTypeUpdate by viewModel.pendingTypeUpdate.collectAsStateWithLifecycle()
    var showReg by remember { mutableStateOf(false) }
    var currentSubView by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(userProfile) { if (userProfile != null && userProfile!!.name.isEmpty()) showReg = true }

    if (pendingTypeUpdate != null) {
        AlertDialog(onDismissRequest = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }, title = { Text("Sync Sector?") }, text = { Text("Update Sector to '${pendingTypeUpdate!!.type}' for all '${pendingTypeUpdate!!.item}' records?") }, confirmButton = { Button(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, true) }) { Text("Yes") } }, dismissButton = { TextButton(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }) { Text("No") } })
    }

    ModalNavigationDrawer(
        drawerState = drawerState, gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) { 
                GlobalProfileDrawer(userProfile, 
                    onSupport = { 
                        cs.launch { 
                            pagerState.animateScrollToPage(3)
                            currentSubView = "Contact"
                            drawerState.close() 
                        } 
                    }, 
                    onClose = { cs.launch { drawerState.close() } }
                ) 
            }
        }
    ) {
        Scaffold(
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { cs.launch { drawerState.open() } }) { Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }; Column(Modifier.padding(start = 8.dp)) { Text("FinFolio Pro", fontWeight = FontWeight.Bold); Text("EXECUTIVE ANALYTICS", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary) } } }, actions = { if (nepseIndex != null) NepsePillBadge(nepseIndex.index, nepseIndex.value, nepseIndex.percentChange); IconButton(onClick = { 
                viewModel.refreshLivePrices()
                marketViewModel.refreshMarketData()
                Toast.makeText(context, "Refreshing market data...", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.Refresh, null) } }) },
            bottomBar = { NavigationBar { tabs.forEachIndexed { i, tab -> NavigationBarItem(selected = pagerState.currentPage == i, onClick = { cs.launch { pagerState.animateScrollToPage(i) } }, label = { Text(tab.name.lowercase().capitalize()) }, icon = { Icon(when(tab){ NavigationTab.DASHBOARD -> Icons.Default.Dashboard; NavigationTab.MATRIX -> Icons.Default.TableChart; NavigationTab.DATA -> Icons.AutoMirrored.Filled.Input; NavigationTab.MORE -> Icons.Default.MoreHoriz }, null) }) } } }
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
fun GlobalProfileDrawer(user: com.example.data.model.UserProfile?, onSupport: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(0.3f)).padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.padding(12.dp)) }
                Spacer(Modifier.width(16.dp)); Column { Text(user?.name ?: "Guest", fontWeight = FontWeight.Bold); Text(user?.email ?: "local@finfolio.app", fontSize = 12.sp) }
                Spacer(Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
        }
        DrawerItem(Icons.Default.Person, "My Profile"); DrawerItem(Icons.Default.Settings, "Settings"); DrawerItem(Icons.AutoMirrored.Filled.HelpCenter, "Support") { onSupport() }
        Spacer(Modifier.weight(1f)); DrawerItem(Icons.AutoMirrored.Filled.Logout, "Logout", MaterialTheme.colorScheme.error); Text("Version 2.5.0", Modifier.padding(16.dp).align(Alignment.CenterHorizontally), fontSize = 10.sp, color = Color.Gray)
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
        "Contact" -> DeveloperProfilePanel(userProfile?.name ?: "User", userProfile?.email ?: "") { onSubViewChange(null) }
        else -> {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { Text("Utilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { 
                    MoreGrid(
                        { MoreCard("Markets", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF10B981)) { onSubViewChange("Market") } }, 
                        { MoreCard("Bulk IPO", Icons.AutoMirrored.Filled.FactCheck, Color(0xFFEF4444)) { onSubViewChange("BulkCheck") } }, 
                        { MoreCard("IPO Master", Icons.Default.Inventory, Color(0xFF8B5CF6)) { onSubViewChange("IpoMaster") } },
                        { MoreCard("Support", Icons.Default.SupportAgent, Color(0xFF3B82F6)) { onSubViewChange("Contact") } }, 
                        { MoreCard("Calculator", Icons.Default.Calculate, Color(0xFFF59E0B)) {} }
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
            Spacer(Modifier.height(4.dp)); Text(t, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MarketScreen(vm: MarketViewModel, pvm: PortfolioViewModel, onBack: () -> Unit) {
    val indices by vm.filteredIndices.collectAsStateWithLifecycle(); val changes by vm.priceChanges.collectAsStateWithLifecycle()
    val allIdx by vm.indices.collectAsStateWithLifecycle(); val visIdx by vm.visibleIndices.collectAsStateWithLifecycle()
    val items by pvm.itemMetrics.collectAsStateWithLifecycle(); val wishMovers by vm.watchlistMovers.collectAsStateWithLifecycle()
    val pSyms = remember(items) { items.map { it.item.uppercase() }.toSet() }
    var showSch by remember { mutableStateOf(false) }; var showCfg by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf("Global") } // "Global" or "ScripOnly"

    var expInd by remember { mutableStateOf(true) }
    var expHol by remember { mutableStateOf(true) }
    var expWis by remember { mutableStateOf(true) }
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
            indices.chunked(2).forEach { row -> item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Box(modifier = Modifier.weight(1f)) { MarketIndexCard(it) } }; if (row.size == 1) Spacer(modifier = Modifier.weight(1f)) } } }
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
                    items(scrips) { MoverCard(it, true) }
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
            items(wM) { MoverCard(it, false) }
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
fun MarketIndexCard(idx: NepseIndex) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(idx.index, fontSize = 10.sp, maxLines = 1, color = Color.Gray); 
            Row(verticalAlignment = Alignment.Bottom) {
                Text(String.format(Locale.US, "%,.1f", idx.value), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(String.format(Locale.US, "Prev: %,.1f", idx.previousValue), fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 2.dp))
            }
            val c = if (idx.percentChange >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Text(String.format(Locale.US, "%+.2f (%+.2f%%)", idx.change, idx.percentChange), color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MoverCard(m: ScripPriceChange, isH: Boolean) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { 
                Row(verticalAlignment = Alignment.CenterVertically) { 
                    Text(m.symbol, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    if (isH) { 
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) { Text("Holding", fontSize = 8.sp) } 
                    } 
                }
                Text(String.format(Locale.US, "LTP: %,.1f (Prev: %,.1f)", m.ltp, m.previousLtp), fontSize = 11.sp, color = Color.Gray) 
            }
            val c = if (m.change >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Column(horizontalAlignment = Alignment.End) { 
                Text(String.format(Locale.US, "%+.1f", m.change), color = c, fontWeight = FontWeight.Bold)
                Text(String.format(Locale.US, "%+.2f%%", m.percentChange), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold) 
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
                        item { Spacer(Modifier.height(8.dp)); Text("SCRIPS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray) }
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
                                        Text(s.name, fontSize = 10.sp, color = Color.Gray) 
                                        if (!hasLtp) {
                                            Text("No live data available", fontSize = 9.sp, color = Color.Red.copy(0.7f))
                                        }
                                    }
                                    IconButton(onClick = { onToggleWishlist(s) }) { 
                                        Icon(
                                            if (s.isWishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder, 
                                            null, 
                                            tint = if (s.isWishlisted) Color.Red else Color.Gray
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
        else ipos.filter { it.companyName.contains(searchQuery, true) || it.companyCode?.contains(searchQuery, true) == true }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "Bulk IPO Result",
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { vm.syncIpos() }, enabled = !isS) { 
                    if (isS) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null) 
                }
            }
        )
        
        Spacer(Modifier.height(16.dp))
        
        ExposedDropdownMenuBox(exp, { exp = it }) {
            OutlinedTextField(
                value = searchQuery.ifBlank { sel?.companyName ?: "Select IPO" },
                onValueChange = { searchQuery = it; exp = true },
                label = { Text("Search IPO") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                singleLine = true
            )
            
            if (filteredIpos.isNotEmpty()) {
                ExposedDropdownMenu(exp, { exp = false }) {
                    filteredIpos.forEach { ipo ->
                        DropdownMenuItem(
                            text = { 
                                Column {
                                    Text(ipo.companyName, fontWeight = FontWeight.Bold)
                                    if (!ipo.companyCode.isNullOrBlank()) {
                                        Text(ipo.companyCode, fontSize = 10.sp, color = Color.Gray)
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
                Text(r.boidEntry.name, fontWeight = FontWeight.Bold)
                Text(r.boidEntry.boid, fontSize = 10.sp, color = Color.Gray) 
            }
            
            Box(contentAlignment = Alignment.CenterEnd) {
                if (r.isChecking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (r.result != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (isSuccess) "ALLOTTED" else "NOT ALLOTTED",
                            color = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                        if (isSuccess) {
                            val unitsMatch = """(\d+)\s+units""".toRegex().find(r.result.message)
                            val units = unitsMatch?.groupValues?.get(1) ?: "Check"
                            Text("$units Units", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        } else {
                            Text(r.result.message, fontSize = 9.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else if (r.error != null) {
                    Text(r.error, color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        title = { Text("Paste BOIDs") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Paste BOIDs (Comma or Line separated)") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                placeholder = { Text("13010200000001\n13010200000002") }
            )
        },
        confirmButton = { Button(onClick = { onA(text) }) { Text("Import") } },
        dismissButton = { TextButton(onD) { Text("Cancel") } }
    )
}

@Composable
fun IpoMasterScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val ipos by vm.ipos.collectAsStateWithLifecycle()
    val isS by vm.isSyncing.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubScreenHeader(
            title = "IPO Master List",
            onBack = onBack,
            trailingIcon = {
                IconButton(onClick = { vm.syncIpos() }, enabled = !isS) {
                    if (isS) CircularProgressIndicator(Modifier.size(20.dp))
                    else Icon(Icons.Default.Refresh, null)
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(ipos) { ipo ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ipo.companyName, fontWeight = FontWeight.Bold)
                            Text("ID: ${ipo.cdscCompanyId} | ${ipo.companyCode ?: "No Symbol"}", fontSize = 10.sp, color = Color.Gray)
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(ipo.status, fontSize = 9.sp)
                        }
                    }
                }
            }
            if (ipos.isEmpty() && !isS) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No IPOs found.", color = Color.Gray)
                            Button(onClick = { vm.syncIpos() }, Modifier.padding(top = 8.dp)) {
                                Text("Sync from CDSC")
                            }
                        }
                    }
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
    val items by vm.itemMetrics.collectAsStateWithLifecycle(); val types by vm.typeMetrics.collectAsStateWithLifecycle(); val scope by vm.datasetScope.collectAsStateWithLifecycle()
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
        item { ValuationSummaryCard(tEval, tInv, tRec, tGain, tGrowth, tProf, tProfitPct) }
        item { SectorAllocationCard(types, tInv) }
        item { PerformersSection(items) }
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
    compact: Boolean = false
) {
    val c = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF1D4ED8)))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 16.dp else 24.dp)) {
        Column(Modifier.background(c).padding(if (compact) 16.dp else 24.dp)) {
            Text("Portfolio Value", color = Color.White.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(String.format(Locale.US, "$%,.0f", portfolio), fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(0.2f))
            
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    MetricItem(Modifier.weight(1f), "INVEST", invest)
                    MetricItem(Modifier.weight(1f), "RECEIVABLE", receivable, horizontalAlignment = Alignment.End)
                }
                Row(Modifier.fillMaxWidth()) {
                    MetricItem(Modifier.weight(1f), "NET GAIN", netGain, growthPct, isGain = true)
                    MetricItem(Modifier.weight(1f), "PROFIT", profit, profitPct, isGain = true, horizontalAlignment = Alignment.End)
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
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Text(label, color = Color.White.copy(0.7f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (isGain) {
                if (value >= 0) Color(0xFF4ADE80) else Color(0xFFF87171)
            } else Color.White
            Text(String.format(Locale.US, "$%,.0f", value), color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
fun SectorAllocationCard(m: List<TypeMetrics>, t: Double) {
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
                            Text(String.format(Locale.US, "$%,.0f", t), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
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
fun PerformersSection(m: List<ItemMetrics>) {
    val gainers = m.filter { it.netGain > 0 }.sortedByDescending { it.netGain }.take(2)
    val losers = m.filter { it.netGain < 0 }.sortedBy { it.netGain }.take(2)

    if (gainers.isEmpty() && losers.isEmpty()) return

    Column {
        Text("Market Performers", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOP GAINERS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2ECE7B))
                gainers.forEach { PerformerCard(it) }
                if (gainers.isEmpty()) Text("No gainers", fontSize = 10.sp, color = Color.Gray)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOP LOSERS", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEF4444))
                losers.forEach { PerformerCard(it) }
                if (losers.isEmpty()) Text("No losers", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun PerformerCard(met: ItemMetrics) {
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
                text = String.format(Locale.US, "$%,.0f", met.netGain),
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
        ValuationSummaryCard(tEval, tInv, tRec, tGain, tGrowth, tProf, tProfitPct, true)
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
                    dTypes.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { vm.setSelectedTypeFilter(it); exp = false }) } 
                } 
            }
            
            Text("${fItems.size} Scrips", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)

            Button(onClick = { showCfg = true }, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(8.dp)) { 
                Icon(Icons.Default.ViewColumn, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Columns", fontSize = 10.sp) 
            }
        }
        Box(Modifier.weight(1f).padding(top = 12.dp)) { ItemMatrixTable(fItems, iCols) }
    }
    if (showCfg) ColumnConfigurationDialog(true, iCols, { vm.toggleItemColumn(it) }, { showCfg = false })
}

@Composable
fun ItemMatrixTable(items: List<ItemMetrics>, cols: Set<String>) {
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
        LazyColumn(Modifier.fillMaxSize()) { 
            items(items) { r -> 
                Row(Modifier.padding(vertical = 8.dp)) { 
                    MatrixCellText(r.item, FontWeight.Bold, 80.dp, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(scroll)) { 
                        if (cols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.0f", r.buyAmount), width = w)
                        if (cols.contains("Buy_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.buyQty), width = w)
                        if (cols.contains("Buy_Count")) MatrixCellText(r.buyCount.toString(), width = w)
                        if (cols.contains("Sale_Amount")) MatrixCellText(String.format(Locale.US, "%,.0f", r.saleAmount), width = w)
                        if (cols.contains("Sale_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.saleQty), width = w)
                        if (cols.contains("Sale_Count")) MatrixCellText(r.saleCount.toString(), width = w)
                        if (cols.contains("Returns_Cash")) MatrixCellText(String.format(Locale.US, "%,.0f", r.returnsCash), width = w)
                        if (cols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.returnsQty), width = w)
                        if (cols.contains("Return_Count")) MatrixCellText(r.returnCount.toString(), width = w)
                        if (cols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.0f", r.balanceQty), width = w)
                        if (cols.contains("Avg_CP")) MatrixCellText(String.format(Locale.US, "%,.1f", r.avgCp), width = w)
                        if (cols.contains("Avg_SP")) MatrixCellText(String.format(Locale.US, "%,.1f", r.avgSp), width = w)
                        if (cols.contains("LTP")) MatrixCellText(String.format(Locale.US, "%,.1f", r.ltp), width = w)
                        if (cols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netInvest), width = w)
                        if (cols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.0f", r.evaluation), width = w)
                        if (cols.contains("Realized_Gain")) MatrixCellText(String.format(Locale.US, "%,.0f", r.realizedGain), width = w, color = if (r.realizedGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Unrealized_Gain")) MatrixCellText(String.format(Locale.US, "%,.0f", r.unrealizedGain), width = w, color = if (r.unrealizedGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Deductions")) MatrixCellText(String.format(Locale.US, "%,.0f", r.deductions), width = w, color = Color.Gray)
                        if (cols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netGain), width = w, color = if (r.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%.1f%%", r.growth), width = w, color = if (r.growth >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Receivable_Amount")) MatrixCellText(String.format(Locale.US, "%,.0f", r.receivableAmount), width = w)
                        if (cols.contains("Profit_Amount")) MatrixCellText(String.format(Locale.US, "%,.0f", r.profitAmount), width = w, color = if (r.profitAmount >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                        if (cols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%.1f%%", r.profitPercent), width = w, color = if (r.profitPercent >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444))
                    } 
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
    )
    Dialog(onDismissRequest = onD) { 
        Card(shape = RoundedCornerShape(16.dp)) { 
            Column(Modifier.padding(16.dp)) { 
                Text("Configure Columns", fontWeight = FontWeight.Bold)
                LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) { 
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

@Composable
fun RowEntryForm(dI: List<String>, dT: List<String>, onGetSector: suspend (String) -> String, onS: (TransactionRecord) -> Unit) {
    var item by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("Buy") }
    var qty by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    val cs = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AutoCompleteTextField("Scrip", item, { 
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
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                var ex by remember { mutableStateOf(false) }; Box(Modifier.weight(1f)) { OutlinedButton({ ex = true }, Modifier.fillMaxWidth()) { Text(action); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(ex, { ex = false }) { listOf("Buy", "Sale", "Returns").forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { action = a; ex = false }) } } }
                OutlinedTextField(qty, { qty = it }, label = { Text("Qty") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(amt, { amt = it }, label = { Text("Amount ($)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = { val q = qty.toDoubleOrNull() ?: 0.0; val a = amt.toDoubleOrNull() ?: 0.0; if (item.isNotBlank()) { onS(TransactionRecord(date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()), item = item, type = type, action = action, qty = q, amount = a)); item = ""; qty = ""; amt = "" } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Add Record") }
        }
    }
}

@Composable
fun TransactionListItem(tx: TransactionRecord, onE: () -> Unit, onD: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val clr = when(tx.action){ "Buy" -> Color(0xFF2ECE7B); "Sale" -> Color(0xFFEF4444); else -> Color(0xFF3B82F6) }
            Box(Modifier.size(36.dp).clip(CircleShape).background(clr.copy(0.1f)), contentAlignment = Alignment.Center) { Text(tx.action.take(1), color = clr, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(tx.item, fontWeight = FontWeight.Bold); if (tx.isSystemAdjustment) Icon(Icons.Default.AutoFixHigh, null, Modifier.size(12.dp), Color.Gray) }; Text("${tx.date} • ${tx.type}", fontSize = 10.sp, color = Color.Gray) }
            Column(horizontalAlignment = Alignment.End) { Text(String.format(Locale.US, "$%,.0f", tx.amount), fontWeight = FontWeight.Bold); Text("${tx.qty} units", fontSize = 10.sp, color = Color.Gray) }
            IconButton(onClick = onE) { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
            IconButton(onClick = onD) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = Color.Red) }
        }
    }
}

@Composable
fun EditTransactionDialog(r: TransactionRecord, dI: List<String>, dT: List<String>, onGetSector: suspend (String) -> String, onS: (TransactionRecord) -> Unit, onD: () -> Unit) {
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
                OutlinedTextField(qty, { qty = it }, label = { Text("Qty") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
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
        if (v.isEmpty()) sug.take(15) 
        else sug.filter { it.contains(v, true) }.take(25) 
    }
    Box { 
        OutlinedTextField(
            value = v, 
            onValueChange = onV, 
            label = { Text(l) }, 
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) ex = true }, 
            singleLine = true
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
    val context = LocalContext.current; val transactions by viewModel.allTransactions.collectAsStateWithLifecycle(); val dItems by viewModel.distinctItems.collectAsStateWithLifecycle(); val dTypes by viewModel.distinctTypes.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }; var csvText by remember { mutableStateOf<String?>(null) }; var isWacc by remember { mutableStateOf(false) }; var showMS by remember { mutableStateOf(false) }
    var pMS by remember { mutableStateOf<String?>(null) }; var pDel by remember { mutableStateOf<TransactionRecord?>(null) }; var pAdd by remember { mutableStateOf<TransactionRecord?>(null) }
    var pUpd by remember { mutableStateOf<TransactionRecord?>(null) }; var editingRec by remember { mutableStateOf<TransactionRecord?>(null) }
    var filter by remember { mutableStateOf("All") }; var expY by remember { mutableStateOf(setOf<String>()) }; val cs = rememberCoroutineScope()

    val msLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) pMS = t } }
    val txLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = false; showImport = true } } }
    val waLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) cs.launch { val t = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(it)?.use { it.bufferedReader().readText() } }; if (t != null) { csvText = t; isWacc = true; showImport = true } } }
    val exLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { if (it != null) cs.launch { val c = buildString { append("Date,Item,Action,Qty,Amount,Type\n"); transactions.forEach { append("${it.date},${it.item},${it.action},${it.qty},${it.amount},${it.type}\n") } }; withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(it)?.use { it.bufferedWriter().write(c) } }; withContext(Dispatchers.Main) { Toast.makeText(context, "Exported", Toast.LENGTH_SHORT).show() } } }

    val txY = remember(transactions, filter) { (if (filter == "All") transactions else transactions.filter { it.item.equals(filter, true) }).groupBy { it.date.split("-").firstOrNull() ?: "Unknown" }.toSortedMap(compareByDescending { it }) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { 
            Text("Record Transaction", fontWeight = FontWeight.Bold)
            RowEntryForm(dItems, dTypes, onGetSector = { viewModel.getSectorForScrip(it) }) { pAdd = it } 
        }
        if (showMS) item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.TrendingUp, null); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("WACC Loaded", fontWeight = FontWeight.Bold); Text("Sync Prices Now.", fontSize = 11.sp) }; Button(onClick = { msLauncher.launch("*/*") }) { Text("Sync") } } } }
        item { UnifiedIOCard(txLauncher, waLauncher, msLauncher, exLauncher) }
        item { Text("History (${transactions.size})", fontWeight = FontWeight.Bold); FilterDropdown(filter, dItems) { filter = it } }
        txY.forEach { (y, list) ->
            item { YearHeader(y, list.size, expY.contains(y)) { expY = if (expY.contains(y)) expY - y else expY + y } }
            if (expY.contains(y)) items(list) { TransactionListItem(it, { editingRec = it }, { pDel = it }) }
        }
    }
    if (showImport) AlertDialog({ showImport = false }, title = { Text("Import Mode") }, text = { Text("Append or Overwrite existing?") }, confirmButton = { Button({ viewModel.importTransactions(csvText!!, false, isWacc); showImport = false; showMS = true }) { Text("Append") } }, dismissButton = { TextButton({ viewModel.importTransactions(csvText!!, true, isWacc); showImport = false; showMS = true }) { Text("Overwrite") } })
    if (editingRec != null) EditTransactionDialog(editingRec!!, dItems, dTypes, onGetSector = { viewModel.getSectorForScrip(it) }, onS = { pUpd = it; editingRec = null }, onD = { editingRec = null })
    if (pMS != null) AlertDialog({ pMS = null }, title = { Text("Align Portfolio?") }, confirmButton = { Button({ viewModel.importMeroshare(pMS!!); pMS = null; showMS = false }) { Text("Proceed") } }, dismissButton = { TextButton({ pMS = null }) { Text("Cancel") } })
    if (pDel != null) AlertDialog({ pDel = null }, title = { Text("Delete?") }, confirmButton = { Button({ viewModel.deleteTransaction(pDel!!); pDel = null }) { Text("Delete") } }, dismissButton = { TextButton({ pDel = null }) { Text("Cancel") } })
    if (pAdd != null) AlertDialog({ pAdd = null }, title = { Text("Add?") }, confirmButton = { Button({ viewModel.addTransaction(pAdd!!); pAdd = null }) { Text("Add") } }, dismissButton = { TextButton({ pAdd = null }) { Text("Cancel") } })
    if (pUpd != null) AlertDialog({ pUpd = null }, title = { Text("Update?") }, confirmButton = { Button({ viewModel.updateTransaction(pUpd!!); pUpd = null }) { Text("Update") } }, dismissButton = { TextButton({ pUpd = null }) { Text("Cancel") } })
}

@Composable
fun UnifiedIOCard(tx: androidx.activity.result.ActivityResultLauncher<String>, wa: androidx.activity.result.ActivityResultLauncher<String>, ms: androidx.activity.result.ActivityResultLauncher<String>, ex: androidx.activity.result.ActivityResultLauncher<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f))) {
        Column(Modifier.padding(16.dp)) {
            Text("I/O Management", fontWeight = FontWeight.Bold)
            Button(onClick = { tx.launch("*/*") }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Import Transaction CSV") }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ wa.launch("*/*") }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.tertiary)) { Text("WACC") }
                Button({ ms.launch("*/*") }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.secondary)) { Text("Portfolio") }
            }
            Button({ ex.launch("finfolio_export.csv") }, Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(Color(0xFF2E7D32))) { Text("Export History") }
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
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = type.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Gray,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.width(8.dp))
        Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
            Text(count.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
        SubScreenHeader("Support & Contact", onBack)
        
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primaryContainer.copy(0.3f), MaterialTheme.colorScheme.surface)
                        )
                    )
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                        shadowElevation = 8.dp
                    ) {
                        Icon(
                            imageVector = Icons.Default.SupportAgent,
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Kedar Bhandari",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Lead Developer • FinFolio Pro",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/bkedarnp"))) },
                            label = { Text("GitHub") },
                            leadingIcon = { Icon(Icons.Default.Code, null, Modifier.size(16.dp)) }
                        )
                        AssistChip(
                            onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:bkedarnp@gmail.com"))) },
                            label = { Text("Email") },
                            leadingIcon = { Icon(Icons.Default.AlternateEmail, null, Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Direct Support",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "We typically respond within 24 hours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject") },
                    placeholder = { Text("What is this regarding?") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Topic, null, Modifier.size(20.dp)) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = customMessage,
                    onValueChange = { customMessage = it },
                    label = { Text("Message") },
                    placeholder = { Text("Please describe your issue in detail...") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Box(Modifier.fillMaxHeight().padding(top = 12.dp, start = 12.dp)) { Icon(Icons.AutoMirrored.Filled.Message, null, Modifier.size(20.dp)) } }
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
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Submit Request", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                
                Spacer(modifier = Modifier.height(40.dp)) // Padding for keyboard
            }
        }
    }
}
