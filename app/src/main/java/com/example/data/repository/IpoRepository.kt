package com.example.data.repository

import com.example.data.db.BoidEntity
import com.example.data.db.IpoMaster
import com.example.data.db.IpoMasterDao
import com.example.data.db.IpoResultCache
import com.example.data.db.PortfolioDao
import com.example.data.model.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Repository for handling IPO listings, result checking, and BOID management.
 * Leverages prioritized fallback URLs for all scraping operations.
 */
class IpoRepository(
    private val portfolioDao: PortfolioDao,
    private val ipoMasterDao: IpoMasterDao
) {

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

    suspend fun addIpo(ipo: IpoMaster) {
        ipoMasterDao.insert(ipo)
    }

    /**
     * Synchronizes the IPO master list from multiple sources (CDSC, SEBON, Nepali Paisa).
     * Uses prioritized URL lists for each category and continues even if some sources fail.
     */
    suspend fun syncIpos(): Result<Unit> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        var successCount = 0

        // 1. Sync from CDSC Result Portal (Primary for Bulk Checker)
        try {
            val cdscResult = syncFromCdscResultPortal()
            if (cdscResult.isSuccess) {
                successCount++
                results.add("CDSC Result Portal synced")
            }
        } catch (e: Exception) {
            Log.e("IpoRepository", "CDSC Sync Failed", e)
        }

        // 2. Sync from Fallback IPO List (Nepali Paisa / SEBON)
        try {
            val urls = getScraperUrls(ScraperCategory.IPO_LISTING)
            var ipoSynced = false
            for (url in urls) {
                val result = if (url.contains("nepalipaisa")) syncFromNepaliPaisa(url) else syncFromSebonPipeline(url)
                if (result.isSuccess) {
                    successCount++
                    ipoSynced = true
                    results.add("IPO Listing synced from $url")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("IpoRepository", "IPO Listing Sync Failed", e)
        }

        if (successCount > 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to sync from all sources"))
        }
    }

    private suspend fun syncFromSebonPipeline(url: String): Result<Unit> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(15000)
                .get()
            
            val rows = doc.select("table tbody tr")
            val newIpos = mutableListOf<IpoMaster>()
            
            for (row in rows) {
                val cols = row.select("td")
                if (cols.size >= 2) {
                    val name = cols[1].text().trim()
                    if (name.isNotBlank()) {
                        val existing = ipoMasterDao.getByName(name)
                        if (existing == null) {
                            newIpos.add(IpoMaster(
                                companyName = name,
                                status = "Pipeline",
                                source = "SEBON",
                                issueType = "IPO"
                            ))
                        }
                    }
                }
            }
            if (newIpos.isNotEmpty()) {
                ipoMasterDao.insertAll(newIpos)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("IpoRepository", "SEBON Sync Failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun syncFromCdscResultPortal(): Result<Unit> {
        val urls = getScraperUrls(ScraperCategory.CDSC_COMPANIES)
        var lastError: Exception? = null
        
        for (cdscBase in urls) {
            val conn = URL(cdscBase).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 10000
                
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONArray(text)
                    val newIpos = mutableListOf<IpoMaster>()
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        val cdscId = obj.getInt("id")
                        val name = obj.getString("name")
                        val scrip = if (obj.has("scrip") && !obj.isNull("scrip")) obj.getString("scrip") else null
                        
                        val existing = ipoMasterDao.getIpoByCdscId(cdscId)
                        if (existing == null) {
                            newIpos.add(IpoMaster(
                                companyName = name,
                                companyCode = scrip,
                                cdscCompanyId = cdscId,
                                status = "Allotted",
                                resultAvailable = true,
                                source = "CDSC_PORTAL"
                            ))
                        } else {
                            ipoMasterDao.update(existing.copy(
                                companyName = name,
                                companyCode = scrip,
                                resultAvailable = true,
                                updatedAt = System.currentTimeMillis()
                            ))
                        }
                    }
                    if (newIpos.isNotEmpty()) ipoMasterDao.insertAll(newIpos)
                    return Result.success(Unit)
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn.disconnect()
            }
        }
        return Result.failure(lastError ?: Exception("CDSC Sync failed"))
    }

    private suspend fun syncFromNepaliPaisa(url: String): Result<Unit> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .timeout(10000)
                .get()
            
            val newIpos = mutableListOf<IpoMaster>()
            val rows = doc.select("table tbody tr, .ipo-item")
            
            for (element in rows) {
                val companyName = element.select("td, .title, h4").firstOrNull()?.text()?.trim()
                if (!companyName.isNullOrBlank() && companyName.length > 3) {
                    val existing = ipoMasterDao.getByName(companyName) 
                    if (existing == null) {
                        newIpos.add(IpoMaster(
                            companyName = companyName,
                            status = "Active",
                            source = "NEPALI_PAISA",
                            issueType = "IPO"
                        ))
                    }
                }
            }
            if (newIpos.isNotEmpty()) ipoMasterDao.insertAll(newIpos.distinctBy { it.companyName })
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks the IPO allotment result for a specific BOID and company.
     * Tries multiple CDSC result endpoints in order of priority.
     * Caches successful results for 24 hours.
     */
    suspend fun checkIpoResult(cdscCompanyId: Int, boid: String): Result<IpoResultResponse> = withContext(Dispatchers.IO) {
        val cached = ipoMasterDao.getIpoResult(cdscCompanyId, boid)
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < 24 * 60 * 60 * 1000) {
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
                val url = URL(resultUrlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.doOutput = true
                
                val payload = JSONObject().apply {
                    put("companyShareId", cdscCompanyId)
                    put("boid", boid)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
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
                }
            } catch (e: Exception) {
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
                ScraperCategory.IPO_LISTING -> listOf("https://sebon.gov.np/ipo-pipeline", "https://www.nepalipaisa.com/ipo")
                ScraperCategory.CDSC_COMPANIES -> listOf("https://iporesult.cdsc.com.np/result/company/list")
                ScraperCategory.CDSC_RESULT -> listOf("https://iporesult.cdsc.com.np/result/ipo/result")
                else -> emptyList()
            }
        }
    }
}
