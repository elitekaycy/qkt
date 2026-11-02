#!/usr/bin/env bash
# Prove a live continuous-futures roll against real Deribit books through the paper venue, on a stack of its own.
# Roll timing is config: a fresh root policy `{daysBeforeExpiry, atUtc}` makes a real roll fall minutes ahead.
# No keys and no real money: the paper adapter on Deribit's public data. Nothing outside $ROOT is touched.
#
#   QKT_INSTALL=<dir of `gradlew installDist`'s build/install/qkt>  (the qkt under test)
#   run.sh up <daysBeforeExpiry> <HH:MM> <SELL|BUY> [plain|venueproxy|vgpproxy]
#   run.sh front                    # the contract and roll instants the running policy gives
#   run.sh far '<json>'             # venueproxy: SIMULATED tickers, e.g. '{"BTC_USDC-5NOV26":{"best_bid_price":38787.5,"best_ask_price":233490}}'
#   run.sh hold ':open'             # vgpproxy: hold a leg's POST, so `docker kill`/`start` of qkt lands between the legs
#   run.sh release                  # vgpproxy: send the held POST on to the gateway
#   run.sh evidence                 # the venue's orders and fills, balance, positions, lane, realized, qkt's log
#   run.sh down                     # removes the stack and everything under its run directory
#
# The contract chain needs the roll >= expiryGuardHours (24) before expiry: pick daysBeforeExpiry so it is.
# A far or held phase is simulated at that step and only there: say so wherever its result is quoted. Deribit's real
# ticker stream itself shows far touches for moments, so a market entry may fill far: `down` and `up` again.
set -euo pipefail

ROOT=${ROOT:-/var/tmp/qkt-build/rollproof}
RUN=$ROOT/run
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
QKT_IMAGE=${QKT_IMAGE:-ghcr.io/elitekaycy/qkt:edge}
GW_IMAGE=${GW_IMAGE:-ghcr.io/elitekaycy/qkt-venue-gateway:0.3.1}
HOST_PORT=${HOST_PORT:-18445}
PROJECT=rollproof
compose() { docker compose -p "$PROJECT" -f "$RUN/docker-compose.yml" "$@"; }

