package com.example.data.util

import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Robust utility for AD <-> BS (Bikram Sambat) date conversion.
 * Supports years 2075 to 2085 BS.
 */
object NepDateUtils {

    data class BSDate(val year: Int, val month: Int, val day: Int) {
        override fun toString(): String = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    // Mapping of BS years to their month lengths (Baisakh to Chaitra)
    private val BS_MONTH_DAYS = mapOf(
        2075 to intArrayOf(31, 31, 32, 32, 31, 30, 30, 30, 30, 29, 30, 30),
        2076 to intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 30, 29, 30, 30),
        2077 to intArrayOf(31, 32, 32, 31, 31, 30, 30, 30, 29, 30, 30, 30),
        2078 to intArrayOf(31, 31, 32, 32, 31, 30, 30, 30, 29, 30, 30, 30),
        2079 to intArrayOf(31, 31, 32, 32, 31, 31, 30, 29, 30, 29, 30, 30),
        2080 to intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30),
        2081 to intArrayOf(31, 32, 32, 32, 31, 30, 30, 30, 29, 29, 30, 30),
        2082 to intArrayOf(31, 32, 32, 32, 31, 30, 30, 29, 30, 29, 30, 30),
        2083 to intArrayOf(31, 31, 32, 32, 31, 31, 30, 30, 29, 30, 29, 30),
        2084 to intArrayOf(31, 31, 32, 32, 31, 30, 30, 30, 29, 30, 30, 30),
        2085 to intArrayOf(31, 32, 31, 32, 31, 30, 30, 30, 29, 30, 30, 30)
    )

    // Reference point: 2075-01-01 BS is 2018-04-14 AD
    private const val REF_BS_YEAR = 2075
    private const val REF_AD_YEAR = 2018
    private const val REF_AD_MONTH = Calendar.APRIL
    private const val REF_AD_DAY = 14

    /**
     * Converts BS (Bikram Sambat) date string (YYYY-MM-DD) to AD string (YYYY-MM-DD).
     */
    fun bsToAd(bsDateStr: String): String? {
        val parts = bsDateStr.split("-", "/")
        if (parts.size != 3) return null
        return try {
            val year = parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()
            val adDate = bsToAd(BSDate(year, month, day))
            if (adDate != null) {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).format(adDate)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts BS (Bikram Sambat) date to AD.
     */
    fun bsToAd(bsDate: BSDate): Date? {
        if (!BS_MONTH_DAYS.containsKey(bsDate.year)) return null
        if (bsDate.month < 1 || bsDate.month > 12) return null
        
        var totalDays = 0
        
        // Days from reference year to current year
        for (y in REF_BS_YEAR until bsDate.year) {
            totalDays += BS_MONTH_DAYS[y]?.sum() ?: 365
        }
        
        // Days from Baisakh to current month
        val monthDays = BS_MONTH_DAYS[bsDate.year] ?: return null
        if (bsDate.day < 1 || bsDate.day > monthDays[bsDate.month - 1]) return null
        
        for (m in 0 until (bsDate.month - 1)) {
            totalDays += monthDays[m]
        }
        
        // Days in current month
        totalDays += (bsDate.day - 1)
        
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(REF_AD_YEAR, REF_AD_MONTH, REF_AD_DAY, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, totalDays)
        }
        return cal.time
    }

    /**
     * Converts AD (Calendar) date to BS (Bikram Sambat).
     */
    fun adToBs(adDate: Date): BSDate? {
        val refCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(REF_AD_YEAR, REF_AD_MONTH, REF_AD_DAY, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val targetCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = adDate
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        if (targetCal.before(refCal)) return null
        
        var diffDays = ((targetCal.timeInMillis - refCal.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()
        
        var currentYear = REF_BS_YEAR
        while (true) {
            val daysInYear = BS_MONTH_DAYS[currentYear]?.sum() ?: 365
            if (diffDays < daysInYear) break
            diffDays -= daysInYear
            currentYear++
            if (currentYear > 2085) return null
        }
        
        var currentMonth = 1
        val monthDays = BS_MONTH_DAYS[currentYear] ?: return null
        while (true) {
            val daysInMonth = monthDays[currentMonth - 1]
            if (diffDays < daysInMonth) break
            diffDays -= daysInMonth
            currentMonth++
        }
        
        return BSDate(currentYear, currentMonth, diffDays + 1)
    }
}
