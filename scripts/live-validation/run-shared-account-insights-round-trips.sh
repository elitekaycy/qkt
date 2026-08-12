#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
base_runner="$repo_root/scripts/live-validation/run-container-round-trips.sh"

usage() {
    cat <<'EOF'
Usage: run-shared-account-insights-round-trips.sh --scenario-a DIR --scenario-b DIR \
  --insights-image IMAGE [--cli PATH]
       run-shared-account-insights-round-trips.sh --scenario-a DIR --scenario-b DIR \
  --output DIR --image IMAGE --insights-image IMAGE [--cli PATH] [--port N] [--timeout-seconds N]
  [--arm I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01]

Wraps the bounded two-container same-account live round-trip runner with a local
QKT Insights collector. It proves that two independent live daemons can trade one
hedging MT5 account at the same time while collector attribution stays isolated per
instance and per strategy. The collector image must satisfy the JSON /healthz
contract and the causal ingest probe before any broker mutation is allowed.
EOF
}

fail() {
    printf 'run-shared-account-insights-round-trips: %s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

scenario_a=""
scenario_b=""
output=""
image=""
insights_image=""
cli="$repo_root/build/install/qkt/bin/qkt"
port=18440
timeout_seconds=420
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario-a) scenario_a="${2:-}"; shift 2 ;;
        --scenario-b) scenario_b="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
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

[ -n "$scenario_a" ] || fail "--scenario-a is required"
[ -n "$scenario_b" ] || fail "--scenario-b is required"
[ -n "$insights_image" ] || fail "--insights-image is required"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || fail "--port must be in 1024..65535"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
for command in bash cp curl docker jq openssl realpath rg sha256sum sqlite3 stat; do
    require_command "$command"
done

scenario_a="$(realpath "$scenario_a")"
scenario_b="$(realpath "$scenario_b")"

bash "$base_runner" --scenario-a "$scenario_a" --scenario-b "$scenario_b" --cli "$cli" --verify-only >/dev/null

if $verify_only; then
    printf 'verified %s %s with Insights image %s\n' "$scenario_a" "$scenario_b" "$insights_image"
    exit 0
fi

[ -n "$output" ] || fail "--output is required"
[ -n "$image" ] || fail "--image is required"
[ "$arm" = "I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for jvm_env in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [ -z "${!jvm_env:-}" ] || fail "$jvm_env must be unset; this run does not restrict the JVM"
done
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
docker image inspect "$image" >/dev/null 2>&1 || fail "QKT image does not exist: $image"
docker image inspect "$insights_image" >/dev/null 2>&1 || fail "Insights image does not exist: $insights_image"

output="$(realpath -m "$output")"
[ ! -e "$output" ] || fail "output already exists: $output"
mkdir -m 700 -p "$output/evidence" "$output/scenarios"

clone_a="$output/scenarios/$(basename "$scenario_a")"
clone_b="$output/scenarios/$(basename "$scenario_b")"
cp -a "$scenario_a" "$clone_a"
cp -a "$scenario_b" "$clone_b"

scenario_id_a="$(jq -er '.scenarioId' "$clone_a/scenario.json")"
scenario_id_b="$(jq -er '.scenarioId' "$clone_b/scenario.json")"
strategy_a="$(basename "$(find "$clone_a/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort | head -n 1)" .qkt)"
strategy_b="$(basename "$(find "$clone_b/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort | head -n 1)" .qkt)"
symbol_a="$(jq -er '.armedScenario.symbol' "$clone_a/expected.json")"
symbol_b="$(jq -er '.armedScenario.symbol' "$clone_b/expected.json")"
instance_a="qkt-live-shared-${scenario_id_a}"
instance_b="qkt-live-shared-${scenario_id_b}"
insights_token="$(openssl rand -hex 32)"
admin_password="$(openssl rand -hex 24)"
session_secret="$(openssl rand -hex 32)"
collector="$(
    printf 'qkt-shared-insights-%s' "$(date -u +%Y%m%d%H%M%S)-$$" |
        tr -c 'A-Za-z0-9_.-' '_'
)"
cleanup_running=false

strip_top_level_block() {
    local file="$1"
    local key="$2"
    local temporary="$file.tmp"
    awk -v key="$key" '
        BEGIN { skipping = 0 }
        skipping {
            if ($0 ~ /^[^[:space:]][^:]*:/) {
                skipping = 0
            } else {
                next
            }
        }
        !skipping && $0 ~ ("^" key ":$") {
            skipping = 1
            next
        }
        { print }
    ' "$file" > "$temporary"
    mv "$temporary" "$file"
}