write_stack() { # <days> <hh:mm> <side> <mode>
  local days=$1 at=$2 side=$3 mode=$4
  local gw_url=https://www.deribit.com gw_ws=wss://www.deribit.com/ws/api/v2 qkt_gw=http://gw:8443
  [ "$mode" = venueproxy ] && { gw_url=http://venueproxy:8080; gw_ws=ws://venueproxy:8080/ws/api/v2; }
  [ "$mode" = vgpproxy ] && qkt_gw=http://vgpproxy:8080

  mkdir -p "$RUN"/{gw,state,strategies,tok,ctl,pip,data/contracts/PAPER_DERIBIT}
  [ -f "$RUN/tok/trader" ] || { for t in trader guardian; do openssl rand -hex 24 > "$RUN/tok/$t"; done; }
  chmod -R a+rwX "$RUN"
  cp "$HERE/proxy.py" "$RUN/proxy.py"

  cat > "$RUN/data/instruments.yaml" <<YAML
futures:
  - root: PAPER_DERIBIT:BTC_USDC
    currency: USDC
    multiplier: 1
    tickSize: 2.5
    volumeStep: 0.0001
    volumeMin: 0.0001
    takerFeeRate: 0.0005
    roll: { daysBeforeExpiry: $days, atUtc: "$at", adjust: panama }
YAML

  cat > "$RUN/strategies/rollproof.qkt" <<QKT
STRATEGY rollproof VERSION 1

SYMBOLS
    fut = PAPER_DERIBIT:BTC_USDC@front EVERY 1m

RULES
    WHEN POSITION.fut = 0
    THEN $side fut SIZING 0.001
QKT

  cat > "$RUN/qkt.config.yaml" <<YAML
source: local
data_root: /data
starting_balance: 10000
log_level: info
runtime: { mode: paper, candle_close_grace_ms: 15000 }
account: { currency: USD }
state: { enabled: true }
brokers:
  paper_deribit:
    type: gateway
    gateway_url: $qkt_gw
    api_key: file:/run/tok/trader
    expected_adapter: paper
    expected_account_login: rollproof-paper
    expected_trade_mode: demo
risk:
  live_equity_basis: modeled
  max_order_qty: "5"
  max_order_notional: "20000"
  price_collar_pct: "5"
  per_strategy:
    rollproof: { max_daily_loss: "50", max_position_size: "10" }
YAML

  cat > "$RUN/docker-compose.yml" <<YAML
name: $PROJECT
networks: { rollproof: {} }
x-proxy: &proxy
  image: python:3.12-slim
  networks: [rollproof]
  mem_limit: 256m
  cpus: 0.5
  volumes: ["./proxy.py:/proxy.py:ro", "./ctl:/ctl", "./pip:/root/.cache/pip"]
services:
  gw:
    image: $GW_IMAGE
    networks: [rollproof]
    mem_limit: 512m
    ports: ["127.0.0.1:$HOST_PORT:8443"]
    environment:
      GATEWAY_ADAPTER: paper
      GATEWAY_SETTING_LOGIN: rollproof-paper
      GATEWAY_SETTING_STARTING_BALANCE: "10000"
      GATEWAY_SETTING_CURRENCY: USDC
      GATEWAY_SETTING_FEE_RATE: "0.0005"
      GATEWAY_SETTING_DERIBIT_URL: $gw_url
      GATEWAY_SETTING_DERIBIT_WS_URL: $gw_ws
      GATEWAY_TRADER_TOKEN_FILE: /run/tok/trader
      GATEWAY_GUARDIAN_TOKEN_FILE: /run/tok/guardian
      GATEWAY_RECORDING_RETENTION_DAYS: "1"
    volumes: ["./gw:/data", "./tok:/run/tok:ro"]
  venueproxy:
    <<: *proxy
    profiles: [venueproxy]
    command: ["sh", "-c", "pip install -q aiohttp && exec python -I /proxy.py deribit"]
  vgpproxy:
    <<: *proxy
    profiles: [vgpproxy]
    command: ["sh", "-c", "pip install -q aiohttp && exec python -I /proxy.py vgp"]
  qkt:
    image: $QKT_IMAGE
    networks: [rollproof]
    cpus: 2
    mem_limit: 3g
    command: ["--config", "/etc/qkt/qkt.config.yaml", "--control-port", "47000"]
    environment: { QKT_STATE_DIR: /var/lib/qkt, QKT_DATA_HOME: /data, QKT_PREFLIGHT_RETRY_SECONDS: "600" }
    volumes:
      - "${QKT_INSTALL:?the qkt under test: QKT_INSTALL=.../build/install/qkt}:/opt/qkt:ro"
      - ./strategies:/strategies:ro
      - ./qkt.config.yaml:/etc/qkt/qkt.config.yaml:ro
      - ./data:/data
      - ./state:/var/lib/qkt
      - ./tok:/run/tok:ro
YAML
}

qkt_cli() { compose run --rm --no-deps -T --entrypoint qkt qkt "$@"; }

gw_get() { curl -fsS -H "Authorization: Bearer $(cat "$RUN/tok/trader")" "http://127.0.0.1:$HOST_PORT$1"; }

cmd=${1:?usage: see the top of this file}; shift
case "$cmd" in
  up)
    days=$1 at=$2 side=$3 mode=${4:-plain}
    write_stack "$days" "$at" "$side" "$mode"
    profile=(); [ "$mode" != plain ] && profile=(--profile "$mode")
    if [ "$mode" != plain ]; then
      compose "${profile[@]}" up -d "$mode"
      until compose "${profile[@]}" exec -T "$mode" python -c "import socket; socket.create_connection(('127.0.0.1', 8080))" 2>/dev/null; do sleep 2; done
    fi
    compose "${profile[@]}" up -d gw
    until gw_get /v1/health >/dev/null 2>&1; do sleep 2; done
    qkt_cli fetch PAPER_DERIBIT:BTC_USDC --catalog --config /etc/qkt/qkt.config.yaml
    qkt_cli fetch PAPER_DERIBIT:BTC_USDC --rolls --config /etc/qkt/qkt.config.yaml
    # `fetch --rolls` also prices rolls still ahead from today's bars. Keep only past ones: the proof's roll is then
    # measured live, as on a long-running daemon.
    docker run --rm -v "$RUN/data:/d" python:3.12-slim python -I -c "
