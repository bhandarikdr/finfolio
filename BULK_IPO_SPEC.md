# Bulk IPO Check Specification

This document defines the logic and UI for verifying allotment results for multiple accounts simultaneously using a hybrid automation strategy.

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

## 4. Troubleshooting
- **CAPTCHA Loop**: If a portal enters a loop, wait 10 minutes (server-side rate-limit).
- **Stale Results**: Use the "Clear Result Cache" option to force a re-check if results were recently updated on the server.
