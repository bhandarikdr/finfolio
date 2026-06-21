package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import com.example.data.db.MarketIndexEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

data class IpoCompany(val id: Int, val name: String, val scrip: String)
data class IpoResult(val success: Boolean, val message: String)

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

    private suspend fun getScraperUrl(key: String): String {
        return withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (!existing?.scraperUrlsJson.isNullOrBlank()) {
                try {
                    val json = org.json.JSONObject(existing!!.scraperUrlsJson)
                    if (json.has(key)) return@withContext json.getString(key)
                } catch (e: Exception) {}
            }
            // Default Fallbacks
            when(key) {
                "SCRIP_MASTER" -> "https://www.sharesansar.com/company-list"
                "INDICES_SHARESANSAR" -> "https://www.sharesansar.com/market"
                "INDEX_MEROLAGANI" -> "https://merolagani.com/latestmarket.aspx"
                "LTP_SHARESANSAR" -> "https://www.sharesansar.com/live-trading"
                else -> ""
            }
        }
    }

    suspend fun fetchMasterScrips(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val scripMasterUrl = getScraperUrl("SCRIP_MASTER")
                val doc = Jsoup.connect(scripMasterUrl)
                    .userAgent("Mozilla/5.0")
                    .timeout(25000)
                    .get()
                
                val scrips = mutableListOf<ScripMaster>()
                val rows = doc.select("table tr")
                rows.forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 4) {
                        val symbol = cells[1].text().trim().uppercase()
                        val name = cells[2].text().trim()
                        val sector = cells[3].text().trim()
                        if (symbol.isNotEmpty() && symbol.length <= 15 && symbol != "SYMBOL") {
                            scrips.add(ScripMaster(symbol, name, sector))
                        }
                    }
                }
                
                if (scrips.isNotEmpty()) {
                    portfolioDao.insertScripMaster(scrips)
                    return@withContext true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            false
        }
    }

    suspend fun fetchNepseIndices(): List<NepseIndex> {
        return withContext(Dispatchers.IO) {
            val scrapedList = mutableListOf<NepseIndex>()
            try {
                // Source 1: Sharesansar Market Page
                val indicesUrl = getScraperUrl("INDICES_SHARESANSAR")
                val doc = Jsoup.connect(indicesUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(20000)
                    .get()
                
                val tables = doc.select("table")
                for (table in tables) {
                    val rows = table.select("tr")
                    for (row in rows) {
                        val cells = row.select("td")
                        if (cells.size >= 2) {
                            val rawName = cells[0].text().trim()
                            val name = normalizeIndexName(rawName)
                            if (name != null) {
                                val value = cells.getOrNull(1)?.text()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                                if (value > 0) {
                                    val existing = portfolioDao.getIndexByName(name)
                                    
                                    // LOGIC: Only update if value actually changed
                                    if (existing == null || Math.abs(existing.currentValue - value) > 0.001) {
                                        val prevValue = existing?.currentValue ?: (value * 0.99)
                                        val diff = value - prevValue
                                        val pct = if (prevValue > 0) (diff / prevValue) * 100.0 else 0.0
                                        scrapedList.add(NepseIndex(name, value, diff, pct, prevValue))
                                    } else {
                                        scrapedList.add(NepseIndex(
                                            name, 
                                            existing.currentValue, 
                                            existing.currentValue - existing.previousValue,
                                            existing.changePercent,
                                            existing.previousValue
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }

                // Fallback/Reinforcement for NEPSE Index from Merolagani if missing or for verification
                if (scrapedList.none { it.index.contains("NEPSE Index", true) }) {
                    try {
                        val meroUrl = getScraperUrl("INDEX_MEROLAGANI")
                        val meroDoc = Jsoup.connect(meroUrl)
                            .userAgent("Mozilla/5.0")
                            .timeout(10000)
                            .get()
                        
                        val idxValEl = meroDoc.select("#ctl00_ContentPlaceHolder1_lblIndexValue").firstOrNull()
                        if (idxValEl != null && idxValEl.text().trim().isNotEmpty()) {
                            val value = idxValEl.text().trim().replace(",", "").toDoubleOrNull() ?: 0.0
                            if (value > 0) {
                                val name = "NEPSE Index"
                                val existing = portfolioDao.getIndexByName(name)
                                if (existing == null || Math.abs(existing.currentValue - value) > 0.001) {
                                    val prevValue = existing?.currentValue ?: (value * 0.99)
                                    val diff = value - prevValue
                                    val pct = if (prevValue > 0) (diff / prevValue) * 100.0 else 0.0
                                    scrapedList.add(NepseIndex(name, value, diff, pct, prevValue))
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                if (scrapedList.isNotEmpty()) {
                    val toPersist = mutableListOf<MarketIndexEntity>()
                    for (s in scrapedList) {
                        val ex = portfolioDao.getIndexByName(s.index)
                        if (ex == null || Math.abs(ex.currentValue - s.value) > 0.001) {
                            toPersist.add(MarketIndexEntity(s.index, s.value, s.previousValue, s.percentChange))
                        }
                    }
                    if (toPersist.isNotEmpty()) {
                        portfolioDao.insertMarketIndices(toPersist)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            scrapedList.distinctBy { it.index }
        }
    }

    private fun normalizeIndexName(name: String): String? {
        if (name.isEmpty() || name.length > 50) return null
        val lower = name.lowercase()
        
        // Map common variations to standard names
        return when {
            lower.contains("nepse index") || lower == "nepse" -> "NEPSE Index"
            lower.contains("sensitive index") -> "Sensitive Index"
            lower.contains("float index") -> "Float Index"
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
            lower.contains("non life") -> "Non Life Insurance"
            lower.contains("others") -> "Others"
            lower.contains("trading") -> "Trading"
            else -> null
        }
    }

    suspend fun fetchPriceChanges(): List<ScripPriceChange> {
        return withContext(Dispatchers.IO) {
            val changes = mutableListOf<ScripPriceChange>()
            try {
                // Ensure we get the latest data by adding a timestamp
                val baseLtpUrl = getScraperUrl("LTP_SHARESANSAR")
                val url = if (baseLtpUrl.contains("?")) "$baseLtpUrl&t=${System.currentTimeMillis()}" else "$baseLtpUrl?t=${System.currentTimeMillis()}"
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(25000)
                    .get()
                
                val rows = doc.select("table tbody tr")
                val scripEntities = mutableListOf<com.example.data.db.ExternalLtp>()
                
                rows.forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 6) {
                        // Sharesansar live trading: 1=Symbol, 5=LTP (usually)
                        // But let's be safer and find index by header if possible, 
                        // or stick to the most common structure.
                        val symbol = cells[1].text().trim().uppercase()
                        val ltpStr = cells[5].text().replace(",", "").trim()
                        val ltp = ltpStr.toDoubleOrNull() ?: 0.0
                        
                        if (symbol.isNotEmpty() && symbol.length <= 15 && ltp > 0) {
                            val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                            
                            // LOGIC: Only update if LTP actually changed (using tolerance)
                            if (existing == null || Math.abs(existing.ltp - ltp) > 0.001) {
                                val prevLtp = existing?.ltp ?: (ltp * 0.99)
                                val diff = ltp - prevLtp
                                val pct = if (prevLtp > 0) (diff / prevLtp) * 100.0 else 0.0

                                changes.add(ScripPriceChange(symbol, ltp, diff, pct, prevLtp))
                                scripEntities.add(com.example.data.db.ExternalLtp(
                                    symbol = symbol, 
                                    ltp = ltp, 
                                    previousLtp = prevLtp, 
                                    source = "Scraped",
                                    timestamp = System.currentTimeMillis()
                                ))
                            } else {
                                // Add existing data to changes list for UI consistency
                                changes.add(ScripPriceChange(
                                    symbol = symbol, 
                                    ltp = existing.ltp, 
                                    change = existing.ltp - existing.previousLtp,
                                    percentChange = if (existing.previousLtp > 0) ((existing.ltp - existing.previousLtp) / existing.previousLtp) * 100.0 else 0.0,
                                    previousLtp = existing.previousLtp
                                ))
                            }
                        }
                    }
                }
                if (scripEntities.isNotEmpty()) {
                    portfolioDao.insertExternalLtps(scripEntities)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            changes.distinctBy { it.symbol }
        }
    }
}
