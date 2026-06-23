package com.example.data.repository

import com.example.data.db.BoidEntity
import com.example.data.db.IpoMaster
import com.example.data.db.IpoMasterDao
import com.example.data.db.IpoResultCache
import com.example.data.db.PortfolioDao
import com.example.data.model.*
import com.example.data.util.AppLogger
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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

    val allBoids: Flow<List<BoidEntry>> = portfolioDao.getAllBoids().map { list ->
        list.map { BoidEntry(name = it.name, boid = it.boid) }
    }

    val ipoMasterList: Flow<List<IpoMaster>> = ipoMasterDao.getAllIPOs()

    suspend fun addBoid(name: String, boid: String) {
        portfolioDao.insertBoid(BoidEntity(boid, name))
    }

    suspend fun removeBoid(boid: String) {
        portfolioDao.deleteBoid(BoidEntity(boid, ""))
    }

    suspend fun updateIpo(ipo: IpoMaster) {
        ipoMasterDao.update(ipo)
    }

    suspend fun syncIpos(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _syncLog.value = "STEP 1/2: Fetching from Nepali Paisa API..."
            val npResult = syncFromNepaliPaisaApi()
            
            if (npResult.isFailure) {
                _syncLog.value = "STEP 1/2 FAILED: ${npResult.exceptionOrNull()?.message}"
                return@withContext npResult
            }

            _syncLog.value = "STEP 2/2: Mapping CDSC IDs..."
            val cdscResult = syncFromCdscResultPortal()
            
            if (cdscResult.isFailure) {
                _syncLog.value = "CDSC Mapping failed (WAF Block). Manual ID entry enabled."
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

    private suspend fun syncFromNepaliPaisaApi(): Result<Unit> {
        AppLogger.i("IpoSync", "Starting Nepali Paisa API Sync")
        return try {
            val apiUrl = "https://nepalipaisa.com/api/GetIpos?stockSymbol=&pageNo=1&itemsPerPage=100&pagePerDisplay=5"
            AppLogger.d("IpoSync", "Requesting URL: $apiUrl")
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            
            val responseCode = conn.responseCode
            AppLogger.d("IpoSync", "Response Code: $responseCode")
            if (responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                AppLogger.d("IpoSync", "Raw Response (First 500 chars): ${text.take(500)}")
                
                if (text.isBlank()) {
                    AppLogger.e("IpoSync", "Empty response from API")
                    return Result.failure(Exception("Empty response from API"))
                }
                
                val json = JSONObject(text)
                val resultObj = json.optJSONObject("result") ?: json
                val data = resultObj.optJSONArray("data") ?: resultObj.optJSONArray("Data")
                
                if (data == null) {
                    AppLogger.e("IpoSync", "JSON 'data' field missing. Keys found: ${json.keys().asSequence().toList()}")
                    return Result.failure(Exception("JSON 'data' field missing. Root keys: ${json.keys().asSequence().toList()}"))
                }
                
                AppLogger.i("IpoSync", "Found ${data.length()} potential items in JSON")
                val newIpos = mutableListOf<IpoMaster>()
                val now = System.currentTimeMillis()
                for (i in 0 until data.length()) {
                    val obj = data.getJSONObject(i)
                    val rawName = (obj.optString("companyName").ifBlank { obj.optString("CompanyName") }
                        .ifBlank { obj.optString("name") }.ifBlank { obj.optString("Name") }).trim()
                    
                    if (rawName.isEmpty()) continue
                    
                    val name = rawName.uppercase()
                    val existing = ipoMasterDao.getByName(name)
                    
                    val status = obj.optString("status").ifBlank { obj.optString("Status") } ?: "Unknown"
                    val opening = obj.optString("openingDate").ifBlank { obj.optString("OpeningDate") }
                    val closing = obj.optString("closingDate").ifBlank { obj.optString("ClosingDate") }
                    val scrip = obj.optString("scrip").ifBlank { obj.optString("Scrip") }
                    
                    newIpos.add(IpoMaster(
                        companyName = name,
                        shareType = obj.optString("shareType").ifBlank { obj.optString("ShareType") },
                        units = obj.optString("units").ifBlank { obj.optString("Units") },
                        openingDate = opening,
                        closingDate = closing,
                        issueManager = obj.optString("issueManager").ifBlank { obj.optString("IssueManager") },
                        status = status,
                        scrip = if (scrip.isNotBlank()) scrip.uppercase() else existing?.scrip,
                        cdscCompanyId = existing?.cdscCompanyId,
                        updatedAt = now - i // Preserves API order
                    ))
                }
                
                if (newIpos.isNotEmpty()) {
                    ipoMasterDao.insertAll(newIpos)
                    return Result.success(Unit)
                }
                Result.failure(Exception("Found 0 items in 'data' array"))
            } else {
                Result.failure(Exception("HTTP $responseCode: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Network/JSON Error: ${e.message}"))
        }
    }

    private suspend fun syncFromCdscResultPortal(): Result<Unit> {
        val cdscUrl = "https://iporesult.cdsc.com.np/result/company/list"
        AppLogger.i("IpoSync", "Starting CDSC Portal Mapping")
        try {
            // Step 0: Optional landing page visit to establish session/cookies if needed
            // For now, we'll focus on making the request look like a real browser
            
            val conn = URL(cdscUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            
            // Comprehensive Browser Headers
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Referer", "https://iporesult.cdsc.com.np/")
            conn.setRequestProperty("Origin", "https://iporesult.cdsc.com.np")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
            
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            
            AppLogger.d("IpoSync", "Requesting CDSC URL: $cdscUrl")
            val responseCode = conn.responseCode
            val contentType = conn.contentType ?: ""
            
            // Log full response metadata for debugging WAF issues
            AppLogger.d("IpoSync", "CDSC Response Code: $responseCode, Content-Type: $contentType")
            val headers = conn.headerFields.filterKeys { it != null }
            AppLogger.d("IpoSync", "CDSC Response Headers: $headers")

            if (responseCode == 200) {
                // Defensive check: If it's HTML, it's a WAF rejection or landing page
                if (contentType.contains("text/html")) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    AppLogger.e("IpoSync", "Received HTML instead of JSON. Potential WAF block. Body: ${text.take(200)}...")
                    return Result.failure(Exception("CDSC returned HTML instead of JSON (WAF Block)"))
                }

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (!text.trim().startsWith("[") && !text.trim().startsWith("{")) {
                    AppLogger.e("IpoSync", "Invalid JSON format from CDSC. Body: ${text.take(100)}...")
                    return Result.failure(Exception("Invalid JSON format from CDSC"))
                }

                val json = JSONArray(text)
                AppLogger.i("IpoSync", "Found ${json.length()} companies in CDSC Portal")
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val cdscId = obj.getInt("id")
                    val name = obj.getString("name").trim().uppercase()
                    
                    val existing = ipoMasterDao.getByName(name) ?: ipoMasterDao.getByCdscId(cdscId)
                    if (existing != null) {
                        ipoMasterDao.update(existing.copy(cdscCompanyId = cdscId))
                    } else {
                        ipoMasterDao.insert(IpoMaster(
                            companyName = name,
                            cdscCompanyId = cdscId,
                            status = "Allotted",
                            scrip = if (obj.has("scrip") && !obj.isNull("scrip")) obj.getString("scrip").uppercase() else null
                        ))
                    }
                }
                return Result.success(Unit)
            } else {
                val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                AppLogger.e("IpoSync", "CDSC Portal HTTP $responseCode. Error: $errorText")
                return Result.failure(Exception("CDSC Portal HTTP $responseCode"))
            }
        } catch (e: Exception) {
            AppLogger.e("IpoSync", "CDSC Sync Failed", e)
            return Result.failure(e)
        }
    }

    suspend fun checkIpoResult(cdscCompanyId: Int, boid: String): Result<IpoResultResponse> = withContext(Dispatchers.IO) {
        val cached = ipoMasterDao.getIpoResult(cdscCompanyId, boid)
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < 24 * 60 * 60 * 1000) {
            AppLogger.d("IpoCheck", "Returning cached result for BOID: $boid")
            return@withContext Result.success(IpoResultResponse(
                success = cached.result == "Allotted" || cached.result == "Applied",
                message = cached.message ?: cached.result,
                status = cached.result
            ))
        }

        val urls = getScraperUrls(ScraperCategory.CDSC_RESULT)
        var lastErr: Exception? = null

        for (resultUrlStr in urls) {
            try {
                AppLogger.d("IpoCheck", "Checking result for BOID: $boid at $resultUrlStr")
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
                conn.setRequestProperty("Sec-Fetch-Dest", "empty")
                conn.setRequestProperty("Sec-Fetch-Mode", "cors")
                conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
                
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val payload = JSONObject().apply {
                    put("companyShareId", cdscCompanyId)
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
                        ipoId = cdscCompanyId,
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

    suspend fun bulkCheckIpoResults(cdscCompanyId: Int, boids: List<BoidEntry>, onProgress: (Int, Result<IpoResultResponse>) -> Unit) {
        coroutineScope {
            boids.chunked(10).forEach { batch ->
                batch.map { boidEntry ->
                    async {
                        val result = checkIpoResult(cdscCompanyId, boidEntry.boid)
                        onProgress(boids.indexOf(boidEntry), result)
                    }
                }.awaitAll()
                delay(300)
            }
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
                ScraperCategory.IPO_LISTING -> listOf("https://nepalipaisa.com/api/GetIpos?stockSymbol=&pageNo=1&itemsPerPage=100&pagePerDisplay=5")
                ScraperCategory.CDSC_COMPANIES -> listOf("https://iporesult.cdsc.com.np/result/company/list")
                ScraperCategory.CDSC_RESULT -> listOf("https://iporesult.cdsc.com.np/result/ipo/result")
                else -> emptyList()
            }
        }
    }
}
