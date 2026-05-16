# Project Log — LucidLynx Market Signals

*Local only — do not commit to market-signals repo*
*Renamed from signal-strategy-notes.md — April 2026*
*Last updated: May 2026*

---

## 2026-05-16 — Partial suppression, Gold RSI block, P&L labels, MACD divergence gate

### Performance context
Last 5 closed signals positive. Crypto TREND_BUY_DIP (SOL/BTC/ETH) appears to be working under current filter stack (ADX, MACD histogram, volume). IG indices remain disabled for TREND_BUY_DIP pending more data — DAX/FTSE/SP500 continue to accumulate position_outcomes for later review. Trailing stop automation (move stop to break-even after +€50 move) noted as a future Phase 4 roadmap item.

### Task 1 — PARTIAL_SIGNALS_ENABLED=false (.env only)

Added `PARTIAL_SIGNALS_ENABLED=false` to `.env` (was defaulting `true`). Also added `WATCH_SIGNALS_ENABLED=false` explicitly (was already defaulting `false` via YAML default, now visible in `.env`). No code changes — only env var override. Effect: only 3/3 full OVERSOLD/OVERBOUGHT signals and TREND_BUY_DIP fire. Rationale: partial signals have generated noise without follow-through; full alignment only until further data.

### Task 2 — Gold RSI signal suppression (`rsi-signals-enabled: false`)

Gold (`CS.D.USCGC.TODAY.IP`) has `trend-buy-dip-enabled: false` already but standard OVERSOLD/PARTIAL_OVERSOLD signals still fired historically at −1.00R (0% TP, 100% SL). Added `rsi-signals-enabled: false` per-instrument flag to suppress ALL RSI signal types for Gold while keeping the instrument `enabled: true` (price data still accumulates for `CrossAssetCorrelationService` commodity regime detection).

Implementation: new `rsiSignalsEnabled` Boolean column on `instruments` table (Hibernate auto-migrates, default `true`). Guard added in `SignalDetectionService.analyzeInstrument()` after RSI collection loop but before `detectSignals()` — price history collection is unaffected. Memory impact: one boolean field per instrument row (negligible). YAML is source of truth (synced via `DataInitializer` on restart, same pattern as `trendBuyDipEnabled`).

### Task 3 — P&L report: Trail+/Expire exit labels

Changed display-only label in `PositionReportService.formatClosedRowCompact()`:
- `!tpHit && !slHit && pnlPct > 0` → **Trail+** (profitable disciplined trailing exit)
- `!tpHit && !slHit && pnlPct <= 0` → **Expire** (position ran out of time without profit)

Previously both were labelled `24h`. P&L numbers unchanged. No DB columns, no API endpoints, no domain model changes.

### Task 4 — MACD Divergence gate (deployed OFF)

Added `MacdCalculator.computeDivergence(symbol, timeframe, fastPeriod, slowPeriod, signalPeriod, lookback=20)`. Bounded fetch: `Pageable.ofSize(slowPeriod + lookback)` = 46 candles at defaults (~11 KB). Divergence logic: split lookback window into two halves; track price extreme and corresponding MACD line value in each half. Bullish: second-half price low < first-half low AND MACD at that low is higher. Bearish: second-half price high > first-half high AND MACD lower.

Also confirmed `compute()` is already bounded (`Pageable.ofSize(slowPeriod + signalPeriod + 2)` = 37 candles, ~9 KB vs the prior unbounded `findBySymbolAndTimeframeOrderByCandleTimeAsc` which loaded up to 1,440 candles / ~360 KB).

Wired into `TrendDetectionService.checkForTrendEntry()` behind `macd-divergence-enabled: ${TREND_MACD_DIVERGENCE_ENABLED:false}`. Logic: when enabled and histogram is clearly bullish (>0 AND rising), entry passes regardless. When histogram is only partially bullish AND divergence is absent → block + record `MACD_DIVERGENCE_ABSENT` in `filter_event_counts`. `Optional.empty()` (warmup) is pass-through. **Deployed OFF. Monitor `filter_event_counts` for 1 week before enabling.**

---

## 2026-05-10 — Trend signal gap analysis

### New endpoint: GET /api/positions/signal-gaps

Queries `filter_event_counts` (last 30 days) and returns per-symbol:
- `trendSignalsFired` — TREND_BUY_DIP entries in `signal_logs` that passed all detection filters
- `totalSuppressed` — sum of all `filter_event_counts` entries for that symbol
- `suppressionRate` — `totalSuppressed / (totalSuppressed + trendSignalsFired)`
- `filters` — per-filter counts, sorted by frequency

Also returns `raceConditionCandidates`: 24h auto-closes where `POSITION_COOLDOWN` fired on the same calendar day. Uses a single JOIN query across `position_outcomes` and `filter_event_counts`. Day-level granularity only (no timestamps in filter_event_counts) — matches same/next day.

**Note on filter scope**: `ADX_RANGING`, `MACD_HISTOGRAM`, `CRYPTO_VOLUME` are TREND_BUY_DIP-specific (fired by `TrendDetectionService`). `DUPE_OPEN_POSITION`, `POSITION_COOLDOWN`, `RISK_OFF`, `RISK_ON`, `HIGH_VOLATILITY` cover all signal types.

### P&L report: Trend Signal Suppressions section

`PositionReportService` now includes a **Trend Signal Suppressions (Last 7 Days)** table in the daily `pnl-report.md`. Shows top 3 suppression reasons per active TREND_BUY_DIP symbol (enabled=true, trendBuyDipEnabled≠false).

