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

- **Binance instruments**: `SOLUSDT` (full signals, **auto-execute**), `BTCUSDT`/`ETHUSDT` (enabled, **auto-execute**), `BCHUSDT` (disabled)
  - Timeframes: `15m,1h,4h`
  - Auto-execute: crypto instruments skip the 120s Telegram confirmation keyboard and place IG demo deals immediately. Indices/commodities still require manual approval. Toggle per-instrument via `auto-execute-enabled` in YAML (synced to DB on restart). Jul 4 2026.
- **IG indices**: DAX (TREND_BUY_DIP re-enabled SILENT `notify:false` Jun 23 2026 — gathering post-chop-filter forward data; old 0/9 / €901 record predates the May ADX-12/EMA/MACD filters); S&P 500 (TREND_BUY_DIP `notify:true` as of Jun 14 2026 — best forward performer; revisit if win rate <50% over next ~10 trades); FTSE 100 (TREND_BUY_DIP disabled); Nasdaq 100 (disabled)
  - Timeframes: `15m,30m,1h`
- **IG commodities**: Gold (TREND_BUY_DIP re-enabled SILENT `notify:false` Jun 23 2026 — low-confidence −1.00R backtest, visibility only; `rsi-signals-enabled:false`); Silver (re-enabled Jun 23 2026, TREND_BUY_DIP SILENT — **needs runtime toggle**, see note); Oil (enabled, TREND_BUY_DIP disabled — leading indicator only)
  - Timeframes: `15m,1h,4h`
- **`trend-buy-dip-enabled` / `trend-buy-dip-notify` / `auto-execute-enabled` flags**: per-instrument in YAML, synced to DB on restart. YAML wins for these fields (unlike `enabled` which DB preserves). DAX/Gold/Silver = enabled-but-silent; FTSE = disabled; S&P = notifying (Jun 14 2026). SOL/BTC/ETH = auto-execute (Jul 4 2026).
- **Silver `enabled` caveat**: YAML `enabled:true` does NOT override an existing disabled DB row (`DataInitializer` preserves DB `enabled`). Silver (id 11) must be turned on once at runtime: `curl -X POST http://localhost:8080/api/instruments/11/toggle`.

## Important Guardrails

- **IG historical data allowance is the main constraint**: 10,000 data points/week
- Current enabled IG set is designed to stay around **~5,300 points/week** (Silver added Jun 23 2026 → ~6,300/week, still under the 10,000 cap)
- **Do not** add IG instruments, change IG polling frequency, or trigger repeated warmups without recalculating budget
- **Do not** trust a new IG epic code until it is verified with:
  - IG market search
  - a direct one-candle `/prices/.../1` curl test
- `application.yml` seeds instruments, but stale DB rows can still exist after epic changes; restarting and checking enabled instruments/logs matters

## Risk model (current — see `docs/project-log.md` for history)

