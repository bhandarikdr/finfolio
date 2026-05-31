package com.example

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.outlined.Input
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.example.data.model.ItemMetrics
import com.example.data.model.TypeMetrics
import com.example.data.repository.PortfolioRepository
import com.example.data.work.ScrapeWorker
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DatasetScope
import com.example.ui.viewmodel.PortfolioViewModel
import com.example.ui.viewmodel.PortfolioViewModelFactory
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
                workRequest
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val database = remember { AppDatabase.getDatabase(context) }
                val repository = remember { PortfolioRepository(database.portfolioDao()) }
                val factory = remember { PortfolioViewModelFactory(repository) }
                val viewModel: PortfolioViewModel = viewModel(factory = factory)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PortfolioAppContent(viewModel)
                }
            }
        }
    }
}

enum class NavigationTab {
    DASHBOARD,
    MATRIX,
    DATA
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PortfolioAppContent(viewModel: PortfolioViewModel) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(NavigationTab.DASHBOARD) }
    
    // SnackBar notification observer
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(key1 = true) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Analytics,
                            contentDescription = "App Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "FinFolio Pro",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
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
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp).padding(end = 12.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refreshLivePrices() },
                        modifier = Modifier.testTag("refresh_live_prices_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh Live Prices"
                        )
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
                    selected = currentTab == NavigationTab.DASHBOARD,
                    onClick = { currentTab = NavigationTab.DASHBOARD },
                    label = { Text("Dashboard") },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.DASHBOARD) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                            contentDescription = "Dashboard"
                        )
                    }
                )
                NavigationBarItem(
                    selected = currentTab == NavigationTab.MATRIX,
                    onClick = { currentTab = NavigationTab.MATRIX },
                    label = { Text("Matrices") },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.MATRIX) Icons.Filled.TableChart else Icons.Outlined.TableChart,
                            contentDescription = "Matrices"
                        )
                    }
                )
                NavigationBarItem(
                    selected = currentTab == NavigationTab.DATA,
                    onClick = { currentTab = NavigationTab.DATA },
                    label = { Text("Data & CSV") },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == NavigationTab.DATA) Icons.AutoMirrored.Filled.Input else Icons.AutoMirrored.Outlined.Input,
                            contentDescription = "Data"
                        )
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentTab) {
                NavigationTab.DASHBOARD -> DashboardScreen(viewModel)
                NavigationTab.MATRIX -> MatrixScreen(viewModel)
                NavigationTab.DATA -> DataScreen(viewModel)
            }
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
        val overallProfit = totalEvaluation - totalNetInvest
        val overallGrowthPercent = if (totalNetInvest > 0.0) {
            (overallProfit / totalNetInvest) * 100.0
        } else {
            val totalBuy = itemMetrics.sumOf { it.buyAmount }
            if (totalBuy > 0.0) (overallProfit / totalBuy) * 100.0 else 0.0
        }

        item {
            ValuationSummaryCard(
                totalNetInvest = totalNetInvest,
                totalEvaluation = totalEvaluation,
                overallProfit = overallProfit,
                overallGrowthPercent = overallGrowthPercent
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
            DatasetScope.MEROSHARE to "Meroshare Only"
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
            }
        }
    }
}

