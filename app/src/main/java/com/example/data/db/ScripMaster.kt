package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ScripMaster")
data class ScripMaster(
    @PrimaryKey val symbol: String,
    val name: String,
    val sector: String,
    val isWishlisted: Boolean = false
)
