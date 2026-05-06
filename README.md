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
- ✅ Claude AI signal enrichment (optional)
- ✅ Signal CSV archival with outcome backfill (1h/4h/24h price tracking)
- ✅ Auto P&L tracking — positions opened on signal, TP/SL checked hourly, daily markdown report
- ✅ REST API for instruments, signals, settings, positions, retrospective analysis
- ✅ Auto-trading scaffolded (Phase 4 — hard-disabled, requires demo validation)

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
GET  /api/instruments/enabled          # Active instruments
GET  /api/signals/recent?hours=24      # Recent signals
GET  /api/signals/rsi-snapshot          # Live RSI values
POST /api/signals/no-trade-mode/on     # Suppress PARTIAL/WATCH
POST /api/trading/kill-switch/activate  # Emergency stop
POST /api/test/notify                   # Fire test notification
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
| 1 | 🟢 OVERSOLD / 🔴 OVERBOUGHT | All TFs aligned | Alert |
| 2 | 📈 TREND_BUY_DIP / 📉 TREND_SELL_RALLY | RSI pullback in confirmed trend | Alert |
| 3 | 🟡 PARTIAL | All but 1 TF aligned | Alert |
| 4 | 👀 WATCH | 1 TF crossed + others approaching | Disabled by default |

> TREND_SELL_RALLY is currently **disabled** (`trend.sell-rally-enabled: false`) due to −0.79R expectancy in backtest.

## Trade Duration & IG Financing Costs

IG Daily Funded Bets (DFB) accrue **overnight financing charges every calendar day** the position is held, regardless of whether markets are open. Typical cost on a crypto position:

- Daily Admin Fee: ~€0.14/day
- Daily Financing Adjustment: ~€0.38/day
- **Total: ~€0.50–0.55/day per standard position**

> **Target in-and-out within 1–4 days.** If a trade hasn't moved in your favour within 3–4 days, the carry cost alone is a valid reason to close — regardless of RSI. A week's hold costs ~€3.50+ before spread is considered.

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

⚠️ **Personal use only — not financial advice — MiFID II: no public distribution**

---

*April 2026*
