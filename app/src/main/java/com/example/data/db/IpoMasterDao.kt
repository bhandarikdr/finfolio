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

    @Query("SELECT * FROM ipo_master WHERE scrip = :scrip LIMIT 1")
    suspend fun getByScrip(scrip: String): IpoMaster?

    @Query("SELECT * FROM ipo_master WHERE resultPortalId = :id LIMIT 1")
    suspend fun getByResultPortalId(id: Int): IpoMaster?

    @Query("SELECT resultPortalId FROM ipo_master WHERE resultPortalId IS NOT NULL ORDER BY allotmentDate DESC, closingDate DESC LIMIT 1")
    suspend fun getLatestResultPortalId(): Int?

    @Query("SELECT COUNT(*) FROM ipo_master")
    suspend fun getIpoCount(): Int

    @Query("DELETE FROM ipo_master")
    suspend fun deleteAll()

    // --- IPO RESULT CACHE ---
    // Note: IpoResultCache still uses ipoId (Int) which might need mapping if we use external listing names
    @Query("SELECT * FROM ipo_result_cache WHERE ipoId = :ipoId AND boid = :boid LIMIT 1")
    suspend fun getIpoResult(ipoId: Int, boid: String): IpoResultCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoResult(result: IpoResultCache)

    @Query("DELETE FROM ipo_result_cache")
    suspend fun clearResultCache()

    // --- IPO MEMBER ACTIVITY ---
    @Query("SELECT * FROM ipo_member_activity WHERE companyName = :companyName AND boid = :boid LIMIT 1")
    suspend fun getActivity(companyName: String, boid: String): IpoMemberActivity?

    @Query("SELECT * FROM ipo_member_activity WHERE companyName = :companyName")
    fun getActivityForCompany(companyName: String): kotlinx.coroutines.flow.Flow<List<IpoMemberActivity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: IpoMemberActivity)

    @Query("UPDATE ipo_member_activity SET allotmentStatus = 'NOT_CHECKED', allotmentUnits = 0, allotmentMessage = NULL, checkedAt = 0 WHERE companyName = :companyName AND boid = :boid")
    suspend fun resetAllotmentStatus(companyName: String, boid: String)

    @Query("UPDATE ipo_member_activity SET applyStatus = :status, applyMessage = :message, appliedAt = :timestamp WHERE companyName = :companyName AND boid = :boid")
    suspend fun updateApplyStatus(companyName: String, boid: String, status: String, message: String?, timestamp: Long)
}
