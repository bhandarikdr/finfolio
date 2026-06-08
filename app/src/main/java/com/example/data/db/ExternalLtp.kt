package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ExternalLtp")
data class ExternalLtp(
    @PrimaryKey val symbol: String, // Scrip / Item symbol
    val ltp: Double,
    val previousLtp: Double = 0.0,
    val source: String, // "Scraped" or "Meroshare"
    val timestamp: Long = System.currentTimeMillis(),
    val isInMeroshareCsv: Boolean = false
)
