#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
    printf 'run-insights-attribution: %s\n' "$1" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: run-insights-attribution.sh --scenario DIR --insights-image IMAGE
       [--cli PATH] [--port N] [--timeout-seconds N]
       [--arm I_UNDERSTAND_DEMO_ORDER_0.01]
       run-insights-attribution.sh --scenario DIR --insights-image IMAGE --verify-only

Live execution also requires QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY and
QKT_BROKER_API_KEY. It runs a read-only M1/M5 sibling and one bounded 0.01-lot
strategy, interrupts the isolated Insights collector, verifies durable replay and
ticket attribution, and waits for the strategy's own second DSL decision to close it.
EOF
}

scenario=""
insights_image=""
cli="$repo_root/build/install/qkt/bin/qkt"
port=18420
timeout_seconds=360
arm=""
verify_only=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --insights-image) insights_image="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --port) port="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$insights_image" ] || fail "--insights-image is required"
scenario="$(realpath "$scenario")"
[ -d "$scenario" ] || fail "scenario directory does not exist: $scenario"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || fail "--port must be in 1024..65535"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] && [ "$timeout_seconds" -ge 180 ] && [ "$timeout_seconds" -le 600 ] ||
    fail "--timeout-seconds must be in 180..600"
for command in cmp find git jq realpath rg sha256sum sort stat; do
    command -v "$command" >/dev/null || fail "$command is required"
done
bash "$repo_root/scripts/live-validation/run-readonly.sh" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t readonly_sources < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
mapfile -t armed_sources < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort)
[ "${#readonly_sources[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
[ "${#armed_sources[@]}" -eq 1 ] || fail "expected exactly one armed strategy"
readonly_name="$(basename "${readonly_sources[0]}" .qkt)"
armed_name="$(basename "${armed_sources[0]}" .qkt)"
armed_symbol="$(jq -er '.armedScenario.symbol' "$scenario/expected.json")"
case "$armed_symbol" in
    EXNESS:EURUSD) venue_symbol=EURUSDm ;;
    EXNESS:GBPUSD) venue_symbol=GBPUSDm ;;
    *) fail "armed scenario symbol is outside the reviewed FX set: $armed_symbol" ;;
esac
grep -F 'EVERY 1m' "${readonly_sources[0]}" >/dev/null || fail "read-only strategy is missing M1 bars"
grep -F 'EVERY 5m' "${readonly_sources[0]}" >/dev/null || fail "read-only strategy is missing M5 bars"
grep -F 'SIZING 0.01' "${armed_sources[0]}" >/dev/null || fail "armed strategy is not fixed at 0.01 lots"
grep -F 'TRADES.today = 0' "${armed_sources[0]}" >/dev/null || fail "armed strategy does not prevent re-entry"
grep -F 'POSITION.asset1.holding_duration >= 1' "${armed_sources[0]}" >/dev/null ||
    fail "armed strategy does not contain the reviewed DSL close delay"
grep -F 'THEN CLOSE asset1' "${armed_sources[0]}" >/dev/null || fail "armed strategy has no strategy-owned close"
jq -e --arg strategy "$armed_name" --arg symbol "$armed_symbol" '
    .schema == "qkt-live-validation-expected-v2" and
    .account.tradeMode == "demo" and .account.currency == "USD" and
    .safety.maximumLots == "0.01" and .safety.maximumOpenPositions == 1 and
    .safety.maximumTradesPerDay == 1 and
    .armedScenario.strategy == $strategy and .armedScenario.symbol == $symbol and
    (.armedScenario.streams | map(.timeframe)) == ["1m", "5m"] and
    all(.armedScenario.streams[]; .symbol == $symbol and .warmupBars == 10) and
    .armedScenario.quantityLots == "0.01" and .armedScenario.maximumEntries == 1 and
    .armedScenario.maximumExits == 1 and .armedScenario.minimumHoldingSeconds == 1 and
    .armedScenario.stopDistance == "0.0030" and .armedScenario.takeProfitDistance == "0.0060"
' "$scenario/expected.json" >/dev/null || fail "expected metadata is not the bounded DSL round trip"

if $verify_only; then
    printf 'verified %s on %s with sibling %s and Insights image %s\n' \
        "$armed_name" "$armed_symbol" "$readonly_name" "$insights_image"
    exit 0
fi

for command in awk curl docker openssl sqlite3; do
    command -v "$command" >/dev/null || fail "$command is required"
done
docker image inspect "$insights_image" >/dev/null 2>&1 || fail "Insights image does not exist: $insights_image"
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for jvm_env in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [ -z "${!jvm_env:-}" ] || fail "$jvm_env must be unset; this run does not restrict the JVM"
done
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
[ "$(jq -er '.qktCommit' "$scenario/scenario.json")" = "$qkt_commit" ] ||
    fail "scenario was not freshly prepared from the current QKT commit"
jq -e '.qktDirty == false' "$scenario/scenario.json" >/dev/null ||
    fail "scenario must be freshly prepared from a clean checkout"