- **Stops**: ATR(14) on 15m × multiplier (1.5 trend, 2.0 non-trend). Falls back to fixed-pct (`stop-percent-*`) when ATR unavailable. Toggle via `rsi.demo.atr-stops-enabled`.
- **Reward:Risk** (trend signals): crypto 2:1, indices 3:1, commodities 3:1. Non-trend always 2:1. Configurable per asset class under `rsi.demo.trend-rr-*`.
- **Crypto 2:1** was lowered from 3:1 after SOL produced 5 wins all via 24h auto-close at +2–3% with zero TP hits — 3:1 target was unreachable on a 24h horizon.
- **P&L report** uses R-multiple €-estimation: `€ = (pnlPct / stopPctAtEntry) × riskEur`. This correctly credits 24h auto-closes with positive P&L (was previously mis-classified as fixed-€ losses).
- **Apr 24 2026 P1**: `dipRsiThreshold` lowered 60→45; `ADX(14) > 20` filter on trend timeframe.
- **Crypto volume confirmation** (`rsi.trend.crypto-volume-*`): TREND_BUY_DIP on CRYPTO requires trigger-candle 15m volume > 1.2× the 20-period mean. IG CFD volume is unreliable — filter is silently skipped for indices/commodities and during warmup. Source: LuxAlgo + r/algotrading.
- **May 22 2026 chop filters**: EMA-slope filter (default **ON**, 0.05% over 5 candles) and dedupe tightening (RSI recovery requires threshold + 5; pctMove floor 0.5%) live; ATR-min-% filter staged **OFF**. Telegram alerts now include a `🪜 Trail:` line for actionable signals. Full detail: `docs/project-log.md#2026-05-22`.
- **Jun 14 2026 paper trailing + concurrency cap**: trailing now actually executes for paper positions (no live IG deal). `PositionOutcomeService.replayCandles` ratchets the stop in the close-time candle replay — at +50%-of-stop → breakeven, then trails that distance below new extremes. Previously trailing only ran for `igDealId != null` (live auto-exec), so paper/confirmation trades were never trailed and the report's `Trail+` label was a misnomer for profitable 16h auto-closes (those are now `Hold+`). Genuine trailed exits are `slHit && profitable` → still labelled `Trail+`. Toggle via `rsi.demo.paper-trailing-enabled` (default true). New per-asset-class concurrency cap `rsi.demo.max-concurrent-per-asset-class` (default 2) blocks correlated SOL+BTC+ETH from all opening on one market-wide dip.
- **Jun 17 2026 confirm-button regression fix**: `IGTradingService.checkRiskLimits()` was gating on an in-memory `openPositionCount` that only ever incremented (never decremented on close), so after 2 accepted IG deals the gate wedged shut and **every subsequent confirmation keyboard was silently suppressed** (the "button disappeared"). Now derives the live count from `positionOutcomeRepository.findByExitTimeIsNull()` filtered by `igDealId != null` — self-correcting. Also synced quiet-hours into `IGTradingService` and `NotificationService` (FULL signals no longer bypass quiet hours).
- **Jun 23 2026 trailing loosened + signal-volume push** (user: "few winners, had to trail manually, prefer more signals"): (1) Trailing geometry made configurable and loosened from a hardcoded 0.5/0.5 to **1.0/1.0** (`rsi.demo.trail-activation-mult` / `trail-distance-mult`) — at 0.5 the stop snapped to breakeven at +0.5R and trailed half a stop away, so low-vol winners exited at ~breakeven (the Jun 20-22 +€10/€17/€32 scratches). 1.0/1.0 gives a full stop of room before breakeven and trails a full stop wide. Reads in `PositionOutcomeService` (live `tryTrailStop` + paper `replayCandles`) and the Telegram `🪜 Trail:` guidance. (2) `adx-threshold` 15→**12** — ADX_RANGING was the most-firing 'marginal' suppressor. (3) DAX/Gold/Silver TREND_BUY_DIP re-enabled SILENT for forward data. See `docs/project-log.md#2026-06-23`.
- **Jun 23 2026 spread tracking + filter** (user: "should we consider the spread? seems a lot from some placements"): (1) Added `entry_spread_pts` / `entry_spread_pct` to `position_outcomes`, captured from the live IG snapshot when `IGTradingService.placeDeal` runs. Visible in the P&L report (`Sprd` column) and CSV export. (2) Added `trading.auto-execution.max-spread-pct-of-stop:25.0` — live IG deals are skipped if the bid/offer spread is >25% of the planned stop. Catches low-edge Sunday/thin-market entries. The Jun 21 insta-stop-out fix already floors stops at `spread × 2.0`; this adds an explicit rejection guard. See `docs/project-log.md#2026-06-23-spread`.
- **Jul 4 2026 per-instrument auto-execute + quiet-hours bypass**: crypto instruments (SOL/BTC/ETH) now auto-execute IG demo deals immediately — no 120s Telegram confirmation keyboard. Indices/commodities (S&P/DAX/Gold/Silver) still require manual approval. Toggle per-instrument via `auto-execute-enabled` in YAML (synced to DB on restart). Quiet hours (22:00–08:00 UTC) now bypassed for auto-execute instruments only — manual-approval instruments still skip during sleep so their confirmation keyboard never fires at 3am. `NotificationService`/`PositionOutcomeService` independently suppress Telegram pushes for ALL instruments during quiet hours, so overnight auto-executed trades are silent and appear in the next `/pnl-report` (same as the existing `trend-buy-dip-notify:false` silent pattern). See `docs/project-log.md#2026-07-04`.
- **Jul 4 2026 AdxCalculator bounded query**: `computeAdx` now uses `findBySymbolAndTimeframeOrderByCandleTimeDesc(..., PageRequest.of(0, n))` + reverse — same bounded pattern as `AtrCalculator` (May 2026). The old unbounded `...Asc` variant was the last remaining full-table-per-symbol scan on the hot path (called every poll cycle per instrument via `TrendDetectionService`). Removed the unbounded repository method entirely to prevent accidental reuse. See `docs/project-log.md#2026-07-04`.
- **Jun 17 2026 falling-knife filter** (`rsi.trend.dip-max-drop-*`): TREND_BUY_DIP is suppressed when price has dropped more than `dip-max-drop-pct` over the last `dip-max-drop-lookback` (default 3) fast-TF candles — a violent fall, not a healthy pullback. Tracked via `filter_event_counts` (`DIP_FALLING_KNIFE`).
  - **Jun 18 2026 floor raised 0.6%→2.0%**: at 0.6% over 45min (3×15m) the filter overlapped with the very mechanism that produces a dip signal and muted most legitimate crypto pullbacks (no signals 16th→18th in daytime). 2.0% only catches genuine crashes.
  - **Jun 20 2026 DISABLED by default** (`dip-max-drop-filter-enabled=false`): 2.0% was still not enough — the user reported a week-long dip-signal drought. The filter is on the *same fast timeframe whose RSI pullback defines the dip*, so it structurally fights the signal it guards. Turned OFF to restore signal volume, in line with the user's economics (more signals + trailing stops; one runner pays for several small losers). Re-enable with a higher pct (3-4%) only if mid-knife entries become a measurable drag in `position_outcomes`.

