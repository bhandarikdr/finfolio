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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import com.example.data.model.ItemMetrics
import com.example.data.model.TypeMetrics
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
                Text(text = String.format(Locale.US, "%,.1f (%+.1f%%)", value, pct), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = baseColor)
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
                val ipoRepo = remember { IpoRepository() }

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

    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val indices by marketViewModel.indices.collectAsStateWithLifecycle()
    val nepseIndex = remember(indices) { indices.find { it.index.contains("NEPSE Index", true) } }
    val pendingTypeUpdate by viewModel.pendingTypeUpdate.collectAsStateWithLifecycle()
    var showReg by remember { mutableStateOf(false) }

    LaunchedEffect(userProfile) { if (userProfile != null && userProfile!!.name.isEmpty()) showReg = true }

    if (pendingTypeUpdate != null) {
        AlertDialog(onDismissRequest = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }, title = { Text("Sync Sector?") }, text = { Text("Update Sector to '${pendingTypeUpdate!!.type}' for all '${pendingTypeUpdate!!.item}' records?") }, confirmButton = { Button(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, true) }) { Text("Yes") } }, dismissButton = { TextButton(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }) { Text("No") } })
    }

    ModalNavigationDrawer(drawerState = drawerState, gesturesEnabled = false, drawerContent = {
        ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) { GlobalProfileDrawer(userProfile, { cs.launch { pagerState.animateScrollToPage(3); drawerState.close() } }, { cs.launch { drawerState.close() } }) }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { cs.launch { drawerState.open() } }) { Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }; Column(Modifier.padding(start = 8.dp)) { Text("FinFolio Pro", fontWeight = FontWeight.Bold); Text("EXECUTIVE ANALYTICS", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary) } } }, actions = { if (nepseIndex != null) NepsePillBadge(nepseIndex.index, nepseIndex.value, nepseIndex.percentChange); IconButton(onClick = { viewModel.refreshLivePrices(); if (tabs[pagerState.currentPage] == NavigationTab.MORE) marketViewModel.refreshMarketData() }) { Icon(Icons.Default.Refresh, null) } }) },
            bottomBar = { NavigationBar { tabs.forEachIndexed { i, tab -> NavigationBarItem(selected = pagerState.currentPage == i, onClick = { cs.launch { pagerState.animateScrollToPage(i) } }, label = { Text(tab.name.lowercase().capitalize()) }, icon = { Icon(when(tab){ NavigationTab.DASHBOARD -> Icons.Default.Dashboard; NavigationTab.MATRIX -> Icons.Default.TableChart; NavigationTab.DATA -> Icons.AutoMirrored.Filled.Input; NavigationTab.MORE -> Icons.Default.MoreHoriz }, null) }) } } }
        ) { inner ->
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().padding(inner)) { page ->
                when (tabs[page]) {
                    NavigationTab.DASHBOARD -> DashboardScreen(viewModel)
                    NavigationTab.MATRIX -> MatrixScreen(viewModel)
                    NavigationTab.DATA -> DataScreen(viewModel)
                    NavigationTab.MORE -> MoreScreen(marketViewModel, viewModel, ipoViewModel)
                }
            }
        }
    }
    if (showReg) RegistrationDialog { n, e -> viewModel.registerUser(n, e); showReg = false }
}

