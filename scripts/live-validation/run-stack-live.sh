#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-stack-live.sh --output DIR --id ID --variant VARIANT --gateway-url URL \
  --expected-login N --expected-server NAME --magic N \
  [--symbol EURUSD] [--min-legs N] [--hold-seconds N] [--timeout-seconds N] [--http-timeout-ms N] \
  [--max-position-size LOTS] [--cli PATH] \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01

Runs ONE stacking strategy against the local demo gateway with real 0.01-lot orders, lets the
strategy's own rule close everything, then captures the golden bundle so
compare-stack-replay.sh can hold the live fills to the tick and bar replays.

Variants (every entry is 0.01 lots; every exit is strategy-owned):
  dependent_up      BUY, STACK 10 SPACING 2 ABOVE  -- layers add as price moves for the position
  dependent_down    BUY, STACK 10 SPACING 2 BELOW  -- layers add as price moves against it
  independent_mfe   BUY + five STACK_AT MFE tiers, each with its own bracket
  burst_10_market   ten independent bracketed BUYs placed on the same tick
  scalp_reentry     3-pip target scalp that re-enters after each close, up to three cycles
  layer_list_limit  BUY, then nine resting LIMIT layers below the seed (a bid ladder)

Live execution requires both --arm I_UNDERSTAND_DEMO_ORDER_0.01 and
QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY, plus QKT_BROKER_API_KEY in the environment.
The account must be flat at start; the run refuses otherwise. Positions the strategy has not
closed by --timeout-seconds are flattened by the operator and the run is marked as such.
EOF
}

fail() {
    printf 'run-stack-live: %s\n' "$1" >&2
    exit 1
}

output=""; scenario_id=""; variant=""; gateway_url=""; expected_login=""; expected_server=""
magic=""; symbol="EURUSD"; min_legs=2; hold_seconds=120; timeout_seconds=300; arm=""; http_timeout_ms=20000
# Aggregate position cap for the strategy. Every leg of a burst or stack counts toward it, so a
# run of N legs at 0.01 lots needs at least N/100 here or the tail is rejected pre-trade.
max_position_size="0.25"
cli="$repo_root/build/install/qkt/bin/qkt"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --id) scenario_id="${2:-}"; shift 2 ;;
        --variant) variant="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --magic) magic="${2:-}"; shift 2 ;;
        --symbol) symbol="${2:-}"; shift 2 ;;
        --min-legs) min_legs="${2:-}"; shift 2 ;;
        --hold-seconds) hold_seconds="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --http-timeout-ms) http_timeout_ms="${2:-}"; shift 2 ;;
        --max-position-size) max_position_size="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[[ "$scenario_id" =~ ^[a-z][a-z0-9_]{2,47}$ ]] || fail "--id must be a lowercase DSL identifier"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]+$ ]] || fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
[[ "$expected_login" =~ ^[0-9]+$ ]] || fail "--expected-login must be an integer"
[ -n "$expected_server" ] || fail "--expected-server is required"
[[ "$magic" =~ ^[0-9]+$ ]] || fail "--magic must be an integer"
[[ "$symbol" =~ ^(EURUSD|GBPUSD)$ ]] || fail "--symbol must be EURUSD or GBPUSD (the reviewed 0.00001-point set)"
[[ "$min_legs" =~ ^[0-9]+$ ]] && [ "$min_legs" -ge 1 ] || fail "--min-legs must be >= 1"
[[ "$hold_seconds" =~ ^[0-9]+$ ]] && [ "$hold_seconds" -ge 30 ] || fail "--hold-seconds must be >= 30"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && [ "$timeout_seconds" -ge 120 ] && [ "$timeout_seconds" -le 900 ] || fail "--timeout-seconds must be in 120..900"
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] || fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"
for tool in jq curl flock unzip sha256sum; do command -v "$tool" >/dev/null || fail "$tool is required"; done

venue_symbol="${symbol}m"
strategy_name="${scenario_id}_${variant}"
qkt_version="$("$cli" --version | head -n 1)"
qkt_commit="$(printf '%s\n' "$qkt_version" | sed -nE 's/.*\(([0-9a-f]{8,40})\).*/\1/p')"
[ -n "$qkt_commit" ] || fail "CLI version line did not expose a git sha: $qkt_version"

gateway_get() {
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url$1"
}

