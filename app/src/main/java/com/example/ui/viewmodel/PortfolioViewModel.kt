package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransactionRecord
import com.example.data.model.*
import com.example.data.repository.PortfolioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class PortfolioViewModel(
    private val repository: PortfolioRepository,
    private val marketRepository: com.example.data.repository.MarketRepository? = null,
    private val ipoRepository: com.example.data.repository.IpoRepository? = null
) : ViewModel() {

    private val _isScraping = MutableStateFlow(false)
    val isScraping = _isScraping.asStateFlow()

    private val _scrapeStatus = MutableStateFlow("")
    val scrapeStatus = _scrapeStatus.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    fun triggerIndividualScrape(category: ScraperCategory) {
        viewModelScope.launch {
            _isScraping.value = true
            _scrapeStatus.value = "Starting ${category.displayName}..."
            com.example.data.util.AppLogger.i("ScraperConfig", "Manual trigger for ${category.name}")
            
            try {
                when (category) {
                    ScraperCategory.PRIMARY_INDEX_STATUS, ScraperCategory.INDICES_UPDATE -> {
                        repository.refreshLivePrices()
                        marketRepository?.fetchMarketIndices(force = true)
                    }
                    ScraperCategory.SCRIP_SYNC -> {
                        marketRepository?.fetchMasterScrips()
                    }
                    ScraperCategory.LTP_UPDATE -> {
                        marketRepository?.fetchPriceChanges(force = true)
                    }
                    ScraperCategory.DP_MASTER -> {
                        marketRepository?.fetchDpMaster()
                    }
                    ScraperCategory.ISSUES_LISTING -> {
                        ipoRepository?.syncIpos(force = true)
                    }
                    else -> {
                        repository.triggerSnackbar("Direct scraping for ${category.displayName} is not supported.")
                    }
                }
                repository.triggerSnackbar("${category.displayName} completed.")
            } catch (e: Exception) {
                com.example.data.util.AppLogger.e("ScraperConfig", "Failed scrape for ${category.name}", e)
                repository.triggerSnackbar("${category.displayName} failed: ${e.message}")
            } finally {
                _isScraping.value = false
                _scrapeStatus.value = ""
            }
        }
    }

    fun triggerGlobalRefresh() {
        viewModelScope.launch {
            if (_isScraping.value) return@launch
            _isScraping.value = true
            _isLoading.value = true
            
            val sequence = listOf(
                ScraperCategory.PRIMARY_INDEX_STATUS to "Updating Market Status...",
                ScraperCategory.INDICES_UPDATE to "Fetching Market Indices...",
                ScraperCategory.SCRIP_SYNC to "Syncing Master Scrip List...",
                ScraperCategory.LTP_UPDATE to "Updating Live Prices (LTP)...",
                ScraperCategory.DP_MASTER to "Refreshing DP Member List...",
                ScraperCategory.ISSUES_LISTING to "Checking IPO Pipeline..."
            )
            
            com.example.data.util.AppLogger.i("GlobalRefresh", "Sequence started")
            
            try {
                for (step in sequence) {
                    val (cat, status) = step
                    _scrapeStatus.value = status
                    com.example.data.util.AppLogger.d("GlobalRefresh", "Task: $status")
                    
                    when (cat) {
                        ScraperCategory.PRIMARY_INDEX_STATUS -> repository.refreshLivePrices()
                        ScraperCategory.INDICES_UPDATE -> marketRepository?.fetchMarketIndices(force = true)
                        ScraperCategory.SCRIP_SYNC -> marketRepository?.fetchMasterScrips()
                        ScraperCategory.LTP_UPDATE -> marketRepository?.fetchPriceChanges(force = true)
                        ScraperCategory.DP_MASTER -> marketRepository?.fetchDpMaster()
                        ScraperCategory.ISSUES_LISTING -> ipoRepository?.syncIpos(force = true)
                        else -> {}
                    }
                    kotlinx.coroutines.delay(500)
                }
                repository.triggerSnackbar("Global Market Refresh Complete")
            } catch (e: Exception) {
                com.example.data.util.AppLogger.e("GlobalRefresh", "Sequence interrupted", e)
                repository.triggerSnackbar("Refresh failed: ${e.message}")
            } finally {
                _isScraping.value = false
                _isLoading.value = false
                _scrapeStatus.value = ""
            }
        }
    }

    val userProfile: StateFlow<UserProfile?> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val marketStatus: StateFlow<MarketStatus> = repository.marketStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MarketStatus())

    val defaultScrapers = com.example.data.model.ScraperDefaults.defaultScrapersByCategory

    fun registerUser(name: String, email: String, boid: String? = null) {
        viewModelScope.launch { repository.saveUserProfile(name, email, boid) }
    }

    fun updateAppSettings(currency: String, dateFormat: String) {
        viewModelScope.launch { repository.updateAppSettings(currency, dateFormat) }
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

    private val _isMigrationSkipped = MutableStateFlow(false)
    val isMigrationRequired: StateFlow<Boolean> = combine(allTransactions, allHoldings, _isMigrationSkipped) { txs, holdings, skipped ->
        !skipped && txs.isNotEmpty() && holdings.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun skipMigration() { _isMigrationSkipped.value = true }

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
            fromData.filter { 
                val clean = it.lowercase().trim()
                it.isNotBlank() && !garbage.contains(clean) && it.replace(",", "").toDoubleOrNull() == null 
            }.distinct().sorted()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val snackbarMessage = repository.snackbarMessage

    private val _pendingSectorUpdate = MutableStateFlow<TransactionRecord?>(null)
    val pendingSectorUpdate = _pendingSectorUpdate.asStateFlow()

    private val _pendingTransaction = MutableStateFlow<TransactionRecord?>(null)
    val pendingTransaction = _pendingTransaction.asStateFlow()

    fun setPendingTransaction(record: TransactionRecord?) { _pendingTransaction.value = record }

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

    val dashboardItemMetrics: StateFlow<List<ItemMetrics>> = combine(
        allHoldings, dashboardScope, userProfile
    ) { holdingsList, scope, profile ->
        val computedAll = FinancialEngines.computeItemMetricsFromHoldings(
            holdingsList, 
            commissionRate = profile?.commissionRate ?: 0.0038,
            flatFee = profile?.flatFee ?: 25.0,
            cgtRate = profile?.cgtRate ?: 0.075
        )
        if (scope == DatasetScope.OVERALL) computedAll else computedAll.filter { it.isInExternalSync || it.balanceQty > 0.0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val matrixItemMetrics: StateFlow<List<ItemMetrics>> = combine(
        allHoldings, matrixScope, userProfile
    ) { holdingsList, scope, profile ->
        val computedAll = FinancialEngines.computeItemMetricsFromHoldings(
            holdingsList, 
            commissionRate = profile?.commissionRate ?: 0.0038,
            flatFee = profile?.flatFee ?: 25.0,
            cgtRate = profile?.cgtRate ?: 0.075
        )
        if (scope == DatasetScope.OVERALL) computedAll else computedAll.filter { it.isInExternalSync || it.balanceQty > 0.0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val matrixSectors: StateFlow<List<String>> = combine(
        matrixScope, distinctSectors, matrixItemMetrics
    ) { scope, allSectors, currentItems ->
        if (scope == DatasetScope.OVERALL) allSectors else currentItems.map { it.sector }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dashboardSectorMetrics: StateFlow<List<TypeMetrics>> = dashboardItemMetrics.map { activeItems ->
        FinancialEngines.computeTypeMetrics(activeItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val matrixSectorMetrics: StateFlow<List<TypeMetrics>> = matrixItemMetrics.map { activeItems ->
        FinancialEngines.computeTypeMetrics(activeItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalInvestment: StateFlow<Double> = dashboardItemMetrics.map { items -> items.sumOf { it.netInvest } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalEvaluation: StateFlow<Double> = dashboardItemMetrics.map { items -> items.sumOf { it.evaluation } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun setDashboardScope(scope: DatasetScope) {
        _dashboardScope.value = scope
        viewModelScope.launch { repository.updateDashboardScope(scope.name) }
    }

    fun setMatrixScope(scope: DatasetScope) {
        _matrixScope.value = scope
        _selectedSectorFilter.value = "All"
        viewModelScope.launch { 
            repository.updateMatrixScope(scope.name)
            repository.updateSelectedSectorFilter("All")
        }
    }

    suspend fun getSectorForScrip(symbol: String): String = repository.getSectorForScrip(symbol)

    fun setSelectedSectorFilter(sector: String) {
        _selectedSectorFilter.value = sector
        viewModelScope.launch { repository.updateSelectedSectorFilter(sector) }
    }

    fun toggleItemColumn(column: String) {
        val current = itemColumns.value.toMutableSet()
        if (current.contains(column)) current.remove(column) else current.add(column)
        _itemColumns.value = current
        viewModelScope.launch { repository.updateItemColumns(current) }
    }

    fun toggleSectorColumn(column: String) {
        val current = sectorColumns.value.toMutableSet()
        if (current.contains(column)) current.remove(column) else current.add(column)
        _sectorColumns.value = current
        viewModelScope.launch { repository.updateSectorColumns(current) }
    }

    fun setItemColumns(columns: Set<String>) {
        _itemColumns.value = columns
        viewModelScope.launch { repository.updateItemColumns(columns) }
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
            if (existing != null && existing.sector != record.sector) _pendingSectorUpdate.value = record
            else performUpdate(record)
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
                if (!approve) repository.triggerSnackbar("Successfully modified record (Bulk update skipped)")
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

    private val _showSyncRecommendation = MutableStateFlow(false)
    val showSyncRecommendation = _showSyncRecommendation.asStateFlow()

    fun setShowSyncRecommendation(show: Boolean) { _showSyncRecommendation.value = show }

    fun importTransactions(csvText: String, overwrite: Boolean, isWacc: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importTransactionCsv(csvText.byteInputStream(), overwrite, isWacc)
            result.onSuccess { count ->
                val type = if (isWacc) "WACC" else "Standard"
                repository.triggerSnackbar("Successfully imported $count $type records")
                _showSyncRecommendation.value = true
            }.onFailure { err -> repository.triggerSnackbar("Import Error: ${err.message}") }
            _isLoading.value = false
        }
    }

    private val _pendingPortfolioSync = MutableStateFlow<Pair<String, Int>?>(null)
    val pendingPortfolioSync = _pendingPortfolioSync.asStateFlow()

    fun preparePortfolioSync(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.calculatePortfolioSyncAdjustments(csvText.byteInputStream())
            result.onSuccess { count -> _pendingPortfolioSync.value = csvText to count }
            .onFailure { err -> repository.triggerSnackbar("CSV Parse Error: ${err.message}") }
            _isLoading.value = false
        }
    }

    fun cancelPortfolioSync() { _pendingPortfolioSync.value = null }

    fun importPortfolioSync(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importPortfolioSyncCsv(csvText.byteInputStream())
            result.onSuccess { count ->
                repository.triggerSnackbar("Sync Complete: $count scrips updated. Adjustment records added to match actual holdings.")
                _showSyncRecommendation.value = false
            }.onFailure { err -> repository.triggerSnackbar("Portfolio Data Error: ${err.message}") }
            _isLoading.value = false
        }
    }

    fun updateVisibleIndices(visible: List<String>) { viewModelScope.launch { repository.updateVisibleIndices(visible) } }

    fun updateScraperUrls(category: ScraperCategory, urls: List<String>) {
        viewModelScope.launch {
            repository.updateScraperUrls(category, urls)
            repository.triggerSnackbar("Scraper URLs for ${category.displayName} updated")
        }
    }

    fun resetAllScraperUrls() {
        viewModelScope.launch {
            repository.resetAllScraperUrls()
            repository.triggerSnackbar("All Scraper URLs restored to default")
        }
    }

    fun resetScraperUrls(category: ScraperCategory) {
        viewModelScope.launch {
            repository.resetScraperUrls(category)
            repository.triggerSnackbar("${category.displayName} restored to default")
        }
    }

    fun exportScraperConfigToCsv(): String {
        val profile = userProfile.value
        val currentScrapers = profile?.scraperUrls ?: emptyMap()
        return buildString {
            append("Category,Priority,URL\n")
            ScraperCategory.values().forEach { category ->
                val urls = currentScrapers[category] ?: defaultScrapers[category] ?: emptyList()
                urls.forEachIndexed { index, url ->
                    val safeUrl = if (url.contains(",")) "\"$url\"" else url
                    append("${category.name},${index + 1},$safeUrl\n")
                }
            }
        }
    }

    suspend fun testScraperUrl(category: ScraperCategory, url: String): kotlin.Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val client = getUnsafeOkHttpClient()
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Accept", "*/*").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext kotlin.Result.failure(Exception("HTTP ${response.code}"))
                val contentType = response.header("Content-Type") ?: ""
                val success = when(category) {
                    ScraperCategory.INDICES_UPDATE -> body.contains("ctl00_ContentPlaceHolder1_lblIndexValue") || body.contains("table")
                    ScraperCategory.PRIMARY_INDEX_STATUS -> body.contains("Index") || body.contains("Market") || body.contains("table")
                    ScraperCategory.LTP_UPDATE -> body.contains("table") && body.length > 500
                    ScraperCategory.SCRIP_SYNC -> body.contains("table") && body.length > 1000
                    ScraperCategory.ISSUES_LISTING -> contentType.contains("application/json") || body.contains("tr") || body.contains("table")
                    ScraperCategory.IPO_RESULT -> body.contains("form") || body.contains("input") || contentType.contains("application/json")
                    ScraperCategory.DP_MASTER -> body.contains("table") && body.length > 500
                    else -> response.code == 200
                }
                if (success) kotlin.Result.success(body.take(2000)) else kotlin.Result.failure(Exception("Validation failed"))
            } catch (e: Exception) { kotlin.Result.failure(e) }
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
            OkHttpClient.Builder().sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager).hostnameVerifier { _, _ -> true }.connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        } catch (e: Exception) { OkHttpClient.Builder().build() }
    }
}

class PortfolioViewModelFactory(
    private val repository: PortfolioRepository,
    private val marketRepository: com.example.data.repository.MarketRepository? = null,
    private val ipoRepository: com.example.data.repository.IpoRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PortfolioViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PortfolioViewModel(repository, marketRepository, ipoRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
