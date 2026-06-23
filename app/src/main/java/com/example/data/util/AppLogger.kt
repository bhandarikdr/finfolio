package com.example.data.util

import com.example.data.db.AppLog
import com.example.data.db.AppLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private var logDao: AppLogDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(dao: AppLogDao) {
        logDao = dao
        d("Logger", "AppLogger initialized")
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg \nException: ${tr.message}\nStacktrace: ${tr.stackTraceToString()}" else msg
        log("ERROR", tag, fullMsg)
    }

    private fun log(level: String, tag: String, msg: String) {
        // Also log to Logcat
        when (level) {
            "INFO" -> android.util.Log.i(tag, msg)
            "DEBUG" -> android.util.Log.d(tag, msg)
            "WARN" -> android.util.Log.w(tag, msg)
            "ERROR" -> android.util.Log.e(tag, msg)
        }
        
        scope.launch {
            try {
                logDao?.insert(AppLog(tag = tag, message = msg, level = level))
            } catch (e: Exception) {
                android.util.Log.e("AppLogger", "Failed to insert log into DB", e)
            }
        }
    }

    suspend fun exportLogs(): String {
        val logs = logDao?.getAllLogsSync() ?: emptyList()
        return buildString {
            append("--- FINFOLIO SYSTEM LOG EXPORT ---\n")
            append("Export Time: ${dateFormat.format(Date())}\n")
            append("Device: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n")
            append("----------------------------------\n\n")
            logs.forEach { log ->
                val time = dateFormat.format(Date(log.timestamp))
                append("[$time] [${log.level}] [${log.tag}]: ${log.message}\n")
            }
        }
    }

    fun clearLogs() {
        scope.launch { logDao?.clearAll() }
    }
}
