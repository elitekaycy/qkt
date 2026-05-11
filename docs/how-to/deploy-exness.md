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

Edit `.env` with your MT5 credentials:

```dotenv title=".env"
MT5_LOGIN=12345678
MT5_PASSWORD=your-mt5-password
MT5_SERVER=Exness-MT5Trial
VNC_PASSWORD=pickanything
EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

The `MT5_SERVER` value comes from Exness — it's usually `Exness-MT5Trial` for demo or `Exness-MT5Real` for live. Check the email Exness sent at signup.

Edit `qkt.config.yaml` to use the built-in Exness profile:

```yaml title="qkt.config.yaml"
source: live

brokers:
  exness:
    type: mt5
    extends: exness          # inherits suffix, tz, defaults
    gateway_url: ${EXNESS_GATEWAY_URL}
    magic: 4242              # unique per qkt deployment; identifies your orders on restart
```

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

## 3. Log in to MT5 once

The MT5 terminal needs an interactive login the first time. Connect to it via VNC:

```bash
# Mac: brew install tigervnc-viewer ; open vnc://localhost:3000
# Linux: any VNC client → localhost:3000
# Windows: TightVNC, RealVNC, or RDP-style tools
```

Use the `VNC_PASSWORD` you set in `.env`.

Inside the MT5 GUI:

1. **File → Login to Trade Account**
2. Enter your login, password, and server name
3. Click "Login"

You should see your balance + a green "connected" indicator at the bottom-right of MT5.

**Verify from the host:**

```bash
curl http://localhost:5001/health
# {"ok": true, "account": {"login": 12345678, "balance": 10000.00, ...}}
```

If `ok: false`, the login didn't take — back into the VNC GUI.

## 4. Audit the tick feed first

Before deploying a real strategy, verify the gateway's ticks match what you expect. The `audit-ticks` CLI captures live ticks for a few minutes and reports any anomalies:

```bash
docker compose exec qkt qkt audit-ticks EXNESS:EURUSD --duration 60s
```

Output:

```
audit-ticks EXNESS:EURUSD
  ticks received:    312
  bid range:         1.0828–1.0834
  ask range:         1.0829–1.0835
  spread (avg):      1.2 points
  spread (max):      3.4 points
  duplicates:        0
  out-of-order:      0
  gaps > 5s:         0
```

If you see large gaps or out-of-order ticks, that's a feed quality problem — investigate before deploying.

## 5. Write a paper strategy

Put it in `./strategies/` (mounted into the container at `/strategies`):

```qkt title="strategies/eur-paper.qkt"
STRATEGY eur_paper VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 5m

RULES
    WHEN ema(eur.close, 9) CROSSES ABOVE ema(eur.close, 21)
    THEN BUY eur SIZING 0.01
         BRACKET { STOP_LOSS BY 30 PCT, TAKE_PROFIT BY 60 PCT }
         LOG INFO "long entry" price=eur.close
```

Note: `SIZING 0.01` is 0.01 lots — the minimum on most MT5 accounts, ~$1000 notional on EURUSD.

## 6. Deploy it

```bash
docker compose exec qkt qkt deploy /strategies/eur-paper.qkt --as eur-paper
```

Output:

```
[INFO] deployed eur-paper
QKT_PORT=47291
```

Verify it's running:

```bash
docker compose exec qkt qkt list
```

```
NAME              KIND       UPTIME   PORT     TRADES   STATE
eur-paper         strategy   00:00:42 47291    0        running
```

## 7. Watch it work

Tail the logs:

```bash
docker compose exec qkt qkt logs eur-paper -f
```

You'll see ticks arrive + condition evaluations. When the EMA crosses, you'll see a `BUY` action and a broker submission. The fill comes back as a `TradeEvent` a few hundred ms later.

Check the position from the host:

```bash
curl http://localhost:5001/positions | jq
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
