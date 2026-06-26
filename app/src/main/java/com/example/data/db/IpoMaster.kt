package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ipo_master")
data class IpoMaster(
    @PrimaryKey val companyName: String,
    val shareType: String? = null,
    val units: String? = null,
    val openingDate: String? = null,
    val closingDate: String? = null,
    val issueManager: String? = null,
    val status: String,
    val scrip: String? = null,
    val resultPortalId: Int? = null,
    val allotmentDate: String? = null, // Format: yyyy-MM-dd
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
