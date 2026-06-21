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

        // 2. Sync from Nepali Paisa (Investment Calendar)
        try {
            val npResult = syncFromNepaliPaisa()
            if (npResult.isSuccess) {
                successCount++
                results.add("Nepali Paisa synced")
            }
        } catch (e: Exception) {
            Log.e("IpoRepository", "Nepali Paisa Sync Failed", e)
        }

        // 3. Sync from SEBON Pipeline
        try {
            val sebonResult = syncFromSebonPipeline()
            if (sebonResult.isSuccess) {
                successCount++
                results.add("SEBON synced")
            }
        } catch (e: Exception) {
            Log.e("IpoRepository", "SEBON Sync Failed", e)
        }

        if (successCount > 0) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to sync from all sources"))
        }
    }

    private suspend fun syncFromSebonPipeline(): Result<Unit> {
        return try {
            val sebonUrl = getScraperUrl("IPO_PIPELINE")
            val doc = Jsoup.connect(sebonUrl)
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
        val cdscBase = getScraperUrl("CDSC_COMPANY_LIST")
        val url = URL(cdscBase)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        
        // Dynamic referer/origin extraction
        val uri = java.net.URI(cdscBase)
        val host = "${uri.scheme}://${uri.host}"
        conn.setRequestProperty("Referer", "$host/")
        conn.setRequestProperty("Origin", host)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        
        return try {
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
                if (newIpos.isNotEmpty()) {
                    ipoMasterDao.insertAll(newIpos)
                }
                Result.success(Unit)
            } else {
                Log.e("IpoRepository", "CDSC Error: ${conn.responseCode}")
                Result.failure(Exception("CDSC returned ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Log.e("IpoRepository", "CDSC Network Error: ${e.message}")
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun syncFromNepaliPaisa(): Result<Unit> {
        return try {
            // nepalipaisa.com uses a complex layout, let's try a broader search for company names
            val npUrl = getScraperUrl("NEPALI_PAISA_IPO")
            val doc = Jsoup.connect(npUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()
            
            val newIpos = mutableListOf<IpoMaster>()
            
            // Try to find tables or lists that contain IPO info
            val elements = doc.select("tr, .ipo-item, .calendar-item")
            
            for (element in elements) {
                val text = element.text()
                // Simple heuristic: if it contains "IPO" and some typical company name markers
                if (text.contains("IPO", ignoreCase = true) && text.length > 10) {
                    val companyName = element.select("td, h4, .title").firstOrNull()?.text()?.trim()
                    
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
            }
            
            // Try specific table if above is too broad
            val rows = doc.select("table tbody tr")
            for (row in rows) {
                val cols = row.select("td")
                if (cols.size >= 2) {
                    val companyName = cols[0].text().trim()
                    if (companyName.isNotBlank() && companyName.length > 2) {
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
            }

            if (newIpos.isNotEmpty()) {
                ipoMasterDao.insertAll(newIpos.distinctBy { it.companyName })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("IpoRepository", "Nepali Paisa Sync Failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun checkIpoResult(cdscCompanyId: Int, boid: String): Result<IpoResultResponse> = withContext(Dispatchers.IO) {
        // Check cache first (24h validity)
        val cached = ipoMasterDao.getIpoResult(cdscCompanyId, boid)
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < 24 * 60 * 60 * 1000) {
            return@withContext Result.success(IpoResultResponse(
                success = cached.result == "Allotted" || cached.result == "Applied",
                message = cached.message ?: cached.result,
                status = cached.result
            ))
        }

        try {
            val resultUrlStr = getScraperUrl("CDSC_RESULT_CHECK")
            val url = URL(resultUrlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
                
                // Cache result
                ipoMasterDao.insertIpoResult(IpoResultCache(
                    ipoId = cdscCompanyId,
                    boid = boid,
                    result = if (success) "Allotted" else "Not Allotted",
                    units = if (success) extractUnits(message) else 0,
                    message = message
                ))
                
                Result.success(result)
            } else {
                Result.failure(Exception("Server error ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractUnits(message: String): Int {
        // Example message: "Congratulations! You have been allotted 10 units."
        return try {
            val regex = """(\d+)\s+units""".toRegex()
            regex.find(message)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun bulkCheckIpoResults(cdscCompanyId: Int, boids: List<BoidEntry>, onProgress: (Int, Result<IpoResultResponse>) -> Unit) {
        coroutineScope {
            val batchSize = 20
            boids.chunked(batchSize).forEach { batch ->
                val jobs = batch.map { boidEntry ->
                    async {
                        val result = checkIpoResult(cdscCompanyId, boidEntry.boid)
                        val originalIndex = boids.indexOf(boidEntry)
                        onProgress(originalIndex, result)
                    }
                }
                jobs.awaitAll()
                if (boids.size > batchSize) delay(500) // Small delay between batches to be nice
            }
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
                "IPO_PIPELINE" -> "https://sebon.gov.np/ipo-pipeline"
                "CDSC_COMPANY_LIST" -> "https://iporesult.cdsc.com.np/result/company/list"
                "NEPALI_PAISA_IPO" -> "https://www.nepalipaisa.com/ipo"
                "CDSC_RESULT_CHECK" -> "https://iporesult.cdsc.com.np/result/ipo/result"
                else -> ""
            }
        }
    }
}
