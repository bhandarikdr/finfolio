package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Boids")
data class BoidEntity(
    @PrimaryKey val boid: String,
    val name: String,
    val isDefault: Boolean = false,
    val isEnabledForCheck: Boolean = true,
    val isEnabledForApply: Boolean = true,
    val isEnabledForBulk: Boolean = true,
    // MeroShare Credentials
    val msUsername: String? = null,
    val msPassword: String? = null,
    val msPin: String? = null,
    val msCrn: String? = null
)
