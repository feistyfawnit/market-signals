# LucidLynx Market Signals — Architecture

*Last updated: May 2026*

---

## System Overview

Spring Boot service that polls Binance + IG market data on a scheduled interval, calculates RSI, Stochastic, EMA, MACD, ADX, and ATR across multiple timeframes per instrument, runs trend detection with multi-layer filters, opens tracked positions, and pushes Telegram alerts. Runs in Docker with PostgreSQL for persistence.

```
                    +-----------------------+        +------------------+
                    |  MarketDataService    |------->| RSI / Stoch      |
                    |  (Binance 5m, IG 15m) |        | Calculators      |
                    +-----------------------+        +---------+--------+
                                                               |
                            +----------------------------------+----------+
                            | SignalDetection + TrendDetection            |
                            | (RSI alignment, EMA trend, ADX/MACD/ATR     |
                            |  filters, EMA-slope + dedupe, vol confirm)  |
                            +----------------------------------+----------+
                                                               | SignalEvent
            +---------------------+---------------------+---------------------+
            |                     |                     |                     |
            v                     v                     v                     v
   +-----------------+ +--------------------+ +---------------------+ +----------------+
   | Notification    | | PositionOutcome    | | SignalLogger        | | IGTradingSvc   |
   | + Telegram      | | (TP/SL, 16h close, | | (signal_logs +      | | (auto-exec +   |
   | + DeepSeek      | |  trailing stop)    | |  alert CSV + daily  | |  Telegram      |
   |   enrichment    | |                    | |  rollup)            | |  confirm)      |
   +-----------------+ +--------------------+ +---------------------+ +-------+--------+
                                                                              |
   +----------------------------------------------------------------+         |
   | REST API: /instruments /signals /positions /settings           |         |
   |           /trading /test /admin                                |         |
   +----------------------------------------------------------------+         |
                                                                              |
            +---------------+         +-----------------+            +--------v--------+
            | IG REST API   |         | Binance API     |            | Telegram Bot    |
            | (data + deal) |         | (free, crypto)  |            |  API            |
            +---------------+         +-----------------+            +-----------------+

```

---

## Data Sources

| Source | Cost | Coverage | Status |
|--------|------|----------|--------|
| Binance | FREE | Crypto (SOL, BTC, ETH, BCH) | Live |
| IG API | FREE (with account) | Indices, FX, commodities, crypto | Live |

---

## Component Responsibilities

### Polling & Calculators

- **`MarketDataService`** — polls Binance on a 5 min cadence and IG on a 15 min cadence; applies candle-period skip to avoid redundant IG fetches.
- **`RsiCalculator`** — Wilder's smoothing RSI (period=14) from close prices held in memory by `PriceHistoryService`.
- **`StochasticCalculator`** — %K/%D (14,3) on the last 50 OHLC candles. Included in FULL signal payloads only.
- **`EmaCalculator`** — EMA on close prices; period 20 default for the trend timeframe. Used by trend detection and the new EMA-slope filter.
- **`MacdCalculator`** — 12/26/9 MACD line + histogram + divergence detection. Bounded fetch (37 candles for histogram, 46 for divergence).
- **`AdxCalculator`** — Wilder ADX(14) on the trend timeframe. Gate for trend entries when `adx-filter-enabled=true` (ADX < 20 → ranging market, suppress).
- **`AtrCalculator`** — ATR(14) with Wilder smoothing. **Bounded** to last `5×period` candles via `PageRequest` (May 2026 — see AGENTS.md § Efficiency Guardrails). Powers ATR-based stops and the new ATR-min-% chop filter.

### Signal Detection

