package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
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

class MarketRepository(private val portfolioDao: PortfolioDao) {

    val allScripMaster: Flow<List<ScripMaster>> = portfolioDao.getAllScripMaster()
    val wishlistedScrips: Flow<List<ScripMaster>> = portfolioDao.getWishlistedScrips()

    suspend fun updateWishlist(scrip: ScripMaster) {
        portfolioDao.insertScripMasterSingle(scrip)
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
            val list = mutableListOf<NepseIndex>()
            try {
                // Correct source for indices as requested: https://www.sharesansar.com/market
                val doc = Jsoup.connect("https://www.sharesansar.com/market")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(15000)
                    .get()
                
                // On /market, indices are in a dedicated section with ID "indices"
                val tables = doc.select("table")
                for (table in tables) {
                    val rows = table.select("tr")
                    for (row in rows) {
                        val cells = row.select("td")
                        if (cells.size >= 2) {
                            val name = cells[0].text().trim()
                            
                            if (isKnownIndex(name)) {
                                var value = 0.0
                                var pointChange = 0.0
                                var percentChange = 0.0
                                
                                // Standard /market table: Index Name, Value, Point Change, % Change
                                if (cells.size >= 4) {
                                    value = cells[1].text().replace(",", "").toDoubleOrNull() ?: 0.0
                                    pointChange = cells[2].text().replace(",", "").replace("+", "").trim().toDoubleOrNull() ?: 0.0
                                    val pctText = cells[3].text().replace(",", "").replace("%", "").trim()
                                    percentChange = pctText.toDoubleOrNull() ?: 0.0
                                } else if (cells.size == 3) {
                                    value = cells[1].text().replace(",", "").toDoubleOrNull() ?: 0.0
                                    val pctText = cells[2].text().replace(",", "").replace("%", "").trim()
                                    percentChange = pctText.toDoubleOrNull() ?: 0.0
                                    // Estimate point change if missing
                                }

                                if (value > 0) {
                                    list.add(NepseIndex(name, value, pointChange, percentChange, value - pointChange))
                                }
                            }
                        }
                    }
                }
                
                // Fallback for top-bar tickers if main table fails
                if (list.isEmpty()) {
                    val tickerItems = doc.select(".index-wrapper, .market-index, .top-indices, .index-item")
                    for (item in tickerItems) {
                        val name = item.select(".index-name, .name").text().trim()
                        if (isKnownIndex(name)) {
                            val value = item.select(".index-value, .value").text().replace(",", "").toDoubleOrNull() ?: 0.0
                            val pct = item.select(".index-per-change, .per-change, .percent").text().replace("%", "").replace(",", "").trim().toDoubleOrNull() ?: 0.0
                            if (value > 0) {
                                list.add(NepseIndex(name, value, 0.0, pct))
                            }
                        }
                    }
                }

                return@withContext list.distinctBy { it.index.lowercase() }
                    .sortedByDescending { it.index.contains("NEPSE", true) }

            } catch (e: Exception) {
                e.printStackTrace()
            }
            list
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
                
                val rows = doc.select("table#top-gainer-table tr, table.table-hover tr, table tbody tr, table#headertbl tr")
                rows.forEach { row ->
                    val cells = row.select("td")
                    if (cells.size >= 8) {
                        val symbol = cells[1].text().trim().uppercase()
                        val ltp = cells[3].text().replace(",", "").toDoubleOrNull() ?: 0.0
                        val change = cells[4].text().replace(",", "").toDoubleOrNull() ?: 0.0
                        val pct = cells[5].text().replace(",", "").replace("%", "").trim().toDoubleOrNull() ?: 0.0
                        if (symbol.isNotEmpty() && symbol.length <= 15 && ltp > 0) {
                            val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                            changes.add(
                                ScripPriceChange(
                                    symbol = symbol, 
                                    ltp = ltp, 
                                    change = change, 
                                    percentChange = pct,
                                    previousLtp = existing?.ltp ?: ltp
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            changes.distinctBy { it.symbol }
        }
    }
}