# ---------------------------------------------------------------- strategy text per variant
# Distances are in price units on a 0.00001-point symbol. Protective brackets are wide on
# purpose: the run measures stacking mechanics and parity, and the strategy's own timed exit is
# what takes every leg out, so a venue-side stop or target firing first would only blur the
# lifecycle under test. TRADES.today keeps each run to its intended number of cycles.
strategy_body() {
    case "$variant" in
        dependent_up)
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0
    THEN BUY x SIZING 0.01
         STACK 10 SPACING 0.00002 ABOVE WITHIN 3m
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CANCEL x ; CLOSE x
EOF
            ;;
        dependent_down)
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0
    THEN BUY x SIZING 0.01
         STACK 10 SPACING 0.00002 BELOW WITHIN 3m
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CANCEL x ; CLOSE x
EOF
            ;;
        independent_mfe)
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0
    THEN BUY x SIZING 0.01
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         STACK_AT MFE >= 0.00001 WITHIN 3m SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         STACK_AT MFE >= 0.00002 WITHIN 3m SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         STACK_AT MFE >= 0.00003 WITHIN 3m SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         STACK_AT MFE >= 0.00004 WITHIN 3m SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         STACK_AT MFE >= 0.00005 WITHIN 3m SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CANCEL x ; CLOSE x
EOF
            ;;
        burst_10_market)
            {
                printf '    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0\n    THEN '
                for i in $(seq 1 10); do
                    [ "$i" -eq 1 ] || printf '       ; '
                    printf 'BUY x SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }\n'
                done
                printf '\n    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= %s\n    THEN CLOSE x\n' "$hold_seconds"
            }
            ;;
        scalp_reentry)
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today < 3
    THEN BUY x SIZING 0.01
         BRACKET { STOP LOSS BY 0.0010, TAKE PROFIT BY 0.0003 }

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CLOSE x
EOF
            ;;
        times_burst_30)
            # The TIMES clause under live fire: one condition, thirty independent bracketed
            # orders. Each is its own ticket with its own protection, and the strategy's timed
            # rule takes the whole burst out. max_round_trips_10m is already 0 in this scenario's
            # config, which is what a burst strategy needs -- see docs/reference/dsl/times.md.
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0
    THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 } TIMES 30

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CLOSE x
EOF
            ;;
        times_dynamic_count)
            # The count decided at fire time rather than written down: one leg per whole point
            # of ATR, capped. Proves an expression-valued count survives the live path.
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0 AND atr(x.candle, 5) IS NOT NULL
    THEN BUY x SIZING 0.01 BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }
         TIMES min(2 + floor(atr(x.candle, 5) * 10000), 6)

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CLOSE x
EOF
            ;;
        layer_list_limit)
            cat <<EOF
    WHEN POSITION.x = 0 AND OPEN_ORDERS.x = 0 AND TRADES.today = 0
    THEN BUY x STACK [
           0.01,
           0.01 LIMIT AT entry - 0.00002,
           0.01 LIMIT AT entry - 0.00004,
           0.01 LIMIT AT entry - 0.00006,
           0.01 LIMIT AT entry - 0.00008,
           0.01 LIMIT AT entry - 0.00010,
           0.01 LIMIT AT entry - 0.00012,
           0.01 LIMIT AT entry - 0.00014,
           0.01 LIMIT AT entry - 0.00016,
           0.01 LIMIT AT entry - 0.00018
         ]
         BRACKET { STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060 }

    WHEN POSITION.x > 0 AND POSITION.x.holding_duration >= $hold_seconds
    THEN CANCEL x ; CLOSE x
EOF
            ;;
        *) fail "unknown --variant $variant" ;;
    esac
}

# ---------------------------------------------------------------- gateway preconditions
mkdir -m 700 "$output"
mkdir -m 700 "$output/evidence" "$output/logs" "$output/state" "$output/strategies" "$output/strategies/armed" "$output/data"
evidence="$output/evidence"

gateway_get /health > "$evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"
gateway_get /account > "$evidence/gateway-account-initial.json"
jq -e --argjson login "$expected_login" --arg server "$expected_server" \
    '.login == $login and .server == $server and .trade_mode == 0' "$evidence/gateway-account-initial.json" >/dev/null ||
    fail "gateway account is not the expected demo account"
gateway_get "/get_positions?magic=$magic" > "$evidence/positions-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-initial.json" >/dev/null ||
    fail "magic $magic already has an open position; refusing to start"
gateway_get "/symbol_info/$venue_symbol" > "$evidence/symbol-info.json"
jq -e '(.data // .) | .point == 0.00001 and .volume_min == 0.01' "$evidence/symbol-info.json" >/dev/null ||
    fail "$venue_symbol is not the reviewed 0.00001-point, 0.01-lot instrument"