qkt_short="${qkt_commit:0:8}"
[[ "$("$cli" --version)" == *"($qkt_short)"* ]] || fail "QKT CLI is not built from $qkt_short"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"
case "$(jq -r '.gatewayUrl' "$scenario/scenario.json")" in
    http://127.0.0.1:*|http://localhost:*) ;;
    *) fail "scenario gateway must be localhost" ;;
esac

evidence="$scenario/evidence"
mkdir -p "$evidence/insights-data" "$evidence/live-state-samples"
config="$scenario/qkt.insights.config.yaml"
scenario_id="$(jq -r '.scenarioId' "$scenario/scenario.json")"
instance="qkt-live-$scenario_id"
magic="$(jq -r '.magic' "$scenario/scenario.json")"
gateway_url="$(jq -r '.gatewayUrl' "$scenario/scenario.json")"
container="qkt-insights-$(printf '%s' "$scenario_id" | tr -c 'A-Za-z0-9_.-' '_')"
ingest_token="$(openssl rand -hex 32)"
admin_password="$(openssl rand -hex 24)"
session_secret="$(openssl rand -hex 32)"
cookie="$(mktemp)"
daemon_pid=""
owned_ticket=""
cleanup_running=false
broker_mutation_possible=false

sed '$d' "$scenario/qkt.config.yaml" | sed '$d' > "$config"
cat >> "$config" <<EOF
insights:
  enabled: true
  url: "http://127.0.0.1:$port/ingest"
  instance_id: "$instance"
  token: "\${QKT_INSIGHTS_TOKEN}"
  events: [trade, order, signal, risk, position, log, state, deal, lifecycle]
  flush_interval_ms: 100
  batch_size: 50
  queue_capacity: 10000
  journal_enabled: true
  journal_dir: "$scenario/state/state/insights-journal"
  state_poll_ms: 1000
  deal_backfill_days: 1
EOF

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url$path"
}

cleanup() {
    $cleanup_running && return
    cleanup_running=true
    set +e
    if $broker_mutation_possible; then
        local positions orders
        positions="$(gateway_get "/get_positions?magic=$magic" 2>/dev/null)"
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot close "$armed_symbol" --ticket "$ticket" --config "$config" --json \
                > "$evidence/emergency-close-$ticket.json" 2>/dev/null || true
        done < <(jq -r '.data[]?.ticket' <<<"$positions")
        orders="$(gateway_get "/orders?magic=$magic" 2>/dev/null)"
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot cancel "$armed_symbol" --order "$ticket" --config "$config" --json \
                > "$evidence/emergency-cancel-$ticket.json" 2>/dev/null || true
        done < <(jq -r '.orders[]?.ticket' <<<"$orders")
    fi
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null
        wait "$daemon_pid" 2>/dev/null
    fi
    docker container rm --force "$container" >/dev/null 2>&1
    [ ! -e "$cookie" ] || unlink "$cookie"
}
trap cleanup EXIT

docker container rm --force "$container" >/dev/null 2>&1 || true
docker run -d --name "$container" --network host \
    -e PORT="$port" -e INSIGHTS_DB=/data/insights.db \
    -e INGEST_TOKEN="$ingest_token" -e ADMIN_USERNAME=admin \
    -e ADMIN_PASSWORD="$admin_password" -e SESSION_SECRET="$session_secret" \
    -v "$evidence/insights-data:/data" "$insights_image" run > "$evidence/insights-container-id.txt"
for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$port/healthz" > "$evidence/insights-health-initial.json" 2>/dev/null && break
    sleep 1
done
jq -e '.ok == true and .mode == "run"' "$evidence/insights-health-initial.json" >/dev/null || fail "Insights did not become healthy"

