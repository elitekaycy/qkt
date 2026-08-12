#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: prepare-stateful-risk-matrix.sh --output DIR --id ID --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N --magic-base N [--cli PATH]

Prepares four deterministic live stateful-risk cases: global daily loss,
strategy daily loss, global drawdown, and loss-streak halt. Each case restores
persisted risk state, proves the real halt rule trips on live ticks/bars, and
then requires one causally linked RiskRejectedEvent before MT5 transport. Margin
floor remains explicitly deferred because it needs controlled live margin usage.
EOF
}

fail() {
    printf 'prepare-stateful-risk-matrix: %s\n' "$1" >&2
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
[ "$magic_base" -le 2147483644 ] || fail "--magic-base plus three must fit a signed 32-bit integer"
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
    local risk_block="$3"

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
$risk_block
  max_order_qty: "0.01"
  max_order_notional: "100000000"
  price_collar_pct: "100"
  margin_floor_pct: "0"
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
    local case_dir="$1"
    local strategy="$2"

    cat > "$case_dir/strategies/$strategy.qkt" <<EOF
STRATEGY $strategy VERSION 1

SYMBOLS
    eur = EXNESS:EURUSD EVERY 1m WARMUP 2 BARS

RULES
    WHEN eur.close IS NOT NULL
     AND mod(NOW.minute_utc, 2) = 0
    THEN BUY eur SIZING 0.01
EOF
    "$cli" parse "$case_dir/strategies/$strategy.qkt" >/dev/null
}

write_case_contract() {
    local case_dir="$1"
    local case_id="$2"
    local strategy="$3"
    local magic="$4"
    local seed_kind="$5"
    local halt_rule="$6"
    local halt_strategy="$7"
    local halt_reason_kind="$8"
    local halt_reason_value="$9"
    local reject_reason_kind="${10}"
    local reject_reason_value="${11}"

    jq -n \
        --arg caseId "$case_id" \
        --arg strategy "$strategy" \
        --argjson magic "$magic" \
        --arg seedKind "$seed_kind" \
        --arg haltRule "$halt_rule" \
        --arg haltStrategy "$halt_strategy" \
        --arg haltReasonKind "$halt_reason_kind" \
        --arg haltReasonValue "$halt_reason_value" \
        --arg rejectReasonKind "$reject_reason_kind" \
        --arg rejectReasonValue "$reject_reason_value" '
        {
          schema:"qkt-live-stateful-risk-case-v1",
          caseId:$caseId,
          strategy:$strategy,
          magic:$magic,
          seedKind:$seedKind,
          symbol:"EXNESS:EURUSD",
          venueSymbol:"EURUSDm",
          timeframe:"1m",
          warmupBars:2,
          synchronizedEvenMinuteTrigger:true,
          fixedIntentQty:"0.01",
          expectedHalt:{
            rule:$haltRule,
            strategyId:(if $haltStrategy == "" then null else $haltStrategy end),
            reason:{kind:$haltReasonKind,value:$haltReasonValue}
          },
          expectedRejection:{
            reason:{kind:$rejectReasonKind,value:$rejectReasonValue}
          },
          required:{
            streamCandlesMin:1,
            evaluatedCandlesMin:1,
            haltedEvents:1,
            ruleDecisions:1,
            decisionOrderLinks:1,
            riskRejections:1,
            orderEvents:0,
            fills:0,
            gatewayMutations:0
          }
        }
    ' > "$case_dir/expected.json"
}

