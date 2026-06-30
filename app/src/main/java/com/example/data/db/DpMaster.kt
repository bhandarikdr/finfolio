package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a Depository Participant (DP) in Nepal.
 * Used to map the first 8 digits of a BOID to the MeroShare clientId.
 */
@Entity(tableName = "DpMaster")
data class DpMaster(
    @PrimaryKey val dpCode: String, // e.g., "10600"
    val name: String,             // e.g., "NIMB ACE CAPITAL LIMITED"
    val clientId: Int,            // e.g., 173 (Required for MeroShare API login)
    val address: String? = null,
    val telephone: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
