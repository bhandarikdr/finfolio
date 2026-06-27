package com.example.data.repository

import com.example.data.db.BoidEntity
import com.example.data.db.IpoMaster
import com.example.data.db.IpoMasterDao
import com.example.data.db.IpoResultCache
import com.example.data.db.PortfolioDao
import com.example.data.model.*
import com.example.data.util.AppLogger
import com.example.data.util.NetworkUtils
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for handling IPO listings, result checking, and BOID management.
 * Dynamically fetches and persists IPO master data from prioritized sources.
 */
class IpoRepository(
    private val portfolioDao: PortfolioDao,
    private val ipoMasterDao: IpoMasterDao
) {

    private val _syncLog = MutableStateFlow<String>("IDLE")
    val syncLog = _syncLog.asStateFlow()

    private var sessionCookies: String? = null
    private var lastIpoSync = 0L
    private val IPO_SYNC_COOLDOWN = 6 * 60 * 60 * 1000L // 6 Hours as suggested

    private suspend fun refreshSessionCookies(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val urls = getScraperUrls(ScraperCategory.IPO_RESULT)
                if (urls.isEmpty()) return@withContext null
                
                val url = URL(urls.first())
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val cookies = conn.headerFields["Set-Cookie"]
                if (!cookies.isNullOrEmpty()) {
                    sessionCookies = cookies.joinToString("; ") { it.split(";")[0] }
                    AppLogger.d("IpoSync", "Acquired Session Cookies: $sessionCookies")
                }
                sessionCookies
            } catch (e: Exception) {
                AppLogger.e("IpoSync", "Failed to acquire session cookies - WAF likely blocking initial landing", e)
                null
            }
        }
    }

    val allBoids: Flow<List<BoidEntry>> = portfolioDao.getAllBoids().map { list ->
        list.map { BoidEntry(name = it.name, boid = it.boid, isDefault = it.isDefault) }
    }

    val ipoMasterList: Flow<List<IpoMaster>> = ipoMasterDao.getAllIPOs()

    suspend fun addBoid(name: String, boid: String) {
        val currentDefault = portfolioDao.getDefaultBoidSync()
        portfolioDao.insertBoid(BoidEntity(boid, name, isDefault = currentDefault == null))
    }

    suspend fun setDefaultBoid(boid: String) {
        portfolioDao.clearDefaultBoid()
        portfolioDao.setDefaultBoid(boid)
    }

    suspend fun removeBoid(boid: String) {
        portfolioDao.deleteBoid(BoidEntity(boid, ""))
    }

    suspend fun updateIpo(ipo: IpoMaster) {
        ipoMasterDao.update(ipo)
    }

    suspend fun syncIpos(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastIpoSync) < IPO_SYNC_COOLDOWN) {
            AppLogger.d("IpoSync", "Skipping IPO Sync (Cooldown active)")
            _syncLog.value = "SYNC SKIPPED: Up to date"
            return@withContext Result.success(Unit)
        }
        
        try {
            _syncLog.value = "STEP 1/2: Fetching from configured IPO listing sources..."
            val listingResult = syncFromIpoListingSource()
            
            if (listingResult.isFailure) {
                _syncLog.value = "STEP 1/2 FAILED: ${listingResult.exceptionOrNull()?.message}"
                return@withContext listingResult
            }

            lastIpoSync = now
            _syncLog.value = "STEP 2/2: Mapping Company IDs for Result Checking..."
            val mappingResult = syncFromIpoResultMappingSource()
            
            if (mappingResult.isFailure) {
                _syncLog.value = "Mapping failed (Firewall/WAF Block). Manual ID entry may be required."
            } else {
                _syncLog.value = "SYNC COMPLETE: Successfully fetched and mapped."
            }

            Result.success(Unit)
        } catch (e: Exception) {
            val err = "CRITICAL SYNC ERROR: ${e.message}"
            _syncLog.value = err
            Log.e("IpoRepository", err, e)
            Result.failure(e)
        }
    }

    private suspend fun syncFromIpoListingSource(): Result<Unit> {
        AppLogger.i("IpoSync", "Starting IPO Listing Sync")
        val urls = getScraperUrls(ScraperCategory.IPO_LISTING)
        
        for (apiUrl in urls) {
            try {
                AppLogger.d("IpoSync", "Requesting URL: $apiUrl")
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                
                val responseCode = conn.responseCode
                val contentType = conn.contentType ?: ""
                
                if (responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    if (text.isBlank()) continue
                    
                    val newIpos = mutableListOf<IpoMaster>()
                    val now = System.currentTimeMillis()

                    if (contentType.contains("application/json") || text.trim().startsWith("{") || text.trim().startsWith("[")) {
                        // Handle JSON format
                        val json = try { JSONObject(text) } catch (e: Exception) { null }
                        if (json != null) {
                            val resultObj = json.optJSONObject("result") ?: json
                            val data = resultObj.optJSONArray("data") ?: resultObj.optJSONArray("Data") ?: resultObj.optJSONArray("items")
                            
                            if (data != null) {
                                for (i in 0 until data.length()) {
                                    val obj = data.getJSONObject(i)
                                    val rawName = (obj.optString("companyName").ifBlank { obj.optString("CompanyName") }
                                        .ifBlank { obj.optString("name") }.ifBlank { obj.optString("Name") }
                                        .ifBlank { obj.optString("company") }).trim()
                                    
                                    if (rawName.isEmpty()) continue
                                    
                                    val name = rawName.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } }
                                    val existing = ipoMasterDao.getByName(name.uppercase()) ?: ipoMasterDao.getByName(name)
                                    
                                    val status = obj.optString("status").ifBlank { obj.optString("Status") }.ifBlank { obj.optString("ipoStatus") } ?: "Unknown"
                                    val opening = obj.optString("openingDate").ifBlank { obj.optString("OpeningDate") }.ifBlank { obj.optString("openDate") }
                                    val closing = obj.optString("closingDate").ifBlank { obj.optString("ClosingDate") }.ifBlank { obj.optString("closeDate") }
                                    val scrip = obj.optString("scrip").ifBlank { obj.optString("Scrip") }.ifBlank { obj.optString("symbol") }
                                    
                                    newIpos.add(IpoMaster(
                                        companyName = name,
                                        shareType = obj.optString("shareType").ifBlank { obj.optString("ShareType") }.ifBlank { obj.optString("type") },
                                        units = obj.optString("units").ifBlank { obj.optString("Units") }.ifBlank { obj.optString("quantity") },
                                        openingDate = opening,
                                        closingDate = closing,
                                        issueManager = obj.optString("issueManager").ifBlank { obj.optString("IssueManager") }.ifBlank { obj.optString("manager") },
                                        status = status,
                                        scrip = if (scrip.isNotBlank()) scrip.uppercase() else existing?.scrip,
                                        resultPortalId = existing?.resultPortalId,
                                        allotmentDate = existing?.allotmentDate,
                                        updatedAt = now - i
                                    ))
                                }
                            }
                        }
                    } else {
                        // Handle HTML Table format
                        val doc = Jsoup.parse(text)
                        doc.select("table").forEach { table ->
                            val allRows = table.select("tr")
                            if (allRows.size < 2) return@forEach
                            
                            var symIdx = -1; var nameIdx = -1; var statusIdx = -1
                            var openIdx = -1; var closeIdx = -1; var typeIdx = -1; var qtyIdx = -1; var mngrIdx = -1

                            for (i in 0 until minOf(allRows.size, 5)) {
                                val header = allRows[i].select("th, td").map { it.text().lowercase().trim() }
                                header.forEachIndexed { index, hText ->
                                    if (hText == "symbol" || hText == "scrip" || hText == "code" || hText == "ticker") symIdx = index
                                    if (hText.contains("company") || hText == "name" || hText == "security") nameIdx = index
                                    if (hText.contains("status")) statusIdx = index
                                    if (hText.contains("opening") || hText.contains("open date")) openIdx = index
                                    if (hText.contains("closing") || hText.contains("close date")) closeIdx = index
                                    if (hText.contains("type") || hText.contains("share type")) typeIdx = index
                                    if (hText.contains("units") || hText.contains("quantity") || hText.contains("qty")) qtyIdx = index
                                    if (hText.contains("manager")) mngrIdx = index
                                }
                                if (nameIdx != -1) break
                            }

                            if (nameIdx != -1) {
                                allRows.drop(1).forEach { row ->
                                    val cells = row.select("td")
                                    if (cells.size > nameIdx) {
                                        val rawName = cells[nameIdx].text().trim()
                                        if (rawName.isEmpty()) return@forEach
                                        
                                        val name = rawName.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } }
                                        val existing = ipoMasterDao.getByName(name.uppercase()) ?: ipoMasterDao.getByName(name)
                                        
                                        val symbol = if (symIdx != -1 && symIdx < cells.size) cells[symIdx].text().trim().uppercase() else ""
                                        val status = if (statusIdx != -1 && statusIdx < cells.size) cells[statusIdx].text().trim() else "Unknown"
                                        val opening = if (openIdx != -1 && openIdx < cells.size) cells[openIdx].text().trim() else ""
                                        val closing = if (closeIdx != -1 && closeIdx < cells.size) cells[closeIdx].text().trim() else ""
                                        val type = if (typeIdx != -1 && typeIdx < cells.size) cells[typeIdx].text().trim() else ""
                                        val qty = if (qtyIdx != -1 && qtyIdx < cells.size) cells[qtyIdx].text().trim() else ""
                                        val mngr = if (mngrIdx != -1 && mngrIdx < cells.size) cells[mngrIdx].text().trim() else ""

                                        newIpos.add(IpoMaster(
                                            companyName = name,
                                            shareType = type,
                                            units = qty,
                                            openingDate = opening,
                                            closingDate = closing,
                                            issueManager = mngr,
                                            status = status,
                                            scrip = if (symbol.isNotBlank()) symbol else existing?.scrip,
                                            resultPortalId = existing?.resultPortalId,
                                            allotmentDate = existing?.allotmentDate,
                                            updatedAt = now - newIpos.size
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    if (newIpos.isNotEmpty()) {
                        ipoMasterDao.insertAll(newIpos)
                        return Result.success(Unit)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("IpoSync", "Failed to sync from $apiUrl", e)
            }
        }
        return Result.failure(Exception("Could not fetch IPO listing from any configured sources."))
    }

    private suspend fun syncFromIpoResultMappingSource(): Result<Unit> {
        val urls = getScraperUrls(ScraperCategory.IPO_COMPANIES)
        AppLogger.i("IpoSync", "Starting IPO Result Mapping Sync")
        
        for (mappingUrl in urls) {
            try {
                if (sessionCookies == null) refreshSessionCookies()
                
                val conn = URL(mappingUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (sessionCookies != null) conn.setRequestProperty("Cookie", sessionCookies)
                conn.connectTimeout = 15000
                
                if (conn.responseCode == 200) {
                    val contentType = conn.contentType ?: ""
                    if (contentType.contains("text/html")) continue

                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = try { JSONArray(text) } catch (e: Exception) { null } ?: continue

                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        val cdscId = obj.getInt("id")
                        val rawName = obj.getString("name").trim()
                        val name = rawName.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } }
                        
                        val existing = ipoMasterDao.getByName(name.uppercase()) ?: ipoMasterDao.getByName(name) ?: ipoMasterDao.getByResultPortalId(cdscId)
                        if (existing != null) {
                            ipoMasterDao.update(existing.copy(resultPortalId = cdscId, status = "Allotted"))
                        } else {
                            ipoMasterDao.insert(IpoMaster(
                                companyName = name,
                                resultPortalId = cdscId,
                                status = "Allotted",
                                scrip = if (obj.has("scrip") && !obj.isNull("scrip")) obj.getString("scrip").uppercase() else null,
                                allotmentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                            ))
                        }
                    }
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                AppLogger.e("IpoSync", "Mapping sync failed for $mappingUrl", e)
            }
        }
        return Result.failure(Exception("Mapping sync failed"))
    }

    suspend fun checkIpoResult(portalId: Int, boid: String): Result<IpoResultResponse> = withContext(Dispatchers.IO) {
        AppLogger.i("IpoCheck", "Checking result for BOID: $boid, Portal ID: $portalId")
        val cached = ipoMasterDao.getIpoResult(portalId, boid)
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < 24 * 60 * 60 * 1000) {
            AppLogger.d("IpoCheck", "Returning cached result for BOID: $boid")
            return@withContext Result.success(IpoResultResponse(
                success = cached.result == "Allotted" || cached.result == "Applied",
                message = cached.message ?: cached.result,
                status = cached.result
            ))
        }

        val urls = getScraperUrls(ScraperCategory.IPO_RESULT)
        var lastErr: Exception? = null

        for (resultUrlStr in urls) {
            try {
                AppLogger.d("IpoCheck", "Checking result for BOID: $boid at $resultUrlStr")
                
                // Step 0: Ensure session cookies exist for checking result too
                if (sessionCookies == null) {
                    refreshSessionCookies()
                }

                val url = URL(resultUrlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                
                // Comprehensive Browser Headers
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                conn.setRequestProperty("Referer", "https://iporesult.cdsc.com.np/")
                conn.setRequestProperty("Origin", "https://iporesult.cdsc.com.np")
                if (sessionCookies != null) {
                    conn.setRequestProperty("Cookie", sessionCookies)
                }
                conn.setRequestProperty("Sec-Fetch-Dest", "empty")
                conn.setRequestProperty("Sec-Fetch-Mode", "cors")
                conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
                
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val payload = JSONObject().apply {
                    put("companyShareId", portalId)
                    put("boid", boid)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                
                val responseCode = conn.responseCode
                val contentType = conn.contentType ?: ""
                AppLogger.d("IpoCheck", "Response Code: $responseCode, Content-Type: $contentType")
                
                if (responseCode == 200) {
                    // Defensive check: If it's HTML, it's a WAF rejection
                    if (contentType.contains("text/html")) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        AppLogger.e("IpoCheck", "Received HTML rejection. Body: ${text.take(200)}...")
                        if (text.contains("Request Rejected", true)) {
                            lastErr = Exception("Request blocked by portal firewall (WAF). Please try again later or check manually.")
                        }
                        continue // Try next URL if available
                    }

                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    AppLogger.d("IpoCheck", "Response: $text")
                    
                    if (!text.trim().startsWith("{")) {
                        AppLogger.e("IpoCheck", "Invalid JSON format. Body: ${text.take(100)}...")
                        continue
                    }

                    val obj = JSONObject(text)
                    val success = obj.getBoolean("success")
                    val message = obj.getString("message")
                    val result = IpoResultResponse(success, message)
                    
                    ipoMasterDao.insertIpoResult(IpoResultCache(
                        ipoId = portalId,
                        boid = boid,
                        result = if (success) "Allotted" else "Not Allotted",
                        units = if (success) extractUnits(message) else 0,
                        message = message
                    ))
                    return@withContext Result.success(result)
                } else if (conn.responseCode == 403 || conn.responseCode == 418) {
                    val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                    AppLogger.e("IpoCheck", "Access Denied (403/418). Error: $errorText")
                }
            } catch (e: Exception) {
                AppLogger.e("IpoCheck", "Error checking result for $boid", e)
                lastErr = e
            }
        }
        Result.failure(lastErr ?: Exception("Failed to check result"))
    }

    private fun extractUnits(message: String): Int {
        return try {
            val regex = """(\d+)\s+units""".toRegex()
            regex.find(message)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) { 0 }
    }

    suspend fun bulkCheckIpoResults(portalId: Int, boids: List<BoidEntry>, onProgress: (Int, Result<IpoResultResponse>) -> Unit) {
        coroutineScope {
            boids.chunked(10).forEach { batch ->
                batch.map { boidEntry ->
                    async {
                        val result = checkIpoResult(portalId, boidEntry.boid)
                        onProgress(boids.indexOf(boidEntry), result)
                    }
                }.awaitAll()
                delay(300)
            }
        }
    }

    suspend fun discoverResultPortalId(companyName: String): Int? = withContext(Dispatchers.IO) {
        val defaultBoid = portfolioDao.getDefaultBoidSync()?.boid ?: return@withContext null
        val latestId = ipoMasterDao.getLatestResultPortalId() ?: 100 // Fallback to 100
        
        // Strategy: Search upwards from the latest known ID
        // CDSC IDs are sequential and increasing for newer issues.
        val startRange = latestId
        val endRange = latestId + 60
        
        val candidates = (startRange..endRange).toList()
        
        AppLogger.i("IpoDiscovery", "Starting sequential discovery for $companyName using $defaultBoid. Range: $startRange to $endRange")
        
        for (id in candidates) {
            try {
                val result = checkIpoResultInternal(id, defaultBoid)
                result.onSuccess {
                    // CDSC response for result check contains company name in the message:
                    // "You have not been allotted shares of [Company Name]"
                    if (it.message.contains(companyName, ignoreCase = true) || 
                        it.message.contains(companyName.split(" ").first(), ignoreCase = true)) {
                        AppLogger.i("IpoDiscovery", "Found ID $id for $companyName. Message: ${it.message}")
                        return@withContext id
                    }
                }
            } catch (e: Exception) {
                AppLogger.d("IpoDiscovery", "Candidate $id failed: ${e.message}")
            }
            
            // Respectful delay to avoid WAF blocking (3 seconds per request)
            // 50 attempts * 3s = 150 seconds max.
            delay(3000)
        }
        
        null
    }

    private suspend fun checkIpoResultInternal(portalId: Int, boid: String): Result<IpoResultResponse> {
        val urls = getScraperUrls(ScraperCategory.IPO_RESULT)
        val client = NetworkUtils.getUnsafeOkHttpClient()
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (resultUrlStr in urls) {
            try {
                if (sessionCookies == null) refreshSessionCookies()
                
                val payload = JSONObject().apply {
                    put("companyShareId", portalId)
                    put("boid", boid)
                }
                
                val request = Request.Builder()
                    .url(resultUrlStr)
                    .post(payload.toString().toRequestBody(mediaType))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .apply { if (sessionCookies != null) header("Cookie", sessionCookies!!) }
                    .build()
                
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: ""
                        if (text.trim().startsWith("{")) {
                            val obj = JSONObject(text)
                            return Result.success(IpoResultResponse(obj.getBoolean("success"), obj.getString("message")))
                        }
                    }
                }
            } catch (e: Exception) {}
        }
        return Result.failure(Exception("Discovery check failed"))
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
                ScraperCategory.IPO_LISTING -> listOf("https://nepalipaisa.com/api/GetIpos?stockSymbol=&pageNo=1&itemsPerPage=100&pagePerDisplay=5")
                ScraperCategory.IPO_COMPANIES -> listOf("https://iporesult.cdsc.com.np/result/company/list")
                ScraperCategory.IPO_RESULT -> listOf("https://iporesult.cdsc.com.np/result/ipo/result")
                else -> emptyList()
            }
        }
    }
}