### New repo methods added
- `FilterEventCountRepository.sumBySymbolAndFilterSince` — aggregate by symbol+filter for date range
- `FilterEventCountRepository.sumBySymbolSince` — aggregate for a single symbol, ordered by count desc
- `FilterEventCountRepository.findRaceConditionCandidatesSince` — JOIN with position_outcomes to detect race conditions in one query
- `SignalLogRepository.countFiredBySymbolSince` — JPQL count of a given SignalType grouped by symbol since a date

---

## 2026-05-10 — position_outcomes race-condition fix; DeepSeek enrichment

### Task 1 — Missing SOL TREND_BUY_DIP entry (May 7 ~14:52 UTC)

**Root cause**: race condition between the 10-min exit-check job and a new signal firing at the exact 24h mark.

| Step | What happened |
|---|---|
| May 6 ~14:52 UTC | SOL TREND_BUY_DIP position opened |
| May 7 14:50 UTC | Exit-check job runs: `entryTime + 24h (14:52) isBefore now (14:50)` = false → NOT auto-closed |
| May 7 14:52 UTC | New TREND_BUY_DIP fires → `existsBySymbolAndExitTimeIsNull` = true → signal **silently dropped** (log.debug only) |
| May 7 15:00 UTC | Exit-check job runs again → auto-close fires |

**Fix** (`PositionOutcomeService.handleSignalEvent`): when the DUPE check fires, fetch the open position and check if `entryTime + 24h < now`. If overdue, force-close it and proceed with the new entry. If not overdue, skip as before.

Both suppression log lines upgraded `debug → info` so DUPE_OPEN_POSITION and POSITION_COOLDOWN suppressions are visible in `make remote-logs`.

**New repo method**: `PositionOutcomeRepository.findFirstBySymbolAndExitTimeIsNull(String symbol)`.

### Task 2 — DeepSeek enrichment for TREND_BUY_DIP

New `DeepSeekEnrichmentService` (disabled by default, `DEEPSEEK_ENABLED=false`).

| Config | Value |
|---|---|
| API | `https://api.deepseek.com/v1/chat/completions` (OpenAI-compatible) |
| Model | `deepseek-chat` (configurable via `DEEPSEEK_MODEL`) |
| Trigger | `TREND_BUY_DIP` only |
| `max_tokens` | 60 (enforces one-sentence response) |
| Timeout | 2 seconds hard (`WebClient.timeout`) |
| Failure mode | Silent skip — Telegram notification sends regardless |

Appended as final line (🤖) to the Telegram message after Claude context (📰). Both optional and independently toggleable. Added `DEEPSEEK_ENABLED=false` / `DEEPSEEK_API_KEY=` to `.env.example`. Config keys under `deepseek.*` in `application.yml`.

---

## 2026-05-10 — DAX TREND_BUY_DIP disabled; CSV backfill price accuracy fix

| Decision | Detail |
|---|---|
| DAX `trend-buy-dip-enabled: false` | 0/9 wins, €901 cumulative losses, 0% TP. Same outcome as FTSE/Gold/Silver. No edge. |
| `AlertCsvService.resolvePrice()` bug | Was writing current live price into `price_1h/4h/24h_later` — wrong for rows where the window had already passed. Fixed: now queries `candle_history` for candle closest to `signalTime + offset`. |
| `CandleHistoryRepository` new query | `findBySymbolAndCandleTimeLessThanEqualOrderByCandleTimeDesc` — derived, no `@Query` needed. |
| `POST /api/admin/backfill-csv` | New endpoint triggers `backfillOutcomePrices()` immediately. Run post-deploy to fix existing blank rows: `curl -X POST .../api/admin/backfill-csv` |

---

## 2026-04-25 — Phase 5 Anomaly Features Removed

Five features removed as dead weight — none produced actionable signals:

1. **Polymarket monitor** (`PolymarketMonitorService`, `PolymarketDiscoveryService`) — odds shifts never preceded price moves. Deleted service classes, config keys, scheduled poll, test endpoint. `ANOMALY_DETECTION_ENABLED` env var removed.
2. **Volume anomaly detector** (`VolumeAnomalyDetector`) — σ-based volume spikes never correlated with signal quality. Deleted service class, `onNewCandle` call in `MarketDataPollingService`, volume context in `NotificationService`, test endpoint.
3. **Cross-correlation burst** (`AnomalyNotificationService.maybeEmitCorrelationBurst`) — 60s window almost never clustered. With volume + polymarket anomalies gone, this had no inputs. Removed entire `AnomalyNotificationService` + `AnomalyProperties` + `AnomalyAlert`/`AnomalyEvent`/`AnomalyLog`/`AnomalyLogRepository`.
4. **Momentum surge report** (`PriceMomentumSurgeDetector`) — retrospective report endpoint that never informed live trading. Deleted service class + `GET /api/positions/momentum-review` endpoint + config keys.
5. **Oil opportunity review** (`OilOpportunityReportService`) — retrospective report endpoint. Deleted service class + `GET /api/positions/oil-review` endpoint + config keys.

**Kept**: `CrossAssetCorrelationService` (regime detection for risk-on/risk-off signal suppression) and `VolatilityRegimeService` (ATR-based volatility suppression) — both are core signal quality filters, not anomaly features.

**DB cleanup**: The `anomaly_log` table still exists in PostgreSQL. JPA won't auto-drop it (no mapped entity). To remove manually:

```sql
DROP TABLE IF EXISTS anomaly_log;
```

---

## How the Detection Works

### Candles
A candle represents price action over a fixed time period: Open, High, Low, Close, Volume.
RSI uses **close prices only**.

