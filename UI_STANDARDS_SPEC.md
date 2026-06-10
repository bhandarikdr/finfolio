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

## 4. Expandable Sections
- Use `ExpandableHeader` for main sections.
- For nested groupings (like Sectors in Holdings), use `SectorExpandableHeader` which includes an item count next to the title (e.g., "BANKS 5").
