# The MT5 gateway

qkt never talks to MetaTrader 5 directly. Live MT5 trading routes through a small
HTTP service — the **mt5-gateway** — that runs a real MT5 terminal headless under
Wine inside Docker and exposes trading, market data, and account state as a REST
API. qkt calls that API; the gateway drives the terminal.

```
qkt daemon ──HTTP──▶ mt5-gateway (Flask + MT5 under Wine) ──▶ your broker
```

This page covers the gateway on its own: what it is, the different ways to install
it, how it logs into any broker headlessly, and how to verify it before you point
qkt at it. Once the gateway answers `"status":"ready"`, wire qkt to it with
[Deploy MT5](deploy-mt5.md).

!!! warning "It places real orders"
    The gateway can trade a real broker account. Test with a **demo** account
    first. Bind its ports to loopback and never expose them to the public internet.

## Why a gateway at all

MetaTrader 5 is a Windows GUI app with a closed protocol. Running it as a service
normally means a Windows box and a human clicking "Login." The gateway runs the
genuine MT5 terminal under Wine and publishes its Python API over HTTP, so any
system — here, qkt — can drive an MT5 account programmatically.

The hard part it solves is **logging in headless, for any broker**. A fresh MT5
install can't find a broker by name without its encrypted broker directory
(`servers.dat`), and that file can't be generated. The gateway instead **resolves
the broker's server name to a connectable address** and connects directly. You
supply only the account number, password, and the server *name* — never an IP, a
directory file, or a VNC session.

## Install

Pick one. Docker Hub is the fastest; Compose is best for a persistent setup;
from source is for hacking on the gateway itself.

=== "Docker Hub (fastest)"

    Pull the published image and run it headless against a broker account:

    ```bash
    docker pull elitekaycy/mt5-gateway-api:latest   # or pin: :0.3.10

    docker volume create mt5-gateway-config

    docker run -d --name mt5-gateway \
      --restart unless-stopped \
      -p 127.0.0.1:5001:5001 \
      -p 127.0.0.1:3000:3000 \
      -v mt5-gateway-config:/config \
      -e MT5_LOGIN=12345678 \
      -e MT5_PASSWORD='your-trading-password' \
      -e MT5_SERVER=Exness-MT5Trial9 \
      -e MT5_ENABLE_ALGO_TRADING=1 \
      -e API_KEY='change-this-long-random-token' \
      elitekaycy/mt5-gateway-api:latest
    ```

    The `-v mt5-gateway-config:/config` volume persists MT5 state, so every boot
    after the first is instant and offline.

