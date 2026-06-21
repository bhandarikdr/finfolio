package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IpoMasterDao {

    @Query("SELECT * FROM ipo_master WHERE isActive = 1 ORDER BY openingDate DESC, companyName ASC")
    fun getAllIPOs(): Flow<List<IpoMaster>>

    @Query("SELECT * FROM ipo_master WHERE resultAvailable = 1 AND isActive = 1 ORDER BY openingDate DESC")
    fun getIPOsWithResults(): Flow<List<IpoMaster>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ipo: IpoMaster)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ipos: List<IpoMaster>)

    @Update
    suspend fun update(ipo: IpoMaster)

    @Query("SELECT * FROM ipo_master WHERE cdscCompanyId = :cdscId LIMIT 1")
    suspend fun getIpoByCdscId(cdscId: Int): IpoMaster?

    @Query("SELECT * FROM ipo_master WHERE companyCode = :symbol LIMIT 1")
    suspend fun getBySymbol(symbol: String): IpoMaster?

    @Query("SELECT * FROM ipo_master WHERE companyName = :name LIMIT 1")
    suspend fun getByName(name: String): IpoMaster?

    @Query("UPDATE ipo_master SET status = :status, updatedAt = :timestamp WHERE cdscCompanyId = :cdscId")
    suspend fun updateIpoStatus(cdscId: Int, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM ipo_master")
    suspend fun deleteAll()

    // --- IPO RESULT CACHE ---
    @Query("SELECT * FROM ipo_result_cache WHERE ipoId = :ipoId AND boid = :boid LIMIT 1")
    suspend fun getIpoResult(ipoId: Int, boid: String): IpoResultCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoResult(result: IpoResultCache)

    @Query("DELETE FROM ipo_result_cache WHERE checkedAt < :timestamp")
    suspend fun clearOldCache(timestamp: Long)

    @Query("DELETE FROM ipo_result_cache")
    suspend fun clearResultCache()
}
