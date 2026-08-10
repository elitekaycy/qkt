#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"

usage() {
    cat <<'EOF'
Usage: run-market-bracket.sh --scenario DIR [--cli PATH] [--timeout-seconds N]
       run-market-bracket.sh --scenario DIR [--cli PATH] --verify-only

Live execution additionally requires both:
  --arm I_UNDERSTAND_DEMO_ORDER_0.01
  QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY

Runs exactly one generated 0.01-lot EURUSD demo bracket through the real QKT daemon
and localhost MT5 gateway, then uses QKT's broker-verified kill/flatten path. The
scenario must be freshly prepared and the whole account must initially be flat.
EOF
}

fail() {
    printf 'run-market-bracket: %s\n' "$1" >&2
    exit 1
}

scenario=""
cli="$repo_root/build/install/qkt/bin/qkt"
timeout_seconds=180
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
scenario="$(realpath "$scenario")"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
command -v unzip >/dev/null || fail "unzip is required"
bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t armed_strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort)
[ "${#armed_strategies[@]}" -eq 1 ] || fail "expected exactly one armed market-bracket strategy"
armed_strategy="${armed_strategies[0]}"
strategy_name="$(basename "$armed_strategy" .qkt)"
grep -F 'SIZING 0.01' "$armed_strategy" >/dev/null || fail "armed strategy is not fixed at 0.01 lots"
grep -F 'TRADES.today = 0' "$armed_strategy" >/dev/null || fail "armed strategy does not prevent re-entry"
grep -F 'STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060' "$armed_strategy" >/dev/null ||
    fail "armed strategy does not contain the reviewed FX bracket"

if $verify_only; then
    printf 'verified %s\n' "$armed_strategy"
    exit 0
fi

[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 60 ] && [ "$timeout_seconds" -le 600 ] ||
    fail "--timeout-seconds must be in 60..600"
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"

gateway_url="$(jq -r '.gatewayUrl' "$scenario/scenario.json")"
magic="$(jq -r '.magic' "$scenario/scenario.json")"
expected_login="$(jq -r '.account.login' "$scenario/expected.json")"
expected_server="$(jq -r '.account.server' "$scenario/expected.json")"
expected_leverage="$(jq -r '.account.leverage' "$scenario/expected.json")"
expected_balance="$(jq -r '.account.startingBalance' "$scenario/expected.json")"
qkt_commit="$(jq -r '.qktCommit' "$scenario/scenario.json")"
qkt_dirty="$(jq -r '.qktDirty' "$scenario/scenario.json")"
config="$scenario/qkt.config.yaml"
evidence="$scenario/evidence"
run_started_ms="$(date +%s%3N)"

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail --config - "$gateway_url$path"
}

gateway_get /health > "$evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"
gateway_get /account > "$evidence/gateway-account-initial.json"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" \
    --arg balance "$expected_balance" '
        .login == $login and
        .server == $server and
        .trade_mode == 0 and
        .currency == "USD" and
        .leverage == $leverage and
        .balance == ($balance | tonumber) and
        .trade_allowed == true and
        .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the demo allowlist"
gateway_get /symbol_info/EURUSDm > "$evidence/symbol-info.json"
jq -e '
    .name == "EURUSDm" and
    .trade_mode == 4 and
    .volume_min == 0.01 and
    .volume_step == 0.01 and
    .trade_contract_size == 100000
' "$evidence/symbol-info.json" >/dev/null || fail "EURUSDm venue metadata does not match the bounded scenario"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"
gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-initial.json"
gateway_get "/orders?magic=$magic" > "$evidence/orders-magic-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a position"
jq -e '.ok == true and (.orders | length) == 0' "$evidence/orders-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a pending order"

"$cli" preflight "$armed_strategy" --config "$config" > "$evidence/preflight.log" 2>&1

