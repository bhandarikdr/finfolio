package com.example.data.model

import com.example.data.db.ExternalLtp

/**
 * Categories for data scrapers used throughout the app.
 * Each category supports prioritized multiple URLs for fallback reliability.
 */
enum class ScraperCategory(val displayName: String, val description: String) {
    LTP_UPDATE("Live Price (LTP) Update", "Fetches latest market prices for portfolio stocks."),
    INDEX_UPDATE("Market Index Update", "Updates market and sector indices."),
    SCRIP_SYNC("Scrip Master Sync", "Downloads list of all listed companies."),
    IPO_LISTING("IPO Pipeline", "Tracks upcoming and ongoing IPOs."),
    IPO_RESULT("IPO Result Checker", "Endpoint for verifying IPO allotment status."),
    DP_MASTER("DP Member List", "Syncs Depository Participants for automated MeroShare login."),
    MEROSHARE_WEB("MeroShare Web Portal", "Official MeroShare login portal for manual or automated application."),
    MEROSHARE_API("MeroShare Backend API", "Private API endpoint for automated results and application.")
}

object ScraperDefaults {
    val defaultScrapersByCategory = mapOf(
        ScraperCategory.LTP_UPDATE to listOf(
            "https://www.nepalstock.com/",
            "https://www.sharesansar.com/live-trading",
            "https://merolagani.com/latestmarket.aspx"
        ),
        ScraperCategory.INDEX_UPDATE to listOf(
            "https://www.nepalstock.com/",
            "https://www.sharesansar.com/market",
            "https://merolagani.com/latestmarket.aspx"
        ),
        ScraperCategory.SCRIP_SYNC to listOf(
            "https://www.sharesansar.com/company-list",
            "https://merolagani.com/CompanyList.aspx"
        ),
        ScraperCategory.IPO_LISTING to listOf(
            "https://nepalipaisa.com/api/GetIpos?stockSymbol=&pageNo=1&itemsPerPage=100&pagePerDisplay=5",
            "https://www.sharesansar.com/ipo-fpo-news"
        ),
        ScraperCategory.IPO_RESULT to listOf(
            "https://iporesult.cdsc.com.np/"
        ),
        ScraperCategory.DP_MASTER to listOf(
            "https://www.sharesansar.com/dp-member-list"
        ),
        ScraperCategory.MEROSHARE_WEB to listOf(
            "https://meroshare.cdsc.com.np/#/asba"
        ),
        ScraperCategory.MEROSHARE_API to listOf(
            "https://webbackend.cdsc.com.np/api/meroShare"
        )
    )
}

/**
 * Represent the user's profile and application preferences.
 * Includes names, preferences, and custom scraper configurations.
 */
data class UserProfile(
    val name: String,
    val email: String,
    val currencySymbol: String = "रु.",
    val dateFormat: String = "AD",
    val visibleIndices: List<String> = emptyList(),
    /** Map of scraper categories to a prioritized list of URLs. App tries them in order. */
    val scraperUrls: Map<ScraperCategory, List<String>> = emptyMap(),
    val pin: String? = null,
    val itemColumns: Set<String> = emptySet(),
    val sectorColumns: Set<String> = emptySet(),
    val selectedSectorFilter: String = "All",
    val dashboardScope: String = "OVERALL",
    val matrixScope: String = "OVERALL",
    val primaryIndexName: String = "NEPSE Index",
    val commissionRate: Double = 0.0038,
    val flatFee: Double = 25.0,
    val cgtRate: Double = 0.075
)

data class MarketStatus(
    val index: String = "0.00",
    val change: String = "0.00",
    val percentChange: String = "0.00%",
    val date: String = "",
    val status: String = "Market Closed",
    val isPositive: Boolean = true,
)

data class ItemMetrics(
    val item: String,
    val sector: String,
    val buyAmount: Double,
    val buyCount: Int,
    val buyQty: Double,
    val saleAmount: Double,
    val saleCount: Int,
    val saleQty: Double,
    val returnsCash: Double,
    val returnCount: Int,
    val returnsQty: Double,
    val balanceQty: Double,
    val avgCp: Double,
    val avgSp: Double,
    val ltp: Double,
    val prevLtp: Double = 0.0,
    val netInvest: Double,
    val evaluation: Double,
    val realizedGain: Double,
    val unrealizedGain: Double,
    val deductions: Double,
    val netGain: Double,
    val growth: Double,
    val receivableAmount: Double,
    val profitAmount: Double,
    val profitPercent: Double,
    val isInExternalSync: Boolean
)

data class TypeMetrics(
    val sector: String,
    val itemCount: Int,
    val buyAmount: Double,
    val saleAmount: Double,
    val returnsCash: Double,
    val returnsQty: Double,
    val balanceQty: Double,
    val netInvest: Double,
    val evaluation: Double,
    val realizedGain: Double,
    val unrealizedGain: Double,
    val deductions: Double,
    val netGain: Double,
    val growth: Double,
    val receivableAmount: Double,
    val profitAmount: Double,
    val profitPercent: Double
)

object FinancialEngines {

    private fun round(value: Double): Double {
        return Math.round(value * 100.0) / 100.0
    }

