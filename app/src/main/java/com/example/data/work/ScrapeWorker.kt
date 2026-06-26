package com.example.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.repository.PortfolioRepository
import com.example.data.repository.MarketRepository

class ScrapeWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val portfolioRepo = PortfolioRepository(database.portfolioDao(), database.ipoMasterDao())
            val marketRepo = MarketRepository(database.portfolioDao())
            
            // 1. Refresh scrip LTPs (Standard mechanism)
            val successLtp = portfolioRepo.refreshLivePrices()
            
            // 2. Refresh Market Indices (For top bar and pulse screen)
            marketRepo.fetchMarketIndices(force = false)
            marketRepo.fetchPriceChanges(force = false)
            
            if (successLtp.isSuccess) {
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