@Composable
fun ValuationSummaryCard(
    totalNetInvest: Double,
    totalEvaluation: Double,
    overallProfit: Double,
    overallGrowthPercent: Double
) {
    val isPositive = overallProfit >= 0.0
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            Color(0xFF1D4ED8) // Rich Blue 700
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // rounded-3xl
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = gradientBrush)
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Evaluation",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                
                val badgeText = String.format(Locale.US, "%s%.2f%% Growth", if (isPositive) "+" else "", overallGrowthPercent)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = String.format(Locale.US, "$%,.2f", totalEvaluation),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 20.dp),
                color = Color.White.copy(alpha = 0.2f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "NET INVESTMENT",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = String.format(Locale.US, "$%,.2f", totalNetInvest),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "PORTFOLIO NET GAIN",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPositive) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = "Gain direction",
                            tint = if (isPositive) Color(0xFF4ADE80) else Color(0xFFF87171),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = String.format(Locale.US, "$%,.2f", overallProfit),
                            color = if (isPositive) Color(0xFF4ADE80) else Color(0xFFF87171),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }
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

    var activeTabIdx by remember { mutableStateOf(0) } // 0: Items Tab, 1: Types Tab
    var showConfigDialog by remember { mutableStateOf(false) }

    val activeItemCols by viewModel.itemColumns.collectAsStateWithLifecycle()
    val activeTypeCols by viewModel.typeColumns.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
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
                val filteredItems = if (typeFilter == "All") items else items.filter { it.type.equals(typeFilter, ignoreCase = true) }
                ItemMatrixTable(items = filteredItems, activeCols = activeItemCols)
            } else {
                TypesMatrixTable(types = types, activeCols = activeTypeCols)
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

    // Row cell utility helper
    val cellWidth = 110.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Sticky horizontal header
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(scrollState)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text("Scrip Symbol", fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp)
            if (activeCols.contains("Buy_Amount")) Text("Buy Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Buy_Count")) Text("Buy Count", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Buy_Qty")) Text("Buy Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Sale_Amount")) Text("Sale Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Sale_Count")) Text("Sale Count", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Sale_Qty")) Text("Sale Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Balance_Qty")) Text("Bal Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Avg_CP")) Text("Avg CP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Avg_SP")) Text("Avg SP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("LTP")) Text("LTP ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Net_Invest")) Text("Net Invest ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Return_Qty")) Text("Return Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Return_Cash")) Text("Return Cash ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Evaluation")) Text("Evaluation ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Realized_Gain")) Text("Realized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Unrealized_Gain")) Text("Unrealized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Deductions")) Text("Deductions ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Net_Gain")) Text("Net Gain ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Growth")) Text("Growth (%)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Receivable_Amount")) Text("Receivable ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Profit_Amount")) Text("Profit ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Profit_Percent")) Text("Profit (%)", fontWeight = FontWeight.Bold, width = cellWidth)
        }

        // Table Rows Body
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No records map this view", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .horizontalScroll(scrollState)
                                .padding(vertical = 10.dp)
                        ) {
                            Text(row.item, fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp, color = MaterialTheme.colorScheme.primary)
                            if (activeCols.contains("Buy_Amount")) Text(String.format(Locale.US, "%,.2f", row.buyAmount), width = cellWidth)
                            if (activeCols.contains("Buy_Count")) Text("${row.buyCount}", width = cellWidth)
                            if (activeCols.contains("Buy_Qty")) Text(String.format(Locale.US, "%,.2f", row.buyQty), width = cellWidth)
                            if (activeCols.contains("Sale_Amount")) Text(String.format(Locale.US, "%,.2f", row.saleAmount), width = cellWidth)
                            if (activeCols.contains("Sale_Count")) Text("${row.saleCount}", width = cellWidth)
                            if (activeCols.contains("Sale_Qty")) Text(String.format(Locale.US, "%,.2f", row.saleQty), width = cellWidth)
                            if (activeCols.contains("Balance_Qty")) Text(String.format(Locale.US, "%,.2f", row.balanceQty), width = cellWidth)
                            if (activeCols.contains("Avg_CP")) Text(String.format(Locale.US, "%,.2f", row.avgCp), width = cellWidth)
                            if (activeCols.contains("Avg_SP")) Text(String.format(Locale.US, "%,.2f", row.avgSp), width = cellWidth)
                            if (activeCols.contains("LTP")) Text(String.format(Locale.US, "%,.2f", row.ltp), width = cellWidth, color = if (row.ltp > 0.0) MaterialTheme.colorScheme.onSurface else Color.Gray)
                            if (activeCols.contains("Net_Invest")) Text(String.format(Locale.US, "%,.2f", row.netInvest), width = cellWidth)
                            if (activeCols.contains("Return_Qty")) Text(String.format(Locale.US, "%,.2f", row.returnQty), width = cellWidth)
                            if (activeCols.contains("Return_Cash")) Text(String.format(Locale.US, "%,.2f", row.returnCash), width = cellWidth)
                            if (activeCols.contains("Evaluation")) Text(String.format(Locale.US, "%,.2f", row.evaluation), width = cellWidth)
                            if (activeCols.contains("Realized_Gain")) Text(String.format(Locale.US, "%,.2f", row.realizedGain), width = cellWidth, color = if (row.realizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Unrealized_Gain")) Text(String.format(Locale.US, "%,.2f", row.unrealizedGain), width = cellWidth, color = if (row.unrealizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Deductions")) Text(String.format(Locale.US, "%,.2f", row.deductions), width = cellWidth)
                            if (activeCols.contains("Net_Gain")) Text(String.format(Locale.US, "%,.2f", row.netGain), width = cellWidth, color = if (row.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Growth")) Text(String.format(Locale.US, "%+.2f%%", row.growth), width = cellWidth, color = if (row.growth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                            if (activeCols.contains("Receivable_Amount")) Text(String.format(Locale.US, "%,.2f", row.receivableAmount), width = cellWidth)
                            if (activeCols.contains("Profit_Amount")) Text(String.format(Locale.US, "%,.2f", row.profitAmount), width = cellWidth, color = if (row.profitAmount >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Profit_Percent")) Text(String.format(Locale.US, "%+.2f%%", row.profitPercent), width = cellWidth, color = if (row.profitPercent >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Horizontal Sticky Aggregate Totals (Bottom row)
        if (items.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(scrollState)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text("TOTAL SUMS", fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp, color = MaterialTheme.colorScheme.primary)
                if (activeCols.contains("Buy_Amount")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.buyAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Count")) Text("${items.sumOf { it.buyCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Qty")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.buyQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Amount")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.saleAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Count")) Text("${items.sumOf { it.saleCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Qty")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.saleQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Balance_Qty")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.balanceQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                
                // Averages generally not summed directly, display dash
                if (activeCols.contains("Avg_CP")) Text("-", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Avg_SP")) Text("-", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("LTP")) Text("-", fontWeight = FontWeight.Bold, width = cellWidth)

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

                if (activeCols.contains("Net_Invest")) Text(String.format(Locale.US, "%,.2f", sumNetInvest), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Return_Qty")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.returnQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Return_Cash")) Text(String.format(Locale.US, "%,.2f", items.sumOf { it.returnCash }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Evaluation")) Text(String.format(Locale.US, "%,.2f", sumEvaluation), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Realized_Gain")) Text(String.format(Locale.US, "%,.2f", sumRealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumRealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Unrealized_Gain")) Text(String.format(Locale.US, "%,.2f", sumUnrealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumUnrealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Deductions")) Text(String.format(Locale.US, "%,.2f", sumDeductions), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Gain")) Text(String.format(Locale.US, "%,.2f", sumNetGain), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumNetGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Growth")) Text(String.format(Locale.US, "%+.2f%%", overallGrowth), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallGrowth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Receivable_Amount")) Text(String.format(Locale.US, "%,.2f", sumReceivable), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Amount")) Text(String.format(Locale.US, "%,.2f", sumProfitAmt), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumProfitAmt >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Profit_Percent")) Text(String.format(Locale.US, "%+.2f%%", overallProfitPct), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallProfitPct >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
            }
        }
    }
}

@Composable
fun TypesMatrixTable(
    types: List<TypeMetrics>,
    activeCols: Set<String>
) {
    val scrollState = rememberScrollState()
    val cellWidth = 110.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Headers Row
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(scrollState)
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Text("Type Category", fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp)
            if (activeCols.contains("Item_Count")) Text("Scrip Count", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Buy_Amount")) Text("Buy Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Sale_Amount")) Text("Sale Amt ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Balance_Qty")) Text("Bal Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Net_Invest")) Text("Net Invest ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Return_Qty")) Text("Return Qty", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Return_Cash")) Text("Return Cash ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Evaluation")) Text("Evaluation ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Realized_Gain")) Text("Realized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Unrealized_Gain")) Text("Unrealized ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Deductions")) Text("Deductions ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Net_Gain")) Text("Net Gain ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Growth")) Text("Growth (%)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Receivable_Amount")) Text("Receivable ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Profit_Amount")) Text("Profit ($)", fontWeight = FontWeight.Bold, width = cellWidth)
            if (activeCols.contains("Profit_Percent")) Text("Profit (%)", fontWeight = FontWeight.Bold, width = cellWidth)
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .horizontalScroll(scrollState)
                                .padding(vertical = 10.dp)
                        ) {
                            Text(row.type, fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp, color = MaterialTheme.colorScheme.primary)
                            if (activeCols.contains("Item_Count")) Text("${row.itemCount}", width = cellWidth)
                            if (activeCols.contains("Buy_Amount")) Text(String.format(Locale.US, "%,.2f", row.buyAmount), width = cellWidth)
                            if (activeCols.contains("Sale_Amount")) Text(String.format(Locale.US, "%,.2f", row.saleAmount), width = cellWidth)
                            if (activeCols.contains("Balance_Qty")) Text(String.format(Locale.US, "%,.2f", row.balanceQty), width = cellWidth)
                            if (activeCols.contains("Net_Invest")) Text(String.format(Locale.US, "%,.2f", row.netInvest), width = cellWidth)
                            if (activeCols.contains("Return_Qty")) Text(String.format(Locale.US, "%,.2f", row.returnQty), width = cellWidth)
                            if (activeCols.contains("Return_Cash")) Text(String.format(Locale.US, "%,.2f", row.returnCash), width = cellWidth)
                            if (activeCols.contains("Evaluation")) Text(String.format(Locale.US, "%,.2f", row.evaluation), width = cellWidth)
                            if (activeCols.contains("Realized_Gain")) Text(String.format(Locale.US, "%,.2f", row.realizedGain), width = cellWidth, color = if (row.realizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Unrealized_Gain")) Text(String.format(Locale.US, "%,.2f", row.unrealizedGain), width = cellWidth, color = if (row.unrealizedGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Deductions")) Text(String.format(Locale.US, "%,.2f", row.deductions), width = cellWidth)
                            if (activeCols.contains("Net_Gain")) Text(String.format(Locale.US, "%,.2f", row.netGain), width = cellWidth, color = if (row.netGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Growth")) Text(String.format(Locale.US, "%+.2f%%", row.growth), width = cellWidth, color = if (row.growth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                            if (activeCols.contains("Receivable_Amount")) Text(String.format(Locale.US, "%,.2f", row.receivableAmount), width = cellWidth)
                            if (activeCols.contains("Profit_Amount")) Text(String.format(Locale.US, "%,.2f", row.profitAmount), width = cellWidth, color = if (row.profitAmount >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                            if (activeCols.contains("Profit_Percent")) Text(String.format(Locale.US, "%+.2f%%", row.profitPercent), width = cellWidth, color = if (row.profitPercent >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Aggregate Bottom Row
        if (types.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(scrollState)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text("TOTAL SUMS", fontWeight = FontWeight.Bold, width = cellWidth, marginStart = 12.dp, color = MaterialTheme.colorScheme.primary)
                if (activeCols.contains("Item_Count")) Text("${types.sumOf { it.itemCount }}", fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Buy_Amount")) Text(String.format(Locale.US, "%,.2f", types.sumOf { it.buyAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Sale_Amount")) Text(String.format(Locale.US, "%,.2f", types.sumOf { it.saleAmount }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Balance_Qty")) Text(String.format(Locale.US, "%,.2f", types.sumOf { it.balanceQty }), fontWeight = FontWeight.Bold, width = cellWidth)

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

                if (activeCols.contains("Net_Invest")) Text(String.format(Locale.US, "%,.2f", sumNetInvest), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Return_Qty")) Text(String.format(Locale.US, "%,.2f", types.sumOf { it.returnQty }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Return_Cash")) Text(String.format(Locale.US, "%,.2f", types.sumOf { it.returnCash }), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Evaluation")) Text(String.format(Locale.US, "%,.2f", sumEvaluation), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Realized_Gain")) Text(String.format(Locale.US, "%,.2f", sumRealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumRealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Unrealized_Gain")) Text(String.format(Locale.US, "%,.2f", sumUnrealized), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumUnrealized >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Deductions")) Text(String.format(Locale.US, "%,.2f", sumDeductions), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Net_Gain")) Text(String.format(Locale.US, "%,.2f", sumNetGain), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumNetGain >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Growth")) Text(String.format(Locale.US, "%+.2f%%", overallGrowth), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallGrowth >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Receivable_Amount")) Text(String.format(Locale.US, "%,.2f", sumReceivable), fontWeight = FontWeight.Bold, width = cellWidth)
                if (activeCols.contains("Profit_Amount")) Text(String.format(Locale.US, "%,.2f", sumProfitAmt), fontWeight = FontWeight.Bold, width = cellWidth, color = if (sumProfitAmt >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
                if (activeCols.contains("Profit_Percent")) Text(String.format(Locale.US, "%+.2f%%", overallProfitPct), fontWeight = FontWeight.Bold, width = cellWidth, color = if (overallProfitPct >= 0.0) Color(0xFF2ECE7B) else Color(0xFFEB4D4B))
            }
        }
    }
}

// Compact helper to render table items alignment
@Composable
fun Text(
    text: String,
    fontWeight: FontWeight = FontWeight.Normal,
    width: androidx.compose.ui.unit.Dp,
    marginStart: androidx.compose.ui.unit.Dp = 0.dp,
    color: Color = Color.Unspecified
) {
    Text(
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
            "Balance_Qty", "Avg_CP", "Avg_SP", "LTP", "Net_Invest", "Return_Qty", "Return_Cash",
            "Evaluation", "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent"
        )
    } else {
        listOf(
            "Item_Count", "Buy_Amount", "Sale_Amount", "Return_Qty", "Return_Cash", "Balance_Qty", "Net_Invest", "Evaluation",
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
            }
        }
    }
}


// ==========================================
// 3. INGESTION ENGINE & RECORD WRITER (DATA)
// ==========================================

@Composable
fun DataScreen(viewModel: PortfolioViewModel) {
    val context = LocalContext.current
    val distinctItems by viewModel.distinctItems.collectAsStateWithLifecycle()
    val distinctTypes by viewModel.distinctTypes.collectAsStateWithLifecycle()
    val transactions by viewModel.allTransactions.collectAsStateWithLifecycle()

    var showCsvPastDialog by remember { mutableStateOf(false) }
    var pasteSchemaType by remember { mutableStateOf(1) } // 1: Transactions, 2: Meroshare

    // Since triggers outside compose lifecycle are easier, we can invoke dialogs during buttons click!
    var showImportOptionDialog by remember { mutableStateOf(false) }
    var showWipeConfirmationDialog by remember { mutableStateOf(false) }
    var currentCsvTextToImport by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

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
                        viewModel.importMeroshare(text)
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

    val filteredTransactions = remember(transactions, selectedItemFilter) {
        if (selectedItemFilter == "All") transactions
        else transactions.filter { it.item.equals(selectedItemFilter, ignoreCase = true) }
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
                    viewModel.addTransaction(record)
                }
            )
        }

        // Unified CSV Ingestions Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Unified File Ingestion Module", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { importTransactionLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("import_transactions_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.UploadFile, "CSV", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import Tx CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { importMeroshareLauncher.launch("*/*") },
                            modifier = Modifier.weight(1f).height(44.dp).testTag("import_meroshare_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Filled.CloudUpload, "MS", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Meroshare CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                            exportCsvLauncher.launch("finfolio_export_$timestamp.csv")
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("export_transactions_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)) // Forest Green
                    ) {
                        Icon(Icons.Filled.Download, "Export", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export All Transactions (CSV)", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary direct Copy-Paste dialogs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pasteSchemaType = 1
                                showCsvPastDialog = true
                            },
                            modifier = Modifier.weight(1f).height(40.dp).testTag("paste_transactions_txt")
                        ) {
                            Icon(Icons.Filled.ContentPaste, "MS", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste Tx Data", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                pasteSchemaType = 2
                                showCsvPastDialog = true
                            },
                            modifier = Modifier.weight(1f).height(40.dp).testTag("paste_meroshare_txt")
                        ) {
                            Icon(Icons.Filled.ContentPaste, "MS", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste MS Data", fontSize = 11.sp)
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
                Text(
                    "Historical Transaction Logs (${filteredTransactions.size})",
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

        if (filteredTransactions.isEmpty()) {
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
            items(filteredTransactions) { tx ->
                TransactionListItem(
                    tx = tx,
                    onEdit = { editingRecord = tx },
                    onDelete = { viewModel.deleteTransaction(tx) }
                )
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
                        viewModel.importTransactions(currentCsvTextToImport!!, overwrite = false)
                        showImportOptionDialog = false
                        currentCsvTextToImport = null
                    },
                    modifier = Modifier.testTag("import_mode_append")
                ) {
                    Text("Append Records")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.importTransactions(currentCsvTextToImport!!, overwrite = true)
                        showImportOptionDialog = false
                        currentCsvTextToImport = null
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

    // Direct Copy-Paste raw text content parser dialog
    if (showCsvPastDialog) {
        var rawTextByInput by remember { mutableStateOf("") }
        var inputOverwriteChoice by remember { mutableStateOf(false) }

        Dialog(onDismissRequest = { showCsvPastDialog = false }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (pasteSchemaType == 1) "Paste Transaction CSV" else "Paste Meroshare CSV",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (pasteSchemaType == 1) {
                            "Headers: Date,Item,Action,Qty,Amount,Type"
                        } else {
                            "Headers contains Symbol/Scrip & LTP"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = rawTextByInput,
                        onValueChange = { rawTextByInput = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp).testTag("paste_text_field"),
                        placeholder = { Text("Paste raw CSV rows...") },
                        maxLines = 10
                    )

                    if (pasteSchemaType == 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = inputOverwriteChoice, onCheckedChange = { inputOverwriteChoice = it })
                            Text("Clear / Overwrite existing transactions", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showCsvPastDialog = false }) {
                            Text("Close")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (rawTextByInput.isNotBlank()) {
                                    if (pasteSchemaType == 1) {
                                        viewModel.importTransactionsFromText(rawTextByInput, inputOverwriteChoice)
                                    } else {
                                        viewModel.importMeroshareFromText(rawTextByInput)
                                    }
                                    showCsvPastDialog = false
                                }
                            },
                            modifier = Modifier.testTag("submit_paste_btn")
                        ) {
                            Text("Submit Ingest")
                        }
                    }
                }
            }
        }
    }

    // Historical edit dialog modifier overlay
    if (editingRecord != null) {
        EditTransactionDialog(
            record = editingRecord!!,
            distinctItems = distinctItems,
            distinctTypes = distinctTypes,
            onSave = { updated ->
                viewModel.updateTransaction(updated)
                editingRecord = null
            },
            onDismiss = { editingRecord = null }
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
        DatePickerDialog(
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
                        listOf("Buy", "Sale", "Returns", "Bonus").forEach { act ->
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
                                item = itemVal.uppercase().trim(),
                                type = typeVal.uppercase().trim(),
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
                    onValueChange = { if (it.length <= 15) itemVal = it.uppercase() },
                    suggestions = distinctItems
                )

                AutoCompleteTextField(
                    label = "Sector/Category (Max 15 chars)",
                    value = typeVal,
                    onValueChange = { if (it.length <= 15) typeVal = it.uppercase() },
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
                            listOf("Buy", "Sale", "Returns", "Bonus").forEach { act ->
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
                                        item = itemVal.uppercase().trim(),
                                        type = typeVal.uppercase().trim(),
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

@Composable
fun AutoCompleteTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val filteredSuggestions = remember(value, suggestions) {
        if (value.isBlank()) suggestions
        else suggestions.filter { it.contains(value, ignoreCase = true) }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
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
            modifier = Modifier.fillMaxWidth(),
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
