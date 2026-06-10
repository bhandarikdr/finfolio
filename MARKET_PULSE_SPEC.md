# Market Pulse Specification

This document defines the logic and data management for the "Market Pulse" utility in FinFolio.

## 1. Data Categories

### A. Indices
- **Data Source**: Scraped live from market sources (e.g., ShareSansar).
- **Persistence**: Stored in `MarketIndices` table.
- **Update Logic**: 
    1. Newly fetched `currentValue` is compared against the stored `currentValue` in `MarketIndices`.
    2. Database update occurs **ONLY if the value has changed**.
    3. Before updating, the existing `currentValue` is copied to `previousValue`.
    4. `currentValue` is then updated with the newly fetched value.
- **Metrics**:
    - `Value`: The current index value.
    - `Change %`: Calculated as `((currentValue - previousValue) / previousValue) * 100`.
- **Display Selection**:
    - **NEPSE Index**: Permanently fixed and always displayed.
    - **Others**: Selectable via the Settings icon in the Indices header. Selected items are shown alongside the NEPSE index.
    - **Selection Persistence**: Only indices explicitly checked in the Settings dialog are displayed (exact match).

### B. My Holdings
- **Data Source**: Derived from user's transaction history + Live Scrip Scraper.
- **Persistence**: Scrip prices stored in `ExternalLtp` table.
- **Update Logic**: 
    1. Newly fetched `ltp` is compared against the stored `ltp` in `ExternalLtp`.
    2. Database update occurs **ONLY if the ltp has changed**.
    3. Before updating, the current `ltp` is copied to `previousLtp`.
    4. `ltp` is updated with the newly fetched value.
- **Organization**: Scrips are grouped by their **Sector (Type)**.
- **Expandable Sectors**: Each sector subheading is expandable and displays the number of scrips it contains (e.g., "BANKS 5").
- **Metrics**:
    - `LTP`: Last Traded Price.
    - `Change %`: Calculated as `((currentLtp - previousLtp) / previousLtp) * 100`.

### C. Watchlist
- **Data Source**: Scrips manually added by the user + Live Scrip Scraper.
- **Contains**: `Add` button to add the list items.
- **Persistence**: Same as Holdings (`ExternalLtp` table + `isWishlisted` flag in `ScripMaster`).
- **Update Logic**: Same as Holdings.
- **Metrics**: Same as Holdings.
- **Addition Logic**: The `Add` button specifically searches for Scrips (Indices are excluded in this mode).

## 2. Search & Interaction
- **Search Logic**: Searching is primarily done through the `+` button in the Watchlist section.
- **Combined Search Source**: Search results combine data from the fixed `ScripMaster` list and the `Live Trading` scraper to ensure all active scrips are discoverable.
- **Functionality**: Scrips (Holdings/Watchlist) are searchable and can be toggled in/out of the watchlist directly from the search dialog.
- **Locate & Expand**: Selecting a search result identifies its category (Indices, Holdings, or Watchlist) and automatically expands that section to show the item.

## 3. Database Schema Reference
- `MarketIndexEntity`: `indexName` (PK), `currentValue`, `previousValue`, `changePercent`.
- `ExternalLtp`: `symbol` (PK), `ltp`, `previousLtp`, `source`, `timestamp`.
