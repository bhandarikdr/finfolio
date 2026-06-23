package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IpoMasterDao {

    @Query("SELECT * FROM ipo_master ORDER BY updatedAt DESC")
    fun getAllIPOs(): Flow<List<IpoMaster>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ipo: IpoMaster)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ipos: List<IpoMaster>)

    @Update
    suspend fun update(ipo: IpoMaster)

    @Query("SELECT * FROM ipo_master WHERE companyName = :name LIMIT 1")
    suspend fun getByName(name: String): IpoMaster?

    @Query("SELECT * FROM ipo_master WHERE cdscCompanyId = :id LIMIT 1")
    suspend fun getByCdscId(id: Int): IpoMaster?

    @Query("DELETE FROM ipo_master")
    suspend fun deleteAll()

    // --- IPO RESULT CACHE ---
    // Note: IpoResultCache still uses ipoId (Int) which might need mapping if we use Nepali Paisa names
    @Query("SELECT * FROM ipo_result_cache WHERE ipoId = :ipoId AND boid = :boid LIMIT 1")
    suspend fun getIpoResult(ipoId: Int, boid: String): IpoResultCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoResult(result: IpoResultCache)

    @Query("DELETE FROM ipo_result_cache")
    suspend fun clearResultCache()
}
