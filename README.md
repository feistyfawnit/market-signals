# LucidLynx Market Signals

A production-grade Spring Boot service that monitors financial instruments for multi-indicator alignment signals across multiple timeframes and sends instant push notifications. Combines RSI, Stochastic, and trend detection (EMA/ADX/MACD). Monitors crypto (via Binance), indices and commodities (via IG API) in real time.

**Repository:** `https://github.com/feistyfawnit/market-signals` (Private)

## Features

- ✅ Real-time RSI calculation across multiple timeframes (15m, 30m, 1h, 4h — configurable per instrument)
- ✅ Multi-instrument support (crypto via Binance, indices/commodities via IG API)
- ✅ Three-tier signal hierarchy: FULL → PARTIAL → WATCH
- ✅ Private push notifications via Telegram bot (multi-recipient, no app install needed)
- ✅ Partial signal monitoring with lagging-TF follow-ups
- ✅ No-trade mode + per-symbol muting (persistent across restarts via DB)
- ✅ Active position tracking — gates alerts until a trade is open
- ✅ DeepSeek AI signal enrichment (optional — adds market context to Telegram alerts)
- ✅ Signal CSV archival with outcome backfill
- ✅ Auto P&L tracking — positions opened on signal, TP/SL checked hourly, daily markdown report
- ✅ **IG auto-execution with Telegram inline-keyboard confirmation** — trade only after manual approve/skip
- ✅ **Ratcheting trailing stop loss** — locks in profit as price moves favourably, never moves against you
- ✅ **IG position reconciliation** — detects manual closes, keeps DB in sync with live IG positions
- ✅ REST API for instruments, signals, settings, positions, retrospective analysis, trade testing

## Deployment

- **AWS Free Tier** (primary): EC2 t2.micro in Dublin (`eu-west-1`), 12 months free, ~€15/month after
- **CI/CD**: GitHub Actions workflow for auto-deploy on push to `main`
- See `docs/remote-deployment.md` for full setup (manual or Terraform)

## Tech Stack

- Java 25
- Spring Boot 3.5.3
- PostgreSQL 16
- WebFlux for reactive HTTP calls
- Docker & Docker Compose

## Quick Start

### 1. Prerequisites

- Docker & Docker Compose installed

### 2. Configuration

Copy `.env.example` to `.env` and fill in your values. See comments in `.env.example` for each variable.

Key settings for notifications:
```bash
TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=your_bot_token_from_botfather
TELEGRAM_CHAT_IDS=your_chat_id          # comma-separate for multiple recipients
```

Key settings for IG auto-trading (optional):
```bash
IG_ENABLED=true
IG_API_KEY=your_ig_api_key
IG_USERNAME=your_ig_username
IG_PASSWORD=your_ig_password
IG_BASE_URL=https://demo-api.ig.com/gateway/deal  # or live-api.ig.com for real money
TRADING_AUTO_EXECUTION_ENABLED=false  # set true to enable live trading (starts with confirmation)
TRADING_REQUIRE_MANUAL_APPROVAL=true   # Telegram inline keyboard confirm/skip
TELEGRAM_CONFIRMATION_TIMEOUT_SECONDS=120  # how long to wait for your response
```

### 3. Run with Docker Compose

```bash
# Build and start services
docker-compose up -d

# View logs
docker-compose logs -f app

# Stop services
docker-compose down
```

The app will be available at `http://localhost:8080`

### 4. Receive Alerts via Telegram

Alerts are delivered privately via the **B&I Alert Bot** (@LucidLynx1_bot) on Telegram.

**To add a new recipient:**
1. In Telegram, search **@userinfobot** and send it any message — it replies with your numeric chat ID (e.g. `Id: 987654321`)
2. Search **@LucidLynx1_bot** and tap **START** (so the bot can message you)
3. Send your chat ID to an admin — he adds it to `.env`:
   ```
   TELEGRAM_CHAT_IDS=6633143916,987654321
   ```
4. Restart: `docker-compose up -d --build`

