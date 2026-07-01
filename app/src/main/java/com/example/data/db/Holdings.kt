package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pre-computed table for high-performance portfolio metrics.
 * Updated via Transaction Interceptors (Repositories) on every write.
 * Now contains valuation LTP and sync status as single source of truth for owned items.
 */
@Entity(tableName = "Holdings")
data class Holdings(
    @PrimaryKey val symbol: String,
    val sector: String = "Other", // User-defined or override sector
    val ltp: Double = 0.0,        // Price used for current valuation
    val prevLtp: Double = 0.0,    // Previous LTP for change calculation
    val source: String = "Scraped", // "Scraped", "PortfolioSync", or "CSV"
    val isInExternalSync: Boolean = false,
    val timestamp: Long = 0L,
    val totalBuyAmount: Double = 0.0,
    val totalSaleAmount: Double = 0.0,
    val returnsCash: Double = 0.0,
    val totalBuyQty: Double = 0.0,
    val totalSaleQty: Double = 0.0,
    val returnsQty: Double = 0.0,
    val lastTransactionDate: String? = null,
    val assetType: String = "TRADABLE"
)
