# AGENTS.md

## Project Purpose

`market-signals` is a private Spring Boot market-monitoring service.
It polls Binance and IG market data, calculates RSI and Stochastic across configured timeframes, detects signals, logs them, and sends Telegram alerts.

## Start Here

If you are reviewing or changing this repo, read in this order:

1. `README.md` — human-friendly overview and doc map
2. `src/main/resources/application.yml` — current runtime configuration and enabled instruments
3. `docs/troubleshooting.md` — operational checks, IG quota limits, common failures
4. `docs/project-log.md` — important incidents, decisions, and historical context
5. `docs/risk-register.md` — operational constraints and open risks

## Current Runtime Shape

- **Binance instruments**: `SOLUSDT` (full signals), `BTCUSDT`/`ETHUSDT` (enabled, no suppression), `BCHUSDT` (disabled)
  - Timeframes: `15m,1h,4h`
- **IG indices**: DAX (full signals, 0/7 TREND_BUY_DIP in current data — under review); S&P 500 (TREND_BUY_DIP silent `notify:false` — recording P&L without Telegram noise); FTSE 100 (TREND_BUY_DIP disabled); Nasdaq 100 (disabled)
  - Timeframes: `15m,30m,1h`
- **IG commodities**: Gold (TREND_BUY_DIP disabled); Silver, Oil (disabled)
  - Timeframes: `15m,1h,4h`
- **`trend-buy-dip-enabled` / `trend-buy-dip-notify` flags**: per-instrument in YAML, synced to DB on restart. YAML wins for these fields (unlike `enabled` which DB preserves). FTSE/Gold = disabled; S&P = silent recording.

## Important Guardrails

- **IG historical data allowance is the main constraint**: 10,000 data points/week
- Current enabled IG set is designed to stay around **~5,300 points/week**
- **Do not** add IG instruments, change IG polling frequency, or trigger repeated warmups without recalculating budget
- **Do not** trust a new IG epic code until it is verified with:
  - IG market search
  - a direct one-candle `/prices/.../1` curl test
- `application.yml` seeds instruments, but stale DB rows can still exist after epic changes; restarting and checking enabled instruments/logs matters

## Risk model (as of 2026-04-22)

- **Stops**: ATR(14) on 15m × multiplier (1.5 trend, 2.0 non-trend). Falls back to fixed-pct (`stop-percent-*`) when ATR unavailable. Toggle via `rsi.demo.atr-stops-enabled`.
- **Reward:Risk** (trend signals): crypto 2:1, indices 3:1, commodities 3:1. Non-trend always 2:1. Configurable per asset class under `rsi.demo.trend-rr-*`.
- **Crypto 2:1** was lowered from 3:1 after SOL produced 5 wins all via 24h auto-close at +2–3% with zero TP hits — 3:1 target was unreachable on a 24h horizon.
- **P&L report** uses R-multiple €-estimation: `€ = (pnlPct / stopPctAtEntry) × riskEur`. This correctly credits 24h auto-closes with positive P&L (was previously mis-classified as fixed-€ losses).
- **Apr 24 2026 P1 changes deployed**: `dipRsiThreshold` lowered 60→45 (cited: Investopedia pullback-in-uptrend zone); `ADX(14) > 20` filter enabled on trend timeframe to skip entries during ranging markets. Monitoring for 1 week before P2.
- **Crypto volume confirmation** (`rsi.trend.crypto-volume-*`): TREND_BUY_DIP on CRYPTO instruments requires trigger-candle 15m volume > 1.2× the 20-period mean. IG CFD volume is unreliable — filter is silently skipped for indices/commodities and during warmup (<20 candles). Source: LuxAlgo volume guide + r/algotrading consensus.

## Efficiency & Resource Guardrails

The app runs on an **AWS t3.micro (1 GB RAM, 1 vCPU)**. Efficiency is a first-class priority — every change must consider heap, DB, and API cost.

- **JVM is tightly capped**: `-Xmx320m -XX:MaxMetaspaceSize=64m -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC`. Do not raise these without profiling first.
- **Docker hard limits**: app container `memory: 450M`, Postgres `memory: 100M`. OOM kills are real.
- **DB queries must be bounded**: `TrendDetectionService.isVolumeConfirmed()` now uses `findBySymbolAndTimeframeOrderByCandleTimeDesc(..., Pageable.ofSize(lookback))` — only the last N candles are fetched. ✅ Fixed Apr 2026.
- **Tables that grow forever need archival**: `signal_logs` (90-day), `position_outcomes` (90-day), and `candle_history` (60-day) are all archived weekly/monthly by `HistoryArchivalService` to dated CSVs in `signal_archive/`, then pruned from DB. ✅ Fixed Apr 2026.
- **Telegram message batching**: `PartialSignalMonitorService` already tightened (60 min window, 30 min interval). Do not increase notification frequency.
- **No local + AWS simultaneous runs**: doubles IG data point consumption and JVM/DB contention. `make up` is guarded with `LOCAL_RUN=yes` check.

## Canonical Truth vs Historical Docs

- **Current truth**:
  - `application.yml`
  - `README.md`
  - `docs/troubleshooting.md`
- **Historical/reference docs**:
  - `docs/project-log.md` — incident and decision history **← check here before proposing previously-removed features**
  - `docs/archived/requirements.md` — original project specification; useful context, but not the current source of truth
  - `docs/roadmap.md` — backlog and phase tracking

## Previously Removed Features (do not re-add without new evidence)

Features removed as dead weight — zero actionable signals after weeks of monitoring. See `docs/project-log.md#2026-04-25` for full details:

| Feature | Reason |
|---------|--------|
| Volume anomaly detection (σ-based) | Spikes never correlated with signal quality |
| Polymarket odds monitoring | Odds shifts never preceded price moves |
| Cross-correlation burst alerts | 60s window almost never clustered |
| Price momentum surge reports | Retrospective-only, never informed live trading |
| Oil opportunity review | Retrospective-only, never informed live trading |

If proposing a new feature that sounds similar: check `docs/project-log.md` first. Any re-introduction requires concrete evidence (backtest data, new data source, or materially different methodology) that it would behave differently this time.

## Deployment

Primary path: **AWS Free Tier** — Dublin region (`eu-west-1`), 12 months free, ~€15/month after.
See `docs/remote-deployment.md` for full guide (Terraform, Oracle, Alibaba, CI/CD all included).

## Useful Commands

```bash
# Local (Mac / Colima)
make up
make logs
make pnl-report

# AWS EC2 (run from Mac — SSH in automatically)
make deploy        # pull + rebuild + start on EC2
make remote-logs   # tail logs from EC2
make ship          # deploy then tail (one command)

# Health checks (swap localhost for EC2 IP when targeting AWS)
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/instruments/enabled
curl http://localhost:8080/api/signals/rsi-snapshot
curl http://localhost:8080/api/positions/pnl-summary
```
