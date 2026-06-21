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
        if (name.isEmpty() || name.length > 50) return null
        val lower = name.lowercase().trim()
        return when {
            lower.contains("nepse index") || lower == "nepse" || lower == "nepse-index" -> "NEPSE Index"
            lower.contains("sensitive index") || lower == "sensitive" -> "Sensitive Index"
            lower.contains("float index") || lower == "float" -> "Float Index"
            lower.contains("sensitive float") -> "Sensitive Float Index"
            lower.contains("banking") -> "Banking"
            lower.contains("development bank") -> "Development Bank"
            lower.contains("finance") -> "Finance"
            lower.contains("hotels") -> "Hotels"
            lower.contains("hydro") -> "HydroPower Index"
            lower.contains("investment") -> "Investment"
            lower.contains("life insurance") -> "Life Insurance"
            lower.contains("manufacturing") -> "Manufacturing"
            lower.contains("microfinance") -> "Microfinance Index"
            lower.contains("mutual fund") -> "Mutual Fund"
            lower.contains("non life") || lower.contains("non-life") -> "Non Life Insurance"
            lower.contains("others") -> "Others"
            lower.contains("trading") -> "Trading"
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
                ScraperCategory.SCRIP_SYNC -> listOf("https://www.sharesansar.com/company-list")
                ScraperCategory.INDEX_UPDATE -> listOf("https://www.sharesansar.com/market", "https://merolagani.com/latestmarket.aspx")
                ScraperCategory.LTP_UPDATE -> listOf("https://www.sharesansar.com/live-trading")
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
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val scrips = mutableListOf<ScripMaster>()
                doc.select("table tr").forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 4) {
                        val symbol = cells[1].text().trim().uppercase()
                        if (symbol.isNotEmpty() && symbol != "SYMBOL") {
                            scrips.add(ScripMaster(symbol, cells[2].text().trim(), cells[3].text().trim()))
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
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get()
                
                // Try MeroLagani specific selector first if URL matches
                if (url.contains("merolagani")) {
                    val idxValEl = doc.select("#ctl00_ContentPlaceHolder1_lblIndexValue").firstOrNull()
                    if (idxValEl != null) {
                        val value = idxValEl.text().replace(",", "").toDoubleOrNull() ?: 0.0
                        if (value > 0) scrapedList.add(createNepseIndex("NEPSE Index", value))
                    }
                }

                // General table parsing for other indices
                doc.select("tr").forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 2) {
                        val rawName = cells[0].text().trim()
                        val name = normalizeIndexName(rawName)
                        val value = cells[1].text().replace(",", "").toDoubleOrNull() ?: 0.0
                        
                        if (name != null && value > 0) {
                            if (scrapedList.none { it.index == name }) {
                                scrapedList.add(createNepseIndex(name, value))
                            }
                        }
                    }
                }
                if (scrapedList.isNotEmpty()) break 
            } catch (e: Exception) {}
        }

        if (scrapedList.isNotEmpty()) {
            portfolioDao.insertMarketIndices(scrapedList.map { 
                MarketIndexEntity(it.index, it.value, it.previousValue, it.percentChange) 
            })
        }
        return scrapedList.distinctBy { it.index }
    }

    private suspend fun createNepseIndex(name: String, value: Double): NepseIndex {
        val existing = portfolioDao.getIndexByName(name)
        val prevValue = existing?.currentValue ?: (value * 0.99)
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
                val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get()
                val scripEntities = mutableListOf<com.example.data.db.ExternalLtp>()
                
                doc.select("table tbody tr").forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 6) {
                        val symbol = cells[1].text().trim().uppercase()
                        val ltp = cells[5].text().replace(",", "").toDoubleOrNull() ?: 0.0
                        if (symbol.isNotEmpty() && ltp > 0) {
                            val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                            val prevLtp = existing?.ltp ?: ltp
                            changes.add(ScripPriceChange(symbol, ltp, ltp - prevLtp, if(prevLtp>0) (ltp-prevLtp)/prevLtp*100.0 else 0.0, prevLtp))
                            scripEntities.add(com.example.data.db.ExternalLtp(symbol, ltp, prevLtp, "Scraped", System.currentTimeMillis()))
                        }
                    }
                }
                if (scripEntities.isNotEmpty()) {
                    portfolioDao.insertExternalLtps(scripEntities)
                    return changes.distinctBy { it.symbol }
                }
            } catch (e: Exception) {}
        }
        return emptyList()
    }
}