| Timeframe | 1 candle = | 28 candles = | What it detects |
|-----------|-----------|--------------|-----------------|
| 15m | 15 minutes | 7 hours | Short-term momentum extremes. Fast to react. |
| 1h | 1 hour | 28 hours | Intraday trend exhaustion. Moderate lag. |
| 4h | 4 hours | 4.7 days | Multi-day trend confirmation. Very slow to change. |

### RSI Calculation (Wilder's Smoothing, period=14)
1. Calculate average gain and average loss over the first 14 closes
2. RS = avgGain / avgLoss → RSI = 100 - (100 / (1 + RS))
3. Each new candle: smooth using `(previous × 13 + new) / 14`
4. **Key property**: the 4h RSI has "memory" of the previous ~13 four-hour candles.
   A single bearish day is diluted by previous bullish closes.

### Signal Thresholds
- **OVERSOLD (BUY)**: RSI < 30 on all 3 timeframes simultaneously
- **OVERBOUGHT (SELL)**: RSI > 70 on all 3 timeframes simultaneously
- **PARTIAL**: RSI crosses threshold on 2/3 timeframes — watch signal only

---

## Known Gap: The 4h Lag Problem (MITIGATED — see Active 4h Monitoring below)

### What happened on 2 April 2026 (Germany 40 / DAX)

DAX dropped from 23,250 → 22,700 (-550 pts, -2.4%) over ~9 hours.

**Verified RSI values from IG (all timeframes):**

| Time (BST) | Price | 15m RSI | 30m RSI | 1h RSI | 4h RSI | Signal? |
|---|---|---|---|---|---|---|
| 11:20am | ~22,850 | ~30 | ~35 | ~38 | ~40 | 5m <30 (noise) |
| 12:00pm | ~22,750 | ~29 | ~30 | ~38 | ~40 | Borderline |
| **12:30pm** | **22,742** | **28%** | **28%** | ~38 | ~40 | **✅ PARTIAL fires (15m+30m <30)** |
| 12:45pm | ~22,700 | 26% | ~29 | ~37 | ~40 | 15m still <30, 30m borderline |
| **12:55pm** | **~22,680** | ~25 | ~29 | ~37 | ~40 | Near day low (22,671) |
| **1:00pm** | **22,718** | **24%** | **31.8%** | **36%** | ~38 | 30m recovered; only 15m <30, 1h <40 → WATCH |
| 1:30pm | ~22,900 | ~35 | ~38 | ~37 | ~38 | Recovery underway |
| **1:45pm** | **~22,945** | ~40 | ~42 | ~38 | ~38 | **2:1 limit would be hit here** |
| 2:00pm | ~23,100 | >50 | >45 | ~40 | ~38 | V-recovery continues |
| 3:00pm | ~23,200+ | >55 | >50 | ~45 | ~39 | Full recovery |
| 5:30pm | 23,129 | ~48 | ~47 | ~44 | ~39 | IG extended hours |

**With new system (15m,30m,1h config + WATCH tier):**
- **12:30pm: PARTIAL_OVERSOLD fires** — 15m=28% + 30m=28% both <30 (2/3 aligned)
- PartialSignalMonitorService begins tracking 1h as lagging TF (36%, needs <30)
- You see the notification at lunch, check IG chart, see the -500pt drop into support
- Entry at ~22,742, stop at 22,628 (0.5%), limit at 22,969 (2:1) — **limit hit ~1:45pm**

Note: Xetra cash index closed at 22,772 (different instrument; closes earlier). IG CFD trades extended hours.
**Result: With 15m,30m,1h config, a PARTIAL fires at 12:30pm. 2:1 limit hit in ~75 min. Profit ~EUR 200.**

### Root Cause
The 4h RSI requires a sustained multi-day decline to reach <30, because:
- It smooths over 14 four-hour periods = ~2.3 days of data
- A single morning's drop affects only 1-2 of those 14 candles
- Preceding bullish closes dampen the new bearish data

### Instruments where this gap is most impactful
Indices (DAX, FTSE, S&P, Nasdaq) — these are the instruments most prone to sharp V-recoveries
before the 4h catches up. Crypto less so because the moves are larger (5–15%+).

---

## Proximity Hint + WATCH Signal Tier (IMPLEMENTED)

When a PARTIAL signal fires, the notification now shows how far the lagging timeframe
is from the alignment threshold. Example for PARTIAL_OVERSOLD:

```
⏳ Waiting: 1h RSI 35.8 → needs <30 (5.8 pts to go)
```

This lets you judge whether to monitor actively (gap < 5) or ignore (gap > 15).

### WATCH Signal Tier (IMPLEMENTED — April 5 2026)

New lowest-priority alert when:
- **1 timeframe is below 30** (clearly oversold) AND
- **at least 1 other timeframe is below 40** (approaching, under stress)

This catches days like April 2 where only 15m crossed <30 but 1h was at 36 (within the
proximity band). On April 2 at 1pm: 15m=24 (✓ <30) + 1h=36 (✓ <40) → WATCH fires.

Config: `WATCH_PROXIMITY_THRESHOLD=40` (default). Env-overridable.

Notification hierarchy (highest to lowest priority):
1. 🟢 **OVERSOLD / OVERBOUGHT** — all TFs aligned. Trade signal.
2. 🟡 **PARTIAL** — all but 1 TF aligned. Watch + follow-up monitoring.
3. 👀 **WATCH** — 1 TF crossed + others approaching. Check chart. **Disabled by default** (`WATCH_SIGNALS_ENABLED=false`) — too noisy in volatile markets; all instruments can fire simultaneously. Re-enable when conditions settle.

### Active Lagging-TF Monitoring (IMPLEMENTED — April 5 2026)

