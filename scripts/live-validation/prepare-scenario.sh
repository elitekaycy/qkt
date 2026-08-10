#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-scenario.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic N [--symbol EURUSD|GBPUSD]

Creates a sanitized, isolated Exness-demo validation scenario. The gateway URL must
be an explicit 127.0.0.1 HTTP endpoint. Credentials are never accepted as arguments;
the generated config resolves QKT_BROKER_API_KEY only at execution time.
EOF
}

fail() {
    printf 'prepare-scenario: %s\n' "$1" >&2
    exit 1
}

output=""
scenario_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
magic=""
symbol="EURUSD"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) scenario_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --magic) magic="${2:-}"; shift 2 ;;
        --symbol) symbol="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$output" != *$'\n'* && "$output" != *'"'* && "$output" != *'\'* ]] ||
    fail "--output cannot contain newlines, double quotes, or backslashes"
output="$(realpath -m "$output")"
[[ "$scenario_id" =~ ^[a-z][a-z0-9_]{2,47}$ ]] ||
    fail "--id must match [a-z][a-z0-9_]{2,47}"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
gateway_port="${gateway_url##*:}"
[ "$gateway_port" -ge 1 ] && [ "$gateway_port" -le 65535 ] || fail "gateway port must be in 1..65535"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a non-negative decimal"
[[ ! "$expected_balance" =~ ^0+([.]0+)?$ ]] || fail "--expected-balance must be greater than zero"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
[[ "$magic" =~ ^[1-9][0-9]*$ ]] || fail "--magic must be a positive integer"
[ "$magic" -le 2147483647 ] || fail "--magic must fit a signed 32-bit integer"
case "$symbol" in
    EURUSD|GBPUSD) ;;
    *) fail "--symbol must be one of: EURUSD, GBPUSD" ;;
esac

git_sha="$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')"
if [ -n "$(git -C "$repo_root" status --porcelain 2>/dev/null)" ]; then
    git_dirty=true
else
    git_dirty=false
fi

