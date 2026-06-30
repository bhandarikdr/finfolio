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
        Holdings::class,
        DpMaster::class,
        IpoMemberActivity::class
    ],
    version = 24,
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
                .addMigrations(MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24)
                .fallbackToDestructiveMigration(false)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `DpMaster` ADD COLUMN `address` TEXT")
                database.execSQL("ALTER TABLE `DpMaster` ADD COLUMN `telephone` TEXT")
            }
        }

        private val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ipo_member_activity` (
                        `companyName` TEXT NOT NULL, 
                        `boid` TEXT NOT NULL, 
                        `applyStatus` TEXT NOT NULL DEFAULT 'PENDING', 
                        `applyMessage` TEXT, 
                        `appliedAt` INTEGER NOT NULL DEFAULT 0, 
                        `allotmentStatus` TEXT NOT NULL DEFAULT 'NOT_CHECKED', 
                        `allotmentUnits` INTEGER NOT NULL DEFAULT 0, 
                        `allotmentMessage` TEXT, 
                        `checkedAt` INTEGER NOT NULL DEFAULT 0, 
                        PRIMARY KEY(`companyName`, `boid`),
                        FOREIGN KEY(`boid`) REFERENCES `Boids`(`boid`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_ipo_member_activity_boid` ON `ipo_member_activity` (`boid`)")
            }
        }

        private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `isEnabledForCheck` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `isEnabledForApply` INTEGER NOT NULL DEFAULT 1")
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

        private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create DpMaster table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `DpMaster` (
                        `dpCode` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `clientId` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`dpCode`)
                    )
                """.trimIndent())

                // 2. Update Boids table (Add new columns)
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `isEnabledForBulk` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `msUsername` TEXT")
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `msPassword` TEXT")
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `msPin` TEXT")
                database.execSQL("ALTER TABLE `Boids` ADD COLUMN `msCrn` TEXT")
            }
        }
    }
}
