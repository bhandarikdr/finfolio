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
- **Negative Balance Protection**: If a sale exceeds owned quantity, the system flags it as an "Inconsistency" rather than calculating invalid metrics.

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

## 5. UI Implementation
- **Sticky Column**: The "Scrip" name remains visible while horizontally scrolling.
- **Color Coding**: Positive gains in Green; losses in Red.
- **Stale Data Warning**: Scrips with LTP older than 24 hours are displayed with a "Stale" indicator.
