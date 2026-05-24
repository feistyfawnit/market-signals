# LucidLynx Market Signals — API Reference

*Last updated: May 2026*

Base URL: `http://localhost:8080`

---

## Instruments — `/api/instruments`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/instruments` | List all instruments |
| `GET` | `/api/instruments/enabled` | List enabled instruments only |
| `GET` | `/api/instruments/{id}` | Get instrument by ID |
| `POST` | `/api/instruments` | Create instrument |
| `PUT` | `/api/instruments/{id}` | Update instrument |
| `PATCH` | `/api/instruments/{id}/toggle` | Toggle enabled/disabled |
| `DELETE` | `/api/instruments/{id}` | Delete instrument |

### Create/Update payload

```json
{
  "symbol": "ETHUSDT",
  "name": "Ethereum",
  "source": "BINANCE",
  "type": "CRYPTO",
  "enabled": true,
  "oversoldThreshold": 30,
  "overboughtThreshold": 70,
  "timeframes": "15m,1h,4h"
}
```

---

## Signals — `/api/signals`

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/signals` | All signal logs |
| `GET` | `/api/signals/symbol/{symbol}` | Signals for a specific instrument |
| `GET` | `/api/signals/recent?hours=24` | Signals from last N hours |
| `GET` | `/api/signals/rsi-snapshot` | Live RSI values for all enabled instruments (from in-memory history) |
| `GET` | `/api/signals/retrospective/{symbol}?at=ISO8601` | Historical RSI analysis — would a signal have fired at this time? (IG instruments only) |

### Daily Price History — `/api/signals/daily-prices`

Long-term OHLCV (Open/High/Low/Close/Volume) summaries rolled up once per day per instrument. Never trimmed — grows ~4KB/year. Useful for trend analysis and backtesting.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/signals/daily-prices` | All daily summaries (JSON) |
| `GET` | `/api/signals/daily-prices?symbol=BTCUSDT` | Filter by symbol |
| `GET` | `/api/signals/daily-prices?symbol=BTCUSDT&from=2026-04-01&to=2026-04-15` | Date range |
| `GET` | `/api/signals/daily-prices/csv` | CSV export (same filters supported) |
| `POST` | `/api/signals/daily-prices/rollup?date=2026-04-14` | Manually trigger rollup for a specific date |

The rollup runs automatically at **00:05 UTC daily**, summarising the previous day's candle data using the shortest configured timeframe per instrument (typically 15m).

---

### Retrospective example

```
GET /api/signals/retrospective/IX.D.DAX.DAILY.IP?at=2026-04-02T12:30:00Z
```

Returns per-timeframe RSI values, distance from thresholds, and verdict (FULL / PARTIAL / No signal).

---

## Positions & P&L Tracking — `/api/positions`

Tracks outcomes of actionable signals (OVERSOLD, OVERBOUGHT, TREND_BUY_DIP, TREND_SELL_RALLY). Positions are opened automatically on signal, checked hourly for TP/SL hits, and auto-closed after **16h** (lowered 24h→16h May 2026 — `PositionOutcomeService.MAX_HOLDING`).

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/positions/pnl-summary` | Win rate, avg P&L, expectancy by signal type (JSON) |
| `GET` | `/api/positions/pnl-report` | Human-readable markdown report — includes Trend-Suppressions table (last 7d) and Suppressed-Signal Retrospective with hindsight verdicts (last 14d) |
| `GET` | `/api/positions/pnl-report/csv` | Same data as CSV |
| `GET` | `/api/positions/signal-gaps` | Reports signals that fired but didn't open positions (e.g. cooldown/quiet-hours) |
| `POST` | `/api/positions/recalculate` | Recompute P&L for closed positions (used after stop/exit logic changes) |

The report auto-writes daily at 06:00 UTC to `reports/pnl-report.md` (host-mounted volume). On-demand: `make pnl-report`.

---

## Per-Instrument Muting — `/api/signals/mute`

Suppresses **all** notifications (FULL, PARTIAL, WATCH) for a specific instrument. Persisted to DB — survives restarts.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/signals/mute/{symbol}` | Mute all alerts for a symbol (e.g. `BTCUSDT`, `ETHUSDT`) |
| `POST` | `/api/signals/unmute/{symbol}` | Re-enable alerts for a symbol |
| `GET` | `/api/signals/muted` | List currently muted symbols |

---

## No-Trade Mode — `/api/signals/no-trade-mode`

Suppresses PARTIAL and WATCH notifications for **all** instruments. FULL signals still fire. Persisted to DB — survives restarts.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/signals/no-trade-mode/on` | Enable no-trade mode |
| `POST` | `/api/signals/no-trade-mode/off` | Disable no-trade mode |
| `GET` | `/api/signals/no-trade-mode` | Current status |

---

## Settings & Active Position — `/api/settings`

Manages persistent operational state (stored in `app_settings` DB table). Used to track open positions and gate anomaly alerts.

**Anomaly alerts are suppressed when no `active_position` is set.** They re-enable automatically when a FULL signal fires or you set a position manually.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/settings` | All current settings (no-trade mode, muted symbols, active position) |
| `GET` | `/api/settings/active-position` | Current open position and whether one exists |
| `POST` | `/api/settings/active-position/{symbol}` | Manually set active position (e.g. after entering a trade) |
| `DELETE` | `/api/settings/active-position` | Clear active position when trade is closed |

