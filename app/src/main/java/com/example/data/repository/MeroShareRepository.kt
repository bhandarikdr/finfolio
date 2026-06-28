package com.example.data.repository

import com.example.data.db.PortfolioDao
import com.example.data.model.BoidEntry
import com.example.data.model.IpoResultResponse
import com.example.data.util.AppLogger
import com.example.data.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for interacting with the private MeroShare API.
 * Handles authentication, fetching application reports, and bulk submissions.
 */
class MeroShareRepository(private val portfolioDao: PortfolioDao) {

    private val client = NetworkUtils.getUnsafeOkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun getApiUrl(): String {
        return withContext(Dispatchers.IO) {
            val existing = portfolioDao.getUserProfileSync()
            if (!existing?.scraperUrlsJson.isNullOrBlank()) {
                try {
                    val json = JSONObject(existing!!.scraperUrlsJson)
                    if (json.has(com.example.data.model.ScraperCategory.MEROSHARE_API.name)) {
                        val arr = json.getJSONArray(com.example.data.model.ScraperCategory.MEROSHARE_API.name)
                        if (arr.length() > 0) return@withContext arr.getString(0)
                    }
                } catch (e: Exception) {}
            }
            com.example.data.model.ScraperDefaults.defaultScrapersByCategory[com.example.data.model.ScraperCategory.MEROSHARE_API]!!.first()
        }
    }

    /**
     * Authenticates with MeroShare and returns the JWT Authorization token.
     */
    suspend fun login(boid: String, username: String, password: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getApiUrl()
            // First 8 digits of BOID contain DP code
            val dpCode = boid.take(8).takeLast(5)
            val dp = portfolioDao.getDpByCode(dpCode) 
                ?: return@withContext Result.failure(Exception("DP Mapping Missing for $dpCode. Please Sync DP Master in Settings."))
            
            val payload = JSONObject().apply {
                put("clientId", dp.clientId)
                put("username", username)
                put("password", password)
            }

            AppLogger.d("MeroShare", "Attempting login for $username at DP ${dp.name} (${dp.clientId})")

            val request = Request.Builder()
                .url("${baseUrl}/auth/")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val token = response.header("Authorization")
                    if (!token.isNullOrBlank()) {
                        return@withContext Result.success(token)
                    } else {
                        return@withContext Result.failure(Exception("Login successful but no Authorization token received"))
                    }
                } else {
                    val msg = try { JSONObject(body).getString("message") } catch (e: Exception) { "HTTP ${response.code}: ${response.message}" }
                    AppLogger.e("MeroShare", "Login failed for $username: $msg")
                    return@withContext Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("MeroShare", "Login exception for $username", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches all recent application reports and returns the status of a specific company.
     */
    suspend fun checkResult(authToken: String, companyName: String): IpoResultResponse? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getApiUrl()
            val payload = JSONObject().apply {
                put("filterFieldParams", JSONArray().apply {
                    put(JSONObject().apply {
                        put("key", "companyShare.companyIssue.companyISIN.company.name")
                        put("alias", "Company Name")
                    })
                })
                put("page", 1)
                put("size", 20)
                put("searchRoleViewConstants", "VIEW_APPLICANT_FORM_COMPLETE")
            }

            val request = Request.Builder()
                .url("${baseUrl}/applicantForm/active/search/")
                .header("Authorization", authToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val objects = json.getJSONArray("object")
                    
                    for (i in 0 until objects.length()) {
                        val item = objects.getJSONObject(i)
                        val name = item.getString("companyName")
                        
                        if (name.contains(companyName, ignoreCase = true)) {
                            val appId = item.getLong("applicantFormId")
                            return@withContext fetchAllotmentDetail(authToken, appId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("MeroShare", "Result check failed for $companyName", e)
        }
        null
    }

    private suspend fun fetchAllotmentDetail(authToken: String, appId: Long): IpoResultResponse? {
        val baseUrl = getApiUrl()
        val request = Request.Builder()
            .url("${baseUrl}/applicantForm/report/detail/$appId")
            .header("Authorization", authToken)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val status = json.optString("statusName")
                val message = json.optString("statusDescription")
                
                return IpoResultResponse(
                    success = status == "Allotted",
                    message = if (status == "Allotted") "Congratulations! Allotted." else message,
                    status = status
                )
            }
        }
        return null
    }

    /**
     * Applies for an IPO for the authenticated user.
     */
    suspend fun applyForIpo(
        authToken: String, 
        boid: String,
        crn: String,
        pin: String,
        companyShareId: Int,
        units: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = getApiUrl()
            // 1. Get Bank Info
            val bankInfo = getBankInfo(authToken) ?: return@withContext Result.failure(Exception("Bank info not found"))
            
            // 2. Get Branch Details
            val branchDetail = getBranchDetails(authToken, bankInfo.getInt("id")) ?: return@withContext Result.failure(Exception("Branch detail failed"))
            
            // 3. Build Payload
            val payload = JSONObject().apply {
                put("demat", boid) // For apply, CDSC often expects 16-digit demat as demat field
                put("boid", boid.takeLast(8)) // Internal boid is last 8 digits
                put("accountNumber", branchDetail.getString("accountNumber"))
                put("customerId", branchDetail.getLong("id"))
                put("accountBranchId", branchDetail.getLong("accountBranchId"))
                put("accountTypeId", branchDetail.getLong("accountTypeId"))
                put("appliedKitta", units.toString())
                put("crnNumber", crn)
                put("transactionPIN", pin)
                put("companyShareId", companyShareId.toString())
                put("bankId", bankInfo.getInt("id"))
            }

            val request = Request.Builder()
                .url("${baseUrl}/applicantForm/share/apply")
                .header("Authorization", authToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Applied Successfully")
                } else {
                    val msg = try { JSONObject(body).getString("message") } catch (e: Exception) { "Apply failed: ${response.code}" }
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getBankInfo(authToken: String): JSONObject? {
        val baseUrl = getApiUrl()
        val request = Request.Builder()
            .url("${baseUrl}/bank/")
            .header("Authorization", authToken)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val arr = JSONArray(response.body?.string() ?: "[]")
                if (arr.length() > 0) return arr.getJSONObject(0)
            }
        }
        return null
    }

    private suspend fun getBranchDetails(authToken: String, bankId: Int): JSONObject? {
        val baseUrl = getApiUrl()
        val request = Request.Builder()
            .url("${baseUrl}/bank/$bankId")
            .header("Authorization", authToken)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val arr = JSONArray(response.body?.string() ?: "[]")
                if (arr.length() > 0) return arr.getJSONObject(0)
            }
        }
        return null
    }
}
