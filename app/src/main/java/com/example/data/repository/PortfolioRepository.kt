package com.example.data.repository

import android.content.Context
import androidx.core.content.edit
import com.example.data.db.ExternalLtp
import com.example.data.db.IpoMasterDao
import com.example.data.db.PortfolioDao
import com.example.data.db.TransactionRecord
import com.example.data.model.NepseStatus
import com.example.data.model.ScraperCategory
import com.example.data.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

import com.example.data.db.SectorMapping
import com.example.data.db.UserEntity
import kotlinx.coroutines.flow.map

/**
 * Repository handling user profile, application settings, and generic scraper configurations.
 * Manages persistence of transactions and external LTP data.
 */
class PortfolioRepository(
    private val portfolioDao: PortfolioDao,
    private val ipoMasterDao: IpoMasterDao
) {

    /** Default scraper URLs categorized by function. Used as fallback if no user overrides exist. */
    val defaultScrapersByCategory = mapOf(
        ScraperCategory.LTP_UPDATE to listOf("https://www.sharesansar.com/live-trading", "https://www.sharesansar.com/today-share-price", "https://merolagani.com/latestmarket.aspx"),
        ScraperCategory.INDEX_UPDATE to listOf("https://merolagani.com/latestmarket.aspx", "https://www.sharesansar.com/market"),
        ScraperCategory.SCRIP_SYNC to listOf("https://www.sharesansar.com/company-list", "https://merolagani.com/CompanyList.aspx"),
        ScraperCategory.IPO_LISTING to listOf("https://www.sharesansar.com/ipo-fpo-news", "https://merolagani.com/Ipo.aspx"),
        ScraperCategory.CDSC_COMPANIES to listOf("https://iporesult.cdsc.com.np/result/company/list"),
        ScraperCategory.CDSC_RESULT to listOf("https://iporesult.cdsc.com.np/result/ipo/result")
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
                ScraperCategory.values().forEach { cat ->
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
        ScraperCategory.values().forEach { cat ->
            if (scraperMap[cat].isNullOrEmpty()) {
                scraperMap[cat] = defaultScrapersByCategory[cat] ?: emptyList()
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
            typeColumns = entity?.typeColumnsJson?.let { 
                if (it.isBlank()) emptySet() else it.split(",").toSet()
            } ?: emptySet(),
            selectedSectorFilter = entity?.selectedSectorFilter ?: "All",
            datasetScope = entity?.datasetScope ?: "OVERALL"
        )
    }

    private val _nepseStatus = MutableStateFlow(NepseStatus())
    val nepseStatus = _nepseStatus.asStateFlow()

    suspend fun saveUserProfile(name: String, email: String) {
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
                    pin = existing?.pin
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
                    pin = existing?.pin
                )
            )
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

    suspend fun updateTypeColumns(cols: Set<String>) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(typeColumnsJson = cols.joinToString(",")))
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

    suspend fun updateDatasetScope(scope: String) {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (existing != null) {
                portfolioDao.saveUserProfile(existing.copy(datasetScope = scope))
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
            
            portfolioDao.saveUserProfile(
                UserEntity(
                    id = 0,
                    name = existing?.name ?: "",
                    email = existing?.email ?: "",
                    currencySymbol = existing?.currencySymbol ?: "रु.",
                    dateFormat = existing?.dateFormat ?: "AD",
                    visibleIndicesJson = existing?.visibleIndicesJson ?: "",
                    scraperUrlsJson = json.toString(),
                    pin = existing?.pin
                )
            )
        }
    }

    /** Wipes all custom scraper overrides and restores app to factory default URLs. */
    suspend fun resetAllScraperUrls() {
        withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            portfolioDao.saveUserProfile(
                UserEntity(
                    id = 0,
                    name = existing?.name ?: "",
                    email = existing?.email ?: "",
                    currencySymbol = existing?.currencySymbol ?: "रु.",
                    dateFormat = existing?.dateFormat ?: "AD",
                    visibleIndicesJson = existing?.visibleIndicesJson ?: "",
                    scraperUrlsJson = "",
                    pin = existing?.pin
                )
            )
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
            defaultScrapersByCategory[category] ?: emptyList()
        }
    }

    suspend fun getScraperUrl(key: String): String {
        val category = when(key) {
            "LTP_SHARESANSAR" -> ScraperCategory.LTP_UPDATE
            "INDEX_MEROLAGANI", "INDICES_SHARESANSAR" -> ScraperCategory.INDEX_UPDATE
            "SCRIP_MASTER" -> ScraperCategory.SCRIP_SYNC
            "IPO_PIPELINE", "NEPALI_PAISA_IPO" -> ScraperCategory.IPO_LISTING
            "CDSC_COMPANY_LIST" -> ScraperCategory.CDSC_COMPANIES
            "CDSC_RESULT_CHECK" -> ScraperCategory.CDSC_RESULT
            else -> return ""
        }
        return getScraperUrls(category).firstOrNull() ?: ""
    }

    val allTransactions: Flow<List<TransactionRecord>> = portfolioDao.getAllTransactions()
    val allExternalLtps: Flow<List<ExternalLtp>> = portfolioDao.getAllExternalLtps()
    val distinctItems: Flow<List<String>> = portfolioDao.getDistinctItems()
    val allScripSymbols: Flow<List<String>> = portfolioDao.getAllScripMaster().map { list -> list.map { it.symbol } }
    val recentItems: Flow<List<String>> = portfolioDao.getRecentItems()
    val recentTypes: Flow<List<String>> = portfolioDao.getRecentTypes()
    val distinctTypes: Flow<List<String>> = portfolioDao.getDistinctTypes()
    val distinctSectorsFromMaster: Flow<List<String>> = portfolioDao.getDistinctSectorsFromMaster()

    suspend fun insertTransaction(record: TransactionRecord): Long {
        return withContext(Dispatchers.IO) {
            portfolioDao.insertTransaction(record)
        }
    }

    suspend fun updateTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.updateTransaction(record)
        }
    }

    suspend fun deleteTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.deleteTransaction(record)
        }
    }

    suspend fun clearAllTransactions() {
        withContext(Dispatchers.IO) {
            portfolioDao.clearAllTransactions()
        }
    }

    suspend fun flushAllData() {
        withContext(Dispatchers.IO) {
            portfolioDao.clearAllTransactions()
            portfolioDao.clearAllExternalLtps()
            // Kept: clearAllMarketIndices() - Per user request to preserve index data
            portfolioDao.clearAllSectorMappings()
            portfolioDao.clearAllBoids()
            ipoMasterDao.deleteAll()
            ipoMasterDao.clearResultCache()
            // Kept: UserProfile settings (name, email, pin, scrapers) preserved
        }
    }

    suspend fun updateScripSector(symbol: String, newSector: String) {
        withContext(Dispatchers.IO) {
            portfolioDao.updateSectorBySymbol(symbol.uppercase().trim(), newSector)
        }
    }

    suspend fun getSectorForScrip(symbol: String): String {
        return withContext(Dispatchers.IO) {
            val existing = portfolioDao.getExistingTypeBySymbol(symbol.uppercase().trim())
            if (existing != null && existing != "Other") return@withContext existing
            
            val masterSector = portfolioDao.getSectorFromMaster(symbol.uppercase().trim())
            masterSector ?: "Other"
        }
    }

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
                    val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") }
                    val qtyIdx = header.indexOfFirst { it.contains("quantity") || it.contains("qty") }
                    val rateIdx = header.indexOfFirst { it.contains("rate") || it.contains("price") }
                    val costIdx = header.indexOfFirst { it.contains("cost") || it.contains("amount") }
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
                            
                            records.add(TransactionRecord(date = date, item = symbol, type = getSectorForScrip(symbol), action = "Buy", qty = qty, amount = amount))
                        }
                    }
                } else {
                    val dateIdx = header.indexOfFirst { it.contains("date") }
                    val itemIdx = header.indexOfFirst { it.contains("item") || it.contains("symbol") || it.contains("scrip") }
                    val actionIdx = header.indexOfFirst { it.contains("action") || it.contains("buy/sell") }
                    val qtyIdx = header.indexOfFirst { it.contains("qty") || it.contains("quantity") }
                    val amountIdx = header.indexOfFirst { it.contains("amount") || it.contains("total") }
                    val typeIdx = header.indexOfFirst { it.contains("type") || it.contains("category") || it.contains("sector") }
                    val ltpIdx = header.indexOfFirst { it == "ltp" || it == "price" || it.contains("last traded") }
                    val prevLtpIdx = header.indexOfFirst { it.contains("prev") && it.contains("ltp") || it.contains("previous") }

                    if (itemIdx == -1) return@withContext Result.failure(Exception("Invalid CSV: Symbol column missing"))

                    for (i in 1 until lines.size) {
                        val cols = parseCsvRow(lines[i], separator).map { it.replace("\"", "").trim() }
                        if (cols.size > itemIdx) {
                            val symbol = cols[itemIdx].uppercase().trim()
                            if (symbol.isBlank() || garbageTerms.contains(symbol.lowercase())) continue

                            val type = if (typeIdx != -1 && typeIdx < cols.size && cols[typeIdx].isNotBlank()) cols[typeIdx] else getSectorForScrip(symbol)
                            val actionRaw = if (actionIdx != -1 && actionIdx < cols.size) cols[actionIdx] else "Buy"
                            val qty = cols.getOrNull(qtyIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val amount = cols.getOrNull(amountIdx)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                            val date = cols.getOrNull(dateIdx)?.takeIf { it.isNotBlank() } ?: todayStr

                            if (ltpIdx != -1 && ltpIdx < cols.size) {
                                val ltpVal = cols[ltpIdx].replace(",", "").toDoubleOrNull() ?: 0.0
                                if (ltpVal > 0) {
                                    val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                    val finalPrevLtp = if (prevLtpIdx != -1 && prevLtpIdx < cols.size) {
                                        cols[prevLtpIdx].replace(",", "").toDoubleOrNull() ?: existing?.ltp ?: ltpVal
                                    } else {
                                        if (existing != null && existing.ltp != ltpVal) existing.ltp else existing?.previousLtp ?: 0.0
                                    }
                                    portfolioDao.insertExternalLtp(ExternalLtp(symbol = symbol, ltp = ltpVal, previousLtp = finalPrevLtp, source = "CSV_Import", timestamp = System.currentTimeMillis()))
                                }
                            }

                            val normalizedAction = when {
                                actionRaw.equals("sale", ignoreCase = true) || actionRaw.equals("sell", ignoreCase = true) -> "Sale"
                                actionRaw.equals("returns", ignoreCase = true) || actionRaw.equals("return", ignoreCase = true) || actionRaw.equals("bonus", ignoreCase = true) -> "Returns"
                                else -> "Buy"
                            }
                            records.add(TransactionRecord(date = date, item = symbol, type = type, action = normalizedAction, qty = qty, amount = amount))
                        }
                    }
                }

                if (overwrite) portfolioDao.clearAllTransactions()
                records.forEach { portfolioDao.insertTransaction(it) }
                Result.success(records.size)
            } catch (e: Exception) { Result.failure(e) }
        }
    }

    suspend fun calculateMeroshareAdjustments(inputStream: InputStream): Result<Int> {
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

    suspend fun importMeroshareCsv(inputStream: InputStream): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) return@withContext Result.failure(Exception("The selected CSV file is empty"))

                val headerLine = lines.first().replace("\uFEFF", "")
                var separator = ","
                if (headerLine.contains(";") && (headerLine.count { it == ';' } > headerLine.count { it == ',' })) separator = ";"
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") || it.contains("item") || it.contains("name") }
                val ltpIdx = header.indexOfFirst { it.contains("last transaction price") || it.contains("ltp") || it.contains("price") || it.contains("rate") || it.contains("valu") }
                val prevLtpIdx = header.indexOfFirst { it.contains("prev") || it.contains("previous") }
                val qtyIdx = header.indexOfFirst { it.contains("current") || it.contains("balance") || it.contains("units") || it.contains("qty") || it.contains("quantity") }

                if (scripIdx == -1 || ltpIdx == -1) return@withContext Result.failure(Exception("Required columns 'Scrip' and 'LTP' not found in CSV."))

                val newLtpRecords = mutableListOf<ExternalLtp>()
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
                        val existing = portfolioDao.getExternalLtpBySymbol(scrip)
                        val finalPrevLtp = if (prevLtpIdx != -1 && prevLtpIdx < cols.size) cols[prevLtpIdx].replace("\"", "").replace(",", "").trim().toDoubleOrNull() ?: existing?.ltp ?: ltpValue else existing?.ltp ?: ltpValue

                        newLtpRecords.add(ExternalLtp(symbol = scrip, ltp = ltpValue, previousLtp = finalPrevLtp, source = "Meroshare", timestamp = timestamp, isInMeroshareCsv = true))

                        if (qtyIdx != -1) {
                            val scripTx = groupedTx[scrip] ?: emptyList()
                            val currentBalance = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty } - scripTx.filter { it.action == "Sale" }.sumOf { it.qty }
                            if (Math.abs(currentBalance - importedQty) > 0.001) {
                                val diff = importedQty - currentBalance
                                val totalBuyAmount = scripTx.filter { it.action == "Buy" }.sumOf { it.amount }
                                val totalBuyQty = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty }
                                val avgCost = if (totalBuyQty > 0) totalBuyAmount / totalBuyQty else 0.0
                                val (action, adjQty, adjAmount) = if (diff < 0) Triple("Sale", Math.abs(diff), Math.abs(diff) * (if (avgCost > 0) avgCost else ltpValue)) else Triple("Returns", diff, 0.0)
                                portfolioDao.insertTransaction(TransactionRecord(date = todayStr, item = scrip, type = scripTx.firstOrNull()?.type ?: getSectorForScrip(scrip), action = action, qty = adjQty, amount = adjAmount, isSystemAdjustment = true))
                            }
                        }
                    }
                }

                val csvScrips = newLtpRecords.map { it.symbol }.toSet()
                for ((scrip, scripTx) in groupedTx) {
                    if (scrip !in csvScrips) {
                        val currentBalance = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty } - scripTx.filter { it.action == "Sale" }.sumOf { it.qty }
                        if (currentBalance > 0.001) {
                            val totalBuyAmount = scripTx.filter { it.action == "Buy" }.sumOf { it.amount }
                            val totalBuyQty = scripTx.filter { it.action == "Buy" || it.action == "Returns" }.sumOf { it.qty }
                            val avgCost = if (totalBuyQty > 0) totalBuyAmount / totalBuyQty else 0.0
                            portfolioDao.insertTransaction(TransactionRecord(date = todayStr, item = scrip, type = scripTx.firstOrNull()?.type ?: "Other", action = "Sale", qty = currentBalance, amount = currentBalance * avgCost, isSystemAdjustment = true))
                        }
                    }
                }

                portfolioDao.deleteExternalLtpBySource("Meroshare")
                portfolioDao.resetMeroshareCsvFlag()
                portfolioDao.insertExternalLtps(newLtpRecords)
                for (rec in newLtpRecords) {
                    val existingScraped = portfolioDao.getExternalLtpBySymbol(rec.symbol)
                    if (existingScraped != null && existingScraped.source == "Scraped") portfolioDao.insertExternalLtp(existingScraped.copy(isInMeroshareCsv = true))
                }
                Result.success(newLtpRecords.size)
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

    suspend fun refreshLivePrices(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var indexValue = "0.00"
                var pctChange = "0.00%"
                var ptChange = ""
                var isPositive = true
                var marketStatus = "Market Closed"
                var marketDate = SimpleDateFormat("MMM dd | hh:mm a", Locale.US).format(Date())

                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

                val indexUrls = getScraperUrls(ScraperCategory.INDEX_UPDATE)
                for (url in indexUrls) {
                    try {
                        val doc = Jsoup.connect(url).userAgent(userAgent).timeout(10000).get()
                        if (url.contains("merolagani.com")) {
                            val idxValEl = doc.select("#ctl00_ContentPlaceHolder1_lblIndexValue").firstOrNull()
                            if (idxValEl != null && idxValEl.text().isNotBlank()) {
                                indexValue = idxValEl.text().trim()
                                ptChange = doc.select("#ctl00_ContentPlaceHolder1_lblIndexChange").firstOrNull()?.text()?.trim() ?: ""
                                pctChange = doc.select("#ctl00_ContentPlaceHolder1_lblIndexPercent").firstOrNull()?.text()?.trim() ?: "0.00%"
                                isPositive = !pctChange.contains("-")
                                val statusEl = doc.select("#ctl00_ContentPlaceHolder1_lblMarketStatus").firstOrNull()
                                if (statusEl != null) {
                                    marketStatus = if (statusEl.text().contains("Live", true)) "Market Open" else "Market Closed"
                                    marketDate = statusEl.text().substringBefore("(").replace("As of", "").trim()
                                }
                                break
                            }
                        } else if (url.contains("sharesansar.com")) {
                            val ssRows = doc.select("table tr")
                            val nepseRow = ssRows.find { it.text().contains("NEPSE Index", true) && !it.text().contains("Sub-Index", true) }
                            val cells = nepseRow?.select("td")
                            if (cells != null && cells.size >= 5) {
                                indexValue = cells[4].text().trim()
                                ptChange = cells[5].text().trim()
                                pctChange = cells[6].text().trim()
                                isPositive = !pctChange.contains("-")
                            } else if (cells != null && cells.size >= 4) {
                                indexValue = cells[1].text().trim()
                                ptChange = cells[2].text().trim()
                                pctChange = cells[3].text().trim()
                                isPositive = !pctChange.contains("-")
                            }
                            
                            val ssStatus = doc.select(".market-status, .market-update button").firstOrNull()?.text()?.trim()
                            if (!ssStatus.isNullOrBlank()) marketStatus = ssStatus
                            val ssDate = doc.select(".market-update .date, .date").firstOrNull()?.text()?.replace("As of :", "")?.trim()
                            if (!ssDate.isNullOrBlank()) marketDate = ssDate
                            if (indexValue != "0.00") break
                        }
                    } catch (e: Exception) {}
                }

                // Sanitization
                indexValue = indexValue.replace("\"", "").replace(",", "").trim()
                ptChange = ptChange.replace("(", "").replace(")", "").replace("+", "").replace("-", "").trim()
                if (ptChange.isNotEmpty()) ptChange = if (isPositive) "+$ptChange" else "-$ptChange"

                _nepseStatus.value = NepseStatus(index = indexValue, change = ptChange, percentChange = pctChange, date = marketDate, status = marketStatus, isPositive = isPositive)

                // Sync NEPSE with DB
                try {
                    val idxVal = indexValue.toDoubleOrNull() ?: 0.0
                    if (idxVal > 0) {
                        val existing = portfolioDao.getIndexByName("NEPSE Index")
                        val prevVal = existing?.currentValue ?: (idxVal - (ptChange.toDoubleOrNull() ?: 0.0))
                        portfolioDao.insertMarketIndices(listOf(com.example.data.db.MarketIndexEntity("NEPSE Index", idxVal, prevVal, pctChange.replace("%","").toDoubleOrNull() ?: 0.0)))
                    }
                } catch (e: Exception) {}

                // 3. LTP Scrip Prices (Priority Fallback List)
                val urls = getScraperUrls(ScraperCategory.LTP_UPDATE)
                
                var success = false
                for (baseUrl in urls) {
                    try {
                        val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
                        val doc = Jsoup.connect(url).userAgent(userAgent).timeout(20000).get()
                        val scrapedLtps = mutableListOf<ExternalLtp>()
                        val timestamp = System.currentTimeMillis()

                        doc.select("table").forEach { table ->
                            val header = table.select("tr").firstOrNull()?.select("th, td")?.map { it.text().uppercase().trim() } ?: emptyList()
                            var symIdx = header.indexOfFirst { it.contains("SYMBOL") || it.contains("SCRIP") || it == "CODE" }
                            var ltpIdx = header.indexOfFirst { it == "LTP" || it.contains("LAST TRADED") || it.contains("PRICE") || it == "CLOSE" }
                            var prvIdx = header.indexOfFirst { it.contains("PREV") || it.contains("CLOSE") && !it.contains("LTP") }

                            if (symIdx == -1 && header.size >= 2) symIdx = 1
                            if (ltpIdx == -1 && header.size >= 3) ltpIdx = if (header.size >= 6) 5 else 2

                            if (symIdx != -1 && ltpIdx != -1) {
                                table.select("tr").drop(1).forEach { row ->
                                    val cols = row.select("td")
                                    if (cols.size > maxOf(symIdx, ltpIdx)) {
                                        val symbol = cols[symIdx].text().trim().uppercase()
                                        val ltp = cols[ltpIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0
                                        if (symbol.isNotEmpty() && ltp > 0 && !garbageTerms.contains(symbol.lowercase())) {
                                            val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                            val prevVal = if (prvIdx != -1 && prvIdx < cols.size) cols[prvIdx].text().replace(",", "").trim().toDoubleOrNull() ?: existing?.ltp ?: ltp else existing?.ltp ?: ltp
                                            scrapedLtps.add(ExternalLtp(symbol = symbol, ltp = ltp, previousLtp = prevVal, source = "Scraped", timestamp = timestamp, isInMeroshareCsv = existing?.isInMeroshareCsv ?: false))
                                        }
                                    }
                                }
                            }
                        }

                        if (scrapedLtps.isNotEmpty()) {
                            portfolioDao.insertExternalLtps(scrapedLtps)
                            success = true
                            if (scrapedLtps.size > 100) return@withContext true
                        }
                    } catch (e: Exception) {}
                }
                success
            } catch (e: Exception) {
                false
            }
        }
    }
}
