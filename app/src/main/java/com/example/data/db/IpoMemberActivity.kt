package com.example.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ipo_member_activity",
    primaryKeys = ["companyName", "boid"],
    foreignKeys = [
        ForeignKey(
            entity = BoidEntity::class,
            parentColumns = ["boid"],
            childColumns = ["boid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["boid"])]
)
data class IpoMemberActivity(
    val companyName: String,
    val boid: String,
    
    // Application Tracking
    val applyStatus: String = "PENDING", // PENDING, APPLIED, FAILED
    val applyMessage: String? = null,
    val appliedAt: Long = 0,
    
    // Allotment Tracking
    val allotmentStatus: String = "NOT_CHECKED", // NOT_CHECKED, ALLOTTED, NOT_ALLOTTED, ERROR
    val allotmentUnits: Int = 0,
    val allotmentMessage: String? = null,
    val checkedAt: Long = 0,
    val isRecorded: Boolean = false
)
