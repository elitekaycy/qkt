#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-risk-rejection-matrix.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH]

Prepares five zero-mutation live risk-rejection cases: maximum quantity, maximum
notional, far pending-price collar, measured usage, and operator halt. Every case
uses a fixed 0.01-lot DSL intent which must be rejected before MT5 transport.
Margin floor, daily loss, drawdown, and loss streak remain explicitly deferred
because they need controlled stateful fixtures. No gateway request is made and no
credential is accepted or retained.
EOF
}

fail() {
    printf 'prepare-risk-rejection-matrix: %s\n' "$1" >&2
    exit 1
}

output=""
suite_id=""
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
        --id) suite_id="${2:-}"; shift 2 ;;
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
[[ "$suite_id" =~ ^[a-z][a-z0-9_]{2,31}$ ]] || fail "--id must be a lowercase DSL identifier"
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
[ "$magic_base" -le 2147483643 ] || fail "--magic-base plus four must fit a signed 32-bit integer"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"

output="$(realpath -m "$output")"
git_sha="$(git -C "$repo_root" rev-parse HEAD)"
if [ -n "$(git -C "$repo_root" status --porcelain)" ]; then
    git_dirty=true
else
    git_dirty=false
fi
mkdir -m 700 -p "$output/cases"

write_config() {
    local case_dir="$1"
    local magic="$2"
    local max_qty="$3"
    local max_notional="$4"
    local collar_pct="$5"
    local measured_hours="$6"
    local measured_max_qty="$7"

    mkdir -m 700 -p "$case_dir/strategies" "$case_dir/data" "$case_dir/state" \
        "$case_dir/logs" "$case_dir/evidence"
    cat > "$case_dir/qkt.config.yaml" <<EOF
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
  max_order_qty: "$max_qty"
  max_order_notional: "$max_notional"
  price_collar_pct: "$collar_pct"
  margin_floor_pct: "0"
  measured_usage_hours: "$measured_hours"
  measured_usage_max_qty: "$measured_max_qty"
  max_round_trips_10m: 100
  max_broker_rejections_1m: 100
  max_drawdown_pct: "100"
  max_daily_drawdown_pct: "100"
  live_equity_basis: venue

state:
  enabled: true
  async: true

insights:
  enabled: false
EOF
}

write_strategy() {
    local case_dir="$1"
    local strategy="$2"
    local action="$3"

    cat > "$case_dir/strategies/$strategy.qkt" <<EOF
STRATEGY $strategy VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 2 BARS

RULES
    WHEN eur.close IS NOT NULL
     AND mod(NOW.minute_utc, 2) = 0
    THEN $action
EOF
    "$cli" parse "$case_dir/strategies/$strategy.qkt" >/dev/null
}

write_case_contract() {
    local case_dir="$1"
    local case_id="$2"
    local strategy="$3"
    local magic="$4"
    local rule="$5"
    local reason_kind="$6"
    local reason_value="$7"
    local order_type="$8"
    local operator_halt="$9"

    jq -n \
        --arg caseId "$case_id" \
        --arg strategy "$strategy" \
        --argjson magic "$magic" \
        --arg rule "$rule" \
        --arg reasonKind "$reason_kind" \
        --arg reasonValue "$reason_value" \
        --arg orderType "$order_type" \
        --argjson operatorHalt "$operator_halt" '
        {
          schema:"qkt-live-risk-rejection-case-v1",
          caseId:$caseId,
          strategy:$strategy,
          magic:$magic,
          symbol:"EXNESS:EURUSD",
          venueSymbol:"EURUSDm",
          timeframe:"1m",
          warmupBars:2,
          synchronizedEvenMinuteTrigger:true,
          fixedIntentQty:"0.01",
          expectedRule:$rule,
          expectedReason:{kind:$reasonKind,value:$reasonValue},
          expectedOrderType:$orderType,
          operatorHaltBeforeTrigger:$operatorHalt,
          required:{ruleDecisions:1,decisionOrderLinks:1,riskRejections:1,orderEvents:0,fills:0,gatewayMutations:0}
        }
    ' > "$case_dir/expected.json"
}

