package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import com.example.data.db.MarketIndexEntity
import com.example.data.model.*
import com.example.data.util.AppLogger
import com.example.data.util.CircuitBreaker
import com.example.data.util.PriceAuditBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.util.Log

data class MarketIndex(
    val index: String,
    val value: Double,
    val change: Double,
    val percentChange: Double,
    val previousValue: Double = 0.0
)

data class ScripPriceChange(
    val symbol: String,
    val ltp: Double,
    val change: Double,
    val percentChange: Double,
    val previousLtp: Double = 0.0,
    val source: String = "Scraped"
)

/**
 * Repository for market-wide data including indices and scrip price updates.
 * Implements a dynamic prioritized fallback mechanism for data scraping from user-configured URLs.
 */
class MarketRepository(private val portfolioDao: PortfolioDao) {
    
    /** 
     * STRATEGY: Garbage Terms
     * Used to filter out header-like rows or non-financial data rows during table iteration.
     * Case-insensitive comparison is used.
     */
    private val garbageTerms = listOf("symbol", "s.no", "name", "sector", "company", "action", "total", "index", "indices", "type")

    private var lastIndicesSync = 0L
    private var lastPriceSync = 0L
    private val SYNC_COOLDOWN = 60 * 1000L // 1 minute cooldown for manual refresh button
    private val BACKGROUND_SYNC_COOLDOWN = 15 * 60 * 1000L // 15 minutes for automatic sync

    private val circuitBreakers = mutableMapOf<String, CircuitBreaker>()

    private fun getCircuitBreaker(url: String): CircuitBreaker {
        return circuitBreakers.getOrPut(url) { CircuitBreaker() }
    }

    val allScripMaster: Flow<List<ScripMaster>> = portfolioDao.getAllScripMaster()
    val wishlistedScrips: Flow<List<ScripMaster>> = portfolioDao.getWishlistedScrips()
    
    val persistedIndices: Flow<List<MarketIndex>> = portfolioDao.getAllMarketIndices().map { entities ->
        entities.map { 
            MarketIndex(
                index = it.indexName,
                value = it.currentValue,
                change = it.pointChange,
                percentChange = it.changePercent,
                previousValue = it.previousValue
            )
        }
    }

    suspend fun updateWishlist(symbol: String, isWishlisted: Boolean) {
        val sector = portfolioDao.getSectorFromMaster(symbol)
        if (sector == null) {
            portfolioDao.insertScripMasterSingle(ScripMaster(symbol, symbol, "Other", isWishlisted))
        } else {
            portfolioDao.updateWishlistStatus(symbol, isWishlisted)
        }
    }

    /**
     * STRATEGY: Index Name Normalization
     * Logic:
     * 1. If the name matches the user's Primary Index name (case-insensitive), return the exact Primary Index name.
     * 2. Standardize secondary indices (Sensitive, Float).
     * 3. Otherwise, return Title-cased for UI consistency.
     */
    private fun normalizeIndexName(name: String, primaryIndexName: String): String {
        val clean = name.trim()
        val lower = clean.lowercase()

        // If it matches what the user defined as primary, return it exactly as they defined it
        if (clean.equals(primaryIndexName, ignoreCase = true)) return primaryIndexName

        if (lower.contains("sensitive float")) return "Sensitive Float Index"
        if (lower.contains("sensitive index")) return "Sensitive Index"
        if (lower.contains("float index")) return "Float Index"

        // Return title-cased name for any found sector or sub-index
        return clean.split(" ").filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private suspend fun getScraperUrls(category: ScraperCategory): List<String> {
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
            when(category) {
                ScraperCategory.INDEX_UPDATE -> listOf("https://www.sharesansar.com/market", "https://merolagani.com/LatestMarket.aspx")
                ScraperCategory.LTP_UPDATE -> listOf("https://www.sharesansar.com/live-trading", "https://merolagani.com/LatestMarket.aspx")
                ScraperCategory.SCRIP_SYNC -> listOf("https://www.sharesansar.com/company-list", "https://merolagani.com/CompanyList.aspx")
                else -> emptyList()
            }
        }
    }

