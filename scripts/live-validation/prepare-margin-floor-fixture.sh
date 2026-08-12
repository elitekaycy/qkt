#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-margin-floor-fixture.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH]

Prepares a controlled live margin-floor fixture for the localhost MT5 demo
gateway. The fixture has two roles:

1. an opener strategy that owns exactly one bounded 0.01-lot live position; and
2. a probe strategy whose runtime config must materialize a dynamic
   margin_floor_pct above the observed live margin level so the entry rejects
   before MT5 transport, then becomes eligible after the opener is flattened
   and headroom recovers.

No gateway request is made and no credential is accepted or retained.
EOF
}

fail() {
    printf 'prepare-margin-floor-fixture: %s\n' "$1" >&2
    exit 1
}

output=""
fixture_id=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
magic_base=""
cli="$repo_root/build/install/qkt/bin/qkt"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) fixture_id="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --magic-base) magic_base="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$output" != *$'\n'* && "$output" != *'"'* && "$output" != *'\'* ]] ||
    fail "--output cannot contain newlines, double quotes, or backslashes"
[[ "$fixture_id" =~ ^[a-z][a-z0-9_]{2,31}$ ]] || fail "--id must be a lowercase DSL identifier"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
gateway_port="${gateway_url##*:}"
[ "$gateway_port" -ge 1 ] && [ "$gateway_port" -le 65535 ] || fail "gateway port must be in 1..65535"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a decimal"
[[ ! "$expected_balance" =~ ^0+([.]0+)?$ ]] || fail "--expected-balance must be greater than zero"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
[[ "$magic_base" =~ ^[1-9][0-9]*$ ]] || fail "--magic-base must be a positive integer"
[ "$magic_base" -le 2147483645 ] || fail "--magic-base plus one must fit a signed 32-bit integer"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"

output="$(realpath -m "$output")"
git_sha="$(git -C "$repo_root" rev-parse HEAD)"
if [ -n "$(git -C "$repo_root" status --porcelain)" ]; then
    git_dirty=true
else
    git_dirty=false
fi

mkdir -m 700 -p "$output/opener" "$output/probe"

write_common_config() {
    local config_path="$1"
    local magic="$2"
    local margin_floor="$3"

    cat > "$config_path" <<EOF
source: local
data_root: "/work/data"
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
  max_daily_loss: "999999999"
  max_drawdown_pct: "100"
  max_order_qty: "0.01"
  max_order_notional: "100000000"
  price_collar_pct: "100"
  margin_floor_pct: "$margin_floor"
  measured_usage_hours: "0"
  measured_usage_max_qty: "0.01"
  max_round_trips_10m: 100
  max_broker_rejections_1m: 100
  live_equity_basis: venue

state:
  enabled: true
  async: true

insights:
  enabled: false
EOF
}

write_strategy() {
    local strategy_path="$1"
    local strategy="$2"
    local single_entry_guard="$3"

    cat > "$strategy_path" <<EOF
STRATEGY $strategy VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 2 BARS

RULES
    WHEN eur.close IS NOT NULL
     AND mod(NOW.minute_utc, 2) = 0
$single_entry_guard
    THEN BUY eur SIZING 0.01
EOF
    "$cli" parse "$strategy_path" >/dev/null
}