# Reject an image that predates the causal execution contract before touching the broker.
probe_instance="qkt-insights-contract-probe-$scenario_id"
probe_ts="$(date +%s%3N)"
jq -n --arg instance "$probe_instance" --arg ts "$probe_ts" '
    ($ts | tonumber) as $time |
    {instanceId:$instance,events:[
      {v:1,instanceId:$instance,id:"probe-rule",seq:1,ts:$time,strategyId:"probe",
       type:"decision.rule_evaluated",payload:{decisionId:"probe-decision",ruleId:"probe#0",
       strategyFingerprint:("a"*64),ruleFingerprint:("b"*64),conditionFingerprint:("c"*64),
       conditionResult:true,alias:"asset1",broker:"EXNESS",timeframe:"1m",signalCount:1,
       candle:{symbol:"EXNESS:EURUSD",startTimeMs:($time-60000),endTimeMs:$time,open:1,high:1,low:1,close:1,volume:1}}},
      {v:1,instanceId:$instance,id:"probe-link",seq:2,ts:($time+1),strategyId:"probe",
       type:"decision.order_linked",payload:{decisionId:"probe-decision",ruleId:"probe#0",signalIndex:0,orderId:"probe-order"}},
      {v:1,instanceId:$instance,id:"probe-accounted",seq:3,ts:($time+2),strategyId:"probe",
       type:"fill.accounted",payload:{orderId:"probe-order",symbol:"EXNESS:EURUSD",fillSliceId:"probe-order:3",
       sourceFillSequenceId:3,cumulativeFilled:0.01,modeledCommissionAccount:0,venueCostsAccount:0,
       totalCostsAccount:0,accountNativeRealized:0,strategyNativeRealized:0,nativeCurrency:"USD",
       grossAccountRealized:0,grossStrategyAccountRealized:0,accountCurrency:"USD",netAccountRealized:0,
       netStrategyAccountRealized:0,reducedExposure:false,partial:false}},
      {v:1,instanceId:$instance,id:"probe-order-submit",seq:99,ts:($time+3),strategyId:"probe",
       type:"order.submit",payload:{orderId:"probe-restart-order",orderType:"Market",symbol:"EXNESS:EURUSD",side:"BUY",qty:0.01}},
      {v:1,instanceId:$instance,id:"probe-order-filled",seq:1,ts:($time+4),strategyId:"probe",
       type:"order.filled",payload:{orderId:"probe-restart-order",brokerOrderId:"probe-broker",symbol:"EXNESS:EURUSD",price:1,qty:0.01}},
      {v:1,instanceId:$instance,id:"probe-position-owner",seq:2,ts:($time+5),type:"state.positions",
       payload:{broker:"EXNESS",positions:[{ticket:"probe-ticket",symbol:"EXNESS:EURUSD",side:"BUY",qty:0.01,
       entryPrice:1,currentPrice:1,profit:0,strategyId:"probe"}]}},
      {v:1,instanceId:$instance,id:"probe-position-sibling",seq:3,ts:($time+6),type:"state.positions",
       payload:{broker:"EXNESS",positions:[{ticket:"probe-ticket",symbol:"EXNESS:EURUSD",side:"BUY",qty:0.01,
       entryPrice:1,currentPrice:1,profit:0,strategyId:null}]}}
    ]}
' > "$evidence/collector-contract-probe-request.json"
curl --silent --show-error --fail-with-body \
    -H "Authorization: Bearer $ingest_token" -H 'Content-Type: application/json' \
    --data-binary @"$evidence/collector-contract-probe-request.json" \
    "http://127.0.0.1:$port/ingest" > "$evidence/collector-contract-probe-response.json" ||
    fail "Insights image rejected the causal execution contract"
jq -e '.accepted == 7 and .ack.received == 7 and (.ack.acknowledgedIds | length) == 7' \
    "$evidence/collector-contract-probe-response.json" >/dev/null || fail "Insights causal contract probe was not fully accepted"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from events where instance_id='$probe_instance' and type in ('decision.rule_evaluated','decision.order_linked','fill.accounted');")" -eq 3 ] ||
    fail "Insights did not durably retain the causal contract probe"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from orders where instance_id='$probe_instance' and order_id='probe-restart-order' and state='FILLED';")" -eq 1 ] ||
    fail "Insights does not fold lifecycle events after a producer sequence restart"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from positions_current where instance_id='$probe_instance' and ticket='probe-ticket' and strategy_id='probe';")" -eq 1 ] ||
    fail "Insights does not preserve known position attribution across sibling state polls"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from ingest_observations where instance_id='$probe_instance' and kind in ('gap','regression');")" -eq 0 ] ||
    fail "Insights treats producer-local sequences as global delivery continuity"
unlink "$evidence/collector-contract-probe-request.json"

gateway_get /account > "$evidence/gateway-account-initial.json"
gateway_get /get_positions > "$evidence/positions-account-initial.json"
gateway_get /orders > "$evidence/orders-account-initial.json"
expected_login="$(jq -r '.account.login' "$scenario/expected.json")"
expected_server="$(jq -r '.account.server' "$scenario/expected.json")"
expected_leverage="$(jq -r '.account.leverage' "$scenario/expected.json")"
expected_balance="$(jq -r '.account.startingBalance' "$scenario/expected.json")"
jq -e --argjson login "$expected_login" --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" --arg balance "$expected_balance" '
    .login == $login and .server == $server and .leverage == $leverage and
    .balance == ($balance | tonumber) and .equity == .balance and .margin == 0 and
    .trade_mode == 0 and .currency == "USD" and .trade_allowed == true and .trade_expert == true
' "$evidence/gateway-account-initial.json" >/dev/null || fail "account does not match the prepared demo allowlist"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-account-initial.json" >/dev/null || fail "account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$evidence/orders-account-initial.json" >/dev/null || fail "account has a pending order"

export QKT_INSIGHTS_TOKEN="$ingest_token" QKT_STATE_DIR="$scenario/state"
QKT_LATENCY_TRACKING=1 "$cli" daemon start --config "$config" --state-dir "$scenario/state" \
    --load-dir "$scenario/strategies/readonly" > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!
ready=false
for _ in $(seq 1 90); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during startup"
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-readonly.json" 2>/dev/null; then ready=true; break; fi
    sleep 1
done
$ready || fail "daemon did not become ready"