## Efficiency & Resource Guardrails

The app runs on an **AWS t3.micro (1 GB RAM, 1 vCPU)**. Efficiency is a first-class priority — every change must consider heap, DB, and API cost.

- **JVM is tightly capped**: `-Xmx320m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC` (set in `Dockerfile` ENTRYPOINT). Do not raise these without profiling first.
- **Docker hard limits**: app container `memory: 450M`, Postgres `memory: 100M`. OOM kills are real.
- **DB queries must be bounded**: candle-history reads use `findBySymbolAndTimeframeOrderByCandleTimeDesc(..., PageRequest.of(0, n))` — only the last N candles are fetched. Applied in `TrendDetectionService.isVolumeConfirmed()` (Apr 2026), `AtrCalculator.computeAtr` / `atrExpansionRatio` (May 2026), and `AdxCalculator.computeAdx` (Jul 2026). When adding a new caller, reuse the same pattern; never use the unbounded `...Asc` variant. The unbounded `findBySymbolAndTimeframeOrderByCandleTimeAsc` repository method was removed entirely Jul 2026 to prevent accidental reuse.
- **Tables that grow forever need archival**: `signal_logs` (90-day), `position_outcomes` (90-day), and `candle_history` (60-day) are all archived weekly/monthly by `HistoryArchivalService` to dated CSVs in `signal_archive/`, then pruned from DB. ✅ Fixed Apr 2026.
- **Telegram message batching**: `PartialSignalMonitorService` already tightened (60 min window, 30 min interval). Do not increase notification frequency.
- **No local + AWS simultaneous runs**: doubles IG data point consumption and JVM/DB contention. `make up` is guarded with `LOCAL_RUN=yes` check.

## Canonical Truth vs Historical Docs

- **Current truth**:
  - `application.yml`
  - `README.md` — includes full doc index table
  - `docs/troubleshooting.md`
- **Historical/reference docs**:
  - `docs/project-log.md` — incident and decision history **← check here before proposing previously-removed features**
  - `docs/archived/requirements.md` — original project specification; useful context, but not the current source of truth
  - `docs/roadmap.md` — backlog and phase tracking
- **MCP / agent tooling**: `../cline-mcp-setup.md` (workspace level) documents Cline + VS Code Copilot MCP servers (fetch, Atlassian, Datadog, DBMentor). This repo has no project-specific MCP servers — standard Cascade/Cline tooling is sufficient.

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

Primary path: **git push to `main`** → GitHub Actions (`.github/workflows/deploy.yml`) auto-deploys to EC2 via SSH.
- **Do NOT use `make ship` for normal deploys** — GHA is the canonical deploy path.
- `make ship` / `make deploy` are available for manual/emergency deploys only.
- GHA workflow: `git fetch + reset --hard + docker-compose down + up -d --build`
- No build or test step runs before deploy — a broken push goes straight to production.

AWS Free Tier — Dublin region (`eu-west-1`), 12 months free, ~€15/month after.
See `docs/remote-deployment.md` for full guide (Terraform, Oracle, Alibaba, CI/CD all included).

## Useful Commands

```bash
# Local (Mac / Colima)
make up
make logs
make pnl-report

# Deploy (primary path — triggers GHA auto-deploy)
git push  # push to main → GHA deploys automatically

# AWS EC2 manual/emergency deploy (run from Mac — SSH in automatically)
make deploy        # pull + rebuild + start on EC2
make remote-logs   # tail logs from EC2
make ship          # deploy then tail (one command) — manual only

# Health checks (swap localhost for EC2 IP when targeting AWS)
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/instruments/enabled
curl http://localhost:8080/api/signals/rsi-snapshot
curl http://localhost:8080/api/positions/pnl-summary
```
