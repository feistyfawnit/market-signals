# Next-Work Prompt & Analysis Playbook

*Pair with `roadmap.md` (phase gates) and `project-log.md` (decision history).*

---

## Reusable Prompt

> Run `make remote-report && make remote-csv`. Then review container logs and advise:
> 1. Did any auto-trade fail to open, orphan on IG, or get rejected? (grep `No open DB position`, `Trade REJECTED`, `spread too wide`)
> 2. Are the € outcomes correct? (R denominator = `stopPts`, not the mutated `slPrice`)
> 3. Is per-instrument performance in line — right instruments enabled/auto-executing?
> 4. Are we exiting winners too early (manual trails vs bot TP/trail)?
> 5. Any config drift vs `AGENTS.md`?
> Cite log lines and CSV rows. Propose fixes but confirm before `make deploy`.

### Evidence commands

```bash
make remote-report && make remote-csv

ssh -i ~/.ssh/market-signals.pem ubuntu@$EC2 \
  "cd ~/apps/market-signals && docker-compose logs app 2>/dev/null \
   | grep -E 'Placing IG deal|Trade placed|confirmed —|Position (opened|closed)|Trade REJECTED|No open DB position|spread too wide|Auto-execut'"

ssh ... "curl -s http://localhost:8080/api/instruments" | python3 -m json.tool
```

---

## Backlog (2026-07-16 review)

### P0 — Orphaned overnight crypto auto-trades — **FIXED 2026-07-12**
`PositionOutcomeService` skipped all position opens during quiet hours, while `IGTradingService` placed live IG deals for auto-execute instruments. Fixed: auto-execute exemption mirrored. Orphan fallback upgraded to `log.error` + Telegram alert.

### P1 — Shorting enabled — **DEPLOYED 2026-07-16**
`sell-rally-enabled: true`. `TREND_SELL_RALLY` fires on rallies in downtrends. Starts SILENT (no auto-execute for shorts). Monitor forward data before enabling auto-execute on shorts.

### P2 — Crypto stop widening — **DEPLOYED 2026-07-16**
ATR multiplier raised 1.5→2.0. ETH/SOL stops were too tight (53% SL hit rate). Monitor whether win rate holds or improves.

### P3 — S&P auto-execute — **DEPLOYED 2026-07-16**
S&P flipped to `auto-execute-enabled: true`. Best index performer (67% win, +€334 net). Market hours only (11:00-22:00 UTC), no overnight risk.

### P4 — Instrument reshuffle — **DEPLOYED 2026-07-16**
Disabled: FTSE (0 trades, −0.86R), Silver (0 trades, −1.00R). Enabled: Nasdaq (silent, forward data). Kept: Gold + Oil (Hormuz/Iran geopolitical plays). IG budget: ~5,900 pts/week (under 10k cap).

### P5 — Monitor shorting forward data
After ≥10 `TREND_SELL_RALLY` signals, review win rate and expectancy. If positive, consider auto-execute for shorts on crypto (different margin/overnight cost).

---

## Gotchas
- **`stopPts` is the R denominator** everywhere now (report, CSV, open positions, archival). `slPrice` is the trailed stop — never use it for R-multiple. Fixed 2026-07-11/12.
- **Manual trailing** keeps exiting winners early. Prefer letting the bot manage exits.