registered=false
for _ in $(seq 1 90); do
    count="$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from strategies where instance_id='$instance' and strategy_id='$readonly_name';" 2>/dev/null || printf 0)"
    if [ "$count" = 1 ]; then registered=true; break; fi
    sleep 1
done
$registered || fail "read-only strategy did not reach Insights"

# Cross a real M1 close while both M1 and M5 streams remain active, then deploy near
# the start of a minute so the collector has time to recover and observe the open leg.
sleep 70
mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no audit journal"
closed_candles="$(jq -r 'select(.eventType == "com.qkt.events.CandleEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$closed_candles" -ge 1 ] || fail "read-only strategy produced no closed bar"
for _ in $(seq 1 70); do
    second="$(date -u +%S)"
    [ "$second" -le 10 ] && break
    sleep 1
done
[ "$(date -u +%S)" -le 12 ] || fail "could not align the bounded deployment after an M1 boundary"

docker stop "$container" > "$evidence/insights-stop-outage.txt"
broker_mutation_possible=true
"$cli" deploy "${armed_sources[0]}" --as "$armed_name" --state-dir "$scenario/state" --json > "$evidence/deploy-armed.json"
position_seen=false
for _ in $(seq 1 180); do
    gateway_get "/get_positions?magic=$magic" > "$evidence/position-open.json"
    count="$(jq '.data | length' "$evidence/position-open.json")"
    [ "$count" -le 1 ] || fail "armed strategy created more than one position"
    if [ "$count" -eq 1 ]; then position_seen=true; break; fi
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for fill"
    sleep 1
done
$position_seen || fail "bounded position did not open"
owned_ticket="$(jq -r '.data[0].ticket' "$evidence/position-open.json")"
jq -e --argjson magic "$magic" --arg symbol "$venue_symbol" '
    .data | length == 1 and .[0].magic == $magic and .[0].symbol == $symbol and
    .[0].volume == 0.01 and .[0].price_open > 0 and .[0].sl > 0 and .[0].tp > 0
' "$evidence/position-open.json" >/dev/null || fail "open position violates the bounded contract"
jq --argjson ticket "$owned_ticket" '.ownedPositionTickets = [$ticket] | .status = "position_open"' \
    "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

sleep 3
journal="$(find "$scenario/state/state/insights-journal" -type f -name '*.jsonl' -print -quit)"
[ -s "$journal" ] || fail "Insights outage produced no durable spool"
cursor="${journal%.jsonl}.cursor"
last_seq="$(tail -1 "$journal" | cut -f1)"
acked_seq="$(cat "$cursor" 2>/dev/null || printf 0)"
pending_before="$((last_seq - acked_seq))"
[ "$pending_before" -gt 0 ] || fail "Insights outage had no pending envelopes"
jq -n --arg last "$last_seq" --arg acked "$acked_seq" --arg pending "$pending_before" \
    '{lastSeq:($last|tonumber),ackedSeq:($acked|tonumber),pending:($pending|tonumber)}' > "$evidence/outage-journal.json"

docker start "$container" > "$evidence/insights-start-recovery.txt"
for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$port/healthz" > "$evidence/insights-health-recovered.json" 2>/dev/null && break
    sleep 1
done
jq -e '.ok == true' "$evidence/insights-health-recovered.json" >/dev/null || fail "Insights did not recover"
curl -fsS -c "$cookie" -H 'Content-Type: application/json' \
    --data "{\"username\":\"admin\",\"password\":\"$admin_password\"}" \
    "http://127.0.0.1:$port/auth/login" >/dev/null

replayed=false
for _ in $(seq 1 120); do
    current_acked="$(cat "$cursor" 2>/dev/null || printf 0)"
    if [ "$current_acked" -ge "$last_seq" ]; then replayed=true; break; fi
    sleep 1
done
$replayed || fail "durable Insights replay did not drain"

open_state_seen=false
for sample in $(seq 1 30); do
    path="$evidence/live-state-samples/$sample.json"
    curl -fsS -b "$cookie" "http://127.0.0.1:$port/live/state" > "$path"
    if jq -e --arg ticket "$owned_ticket" --arg strategy "$armed_name" '
        ([.positions[].list[] | select(.ticket == $ticket)] | length) == 1 and
        ([.positions[].list[] | select(.ticket == $ticket)][0].strategyId == $strategy)
    ' "$path" >/dev/null; then
        open_state_seen=true
        cp "$path" "$evidence/live-state-open-attributed.json"
        break
    fi
    sleep 0.2
done
$open_state_seen || fail "Insights never exposed the open ticket with the armed strategy attribution"
sqlite3 -json "$evidence/insights-data/insights.db" \
    "select ticket,strategy_id,profit,last_seq from positions_current where instance_id='$instance' and ticket='$owned_ticket';" \
    > "$evidence/position-current-open.json"
jq -e --arg strategy "$armed_name" 'length == 1 and .[0].strategy_id == $strategy' "$evidence/position-current-open.json" >/dev/null || fail "durable current position lost attribution"

