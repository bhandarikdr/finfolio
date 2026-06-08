package com.example.data.repository

import com.example.data.api.CdsIpoApi
import com.example.data.model.IpoCheckPayload
import com.example.data.model.IpoCompany
import com.example.data.model.IpoResultResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class IpoRepository {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://iporesult.cdsc.com.np/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(CdsIpoApi::class.java)

    suspend fun getCompanyList(): Result<List<IpoCompany>> {
        return try {
            Result.success(api.getCompanyList())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkResult(boid: String, companyId: Int): Result<IpoResultResponse> {
        return try {
            Result.success(api.checkResult(IpoCheckPayload(boid, companyId)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
