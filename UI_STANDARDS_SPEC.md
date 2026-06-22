# UI Standards Specification

This document defines the comprehensive UI/UX standards for FinFolio, including header structures, typography hierarchies, component patterns, and interaction rules.

## 1. Screen & Header Structure
All secondary screens must follow a consistent structure.
- **Header**: Use the `SubScreenHeader` composable.
    - **Back Button**: An `ArrowBack` icon tinted with the primary theme color.
    - **Title**: Bold, large title (e.g., "Market Pulse", "Bulk IPO Result").
    - **Trailing Action**: (Optional) For refresh or settings buttons.
- **Backgrounds**: Standard `MaterialTheme.colorScheme.surface`.

## 2. Typography Hierarchy
Font sizes are standardized to ensure accessibility and professional readability.

| Element Category | Size | Weight | Use Case |
| :--- | :--- | :--- | :--- |
| **Primary Titles** | `typography.titleLarge` | Bold | Main screen headers. |
| **Section Titles (Inner)** | `16.sp` | Bold | Sub-headers within cards and lists. |
| **Primary Body Text** | `14.sp` | Normal | Main descriptions and item labels. |
| **Secondary Info / Captions** | `12.sp` / `13.sp` | Normal | BOIDs, dates, secondary labels. |
| **Small Status / Version** | `11.sp` | Normal | Version numbers, minor status messages. |
| **Badges / Tiny Text** | `10.sp` | ExtraBold | Count badges and indicator text (Caps). |

## 3. Colors & Readability
- **Thematic Colors**: Prefer `MaterialTheme.colorScheme` for dynamic support (e.g., `onSurfaceVariant` instead of static `Color.Gray`).
- **Semantic Status**: Retain specific colors for financial status:
    - **Success/Gain**: `0xFF2E7D32` (Deep Green) or `0xFF10B981` (Emerald).
    - **Loss/Error**: `0xFFC62828` (Deep Red) or `0xFFEF4444` (Rose).
    - **Caution/Pending**: `0xFFF59E0B` (Amber).

## 4. Component Patterns
### List Items & Cards
- **Enclosure**: Wrap items (Scrips, IPOs, BOIDs) in `Card` or `Surface` with rounded corners (`8.dp` to `12.dp`).
- **Badges**: Use the `Badge` component for counts and status indicators (e.g., "Holding", "Allotted").

### Input Forms & Dialogs
- **Field Heights**: Standardize form input heights (Buttons and TextFields) to `60.dp`.
- **Row Alignment**: When a dropdown and text field share a row, use a Read-Only `OutlinedTextField` with a `trailingIcon` for the dropdown to ensure perfect vertical alignment.
- **Smart Selectors**: Prefer dropdowns for Scrips/Sectors to minimize errors, prioritized by "Recent Items".
- **Focus**: Use clean, focused dialogs for adding new entities that aren't in existing lists.

## 5. Currency & Data Formatting
- **Currency**: Always use the `formatCurrency(amount, symbol)` helper with `userProfile.currencySymbol`.
- **Localization**: Standardize on `Locale.US` for thousand separators (e.g., `2,700.00`).
- **Date Format**: Maintain support for both AD and BS (where implemented).

## 6. Feedback & Interaction
- **Confirmations**: Mandate user confirmation for destructive actions (Deletion) or manual data overrides.
- **Notifications**: Use `Snackbar` for asynchronous completions (CSV I/O, Syncing).
- **Progress**: Always show indeterminate indicators during network or disk operations.

## 7. Responsive Layouts
- **Support Panels**: Concise layout, avoiding vertical overflow; ensure all fields are visible without scrolling where possible.
- **Drawer**: High-contrast labels with increased font sizes for primary actions.

## 8. Functional Integrity
- **Strict Adherence**: Do not change existing functionality unless specifically instructed. Reference this documentation for all UI/logic adjustments to maintain project consistency.