# The successful path must be the actual second DSL decision followed by the
# strategy-owned close-by-ticket transport call. Operator flatten is never used.
strategy_closed=false
deadline=$((SECONDS + timeout_seconds))
while [ "$SECONDS" -lt "$deadline" ]; do
    mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    decision_count="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | awk 'END {print NR + 0}')"
    close_count=0
    if [ "${#transport_journals[@]}" -gt 0 ]; then
        close_count="$(jq -r --argjson ticket "$owned_ticket" '
            select(.method == "POST" and .path == "/close_position" and
                ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
                .responseCode >= 200 and .responseCode < 300) | 1
        ' "${transport_journals[@]}" 2>/dev/null | awk 'END {print NR + 0}')"
    fi
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    if [ "$decision_count" -eq 2 ] && [ "$close_count" -eq 1 ] &&
        jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null; then
        strategy_closed=true
        break
    fi
    [ "$decision_count" -le 2 ] || fail "armed strategy produced more than the reviewed entry and exit decisions"
    [ "$close_count" -le 1 ] || fail "armed strategy issued more than one close mutation"
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for the strategy close"
    sleep 0.2
done
$strategy_closed || fail "second DSL decision did not close the owned ticket within $timeout_seconds seconds"

# Keep both pollers alive until the attributed close deal and flat state projection
# reach the collector.
collector_flat=false
for _ in $(seq 1 30); do
    deal_legs="$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from deals where instance_id='$instance' and position_ticket='$owned_ticket' and strategy_id='$armed_name' and entry in ('IN','OUT');" 2>/dev/null || printf 0)"
    projected="$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from positions_current where instance_id='$instance' and ticket='$owned_ticket';" 2>/dev/null || printf 1)"
    if [ "$deal_legs" -eq 2 ] && [ "$projected" -eq 0 ]; then
        collector_flat=true
        break
    fi
    sleep 1
done
$collector_flat || fail "collector did not retain both deal legs and the final flat projection"
"$cli" stop "$armed_name" --state-dir "$scenario/state" --json > "$evidence/stop-armed.json"
"$cli" stop "$readonly_name" --state-dir "$scenario/state" --json > "$evidence/stop-readonly.json"
"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""
sleep 2

gateway_get /account > "$evidence/gateway-account-final.json"
gateway_get /get_positions > "$evidence/positions-account-final.json"
gateway_get /orders > "$evidence/orders-account-final.json"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-account-final.json" >/dev/null || fail "account is not flat"
jq -e '.ok == true and (.orders | length) == 0' "$evidence/orders-account-final.json" >/dev/null || fail "account has pending orders"
jq -e '.trade_mode == 0 and .margin == 0 and .equity == .balance' "$evidence/gateway-account-final.json" >/dev/null || fail "final account snapshot is inconsistent"

db="$evidence/insights-data/insights.db"
sqlite3 -json "$db" "select type,strategy_id,count(*) count from events where instance_id='$instance' group by type,strategy_id order by type,strategy_id;" > "$evidence/insights-events.json"
sqlite3 -json "$db" "select order_id,strategy_id,state,broker_order_id from orders where instance_id='$instance' order by created_ts,order_id;" > "$evidence/insights-orders.json"
sqlite3 -json "$db" "select strategy_id,entry,count(*) count,printf('%.2f',sum(profit+coalesce(commission,0)+coalesce(swap,0)+coalesce(fee,0))) net from deals where instance_id='$instance' and position_ticket='$owned_ticket' group by strategy_id,entry order by entry;" > "$evidence/insights-deals-by-strategy.json"
sqlite3 -json "$db" "select kind,count(*) count from ingest_observations where instance_id='$instance' group by kind order by kind;" > "$evidence/ingest-observations.json"
sqlite3 -json "$db" "select event_id,count(*) count from ingest_observations where instance_id='$instance' and kind='duplicate' group by event_id order by count desc,event_id limit 20;" > "$evidence/duplicate-event-ids.json"
sqlite3 -json "$db" "select payload from events where instance_id='$instance' and type='decision.rule_evaluated' order by ts,seq;" > "$evidence/insights-rule-decisions.json"

for type_and_count in \
    decision.rule_evaluated:2 decision.order_linked:2 order.submit:2 order.accepted:2 \
    order.filled:2 trade:2 fill.accounted:2; do
    event_type="${type_and_count%:*}"
    expected_count="${type_and_count#*:}"
    actual_count="$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='$event_type' and strategy_id='$armed_name';")"
    [ "$actual_count" -eq "$expected_count" ] ||
        fail "Insights retained $actual_count $event_type events; expected exactly $expected_count"
done
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type in ('risk.rejected','order.rejected');")" -eq 0 ] ||
    fail "Insights retained a rejected risk or order event"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type in ('decision.rule_evaluated','decision.order_linked','order.submit','order.accepted','order.filled','trade','fill.accounted') and coalesce(strategy_id,'') != '$armed_name';")" -eq 0 ] ||
    fail "causal execution events leaked to the read-only sibling or null owner"

