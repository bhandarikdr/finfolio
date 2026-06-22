# User Profile Specification

Details regarding user personalization and profile management.

## 1. Identity Data
- **Name**: Displayed in the Navigation Drawer and support requests. Editable via My Profile.
- **Email**: Used for "Registering" the app instance and as a "Reply-To" for support emails. Editable via My Profile.

## 2. App Settings & Security
Users can configure their application experience through the Settings page:
- **Currency Symbol**: Choose from रु., $, €, £, ¥, ₹.
- **Date Format**: (Future) Support for AD/BS toggle.
- **App PIN Lock**: Optional 4-digit PIN to secure the app from unauthenticated access. 
- **Primary Market Index**: Customizable target index name (defaults to "NEPSE Index") used for the home screen and top bar metrics.

## 3. Scraper Configuration
Users can manage prioritized lists of URLs for each scraper category:
- **Prioritized Fallback**: App attempts to fetch data using URLs in the order they are listed. If the first fails, it automatically falls back to the next.
- **URL Management**: Users can add, edit, and test connectivity for individual URLs.
- **Safe Deletion**: To ensure reliability, the delete option is automatically disabled if a category contains only one URL.
- **Factory Reset**: A "Restore All" option allows resetting all scraper categories to their default priority lists.

## 4. Global Profile Drawer
- Accessed via the user icon in the Top Bar.
- **Drawer Close**: A sleek left-pointing arrow button positioned at the top right of the profile header.
- Contains quick links to:
    - **My Profile**: Edit Name and Email.
    - **Settings**: Manage regional preferences and Security (PIN Lock).
    - **Support**: Direct developer contact.
    - **Exit**: Close the application immediately.

## 4. System Maintenance
- **Flush All Data**: Irreversible action available in Settings. Wipes all transactions, prices, BOIDs, and custom settings. Requires user confirmation via alert.

## 3. Support Workflow
- Integrated email client trigger.
- Automatically prepends system info (Android Version, App Version) to the message body.
- Pre-fills developer contact: `bkedarnp@gmail.com`.