@Composable
fun GlobalProfileDrawer(user: com.example.data.model.UserProfile?, onSupport: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(0.3f)).padding(24.dp)) {
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
fun MoreScreen(marketVM: MarketViewModel, portfolioVM: PortfolioViewModel, ipoVM: BulkIpoViewModel) {
    var subView by remember { mutableStateOf<String?>(null) }
    if (subView == "Market") {
        Column {
            TextButton(onClick = { subView = null }, Modifier.padding(8.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Back") }
            MarketScreen(marketVM, portfolioVM)
        }
    } else if (subView == "BulkCheck") {
        BulkIpoCheckScreen(ipoVM) { subView = null }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text("Utilities", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { MoreGrid({ MoreCard("Markets", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF10B981)) { subView = "Market" } }, { MoreCard("Bulk IPO", Icons.AutoMirrored.Filled.FactCheck, Color(0xFFEF4444)) { subView = "BulkCheck" } }, { MoreCard("Calculator", Icons.Default.Calculate, Color(0xFF3B82F6)) {} }, { MoreCard("Converter", Icons.Default.CalendarMonth, Color(0xFFF59E0B)) {} }) }
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
fun MarketScreen(vm: MarketViewModel, pvm: PortfolioViewModel) {
    val indices by vm.filteredIndices.collectAsStateWithLifecycle(); val changes by vm.priceChanges.collectAsStateWithLifecycle()
    val allIdx by vm.indices.collectAsStateWithLifecycle(); val visIdx by vm.visibleIndices.collectAsStateWithLifecycle()
    val items by pvm.itemMetrics.collectAsStateWithLifecycle(); val wish by vm.wishlistedScrips.collectAsStateWithLifecycle()
    val pSyms = remember(items) { items.map { it.item.uppercase() }.toSet() }; val wSyms = remember(wish) { wish.map { it.symbol.uppercase() }.toSet() }
    var showSch by remember { mutableStateOf(false) }; var showCfg by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Market Pulse", fontWeight = FontWeight.Bold, fontSize = 20.sp); Row { IconButton(onClick = { showCfg = true }) { Icon(Icons.Default.Settings, null) }; IconButton(onClick = { showSch = true }) { Icon(Icons.Default.Search, null) } } } }
        item { ExpandableHeader("Indices", indices.size, true, Color(0xFF3B82F6)) }
        indices.chunked(2).forEach { row -> item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Box(modifier = Modifier.weight(1f)) { MarketIndexCard(it) } }; if (row.size == 1) Spacer(modifier = Modifier.weight(1f)) } } }
        val hM = changes.filter { it.symbol in pSyms }; val wM = changes.filter { it.symbol in wSyms && it.symbol !in pSyms }
        item { ExpandableHeader("My Holdings", hM.size, true, Color(0xFF10B981)) }
        items(hM) { MoverCard(it, true) }
        item { ExpandableHeader("Watchlist", wM.size, true, Color(0xFFF59E0B)) }
        items(wM) { MoverCard(it, false) }
    }
    if (showSch) ScripSearchDialog(vm.allScripMaster.collectAsStateWithLifecycle().value, { vm.toggleWishlist(it) }) { showSch = false }
    if (showCfg) IndicesConfigDialog(allIdx, visIdx, { vm.toggleIndexVisibility(it) }) { showCfg = false }
}

@Composable
fun ExpandableHeader(t: String, c: Int, ex: Boolean, clr: Color) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (ex) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = clr); Text(t, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)); Spacer(Modifier.width(8.dp)); Badge(containerColor = clr.copy(0.1f)) { Text(c.toString(), color = clr, fontWeight = FontWeight.Bold) } } }

@Composable
fun MarketIndexCard(idx: NepseIndex) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(idx.index, fontSize = 10.sp, maxLines = 1); Text(String.format(Locale.US, "%,.1f", idx.value), fontWeight = FontWeight.Bold)
            val c = if (idx.percentChange >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Text(String.format(Locale.US, "%+.2f%%", idx.percentChange), color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MoverCard(m: ScripPriceChange, isH: Boolean) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Row { Text(m.symbol, fontWeight = FontWeight.Bold); if (isH) { Spacer(Modifier.width(8.dp)); Badge { Text("Holding", fontSize = 8.sp) } } }; Text("LTP: ${m.ltp}", fontSize = 11.sp, color = Color.Gray) }
            val c = if (m.change >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)
            Column(horizontalAlignment = Alignment.End) { Text(String.format(Locale.US, "%+.1f", m.change), color = c, fontWeight = FontWeight.Bold); Text(String.format(Locale.US, "%+.2f%%", m.percentChange), color = c, fontSize = 12.sp) }
        }
    }
}

