#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-higher-timeframe-warmup.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N [--symbol EURUSD|GBPUSD|XAUUSD]

Prepares a financially read-only higher-timeframe live warmup probe. The probe
uses the local MT5 gateway through qkt bot bars and retains closed M15/H1/H4
bar evidence for one-hour, one-day, and two-day style warmup windows.
EOF
}

fail() {
    printf 'prepare-higher-timeframe-warmup: %s\n' "$1" >&2
    exit 1
}

output=""
scenario_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
symbol="XAUUSD"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) scenario_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --symbol) symbol="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$scenario_id" =~ ^[a-z][a-z0-9_]{2,47}$ ]] ||
    fail "--id must match [a-z][a-z0-9_]{2,47}"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a decimal"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
case "$symbol" in
    EURUSD|GBPUSD|XAUUSD) ;;
    *) fail "--symbol must be one of: EURUSD, GBPUSD, XAUUSD" ;;
esac

output="$(realpath -m "$output")"
[ ! -e "$output" ] || fail "output already exists: $output"
mkdir -m 700 -p "$output/data" "$output/evidence" "$output/logs" "$output/state"

git_sha="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')"
if [ -n "$(git -C "$repo_root" status --porcelain 2>/dev/null)" ]; then
    git_dirty=true
else
    git_dirty=false
fi

cat > "$output/qkt.config.yaml" <<EOF
source: local
data_root: "$output/data"
starting_balance: "$expected_balance"
log_level: info

runtime:
  mode: dev

account:
  currency: USD

brokers:
  exness:
    type: mt5
    extends: exness
    gateway_url: $gateway_url
    api_key: \${QKT_BROKER_API_KEY}
    magic: 918900
    server_time_zone: Etc/UTC
    expected_account_login: $expected_login
    expected_account_server: $expected_server
    expected_trade_mode: demo
    expected_account_currency: USD
    tick_poll_interval_ms: 500
    poll_interval_ms: 5000
    http_timeout_ms: 5000
    retry_attempts: 3

risk:
  max_daily_loss: "1"
  max_order_qty: "0.01"
  max_order_notional: "1"
  price_collar_pct: "0.01"
  margin_floor_pct: "1000"
  measured_usage_hours: "720"
  measured_usage_max_qty: "0.01"
  max_round_trips_10m: 1
  max_broker_rejections_1m: 1
  max_drawdown_pct: "0.01"
  max_daily_drawdown_pct: "0.01"
  live_equity_basis: venue

book_risk:
  capital: "$expected_balance"
  limits:
    max_gross_exposure: "0.01"
    max_net_exposure: "0.01"
    max_symbol_concentration: "0.01"
  allocation:
    method: FIXED
    max_leverage: "1"

state:
  enabled: true
  async: true

insights:
  enabled: false
EOF

cat > "$output/expected.json" <<EOF
{
  "schema": "qkt-live-higher-timeframe-warmup-expected-v1",
  "scenarioId": "$scenario_id",
  "account": {
    "login": $expected_login,
    "server": "$expected_server",
    "tradeMode": "demo",
    "currency": "USD",
    "leverage": $expected_leverage,
    "startingBalance": "$expected_balance"
  },
  "symbol": "EXNESS:$symbol",
  "venueSymbol": "${symbol}m",
  "financiallyReadOnly": true,
  "probes": [
    {"timeframe": "15m", "timeframeMs": 900000, "warmupLabel": "one-hour", "bars": 4},
    {"timeframe": "15m", "timeframeMs": 900000, "warmupLabel": "one-day", "bars": 96},
    {"timeframe": "15m", "timeframeMs": 900000, "warmupLabel": "two-days", "bars": 192},
    {"timeframe": "1h", "timeframeMs": 3600000, "warmupLabel": "one-hour", "bars": 1},
    {"timeframe": "1h", "timeframeMs": 3600000, "warmupLabel": "one-day", "bars": 24},
    {"timeframe": "1h", "timeframeMs": 3600000, "warmupLabel": "two-days", "bars": 48},
    {"timeframe": "4h", "timeframeMs": 14400000, "warmupLabel": "four-hours", "bars": 1},
    {"timeframe": "4h", "timeframeMs": 14400000, "warmupLabel": "one-day", "bars": 6},
    {"timeframe": "4h", "timeframeMs": 14400000, "warmupLabel": "two-days", "bars": 12}
  ]
}
EOF

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$output/scenario.json" <<EOF
{
  "schema": "qkt-live-higher-timeframe-warmup-scenario-v1",
  "scenarioId": "$scenario_id",
  "createdAt": "$created_at",
  "qktCommit": "$git_sha",
  "qktDirty": $git_dirty,
  "gatewayUrl": "$gateway_url",
  "credentialsStored": false,
  "executionState": "prepared"
}
EOF

(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > SHA256SUMS
)

printf '%s\n' "$output"
