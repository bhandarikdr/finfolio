package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {

    // --- TRANSACTION RECORDS ---
    @Query("SELECT * FROM Data ORDER BY date DESC, id DESC")
    fun getAllTransactions(): Flow<List<TransactionRecord>>

    @Query("SELECT * FROM Data ORDER BY date DESC, id DESC")
    suspend fun getAllTransactionsSync(): List<TransactionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(record: TransactionRecord): Long

    @Update
    suspend fun updateTransaction(record: TransactionRecord)

    @Delete
    suspend fun deleteTransaction(record: TransactionRecord)

    @Query("DELETE FROM Data")
    suspend fun clearAllTransactions()

    @Query("SELECT DISTINCT item FROM Data ORDER BY item ASC")
    fun getDistinctItems(): Flow<List<String>>

    @Query("SELECT DISTINCT type FROM Data ORDER BY type ASC")
    fun getDistinctTypes(): Flow<List<String>>

    @Query("SELECT type FROM Data WHERE item = :symbol LIMIT 1")
    suspend fun getExistingTypeBySymbol(symbol: String): String?

    @Query("UPDATE Data SET type = :newType WHERE item = :symbol")
    suspend fun updateSectorBySymbol(symbol: String, newType: String)


    // --- EXTERNAL LTP RECORDS ---
    @Query("SELECT * FROM ExternalLtp")
    fun getAllExternalLtps(): Flow<List<ExternalLtp>>

    @Query("SELECT * FROM ExternalLtp WHERE symbol = :symbol LIMIT 1")
    suspend fun getExternalLtpBySymbol(symbol: String): ExternalLtp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalLtp(ltp: ExternalLtp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExternalLtps(ltps: List<ExternalLtp>)

    @Query("DELETE FROM ExternalLtp WHERE source = :source")
    suspend fun deleteExternalLtpBySource(source: String)

    @Query("UPDATE ExternalLtp SET isInMeroshareCsv = 0")
    suspend fun resetMeroshareCsvFlag()

    // --- USER PROFILE ---
    @Query("SELECT * FROM UserProfile WHERE id = 0 LIMIT 1")
    fun getUserProfile(): Flow<UserEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserProfile(user: UserEntity)

    // --- SECTOR MAPPINGS ---
    @Query("SELECT * FROM SectorMapping")
    suspend fun getAllSectorMappings(): List<SectorMapping>

    @Query("SELECT sector FROM SectorMapping WHERE symbol = :symbol LIMIT 1")
    suspend fun getSectorBySymbol(symbol: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSectorMappings(mappings: List<SectorMapping>)

    // --- SCRIP MASTER & WISHLIST ---
    @Query("SELECT * FROM ScripMaster ORDER BY symbol ASC")
    fun getAllScripMaster(): Flow<List<ScripMaster>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScripMaster(scrips: List<ScripMaster>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScripMasterSingle(scrip: ScripMaster)

    @Query("UPDATE ScripMaster SET isWishlisted = :isWishlisted WHERE symbol = :symbol")
    suspend fun updateWishlistStatus(symbol: String, isWishlisted: Boolean)

    @Query("SELECT * FROM ScripMaster WHERE isWishlisted = 1")
    fun getWishlistedScrips(): Flow<List<ScripMaster>>

    @Query("SELECT sector FROM ScripMaster WHERE symbol = :symbol LIMIT 1")
    suspend fun getSectorFromMaster(symbol: String): String?

    @Query("SELECT DISTINCT sector FROM ScripMaster ORDER BY sector ASC")
    fun getDistinctSectorsFromMaster(): Flow<List<String>>

    // --- MARKET INDICES PERSISTENCE ---
    @Query("SELECT * FROM MarketIndices")
    fun getAllMarketIndices(): Flow<List<MarketIndexEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarketIndices(indices: List<MarketIndexEntity>)

    @Query("SELECT * FROM MarketIndices WHERE indexName = :name LIMIT 1")
    suspend fun getIndexByName(name: String): MarketIndexEntity?

    // --- BOIDS ---
    @Query("SELECT * FROM Boids")
    fun getAllBoids(): Flow<List<BoidEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoid(boid: BoidEntity)

    @Delete
    fun deleteBoid(boid: BoidEntity)

    // --- IPO MASTER ---
    @Query("SELECT * FROM ipo_master WHERE isActive = 1 ORDER BY companyName ASC")
    fun getAllIpos(): Flow<List<IpoMaster>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoMaster(ipo: IpoMaster)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoMasters(ipos: List<IpoMaster>)

    @Query("SELECT * FROM ipo_master WHERE cdscCompanyId = :cdscId LIMIT 1")
    suspend fun getIpoByCdscId(cdscId: Int): IpoMaster?

    @Query("UPDATE ipo_master SET status = :status, updatedAt = :timestamp WHERE cdscCompanyId = :cdscId")
    suspend fun updateIpoStatus(cdscId: Int, status: String, timestamp: Long = System.currentTimeMillis())

    // --- IPO RESULT CACHE ---
    @Query("SELECT * FROM ipo_result_cache WHERE ipoId = :ipoId AND boid = :boid LIMIT 1")
    suspend fun getIpoResult(ipoId: Int, boid: String): IpoResultCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIpoResult(result: IpoResultCache)

    @Query("DELETE FROM ipo_result_cache WHERE checkedAt < :timestamp")
    suspend fun clearOldCache(timestamp: Long)
}
