# UI Standards & Improvements

This document tracks the UI enhancements made to improve readability and user experience.

## 1. Font Size Hierarchy
To ensure better readability and consistency across the app, font sizes have been increased by approximately 2sp for most secondary and body text.

| Element Category | Previous Size | Updated Size | Description |
| :--- | :--- | :--- | :--- |
| **Section Titles (Inner)** | 14.sp | 16.sp | Sub-headers within cards and lists. |
| **Primary Body Text** | 12.sp | 14.sp | Main descriptions and item labels. |
| **Secondary Info / Captions** | 10.sp / 11.sp | 12.sp / 13.sp | BOIDs, dates, secondary labels. |
| **Small Status / Version** | 9.sp | 11.sp | Version number, minor status messages. |
| **Badges / Tiny Text** | 8.sp | 10.sp | Count badges and small indicator text. |

## 2. Color & Readability
- **Standard Gray**: `Color.Gray` has been largely replaced with `MaterialTheme.colorScheme.onSurfaceVariant` to better align with Material Design 3 and ensure appropriate contrast across different themes.
- **Status Colors**: Specific green (`0xFF2E7D32`) and red (`0xFFC62828`) have been retained for clear status indication (e.g., Allotted vs Not Allotted) but font weights were adjusted for emphasis.

## 3. Component Specific Adjustments
- **Drawer**: Increased email and version font sizes for better visibility.
- **TopAppBar**: "EXECUTIVE ANALYTICS" tagline size increased.
- **Market Pulse**: Headers and scrip details now use larger, more readable fonts.
- **IPO Checker**: Results (Allotted/Not Allotted) and units are more prominent.
- **Support Panel**: Refined to a more concise layout, removing large profile cards to ensure all fields are visible without scrolling on standard screens.
- **Transaction Forms**: Replaced standard text inputs for Scrip/Sector with "Recent Item" dropdowns to improve data entry speed and accuracy.

## 4. Implementation Details
- Applied via `@Composable` parameter updates in `MainActivity.kt`.
- Uses `MaterialTheme` for dynamic color resolution where possible.