rewrite_scenario_config() {
    local scenario="$1"
    local instance="$2"
    strip_top_level_block "$scenario/qkt.config.yaml" insights
    cat >> "$scenario/qkt.config.yaml" <<EOF
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
    (
        cd "$scenario"
        find . -type f ! -path './SHA256SUMS' ! -path './cleanup.json' -print0 |
            sort -z | xargs -0 sha256sum > SHA256SUMS
        sha256sum --check SHA256SUMS >/dev/null
    )
}

rewrite_scenario_config "$clone_a" "$instance_a"
rewrite_scenario_config "$clone_b" "$instance_b"

cleanup() {
    $cleanup_running && return
    cleanup_running=true
    set +e
    docker container rm --force "$collector" >/dev/null 2>&1
}
trap cleanup EXIT

docker container rm --force "$collector" >/dev/null 2>&1 || true
docker run -d --name "$collector" --network host \
    -e PORT="$port" -e INSIGHTS_DB=/data/insights.db \
    -e INGEST_TOKEN="$insights_token" -e ADMIN_USERNAME=admin \
    -e ADMIN_PASSWORD="$admin_password" -e SESSION_SECRET="$session_secret" \
    -v "$output/evidence/insights-data:/data" "$insights_image" run > "$output/evidence/insights-container-id.txt"

for _ in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:$port/healthz" > "$output/evidence/insights-health-initial.json" 2>/dev/null && break
    sleep 1
done
jq -e '.ok == true and .mode == "run"' "$output/evidence/insights-health-initial.json" >/dev/null ||
    fail "Insights did not become healthy"

probe_instance="qkt-shared-contract-probe"
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
' > "$output/evidence/collector-contract-probe-request.json"
curl --silent --show-error --fail-with-body \
    -H "Authorization: Bearer $insights_token" -H 'Content-Type: application/json' \
    --data-binary @"$output/evidence/collector-contract-probe-request.json" \
    "http://127.0.0.1:$port/ingest" > "$output/evidence/collector-contract-probe-response.json" ||
    fail "Insights image rejected the causal execution contract"
jq -e '.accepted == 7 and .ack.received == 7 and (.ack.acknowledgedIds | length) == 7' \
    "$output/evidence/collector-contract-probe-response.json" >/dev/null || fail "Insights causal contract probe was not fully accepted"

db="$output/evidence/insights-data/insights.db"
[ "$(sqlite3 "$db" "select count(*) from events where instance_id='$probe_instance' and type in ('decision.rule_evaluated','decision.order_linked','fill.accounted');")" -eq 3 ] ||
    fail "Insights did not durably retain the causal contract probe"
[ "$(sqlite3 "$db" "select count(*) from orders where instance_id='$probe_instance' and order_id='probe-restart-order' and state='FILLED';")" -eq 1 ] ||
    fail "Insights does not fold lifecycle events after a producer sequence restart"
[ "$(sqlite3 "$db" "select count(*) from positions_current where instance_id='$probe_instance' and ticket='probe-ticket' and strategy_id='probe';")" -eq 1 ] ||
    fail "Insights does not preserve known position attribution across sibling state polls"
[ "$(sqlite3 "$db" "select count(*) from ingest_observations where instance_id='$probe_instance' and kind in ('gap','regression');")" -eq 0 ] ||
    fail "Insights treats producer-local sequences as global delivery continuity"

export QKT_INSIGHTS_TOKEN="$insights_token"
bash "$base_runner" \
    --scenario-a "$clone_a" \
    --scenario-b "$clone_b" \
    --output "$output/base-roundtrip" \
    --image "$image" \
    --cli "$cli" \
    --timeout-seconds "$timeout_seconds" \
    --arm I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01

sqlite3 -json "$db" "select strategy_id, metadata from strategies where instance_id in ('$instance_a','$instance_b') order by instance_id, strategy_id;" \
    > "$output/evidence/insights-strategies.json"
sqlite3 -json "$db" "select instance_id, strategy_id, order_id, state, broker_order_id from orders where instance_id in ('$instance_a','$instance_b') order by instance_id, created_ts, order_id;" \
    > "$output/evidence/insights-orders.json"
