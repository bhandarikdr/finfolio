# Import/Export Specification (formerly Data I/O)

This document defines how FinFolio handles transaction recording and external data imports/exports with a focus on data safety.

## 1. Manual Transaction Entry
- **Scrip & Sector Selection**: Uses dropdown buttons for selecting from recently used items.
- **Sector Auto-sync**: Automatically suggests a sector based on previous entries for the same scrip.
- **Actions**: Buy, Sale, Returns (Bonus/Right/Split/Dividend), SIP.

## 2. CSV Imports & Data Safety
To prevent data loss during complex imports, FinFolio implements a **Safety-First Protocol**:

### A. Pre-Flight Export
- Every time a user initiates a "Bulk Import", the app automatically generates a **JSON Snapshot** of the current database in the app's internal cache.
- If the import causes mathematical inconsistencies (e.g., negative balances), the user is prompted to "Restore to Snapshot".

### B. Standard Transaction CSV
- **Fields**: `Date, Item, Action, Qty, Amount, Sector`.
- **Validation Layer**: The importer checks for duplicate entries based on `(Date, Item, Action, Qty)` to prevent double-counting.

### C. Smart Portfolio Sync (Alignment)
- **Source**: Generic Portfolio CSV export or "My Holdings" export.
- **Logic**: Aligning local history with external statements. If there is a mismatch in `Balance Qty`, the app prompts to create an "Adjustment" transaction rather than overwriting historical records.

## 3. Bulk BOID Management
- **Manual Add**: 16-digit BOID + Holder Name.
- **Paste Mode**: Smart-parser that extracts valid 16-digit BOIDs from unstructured text.

## 4. Export
- **Transaction Export**: Generates a standard CSV of all recorded transactions. 
- **Holdings Export**: Specialized export from the Market Pulse screen focused on current asset valuation.
- **Precision Standard**: All calculated numeric fields in exports (Amounts, Rates, Values) must be formatted to exactly **2 decimal places** using `String.format(Locale.US, "%.2f", value)` to ensure compatibility and readability.
- **Format**: `Scrip, Sector, Qty, Previous LTP, Previous Amount, LTP, Current Amount`.
