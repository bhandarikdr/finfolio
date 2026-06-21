# UI Standards Specification

This document defines the UI standards for secondary screens (sub-views) in the FinFolio application, particularly those accessible from the "More" tab.

## 1. Header Structure
All secondary screens must use the `SubScreenHeader` composable to maintain consistency.
- **Back Button**: An `ArrowBack` icon tinted with the primary theme color.
- **Title**: Bold, large title (e.g., "Market Pulse", "Bulk IPO Result").
- **Trailing Action**: (Optional) For refresh or settings buttons.

## 2. Colors & Typography
- **Primary Headers**: `MaterialTheme.typography.titleLarge`, `FontWeight.Bold`.
- **Secondary Headers**: `FontWeight.ExtraBold`, uppercase, `Color.Gray`, `11.sp` or `10.sp`.
- **Backgrounds**: Standard `MaterialTheme.colorScheme.surface`.

## 3. List Item Patterns
- **Cards**: All items (Scrips, IPOs, BOIDs) should be wrapped in `Card` or `Surface` with rounded corners (8.dp to 12.dp).
- **Badges**: Use `Badge` for counts and status indicators (e.g., "Holding", "Allotted").

## 5. Currency & Numbers
- **Formatting**: Always use the `formatCurrency(amount, symbol)` helper.
- **Dynamic Symbol**: Retrieve the symbol from `userProfile.currencySymbol`.
- **Locale**: Standardize on `Locale.US` for comma separators in thousands.

- **Input Fields**: Prefer dropdown selectors for repetitive data (e.g., Scrips, Sectors) to minimize typing errors.
- **Dynamic Recent Items**: Dropdowns should prioritize "Recent Items" based on the user's transaction history.
- **Field Heights**: Standardize form input heights (Buttons and TextFields) to `60.dp` for better visual alignment and touch target size.
- **Row Alignment**: When a dropdown and text field are in the same row, the dropdown must be implemented using a Read-Only `OutlinedTextField` with a `trailingIcon` (instead of a Button) to ensure perfect vertical and label alignment.
- **Dialogs for New Entry**: Use clean, focused dialogs for adding new entities that aren't in existing lists.

## 9. Functional Integrity
- **Strict Adherence**: Do not change any existing functionality unless specifically instructed to do so. Documentation references are mandatory for all UI and logic adjustments to maintain project consistency.

## 7. Responsive Feedback & Confirmations
- **Action Confirmation**: Always ask for user confirmation before destructive actions (e.g., Deletion) or significant data changes (e.g., Adding/Updating records manually).
- **Completion Notifications**: Use `Snackbar` to notify users upon successful completion of background or asynchronous tasks such as CSV Imports, Exports, and Portfolio Syncing.
- **Progress Indicators**: Show loading states or progress indicators during long-running I/O operations to keep the UI responsive.

## 8. Settings Page
- **Location**: Accessible from the "More" tab.
- **Sections**: Grouped by category (Regional, System, etc.).
- **Interactive Elements**: Use `FilterChip` for selection menus and `FilterRow` for layout.
