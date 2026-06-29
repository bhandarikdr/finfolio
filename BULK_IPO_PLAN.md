# Robust Bulk IPO Check & Apply Plan

This document outlines the implementation for IPO management, utilizing the private MeroShare API to bypass CAPTCHAs, automate results checking, and enable one-tap bulk applications.

## 🎯 Objectives
- **Zero-Captcha Results**: Use MeroShare login to fetch official allotment status.
- **Bulk Application**: Apply for 10 units (or custom) for all family members in one click.
- **Automated DP Mapping**: Automatically identify Depository Participants (DP) from BOIDs.
- **Data Security**: Secure local storage of MeroShare credentials (Username, Password, CRN, PIN).

---

## 🏗️ Technical Architecture

### 1. Data Layer Enhancements
- **DP Master Sync**: Scrape `https://www.sharesansar.com/dp-member-list` to map the first 8 digits of a BOID to the required MeroShare `clientId`.
- **Encrypted Account Storage**: Extend the user database to store MeroShare credentials for each BOID entry.
- **Unified Result Cache**: Merge results from "Accuracy Mode" (WebView) and "Pro Mode" (API) into a single source of truth.

### 2. MeroShare API Service (`MeroShareRepository`)
Implementation of the core logic found in `main.py`:
- `POST /auth/`: Login and retrieve JWT Authorization token.
- `GET /bank/`: Fetch bank details and `accountBranchId`.
- `POST /applicableIssue/`: List active IPOs/FPOs.
- `POST /active/search/`: Fetch application history (Allotment Status).
- `POST /share/apply`: The submission endpoint for applications.

---

## 📋 Implementation Checklist

### Phase 1: Configuration & DP Metadata
- [x] Add `DP_MASTER` to `ScraperCategory` enum in `FinancialModels.kt`.
- [x] Set `https://www.sharesansar.com/dp-member-list` as the default source in `ScraperDefaults`.
- [x] Create `DpMaster` database table and DAO.
- [x] Implement `MarketRepository.fetchDpMaster()` to populate the mapping.

### Phase 2: Secure Account Management
- [x] Update `BoidEntry` model to include `username`, `password`, `crn`, and `pin`.
- [x] Update `Boids` database table with new columns and migration.
- [x] Implement a "Credential Vault" UI to allow users to securely add MeroShare details for their BOIDs.
- [x] Logic to auto-fill `DP Code` by parsing the first 8 digits of the BOID.

### Phase 3: Pro-Check Workflow (Results)
- [x] Implement `MeroShareRepository` with OkHttp (SSL bypass for CDSC servers).
- [x] Build `BulkIpoViewModel.startProCheck()`:
    - Iterate accounts -> Authenticate -> Fetch Report -> Update Cache.
- [x] UI: Add "Switch to Professional Mode (Login Required)" toggle.

### Phase 4: Bulk Apply Workflow (Submission)
- [x] Implement `MeroShareRepository.applyForIpo()`:
    - Get Bank Info -> Build Payload -> Submit Application.
- [x] Implement `BulkIpoViewModel.startBulkApply()` for automated submissions.
- [x] UI: "Bulk Apply" button with a confirmation dialog showing total cost (e.g., 10 users x 1000 = 10,000).
- [x] UI: Separate "IPO Check" and "IPO Apply" interfaces in Utilities.
- [x] UI: Centralized and synchronized Family BOID management header.
- [x] UI: Execution log/console (via Member Activity) to show progress for each user.

### Phase 5: Reliability & Error Handling
- [x] Handle "Password Expired" or "Account Locked" scenarios (via error messages).
- [x] Implement delays for CDSC API rate limits (1.5s between accounts).
- [ ] Encrypt sensitive fields (Password/PIN) using Android Keystore.

---

## 🎨 UI & UX Standards for Member Cards

To ensure robustness and clarity in IPO management, the following standards are applied to the **Member Card** (`BoidItem`):

1.  **Vault Status**: A green shield (`VerifiedUser` icon) must be shown if MeroShare credentials (Username/Password) are available in the local vault.
2.  **Activity State**:
    *   **Not Checked**: Subtle hint ("Not checked for this company") if no result exists for the selected IPO.
    *   **Checking**: A blue "CHECKING..." badge with a sync icon during automated background checks.
    *   **Allotted**: A prominent green badge with a checkmark and the specific allotment message.
    *   **Not Allotted**: A grey/muted badge showing the official portal rejection message.
    *   **Error**: A red badge showing specific failure reasons (e.g., "Login Failed", "Portal Unavailable").
3.  **Audit Trail**: Every result check must include a "Checked at" timestamp (e.g., `Jan 05, 10:30 AM`) to distinguish old cache from fresh results.
4.  **Reset Capability**: A refresh icon must be available on individual member cards to clear cached results and allow re-checking.

---

## 🔄 User Workflow (Pro Mode)

1. **Setup**: User enters MeroShare credentials for their family members once.
2. **Result Check**: 
   - User taps "Check All".
   - App logs into each account in the background.
   - Allotment status is updated instantly (No CAPTCHA).
3. **Application**:
   - App detects a new "Ordinary Shares" IPO.
   - User taps "Apply for All".
   - App automatically handles PIN/CRN submission for every account.

---

## 🛠️ Maintenance & Standards
- Follow the existing **Repository-ViewModel** pattern.
- Maintain **Compose UI** standards with `Material3`.
- Use the **Centralized Scraper Configuration** for all URL management.
- Preserve the existing "Accuracy Mode" (WebView) as a fallback for users who don't want to provide passwords.

---

## ⚠️ Technical Constraints & WebView Hacks

### CDSC Result Portal Centering
The official CDSC result portal (`iporesult.cdsc.com.np`) is an Angular SPA that uses rigid, non-flexible layouts.
- **Problem**: The portal uses `absolute` or `fixed` positioning internally for its main card. Standard CSS Flexbox centering on the `body` or `html` tags fails because these positioned elements ignore the flex container's alignment rules, often resulting in the portal being pinned to a coordinate that is cropped or off-screen in a mobile WebView.
- **Solution**: 
    1. Force a global CSS reset in the WebView using `* { position: static !important; }` to break the rigid positioning.
    2. Explicitly re-apply `position: relative !important; margin: auto !important;` to the primary container (`mat-card` or `.card`).
    3. Set `useWideViewPort = false` and `loadWithOverviewMode = false` in Android `WebSettings` to prevent the WebView from using a desktop-sized canvas.
    4. Continuously monitor and re-apply these styles via `setInterval` as Angular may re-render and strip injected styles during its lifecycle.
