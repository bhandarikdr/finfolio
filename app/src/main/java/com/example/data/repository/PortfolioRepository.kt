package com.example.data.repository

import com.example.data.db.ExternalLtp
import com.example.data.db.PortfolioDao
import com.example.data.db.TransactionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader

class PortfolioRepository(private val portfolioDao: PortfolioDao) {

    val allTransactions: Flow<List<TransactionRecord>> = portfolioDao.getAllTransactions()
    val allExternalLtps: Flow<List<ExternalLtp>> = portfolioDao.getAllExternalLtps()
    val distinctItems: Flow<List<String>> = portfolioDao.getDistinctItems()
    val distinctTypes: Flow<List<String>> = portfolioDao.getDistinctTypes()

    suspend fun insertTransaction(record: TransactionRecord): Long {
        return withContext(Dispatchers.IO) {
            portfolioDao.insertTransaction(record)
        }
    }

    suspend fun updateTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.updateTransaction(record)
        }
    }

    suspend fun deleteTransaction(record: TransactionRecord) {
        withContext(Dispatchers.IO) {
            portfolioDao.deleteTransaction(record)
        }
    }

    suspend fun clearAllTransactions() {
        withContext(Dispatchers.IO) {
            portfolioDao.clearAllTransactions()
        }
    }

    suspend fun importTransactionCsv(inputStream: InputStream, overwrite: Boolean): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                var lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    return@withContext Result.failure(Exception("The selected CSV file is empty"))
                }

                // Detect headers and strip any UTF-8 BOM
                val headerLine = lines.first().replace("\uFEFF", "")
                
                // Detect separator
                var separator = ","
                if (headerLine.contains(";") && headerLine.count { it == ';' } > headerLine.count { it == ',' }) {
                    separator = ";"
                }
                
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                val dateIdx = header.indexOfFirst { it.contains("date") }
                val itemIdx = header.indexOfFirst { it.contains("item") || it.contains("symbol") || it.contains("scrip") || it.contains("name") }
                val actionIdx = header.indexOfFirst { it.contains("action") || it.contains("buy/sell") || it.contains("direction") || it.contains("side") || (it == "type" && header.indexOf("action") == -1) }
                val qtyIdx = header.indexOfFirst { it.contains("qty") || it.contains("quantity") || it.contains("unit") || it.contains("vol") || it.contains("size") }
                val amountIdx = header.indexOfFirst { it.contains("amount") || it.contains("total") || it.contains("price") || it.contains("rate") || it.contains("cost") || it.contains("value") }
                val typeIdx = header.indexOfFirst { it.contains("type") || it.contains("category") || it.contains("sector") || it.contains("group") }

                if (itemIdx == -1) {
                    return@withContext Result.failure(
                        Exception("Required column for 'Item' or 'Symbol' or 'Scrip' not found in CSV. Headers found: $header")
                    )
                }

                val records = mutableListOf<TransactionRecord>()
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

                for (i in 1 until lines.size) {
                    val cols = parseCsvRow(lines[i], separator)
                    if (cols.size > itemIdx) {
                        val date = if (dateIdx != -1 && dateIdx < cols.size && cols[dateIdx].isNotBlank()) cols[dateIdx] else todayStr
                        val item = cols[itemIdx].uppercase()
                        val type = if (typeIdx != -1 && typeIdx < cols.size && cols[typeIdx].isNotBlank()) cols[typeIdx].uppercase() else "EQUITY"
                        
                        val actionRaw = if (actionIdx != -1 && actionIdx < cols.size) cols[actionIdx] else "Buy"
                        val qtyStr = if (qtyIdx != -1 && qtyIdx < cols.size) cols[qtyIdx].replace("[^0-9.]".toRegex(), "") else ""
                        val qty = qtyStr.toDoubleOrNull() ?: 0.0
                        val amountStr = if (amountIdx != -1 && amountIdx < cols.size) cols[amountIdx].replace("[^0-9.]".toRegex(), "") else ""
                        val amount = amountStr.toDoubleOrNull() ?: 0.0

                        val normalizedAction = when {
                            actionRaw.equals("buy", ignoreCase = true) || actionRaw.equals("purchase", ignoreCase = true) -> "Buy"
                            actionRaw.equals("sale", ignoreCase = true) || actionRaw.equals("sell", ignoreCase = true) -> "Sale"
                            actionRaw.equals("returns", ignoreCase = true) || actionRaw.equals("return", ignoreCase = true) -> "Returns"
                            actionRaw.equals("bonus", ignoreCase = true) -> "Bonus"
                            else -> "Buy"
                        }

                        records.add(
                            TransactionRecord(
                                date = date,
                                item = item,
                                type = type,
                                action = normalizedAction,
                                qty = qty,
                                amount = amount
                            )
                        )
                    }
                }

                if (overwrite) {
                    portfolioDao.clearAllTransactions()
                }

                var successCount = 0
                for (rec in records) {
                    portfolioDao.insertTransaction(rec)
                    successCount++
                }

                Result.success(successCount)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun importMeroshareCsv(inputStream: InputStream): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(inputStream))
                val lines = reader.readLines().filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    return@withContext Result.failure(Exception("The selected CSV file is empty"))
                }

                val headerLine = lines.first().replace("\uFEFF", "")
                
                // Detect separator
                var separator = ","
                if (headerLine.contains(";") && headerLine.count { it == ';' } > headerLine.count { it == ',' }) {
                    separator = ";"
                }
                
                val header = parseCsvRow(headerLine, separator).map { it.lowercase().replace("\"", "").trim() }

                val scripIdx = header.indexOfFirst { it.contains("scrip") || it.contains("symbol") || it.contains("item") || it.contains("scrip name") || it.contains("name") }
                val ltpIdx = header.indexOfFirst { it.contains("last transaction price") || it.contains("ltp") || it.contains("price") || it.contains("rate") || it.contains("valu") }

                if (scripIdx == -1 || ltpIdx == -1) {
                    return@withContext Result.failure(
                        Exception("Required columns 'Scrip' and 'Last Transaction Price(LTP)' not found in CSV. Headers found: $header")
                    )
                }

                val newLtpRecords = mutableListOf<ExternalLtp>()
                val timestamp = System.currentTimeMillis()

                for (i in 1 until lines.size) {
                    val rowText = lines[i]
                    val cols = parseCsvRow(rowText, separator)
                    if (cols.size > maxOf(scripIdx, ltpIdx)) {
                        val scrip = cols[scripIdx].uppercase()
                        val ltpValClean = cols[ltpIdx].replace("\"", "").replace(",", "").trim()
                        val ltpValue = ltpValClean.toDoubleOrNull() ?: 0.0
                        if (scrip.isNotEmpty()) {
                            newLtpRecords.add(
                                ExternalLtp(
                                    symbol = scrip,
                                    ltp = ltpValue,
                                    source = "Meroshare",
                                    timestamp = timestamp,
                                    isInMeroshareCsv = true
                                )
                            )
                        }
                    }
                }

                // 1. Completely overwrite local database where source is 'Meroshare'
                portfolioDao.deleteExternalLtpBySource("Meroshare")
                
                // 2. Clear out `isInMeroshareCsv` across remaining tables (which represents remaining Scraped records)
                portfolioDao.resetMeroshareCsvFlag()

                // 3. Mark isInMeroshareCsv = true for imported records and insert
                portfolioDao.insertExternalLtps(newLtpRecords)

                // 4. Update the Scraped source table to also reflect the isInMeroshareCsv flag if there are duplicates
                for (rec in newLtpRecords) {
                    val existingScraped = portfolioDao.getExternalLtpBySymbol(rec.symbol)
                    if (existingScraped != null && existingScraped.source == "Scraped") {
                        portfolioDao.insertExternalLtp(existingScraped.copy(isInMeroshareCsv = true))
                    }
                }

                Result.success(newLtpRecords.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun parseCsvRow(rowText: String, separator: String = ","): List<String> {
        val tokens = mutableListOf<String>()
        var curToken = StringBuilder()
        var insideQuotes = false
        var i = 0
        val sepChar = if (separator.isNotEmpty()) separator[0] else ','
        while (i < rowText.length) {
            val c = rowText[i]
            if (c == '"') {
                insideQuotes = !insideQuotes
            } else if (c == sepChar && !insideQuotes) {
                tokens.add(curToken.toString().trim())
                curToken = StringBuilder()
            } else {
                curToken.append(c)
            }
            i++
        }
        tokens.add(curToken.toString().trim())
        return tokens
    }

    suspend fun refreshLivePrices(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val doc = Jsoup.connect("https://www.sharesansar.com/live-trading")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                    .timeout(20000)
                    .get()

                val tables = doc.select("table")
                val table = if (tables.size > 1) tables[1] else if (tables.isNotEmpty()) tables[0] else null
                
                if (table != null) {
                    val headers = table.select("thead tr th").map { it.text().trim().uppercase() }
                    var symbolIdx = headers.indexOfFirst { it.contains("SYMBOL") || it.contains("SCRIP") }
                    var ltpIdx = headers.indexOfFirst { it.contains("LTP") || it.contains("LAST TRANSACTION PRICE") || it.contains("L.T.P") || it.contains("LAST TRADED PRICE") }

                    if (symbolIdx == -1) symbolIdx = 1 // fallback
                    if (ltpIdx == -1) {
                        // find standard LTP header contains "LTP" or "PRICE"
                        ltpIdx = headers.indexOfFirst { it.contains("PRICE") || it.contains("L.T.P") }
                        if (ltpIdx == -1) ltpIdx = 5 // classic table layout fallback
                    }

                    val rows = table.select("tbody tr")
                    val scrapedLtps = mutableListOf<ExternalLtp>()
                    val timestamp = System.currentTimeMillis()

                    for (row in rows) {
                        val cols = row.select("td")
                        if (cols.size > symbolIdx && cols.size > ltpIdx) {
                            val symbol = cols[symbolIdx].text().trim().uppercase()
                            val ltpStrClean = cols[ltpIdx].text().trim().replace(",", "")
                            val ltpVal = ltpStrClean.toDoubleOrNull()
                            if (symbol.isNotEmpty() && ltpVal != null) {
                                // Keep the isInMeroshareCsv flag if it was already flag-set
                                val existing = portfolioDao.getExternalLtpBySymbol(symbol)
                                val inMs = existing?.isInMeroshareCsv ?: false
                                scrapedLtps.add(
                                    ExternalLtp(
                                        symbol = symbol,
                                        ltp = ltpVal,
                                        source = "Scraped",
                                        timestamp = timestamp,
                                        isInMeroshareCsv = inMs
                                    )
                                )
                            }
                        }
                    }

                    if (scrapedLtps.isNotEmpty()) {
                        portfolioDao.insertExternalLtps(scrapedLtps)
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