if [ -e "$output" ] && [ -n "$(find "$output" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    fail "output directory already exists and is not empty: $output"
fi

mkdir -p \
    "$output/strategies/readonly" \
    "$output/strategies/armed" \
    "$output/data" \
    "$output/state" \
    "$output/logs" \
    "$output/journal" \
    "$output/evidence"

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
    magic: $magic
    server_time_zone: Etc/UTC
    expected_account_login: $expected_login
    expected_account_server: $expected_server
    expected_trade_mode: demo
    expected_account_currency: USD
    tick_poll_interval_ms: 100
    poll_interval_ms: 1000
    http_timeout_ms: 5000
    retry_attempts: 3

risk:
  max_daily_loss: "25"
  max_order_qty: "0.01"
  max_order_notional: "2500"
  price_collar_pct: "1"
  margin_floor_pct: "500"
  measured_usage_hours: "720"
  measured_usage_max_qty: "0.01"
  max_round_trips_10m: 2
  max_broker_rejections_1m: 2
  max_drawdown_pct: "0.5"
  max_daily_drawdown_pct: "0.25"
  live_equity_basis: venue
  per_strategy:
    ${scenario_id}_market_bracket:
      max_daily_loss: "10"
      max_position_size: "0.01"
      max_open_positions: 1
      max_trades_per_day: 1
      max_drawdown_pct: "0.25"
      max_daily_drawdown_pct: "0.10"

book_risk:
  capital: "$expected_balance"
  limits:
    max_gross_exposure: "0.05"
    max_net_exposure: "0.05"
    max_symbol_concentration: "1.0"
  allocation:
    method: FIXED
    max_leverage: "1"

state:
  enabled: true
  async: true

insights:
  enabled: false
EOF

cat > "$output/strategies/readonly/${scenario_id}_bars_readonly.qkt" <<EOF
STRATEGY ${scenario_id}_bars_readonly VERSION 1

SYMBOLS
    eur1 = EXNESS:EURUSD EVERY 1m WARMUP 20 BARS,
    eur5 = EXNESS:EURUSD EVERY 5m WARMUP 20 BARS

LET eur1_ema = ema(eur1.close, 3),
    eur1_rsi = rsi(eur1.close, 5),
    eur5_ema = ema(eur5.close, 3),
    eur5_atr = atr(eur5, 5)

RULES
    WHEN eur1_ema IS NOT NULL AND eur1_rsi IS NOT NULL
    THEN LOG "closed bar trace timeframe={timeframe} ema={ema} rsi={rsi} close={bar_close}"
         timeframe="1m" ema=eur1_ema rsi=eur1_rsi bar_close=eur1.close

    WHEN eur5_ema IS NOT NULL AND eur5_atr IS NOT NULL
    THEN LOG "closed bar trace timeframe={timeframe} ema={ema} atr={atr} close={bar_close}"
         timeframe="5m" ema=eur5_ema atr=eur5_atr bar_close=eur5.close
EOF

cat > "$output/strategies/armed/${scenario_id}_market_bracket.qkt" <<EOF
STRATEGY ${scenario_id}_market_bracket VERSION 1

SYMBOLS
    asset1 = EXNESS:$symbol EVERY 1m WARMUP 10 BARS,
    asset5 = EXNESS:$symbol EVERY 5m WARMUP 10 BARS

LET m1_fast = ema(asset1.close, 3),
    m1_slow = ema(asset1.close, 5),
    m5_fast = ema(asset5.close, 3),
    m5_slow = ema(asset5.close, 5),
    score = (m1_fast - m1_slow) + (m5_fast - m5_slow)

RULES
    WHEN score IS NOT NULL
     AND score >= 0
     AND POSITION.asset1 = 0
     AND OPEN_ORDERS.asset1 = 0
     AND TRADES.today = 0
    THEN BUY asset1 SIZING 0.01
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         ; LOG "bounded indicator entry side={side} score={score} m1_fast={m1_fast} m1_slow={m1_slow} m5_fast={m5_fast} m5_slow={m5_slow} close={bar_close}"
             side="BUY" score=score m1_fast=m1_fast m1_slow=m1_slow m5_fast=m5_fast m5_slow=m5_slow bar_close=asset1.close

    WHEN score IS NOT NULL
     AND score < 0
     AND POSITION.asset1 = 0
     AND OPEN_ORDERS.asset1 = 0
     AND TRADES.today = 0
    THEN SELL asset1 SIZING 0.01
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         ; LOG "bounded indicator entry side={side} score={score} m1_fast={m1_fast} m1_slow={m1_slow} m5_fast={m5_fast} m5_slow={m5_slow} close={bar_close}"
             side="SELL" score=score m1_fast=m1_fast m1_slow=m1_slow m5_fast=m5_fast m5_slow=m5_slow bar_close=asset1.close

    WHEN POSITION.asset1 != 0
     AND TRADES.today >= 1
     AND POSITION.asset1.holding_duration >= 1
    THEN CLOSE asset1
         ; LOG "bounded indicator exit signed_qty={signed_qty} holding_seconds={holding_seconds} close={bar_close}"
             signed_qty=POSITION.asset1 holding_seconds=POSITION.asset1.holding_duration bar_close=asset1.close
EOF

cat > "$output/expected.json" <<EOF
{
  "schema": "qkt-live-validation-expected-v2",
  "scenarioId": "$scenario_id",
  "account": {
    "login": $expected_login,
    "server": "$expected_server",
    "tradeMode": "demo",
    "currency": "USD",
    "leverage": $expected_leverage,
    "startingBalance": "$expected_balance"
  },
  "safety": {
    "gatewayUrl": "$gateway_url",
    "maximumLots": "0.01",
    "maximumOpenPositions": 1,
    "maximumTradesPerDay": 1,
    "stopDistance": "0.0030",
    "takeProfitDistance": "0.0060",
    "requiredInitialOwnership": "reconciled",
    "requiredFinalPositions": 0,
    "requiredFinalOrders": 0
  },
  "readOnlyStreams": [
    {"symbol": "EXNESS:EURUSD", "timeframe": "1m", "warmupBars": 20},
    {"symbol": "EXNESS:EURUSD", "timeframe": "5m", "warmupBars": 20}
  ],
  "armedScenario": {
    "strategy": "${scenario_id}_market_bracket",
    "symbol": "EXNESS:$symbol",
    "streams": [
      {"symbol": "EXNESS:$symbol", "timeframe": "1m", "warmupBars": 10},
      {"symbol": "EXNESS:$symbol", "timeframe": "5m", "warmupBars": 10}
    ],
    "indicators": ["ema(1m,3)", "ema(1m,5)", "ema(5m,3)", "ema(5m,5)"],
    "score": "(m1_fast-m1_slow)+(m5_fast-m5_slow)",
    "buyWhen": "score>=0",
    "sellWhen": "score<0",
    "quantityLots": "0.01",
    "maximumEntries": 1,
    "maximumExits": 1,
    "closeWhen": "position!=0 and tradesToday>=1 and holdingDurationSeconds>=1",
    "exitTimeframe": "1m",
    "minimumHoldingSeconds": 1,
    "maximumEntryAnchorDriftPoints": 20,
    "stopDistance": "0.0030",
    "takeProfitDistance": "0.0060"
  }
}
EOF

cat > "$output/cleanup.json" <<EOF
{
  "schema": "qkt-live-validation-cleanup-v1",
  "scenarioId": "$scenario_id",
  "magic": $magic,
  "ownedPositionTickets": [],
  "ownedOrderTickets": [],
  "status": "not_started"
}
EOF

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "$output/scenario.json" <<EOF
{
  "schema": "qkt-live-validation-scenario-v1",
  "scenarioId": "$scenario_id",
  "createdAt": "$created_at",
  "qktCommit": "$git_sha",
  "qktDirty": $git_dirty,
  "gatewayUrl": "$gateway_url",
  "magic": $magic,
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