jq -e --arg symbol "$armed_symbol" '
    length == 2 and all(.[];
      (.payload | fromjson) as $p |
      ($p.decisionId | type == "string" and length > 0) and
      ($p.ruleId | type == "string" and length > 0) and
      ($p.strategyFingerprint | test("^[0-9a-f]{64}$")) and
      ($p.ruleFingerprint | test("^[0-9a-f]{64}$")) and
      ($p.conditionFingerprint | test("^[0-9a-f]{64}$")) and
      $p.conditionResult == true and ($p.alias == "asset1" or $p.alias == "asset5") and
      $p.broker == "EXNESS" and ($p.timeframe == "1m" or $p.timeframe == "5m") and
      $p.signalCount == 1 and $p.candle.startTimeMs < $p.candle.endTimeMs and
      (["open","high","low","close","volume"] | all(. as $field; $p.candle[$field] != null)) and
      $p.candle.symbol == $symbol)
' "$evidence/insights-rule-decisions.json" >/dev/null || fail "collector rule decisions lack canonical fingerprints, result, stream, signal, or candle evidence"

# Every collector rule decision must equal its engine-audit source on the causal fields.
mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] && [ "${#transport_journals[@]}" -gt 0 ] || fail "engine or transport journal is missing"
for journal_file in "${audit_journals[@]}" "${transport_journals[@]}"; do
    jq -c . "$journal_file" >/dev/null || fail "invalid JSONL journal: $journal_file"
done
[ -z "$(find "$scenario/state/state/audit-journal" "$scenario/state/state/mt5-transport-journal" "$scenario/state/state/insights-journal" -type f -name '*.dropped' -print -quit)" ] ||
    fail "a live journal reported dropped records"
jq -r --arg strategy "$armed_name" '
    select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) |
    [.decisionId,.ruleId,.strategyFingerprint,.ruleFingerprint,.conditionFingerprint,
     (.conditionResult|tostring),.alias,.broker,.timeframe,.symbol,(.signalCount|tostring),
     (.candle.startTimeMs|tostring),(.candle.endTimeMs|tostring),
     (.candle.open|tonumber|tostring),(.candle.high|tonumber|tostring),(.candle.low|tonumber|tostring),
     (.candle.close|tonumber|tostring),(.candle.volume|tonumber|tostring)] | @tsv
' "${audit_journals[@]}" | sort > "$evidence/engine-rule-decisions.tsv"
jq -r '.[] | (.payload | fromjson) |
    [.decisionId,.ruleId,.strategyFingerprint,.ruleFingerprint,.conditionFingerprint,
     (.conditionResult|tostring),.alias,.broker,.timeframe,.candle.symbol,(.signalCount|tostring),
     (.candle.startTimeMs|tostring),(.candle.endTimeMs|tostring),
     (.candle.open|tonumber|tostring),(.candle.high|tonumber|tostring),(.candle.low|tonumber|tostring),
     (.candle.close|tonumber|tostring),(.candle.volume|tonumber|tostring)] | @tsv
' "$evidence/insights-rule-decisions.json" | sort > "$evidence/collector-rule-decisions.tsv"
[ "$(awk 'END {print NR + 0}' "$evidence/engine-rule-decisions.tsv")" -eq 2 ] || fail "engine audit lacks exactly two rule decisions"
cmp -s "$evidence/engine-rule-decisions.tsv" "$evidence/collector-rule-decisions.tsv" ||
    fail "collector rule decisions differ from engine audit"

[ "$(sqlite3 "$db" "select count(*) from events link join events rule on rule.instance_id=link.instance_id and rule.type='decision.rule_evaluated' and json_extract(rule.payload,'$.decisionId')=json_extract(link.payload,'$.decisionId') join events submit on submit.instance_id=link.instance_id and submit.type='order.submit' and json_extract(submit.payload,'$.orderId')=json_extract(link.payload,'$.orderId') where link.instance_id='$instance' and link.type='decision.order_linked' and link.strategy_id='$armed_name' and rule.strategy_id=link.strategy_id and submit.strategy_id=link.strategy_id;")" -eq 2 ] ||
    fail "collector did not join both rule decisions through links to submitted orders"
[ "$(sqlite3 "$db" "select count(*) from orders where instance_id='$instance' and strategy_id='$armed_name' and state='FILLED';")" -eq 2 ] ||
    fail "collector orders did not fold exactly entry and exit to FILLED"
[ "$(sqlite3 "$db" "select count(*) from orders where instance_id='$instance';")" -eq 2 ] || fail "collector created an unexpected order row"

bracket_entry_id="$(sqlite3 "$db" "select json_extract(payload,'$.orderId') from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.orderType')='Bracket';")"
bracket_plan_id="$(sqlite3 "$db" "select json_extract(payload,'$.planOrderId') from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.orderType')='Bracket';")"
[ -n "$bracket_entry_id" ] && [ -n "$bracket_plan_id" ] || fail "canonical bracket identity is missing"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.orderSchemaVersion')=1 and json_extract(payload,'$.orderType')='Bracket' and json_extract(payload,'$.symbol')='$armed_symbol' and json_extract(payload,'$.qty')=0.01 and json_extract(payload,'$.entry.orderType')='Market' and json_extract(payload,'$.stopLossAst.type')='By' and json_extract(payload,'$.stopLossAst.distance.type')='NumLit' and json_extract(payload,'$.stopLossAst.distance.value')=0.0030 and json_extract(payload,'$.takeProfitAst.type')='By' and json_extract(payload,'$.takeProfitAst.distance.type')='NumLit' and json_extract(payload,'$.takeProfitAst.distance.value')=0.0060;")" -eq 1 ] ||
    fail "collector lacks the canonical bracket AST and reviewed distances"