case_ids=(global-daily-loss strategy-daily-loss global-drawdown loss-streak)
suffixes=(global_daily_loss strategy_daily_loss global_drawdown loss_streak)
seed_kinds=(global-daily-loss strategy-daily-loss global-drawdown loss-streak)
halt_rules=(MaxDailyLoss MaxStrategyDailyLoss MaxDrawdown LossStreakHalt)
halt_strategy_ids=("" strategy "" "")
halt_reason_kinds=(exact exact regex exact)
halt_reason_values=(
    'daily loss 10 exceeds max 5'
    'strategy daily loss 10 exceeds max 5'
    '^global drawdown [0-9]+([.][0-9]+)? exceeds max 0[.]00005$'
    ''
)
reject_reason_kinds=(exact exact regex exact)
reject_reason_values=(
    'halted: daily loss 10 exceeds max 5'
    'halted: strategy daily loss 10 exceeds max 5'
    '^halted: global drawdown [0-9]+([.][0-9]+)? exceeds max 0[.]00005$'
    ''
)

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    strategy="${suite_id}_${suffixes[$index]}"
    magic="$((magic_base + index))"
    case_dir="$output/cases/$case_id"
    halt_strategy="${halt_strategy_ids[$index]}"
    [ "$halt_strategy" != strategy ] || halt_strategy="$strategy"
    case "$case_id" in
        global-daily-loss)
            write_config "$case_dir" "$magic" \
'  max_daily_loss: "5"
  max_drawdown_pct: "100"'
            ;;
        strategy-daily-loss)
            write_config "$case_dir" "$magic" \
"  max_daily_loss: \"0\"
  max_drawdown_pct: \"100\"
  per_strategy:
    $strategy:
      max_daily_loss: \"5\""
            ;;
        global-drawdown)
            write_config "$case_dir" "$magic" \
'  max_daily_loss: "0"
  max_drawdown_pct: "0.005"'
            ;;
        loss-streak)
            write_config "$case_dir" "$magic" \
"  max_daily_loss: \"0\"
  max_drawdown_pct: \"100\"
  per_strategy:
    $strategy:
      loss_streak_halt: \"1\""
            ;;
    esac
    write_strategy "$case_dir" "$strategy"
    if [ "$case_id" = loss-streak ]; then
        halt_reason_values[$index]="LossStreakHalt[$strategy]: 1 consecutive losses, max 1"
        reject_reason_values[$index]="halted: LossStreakHalt[$strategy]: 1 consecutive losses, max 1"
        halt_strategy="$strategy"
    fi
    write_case_contract \
        "$case_dir" "$case_id" "$strategy" "$magic" "${seed_kinds[$index]}" "${halt_rules[$index]}" \
        "$halt_strategy" "${halt_reason_kinds[$index]}" "${halt_reason_values[$index]}" \
        "${reject_reason_kinds[$index]}" "${reject_reason_values[$index]}"
done

jq -n '
    {
      schema:"qkt-live-risk-stateful-deferred-v1",
      status:"deferred-not-passed",
      cases:[
        {id:"margin-floor",why:"flat accounts approve margin level zero by design",requiredFixture:"owned live exposure with deterministic venue margin-level drift"}
      ]
    }
' > "$output/stateful-deferred.json"

jq -n \
    --arg id "$suite_id" \
    --arg gatewayUrl "$gateway_url" \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" \
    --arg qktCommit "$git_sha" \
    --argjson qktDirty "$git_dirty" \
    --slurpfile gdl "$output/cases/global-daily-loss/expected.json" \
    --slurpfile sdl "$output/cases/strategy-daily-loss/expected.json" \
    --slurpfile gdd "$output/cases/global-drawdown/expected.json" \
    --slurpfile lst "$output/cases/loss-streak/expected.json" \
    --slurpfile deferred "$output/stateful-deferred.json" '
    {
      schema:"qkt-live-stateful-risk-matrix-v1",
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
        containers:4,
        parallel:true,
        financiallyReadOnly:true,
        fixedIntentQty:"0.01",
        requiredGatewayMutations:0,
        requiredFills:0,
        barsObserved:true,
        restoredStateTripsLiveHalts:true
      },
      synchronization:{
        deployOnOddUtcMinute:true,
        triggerOnNextEvenUtcMinute:true
      },
      cases:[$gdl[0],$sdl[0],$gdd[0],$lst[0]],
      deferredStateful:$deferred[0],
      claims:{
        dailyLossPassed:false,
        drawdownPassed:false,
        lossStreakPassed:false,
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