starting_balance="$(jq -r '.balance | tostring' "$evidence/gateway-account-initial.json")"
leverage="$(jq -r '.leverage' "$evidence/gateway-account-initial.json")"

# ---------------------------------------------------------------- scenario files
# Limits are sized for a stack, not for one trade: every layer is a separate 0.01-lot entry fill
# on the same symbol, and the standard single-trade scenario limits (position size 0.01, one
# trade a day, two round trips in ten minutes) would reject layer two onward and report a stack
# the engine never got to build. Nothing here loosens what protects the account itself.
cat > "$output/qkt.config.yaml" <<EOF
source: local
data_root: "$output/data"
starting_balance: "$starting_balance"
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
    http_timeout_ms: $http_timeout_ms
    retry_attempts: 3

risk:
  max_daily_loss: "60"
  max_order_qty: "0.25"
  max_order_notional: "40000"
  price_collar_pct: "1"
  margin_floor_pct: "500"
  measured_usage_hours: "0"
  max_round_trips_10m: 0
  max_broker_rejections_1m: 3
  max_drawdown_pct: "0.5"
  max_daily_drawdown_pct: "0.25"
  live_equity_basis: venue
  per_strategy:
    $strategy_name:
      max_daily_loss: "40"
      max_position_size: "$max_position_size"
      max_open_positions: 1
      max_trades_per_day: 60
      max_drawdown_pct: "0.25"
      max_daily_drawdown_pct: "0.10"

book_risk:
  capital: "$starting_balance"
  limits:
    max_gross_exposure: "0.60"
    max_net_exposure: "0.60"
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

{
    printf 'STRATEGY %s VERSION 1\n\nSYMBOLS\n    x = EXNESS:%s EVERY 1m WARMUP 10 BARS\n\nRULES\n' "$strategy_name" "$symbol"
    strategy_body
} > "$output/strategies/armed/$strategy_name.qkt"
"$cli" parse "$output/strategies/armed/$strategy_name.qkt" > "$evidence/parse.json" 2>&1 ||
    fail "stack strategy did not parse: $(head -c 400 "$evidence/parse.json")"

created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n --arg id "$scenario_id" --arg variant "$variant" --arg strategy "$strategy_name" --arg symbol "EXNESS:$symbol" \
    --arg venueSymbol "$venue_symbol" --arg balance "$starting_balance" --argjson leverage "$leverage" \
    --argjson login "$expected_login" --arg server "$expected_server" --argjson magic "$magic" \
    --argjson minLegs "$min_legs" --argjson holdSeconds "$hold_seconds" '{
      schema: "qkt-live-stack-scenario-v1",
      scenarioId: $id, variant: $variant, strategy: $strategy,
      account: {login: $login, server: $server, tradeMode: "demo", currency: "USD", leverage: $leverage, startingBalance: $balance},
      armedScenario: {symbol: $symbol, venueSymbol: $venueSymbol, expectedContractSize: "100000",
                      quantityLots: "0.01", minimumLegs: $minLegs, holdSeconds: $holdSeconds,
                      maximumEntryAnchorDriftPoints: 80, exitOwner: "strategy"}
    }' > "$output/expected.json"
jq -n --arg id "$scenario_id" --arg createdAt "$created_at" --arg commit "$qkt_commit" --arg gw "$gateway_url" --argjson magic "$magic" '{
      schema: "qkt-live-stack-scenario-v1", scenarioId: $id, createdAt: $createdAt, qktCommit: $commit, qktDirty: false,
      gatewayUrl: $gw, magic: $magic, credentialsStored: false, executionState: "prepared"
    }' > "$output/scenario.json"
( cd "$output" && find . -type f ! -name SHA256SUMS ! -path './evidence/*' ! -path './logs/*' ! -path './state/*' ! -path './data/*' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS )

# ---------------------------------------------------------------- live lock, daemon, deploy
live_lock_path="/var/tmp/qkt-validation/LIVE-LOCK-${expected_server}-${expected_login}"
mkdir -p "$(dirname "$live_lock_path")"
exec {live_lock_fd}> "$live_lock_path"
flock -n "$live_lock_fd" || fail "live lock is already held at $live_lock_path"
printf 'owner=run-stack-live\ncase=%s\nscenario=%s\nstarted_at_utc=%s\npid=%s\n' "$strategy_name" "$output" "$created_at" "$$" >&"$live_lock_fd"

