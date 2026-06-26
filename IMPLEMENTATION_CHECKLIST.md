# FinFolio: Implementation Progress Checklist

Use this checklist to track development progress step-by-step.

## 🛠️ Phase 0: Foundation & Safety Net
- [x] **0.1 Migration Safety Screen UI**: 3-step wizard with Backup, Test, and Apply controls.
- [x] **0.2 Snapshot State Exporter**: JSON-based backup/restore mechanism before schema alterations.
- [x] **0.3 Round-Trip Date Utility**: Bidirectional conversion validation (AD -> BS -> AD) to eliminate off-by-one errors.

## 🏗️ Phase 1: The Core Engine
- [x] **1.1 Unified Schema Deployment**: Creating the `Holdings` physical table.
- [x] **1.2 Transaction Interceptors**: Room triggers or Hooks to keep `Holdings` updated.
- [x] **1.3 WACC Engine (Golden Matrix)**: Parameterized test execution against 35 distinct scenario combinations.
- [x] **1.4 Pre-Flight Migration Script**: Automated data mapper from legacy tables to new architecture.

## 📡 Phase 2: Resilient Market Data
- [x] **2.1 Multi-Source Failover Scraper**: Automated failover with Circuit Breakers.
- [x] **2.2 In-Memory Audit Buffer**: RAM caching layer for minor price updates.
- [x] **2.3 Data Source Badges**: UI indicators distinguishing Scraped vs. User-defined asset prices.

## 📊 Phase 3: Dashboard & Analytics
- [x] **3.1 High-Performance Dashboard**: Instantly loading layout rendering from the pre-computed physical table.
- [x] **3.2 Asset Breakdown Visuals**: Diverse sector allocation multi-charts with zero compute calculation lags.

## 🔒 Phase 4: Hybrid IPO Checker & Maintenance
- [x] **4.1 WebView Result Handler**: CAPTCHA handling overlay and document object injection.
- [x] **4.2 Weekly Log Pruner**: Background WorkManager log-cleaning worker.
