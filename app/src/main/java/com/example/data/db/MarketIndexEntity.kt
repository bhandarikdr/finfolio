package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "MarketIndices")
data class MarketIndexEntity(
    @PrimaryKey val indexName: String,
    val currentValue: Double,
    val previousValue: Double,
    val pointChange: Double = 0.0,
    val changePercent: Double,
    val source: String = "Scraped",
    val timestamp: Long = System.currentTimeMillis()
)
