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
       [--cli PATH] [--port N] [--arm I_UNDERSTAND_DEMO_ORDER_0.01]
       run-insights-attribution.sh --scenario DIR --insights-image IMAGE --verify-only

Live execution also requires QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY and
QKT_BROKER_API_KEY. It runs a read-only M1/M5 sibling and one bounded 0.01-lot
strategy, interrupts the isolated Insights collector, verifies durable replay and
ticket attribution, then uses QKT's broker-verified flatten path.
EOF
}

scenario=""
insights_image=""
cli="$repo_root/build/install/qkt/bin/qkt"
port=18420
arm=""
verify_only=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --insights-image) insights_image="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --port) port="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$insights_image" ] || fail "--insights-image is required"
scenario="$(realpath "$scenario")"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$port" =~ ^[0-9]+$ ]] && [ "$port" -ge 1024 ] && [ "$port" -le 65535 ] || fail "--port must be in 1024..65535"
command -v docker >/dev/null || fail "docker is required"
command -v sqlite3 >/dev/null || fail "sqlite3 is required"
command -v jq >/dev/null || fail "jq is required"
command -v openssl >/dev/null || fail "openssl is required"
docker image inspect "$insights_image" >/dev/null 2>&1 || fail "Insights image does not exist: $insights_image"
bash "$repo_root/scripts/live-validation/run-readonly.sh" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t readonly_sources < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
mapfile -t armed_sources < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort)
[ "${#readonly_sources[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
[ "${#armed_sources[@]}" -eq 1 ] || fail "expected exactly one armed strategy"
readonly_name="$(basename "${readonly_sources[0]}" .qkt)"
armed_name="$(basename "${armed_sources[0]}" .qkt)"
grep -F 'EVERY 1m' "${readonly_sources[0]}" >/dev/null || fail "read-only strategy is missing M1 bars"
grep -F 'EVERY 5m' "${readonly_sources[0]}" >/dev/null || fail "read-only strategy is missing M5 bars"
grep -F 'SIZING 0.01' "${armed_sources[0]}" >/dev/null || fail "armed strategy is not fixed at 0.01 lots"
grep -F 'TRADES.today = 0' "${armed_sources[0]}" >/dev/null || fail "armed strategy does not prevent re-entry"

if $verify_only; then
    printf 'verified %s with sibling %s and Insights image %s\n' "$armed_name" "$readonly_name" "$insights_image"
    exit 0
fi

[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] || fail "evidence directory is not empty"
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
    set +e
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" kill "$armed_name" --flatten --state-dir "$scenario/state" --json > "$evidence/emergency-kill.json" 2>/dev/null
    fi
    local positions
    positions="$(gateway_get "/get_positions?magic=$magic" 2>/dev/null)"
    while IFS= read -r ticket; do
        [ -n "$ticket" ] || continue
        "$cli" bot close EXNESS:EURUSD --ticket "$ticket" --config "$config" --json >/dev/null 2>&1
    done < <(jq -r '.data[]?.ticket' <<<"$positions")
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

# Cross a real M1 close while both M1 and M5 streams remain active.
sleep 70
mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no audit journal"
closed_candles="$(jq -r 'select(.eventType == "com.qkt.events.CandleEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$closed_candles" -ge 1 ] || fail "read-only strategy produced no closed bar"

docker stop "$container" > "$evidence/insights-stop-outage.txt"
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
jq -e --argjson magic "$magic" '.data | length == 1 and .[0].magic == $magic and .[0].volume == 0.01 and .[0].sl > 0 and .[0].tp > 0' "$evidence/position-open.json" >/dev/null || fail "open position violates the bounded contract"

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

for sample in $(seq 1 30); do
    path="$evidence/live-state-samples/$sample.json"
    curl -fsS -b "$cookie" "http://127.0.0.1:$port/live/state" > "$path"
    jq -e --arg ticket "$owned_ticket" --arg strategy "$armed_name" '
        ([.positions[].list[] | select(.ticket == $ticket)] | length) == 1 and
        ([.positions[].list[] | select(.ticket == $ticket)][0].strategyId == $strategy)
    ' "$path" >/dev/null || fail "live position attribution was missing or incorrect in sample $sample"
    sleep 0.2
done
sqlite3 -json "$evidence/insights-data/insights.db" \
    "select ticket,strategy_id,profit,last_seq from positions_current where instance_id='$instance' and ticket='$owned_ticket';" \
    > "$evidence/position-current-open.json"
jq -e --arg strategy "$armed_name" 'length == 1 and .[0].strategy_id == $strategy' "$evidence/position-current-open.json" >/dev/null || fail "durable current position lost attribution"

"$cli" kill "$armed_name" --flatten --state-dir "$scenario/state" --json > "$evidence/kill-flatten.json"
jq -e '.state == "killed" and .flattenVerified == true and (.remainingTickets | length) == 0' "$evidence/kill-flatten.json" >/dev/null || fail "QKT did not verify flattening"
flat=false
for _ in $(seq 1 30); do
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    if jq -e '.data | length == 0' "$evidence/positions-magic-final.json" >/dev/null; then flat=true; break; fi
    sleep 1
done
$flat || fail "owned position remained open"

# Keep both pollers alive until the close deal reaches the collector.
sleep 5
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

sqlite3 -json "$evidence/insights-data/insights.db" "select strategy_id,count(*) count from orders where instance_id='$instance' group by strategy_id order by strategy_id;" > "$evidence/insights-orders-by-strategy.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select order_id,strategy_id,state,broker_order_id from orders where instance_id='$instance' order by created_ts,order_id;" > "$evidence/insights-orders.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select strategy_id,entry,count(*) count,printf('%.2f',sum(profit+coalesce(commission,0)+coalesce(swap,0)+coalesce(fee,0))) net from deals where instance_id='$instance' and position_ticket='$owned_ticket' group by strategy_id,entry order by entry;" > "$evidence/insights-deals-by-strategy.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select type,strategy_id,count(*) count from events where instance_id='$instance' group by type,strategy_id order by type,strategy_id;" > "$evidence/insights-events.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select kind,count(*) count from ingest_observations where instance_id='$instance' group by kind order by kind;" > "$evidence/ingest-observations.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select event_id,count(*) count from ingest_observations where instance_id='$instance' and kind='duplicate' group by event_id order by count desc,event_id limit 20;" > "$evidence/duplicate-event-ids.json"
sqlite3 -json "$evidence/insights-data/insights.db" "select strategy_id,count(*) count from logs where instance_id='$instance' group by strategy_id order by strategy_id;" > "$evidence/insights-logs.json"

jq -e --arg armed "$armed_name" --arg readonly "$readonly_name" '
    ([.[] | select(.strategy_id == $armed)] | length) == 1 and
    ([.[] | select(.strategy_id == $readonly)] | length) == 0 and
    ([.[] | select(.strategy_id == null)] | length) == 0
' "$evidence/insights-orders-by-strategy.json" >/dev/null || fail "order attribution leaked across strategies"
jq -e --arg armed "$armed_name" '
    ([.[] | select(.strategy_id == $armed and .entry == "IN")] | length) >= 1 and
    ([.[] | select(.strategy_id == $armed and .entry == "OUT")] | length) >= 1 and
    ([.[] | select(.strategy_id == null)] | length) == 0
' "$evidence/insights-deals-by-strategy.json" >/dev/null || fail "deal attribution is incomplete"
trade_count="$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from events where instance_id='$instance' and type='trade';")"
bad_trade_count="$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from events where instance_id='$instance' and type='trade' and coalesce(strategy_id,'') != '$armed_name';")"
[ "$trade_count" -ge 2 ] && [ "$bad_trade_count" -eq 0 ] || fail "trade events are missing strategy attribution"
bracket_entry_id="$(sqlite3 "$evidence/insights-data/insights.db" "select json_extract(payload,'$.orderId') from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.planOrderId') is not null limit 1;")"
bracket_plan_id="$(sqlite3 "$evidence/insights-data/insights.db" "select json_extract(payload,'$.planOrderId') from events where instance_id='$instance' and type='order.submit' and json_extract(payload,'$.planOrderId') is not null limit 1;")"
[ -n "$bracket_entry_id" ] && [ -n "$bracket_plan_id" ] || fail "bracket order identity evidence is missing"
sqlite3 "$evidence/insights-data/insights.db" "select 1 from orders where instance_id='$instance' and order_id='$bracket_entry_id' and state='FILLED';" | grep -qx 1 || fail "bracket entry lifecycle did not fold to FILLED"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from orders where instance_id='$instance' and order_id='$bracket_plan_id';")" -eq 0 ] || fail "bracket plan created an orphan submitted order"
[ "$(sqlite3 "$evidence/insights-data/insights.db" "select count(*) from ingest_observations where instance_id='$instance' and kind in ('gap','regression');")" -eq 0 ] || fail "producer-local sequences created false delivery observations"
max_duplicate_count="$(jq -r '([.[].count] | max) // 0' "$evidence/duplicate-event-ids.json")"
[ "$max_duplicate_count" -le 2 ] || fail "an event id was replayed excessively"

