# Matrix (Metrics) Specification

The Matrix screen provides a granular, tabular view of every scrip in the portfolio with configurable analytics.

## 1. Column Options
Users can toggle visibility for the following columns (displayed in ascending order in the configuration dialog):
- **Buy/Sale Metrics**: Amount, Qty, Count.
- **Returns**: Cash and Qty received from bonus/right/dividends.
- **Cost Analysis**: Balance Qty, Average Cost Price (Avg CP), Average Selling Price (Avg SP). *Note: Avg CP and Avg SP display the regional currency symbol.*
- **Valuation**: LTP, Net Invest, Evaluation. *Note: LTP displays the regional currency symbol.*
- **Performance**: Realized Gain, Unrealized Gain, Deductions, Net Gain, Growth %.
- **Receivables**: Receivable Amount, Total Profit, Profit %.

## 2. Filtering & Sorting
- **Sector Filter**: Dropdown to filter scrips by sector (e.g., "HydroPower").
- **Scrip Count**: Real-time display of total unique scrips in the current view.

## 3. UI Implementation
- **Sticky Column**: The "Scrip" name remains visible while horizontally scrolling through metrics.
- **Color Coding**: Positive gains are in Green; losses are in Red.
