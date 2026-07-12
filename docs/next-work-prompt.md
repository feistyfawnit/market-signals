# Next-Work Prompt & Analysis Playbook

*Created 2026-07-12. Purpose: streamline the recurring "run a report → advise → fix" cycle, and hold the prioritised backlog that comes out of it. Pair with `roadmap.md` (phase gates) and `project-log.md` (decision history).*

---

## Reusable Prompt (paste to start an analysis session)

> Run a fresh P&L report and CSV from EC2 (`make remote-report && make remote-csv`).
> Then review last night's / this week's activity in the container logs and advise:
> 1. Did any auto-trade fail to open, orphan on IG, or get rejected? (grep `No open DB position`, `Trade REJECTED`, `spread too wide`)
> 2. Are the € outcomes correct given the trailing-stop + R-multiple model? (initial stop = `entryPrice ± stopPts`, not the mutated `slPrice`)
> 3. Is per-instrument performance in line — and are the right instruments enabled/auto-executing?
> 4. Are we exiting winners too early (manual trails vs the bot's TP/trail)?
> 5. Any config drift vs `AGENTS.md` "Current Runtime Shape"?
> Keep advice terse, evidence-first, cite log lines and CSV rows. Propose fixes but confirm before deploying (`make deploy`).

### Standard evidence commands

```bash
# Reports
make remote-report && make remote-csv

# Live trade lifecycle (last night)
ssh -i ~/.ssh/market-signals.pem ubuntu@$EC2 \
  "cd ~/apps/market-signals && docker-compose logs app 2>/dev/null \
   | grep -E 'Placing IG deal|Trade placed|confirmed —|Position (opened|closed)|Trade REJECTED|No open DB position|spread too wide|Auto-execut'"

# Orphan check (live IG deal with no tracked DB position)
#   -> grep 'No open DB position found'

# Current instrument config
ssh ... "curl -s http://localhost:8080/api/instruments" | python3 -m json.tool
```

---

## Prioritised Backlog (from 2026-07-12 review)

### P0 — Orphaned overnight crypto auto-trades (correctness bug)
**Symptom**: 2026-07-11 23:35 UTC, BTC + ETH auto-trades placed live on IG (deal refs `FSEV2ZCKR2GTYS9`, `TLSRQQKZ75LTYS9`) but logged `No open DB position found for … to attach igDealId`. The trades exist on IG but the app never tracked them — no trailing, no P&L report, invisible.

**Root cause**: two `@EventListener` handlers react to a `SignalEvent`:
- `IGTradingService.handleSignalEvent` bypasses quiet hours for auto-execute instruments (`if (isQuietHours() && !instrumentAutoExecute) return;`) → places the live deal.
- `PositionOutcomeService.handleSignalEvent` still `return`s unconditionally during quiet hours (`if (isQuietHours()) { … return; }`) → never opens the tracked DB position.

So during quiet hours (22:00–08:00 UTC), auto-execute crypto trades on IG with nothing to attach to. `AGENTS.md` line 53 wrongly assumes `PositionOutcomeService` merely "suppresses the Telegram push" — it actually skips the whole position-open.

**Fix**: mirror the auto-execute exemption in `PositionOutcomeService.handleSignalEvent` — during quiet hours, still open the tracked position for auto-execute instruments (keep skipping the Telegram push). Then `handleConfirm` can attach the `igDealId`, trailing works, and the trade shows in the report.

**Also consider**: `handleConfirm`'s `findFirstBySymbolAndExitTimeIsNull` fallback should log louder / alert when an ACCEPTED live deal has no DB position, so future orphans surface immediately instead of only in a manual log grep.

### P1 — Shorting is not enabled (crash protection gap)
The system is currently **long-only**. Every position in the report is `LONG`.
- `rsi.trend.sell-rally-enabled: false` → `TREND_SELL_RALLY` never fires.
- `OVERBOUGHT` (the other `SELL` path) is suppressed in strong uptrends by design, and crypto `rsi-signals-enabled` gates it further.
- The plumbing exists: `IGTradingService` direction switch maps `OVERBOUGHT`/`TREND_SELL_RALLY → SELL`; `PositionOutcomeService` handles short SL/TP and short trailing; `replayCandles` replays shorts.

**To be able to short a crash**, decide and stage:
1. Flip `sell-rally-enabled: true` (start SILENT / paper, like DAX re-enable pattern) to gather forward data before auto-executing shorts.
2. Confirm downtrend detection (`STRONG_DOWNTREND`) and `rally-rsi-threshold` (currently 55) behave on a real sell-off.
3. Only then consider `auto-execute` for short signals — shorts on crypto CFDs have different margin/overnight cost; verify on demo first.

### P2 — Instrument enablement review
Current enabled + auto-execute: **SOL, BTC, ETH** (crypto, 24/7). Manual-approval: **S&P, DAX (silent), Gold (silent, rsi off), Oil (trend off), Silver (silent, rsi off)**. Disabled: **BCH, FTSE, Nasdaq**.
- **S&P** is the best forward index performer and is still manual-approval — candidate for Phase 2 auto-execute per `roadmap.md` gate (≥20 crypto trades, slippage ≤0.15%).
- **Nasdaq disabled** — worth enabling (silent) for signal data given it trends well; watch the 10k/week IG data budget (`AGENTS.md` guardrail).
- Confirm none of the silent/`rsi-signals-enabled:false` commodities are dead weight after their forward-data window.

### P3 — BTC "small gains" perception vs reality
BTC per-trade **%** moves look small next to SOL/S&P, but under fixed-€ risk this is expected and BTC is actually the **top net earner** (67% win rate, +€1,022 net over 15 trades in the 2026-07-12 report). Reasons:
- BTC has lower **% volatility** than SOL, so a BTC winner shows +0.4% where SOL shows +5%.
- The R-multiple €-model normalises for stop size, so the € figures are comparable; BTC's high hit-rate makes it the steadiest contributor.
- Not a bug. If bigger BTC winners are wanted, the lever is `trend-rr-crypto` (currently 2:1) or trailing geometry — but 2:1 was deliberately chosen after 3:1 targets never hit on the 24h horizon. Revisit only with new data.

---

## Notes / Gotchas confirmed this session
- **CSV `stop_price`** now = initial stop (`entryPrice ± stopPts`); `final_stop_price` = trailed level (fixed 2026-07-11).
- **`estEur`** now uses `stopPts` for the R denominator, not the mutated `slPrice` (fixed 2026-07-11) — earlier reports overstated net by ~€590.
- **Manual trailing** keeps exiting winners early (S&P Jul 10 closed ~€734 short of TP). Recurring theme — prefer letting the bot's TP/trail manage the exit.
