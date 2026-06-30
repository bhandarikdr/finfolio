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
- **Security & Privacy**: 
    - **Masking**: Sensitive credentials like Transaction PINs and CRN numbers must be masked in UI cards (e.g., `••••`) and only visible/editable within dedicated secure dialogs.
    - **Credential Logic**: Usernames and DPs for external logins (like MeroShare) should be auto-parsed from the primary identifier (BOID) where possible to minimize manual entry errors. 
        - **DP Logic**: 5 digits starting from the 4th position of the 16-digit BOID.
        - **Username Logic**: All digits to the right of the DP.
    - **Fuzzy Portal Mapping**: For external portals (like CDSC IPO Result), the app must prioritize matching entities by name (fuzzy matching) over internal IDs. This ensures the app remains functional even if the portal's internal numbering changes.
- **Badges**: Use the `Badge` component for counts and status indicators (e.g., "Holding", "Allotted").

### Centralized Family Management
- **Synchronized Headers**: Screens managing family members (IPO Check, IPO Apply, Credential Vault) must use a unified `FamilyBoidHeader` component.
- **Header Actions**: Must include consistent icons for "Import" (File Upload), "Paste" (Content Paste), and "Add" (Person Add).
- **Selection Logic**: Always provide "Select All" and "None" quick-toggle links within the header for batch operations.

### Input Forms & Dialogs
- **Field Heights**: Standardize form input heights (Buttons and TextFields) to `60.dp`.
- **Clear Buttons**: Use a `trailingIcon` (Close/X) in `OutlinedTextField` for searchable or long-form text fields to allow one-tap clearance of the entire content. Icon size should be `18.dp`.
- **Row Alignment**: When a dropdown and text field share a row, use a Read-Only `OutlinedTextField` with a `trailingIcon` for the dropdown to ensure perfect vertical alignment.
- **Smart Selectors**: Prefer dropdowns for Scrips/Sectors to minimize errors, prioritized by "Recent Items".
- **Attachment Management**: 
    - **Chips**: Use `AssistChip` or similar small chips for file attachments.
    - **Removal**: Each attachment chip must include a trailing close icon to allow individual removal.
    - **Visual Feedback**: Show the dynamic count of attachments, e.g., `Attachments (2)`.
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
    - **Dynamic Context (Filter Counts)**: Group headers and filter selectors (Sectors, Scrips, Years) must include the count of child items in parentheses, e.g., `January (12)`, `Sector: All (45)`, `Scrip: AAPL (12)`.
    - **Filter Count Consistency**: The count in the filter title must match the number of visible items when that filter is applied.

## 9. Sorting & Filtering Rules
- **Date-Based Sorting**:
    - **Upcoming Events**: Sort by **Opening Date (Ascending)** to highlight the next available action.
    - **Past/Current Events**: Sort by **Closing or Allotment Date (Descending)** to keep the most recent entries at the top.
- **Dynamic Filtering**: IPO Check lists must automatically include "Allotment Completed" and "Previous" categories to ensure users can check both recent and old results.

## 10. Button Layout & Overflow
- **Proportional Weighting**: In multi-button rows, use `weight` (e.g., `1.1f` vs `0.9f`) to accommodate labels of different lengths.
- **Label Integrity**: Use `10.sp` font size and `softWrap = false` for critical action buttons in tight layouts to prevent text wrap from breaking the UI.

## 11. Functional Icons
- **Exporting**: Use `Icons.Default.Download` or `Icons.Default.FileUpload` (Up Arrow) for data exports to represent "sending data out."
- **Importing/Downloading**: Use `Icons.Default.FileDownload` for data retrieval.
- **Surgical Toggles**:
    - **Atomic Interaction**: Toggles for independent features (e.g., "Result Check" vs "IPO Apply") must be decoupled at the repository level.
    - **Interaction Source**: Ensure click listeners are placed on the `Row` or `Text` label rather than just the `Switch` to increase the touch target and prevent double-triggering.
