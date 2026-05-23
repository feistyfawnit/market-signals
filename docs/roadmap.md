# LucidLynx Market Signals — Roadmap

*Last updated: May 2026. Live on AWS EC2 (eu-west-1). For deeper architecture notes see `architecture.md`; for risk decisions see `risk-register.md`; for full signal-design narrative see `project-log.md`.*

---

## Phase Status

| Phase | Status | Notes |
|-------|--------|-------|
| 1 — Core multi-indicator alerts | ✅ Live | Binance + IG; RSI across 15m/1h/4h; Telegram (ntfy.sh retired Apr 2026). |
| 2 — IG API integration | ✅ Live | Session auto-refresh; DAX / FTSE / S&P / Gold / Oil / Silver seeded. |
| 3 — AI enrichment | ✅ Built, disabled | Both `ClaudeEnrichmentService` and `DeepSeekEnrichmentService` wired. Toggle with `CLAUDE_ENABLED=true` or `DEEPSEEK_ENABLED=true` + corresponding API key. DeepSeek is the preferred path going forward. |
| 4 — IG auto-execution | ✅ Scaffolded, OFF by default | `TRADING_AUTO_EXECUTION_ENABLED=false` default. Telegram inline-keyboard manual approval + ratcheting trailing stop + kill switch all live. Requires 3+ months paper-trade validation before enabling. Direction switch + standard-stop fixes shipped May 17 2026. |
| 5 — Anomaly / geopolitical | ⏳ Partial | **Cross-instrument correlation** and **Volatility regime filter** live. Volume spike, Polymarket, momentum surge, and oil opportunity features removed Apr 2026 — zero actionable signals after weeks of monitoring (see `project-log.md`). Only **Uncertainty Mode** not started. |

---

## ✅ Completed Milestones (newest first)

| Date | Milestone | Details |
|------|-----------|---------|
| 2026-05-22 | **Chop filters + dedupe tightening + trail-stop guidance** | EMA-slope filter (default ON, 0.05% over 5 candles) and dedupe tightening (RSI recovery requires threshold + 5; pctMove floor 0.5%) live; ATR-min-% filter staged OFF. Telegram alerts include `🪜 Trail:` line for actionable signals. See `project-log.md#2026-05-22`. |
| 2026-05-22 | **AtrCalculator query bounding** | Both `computeAtr` and `atrExpansionRatio` now use `PageRequest.of(0, 5×period)` instead of full-table fetch. Matches the `isVolumeConfirmed` pattern; removes a latent hot-path scan that ran on every poll. |
| 2026-05-17 | **IGTradingService fixes + Phase 4 trail-stop design** | Direction switch added for `TREND_BUY_DIP` / `TREND_SELL_RALLY` (previously fell through to default null and silently aborted); standard (non-guaranteed) stops adopted — free to place and free to modify (required for trailing). |
| 2026-04-25 | **Removed dead-weight anomaly features** | Volume anomaly, Polymarket, cross-correlation burst, momentum surge, oil opportunity review — zero actionable signals after weeks of monitoring. Net heap + scheduler load reduction. |
| 2026-04-24 | **dipRsiThreshold 60→45 + ADX(14)>20 filter** | P1 fixes from 22-trade analysis. Threshold targets Investopedia-cited "deep pullback in uptrend" zone (40–50). ADX filter suppresses TREND_BUY_DIP during ranging markets (Schwab/Investopedia: ADX<20 = no trend). Deployed together; monitoring 1 week before P2. |
| 2026-04-24 | **Volume confirmation for crypto TREND_BUY_DIP** | Require 15m entry-candle volume > 1.2× 20-period mean for crypto TREND_BUY_DIP. IG CFD volume unreliable — filter only applies to CRYPTO; skipped silently during warmup. Source: LuxAlgo + r/algotrading consensus. |
| 2026-04-22 | **Silent signal recording** | Per-instrument `trend-buy-dip-notify` flag — signals log to `position_outcomes` without Telegram alert. S&P 500 TREND_BUY_DIP now silent pending ≥20 trade sample. |
| 2026-04-22 | **Crypto enabled broadly** | BTC + ETH TREND_BUY_DIP re-enabled after R:R drop to 2:1 (previously unreachable at 3:1). |
| 2026-04-22 | **ATR stops + asset-class R:R + R-multiple P&L** | `AtrCalculator` on 15m; stops = ATR × 1.5 (trend) / 2.0 (other). Trend R:R now 2:1 crypto / 3:1 indices. Report €-estimate uses `pnlPct / stopPctAtEntry × riskEur` so 24h auto-closes at +P&L count correctly. Unified crypto exit timeframe to 15m (fixes the SOL 24h auto-close bug where 5m was never polled). |
| 2026-04-22 | **Open positions at top of P&L report** | Same columns as Closed table so rows align visually. Added `Realistic Net` line excluding (symbol, signalType) combos with ≥3 trades and 0 TP hits. |
| 2026-04-22 | **Candle history CSV backup** | `make candles-backup-local` / `make candles-backup-remote` dumps `candle_history` via `\copy` for offline analysis. |
| 2026-04-21 | **BTC re-enabled** | Added back to watchlist for history collection after fixing its data gap. |
| 2026-04-18 | **P&L report with EUR totals + CSV endpoint** | `GET /api/positions/pnl-report/csv`; By-instrument breakdown; Makefile `remote-report` / `remote-csv`. |
| 2026-04-17 | **Duplicate position guard** | `PositionOutcomeService.handleSignalEvent` skips creating a second open position for the same symbol. |
| 2026-04-16 | **Candle history persistence (DB)** | `CandleHistory` entity; ~2,900 candles loaded on startup; RSI accuracy preserved across restarts. |
| 2026-04-14…16 | **Trend Detection v2 + v2.1** | EMA20(1h) primary trend filter + momentum fallback + consecutive-signal fallback. TREND_BUY_DIP / TREND_SELL_RALLY signal types. Stops at half width, trend limits at 3× stop. Suppresses counter-trend signals immediately after warmup. |
| 2026-04-XX | **Deployed to AWS EC2** | Live on `108.128.230.238` (eu-west-1). See `docs/remote-deployment.md`. |
| 2026-04-XX | **ntfy.sh → Telegram migration** | Private bot `@LucidLynx1_bot`; chat-id whitelist in `TELEGRAM_CHAT_IDS`. ntfy code fully removed. |
| 2026-04-XX | **Phase 1 core engine** | Spring Boot + Postgres + Docker; multi-TF RSI; watchlist CRUD REST; cooldown / quiet-hours; repo renamed `rsi-alert-service → market-signals`. |

