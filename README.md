# FinFolio

A comprehensive portfolio tracker managing transactions, parsing live markets, importing CSVs, and displaying customizable matrices. Designed with a focus on Nepali stock market functionality and aesthetic.

## Features

- **Transaction Management**: Track Buy, Sale, and Returns (including Dividends and Bonus shares).
- **Live Market Data**: Integration for parsing live market prices.
- **CSV Import**: Support for importing transaction history and Meroshare exports.
- **Advanced Metrics**:
  - **Realized Gain**: `(Sale Amt - Buy Amt) + Returns Amt + Net Invest`
  - **Unrealized Gain**: `Evaluation - Net Invest`
  - **Net Investment**: Tracking remaining capital in each scrip.
- **Customizable UI**: High-density matrix tables for both individual scrips and sectors.
- **Nepali Aesthetic**: Custom app icon and UI elements reflecting Nepali culture.

## Getting Started

### Prerequisites

- [Android Studio Ladybug](https://developer.android.com/studio) or newer.
- Android device or emulator running API 24 (Nougat) or higher.

### Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/finfolio.git
   ```
2. Open the project in Android Studio.
3. Create a `.env` file in the root directory (refer to `.env.example`):
   ```
   GEMINI_API_KEY=your_api_key_here
   ```
4. Build and run the app.

## Project Structure

- `app/src/main/java/com/example/data`: Database, Repository, and Financial Engines.
- `app/src/main/java/com/example/ui`: Jetpack Compose UI components and ViewModels.
- `app/src/main/res`: Custom assets including the Nepali-themed adaptive icon.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
