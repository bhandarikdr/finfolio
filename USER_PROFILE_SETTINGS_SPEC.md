# User Profile and Setting Specs

Details regarding user personalization and profile management.

## 1. Identity Data
- **Name**: Displayed in the Navigation Drawer and support requests. Editable via My Profile.
- **Email**: Used for "Registering" the app instance and as a "Reply-To" for support emails. Editable via My Profile.
- **My BOID**: (Optional) 16-digit primary BOID of the user. Used to identify the "ME" card in family lists for restricted actions like transaction recording.

## 2. App Settings & Security
Users can configure their application experience through the Settings page:
- **Currency Symbol**: Choose from रु., $, €, £, ¥, ₹.
- **Date Format**: Support for AD/BS toggle with round-trip verification logic.
- **App PIN Lock**: Optional 4-digit PIN to secure the app from unauthenticated access. 
- **Primary Market Index**: 
    - Customizable target index name (defaults to "NEPSE Index") used for the home screen and top bar metrics.
    - **Switching vs Renaming**: Changing the Primary Index name acts as a switch. The app will search for the new name across all configured scraper URLs. If the name is not found, metrics will show `0.00` until a matching source is found.
    - **Missing Index Warning**: Real-time validation in the Settings UI displays a red warning label (*"Primary Index '[Name]' not found in market data sources."*) if the custom name does not match any data from active scraper URLs.

## 3. Scraper Configuration
Users can manage prioritized lists of URLs for each scraper category:
- **Prioritized Fallback**: App attempts to fetch data using URLs in the order they are listed. If the first fails, it automatically falls back to the next.
- **Batch Editing**: Changes to URLs within a category are staged locally. A contextual "Apply" button appears in the category header when pending changes exist, allowing for batch persistence.
- **Connectivity Testing**: Users can test individual URLs. Testing logic is robust, bypassing common SSL certificate issues and supporting diverse content types (HTML/JSON) to ensure high reliability.
- **Safe Deletion**: To ensure reliability, the delete option is automatically disabled if a category contains only one URL.
- **Factory Reset**: A "Restore All" option allows resetting all scraper categories to their default priority lists.

## 4. Debug & Support
Features to assist users in resolving technical issues:
- **Export Debug Logs**: Allows users to save a `finfolio_debug_logs.txt` file containing system and network logs for their own review.
- **Send to Developer**: One-click support workflow. 
    - Automatically exports current logs to a temporary cache file.
    - Navigates to the Support screen with pre-filled subject ("Bug Report & Debug Logs") and a default message.
    - Automatically attaches the log file for submission via email.
- **Clear Internal Logs**: Manual maintenance option to wipe the local log database.

## 5. Global Profile Drawer
- Accessed via the user icon in the Top Bar.
- **Drawer Close**: A sleek left-pointing arrow button positioned at the top right of the profile header.
- Contains quick links to:
    - **My Profile**: Edit Name, Email, and BOID.
    - **Settings**: Manage regional preferences, Security (PIN Lock), and Debug options.
    - **Support**: Direct developer contact.
    - **Exit**: Close the application immediately.

## 6. System Maintenance
- **Flush All Data**: Irreversible action available in Settings. Wipes all transactions, prices, BOIDs, and custom settings. Requires user confirmation via alert.

## 7. Support Workflow
- **Centered Layout**: Professional single-page layout with centered developer credentials.
- **Integrated Email**: Triggered via system email client using `ACTION_SEND_MULTIPLE`.
- **Contextual Body**: Automatically prepends system info (Android Version, App Version) and User Identity (Name/Email) to the message body.
- **Attachment Support**: Supports manual attachment of multiple files and automatic attachment of debug logs.
- **Developer Contact**: Pre-filled to `bkedarnp@gmail.com`.