---

## 🎯 Active Backlog

### P1 — Do next (1–2 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **Forward-monitor May 22 filters** | ongoing | Watch `filter_event_counts.EMA_SLOPE_FLAT` and `DIP_DEDUPE` weekly. Loosen `ema-slope-min-pct` (0.05 → 0.03%) if a known-trending instrument is over-suppressed. |
| **ATR-min-% filter calibration** | ~2h | Currently staged OFF. After 2+ weeks of `filter_event_counts.ATR_RANGE_BOUND` data + `position_outcomes` review, decide starter threshold (suggested 0.4% for crypto) and enable via `TREND_ATR_MIN_PCT_FILTER_ENABLED=true`. |
| **RSI-bucket outcome analysis** | ~1h SQL | Once ≥2 weeks of `position_outcomes` exist post-May-22, split TREND_BUY_DIP wins/losses by rsi15m bucket. If <50 fires win materially more, the Apr 24 threshold change was correct. Query in `project-log.md`. |
| **Enable DeepSeek enrichment** | ~30min | Set `DEEPSEEK_API_KEY` + `DEEPSEEK_ENABLED=true`. Service already built; preferred over Claude (balance available). |
| **Telegram bot commands** | ~3h | `/position`, `/close`, `/status`, `/mute`, `/notrade` — manage service via Telegram. Admin-only via chat-id allowlist. |
| **Momentum fading detector** | ~2h | Notify "FAST TF DIVERGENCE" when 3/3 aligned but fast TFs flip. Exit-timing signal using existing RSI values. |

### P2 — Next sprint (2–4 weeks)

| Item | Effort | Notes |
|------|--------|-------|
| **ATR-stops A/B tracking** | ~1h | Persist `stop_basis` on `PositionOutcome`; group expectancy by stop source in the report. Drives the decision to leave ATR on permanently. |
| **Stochastic confirmation layer** | ~3h | Optional %K(14,3,3) confirmation on RSI signals. Proposed logic in `project-log.md`. |
| **Self-service Telegram onboarding** | ~3h | `/start` → admin DM `/approve <id>`; hot-reload approved IDs into `TELEGRAM_CHAT_IDS`. |

