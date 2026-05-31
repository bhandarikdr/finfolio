package com.example.data.model

import com.example.data.db.ExternalLtp
import com.example.data.db.TransactionRecord

data class UserProfile(val name: String, val email: String)

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
    val returnCash: Double,
    val returnCount: Int,
    val returnQty: Double,
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
    val returnCash: Double,
    val returnQty: Double,
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

            // "Returns" now covers everything that was previously "Bonus"
            val returnRecords = txs.filter { (it.action == "Returns") || (it.action == "Bonus") }
            val returnCash = returnRecords.sumOf { it.amount }
            val returnCount = returnRecords.size
            val returnQty = returnRecords.sumOf { it.qty }

            // Intermediate Matrix Computations
            val balanceQty = (buyQty + returnQty - saleQty).coerceAtLeast(0.0)
            val avgCp = if (buyQty + returnQty == 0.0) 0.0 else buyAmount / (buyQty + returnQty)
            val avgSp = if (saleQty == 0.0) 0.0 else saleAmount / saleQty
            
            // Net Investment: Capital still tied up in this scrip (Cost Recovery Model)
            // If balanceQty is 0, we have no investment left in this scrip.
            val netInvest = if (balanceQty <= 0.0) 0.0 else (buyAmount - saleAmount - returnCash).coerceAtLeast(0.0)

            // Fetching LTP Value
            val ltpValRecord = ltpMap[symbol]
            val ltp = ltpValRecord?.ltp ?: 0.0
            val isInMs = meroshareFlags[symbol] ?: false

            // Evaluation: Current Market Value of holdings
            val evaluation = balanceQty * ltp

            // Realized Gain: Profit/Loss from recovered capital and returns
            val realizedGain = (saleAmount - buyAmount) + returnCash + netInvest
            
            // Unrealized Gain: Market Value - Remaining Capital
            val unrealizedGain = evaluation - netInvest
            
            // Estimated Deductions (Commission, DP Fee, and CGT on profit)
            val deductions = if (unrealizedGain > 0.0) {
                (evaluation * 0.0038) + 25.0 + (unrealizedGain * 0.075)
            } else {
                (evaluation * 0.0038) + 25.0
            }
            
            val netGain = realizedGain + unrealizedGain - deductions
            val growth = if (buyAmount == 0.0) 0.0 else (netGain / buyAmount) * 100.0
            val receivableAmount = (evaluation - deductions).coerceAtLeast(0.0)
            
            // Profit Amount should match the overall Net Gain for the scrip
            val profitAmount = netGain

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
                returnCash = returnCash,
                returnCount = returnCount,
                returnQty = returnQty,
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
            val returnCash = items.sumOf { it.returnCash }
            val returnQty = items.sumOf { it.returnQty }
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
                returnCash = returnCash,
                returnQty = returnQty,
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
