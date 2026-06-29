# Matrix (Metrics) Specification

The Matrix screen provides a granular, tabular view of every scrip in the portfolio with configurable analytics, powered by a pre-computed high-performance engine.

## 1. Data Architecture: The Holdings Engine
Unlike traditional systems that calculate metrics on-the-fly, FinFolio uses a **State-Driven Holdings Engine**:
- **Source of Truth**: The `Holdings` table stores pre-calculated balances, WACC, and realized profits.
- **Update Trigger**: Any change to `TransactionHistory` triggers an **Atomic Interceptor** that re-calculates only the affected scrip's holding.
- **Performance**: Dashboard and Matrix loads are $O(1)$ regarding transaction history size, ensuring zero UI lag.

## 2. Calculation Logic (State-Machine)
To ensure 100% accuracy, WACC and Profit are calculated using a specialized handler for each transaction type:
- **Buy/Sale**: Standard weighted average cost adjustment.
- **Bonus/Right/Split**: Adjustment of `totalQuantity` and proportional reduction of `avgCostPrice`.
- **SIP (Monthly Investment)**: Incremental cost basis update.
- **Negative Balance Protection**: If a sale exceeds owned quantity, the system flags it as an "Inconsistency".
- **Financial Precision**: All monetary values (WACC, Net Invest, Evaluation, Gains, and Profits) must be rounded to exactly **2 decimal places** using `Math.round(v * 100.0) / 100.0` before persistence or visualization to prevent floating-point artifacts.

## 3. Column Options
Users can toggle visibility for the following columns:
- **Buy/Sale Metrics**: Amount, Qty, Count.
- **Returns**: Cash and Qty received from bonus/right/dividends.
- **Cost Analysis**: Balance Qty, Average Cost Price (Avg CP), Average Selling Price (Avg SP).
- **Valuation**: LTP, Net Invest, Evaluation.
- **Performance**: Realized Gain, Unrealized Gain, Deductions, Net Gain, Growth %.
- **Receivables**: Receivable Amount, Total Profit, Profit %.

## 4. Filtering & Sorting
- **Sector Filter**: Dropdown to filter scrips by sector.
- **History Scrip Filter**: Filter in the transaction history view that lists only items present in the user's actual history (Context-Aware).
- **Sector Sorting**: The sector list is sorted alphabetically, with "All" always at the top.
- **Scrip Count**: Real-time display of total unique scrips in the current view.

## 6. Refresh & Sync Standards
- **User-Triggered Refresh**: To preserve battery and data, all network-intensive market syncs must be manually triggered by the user via a dedicated Refresh button.
- **Relaxed Update Logic**: 
    - To provide responsive feedback, index and LTP updates should be accepted even if the value has not changed, provided the previous update occurred more than **60 seconds** ago.
    - This ensures the "Last Updated" timestamp refreshes and the user receives a "Sync Successful" confirmation.
- **Robust Scraping**: 
    - The system must use categorized scrapers with circuit breakers to handle flaky network sources.
    - Market status (Open/Closed) must be detected via both targeted CSS elements and full-text document scans for high reliability.
- **Primary Index Sync**: The app's primary market badge (Top Bar) and the database-level index metrics must stay synchronized using a unified naming convention.
