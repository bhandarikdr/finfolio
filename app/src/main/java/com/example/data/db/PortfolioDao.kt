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
}
