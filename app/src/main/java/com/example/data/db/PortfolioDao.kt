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

    @Query("DELETE FROM ExternalLtp")
    suspend fun clearAllExternalLtps()

    @Query("DELETE FROM MarketIndices")
    suspend fun clearAllMarketIndices()

    @Query("DELETE FROM SectorMapping")
    suspend fun clearAllSectorMappings()

    @Query("DELETE FROM Boids")
    suspend fun clearAllBoids()

    @Query("SELECT item FROM Data GROUP BY item ORDER BY MAX(id) DESC LIMIT 15")
    fun getRecentItems(): Flow<List<String>>

    @Query("SELECT sector FROM Data GROUP BY sector ORDER BY MAX(id) DESC LIMIT 10")
    fun getRecentSectors(): Flow<List<String>>

    @Query("SELECT DISTINCT item FROM Data ORDER BY item ASC")
    fun getDistinctItems(): Flow<List<String>>

    @Query("SELECT DISTINCT sector FROM Data ORDER BY sector ASC")
    fun getDistinctSectors(): Flow<List<String>>

    @Query("SELECT sector FROM Data WHERE item = :symbol LIMIT 1")
    suspend fun getExistingSectorBySymbol(symbol: String): String?

    @Query("UPDATE Data SET sector = :newSector WHERE item = :symbol")
    suspend fun updateSectorBySymbol(symbol: String, newSector: String)


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

    @Query("UPDATE ExternalLtp SET isInExternalSync = 0")
    suspend fun resetExternalSyncFlag()

    // --- USER PROFILE ---
    @Query("SELECT * FROM UserProfile WHERE id = 0 LIMIT 1")
    fun getUserProfile(): Flow<UserEntity?>

    @Query("SELECT * FROM UserProfile WHERE id = 0 LIMIT 1")
    suspend fun getUserProfileSync(): UserEntity?

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

    @Query("DELETE FROM MarketIndices WHERE indexName = :name")
    suspend fun deleteMarketIndexByName(name: String)

    // --- BOIDS ---
    @Query("SELECT * FROM Boids")
    fun getAllBoids(): Flow<List<BoidEntity>>

    @Query("SELECT * FROM Boids")
    suspend fun getAllBoidsSync(): List<BoidEntity>

    @Query("SELECT * FROM Boids WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultBoidSync(): BoidEntity?

    @Query("UPDATE Boids SET isDefault = 0")
    suspend fun clearDefaultBoid()

    @Query("UPDATE Boids SET isDefault = 1 WHERE boid = :boid")
    suspend fun setDefaultBoid(boid: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoid(boid: BoidEntity)

    @Delete
    fun deleteBoid(boid: BoidEntity)

    // --- HOLDINGS (PRE-COMPUTED) ---
    @Query("SELECT * FROM Holdings ORDER BY symbol ASC")
    fun getAllHoldings(): Flow<List<Holdings>>

    @Query("SELECT * FROM Holdings WHERE symbol = :symbol LIMIT 1")
    suspend fun getHoldingsBySymbol(symbol: String): Holdings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHoldings(holdings: Holdings)

    @Query("DELETE FROM Holdings")
    suspend fun clearAllHoldings()
}
