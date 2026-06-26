package com.example.data.db

import androidx.room.Entity

@Entity(tableName = "ipo_result_cache", primaryKeys = ["ipoId", "boid"])
data class IpoResultCache(
    val ipoId: Int, // resultPortalId
    val boid: String,
    val result: String, // e.g. "Allotted", "Not Allotted"
    val units: Int,
    val message: String? = null,
    val checkedAt: Long = System.currentTimeMillis()
)