sqlite3 -json "$db" "select instance_id, strategy_id, symbol, entry, position_ticket, printf('%.2f', profit + coalesce(commission,0) + coalesce(swap,0) + coalesce(fee,0)) net from deals where instance_id in ('$instance_a','$instance_b') order by instance_id, ts, deal_ticket;" \
    > "$output/evidence/insights-deals.json"
sqlite3 -json "$db" "select instance_id, kind, count(*) count from ingest_observations where instance_id in ('$instance_a','$instance_b') group by instance_id, kind order by instance_id, kind;" \
    > "$output/evidence/ingest-observations.json"
sqlite3 -json "$db" "select instance_id, count(*) count from positions_current where instance_id in ('$instance_a','$instance_b') group by instance_id order by instance_id;" \
    > "$output/evidence/final-positions-current.json"

for triple in \
    "$instance_a|$strategy_a|$symbol_a" \
    "$instance_b|$strategy_b|$symbol_b"; do
    instance="${triple%%|*}"
    rest="${triple#*|}"
    strategy="${rest%%|*}"
    symbol="${triple##*|}"
    [ "$(sqlite3 "$db" "select count(*) from strategies where instance_id='$instance' and strategy_id='$strategy';")" -eq 1 ] ||
        fail "collector did not register strategy $strategy for $instance"
    [ "$(sqlite3 "$db" "select count(*) from orders where instance_id='$instance' and strategy_id='$strategy' and state='FILLED';")" -eq 2 ] ||
        fail "collector did not retain exactly two filled lifecycle rows for $strategy"
    [ "$(sqlite3 "$db" "select count(*) from deals where instance_id='$instance' and strategy_id='$strategy' and symbol='$symbol' and entry in ('IN','OUT');")" -eq 2 ] ||
        fail "collector did not retain exactly one entry and one exit deal for $strategy"
    [ "$(sqlite3 "$db" "select count(*) from deals where instance_id='$instance' and coalesce(strategy_id,'') != '$strategy';")" -eq 0 ] ||
        fail "collector retained a foreign or null-owned deal in $instance"
    [ "$(sqlite3 "$db" "select count(*) from events where instance_id='$instance' and ((type in ('decision.order_linked','order.submit','order.accepted','order.filled','trade','fill.accounted')) or (type='decision.rule_evaluated' and coalesce(json_extract(payload,'$.signalCount'),0) > 0)) and coalesce(strategy_id,'') != '$strategy';")" -eq 0 ] ||
        fail "collector retained cross-owner causal execution in $instance"
    [ "$(sqlite3 "$db" "select count(*) from positions_current where instance_id='$instance';")" -eq 0 ] ||
        fail "collector final position projection is not flat for $instance"
    [ "$(sqlite3 "$db" "select count(*) from ingest_observations where instance_id='$instance' and kind in ('gap','regression');")" -eq 0 ] ||
        fail "collector observed a sequence gap or regression for $instance"
done

[ "$(sqlite3 "$db" "select count(distinct instance_id) from strategies where instance_id in ('$instance_a','$instance_b');")" -eq 2 ] ||
    fail "collector did not retain both daemon instances"

if printf '%s\n%s\n%s' "$QKT_BROKER_API_KEY" "$insights_token" "$admin_password" |
    rg --text --fixed-strings --quiet -f - "$output"; then
    fail "a runtime credential reached retained artifacts"
fi

jq -n \
    --arg insightsImage "$(docker image inspect "$insights_image" --format '{{.Id}}')" \
    --arg baseResult "$output/base-roundtrip/evidence/result.json" \
    --arg instanceA "$instance_a" \
    --arg instanceB "$instance_b" \
    --arg strategyA "$strategy_a" \
    --arg strategyB "$strategy_b" \
    --arg symbolA "$symbol_a" \
    --arg symbolB "$symbol_b" '
    {
      schema:"qkt-live-shared-account-insights-round-trips-v1",
      status:"passed",
      insightsImage:$insightsImage,
      baseRoundTripResult:$baseResult,
      instances:[
        {instanceId:$instanceA,strategyId:$strategyA,symbol:$symbolA,filledOrders:2,dealLegs:2,flat:true},
        {instanceId:$instanceB,strategyId:$strategyB,symbol:$symbolB,filledOrders:2,dealLegs:2,flat:true}
      ],
      collector:{
        healthy:true,
        causalContractProbe:true,
        retainedInstances:2,
        crossOwnerCausalLeakage:false,
        gapsOrRegressions:false
      }
    }
' > "$output/evidence/result.json"

cat "$output/evidence/result.json"
