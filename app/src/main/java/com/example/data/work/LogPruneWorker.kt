package com.example.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.util.AppLogger

/**
 * Phase 4.2: Weekly Log Pruner.
 * Background WorkManager worker to clean up old LTP audit logs.
 */
class LogPruneWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val dao = database.appLogDao()
            
            // Rules:
            // 1. Keep LTP logs only for last 7 days
            // 2. Keep standard logs (INFO/ERROR) for 30 days
            val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)

            dao.deleteLogsOlderThan(sevenDaysAgo, "LTP")
            dao.deleteLogsOlderThan(thirtyDaysAgo, null) // null means all levels
            
            AppLogger.i("LogPrune", "Weekly log pruning completed successfully.")
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