When a PARTIAL signal fires, `PartialSignalMonitorService` now:
1. **Registers the lagging timeframe** (e.g. 4h RSI at 35.8, needs <30)
2. **Sends follow-up notifications every 15 minutes** for up to 2 hours
3. Each follow-up shows: current RSI, gap to threshold, whether gap is closing or widening
4. **Fires an urgent "FULL ALIGNMENT" alert** if the lagging TF crosses during the window
5. **Sends an "expired" notification** when the 2-hour window closes without alignment

Config (env-overridable):
```
PARTIAL_MONITORING_ENABLED=true       # default: true
PARTIAL_MONITORING_WINDOW=120          # minutes to track (default: 120)
PARTIAL_MONITORING_INTERVAL=15         # minutes between follow-ups (default: 15)
```

**Notification volume:** ~8 messages over 2 hours per partial event (initial + 7 follow-ups).
During normal operations: zero. Partials are rare — a few per week max.
No additional API calls — monitoring reuses RSI from normal poll cycle.

---

## Retrospective Analysis Endpoint (IMPLEMENTED — April 5 2026)

New REST endpoint to check whether a signal would have fired at any historical point:
```
GET /api/signals/retrospective/IX.D.DAX.DAILY.IP?at=2026-04-02T12:30:00Z
```

Returns per-timeframe RSI values, distance from thresholds, and a verdict
("FULL signal WOULD have fired" / "PARTIAL only" / "No signal").
Uses IG v3 date-range API to fetch historical candles. Only works for IG instruments.

Also available: `GET /api/signals/rsi-snapshot` — live RSI for all instruments from
in-memory history, useful for checking current state without waiting for a signal.

---

## Signal Log Archival & Research CSV (IMPLEMENTED — April 5–6 2026)

### Weekly archival job (`HistoryArchivalService`)
Runs every Sunday 03:00 UTC. Exports `signal_logs` DB rows older than 90 days to `./signal_archive/signal_logs_YYYY-MM.csv`, then deletes them from the DB.

CSV columns: `id, symbol, instrument_name, signal_type, current_price, rsi_1m, rsi_5m, rsi_15m, rsi_30m, rsi_1h, rsi_4h, timeframes_aligned, signal_strength, created_at`

Config: `ARCHIVE_RETENTION_DAYS=90`, `ARCHIVE_DIR=./signal_archive`

**DB size is not a concern.** signal_logs grows ~5–10 rows/day during volatile markets, zero on quiet days.

### Real-time alert CSV (`AlertCsvService` — April 6 2026)

Every signal that fires (FULL, PARTIAL, or WATCH) **immediately appends one row** to `./signal_archive/signal_alerts_YYYY-MM.csv`. Written regardless of quiet hours or weekend suppression — captures all detection events.

| Column group | Fields |
|---|---|
| Identity | `alert_id` (UUID), `symbol`, `instrument_name`, `signal_type` |
| Timing | `signal_time` (ISO-8601 UTC) |
| Price & RSI | `current_price`, `rsi_1m`, `rsi_5m`, `rsi_15m`, `rsi_30m`, `rsi_1h`, `rsi_4h` (sparse — only populated if the instrument uses that TF) |
| Signal metadata | `timeframes_aligned`, `total_timeframes`, `signal_strength` |
| Candle snapshot | `candle_time`, `candle_open`, `candle_high`, `candle_low`, `candle_close`, `candle_volume` — full OHLCV of the trigger candle |
| Outcome (backfilled) | `price_1h_later`, `price_4h_later`, `price_24h_later` |

### Outcome backfill (hourly, in `AlertCsvService`)

Runs every hour on the hour (UTC). Scans all `signal_alerts_*.csv` files and fills outcome prices once elapsed thresholds are met:
- `price_1h_later` → filled when ≥1h has elapsed since `signal_time`
- `price_4h_later` → filled at ≥4h
- `price_24h_later` → filled at ≥24h

Price source: most recent in-memory price for that symbol from the live poll cycle. If the service was restarted and has no price yet, the row is skipped and retried on the next hourly run. Per-file `ReentrantLock` prevents race conditions between the async appender and the rewrite-on-backfill.

**Use for research**: open `signal_alerts_YYYY-MM.csv` in Excel/Python to evaluate which signal types and market conditions produced wins vs losses. The outcome prices provide a forward view without needing to manually track exits.

---

## Index Timeframe Change (April 5 2026)

Indices changed from `15m,1h,4h` to `15m,30m,1h`:
- **Why**: 4h RSI is too slow for sharp intraday V-recoveries on indices (see April 2 analysis)
- **Effect**: Catches dips ~30 min earlier. On April 2: PARTIAL fires at 12:30pm instead of never.
- **Crypto stays on `15m,1h,4h`**: Their moves are bigger (5–15%) and slower; 4h adds real value.
- **Commodities stay on `15m,1h,4h`**: Gold/Oil/Silver behave more like crypto than indices.

| Instrument type | Timeframes | Rationale |
|---|---|---|
| **Indices** (DAX, FTSE, S&P, Nasdaq) | 15m, 30m, 1h | Fast intraday V-recoveries; need responsiveness |
| **Crypto** (BTC, ETH, SOL, BCH) | 15m, 1h, 4h | Larger moves; 4h confirmation valuable |
| **Commodities** (Gold, Silver, Oil) | 15m, 1h, 4h | Slower trends; 4h confirmation valuable |

**False positive risk**: 15m+30m alignment is less reliable than 15m+1h. Expect ~2–4 extra
PARTIAL alerts per month on indices during choppy markets. Acceptable tradeoff — these are
"check chart" signals, not "trade now", and 2:1 risk-reward only needs >33% win rate.

#### Break-Even Win Rates by Risk:Reward

