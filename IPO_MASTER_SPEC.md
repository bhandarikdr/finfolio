# IPO Master Specification

This document defines the management and synchronization of the IPO Master list, which serves as the source of truth for upcoming and ongoing share offerings.

## 1. Data Acquisition

The IPO list is populated from user-configured sources in **Settings -> Scraper Configuration**.

| Category | Purpose | Method |
| :--- | :--- | :--- |
| **IPO Listing** | Primary list of IPO, FPO, and Right Shares. | JSON/HTML Scraper |
| **IPO Result Mapping** | Obtaining internal IDs required for result portals. | JSON API |

### Synchronization Logic
- **Event-Driven**: Sync is triggered manually by the user or upon first app launch if the database is empty.
- **Order Preservation**: The app preserves the order of items returned by the API (often chronological) using an internal timestamp.
- **Deduplication**: Companies are uniquely identified by their name (Primary Key).

## 2. Technical Implementation

### Data Layer (`IpoMaster` Entity)
The following attributes are tracked for each offering:
- `companyName`: Primary identifier.
- `scrip`: Market symbol (if available).
- `shareType`: IPO, FPO, or Right Share.
- `units`: Total number of shares being offered.
- `openingDate` / `closingDate`: The subscription window.
- `status`: Current phase (e.g., Open, Closed, Allotted).
- `resultPortalId`: The specific ID required to query results from external portals.

### Organization & UI
The IPO Master page organizes listings into three distinct expandable dropdowns:
1. **Upcoming Issues**: Offerings that haven't opened yet (Opening Date > Today).
2. **Current Issues**: Active offerings (Open/Active/Closed) or those allotted within the last 7 days.
3. **Previous Issues**: Historical offerings (Allotted > 7 days ago).

As users modify the status or allotment dates, the cards automatically migrate between these categories in real-time.

### ID Mapping & Discovery
Since listing APIs and result portals often use different internal IDs, FinFolio implements:
1. **Fuzzy Matching**: Automated name-based matching between listing and mapping sources.
2. **Deep Discovery (Magic Wand)**: Users can trigger a targeted "Auto Find" for a specific company. The discovery engine starts searching from the ID of the most recently allotted company and searches upwards, leveraging the sequential nature of portal IDs.
3. **Manual Entry**: Users can override or set the `resultPortalId` manually if automation fails.

## 3. Troubleshooting
- **Missing Company**: Ensure the "IPO Listing" URL is correct and the site is reachable.
- **Mapping Failures**: If a company appears but lacks a "Portal ID", use the **Deep Search** tool or manual entry in the IPO detail screen.