@Composable
fun ScripSearchDialog(all: List<ScripMaster>, onT: (ScripMaster) -> Unit, onD: () -> Unit) {
    var q by remember { mutableStateOf("") }; val filtered = remember(q, all) { if (q.isBlank()) all.take(20) else all.filter { it.symbol.contains(q, true) || it.name.contains(q, true) }.take(50) }
    Dialog(onDismissRequest = onD) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.7f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Search Scrips", fontWeight = FontWeight.Bold); OutlinedTextField(value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                    items(filtered) { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(s.symbol, fontWeight = FontWeight.Bold); Text(s.name, fontSize = 10.sp, maxLines = 1) }
                            IconButton(onClick = { onT(s) }) { Icon(if (s.isWishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (s.isWishlisted) Color.Red else Color.Gray) }
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
                LazyColumn(Modifier.weight(1f, false)) { items(all) { idx -> Row(Modifier.fillMaxWidth().clickable { onT(idx.index) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(vis.contains(idx.index), { onT(idx.index) }); Text(idx.index) } } }
                TextButton(onClick = onD, Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkIpoCheckScreen(vm: BulkIpoViewModel, onBack: () -> Unit) {
    val comps by vm.companies.collectAsStateWithLifecycle(); val sel by vm.selectedCompany.collectAsStateWithLifecycle()
    val boids by vm.boids.collectAsStateWithLifecycle(); val res by vm.results.collectAsStateWithLifecycle(); val isC by vm.isChecking.collectAsStateWithLifecycle()
    var showA by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }; Text("Bulk IPO Result", fontWeight = FontWeight.Bold) }
        var exp by remember { mutableStateOf(false) }; ExposedDropdownMenuBox(exp, { exp = it }) {
            OutlinedTextField(value = sel?.name ?: "Select IPO", onValueChange = {}, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) }, modifier = Modifier.fillMaxWidth().menuAnchor(), shape = RoundedCornerShape(12.dp))
            ExposedDropdownMenu(exp, { exp = false }) { comps.forEach { DropdownMenuItem(text = { Text(it.name) }, onClick = { vm.selectCompany(it); exp = false }) } }
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Family BOIDs (${boids.size})", fontWeight = FontWeight.Bold); Button(onClick = { showA = true }) { Text("Add BOID") } }
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) { if (res.isNotEmpty()) items(res) { IpoResultItem(it) } else items(boids) { BoidItem(it) { vm.removeBoid(it) } } }
        Button(onClick = { vm.startBulkCheck() }, Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp), enabled = !isC && boids.isNotEmpty() && sel != null, shape = RoundedCornerShape(16.dp)) {
            if (isC) CircularProgressIndicator(color = Color.White) else Text("Check Results", fontWeight = FontWeight.Bold)
        }
    }
    if (showA) AddBoidDialog({ n, b -> vm.addBoid(n, b); showA = false }, { showA = false })
}

@Composable
fun IpoResultItem(r: BulkIpoResult) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = if (r.result?.success == true) Color(0xFFE8F5E9) else if (r.result?.success == false) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(r.boidEntry.name, fontWeight = FontWeight.Bold); Text(r.boidEntry.boid, fontSize = 10.sp) }
            if (r.isChecking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else if (r.result != null) Text(r.result!!.message, color = if (r.result!!.success) Color(0xFF2E7D32) else Color(0xFFC62828), fontWeight = FontWeight.Bold, fontSize = 11.sp, textAlign = TextAlign.End)
        }
    }
}

@Composable
fun BoidItem(b: BoidEntry, onR: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(b.name, fontWeight = FontWeight.Bold); Text(b.boid, fontSize = 10.sp) }; IconButton(onClick = onR) { Icon(Icons.Default.Delete, null, tint = Color.Red) } } }
}

@Composable
fun AddBoidDialog(onA: (String, String) -> Unit, onD: () -> Unit) {
    var n by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onD, title = { Text("Add BOID") }, text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Name") }); OutlinedTextField(b, { if (it.length <= 16) b = it }, label = { Text("16-digit BOID") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) } }, confirmButton = { Button(onClick = { if (n.isNotBlank() && b.length == 16) onA(n, b) }) { Text("Add") } }, dismissButton = { TextButton(onD) { Text("Cancel") } })
}

@Composable
fun DashboardScreen(vm: PortfolioViewModel) {
    val items by vm.itemMetrics.collectAsStateWithLifecycle(); val types by vm.typeMetrics.collectAsStateWithLifecycle(); val scope by vm.datasetScope.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ExecutiveScopeSelector(scope) { vm.setDatasetScope(it) } }
        val tInv = items.sumOf { it.netInvest }; val tEval = items.sumOf { it.evaluation }; val tBuy = items.sumOf { it.buyAmount }; val tGain = items.sumOf { it.netGain }; val tProf = items.sumOf { it.profitAmount }
        item { ValuationSummaryCard(tInv, tEval, tGain, if (tBuy > 0.0) (tGain/tBuy)*100.0 else 0.0, if (tInv > 0.0) (tProf/tInv)*100.0 else 0.0) }
        item { SectorAllocationCard(types, tInv) }
        item { TopPerformersSection(items) }
    }
}

