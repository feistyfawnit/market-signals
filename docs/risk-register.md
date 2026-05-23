# LucidLynx Market Signals — Risk Register & Open Items

*Last updated: May 2026*
*Renamed from Windsurf.md — April 2026*

---

> ⚠️ **IG API DATA ALLOWANCE — CRITICAL**
> IG permits **10,000 data points/week**. Exceeding this returns HTTP 403 (`exceeded-account-historical-data-allowance`). **First occurred April 8, 2026.**
> - Current safe budget: **~5,300 pts/week** (IG polling every 15 min, 5 IG instruments with candle-period skip). Pre-Apr-8 baseline was ~4,700/week before TREND_BUY_DIP intraday warmups were added.
> - Each candle fetch = 1 data point; warmup fetches 28 at once
> - **Do NOT**: add IG instruments, shorten IG polling interval, or trigger bulk warmup without recalculating budget
> - **Do NOT**: make ad-hoc IG price/candle API calls without counting the cost

---

## Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| IG API rate limit ban | Medium | High | Exponential backoff; respect limits; demo-first |
| False signals in choppy markets | High | Medium | ✅ ADX(14)>20 filter (Apr 24 2026); ✅ EMA-slope filter ≥ 0.05% (May 22 2026); ✅ dedupe tightening (May 22 2026) — see `project-log.md#2026-05-22` |
| RSI calculation bugs | Low | High | Unit test against TA-Lib; backtest on historical data |
| AWS EC2 downtime / OOM kill | **Occurred** | **High** | JVM capped at `-Xmx320m`; 2 GB swap added; `restart: unless-stopped` auto-recovers app but does not recover sshd if OOM kills OS processes |
| Feature growth on t3.micro | Medium | Medium | Each new scheduled service adds heap + thread pressure. t3.micro has 1 GB RAM. New features must be assessed for memory/CPU impact before merging (AGENTS.md § Efficiency Guardrails). |
| Unbounded DB query on hot path | Low | Medium | ✅ `AtrCalculator` bounded May 22 2026; pattern enforced via AGENTS.md (`findBy...Desc(..., PageRequest)`) |
| Market closure edge case | Medium | Low | Skip polling outside market hours per instrument |
| IG 10k/week data allowance exceeded | **Occurred** | **Critical** | Split polling (IG 15 min), candle-period skip, stale DB cleanup; see April 7 incident |

## Business / Trading Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Strategy stops working | Medium | High | R-multiple P&L tracking + daily report; per-instrument disable flags (e.g. DAX TREND_BUY_DIP turned off after 0/9 wins) |
| Trend detection false positive | Medium | Medium | Tighter stops (1.5× ATR for trend); 16 h auto-close (lowered 24→16h May 2026); EMA-slope + ATR-min + dedupe filters |
| Overfitting to recent conditions | Medium | Medium | Apr 2026 backtest archived in `docs/archived/backtest-report.md`; new filters staged OFF before observation |
| Auto-execution bug | Low | **Critical** | Demo-only by default; manual approval via Telegram inline keyboard; kill switch; position limits; ratcheting trailing stop never moves stop adverse |
| MiFID II breach if shared | Low | **Critical** | Never distribute; keep private; personal use documented |

---

## Open Items

- **DB/YAML instrument sync** — when epic codes change in `application.yml`, stale DB rows must be disabled/deleted manually. JPA `ddl-auto: update` does not remove old rows. Consider a startup reconciliation job that disables DB instruments not present in YAML.
- **ATR-min-% filter calibration** — staged OFF by default (May 22 2026). Needs 2+ weeks of `filter_event_counts.ATR_RANGE_BOUND` data + `position_outcomes` review before enabling. Suggested starter for crypto: 0.4%.
- **Forward observation of May 22 filters** — monitor `filter_event_counts.EMA_SLOPE_FLAT` and `DIP_DEDUPE` weekly. Loosen EMA-slope threshold (0.05% → 0.03%) if it suppresses real trend days.
- **Backtesting refresh** — Apr 2026 backtest archived; consider re-running on May 2026 data once new filter set has 30+ days of signals.

---

## Configuration Decisions Log

> **Canonical source**: See [`docs/project-log.md`](project-log.md) for the full incident and decision history. This section summarises only risk-relevant decisions.

| Date | Decision | Risk Impact |
|------|----------|-------------|
| Mar 2026 | Volume spike threshold: 4.0σ → 5.5σ, lookback 20→30, min-periods 10→20 | Later retired entirely (Apr 25) — zero correlation with signal quality |
| 8 Apr 2026 | IG 15 min polling + candle-period skip + 403 handling | IG 10k/week budget: ~4,700/week baseline; now ~5,300 with TREND warmups |
| 14 Apr 2026 | Trend Detection + TREND_BUY_DIP / TREND_SELL_RALLY | Trend signals dominate alert volume (79.5%). SELL_RALLY disabled Apr 18 (−0.79R) |
| 24 Apr 2026 | `dipRsiThreshold` 60→45; ADX(14)>20 filter on trend timeframe | Cuts dip-in-uptrend false positives |
| 25 Apr 2026 | Removed volume-anomaly, Polymarket, cross-correlation, momentum-surge, oil-review features | Zero actionable signals — net heap + scheduler load reduction |
| 17 May 2026 | `IGTradingService` direction switch fix; standard (non-guaranteed) stops | Enables TREND_BUY_DIP / TREND_SELL_RALLY auto-execution; trailing-stop updates incur no IG charge |
| 22 May 2026 | EMA-slope filter ON (0.05% over 5 candles); RSI-recovery + pctMove dedupe tightening; trail-stop guidance in Telegram | SOL chop suppression — monitor `filter_event_counts.EMA_SLOPE_FLAT`/`DIP_DEDUPE` |
| 22 May 2026 | `AtrCalculator` queries bounded via `PageRequest` (5×period rows) | Removes latent unbounded scan on every poll — AGENTS.md guardrail compliance |

---

*Private Use*