[ "$(sqlite3 "$db" "select count(*) from orders where instance_id='$instance' and order_id='$bracket_plan_id';")" -eq 0 ] ||
    fail "bracket plan created an orphan order row"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.orderSchemaVersion')=1 and json_extract(payload,'$.orderType')='Market' and json_extract(payload,'$.symbol')='$armed_symbol' and json_extract(payload,'$.qty')=0.01 and cast(json_extract(payload,'$.closesTicket') as text)=cast('$owned_ticket' as text) and json_extract(payload,'$.closesLegId') is not null and json_extract(payload,'$.partialClose')=0;")" -eq 1 ] ||
    fail "collector close order lacks full strategy-owned ticket and leg ownership"

[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='fill.accounted' and strategy_id='$armed_name' and json_extract(payload,'$.reducedExposure')=0 and json_extract(payload,'$.partial')=0 and abs(json_extract(payload,'$.strategyPositionAfter.quantity'))=0.01;")" -eq 1 ] ||
    fail "entry fill accounting does not establish the 0.01 strategy position"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='fill.accounted' and strategy_id='$armed_name' and json_extract(payload,'$.reducedExposure')=1 and json_extract(payload,'$.partial')=0 and abs(json_extract(payload,'$.strategyPositionBefore.quantity'))=0.01 and json_extract(payload,'$.strategyPositionAfter') is null;")" -eq 1 ] ||
    fail "exit fill accounting does not reduce the complete strategy position"
jq -e --arg armed "$armed_name" '
    length == 2 and
    ([.[] | select(.strategy_id == $armed and .entry == "IN" and .count == 1)] | length) == 1 and
    ([.[] | select(.strategy_id == $armed and .entry == "OUT" and .count == 1)] | length) == 1
' "$evidence/insights-deals-by-strategy.json" >/dev/null || fail "collector lacks exactly one attributed entry and exit deal"
[ "$(sqlite3 "$db" "select count(*) from positions_current where instance_id='$instance';")" -eq 0 ] || fail "collector final position projection is not flat"

for timeframe_ms in 60000 300000; do
    jq -e --arg symbol "$armed_symbol" --argjson timeframeMs "$timeframe_ms" '
        select(.eventType == "com.qkt.events.WarmupTickEvent" and .symbol == $symbol and .sourceTimeframeMs == $timeframeMs)
    ' "${audit_journals[@]}" >/dev/null || fail "armed runtime lacks $timeframe_ms ms warmup evidence"
done
jq -e --arg symbol "$armed_symbol" 'select(.eventType == "com.qkt.events.TickEvent" and .symbol == $symbol)' \
    "${audit_journals[@]}" >/dev/null || fail "armed runtime lacks live tick evidence"
jq -s -e --arg strategy "$armed_name" --arg symbol "$armed_symbol" '
    . as $events | all([["asset1","1m"],["asset5","5m"]][];
      .[0] as $alias | .[1] as $timeframe |
      any($events[]; .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
        .strategyId == $strategy and .symbol == $symbol and .alias == $alias and .timeframe == $timeframe and
        (. as $evaluation | any($events[]; .eventType == "com.qkt.events.StreamCandleEvent" and
          .symbol == $symbol and .timeframe == $timeframe and
          .candle.startTimeMs == $evaluation.candle.startTimeMs and .candle.endTimeMs == $evaluation.candle.endTimeMs))))
' "${audit_journals[@]}" >/dev/null || fail "armed runtime lacks matched M1/M5 bars and evaluations"

order_posts="$(jq -r 'select(.method == "POST" and .path == "/order") | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
protection_posts="$(jq -r 'select(.method == "POST" and .path == "/modify_sl_tp") | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
close_posts="$(jq -r 'select(.method == "POST" and .path == "/close_position") | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
[ "$order_posts" -eq 1 ] && [ "$protection_posts" -eq 1 ] && [ "$close_posts" -eq 1 ] ||
    fail "transport did not retain exactly one entry, protection update, and strategy close"
jq -s -e --argjson ticket "$owned_ticket" '
    [.[] | select(.method == "POST" and .path == "/close_position")] as $closes |
    ($closes | length) == 1 and
    (($closes[0].requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
    $closes[0].responseCode >= 200 and $closes[0].responseCode < 300 and $closes[0].error == null
' "${transport_journals[@]}" >/dev/null || fail "strategy close did not successfully target the observed venue ticket"

[ "$(sqlite3 "$db" "select count(*) from ingest_observations where instance_id='$instance' and kind in ('gap','regression');")" -eq 0 ] ||
    fail "collector observed a sequence gap or regression"
max_duplicate_count="$(jq -r '([.[].count] | max) // 0' "$evidence/duplicate-event-ids.json")"
[ "$max_duplicate_count" -le 2 ] || fail "an event id was replayed excessively"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='insights.health' and json_extract(payload,'$.dropped') != 0;")" -eq 0 ] ||
    fail "Insights health reported dropped envelopes"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and type='insights.health';")" -gt 0 ] ||
    fail "Insights published no durable health evidence"