### P3 — Speculative (1–3 months)

| Item | Effort | Notes |
|------|--------|-------|
| **Restore 5m crypto exit granularity** | ~1h | If 15m ever misses SOL wicks, add `5m` to SOL `timeframes` and revert `finestExitTimeframe` to the original 5m/15m split. Affects signal alignment counts — review first. |
| **High Uncertainty Mode toggle** | ~2h | **Last remaining Phase 5 item.** Suppress all but urgent full-alignment signals during elevated VIX-equivalent or macro events. |
| **Phase 4 auto-trading (enable)** | weeks | Only after 3+ months of positive paper P&L. Do not rush. |

---

## Immediate Next Actions

1. **Forward-monitor May 22 filters** — review `filter_event_counts.EMA_SLOPE_FLAT` and `DIP_DEDUPE` weekly via the P&L report's suppressions table. Tune `ema-slope-min-pct` if real trend days are over-suppressed.
2. **Monitor Phase 5 filters** — watch logs for `RISK-OFF SUPPRESSED`, `RISK-ON SUPPRESSED`, and `HIGH VOLATILITY SUPPRESSED` to verify correlation + volatility filters keep working as instrument set evolves.
3. **Paper trade only** — do not enable Phase 4 auto-exec (`TRADING_AUTO_EXECUTION_ENABLED=false`).
4. **Pull reports nightly** — `make pull-reports` or the crontab entry in `troubleshooting.md` keeps the local P&L + CSVs in sync.
5. **Add `DEEPSEEK_API_KEY`** when you want richer Telegram context (preferred over `CLAUDE_API_KEY` going forward).

---

## Notes

- **Data sources**: Binance (FREE, crypto) and IG (FREE with account, indices/FX/commodities/crypto). Finnhub and Twelve Data rejected — free tiers insufficient for indices coverage and rate limits.
- **Hosting**: AWS EC2 t3.micro (eu-west-1, Free Tier 12 months). Postgres self-hosted in the same Docker Compose. No RDS.
- **AI model swap**: both `ClaudeEnrichmentService` and `DeepSeekEnrichmentService` are wired and selected by their respective `*_ENABLED` env flags. DeepSeek is the preferred path.

---

## 🔍 What to Build Next (Current Reality Assessment)

*As of May 2026, with Correlation and Volatility regime filters live, and anomaly features (volume spikes, Polymarket, momentum surge, oil review) removed as dead weight:*

### Worth Building Now

1. **RSI-bucket outcome analysis** (P1) — The TREND_BUY_DIP threshold was lowered to 45 on Apr 24. After 2+ weeks of data, split wins/losses by RSI15 bucket to validate the change. If <50 fires materially outperform, the threshold change was correct.

2. **ATR-stops A/B tracking** (P2) — Persist `stop_basis` on `PositionOutcome` so the P&L report can compare expectancy: ATR stops vs fixed-pct stops. This is the missing data to decide whether to leave ATR on permanently.

3. **Stochastic confirmation layer** (P2) — The one remaining unbuilt signal filter. Optional %K(14,3,3) confirmation could reduce false positives on TREND_BUY_DIP entries.

4. **High Uncertainty Mode** (P3, but small) — The *only* remaining Phase 5 item. A simple toggle that suppresses all but full-alignment signals when VIX-equivalent is elevated. ~2h to complete the Phase 5 spec.

### Probably Skip

- **Cross-instrument correlation detector** — *Already live* as `CrossAssetCorrelationService`. Risk-on/off regime detection works via price momentum, not RSI alignment counts.
- **Volatility-spike entry filter** — *Already live* as `VolatilityRegimeService`. Suppresses signals when ATR expands >1.5× across 2+ instruments.
- **Telegram bot commands** — Nice-to-have, but the system already has signal recording, P&L reports, and HTTP endpoints for everything. Lower priority than signal-quality improvements.

### Strategic Observation

The system now has more filtering infrastructure than signal-generation refinement. Focus on RSI-bucket outcome analysis and ATR-stops A/B tracking to refine the existing signal pipeline before adding new features.

---

*Private Use — Not for Distribution*
