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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.HelpCenter
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.data.model.ItemMetrics
import com.example.data.model.TypeMetrics
import com.example.data.repository.PortfolioRepository
import com.example.data.repository.MarketRepository
import com.example.data.work.ScrapeWorker
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DatasetScope
import com.example.ui.viewmodel.PortfolioViewModel
import com.example.ui.viewmodel.PortfolioViewModelFactory
import com.example.ui.viewmodel.MarketViewModel
import com.example.ui.viewmodel.MarketViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun NepsePillBadge(status: com.example.data.model.NepseStatus) {
    val isPositive = status.isPositive
    val emeraldGreen = Color(0xFF10B981)
    val crimsonRed = Color(0xFFEF4444)
    val baseColor = if (isPositive) emeraldGreen else crimsonRed
    
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        modifier = Modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Brush.horizontalGradient(
            listOf(baseColor.copy(alpha = 0.5f), baseColor.copy(alpha = 0.1f))
        ))
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(baseColor.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live Status Indicator (Pulse dot)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(baseColor.copy(alpha = pulseAlpha))
            )

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = status.index,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isPositive) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = baseColor
                    )
                    Spacer(Modifier.width(4.dp))
                    val changeText = buildString {
                        if (status.change.isNotEmpty()) {
                            append(status.change)
                            append(" ")
                        }
                        append("(${status.percentChange})")
                    }
                    Text(
                        text = changeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = baseColor
                    )
                }


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Start live trading crawler background schedule
        try {
            val workRequest = PeriodicWorkRequestBuilder<ScrapeWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "ScrapeLiveTrading",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest,
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val database = remember { AppDatabase.getDatabase(context) }
                val repository = remember { PortfolioRepository(database.portfolioDao()) }
                val marketRepository = remember { com.example.data.repository.MarketRepository(database.portfolioDao()) }
                
                val factory = remember { PortfolioViewModelFactory(repository) }
                val viewModel: PortfolioViewModel = viewModel(factory = factory)
                
                val marketFactory = remember { com.example.ui.viewmodel.MarketViewModelFactory(marketRepository) }
                val marketViewModel: com.example.ui.viewmodel.MarketViewModel = viewModel(factory = marketFactory)

                val ipoRepository = remember { com.example.data.repository.IpoRepository() }
                val ipoFactory = remember { com.example.ui.viewmodel.BulkIpoViewModelFactory(ipoRepository) }
                val ipoViewModel: com.example.ui.viewmodel.BulkIpoViewModel = viewModel(factory = ipoFactory)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PortfolioAppContent(viewModel, marketViewModel, ipoViewModel)
                }


enum class NavigationTab {
    DASHBOARD,
    MATRIX,
    DATA,
    MORE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioAppContent(
    viewModel: PortfolioViewModel,
    marketViewModel: MarketViewModel,
    ipoViewModel: com.example.ui.viewmodel.BulkIpoViewModel
) {
    val context = LocalContext.current
    val tabs = listOf(NavigationTab.DASHBOARD, NavigationTab.MATRIX, NavigationTab.DATA, NavigationTab.MORE)
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()
    
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val nepseStatus by viewModel.nepseStatus.collectAsStateWithLifecycle()
    val pendingTypeUpdate by viewModel.pendingTypeUpdate.collectAsStateWithLifecycle()
    var showRegistration by remember { mutableStateOf(false) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val activeTab = tabs[pagerState.currentPage]
    LaunchedEffect(activeTab) {
        if (activeTab == NavigationTab.MORE) { // More screen contains Market data
             marketViewModel.refreshMarketData()
        }
    }

    LaunchedEffect(userProfile) {
        if (userProfile != null && userProfile!!.name.isEmpty()) {
            showRegistration = true
        }
    }

    
    // SnackBar notification observer
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(key1 = true) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }


    if (pendingTypeUpdate != null) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) },
            title = { androidx.compose.material3.Text("Sync Sector/Type?", fontWeight = FontWeight.Bold) },
            text = { androidx.compose.material3.Text("Do you want to update the Sector to '${pendingTypeUpdate!!.type}' for ALL existing '${pendingTypeUpdate!!.item}' records in your database?") },
            confirmButton = {
                Button(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, true) }) {
                    androidx.compose.material3.Text("Yes, Sync All")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmTypeUpdate(pendingTypeUpdate!!, false) }) {
                    androidx.compose.material3.Text("No, Only This One")
                }
            }
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                    drawerContainerColor = MaterialTheme.colorScheme.surface
                ) {
                    GlobalProfileDrawer(
                        userProfile = userProfile,
                        onNavigateToSupport = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(3)
                                drawerState.close()
                            }
                        },
                        onClose = { coroutineScope.launch { drawerState.close() } }
                    )
                }
            }
        ) {
            Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { coroutineScope.launch { drawerState.open() } }
                                ) {
                                    Box(
                                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_launcher_background),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                            contentDescription = "App Logo",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        androidx.compose.material3.Text(
                                            text = "FinFolio Pro",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 18.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        androidx.compose.material3.Text(
                                            text = "EXECUTIVE ANALYTICS",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            actions = {
                                val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
                                
                                // Live NEPSE Index Info
                                if (nepseStatus.index != "0.00") {
                                    NepsePillBadge(nepseStatus)
                                }

                                IconButton(onClick = { 
                                    viewModel.refreshLivePrices()
                                    marketViewModel.refreshMarketData()
                                }) {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Update LTP",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            NavigationBarItem(
                                selected = pagerState.currentPage == 0,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                                label = { androidx.compose.material3.Text("Dashboard") },
                                icon = {
                                    Icon(
                                        imageVector = if (pagerState.currentPage == 0) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                                        contentDescription = "Dashboard"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 1,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                                label = { androidx.compose.material3.Text("Matrices") },
                                icon = {
                                    Icon(
                                        imageVector = if (pagerState.currentPage == 1) Icons.Filled.TableChart else Icons.Outlined.TableChart,
                                        contentDescription = "Matrices"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 2,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(2) } },
                                label = { androidx.compose.material3.Text("Data & CSV") },
                                icon = {
                                    Icon(
                                        imageVector = if (pagerState.currentPage == 2) Icons.AutoMirrored.Filled.Input else Icons.AutoMirrored.Outlined.Input,
                                        contentDescription = "Data"
                                    )
                                }
                            )
                            NavigationBarItem(
                                selected = pagerState.currentPage == 3,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(3) } },
                                label = { androidx.compose.material3.Text("More") },
                                icon = {
                                    Icon(
                                        imageVector = if (pagerState.currentPage == 3) Icons.Filled.MoreHoriz else Icons.Outlined.MoreHoriz,
                                        contentDescription = "More"
                                    )
                                }
                            )
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.background),
                        beyondViewportPageCount = 1,
                        userScrollEnabled = true
                    ) { page ->
                        when (tabs[page]) {
                            NavigationTab.DASHBOARD -> DashboardScreen(viewModel)
                            NavigationTab.MATRIX -> MatrixScreen(viewModel)
                            NavigationTab.DATA -> DataScreen(viewModel)
                            NavigationTab.MORE -> MoreScreen(marketViewModel, viewModel, ipoViewModel)
                        }
                    }
                }
            }
        }

    if (showRegistration) {
        RegistrationDialog(
            onRegister = { name, email ->
                viewModel.registerUser(name, email)
                showRegistration = false
            }
        )
    }
}

@Composable
fun SchemaReferenceItem(title: String, schema: String, color: Color) {
    Column {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
        SelectionContainer {
            Text(
                schema,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = color
            )
        }
    }
}

@Composable
fun RegistrationDialog(onRegister: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { }, // Force registration
        title = { Text("Welcome to FinFolio", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Please register to personalize your experience.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && email.isNotBlank()) onRegister(name, email) },
                enabled = name.isNotBlank() && email.isNotBlank()
            ) {
                Text("Get Started")
            }
        }
    )
}

@Composable
fun DeveloperProfilePanel(
    userName: String,
    userEmail: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var customMessage by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }

    // Top-aligned layout using Column and verticalScroll
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        // Professional Header Section
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface)
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

// ==========================================
// 1. EXECUTIVE ANALYTICS PORTAL (DASHBOARD)
// ==========================================

