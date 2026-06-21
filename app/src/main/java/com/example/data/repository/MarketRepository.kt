package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import com.example.data.db.MarketIndexEntity
import com.example.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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
 * Implements a prioritized fallback mechanism for data scraping.
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
                change = it.currentValue - it.previousValue,
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

        if (isWishlisted) {
            fetchPriceChanges()
        }
    }

    private fun normalizeIndexName(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 60) return null
        
        val lower = clean.lowercase()
        return when {
            lower.contains("nepse index") || lower == "nepse" || lower == "nepse-index" -> "NEPSE Index"
            lower.contains("sensitive float") -> "Sensitive Float Index"
            lower.contains("sensitive index") || lower == "sensitive" -> "Sensitive Index"
            lower.contains("float index") || lower == "float" -> "Float Index"
            lower.contains("banking") -> "Banking"
            lower.contains("development bank") -> "Development Bank"
            lower.contains("finance") -> "Finance"
            lower.contains("hotels") || lower.contains("tourism") -> "Hotels"
            lower.contains("hydro") || lower.contains("hydropower") -> "HydroPower Index"
            lower.contains("investment") -> "Investment"
            lower.contains("life insurance") -> "Life Insurance"
            lower.contains("manufacturing") || lower.contains("production") -> "Manufacturing"
            lower.contains("microfinance") -> "Microfinance Index"
            lower.contains("mutual fund") -> "Mutual Fund"
            lower.contains("non life") || lower.contains("non-life") -> "Non Life Insurance"
            lower.contains("others") -> "Others"
            lower.contains("trading") -> "Trading"
            lower.contains("index") || lower.contains("subindex") || lower.contains("sub index") -> {
                clean.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            else -> null
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
                ScraperCategory.SCRIP_SYNC -> listOf(
                    "https://www.sharesansar.com/company-list",
                    "https://merolagani.com/CompanyList.aspx"
                )
                ScraperCategory.INDEX_UPDATE -> listOf(
                    "https://www.sharesansar.com/market",
                    "https://merolagani.com/LatestMarket.aspx"
                )
                ScraperCategory.LTP_UPDATE -> listOf(
                    "https://www.sharesansar.com/live-trading",
                    "https://www.sharesansar.com/today-share-price",
                    "https://merolagani.com/LatestMarket.aspx"
                )
                else -> emptyList()
            }
        }
    }

    /**
     * Downloads the latest scrip master list (Symbol, Name, Sector) from prioritized URLs.
     * Updates the local database on success.
     */
    suspend fun fetchMasterScrips(): Boolean {
        val urls = getScraperUrls(ScraperCategory.SCRIP_SYNC)
        for (url in urls) {
            try {
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36").timeout(20000).get()
                val scrips = mutableListOf<ScripMaster>()
                doc.select("table tr").forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 4) {
                        val symbol = cells[1].text().trim().uppercase()
                        val name = cells[2].text().trim()
                        val sector = cells[3].text().trim()
                        if (symbol.isNotEmpty() && !garbageTerms.contains(symbol.lowercase()) && symbol.length <= 12) {
                            scrips.add(ScripMaster(symbol, name, sector))
                        }
                    }
                }
                if (scrips.isNotEmpty()) {
                    portfolioDao.insertScripMaster(scrips)
                    return true
                }
            } catch (e: Exception) {}
        }
        return false
    }

    /**
     * Fetches market indices (NEPSE, Banking, etc.) from prioritized sources.
     * Implements site-specific parsing for MeroLagani and general table parsing.
     */
    suspend fun fetchNepseIndices(): List<NepseIndex> {
        val urls = getScraperUrls(ScraperCategory.INDEX_UPDATE)
        val scrapedList = mutableListOf<NepseIndex>()
        
        for (url in urls) {
            try {
                // desktop user agent
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36").timeout(15000).get()
                
                // MeroLagani Specific Selector
                if (url.contains("merolagani.com")) {
                    val idxValEl = doc.select("#ctl00_ContentPlaceHolder1_lblIndexValue").firstOrNull()
                    if (idxValEl != null) {
                        val value = idxValEl.text().replace(",", "").toDoubleOrNull() ?: 0.0
                        if (value > 0 && scrapedList.none { it.index == "NEPSE Index" }) {
                            scrapedList.add(createNepseIndex("NEPSE Index", value))
                        }
                    }
                }

                // Robust Table Parsing (ShareSansar / Merolagani fallback)
                doc.select("table").forEach { table ->
                    val rows = table.select("tr")
                    if (rows.size < 2) return@forEach
                    
                    val header = rows.first()?.select("th, td")?.map { it.text().lowercase().trim() } ?: emptyList()
                    var nameIdx = -1; var valIdx = -1; var chgIdx = -1
                    
                    header.forEachIndexed { index, text ->
                        when {
                            text == "index" || text == "sub index" || text == "sector" || text == "indices" -> if (nameIdx == -1) nameIdx = index
                            text == "close" || text == "value" || text == "current" || text == "pts" || text == "points" || text == "index value" -> if (valIdx == -1) valIdx = index
                            text.contains("change") || text == "+/-" || text == "diff" -> if (chgIdx == -1) chgIdx = index
                        }
                    }

                    // Defaults if headers not found (Specific to SS table layout)
                    if (nameIdx == -1) nameIdx = 0
                    if (valIdx == -1) valIdx = if (header.size >= 5) 4 else 1 // SS close is at 4
                    if (chgIdx == -1) chgIdx = if (header.size >= 6) 5 else 2

                    rows.drop(1).forEach { row ->
                        val cells = row.select("td")
                        if (cells.size > maxOf(nameIdx, valIdx)) {
                            val rawName = cells[nameIdx].text().trim()
                            val valueStr = cells[valIdx].text().replace(",", "").trim()
                            val value = valueStr.toDoubleOrNull() ?: 0.0
                            
                            if (rawName.isNotEmpty() && value > 0 && !garbageTerms.contains(rawName.lowercase())) {
                                val name = normalizeIndexName(rawName)
                                if (name != null && scrapedList.none { it.index == name }) {
                                    val changeStr = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().replace(",", "").replace("+", "").trim() else "0"
                                    val changeVal = changeStr.toDoubleOrNull() ?: 0.0
                                    val isNeg = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().contains("-") else false
                                    val prevValue = if (isNeg) value + Math.abs(changeVal) else value - changeVal
                                    scrapedList.add(createNepseIndex(name, value, prevValue))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (scrapedList.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (item in scrapedList) {
                    val existing = portfolioDao.getIndexByName(item.index)
                    if (existing == null) {
                        val pct = if (item.previousValue > 0) ((item.value - item.previousValue) / item.previousValue) * 100.0 else 0.0
                        portfolioDao.insertMarketIndices(listOf(MarketIndexEntity(item.index, item.value, item.previousValue, pct)))
                    } else if (existing.currentValue != item.value) {
                        val prevVal = existing.currentValue
                        val pct = if (prevVal > 0) ((item.value - prevVal) / prevVal) * 100.0 else 0.0
                        portfolioDao.insertMarketIndices(listOf(MarketIndexEntity(item.index, item.value, prevVal, pct)))
                    }
                }
            }
        }

        return scrapedList.distinctBy { it.index }
    }

    private suspend fun createNepseIndex(name: String, value: Double, estimatedPrev: Double? = null): NepseIndex {
        val existing = portfolioDao.getIndexByName(name)
        val prevValue = existing?.currentValue ?: (estimatedPrev ?: (value * 1.0))
        val diff = value - prevValue
        val pct = if (prevValue > 0) (diff / prevValue) * 100.0 else 0.0
        return NepseIndex(name, value, diff, pct, prevValue)
    }

    /**
     * Fetches the latest Last Traded Prices (LTP) and calculates changes.
     * Uses prioritized Live Trading URLs (e.g., Sharesansar).
     */
    suspend fun fetchPriceChanges(): List<ScripPriceChange> {
        val urls = getScraperUrls(ScraperCategory.LTP_UPDATE)
        val changes = mutableListOf<ScripPriceChange>()
        
        for (baseUrl in urls) {
            try {
                val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36").timeout(20000).get()
                val scripEntities = mutableListOf<com.example.data.db.ExternalLtp>()
                val timestamp = System.currentTimeMillis()
                
                doc.select("table").forEach { table ->
                    val rows = table.select("tr")
                    if (rows.isEmpty()) return@forEach
                    
                    val header = rows.firstOrNull()?.select("th, td")?.map { it.text().lowercase().trim() } ?: emptyList()
                    var symIdx = -1; var ltpIdx = -1; var prvIdx = -1

                    header.forEachIndexed { index, text ->
                        when {
                            text == "symbol" || text == "scrip" || text == "code" -> if (symIdx == -1) symIdx = index
                            text == "ltp" || text.contains("last traded") || text.contains("price") || text == "close" -> if (ltpIdx == -1) ltpIdx = index
                            text.contains("prev") || text.contains("close") && !text.contains("last") -> if (prvIdx == -1) prvIdx = index
                        }
                    }

                    // Fallbacks for common SS table layouts
                    if (symIdx == -1) symIdx = 1
                    if (ltpIdx == -1) ltpIdx = if (header.size >= 6) 5 else 2

                    rows.drop(1).forEach { row ->
                        val cells = row.select("td")
                        if (cells.size > maxOf(symIdx, ltpIdx)) {
                            val symbol = cells[symIdx].text().trim().uppercase()
                            val ltpStr = cells[ltpIdx].text().replace(",", "").trim()
                            val ltp = ltpStr.toDoubleOrNull() ?: 0.0
                            
                            if (symbol.isNotEmpty() && ltp > 0 && !garbageTerms.contains(symbol.lowercase())) {
                                val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                
                                if (existing == null || existing.ltp != ltp) {
                                    val prevStr = if (prvIdx != -1 && prvIdx < cells.size) cells[prvIdx].text().replace(",", "").trim() else ""
                                    val scrapedPrev = prevStr.toDoubleOrNull()
                                    val prevLtp = scrapedPrev ?: (existing?.ltp ?: ltp)
                                    
                                    changes.add(ScripPriceChange(symbol, ltp, ltp - prevLtp, if(prevLtp > 0) (ltp-prevLtp)/prevLtp*100.0 else 0.0, prevLtp))
                                    scripEntities.add(com.example.data.db.ExternalLtp(symbol, ltp, prevLtp, "Scraped", timestamp, existing?.isInMeroshareCsv ?: false))
                                } else {
                                    changes.add(ScripPriceChange(symbol, existing.ltp, existing.ltp - existing.previousLtp, if(existing.previousLtp > 0) (existing.ltp - existing.previousLtp) / existing.previousLtp * 100.0 else 0.0, existing.previousLtp))
                                }
                            }
                        }
                    }
                }
                if (scripEntities.isNotEmpty()) {
                    portfolioDao.insertExternalLtps(scripEntities)
                    return changes.distinctBy { it.symbol }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return emptyList()
    }
}
