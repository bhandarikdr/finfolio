package com.example.data.repository

import android.content.Context
import com.example.data.db.IpoMasterDao
import com.example.data.db.PortfolioDao
import com.example.data.db.TransactionRecord
import com.example.data.model.MarketStatus
import com.example.data.model.ScraperCategory
import com.example.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

import com.example.data.db.UserEntity
import com.example.data.model.DatabaseSnapshot
import com.example.data.util.AppLogger
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream

/**
 * Repository handling user profile, application settings, and generic scraper configurations.
 * Manages persistence of transactions and external LTP data.
 */
class PortfolioRepository(
    private val portfolioDao: PortfolioDao,
    private val ipoMasterDao: IpoMasterDao
) {

    val DEFAULT_PRIMARY_INDEX = "NEPSE Index"

    /** Default financial calculation rates (Nepal Market Standards) */
    val defaultFinancialRates = mapOf(
        "commission" to 0.0038,
        "flat_fee" to 25.0,
        "cgt" to 0.075
    )

    /** 
     * Flow of the user profile, merging database entity with default values.
     * Parses scraper URLs from JSON storage into a typed Map.
     */
    val userProfile: Flow<UserProfile> = portfolioDao.getUserProfile().map { entity ->
        val scraperMap = mutableMapOf<ScraperCategory, List<String>>()
        if (!entity?.scraperUrlsJson.isNullOrBlank()) {
            try {
                val json = JSONObject(entity!!.scraperUrlsJson)
                ScraperCategory.entries.forEach { cat ->
                    if (json.has(cat.name)) {
                        val arr = json.getJSONArray(cat.name)
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        scraperMap[cat] = list
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        // Fill defaults if missing
        ScraperCategory.entries.forEach { cat ->
            if (scraperMap[cat].isNullOrEmpty()) {
                scraperMap[cat] = com.example.data.model.ScraperDefaults.defaultScrapersByCategory[cat] ?: emptyList()
            }
        }
        
        UserProfile(
            name = entity?.name ?: "",
            email = entity?.email ?: "",
            currencySymbol = entity?.currencySymbol ?: "रु.",
            dateFormat = entity?.dateFormat ?: "AD",
            visibleIndices = entity?.visibleIndicesJson?.let { 
                if (it.isBlank()) emptyList() else it.split(",").filter { s -> s.isNotBlank() }
            } ?: emptyList(),
            scraperUrls = scraperMap,
            pin = entity?.pin,
            itemColumns = entity?.itemColumnsJson?.let { 
                if (it.isBlank()) emptySet() else it.split(",").toSet()
            } ?: emptySet(),
            sectorColumns = entity?.sectorColumnsJson?.let { 
                if (it.isBlank()) emptySet() else it.split(",").toSet()
            } ?: emptySet(),
            selectedSectorFilter = entity?.selectedSectorFilter ?: "All",
            dashboardScope = entity?.dashboardScope ?: "OVERALL",
            matrixScope = entity?.matrixScope ?: "OVERALL",
            primaryIndexName = entity?.primaryIndexName ?: "NEPSE Index",
            commissionRate = entity?.commissionRate ?: 0.0038,
            flatFee = entity?.flatFee ?: 25.0,
            cgtRate = entity?.cgtRate ?: 0.075,
            boid = entity?.boid
        )
    }

    private val _marketStatus = MutableStateFlow(MarketStatus())
    val marketStatus = _marketStatus.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(replay = 0)
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    suspend fun triggerSnackbar(message: String) {
        _snackbarMessage.emit(message)
    }

    suspend fun saveUserProfile(name: String, email: String, boid: String? = null) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            portfolioDao.saveUserProfile(
                UserEntity(
                    name = name,
                    email = email,
                    currencySymbol = existing?.currencySymbol ?: "रु.",
                    dateFormat = existing?.dateFormat ?: "AD",
                    visibleIndicesJson = existing?.visibleIndicesJson ?: "",
                    scraperUrlsJson = existing?.scraperUrlsJson ?: "",
                    pin = existing?.pin,
                    itemColumnsJson = existing?.itemColumnsJson ?: "",
                    sectorColumnsJson = existing?.sectorColumnsJson ?: "",
                    selectedSectorFilter = existing?.selectedSectorFilter ?: "All",
                    dashboardScope = existing?.dashboardScope ?: "OVERALL",
                    matrixScope = existing?.matrixScope ?: "OVERALL",
                    primaryIndexName = existing?.primaryIndexName ?: "NEPSE Index",
                    commissionRate = existing?.commissionRate ?: 0.0038,
                    flatFee = existing?.flatFee ?: 25.0,
                    cgtRate = existing?.cgtRate ?: 0.075,
                    boid = boid ?: existing?.boid
                )
            )
        }
    }

    suspend fun updateAppSettings(currency: String, dateFormat: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            portfolioDao.saveUserProfile(
                UserEntity(
                    name = existing?.name ?: "",
                    email = existing?.email ?: "",
                    currencySymbol = currency,
                    dateFormat = dateFormat,
                    visibleIndicesJson = existing?.visibleIndicesJson ?: "",
                    scraperUrlsJson = existing?.scraperUrlsJson ?: "",
                    pin = existing?.pin,
                    itemColumnsJson = existing?.itemColumnsJson ?: "",
                    sectorColumnsJson = existing?.sectorColumnsJson ?: "",
                    selectedSectorFilter = existing?.selectedSectorFilter ?: "All",
                    dashboardScope = existing?.dashboardScope ?: "OVERALL",
                    matrixScope = existing?.matrixScope ?: "OVERALL",
                    primaryIndexName = existing?.primaryIndexName ?: "NEPSE Index",
                    commissionRate = existing?.commissionRate ?: 0.0038,
                    flatFee = existing?.flatFee ?: 25.0,
                    cgtRate = existing?.cgtRate ?: 0.075
                )
            )
        }
    }

    suspend fun updateFinancialRates(commission: Double, flat: Double, cgt: Double) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(
                    commissionRate = commission,
                    flatFee = flat,
                    cgtRate = cgt
                ))
            }
        }
    }

    suspend fun resetFinancialRates() {
        updateFinancialRates(
            defaultFinancialRates["commission"] ?: 0.0038,
            defaultFinancialRates["flat_fee"] ?: 25.0,
            defaultFinancialRates["cgt"] ?: 0.075
        )
    }

    suspend fun updatePrimaryIndexName(newName: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                val oldName = existing.primaryIndexName
                
                // Cleanup: Remove the old name from MarketIndices table to avoid duplicates
                portfolioDao.deleteMarketIndexByName(oldName)
                
                // Also remove old name from visibleIndices set if it exists
                val visibleList = existing.visibleIndicesJson.split(",").toMutableSet()
                visibleList.remove(oldName)
                
                portfolioDao.saveUserProfile(existing.copy(
                    primaryIndexName = newName,
                    visibleIndicesJson = visibleList.joinToString(",")
                ))

                // Explicitly trigger a refresh to fetch the new primary index data immediately
                refreshLivePrices()
            }
        }
    }

    suspend fun updatePin(newPin: String?) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(pin = newPin))
            }
        }
    }

    /** Updates the list of market indices that are displayed on the home screen pulse. */
    suspend fun updateVisibleIndices(indices: List<String>) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(visibleIndicesJson = indices.joinToString(",")))
            }
        }
    }

    suspend fun updateItemColumns(cols: Set<String>) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(itemColumnsJson = cols.joinToString(",")))
            }
        }
    }

    suspend fun updateSectorColumns(cols: Set<String>) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(sectorColumnsJson = cols.joinToString(",")))
            }
        }
    }

    suspend fun updateSelectedSectorFilter(filter: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(selectedSectorFilter = filter))
            }
        }
    }

    suspend fun updateDashboardScope(scope: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(dashboardScope = scope))
            }
        }
    }

    suspend fun updateMatrixScope(scope: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(matrixScope = scope))
            }
        }
    }

    /** Saves a prioritized list of URLs for a specific scraper category. */
    suspend fun updateScraperUrls(category: ScraperCategory, urls: List<String>) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            val json = if (!existing?.scraperUrlsJson.isNullOrBlank()) {
                try { JSONObject(existing!!.scraperUrlsJson) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            
            val arr = org.json.JSONArray()
            urls.forEach { arr.put(it) }
            json.put(category.name, arr)
            
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(scraperUrlsJson = json.toString()))
            } else {
                portfolioDao.saveUserProfile(
                    UserEntity(
                        name = "", email = "",
                        scraperUrlsJson = json.toString()
                    )
                )
            }
        }
    }

    /** Wipes all custom scraper overrides and restores app to factory default URLs. */
    suspend fun resetAllScraperUrls() {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(scraperUrlsJson = ""))
            }
        }
    }

    /** Wipes custom scraper overrides for a specific category. */
    suspend fun resetScraperUrls(category: ScraperCategory) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null && !existing.scraperUrlsJson.isNullOrBlank()) {
                try {
                    val json = JSONObject(existing.scraperUrlsJson)
                    if (json.has(category.name)) {
                        json.remove(category.name)
                        portfolioDao.saveUserProfile(existing.copy(scraperUrlsJson = json.toString()))
                    }
                } catch (e: Exception) {
                    AppLogger.e("PortfolioRepo", "Failed to reset scraper for ${category.name}", e)
                }
            }
        }
    }

    /** Returns the prioritized list of URLs for a category, falling back to defaults if none saved. */
    suspend fun getScraperUrls(category: ScraperCategory): List<String> {
        return withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (!existing?.scraperUrlsJson.isNullOrBlank()) {
                try {
                    val json = JSONObject(existing!!.scraperUrlsJson)
                    if (json.has(category.name)) {
                        val arr = json.getJSONArray(category.name)
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        if (list.isNotEmpty()) return@withContext list
                    }
                } catch (e: Exception) {}
            }
            com.example.data.model.ScraperDefaults.defaultScrapersByCategory[category] ?: emptyList()
        }
    }

    suspend fun getScraperUrl(key: String): String {
        val category = when(key) {
            "LTP_SOURCE_1" -> ScraperCategory.LTP_UPDATE
            "INDEX_SOURCE_1", "INDEX_SOURCE_2" -> ScraperCategory.INDICES_UPDATE
            "SCRIP_MASTER" -> ScraperCategory.SCRIP_SYNC
            "IPO_PIPELINE", "IPO_LISTING_SOURCE" -> ScraperCategory.ISSUES_LISTING
            "CDSC_RESULT_CHECK" -> ScraperCategory.IPO_RESULT
            else -> return ""
        }
        return getScraperUrls(category).firstOrNull() ?: ""
    }

    val allTransactions: Flow<List<TransactionRecord>> = portfolioDao.getAllTransactions()
    val allHoldings: Flow<List<com.example.data.db.Holdings>> = portfolioDao.getAllHoldings()
    val distinctItems: Flow<List<String>> = portfolioDao.getDistinctItems()
    val allScripSymbols: Flow<List<String>> = portfolioDao.getAllScripMaster().map { list -> list.map { it.symbol } }
    val recentItems: Flow<List<String>> = portfolioDao.getRecentItems()
    val recentSectors: Flow<List<String>> = portfolioDao.getRecentSectors()
    val distinctSectors: Flow<List<String>> = portfolioDao.getDistinctSectors()
    val distinctSectorsFromMaster: Flow<List<String>> = portfolioDao.getDistinctSectorsFromMaster()

    suspend fun insertTransaction(record: TransactionRecord): Long {
        return withContext(Dispatchers.IO) {
            val id = portfolioDao.insertTransaction(record)
            recalculateHoldingsForScrip(record.item)
            id
        }
    }

    suspend fun updateTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.updateTransaction(record)
            recalculateHoldingsForScrip(record.item)
        }
    }

    suspend fun deleteTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.deleteTransaction(record)
            recalculateHoldingsForScrip(record.item)
        }
    }

    suspend fun clearAllTransactions() {
        withContext(Dispatchers.IO) {
            portfolioDao.clearAllTransactions()
            portfolioDao.clearAllHoldings()
        }
    }

    /**
     * CORE ENGINE: Recalculates the pre-computed Holdings entry for a specific scrip.
     * Uses the State-Driven Cost Recovery Model.
     */
    private suspend fun recalculateHoldingsForScrip(symbol: String) {
        val transactions = portfolioDao.getAllTransactionsSync()
            .filter { it.item.uppercase().trim() == symbol.uppercase().trim() }

        if (transactions.isEmpty()) {
            // Optional: delete holding if no transactions exist
            return
        }

        val existingHolding = portfolioDao.getHoldingsBySymbol(symbol.uppercase().trim())
        val scripMaster = portfolioDao.getScripMasterBySymbol(symbol.uppercase().trim())

        val sector = transactions.firstOrNull()?.sector ?: "Other"
        var totalBuyAmt = 0.0
        var totalSaleAmt = 0.0
        var totalReturnCash = 0.0
        var totalBuyQty = 0.0
        var totalSaleQty = 0.0
        var totalReturnQty = 0.0
        var lastDate: String? = null

        for (tx in transactions) {
            lastDate = tx.date
            when (tx.action) {
                "Buy" -> {
                    totalBuyQty += tx.qty
                    totalBuyAmt += tx.amount
                }
                "Sale" -> {
                    totalSaleQty += tx.qty
                    totalSaleAmt += tx.amount
                }
                "Returns" -> {
                    totalReturnQty += tx.qty
                    totalReturnCash += tx.amount
                }
            }
        }

        portfolioDao.insertHoldings(
            com.example.data.db.Holdings(
                symbol = symbol.uppercase().trim(),
                sector = sector,
                ltp = existingHolding?.ltp ?: scripMaster?.ltp ?: 0.0,
                prevLtp = existingHolding?.prevLtp ?: scripMaster?.previousLtp ?: 0.0,
                source = existingHolding?.source ?: "Scraped",
                isInExternalSync = existingHolding?.isInExternalSync ?: false,
                timestamp = existingHolding?.timestamp ?: scripMaster?.timestamp ?: 0L,
                totalBuyAmount = totalBuyAmt,
                totalSaleAmount = totalSaleAmt,
                returnsCash = totalReturnCash,
                totalBuyQty = totalBuyQty,
                totalSaleQty = totalSaleQty,
                returnsQty = totalReturnQty,
                lastTransactionDate = lastDate
            )
        )
    }

    suspend fun flushAllData() {
        withContext(Dispatchers.IO) {
            portfolioDao.clearAllTransactions()
            portfolioDao.clearAllMarketData()
            portfolioDao.clearAllHoldings()
            // Kept: clearAllMarketIndices() - Per user request to preserve index data
            portfolioDao.clearAllSectorMappings()
            portfolioDao.clearAllBoids()
            ipoMasterDao.deleteAll()
            ipoMasterDao.clearResultCache()
            // Kept: UserProfile settings (name, email, pin, scrapers) preserved
        }
    }

    /**
     * Phase 1.4: Pre-Flight Migration Script.
     * Populates the Holdings table from all existing transactions.
     */
    suspend fun performV2Migration(): Int {
        AppLogger.i("Migration", "Starting V2 Schema Migration (Populating Holdings)")
        return withContext(Dispatchers.IO) {
            val scrips = portfolioDao.getAllTransactionsSync().map { it.item.uppercase().trim() }.distinct()
            portfolioDao.clearAllHoldings()
            scrips.forEach { recalculateHoldingsForScrip(it) }
            AppLogger.i("Migration", "Migration complete. Processed ${scrips.size} scrips.")
            scrips.size
        }
    }

    /**
     * Phase 0.2: Snapshot State Exporter.
     * Creates a full JSON backup of the current database state.
     */
    suspend fun createFullBackup(context: Context): Result<String> {
        AppLogger.i("Backup", "Creating full database snapshot...")
        return withContext(Dispatchers.IO) {
            try {
                val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                val adapter = moshi.adapter(DatabaseSnapshot::class.java)
                
                val snapshot = DatabaseSnapshot(
                    timestamp = System.currentTimeMillis(),
                    appVersion = "2.0",
                    transactions = portfolioDao.getAllTransactionsSync(),
                    holdings = emptyList(), // No need to backup pre-computed table
                    scripMaster = emptyList(), // Master list can be re-synced
                    boids = portfolioDao.getAllBoidsSync(),
                    userProfile = portfolioDao.getUserProfileSync()
                )
                
                val json = adapter.toJson(snapshot)
                val file = File(context.cacheDir, "finfolio_backup_${System.currentTimeMillis()}.json")
                FileOutputStream(file).use { it.write(json.toByteArray()) }
                
                AppLogger.i("Backup", "Snapshot created successfully: ${file.name}")
                Result.success(file.absolutePath)
            } catch (e: Exception) {
                AppLogger.e("Backup", "Snapshot failed", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateScripSector(symbol: String, newSector: String) {
        withContext(Dispatchers.IO) {
            portfolioDao.updateSectorBySymbol(symbol.uppercase().trim(), newSector)
        }
    }

    suspend fun getSectorForScrip(symbol: String): String {
        return withContext(Dispatchers.IO) {
            val existing = portfolioDao.getExistingSectorBySymbol(symbol.uppercase().trim())
            if (existing != null && existing != "Other") return@withContext existing
            
            val masterSector = portfolioDao.getSectorFromMaster(symbol.uppercase().trim())
            masterSector ?: "Other"
        }
    }

    /** 
     * STRATEGY: Global Scraping Refusal / Filtering
     * Used to reject invalid CSV or Scraper rows.
     */
    private val garbageTerms = listOf("symbol", "s.no", "name", "sector", "company", "action", "total", "index", "indices", "type")

    suspend fun importTransactionCsv(inputStream: InputStream, overwrite: Boolean, isWaccSchema: Boolean = false): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    return@withContext Result.failure(Exception("The selected CSV file is empty"))
                }

                val headerLine = lines.first().replace("\uFEFF", "")
                var separator = ","
                if (headerLine.contains(";") && (headerLine.count { it == ';' } > headerLine.count { it == ',' })) {
                    separator = ";"
                }
                
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                val records = mutableListOf<TransactionRecord>()
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                if (isWaccSchema) {
                    /**
                     * STRATEGY: WACC CSV Mapping
                     * Scans for Scrip/Symbol, Quantity, Rate/Price, and Amount/Cost.
                     * Optionally parses "Sector" if present.
                     */
                    val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") }
                    val qtyIdx = header.indexOfFirst { it.contains("quantity") || it.contains("qty") || it.contains("balance") }
                    val rateIdx = header.indexOfFirst { it.contains("rate") || it.contains("price") }
                    val costIdx = header.indexOfFirst { it.contains("cost") || it.contains("amount") }
                    val sectorIdx = header.indexOfFirst { it.contains("sector") || it.contains("type") }
                    val dateIdx = header.indexOfFirst { it.contains("date") }

                    if (scripIdx == -1) return@withContext Result.failure(Exception("Invalid WACC CSV: Symbol column missing"))

                    for (i in 1 until lines.size) {
                        val cols = parseCsvRow(lines[i], separator).map { it.replace("\"", "").trim() }
                        if (cols.size > scripIdx && cols[scripIdx].isNotBlank()) {
                            val symbol = cols[scripIdx].uppercase().trim()
                            if (garbageTerms.contains(symbol.lowercase())) continue

                            val qty = cols.getOrNull(qtyIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            var amount = cols.getOrNull(costIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            if (amount == 0.0 && rateIdx != -1) {
                                val rate = cols.getOrNull(rateIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                                amount = qty * rate
                            }
                            
                            val date = cols.getOrNull(dateIdx)?.takeIf { it.isNotBlank() } ?: todayStr
                            val sector = if (sectorIdx != -1 && sectorIdx < cols.size && cols[sectorIdx].isNotBlank()) cols[sectorIdx] else getSectorForScrip(symbol)
                            
                            records.add(TransactionRecord(date = date, item = symbol, sector = sector, action = "Buy", qty = qty, amount = amount))
                        }
                    }
                } else {
                    /**
                     * STRATEGY: Standard Transaction CSV Mapping
                     * Comprehensive scan for all transaction metadata.
                     */
                    val dateIdx = header.indexOfFirst { it.contains("date") }
                    val itemIdx = header.indexOfFirst { it.contains("item") || it.contains("symbol") || it.contains("scrip") }
                    val actionIdx = header.indexOfFirst { it.contains("action") || it.contains("buy/sell") }
                    val qtyIdx = header.indexOfFirst { it.contains("qty") || it.contains("quantity") || it.contains("balance") }
                    val amountIdx = header.indexOfFirst { it.contains("amount") || it.contains("total") }
                    val sectorIdx = header.indexOfFirst { it.contains("sector") || it.contains("type") || it.contains("category") }
                    val ltpIdx = header.indexOfFirst { it == "ltp" || it == "price" || it.contains("last traded") }
                    val prevLtpIdx = header.indexOfFirst { it.contains("prev") && it.contains("ltp") || it.contains("previous") }

                    if (itemIdx == -1) return@withContext Result.failure(Exception("Invalid CSV: Symbol column missing"))

                    for (i in 1 until lines.size) {
                        val cols = parseCsvRow(lines[i], separator).map { it.replace("\"", "").trim() }
                        if (cols.size > itemIdx) {
                            val symbol = cols[itemIdx].uppercase().trim()
                            if (symbol.isBlank() || garbageTerms.contains(symbol.lowercase())) continue

                            val sector = if (sectorIdx != -1 && sectorIdx < cols.size && cols[sectorIdx].isNotBlank()) cols[sectorIdx] else getSectorForScrip(symbol)
                            val actionRaw = if (actionIdx != -1 && actionIdx < cols.size) cols[actionIdx] else "Buy"
                            val qty = cols.getOrNull(qtyIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val amount = cols.getOrNull(amountIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val date = cols.getOrNull(dateIdx)?.takeIf { it.isNotBlank() } ?: todayStr

                            if (ltpIdx != -1 && ltpIdx < cols.size) {
                                val ltpVal = cols[ltpIdx].replace(",", "").toDoubleOrNull() ?: 0.0
                                if (ltpVal > 0) {
                                    val existingH = portfolioDao.getHoldingsBySymbol(symbol)
                                    val scripM = portfolioDao.getScripMasterBySymbol(symbol)
                                    portfolioDao.updateHoldingPrice(
                                        symbol = symbol,
                                        ltp = ltpVal,
                                        prev = existingH?.prevLtp ?: scripM?.previousLtp ?: 0.0,
                                        source = "CSV_Import",
                                        sync = existingH?.isInExternalSync ?: false,
                                        ts = System.currentTimeMillis()
                                    )
                                }
                            }

                /**
                 * STRATEGY: Transaction Normalization
                 * Logic for mapping action aliases to internal Enums (Buy, Sale, Returns).
                 */
                val normalizedAction = when {
                    actionRaw.equals("sale", ignoreCase = true) || actionRaw.equals("sell", ignoreCase = true) -> "Sale"
                    actionRaw.equals("returns", ignoreCase = true) || actionRaw.equals("return", ignoreCase = true) || actionRaw.equals("bonus", ignoreCase = true) -> "Returns"
                    else -> "Buy"
                }
                            records.add(TransactionRecord(date = date, item = symbol, sector = sector, action = normalizedAction, qty = qty, amount = amount))
                        }
                    }
                }

                if (overwrite) {
                    portfolioDao.clearAllTransactions()
                    portfolioDao.clearAllHoldings()
                }
                
                records.forEach { portfolioDao.insertTransaction(it) }
                
                // Recalculate holdings for all imported scrips
                val affectedScrips = records.map { it.item.uppercase().trim() }.distinct()
                affectedScrips.forEach { recalculateHoldingsForScrip(it) }
                
                Result.success(records.size)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    suspend fun calculatePortfolioSyncAdjustments(inputStream: InputStream): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@withContext Result.success(0)

                val headerLine = lines.first().replace("\uFEFF", "")
                var separator = ","
                if (headerLine.contains(";") && (headerLine.count { it == ';' } > headerLine.count { it == ',' })) separator = ";"
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") || it.contains("item") || it.contains("name") }
                val qtyIdx = header.indexOfFirst { it.contains("current") || it.contains("balance") || it.contains("units") || it.contains("qty") || it.contains("quantity") }

                if (scripIdx == -1 || qtyIdx == -1) return@withContext Result.success(0)

                val allTx = portfolioDao.getAllTransactionsSync()
                val groupedTx = allTx.groupBy { it.item.uppercase().trim() }
                var adjustmentCount = 0
                val csvScrips = mutableSetOf<String>()

                for (i in 1 until lines.size) {
                    val cols = parseCsvRow(lines[i], separator)
                    if (cols.size > maxOf(scripIdx, qtyIdx)) {
                        val scrip = cols[scripIdx].uppercase().trim()
                        if (scrip.isEmpty() || garbageTerms.contains(scrip.lowercase())) continue
                        csvScrips.add(scrip)
                        
                        val importedQty = cols[qtyIdx].replace("\"", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                        val scripTx = groupedTx[scrip] ?: emptyList()
                        val currentBalance = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty } - scripTx.filter { it.action == "Sale" }.sumOf { it.qty }
                        if (Math.abs(currentBalance - importedQty) > 0.001) adjustmentCount++
                    }
                }

                for ((scrip, scripTx) in groupedTx) {
                    if (scrip !in csvScrips) {
                        val balance = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty } - scripTx.filter { it.action == "Sale" }.sumOf { it.qty }
                        if (balance > 0.001) adjustmentCount++
                    }
                }
                Result.success(adjustmentCount)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    suspend fun importPortfolioSyncCsv(inputStream: InputStream): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@withContext Result.failure(Exception("The selected CSV file is empty"))

                val headerLine = lines.first().replace("\uFEFF", "")
                var separator = ","
                if (headerLine.contains(";") && (headerLine.count { it == ';' } > headerLine.count { it == ',' })) separator = ";"
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                /**
                 * STRATEGY: Portfolio Sync CSV Mapping
                 * Maps scrip, price, and balance for portfolio alignment.
                 */
                val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") || it.contains("item") || it.contains("name") }
                val ltpIdx = header.indexOfFirst { it.contains("last transaction price") || it.contains("ltp") || it.contains("price") || it.contains("rate") || it.contains("valu") }
                val prevLtpIdx = header.indexOfFirst { it.contains("prev") || it.contains("previous") }
                val qtyIdx = header.indexOfFirst { it.contains("current") || it.contains("balance") || it.contains("units") || it.contains("qty") || it.contains("quantity") }

                if (scripIdx == -1 || ltpIdx == -1) return@withContext Result.failure(Exception("Required columns 'Scrip' and 'LTP' not found in CSV."))

                val timestamp = System.currentTimeMillis()
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                val allTx = portfolioDao.getAllTransactionsSync()
                val groupedTx = allTx.groupBy { it.item.uppercase().trim() }

                for (i in 1 until lines.size) {
                    val rowText = lines[i]
                    val cols = parseCsvRow(rowText, separator)
                    if (cols.size > maxOf(scripIdx, ltpIdx)) {
                        val scrip = cols[scripIdx].uppercase().trim()
                        if (scrip.isEmpty() || garbageTerms.contains(scrip.lowercase())) continue
                        val ltpValue = cols[ltpIdx].replace("\"", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                        val importedQty = if (qtyIdx != -1 && qtyIdx < cols.size) cols[qtyIdx].replace("\"", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0 else 0.0
                        
                        // Update Holding Price directly
                        val existingH = portfolioDao.getHoldingsBySymbol(scrip)
                        val scripM = portfolioDao.getScripMasterBySymbol(scrip)
                        portfolioDao.updateHoldingPrice(
                            symbol = scrip,
                            ltp = ltpValue,
                            prev = existingH?.prevLtp ?: scripM?.previousLtp ?: 0.0,
                            source = "PortfolioSync",
                            sync = true,
                            ts = timestamp
                        )

                        if (qtyIdx != -1) {
                            val scripTx = groupedTx[scrip] ?: emptyList()
                            val currentBalance = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty } - scripTx.filter { it.action == "Sale" }.sumOf { it.qty }
                            if (Math.abs(currentBalance - importedQty) > 0.001) {
                                val diff = importedQty - currentBalance
                                val totalBuyAmount = scripTx.filter { it.action == "Buy" }.sumOf { it.amount }
                                val totalBuyQty = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty }
                                val avgCost = if (totalBuyQty > 0) totalBuyAmount / totalBuyQty else 0.0
                                val (action, adjQty, adjAmount) = if (diff < 0) Triple("Sale", Math.abs(diff), Math.abs(diff) * (if (avgCost > 0) avgCost else ltpValue)) else Triple("Returns", diff, 0.0)
                                portfolioDao.insertTransaction(TransactionRecord(date = todayStr, item = scrip, sector = scripTx.firstOrNull()?.sector ?: getSectorForScrip(scrip), action = action, qty = adjQty, amount = adjAmount, isSystemAdjustment = true))
                            }
                        }
                    }
                }

                portfolioDao.resetExternalSyncFlag()
                
                // Recalculate holdings for all scrips involved in the import (to update amounts, but ltp was already set above)
                val allAffectedScrips = (lines.drop(1).mapNotNull { row -> 
                    val cols = parseCsvRow(row, separator)
                    if (cols.size > scripIdx) cols[scripIdx].uppercase().trim() else null 
                } + groupedTx.keys).distinct()
                
                allAffectedScrips.forEach { recalculateHoldingsForScrip(it) }

                Result.success(allAffectedScrips.size)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    private fun parseCsvRow(rowText: String, separator: String = ","): List<String> {
        val tokens = mutableListOf<String>()
        var curToken = StringBuilder()
        var insideQuotes = false
        var i = 0
        val sepChar = if (separator.isNotEmpty()) separator[0] else ','
        while (i < rowText.length) {
            val c = rowText[i]
            when {
                c == '"' -> insideQuotes = !insideQuotes
                c == sepChar && !insideQuotes -> {
                    tokens.add(curToken.toString().trim())
                    curToken = StringBuilder()
                }
                else -> curToken.append(c)
            }
            i++
        }
        tokens.add(curToken.toString().trim())
        return tokens
    }

    /**
     * LIVE PRICE SYNC (Enhanced Accuracy UX):
     * Refreshes market status and primary index data.
     * Returns true if data was actually updated, false if discarded (no change).
     */
    suspend fun refreshLivePrices(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                var indexValue = "0.00"
                var pctChange = "0.00%"
                var ptChange = ""
                var isPositive = true
                var marketStatus = "Market Closed"
                var marketDate = SimpleDateFormat("MMM dd | hh:mm a", Locale.US).format(Date())
                var success = false

                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                val statusUrls = getScraperUrls(ScraperCategory.PRIMARY_INDEX_STATUS) + getScraperUrls(ScraperCategory.INDICES_UPDATE)
                val primaryName = portfolioDao.getUserProfileSync()?.primaryIndexName ?: "NEPSE Index"

                for (url in statusUrls) {
                    try {
                        AppLogger.d("MarketStatus", "Attempting scrape from: $url")
                        val useUnsafe = url.contains("nepalstock.com")
                        val html = if (useUnsafe) {
                            val client = com.example.data.util.NetworkUtils.getUnsafeOkHttpClient()
                            val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                            client.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                                response.body?.string() ?: ""
                            }
                        } else {
                            Jsoup.connect(url).userAgent(userAgent).timeout(15000).get().html()
                        }
                        
                        if (html.isBlank()) continue
                        val doc = Jsoup.parse(html)
                        
                        // 1. Market Status Identification (Improved regex/contains)
                        val fullText = doc.text()
                        if (fullText.contains("Market Open", true) || fullText.contains("Market Live", true) || fullText.contains("Live Market", true)) {
                            marketStatus = "Market Open"
                        } else if (fullText.contains("Market Closed", true) || fullText.contains("Market Close", true)) {
                            marketStatus = "Market Closed"
                        }

                        // Also check specific elements for status
                        val statusEl = doc.select(".market-status, .live-market, #ctl00_ContentPlaceHolder1_lblMarketStatus").firstOrNull()
                        if (statusEl != null) {
                            val text = statusEl.text()
                            if (text.isNotBlank()) {
                                marketStatus = if (text.contains("Open", true) || text.contains("Live", true)) "Market Open" else "Market Closed"
                            }
                        }

                        // 2. Primary Index Value Extraction (Prioritize exact match for primaryName)
                        val tables = doc.select("table")
                        var foundPrimaryInTable = false
                        
                        for (table in tables) {
                            val allRows = table.select("tr")
                            if (allRows.size < 2) continue
                            
                            // Identify columns via headers (similar to MarketRepository for consistency)
                            var nameIdx = -1; var valIdx = -1; var chgIdx = -1; var pctIdx = -1
                            
                            for (i in 0 until minOf(allRows.size, 3)) {
                                val cells = allRows[i].select("th, td")
                                val texts = cells.map { it.text().lowercase().trim() }
                                texts.forEachIndexed { idx, text ->
                                    if (text.contains("index") || text == "particulars") nameIdx = idx
                                    if (text == "close" || text == "ltp" || text == "current" || text == "value") valIdx = idx
                                    if ((text.contains("change") || text == "+/-") && !text.contains("%")) chgIdx = idx
                                    if (text.contains("%") || text.contains("percent")) pctIdx = idx
                                }
                                if (nameIdx != -1 && valIdx != -1) break
                            }

                            // If headers found, find the row for primaryName
                            if (nameIdx != -1 && valIdx != -1) {
                                val targetRow = allRows.find { it.select("td, th").getOrNull(nameIdx)?.text()?.contains(primaryName, true) == true }
                                if (targetRow != null) {
                                    val cells = targetRow.select("td, th")
                                    if (cells.size > maxOf(valIdx, chgIdx, pctIdx)) {
                                        indexValue = cells[valIdx].text().replace(",", "").trim()
                                        if (chgIdx != -1) ptChange = cells[chgIdx].text().replace(",", "").replace("+", "").trim()
                                        if (pctIdx != -1) pctChange = cells[pctIdx].text().replace(",", "").replace("+", "").trim()
                                        isPositive = !targetRow.text().contains("-")
                                        foundPrimaryInTable = true
                                        break
                                    }
                                }
                            }
                        }

                        // Fallback 1: Row-based search (improved heuristic)
                        if (!foundPrimaryInTable) {
                            for (table in tables) {
                                val row = table.select("tr").find { it.text().contains(primaryName, ignoreCase = true) }
                                if (row != null) {
                                    val cells = row.select("td")
                                    val numbers = cells.map { it.text().replace(",", "").replace("%", "").replace("+", "").trim() }
                                        .filter { it.toDoubleOrNull() != null }
                                    
                                    if (numbers.size >= 4) {
                                        // HEURISTIC: In 6-7 column tables (Open, High, Low, Close, Change, %), Close is the 4th number (index 3)
                                        indexValue = numbers[3] 
                                        ptChange = if (numbers.size >= 5) numbers[4] else ""
                                        pctChange = if (numbers.size >= 6) numbers[5] else ""
                                        isPositive = !row.text().contains("-")
                                        foundPrimaryInTable = true
                                        break
                                    } else if (numbers.isNotEmpty()) {
                                        indexValue = numbers[0]
                                        if (numbers.size >= 2) ptChange = numbers[1]
                                        if (numbers.size >= 3) pctChange = numbers[2]
                                        isPositive = !row.text().contains("-")
                                        foundPrimaryInTable = true
                                        break
                                    }
                                }
                            }
                        }

                        // Fallback to legacy site-specific scrapers only if primaryName is "NEPSE Index"
                        if (!foundPrimaryInTable && (primaryName == "NEPSE Index" || primaryName == "NEPSE")) {
                            if (url.contains("merolagani.com")) {
                                val idxValEl = doc.select("#ctl00_ContentPlaceHolder1_lblIndexValue").firstOrNull()
                                if (idxValEl != null && idxValEl.text().isNotBlank()) {
                                    indexValue = idxValEl.text().trim()
                                    ptChange = doc.select("#ctl00_ContentPlaceHolder1_lblIndexChange").firstOrNull()?.text()?.trim() ?: ""
                                    pctChange = doc.select("#ctl00_ContentPlaceHolder1_lblIndexPercent").firstOrNull()?.text()?.trim() ?: "0.00%"
                                    isPositive = !pctChange.contains("-")
                                }
                            }
                        }
                        
                        if (indexValue != "0.00") {
                            com.example.data.util.AppLogger.d("MarketStatus", "Found primary index '$primaryName' at $url: $indexValue")
                            // We don't break here; we continue to aggregate LTP from other sources if needed, 
                            // but we have our primary index value.
                        }
                    } catch (e: Exception) {
                        com.example.data.util.AppLogger.e("MarketStatus", "Failed: $url -> Error: ${e.message}")
                    }
                }

                // Sanitization
                indexValue = indexValue.replace("\"", "").replace(",", "").trim()
                ptChange = ptChange.replace("(", "").replace(")", "").replace("+", "").replace("-", "").trim()
                if (ptChange.isNotEmpty()) ptChange = if (isPositive) "+$ptChange" else "-$ptChange"

                _marketStatus.value = MarketStatus(index = indexValue, change = ptChange, percentChange = pctChange, date = marketDate, status = marketStatus, isPositive = isPositive)

                // Sync Primary Index with DB
                try {
                    val idxVal = indexValue.toDoubleOrNull() ?: 0.0
                    val pointChg = ptChange.replace("+", "").replace(",", "").toDoubleOrNull() ?: 0.0
                    val pct = pctChange.replace("%", "").replace("+", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                    
                    if (idxVal > 0) {
                        val existing = portfolioDao.getIndexByName(primaryName)
                        val isSameValue = existing != null && Math.abs(existing.currentValue - idxVal) < 0.01
                        val isRecent = existing != null && (System.currentTimeMillis() - existing.timestamp) < 60000
                        val isInvalidZero = existing != null && Math.abs(existing.pointChange) > 0.01 && Math.abs(pointChg) < 0.01
                        
                        if (!(isSameValue && isRecent) && !isInvalidZero) {
                            val prevVal = idxVal - pointChg
                            portfolioDao.insertMarketIndices(listOf(com.example.data.db.MarketIndexEntity(
                                indexName = primaryName,
                                currentValue = idxVal,
                                previousValue = prevVal,
                                pointChange = pointChg,
                                changePercent = pct,
                                source = "Scraped_Live",
                                timestamp = System.currentTimeMillis()
                            )))
                            success = true
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e("MarketStatus", "Failed to sync Primary Index to DB", e)
                }

                Result.success(success)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
