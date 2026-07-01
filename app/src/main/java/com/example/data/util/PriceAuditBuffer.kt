package com.example.data.util

import com.example.data.db.AppLog
import com.example.data.db.AppLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CachedPrice(
    val symbol: String,
    val ltp: Double,
    val changePercent: Double,
    val source: String
)

/**
 * Buffers LTP changes in memory to reduce database I/O.
 * Flushes to disk only for significant changes or at specific intervals.
 */
object PriceAuditBuffer {
    private val buffer = ConcurrentHashMap<String, CachedPrice>()
    private var logDao: AppLogDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastFlushTime = System.currentTimeMillis()

    fun init(dao: AppLogDao) {
        logDao = dao
    }

    /**
     * Records a price update in memory. 
     * Decides whether to flush to persistent logs based on the Robust Roadmap rules.
     */
    fun onPriceUpdate(
        symbol: String,
        ltp: Double,
        changePercent: Double,
        source: String,
        isHeldOrWishlisted: Boolean,
        forceFlush: Boolean = false
    ) {
        val newPrice = CachedPrice(symbol, ltp, changePercent, source)
        val oldPrice = buffer[symbol]
        buffer[symbol] = newPrice

        scope.launch {
            val deltaPercent = if (oldPrice != null && oldPrice.ltp > 0) {
                Math.abs((newPrice.ltp - oldPrice.ltp) / oldPrice.ltp) * 100.0
            } else 0.0

            val shouldLog = forceFlush || 
                            deltaPercent >= 5.0 || 
                            isHeldOrWishlisted || 
                            (System.currentTimeMillis() - lastFlushTime >= 3600_000) // 60 mins

            if (shouldLog) {
                flushToLog(newPrice)
            }
        }
    }

    private suspend fun flushToLog(price: CachedPrice) {
        try {
            logDao?.insert(
                AppLog(
                    tag = "LTP_AUDIT",
                    message = "${price.symbol}: ${price.ltp} (${price.changePercent}%) from ${price.source}",
                    level = "LTP"
                )
            )
            lastFlushTime = System.currentTimeMillis()
        } catch (e: Exception) {
            android.util.Log.e("PriceAuditBuffer", "Failed to flush audit log", e)
        }
    }
}
