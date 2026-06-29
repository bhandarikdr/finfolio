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
    val name: String,
    val boid: String,
    val isDefault: Boolean = false,
    val isEnabledForCheck: Boolean = true,
    val isEnabledForApply: Boolean = true,
    val isEnabledForBulk: Boolean = true, // Legacy
    // MeroShare Credentials (encrypted in local DB)
    val msUsername: String? = null,
    val msPassword: String? = null,
    val msPin: String? = null,
    val msCrn: String? = null
)

data class BulkIpoResult(
    val boidEntry: BoidEntry,
    val result: IpoResultResponse? = null,
    val isChecking: Boolean = false,
    val error: String? = null
)
