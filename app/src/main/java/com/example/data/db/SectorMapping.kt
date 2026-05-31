package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SectorMapping")
data class SectorMapping(
    @PrimaryKey val symbol: String,
    val sector: String
)