### Example: Close a trade

```bash
curl -X DELETE http://localhost:8080/api/settings/active-position
# → {"activePosition": "", "message": "Active position cleared — anomaly alerts back to informational mode"}
```

---

## Trading — `/api/trading`

⚠️ IG auto-execution — **OFF by default** (`TRADING_AUTO_EXECUTION_ENABLED=false`). When enabled, `IG_BASE_URL` selects demo (`https://demo-api.ig.com/...`, the default) vs live (`https://live-api.ig.com/...`). Manual approval via Telegram inline keyboard is enforced when `TRADING_REQUIRE_MANUAL_APPROVAL=true` (default).

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/trading/status` | Auto-execution + kill switch status |
| `POST` | `/api/trading/kill-switch/activate` | Emergency stop — halts all auto-trading |
| `POST` | `/api/trading/kill-switch/deactivate` | Re-enable auto-trading |
| `POST` | `/api/trading/test-telegram` | Send a plain test message to all configured chat IDs |
| `POST` | `/api/trading/test-confirm?symbol=X&direction=BUY&price=100` | Send a test inline-keyboard confirmation prompt (2-min timeout) |

---

## Test / Demo — `/api/test`

For development and demo purposes. See `README.md` for usage.

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/test/notify` | Fire synthetic OVERSOLD signal (labelled `[TEST]`) |
| `POST` | `/api/test/lower-thresholds?oversold=50&overbought=50` | Lower thresholds to trigger real signals on next poll |
| `POST` | `/api/test/reset-thresholds` | Reset all thresholds to 30/70 |
| `GET` | `/api/test/ig/search?term=DAX` | Search IG epic codes |

> Volume / Polymarket anomaly test endpoints were removed when those features were retired (Apr 2026 — see `docs/project-log.md#2026-04-25`).

---

## Admin — `/api/admin`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/admin/backfill-csv` | Force `AlertCsvService` to backfill outcome prices (1h/4h/24h) for rows where the periodic job missed them |

---

## Health

```
GET /actuator/health
```

---

## How Market Data Works

### One API call = one timeframe's candle history

Each Binance poll makes **one HTTP call per instrument per timeframe**. For Solana with `15m,1h,4h` that is 3 calls:

```
GET https://api.binance.com/api/v3/klines?symbol=SOLUSDT&interval=15m&limit=1
GET https://api.binance.com/api/v3/klines?symbol=SOLUSDT&interval=1h&limit=1
GET https://api.binance.com/api/v3/klines?symbol=SOLUSDT&interval=4h&limit=1
```

Each returns an array of candles (one per call during normal polling):

```json
[
  [
    1744243200000,  // open time (epoch ms)
    "132.45",       // open
    "134.20",       // high
    "131.80",       // low
    "133.90",       // close  ← used for RSI and stochastic
    "84231.5",      // volume
    1744246799999,  // close time
    "11284920.3",   // quote asset volume
    1842,           // number of trades
    "42100.2",      // taker buy base asset volume
    "5640230.1",    // taker buy quote asset volume
    "0"             // ignore
  ]
]
```

### RSI and Stochastic are calculated locally — no extra API calls

**RSI**: calculated from the last 50 close prices held in memory per instrument+timeframe key.  
Formula: Wilder's smoothing over 14 periods. RSI < 30 = oversold, RSI > 70 = overbought.

**Stochastic (14,3)**: calculated from the last 50 full candles (high/low/close) in memory.  
Formula: `%K = (Close - LowestLow[14]) / (HighestHigh[14] - LowestLow[14]) × 100`  
`%D = 3-period SMA of %K` (signal line)  
%K < 20 = oversold, %K > 80 = overbought.

Stochastic is only calculated and included in the notification when a **FULL 3/3 signal** fires.  
It does not trigger additional API calls — the candle data is already in memory.

### Polling rates

| Source | Poll interval | Instruments | Calls/poll | Calls/day |
|--------|--------------|-------------|-----------|-----------|
| Binance | 300s (5 min) | 4 crypto × 3 TFs | 12 | ~3,456 |
| IG | 900s (15 min) | 5 instruments × 3 TFs | ≤15 (candle-period skip saves ~60%) | ~400–600 data points |

Binance limit: 1,200 requests/min. Current usage: ~2.4/min. IG limit: 10,000 data points/week (current budget ~5,300/week — see `AGENTS.md` § Important Guardrails).

*See `docs/architecture.md` for system design. See `docs/schema.md` for DB tables. See `README.md` for setup.*