    /**
     * Optimized: Compute metrics using pre-computed Holdings table.
     */
    fun computeItemMetricsFromHoldings(
        holdings: List<com.example.data.db.Holdings>,
        ltpList: List<ExternalLtp>,
        commissionRate: Double = 0.0038,
        flatFee: Double = 25.0,
        cgtRate: Double = 0.075
    ): List<ItemMetrics> {
        val ltpMap = mutableMapOf<String, ExternalLtp>()
        ltpList.sortedBy { it.timestamp }.forEach { ltpMap[it.symbol.uppercase().trim()] = it }

        val externalSyncFlags = ltpList.groupBy { it.symbol.uppercase().trim() }
            .mapValues { (_, list) -> list.any { it.isInExternalSync } }

        return holdings.map { h ->
            val symbol = h.symbol
            val sector = h.sector

            val buyAmount = h.totalBuyAmount
            val buyQty = h.totalBuyQty
            val saleAmount = h.totalSaleAmount
            val saleQty = h.totalSaleQty
            val returnsCash = h.returnsCash
            val returnsQty = h.returnsQty

            val balanceQty = (buyQty + returnsQty - saleQty).coerceAtLeast(0.0)
            val avgCp = if (buyQty + returnsQty == 0.0) 0.0 else round(buyAmount / (buyQty + returnsQty))
            val avgSp = if (saleQty == 0.0) 0.0 else round(saleAmount / saleQty)
            
            val netInvest = round((buyAmount - saleAmount).coerceAtLeast(0.0))

            val ltpValRecord = ltpMap[symbol]
            val ltp = ltpValRecord?.ltp ?: 0.0
            val prevLtp = ltpValRecord?.previousLtp ?: 0.0
            val isInSync = externalSyncFlags[symbol] ?: false

            val evaluation = round(if (avgCp > 0.0) (balanceQty * ltp) else netInvest)
            val realizedGain = round((saleAmount - buyAmount) + returnsCash + netInvest)
            val unrealizedGain = round(evaluation - netInvest)
            
            val deductions = if (avgCp > 0.0 && evaluation > 0.0) {
                round(if (unrealizedGain > 0.0) (evaluation * commissionRate) + flatFee + (unrealizedGain * cgtRate)
                else (evaluation * commissionRate) + flatFee)
            } else 0.0
            
            val netGain = round(realizedGain + unrealizedGain - deductions)
            val growth = if (buyAmount == 0.0) 0.0 else round((netGain / buyAmount) * 100.0)
            val receivableAmount = round((evaluation - deductions).coerceAtLeast(0.0))
            val profitAmount = round(receivableAmount - netInvest)

            val profitPercent = when {
                netInvest > 0.0 -> round((profitAmount / netInvest) * 100.0)
                (netInvest == 0.0) && (buyAmount > 0.0) -> round((profitAmount / buyAmount) * 100.0)
                else -> 0.0
            }

            ItemMetrics(
                item = symbol,
                sector = sector,
                buyAmount = round(buyAmount), buyCount = 0, buyQty = buyQty,
                saleAmount = round(saleAmount), saleCount = 0, saleQty = saleQty,
                returnsCash = round(returnsCash), returnCount = 0, returnsQty = returnsQty,
                balanceQty = balanceQty, avgCp = avgCp, avgSp = avgSp,
                ltp = ltp, prevLtp = prevLtp,
                netInvest = netInvest, evaluation = evaluation,
                realizedGain = realizedGain, unrealizedGain = unrealizedGain,
                deductions = deductions, netGain = netGain, growth = growth,
                receivableAmount = receivableAmount, profitAmount = profitAmount,
                profitPercent = profitPercent, isInExternalSync = isInSync
            )
        }.sortedBy { it.item }
    }

    /**
     * Compute collapsed and grouped metrics by Type categories, matching the formula specifications.
     */
    fun computeTypeMetrics(itemMetrics: List<ItemMetrics>): List<TypeMetrics> {
        val groupedBySector = itemMetrics.groupBy { it.sector.uppercase().trim() }

        return groupedBySector.map { (sector, items) ->
            val itemCount = items.size
            val buyAmount = items.sumOf { it.buyAmount }
            val saleAmount = items.sumOf { it.saleAmount }
            val returnsCash = items.sumOf { it.returnsCash }
            val returnsQty = items.sumOf { it.returnsQty }
            val balanceQty = items.sumOf { it.balanceQty }
            val netInvest = items.sumOf { it.netInvest }
            val evaluation = items.sumOf { it.evaluation }
            val realizedGain = items.sumOf { it.realizedGain }
            val unrealizedGain = items.sumOf { it.unrealizedGain }
            val deductions = items.sumOf { it.deductions }
            val netGain = items.sumOf { it.netGain }
            val receivableAmount = items.sumOf { it.receivableAmount }
            val profitAmount = items.sumOf { it.profitAmount }

            val growth = if (buyAmount == 0.0) 0.0 else (netGain / buyAmount) * 100.0
            val profitPercent = when {
                netInvest > 0.0 -> (profitAmount / netInvest) * 100.0
                netInvest == 0.0 && buyAmount > 0.0 -> (profitAmount / buyAmount) * 100.0
                else -> 0.0
            }

            TypeMetrics(
                sector = sector,
                itemCount = itemCount,
                buyAmount = buyAmount,
                saleAmount = saleAmount,
                returnsCash = returnsCash,
                returnsQty = returnsQty,
                balanceQty = balanceQty,
                netInvest = netInvest,
                evaluation = evaluation,
                realizedGain = realizedGain,
                unrealizedGain = unrealizedGain,
                deductions = deductions,
                netGain = netGain,
                growth = growth,
                receivableAmount = receivableAmount,
                profitAmount = profitAmount,
                profitPercent = profitPercent
            )
        }.sortedBy { it.sector }
    }
}
