package com.example.data.api

import com.example.data.model.IpoCompany
import com.example.data.model.IpoCheckPayload
import com.example.data.model.IpoResultResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Headers

interface CdsIpoApi {
    @GET("result/company/list")
    @Headers("Accept: application/json, text/plain, */*")
    suspend fun getCompanyList(): List<IpoCompany>

    @POST("result/ipo/result")
    @Headers("Accept: application/json, text/plain, */*")
    suspend fun checkResult(@Body payload: IpoCheckPayload): IpoResultResponse
}
