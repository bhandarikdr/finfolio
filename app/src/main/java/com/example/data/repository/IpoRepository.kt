package com.example.data.repository

import com.example.data.db.BoidEntity
import com.example.data.db.IpoMaster
import com.example.data.db.IpoMasterDao
import com.example.data.db.IpoMemberActivity
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
        list.map { 
            BoidEntry(
                name = it.name, 
                boid = it.boid, 
                isDefault = it.isDefault,
                isEnabledForCheck = it.isEnabledForCheck,
                isEnabledForApply = it.isEnabledForApply,
                isEnabledForBulk = it.isEnabledForBulk,
                msUsername = it.msUsername,
                msPassword = it.msPassword,
                msPin = it.msPin,
                msCrn = it.msCrn
            ) 
        }
    }

    val ipoMasterList: Flow<List<IpoMaster>> = ipoMasterDao.getAllIPOs()

    suspend fun addBoid(name: String, boid: String, msDetails: Map<String, String>? = null) {
        val currentDefault = portfolioDao.getDefaultBoidSync()
        portfolioDao.insertBoid(
            BoidEntity(
                boid = boid, 
                name = name, 
                isDefault = currentDefault == null,
                msUsername = msDetails?.get("username"),
                msPassword = msDetails?.get("password"),
                msPin = msDetails?.get("pin"),
                msCrn = msDetails?.get("crn")
            )
        )
    }

    suspend fun updateBoidCredentials(boid: String, msDetails: Map<String, String>) {
        val existing = portfolioDao.getAllBoidsSync().find { it.boid == boid }
        if (existing != null) {
            portfolioDao.insertBoid(existing.copy(
                msUsername = msDetails["username"],
                msPassword = msDetails["password"],
                msPin = msDetails["pin"],
                msCrn = msDetails["crn"]
            ))
        }
    }

    fun getActivityForCompany(companyName: String) = ipoMasterDao.getActivityForCompany(companyName)

    suspend fun updateIpoMemberActivity(activity: IpoMemberActivity) {
        withContext(Dispatchers.IO) {
            val existing = ipoMasterDao.getActivity(activity.companyName, activity.boid)
            if (existing == null) {
                ipoMasterDao.insertActivity(activity)
            } else {
                val merged = existing.copy(
                    applyStatus = if (activity.applyStatus != "PENDING") activity.applyStatus else existing.applyStatus,
                    applyMessage = if (activity.applyMessage != null) activity.applyMessage else existing.applyMessage,
                    appliedAt = if (activity.appliedAt != 0L) activity.appliedAt else existing.appliedAt,
                    allotmentStatus = if (activity.allotmentStatus != "NOT_CHECKED") activity.allotmentStatus else existing.allotmentStatus,
                    allotmentUnits = if (activity.allotmentUnits != 0) activity.allotmentUnits else existing.allotmentUnits,
                    allotmentMessage = if (activity.allotmentMessage != null) activity.allotmentMessage else existing.allotmentMessage,
                    checkedAt = if (activity.checkedAt != 0L) activity.checkedAt else existing.checkedAt,
                    isRecorded = activity.isRecorded || existing.isRecorded
                )
                ipoMasterDao.insertActivity(merged)
            }
        }
    }

    suspend fun resetAllotmentStatus(companyName: String, boid: String) {
        ipoMasterDao.resetAllotmentStatus(companyName, boid)
    }

    suspend fun resetAllAllotments(companyName: String) {
        ipoMasterDao.resetAllAllotments(companyName)
    }

    suspend fun updateApplyStatus(companyName: String, boid: String, status: String, message: String?, timestamp: Long) {
        // Ensure activity record exists first
        val existing = ipoMasterDao.getActivity(companyName, boid)
        if (existing == null) {
            ipoMasterDao.insertActivity(com.example.data.db.IpoMemberActivity(companyName = companyName, boid = boid, applyStatus = status, applyMessage = message, appliedAt = timestamp))
        } else {
            ipoMasterDao.updateApplyStatus(companyName, boid, status, message, timestamp)
        }
    }

    suspend fun updateBoidToggle(boid: String, isCheck: Boolean, enabled: Boolean?) {
        val existing = portfolioDao.getAllBoidsSync().find { it.boid == boid } ?: return
        val newEnabled = enabled ?: if (isCheck) !existing.isEnabledForCheck else !existing.isEnabledForApply
        if (isCheck) {
            portfolioDao.updateCheckEnabled(boid, newEnabled)
        } else {
            portfolioDao.updateApplyEnabled(boid, newEnabled)
        }
    }

    suspend fun toggleAllBoids(isCheck: Boolean?, enabled: Boolean) {
        val all = portfolioDao.getAllBoidsSync()
        portfolioDao.insertBoids(all.map { 
            when (isCheck) {
                true -> it.copy(isEnabledForCheck = enabled)
                false -> it.copy(isEnabledForApply = enabled)
                null -> it.copy(isEnabledForCheck = enabled, isEnabledForApply = enabled)
            }
        })
    }

    suspend fun setDefaultBoid(boid: String) {
        portfolioDao.clearDefaultBoid()
        portfolioDao.setDefaultBoid(boid)
    }

    suspend fun removeBoid(boid: String) {
        portfolioDao.deleteBoidByString(boid)
    }

    suspend fun updateIpo(ipo: IpoMaster) {
        ipoMasterDao.update(ipo)
    }

    suspend fun syncIpos(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastIpoSync) < IPO_SYNC_COOLDOWN) {
            AppLogger.d("IpoSync", "Skipping IPO Sync (Cooldown active)", throttle = true)
            _syncLog.value = "SYNC SKIPPED: Up to date"
            return@withContext Result.success(Unit)
        }
        
        try {
            _syncLog.value = "STEP 1/2: Fetching from configured issues listing sources..."
            val listingResult = syncFromIpoListingSource()
            
            // Add Dummy Issue for testing
            ipoMasterDao.insert(IpoMaster(
                companyName = "FinFolio Test Issue",
                shareType = "Ordinary Shares",
                issueType = "IPO",
                units = "1000000",
                openingDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
                closingDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now + 86400000 * 3),
                issueManager = "FinFolio Systems",
                status = "Open",
                scrip = "FFTEST",
                resultPortalId = 100, // Common test ID
                updatedAt = now + 1000
            ))

            if (listingResult.isFailure) {
                _syncLog.value = "STEP 1/2 FAILED: ${listingResult.exceptionOrNull()?.message}"
                return@withContext listingResult
            }

            lastIpoSync = now
            _syncLog.value = "STEP 2/2: Mapping Company IDs for Result Checking..."
            
            val finalCount = ipoMasterDao.getIpoCount()
            AppLogger.i("IpoSync", "Sync finished. Total IPOs in database: $finalCount")

            _syncLog.value = "SYNC COMPLETE: Successfully fetched and mapped."

            Result.success(Unit)
        } catch (e: Exception) {
            val err = "CRITICAL SYNC ERROR: ${e.message}"
            _syncLog.value = err
            Log.e("IpoRepository", err, e)
            Result.failure(e)
        }
    }

    private suspend fun syncFromIpoListingSource(): Result<Unit> {
        AppLogger.i("IpoSync", "Starting Issues Listing Sync")
        val urls = getScraperUrls(ScraperCategory.ISSUES_LISTING)
        var hasAtLeastOneSuccess = false
        
        for (apiUrl in urls) {
            try {
                AppLogger.d("IpoSync", "Requesting URL: $apiUrl")
                
                val typeFromUrl = when {
                    apiUrl.contains("/ipo", ignoreCase = true) -> "IPO"
                    apiUrl.contains("/fpo", ignoreCase = true) -> "FPO"
                    apiUrl.contains("/auction", ignoreCase = true) -> "Auction"
                    apiUrl.contains("/dividend", ignoreCase = true) -> "Dividend"
                    apiUrl.contains("/bond", ignoreCase = true) -> "Bond"
                    else -> "IPO"
                }
                val isNepaliPaisa = apiUrl.contains("nepalipaisa.com")

                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
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
                        val dataArray = try {
                            if (text.trim().startsWith("[")) {
                                JSONArray(text)
                            } else {
                                val json = JSONObject(text)
                                val resultObj = json.optJSONObject("result") ?: json
                                resultObj.optJSONArray("data") ?: resultObj.optJSONArray("Data") ?: resultObj.optJSONArray("items") ?: resultObj.optJSONArray("list")
                            }
                        } catch (e: Exception) {
                            null
                        }

                        if (dataArray != null) {
                            for (i in 0 until dataArray.length()) {
                                val obj = dataArray.getJSONObject(i)
                                val rawName = getSafeString(obj, listOf("companyName", "CompanyName", "name", "Name", "company", "issuer")).trim()
                                
                                if (rawName.isEmpty()) continue
                                
                                val name = rawName.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } }
                                val scrip = getSafeString(obj, listOf("scrip", "Scrip", "symbol", "Symbol", "code", "stockSymbol")).uppercase()
                                
                                val existing = ipoMasterDao.getByName(name.uppercase()) 
                                    ?: ipoMasterDao.getByName(name)
                                    ?: (if (scrip.isNotBlank()) ipoMasterDao.getByScrip(scrip) else null)
                                
                                val status = getSafeString(obj, listOf("status", "Status", "ipoStatus", "currentStatus", "state"))
                                val opening = cleanDate(getSafeString(obj, listOf("openingDateAD", "openingDate", "OpeningDate", "openingDateBS", "openDate", "OpenDate", "opening", "opening_date", "StartDate")), skipBsConversion = isNepaliPaisa)
                                val closing = cleanDate(getSafeString(obj, listOf("closingDateAD", "closingDate", "ClosingDate", "closingDateBS", "closeDate", "CloseDate", "closing", "closing_date", "EndDate")), skipBsConversion = isNepaliPaisa)
                                val allotment = cleanDate(getSafeString(obj, listOf("allotmentDateAD", "allotmentDate", "AllotmentDate", "allottedDate")), skipBsConversion = isNepaliPaisa)
                                
                                newIpos.add(IpoMaster(
                                    companyName = existing?.companyName ?: name,
                                    shareType = getSafeString(obj, listOf("shareType", "ShareType", "type", "IssueType", "ipoType")),
                                    issueType = typeFromUrl,
                                    units = getSafeString(obj, listOf("units", "Units", "quantity", "Quantity", "kitta", "totalKitta")),
                                    openingDate = if (!opening.isNullOrBlank()) opening else existing?.openingDate,
                                    closingDate = if (!closing.isNullOrBlank()) closing else existing?.closingDate,
                                    issueManager = getSafeString(obj, listOf("issueManager", "IssueManager", "manager", "Manager")),
                                    status = if (status.isNotBlank()) status else (existing?.status ?: "Unknown"),
                                    scrip = if (scrip.isNotBlank()) scrip else existing?.scrip,
                                    resultPortalId = existing?.resultPortalId,
                                    allotmentDate = if (!allotment.isNullOrBlank()) allotment else existing?.allotmentDate,
                                    updatedAt = now - i
                                ))
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
                            var headerRowIdx = -1

                            for (i in 0 until minOf(allRows.size, 5)) {
                                val header = allRows[i].select("th, td").map { it.text().lowercase().trim() }
                                header.forEachIndexed { index, hText ->
                                    if (hText == "symbol" || hText == "scrip" || hText == "code" || hText == "ticker") symIdx = index
                                    if (hText.contains("company") || hText == "name" || hText == "security") nameIdx = index
                                    if (hText.contains("status")) statusIdx = index
                                    if (hText.contains("opening") || hText.contains("open date") || hText == "opening") openIdx = index
                                    if (hText.contains("closing") || hText.contains("close date") || hText == "closing") closeIdx = index
                                    if (hText.contains("type") || hText.contains("share type") || hText.contains("issue type")) typeIdx = index
                                    if (hText.contains("units") || hText.contains("quantity") || hText.contains("qty") || hText.contains("kitta")) qtyIdx = index
                                    if (hText.contains("manager")) mngrIdx = index
                                }
                                if (nameIdx != -1) {
                                    headerRowIdx = i
                                    break
                                }
                            }

                            if (headerRowIdx != -1) {
                                allRows.drop(headerRowIdx + 1).forEach { row ->
                                    val cells = row.select("td")
                                    if (cells.size > nameIdx) {
                                        val rawName = cells[nameIdx].text().trim()
                                        if (rawName.isEmpty()) return@forEach
                                        
                                        val name = rawName.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.US) else c.toString() } }
                                        val symbol = if (symIdx != -1 && symIdx < cells.size) cells[symIdx].text().trim().uppercase() else ""
                                        
                                        val existing = ipoMasterDao.getByName(name.uppercase()) 
                                            ?: ipoMasterDao.getByName(name)
                                            ?: (if (symbol.isNotBlank()) ipoMasterDao.getByScrip(symbol) else null)

                                        val status = if (statusIdx != -1 && statusIdx < cells.size) cells[statusIdx].text().trim() else "Unknown"
                                        val opening = cleanDate(if (openIdx != -1 && openIdx < cells.size) cells[openIdx].text().trim() else "", skipBsConversion = isNepaliPaisa)
                                        val closing = cleanDate(if (closeIdx != -1 && closeIdx < cells.size) cells[closeIdx].text().trim() else "", skipBsConversion = isNepaliPaisa)
                                        val allotment = if (headerRowIdx != -1) {
                                            val headerCells = table.select("tr").get(headerRowIdx).select("th, td")
                                            val allotIdx = headerCells.indexOfFirst { it.text().lowercase().contains("allotment") }
                                            if (allotIdx != -1 && allotIdx < cells.size) cleanDate(cells[allotIdx].text().trim(), skipBsConversion = isNepaliPaisa) else null
                                        } else null

                                        val type = if (typeIdx != -1 && typeIdx < cells.size) cells[typeIdx].text().trim() else ""
                                        val qty = if (qtyIdx != -1 && qtyIdx < cells.size) cells[qtyIdx].text().trim() else ""
                                        val mngr = if (mngrIdx != -1 && mngrIdx < cells.size) cells[mngrIdx].text().trim() else ""

                                        newIpos.add(IpoMaster(
                                            companyName = existing?.companyName ?: name,
                                            shareType = type,
                                            issueType = typeFromUrl,
                                            units = qty,
                                            openingDate = if (!opening.isNullOrBlank()) opening else existing?.openingDate,
                                            closingDate = if (!closing.isNullOrBlank()) closing else existing?.closingDate,
                                            issueManager = mngr,
                                            status = status,
                                            scrip = if (symbol.isNotBlank()) symbol else existing?.scrip,
                                            resultPortalId = existing?.resultPortalId,
                                            allotmentDate = if (!allotment.isNullOrBlank()) allotment else existing?.allotmentDate,
                                            updatedAt = now - newIpos.size
                                        ))
                                    }
                                }
                            }
                        }
                    }

                    val finalIpos = if (apiUrl.contains("nepalipaisa.com")) {
                        // For NepaliPaisa API, we can trust the results are fairly complete for that page
                        newIpos
                    } else {
                        // For others, maybe filter or merge differently
                        newIpos
                    }

                    if (finalIpos.isNotEmpty()) {
                        ipoMasterDao.insertAll(finalIpos)
                        // If we got good data from one URL, we can still continue to other URLs 
                        // to fill in missing gaps, but for now we'll mark this URL as successful
                        hasAtLeastOneSuccess = true
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("IpoSync", "Failed to sync from $apiUrl", e)
            }
        }
        return if (hasAtLeastOneSuccess) Result.success(Unit) else Result.failure(Exception("Could not fetch IPO listing from any configured sources."))
    }

    private suspend fun syncFromIpoResultMappingSource(): Result<Unit> {
        return Result.success(Unit)
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

    private fun getSafeString(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = obj.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun cleanDate(dateStr: String?, skipBsConversion: Boolean = false): String? {
        if (dateStr.isNullOrBlank() || dateStr == "null") return null
        
        val trimmed = dateStr.trim()

        if (!skipBsConversion) {
            // Handle BS (Bikram Sambat) conversion if suffix present or year > 2070
            val bsPart = trimmed.removeSuffix("BS").trim().replace(":", "-").replace("/", "-")
            val yearMatch = Regex("""^(\d{4})""").find(bsPart)
            val possibleBsYear = yearMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            
            if (trimmed.endsWith("BS", ignoreCase = true) || possibleBsYear in 2070..2100) {
                val converted = com.example.data.util.NepDateUtils.bsToAd(bsPart)
                if (converted != null) return converted
            }
        }
        
        // Handle ISO with Time: 2024-06-27T00:00:00
        val tIndex = trimmed.indexOf('T')
        val cleanTrimmed = if (tIndex != -1) trimmed.substring(0, tIndex) else trimmed
        
        // Match Long timestamp (10 or 13 digits)
        if (cleanTrimmed.all { it.isDigit() } && (cleanTrimmed.length == 10 || cleanTrimmed.length == 13)) {
            try {
                val ms = if (cleanTrimmed.length == 10) cleanTrimmed.toLong() * 1000 else cleanTrimmed.toLong()
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
            } catch (e: Exception) {}
        }

        // Match YYYY-MM-DD pattern (Standard ISO)
        val isoMatch = Regex("""(\d{4}-\d{2}-\d{2})""").find(cleanTrimmed)
        if (isoMatch != null) return isoMatch.groupValues[1]
        
        // Match YYYY/MM/DD pattern
        val slashMatch = Regex("""(\d{4}/\d{2}/\d{2})""").find(cleanTrimmed)
        if (slashMatch != null) return slashMatch.groupValues[1].replace("/", "-")

        // Match DD/MM/YYYY or DD-MM-YYYY
        val reverseMatch = Regex("""(\d{1,2})[/-](\d{1,2})[/-](\d{4})""").find(cleanTrimmed)
        if (reverseMatch != null) {
            val d = reverseMatch.groupValues[1].padStart(2, '0')
            val m = reverseMatch.groupValues[2].padStart(2, '0')
            val y = reverseMatch.groupValues[3]
            return "$y-$m-$d"
        }
        
        // If it looks like a month name, try simple parsing
        if (cleanTrimmed.any { it.isLetter() }) {
            try {
                // Try common formats
                val formats = listOf("yyyy MMMM dd", "MMMM dd, yyyy", "dd MMM yyyy", "yyyy-MMM-dd", "MMM dd, yyyy")
                for (fmt in formats) {
                    try {
                        val sdf = SimpleDateFormat(fmt, Locale.US)
                        sdf.isLenient = false
                        val date = sdf.parse(cleanTrimmed)
                        if (date != null) return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }

        return cleanTrimmed
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
        
        var foundId: Int? = null
        for (id in candidates) {
            try {
                val result = checkIpoResultInternal(id, defaultBoid)
                result.onSuccess {
                    // CDSC response for result check contains company name in the message:
                    // "You have not been allotted shares of [Company Name]"
                    if (it.message.contains(companyName, ignoreCase = true) || 
                        it.message.contains(companyName.split(" ").first(), ignoreCase = true)) {
                        AppLogger.i("IpoDiscovery", "SUCCESS: Found matching ID $id for $companyName. Msg: ${it.message}")
                        foundId = id
                        return@onSuccess
                    } else {
                        AppLogger.i("IpoDiscovery", "Checking ID $id: Wrong company ($it.message)", throttle = true)
                    }
                }.onFailure {
                    AppLogger.w("IpoDiscovery", "Checking ID $id: Request failed (${it.message})", throttle = true)
                    if (it.message?.contains("Rejected") == true || it.message?.contains("403") == true) {
                        AppLogger.e("IpoDiscovery", "Sequential discovery aborted: CDSC WAF Block detected.")
                        return@withContext null
                    }
                }
                
                if (foundId != null) return@withContext foundId
            } catch (e: Exception) {
                AppLogger.e("IpoDiscovery", "Error checking candidate $id", e)
            }
            
            // Respectful delay to avoid WAF blocking (3 seconds per request)
            delay(3000)
        }
        
        AppLogger.w("IpoDiscovery", "Sequential discovery finished: No matching ID found for $companyName in range $startRange-$endRange")
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
            com.example.data.model.ScraperDefaults.defaultScrapersByCategory[category] ?: emptyList()
        }
    }
}
