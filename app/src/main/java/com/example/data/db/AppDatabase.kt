package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionRecord::class,
        ExternalLtp::class,
        UserEntity::class,
        SectorMapping::class,
        ScripMaster::class,
        MarketIndexEntity::class,
        BoidEntity::class,
        IpoMaster::class,
        IpoResultCache::class,
        AppLog::class,
        Holdings::class
    ],
    version = 20,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun portfolioDao(): PortfolioDao
    abstract fun ipoMasterDao(): IpoMasterDao
    abstract fun appLogDao(): AppLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "portfolio_database"
                )
                .addMigrations(MIGRATION_19_20)
                .fallbackToDestructiveMigration(false)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `Holdings` (
                        `symbol` TEXT NOT NULL, 
                        `sector` TEXT NOT NULL DEFAULT 'Other',
                        `totalBuyAmount` REAL NOT NULL DEFAULT 0.0, 
                        `totalSaleAmount` REAL NOT NULL DEFAULT 0.0, 
                        `returnsCash` REAL NOT NULL DEFAULT 0.0, 
                        `totalBuyQty` REAL NOT NULL DEFAULT 0.0, 
                        `totalSaleQty` REAL NOT NULL DEFAULT 0.0, 
                        `returnsQty` REAL NOT NULL DEFAULT 0.0, 
                        `lastTransactionDate` TEXT, 
                        `assetType` TEXT NOT NULL DEFAULT 'TRADABLE', 
                        PRIMARY KEY(`symbol`)
                    )
                """.trimIndent())
            }
        }
    }
}
