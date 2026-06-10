# LTP Update Specification

This document details the Last Traded Price (LTP) update mechanism for scrips and indices in FinFolio.

## 1. Data Source
- **Primary Source**: Sharesansar Live Trading (Scraped from `https://www.sharesansar.com/live-trading`).
- **Interval**: Manual refresh via the "Refresh" icon in the Top Bar or Market screen.

## 2. Update Algorithm (Surgical Update)

To maintain data integrity and avoid unnecessary database writes, the following logic is applied during every sync:

```kotlin
val existing = portfolioDao.getStoredValue(symbol)
if (scrapedValue != existing.currentValue) {
    // 1. Snapshot the current value as previous
    val previousValue = existing.currentValue
    
    // 2. Update with the new scraped value
    val newValue = scrapedValue
    
    // 3. Persist to database
    saveToDb(symbol, newValue, previousValue)
} else {
    // DO NOTHING: Value is unchanged
}
```

## 3. Database Schema

### A. ExternalLtp (For Scrips)
| Field | Type | Description |
|---|---|---|
| `symbol` | String (PK) | Scrip symbol (e.g., AHPC) |
| `ltp` | Double | Current Last Traded Price |
| `previousLtp` | Double | LTP from the previous distinct price update |
| `source` | String | Source name (e.g., "Scraped") |
| `timestamp` | Long | System time of last update |

### B. MarketIndexEntity (For Indices)
| Field | Type | Description |
|---|---|---|
| `indexName` | String (PK) | Name of the index (e.g., NEPSE Index) |
| `currentValue` | Double | Current value of the index |
| `previousValue` | Double | Value from the previous distinct update |
| `changePercent` | Double | Calculated percent change |

## 4. Calculation Rules
- **Change Value**: `currentValue - previousValue`
- **Change %**: `((currentValue - previousValue) / previousValue) * 100`
- **Initial State**: If no previous value exists, a default placeholder (value * 0.99) is used for the first calculation to avoid zero-division errors.
