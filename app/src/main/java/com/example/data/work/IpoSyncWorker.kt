package com.example.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.repository.IpoRepository

class IpoSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val ipoRepo = IpoRepository(database.portfolioDao(), database.ipoMasterDao())
            
            val result = ipoRepo.syncIpos()
            
            if (result.isSuccess) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