> ⚠️ **Note:** Self-service onboarding (no admin step needed) is planned — see ROADMAP.

## API Endpoints

Full reference: **[docs/api.md](docs/api.md)**

Key endpoints:

```bash
GET  /api/instruments/enabled               # Active instruments
GET  /api/signals/recent?hours=24           # Recent signals
GET  /api/signals/rsi-snapshot               # Live RSI values
POST /api/signals/no-trade-mode/on          # Suppress PARTIAL/WATCH
POST /api/trading/kill-switch/activate      # Emergency stop
POST /api/trading/test-telegram             # Send test Telegram notification
POST /api/trading/test-confirm?symbol=X&direction=BUY&price=100  # Test confirmation keyboard
POST /api/test/notify                        # Fire test notification
```

## Pre-Configured Instruments

The app comes pre-configured with:
- **Solana (SOLUSDT)** - Binance
- **Bitcoin (BTCUSDT)** - Binance
- **Ethereum (ETHUSDT)** - Binance
- **Bitcoin Cash (BCHUSDT)** - Binance
- **DAX 40, FTSE 100, S&P 500, Nasdaq 100** - IG API
- **Gold, Oil (Brent)** - IG API (Silver, Nasdaq disabled)

All crypto data is FREE via Binance API (no API key required). Indices/commodities require an IG account.

## Configuration

Edit `src/main/resources/application.yml` or use environment variables:

```yaml
rsi:
  period: 14                    # RSI calculation period
  oversold-threshold: 30        # Default oversold threshold
  overbought-threshold: 70      # Default overbought threshold
  polling:
    interval-seconds: 300       # Binance polling interval (5 min)
    ig-interval-seconds: 900    # IG polling interval (15 min)
  quiet-hours:
    enabled: true
    start-hour: 22              # 10 PM UTC (11 PM BST)
    end-hour: 8                 # 8 AM UTC (9 AM BST) — full signals bypass quiet hours
```

See [docs/api.md](docs/api.md) for adding instruments via the REST API.

## Signal Types

| Priority | Signal | Condition | Telegram |
|---|---|---|---|
| 1 | 🟢 BUY SIGNAL / 🔴 SELL SIGNAL | All TFs aligned (3/3) | Alert |
| 2 | 📈 BUY DIP / 📉 SELL RALLY | RSI pullback in confirmed trend | Alert |
| 3 | 🟡 PARTIAL | All but 1 TF aligned (2/3) | Off by default (`PARTIAL_SIGNALS_ENABLED=false`) |
| 4 | 👀 WATCH | 1 TF crossed + others approaching | Off by default (`WATCH_SIGNALS_ENABLED=false`) |

> SELL RALLY is currently **disabled** (`trend.sell-rally-enabled: false`) due to −0.79R expectancy in backtest.

## Trade Duration & IG Financing Costs

IG Daily Funded Bets (DFB) accrue **overnight financing charges every calendar day** the position is held, regardless of whether markets are open. Typical cost on a crypto position:

- Daily Admin Fee: ~€0.14/day
- Daily Financing Adjustment: ~€0.38/day
- **Total: ~€0.50–0.55/day per standard position**

> **Target in-and-out within hours to 2 days.** Positions auto-close after **16 hours** if still open — this frees capital for the next signal and limits financing drag. If a trade hasn't moved in your favour within a day, the carry cost (~€0.50/day) and opportunity cost of a blocked slot are valid reasons to close regardless of RSI.

## Troubleshooting

See [docs/troubleshooting.md](docs/troubleshooting.md) for operational troubleshooting, IG quota guidance, and demo steps. Quick checks:

```bash
curl http://localhost:8080/actuator/health           # App running?
curl http://localhost:8080/api/instruments/enabled     # Instruments active?
curl http://localhost:8080/api/signals/recent?hours=1  # Any signals?
docker-compose logs -f app                             # Check logs
```

## Documentation

