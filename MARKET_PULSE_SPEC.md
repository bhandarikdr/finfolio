# Market Pulse Specification

This document defines the logic, data management, and UI behavior for the "Market Pulse" utility, focusing on resilient data acquisition and storage.

## 1. Overview
Market Pulse provides a real-time overview of market performance, focusing on user-specific holdings, a custom watchlist, and broader market indices.

## 2. Resilient Data Sources
The system uses a **Multi-Source Failover** strategy to ensure high availability, driven entirely by user-configured URLs:

| Category | Typical Elements |
| :--- | :--- |
| **Market Indices** | Index tables, Market Status (Open/Closed). |
| **Scrip LTP** | Live Trading tables, Last Traded Price, Change %. |

- **User-Defined URLs**: All scraping sources are fully configurable via Settings -> Scraper Configuration.
- **Failover Logic**: The app attempts to fetch data from the prioritized list of URLs. If a source fails or returns invalid data, it automatically falls back to the next available URL.
- **Graceful Degradation**: If all sources fail, the UI displays the "Last Successful Sync" time and uses cached data.
- **Market Agnostic**: The system does not hardcode source websites, allowing users to track different global markets by providing the appropriate URLs.

## 3. Data Categories & Business Logic

### A. Market Indices
- **Persistence**: Stored in the `MarketIndices` table.
- **Fields**: `indexName`, `currentValue`, `pointChange`, `changePercent`, `timestamp`.
- **Primary Index Tracking**: 
    - The **Primary Market Index** is fully customizable via Settings (default: "NEPSE Index").
    - **Positioning**: The primary index is strictly fixed at the top of the Market Pulse indices list.
    - **Metric Mapping**: The app uses strict name matching for metrics. If a custom name is provided, the engine searches for that specific name in the configured scraper sources.
    - **Missing Data Handling**: If a custom index name is not found in the scraped data, it is displayed with `0.00` metrics to avoid inheriting incorrect data, and a warning is displayed in Settings.
- **Visibility & Configuration**: 
    - Users can configure which indices are visible via the Indices Config dialog.
    - **Persistence**: Unselecting all indices is persistent; the app will not automatically restore defaults upon refresh, respecting the user's manual configuration.

### B. My Holdings
- **Definition**: Scrips found in the `Holdings` table with `totalQuantity > 0`.
- **Manual vs. External**: 
    - **External LTP**: Pulled from scrapers for tradable scrips.
    - **Manual LTP**: User-defined prices for non-tradable or unlisted assets. Manual prices are badged "Manual" in the UI.
- **Organization**: Grouped by Sector, split into Gainers/Losers.

### C. Watchlist
- **Definition**: A manual collection of scrips managed via the `isWishlisted` flag in `ScripMaster`.
- **Price Data**: Pulled from the same `ExternalLtp` table as Holdings.

## 4. Storage & Audit Management

### Audit Log Rotation (Auto-Cleanup)
To prevent database bloat:
1. Every LTP change is logged in the `AuditLog` table for trend analysis.
2. A **Weekly Worker** prunes any `AuditLog` entries of type `LTP_CHANGE` older than 7 days.
3. Transaction logs are preserved permanently.

### Optimization (Strict Flat Logic)
1. Newly scraped LTP values are compared against the existing values.
2. The database is updated **ONLY if the LTP has changed** or if it provides a valid non-zero change while the database has none.
3. **Stale Data Protection**: If a scraper returns zero-change data (common after market close) while the database contains valid intra-day movement, the update is rejected to preserve the daily performance metrics.

## 5. UI Interaction
- **Last Sync Indicator**: Displays "Synced X mins ago" or "Offline - Using Cache".
- **Search**: Scrips can be toggled in/out of the watchlist from search results. The search header displays the total count of matching scrips in the database (e.g., "SCRIPS (303)").
- **Expansion State**: Categories are collapsed by default.
- **Scrollable Filters**: Dropdown filters (like Sector) are height-limited and scrollable to ensure UI consistency on smaller screens.
