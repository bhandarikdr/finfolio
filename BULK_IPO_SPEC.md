This document defines the logic and UI for verifying allotment results and applying for IPOs across multiple family accounts.

## 0. UI Organization
IPO functionality is split into two specialized interfaces to improve focus and reduce UI clutter:
1. **IPO Check**: Focused on allotment results (Accuracy Mode & Bulk Pro). Filters for companies with status "Allotted" or "Completed".
2. **IPO Apply**: Focused on new submissions. Filters for companies with status "Open" or "Ongoing".

## 1. BOID Management
- **Format**: Standard 16-digit numeric identifier.
- **Privacy**: BOIDs are stored locally on the device and never uploaded to FinFolio servers.
- **Auto-Extraction**: Uses Regex (`\b\d{16}\b`) to identify and extract BOIDs from pasted text or imported documents.

## 2. Hybrid Check Engine
Due to external portals implementing strict CAPTCHA and rate-limiting, FinFolio uses a **Hybrid Automation Mode**:

1. **Integrated WebView**: The app opens a WebView to the configured **Result Checker** endpoint.
2. **User Interaction**: If a CAPTCHA is detected, the WebView is brought to the foreground for the user to solve.
3. **JS Injection**: Once solved/ready, the app injects JavaScript to:
    - Select the correct company (via `resultPortalId`).
    - Input the BOID.
    - Submit the form.
    - Extract the result text from the DOM.
4. **Bulk Processing**: This process is repeated for each BOID in the list, with a delay (approx. 2s) to prevent IP blocking.

### User Interface & Interaction
- **Primary Actions**: Two fixed action buttons are provided at the top for immediate access:
    1. **Accuracy Mode (Hybrid)**: Launches the WebView automation engine for CAPTCHA-heavy portals.
    2. **Check Bulk**: Triggers a background batch check for the currently selected company.
- **Account Management**: 
    - **Toggle Selection**: Each BOID card features a switch to enable/disable it for the bulk check.
    - **Live Feedback**: Results (Allotted/Not Allotted) are displayed directly on each BOID card after a check is performed.
    - **Default/Discovery Account**: One account can be marked as "Default" (Star icon) and is used as the reference for the "Deep Search" discovery tool.

## 3. Results Parsing & Caching
- **Regex Extraction**: The result message (e.g., "...allotted 10 units") is parsed to extract the numerical count.
- **UI States**: 
    - **Allotted**: Highlighted in green with unit count.
    - **Not Allotted**: Marked in red.
- **Persistence**: Results are cached in `ipo_result_cache` for 24 hours to avoid redundant network requests.

---

## 4. Centralized Family Management
To ensure a seamless user experience, family account management is synchronized across three primary screens:
- **Credential Vault**: The master configuration screen for MeroShare login details.
    - **Member Management**: Integrated row for Adding (Manual), Pasting (Text), and Uploading (CSV) family accounts.
    - **Visibility**: Title format `Family BOIDs (#)` dynamically shows total members.
    - **Dual Toggles**: Independent control for "Result Check" and "IPO Apply" for each account.
    - **Data Inspector**: Display of parsed credentials (User, PIN, CRN) and automatic **DP Mapping** based on BOID.
    - **Validation**: "Test Login" mechanism to verify credentials against MeroShare API before bulk operations.
    - **Security**: Edit and Delete (with confirmation) features for lifecycle management.
    - **Export**: Built-in CSV export for vault backups.
- **IPO Check**: Allows enabling/disabling specific accounts for bulk result verification.
- **IPO Apply**: Allows selection of accounts for bulk application submissions.

## 4. Troubleshooting
- **CAPTCHA Loop**: If a portal enters a loop, wait 10 minutes (server-side rate-limit).
- **Stale Results**: Use the "Clear Result Cache" option to force a re-check if results were recently updated on the server.

---

## 5. Professional Mode (MeroShare API)
*Phase 4 Implementation*

Professional Mode extends the hybrid engine by using direct MeroShare API calls for zero-CAPTCHA verification and one-tap bulk applications.

### 5.1 Credential Management
- **Security**: Sensitive credentials (PIN, Password, CRN) are stored locally. Future implementation will leverage **Android Keystore** for encryption.
- **DP Mapping**: To log in, the app requires a `clientId`. This is resolved via the **DP Master Sync**.
    - **Mechanism**: The app scrapes the DP list (e.g., from ShareSansar) to map the first 8 digits of the BOID (the DP Code) to the required MeroShare `clientId`.
    - **Fallback**: A hardcoded mapping of known DP codes (e.g., `10600 -> 173`) is used if scraping fails.

### 5.2 API Interaction Logic
The `MeroShareRepository` implements the following flow:
1. **Authentication**: `POST /auth/` to obtain a JWT.
2. **Account Details**: `GET /bank/` to retrieve `accountBranchId` and bank information.
3. **Check Allotment**: `POST /active/search/` with the IPO's `resultPortalId` to fetch status without CAPTCHA.
4. **Bulk Apply**: 
    - Fetches applicable issues via `POST /applicableIssue/`.
    - Submits application via `POST /share/apply` using the stored CRN and PIN.

### 5.3 Technical Constraints
- **Validation**: Scraper configuration tests for `DP_MASTER` ensure the URL returns a valid table structure (min length 500 characters).
- **Rate Limiting**: Implementation includes exponential backoff to respect CDSC API limits.
