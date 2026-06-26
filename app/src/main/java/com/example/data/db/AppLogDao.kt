package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(log: AppLog)

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 2000")
    fun getAllLogs(): Flow<List<AppLog>>

    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 2000")
    suspend fun getAllLogsSync(): List<AppLog>

    @Query("DELETE FROM app_logs")
    suspend fun clearAll()

    @Query("DELETE FROM app_logs WHERE timestamp < :expiry")
    suspend fun deleteOldLogs(expiry: Long)

    @Query("DELETE FROM app_logs WHERE timestamp < :expiry AND (:level IS NULL OR level = :level)")
    suspend fun deleteLogsOlderThan(expiry: Long, level: String?)
}
