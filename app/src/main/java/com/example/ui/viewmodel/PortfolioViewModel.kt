package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.ExternalLtp
import com.example.data.db.TransactionRecord
import com.example.data.model.FinancialEngines
import com.example.data.model.ItemMetrics
import com.example.data.model.NepseStatus
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

enum class DatasetScope {
    OVERALL,
    MEROSHARE
}

/**
 * ViewModel responsible for managing portfolio metrics, transactions, and user settings.
 * Handles the logic for prioritized scraper URL management and testing.
 */
class PortfolioViewModel(private val repository: PortfolioRepository) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = repository.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nepseStatus: StateFlow<NepseStatus> = repository.nepseStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NepseStatus())

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

    fun updatePin(pin: String?) {
        viewModelScope.launch {
            repository.updatePin(pin)
            _snackbarMessage.emit(if (pin == null) "PIN Lock Disabled" else "PIN Lock Enabled")
        }
    }

    val allTransactions: StateFlow<List<TransactionRecord>> = repository.allTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val recentTypes: StateFlow<List<String>> = repository.recentTypes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val distinctTypes: StateFlow<List<String>> = combine(
        repository.distinctTypes,
        repository.distinctSectorsFromMaster
    ) { fromData, fromMaster ->
        val garbage = listOf("sector", "type", "total", "action", "company", "s.no", "name", "symbol", "index", "indices")
        (fromData + fromMaster)
            .filter { it.isNotBlank() && !garbage.contains(it.lowercase().trim()) }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI States and Filters
    private val _datasetScope = MutableStateFlow(DatasetScope.OVERALL)
    val datasetScope: StateFlow<DatasetScope> = combine(_datasetScope, userProfile) { current, profile ->
        if (profile != null) {
            try { DatasetScope.valueOf(profile.datasetScope) } catch(e: Exception) { current }
        } else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DatasetScope.OVERALL)

    private val _selectedTypeFilter = MutableStateFlow("All")
    val selectedTypeFilter: StateFlow<String> = combine(_selectedTypeFilter, userProfile) { current, profile ->
        if (profile != null) profile.selectedSectorFilter else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "All")

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private val _pendingTypeUpdate = MutableStateFlow<TransactionRecord?>(null)
    val pendingTypeUpdate = _pendingTypeUpdate.asStateFlow()

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

    private val _typeColumns = MutableStateFlow(
        setOf(
            "Item_Count", "Buy_Amount", "Sale_Amount", "Returns_Qty", "Returns_Cash", "Balance_Qty", "Net_Invest", "Evaluation",
            "Realized_Gain", "Unrealized_Gain", "Deductions", "Net_Gain", "Growth",
            "Receivable_Amount", "Profit_Amount", "Profit_Percent",
        )
    )
    val typeColumns: StateFlow<Set<String>> = combine(_typeColumns, userProfile) { current, profile ->
        if (profile != null && profile.typeColumns.isNotEmpty()) profile.typeColumns else current
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _typeColumns.value)

    // Aggregated Metrics calculated reactively based on scope selection
    val itemMetrics: StateFlow<List<ItemMetrics>> = combine(
        allTransactions,
        allExternalLtps,
        _datasetScope
    ) { txList, ltpList, scope ->
        val computedAll = FinancialEngines.computeItemMetrics(txList, ltpList)
        when (scope) {
            DatasetScope.OVERALL -> computedAll
            DatasetScope.MEROSHARE -> computedAll.filter { it.isInMeroshareCsv || it.balanceQty > 0.0 }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val typeMetrics: StateFlow<List<TypeMetrics>> = itemMetrics
        .map { activeItems ->
            FinancialEngines.computeTypeMetrics(activeItems)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalInvestment: StateFlow<Double> = itemMetrics.map { items ->
        items.sumOf { it.netInvest }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalEvaluation: StateFlow<Double> = itemMetrics.map { items ->
        items.sumOf { it.evaluation }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun setDatasetScope(scope: DatasetScope) {
        _datasetScope.value = scope
        viewModelScope.launch { repository.updateDatasetScope(scope.name) }
    }

    suspend fun getSectorForScrip(symbol: String): String {
        return repository.getSectorForScrip(symbol)
    }

    fun setSelectedTypeFilter(type: String) {
        _selectedTypeFilter.value = type
        viewModelScope.launch { repository.updateSelectedSectorFilter(type) }
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

    fun toggleTypeColumn(column: String) {
        val current = typeColumns.value.toMutableSet()
        if (current.contains(column)) {
            current.remove(column)
        } else {
            current.add(column)
        }
        _typeColumns.value = current
        viewModelScope.launch { repository.updateTypeColumns(current) }
    }

    fun setTypeColumns(columns: Set<String>) {
        _typeColumns.value = columns
        viewModelScope.launch { repository.updateTypeColumns(columns) }
    }

    fun addTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.insertTransaction(record)
                _snackbarMessage.emit("Successfully recorded transaction")
            } catch (e: Exception) {
                _snackbarMessage.emit("Error adding transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            val existing = allTransactions.value.find { it.id == record.id }
            if (existing != null && existing.type != record.type) {
                _pendingTypeUpdate.value = record
            } else {
                performUpdate(record)
            }
        }
    }

    fun confirmTypeUpdate(record: TransactionRecord, approve: Boolean) {
        viewModelScope.launch {
            _pendingTypeUpdate.value = null
            _isLoading.value = true
            try {
                if (approve) {
                    repository.updateScripSector(record.item, record.type)
                    _snackbarMessage.emit("Sector '${record.type}' applied to all ${record.item} records.")
                }
                repository.updateTransaction(record)
                if (!approve) {
                    _snackbarMessage.emit("Successfully modified record (Bulk update skipped)")
                }
            } catch (e: Exception) {
                _snackbarMessage.emit("Error updating transaction: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun performUpdate(record: TransactionRecord) {
        _isLoading.value = true
        try {
            repository.updateTransaction(record)
            _snackbarMessage.emit("Successfully modified record")
        } catch (e: Exception) {
            _snackbarMessage.emit("Error updating transaction: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun deleteTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.deleteTransaction(record)
                _snackbarMessage.emit("Transaction deleted")
            } catch (e: Exception) {
                _snackbarMessage.emit("Error deleting: ${e.message}")
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
                _snackbarMessage.emit("Wiped out portfolio records successfully")
            } catch (e: Exception) {
                _snackbarMessage.emit("Error: ${e.message}")
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
                _snackbarMessage.emit("App data flushed successfully")
            } catch (e: Exception) {
                _snackbarMessage.emit("Error flushing data: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshLivePrices() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val success = repository.refreshLivePrices()
                if (success) {
                    _snackbarMessage.emit("Live prices fetched and updated successfully")
                } else {
                    _snackbarMessage.emit("Failed to parse live HTML tracking page")
                }
            } catch (e: Exception) {
                _snackbarMessage.emit("Network failure: ${e.message}")
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
                _snackbarMessage.emit("Successfully imported $count $type records")
            }.onFailure { err ->
                _snackbarMessage.emit("Import Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    private val _pendingMeroshareImport = MutableStateFlow<Pair<String, Int>?>(null)
    val pendingMeroshareImport = _pendingMeroshareImport.asStateFlow()

    fun prepareMeroshareImport(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.calculateMeroshareAdjustments(csvText.byteInputStream())
            result.onSuccess { count ->
                _pendingMeroshareImport.value = csvText to count
            }.onFailure { err ->
                _snackbarMessage.emit("CSV Parse Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    fun cancelMeroshareImport() {
        _pendingMeroshareImport.value = null
    }

    fun importMeroshare(csvText: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.importMeroshareCsv(csvText.byteInputStream())
            result.onSuccess { count ->
                _snackbarMessage.emit("Sync Complete: $count scrips updated. Adjustment records added to match actual holdings. Please review amounts in History.")
            }.onFailure { err ->
                _snackbarMessage.emit("Portfolio Data Error: ${err.message}")
            }
            _isLoading.value = false
        }
    }

    fun updateVisibleIndices(visible: List<String>) {
        viewModelScope.launch {
            repository.updateVisibleIndices(visible)
        }
    }

    /** Triggers a bulk update for a prioritized list of URLs for a category. */
    fun updateScraperUrls(category: ScraperCategory, urls: List<String>) {
        viewModelScope.launch {
            repository.updateScraperUrls(category, urls)
            _snackbarMessage.emit("Scraper URLs for ${category.displayName} updated")
        }
    }

    /** Wipes all custom scraper overrides and restores app to factory default URLs. */
    fun resetAllScraperUrls() {
        viewModelScope.launch {
            repository.resetAllScraperUrls()
            _snackbarMessage.emit("All Scraper URLs restored to default")
        }
    }

    /** 
     * Tests a specific URL for its ability to provide data for a given category.
     * Performs lightweight parsing/checking based on category expectations.
     */
    suspend fun testScraperUrl(category: ScraperCategory, url: String): kotlin.Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(10000)
                    .get()
                
                val success = when(category) {
                    ScraperCategory.INDEX_UPDATE -> doc.select("#ctl00_ContentPlaceHolder1_lblIndexValue, table tr").isNotEmpty()
                    ScraperCategory.LTP_UPDATE -> doc.select("table tr").size > 5
                    ScraperCategory.SCRIP_SYNC -> doc.select("table tr").size > 10
                    ScraperCategory.IPO_LISTING -> doc.select("tr, .ipo-item, table").isNotEmpty()
                    ScraperCategory.CDSC_COMPANIES -> {
                        // CDSC usually returns JSON
                        try {
                            val text = Jsoup.connect(url).ignoreContentType(true).execute().body()
                            org.json.JSONArray(text).length() >= 0
                        } catch (e: Exception) { false }
                    }
                    ScraperCategory.CDSC_RESULT -> true
                }
                
                if (success) kotlin.Result.success("Success: URL is reachable and data structure looks valid.")
                else kotlin.Result.failure(Exception("URL reachable but target data not found."))
            } catch (e: Exception) {
                kotlin.Result.failure(e)
            }
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