case_ids=(max-quantity max-notional far-price-collar measured-usage operator-halt)
suffixes=(max_quantity max_notional far_price_collar measured_usage operator_halt)
rules=(MaxOrderQty MaxOrderNotional PriceCollar MeasuredUsage RiskEngineHaltGate)
reason_kinds=(exact regex regex regex exact)
reason_values=(
    'order qty 0.01 exceeds per-order cap 0.005'
    '^order notional [0-9]+([.][0-9]+)? exceeds cap 1 [(]qty=0[.]01 ref=[0-9]+([.][0-9]+)? contractSize=100000([.]0+)? currency=USD[)]$'
    '^order price 9[.]00000 deviates [0-9]+([.][0-9]+)? from last [0-9]+([.][0-9]+)? [(]collar 0[.]01[)]$'
    '^measured-usage window active until epoch [0-9]+ms: order qty 0[.]01 exceeds the validation cap 0[.]005 [(]set risk[.]measured_usage_hours: 0 to opt out[)]$'
    'halted: operator'
)
order_types=(Market Market Limit Market Market)
operator_halts=(false false false false true)

for index in 0 1 2 3 4; do
    case_id="${case_ids[$index]}"
    strategy="${suite_id}_${suffixes[$index]}"
    magic="$((magic_base + index))"
    case_dir="$output/cases/$case_id"
    case "$case_id" in
        max-quantity) write_config "$case_dir" "$magic" 0.005 1 100 0 0.01 ;;
        max-notional) write_config "$case_dir" "$magic" 0.01 1 100 720 0.005 ;;
        far-price-collar) write_config "$case_dir" "$magic" 0.01 100000000 1 720 0.005 ;;
        measured-usage) write_config "$case_dir" "$magic" 0.01 100000000 100 720 0.005 ;;
        operator-halt) write_config "$case_dir" "$magic" 0.01 1 100 0 0.01 ;;
    esac
    action="BUY eur SIZING 0.01"
    [ "$case_id" != far-price-collar ] || action="BUY eur SIZING 0.01 ORDER_TYPE = LIMIT AT 9.00000 TIF GTC"
    write_strategy "$case_dir" "$strategy" "$action"
    write_case_contract \
        "$case_dir" "$case_id" "$strategy" "$magic" "${rules[$index]}" \
        "${reason_kinds[$index]}" "${reason_values[$index]}" "${order_types[$index]}" \
        "${operator_halts[$index]}"
done

jq -n '
    {
      schema:"qkt-live-risk-stateful-deferred-v1",
      status:"deferred-not-passed",
      cases:[
        {id:"margin-floor",why:"requires a controlled account margin-level fixture",requiredFixture:"owned open exposure with deterministic venue margin"},
        {id:"daily-loss",why:"requires controlled realized-loss state",requiredFixture:"owned fills and deterministic day boundary"},
        {id:"drawdown",why:"requires controlled equity high-water and drawdown state",requiredFixture:"owned mark path with deterministic equity basis"},
        {id:"loss-streak",why:"requires ordered losing trade history",requiredFixture:"owned losing round trips with deterministic ledger recovery"}
      ]
    }
' > "$output/stateful-deferred.json"

jq -s \
    --arg id "$suite_id" \
    --arg gatewayUrl "$gateway_url" \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" \
    --arg qktCommit "$git_sha" \
    --argjson qktDirty "$git_dirty" \
    --slurpfile deferred "$output/stateful-deferred.json" '
    {
      schema:"qkt-live-risk-rejection-matrix-v1",
      id:$id,
      gatewayUrl:$gatewayUrl,
      credentialsStored:false,
      qktCommit:$qktCommit,
      qktDirty:$qktDirty,
      account:{login:$login,server:$server,tradeMode:"demo",currency:"USD",balance:$balance,leverage:$leverage},
      contract:{containers:5,parallel:true,financiallyReadOnly:true,fixedIntentQty:"0.01",requiredGatewayMutations:0,requiredFills:0},
      synchronization:{deployOnOddUtcMinute:true,triggerOnNextEvenUtcMinute:true},
      cases:.,
      deferredStateful:$deferred[0],
      claims:{preTransportStaticRejectionsOnly:true,statefulRiskCasesPassed:false,productionReadiness:false}
    }
' "$output"/cases/*/expected.json > "$output/suite.json"

find "$output" -type f -exec chmod 600 {} +
(
    cd "$output"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
chmod 600 "$output/SHA256SUMS"
printf 'prepared %s\n' "$output"
