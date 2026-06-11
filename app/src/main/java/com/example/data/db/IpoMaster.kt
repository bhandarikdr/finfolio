package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ipo_master")
data class IpoMaster(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val companyName: String,
    val companyCode: String? = null,
    val issueType: String? = null, // IPO, FPO, Right, etc.
    val issueManager: String? = null,
    val openingDate: Long? = null,
    val closingDate: Long? = null,
    val allotmentDate: Long? = null,
    val status: String, // Pipeline, Active, Allotted, Closed
    val cdscCompanyId: Int? = null, // ID used for result checking
    val resultAvailable: Boolean = false,
    val source: String = "CDSC", // CDSC_PORTAL, CDSC_LIST, SEBON_PIPELINE
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)