daemon_pid=""
owned_ticket=""
cleanup_running=false
cleanup_owned() {
    $cleanup_running && return
    cleanup_running=true
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" kill "$strategy_name" --flatten --state-dir "$scenario/state" --json \
            > "$evidence/emergency-kill.json" 2>/dev/null || true
    fi
    local positions=""
    positions="$(gateway_get "/get_positions?magic=$magic" 2>/dev/null || true)"
    if [ -n "$positions" ]; then
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot close EXNESS:EURUSD --ticket "$ticket" --config "$config" --json >/dev/null 2>&1 || true
        done < <(jq -r '.data[]?.ticket' <<<"$positions")
    fi
    local orders=""
    orders="$(gateway_get "/orders?magic=$magic" 2>/dev/null || true)"
    if [ -n "$orders" ]; then
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot cancel EXNESS:EURUSD --order "$ticket" --config "$config" --json >/dev/null 2>&1 || true
        done < <(jq -r '.orders[]?.ticket' <<<"$orders")
    fi
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
    for transient in "$scenario/state/control.token" "$scenario/state/daemon.pid"; do
        if [ -e "$transient" ]; then
            unlink "$transient"
        fi
    done
}
trap cleanup_owned EXIT

QKT_STATE_DIR="$scenario/state" "$cli" daemon start \
    --config "$config" \
    --state-dir "$scenario/state" \
    > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!

ready=false
for _ in $(seq 1 60); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited before becoming ready"
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-initial.json" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail "daemon did not become ready within 60 seconds"

"$cli" deploy "$armed_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/deploy.json"
jq -e --arg name "$strategy_name" '.name == $name and .state == "running"' "$evidence/deploy.json" >/dev/null ||
    fail "armed strategy did not enter running state"

position_seen=false
for second in $(seq 1 "$timeout_seconds"); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for the bracket fill"
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-open.json"
    count="$(jq '.data | length' "$evidence/positions-magic-open.json")"
    [ "$count" -le 1 ] || fail "scenario created more than one position"
    if [ "$count" -eq 1 ]; then
        position_seen=true
        break
    fi
    if rg --quiet 'Order rejected:' "$scenario/logs/daemon.log"; then
        fail "broker rejected the bracket before a position opened"
    fi
    sleep 1
done
$position_seen || fail "no magic-scoped bracket position appeared within $timeout_seconds seconds"

jq -e \
    --argjson magic "$magic" \
    --arg strategyPrefix "dsl-${strategy_name}" '
        .ok == true and
        (.data | length) == 1 and
        .data[0].symbol == "EURUSDm" and
        .data[0].magic == $magic and
        .data[0].type == 0 and
        .data[0].volume == 0.01 and
        .data[0].price_open > 0 and
        .data[0].sl > 0 and
        .data[0].tp > 0 and
        .data[0].sl < .data[0].price_open and
        .data[0].tp > .data[0].price_open and
        (.data[0].comment as $comment | ($strategyPrefix | startswith($comment)))
    ' "$evidence/positions-magic-open.json" >/dev/null || fail "venue position does not match the reviewed bracket contract"
owned_ticket="$(jq -r '.data[0].ticket' "$evidence/positions-magic-open.json")"
jq \
    --argjson ticket "$owned_ticket" \
    '.ownedPositionTickets = [$ticket] | .status = "position_open"' \
    "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

"$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/strategy-status-open.json"
"$cli" kill "$strategy_name" --flatten --state-dir "$scenario/state" --json > "$evidence/kill-flatten.json"
jq -e '.state == "killed" and .flatten == true and .flattenVerified == true and (.remainingTickets | length) == 0' \
    "$evidence/kill-flatten.json" >/dev/null || fail "QKT could not verify the bracket position was flattened"

flat_seen=false
for _ in $(seq 1 30); do
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    if jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null; then
        flat_seen=true
        break
    fi
    sleep 1
done
$flat_seen || fail "scenario magic remained non-flat after verified flatten"

"$cli" stop "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/stop-strategy.json"
"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-final.json"
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "demo account is not flat after the scenario"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "demo account has a pending order after the scenario"

