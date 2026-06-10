package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ipo_master")
data class IpoMaster(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val companyName: String,
    val companyCode: String? = null,
    val issueType: String? = null,
    val issueManager: String? = null,
    val openingDate: Long? = null,
    val closingDate: Long? = null,
    val allotmentDate: Long? = null,
    val status: String,
    val cdscCompanyId: Int, // id from CDSC API
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)
