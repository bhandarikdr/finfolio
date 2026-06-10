package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "MarketIndices")
data class MarketIndexEntity(
    @PrimaryKey val indexName: String,
    val currentValue: Double,
    val previousValue: Double,
    val changePercent: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)