@Composable
fun ValuationSummaryCard(tInv: Double, tEval: Double, tGain: Double, gr: Double, prof: Double, compact: Boolean = false) {
    val c = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF1D4ED8)))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(if (compact) 16.dp else 24.dp)) {
        Column(Modifier.background(c).padding(if (compact) 16.dp else 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Portfolio", color = Color.White.copy(0.8f), fontSize = 12.sp); Row { Badge(containerColor = if (gr >= 0) Color(0xFF4ADE80) else Color(0xFFF87171)) { Text(String.format(Locale.US, "%+.1f%% G", gr), color = Color.White) }; Spacer(Modifier.width(4.dp)); Badge(containerColor = if (prof >= 0) Color(0xFF4ADE80) else Color(0xFFF87171)) { Text(String.format(Locale.US, "%+.1f%% P", prof), color = Color.White) } } }
            Text(String.format(Locale.US, "$%,.0f", tEval), fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.White.copy(0.2f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("INVEST", color = Color.White.copy(0.7f), fontSize = 9.sp); Text(String.format(Locale.US, "$%,.0f", tInv), color = Color.White, fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text("GAIN", color = Color.White.copy(0.7f), fontSize = 9.sp); Text(String.format(Locale.US, "$%,.0f", tGain), color = if (tGain >= 0) Color(0xFF4ADE80) else Color(0xFFF87171), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun SectorAllocationCard(m: List<TypeMetrics>, t: Double) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Allocations", fontWeight = FontWeight.Bold)
            if (m.isEmpty() || t == 0.0) Text("No data", color = Color.Gray)
            else {
                val colors = listOf(Color(0xFF00D2C4), Color(0xFF2ECE7B), Color(0xFFFFB300), Color(0xFFEB4D4B))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(80.dp)) {
                        var start = -90f
                        m.forEachIndexed { i, met -> val sweep = (met.netInvest / t).toFloat() * 360f; if (sweep > 0) { drawArc(colors[i % colors.size], start, sweep, false, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round)); start += sweep } }
                    }
                    Column(Modifier.padding(start = 16.dp)) { m.take(5).forEachIndexed { i, met -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(colors[i % colors.size])); Text(met.type, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp)) } } }
                }
            }
        }
    }
}

@Composable
fun TopPerformersSection(m: List<ItemMetrics>) {
    val top = m.sortedByDescending { it.netGain }.take(5)
    if (top.isEmpty()) return
    Column { Text("Performers", fontWeight = FontWeight.Bold); Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { top.forEach { met -> Card(Modifier.width(120.dp)) { Column(Modifier.padding(10.dp)) { Text(met.item, fontWeight = FontWeight.Bold); Text(String.format(Locale.US, "$%,.0f", met.netGain), color = if (met.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.Bold) } } } } }
}

@Composable
fun MatrixScreen(vm: PortfolioViewModel) {
    val scope by vm.datasetScope.collectAsStateWithLifecycle(); val filter by vm.selectedTypeFilter.collectAsStateWithLifecycle()
    val items by vm.itemMetrics.collectAsStateWithLifecycle(); val types by vm.typeMetrics.collectAsStateWithLifecycle(); val dTypes by vm.distinctTypes.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }; var showCfg by remember { mutableStateOf(false) }
    val iCols by vm.itemColumns.collectAsStateWithLifecycle(); val tCols by vm.typeColumns.collectAsStateWithLifecycle()
    val fItems = if (tab == 0) { if (filter == "All") items else items.filter { it.type.equals(filter, true) } } else emptyList()
    val tInv = if (tab == 0) fItems.sumOf { it.netInvest } else types.sumOf { it.netInvest }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ValuationSummaryCard(tInv, fItems.sumOf{it.evaluation}, fItems.sumOf{it.netGain}, 0.0, 0.0, true)
        Spacer(Modifier.height(8.dp)); ExecutiveScopeSelector(scope) { vm.setDatasetScope(it) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Tab(tab == 0, { tab = 0 }, Modifier.weight(1f).background(if (tab == 0) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)).padding(8.dp)) { Text("Items", fontWeight = FontWeight.Bold) }
            Tab(tab == 1, { tab = 1 }, Modifier.weight(1f).background(if (tab == 1) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)).padding(8.dp)) { Text("Sectors", fontWeight = FontWeight.Bold) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (tab == 0) { var exp by remember { mutableStateOf(false) }; Box { Button(onClick = { exp = true }, modifier = Modifier.height(36.dp)) { Text("Filter: $filter", fontSize = 10.sp); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(exp, { exp = false }) { DropdownMenuItem(text = { Text("All") }, onClick = { vm.setSelectedTypeFilter("All"); exp = false }); dTypes.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { vm.setSelectedTypeFilter(it); exp = false }) } } } } else Spacer(Modifier.width(10.dp))
            Button(onClick = { showCfg = true }, modifier = Modifier.height(36.dp)) { Text("Columns", fontSize = 10.sp) }
        }
        Box(Modifier.weight(1f).padding(top = 8.dp)) { if (tab == 0) ItemMatrixTable(fItems, iCols) else TypesMatrixTable(types, tCols) }
    }
    if (showCfg) ColumnConfigurationDialog(tab == 0, if (tab == 0) iCols else tCols, { if (tab == 0) vm.toggleItemColumn(it) else vm.toggleTypeColumn(it) }, { showCfg = false })
}

