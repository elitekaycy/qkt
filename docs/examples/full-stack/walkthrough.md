# Full-stack walkthrough

Step-by-step deployment of the [full-stack starter bundle](index.md). About 15 minutes from clone to first paper trade.

!!! warning "Demo accounts only"
    Use a Bybit testnet API key and an Exness MT5 **demo** account for this walkthrough. qkt is pre-1.0 — do not connect funded accounts.

## 1. Create the directory structure

```bash
mkdir -p my-trading-stack/strategies my-trading-stack/reports
cd my-trading-stack
```

## 2. Copy the bundle files

Copy the seven files from the [bundle page](index.md) into this directory. Final layout:

```text
my-trading-stack/
├── .env.example
├── docker-compose.yml
├── qkt.config.yaml
└── strategies/
    ├── btc-trend.qkt
    └── eur-meanrev.qkt
```

## 3. Create your `.env` from the template

```bash
cp .env.example .env
```

Edit `.env`:

```dotenv title=".env"
BYBIT_API_KEY=your-bybit-testnet-key
BYBIT_API_SECRET=your-bybit-testnet-secret
BYBIT_TESTNET=true                     # important — testnet first

MT5_LOGIN=12345678
MT5_PASSWORD=your-exness-demo-password
MT5_SERVER=Exness-MT5Trial
VNC_PASSWORD=changeme

EXNESS_GATEWAY_URL=http://mt5-gateway:5001
```

