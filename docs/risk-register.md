# LucidLynx Market Signals — Risk Register & Open Items

*Last updated: April 2026*
*Renamed from Windsurf.md — April 2026*

---

> ⚠️ **IG API DATA ALLOWANCE — CRITICAL**
> IG permits **10,000 data points/week**. Exceeding this returns HTTP 403 (`exceeded-account-historical-data-allowance`). **This occurred April 8, 2026.**
> - Current safe budget: ~5,300 pts/week (IG polling every 15 min, 5 IG instruments with candle-period skip)
> - Each candle fetch = 1 data point; warmup fetches 28 at once
> - **Do NOT**: add IG instruments, shorten IG polling interval, or trigger bulk warmup without recalculating budget
> - **Do NOT**: make ad-hoc IG price/candle API calls without counting the cost

---

## Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| IG API rate limit ban | Medium | High | Exponential backoff; respect limits; demo-first |
| False signals in choppy markets | High | Medium | ✅ ADX filter deployed Apr 24 — ADX(14)>20 required for trend entries |
| RSI calculation bugs | Low | High | Unit test against TA-Lib; backtest on historical data |
| AWS EC2 downtime / OOM kill | **Occurred** | **High** | JVM caps now set (see May 2026 incident); 1GB swap added; `restart: unless-stopped` auto-recovers app but does not recover sshd if OOM kills OS processes |
| Feature growth on t3.micro | Medium | Medium | Each new scheduled service (MACD, ADX, correlation, archival, oil report) adds heap + thread pressure. t3.micro has 1GB RAM. New features must be assessed for memory/CPU impact before merging. |
| Market closure edge case | Medium | Low | Skip polling outside market hours per instrument |
| IG 10k/week data allowance exceeded | **Occurred** | **Critical** | Split polling (IG 15 min), candle-period skip, stale DB cleanup; see April 7 incident |

## Business / Trading Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Strategy stops working | Medium | High | Monthly performance review; kill switch if drawdown >5% |
| Trend detection false positive | Low | Medium | Tighter stops (half normal) on TREND_BUY_DIP/TREND_SELL_RALLY; trend expires after 12h if no confirming signal |
| Overfitting to recent conditions | Medium | Medium | Backtest across multi-year bull/bear data |
| Auto-execution bug (Phase 4) | Low | **Critical** | Extensive demo testing; manual approval; position limits |
| MiFID II breach if shared | Low | **Critical** | Never distribute; keep private; personal use documented |

---

## Open Items

- **Backtesting** — fetch 3–6 months historical data, replay signals before live trading
- **Market hours** — skip polling closed markets (DAX 08:00–22:00 CET, FTSE 08:00–16:30 GMT); crypto 24/7
- **Staggered polling** — 1m TF: 15s, 5m TF: 30s, 1h/4h TF: 2–5 min — reduces IG API calls ~60%
- **DB/YAML instrument sync** — when epic codes change in `application.yml`, stale DB rows must be disabled/deleted manually. JPA `ddl-auto: update` does not remove old rows. Consider a startup reconciliation job that disables DB instruments not present in YAML.

---

## Configuration Decisions Log

> **Canonical source**: See [`docs/project-log.md`](project-log.md) for the full incident and decision history. This section summarises only risk-relevant decisions.

| Date | Decision | Risk Impact |
|------|----------|-------------|
| Mar 2026 | Volume spike threshold: 4.0σ → 5.5σ, lookback 20→30, min-periods 10→20 | Reduced false positives at market open |
| 8 Apr 2026 | IG 15 min polling + candle-period skip + 403 handling | IG 10k/week budget: ~4,700/week. Critical — do not exceed. |
| 14 Apr 2026 | Trend Detection + TREND_BUY_DIP / TREND_SELL_RALLY | Trend signals dominate alert volume (79.5%). SELL_RALLY disabled Apr 18 (−0.79R). |

---

*Private Use*
