# Deploy live on Exness (MT5)

End-to-end: from cloning qkt to a paper trade on an Exness demo account, through the Docker stack.

!!! warning "Demo account only"
    Use an Exness **demo** account for this walkthrough. The qkt project is pre-1.0 and not yet recommended for live capital. Demo accounts let you verify the integration without risk.

## What you'll need

- Docker + Docker Compose v2
- An Exness demo account (free, takes 60 seconds at exness.com)
- Your MT5 login + password + server name (Exness emails them at signup)
- ~10 minutes

## 1. Clone and configure

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
cp .env.example .env
cp qkt.config.yaml.example qkt.config.yaml
```

Fill in these lines of `.env` with your MT5 credentials and leave the rest as they are:

```dotenv title=".env"
MT5_LOGIN=12345678
MT5_PASSWORD=your-mt5-password
MT5_SERVER=Exness-MT5Trial
MT5_ENABLE_ALGO_TRADING=1
MT5_API_KEY=replace-with-a-long-random-value
VNC_PASSWORD=pickanything
```

The `MT5_SERVER` value comes from Exness: demo servers are named `Exness-MT5Trial` followed by a
number (for example `Exness-MT5Trial9`), live ones `Exness-MT5Real` and a number. Copy the exact name
from the email Exness sent at signup. `MT5_API_KEY` is a password you make up: the gateway requires it
on every request, and `docker-compose.yml` hands the same value to qkt as `QKT_BROKER_API_KEY`.

Replace the contents of `qkt.config.yaml` with an account that uses the built-in Exness profile:

```yaml title="qkt.config.yaml"
source: local                # prices come from the broker; no TradingView fallback

brokers:
  exness:
    type: mt5
    extends: exness          # inherits the m suffix, the UTC server clock and Exness defaults
    gateway_url: ${QKT_BROKER_GATEWAY_URL}
    api_key: ${QKT_BROKER_API_KEY}
    magic: 4242              # unique per qkt deployment; identifies your orders on restart
```

The account's name, `exness`, is the prefix your strategies write: `EXNESS:EURUSD`.
`QKT_BROKER_GATEWAY_URL` and `QKT_BROKER_API_KEY` are set for the qkt container by
`docker-compose.yml`.

## 2. Bring up the stack

```bash
docker compose up -d
```

This starts two containers:

- **`mt5-gateway`** — Wine + MT5 terminal exposing an HTTP API on port 5001
- **`qkt`** — the trading daemon, waits for the gateway to be healthy before starting

Verify both are running:

```bash
docker compose ps
```

## 3. Verify the headless MT5 login

The gateway uses `MT5_LOGIN`, `MT5_PASSWORD`, and `MT5_SERVER` from `.env`. Ask it whether it is ready (the check runs inside the gateway container, which holds the API key):

```bash
docker compose exec mt5-gateway sh -c 'curl -fsS -H "Authorization: Bearer $API_KEY" http://localhost:5001/health/ready'
# {"ok":true,"status":"ready",...}
```

If readiness is 503, inspect `docker compose logs mt5-gateway`; VNC remains available as a diagnostic fallback.

## 4. Audit the tick feed first

Before deploying a real strategy, verify the gateway's ticks match what you expect. The `audit-ticks` CLI polls the gateway's live quotes for `--duration` seconds, then checks every quote it saw against MT5's own recorded tick history for the same window:

```bash
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 60 \
  --mt5-profile exness --reference mt5-history
```

Output (your counts differ; the last line is what matters):

```text
qkt audit-ticks: symbol=EURUSD duration=60s profile=exness reference=mt5-history poll=250ms
poll samples:             240
unique in-window ticks:   187
history ticks:            193
exact timestamp matches:  187
exact bid/ask matches:    187
timestamp price mismatch: 0
missing from history:     0
invalid live quotes:      0
quote age p95 ms:         212
result:                   PASS
```

`PASS` means every live quote qkt saw is a tick MT5 recorded, at the same bid and ask. Run it while
the market is open: on a weekend there are no ticks and the audit reports `FAIL`.

If it reports `FAIL` with the market open (quotes missing from history, mismatched prices, invalid quotes), that is a feed quality problem — investigate before deploying.

## 5. Write a paper strategy

Put it in `./strategies/` (mounted into the container at `/strategies`):

```qkt title="strategies/eur-paper.qkt"
STRATEGY eur_paper VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 5m

