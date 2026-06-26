package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pre-computed table for high-performance portfolio metrics.
 * Updated via Transaction Interceptors (Repositories) on every write.
 */
@Entity(tableName = "Holdings")
data class Holdings(
    @PrimaryKey val symbol: String,
    val sector: String = "Other",
    val totalBuyAmount: Double = 0.0,
    val totalSaleAmount: Double = 0.0,
    val returnsCash: Double = 0.0,
    val totalBuyQty: Double = 0.0,
    val totalSaleQty: Double = 0.0,
    val returnsQty: Double = 0.0,
    val lastTransactionDate: String? = null,
    val assetType: String = "TRADABLE"
)
