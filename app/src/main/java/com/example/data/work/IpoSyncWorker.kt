package com.example.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.db.AppDatabase
import com.example.data.db.IpoMaster
import org.jsoup.Jsoup
import java.util.UUID

class IpoSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val database = AppDatabase.getDatabase(applicationContext)
            val ipoDao = database.ipoMasterDao()

            // 1. Fetch from CDSC Result Portal (Current/Result available IPOs)
            // This is already partially handled by Repository.syncIpos() 
            // but we can integrate it here for a unified sync.
            
            // 2. Fetch from SEBON Pipeline (Future IPOs)
            syncSebonPipeline(ipoDao)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private suspend fun syncSebonPipeline(ipoDao: com.example.data.db.IpoMasterDao) {
        try {
            val doc = Jsoup.connect("https://sebon.gov.np/ipo-pipeline").get()
            val rows = doc.select("table tbody tr")
            
            val ipos = mutableListOf<IpoMaster>()
            for (row in rows) {
                val cols = row.select("td")
                if (cols.size >= 5) {
                    val companyName = cols[1].text().trim()
                    
                    val existing = ipoDao.getByName(companyName)
                    if (existing == null) {
                        ipos.add(IpoMaster(
                            id = UUID.randomUUID().toString(),
                            companyName = companyName,
                            status = "Pipeline",
                            source = "SEBON",
                            issueType = "IPO"
                        ))
                    }
                }
            }
            if (ipos.isNotEmpty()) {
                ipoDao.insertAll(ipos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
