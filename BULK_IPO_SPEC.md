# Bulk IPO Check Specification

Logic for checking IPO allotment results for multiple family members simultaneously.

## 1. IPO Master List
- **Synchronization**: Fetched from CDSC Result Portal (`iporesult.cdsc.com.np`).
- **Persistence**: Stored in `ipo_master` table to allow for searchable selection.
- **Refresh**: Manual trigger updates the local list with new company IDs from CDSC.

## 2. Bulk Check Engine
- **Input**: List of 16-digit BOIDs.
- **Processing**: Parallel API requests to CDSC.
- **Batching**: Max 20 concurrent requests to avoid server-side rate limiting.
- **Caching**: Results are stored in `ipo_result_cache` for 24 hours.

## 3. Results Parsing
- **Regex Extraction**: The string message from CDSC (e.g., "...allotted 10 units") is parsed to extract the integer unit count.
- **UI Styling**: 
    - **Allotted**: Green background, "ALLOTTED" label, unit count displayed.
    - **Not Allotted**: Red background, "NOT ALLOTTED" label, failure message.