RULES
    WHEN ema(eur.close, 9) CROSSES ABOVE ema(eur.close, 21)
     AND POSITION.eur = 0
    THEN BUY eur SIZING 0.01
         BRACKET { STOP_LOSS BY 0.0020, TAKE_PROFIT BY 0.0040 }
       ; LOG "long entry" price=eur.close
```

Notes:

- `SIZING 0.01` is 0.01 lots — the minimum on most MT5 accounts, ~$1000 notional on EURUSD.
- `BY 0.0020` is a price distance: 20 pips on EURUSD, so the stop sits 0.0020 below the fill and
  the target 0.0040 above it. (`BY 30 PCT` would be a 30% move, far beyond any EURUSD day.)
- The `;` before `LOG` separates the two actions of the rule; without it the parser stops at `LOG`.

Check that it parses before deploying:

```bash
docker compose exec qkt qkt parse /strategies/eur-paper.qkt
```

```text
ok
```

## 6. Deploy it

```bash
docker compose exec qkt qkt deploy /strategies/eur-paper.qkt --as eur-paper
```

Output (the port is picked at random, the time is when it started, in UTC):

```text
NAME          PORT     STATE     STARTED
eur-paper     47291    running   2026-10-19T09:30:12.418Z
```

Verify it's running:

```bash
docker compose exec qkt qkt list
```

```text
NAME                KIND       UPTIME   PORT     TRADES   STATE
eur-paper           strategy   00:00:42 47291    0        running
```

For later edits, validate the replacement before applying it:

```bash
docker compose exec qkt qkt resync /strategies/eur-paper.qkt --as eur-paper --dry-run
docker compose exec qkt qkt resync /strategies/eur-paper.qkt --as eur-paper
```

## 7. Watch it work

Tail the logs:

```bash
docker compose exec qkt qkt logs eur-paper -f
```

You'll see ticks arrive + condition evaluations. When the EMA crosses, you'll see a `BUY` action and a broker submission. The fill comes back as a `TradeEvent` a few hundred ms later.

Check the account's open positions:

```bash
docker compose exec qkt qkt bot positions EXNESS:EURUSD --json
```

Also visible inside MT5 (via VNC) — the position appears in the "Trade" tab with the `magic` number 4242.

## 8. Stop cleanly

```bash
docker compose exec qkt qkt stop eur-paper --flatten
```

`--flatten` closes any open position at market before stopping. Without it, the position stays open.

To tear down the whole stack:

```bash
docker compose down            # keeps state volume
docker compose down -v         # wipes state + logs
```

## Going to a different MT5 broker

Same pattern, different profile:

```yaml title="qkt.config.yaml"
brokers:
  icmarkets:
    type: mt5
    extends: icmarkets       # built-in
    gateway_url: http://icmarkets-gateway:5001
    magic: 4243

  ftmo:
    type: mt5
    extends: ftmo            # built-in
    gateway_url: http://ftmo-gateway:5001
    magic: 4244
```

Then in the strategy: `eur = ICMARKETS:EURUSD EVERY 5m`. Each broker gets its own gateway container — they don't share. Add them to `docker-compose.yml` with the same shape as `mt5-gateway`.

## Common gotchas

- **MT5 logs you out periodically.** Some brokers force re-auth daily. VNC back in and log in again. Add a healthcheck alert if you care.
- **Exness symbol suffix.** Exness adds `m` (EURUSDm, GBPUSDm, etc). The built-in `exness` profile handles this — but if you `extends:` a different base and override `symbolPolicy`, double-check the suffix.
- **Magic number collision.** If you run multiple qkt instances against the same MT5 account, give them different `magic` numbers. State recovery uses magic to identify "its own" positions.
- **Gateway image not on Docker Hub.** Build it locally from [github.com/elitekaycy/mt5-gateway](https://github.com/elitekaycy/mt5-gateway) or pull from your private registry.

## See also

- [Deploy with Docker](../operations/deploy-docker.md) — operations-level reference
- [Config schema](../reference/config-schema.md) — full `qkt.config.yaml` fields
- [Broker integration](../concepts/broker-integration.md) — capability matrix and per-broker quirks
- [Phase 17 — MT5 broker](../phases/phase-17.md) — the gateway protocol + profile internals