deals_seen=false
for _ in $(seq 1 30); do
    "$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json > "$evidence/history-during-run.json"
    entry_count="$(jq '[.[] | select(.symbol == "EURUSDm" and .entry == "IN" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    exit_count="$(jq '[.[] | select(.symbol == "EURUSDm" and .entry == "OUT" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    if [ "$entry_count" -ge 1 ] && [ "$exit_count" -ge 1 ]; then
        deals_seen=true
        break
    fi
    sleep 1
done
$deals_seen || fail "venue history did not expose both entry and exit deals"
jq -e --argjson ticket "$owned_ticket" '
    ([.[] | select(.positionTicket == $ticket and .entry == "IN")] | length) >= 1 and
    ([.[] | select(.positionTicket == $ticket and .entry == "OUT")] | length) >= 1
' "$evidence/history-during-run.json" >/dev/null || fail "entry and exit deals do not share the owned position ticket"

gateway_get /account > "$evidence/gateway-account-final.json"
initial_balance="$(jq -r '.balance' "$evidence/gateway-account-initial.json")"
final_balance="$(jq -r '.balance' "$evidence/gateway-account-final.json")"
initial_leverage="$(jq -r '.leverage' "$evidence/gateway-account-initial.json")"
final_leverage="$(jq -r '.leverage' "$evidence/gateway-account-final.json")"
leverage_changed=false
[ "$initial_leverage" = "$final_leverage" ] || leverage_changed=true
balance_delta="$(awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}')"
deal_net="$(
    jq -r --argjson ticket "$owned_ticket" '
        [.[] | select(.positionTicket == $ticket) | ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
    ' "$evidence/history-during-run.json" |
        awk '{printf "%.2f", $1}'
)"
[ "$balance_delta" = "$deal_net" ] || fail "venue balance delta $balance_delta does not reconcile to deal net $deal_net"
jq -e '.trade_mode == 0 and .trade_allowed == true and .trade_expert == true and .leverage > 0 and .margin == 0 and .equity == .balance' \
    "$evidence/gateway-account-final.json" >/dev/null || fail "final demo account snapshot is not flat and tradeable"

mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no engine audit journal"
[ "${#transport_journals[@]}" -gt 0 ] || fail "daemon produced no MT5 transport journal"
for journal in "${audit_journals[@]}" "${transport_journals[@]}"; do
    jq -c . "$journal" >/dev/null || fail "journal is not valid JSONL: $journal"
done
accepted_events="$(jq -r 'select(.eventType == "com.qkt.events.BrokerEvent.OrderAccepted") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
filled_events="$(jq -r 'select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$accepted_events" -ge 2 ] || fail "audit journal is missing accepted entry/exit events"
[ "$filled_events" -ge 2 ] || fail "audit journal is missing filled entry/exit events"
order_posts="$(jq -r 'select(.method == "POST" and .path == "/order" and (.responseCode >= 200 and .responseCode < 300)) | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
close_posts="$(jq -r 'select(.method == "POST" and .path == "/close_position" and (.responseCode >= 200 and .responseCode < 300)) | 1' "${transport_journals[@]}" | awk 'END {print NR + 0}')"
[ "$order_posts" -ge 1 ] || fail "transport journal is missing the accepted MT5 order call"
[ "$close_posts" -ge 1 ] || fail "transport journal is missing the accepted MT5 close call"

golden_zip="$evidence/golden.zip"
golden_manifest="$evidence/golden-manifest.json"
"$cli" golden capture \
    --session "$strategy_name" \
    --state-dir "$scenario/state" \
    --out "$golden_zip" > "$evidence/golden-capture.log"
unzip -p "$golden_zip" manifest.json > "$golden_manifest"
jq -e \
    --arg strategy "$strategy_name" \
    --arg qktCommit "$qkt_commit" '
        .schemaVersion == 2 and
        .kind == "MT5_GOLDEN_CAPTURE" and
        .session == $strategy and
        (.captureGitSha as $capture | ($qktCommit | startswith($capture))) and
        .counts.ticks > 0 and
        .counts.fills >= 2 and
        .counts.gatewayExchanges > 0 and
        .counts.linkedPlacements >= 1
    ' "$golden_manifest" >/dev/null || fail "golden capture does not match the completed live session"
while IFS=$'\t' read -r path expected_sha; do
    actual_sha="$(unzip -p "$golden_zip" "$path" | sha256sum | awk '{print $1}')"
    [ "$actual_sha" = "$expected_sha" ] || fail "golden capture entry hash mismatch: $path"
