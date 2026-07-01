package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ScripMaster")
data class ScripMaster(
    @PrimaryKey val symbol: String,
    val name: String,
    val sector: String, // Market sector
    val ltp: Double = 0.0,
    val previousLtp: Double = 0.0,
    val pointChange: Double = 0.0,
    val changePercent: Double = 0.0,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val isWishlisted: Boolean = false,
    val timestamp: Long = 0L
)