daemon_pid=""
operator_flattened=false
cleanup() {
    local status=$?
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" kill "$strategy_name" --flatten --state-dir "$output/state" --json > "$evidence/emergency-flatten.json" 2>&1 || true
        "$cli" daemon stop --state-dir "$output/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
    fi
    exit "$status"
}
trap cleanup EXIT

run_started_ms="$(date +%s%3N)"
QKT_STATE_DIR="$output/state" "$cli" daemon start --config "$output/qkt.config.yaml" --state-dir "$output/state" \
    > "$output/logs/daemon.log" 2>&1 &
daemon_pid=$!
ready=false
for _ in $(seq 1 60); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited before becoming ready"
    if "$cli" daemon status --state-dir "$output/state" --json > "$evidence/daemon-status-initial.json" 2>/dev/null; then ready=true; break; fi
    sleep 1
done
$ready || fail "daemon did not become ready within 60 seconds"

"$cli" deploy "$output/strategies/armed/$strategy_name.qkt" --as "$strategy_name" --state-dir "$output/state" --json > "$evidence/deploy.json"
jq -e --arg name "$strategy_name" '.name == $name and .state == "running"' "$evidence/deploy.json" >/dev/null ||
    fail "armed strategy did not enter running state"

# ---------------------------------------------------------------- watch the stack build and unwind
max_open=0
saw_open=false
flat_after_open=false
samples="$evidence/positions-samples.jsonl"
: > "$samples"
for second in $(seq 1 "$timeout_seconds"); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during the run"
    if gateway_get "/get_positions?magic=$magic" > "$evidence/positions-latest.json" 2>/dev/null; then
        count="$(jq '.data | length' "$evidence/positions-latest.json")"
        jq -c --argjson t "$second" --argjson n "$count" '{t:$t, open:$n, tickets:[.data[].ticket]}' "$evidence/positions-latest.json" >> "$samples"
        [ "$count" -le "$max_open" ] || max_open="$count"
        [ "$count" -eq 0 ] || saw_open=true
        if $saw_open && [ "$count" -eq 0 ] && [ "$second" -gt "$hold_seconds" ]; then flat_after_open=true; break; fi
    fi
    if grep -q 'Order rejected:' "$output/logs/daemon.log"; then
        cp "$output/logs/daemon.log" "$evidence/daemon-log-at-rejection.log"
    fi
    sleep 1
done

if ! $flat_after_open; then
    operator_flattened=true
    "$cli" kill "$strategy_name" --flatten --state-dir "$output/state" --json > "$evidence/operator-flatten.json" 2>&1 || true
    for _ in $(seq 1 60); do
        gateway_get "/get_positions?magic=$magic" > "$evidence/positions-latest.json"
        [ "$(jq '.data | length' "$evidence/positions-latest.json")" -eq 0 ] && break
        sleep 1
    done
fi

"$cli" status "$strategy_name" --state-dir "$output/state" > "$evidence/strategy-status-final.json" 2>&1 || true
"$cli" stop "$strategy_name" --state-dir "$output/state" --json > "$evidence/stop-strategy.json"
"$cli" daemon stop --state-dir "$output/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid" || true
daemon_pid=""

gateway_get "/get_positions?magic=$magic" > "$evidence/positions-final.json"
gateway_get "/orders?magic=$magic" > "$evidence/orders-final.json"
jq -e '(.data | length) == 0' "$evidence/positions-final.json" >/dev/null || fail "demo account still has a $magic position after the run"
jq -e '(.data | length) == 0' "$evidence/orders-final.json" >/dev/null || fail "demo account still has a $magic pending order after the run"
gateway_get /account > "$evidence/gateway-account-final.json"

# ---------------------------------------------------------------- golden capture and evidence
"$cli" golden capture --session "$strategy_name" --state-dir "$output/state" --out "$evidence/golden.zip" > "$evidence/golden-capture.log"
unzip -p "$evidence/golden.zip" manifest.json > "$evidence/golden-manifest.json"
jq -e --arg strategy "$strategy_name" --arg commit "$qkt_commit" \
    '.kind == "MT5_GOLDEN_CAPTURE" and .session == $strategy and (.captureGitSha as $c | ($commit | startswith($c))) and .counts.fills > 0' \
    "$evidence/golden-manifest.json" >/dev/null || fail "golden capture does not match the completed live session"

