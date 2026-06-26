package com.example.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "Data",
    indices = [
        Index(value = ["item"]),
        Index(value = ["sector"])
    ]
)
data class TransactionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String, // Format: YYYY-MM-DD
    val item: String, // Shares scrip name (e.g., AAPL)
    val sector: String, // Sector / Category (e.g., Banks, Hydro)
    val action: String, // "Buy", "Sale", "Returns"
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val isSystemAdjustment: Boolean = false
)