while IFS= read -r insight_journal; do
    insight_cursor="${insight_journal%.jsonl}.cursor"
    [ -f "$insight_cursor" ] || fail "Insights journal cursor is missing: $insight_cursor"
    cursor_last="$(<"$insight_cursor")"
    journal_last="$cursor_last"
    if [ -s "$insight_journal" ]; then
        journal_last="$(tail -1 "$insight_journal" | cut -f1)"
    fi
    [ "$cursor_last" -ge "$journal_last" ] || fail "Insights journal did not fully drain: $insight_journal"
done < <(find "$scenario/state/state/insights-journal" -type f -name '*.jsonl' | sort)

initial_balance="$(jq -er '.balance' "$evidence/gateway-account-initial.json")"
final_balance="$(jq -er '.balance' "$evidence/gateway-account-final.json")"
balance_delta="$(awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}')"
deal_net="$(sqlite3 "$db" "select printf('%.2f',sum(profit+coalesce(commission,0)+coalesce(swap,0)+coalesce(fee,0))) from deals where instance_id='$instance' and position_ticket='$owned_ticket';")"
[ "$balance_delta" = "$deal_net" ] || fail "account balance delta $balance_delta differs from attributed deal net $deal_net"

image_id="$(docker image inspect "$insights_image" --format '{{.Id}}')"
qkt_version="$("$cli" --version)"
jq --argjson ticket "$owned_ticket" '
    .ownedPositionTickets = [$ticket] | .ownedOrderTickets = [] | .status = "verified_flat"
' "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"
jq -n --arg qktVersion "$qkt_version" --arg insightsImage "$image_id" --arg instance "$instance" \
    --arg owner "$armed_name" --arg sibling "$readonly_name" --arg ticket "$owned_ticket" \
    --arg symbol "$armed_symbol" --arg pending "$pending_before" --arg candles "$closed_candles" \
    --arg maxDuplicates "$max_duplicate_count" '
    {schema:"qkt-live-insights-attribution-v1",status:"passed",qktVersion:$qktVersion,
     insightsImage:$insightsImage,instanceId:$instance,ownerStrategy:$owner,readonlySibling:$sibling,
     positionTicket:$ticket,symbol:$symbol,bars:{warmupM1:true,warmupM5:true,liveTicks:true,
       matchedM1Evaluation:true,matchedM5Evaluation:true,readonlyClosedCandleEvents:($candles|tonumber)},
     outage:{pendingBeforeRecovery:($pending|tonumber),replayDrained:true},
     telemetry:{ruleDecisions:2,decisionOrderLinks:2,submitted:2,accepted:2,filled:2,trades:2,
       fillAccounted:2,rejected:0,maxDuplicateAttemptsPerEventId:($maxDuplicates|tonumber),
       bracketLifecycleFolded:true,causalJoins:2,falseSequenceObservations:0,dropped:0},
     accounting:{entry:true,exit:true,balanceReconciled:true},
     liveState:{attributedOpenObserved:true},strategyOwnedClose:true,final:{flat:true,pendingOrders:0}}
' > "$evidence/result.json"

control_token=""
if [ -f "$scenario/state/control.token" ]; then
    control_token="$(<"$scenario/state/control.token")"
    unlink "$scenario/state/control.token"
fi
[ ! -e "$scenario/state/daemon.pid" ] || unlink "$scenario/state/daemon.pid"
if printf '%s\n%s\n%s\n%s\n%s' "$QKT_BROKER_API_KEY" "$ingest_token" "$admin_password" "$session_secret" "$control_token" |
    rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "a runtime credential reached retained artifacts"
fi

manifest="$evidence/artifact-manifest.json"
printf '{"schema":"qkt-live-insights-attribution-artifacts-v1","artifacts":[' > "$manifest"
first=true
while IFS= read -r -d '' artifact; do
    [ "$artifact" = "$manifest" ] && continue
    relative="${artifact#"$scenario/"}"
    [ "$relative" = "RUN-SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >> "$manifest"; fi
    jq -cn --arg path "$relative" --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" '{path:$path,sha256:$sha256,size:$size}' >> "$manifest"
done < <(find "$evidence" -type f -print0 | sort -z)
printf ']}\n' >> "$manifest"
(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > RUN-SHA256SUMS
    sha256sum --check RUN-SHA256SUMS >/dev/null
    sha256sum --check SHA256SUMS >/dev/null
)

cat "$evidence/result.json"
trap - EXIT
docker container rm --force "$container" >/dev/null
[ ! -e "$cookie" ] || unlink "$cookie"
