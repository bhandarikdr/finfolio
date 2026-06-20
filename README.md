# FinFolio

A comprehensive portfolio tracker managing transactions, parsing live markets, importing CSVs, and displaying customizable matrices. Designed with a focus on Nepali stock market functionality and aesthetic.

## Features

- **Transaction Management**: Track Buy, Sale, and Returns (including Dividends and Bonus shares).
- **Live Market Data**: Integration for parsing live market prices and capturing snapshots during export.
- **CSV Import/Export**: Support for importing/exporting transaction history, WACC, and Meroshare exports with smart alignment prompts.

## Advanced Metrics

The app uses a **Cost Recovery Model** for Nepali stock market analytics and supporting non-unit investments (FDs/Pension).

| Metric                            | Formula / Logic                                                                         |
|:----------------------------------|:----------------------------------------------------------------------------------------|
| **Balance Qty**                   | `Buy Qty + Returns Qty - Sale Qty`                                                      |
| **Avg Cost Price**                | `Buy Amount / (Buy Qty + Returns Qty)`                                                  |
| **Net Invest (Individual)**       | `MAX(0, Buy Amt - Sale Amt)`                                                            |
| **Evaluation (Individual)**       | `IF Avg CP > 0 THEN (Qty * LTP) ELSE MAX(0, Buy Amt - Sale Amt)`                        |
| **Net Investment (Selected)**     | `SUM(Net Invest Selected)`                                                              |
| **Current Evaluation (Selected)** | `SUM(Evaluation Selected)`                                                              |
| **Realized Gain**                 | `(Sale Amt - Buy Amt) + Returns Cash + Net Invest`                                      |
| **Unrealized Gain**               | `Evaluation - Net Invest`                                                               |
| **Deductions**                    | `IF Avg CP > 0 AND Evaluation > 0 THEN [(Eval * 0.0038) + 25 + (CGT if profit)] ELSE 0` |
| **Net Gain (Individual)**         | `Realized Gain + Unrealized Gain - Deductions`                                          |
| **Net Gain (Selected)**           | `SUM(Net Gain Selected)`                                                                |
| **Growth %**                      | `(Net Gain / Buy Amount) * 100`                                                         |
| **Receivable**                   | `Evaluation - Deductions`                                                               |
| **Profit Amount**                 | `Evaluation - Net Invest`                                                               |
| **Profit % (ROI)**                | `(Profit Amount / Net Invest) * 100`                                                    |

*Note: For cash-only investments (Avg CP = 0), Evaluation represents your remaining principal. Returns are reflected as gains without reducing the perceived worth of the principal investment.*

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
