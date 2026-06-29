package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "UserProfile")
data class UserEntity(
    @PrimaryKey val id: Int = 0, // Singleton row
    val name: String,
    val email: String,
    val currencySymbol: String = "रु.",
    val dateFormat: String = "AD", // Placeholder for BS/AD
    val visibleIndicesJson: String = "", // JSON array of visible index names
    val scraperUrlsJson: String = "", // JSON map of scraper keys to URLs
    val pin: String? = null, // Optional 4-digit PIN lock
    val itemColumnsJson: String = "", // JSON array of visible item columns
    val sectorColumnsJson: String = "", // JSON array of visible sector columns
    val selectedSectorFilter: String = "All", // Last selected sector in Matrix
    val dashboardScope: String = "OVERALL", // Last selected scope for Dashboard
    val matrixScope: String = "OVERALL", // Last selected scope for Matrix
    val primaryIndexName: String = "NEPSE Index", // The main index name to track
    val commissionRate: Double = 0.0038, // Default commission rate
    val flatFee: Double = 25.0, // Default flat fee (e.g., DP fee)
    val cgtRate: Double = 0.075, // Default capital gains tax rate
    val boid: String? = null // Primary User's BOID for transaction matching
)
