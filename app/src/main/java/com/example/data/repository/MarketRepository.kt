package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.db.ScripMaster
import com.example.data.model.*
import com.example.data.util.AppLogger
import com.example.data.util.CircuitBreaker
import com.example.data.util.PriceAuditBuffer
import com.example.data.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale

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
    
    fun getAllDpMaster(): Flow<List<com.example.data.db.DpMaster>> = portfolioDao.getAllDpMaster()
    
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
            portfolioDao.insertScripMasterSingle(ScripMaster(symbol, symbol, "Other", isWishlisted = isWishlisted))
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
            com.example.data.model.ScraperDefaults.defaultScrapersByCategory[category] ?: emptyList()
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
                    if (allRows.size < 2) return@forEach
                    
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

                    // If no sector column, try to infer from headings (Required for sites like Merolagani)
                    var inferredSector = "Other"
                    if (secIdx == -1) {
                        var prev = table.previousElementSibling()
                        while (prev != null && inferredSector == "Other") {
                            val h = prev.select("h1, h2, h3, h4, h5, h6, .panel-title, .title, .heading").firstOrNull()
                            if (h != null) inferredSector = h.text().trim()
                            prev = prev.previousElementSibling()
                        }
                        if (inferredSector == "Other") {
                            inferredSector = table.parents().select(".panel-default, .panel, .section, .category-box").firstOrNull()
                                ?.select("h1, h2, h3, h4, h5, h6, .panel-title, .title, .heading")?.firstOrNull()?.text()?.trim() ?: "Other"
                        }
                    }
                    
                    if (symIdx != -1) {
                        allRows.forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > symIdx) {
                                val symbol = cells[symIdx].text().trim().uppercase()
                                val name = if (nameIdx != -1 && nameIdx < cells.size) cells[nameIdx].text().trim() else symbol
                                val sector = if (secIdx != -1 && secIdx < cells.size) cells[secIdx].text().trim() else inferredSector
                                
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
                    val existing = portfolioDao.getAllScripMaster().first()
                    val wishlistedSymbols = existing.filter { it.isWishlisted }.map { it.symbol }.toSet()
                    val scripDataMap = existing.associateBy { it.symbol }
                    
                    val updatedScrips = scrips.map { scrip ->
                        val existingData = scripDataMap[scrip.symbol]
                        scrip.copy(
                            isWishlisted = wishlistedSymbols.contains(scrip.symbol),
                            ltp = existingData?.ltp ?: 0.0,
                            previousLtp = existingData?.previousLtp ?: 0.0,
                            pointChange = existingData?.pointChange ?: 0.0,
                            changePercent = existingData?.changePercent ?: 0.0,
                            open = existingData?.open ?: 0.0,
                            high = existingData?.high ?: 0.0,
                            low = existingData?.low ?: 0.0,
                            timestamp = existingData?.timestamp ?: 0L
                        )
                    }
                    
                    portfolioDao.insertScripMaster(updatedScrips)
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
        // Removed BACKGROUND_SYNC_COOLDOWN check to allow user-triggered refresh always
        
        lastIndicesSync = now
        AppLogger.i("MarketSync", "Starting Market Indices Sync")
        val urls = getScraperUrls(ScraperCategory.PRIMARY_INDEX_STATUS) + getScraperUrls(ScraperCategory.INDICES_UPDATE)
        val scrapedIndices = mutableMapOf<String, MarketIndex>()
        val timestamp = System.currentTimeMillis()
        val primaryName = portfolioDao.getUserProfileSync()?.primaryIndexName ?: "NEPSE Index"
        
        var updateCount = 0

        for (url in urls) {
            val cb = getCircuitBreaker(url)
            if (!cb.canAttempt()) {
                AppLogger.w("MarketSync", "Circuit OPEN for $url, skipping...", throttle = true)
                continue
            }

            try {
                AppLogger.d("MarketSync", "Scraping URL: $url")
                val useUnsafe = url.contains("nepalstock.com")
                val html = if (useUnsafe) {
                    val client = NetworkUtils.getUnsafeOkHttpClient()
                    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        response.body?.string() ?: ""
                    }
                } else {
                    Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get().html()
                }

                if (html.isBlank()) {
                    cb.recordFailure()
                    continue
                }

                val doc = Jsoup.parse(html)
                val tables = doc.select("table")
                
                tables.forEach { table ->
                    val allRows = table.select("tr")
                    if (allRows.size < 2) return@forEach
                    
                    var nameIdx = -1; var valIdx = -1; var chgIdx = -1; var pctIdx = -1
                    var headerRowIdx = -1

                    // 1. INTELLIGENT HEADER IDENTIFICATION (Keyword-First)
                    // Search first 3 rows for a valid header set
                    for (i in 0 until minOf(allRows.size, 3)) {
                        val cells = allRows[i].select("th, td")
                        val texts = cells.map { it.text().lowercase().trim() }
                        
                        var fName = -1; var fVal = -1; var fChg = -1; var fPct = -1
                        
                        texts.forEachIndexed { idx, text ->
                            // Name identification
                            if (text == "index" || text == "sub index" || text == "indices" || text == "particulars") {
                                fName = idx
                            } else if (fName == -1 && (text.contains("index") || text.contains("sector"))) {
                                fName = idx
                            }
                            
                            // Value identification (Strict priority for Close/LTP)
                            if (text == "close" || text == "ltp" || text == "current" || text == "points") {
                                fVal = idx
                            } else if (fVal == -1 && (text == "value" || text == "price")) {
                                fVal = idx
                            }
                            
                            // Change identification (Must contain change but NOT %)
                            if ((text.contains("point") || text == "diff" || text == "+/-") && text.contains("change") && !text.contains("%")) {
                                fChg = idx
                            } else if (fChg == -1 && text == "change" && !text.contains("%")) {
                                fChg = idx
                            }
                            
                            // Percentage identification
                            if (text.contains("%") || text.contains("percentage") || text.contains("percent")) {
                                fPct = idx
                            }
                        }
                        
                        // Valid if we at least have Name and Value
                        if (fName != -1 && fVal != -1) {
                            nameIdx = fName; valIdx = fVal; chgIdx = fChg; pctIdx = fPct
                            headerRowIdx = i
                            break
                        }
                    }

                    // 2. DATA-DRIVEN INFERENCE (Validation-Second)
                    // If no header found, analyze the first data row to guess columns
                    if (headerRowIdx == -1) {
                        val firstRow = allRows[0].select("td, th")
                        if (firstRow.size >= 2) {
                            nameIdx = 0
                            
                            val numbers = firstRow.mapIndexed { idx, cell -> 
                                idx to cell.text().replace(",", "").replace("%", "").replace("+", "").trim().toDoubleOrNull()
                            }.filter { it.second != null }

                            if (numbers.size >= 2) {
                                // HEURISTIC: Large numbers > 10.0 are likely index values. 
                                // In tables with OHLC, Close is usually the LAST large number before the small change numbers.
                                val valueCandidates = numbers.filter { it.second!! > 10.0 }
                                if (valueCandidates.isNotEmpty()) {
                                    valIdx = valueCandidates.last().first
                                    
                                    // Change and Pct are usually the numbers immediately following the value
                                    val following = numbers.filter { it.first > valIdx }
                                    if (following.size >= 1) chgIdx = following[0].first
                                    if (following.size >= 2) pctIdx = following[1].first
                                    
                                    // Reverse check: if only one following and it contains %, it's pct
                                    if (following.size == 1 && firstRow[following[0].first].text().contains("%")) {
                                        pctIdx = following[0].first
                                        chgIdx = -1
                                    }
                                }
                            }
                        }
                    }

                    // 3. EXTRACTION
                    if (nameIdx != -1 && valIdx != -1) {
                        allRows.drop(headerRowIdx + 1).forEach { row ->
                            val cells = row.select("td, th")
                            if (cells.size > maxOf(nameIdx, valIdx)) {
                                val rawName = cells[nameIdx].text().trim()
                                val valStr = cells[valIdx].text().replace(",", "").trim()
                                val value = valStr.toDoubleOrNull() ?: 0.0
                                
                                val cleanName = rawName.replace(",", "")
                                if (rawName.isNotEmpty() && value > 0 && !garbageTerms.contains(rawName.lowercase()) && cleanName.toDoubleOrNull() == null) {
                                    val name = normalizeIndexName(rawName, primaryName)
                                    
                                    // Dynamic fallbacks for change values
                                    val rawChg = if (chgIdx != -1 && chgIdx < cells.size) 
                                        cells[chgIdx].text().replace(",", "").replace("+", "").trim().toDoubleOrNull() ?: 0.0 
                                        else 0.0
                                    val rawPct = if (pctIdx != -1 && pctIdx < cells.size) 
                                        cells[pctIdx].text().replace(",", "").replace("+", "").replace("%", "").trim().toDoubleOrNull() ?: 0.0 
                                        else 0.0
                                        
                                    // Heuristic to detect negative status if "-" sign is present in text but missing in parsed double
                                    val isNegText = (chgIdx != -1 && chgIdx < cells.size && cells[chgIdx].text().contains("-")) || 
                                                    (pctIdx != -1 && pctIdx < cells.size && cells[pctIdx].text().contains("-"))
                                    
                                    val finalChg = if (isNegText) -Math.abs(rawChg) else rawChg
                                    val finalPct = if (isNegText) -Math.abs(rawPct) else rawPct
                                    
                                    val existing = portfolioDao.getIndexByName(name)
                                    
                                    // RELAXED FLAT LOGIC: Allow update if timestamp is old
                                    val isSameValue = existing != null && Math.abs(existing.currentValue - value) < 0.01
                                    val isRecent = existing != null && (timestamp - existing.timestamp) < 60000
                                    
                                    if (isSameValue && isRecent) return@forEach

                                    // If we already have this index from a previous source/table, only overwrite if current has more info (non-zero change)
                                    val current = scrapedIndices[name]
                                    if (current == null || (Math.abs(current.change) < 0.01 && Math.abs(finalChg) > 0.01)) {
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
                com.example.data.db.MarketIndexEntity(it.index, it.value, it.previousValue, it.change, it.percentChange, "Scraped", timestamp)
            })
            updateCount = scrapedIndices.size
        }
        Result.success(updateCount)
    }

    /**
     * Phase 1.4: DP Master Sync (Refined)
     * Fetches Depository Participants from user-configured URL.
     * Maps the 5-digit DP code (e.g. 10600) to MeroShare clientId.
     */
    suspend fun fetchDpMaster(): Boolean = withContext(Dispatchers.IO) {
        val urls = getScraperUrls(ScraperCategory.DP_MASTER)
        if (urls.isEmpty()) {
            AppLogger.w("DpSync", "No scraper URLs configured for DP Master")
            return@withContext false
        }
        
        for (url in urls) {
            try {
                AppLogger.d("DpSync", "Scraping DP List from: $url")
                val client = NetworkUtils.getUnsafeOkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.google.com/")
                    .build()
                
                val html = client.newCall(request).execute().use { it.body?.string() ?: "" }
                if (html.isBlank()) {
                    AppLogger.w("DpSync", "Empty HTML response from $url")
                    continue
                }
                
                val doc = Jsoup.parse(html)
                val dps = mutableListOf<com.example.data.db.DpMaster>()
                val now = System.currentTimeMillis()

                // Strategy: Focus on the specific structure of ShareSansar's DP member list
                val tables = doc.select("table#example, table.table")
                AppLogger.d("DpSync", "Found ${tables.size} potential tables in $url")

                tables.forEachIndexed { tableIdx, table ->
                    val rows = table.select("tr")
                    if (rows.size < 5) return@forEachIndexed

                    var codeIdx = -1; var nameIdx = -1; var addrIdx = -1; var telIdx = -1
                    var headerRowIdx = -1

                    // Robust header identification based on ShareSansar's specific column names
                    for (i in 0 until minOf(rows.size, 10)) {
                        val headerCells = rows[i].select("th, td")
                        val header = headerCells.map { it.text().lowercase().trim() }
                        
                        header.forEachIndexed { idx, text ->
                            when {
                                text.contains("dp id") -> codeIdx = idx
                                text.contains("participant") && text.contains("name") -> nameIdx = idx
                                text.contains("address") -> addrIdx = idx
                                text.contains("telephone") || text.contains("phone") -> telIdx = idx
                            }
                        }
                        if (codeIdx != -1 && nameIdx != -1) {
                            headerRowIdx = i
                            break
                        }
                    }

                    if (codeIdx != -1 && nameIdx != -1) {
                        AppLogger.d("DpSync", "Processing Table $tableIdx (Header row: $headerRowIdx)")
                        rows.drop(headerRowIdx + 1).forEach { row ->
                            val cells = row.select("td")
                            if (cells.size > maxOf(codeIdx, nameIdx)) {
                                val rawCode = cells[codeIdx].text().trim()
                                val rawName = cells[nameIdx].text().trim()
                                val rawAddr = if (addrIdx != -1 && addrIdx < cells.size) cells[addrIdx].text().trim() else ""
                                val rawTel = if (telIdx != -1 && telIdx < cells.size) cells[telIdx].text().trim() else ""
                                
                                // ShareSansar provides 8-digit DP ID (e.g. 13013200)
                                // We need the last 5 digits for the MeroShare clientId mapping (e.g. 13200)
                                val digitsOnly = rawCode.filter { it.isDigit() }
                                if (digitsOnly.length >= 5) {
                                    val cleanCode = if (digitsOnly.length >= 8) digitsOnly.take(8).takeLast(5) else digitsOnly.takeLast(5)
                                    val clientId = getClientIdFromFallback(cleanCode)
                                    
                                    if (rawName.length > 3 && !garbageTerms.contains(rawName.lowercase())) {
                                        dps.add(com.example.data.db.DpMaster(cleanCode, rawName, clientId, rawAddr, rawTel, now))
                                    }
                                }
                            }
                        }
                    }
                }

                if (dps.isNotEmpty()) {
                    portfolioDao.insertDpMaster(dps)
                    AppLogger.i("DpSync", "Successfully synced ${dps.size} DPs from $url")
                    return@withContext true
                }
            } catch (e: Exception) {
                AppLogger.e("DpSync", "Failed to sync DP Master from $url: ${e.message}")
            }
        }
        false
    }

    private fun getClientIdFromFallback(dpCode: String): Int {
        // Fallback mapping based on constants.py for known Client IDs
        val mapping = mapOf(
            "10100" to 138, "10200" to 172, "10400" to 164, "10600" to 173, "10700" to 157,
            "10800" to 145, "10900" to 190, "11000" to 175, "11100" to 134, "11200" to 146,
            "11300" to 143, "11400" to 196, "11500" to 171, "11600" to 187, "11700" to 137,
            "11800" to 176, "11900" to 131, "12000" to 141, "12200" to 151, "12300" to 129,
            "12400" to 195, "12500" to 199, "12600" to 179, "12700" to 188, "12800" to 182,
            "12900" to 189, "13000" to 192, "13100" to 150, "13200" to 128, "13300" to 139,
            "13400" to 140, "13500" to 200, "13600" to 161, "13700" to 174, "13800" to 158,
            "13900" to 178, "14000" to 193, "14100" to 155, "14200" to 194, "14300" to 154,
            "14400" to 185, "14500" to 142, "14600" to 191, "14700" to 133, "14800" to 180,
            "14900" to 144, "15000" to 135, "15100" to 166, "15200" to 156, "15300" to 170,
            "15400" to 152, "15500" to 169, "15600" to 132, "15700" to 167, "15800" to 186,
            "15900" to 163, "16000" to 136, "16100" to 159, "16200" to 147, "16300" to 168,
            "16400" to 165, "16500" to 184, "16600" to 183, "16700" to 160, "16800" to 198,
            "16900" to 181, "17000" to 177, "17100" to 197, "17200" to 130, "17300" to 162,
            "17400" to 149, "17500" to 201, "17600" to 153, "17700" to 148, "17800" to 370,
            "17900" to 402, "18000" to 681, "18100" to 1080, "18200" to 1182, "18300" to 1186,
            "18400" to 1189, "18500" to 1196, "18600" to 1270, "18700" to 1271, "18800" to 1274,
            "18900" to 1281, "19000" to 1287, "19100" to 1298, "19200" to 1294, "19300" to 1296,
            "19400" to 1293, "19500" to 1292, "19600" to 1297, "19700" to 1295, "19800" to 1305,
            "19900" to 1306, "20000" to 1308, "20100" to 1309, "20200" to 1310, "20300" to 1311,
            "20400" to 1320, "20500" to 1317, "20600" to 1315, "20700" to 1314, "20800" to 1316,
            "20900" to 1318, "21000" to 1319, "21100" to 1325, "21200" to 1324, "21300" to 1328,
            "21400" to 1327, "21500" to 1326, "21600" to 1329
        )
        return mapping[dpCode] ?: 0
    }

    suspend fun fetchPriceChanges(force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastPriceSync) < BACKGROUND_SYNC_COOLDOWN) {
            AppLogger.d("LtpSync", "Skipping Scrip LTP Sync (Cooldown active)", throttle = true)
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
                AppLogger.w("LtpSync", "Circuit OPEN for $baseUrl, skipping...", throttle = true)
                continue
            }

            try {
                val url = if (baseUrl.contains("?")) "$baseUrl&t=${System.currentTimeMillis()}" else "$baseUrl?t=${System.currentTimeMillis()}"
                AppLogger.d("LtpSync", "Requesting URL: $url")
                
                val useUnsafe = baseUrl.contains("nepalstock.com")
                val html = if (useUnsafe) {
                    val client = NetworkUtils.getUnsafeOkHttpClient()
                    val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        response.body?.string() ?: ""
                    }
                } else {
                    Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20000).get().html()
                }

                if (html.isBlank()) {
                    cb.recordFailure()
                    continue
                }

                val doc = Jsoup.parse(html)
                val timestamp = System.currentTimeMillis()

                doc.select("table").forEachIndexed { tableIdx, table ->
                    val rows = table.select("tr")
                    if (rows.isEmpty()) return@forEachIndexed
                    var symIdx = -1; var ltpIdx = -1; var prvIdx = -1; var chgIdx = -1; var pctIdx = -1
                    var openIdx = -1; var highIdx = -1; var lowIdx = -1
                    var headerIdx = -1
                    for (i in 0 until minOf(rows.size, 5)) {
                        val header = rows[i].select("th, td").map { it.text().lowercase().trim() }
                        header.forEachIndexed { index, text ->
                            if (text == "symbol" || text == "scrip" || text == "code") symIdx = index
                            if (text == "ltp" || text.contains("last traded") || (text == "close" && ltpIdx == -1)) ltpIdx = index
                            if (text.contains("prev") || (text == "close" && ltpIdx != index)) prvIdx = index
                            if (text.contains("change") && !text.contains("%") || text == "+/-") chgIdx = index
                            if (text.contains("%")) pctIdx = index
                            if (text == "open") openIdx = index
                            if (text == "high") highIdx = index
                            if (text == "low") lowIdx = index
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
                                    val existingScrip = portfolioDao.getScripMasterBySymbol(symbol)
                                    val existingHolding = portfolioDao.getHoldingsBySymbol(symbol)
                                    
                                    // Try direct change/percent if available
                                    val directChange = if (chgIdx != -1 && chgIdx < cells.size) cells[chgIdx].text().replace(",", "").replace("+", "").trim().toDoubleOrNull() else null
                                    val directPct = if (pctIdx != -1 && pctIdx < cells.size) cells[pctIdx].text().replace(",", "").replace("+", "").replace("%", "").trim().toDoubleOrNull() else null
                                    
                                    val openVal = if (openIdx != -1 && openIdx < cells.size) cells[openIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0 else 0.0
                                    val highVal = if (highIdx != -1 && highIdx < cells.size) cells[highIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0 else 0.0
                                    val lowVal = if (lowIdx != -1 && lowIdx < cells.size) cells[lowIdx].text().replace(",", "").trim().toDoubleOrNull() ?: 0.0 else 0.0

                                    val isNeg = (chgIdx != -1 && chgIdx < cells.size && cells[chgIdx].text().contains("-")) || 
                                                (pctIdx != -1 && pctIdx < cells.size && cells[pctIdx].text().contains("-"))

                                    val finalChg = directChange?.let { if (isNeg) -Math.abs(it) else it } ?: 0.0
                                    val finalPct = directPct?.let { if (isNeg) -Math.abs(it) else it } ?: 0.0

                                    // SMART UPDATE LOGIC:
                                    // 1. If current data shows ZERO change, but DB has valid NON-ZERO change, skip it (likely stale/closed market data).
                                    val isInvalidZero = existingScrip != null && Math.abs(existingScrip.pointChange) > 0.01 && Math.abs(finalChg) < 0.01
                                    
                                    // 2. If LTP is same AND current change is same (or zero), skip update to avoid unnecessary writes.
                                    val isExactlySame = existingScrip != null && Math.abs(existingScrip.ltp - ltp) < 0.001 && Math.abs(existingScrip.pointChange - finalChg) < 0.01
                                    
                                    if (isInvalidZero || isExactlySame) {
                                        return@forEach
                                    }

                                    val prevLtp = if (prvIdx != -1 && prvIdx < cells.size) cells[prvIdx].text().replace(",", "").trim().toDoubleOrNull() ?: (ltp - finalChg) else (ltp - finalChg)
                                    val finalPctComputed = if (directPct != null) finalPct else (if (prevLtp > 0) (ltp - prevLtp) / prevLtp * 100.0 else 0.0)

                                    if (existingScrip == null) {
                                        portfolioDao.insertScripMasterSingle(
                                            ScripMaster(
                                                symbol = symbol,
                                                name = symbol,
                                                sector = "Other",
                                                ltp = ltp,
                                                previousLtp = prevLtp,
                                                pointChange = finalChg,
                                                changePercent = finalPctComputed,
                                                open = openVal,
                                                high = highVal,
                                                low = lowVal,
                                                timestamp = timestamp
                                            )
                                        )
                                    } else {
                                        // Update Global ScripMaster
                                        portfolioDao.updateScripPriceFull(
                                            symbol = symbol,
                                            ltp = ltp,
                                            prev = prevLtp,
                                            chg = finalChg,
                                            pct = finalPctComputed,
                                            o = openVal,
                                            h = highVal,
                                            l = lowVal,
                                            ts = timestamp
                                        )
                                    }

                                    // Update User Holdings (Overwrite imported price on Market Refresh as per user instruction)
                                    if (existingHolding != null) {
                                        portfolioDao.updateHoldingPrice(
                                            symbol = symbol,
                                            ltp = ltp,
                                            prev = prevLtp,
                                            source = "Scraped",
                                            sync = existingHolding.isInExternalSync,
                                            ts = timestamp
                                        )
                                    }
                                    
                                    updateCount++
                                    changes.add(ScripPriceChange(symbol, ltp, finalChg, finalPctComputed, prevLtp, "Scraped"))

                                    // 2.2: Price Audit Buffer (RAM caching layer)
                                    PriceAuditBuffer.onPriceUpdate(
                                        symbol = symbol,
                                        ltp = ltp,
                                        changePercent = finalPctComputed,
                                        source = "Scraped",
                                        isHeldOrWishlisted = watchedScrips.contains(symbol)
                                    )
                                }
                            }
                        }
                    }
                }
                if (updateCount > 0) {
                    cb.recordSuccess()
                    AppLogger.i("LtpSync", "Sync successful from $baseUrl. Updated $updateCount items.")
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
