package com.example.data

import com.example.data.db.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class CostRecoveryEngineTest {

    @Test
    fun testCostRecoveryLogic() {
        // SCENARIO 1: Basic Buy -> Sell -> Returns
        val tx1 = listOf(
            TransactionRecord(item = "NABIL", action = "Buy", qty = 100.0, amount = 10000.0, date = "2023-01-01", sector = "Banks"),
            TransactionRecord(item = "NABIL", action = "Sale", qty = 50.0, amount = 6000.0, date = "2023-02-01", sector = "Banks"),
            TransactionRecord(item = "NABIL", action = "Returns", qty = 0.0, amount = 500.0, date = "2023-03-01", sector = "Banks"), // Dividend
            TransactionRecord(item = "NABIL", action = "Returns", qty = 10.0, amount = 0.0, date = "2023-04-01", sector = "Banks")   // Bonus
        )
        assertMetrics(tx1, 60.0, 4000.0, 500.0)

        // SCENARIO 2: Complete Exit with Profit
        val tx2 = listOf(
            TransactionRecord(item = "ADBL", action = "Buy", qty = 100.0, amount = 10000.0, date = "2023-01-01", sector = "Banks"),
            TransactionRecord(item = "ADBL", action = "Sale", qty = 100.0, amount = 15000.0, date = "2023-02-01", sector = "Banks")
        )
        assertMetrics(tx2, 0.0, 0.0, 5000.0)

        // SCENARIO 3: SIP / Accumulation
        val tx3 = listOf(
            TransactionRecord(item = "SIP", action = "Buy", qty = 10.0, amount = 1000.0, date = "2023-01-01", sector = "SIP"),
            TransactionRecord(item = "SIP", action = "Buy", qty = 10.0, amount = 1100.0, date = "2023-02-01", sector = "SIP")
        )
        assertMetrics(tx3, 20.0, 2100.0, 0.0)

        // SCENARIO 4: Right Shares (Buy with cost)
        val tx4 = listOf(
            TransactionRecord(item = "RIGHT", action = "Buy", qty = 100.0, amount = 10000.0, date = "2023-01-01", sector = "Banks"),
            TransactionRecord(item = "RIGHT", action = "Returns", qty = 20.0, amount = 2000.0, date = "2023-02-01", sector = "Banks")
        )
        assertMetrics(tx4, 120.0, 12000.0, 0.0)
    }

    private fun assertMetrics(transactions: List<TransactionRecord>, expBal: Double, expNet: Double, expGain: Double) {
        var tbA = 0.0; var tsA = 0.0; var trC = 0.0; var tbQ = 0.0; var tsQ = 0.0; var trQ = 0.0
        for (tx in transactions) {
            when (tx.action) {
                "Buy" -> { tbQ += tx.qty; tbA += tx.amount }
                "Sale" -> { tsQ += tx.qty; tsA += tx.amount }
                "Returns" -> { trQ += tx.qty; trC += tx.amount }
            }
        }
        val balanceQty = tbQ + trQ - tsQ
        val netInvest = (tbA - tsA).coerceAtLeast(0.0)
        val realizedGain = (tsA - tbA) + trC + netInvest
        
        assertEquals(expBal, balanceQty, 0.001)
        assertEquals(expNet, netInvest, 0.001)
        assertEquals(expGain, realizedGain, 0.001)
    }
}
