package com.example.data.repository

import com.example.data.db.BoidEntity
import com.example.data.db.IpoMaster
import com.example.data.db.IpoResultCache
import com.example.data.db.PortfolioDao
import com.example.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IpoRepository(private val portfolioDao: PortfolioDao) {

    val allBoids: Flow<List<BoidEntry>> = portfolioDao.getAllBoids().map { list ->
        list.map { BoidEntry(name = it.name, boid = it.boid) }
    }

    val ipoMasterList: Flow<List<IpoMaster>> = portfolioDao.getAllIpos()

    suspend fun addBoid(name: String, boid: String) {
        portfolioDao.insertBoid(BoidEntity(boid, name))
    }

    suspend fun removeBoid(boid: String) {
        portfolioDao.deleteBoid(BoidEntity(boid, ""))
    }

    suspend fun syncIpos(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://iporesult.cdsc.com.np/result/company/list")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONArray(text)
                val newIpos = mutableListOf<IpoMaster>()
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val cdscId = obj.getInt("id")
                    val name = obj.getString("name")
                    val scrip = if (obj.has("scrip") && !obj.isNull("scrip")) obj.getString("scrip") else null
                    
                    val existing = portfolioDao.getIpoByCdscId(cdscId)
                    if (existing == null) {
                        newIpos.add(IpoMaster(
                            companyName = name,
                            companyCode = scrip,
                            cdscCompanyId = cdscId,
                            status = "Allotted" // Defaulting to Allotted as these are from result portal
                        ))
                    } else if (existing.companyName != name || existing.companyCode != scrip) {
                        portfolioDao.insertIpoMaster(existing.copy(
                            companyName = name,
                            companyCode = scrip,
                            updatedAt = System.currentTimeMillis()
                        ))
                    }
                }
                if (newIpos.isNotEmpty()) {
                    portfolioDao.insertIpoMasters(newIpos)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("CDSC Server returned ${conn.responseCode}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkIpoResult(cdscCompanyId: Int, boid: String): Result<IpoResultResponse> = withContext(Dispatchers.IO) {
        // Check cache first (24h validity)
        val cached = portfolioDao.getIpoResult(cdscCompanyId, boid)
        if (cached != null && (System.currentTimeMillis() - cached.checkedAt) < 24 * 60 * 60 * 1000) {
            return@withContext Result.success(IpoResultResponse(
                success = cached.result == "Allotted" || cached.result == "Applied",
                message = cached.message ?: cached.result,
                status = cached.result
            ))
        }

        try {
            val url = URL("https://iporesult.cdsc.com.np/result/ipo/result")
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
                portfolioDao.insertIpoResult(IpoResultCache(
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
}