    /**
     * STRATEGY: Scrip Master Sync
     * Fetches the global list of companies/scrips from user-defined URLs.
     * Identifies columns for Symbol, Name, and Sector using common table aliases.
     */
    suspend fun fetchMasterScrips(): Boolean = withContext(Dispatchers.IO) {
        val urls = getScraperUrls(ScraperCategory.SCRIP_SYNC)
        for (url in urls) {
            try {
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val scrips = mutableListOf<ScripMaster>()
                
                doc.select("table").forEach { table ->
                    val allRows = table.select("tr")
                    if (allRows.size < 5) return@forEach
                    
                    var symIdx = -1; var nameIdx = -1; var secIdx = -1
                    
                    // Identify columns for Symbol, Name, and Sector using common table aliases.
                    for (i in 0 until minOf(allRows.size, 5)) {
                        val header = allRows[i].select("th, td").map { it.text().lowercase().trim() }
                        header.forEachIndexed { index, text ->
                            if (text == "symbol" || text == "scrip" || text == "code" || text == "ticker") symIdx = index
                            if (text == "name" || text.contains("company name") || text == "company" || text == "security") nameIdx = index
                            if (text == "sector" || text.contains("category") || text == "type" || text == "industry") secIdx = index
                        }
                        if (symIdx != -1) break
                    }
                    
                    if (symIdx != -1) {
                        allRows.drop(1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > symIdx) {
                                val symbol = cells[symIdx].text().trim().uppercase()
                                val name = if (nameIdx != -1 && nameIdx < cells.size) cells[nameIdx].text().trim() else symbol
                                val sector = if (secIdx != -1 && secIdx < cells.size) cells[secIdx].text().trim() else "Other"
                                
                                if (symbol.isNotEmpty() && 
                                    !garbageTerms.contains(symbol.lowercase()) && 
                                    symbol.toDoubleOrNull() == null) {
                                    scrips.add(ScripMaster(symbol, name, sector))
                                }
                            }
                        }
                    }
                }

                if (scrips.isNotEmpty()) {
                    portfolioDao.insertScripMaster(scrips)
                    return@withContext true
                }
            } catch (e: Exception) {
                AppLogger.e("MasterSync", "Failed to sync scrip master from $url", e)
            }
        }
        false
    }

    suspend fun fetchMarketIndices(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastIndicesSync) < BACKGROUND_SYNC_COOLDOWN) {
            AppLogger.d("MarketSync", "Skipping Market Indices Sync (Cooldown active)")
            return@withContext Result.success(0)
        }
        lastIndicesSync = now
        AppLogger.i("MarketSync", "Starting Market Indices Sync")
        val urls = getScraperUrls(ScraperCategory.INDEX_UPDATE)
        val scrapedIndices = mutableMapOf<String, MarketIndex>()
        val timestamp = System.currentTimeMillis()
        val primaryName = portfolioDao.getUserProfileSync()?.primaryIndexName ?: "NEPSE Index"
        
        var updateCount = 0

        for (url in urls) {
            val cb = getCircuitBreaker(url)
            if (!cb.canAttempt()) {
                AppLogger.w("MarketSync", "Circuit OPEN for $url, skipping...")
                continue
            }

            try {
                AppLogger.d("MarketSync", "Scraping URL: $url")
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get()
                val tables = doc.select("table")
                
                tables.forEachIndexed { tableIdx, table ->
                    val allRows = table.select("tr")
                    if (allRows.size < 2) return@forEachIndexed
                    
                    var nameIdx = -1; var valIdx = -1; var chgIdx = -1; var pctIdx = -1
                    var headerRowIdx = -1

                    for (i in 0 until minOf(allRows.size, 5)) {
                        val cells = allRows[i].select("th, td")
                        val headerText = cells.map { it.text().lowercase().trim() }
                        
                        var foundName = -1; var foundVal = -1; var foundChg = -1; var foundPct = -1
                        headerText.forEachIndexed { index, text ->
                            if (text.contains("index") || text.contains("indices") || text.contains("sector") || text.contains("nifty")) foundName = index
                            if (text.contains("value") || text.contains("close") || text.contains("current") || text.contains("pts") || text == "ltp") foundVal = index
                            if (text.contains("change") && !text.contains("%") || text == "+/-") foundChg = index
                            if (text.contains("%")) foundPct = index
                        }
                        
                        if (foundName != -1 && foundVal != -1) {
                            nameIdx = foundName; valIdx = foundVal; chgIdx = foundChg; pctIdx = foundPct
                            headerRowIdx = i
                            break
                        }
                    }

                    if (headerRowIdx != -1) {
                        allRows.drop(headerRowIdx + 1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > maxOf(nameIdx, valIdx)) {
                                val rawName = cells[nameIdx].text().trim()
                                val valStr = cells[valIdx].text().replace(",", "").trim()
                                val value = valStr.toDoubleOrNull() ?: 0.0
                                
                                val cleanName = rawName.replace(",", "")
                                if (rawName.isNotEmpty() && value > 0 && !garbageTerms.contains(rawName.lowercase()) && cleanName.toDoubleOrNull() == null) {
                                    val name = normalizeIndexName(rawName, primaryName)
                                    val changeStr = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().replace(",", "").replace("+", "").trim() else "0"
                                    val pctStr = if (pctIdx != -1 && pctIdx < cells.size) cells[pctIdx].text().replace(",", "").replace("+", "").replace("%", "").trim() else "0"
                                    
                                    val change = changeStr.toDoubleOrNull() ?: 0.0
                                    val pct = pctStr.toDoubleOrNull() ?: 0.0
                                    val isNeg = (chgIdx != -1 && cells[chgIdx].text().contains("-")) || (pctIdx != -1 && cells[pctIdx].text().contains("-"))
                                    
                                    val finalChg = if (isNeg) -Math.abs(change) else change
                                    val finalPct = if (isNeg) -Math.abs(pct) else pct
                                    
                                    val existing = portfolioDao.getIndexByName(name)
                                    
                                    // STRICT FLAT LOGIC: 
                                    // Discard if value is same OR if we are trying to overwrite a non-zero change with zero.
                                    val isSameValue = existing != null && Math.abs(existing.currentValue - value) < 0.01
                                    val isInvalidZero = existing != null && Math.abs(existing.pointChange) > 0.01 && Math.abs(finalChg) < 0.01
                                    
                                    if (isSameValue || isInvalidZero) {
                                        return@forEach
                                    }

                                    // Aggregate results: If we already found this index in a previous URL/table, we don't overwrite
                                    // unless the current one is likely the user's primary index and the previous wasn't.
                                    if (!scrapedIndices.containsKey(name)) {
                                        scrapedIndices[name] = MarketIndex(name, value, finalChg, finalPct, value - finalChg)
                                    }
                                }
                            }
                        }
                    }
                }
                
                if (scrapedIndices.isNotEmpty()) {
                    cb.recordSuccess()
                }
            } catch (e: Exception) { 
                cb.recordFailure()
                AppLogger.e("MarketSync", "Scrape failed for $url", e)
            }
        }
        
        AppLogger.i("MarketSync", "Sync finished. Total unique indices updated: ${scrapedIndices.size}")

        if (scrapedIndices.isNotEmpty()) {
            portfolioDao.insertMarketIndices(scrapedIndices.values.map { 
                MarketIndexEntity(it.index, it.value, it.previousValue, it.change, it.percentChange, "Scraped", timestamp)
            })
            updateCount = scrapedIndices.size
        }
        Result.success(updateCount)
    }

    suspend fun fetchPriceChanges(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastPriceSync) < BACKGROUND_SYNC_COOLDOWN) {
            AppLogger.d("LtpSync", "Skipping Scrip LTP Sync (Cooldown active)")
            return@withContext Result.success(0)
        }
        lastPriceSync = now
        AppLogger.i("LtpSync", "Starting Scrip LTP Sync")
        val urls = getScraperUrls(ScraperCategory.LTP_UPDATE)
        val changes = mutableListOf<ScripPriceChange>()
        var updateCount = 0

        // Get held and wishlisted scrips for audit buffer rules
        val heldScrips = portfolioDao.getAllHoldings().first().map { it.symbol }
        val wishlistedScrips = portfolioDao.getWishlistedScrips().first().map { it.symbol }
        val watchedScrips = (heldScrips + wishlistedScrips).toSet()

        for (baseUrl in urls) {
            val cb = getCircuitBreaker(baseUrl)
            if (!cb.canAttempt()) {
                AppLogger.w("LtpSync", "Circuit OPEN for $baseUrl, skipping...")
                continue
            }

            try {
                val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
                AppLogger.d("LtpSync", "Requesting URL: $url")
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val scrapedLtps = mutableListOf<com.example.data.db.ExternalLtp>()
                val timestamp = System.currentTimeMillis()

                doc.select("table").forEachIndexed { tableIdx, table ->
                    val rows = table.select("tr")
                    if (rows.isEmpty()) return@forEachIndexed
                    var symIdx = -1; var ltpIdx = -1; var prvIdx = -1; var chgIdx = -1; var pctIdx = -1
                    var headerIdx = -1
                    for (i in 0 until minOf(rows.size, 5)) {
                        val header = rows[i].select("th, td").map { it.text().lowercase().trim() }
                        header.forEachIndexed { index, text ->
                            if (text == "symbol" || text == "scrip" || text == "code") symIdx = index
                            if (text == "ltp" || text.contains("last traded") || (text == "close" && ltpIdx == -1)) ltpIdx = index
                            if (text.contains("prev") || (text == "close" && ltpIdx != index)) prvIdx = index
                            if (text.contains("change") && !text.contains("%") || text == "+/-") chgIdx = index
                            if (text.contains("%")) pctIdx = index
                        }
                        if (symIdx != -1 && ltpIdx != -1) { 
                            headerIdx = i
                            break 
                        }
                    }
                    if (headerIdx != -1) {
                        rows.drop(headerIdx + 1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > maxOf(symIdx, ltpIdx)) {
                                val symbol = cells[symIdx].text().trim().uppercase()
                                val ltp = cells[ltpIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0
                                if (symbol.isNotEmpty() && ltp > 0 && !garbageTerms.contains(symbol.lowercase())) {
                                    val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                    
                                    // Try direct change/percent if available
                                    val directChange = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().replace(",", "").replace("+", "").trim().toDoubleOrNull() else null
                                    val directPct = if (pctIdx != -1 && pctIdx < cells.size) cells[pctIdx].text().replace(",", "").replace("+", "").replace("%", "").trim().toDoubleOrNull() else null
                                    
                                    val isNeg = (chgIdx != -1 && chgIdx < cells.size && cells[chgIdx].text().contains("-")) || 
                                                (pctIdx != -1 && pctIdx < cells.size && cells[pctIdx].text().contains("-"))

                                    val finalChg = directChange?.let { if (isNeg) -Math.abs(it) else it } ?: 0.0
                                    val finalPct = directPct?.let { if (isNeg) -Math.abs(it) else it } ?: 0.0

                                    // STRICT FLAT LOGIC: 
                                    // 1. Skip if price hasn't moved at all (prevents timestamp-only updates)
                                    val isSamePrice = existing != null && Math.abs(existing.ltp - ltp) < 0.001
                                    
                                    // 2. Skip if the current data shows ZERO change, but our database has a valid NON-ZERO change.
                                    // This usually happens when a scraper returns a static/stale table after market close
                                    // where the "Change" column is reset to 0 but LTP is still yesterday's.
                                    val isInvalidZero = existing != null && Math.abs(existing.pointChange) > 0.01 && Math.abs(finalChg) < 0.01
                                    
                                    if (isSamePrice || isInvalidZero) {
                                        return@forEach
                                    }

                                    val prevLtp = if (prvIdx != -1 && prvIdx < cells.size) cells[prvIdx].text().replace(",", "").trim().toDoubleOrNull() ?: (ltp - finalChg) else (ltp - finalChg)
                                    
                                    val ltpEntity = com.example.data.db.ExternalLtp(
                                        symbol = symbol,
                                        ltp = ltp,
                                        previousLtp = prevLtp,
                                        pointChange = finalChg,
                                        changePercent = if (directPct != null) finalPct else (if (prevLtp > 0) (ltp - prevLtp) / prevLtp * 100.0 else 0.0),
                                        source = "Scraped",
                                        timestamp = timestamp,
                                        isInExternalSync = existing?.isInExternalSync ?: false
                                    )
                                    scrapedLtps.add(ltpEntity)
                                    changes.add(ScripPriceChange(symbol, ltp, finalChg, finalPct, ltpEntity.previousLtp, "Scraped"))
                                    
                                    // 2.2: Price Audit Buffer (RAM caching layer)
                                    PriceAuditBuffer.onPriceUpdate(ltpEntity, watchedScrips.contains(symbol))
                                }
                            }
                        }
                    }
                }
                if (scrapedLtps.isNotEmpty()) {
                    portfolioDao.insertExternalLtps(scrapedLtps)
                    cb.recordSuccess()
                    AppLogger.i("LtpSync", "Sync successful from $baseUrl. Scraped ${scrapedLtps.size} items.")
                    updateCount = scrapedLtps.size
                    return@withContext Result.success(updateCount)
                }
            } catch (e: Exception) {
                cb.recordFailure()
                AppLogger.e("LtpSync", "Scrape failed for $baseUrl", e)
            }
        }
        AppLogger.w("LtpSync", "Sync finished with 0 items")
        Result.success(0)
    }
}