=== "Docker Compose"

    ```bash
    cp .env.example .env      # then edit it (see the env table below)
    docker compose up -d
    ```

    Minimal `.env` for headless login to any broker:

    ```dotenv
    MT5_LOGIN=12345678
    MT5_PASSWORD=your-trading-password
    MT5_SERVER=Exness-MT5Trial9      # the server name from your broker, that's all
    MT5_ENABLE_ALGO_TRADING=1        # default 1; set 0 to disable live/Expert trading
    API_KEY=change-this-long-random-token
    ```

    A production-shaped Compose file (named volume, loopback ports, healthcheck,
    optional self-hosted resolver) is in the
    [gateway README](https://github.com/elitekaycy/mt5-gateway#production-compose-example).

    If you are running the **bundled qkt stack** — gateway and qkt daemon in one
    `docker-compose.yml` — you don't install the gateway separately; follow
    [Deploy MT5](deploy-mt5.md) instead, which starts both together.

=== "From source"

    ```bash
    git clone https://github.com/elitekaycy/mt5-gateway.git && cd mt5-gateway
    cp .env.example .env      # edit credentials
    docker compose up --build -d
    ```

    Local dev checks: `ruff check .`, `mypy app/`, `pytest -q --cov`.

## How headless login works

You give the gateway the broker **server name** — the exact string from your
broker (e.g. `ICMarketsSC-Demo`, `Exness-MT5Trial9`, `FTMO-Demo`). On first boot:

1. The gateway resolves that name to a trade-server **access point** (`host:port`)
   using a broker-directory search that mirrors MetaQuotes' own directory.
2. It writes MT5's startup config with `Server=<host:port>` and launches the
   terminal, which connects directly and authorizes — no `servers.dat` needed.
3. Unless `MT5_ENABLE_ALGO_TRADING=0`, the startup config also enables MT5
   Expert/live trading, so orders can actually be placed.
4. MT5 writes its own directory entry into the `/config` volume, so **every later
   boot is instant and offline**. The login is idempotent.

Resolution uses the public `mt5.mtapi.io` directory by default — one call, only on
a broker's first boot. For a **zero-third-party** setup, run the bundled
self-hosted resolver:

```bash
docker compose --profile self-hosted-resolver up
```

The resolver container only maps server names to addresses during first boot; it
never sees trades or credentials. Full detail:
[gateway headless-login docs](https://elitekaycy.github.io/mt5-gateway/headless-login/).

!!! tip "Manual login as a fallback"
    Leave `MT5_LOGIN` empty to skip headless login and sign in by hand via the VNC
    desktop on `http://localhost:3000`. Useful only to diagnose an automatic-login
    failure; headless is the normal path.

## Environment variables

| Var | Meaning |
|---|---|
| `MT5_LOGIN` / `MT5_PASSWORD` / `MT5_SERVER` | Broker account number, trading password, and server name. These three drive headless login. Leave `MT5_LOGIN` empty for the manual VNC flow. |
| `API_KEY` | Bearer token required by every API operation except `/health/live`. Set a long random value. |
| `MT5_ENABLE_ALGO_TRADING` | `1` by default (live/Expert trading on). Set `0`, `false`, `no`, `off`, or `disabled` to boot with trading disabled. |
| `MT5_SERVER_UTC_OFFSET_SECONDS` | Broker-server clock offset for GTD ("good-till-date") expiries. **When driven by qkt, set this to `0`** — qkt performs the single broker-wall-to-UTC conversion itself (see [Deploy MT5](deploy-mt5.md)). |

Every other knob — resolver tuning, pre-trade limits, audit and kill-switch paths,
CORS, VNC — is documented in the
[gateway configuration reference](https://elitekaycy.github.io/mt5-gateway/reference/configuration/).

## Verify before wiring qkt

Three probes, cheapest first:

```bash
export API_KEY='change-this-long-random-token'

curl http://localhost:5001/health/live
# {"ok": true, "status": "alive"}                       process is up (no auth)

curl -H "Authorization: Bearer $API_KEY" http://localhost:5001/health/ready
# {"ok": true, "status": "ready", "mt5_status": "connected"}   terminal + account

curl -H "Authorization: Bearer $API_KEY" http://localhost:5001/account
# expect your login/server plus "trade_allowed": true and "trade_expert": true
```

A `ready` response with `"status":"ready"` means the API, the terminal, and the
broker login are all good. Interactive endpoint docs (Swagger/OpenAPI) are at
`http://localhost:5001/apidocs`.

## Point qkt at the gateway

In `qkt.config.yaml`, a broker profile names the gateway URL and API key:

```yaml title="qkt.config.yaml"
brokers:
  mt5:
    type: mt5
    gateway_url: ${QKT_BROKER_GATEWAY_URL:-http://mt5-gateway:5001}
    api_key: ${QKT_BROKER_API_KEY}
    server_time_zone: ${QKT_BROKER_SERVER_TIME_ZONE}   # UTC, new_york_close, an IANA id, or +02:00
    symbol_suffix: ${QKT_BROKER_SYMBOL_SUFFIX:-}       # e.g. "m" for Exness (EURUSD -> EURUSDm)
    magic: ${QKT_BROKER_MAGIC:-10001}
```

Use `http://mt5-gateway:5001` when qkt runs in the same Docker network as the
gateway, or `http://localhost:5001` for a non-Docker qkt. The full qkt-side
walkthrough — compose wiring, broker profile, and the first live deploy — is in
[Deploy MT5](deploy-mt5.md).

## Running more than one broker

One gateway container serves **one** MT5 account. To trade several brokers or
accounts at once, run one gateway per account on distinct names and ports, each
with its own `MT5_*` credentials:

```bash
docker run -d --name mt5-exness   -p 127.0.0.1:5001:5001 ... -e MT5_SERVER=Exness-MT5Trial9 ...
docker run -d --name mt5-icmarkets -p 127.0.0.1:5002:5001 ... -e MT5_SERVER=ICMarketsSC-Demo ...
```

Then give qkt one broker profile per gateway (distinct `gateway_url`, `magic`, and
`symbol_suffix`), composing them with `extends:`. A strategy or `PORTFOLIO` can
then route different children to different brokers — see the
[cross-broker portfolio example](../examples/cross-broker.md).

## Ports and security

- **5001** — HTTP API. Bind to loopback; always set `API_KEY`.
- **3000** — VNC desktop for optional manual login / diagnostics.

Swagger UI and its spec load without the token so browser docs work, but executing
any API operation still requires `Authorization: Bearer <key>`. CORS is off unless
`CORS_ORIGINS` is set. Never expose either port to the public internet — put the
gateway on a private network behind an authenticated reverse proxy or mTLS.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `/health/ready` never turns `ready` | Wrong `MT5_SERVER` name, bad credentials, or first-boot resolution failed. Open VNC on `:3000` to see the terminal's login error. Confirm the server string matches your broker exactly. |
| Orders rejected with trading disabled | `MT5_ENABLE_ALGO_TRADING` is `0`, or the account itself has Expert trading off. Check `"trade_expert": true` in `/account`. |
| GTD orders expire at the wrong time | Let qkt own the clock: set `MT5_SERVER_UTC_OFFSET_SECONDS=0` on the gateway and configure `server_time_zone` in qkt. If neither the env nor a live quote yields an offset, the gateway rejects the GTD order rather than expiring it wrongly. |
| Symbol not found | Your broker suffixes symbols (Exness uses `m`: `XAUUSDm`). Set `symbol_suffix` in the qkt profile; the DSL still names the bare symbol (`MT5:XAUUSD`). |
| Need to halt everything now | `POST /kill` stops trading; `POST /kill/release` resumes. `/health/ready` reports `not ready` while the kill switch is engaged. |

For the complete API, safety fields, and idempotency contract, see the
[gateway API reference](https://elitekaycy.github.io/mt5-gateway/reference/api/).