write_role_contract() {
    local output_path="$1"
    local schema="$2"
    local strategy="$3"
    local magic="$4"
    local mode="$5"

    if [ "$mode" = opener ]; then
        jq -n \
            --arg schema "$schema" \
            --arg strategy "$strategy" \
            --argjson magic "$magic" '
            {
              schema:$schema,
              strategy:$strategy,
              magic:$magic,
              symbol:"EXNESS:EURUSD",
              venueSymbol:"EURUSDm",
              timeframe:"1m",
              warmupBars:2,
              synchronizedEvenMinuteTrigger:true,
              fixedIntentQty:"0.01",
              required:{
                livePositionsObserved:1,
                orderEventsMin:1,
                fillsMin:1,
                closeMutationsMin:1,
                finalFlat:true
              }
            }
        ' > "$output_path"
    else
        jq -n \
            --arg schema "$schema" \
            --arg strategy "$strategy" \
            --argjson magic "$magic" '
            {
              schema:$schema,
              strategy:$strategy,
              magic:$magic,
              symbol:"EXNESS:EURUSD",
              venueSymbol:"EURUSDm",
              timeframe:"1m",
              warmupBars:2,
              synchronizedEvenMinuteTrigger:true,
              fixedIntentQty:"0.01",
              dynamicMarginFloorSelection:{
                source:"gateway_account.margin_level",
                floorPct:"ceil(observed_margin_level_pct) + 1000",
                materializationTarget:"probe/qkt.config.yaml"
              },
              expectedRule:"MarginFloor",
              expectedReason:{
                kind:"regex",
                value:"^margin level [0-9]+([.][0-9]+)?% below floor [0-9]+([.][0-9]+)?% — no new exposure until headroom recovers [(]stop-out is the alternative[)]$"
              },
              required:{
                streamCandlesMin:1,
                evaluatedCandlesMin:1,
                ruleDecisions:1,
                decisionOrderLinks:1,
                riskRejections:1,
                preRecoveryOrderEvents:0,
                preRecoveryFills:0,
                preRecoveryGatewayMutations:0,
                recoveredOrderEventsMin:1,
                recoveredFillsMin:1,
                recoveredCloseMutationsMin:1,
                finalFlat:true
              }
            }
        ' > "$output_path"
    fi
}

opener_magic="$magic_base"
probe_magic="$((magic_base + 1))"
opener_strategy="${fixture_id}_margin_floor_opener"
probe_strategy="${fixture_id}_margin_floor_probe"

mkdir -m 700 -p "$output/opener/strategies" "$output/opener/data" "$output/opener/state" \
    "$output/opener/logs" "$output/opener/evidence"
mkdir -m 700 -p "$output/probe/strategies" "$output/probe/data" "$output/probe/state" \
    "$output/probe/logs" "$output/probe/evidence"

write_common_config "$output/opener/qkt.config.yaml" "$opener_magic" "0"
write_common_config "$output/probe/qkt.config.template.yaml" "$probe_magic" "__QKT_DYNAMIC_MARGIN_FLOOR_PCT__"
write_strategy "$output/opener/strategies/$opener_strategy.qkt" "$opener_strategy" '     AND TRADES.today = 0'
write_strategy "$output/probe/strategies/$probe_strategy.qkt" "$probe_strategy" '     AND TRADES.today = 0'
write_role_contract "$output/opener/expected.json" "qkt-live-margin-floor-opener-v1" "$opener_strategy" "$opener_magic" opener
write_role_contract "$output/probe/expected.json" "qkt-live-margin-floor-probe-v1" "$probe_strategy" "$probe_magic" probe

jq -n '
    {
      schema:"qkt-live-margin-floor-selection-v1",
      source:"gateway_account.margin_level",
      floorPctFormula:"ceil(observed_margin_level_pct) + 1000",
      minObservedMarginLevelPct:"0.00000001",
      openerPositionRequired:true,
      finalMaterializedConfig:"probe/qkt.config.yaml"
    }
' > "$output/probe/dynamic-floor-selection.json"

jq -n \
    --arg id "$fixture_id" \
    --arg gatewayUrl "$gateway_url" \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" \
    --arg qktCommit "$git_sha" \
    --argjson qktDirty "$git_dirty" \
    --slurpfile opener "$output/opener/expected.json" \
    --slurpfile probe "$output/probe/expected.json" \
    --slurpfile selection "$output/probe/dynamic-floor-selection.json" '
    {
      schema:"qkt-live-margin-floor-fixture-v1",
      id:$id,
      gatewayUrl:$gatewayUrl,
      account:{
        login:$login,
        server:$server,
        currency:"USD",
        balance:$balance,
        leverage:$leverage
      },
      qktCommit:$qktCommit,
      qktDirty:$qktDirty,
      credentialsStored:false,
      contract:{
        openerCreatesLiveExposure:true,
        probeRejectsBeforeTransport:true,
        probeAllowedAfterHeadroomRecovery:true,
        dynamicMarginFloorPct:true,
        fixedIntentQty:"0.01",
        finalVenueFlat:true,
        finalPendingOrders:false
      },
      opener:$opener[0],
      probe:$probe[0],
      dynamicFloorSelection:$selection[0],
      claims:{
        marginFloorPassed:false,
        productionReadiness:false
      }
    }
' > "$output/suite.json"

(
    cd "$output"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
chmod 600 "$output/SHA256SUMS"