@Composable
fun ItemMatrixTable(items: List<ItemMetrics>, cols: Set<String>) {
    val scroll = rememberScrollState(); val w = 80.dp
    Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp)) { MatrixCellText("Scrip", FontWeight.Bold, 80.dp); Row(Modifier.horizontalScroll(scroll)) { if (cols.contains("Buy_Amount")) MatrixCellText("Buy", FontWeight.Bold, w); if (cols.contains("Net_Invest")) MatrixCellText("Invest", FontWeight.Bold, w); if (cols.contains("Evaluation")) MatrixCellText("Eval", FontWeight.Bold, w); if (cols.contains("Net_Gain")) MatrixCellText("Gain", FontWeight.Bold, w) } }
        LazyColumn(Modifier.fillMaxSize()) { items(items) { r -> Row(Modifier.padding(vertical = 8.dp)) { MatrixCellText(r.item, FontWeight.Bold, 80.dp, color = MaterialTheme.colorScheme.primary); Row(Modifier.horizontalScroll(scroll)) { if (cols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.0f", r.buyAmount), width = w); if (cols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netInvest), width = w); if (cols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.0f", r.evaluation), width = w); if (cols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netGain), width = w, color = if (r.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)) } } } }
    }
}

@Composable
fun TypesMatrixTable(types: List<TypeMetrics>, cols: Set<String>) {
    val scroll = rememberScrollState(); val w = 100.dp
    Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp)) { MatrixCellText("Sector", FontWeight.Bold, 110.dp); Row(Modifier.horizontalScroll(scroll)) { if (cols.contains("Net_Invest")) MatrixCellText("Invest", FontWeight.Bold, w); if (cols.contains("Evaluation")) MatrixCellText("Eval", FontWeight.Bold, w); if (cols.contains("Net_Gain")) MatrixCellText("Gain", FontWeight.Bold, w) } }
        LazyColumn(Modifier.fillMaxSize()) { items(types) { r -> Row(Modifier.padding(vertical = 8.dp)) { MatrixCellText(r.type, FontWeight.Bold, 110.dp, color = MaterialTheme.colorScheme.primary); Row(Modifier.horizontalScroll(scroll)) { if (cols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netInvest), width = w); if (cols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.0f", r.evaluation), width = w); if (cols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.0f", r.netGain), width = w, color = if (r.netGain >= 0) Color(0xFF2ECE7B) else Color(0xFFEF4444)) } } } }
    }
}