| Risk:Reward | Break-even win rate | Notes |
|---|---|---|
| 1:1 | 50% | High bar — generally not worth trading RSI signals at 1:1 |
| 1.5:1 | 40% | Marginal; needs consistent edge |
| **2:1** | **33%** | **Current default. 2 losers per winner = breakeven.** |
| 3:1 | 25% | 3 losers, 1 winner is fine — achievable on strong full-alignment signals |
| 4:1 | 20% | Rare but possible on sharp intraday moves (indices V-recoveries) |
| 5:1 | 17% | Crypto full-alignment on major macro shocks can achieve this |

*Even if 40–50% of partials are false, a 2:1 structure is profitable as long as winners reach the limit. Track this in the paper trading log — the key question is whether limits are consistently hit or whether the initial move reverses before reaching target.*

---

## Instrument Signal Quality Analysis

Based on historical signals (28 March – April 2026) and instrument characteristics:

| Instrument | Signal frequency | Why | Stop % | Notes |
|---|---|---|---|---|
| **BTC, ETH, SOL** | HIGH ⭐⭐⭐ | Volatile, moves 5–15% on macro news. All 3 TFs align fast. | 2% | Best historical record. 6 signals on 28 March alone. |
| **BCH** | LOW ⭐ | Low liquidity. Volume anomalies noisy. RSI signals possible but infrequent. | 2% | Consider disabling if noise remains high |
| **Gold** | LOW–MED ⭐⭐ | Strong uptrend. Oversold signals rare but high quality. Partial sell signals active. | 1% | 4h tends to stay elevated in strong uptrends |
| **Silver** | MED ⭐⭐ | More volatile than Gold. RSI extremes more accessible. | 1% | Good diversification of commodity signals |
| **Oil (Brent)** | MED ⭐⭐ | Moves sharply on OPEC/geopolitical news. | 1% | Iran/Russia events can cause 5–10% moves → alignment |
| **Nasdaq 100** | MED ⭐⭐ | More volatile than S&P 500. Better for signals on tech/macro selloffs. | 0.5% | Now on 15m,30m,1h — should catch intraday dips |
| **S&P 500, DAX, FTSE** | LOW→MED ⭐⭐ | Previously LOW with 4h lag. Now on 15m,30m,1h — V-recoveries catchable. | 0.5% | April 2 DAX: would have fired PARTIAL at 12:30pm |

### Best instruments for waking-hours signals (9am–11pm BST)
1. BTC/ETH/SOL — next big macro shock (Fed, Trump, geopolitics)
2. Oil — OPEC meeting or Iran escalation
3. Gold/Silver — safe-haven unwind or demand shock

---

## API Capacity (April 2026, updated 8 Apr)

| Source | Limit | Current usage | Headroom |
|---|---|---|---|
| **IG API** | 60 req/min | 6 instruments × 3 TFs = 18 req/min (at 300s interval = ~3.6 req/min avg) | ~56 remaining |
| **Binance** | 1,200 weight/min | 4 instruments × 3 TFs = 12 req/min | ~1,188 remaining |

Active IG instruments: DAX, FTSE, SActive IG instruments: DAX, FTSE, S&P, Nasdaq (indices), Oil/Brent, Silver (commodities). Gold disabled.P 500, Gold, Oil/Brent (indices/commodities). Nasdaq, Silver disabled. Stale DB records `CS.D.USCGC.TODAY.IP` + `CC.D.LCO.FSD.IP` disabled on 8 Apr.
Stale DB records `CS.D.USCGC.TODAY.IP` + `CC.D.LCO.FSD.IP` disabled on 8 Apr.

---

## Paper Trading Log

Track every signal here before risking real money. Target: 3 months of signals.

| Date | Instrument | Signal | Entry | Stop | Limit | Outcome | Market Regime | Notes |
|---|---|---|---|---|---|---|---|---|
| 28 Mar | SOL | OVERSOLD (full) | $142.50 | $139.65 | $148.20 | ~False positive | News-Driven / High Vol | Pre-guidance. $14.16B BTC options expiry (Mar 27) + Iran/Hormuz escalation drove market-wide sell-off. SOL continued declining through week — limit not hit within 4h. Falling knife in macro-risk-off conditions. |
| 28 Mar | ETH | OVERBOUGHT (full) | $2,004 | $2,044 | $1,924 | ~Correct direction; limit unverified | News-Driven / High Vol | Pre-guidance. ETH broke below $2,000 for first time since mid-2024 on Mar 28 — correct direction. Whether $1,924 limit was hit within 4h unverified; price continued lower over following days. |
| 28 Mar | BTC | OVERBOUGHT (full) | $66,658 | $67,991 | $63,991 | ~Correct direction; -1.5% in session | High Volatility | Pre-guidance. BTC dropped from $71k → $65,720 intraday (Mar 27–28, options expiry). Correct direction. 2:1 limit (~$63,991) not hit on the day — needed ~4.5% move, got ~1.5%. |
| 2 Apr | DAX | PARTIAL_OVERSOLD | 22,742 | 22,628 | 22,969 | **MISSED (would be WIN)** | High Volatility | See April 2 analysis above. With new 15m,30m,1h config: PARTIAL at 12:30pm, 2:1 limit hit ~1:45pm (~EUR 200). |

*Start logging from first signal received with the new guidance notifications*

---

## False Positive Log

Track index PARTIAL signals that fire but don't follow through. Look for emerging patterns over time.

| Date | Instrument | Signal | Time (BST) | Proximity Gap | Why False | Pattern Notes |
|---|---|---|---|---|---|---|
| *(log from first live signal)* | | | | | | |

