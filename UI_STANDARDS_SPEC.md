# UI Standards Specification

This document defines the comprehensive UI/UX standards for FinFolio, including header structures, typography hierarchies, component patterns, and interaction rules.

## 1. Screen & Header Structure
All secondary screens must follow a consistent structure.
- **Header**: Use the `SubScreenHeader` composable.
    - **Back Button**: An `ArrowBack` icon tinted with the primary theme color.
    - **Title**: Bold, large title (e.g., "Market Pulse", "IPO Check (5)"). Titles for list-based screens must dynamically include the count of available items in parentheses.
    - **Trailing Action**: (Optional) For refresh or settings buttons.
- **Section Headers**: Expandable headers within sub-screens (like Indices or Holdings) should use `typography.titleMedium` to distinguish them from item-level text while remaining smaller than the page title.
- **Subtitle Branding**: The app subtitle in the Top Bar is fixed as "PortFolio Tracker" to provide consistent brand identity.
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
    - **Process Steps**: `0xFFF59E0B` (Amber/Yellow) with `Color.Black` text for high-contrast badges (e.g., STEP 1, STEP 2).

## 4. Component Patterns
### List Items & Cards
- **Enclosure**: Wrap items (Scrips, IPOs, BOIDs) in `Card` or `Surface` with rounded corners (`8.dp` to `12.dp`).
- **Badges**: Use the `Badge` component for counts and status indicators (e.g., "Holding", "Allotted").

### Centralized Family Management
- **Synchronized Headers**: Screens managing family members (IPO Check, IPO Apply, Credential Vault) must use a unified `FamilyBoidHeader` component.
- **Header Actions**: Must include consistent icons for "Import" (File Upload), "Paste" (Content Paste), and "Add" (Person Add).
- **Selection Logic**: Always provide "Select All" and "None" quick-toggle links within the header for batch operations.

### Input Forms & Dialogs
- **Field Heights**: Standardize form input heights (Buttons and TextFields) to `60.dp`.
- **Row Alignment**: When a dropdown and text field share a row, use a Read-Only `OutlinedTextField` with a `trailingIcon` for the dropdown to ensure perfect vertical alignment.
- **Smart Selectors**: Prefer dropdowns for Scrips/Sectors to minimize errors, prioritized by "Recent Items".
- **Focus**: Use clean, focused dialogs for adding new entities that aren't in existing lists.
- **Column Selection Dialogs**:
    - The primary identifier (e.g., "Scrip") must be listed at the top, pre-selected, and disabled for modification to maintain table integrity.
    - The dialog title must dynamically show the count of selected columns, e.g., `Columns (12)`.
- **Batch Actions**: For complex configuration cards (e.g., Scraper URLs), use a per-card "Apply" button that only appears when changes are pending. This prevents accidental partial updates and provides a clear signal of state persistence.

## 5. Currency & Data Formatting
- **Currency**: Always use the `formatCurrency(amount, symbol)` helper with `userProfile.currencySymbol`.
- **Localization**: Standardize on `Locale.US` for thousand separators (e.g., `2,700.00`).
- **Date Format**: Maintain support for both AD and BS (where implemented).

## 6. Feedback & Interaction
- **Confirmations**: Mandate user confirmation for destructive actions (Deletion) or manual data overrides.
- **Notifications**: Use `Snackbar` for asynchronous completions (CSV I/O, Syncing).
- **Progress**: Always show indeterminate indicators during network or disk operations.

## 7. Responsive Layouts
- **Dashboard Consistency**: Data scope selectors (Overall/Portfolio) must be **fixed at the top** of the scrollable content area to ensure users always know which dataset they are viewing.
- **Support Panels**: Single-page layout where all core elements (Developer profile, Subject, and Message) are visible without scrolling. Profile elements (Icon, Name, Title) must be **centered** on the page. Must support **file attachments** for debugging or data verification.
- **Drawer**: High-contrast labels with increased font sizes for primary actions.

## 8. Data Grouping Standards
- **Hierarchical Expandables**: Large datasets (like History) should be grouped logically (e.g., Year -> Month) and remain **unexpanded by default** to prevent overwhelming the user.
- **Dynamic Context**: Group headers must include the count of child items in parentheses, e.g., `January (12)`.
- **Accordion-Style Lists (Accordion Mode)**: 
    - For screens like "IPO Master (Companies)", use **Single Expansion Mode** where expanding one category automatically collapses others.
    - **Animated Transitions**: Always wrap expandable content in `AnimatedVisibility` for smooth transitions.
    - **Search-Driven Expansion**: Searching within a grouped list must trigger auto-expansion of the category containing the matching result.

## 9. Sorting & Filtering Rules
- **Date-Based Sorting**:
    - **Upcoming Events**: Sort by **Opening Date (Ascending)** to highlight the next available action.
    - **Past/Current Events**: Sort by **Closing or Allotment Date (Descending)** to keep the most recent entries at the top.
- **Dynamic Filtering**: IPO Check lists must automatically include "Allotment Completed" and "Previous" categories to ensure users can check both recent and old results.

## 10. Button Layout & Overflow
- **Proportional Weighting**: In multi-button rows, use `weight` (e.g., `1.1f` vs `0.9f`) to accommodate labels of different lengths.
- **Label Integrity**: Use `10.sp` font size and `softWrap = false` for critical action buttons in tight layouts to prevent text wrap from breaking the UI.

## 11. Functional Icons
- **Exporting**: Use `Icons.Default.FileUpload` (Up Arrow) for data exports to represent "sending data out."
- **Importing/Downloading**: Use `Icons.Default.FileDownload` for data retrieval.
