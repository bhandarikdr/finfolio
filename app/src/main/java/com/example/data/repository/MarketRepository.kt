package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import com.example.data.db.MarketIndexEntity
import com.example.data.model.*
import com.example.data.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.util.Log

data class NepseIndex(
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
    val previousLtp: Double = 0.0
)

/**
 * Repository for market-wide data including indices and scrip price updates.
 * Implements a dynamic prioritized fallback mechanism for data scraping.
 */
class MarketRepository(private val portfolioDao: PortfolioDao) {
    
    private val garbageTerms = listOf("symbol", "s.no", "name", "sector", "company", "action", "total", "index", "indices", "type")

    val allScripMaster: Flow<List<ScripMaster>> = portfolioDao.getAllScripMaster()
    val wishlistedScrips: Flow<List<ScripMaster>> = portfolioDao.getWishlistedScrips()
    
    val persistedIndices: Flow<List<NepseIndex>> = portfolioDao.getAllMarketIndices().map { entities ->
        entities.map { 
            NepseIndex(
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

    private fun normalizeIndexName(name: String, primaryIndexName: String = "NEPSE Index"): String {
        val clean = name.trim()
        val lower = clean.lowercase()
        
        if (lower.contains("nepse index") || lower == "nepse") return primaryIndexName
        if (lower.contains("sensitive float")) return "Sensitive Float Index"
        if (lower.contains("sensitive index")) return "Sensitive Index"
        if (lower.contains("float index")) return "Float Index"
        
        // Dynamic: Return title-cased name for any found sector or sub-index
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

    suspend fun fetchMasterScrips(): Boolean = withContext(Dispatchers.IO) {
        val urls = getScraperUrls(ScraperCategory.SCRIP_SYNC)
        for (url in urls) {
            try {
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val scrips = mutableListOf<ScripMaster>()
                doc.select("table tr").forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 4) {
                        val symbol = cells[1].text().trim().uppercase()
                        val name = cells[2].text().trim()
                        val sector = cells[3].text().trim()
                        if (symbol.isNotEmpty() && !garbageTerms.contains(symbol.lowercase())) {
                            scrips.add(ScripMaster(symbol, name, sector))
                        }
                    }
                }
                if (scrips.isNotEmpty()) {
                    portfolioDao.insertScripMaster(scrips)
                    return@withContext true
                }
            } catch (e: Exception) {}
        }
        false
    }

    suspend fun fetchNepseIndices(): List<NepseIndex> = withContext(Dispatchers.IO) {
        AppLogger.i("MarketSync", "Starting Market Indices Sync")
        val urls = getScraperUrls(ScraperCategory.INDEX_UPDATE)
        val scrapedList = mutableListOf<NepseIndex>()
        val timestamp = System.currentTimeMillis()
        val primaryName = portfolioDao.getUserProfileSync()?.primaryIndexName ?: "NEPSE Index"
        
        for (url in urls) {
            try {
                AppLogger.d("MarketSync", "Scraping URL: $url")
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get()
                val tables = doc.select("table")
                AppLogger.d("MarketSync", "Found ${tables.size} tables on page")
                
                tables.forEachIndexed { tableIdx, table ->
                    val allRows = table.select("tr")
                    if (allRows.size < 2) return@forEachIndexed
                    
                    var nameIdx = -1; var valIdx = -1; var chgIdx = -1; var pctIdx = -1
                    var headerRowIdx = -1

                    // Robust Scan: Find the row that contains "Index" and "Value/Close"
                    for (i in 0 until minOf(allRows.size, 5)) {
                        val cells = allRows[i].select("th, td")
                        val headerText = cells.map { it.text().lowercase().trim() }
                        
                        var foundName = -1; var foundVal = -1; var foundChg = -1; var foundPct = -1
                        headerText.forEachIndexed { index, text ->
                            if (text.contains("index") || text.contains("indices") || text.contains("sector")) foundName = index
                            if (text.contains("value") || text.contains("close") || text.contains("current") || text.contains("pts") || text == "ltp") foundVal = index
                            if (text.contains("change") && !text.contains("%") || text == "+/-") foundChg = index
                            if (text.contains("%")) foundPct = index
                        }
                        
                        if (foundName != -1 && foundVal != -1) {
                            nameIdx = foundName; valIdx = foundVal; chgIdx = foundChg; pctIdx = foundPct
                            headerRowIdx = i
                            AppLogger.d("MarketSync", "Table $tableIdx: Found header at row $i. Mapping: Name=$nameIdx, Val=$valIdx, Chg=$chgIdx, Pct=$pctIdx")
                            break
                        }
                    }

                    if (headerRowIdx != -1) {
                        var itemsInTable = 0
                        allRows.drop(headerRowIdx + 1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > maxOf(nameIdx, valIdx)) {
                                val rawName = cells[nameIdx].text().trim()
                                val valStr = cells[valIdx].text().replace(",", "").trim()
                                val value = valStr.toDoubleOrNull() ?: 0.0
                                
                                if (rawName.isNotEmpty() && value > 0 && !garbageTerms.contains(rawName.lowercase())) {
                                    itemsInTable++
                                    val name = normalizeIndexName(rawName, primaryName)
                                    val changeStr = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().replace(",", "").replace("+", "").trim() else "0"
                                    val pctStr = if (pctIdx != -1 && pctIdx < cells.size) cells[pctIdx].text().replace(",", "").replace("+", "").replace("%", "").trim() else "0"
                                    
                                    val change = changeStr.toDoubleOrNull() ?: 0.0
                                    val pct = pctStr.toDoubleOrNull() ?: 0.0
                                    val isNeg = (chgIdx != -1 && cells[chgIdx].text().contains("-")) || (pctIdx != -1 && cells[pctIdx].text().contains("-"))
                                    
                                    val finalChg = if (isNeg) -Math.abs(change) else change
                                    val finalPct = if (isNeg) -Math.abs(pct) else pct
                                    
                                    if (scrapedList.none { it.index == name }) {
                                        scrapedList.add(NepseIndex(name, value, finalChg, finalPct, value - finalChg))
                                    }
                                }
                            }
                        }
                        AppLogger.d("MarketSync", "Table $tableIdx: Parsed $itemsInTable items")
                    }
                }
            } catch (e: Exception) { 
                AppLogger.e("MarketSync", "Scrape failed for $url", e)
                Log.e("MarketRepo", "Scrape failed for $url", e) 
            }
        }
        
        AppLogger.i("MarketSync", "Sync finished. Total unique indices scraped: ${scrapedList.size}")

        if (scrapedList.isNotEmpty()) {
            portfolioDao.insertMarketIndices(scrapedList.map { 
                MarketIndexEntity(it.index, it.value, it.previousValue, it.change, it.percentChange, "Scraped", timestamp)
            })
        }
        scrapedList
    }

    suspend fun fetchPriceChanges(): List<ScripPriceChange> = withContext(Dispatchers.IO) {
        AppLogger.i("LtpSync", "Starting Scrip LTP Sync")
        val urls = getScraperUrls(ScraperCategory.LTP_UPDATE)
        val changes = mutableListOf<ScripPriceChange>()
        for (baseUrl in urls) {
            try {
                val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
                AppLogger.d("LtpSync", "Requesting URL: $url")
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val tables = doc.select("table")
                AppLogger.d("LtpSync", "Found ${tables.size} tables on page")
                
                tables.forEachIndexed { tableIdx, table ->
                    val rows = table.select("tr")
                    if (rows.isEmpty()) return@forEachIndexed
                    var symIdx = -1; var ltpIdx = -1; var prvIdx = -1
                    var headerIdx = -1
                    for (i in 0 until minOf(rows.size, 3)) {
                        val header = rows[i].select("th, td").map { it.text().lowercase().trim() }
                        header.forEachIndexed { index, text ->
                            if (text == "symbol" || text == "scrip" || text == "code") symIdx = index
                            if (text == "ltp" || text.contains("last traded") || (text == "close" && ltpIdx == -1)) ltpIdx = index
                            if (text.contains("prev") || (text == "close" && ltpIdx != index)) prvIdx = index
                        }
                        if (symIdx != -1 && ltpIdx != -1) { 
                            headerIdx = i
                            AppLogger.d("LtpSync", "Table $tableIdx: Found header at row $i. Mapping: Symbol=$symIdx, LTP=$ltpIdx, Prev=$prvIdx")
                            break 
                        }
                    }
                    if (headerIdx != -1) {
                        var scripsInTable = 0
                        rows.drop(headerIdx + 1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > maxOf(symIdx, ltpIdx)) {
                                val symbol = cells[symIdx].text().trim().uppercase()
                                val ltp = cells[ltpIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0
                                if (symbol.isNotEmpty() && ltp > 0 && !garbageTerms.contains(symbol.lowercase())) {
                                    scripsInTable++
                                    val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                    val prevLtp = if (prvIdx != -1 && prvIdx < cells.size) cells[prvIdx].text().replace(",", "").trim().toDoubleOrNull() ?: existing?.ltp ?: ltp else existing?.ltp ?: ltp
                                    changes.add(ScripPriceChange(symbol, ltp, ltp - prevLtp, if (prevLtp > 0) (ltp - prevLtp) / prevLtp * 100.0 else 0.0, prevLtp))
                                }
                            }
                        }
                        AppLogger.d("LtpSync", "Table $tableIdx: Parsed $scripsInTable scrips")
                    }
                }
                if (changes.isNotEmpty()) {
                    val timestamp = System.currentTimeMillis()
                    AppLogger.i("LtpSync", "Sync successful. Total unique scrips: ${changes.size}")
                    portfolioDao.insertExternalLtps(changes.map { com.example.data.db.ExternalLtp(it.symbol, it.ltp, it.previousLtp, it.change, it.percentChange, "Scraped", timestamp) })
                    return@withContext changes.distinctBy { it.symbol }
                }
            } catch (e: Exception) {
                AppLogger.e("LtpSync", "Scrape failed for $baseUrl", e)
            }
        }
        AppLogger.w("LtpSync", "Sync finished with 0 items")
        emptyList<ScripPriceChange>()
    }
}