**What counts as false**: PARTIAL fired, price reversed within the 2h monitoring window, stop hit (or would have been) before limit. Focus on index 15m+30m partials — most prone under the new config. Log: time of day, size of proximity gap to lagging TF, volume context at signal time.

**Patterns to watch for**: Signals firing at market open (high noise), proximity gap >10 pts (lagging TF too far out), pre-close choppy action (15:00–16:30 BST for DAX/FTSE).

---

## Stochastic Confirmation Layer (TODO)

Brian uses Stochastic (14,3,3) as his *primary* trigger on IG — RSI is the filter, Stochastic provides entry timing. Adding this as an optional confirmation check before acting on a PARTIAL or FULL RSI signal would align the system more closely with how Brian actually trades.

**Proposed logic:**
- RSI signal fires (FULL or PARTIAL) → check Stochastic %K on at least the 1h timeframe
- %K < 20 (oversold) → HIGH confidence, proceed
- %K > 20 → signal fires but flagged as "RSI only — no Stochastic confirmation"
- Overbought mirror: %K > 80 = HIGH confidence on OVERBOUGHT signal
- Params: %K=14, Slowing=3, %D=3 (matching Brian's IG setup)

**Implementation note**: IG API provides OHLC candles already fetched for RSI — Stochastic is computable from the same data. No additional API calls needed. Add to `RSICalculatorService` or a new `StochasticCalculatorService`.

*See Section 6.1 of `docs/archived/requirements.md` for Brian's observed Stochastic usage.*

---

## April 7–8 Incident: IG Instruments Total Blackout

### What happened

On 7 April 2026, DAX rallied ~2,200 pts (from ~22,200 to ~24,400 by 8 Apr 09:00 BST) — a major move
ahead of the Iran ceasefire announcement. **No index signals were sent.** Only crypto (Binance)
notifications were received. All IG instruments (DAX, FTSE, S&P, Nasdaq, Oil, Silver) produced
**zero data for the entire day**. 360× IG 403 errors logged, zero successful fetches.

### Root cause: IG historical data allowance exceeded

The IG REST API imposes a **10,000 historical data points per week** limit. The previous config
burned through it in ~3 days:

| Scenario | Instruments | Requests/cycle | Cycles/day (16h) | Daily data points | Weekly |
|---|---|---|---|---|---|
| **Old config** (8 enabled, 5 min interval) | 8 × 3 TFs = 24 | 24 | 192 | 4,608 + warmups | **~37k** |
| Without stale records (6, 5 min) | 6 × 3 TFs = 18 | 18 | 192 | 3,456 | **~24k** |
| **New config** (6, 15 min + candle skip) | 6 × 3 TFs = 18 | ~6 effective | 64 | ~384 | **~2,700** ✅ |

**Two compounding factors:**
1. **Stale DB records**: `CS.D.USCGC.TODAY.IP` (old Gold, enabled=true) and `CC.D.LCO.FSD.IP`
   (expired Oil, enabled=true) were still in the DB — both generated constant 403/404 errors,
   each burning a data point. Failed warmup retries (28 candles × 192 attempts on Apr 7 = ~5,400
   additional data points).
2. **Polling too fast**: Even without stale records, 6 instruments × 3 TFs polled every 5 min
   exceeds the 10k weekly limit by day 3. The code also re-fetched the same candle multiple times
   per period (e.g. 1h candle fetched 12× at 5 min interval — 11 wasted requests).

Once the allowance was exhausted, every IG API `/prices` call returned:
```
{"errorCode":"error.public-api.exceeded-account-historical-data-allowance"}
```
with HTTP 403. The old `handleIgError()` treated all 403s as session expiry and called
`invalidateSession()` on every one — creating a cascade of re-auth + re-fail across all
instruments. 360× 403 errors logged, zero successful fetches for the entire day.

Binance instruments were unaffected (separate HTTP client, no IG dependency).

### Fix applied (8 April 2026)

1. **DB cleanup**: Disabled stale records `CS.D.USCGC.TODAY.IP` (id=11) and `CC.D.LCO.FSD.IP` (id=10)
2. **Split polling** (`MarketDataPollingService`):
   - Binance: every 5 min (no weekly cap)
   - IG: every 15 min (`ig-interval-seconds: 900`), separate `@Scheduled` method
3. **Smart candle-period skip**: IG poll skips fetch if the candle period hasn't elapsed yet
   (e.g. 1h candle only fetched once per hour, not every 15 min). Cuts effective requests ~60%.
4. **403 body logging** (`IGMarketDataClient`): Now parses 403 response body to distinguish
   `exceeded-account-historical-data-allowance` from session expiry. Allowance errors don't
   invalidate the session.
5. **Per-epic 403 tracking**: Persistent 403s on a single epic no longer cascade-invalidate the
   session for all instruments.

### Data point budget (new config)

6 IG instruments × 3 TFs = 18 request slots. With 15 min polling + candle skip:
- 15m TF: fetched every 15 min = 64/day
- 30m TF: fetched every 30 min = 32/day
- 1h TF: fetched every 1h = 16/day
- Total per instrument: ~112/day × 6 instruments = **~672/day, ~4,700/week** (well under 10k)
- Warmup on startup: 6 × 3 × 28 = 504 data points (one-off)

### Lesson

IG's 10,000 data point/week cap is the binding constraint on all IG instrument coverage. Any
config change (adding instruments, reducing interval) must be checked against this budget.
The allowance resets weekly — when exceeded, **all** IG data stops until reset.

---

## Decisions Log

| Date | Decision | Rationale |
|---|---|---|
| Apr 2026 | Full RSI signals bypass quiet hours | Can't miss a 3/3 crash signal at 7am |
| Apr 2026 | Partial signals suppressed 11pm–9am BST | Reduce noise when asleep |
| Apr 2026 | min-baseline-volume: 200 for anomalies | BCH baseline=4 was generating false CRITICAL anomalies |
| Apr 2026 | 2% crypto stop, 0.5% index stop, 1% commodity stop | Based on typical volatility per asset class |
| Apr 2026 | 1% risk per trade (EUR 100 on EUR 10k account) | Conservative for demo learning phase |
| Apr 2026 | Quiet hours use UTC not local time | Docker runs UTC; avoids DST drift issues |
| 5 Apr 2026 | WATCH signal tier + index TF change + partial monitoring | WATCH: 1 TF <30 + 1 TF <40 (proximity). Indices → 15m,30m,1h (4h too slow for V-recoveries). Partial follow-ups every 15 min for 2h. Retrospective endpoint. Signal archival (90 days). April 2 DAX verified: PARTIAL at 12:30pm, 2:1 hit ~1:45pm. |
| 6 Apr 2026 | Weekend suppression for partial/watch signals | PARTIAL and WATCH signals suppressed Sat–Sun (config: `suppress-partials-on-weekends=true`). Full OVERSOLD/OVERBOUGHT still send. Weekend price action often choppy/thin — partial signals not actionable. |
| 6 Apr 2026 | Real-time alert CSV + candle snapshot + outcome backfill | `AlertCsvService` writes every signal immediately to `signal_alerts_YYYY-MM.csv` with full OHLCV candle. Hourly job backfills `price_1h/4h/24h_later` for outcome tracking. Enables backtesting without manual logging. CSV-only — no DB schema change. Fixed missing `rsi_1m/5m/30m` columns in weekly archival CSV. |
| 6 Apr 2026 | No-trade-mode toggle | `POST /api/signals/no-trade-mode/on` suppresses all PARTIAL and WATCH notifications (initial + follow-ups). FULL OVERSOLD/OVERBOUGHT still fire. Use when IG is unavailable (bank holiday, connectivity, personal). Disable via `POST /api/signals/no-trade-mode/off`. Status: `GET /api/signals/no-trade-mode`. In-memory only — resets on restart. |
| 6 Apr 2026 | Per-signal-type ntfy priority | FULL signals → "urgent" (priority 5, bypasses DND). PARTIAL → "default" (priority 3). WATCH → "low" (priority 2). Previously everything was "high". Reduces phone interruption level for non-actionable signals. |
| 6 Apr 2026 | Partial follow-ups only when gap closing | `PartialSignalMonitorService` now only sends follow-up if lagging TF RSI has moved closer to threshold since last check. Widening/static gap = silent update internally, no notification. Still fires FULL ALIGNMENT and expiry notifications. Eliminates most of the follow-up noise when crypto oscillates near a threshold without crossing. |
| 6 Apr 2026 | Partial monitoring window 120→60 min, interval 15→30 min | Halves max follow-up count (was 7, now max 1–2 in practice). Crypto either aligns within 1h or the setup has failed. Configurable via `PARTIAL_MONITORING_WINDOW` / `PARTIAL_MONITORING_INTERVAL` env vars. |
| 6 Apr 2026 | Fix WATCH_OVERBOUGHT proximity formula | Bug: `val > (100 - proximity + threshold)` = `val > 130` — always false. WATCH_OVERBOUGHT never fired. Fixed to `val > (100 - proximity)` = `val > 60`. Overbought proximity band is now correctly 60–70 (within 10 pts of threshold). |
| 8 Apr 2026 | IG data point budget + split polling + 403 handling | IG 10k/week allowance exceeded (Apr 7 blackout). Split polling: Binance 5 min, IG 15 min. Candle-period skip saves ~60% of IG data points. 403 handler now parses body to detect `exceeded-account-historical-data-allowance` vs session expiry. Per-epic 403 tracking prevents cascade. Disabled stale DB records. |
| 9 Apr 2026 | Stochastic oscillator added to FULL signal notifications | Stochastic (14,3) calculated locally from candle history (high/low/close) stored in `PriceHistoryService`. No extra API calls — uses data already in memory. Only fires on FULL (3/3) signals. Notification shows %K and %D per timeframe with OVERBOUGHT/OVERSOLD/NEUTRAL label. %K >80 = overbought, <20 = oversold. `StochasticCalculator` + `StochasticResult` added. |
| 9 Apr 2026 | WATCH signals disabled by default; file logging added | WATCH (1/3 TF) tier disabled (`WATCH_SIGNALS_ENABLED=false`). In volatile markets 6+ WATCH alerts fire simultaneously across all instruments — not actionable and cause notification fatigue. Re-enable via env var when conditions settle. PARTIAL (2/3) + FULL (3/3) unaffected. Added `logback-spring.xml`: rolling daily log file under `./logs/`, 7-day retention, auto-delete. API call rate confirmed safe: Binance ~2.4 rpm (limit 1200), IG guarded by candle-period skip. |
| 16 Apr 2026 | EMA period 50 → 20 + price momentum fallback for trend detection | Root cause: Nasdaq 100 SELL fired at 00:02 Apr 16 into a confirmed uptrend. EMA50 on 1h needs 50+ candles — trend was NEUTRAL during cold-start so suppression didn't activate. Fix: (1) EMA period reduced to 20 (available after 28-candle warmup). (2) Momentum fallback added: if price moved >1% over last 5 1h candles → classify as STRONG_UPTREND/DOWNTREND (needs only 6 candles). Fallback 2 (consecutive signals) retained as last resort. `rsi.trend.ema-period` default: 50 → 20. |
| 16 Apr 2026 | Telegram HTML mode + compact message format | Enabled HTML `parse_mode` in `TelegramNotificationService`. Message changes: bold signal title + price + direction + trend label; italic trend reason, crypto warning, action hints; stochastic collapsed to one line when all TFs share label (e.g. `ALL OVERBOUGHT`); RSI as a single line; demo guidance as a single line; removed "Confirm chart" reminder (implicit); removed redundant "Wait for X/X TF alignment" in partial guidance. Added `escapeHtml()` to cover `S&P 500` title and AI context. Fixed `WATCH_OVERSOLD` missing from `isLong` check in `buildDemoGuidance` (was showing SHORT for an oversold watch). |
| 18 Apr 2026 | Backtest P1 fixes shipped (backtest-report.md) | TREND_SELL_RALLY disabled (`sell-rally-enabled: false`) — 0% TP, −0.79R across 48 signals. EMA hysteresis ±0.1% added to prevent BCH-style whipsaw. TREND_BUY_DIP dedupe added (require >0.3% price move or RSI recovery above 60 before re-firing). Fixed `active_position` auto-set to exclude TREND signals (only OVERSOLD/OVERBOUGHT set position). Min stop floor added (0.2% of price or 2pts minimum). |
| 21 Apr 2026 | Per-instrument TREND_BUY_DIP suppression | New `trend_buy_dip_enabled` column on `instruments` table (Hibernate auto-migrates; default true). Set `false` for FTSE (−0.86R, 7 signals), Gold (−1.00R, 6 signals), Silver (−1.00R, 3 signals) based on Apr 13–18 backtest. YAML is source of truth for this flag (synced on every restart, unlike `enabled` which DB preserves). Guard added at top of `TrendDetectionService.checkForTrendEntry()`. FTSE TREND_BUY_DIP was actively firing today (rsi15m=59.5) — now suppressed. |
| 21 Apr 2026 | BTC re-enabled for candle history collection | `BTCUSDT` re-enabled (`enabled: true`) with `trend-buy-dip-enabled: false`. Rationale: BTC at +0.18R TREND_BUY_DIP is marginal/noise at current data volume; need RSI-bucket outcome split before enabling. Classic OVERBOUGHT/OVERSOLD signals will still fire. BTC candle history now accumulates for future backtesting. Pending: run RSI-bucket analysis (see SQL in backtest-report.md) once 2–3 weeks of `position_outcomes` data exists. |
| 24 Apr 2026 | **dipRsiThreshold 60→45 + ADX(14)>20 filter deployed** | P1 fixes from 22-trade analysis (7/27 win rate, 0% TP hits). Threshold lowered to target "deep pullback in uptrend" zone (Investopedia: RSI 40–70 in uptrends, dip-buy at ~50; practitioners use 40–45 for deeper pullbacks). ADX(14)>20 on 1h trend timeframe suppresses entries during ranging markets — Schwab/FXNX/Investopedia all cite ADX<20 as no-trend. Both deployed via env vars (`TREND_DIP_RSI_THRESHOLD=45`, `TREND_ADX_FILTER_ENABLED=true`). 1-week observation before P2 volume confirmation or correlation guard. |
| 3 May 2026 | **MACD histogram gate for TREND_BUY_DIP** | MACD (12/26/9) histogram computed on the trend timeframe (1h). TREND_BUY_DIP entries blocked when histogram is neither bullish (>0) nor rising (current > previous). Warmup-friendly — `Optional.empty()` does not block. Config keys: `rsi.trend.macd-filter-enabled` (default true), `macd-fast/slow/signal-period`. `MacdCalculator` already existed; wired as a confirmation gate in `TrendDetectionService.checkForTrendEntry()` after the ADX block. |
| 6 May 2026 | **OOM kill loop — JVM caps + swap added** | Instance became unreachable (sshd killed by OOM killer). Root cause: Dockerfile had no JVM flags — JVM defaulted to unbounded heap, RSS grew to ~370MB before the kernel killed it. `docker-compose restart: unless-stopped` kept respawning it, causing kill cycles every ~24h. Fix: (1) Added `-Xmx320m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC` to Dockerfile ENTRYPOINT. Note: 64m Metaspace caused startup OOM — Spring Boot needs ~100-120MB for class loading; 128m confirmed working. (2) Added 1GB swap (`/swapfile`) to EC2, persisted in `/etc/fstab`. Previous attempt to add JVM flags had used too-low Metaspace and caused startup failures — do not set below 128m. |
| 3 May 2026 | **Persistent filter_event_counts table** | New `filter_event_counts` table records every signal suppression with an UPSERT (one row per filter_name + symbol + day, count incremented on conflict). Survives log rotation. `FilterEventCounterService.record()` wired at all 9 suppression sites: ADX_RANGING, MACD_HISTOGRAM, CRYPTO_VOLUME (TrendDetectionService); ANOMALY_RECENT, RISK_OFF, RISK_ON, HIGH_VOLATILITY (SignalDetectionService); DUPE_OPEN_POSITION, POSITION_COOLDOWN (PositionOutcomeService). Weekly CSV dump added to `backup-ec2-data.yml` workflow. Wrapped in try/catch — counter blip never breaks a hot path. |
| 18 Apr 2026 | P&L position tracking + auto-report | New `position_outcomes` table tracks every actionable signal (OVERSOLD, OVERBOUGHT, TREND_BUY_DIP, TREND_SELL_RALLY) with entry/TP/SL prices. Hourly job checks 1h candle highs/lows for exit conditions; 24h auto-close fallback. `GET /api/positions/pnl-summary` returns win rate, avg P&L, expectancy by signal type. Daily markdown report auto-written to `reports/pnl-report.md` (06:00 UTC). On-demand: `make pnl-report`. Replaces manual SQL backtests — results accumulate automatically. |