done < <(jq -r '.entries[] | [.path,.sha256] | @tsv' "$golden_manifest")
golden_ticks="$(jq -r '.counts.ticks' "$golden_manifest")"
golden_fills="$(jq -r '.counts.fills' "$golden_manifest")"
golden_exchanges="$(jq -r '.counts.gatewayExchanges' "$golden_manifest")"
golden_placements="$(jq -r '.counts.linkedPlacements' "$golden_manifest")"
golden_sha="$(sha256sum "$golden_zip" | awk '{print $1}')"

stale_events="$(rg -c 'market data .* STALE:' "$scenario/logs/daemon.log" || printf '0\n')"
recovery_events="$(rg -c 'market data .* healthy again' "$scenario/logs/daemon.log" || printf '0\n')"
[ "$recovery_events" -ge "$stale_events" ] || fail "market-data stale episode did not recover before shutdown"

jq \
    --argjson ticket "$owned_ticket" \
    '.ownedPositionTickets = [$ticket] | .ownedOrderTickets = [] | .status = "verified_flat"' \
    "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health.json")"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktVersion "$qkt_version" \
    --arg gatewayVersion "$gateway_version" \
    --arg qktCommit "$qkt_commit" \
    --argjson qktDirty "$qkt_dirty" \
    --arg strategy "$strategy_name" \
    --argjson magic "$magic" \
    --argjson ticket "$owned_ticket" \
    --arg balanceDelta "$balance_delta" \
    --arg dealNet "$deal_net" \
    --arg initialLeverage "$initial_leverage" \
    --arg finalLeverage "$final_leverage" \
    --argjson leverageChanged "$leverage_changed" \
    --arg acceptedEvents "$accepted_events" \
    --arg filledEvents "$filled_events" \
    --arg orderPosts "$order_posts" \
    --arg closePosts "$close_posts" \
    --arg goldenTicks "$golden_ticks" \
    --arg goldenFills "$golden_fills" \
    --arg goldenExchanges "$golden_exchanges" \
    --arg goldenPlacements "$golden_placements" \
    --arg goldenSha "$golden_sha" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" '
        {
          schema:"qkt-live-validation-market-bracket-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          strategy:$strategy,
          magic:$magic,
          positionTicket:$ticket,
          lots:"0.01",
          bracket:{stopDistance:"0.0030",takeProfitDistance:"0.0060"},
          flattenVerified:true,
          finalPositions:0,
          finalOrders:0,
          balanceDelta:$balanceDelta,
          dealNet:$dealNet,
          leverage:{
            initial:($initialLeverage|tonumber),
            final:($finalLeverage|tonumber),
            changed:$leverageChanged
          },
          audit:{acceptedEvents:($acceptedEvents|tonumber),filledEvents:($filledEvents|tonumber)},
          transport:{orderPosts:($orderPosts|tonumber),closePosts:($closePosts|tonumber)},
          golden:{
            ticks:($goldenTicks|tonumber),
            fills:($goldenFills|tonumber),
            gatewayExchanges:($goldenExchanges|tonumber),
            linkedPlacements:($goldenPlacements|tonumber),
            sha256:$goldenSha
          },
          staleEvents:($staleEvents|tonumber),
          recoveredStaleEvents:($recoveryEvents|tonumber)
        }
    ' > "$evidence/result.json"

for transient in "$scenario/state/control.token" "$scenario/state/daemon.pid"; do
    if [ -e "$transient" ]; then
        unlink "$transient"
    fi
done
if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "broker credential was persisted in the scenario artifacts"
fi

manifest="$evidence/artifact-manifest.json"
printf '{"schema":"qkt-live-validation-artifacts-v1","artifacts":[' > "$manifest"
first=true
while IFS= read -r -d '' artifact; do
    relative="${artifact#"$scenario/"}"
    [ "$relative" = "evidence/artifact-manifest.json" ] && continue
    [ "$relative" = "RUN-SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >> "$manifest"; fi
    jq -cn \
        --arg path "$relative" \
        --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" \
        '{path:$path,size:$size,sha256:$sha256}' >> "$manifest"
done < <(find "$scenario" -type f -print0 | sort -z)
printf ']}\n' >> "$manifest"
(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > RUN-SHA256SUMS
    sha256sum --check RUN-SHA256SUMS >/dev/null
)

trap - EXIT
printf 'passed %s\n' "$evidence/result.json"