image_id="$(docker image inspect "$insights_image" --format '{{.Id}}')"
qkt_version="$("$cli" --version)"
jq -n --arg qktVersion "$qkt_version" --arg insightsImage "$image_id" --arg instance "$instance" \
    --arg owner "$armed_name" --arg sibling "$readonly_name" --arg ticket "$owned_ticket" \
    --arg pending "$pending_before" --arg candles "$closed_candles" --arg trades "$trade_count" \
    --arg maxDuplicates "$max_duplicate_count" '
    {schema:"qkt-live-insights-attribution-v1",status:"passed",qktVersion:$qktVersion,
     insightsImage:$insightsImage,instanceId:$instance,ownerStrategy:$owner,readonlySibling:$sibling,
     positionTicket:$ticket,bars:{closedCandleEvents:($candles|tonumber)},
     outage:{pendingBeforeRecovery:($pending|tonumber),replayDrained:true},
     telemetry:{attributedTradeEvents:($trades|tonumber),maxDuplicateAttemptsPerEventId:($maxDuplicates|tonumber),
       bracketLifecycleFolded:true,falseSequenceObservations:0},
     liveState:{samples:30,nullAttributionSamples:0},final:{flat:true,pendingOrders:0}}
' > "$evidence/result.json"

find "$evidence" -type f ! -name sha256sums.txt -print0 | sort -z | xargs -0 sha256sum > "$evidence/sha256sums.txt"
if printf '%s\n%s\n%s\n%s' "$QKT_BROKER_API_KEY" "$ingest_token" "$admin_password" "$session_secret" |
    rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "a runtime credential reached retained artifacts"
fi

cat "$evidence/result.json"
trap - EXIT
docker container rm --force "$container" >/dev/null
[ ! -e "$cookie" ] || unlink "$cookie"