import json, time
p = '/d/contracts/PAPER_DERIBIT/BTC_USDC.rolls.json'
h = json.load(open(p))
h['rolls'] = [r for r in h['rolls'] if r['atMs'] < time.time() * 1000]
json.dump(h, open(p, 'w'))"
    compose "${profile[@]}" up -d qkt
    ;;
  far) printf '%s' "$1" > "$RUN/ctl/far.json" ;;
  hold) printf '%s' "$1" > "$RUN/ctl/hold" ;;
  release) printf forward > "$RUN/ctl/release"; printf '' > "$RUN/ctl/hold" ;;
  front)
    python3 - "$RUN" <<'PY'
import json, re, sys, datetime as dt
run = sys.argv[1]
y = open(f"{run}/data/instruments.yaml").read()
d, at = re.search(r'daysBeforeExpiry: (\d+), atUtc: "([\d:]+)"', y).groups()
h, m = map(int, at.split(":"))
now = dt.datetime.now(dt.timezone.utc)
cs = sorted(json.load(open(f"{run}/data/contracts/PAPER_DERIBIT/BTC_USDC.json"))["contracts"], key=lambda c: c["expiryMs"])
for c in cs:
    e = dt.datetime.fromtimestamp(c["expiryMs"] / 1000, dt.timezone.utc)
    r = dt.datetime(e.year, e.month, e.day, h, m, tzinfo=dt.timezone.utc) - dt.timedelta(days=int(d))
    if r > now:
        print(f"front {c['symbol']} rolls {r:%F %H:%M}Z ({(e - r).total_seconds() / 3600:.1f}h before expiry), now {now:%H:%M:%S}Z")
        break
PY
    ;;
  evidence)
    python3 - "$RUN" <<'PY'
import json, sys
run = sys.argv[1]
d = json.load(open(f"{run}/gw/adapter/paper.json"))
print("venue balance", d["balance"], "positions", d["positions"])
for o in d["orders"]:
    print({k: o[k] for k in ("label", "symbol", "side", "type", "limit", "tif", "status", "filled", "avgFill", "reason", "createdMs")})
gross = sum(float(f["price"]) * float(f["quantity"]) * (1 if f["side"] == "SELL" else -1) for f in d["fills"])
print("fills", len(d["fills"]), "venue gross", round(gross, 8), "net", round(float(d["balance"]) - 10000, 8))
h = json.load(open(f"{run}/data/contracts/PAPER_DERIBIT/BTC_USDC.rolls.json"))
print(h["policy"], h["rolls"][-1])
state = f"{run}/state/state/rollproof"
print(json.load(open(f"{state}/PAPER_DERIBIT:BTC_USDC@front-lane.json")))
print(json.load(open(f"{state}/pnl.json")))
PY
    compose logs --no-log-prefix qkt 2>&1 | grep -v -E 'write slow|absurd book' | tail -"${EVIDENCE_LINES:-15}" | cut -c1-320
    ;;
  stop) compose --profile venueproxy --profile vgpproxy down -v --remove-orphans ;;
  wipe) docker run --rm -v "$RUN:/r" alpine sh -c 'rm -rf /r/* /r/.[!.]*' ; rmdir "$RUN" ;;
  down) "$0" stop; "$0" wipe ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
