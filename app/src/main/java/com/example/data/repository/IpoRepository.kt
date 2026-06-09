package com.example.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BoidEntry(val name: String, val boid: String)
data class BulkIpoResult(val boidEntry: BoidEntry, val result: IpoResult? = null, val isChecking: Boolean = false, val error: String? = null)

class IpoRepository {

    suspend fun fetchIpoCompanies(): Result<List<IpoCompany>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://iporesult.cdsc.com.np/result/company/list")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONArray(text)
                    val list = mutableListOf<IpoCompany>()
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        list.add(IpoCompany(obj.getInt("id"), obj.getString("name"), obj.optString("scrip", "")))
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception("Rejected by CDSC Server (${conn.responseCode})"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun checkIpoResult(companyId: Int, boid: String): Result<IpoResult> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://iporesult.cdsc.com.np/result/ipo/result")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.doOutput = true
                
                val payload = JSONObject().apply {
                    put("companyShareId", companyId)
                    put("boid", boid)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(text)
                    Result.success(IpoResult(obj.getBoolean("success"), obj.getString("message")))
                } else {
                    Result.failure(Exception("Server error ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