| Doc | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Concise AI/developer entry point and operational guardrails |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Operational checks, common failures, IG quota notes |
| [docs/architecture.md](docs/architecture.md) | System design, components, data flow |
| [docs/api.md](docs/api.md) | REST API reference |
| [docs/project-log.md](docs/project-log.md) | Incident history and decisions over time |
| [docs/risk-register.md](docs/risk-register.md) | Risks, constraints, and operational warnings |
| [docs/archived/requirements.md](docs/archived/requirements.md) | Original historical specification (not the current source of truth) |
| [docs/archived/backtest-report.md](docs/archived/backtest-report.md) | Apr 2026 signal quality backtest (archived — findings actioned) |

## How the Indicators Work — Plain English

**RSI — Relative Strength Index**
Measures whether recent closes have mostly been gains or losses over 14 candles. If prices went up 14 times in a row, RSI = ~100 (fully overbought). If they fell 14 times, RSI = ~0 (oversold). In practice: RSI above 70 means buyers are exhausted ("who's left to buy?") and a drop is likely. RSI below 30 means sellers are exhausted and a bounce is likely. Calculated entirely from our own candle history — no external API call.

**EMA — Exponential Moving Average**
A weighted average of the last N closing prices where recent prices count more than old ones. Formula: `EMA = price × k + prev_EMA × (1 − k)` where `k = 2 / (period + 1)`. With period 20: each new hourly close contributes ~9.5% weight, the running average carries the rest. We use it as a trend line — if the current price is above EMA20(1h), the market is in an uptrend.

**BUY DIP**
A trend-following entry signal. Requires three things simultaneously: (1) the market is in a confirmed uptrend (price > EMA20 on 1h), (2) the fastest timeframe RSI (15m) has pulled back below 45 — a meaningful cooldown from overbought, not just noise — and (3) ADX and MACD confirm momentum is still intact. Interpretation: "the trend is up, price dipped, buy the dip."

**ADX — Average Directional Index**
Measures *how strong* a trend is, not its direction. Scale is 0–100: below 20 = market is ranging/going sideways (trend signals unreliable); above 25 = established trend; above 40 = very strong. Calculated from our candle_history (high/low/close) using Wilder's method, needs 29 hours of 1h data to produce a first value. Used as a gate: BUY DIP is blocked when ADX < 20 because there is no real trend to buy the dip of.

**MACD — Moving Average Convergence Divergence**
Measures momentum speed using two EMAs: fast (EMA12) and slow (EMA26). The gap between them is the MACD line. A 9-period EMA of that line is the signal line. Histogram = MACD line − signal line. Positive and rising = bullish momentum building.

Used as a second gate for BUY DIP: if the histogram is negative *and* falling, entry is blocked.

A third optional gate checks for **MACD divergence**: when price makes a lower low but the MACD line makes a higher low, hidden upward momentum is present — this *confirms* the entry. If divergence is absent *and* the histogram is also weak (positive but not rising, or vice versa), entry is blocked. If the histogram is clearly bullish (positive *and* rising), entry passes regardless of divergence — to avoid over-tightening. Divergence is currently **OFF** (`macd-divergence-enabled: false`) — monitoring suppression counts for 1 week before enabling. All data fetches are bounded (37 candles for histogram, 46 for divergence — no unbounded DB queries). Uses 12/26/9 (Appel, 1979).

## Previously Removed Features

The following were built, monitored for weeks, then removed (see `docs/project-log.md#2026-04-25`):

| Feature | Reason removed |
|---|---|
| Polymarket odds monitoring | Odds shifts never preceded price moves |
| Volume anomaly detection (σ-based) | Spikes never correlated with signal quality |
| Cross-correlation burst alerts | 60s window almost never clustered meaningfully |

**Future directions being considered:**
- **AI news integration** (Perplexity API with web search) — could answer "why is SOL down today?" in real time. Challenge: latency 1–3s, cost ~$5/month, benefit unproven vs pure technical signals.
- **Re-enable Polymarket** — only if political event odds show leading predictive power in a 30-day backtest.

Re-introduction of any removed feature requires **concrete evidence** it would behave differently this time.

---

⚠️ **Personal use only — not financial advice — MiFID II: no public distribution**

---

*April 2026*