@Composable
fun ColumnConfigurationDialog(isItem: Boolean, active: Set<String>, onT: (String) -> Unit, onD: () -> Unit) {
    val opts = if (isItem) listOf("Buy_Amount", "Net_Invest", "Evaluation", "Net_Gain") else listOf("Net_Invest", "Evaluation", "Net_Gain")
    Dialog(onDismissRequest = onD) { Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(16.dp)) { Text("Columns", fontWeight = FontWeight.Bold); LazyColumn { items(opts) { c -> Row(Modifier.fillMaxWidth().clickable { onT(c) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(active.contains(c), { onT(c) }); Text(c.replace("_", " ")) } } }; TextButton(onD, Modifier.align(Alignment.End)) { Text("Done") } } } }
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
    AlertDialog(onDismissRequest = {}, title = { Text("Personalize") }, text = { Column { OutlinedTextField(n, { n = it }, label = { Text("Name") }); OutlinedTextField(e, { e = it }, label = { Text("Email") }) } }, confirmButton = { Button({ if (n.isNotBlank() && e.isNotBlank()) onR(n, e) }) { Text("Register") } })
}

@Composable
fun RowEntryForm(dI: List<String>, dT: List<String>, onS: (TransactionRecord) -> Unit) {
    var item by remember { mutableStateOf("") }; var type by remember { mutableStateOf("") }; var action by remember { mutableStateOf("Buy") }; var qty by remember { mutableStateOf("") }; var amt by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AutoCompleteTextField("Scrip", item, { item = it.uppercase() }, dI)
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
fun EditTransactionDialog(r: TransactionRecord, dI: List<String>, dT: List<String>, onS: (TransactionRecord) -> Unit, onD: () -> Unit) {
    var item by remember { mutableStateOf(r.item) }; var qty by remember { mutableStateOf(r.qty.toString()) }; var amt by remember { mutableStateOf(r.amount.toString()) }
    Dialog(onD) { Card(Modifier.padding(16.dp), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Edit Transaction", fontWeight = FontWeight.Bold); OutlinedTextField(item, { item = it.uppercase() }, label = { Text("Item") }); OutlinedTextField(qty, { qty = it }, label = { Text("Qty") }); OutlinedTextField(amt, { amt = it }, label = { Text("Amount") }); Row(Modifier.fillMaxWidth(), Arrangement.End) { TextButton(onD) { Text("Cancel") }; Spacer(Modifier.width(8.dp)); Button({ val q = qty.toDoubleOrNull() ?: 0.0; val a = amt.toDoubleOrNull() ?: 0.0; onS(r.copy(item = item, qty = q, amount = a)) }) { Text("Save") } } } } }
}

@Composable
fun AutoCompleteTextField(l: String, v: String, onV: (String) -> Unit, sug: List<String>) {
    var ex by remember { mutableStateOf(false) }; val fil = remember(v, sug) { if (v.isEmpty()) sug.take(5) else sug.filter { it.contains(v, true) }.take(10) }
    Box { OutlinedTextField(v, onV, label = { Text(l) }, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) ex = true }, singleLine = true); DropdownMenu(ex, { ex = false }) { fil.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { onV(s); ex = false }) } } }
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
        item { Text("Record Transaction", fontWeight = FontWeight.Bold); RowEntryForm(dItems, dTypes) { pAdd = it } }
        if (showMS) item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Filled.TrendingUp, null); Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("WACC Loaded", fontWeight = FontWeight.Bold); Text("Sync Prices Now.", fontSize = 11.sp) }; Button(onClick = { msLauncher.launch("*/*") }) { Text("Sync") } } } }
        item { UnifiedIOCard(txLauncher, waLauncher, msLauncher, exLauncher) }
        item { Text("History (${transactions.size})", fontWeight = FontWeight.Bold); FilterDropdown(filter, dItems) { filter = it } }
        txY.forEach { (y, list) ->
            item { YearHeader(y, list.size, expY.contains(y)) { expY = if (expY.contains(y)) expY - y else expY + y } }
            if (expY.contains(y)) items(list) { TransactionListItem(it, { editingRec = it }, { pDel = it }) }
        }
    }
    if (showImport) AlertDialog({ showImport = false }, title = { Text("Import Mode") }, text = { Text("Append or Overwrite existing?") }, confirmButton = { Button({ viewModel.importTransactions(csvText!!, false, isWacc); showImport = false; showMS = true }) { Text("Append") } }, dismissButton = { TextButton({ viewModel.importTransactions(csvText!!, true, isWacc); showImport = false; showMS = true }) { Text("Overwrite") } })
    if (editingRec != null) EditTransactionDialog(editingRec!!, dItems, dTypes, { pUpd = it; editingRec = null }, { editingRec = null })
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
