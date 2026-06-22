# IPO Master & Bulk Result Checker Workflow

This document provides a comprehensive guide to the IPO management system in FinFolio Pro, including data sources, synchronization logic, and UI implementation.

## 1. Data Sources & Integration Links

The application uses a triple-source synchronization engine to ensure data availability and reliability.

| Source | Link | Purpose | Sync Method |
| :--- | :--- | :--- | :--- |
| **CDSC Result Portal** | [iporesult.cdsc.com.np](https://iporesult.cdsc.com.np/result/company/list) | Primary source for allotment results and CDSC Company IDs. | JSON API |
| **Nepali Paisa** | [nepalipaisa.com/ipo](https://www.nepalipaisa.com/ipo) | Alternative source for active and upcoming IPOs. | Jsoup Scraper |
| **SEBON Pipeline** | [sebon.gov.np/ipo-pipeline](https://sebon.gov.np/ipo-pipeline) | Tracks companies currently under review for future issuance. | Jsoup Scraper |

## 2. Technical Implementation

### Data Layer (`IpoMaster` Entity)
Stored in the `ipo_master` Room table. Key fields include:
- `cdscCompanyId`: The unique ID required to query the CDSC result API.
- `resultAvailable`: Boolean flag used to populate the Bulk Result Checker dropdown.
- `status`: Categorized as *Active*, *Allotted*, *Pipeline*, or *Closed*.
- `source`: Tracks which site the record originated from.

### Synchronization Engine (`IpoRepository.syncIpos`)
1. **CDSC JSON Sync**: Fetches the list from the official result portal. It marks all entries as `resultAvailable = true`.
2. **Nepali Paisa Scraping**: Uses heuristic text matching (`"IPO"`) to identify issued companies from the "Investment Calendar".
3. **SEBON Pipeline Scraping**: Parses the SEBON table to identify companies in the regulatory review phase.
4. **Duplicate Prevention**: Uses `getByName` and `getBySymbol` lookups to prevent redundant entries across multiple sources.

### BOID Management & Extraction
The **Bulk Result Checker** supports a flexible import schema:
- **Format**: `Name, 16-digit BOID` (one per line).
- **Auto-Extraction**: The logic uses Regex (`\b\d{16}\b`) to find any 16-digit number in a line of text. The remaining text is automatically treated as the holder's name.

## 3. UI Navigation & User Workflow

### IPO Master Screen
- **Refresh**: Triggers the multi-source sync with a linear progress bar and Toast feedback.
- **Search**: Real-time filtering by company name, symbol, or ID.
- **Archive/Hide**: Use the eye icon in the IPO Master List to hide older or irrelevant IPOs. 
- **View Hidden**: Access archived items by clicking the Archive (Box) icon in the top header of the IPO Master screen.
- **Restore**: Click the eye-off icon in the Archive view to bring an item back to the main list.
- **Manual Addition**: Use the `+` button to add companies if they are not yet listed in the automated sources.

### Bulk IPO Result Screen
- **Dropdown**: Automatically populated with "Active" or "Allotted" IPOs from the Master list.
- **Family Accounts**: Supports single addition, batch paste, or text file upload.
- **Check Results**: Asynchronously queries the CDSC API for each BOID and displays color-coded results (*Success/Failure*).

## 4. Troubleshooting Reference
- **Empty List**: If sync fails, check internet connectivity. Some official Nepal government sites may require specific User-Agent headers (handled in `IpoRepository`).
- **Missing Results**: Only IPOs with a valid `cdscCompanyId` can be checked for results. Ensure this field is populated (automatically done for CDSC synced items).