For Bybit testnet API keys: [testnet.bybit.com → API Management](https://testnet.bybit.com/app/user/api-management). Real and testnet keys are different — don't mix them.

For Exness demo: any phone signup gets you `Exness-MT5Trial` server credentials within a minute.

## 4. Add `.env` to `.gitignore`

```bash
echo '.env' >> .gitignore
echo 'reports/' >> .gitignore
```

You should never commit credentials. Run this even if you don't plan to push the repo anywhere — it's a habit worth building early.

## 5. Bring up the stack

```bash
docker compose up -d
```

This starts two containers:

- `mt5-gateway` — Wine + MT5 terminal + Flask API on port 5001
- `qkt` — the trading daemon, waits for the gateway to pass its healthcheck

Verify both are running:

```bash
docker compose ps
# Output:
#   NAME           STATUS              PORTS
#   mt5-gateway    Up (healthy)        0.0.0.0:3000->3000/tcp, 0.0.0.0:5001->5001/tcp
#   qkt            Up                  0.0.0.0:47000-47100->47000-47100/tcp
```

If `mt5-gateway` is `Up (unhealthy)`, it's still booting Wine + MT5. Wait 60 seconds and re-check.

## 6. Log into MT5 once

The MT5 terminal needs an interactive login the first time. Connect via VNC at `localhost:3000` using `VNC_PASSWORD` from your `.env`.

Inside the MT5 GUI:

1. **File → Login to Trade Account**
2. Enter login + password + server name from `.env`
3. Click **Login** — the indicator bottom-right turns green

Verify from the host:

```bash
curl http://localhost:5001/health
# {"ok": true, "account": {"login": 12345678, "balance": 10000.00, ...}}
```

If `ok: false`, the login didn't take — back into the VNC GUI to retry.

## 7. Verify broker profiles loaded

```bash
docker compose exec qkt qkt brokers list
```

Expected output:

```text
NAME              KIND  GATEWAY                  SUFFIX  TZ  MAGIC
bybit_spot        bybit -                        -       -   -
exness            mt5   http://mt5-gateway:5001  m       2   4242
```

If `exness` is missing, the `qkt.config.yaml` didn't load — check the bind-mount path in `docker-compose.yml`.

## 8. Audit the live feeds before deploying

This is the step new operators skip and regret. Capture a minute of ticks from each venue and check for anomalies:

```bash
docker compose exec qkt qkt audit-ticks --symbol EURUSD --duration 60 --mt5-profile exness
```

Expected output:

```text
audit-ticks EURUSD via exness
  ticks received:    412
  bid range:         1.0828–1.0834
  ask range:         1.0829–1.0835
  spread (avg):      1.2 points
  duplicates:        0
  out-of-order:      0
  gaps > 5s:         0
```

Today `qkt audit-ticks` is MT5-only (it requires a `--mt5-profile` and uses the gateway directly). Auditing the Bybit feed is on the roadmap as part of broader broker-tick audit support — see [Planned features](../../planned.md).

If you see gaps, out-of-order ticks, or duplicates — investigate before deploying. A flaky feed wrecks even a good strategy.

## 9. Deploy both strategies

```bash
docker compose exec qkt qkt deploy /strategies/btc-trend.qkt    --as btc-trend
docker compose exec qkt qkt deploy /strategies/eur-meanrev.qkt  --as eur-meanrev
```

Each prints a port:

```text
[INFO] deployed btc-trend
QKT_PORT=47291

[INFO] deployed eur-meanrev
QKT_PORT=47292
```

## 10. Verify deployment

```bash
docker compose exec qkt qkt list
```

```text
NAME              KIND       UPTIME    PORT     TRADES   STATE
btc-trend         strategy   00:00:18  47291    0        running
eur-meanrev       strategy   00:00:14  47292    0        running
```

## 11. Watch them work

Tail one strategy's logs:

```bash
docker compose exec qkt qkt logs btc-trend -f
```

You'll see ticks arrive + rule evaluations. When a condition transitions to true, you'll see the `BUY` action and the broker submission. Fills come back as `TradeEvent` a few hundred ms later.

Check status from outside the container:

```bash
curl http://localhost:47291/status | jq
```

Returns the full `StatusSnapshot` — positions, recent trades, equity, pending stack layers, etc.

## 12. Stop cleanly

When you're done (or want to bring everything down):

```bash
# Stop strategies one at a time with --flatten to close open positions
docker compose exec qkt qkt stop btc-trend     --flatten
docker compose exec qkt qkt stop eur-meanrev   --flatten

# Then tear down the stack
docker compose down                 # keeps qkt-state volume + reports
docker compose down -v              # full wipe — state + logs gone
```

`--flatten` closes any open positions at market before stopping. Without it, positions stay open on the venue and qkt reconciles them on next start.

## What to do next

- **Adapt the strategies.** Change parameters, swap symbols, try different timeframes. Re-deploy with `qkt deploy --as <name>`.
- **Add more strategies.** Drop more `.qkt` files into `strategies/` and deploy them. The daemon hosts as many as you have (within the port range).
- **Backtest first.** Don't deploy a strategy you haven't backtested. Run `qkt backtest strategies/btc-trend.qkt --from 2024-01-01 --to 2024-06-01` to validate.
- **Set up alerts.** The observability HTTP endpoints (`/status`, `/events`, `/health`) are scrape-able by Prometheus. See [operations/logging](../../operations/logging.md).

## Common gotchas

- **Compose can't pull `mt5-gateway:latest`.** The image isn't on Docker Hub. Build it locally from [github.com/elitekaycy/mt5-gateway](https://github.com/elitekaycy/mt5-gateway) or push it to your own registry.
- **MT5 logs out periodically.** Some brokers force daily re-auth. VNC back in, or restart `mt5-gateway` to trigger a re-login attempt.
- **Bybit testnet vs mainnet.** `BYBIT_TESTNET=true` routes to testnet; positions/keys/symbols all live there. Don't mix testnet and mainnet keys in the same `.env`.
- **Bind-mount path issues on Mac/Windows.** Docker Desktop sometimes needs explicit file-sharing permissions for paths outside your home directory. Keep the project under `~/`.
- **Port collisions.** If something else is already on `47291`, the daemon picks the next available. Check `qkt list` for the actual ports.

## See also

- [Bundle file listing](index.md) — every file shown again for copy-paste
- [Deploy live on Exness](../../how-to/deploy-exness.md) — focused MT5-only walkthrough
- [Cross-broker portfolio](../cross-broker.md) — same architecture, less commentary
- [Operations: deploy with Docker](../../operations/deploy-docker.md) — production hardening notes