- **`SignalDetectionService`** — evaluates RSI alignment across configured timeframes; emits FULL (OVERSOLD / OVERBOUGHT), PARTIAL, or WATCH signals.
- **`TrendDetectionService`** — EMA-based trend state (STRONG_UPTREND / STRONG_DOWNTREND / NEUTRAL) + `TREND_BUY_DIP` / `TREND_SELL_RALLY` entries. Layered filters: ADX, MACD histogram + divergence, EMA-slope, ATR-min-%, crypto volume, RSI-recovery dedupe, pct-move dedupe.
- **`VolatilityRegimeService`** — ATR-expansion ratio across enabled instruments; flags `HIGH_VOLATILITY` regime that suppresses lower-priority signals.
- **`SignalCooldownService`** — per-symbol/per-signal-type cooldown to prevent repeat alerts within configurable window.
- **`FilterEventCounterService`** — persistent per-day per-symbol UPSERT counter for every suppression reason (e.g. `ADX_RANGING`, `EMA_SLOPE_FLAT`, `DIP_DEDUPE`, `MACD_HISTOGRAM`). Surfaced in the P&L report.
- **`PartialSignalMonitorService`** — tracks lagging timeframe after PARTIAL fires (60 min window, 30 min interval); fires FULL ALIGNMENT or expiry notice.

### Notifications & Enrichment

- **`NotificationService`** — formats Telegram payload; handles quiet hours, cooldown, no-trade mode, weekend suppression, per-signal priority. Appends `🪜 Trail:` guidance line to every actionable signal (May 2026).
- **`TelegramNotificationService`** — multi-recipient HTTP send; rate-limit aware.
- **`TelegramConfirmationService`** — inline-keyboard approve/skip prompts for IG auto-execution with configurable timeout.
- **`DeepSeekEnrichmentService`** — optional AI signal enrichment via DeepSeek API. (Replaces the earlier `ClaudeEnrichmentService` which is retained but unused.)

### Persistence & Reporting

- **`PriceHistoryService`** — in-memory rolling candle cache; persists to `candle_history` and reloads on startup to minimise warmup pressure.
- **`SignalLogService`** — writes every emitted signal to `signal_logs`.
- **`AlertCsvService`** — appends every signal to `signal_alerts_YYYY-MM.csv` with full OHLCV; hourly job backfills outcome prices at 1h/4h/24h.
- **`PositionOutcomeService`** — opens a `position_outcomes` row on every actionable signal, monitors TP/SL hourly, force-closes at **16h** if neither hit.
- **`PositionReportService`** — builds the human-readable / CSV P&L reports consumed by `/api/positions/pnl-report*` and the daily 06:00 UTC `reports/pnl-report.md` write.
- **`SignalGapService`** — reports signals that fired but did NOT open a position (cooldown, quiet hours, duplicate open, etc.). Surfaced via `/api/positions/signal-gaps`.
- **`DailyPriceRollupService`** — runs at 00:05 UTC; one OHLCV row per instrument in `daily_price_summary`; yearly CSV export.
- **`HistoryArchivalService`** — weekly/monthly archival of `signal_logs` (90-day), `position_outcomes` (90-day), and `candle_history` (60-day rolling) to `signal_archive/*.csv`, then DB prune.

### IG Auto-Execution

- **`IGAuthService`** — IG API authentication with session auto-refresh every 6 hours.
- **`IGMarketDataClient`** — thin reactive client over the IG REST API with candle-period skip + 403/quota handling.
- **`IGTradingService`** — places trades, ratcheting trailing stop, kill switch, daily loss limit, max concurrent positions. **OFF by default** (`TRADING_AUTO_EXECUTION_ENABLED=false`). When enabled, `IG_BASE_URL` selects demo (default) vs live. Manual approval enforced via `TRADING_REQUIRE_MANUAL_APPROVAL=true`.

---

## Instrument Configuration

Instruments are seeded from `application.yml` on startup via `DataInitializer` and stored in PostgreSQL. Instruments removed from YAML are automatically disabled in the DB (candle history preserved). Each instrument has:
- **Symbol** (e.g. `SOLUSDT`, `IX.D.DAX.DAILY.IP`)
- **Source** (`BINANCE` or `IG`)
- **Timeframes** — configurable per instrument
- **Thresholds** — oversold/overbought, configurable per instrument

