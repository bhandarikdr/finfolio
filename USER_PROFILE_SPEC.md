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

## 3. Global Profile Drawer
- Accessed via the user icon in the Top Bar.
- Contains quick links to:
    - **My Profile**: Edit Name and Email.
    - **Settings**: Manage regional preferences and Security (PIN Lock).
    - **Support**: Direct developer contact.
    - **Logout**: Exit session.

## 4. System Maintenance
- **Flush All Data**: Irreversible action available in Settings. Wipes all transactions, prices, BOIDs, and custom settings. Requires user confirmation via alert.

## 3. Support Workflow
- Integrated email client trigger.
- Automatically prepends system info (Android Version, App Version) to the message body.
- Pre-fills developer contact: `bkedarnp@gmail.com`.