@Composable
fun DashboardScreen(viewModel: PortfolioViewModel) {
    val currentScope by viewModel.datasetScope.collectAsStateWithLifecycle()
    val itemMetrics by viewModel.itemMetrics.collectAsStateWithLifecycle()
    val typeMetrics by viewModel.typeMetrics.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ExecutiveScopeSelector(currentScope) { scope ->
                viewModel.setDatasetScope(scope)
            }
        }

        // Calculations & Valuation Summary Area
        val totalNetInvest = itemMetrics.sumOf { it.netInvest }
        val totalEvaluation = itemMetrics.sumOf { it.evaluation }
        val totalBuyAmt = itemMetrics.sumOf { it.buyAmount }
        val totalNetGain = itemMetrics.sumOf { it.netGain }
        val totalProfitAmt = itemMetrics.sumOf { it.profitAmount }

        val overallGrowthPercent = if (totalBuyAmt > 0.0) (totalNetGain / totalBuyAmt) * 100.0 else 0.0
        val overallProfitPercent = when {
            totalNetInvest > 0.0 -> (totalProfitAmt / totalNetInvest) * 100.0
            (totalNetInvest == 0.0) && (totalBuyAmt > 0.0) -> (totalProfitAmt / totalBuyAmt) * 100.0
            else -> 0.0
        }

        item {
            ValuationSummaryCard(
                totalNetInvest = totalNetInvest,
                totalEvaluation = totalEvaluation,
                totalNetGain = totalNetGain,
                overallGrowthPercent = overallGrowthPercent,
                overallProfitPercent = overallProfitPercent
            )
        }

        // Segment Canvas Sector Allocation Ring
        item {
            SectorAllocationCard(typeMetrics = typeMetrics, totalNetInvest = totalNetInvest)
        }

        // Top Performers section
        item {
            TopPerformersSection(itemMetrics = itemMetrics)
        }
    }
}

@Composable
fun ExecutiveScopeSelector(
    selectedScope: DatasetScope,
    onScopeSelected: (DatasetScope) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val scopeOptions = listOf(
            DatasetScope.OVERALL to "Overall Portfolio",
            DatasetScope.MEROSHARE to "Portfolio Data"
        )
        
        scopeOptions.forEach { (scope, label) ->
            val isSelected = selectedScope == scope
            val tag = if (scope == DatasetScope.OVERALL) "scope_overall_tab" else "scope_meroshare_tab"
            val icon = if (scope == DatasetScope.OVERALL) Icons.Filled.AccountBalanceWallet else Icons.Filled.Share
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent
                    )
                    .clickable { onScopeSelected(scope) }
                    .padding(vertical = 10.dp)
                    .testTag(tag),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }


