# Dashboard Specification

The Dashboard is the executive summary screen providing a high-level overview of the user's financial status, optimized for near-instant loading.

## 1. Key Metrics
All metrics are pulled directly from the **Pre-computed `Holdings` Table**, ensuring $O(1)$ performance:
- **Portfolio Value**: Total current market value of all holdings.
- **Invested Amount**: Net cash outflow for current holdings.
- **Receivable**: Funds from sales or returns not yet realized.
- **Net Gain**: Total unrealized gain/loss based on current LTP.
- **Profit**: Combined realized and unrealized profit.

## 2. Visualizations
- **Valuation Summary Card**: 3D-styled gradient card with core metrics.
- **Sector Allocation**: Semi-donut chart showing investment distribution across sectors.
- **Net Gain Statistics**: Donut chart highlighting sectors contributing to the portfolio's total gain.
- **Market Performers**: Lists the top 2 gainers and top 2 losers in the portfolio.

## 3. Data Scoping & Performance
- **Atomic Updates**: When a user adds a transaction, the Dashboard metrics update instantly without needing a full database re-scan.
- **Asset Silos**: Users can toggle between "Tradable Assets" (Stocks) and "Fixed Assets" (FDs, Bonds) to see specific dashboard views.
