# IPO Management UI Standards

This document outlines the standard UI/UX patterns established for IPO-related screens (Check, Apply, Master) to ensure consistency and efficiency.

## 1. Top Section Layout (Compact Header)
*   **Header**: Use `SubScreenHeader` for the screen title and back navigation.
*   **No Extra Padding**: Avoid large spacers between the title and the primary action element (usually a dropdown).
*   **Selection Component**: Use `ExposedDropdownMenuBox` with an `OutlinedTextField`.
    *   **Alignment**: All search/selection text within the field must be **Center-Aligned** (`TextAlign.Center`).
    *   **Context Info**: 
        *   **IPO Check**: Show **Allotment Date** and Scrip in dropdown items.
        *   **IPO Apply**: Show **Closing Date** and Scrip in dropdown items.
*   **Primary Action**: Keep top-level action buttons (e.g., "Auto Check") compact (height approx. 40.dp-48.dp) with descriptive, labeled icons.

## 2. Family Member Cards (`BoidItem`)
*   **Condensed Padding**: Internal padding should be approx `horizontal = 12.dp, vertical = 10.dp` to maximize screen real estate.
*   **Self-Identification**:
    *   The primary user (matched via `UserProfile.boid`) must be highlighted with a **"ME"** badge next to their name.
    *   Restricted actions (like "Add to Transaction") only appear on the "ME" card.
*   **Status-Aware Buttons**:
    *   **Unchecked State**: Show a prominent "Check via CDSC Portal" button.
    *   **Checked/Result State**: Show the result in a color-coded surface (Green for Allotted, Red for Not Allotted).
    *   **Reset/Stop**: Provide a Reset icon (or "X" during check) in the result area.
*   **Confirmation Logic**: Any destructive action (Reset Result, Stop Check) must trigger a confirmation `AlertDialog`.

## 3. official CDSC Portal Dialog
*   **Clean Navigation**: Remove unnecessary subtitles ("Select member...").
*   **Header Proximity**: Move member selection chips (SuggestionChips) directly under the header.
*   **Inline Status**: Display real-time loading/status messages on the right side, inline with member chips, to save vertical space.
*   **CAPTCHA Handling**: Use a highly visible warning banner (`errorContainer`) when user interaction is required.

## 4. Backend & Data Standards
*   **Persistent Results**: Use a **Merge Strategy** when updating member activity. Fetch existing records and only overwrite new data (e.g., preserving application history when updating allotment results).
*   **Stable Identifiers**: Use a combination of **Scrip and Company Name** for mapping. Prefer preserving the name established during the first record creation to prevent "orphaned" results due to minor external name changes.
*   **Contextual Auto-Selection**: On screen entry, automatically select the most relevant IPO for that context (e.g., most recent "Closed" IPO for the Check screen).

## 5. Companies / Master Screen Standards
*   **Flattened Hierarchy**: Avoid redundant outer headers (e.g., "IPO Master"). List categories like "Open Issues", "Upcoming Issues", and "Closed Issues" directly at the top level.
*   **Top-Level Search**: Place the search bar at the very top of the screen (below the header) for immediate accessibility across all categories.
*   **Accordion Performance**: Use a "Lazy Block" structure for accordions. Instead of rendering all cards inside a single `item` block, use `items()` blocks within the `LazyColumn` for expanded sections. This ensures high performance and instant responsiveness even with hundreds of items.
*   **Single-Expansion Mode**: Expanding one category header should automatically collapse others to maintain a clean and focused view.
*   **Unified Count System**: Use right-aligned **Badges** on category headers to show counts. Remove counts from the text labels to avoid redundancy.
*   **Rounding & Spacing**: Use a standard **16.dp** corner radius for all headers and cards. Reduce vertical gaps between category headers to maximize information density.
*   **Empty State CTA**: Provide a clear "Call to Action" (e.g., a "Sync" button) inside sections when they are empty to guide the user.

---
*Last Updated: 29 June 2026*