engine_entry="$(jq -er '.entries[] | select(.path | startswith("engine/")) | .path' "$evidence/golden-manifest.json")"
transport_entry="$(jq -er '.entries[] | select(.path | startswith("gateway/")) | .path' "$evidence/golden-manifest.json")"
unzip -p "$evidence/golden.zip" "$engine_entry" > "$evidence/engine.jsonl"
unzip -p "$evidence/golden.zip" "$transport_entry" > "$evidence/gateway-transport.jsonl"

fills_json="$(jq -sc --arg strategy "$strategy_name" '
    [.[] | select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled" and .strategyId == $strategy)
         | {orderId, side: .fill.side, quantity: .fill.quantity, price: .fill.price, ticket: .fill.brokerOrderId, ts,
            exitReason: (.payload | capture("exitReason=(?<r>[^,)]+)") .r)}]' "$evidence/engine.jsonl")"
transport_counts="$(jq -sc '{
    orderPosts: [.[] | select(.method == "POST" and .path == "/order")] | length,
    closePosts: [.[] | select(.method == "POST" and (.path | startswith("/close_position")))] | length,
    modifyPosts: [.[] | select(.method == "POST" and (.path | startswith("/modify_sl_tp")))] | length,
    cancelPosts: [.[] | select(.method == "POST" and (.path | test("cancel|delete_order")))] | length,
    rejected: [.[] | select(.method == "POST" and .responseCode != 200)] | length
  }' "$evidence/gateway-transport.jsonl")"
rejections="$(jq -sc --arg strategy "$strategy_name" '[.[] | select(.eventType == "com.qkt.events.RiskRejectedEvent" and .strategyId == $strategy) | .reason]' "$evidence/engine.jsonl")"

entry_fills="$(printf '%s' "$fills_json" | jq '[.[] | select(.exitReason == "null")] | length')"
exit_fills="$(printf '%s' "$fills_json" | jq '[.[] | select(.exitReason != "null")] | length')"
entry_qty="$(printf '%s' "$fills_json" | jq '[.[] | select(.exitReason == "null") | .quantity | tonumber] | add // 0')"
exit_qty="$(printf '%s' "$fills_json" | jq '[.[] | select(.exitReason != "null") | .quantity | tonumber] | add // 0')"
initial_balance="$(jq -r '.balance' "$evidence/gateway-account-initial.json")"
final_balance="$(jq -r '.balance' "$evidence/gateway-account-final.json")"

status="passed"
[ "$max_open" -ge "$min_legs" ] || status="too_few_legs"
$operator_flattened && status="flattened_by_operator"
[ "$entry_fills" -gt 0 ] || status="no_entry_fill"
jq -n --arg status "$status" --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg qktVersion "$qkt_version" --arg commit "$qkt_commit" \
    --arg strategy "$strategy_name" --arg variant "$variant" --argjson magic "$magic" \
    --argjson maxOpen "$max_open" --argjson minLegs "$min_legs" --argjson operatorFlattened "$operator_flattened" \
    --argjson entryFills "$entry_fills" --argjson exitFills "$exit_fills" --argjson entryQty "$entry_qty" --argjson exitQty "$exit_qty" \
    --argjson fills "$fills_json" --argjson transport "$transport_counts" --argjson rejections "$rejections" \
    --arg initialBalance "$initial_balance" --arg finalBalance "$final_balance" \
    --arg goldenSha "$(sha256sum "$evidence/golden.zip" | awk '{print $1}')" --slurpfile manifest "$evidence/golden-manifest.json" '{
      schema: "qkt-live-stack-run-v1", status: $status, finishedAt: $finishedAt, qktVersion: $qktVersion, qktCommit: $commit, qktDirty: false,
      strategy: $strategy, variant: $variant, magic: $magic,
      stack: {maxConcurrentPositions: $maxOpen, minimumLegsRequired: $minLegs, entryFills: $entryFills, exitFills: $exitFills,
              entryQuantity: $entryQty, exitQuantity: $exitQty, netFlat: (($entryQty - $exitQty) | fabs) < 0.0000001,
              strategyOwnedExit: ($operatorFlattened | not)},
      fills: $fills, transport: $transport, riskRejections: $rejections,
      account: {initialBalance: $initialBalance, finalBalance: $finalBalance},
      golden: {sha256: $goldenSha, counts: $manifest[0].counts}
    }' > "$evidence/result.json"
( cd "$output" && find . -type f ! -name RUN-SHA256SUMS ! -path './state/*' ! -path './data/*' -print0 | sort -z | xargs -0 sha256sum > RUN-SHA256SUMS )
jq -c '{status, stack, transport, riskRejections}' "$evidence/result.json"
[ "$status" = "passed" ]
