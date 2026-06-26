# FinFolio

A comprehensive portfolio tracker managing transactions, parsing live markets, importing CSVs, and displaying customizable matrices. The app is a generic "blank canvas" that adapts its behavior, pulses, and terminology entirely based on your configuration.

## Features

- **High-Performance Engine**: Uses a pre-computed `Holdings` table for $O(1)$ dashboard loads and real-time metric updates.
- **Transaction Management**: Track Buy, Sale, SIP, and Returns (including Dividends, Bonus shares, and Splits).
- **Resilient Market Data**: Multi-source failover scraper for live prices with support for manual LTP overrides on non-tradable assets.
- **Hybrid IPO Checker**: Integrated solution for checking IPO results with CAPTCHA support via WebView automation.
- **Privacy & Security**: Optional 4-digit PIN lock and local-first data storage.

## Advanced Metrics

The app uses a **State-Driven Cost Recovery Model** for analytics.

| Metric                            | Formula / Logic                                                                         |
|:----------------------------------|:----------------------------------------------------------------------------------------|
| **1. Balance Qty**                | `Buy Qty + Returns Qty - Sale Qty`                                                      |
| **2. Avg Cost Price (WACC)**      | `Buy Amount / (Buy Qty + Returns Qty)` (Adjusted for Splits/Bonus)                       |
| **3. Net Invest (Individual)**    | `MAX(0, Buy Amt - Sale Amt)`                                                            |
| **4. Evaluation (Individual)**    | `IF Avg CP > 0 THEN (Qty * LTP) ELSE MAX(0, Buy Amt - Sale Amt)`                        |
| **5. Realized Gain**              | `(Sale Amt - Buy Amt) + Returns Cash + Net Invest`                                      |
| **6. Unrealized Gain**            | `Evaluation - Net Invest`                                                               |
| **7. Deductions**                 | `IF Avg CP > 0 AND Evaluation > 0 THEN [(Eval * 0.0038) + 25 + (CGT if profit)] ELSE 0` |
| **8. Receivable**                 | `Evaluation - Deductions`                                                               |
| **9. Net Gain (Individual)**      | `Realized Gain + Unrealized Gain - Deductions`                                          |
| **10. Profit Amount**             | `Receivable - Net Invest`                                                               |
| **11. Profit % (ROI)**            | `(Profit Amount / Net Invest) * 100`                                                    |
| **12. Growth %**                  | `(Net Gain / Buy Amount) * 100`                                                         |

## Project Structure

- `app/src/main/java/com/example/data`: Room Database, Repositories, and the **Holdings Engine**.
- `app/src/main/java/com/example/ui`: Jetpack Compose UI components and ViewModels.

## Documentation

- [Robust Implementation Plan](ROBUST_IMPLEMENTATION_PLAN.md)
- [Metrics Specification](METRICS_SPEC.md)
- [Market Pulse Specification](MARKET_PULSE_SPEC.md)
- [IPO Master Specification](IPO_MASTER_SPEC.md)
- [Bulk IPO Check Specification](BULK_IPO_SPEC.md)
- [Import/Export Specification](DATA_IO_SPEC.md)
- [Dashboard Specification](DASHBOARD_SPEC.md)
- [User Profile and Settings Specification](USER_PROFILE_SETTINGS_SPEC.md)

## Recent Improvements (Latest Release)

- **UI Reliability**: Fixed `ExpandableHeader` implementation across all screens ensuring consistent color coding.
- **Market Agnostic**: Refactored the core engine to support any global market by removing hardcoded site-specific logic.
- **Index Customization**: The Primary Market Index is now fully customizable with user-defined names.
- **Configuration Persistence**: The app now strictly respects user-defined indices visibility settings, persisting manual unselection even across market refreshes.
- **High-Fidelity Scraping**: Improved aggregation logic to pull data from all configured URLs simultaneously.
