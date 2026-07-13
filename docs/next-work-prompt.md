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

## Backlog (2026-07-12 review)

### P0 — Orphaned overnight crypto auto-trades — **FIXED 2026-07-12**
`PositionOutcomeService` skipped all position opens during quiet hours, while `IGTradingService` placed live IG deals for auto-execute instruments. Fixed: auto-execute exemption mirrored. Orphan fallback upgraded to `log.error` + Telegram alert.

### P1 — Shorting not enabled (crash protection gap)
Long-only. `sell-rally-enabled: false`. Plumbing exists (direction switch, short SL/TP, short trailing). To enable:
1. Flip `sell-rally-enabled: true` (start SILENT for forward data)
2. Confirm `STRONG_DOWNTREND` + `rally-rsi-threshold` (55) on a real sell-off
3. Only then consider auto-execute (different margin/overnight cost for short crypto CFDs)

### P2 — Instrument enablement review
Auto-execute: SOL, BTC, ETH. Manual: S&P, DAX, Gold, Oil, Silver. Disabled: BCH, FTSE, Nasdaq.
- **S&P**: best forward index performer, still manual — candidate for auto-execute per `roadmap.md` gate
- **Nasdaq**: worth enabling silent for signal data; watch IG data budget
- Confirm silent commodities aren't dead weight after forward-data window

### P3 — BTC "small gains" — not a bug
BTC is top net earner (67% win, +€1,022 net). Lower % volatility than SOL but R-multiple normalises. Lever for bigger wins: `trend-rr-crypto` (2:1) or trailing geometry. Revisit only with new data.

---

## Gotchas
- **`stopPts` is the R denominator** everywhere now (report, CSV, open positions, archival). `slPrice` is the trailed stop — never use it for R-multiple. Fixed 2026-07-11/12.
- **Manual trailing** keeps exiting winners early. Prefer letting the bot manage exits.
