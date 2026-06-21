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
    val pin: String? = null // Optional 4-digit PIN lock
)
