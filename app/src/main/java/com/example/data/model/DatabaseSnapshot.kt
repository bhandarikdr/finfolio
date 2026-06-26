package com.example.data.model

import com.example.data.db.*
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DatabaseSnapshot(
    val timestamp: Long,
    val appVersion: String,
    val transactions: List<TransactionRecord> = emptyList(),
    val holdings: List<Holdings> = emptyList(),
    val scripMaster: List<ScripMaster> = emptyList(),
    val boids: List<BoidEntity> = emptyList(),
    val userProfile: UserEntity? = null
)
