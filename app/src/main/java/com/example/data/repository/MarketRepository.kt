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

    suspend fun fetchMasterScrips(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect("https://www.sharesansar.com/company-list")
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
                val doc = Jsoup.connect("https://www.sharesansar.com/market")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(20000)
                    .get()
                
                val tables = doc.select("table")
                for (table in tables) {
                    val rows = table.select("tr")
                    for (row in rows) {
                        val cells = row.select("td")
                        if (cells.size >= 2) {
                            val name = cells[0].text().trim()
                            if (isKnownIndex(name)) {
                                val value = cells.getOrNull(1)?.text()?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                                if (value > 0) {
                                    val existing = portfolioDao.getIndexByName(name)
                                    
                                    // LOGIC: Only update if value actually changed
                                    if (existing == null || existing.currentValue != value) {
                                        val prevValue = existing?.currentValue ?: (value * 0.99)
                                        val diff = value - prevValue
                                        val pct = if (prevValue > 0) (diff / prevValue) * 100.0 else 0.0
                                        scrapedList.add(NepseIndex(name, value, diff, pct, prevValue))
                                    } else {
                                        // Still add to scrapedList for returning, using current values
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

                if (scrapedList.isNotEmpty()) {
                    // Only insert those that are actually new or changed
                    val toPersist = scrapedList.filter { s ->
                        val ex = portfolioDao.getIndexByName(s.index)
                        ex == null || ex.currentValue != s.value
                    }.map { 
                        MarketIndexEntity(it.index, it.value, it.previousValue, it.percentChange)
                    }
                    if (toPersist.isNotEmpty()) {
                        portfolioDao.insertMarketIndices(toPersist)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            scrapedList
        }
    }

    private fun isKnownIndex(name: String): Boolean {
        if (name.isEmpty() || name.length > 50) return false
        val lower = name.lowercase()
        val keywords = listOf(
            "nepse index", "sensitive index", "float index", "sensitive float",
            "banking", "development bank", "finance", "hotels", "hydro", 
            "investment", "life insurance", "manufacturing", "microfinance", 
            "mutual fund", "non life", "others", "trading"
        )
        return keywords.any { lower.contains(it) }
    }

    suspend fun fetchPriceChanges(): List<ScripPriceChange> {
        return withContext(Dispatchers.IO) {
            val changes = mutableListOf<ScripPriceChange>()
            try {
                val doc = Jsoup.connect("https://www.sharesansar.com/live-trading")
                    .userAgent("Mozilla/5.0")
                    .timeout(20000)
                    .get()
                
                val rows = doc.select("table tbody tr")
                val scripEntities = mutableListOf<com.example.data.db.ExternalLtp>()
                
                rows.forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 8) {
                        val symbol = cells[1].text().trim().uppercase()
                        val ltp = cells[5].text().replace(",", "").toDoubleOrNull() ?: 0.0
                        
                        if (symbol.isNotEmpty() && symbol.length <= 15 && ltp > 0) {
                            val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                            
                            // LOGIC: Only update if LTP actually changed
                            if (existing == null || existing.ltp != ltp) {
                                val prevLtp = existing?.ltp ?: (ltp * 0.99)
                                val diff = ltp - prevLtp
                                val pct = if (prevLtp > 0) (diff / prevLtp) * 100.0 else 0.0

                                changes.add(ScripPriceChange(symbol, ltp, diff, pct, prevLtp))
                                scripEntities.add(com.example.data.db.ExternalLtp(symbol, ltp, prevLtp, "Scraped"))
                            } else {
                                // Add existing data to changes list for UI consistency
                                changes.add(ScripPriceChange(
                                    symbol, 
                                    existing.ltp, 
                                    existing.ltp - existing.previousLtp,
                                    if (existing.previousLtp > 0) ((existing.ltp - existing.previousLtp) / existing.previousLtp) * 100.0 else 0.0,
                                    existing.previousLtp
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
