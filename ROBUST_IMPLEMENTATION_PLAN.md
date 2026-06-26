# FinFolio: Robust Implementation Roadmap (V2)

## 🎯 Strategic Shift: Performance & Reliability First
This plan prioritizes **Data Safety**, **High-Performance Architecture**, and **Deterministic Financial Accuracy** to eradicate the risks identified in the initial roadmap.

---

## 🏗️ 1. Architectural Foundation Changes

### 1.1 Physical Holdings Table (Performance Fix)
Instead of a complex SQL View, we will use a physical `Holdings` table. 
**Why?** $O(1)$ read performance for the Dashboard. No more "calculating everything" every time the app opens.

### 1.2 Transaction Interceptors (Data Integrity Fix)
Every time a `TransactionHistory` entry is added/updated/deleted, an **Interceptor** updates the `Holdings` table. This ensures the engine is always in sync.

### 1.3 Smart Audit Buffering (I/O Optimization)
- **Memory Buffer**: Minor LTP updates stream to an in-memory collection first.
- **Threshold Triggers**: Disk writes are only triggered if:
    - LTP change > 5%.
    - Asset is in the user's Watchlist or Holdings.
    - Scrape interval reaches 60 minutes.
- **Auto-Pruning**: A weekly worker deletes LTP logs older than 7 days.

### 1.4 AD/BS Bridge (Calendar Integrity)
- **Bidirectional Verification**: Every date conversion is verified via a "Round-Trip" check (AD -> BS -> AD) to catch leap-year or library anomalies before data persistence.

---

## 🛠️ 2. Challenge Eradication Matrix

| Challenge | Original Risk | Revised Strategy (Eradication) |
|-----------|---------------|--------------------------------|
| **Performance** | Complex View logic slowing down UI | **Pre-computed Holdings table** + Memory-buffered auditing. |
| **WACC Accuracy** | Simple division failing on Bonus/Splits | **Golden Dataset testing** (35 scenario matrix) for every code change. |
| **Scraper Brittleness** | UI breaks when website structure changes | **Multi-Source Failover** + Stale-flag degradation UI. |
| **IPO Blocks** | CAPTCHAs stopping auto-check | **Hybrid Mode**: Integrated WebView for solving CAPTCHAs + JS Injection. |
| **Migration Loss** | Schema overhaul breaking user data | **Automated Background Migration**: Direct background population of pre-computed tables, discarding the manual wizard once logic was verified robust. |

---

## 🚀 3. Optimized Implementation Phases

### Phase 0: The Safety Net (Foundation)
*   [x] **Automated Migration**: Implemented background population of Holdings from history (Discarded manual wizard for better UX).
*   [x] **Backup/Restore Logic**: JSON-based full-state snapshots before schema changes.
*   [ ] **Round-Trip Date Utility**: Implement and unit-test the AD/BS validation layer.

### Phase 1: The Core Engine (Week 1-3)
*   [ ] **Unified Schema**: Deploy `ScripMaster`, `Holdings`, and `TransactionHistory`.
*   [ ] **WACC Engine**: Golden-dataset-driven development for all 7 transaction types.
*   [ ] **Pre-Flight Migration**: Implement the automated logic to populate `Holdings` from history.

### Phase 2: Resilient Data & Pulse (Week 4-6)
*   [ ] **Multi-Source Scraper**: Implementation with failover and circuit breakers.
*   [ ] **In-Memory Audit Buffer**: Implementation of the buffered logging service.
*   [ ] **Manual Override badges**: UI to distinguish between Live and Manual data.

### Phase 3: Dashboard & Analytics (Week 7-9)
*   [ ] **High-Performance UI**: Holdings Dashboard powered by the physical table.
*   [ ] **Sector Allocation Charts**: Optimized rendering using cached analytics.

### Phase 4: Hybrid IPO & Maintenance (Week 10-12)
*   [ ] **WebView Result Checker**: Implementation of the CAPTCHA-aware checker.
*   [ ] **Audit Rotation Worker**: Room worker for automatic cleanup.

---

## 🛡️ 4. Risk Mitigation Protocols

### Protocol A: Atomic Integrity
Use Room `@Transaction` for any operation that involves adding a transaction AND updating a holding. If one fails, both roll back.

### Protocol B: Golden Regression Testing
Maintain a `scenarios.csv` with expected outputs for complex actions (e.g., Buy -> Bonus -> Right -> Sale). No engine update is merged without passing the entire matrix.

---

## ✅ 5. Success Definition
1.  **Speed**: Dashboard loads in < 200ms regardless of transaction count.
2.  **Safety**: 0 reports of data loss during V2 migration.
3.  **Accuracy**: Portfolio Value matches manual calculation within 2 decimal places.
