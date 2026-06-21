package com.example.data.model

import com.example.data.db.ExternalLtp
import com.example.data.db.TransactionRecord

/**
 * Categories for data scrapers used throughout the app.
 * Each category supports prioritized multiple URLs for fallback reliability.
 */
enum class ScraperCategory(val displayName: String, val description: String) {
    LTP_UPDATE("Live Price (LTP) Update", "Fetches latest market prices for portfolio stocks."),
    INDEX_UPDATE("Market Index Update", "Updates NEPSE and sector indices."),
    SCRIP_SYNC("Scrip Master Sync", "Downloads list of all listed companies."),
    IPO_LISTING("IPO Pipeline", "Tracks upcoming and ongoing IPOs."),
    CDSC_COMPANIES("IPO Result Company List", "Required to map companies for IPO allotment checks."),
    CDSC_RESULT("IPO Result Checker", "Endpoint for verifying IPO allotment status.")
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
    val scraperUrls: Map<ScraperCategory, List<String>> = emptyMap()
)

data class NepseStatus(
    val index: String = "0.00",
    val change: String = "0.00",
    val percentChange: String = "0.00%",
    val date: String = "",
    val status: String = "Market Closed",
    val isPositive: Boolean = true,
)

data class ItemMetrics(
    val item: String,
    val type: String,
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
    val isInMeroshareCsv: Boolean
)

data class TypeMetrics(
    val type: String,
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

    /**
     * Compute comprehensive metrics for each distinct item, matching the formulas exactly.
     */
    fun computeItemMetrics(
        transactions: List<TransactionRecord>,
        ltpList: List<ExternalLtp>
    ): List<ItemMetrics> {
        val ltpMap = mutableMapOf<String, ExternalLtp>()
        // Put Meroshare first, so Scraped overrides it (since Scraped is step 1 lookup and Meroshare is step 2 check)
        ltpList.filter { it.source == "Meroshare" }.forEach { ltpMap[it.symbol.uppercase().trim()] = it }
        ltpList.filter { it.source == "Scraped" }.forEach { ltpMap[it.symbol.uppercase().trim()] = it }

        val meroshareFlags = ltpList.groupBy { it.symbol.uppercase().trim() }
            .mapValues { (_, list) -> list.any { it.isInMeroshareCsv } }

        val groupedTx = transactions.groupBy { it.item.uppercase().trim() }

        return groupedTx.map { (symbol, txs) ->
            val type = txs.firstOrNull()?.type ?: "UNKNOWN"

            val buyRecords = txs.filter { it.action == "Buy" }
            val buyAmount = buyRecords.sumOf { it.amount }
            val buyCount = buyRecords.size
            val buyQty = buyRecords.sumOf { it.qty }

            val saleRecords = txs.filter { it.action == "Sale" }
            val saleAmount = saleRecords.sumOf { it.amount }
            val saleCount = saleRecords.size
            val saleQty = saleRecords.sumOf { it.qty }

            // "Returns" covers everything including Dividends and Bonus shares
            val returnRecords = txs.filter { it.action == "Returns" }
            val returnsCash = returnRecords.sumOf { it.amount }
            val returnCount = returnRecords.size
            val returnsQty = returnRecords.sumOf { it.qty }

            // Intermediate Matrix Computations
            // Balance Qty = Buy + Returns - Sale
            val balanceQty = (buyQty + returnsQty - saleQty).coerceAtLeast(0.0)
            val avgCp = if (buyQty + returnsQty == 0.0) 0.0 else buyAmount / (buyQty + returnsQty)
            val avgSp = if (saleQty == 0.0) 0.0 else saleAmount / saleQty
            
            // Net Invest: Capital outlay still at risk (Cost Recovery Model)
            // Logic: Total Buy Amount minus Total Sale Amount.
            val netInvest = (buyAmount - saleAmount).coerceAtLeast(0.0)

            // Fetching LTP Value
            val ltpValRecord = ltpMap[symbol]
            val ltp = ltpValRecord?.ltp ?: 0.0
            val isInMs = meroshareFlags[symbol] ?: false

            // Evaluation: Current Market Value
            // Logic: If units exist (Avg CP > 0), use Qty * LTP. 
            // For items without units (Avg CP = 0), Evaluation is the unrecovered capital (Net Invest).
            val evaluation = if (avgCp > 0.0) (balanceQty * ltp) else netInvest

            // Realized Gain: Profit from capital recovery
            // Formula: (Total Sales - Total Buys) + Returns Cash + Net Invest
            val realizedGain = (saleAmount - buyAmount) + returnsCash + netInvest
            
            // Unrealized Gain: Paper profit based on unrecovered capital
            val unrealizedGain = evaluation - netInvest
            
            // Estimated Deductions (Commission, DP Fee, and CGT on profit)
            // Note: Only applied for unit-based (equity) investments where holdings exist.
            val deductions = if (avgCp > 0.0 && evaluation > 0.0) {
                if (unrealizedGain > 0.0) (evaluation * 0.0038) + 25.0 + (unrealizedGain * 0.075)
                else (evaluation * 0.0038) + 25.0
            } else 0.0
            
            // Net Gain: The true "bottom line" profit (Total absolute wealth increase)
            val netGain = realizedGain + unrealizedGain - deductions
            val growth = if (buyAmount == 0.0) 0.0 else (netGain / buyAmount) * 100.0
            val receivableAmount = (evaluation - deductions).coerceAtLeast(0.0)
            
            // Profit: Current gain on at-risk capital
            // Formula: Evaluation - Net Investment
            val profitAmount = evaluation - netInvest

            val profitPercent = when {
                netInvest > 0.0 -> (profitAmount / netInvest) * 100.0
                (netInvest == 0.0) && (buyAmount > 0.0) -> (profitAmount / buyAmount) * 100.0
                else -> 0.0
            }

            ItemMetrics(
                item = symbol,
                type = type,
                buyAmount = buyAmount,
                buyCount = buyCount,
                buyQty = buyQty,
                saleAmount = saleAmount,
                saleCount = saleCount,
                saleQty = saleQty,
                returnsCash = returnsCash,
                returnCount = returnCount,
                returnsQty = returnsQty,
                balanceQty = balanceQty,
                avgCp = avgCp,
                avgSp = avgSp,
                ltp = ltp,
                netInvest = netInvest,
                evaluation = evaluation,
                realizedGain = realizedGain,
                unrealizedGain = unrealizedGain,
                deductions = deductions,
                netGain = netGain,
                growth = growth,
                receivableAmount = receivableAmount,
                profitAmount = profitAmount,
                profitPercent = profitPercent,
                isInMeroshareCsv = isInMs
            )
        }.sortedBy { it.item }
    }

    /**
     * Compute collapsed and grouped metrics by Type categories, matching the formula specifications.
     */
    fun computeTypeMetrics(itemMetrics: List<ItemMetrics>): List<TypeMetrics> {
        val groupedByType = itemMetrics.groupBy { it.type.uppercase().trim() }

        return groupedByType.map { (sector, items) ->
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
                type = sector,
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
        }.sortedBy { it.type }
    }
}
