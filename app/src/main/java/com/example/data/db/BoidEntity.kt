package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Boids")
data class BoidEntity(
    @PrimaryKey val boid: String,
    val name: String
)