@Composable
fun ValuationSummaryCard(
    totalNetInvest: Double,
    totalEvaluation: Double,
    totalNetGain: Double,
    overallGrowthPercent: Double,
    overallProfitPercent: Double,
    isCompact: Boolean = false
) {
    val isPositiveGain = totalNetGain >= 0.0
    val isPositiveGrowth = overallGrowthPercent >= 0.0
    val isPositiveProfit = overallProfitPercent >= 0.0
    
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            Color(0xFF1D4ED8) // Rich Blue 700
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (isCompact) 16.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = gradientBrush)
                .padding(if (isCompact) 14.dp else 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                androidx.compose.material3.Text(
                    text = "Total Evaluation",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = if (isCompact) 12.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val growthBadgeText = String.format(Locale.US, "%s%.1f%% G", if (isPositiveGrowth) "+" else "", overallGrowthPercent)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPositiveGrowth) Color(0xFF4ADE80).copy(alpha = 0.9f) else Color(0xFFF87171).copy(alpha = 0.9f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        androidx.compose.material3.Text(
                            text = growthBadgeText,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    val profitBadgeText = String.format(Locale.US, "%s%.1f%% P", if (isPositiveProfit) "+" else "", overallProfitPercent)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isPositiveProfit) Color(0xFF4ADE80).copy(alpha = 0.9f) else Color(0xFFF87171).copy(alpha = 0.9f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        androidx.compose.material3.Text(
                            text = profitBadgeText,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            androidx.compose.material3.Text(
                text = String.format(Locale.US, "$%,.2f", totalEvaluation),
                fontSize = if (isCompact) 24.sp else 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = if (isCompact) 10.dp else 20.dp),
                color = Color.White.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    androidx.compose.material3.Text(
                        text = "NET INVEST",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    androidx.compose.material3.Text(
                        text = String.format(Locale.US, "$%,.0f", totalNetInvest),
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 15.sp else 18.sp,
                        color = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.Text(
                        text = "NET GAIN",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPositiveGain) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = "Gain direction",
                            tint = if (isPositiveGain) Color(0xFF4ADE80) else Color(0xFFF87171),
                            modifier = Modifier.size(if (isCompact) 14.dp else 16.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        androidx.compose.material3.Text(
                            text = String.format(Locale.US, "$%,.0f", totalNetGain),
                            color = if (isPositiveGain) Color(0xFF4ADE80) else Color(0xFFF87171),
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 15.sp else 18.sp
                        )
                    }
                }


@Composable
fun SectorAllocationCard(
    typeMetrics: List<TypeMetrics>,
    totalNetInvest: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "Sector Asset Allocation",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Percentage distribution of total Net Investment",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )

            Spacer(Modifier.height(20.dp))

            if (typeMetrics.isEmpty() || totalNetInvest == 0.0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PieChart,
                        contentDescription = "Empty Allocations",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No data recorded in database",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                // Multi-color allocations colors
                val colors = listOf(
                    Color(0xFF00D2C4), // Teal
                    Color(0xFF2ECE7B), // Jade
                    Color(0xFFFFB300), // Amber
                    Color(0xFFEB4D4B), // Crimson
                    Color(0xFF9370DB), // Purple
                    Color(0xFF3498DB), // Sky Blue
                    Color(0xFF95A5A6)  // Slate Grey
                )

                val allocations = typeMetrics.mapIndexed { idx, met ->
                    val pct = if (totalNetInvest > 0.0) (met.netInvest / totalNetInvest) * 100.0 else 0.0
                    val col = colors[idx % colors.size]
                    Triple(met.type, pct, col)
                }.sortedByDescending { it.second }

                // Arc layout donut drawing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Canvas(
                        modifier = Modifier
                            .size(130.dp)
                            .padding(8.dp)
                    ) {
                        var currentStartAngle = -90f
                        allocations.forEach { (_, pct, color) ->
                            val sweep = (pct.toFloat() / 100f) * 360f
                            if (sweep > 0f) {
                                drawArc(
                                    color = color,
                                    startAngle = currentStartAngle,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
                                )
                                currentStartAngle += sweep
                            }
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allocations.take(5).forEach { (label, pct, color) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = String.format(Locale.US, "%.1f%%", pct),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (allocations.size > 5) {
                            val othersSum = allocations.drop(5).sumOf { it.second }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Others", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(String.format(Locale.US, "%.1f%%", othersSum), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }


@Composable
fun TopPerformersSection(itemMetrics: List<ItemMetrics>) {
    Column {
        Text(
            text = "Highest Performing Scrips",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.onSurface
        )

        val topPerformers = remember(itemMetrics) {
            itemMetrics.sortedByDescending { it.netGain }.take(5)
        }

        if (topPerformers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No profitable assets recorded yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                topPerformers.forEach { met ->
                    val cardBorder = if (met.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B)
                    Card(
                        modifier = Modifier
                            .width(170.dp)
                            .height(140.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.2.dp, cardBorder.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = met.item,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (met.isInMeroshareCsv) {
                                        Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                                            Text("MS", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
                                        }
                                    }
                                }
                                Text(
                                    text = met.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column {
                                val profitCol = if (met.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B)
                                Text(
                                    text = String.format(Locale.US, "%s$%,.2f", if (met.netGain >= 0.0) "+" else "-", Math.abs(met.netGain)),
                                    color = profitCol,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.1f%% Margin", met.growth),
                                    color = profitCol.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }



// ==========================================
// 2. DUAL TAB PRESENTATION MATRIX VIEWS
// ==========================================

@Composable
fun MatrixScreen(viewModel: PortfolioViewModel) {
    val datasetScope by viewModel.datasetScope.collectAsStateWithLifecycle()
    val typeFilter by viewModel.selectedTypeFilter.collectAsStateWithLifecycle()
    val items by viewModel.itemMetrics.collectAsStateWithLifecycle()
    val types by viewModel.typeMetrics.collectAsStateWithLifecycle()
    val distinctTypesList by viewModel.distinctTypes.collectAsStateWithLifecycle()

    var activeTabIdx by remember { mutableIntStateOf(0) } // 0: Items Tab, 1: Types Tab
    var showConfigDialog by remember { mutableStateOf(false) }

    val activeItemCols by viewModel.itemColumns.collectAsStateWithLifecycle()
    val activeTypeCols by viewModel.typeColumns.collectAsStateWithLifecycle()

    val filteredItems = if (activeTabIdx == 0) {
        if (typeFilter == "All") items else items.filter { it.type.equals(typeFilter, ignoreCase = true) }
    } else emptyList()

    val displayTypes = if (activeTabIdx == 1) types else emptyList()

    // Calculations for the dynamic Summary Card
    val totalNetInvest = if (activeTabIdx == 0) filteredItems.sumOf { it.netInvest } else displayTypes.sumOf { it.netInvest }
    val totalEvaluation = if (activeTabIdx == 0) filteredItems.sumOf { it.evaluation } else displayTypes.sumOf { it.evaluation }
    val totalNetGain = if (activeTabIdx == 0) filteredItems.sumOf { it.netGain } else displayTypes.sumOf { it.netGain }
    val totalBuyAmt = if (activeTabIdx == 0) filteredItems.sumOf { it.buyAmount } else displayTypes.sumOf { it.buyAmount }
    val totalProfitAmt = if (activeTabIdx == 0) filteredItems.sumOf { it.profitAmount } else displayTypes.sumOf { it.profitAmount }

    val overallGrowthPercent = if (totalBuyAmt > 0.0) (totalNetGain / totalBuyAmt) * 100.0 else 0.0
    val overallProfitPercent = when {
        totalNetInvest > 0.0 -> (totalProfitAmt / totalNetInvest) * 100.0
        (totalNetInvest == 0.0) && (totalBuyAmt > 0.0) -> (totalProfitAmt / totalBuyAmt) * 100.0
        else -> 0.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Summary Card reflecting filtered data
        ValuationSummaryCard(
            totalNetInvest = totalNetInvest,
            totalEvaluation = totalEvaluation,
            totalNetGain = totalNetGain,
            overallGrowthPercent = overallGrowthPercent,
            overallProfitPercent = overallProfitPercent,
            isCompact = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Upper switcher and controls
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ExecutiveScopeSelector(datasetScope) { scope ->
                viewModel.setDatasetScope(scope)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Tab(
                selected = activeTabIdx == 0,
                onClick = { activeTabIdx = 0 },
                modifier = Modifier.weight(1f).background(if (activeTabIdx == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp).testTag("matrix_items_tab"),
            ) {
                Text(
                    text = "Items Matrix",
                    fontWeight = FontWeight.Bold,
                    color = if (activeTabIdx == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Tab(
                selected = activeTabIdx == 1,
                onClick = { activeTabIdx = 1 },
                modifier = Modifier.weight(1f).background(if (activeTabIdx == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp).testTag("matrix_types_tab"),
            ) {
                Text(
                    text = "Types Matrix",
                    fontWeight = FontWeight.Bold,
                    color = if (activeTabIdx == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Configuration Checklist dialog trigger
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type dropdown for Items filter
            if (activeTabIdx == 0) {
                Box(modifier = Modifier.width(180.dp)) {
                    var expandedDropdown by remember { mutableStateOf(false) }
                    Button(
                        onClick = { expandedDropdown = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().height(36.dp).testTag("type_filter_dropdown"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Type: $typeFilter", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, "down", modifier = Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Types") },
                            onClick = {
                                viewModel.setSelectedTypeFilter("All")
                                expandedDropdown = false
                            }
                        )
                        distinctTypesList.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    viewModel.setSelectedTypeFilter(type)
                                    expandedDropdown = false
                                }
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }

            Button(
                onClick = { showConfigDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier.height(36.dp).testTag("configure_columns_btn"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Filled.Settings, "Config", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Configure Columns", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Large Responsive Table view
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (activeTabIdx == 0) {
                ItemMatrixTable(items = filteredItems, activeCols = activeItemCols)
            } else {
                TypesMatrixTable(types = displayTypes, activeCols = activeTypeCols)
            }
        }
    }


    if (showConfigDialog) {
        ColumnConfigurationDialog(
            isItemTable = activeTabIdx == 0,
            activeCols = if (activeTabIdx == 0) activeItemCols else activeTypeCols,
            onToggleColumn = { col ->
                if (activeTabIdx == 0) viewModel.toggleItemColumn(col) else viewModel.toggleTypeColumn(col)
            },
            onDismiss = { showConfigDialog = false }
        )
    }
}

@Composable
fun ItemMatrixTable(
    items: List<ItemMetrics>,
    activeCols: Set<String>
) {
    val scrollState = rememberScrollState()
    val cellWidth = 110.dp
    val firstColWidth = 115.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FIXED First Column Header
            MatrixCellText(
                text = "Scrip Symbol",
                fontWeight = FontWeight.Bold,
                width = firstColWidth,
                marginStart = 12.dp
            )
            // Scrollable columns
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(vertical = 12.dp)
            ) {
                if (activeCols.contains("Buy_Amount")) MatrixCellText("Buy Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Count")) MatrixCellText("Buy Count", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Qty")) MatrixCellText("Buy Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Amount")) MatrixCellText("Sale Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Count")) MatrixCellText("Sale Count", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Qty")) MatrixCellText("Sale Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Balance_Qty")) MatrixCellText("Bal Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Avg_CP")) MatrixCellText("Avg CP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Avg_SP")) MatrixCellText("Avg SP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("LTP")) MatrixCellText("LTP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Invest")) MatrixCellText("Net Invest ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Returns_Qty")) MatrixCellText("Returns Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Returns_Cash")) MatrixCellText("Returns Cash ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Evaluation")) MatrixCellText("Evaluation ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Realized_Gain")) MatrixCellText("Realized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Unrealized_Gain")) MatrixCellText("Unrealized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Deductions")) MatrixCellText("Deductions ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Gain")) MatrixCellText("Net Gain ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Growth")) MatrixCellText("Growth (%)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Receivable_Amount")) MatrixCellText("Receivable ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Amount")) MatrixCellText("Profit ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Percent")) MatrixCellText("Profit (%)", fontWeight = FontWeight.Bold, width = cellWidth)
            }
        }

        // Table Body
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No records map this view", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // FIXED First Column Cell
                            Surface(
                                modifier = Modifier.width(firstColWidth),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp
                            ) {
                                MatrixCellText(
                                    text = row.item,
                                    fontWeight = FontWeight.Bold,
                                    width = firstColWidth,
                                    marginStart = 12.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            // Scrollable data cells
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(scrollState)
                                    .padding(vertical = 10.dp)
                            ) {
                                if (activeCols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.buyAmount), width = cellWidth)
                                if (activeCols.contains("Buy_Count")) MatrixCellText(row.buyCount.toString(), width = cellWidth)
                                if (activeCols.contains("Buy_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.buyQty), width = cellWidth)
                                if (activeCols.contains("Sale_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.saleAmount), width = cellWidth)
                                if (activeCols.contains("Sale_Count")) MatrixCellText("${row.saleCount}", width = cellWidth)
                                if (activeCols.contains("Sale_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.saleQty), width = cellWidth)
                                if (activeCols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.balanceQty), width = cellWidth)
                                if (activeCols.contains("Avg_CP")) MatrixCellText(String.format(Locale.US, "%,.2f", row.avgCp), width = cellWidth)
                                if (activeCols.contains("Avg_SP")) MatrixCellText(String.format(Locale.US, "%,.2f", row.avgSp), width = cellWidth)
                                if (activeCols.contains("LTP")) MatrixCellText(String.format(Locale.US, "%,.2f", row.ltp), width = cellWidth, color = if (row.ltp > 0.0) MaterialTheme.colorScheme.onSurface else Color.Gray)
                                if (activeCols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.2f", row.netInvest), width = cellWidth)
                                if (activeCols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.returnsQty), width = cellWidth)
                                if (activeCols.contains("Returns_Cash")) MatrixCellText(String.format(Locale.US, "%,.2f", row.returnsCash), width = cellWidth)
                                if (activeCols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.2f", row.evaluation), width = cellWidth)
                                if (activeCols.contains("Realized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.realizedGain), width = cellWidth, color = if (row.realizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Unrealized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.unrealizedGain), width = cellWidth, color = if (row.unrealizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Deductions")) MatrixCellText(String.format(Locale.US, "%,.2f", row.deductions), width = cellWidth)
                                if (activeCols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.netGain), width = cellWidth, color = if (row.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%+.2f%%", row.growth), width = cellWidth, color = if (row.growth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                                if (activeCols.contains("Receivable_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.receivableAmount), width = cellWidth)
                                if (activeCols.contains("Profit_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.profitAmount), width = cellWidth, color = if (row.profitAmount >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%+.2f%%", row.profitPercent), width = cellWidth, color = if (row.profitPercent >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // Bottom Totals Row
        if (items.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // FIXED First Column Totals
                MatrixCellText(
                    text = "TOTAL SUMS",
                    fontWeight = FontWeight.Bold,
                    width = firstColWidth,
                    marginStart = 12.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                // Scrollable totals
                Row(
                    modifier = Modifier
                        .horizontalScroll(scrollState)
                        .padding(vertical = 12.dp)
                ) {
                    if (activeCols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.buyAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Buy_Count")) MatrixCellText("${items.sumOf { it.buyCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Buy_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.buyQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Sale_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.saleAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Sale_Count")) MatrixCellText("${items.sumOf { it.saleCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Sale_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.saleQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.balanceQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                    
                    if (activeCols.contains("Avg_CP")) MatrixCellText("-", fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Avg_SP")) MatrixCellText("-", fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("LTP")) MatrixCellText("-", fontWeight = FontWeight.Bold, width = cellWidth)

                    val sumNetInvest = items.sumOf { it.netInvest }
                    val sumEvaluation = items.sumOf { it.evaluation }
                    val sumRealized = items.sumOf { it.realizedGain }
                    val sumUnrealized = items.sumOf { it.unrealizedGain }
                    val sumDeductions = items.sumOf { it.deductions }
                    val sumNetGain = items.sumOf { it.netGain }
                    val sumBuyAmt = items.sumOf { it.buyAmount }
                    val overallGrowth = if (sumBuyAmt > 0.0) (sumNetGain / sumBuyAmt) * 100.0 else 0.0

                    val sumReceivable = items.sumOf { it.receivableAmount }
                    val sumProfitAmt = items.sumOf { it.profitAmount }
                    val overallProfitPct = when {
                        sumNetInvest > 0.0 -> (sumProfitAmt / sumNetInvest) * 100.0
                        sumNetInvest == 0.0 && sumBuyAmt > 0.0 -> (sumProfitAmt / sumBuyAmt) * 100.0
                        else -> 0.0
                    }

                    if (activeCols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.2f", sumNetInvest), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.returnsQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Returns_Cash")) MatrixCellText(String.format(Locale.US, "%,.2f", items.sumOf { it.returnsCash }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.2f", sumEvaluation), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Realized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumRealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumRealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Unrealized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumUnrealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumUnrealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Deductions")) MatrixCellText(String.format(Locale.US, "%,.2f", sumDeductions), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumNetGain), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumNetGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%+.2f%%", overallGrowth), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallGrowth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Receivable_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", sumReceivable), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Profit_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", sumProfitAmt), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumProfitAmt >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%+.2f%%", overallProfitPct), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallProfitPct >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                }


@Composable
fun TypesMatrixTable(
    types: List<TypeMetrics>,
    activeCols: Set<String>
) {
    val scrollState = rememberScrollState()
    val cellWidth = 110.dp
    val firstColWidth = 115.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Headers Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FIXED Category Header
            MatrixCellText(
                text = "Type Category",
                fontWeight = FontWeight.Bold,
                width = firstColWidth,
                marginStart = 12.dp
            )
            // Scrollable headers
            Row(
                modifier = Modifier
                    .horizontalScroll(scrollState)
                    .padding(vertical = 12.dp)
            ) {
                if (activeCols.contains("Item_Count")) MatrixCellText("Scrip Count", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Amount")) MatrixCellText("Buy Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Amount")) MatrixCellText("Sale Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Balance_Qty")) MatrixCellText("Bal Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Invest")) MatrixCellText("Net Invest ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Returns_Qty")) MatrixCellText("Returns Qty", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Returns_Cash")) MatrixCellText("Returns Cash ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Evaluation")) MatrixCellText("Evaluation ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Realized_Gain")) MatrixCellText("Realized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Unrealized_Gain")) MatrixCellText("Unrealized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Deductions")) MatrixCellText("Deductions ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Gain")) MatrixCellText("Net Gain ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Growth")) MatrixCellText("Growth (%)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Receivable_Amount")) MatrixCellText("Receivable ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Amount")) MatrixCellText("Profit ($)", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Percent")) MatrixCellText("Profit (%)", fontWeight = FontWeight.Bold, width = cellWidth)
            }
        }

        // Body rows listing
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (types.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No category records available", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(types) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // FIXED Category Cell
                            Surface(
                                modifier = Modifier.width(firstColWidth),
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp
                            ) {
                                MatrixCellText(
                                    text = row.type,
                                    fontWeight = FontWeight.Bold,
                                    width = firstColWidth,
                                    marginStart = 12.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            // Scrollable data cells
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(scrollState)
                                    .padding(vertical = 10.dp)
                            ) {
                                if (activeCols.contains("Item_Count")) MatrixCellText("${row.itemCount}", width = cellWidth)
                                if (activeCols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.buyAmount), width = cellWidth)
                                if (activeCols.contains("Sale_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.saleAmount), width = cellWidth)
                                if (activeCols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.balanceQty), width = cellWidth)
                                if (activeCols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.2f", row.netInvest), width = cellWidth)
                                if (activeCols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", row.returnsQty), width = cellWidth)
                                if (activeCols.contains("Returns_Cash")) MatrixCellText(String.format(Locale.US, "%,.2f", row.returnsCash), width = cellWidth)
                                if (activeCols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.2f", row.evaluation), width = cellWidth)
                                if (activeCols.contains("Realized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.realizedGain), width = cellWidth, color = if (row.realizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Unrealized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.unrealizedGain), width = cellWidth, color = if (row.unrealizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Deductions")) MatrixCellText(String.format(Locale.US, "%,.2f", row.deductions), width = cellWidth)
                                if (activeCols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", row.netGain), width = cellWidth, color = if (row.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%+.2f%%", row.growth), width = cellWidth, color = if (row.growth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                                if (activeCols.contains("Receivable_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.receivableAmount), width = cellWidth)
                                if (activeCols.contains("Profit_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", row.profitAmount), width = cellWidth, color = if (row.profitAmount >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                                if (activeCols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%+.2f%%", row.profitPercent), width = cellWidth, color = if (row.profitPercent >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // Aggregate Totals Row
        if (types.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // FIXED Total Label
                MatrixCellText(
                    text = "TOTAL SUMS",
                    fontWeight = FontWeight.Bold,
                    width = firstColWidth,
                    marginStart = 12.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                // Scrollable totals
                Row(
                    modifier = Modifier
                        .horizontalScroll(scrollState)
                        .padding(vertical = 12.dp)
                ) {
                    if (activeCols.contains("Item_Count")) MatrixCellText("${types.sumOf { it.itemCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Buy_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", types.sumOf { it.buyAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Sale_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", types.sumOf { it.saleAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Balance_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", types.sumOf { it.balanceQty }), fontWeight = FontWeight.Bold, width = cellWidth)

                    val sumNetInvest = types.sumOf { it.netInvest }
                    val sumEvaluation = types.sumOf { it.evaluation }
                    val sumRealized = types.sumOf { it.realizedGain }
                    val sumUnrealized = types.sumOf { it.unrealizedGain }
                    val sumDeductions = types.sumOf { it.deductions }
                    val sumNetGain = types.sumOf { it.netGain }
                    val sumBuyAmt = types.sumOf { it.buyAmount }
                    val overallGrowth = if (sumBuyAmt > 0.0) (sumNetGain / sumBuyAmt) * 100.0 else 0.0

                    val sumReceivable = types.sumOf { it.receivableAmount }
                    val sumProfitAmt = types.sumOf { it.profitAmount }
                    val overallProfitPct = when {
                        sumNetInvest > 0.0 -> (sumProfitAmt / sumNetInvest) * 100.0
                        sumNetInvest == 0.0 && sumBuyAmt > 0.0 -> (sumProfitAmt / sumBuyAmt) * 100.0
                        else -> 0.0
                    }

                    if (activeCols.contains("Net_Invest")) MatrixCellText(String.format(Locale.US, "%,.2f", sumNetInvest), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Returns_Qty")) MatrixCellText(String.format(Locale.US, "%,.2f", types.sumOf { it.returnsQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Returns_Cash")) MatrixCellText(String.format(Locale.US, "%,.2f", types.sumOf { it.returnsCash }), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Evaluation")) MatrixCellText(String.format(Locale.US, "%,.2f", sumEvaluation), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Realized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumRealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumRealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Unrealized_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumUnrealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumUnrealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Deductions")) MatrixCellText(String.format(Locale.US, "%,.2f", sumDeductions), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Net_Gain")) MatrixCellText(String.format(Locale.US, "%,.2f", sumNetGain), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumNetGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Growth")) MatrixCellText(String.format(Locale.US, "%+.2f%%", overallGrowth), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallGrowth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Receivable_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", sumReceivable), fontWeight = FontWeight.Bold, width = cellWidth)
                    if (activeCols.contains("Profit_Amount")) MatrixCellText(String.format(Locale.US, "%,.2f", sumProfitAmt), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumProfitAmt >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                    if (activeCols.contains("Profit_Percent")) MatrixCellText(String.format(Locale.US, "%+.2f%%", overallProfitPct), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallProfitPct >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                }


// Compact helper to render table items alignment
@Composable
fun MatrixCellText(
    text: String,
    fontWeight: FontWeight = FontWeight.Normal,
    width: androidx.compose.ui.unit.Dp,
    marginStart: androidx.compose.ui.unit.Dp = 0.dp,
    color: Color = Color.Unspecified
) {
    androidx.compose.material3.Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = marginStart)
            .width(width)
            .padding(horizontal = 4.dp),
        textAlign = TextAlign.Start
    )
}

@Composable
fun ColumnConfigurationDialog(
    isItemTable: Boolean,
    activeCols: Set<String>,
    onToggleColumn: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val optionsAll = if (isItemTable) {
        listOf(
            "Buy_Amount", "Buy_Count", "Buy_Qty", "Sale_Amount", "Sale_Count", "Sale_Qty",
            "Balance_Qty", "Avg_CP", "Avg_SP", "LTP", "Net_Invest", "Returns_Qty", "Returns_Cash",
            "Evaluation", "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent"
        )
    } else {
        listOf(
            "Item_Count", "Buy_Amount", "Sale_Amount", "Returns_Qty", "Returns_Cash", "Balance_Qty", "Net_Invest", "Evaluation",
            "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent"
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.75f)
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Configure Visible Columns",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(optionsAll) { col ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleColumn(col) }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = activeCols.contains(col),
                                onCheckedChange = { onToggleColumn(col) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = col.replace("_", " "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Save & Close", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }



// ==========================================
// 3. MARKET INTELLIGENCE HUB
// ==========================================

@Composable
fun MarketScreen(
    marketViewModel: com.example.ui.viewmodel.MarketViewModel,
    portfolioViewModel: PortfolioViewModel
) {
    val indices by marketViewModel.filteredIndices.collectAsStateWithLifecycle()
    val priceChanges by marketViewModel.priceChanges.collectAsStateWithLifecycle()
    val isLoading by marketViewModel.isLoading.collectAsStateWithLifecycle()
    val wishlisted by marketViewModel.wishlistedScrips.collectAsStateWithLifecycle()
    val portfolioItems by portfolioViewModel.itemMetrics.collectAsStateWithLifecycle()
    
    val portfolioSymbols = remember(portfolioItems) { portfolioItems.map { it.item.uppercase() }.toSet() }
    val wishlistSymbols = remember(wishlisted) { wishlisted.map { it.symbol.uppercase() }.toSet() }

    var showSearchDialog by remember { mutableStateOf(false) }
    var showIndicesConfig by remember { mutableStateOf(false) }
    var indicesExpanded by remember { mutableStateOf(true) }
    var holdingsExpanded by remember { mutableStateOf(true) }
    var wishlistExpanded by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("NEPSE Market Pulse", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Live indices and personalized watchlist", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    IconButton(onClick = { showIndicesConfig = true }) {
                        Icon(Icons.Default.Settings, "Configure Indices")
                    }
                    IconButton(onClick = { 
                        marketViewModel.refreshMarketData()
                        portfolioViewModel.refreshLivePrices()
                    }) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                }
            }
        }

        // 1. NEPSE Indices Section
        item {
            ExpandableMarketSection(
                title = "Nepal Stock Market Indices",
                count = indices.size,
                isExpanded = indicesExpanded,
                onToggle = { indicesExpanded = !indicesExpanded },
                accentColor = MaterialTheme.colorScheme.primary
            )
        }

        if (indicesExpanded) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Column Headers
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Index Name", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f))
                            Text("Previous", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            Text("Current", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            Text("Change", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f), textAlign = TextAlign.End)
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        
                        if (indices.isEmpty() && isLoading) {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            }
                        } else if (indices.isEmpty()) {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                Text("No index data available. Try refreshing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                        } else {
                            indices.forEach { idx ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        idx.index, 
                                        fontSize = 11.sp, 
                                        fontWeight = if (idx.index.contains("NEPSE")) FontWeight.ExtraBold else FontWeight.Medium,
                                        modifier = Modifier.weight(1.3f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        String.format(Locale.US, "%,.2f", idx.previousValue), 
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.End,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        String.format(Locale.US, "%,.2f", idx.value), 
                                        fontWeight = FontWeight.Bold, 
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.End
                                    )
                                    val color = if (idx.percentChange >= 0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B)
                                    Text(
                                        String.format(Locale.US, "%+.2f%%", idx.percentChange),
                                        color = color,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(0.9f),
                                        textAlign = TextAlign.End
                                    )
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            }
                        }
                    }
                }
            }
        }

        val holdingMovers = priceChanges.filter { it.symbol in portfolioSymbols }
        val wishlistMovers = priceChanges.filter { it.symbol in wishlistSymbols && it.symbol !in portfolioSymbols }

        // 2. Holdings Section
        item {
            ExpandableMarketSection(
                title = "My Holdings",
                count = holdingMovers.size,
                isExpanded = holdingsExpanded,
                onToggle = { holdingsExpanded = !holdingsExpanded },
                accentColor = MaterialTheme.colorScheme.primary
            )
        }

        if (holdingsExpanded) {
            if (holdingMovers.isEmpty()) {
                item {
                    Text("No holdings found in live data", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                }
            } else {
                items(holdingMovers) { mover ->
                    MoverCard(mover, isHolding = true)
                }
            }
        }

        // 3. Wishlist Section
        item {
            ExpandableMarketSection(
                title = "My Wishlist",
                count = wishlistMovers.size,
                isExpanded = wishlistExpanded,
                onToggle = { wishlistExpanded = !wishlistExpanded },
                accentColor = MaterialTheme.colorScheme.secondary,
                action = {
                    TextButton(onClick = { showSearchDialog = true }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            )
        }

        if (wishlistExpanded) {
            if (wishlistMovers.isEmpty()) {
                item {
                    Text("No wishlisted scrips found in live data", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                }
            } else {
                items(wishlistMovers) { mover ->
                    MoverCard(mover, isHolding = false)
                }
            }
        }
    }

    if (showSearchDialog) {
        val allScrips by marketViewModel.allScrips.collectAsStateWithLifecycle(emptyList())
        ScripSearchDialog(
            allScrips = allScrips,
            onToggleWishlist = { scrip ->
                marketViewModel.updateWishlist(scrip.copy(isWishlisted = !scrip.isWishlisted))
            },
            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (showIndicesConfig) {
        IndicesConfigDialog(
            allIndices = marketViewModel.indices.collectAsStateWithLifecycle(emptyList()).value,
            visibleIndices = marketViewModel.visibleIndices.collectAsStateWithLifecycle(emptySet()).value,
            onToggle = { marketViewModel.toggleIndexVisibility(it) },
            onDismiss = { showIndicesConfig = false }
        )
    }
}




@Composable
fun MoverCard(mover: com.example.data.repository.ScripPriceChange, isHolding: Boolean) {
    val pltp = mover.previousLtp
    val ultp = mover.ltp
    val diff = ultp - pltp
    val actualChangePercent = if (pltp > 0.0) (diff / pltp) * 100.0 else 0.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mover.symbol, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (isHolding) {
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text("Holding", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text("Previous LTP: ${String.format(Locale.US, "%,.2f", pltp)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Updated LTP: ${String.format(Locale.US, "%,.2f", ultp)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
            
            Column(horizontalAlignment = Alignment.End) {
                val color = if (diff >= 0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B)
                Text(
                    String.format(Locale.US, "%+.2f", diff),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    String.format(Locale.US, "%+.2f%%", actualChangePercent),
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun ScripSearchDialog(
    allScrips: List<com.example.data.db.ScripMaster>,
    onToggleWishlist: (com.example.data.db.ScripMaster) -> Unit,
    onManualAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(searchQuery, allScrips) {
        if (searchQuery.isBlank()) allScrips.take(20)
        else allScrips.filter { it.symbol.contains(searchQuery, ignoreCase = true) || it.name.contains(searchQuery, ignoreCase = true) }.take(50)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Search Scrips", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Symbol or Name...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(Modifier.height(12.dp))
                
                if (searchQuery.isNotEmpty() && !allScrips.any { it.symbol.equals(searchQuery, ignoreCase = true) }) {
                    Button(
                        onClick = { 
                            onManualAdd(searchQuery)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add '${searchQuery.uppercase()}' manually")
                    }
                }

                LazyColumn(Modifier.weight(1f)) {
                    items(filtered) { scrip ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(scrip.symbol, fontWeight = FontWeight.Bold)
                                Text(scrip.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(scrip.sector, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                            IconButton(onClick = { onToggleWishlist(scrip) }) {
                                Icon(
                                    imageVector = if (scrip.isWishlisted) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (scrip.isWishlisted) Color.Red else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun GlobalProfileDrawer(
    userProfile: com.example.data.model.UserProfile?,
    onNavigateToSupport: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 1. User Profile Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                .padding(top = 48.dp, bottom = 24.dp, start = 24.dp, end = 24.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp)
                    ) {
                        Image(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text(
                    text = userProfile?.name ?: "Guest User",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = userProfile?.email ?: "Sign in to sync data",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Account Section
        DrawerSectionHeader("Account")
        DrawerItem(Icons.Default.Person, "My Profile") { /* Nav to profile */ }
        DrawerItem(Icons.Default.Settings, "Profile Settings")
        DrawerItem(Icons.Default.CardMembership, "Subscription Plan")
        DrawerItem(Icons.Default.Lock, "Change Password")
        DrawerItem(Icons.Default.Notifications, "Notification Settings")
        DrawerItem(Icons.Default.Fingerprint, "Biometric Lock")

        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

        // 3. Application Section
        DrawerSectionHeader("Application")
        DrawerItem(Icons.Default.Palette, "Theme Settings")
        DrawerItem(Icons.Default.Language, "Language")
        DrawerItem(Icons.Default.CalendarToday, "Date Format")
        DrawerItem(Icons.AutoMirrored.Filled.HelpCenter, "Help & Support") {
            onNavigateToSupport()
        }
        DrawerItem(Icons.Default.Policy, "Terms & Policies")
        DrawerItem(Icons.Default.Info, "About App")

        HorizontalDivider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp)

        // 4. Security Section
        DrawerSectionHeader("Security")
        DrawerItem(Icons.Default.Devices, "Device Sessions")
        DrawerItem(Icons.AutoMirrored.Filled.Logout, "Logout", tint = MaterialTheme.colorScheme.error) {
            // Confirm logout
        }

        Spacer(Modifier.weight(1f))
        
        Text(
            text = "Version 2.4.0",
            modifier = Modifier.padding(24.dp).align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit = {}
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.7f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
    }
}

// ==========================================
// 4. THE "MORE" UTILITY HUB
// ==========================================

@Composable
@Composable
fun MoreScreen(
    marketViewModel: MarketViewModel,
    portfolioViewModel: PortfolioViewModel,
    ipoViewModel: com.example.ui.viewmodel.BulkIpoViewModel
) {
    var currentSubView by remember { mutableStateOf<String?>(null) }
    val userProfile by portfolioViewModel.userProfile.collectAsStateWithLifecycle()

    if (currentSubView == "Market") {
        Column {
            TextButton(
                onClick = { currentSubView = null },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Back to More", fontWeight = FontWeight.Bold)
            }
            MarketScreen(marketViewModel, portfolioViewModel)
        }
    } else if (currentSubView == "BulkCheck") {
        BulkIpoCheckScreen(ipoViewModel) { currentSubView = null }
    } else if (currentSubView == "Contact") {
        Column {
            TextButton(
                onClick = { currentSubView = null },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Back to Support", fontWeight = FontWeight.Bold)
            }
            
            DeveloperProfilePanel(
                userName = userProfile?.name ?: "Valued User",
                userEmail = userProfile?.email ?: "",
                onClose = { currentSubView = null }
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Text("More Features", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }

            // Meroshare Services
            item {
                MoreSection("Meroshare Services") {
                    MoreGrid(
                        { MoreCard("Markets", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF10B981)) { currentSubView = "Market" } },
                        { MoreCard("Current Issues", Icons.Default.EventAvailable, Color(0xFF3B82F6)) },
                        { MoreCard("Upcoming Issues", Icons.Default.Upcoming, Color(0xFFF59E0B)) },
                        { MoreCard("IPO Status", Icons.Default.AssignmentInd, Color(0xFF8B5CF6)) },
                        { MoreCard("Bulk Apply", Icons.Default.GroupAdd, Color(0xFFEC4899)) },
                        { MoreCard("Bulk Check", Icons.AutoMirrored.Filled.FactCheck, Color(0xFFEF4444)) { currentSubView = "BulkCheck" } }
                    )
                }
            }

            // Utilities
            item {
                MoreSection("Utilities") {
                    MoreGrid(
                        { MoreCard("Date Converter", Icons.Default.CalendarMonth, Color(0xFF6366F1)) },
                        { MoreCard("EMI Calculator", Icons.Default.Calculate, Color(0xFF14B8A6)) },
                        { MoreCard("Broker List", Icons.Default.Business, Color(0xFFF43F5E)) },
                        { MoreCard("Meroshare Web", Icons.Default.Web, Color(0xFF0EA5E9)) }
                    )
                }
            }

            // Support
            item {
                MoreSection("Support") {
                    MoreGrid(
                        { MoreCard("Help Center", Icons.Default.SupportAgent, Color(0xFF71717A)) },
                        { MoreCard("Report Issue", Icons.Default.BugReport, Color(0xFF71717A)) { currentSubView = "Contact" } },
                        { MoreCard("Policies", Icons.Default.Gavel, Color(0xFF71717A)) }
                    )
                }
            }
        }
    }
}

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkIpoCheckScreen(viewModel: com.example.ui.viewmodel.BulkIpoViewModel, onBack: () -> Unit) {
    val companies by viewModel.companies.collectAsStateWithLifecycle()
    val selectedCompany by viewModel.selectedCompany.collectAsStateWithLifecycle()
    val boids by viewModel.boids.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isChecking by viewModel.isChecking.collectAsStateWithLifecycle()

    var showAddBoidDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Bulk IPO Checker", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Company Selector
        Text("Select Company", fontWeight = FontWeight.Bold)
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedCompany?.name ?: "Select a company",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                companies.forEach { company ->
                    DropdownMenuItem(
                        text = { Text(company.name) },
                        onClick = {
                            viewModel.selectCompany(company)
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BOIDs (${boids.size})", fontWeight = FontWeight.Bold)
            Button(onClick = { showAddBoidDialog = true }, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Add BOID")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(if (results.isNotEmpty()) results.size else boids.size) { index ->
                if (results.isNotEmpty()) {
                    val result = results[index]
                    IpoResultItem(result)
                } else {
                    val boid = boids[index]
                    BoidItem(boid, onRemove = { viewModel.removeBoid(boid) })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.startBulkCheck() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !isChecking && boids.isNotEmpty() && selectedCompany != null,
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isChecking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking...")
            } else {
                Text("Start Bulk Check", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    if (showAddBoidDialog) {
        AddBoidDialog(
            onAdd = { name, boid ->
                viewModel.addBoid(name, boid)
                showAddBoidDialog = false
            },
            onDismiss = { showAddBoidDialog = false }
        )
    }
}

@Composable
fun BoidItem(boid: com.example.data.model.BoidEntry, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(boid.name, fontWeight = FontWeight.Bold)
                Text(boid.boid, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun IpoResultItem(result: com.example.data.model.BulkIpoResult) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                result.result?.success == true -> Color(0xFFE8F5E9)
                result.result?.success == false -> Color(0xFFFFEBEE)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(result.boidEntry.name, fontWeight = FontWeight.Bold)
                Text(result.boidEntry.boid, style = MaterialTheme.typography.bodySmall)
                if (result.error != null) {
                    Text(result.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            
            if (result.isChecking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (result.result != null) {
                val color = if (result.result!!.success) Color(0xFF2E7D32) else Color(0xFFC62828)
                Text(
                    text = result.result!!.message,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun AddBoidDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var boid by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Family BOID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. Mom)") })
                OutlinedTextField(
                    value = boid, 
                    onValueChange = { if (it.length <= 16) boid = it }, 
                    label = { Text("BOID (16 digits)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank() && boid.length == 16) onAdd(name, boid) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun IndicesConfigDialog(
    allIndices: List<com.example.data.repository.NepseIndex>,
    visibleIndices: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Configure Visible Indices", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f, false)) {
                    items(allIndices) { idx ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onToggle(idx.index) }.padding(vertical = 4.dp)
                        ) {
                            Checkbox(checked = visibleIndices.contains(idx.index), onCheckedChange = { onToggle(idx.index) })
                            Text(idx.index)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun MoreSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
fun MoreGrid(vararg cards: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        cards.asList().chunked(2).forEach { rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowItems.forEach { card ->
                    Box(modifier = Modifier.weight(1f)) {
                        card()
                    }
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun MoreCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(160.dp).height(100.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

// ==========================================
// 5. INGESTION ENGINE & RECORD WRITER (DATA)
// ==========================================

@Composable
fun DataScreen(viewModel: PortfolioViewModel) {
    val context = LocalContext.current
    val distinctItems by viewModel.distinctItems.collectAsStateWithLifecycle()
    val distinctTypes by viewModel.distinctTypes.collectAsStateWithLifecycle()
    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()

    // Since triggers outside compose lifecycle are easier, we can invoke dialogs during buttons click!
    var showImportOptionDialog by remember { mutableStateOf(false) }
    var importIsWacc by remember { mutableStateOf(false) }
    var showWipeConfirmationDialog by remember { mutableStateOf(false) }
    var currentCsvTextToImport by remember { mutableStateOf<String?>(null) }
    var showMerosharePrompt by remember { mutableStateOf(false) }
    var pendingMeroshareText by remember { mutableStateOf<String?>(null) }
    var pendingDeleteRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var pendingAddRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var pendingUpdateRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var hideSystemAdjustmentInfo by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val importMeroshareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().use { it.readText() }
                        }
                    }
                    if (text != null) {
                        pendingMeroshareText = text
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read CSV: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    val importTransactionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().use { it.readText() }
                        }
                    }
                    if (text != null) {
                        currentCsvTextToImport = text
                        importIsWacc = false
                        showImportOptionDialog = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read CSV: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    val importWaccLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.bufferedReader().use { it.readText() }
                        }
                    }
                    if (text != null) {
                        currentCsvTextToImport = text
                        importIsWacc = true
                        showImportOptionDialog = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to read CSV: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val csvText = buildString {
                        append("Date,Item,Action,Qty,Amount,Type\n")
                        transactions.forEach { tx ->
                            append("${tx.date},${tx.item},${tx.action},${tx.qty},${tx.amount},${tx.type}\n")
                        }
                    }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.bufferedWriter().use { it.write(csvText) }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Portfolio exported successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


    // Historical record modification state
    var editingRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var selectedItemFilter by remember { mutableStateOf("All") }
    var expandedYears by remember { mutableStateOf(setOf<String>()) }

    val transactionsByYear = remember(transactions, selectedItemFilter) {
        val filtered = if (selectedItemFilter == "All") transactions
        else transactions.filter { it.item.equals(selectedItemFilter, ignoreCase = true) }
        
        filtered.groupBy { it.date.split("-").firstOrNull() ?: "Unknown" }
            .toSortedMap(compareByDescending { it })
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Form Section (Create transactions)
        item {
            Text(
                "Record Portfolio Transaction",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            RowEntryForm(
                distinctItems = distinctItems,
                distinctTypes = distinctTypes,
                onSave = { record ->
                    pendingAddRecord = record
                }
            )
        }

        // System Adjustment Information Message
        val hasSystemAdjustments = transactions.any { it.isSystemAdjustment }
        if (hasSystemAdjustments && !hideSystemAdjustmentInfo) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().animateItem()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Text("Auto-Adjustments Detected", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            androidx.compose.material3.Text("Some records were added automatically to align your portfolio units. Please review and edit their Buy/Sale amounts for better accuracy.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        IconButton(onClick = { hideSystemAdjustmentInfo = true }) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Next Step Banner relocated here
        if (showMerosharePrompt) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().animateItem()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cost basis loaded.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Import your 'My Shares Values' file now to see profit/loss.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Button(
                            onClick = { importMeroshareLauncher.launch("*/*") },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Import Prices", fontSize = 11.sp)
                        }
                        IconButton(onClick = { showMerosharePrompt = false }) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Unified CSV Ingestions Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SettingsSuggest, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Unified I/O Management", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("IMPORT DATA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Row 1: Standard Import
                    Button(
                        onClick = { importTransactionLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("import_transactions_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Standard Data Import (Tx CSV)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Row 2: WACC and Portfolio
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { importWaccLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("import_wacc_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.AccountBalance, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("WACC Import", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { importMeroshareLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f).height(48.dp).testTag("import_meroshare_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.CloudUpload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Portfolio Import", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text("EXPORT DATA", style = MaterialTheme.typography.labelLarge, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Button(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            exportCsvLauncher.launch("finfolio_export_$timestamp.csv")
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("export_transactions_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Export Transaction History (CSV)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // CSV Schema Info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.03f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Info, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "CSV SCHEMA SPECIFICATIONS",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            SchemaReferenceItem(
                                "1. Transactions Exported (This App)", 
                                "\"Date\", \"Item\", \"Action\", \"Qty\", \"Amount\", \"Type\"", 
                                MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(10.dp))
                            SchemaReferenceItem(
                                "2. WACC Export", 
                                "\"S.N\",\"Demat\",\"Scrip Name\",\"WACC Calculated Quantity\",\"WACC Rate\",\"Total Cost of Capital\",\"Last Modification Date\"", 
                                MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.height(10.dp))
                            SchemaReferenceItem(
                                "3. Portfolio Export", 
                                "\"S.N\",\"Scrip\",\"Current Balance\",\"Last Closing Price\",\"Value as of Last Closing Price\",\"Last Transaction Price (LTP)\",\"Value as of LTP\"", 
                                MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }

        // Wipe Data button
        item {
            OutlinedButton(
                onClick = { showWipeConfirmationDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("wipe_db_btn")
            ) {
                Icon(Icons.Filled.DeleteForever, "Wipe")
                Spacer(Modifier.width(6.dp))
                Text("Wipe All Transactions", fontWeight = FontWeight.SemiBold)
            }
        }

        // Historic Transaction Logs Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val totalCount = transactionsByYear.values.sumOf { it.size }
                Text(
                    "Historical Transaction Logs ($totalCount)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        item {
            var expandedDropdown by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { expandedDropdown = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("transaction_item_filter_dropdown"),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (selectedItemFilter == "All") "Filter by Scrip: All Items" else "Filter by Scrip: $selectedItemFilter",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Dropdown",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = expandedDropdown,
                    onDismissRequest = { expandedDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("All Items", fontWeight = FontWeight.Bold) },
                        onClick = {
                            selectedItemFilter = "All"
                            expandedDropdown = false
                        }
                    )
                    distinctItems.sorted().forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                selectedItemFilter = item
                                expandedDropdown = false
                            }
                        )
                    }
                }
            }
        }

        if (transactionsByYear.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (selectedItemFilter == "All") "No transactions logged yet" 
                            else "No transactions found for '$selectedItemFilter'",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            transactionsByYear.forEach { (year, yearTxs) ->
                item(key = year) {
                    val isExpanded = expandedYears.contains(year)
                    Surface(
                        onClick = {
                            expandedYears = if (isExpanded) expandedYears - year else expandedYears + year
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.width(8.dp))
                                androidx.compose.material3.Text(
                                    "Year $year",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                                androidx.compose.material3.Text("${yearTxs.size} tx", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }

                if (expandedYears.contains(year)) {
                    items(yearTxs, key = { it.id }) { tx ->
                        TransactionListItem(
                            tx = tx,
                            onEdit = { editingRecord = tx },
                            onDelete = { pendingDeleteRecord = tx }
                        )
                    }
                }
            }
        }
    }


    // SCHEMA 1 APPEND vs OVERWRITE choice dialog
    if (showImportOptionDialog && currentCsvTextToImport != null) {
        AlertDialog(
            onDismissRequest = {
                showImportOptionDialog = false
                currentCsvTextToImport = null
            },
            title = { Text("CSV Import Mode", fontWeight = FontWeight.Bold) },
            text = { Text("Do you want to APPEND the new CSV records onto the existing records, or OVERWRITE (completely clear out) the transaction database first?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.importTransactions(currentCsvTextToImport!!, overwrite = false, isWacc = importIsWacc)
                        showImportOptionDialog = false
                        currentCsvTextToImport = null
                        showMerosharePrompt = true
                    },
                    modifier = Modifier.testTag("import_mode_append")
                ) {
                    Text("Append Records")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.importTransactions(currentCsvTextToImport!!, overwrite = true, isWacc = importIsWacc)
                        showImportOptionDialog = false
                        currentCsvTextToImport = null
                        showMerosharePrompt = true
                    },
                    modifier = Modifier.testTag("import_mode_overwrite")
                ) {
                    Text("Overwrite Existing", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    // Wipe Confirmation Dialog
    if (showWipeConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmationDialog = false },
            title = { Text("Wipe All Data?", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete ALL historical transaction records from your portfolio. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllData()
                        showWipeConfirmationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_wipe_btn")
                ) {
                    Text("Yes, Wipe Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmationDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Historical edit dialog modifier overlay
    if (editingRecord != null) {
        EditTransactionDialog(
            record = editingRecord!!,
            distinctItems = distinctItems,
            distinctTypes = distinctTypes,
            onSave = { updated ->
                pendingUpdateRecord = updated
                editingRecord = null
            },
            onDismiss = { editingRecord = null }
        )
    }

    // Alignment Confirmation Dialog for Meroshare Import
    if (pendingMeroshareText != null) {
        AlertDialog(
            onDismissRequest = { pendingMeroshareText = null },
            title = { Text("Align Portfolio Holdings?", fontWeight = FontWeight.Bold) },
            text = { Text("Importing 'My Shares Values' will compare your actual Meroshare units with your recorded history. The app will automatically generate adjustment 'Sale' records at your Avg. Cost to align the database with your current portfolio. This is necessary for accurate Net Investment and Profit/Loss calculation. Do you wish to proceed?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.importMeroshare(pendingMeroshareText!!)
                    pendingMeroshareText = null
                    showMerosharePrompt = false
                }) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMeroshareText = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Record Confirmation
    if (pendingDeleteRecord != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteRecord = null },
            title = { Text("Delete Record?", fontWeight = FontWeight.Bold) },
            text = { Text("This action is permanent and will immediately recalculate your portfolio metrics. Proceed?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTransaction(pendingDeleteRecord!!)
                        pendingDeleteRecord = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteRecord = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Record Confirmation
    if (pendingAddRecord != null) {
        AlertDialog(
            onDismissRequest = { pendingAddRecord = null },
            title = { Text("Confirm Transaction?", fontWeight = FontWeight.Bold) },
            text = { Text("Save this record of ${pendingAddRecord!!.action} ${pendingAddRecord!!.qty} units of ${pendingAddRecord!!.item} to the database?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.addTransaction(pendingAddRecord!!)
                    pendingAddRecord = null
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAddRecord = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Update Record Confirmation
    if (pendingUpdateRecord != null) {
        AlertDialog(
            onDismissRequest = { pendingUpdateRecord = null },
            title = { Text("Update Record?", fontWeight = FontWeight.Bold) },
            text = { Text("This will modify the existing transaction. Proceed?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.updateTransaction(pendingUpdateRecord!!)
                    pendingUpdateRecord = null
                }) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUpdateRecord = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RowEntryForm(
    distinctItems: List<String>,
    distinctTypes: List<String>,
    onSave: (TransactionRecord) -> Unit
) {
    val context = LocalContext.current
    var dateVal by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())) }
    var itemVal by remember { mutableStateOf("") }
    var typeVal by remember { mutableStateOf("") }
    var actionVal by remember { mutableStateOf("Buy") }
    var qtyVal by remember { mutableStateOf("") }
    var amountVal by remember { mutableStateOf("") }

    // Date Dialog launcher
    val datePickerLauncher = remember {
        val calendar = Calendar.getInstance()
        android.app.DatePickerDialog(
            context,
            { _, yr, mo, dy ->
                dateVal = String.format(Locale.US, "%04d-%02d-%02d", yr, mo + 1, dy)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Editable Date trigger
            OutlinedButton(
                onClick = { datePickerLauncher.show() },
                modifier = Modifier.fillMaxWidth().testTag("date_picker_btn")
            ) {
                Icon(Icons.Filled.DateRange, "Date", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Transaction Date: $dateVal", fontWeight = FontWeight.SemiBold)
            }

            // Async AutoComplete item
            AutoCompleteTextField(
                label = "Scrip Symbol (Max 15 chars)",
                value = itemVal,
                onValueChange = { if (it.length <= 15) itemVal = it },
                suggestions = distinctItems,
                modifier = Modifier.testTag("item_autocomplete")
            )

            // Async AutoComplete type
            AutoCompleteTextField(
                label = "Category / Sector (Max 15 chars)",
                value = typeVal,
                onValueChange = { if (it.length <= 15) typeVal = it },
                suggestions = distinctTypes,
                modifier = Modifier.testTag("type_autocomplete")
            )

            // Selection drop action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expandedAction by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expandedAction = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("action_selector_btn")
                    ) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Action: $actionVal", fontWeight = FontWeight.Bold)
                            Icon(Icons.Filled.ArrowDropDown, "down")
                        }
                    }
                    DropdownMenu(
                        expanded = expandedAction,
                        onDismissRequest = { expandedAction = false }
                    ) {
                        listOf("Buy", "Sale", "Returns").forEach { act ->
                            DropdownMenuItem(
                                text = { Text(act) },
                                onClick = {
                                    actionVal = act
                                    expandedAction = false
                                }
                            )
                        }
                    }
                }
            }

            // Numeric entries
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = qtyVal,
                    onValueChange = { qtyVal = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("qty_input_field"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = amountVal,
                    onValueChange = { amountVal = it },
                    label = { Text("Total Amount ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("amount_input_field"),
                    singleLine = true
                )
            }

            // Save Form button
            Button(
                onClick = {
                    if (itemVal.isBlank() || typeVal.isBlank()) {
                        Toast.makeText(context, "Item and Type fields are mandatory!", Toast.LENGTH_SHORT).show()
                    } else {
                        // Math parser
                        val q = qtyVal.toDoubleOrNull() ?: 0.0
                        val amt = amountVal.toDoubleOrNull() ?: 0.0
                        onSave(
                            TransactionRecord(
                                date = dateVal,
                                item = itemVal.trim(),
                                type = typeVal.trim(),
                                action = actionVal,
                                qty = q,
                                amount = amt
                            )
                        )
                        // Reset fields
                        itemVal = ""
                        typeVal = ""
                        qtyVal = ""
                        amountVal = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_transaction_btn")
            ) {
                Icon(Icons.Filled.Save, "Save")
                Spacer(Modifier.width(6.dp))
                Text("Add Transaction Record", fontWeight = FontWeight.Bold)
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun TransactionListItem(
    tx: TransactionRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badgeColor = when (tx.action) {
                        "Buy" -> Color(0xFF2ECE7B)
                        "Sale" -> Color(0xFFEB4D4B)
                        else -> Color(0xFF00D2C4)
                    }
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(tx.action.uppercase(), color = badgeColor, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(tx.item, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                    if (tx.isSystemAdjustment) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "System Adjusted",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("Sector: ${tx.type}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Date: ${tx.date}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                    Text(String.format(Locale.US, "$%,.2f", tx.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Qty: ${tx.qty}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp).testTag("edit_tx_btn")) {
                    Icon(Icons.Filled.Edit, "Edit", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp).testTag("delete_tx_btn")) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun EditTransactionDialog(
    record: TransactionRecord,
    distinctItems: List<String>,
    distinctTypes: List<String>,
    onSave: (TransactionRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dateVal by remember { mutableStateOf(record.date) }
    var itemVal by remember { mutableStateOf(record.item) }
    var typeVal by remember { mutableStateOf(record.type) }
    var actionVal by remember { mutableStateOf(record.action) }
    var qtyVal by remember { mutableStateOf(record.qty.toString()) }
    var amountVal by remember { mutableStateOf(record.amount.toString()) }

    val datePickerLauncher = remember {
        val yrPart = record.date.split("-").getOrNull(0)?.toIntOrNull() ?: 2026
        val moPart = (record.date.split("-").getOrNull(1)?.toIntOrNull() ?: 6) - 1
        val dyPart = record.date.split("-").getOrNull(2)?.toIntOrNull() ?: 1
        DatePickerDialog(
            context,
            { _, yr, mo, dy ->
                dateVal = String.format(Locale.US, "%04d-%02d-%02d", yr, mo + 1, dy)
            },
            yrPart, moPart, dyPart
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Modify Transaction Record", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = { datePickerLauncher.show() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DateRange, "Date")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Date: $dateVal")
                }

                AutoCompleteTextField(
                    label = "Scrip Symbol (Max 15 chars)",
                    value = itemVal,
                    onValueChange = { if (it.length <= 15) itemVal = it },
                    suggestions = distinctItems
                )

                AutoCompleteTextField(
                    label = "Sector/Category (Max 15 chars)",
                    value = typeVal,
                    onValueChange = { if (it.length <= 15) typeVal = it },
                    suggestions = distinctTypes
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var expandedAction by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expandedAction = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Action: $actionVal", fontWeight = FontWeight.Bold)
                                Icon(Icons.Filled.ArrowDropDown, "down")
                            }
                        }
                        DropdownMenu(
                            expanded = expandedAction,
                            onDismissRequest = { expandedAction = false }
                        ) {
                            listOf("Buy", "Sale", "Returns").forEach { act ->
                                DropdownMenuItem(
                                    text = { Text(act) },
                                    onClick = {
                                        actionVal = act
                                        expandedAction = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyVal,
                        onValueChange = { qtyVal = it },
                        label = { Text("Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = amountVal,
                        onValueChange = { amountVal = it },
                        label = { Text("Amount ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (itemVal.isBlank() || typeVal.isBlank()) {
                                Toast.makeText(context, "Item and Type fields are mandatory!", Toast.LENGTH_SHORT).show()
                            } else {
                                val q = qtyVal.toDoubleOrNull() ?: 0.0
                                val amt = amountVal.toDoubleOrNull() ?: 0.0
                                onSave(
                                    record.copy(
                                        date = dateVal,
                                        item = itemVal.trim(),
                                        type = typeVal.trim(),
                                        action = actionVal,
                                        qty = q,
                                        amount = amt
                                    )
                                )
                            }
                        },
                        modifier = Modifier.testTag("dialog_save_edit_btn")
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
        }
        }
    }

            onManualAdd = { /* Handled via search query eventually */ },
            onDismiss = { showSearchDialog = false }
        )
    }

            onDismiss = { showIndicesConfig = false }
        )
    }
}


@Composable
fun AutoCompleteTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    // We use a internal state for the TextFieldValue to manage the cursor position smoothly
    var textFieldValue by remember(value) { 
        mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(
            text = value,
            selection = androidx.compose.ui.text.TextRange(value.length)
        )) 
    }

    val filteredSuggestions = remember(value, suggestions) {
        if (value.isBlank()) suggestions
        else suggestions.filter { it.contains(value, ignoreCase = true) }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                if (newValue.text != value) {
                    onValueChange(newValue.text)
                }
                expanded = true
            },
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = "Toggle dropdown suggestions",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        if (textFieldValue.text.isNotEmpty()) {
                            onValueChange("")
                        }
                        expanded = true
                    }
                },
            singleLine = true
        )

        DropdownMenu(
            expanded = expanded && filteredSuggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 180.dp)
        ) {
            filteredSuggestions.take(10).forEach { sug ->
                DropdownMenuItem(
                    text = { Text(sug) },
                    onClick = {
                        onValueChange(sug)
                        expanded = false
                    }
                )
            }
        }
    }
}
