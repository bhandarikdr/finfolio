package com.example.data.model

data class IpoCompany(
    val id: Int,
    val name: String,
    val scrip: String
)

data class IpoCheckPayload(
    val boid: String,
    val companyShareId: Int
)

data class IpoResultResponse(
    val success: Boolean,
    val message: String,
    val status: String? = null
)

data class BoidEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val boid: String
)

data class BulkIpoResult(
    val boidEntry: BoidEntry,
    val result: IpoResultResponse? = null,
    val isChecking: Boolean = false,
    val error: String? = null
)