| Instrument type | Timeframes | Rationale |
|---|---|---|
| Indices (DAX, FTSE, S&P, Nasdaq) | 15m, 30m, 1h | Fast V-recoveries; need responsiveness |
| Crypto (BTC, ETH, SOL, BCH) | 15m, 1h, 4h | Larger moves; 4h confirmation valuable |
| Commodities (Gold, Silver, Oil) | 15m, 1h, 4h | Slower trends; 4h confirmation valuable |

---

## Data Retention

| Store | Location | Retention | Purpose |
|---|---|---|---|
| `signal_archive/signal_alerts_YYYY-MM.csv` | Flat files on disk | **Forever** (monthly partitioned) | Every signal with RSI values, candle OHLCV, and outcome prices at 1h/4h/24h |
| `signal_logs` DB table | PostgreSQL | **Rolling 90 days** | Signal history queryable via REST API; older rows pruned by `HistoryArchivalService` |
| `position_outcomes` DB table | PostgreSQL | **Rolling 90 days** | Open + closed positions with TP/SL/PNL; archived to `signal_archive/position_outcomes_YYYY-MM.csv` before prune |
| `signal_archive/daily_prices_YYYY.csv` | Flat files on disk | **Forever** (yearly partitioned, ~40 KB/year) | Daily OHLCV per instrument — permanent long-term price archive |
| `daily_price_summary` DB table | PostgreSQL | **Rolling 90 days** | Staging area for daily rollup; purged after CSV export |
| `candle_history` DB table | PostgreSQL | **Rolling 60 days** per symbol:timeframe | RSI / Stoch / EMA / MACD / ADX / ATR working set; survives restarts; all reads bounded via `Pageable` |
| `filter_event_counts` DB table | PostgreSQL | **Unbounded** (~30 rows/day, negligible growth) | Suppression counters per filter×symbol×day; weekly CSV backup |

The **daily price CSV** (`signal_archive/daily_prices_YYYY.csv`) is the permanent long-term price archive. At 00:05 UTC daily, the rollup job: (1) aggregates yesterday's candles into one OHLCV row per instrument, (2) appends to the yearly CSV, (3) purges DB rows older than 90 days. The CSV is the source of truth for historical prices.

The `signal_archive` signal CSVs are the analytics backbone — they capture not just when signals fired but what happened afterwards (outcome prices backfilled hourly). These are independent of the DB and survive any database changes.

---

## Signal Hierarchy

| Priority | Signal | Condition | Telegram |
|---|---|---|---|
| 1 | `OVERSOLD` / `OVERBOUGHT` | All TFs aligned (3/3) past threshold | urgent — bypasses DND |
| 1 | `TREND_BUY_DIP` / `TREND_SELL_RALLY` | EMA trend confirmed + fastest-TF RSI pullback + ADX/MACD/EMA-slope/ATR-min/volume gates pass | actionable; quiet-hours gated |
| 2 | `PARTIAL_OVERSOLD` / `PARTIAL_OVERBOUGHT` | All but 1 TF aligned (2/3) | off by default (`PARTIAL_SIGNALS_ENABLED=false`) |
| 3 | `WATCH_OVERSOLD` / `WATCH_OVERBOUGHT` | 1 TF crossed + others approaching (<40 or >60) | off by default (`WATCH_SIGNALS_ENABLED=false`) |

`TREND_SELL_RALLY` is currently disabled (`trend.sell-rally-enabled: false`) due to −0.79R backtest expectancy. See `docs/project-log.md` for filter-by-filter rationale.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.5.3 |
| Language | Java 25 |
| Database | PostgreSQL 16 |
| HTTP Client | Spring WebFlux (reactive) |
| Notifications | Telegram Bot API |
| AI | Claude API (Anthropic) — optional |
| Build | Maven |
| Container | Docker + docker-compose |
| Hosting | AWS EC2 t3.micro (eu-west-1) or local Docker |

---

*See `README.md` for setup. See `docs/api.md` for endpoint reference.*
