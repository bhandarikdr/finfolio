# Import/Export Specification (formerly Data I/O)

This document defines how FinFolio handles transaction recording and external data imports/exports.

## 1. Manual Transaction Entry
- **Scrip & Sector Selection**: Uses dropdown buttons for selecting from recently used items to speed up entry.
- **Manual Add**: Plus (+) buttons allow adding new scrips or sectors via dedicated dialogs.
- **Sector Auto-sync**: Automatically suggests a sector based on previous entries for the same scrip when a scrip is selected.
- **Actions**: Buy, Sale, Returns (Bonus/Right/Dividend).

## 2. CSV Imports
- **Standard Transaction CSV**: Fields include `Date, Item, Action, Qty, Amount, Type`.
- **WACC CSV**: Specialized import for Weighted Average Cost Price data. Fields: `Scrip, Qty, Rate, Cost`.
- **Portfolio CSV**: Meroshare export format for aligning holdings. Fields: `Scrip, LTP, Balance`.
- **Smart Portfolio Sync**: Before importing a Portfolio CSV, the system calculates and prompts the user with the number of adjustment records that will be created.

## 3. Bulk BOID Management
- **Manual Add**: 16-digit BOID + Holder Name.
- **Paste Mode**: Smart-parser that extracts valid 16-digit BOIDs from unstructured text.
- **File Upload**: Text file import for BOID lists.

## 4. Export
- **CSV Export**: Generates a standard CSV of all recorded transactions. 
- **LTP Snapshots**: The export includes an `LTP` column capturing the market price at the time of export for future reference without needing to re-import market data.
