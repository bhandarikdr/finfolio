package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.ExternalLtp
import com.example.data.db.TransactionRecord
import com.example.data.model.FinancialEngines
import com.example.data.model.ItemMetrics
import com.example.data.model.MarketStatus
import com.example.data.model.ScraperCategory
import com.example.data.model.TypeMetrics
import com.example.data.model.UserProfile
import com.example.data.repository.PortfolioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.InputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class DatasetScope {
    OVERALL,
    PORTFOLIO
}

/**
 * ViewModel responsible for managing portfolio metrics, transactions, and user settings.
 * Handles the logic for prioritized scraper URL management and testing.
 */
class PortfolioViewModel(private val repository: PortfolioRepository) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val marketStatus: StateFlow<MarketStatus> = repository.marketStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MarketStatus())

    val defaultScrapers = repository.defaultScrapersByCategory

    fun registerUser(name: String, email: String) {
        viewModelScope.launch {
            repository.saveUserProfile(name, email)
        }
    }

    fun updateAppSettings(currency: String, dateFormat: String) {
        viewModelScope.launch {
            repository.updateAppSettings(currency, dateFormat)
        }
    }

    fun updateFinancialRates(commission: Double, flat: Double, cgt: Double) {
        viewModelScope.launch {
            repository.updateFinancialRates(commission, flat, cgt)
            repository.triggerSnackbar("Financial calculation rates updated")
        }
    }

    fun resetFinancialRates() {
        viewModelScope.launch {
            repository.resetFinancialRates()
            repository.triggerSnackbar("Financial rates restored to defaults")
        }
    }

    fun updatePin(pin: String?) {
        viewModelScope.launch {
            repository.updatePin(pin)
            repository.triggerSnackbar(if (pin == null) "PIN Lock Disabled" else "PIN Lock Enabled")
        }
    }

    fun updatePrimaryIndexName(name: String) {
        viewModelScope.launch {
            repository.updatePrimaryIndexName(name)
            repository.triggerSnackbar("Primary index target updated to: $name")
        }
    }

    val allTransactions: StateFlow<List<TransactionRecord>> = repository.allTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHoldings: StateFlow<List<com.example.data.db.Holdings>> = repository.allHoldings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * MIGRATION LOGIC (UX Refinement):
     * Determines if the "Finalizing Setup" screen should be shown.
     * Required if Transactions exist but Holdings (Core Engine V2) is not yet populated.
     */
    private val _isMigrationSkipped = MutableStateFlow(false)
    val isMigrationRequired: StateFlow<Boolean> = combine(allTransactions, allHoldings, _isMigrationSkipped) { txs, holdings, skipped ->
        !skipped && txs.isNotEmpty() && holdings.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun skipMigration() {
        _isMigrationSkipped.value = true
    }

    fun performV2Migration() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.performV2Migration()
                repository.triggerSnackbar("App optimization complete")
            } catch (e: Exception) {
                repository.triggerSnackbar("Migration failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createBackup(context: android.content.Context, onComplete: (String?) -> Unit) {
        viewModelScope.launch {
            val result = repository.createFullBackup(context)
            result.onSuccess { onComplete(it) }.onFailure { onComplete(null) }
        }
    }

    val allExternalLtps: StateFlow<List<ExternalLtp>> = repository.allExternalLtps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val distinctItems: StateFlow<List<String>> = combine(
        repository.distinctItems,
        repository.allScripSymbols
    ) { fromData, fromMaster ->
        (fromData + fromMaster).filter { it.isNotBlank() }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentItems: StateFlow<List<String>> = repository.recentItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableItems: StateFlow<List<String>> = repository.distinctItems
        .map { list -> list.filter { it.isNotBlank() }.sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentSectors: StateFlow<List<String>> = repository.recentSectors
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val distinctSectors: StateFlow<List<String>> = repository.distinctSectors
        .map { fromData ->
            val garbage = listOf("sector", "type", "total", "action", "company", "s.no", "name", "symbol", "index", "indices")
            fromData
                .filter { 
                    val clean = it.lowercase().trim()
                    it.isNotBlank() && !garbage.contains(clean) && it.replace(",", "").toDoubleOrNull() == null 
                }
                .distinct()
                .sorted()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())


    // UI States and Filters - Independent Dashboard and Matrix Scopes
    private val _dashboardScope = MutableStateFlow(DatasetScope.OVERALL)
    val dashboardScope: StateFlow<DatasetScope> = combine(_dashboardScope, userProfile) { current, profile ->
        if (profile != null) {
            try { DatasetScope.valueOf(profile.dashboardScope) } catch(e: Exception) { current }
        } else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DatasetScope.OVERALL)

    private val _matrixScope = MutableStateFlow(DatasetScope.OVERALL)
    val matrixScope: StateFlow<DatasetScope> = combine(_matrixScope, userProfile) { current, profile ->
        if (profile != null) {
            try { DatasetScope.valueOf(profile.matrixScope) } catch(e: Exception) { current }
        } else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DatasetScope.OVERALL)

    private val _selectedSectorFilter = MutableStateFlow("All")
    val selectedSectorFilter: StateFlow<String> = combine(_selectedSectorFilter, userProfile) { current, profile ->
        if (profile != null) profile.selectedSectorFilter else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "All")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val snackbarMessage = repository.snackbarMessage

    private val _pendingSectorUpdate = MutableStateFlow<TransactionRecord?>(null)
    val pendingSectorUpdate = _pendingSectorUpdate.asStateFlow()

    // Column show/hide configuration sets
    private val _itemColumns = MutableStateFlow(
        setOf(
            "Buy_Amount", "Buy_Count", "Buy_Qty", "Sale_Amount", "Sale_Count", "Sale_Qty",
            "Balance_Qty", "Avg_CP", "Avg_SP", "LTP", "Net_Invest", "Returns_Qty", "Returns_Cash",
            "Evaluation", "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent",
        )
    )
    val itemColumns: StateFlow<Set<String>> = combine(_itemColumns, userProfile) { current, profile ->
        if (profile != null && profile.itemColumns.isNotEmpty()) profile.itemColumns else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _itemColumns.value)

    private val _sectorColumns = MutableStateFlow(
        setOf(
            "Item_Count", "Buy_Amount", "Sale_Amount", "Returns_Qty", "Returns_Cash", "Balance_Qty", "Net_Invest", "Evaluation",
            "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent",
        )
    )
    val sectorColumns: StateFlow<Set<String>> = combine(_sectorColumns, userProfile) { current, profile ->
        if (profile != null && profile.sectorColumns.isNotEmpty()) profile.sectorColumns else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _sectorColumns.value)

    // Aggregated Metrics - Calculated twice to support independent dashboard and matrix filters
    val dashboardItemMetrics: StateFlow<List<ItemMetrics>> = combine(
        allHoldings,
        allExternalLtps,
        dashboardScope,
        userProfile
    ) { holdingsList, ltpList, scope, profile ->
        val computedAll = FinancialEngines.computeItemMetricsFromHoldings(
            holdingsList, 
            ltpList,
            commissionRate = profile?.commissionRate ?: 0.0038,
            flatFee = profile?.flatFee ?: 25.0,
            cgtRate = profile?.cgtRate ?: 0.075
        )
        when (scope) {
            DatasetScope.OVERALL -> computedAll
            DatasetScope.PORTFOLIO -> computedAll.filter { it.isInExternalSync || it.balanceQty > 0.0 }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val matrixItemMetrics: StateFlow<List<ItemMetrics>> = combine(
        allHoldings,
        allExternalLtps,
        matrixScope,
        userProfile
    ) { holdingsList, ltpList, scope, profile ->
        val computedAll = FinancialEngines.computeItemMetricsFromHoldings(
            holdingsList, 
            ltpList,
            commissionRate = profile?.commissionRate ?: 0.0038,
            flatFee = profile?.flatFee ?: 25.0,
            cgtRate = profile?.cgtRate ?: 0.075
        )
        when (scope) {
            DatasetScope.OVERALL -> computedAll
            DatasetScope.PORTFOLIO -> computedAll.filter { it.isInExternalSync || it.balanceQty > 0.0 }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * MATRIX SECTOR FILTER (Context-Aware UX):
     * Dynamically lists sectors based on the active [DatasetScope].
     * Portfolio Scope -> Shows only sectors with active holdings.
     * Overall Scope -> Lists every discovered sector.
     */
    val matrixSectors: StateFlow<List<String>> = combine(
        matrixScope,
        distinctSectors,
        matrixItemMetrics
    ) { scope, allSectors, currentItems ->
        if (scope == DatasetScope.OVERALL) {
            allSectors
        } else {
            currentItems.map { it.sector }.distinct().sorted()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dashboardSectorMetrics: StateFlow<List<TypeMetrics>> = dashboardItemMetrics
        .map { activeItems ->
            FinancialEngines.computeTypeMetrics(activeItems)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val matrixSectorMetrics: StateFlow<List<TypeMetrics>> = matrixItemMetrics
        .map { activeItems ->
            FinancialEngines.computeTypeMetrics(activeItems)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalInvestment: StateFlow<Double> = dashboardItemMetrics.map { items ->
        items.sumOf { it.netInvest }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalEvaluation: StateFlow<Double> = dashboardItemMetrics.map { items ->
        items.sumOf { it.evaluation }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun setDashboardScope(scope: DatasetScope) {
        _dashboardScope.value = scope
        viewModelScope.launch { 
            repository.updateDashboardScope(scope.name)
        }
    }

    fun setMatrixScope(scope: DatasetScope) {
        _matrixScope.value = scope
        _selectedSectorFilter.value = "All"
        viewModelScope.launch { 
            repository.updateMatrixScope(scope.name)
            repository.updateSelectedSectorFilter("All")
        }
    }

    suspend fun getSectorForScrip(symbol: String): String {
        return repository.getSectorForScrip(symbol)
    }

    fun setSelectedSectorFilter(sector: String) {
        _selectedSectorFilter.value = sector
        viewModelScope.launch { repository.updateSelectedSectorFilter(sector) }
    }

    fun toggleItemColumn(column: String) {
        val current = itemColumns.value.toMutableSet()
        if (current.contains(column)) {
            current.remove(column)
        } else {
            current.add(column)
        }
        _itemColumns.value = current
        viewModelScope.launch { repository.updateItemColumns(current) }
    }

    fun setItemColumns(columns: Set<String>) {
        _itemColumns.value = columns
        viewModelScope.launch { repository.updateItemColumns(columns) }
    }

    fun toggleSectorColumn(column: String) {
        val current = sectorColumns.value.toMutableSet()
        if (current.contains(column)) {
            current.remove(column)
        } else {
            current.add(column)
        }
        _sectorColumns.value = current
        viewModelScope.launch { repository.updateSectorColumns(current) }
    }

    fun setSectorColumns(columns: Set<String>) {
        _sectorColumns.value = columns
        viewModelScope.launch { repository.updateSectorColumns(columns) }
    }

    fun addTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.insertTransaction(record)
                repository.triggerSnackbar("Successfully recorded transaction")
            } catch (e: Exception) {
                repository.triggerSnackbar("Error adding transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            val existing = allTransactions.value.find { it.id == record.id }
            if (existing != null && existing.sector != record.sector) {
                _pendingSectorUpdate.value = record
            } else {
                performUpdate(record)
            }
        }
    }

    fun confirmSectorUpdate(record: TransactionRecord, approve: Boolean) {
        viewModelScope.launch {
            _pendingSectorUpdate.value = null
            _isLoading.value = true
            try {
                if (approve) {
                    repository.updateScripSector(record.item, record.sector)
                    repository.triggerSnackbar("Sector '${record.sector}' applied to all ${record.item} records.")
                }
                repository.updateTransaction(record)
                if (!approve) {
                    repository.triggerSnackbar("Successfully modified record (Bulk update skipped)")
                }
            } catch (e: Exception) {
                repository.triggerSnackbar("Error updating transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun performUpdate(record: TransactionRecord) {
        _isLoading.value = true
        try {
            repository.updateTransaction(record)
            repository.triggerSnackbar("Successfully modified record")
        } catch (e: Exception) {
            repository.triggerSnackbar("Error updating transaction: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun deleteTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteTransaction(record)
                repository.triggerSnackbar("Transaction deleted")
            } catch (e: Exception) {
                repository.triggerSnackbar("Error deleting: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.clearAllTransactions()
                repository.triggerSnackbar("Wiped out portfolio records successfully")
            } catch (e: Exception) {
                repository.triggerSnackbar("Error: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun flushAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.flushAllData()
                repository.triggerSnackbar("App data flushed successfully")
            } catch (e: Exception) {
                repository.triggerSnackbar("Error flushing data: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshLivePrices() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = repository.refreshLivePrices()
                result.onSuccess { updated ->
                    if (updated) {
                        repository.triggerSnackbar("Market prices updated successfully")
                    } else {
                        repository.triggerSnackbar("No new market updates available at this time")
                    }
                }.onFailure { e ->
                    repository.triggerSnackbar("Sync failed: ${e.message}")
                }
            } catch (e: Exception) {
                repository.triggerSnackbar("Network failure: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importTransactions(csvText: String, overwrite: Boolean, isWacc: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importTransactionCsv(csvText.byteInputStream(), overwrite, isWacc)
            result.onSuccess { count ->
                val type = if (isWacc) "WACC" else "Standard"
                repository.triggerSnackbar("Successfully imported $count $type records")
            }.onFailure { err ->
                repository.triggerSnackbar("Import Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    private val _pendingPortfolioSync = MutableStateFlow<Pair<String, Int>?>(null)
    val pendingPortfolioSync = _pendingPortfolioSync.asStateFlow()

    fun preparePortfolioSync(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.calculatePortfolioSyncAdjustments(csvText.byteInputStream())
            result.onSuccess { count ->
                _pendingPortfolioSync.value = csvText to count
            }.onFailure { err ->
                repository.triggerSnackbar("CSV Parse Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    fun cancelPortfolioSync() {
        _pendingPortfolioSync.value = null
    }

    fun importPortfolioSync(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importPortfolioSyncCsv(csvText.byteInputStream())
            result.onSuccess { count ->
                repository.triggerSnackbar("Sync Complete: $count scrips updated. Adjustment records added to match actual holdings. Please review amounts in History.")
            }.onFailure { err ->
                repository.triggerSnackbar("Portfolio Data Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    fun updateVisibleIndices(visible: List<String>) {
        viewModelScope.launch {
            repository.updateVisibleIndices(visible)
        }
    }

    fun setAllIndicesVisible(visible: Set<String>) {
        viewModelScope.launch {
            repository.updateVisibleIndices(visible.toList())
        }
    }

    /** Triggers a bulk update for a prioritized list of URLs for a category. */
    fun updateScraperUrls(category: ScraperCategory, urls: List<String>) {
        viewModelScope.launch {
            com.example.data.util.AppLogger.i("ScraperConfig", "Updating URLs for ${category.name}: $urls")
            repository.updateScraperUrls(category, urls)
            repository.triggerSnackbar("Scraper URLs for ${category.displayName} updated")
        }
    }

    /** Wipes all custom scraper overrides and restores app to factory default URLs. */
    fun resetAllScraperUrls() {
        viewModelScope.launch {
            com.example.data.util.AppLogger.w("ScraperConfig", "Restoring all scrapers to factory defaults")
            repository.resetAllScraperUrls()
            repository.triggerSnackbar("All Scraper URLs restored to default")
        }
    }

    /** 
     * Tests a specific URL for its ability to provide data for a given category.
     * Uses a robust OkHttp client to handle SSL issues and various content types.
     */
    suspend fun testScraperUrl(category: ScraperCategory, url: String): kotlin.Result<String> {
        com.example.data.util.AppLogger.i("ScraperConfig", "Testing URL for ${category.name}: $url")
        return withContext(Dispatchers.IO) {
            try {
                val client = getUnsafeOkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (!response.isSuccessful) {
                    return@withContext kotlin.Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val contentType = response.header("Content-Type") ?: ""
                
                val success = when(category) {
                    ScraperCategory.INDEX_UPDATE -> body.contains("ctl00_ContentPlaceHolder1_lblIndexValue") || body.contains("table")
                    ScraperCategory.LTP_UPDATE -> body.contains("table") && body.length > 500
                    ScraperCategory.SCRIP_SYNC -> body.contains("table") && body.length > 1000
                    ScraperCategory.IPO_LISTING -> {
                        if (contentType.contains("application/json")) {
                            body.trim().startsWith("{") || body.trim().startsWith("[")
                        } else {
                            body.contains("tr") || body.contains("table")
                        }
                    }
                    ScraperCategory.IPO_COMPANIES -> {
                        if (body.contains("Request Rejected")) {
                            throw Exception("WAF Block: Request Rejected by CDSC Firewall")
                        }
                        body.trim().startsWith("{") || body.trim().startsWith("[") || body.contains("company")
                    }
                    ScraperCategory.IPO_RESULT -> body.contains("form") || body.contains("input") || contentType.contains("application/json")
                }
                
                if (success) {
                    com.example.data.util.AppLogger.i("ScraperConfig", "Test Success for $url")
                    kotlin.Result.success("Success: URL is reachable and returns valid data.")
                } else {
                    com.example.data.util.AppLogger.e("ScraperConfig", "Test Failed (Validation Error) for $url")
                    kotlin.Result.failure(Exception("URL reachable but data validation failed for ${category.displayName}."))
                }
            } catch (e: Exception) {
                com.example.data.util.AppLogger.e("ScraperConfig", "Test Failed (Network/SSL Error) for $url", e)
                kotlin.Result.failure(e)
            }
        }
    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder().build()
        }
    }
}

class PortfolioViewModelFactory(private val repository: PortfolioRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PortfolioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PortfolioViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
