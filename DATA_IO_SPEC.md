# Data I/O Specification

This document defines how FinFolio handles transaction recording and external data imports.

## 1. Manual Transaction Entry
- **Scrip Auto-complete**: Searches the local `ScripMaster` table.
- **Sector Auto-sync**: Automatically suggests a sector based on previous entries for the same scrip.
- **Actions**: Buy, Sale, Returns (Bonus/Right/Dividend).

## 2. CSV Imports
- **Transaction CSV**: Generic format for bulk adding records.
- **WACC CSV**: Specialized import for Weighted Average Cost Price data.
- **Portfolio CSV**: Meroshare export format for aligning holdings.

## 3. Bulk BOID Management
- **Manual Add**: 16-digit BOID + Holder Name.
- **Paste Mode**: Smart-parser that extracts valid 16-digit BOIDs from unstructured text.
- **File Upload**: Text file import for BOID lists.

## 4. Export
- **CSV Export**: Generates a standard CSV of all recorded transactions for backup or external analysis.
