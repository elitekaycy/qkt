#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
# shellcheck source=scripts/live-validation/lib/account-identity.sh
source "$repo_root/scripts/live-validation/lib/account-identity.sh"
# shellcheck source=scripts/live-validation/lib/container-load-evidence.sh
source "$repo_root/scripts/live-validation/lib/container-load-evidence.sh"
# shellcheck source=scripts/live-validation/lib/catalog-startup-window.sh
source "$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"

usage() {
    cat <<'EOF'
Usage: run-order-gateway-restart.sh --scenario DIR --gateway-container NAME [--cli PATH]
       [--phase-timeout-seconds N] [--timeout-seconds N]
       --arm I_UNDERSTAND_DEMO_ORDER_0.01
       run-order-gateway-restart.sh --scenario DIR --gateway-container NAME [--cli PATH] --verify-only

Verifies a prepared live-validation scenario, starts the read-only sibling against the real
localhost MT5 gateway, deploys the bounded 0.01-lot armed strategy, waits for one
strategy-owned demo position to open, restarts the named Docker gateway container while that
position is open, proves feed disconnect and reconnect, then requires the same strategy to close
the same ticket after reconnect with exact bar evidence retained before and after the restart.
EOF
}

fail() {
    printf 'run-order-gateway-restart: %s\n' "$1" >&2
    exit 1
}

count_records() {
    awk 'END {print NR + 0}'
}

count_matched_evaluations_any() {
    local strategy="$1"
    local alias="$2"
    local symbol="$3"
    local timeframe="$4"
    local after_ms="$5"
    local before_ms="$6"
    shift 6
    jq -s --arg strategy "$strategy" --arg alias "$alias" --arg symbol "$symbol" \
        --arg timeframe "$timeframe" --argjson afterMs "$after_ms" --argjson beforeMs "$before_ms" '
        def in_window:
            ($afterMs < 0 or .ts >= $afterMs) and ($beforeMs < 0 or .ts < $beforeMs);
        . as $events |
        [$events[] | select(
            in_window and
            .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
            .strategyId == $strategy and .alias == $alias and .symbol == $symbol and
            .timeframe == $timeframe and
            (. as $evaluation | any($events[];
                in_window and .eventType == "com.qkt.events.StreamCandleEvent" and
                .symbol == $symbol and .timeframe == $timeframe and
                .candle.startTimeMs == $evaluation.candle.startTimeMs and
                .candle.endTimeMs == $evaluation.candle.endTimeMs
            ))
        )] | length' "$@"
}

scenario=""
gateway_container=""
cli="$repo_root/build/install/qkt/bin/qkt"
phase_timeout_seconds=370
timeout_seconds=720
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --gateway-container) gateway_container="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --phase-timeout-seconds) phase_timeout_seconds="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$gateway_container" ] || fail "--gateway-container is required"
scenario="$(realpath "$scenario")"
[ -d "$scenario" ] || fail "scenario directory does not exist: $scenario"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$phase_timeout_seconds" =~ ^[0-9]+$ ]] || fail "--phase-timeout-seconds must be an integer"
[ "$phase_timeout_seconds" -ge 310 ] || fail "--phase-timeout-seconds must be at least 310"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 420 ] && [ "$timeout_seconds" -le 1200 ] ||
    fail "--timeout-seconds must be in 420..1200"

bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t readonly_sources < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
mapfile -t armed_sources < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' | sort)
[ "${#readonly_sources[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
[ "${#armed_sources[@]}" -eq 1 ] || fail "expected exactly one armed strategy"
readonly_strategy="${readonly_sources[0]}"
armed_strategy="${armed_sources[0]}"
readonly_name="$(basename "$readonly_strategy" .qkt)"
armed_name="$(basename "$armed_strategy" .qkt)"
armed_symbol="$(jq -er '.armedScenario.symbol' "$scenario/expected.json")"
case "$armed_symbol" in
    EXNESS:EURUSD) venue_symbol=EURUSDm ;;
    EXNESS:GBPUSD) venue_symbol=GBPUSDm ;;
    *) fail "armed scenario symbol is outside the reviewed FX set: $armed_symbol" ;;
esac
grep -F 'EVERY 1m' "$readonly_strategy" >/dev/null || fail "read-only strategy is missing M1 bars"
grep -F 'EVERY 5m' "$readonly_strategy" >/dev/null || fail "read-only strategy is missing M5 bars"
grep -F 'SIZING 0.01' "$armed_strategy" >/dev/null || fail "armed strategy is not fixed at 0.01 lots"
grep -F 'TRADES.today = 0' "$armed_strategy" >/dev/null || fail "armed strategy does not prevent re-entry"
grep -F 'POSITION.asset1.holding_duration >= 1' "$armed_strategy" >/dev/null ||
    fail "armed strategy does not contain the reviewed DSL close delay"
grep -F 'THEN CLOSE asset1' "$armed_strategy" >/dev/null || fail "armed strategy has no strategy-owned close"
jq -e --arg readonly "$readonly_name" --arg armed "$armed_name" --arg symbol "$armed_symbol" '
    .schema == "qkt-live-validation-expected-v2" and
    .account.tradeMode == "demo" and .account.currency == "USD" and
    .safety.maximumLots == "0.01" and .safety.maximumOpenPositions == 1 and
    .safety.maximumTradesPerDay == 1 and
    (.readOnlyStreams | map(.timeframe)) == ["1m", "5m"] and
    all(.readOnlyStreams[]; .symbol == "EXNESS:EURUSD" and .warmupBars == 20) and
    .armedScenario.strategy == $armed and .armedScenario.symbol == $symbol and
    (.armedScenario.streams | map(.timeframe)) == ["1m", "5m"] and
    all(.armedScenario.streams[]; .symbol == $symbol and .warmupBars == 10) and
    .armedScenario.quantityLots == "0.01" and
    .armedScenario.maximumEntries == 1 and .armedScenario.maximumExits == 1 and
    .armedScenario.minimumHoldingSeconds == 1 and
    .armedScenario.stopDistance == "0.0030" and .armedScenario.takeProfitDistance == "0.0060"
' "$scenario/expected.json" >/dev/null || fail "expected metadata is not the bounded restart contract"
"$cli" parse "$readonly_strategy" >/dev/null
"$cli" parse "$armed_strategy" >/dev/null

if $verify_only; then
    printf 'verified %s with %s via %s\n' "$readonly_name" "$armed_name" "$gateway_container"
    exit 0
fi

for command in awk curl docker jq openssl realpath rg sha256sum sort stat; do
    command -v "$command" >/dev/null || fail "$command is required"
done
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for jvm_env in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [ -z "${!jvm_env:-}" ] || fail "$jvm_env must be unset; this run does not restrict the JVM"
done
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"

expected_identity_source="$(jq -r '.account.identitySource // "preparedScenario"' "$scenario/expected.json")"
if [ "$expected_identity_source" = "runtimeEnvironment" ]; then
    qkt_require_runtime_account_identity || fail "runtime account identity is required"
    expected_login="$QKT_EXPECTED_ACCOUNT_LOGIN"
    expected_server="$QKT_EXPECTED_ACCOUNT_SERVER"
else
    expected_login="$(jq -er '.account.login' "$scenario/expected.json")"
    expected_server="$(jq -er '.account.server' "$scenario/expected.json")"
fi

gateway_url="$(jq -r '.gatewayUrl' "$scenario/scenario.json")"
case "$gateway_url" in
    http://127.0.0.1:*|http://localhost:*) ;;
    *) fail "scenario gateway must be localhost" ;;
esac
magic="$(jq -r '.magic' "$scenario/scenario.json")"
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

sanitize_container_inspect() {
    jq '
        map(
            if .Config.Env then
                .Config.Env |= map(
                    if startswith("API_KEY=") then
                        "API_KEY=<redacted>"
                    elif startswith("MT5_PASSWORD=") then
                        "MT5_PASSWORD=<redacted>"
                    elif startswith("MT5_LOGIN=") then
                        "MT5_LOGIN=<redacted>"
                    elif startswith("MT5_SERVER=") then
                        "MT5_SERVER=<redacted>"
                    else
                        .
                    end
                )
            else
                .
            end
        )
    '
}

wait_for_startup_window() {
    local evidence_path="$evidence/startup-window.jsonl"
    local max_total_wait_seconds=260
    local total_wait_seconds=0
    : > "$evidence_path"
    for attempt in 1 2 3; do
        local tick_file="$evidence/startup-tick-$attempt.json"
        gateway_get /symbol_info_tick/EURUSDm > "$tick_file"
        local broker_tick_ms
        broker_tick_ms="$(jq -er '(.time_msc // ((.time | tonumber) * 1000)) | tonumber' "$tick_file")" ||
            fail "gateway startup tick did not contain a usable broker timestamp"
        local observed_at_ms
        observed_at_ms="$(date +%s%3N)"
        local tick_age_ms=$((observed_at_ms - broker_tick_ms))
        [ "$tick_age_ms" -ge -5000 ] && [ "$tick_age_ms" -le 60000 ] ||
            fail "gateway startup tick is not current enough to select a safe deploy window"
        local phase_clock_ms="$broker_tick_ms"
        [ "$tick_age_ms" -lt 0 ] || phase_clock_ms=$((broker_tick_ms + tick_age_ms))
        local broker_phase_ms=$((broker_tick_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local phase_ms=$((phase_clock_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local delay_ms
        delay_ms="$(qkt_catalog_startup_delay_ms "$phase_ms")" || fail "invalid broker startup phase: $phase_ms"
        local sleep_seconds=0
        if [ "$delay_ms" -gt 0 ]; then
            sleep_seconds=$(((delay_ms + 999) / 1000 + 1))
        fi
        jq -cn \
            --argjson attempt "$attempt" \
            --argjson observedAtMs "$observed_at_ms" \
            --argjson brokerTickMs "$broker_tick_ms" \
            --argjson tickAgeMs "$tick_age_ms" \
            --argjson phaseClockMs "$phase_clock_ms" \
            --argjson brokerPhaseMs "$broker_phase_ms" \
            --argjson phaseMs "$phase_ms" \
            --argjson delayMs "$delay_ms" \
            --argjson sleepSeconds "$sleep_seconds" '
            {
              attempt:$attempt,
              observedAtMs:$observedAtMs,
              brokerTickMs:$brokerTickMs,
              tickAgeMs:$tickAgeMs,
              phaseClockMs:$phaseClockMs,
              brokerPhaseMs:$brokerPhaseMs,
              phaseMs:$phaseMs,
              delayMs:$delayMs,
              sleepSeconds:$sleepSeconds,
              safeToLaunch:($delayMs == 0)
            }
        ' >> "$evidence_path"
        if [ "$delay_ms" -eq 0 ]; then
            jq -n \
                --argjson enteredAtBrokerMs "$broker_tick_ms" \
                --argjson enteredAtClockMs "$phase_clock_ms" \
                --argjson enteredAtPhaseMs "$phase_ms" \
                --argjson totalWaitSeconds "$total_wait_seconds" '
                {
                  schema:"qkt-live-order-gateway-restart-startup-window-v1",
                  status:"passed",
                  clockSource:"broker-tick-validated-utc",
                  wireSymbol:"EURUSDm",
                  periodMs:300000,
                  safeStartMs:90000,
                  safeEndMs:150000,
                  enteredAtBrokerMs:$enteredAtBrokerMs,
                  enteredAtClockMs:$enteredAtClockMs,
                  enteredAtPhaseMs:$enteredAtPhaseMs,
                  totalWaitSeconds:$totalWaitSeconds,
                  maxWaitSeconds:260,
                  maxObservations:3
                }
            ' > "$evidence/startup-window.json"
            return
        fi
        [ "$((total_wait_seconds + sleep_seconds))" -le "$max_total_wait_seconds" ] ||
            fail "broker tick clock did not enter the order gateway-restart startup window within 260 seconds"
        total_wait_seconds=$((total_wait_seconds + sleep_seconds))
        sleep "$sleep_seconds"
    done
    fail "broker tick clock did not enter the bounded order gateway-restart startup window after three observations"
}

health_initial="$(gateway_get /health)"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    <<<"$health_initial" >/dev/null || fail "gateway is not healthy and connected"
qkt_write_safe_gateway_health_snapshot "$evidence/gateway-health-initial.json" <<< "$health_initial"
unset health_initial

docker inspect "$gateway_container" 2>/dev/null | sanitize_container_inspect > "$evidence/gateway-container-initial.json" ||
    fail "gateway container is not inspectable: $gateway_container"
jq -e '.[0].State.Running == true' "$evidence/gateway-container-initial.json" >/dev/null ||
    fail "gateway container is not running"

account_initial="$(gateway_get /account)"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" \
    --arg balance "$expected_balance" '
        .login == $login and .server == $server and .trade_mode == 0 and .currency == "USD" and
        .leverage == $leverage and .balance == ($balance | tonumber) and .equity == ($balance | tonumber) and
        .trade_allowed == true and .trade_expert == true
    ' <<< "$account_initial" >/dev/null || fail "gateway account does not match the demo allowlist"
qkt_write_safe_account_snapshot "$evidence/gateway-account-initial.json" <<< "$account_initial"
unset account_initial

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-account-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-account-initial.json"
jq -e 'length == 0' "$evidence/positions-account-initial.json" >/dev/null || fail "account has an open position"
jq -e 'length == 0' "$evidence/orders-account-initial.json" >/dev/null || fail "account has a pending order"
gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-initial.json"
gateway_get "/orders?magic=$magic" > "$evidence/orders-magic-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a position"
jq -e '.ok == true and (.orders | length) == 0' "$evidence/orders-magic-initial.json" >/dev/null ||
    fail "scenario magic already owns a pending order"

"$cli" preflight "$readonly_strategy" --config "$config" > "$evidence/preflight-readonly.log" 2>&1
"$cli" preflight "$armed_strategy" --config "$config" > "$evidence/preflight-armed.log" 2>&1
qkt_redact_account_identity_log "$evidence/preflight-readonly.log" "$expected_login" "$expected_server"
qkt_redact_account_identity_log "$evidence/preflight-armed.log" "$expected_login" "$expected_server"

daemon_pid=""
owned_ticket=""
broker_mutation_possible=false
cleanup_running=false
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
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
    qkt_sanitize_account_transport_journals "$scenario/state/state/mt5-transport-journal" 2>/dev/null || true
    qkt_redact_account_identity_log "$scenario/logs/daemon.log" "$expected_login" "$expected_server" || true
    qkt_assert_no_retained_account_identity "$scenario" "$expected_login" "$expected_server" || true
}
trap cleanup EXIT

wait_for_startup_window

export QKT_STATE_DIR="$scenario/state"
daemon_started_ms="$(date +%s%3N)"
QKT_LATENCY_TRACKING=1 "$cli" daemon start \
    --config "$config" \
    --state-dir "$scenario/state" \
    --load-dir "$scenario/strategies/readonly" \
    > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!

ready=false
for _ in $(seq 1 90); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during startup"
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-initial.json" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail "daemon did not become ready"

printf 'elapsed_seconds,cpu_percent,rss_kb,threads,strategies\n' > "$evidence/resources.csv"
: > "$evidence/health.jsonl"
clock_ticks="$(getconf CLK_TCK)"
previous_ticks="$(awk '{print $14 + $15}' "/proc/$daemon_pid/stat")"
observation_started_second=$SECONDS

sample_runtime() {
    local label="$1"
    local elapsed_seconds=$((SECONDS - observation_started_second))
    local current_ticks
    current_ticks="$(awk '{print $14 + $15}' "/proc/$daemon_pid/stat")"
    local cpu_percent
    cpu_percent="$(
        awk -v now="$current_ticks" -v previous="$previous_ticks" -v hz="$clock_ticks" '
            BEGIN {
                delta = now - previous
                if (delta < 0) delta = 0
                printf "%.2f", (delta / hz) * 100
            }
        '
    )"
    local rss_kb threads status_json strategies
    rss_kb="$(awk '/^VmRSS:/ {print $2}' "/proc/$daemon_pid/status")"
    threads="$(awk '/^Threads:/ {print $2}' "/proc/$daemon_pid/status")"
    status_json="$("$cli" daemon status --state-dir "$scenario/state" --json)"
    strategies="$(jq -r '.strategies' <<<"$status_json")"
    printf '%s,%s,%s,%s,%s\n' "$elapsed_seconds" "$cpu_percent" "$rss_kb" "$threads" "$strategies" \
        >> "$evidence/resources.csv"
    jq -c --arg label "$label" '. + {sampleLabel:$label}' <<<"$status_json" >> "$evidence/health.jsonl"
    previous_ticks="$current_ticks"
}

wait_for_strategy_running() {
    local strategy="$1"
    local expected_strategies="$2"
    local output_path="$3"
    local deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited while waiting for running strategy state"
        fi
        if "$cli" daemon status --state-dir "$scenario/state" --json > "$output_path" 2>/dev/null &&
            jq -e --arg strategy "$strategy" --argjson strategies "$expected_strategies" '
                .status == "ok" and .strategies == $strategies and
                ([.perStrategy[] | select(.name == $strategy and .running == true and .halted == false and .droppedTicks == 0)] | length) == 1
            ' "$output_path" >/dev/null; then
            return
        fi
        sleep 1
    done
    fail "strategy did not reach running state: $strategy"
}

wait_for_matched_evaluations() {
    local phase="$1"
    local strategy="$2"
    local symbol="$3"
    local alias_m1="$4"
    local alias_m5="$5"
    local after_ms="$6"
    local match_mode="${7:-rule_driving}"
    local deadline=$((SECONDS + phase_timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited during $phase"
        fi
        sample_runtime "$phase"
        local -a audits=()
        mapfile -t audits < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
        if [ "${#audits[@]}" -gt 0 ]; then
            local m1_matches m5_matches
            if [ "$match_mode" = "any" ]; then
                m1_matches="$(count_matched_evaluations_any "$strategy" "$alias_m1" "$symbol" 1m "$after_ms" -1 "${audits[@]}")"
                m5_matches="$(count_matched_evaluations_any "$strategy" "$alias_m5" "$symbol" 5m "$after_ms" -1 "${audits[@]}")"
            else
                m1_matches="$(qkt_count_matched_evaluations "$strategy" "$alias_m1" "$symbol" 1m "$after_ms" -1 "${audits[@]}")"
                m5_matches="$(qkt_count_matched_evaluations "$strategy" "$alias_m5" "$symbol" 5m "$after_ms" -1 "${audits[@]}")"
            fi
            if [ "$m1_matches" -gt 0 ] && [ "$m5_matches" -gt 0 ]; then
                return
            fi
        fi
        sleep 10
    done
    fail "$phase lacked exact matched M1/M5 bars and evaluations within $phase_timeout_seconds seconds"
}

wait_for_log_marker() {
    local marker="$1"
    local phase="$2"
    local deadline=$((SECONDS + 180))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if rg --fixed-strings --quiet "$marker" "$scenario/logs/daemon.log"; then
            return
        fi
        sleep 1
    done
    fail "$phase retained no log marker: $marker"
}

wait_for_gateway_health() {
    local output_path="$1"
    local deadline=$((SECONDS + 180))
    local health
    while [ "$SECONDS" -lt "$deadline" ]; do
        health="$(gateway_get /health 2>/dev/null)" || {
            sleep 2
            continue
        }
        if jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
            <<<"$health" >/dev/null; then
            qkt_write_safe_gateway_health_snapshot "$output_path" <<< "$health"
            return
        fi
        sleep 2
    done
    fail "gateway did not return to healthy connected state after container restart"
}

wait_for_strategy_running "$readonly_name" 1 "$evidence/daemon-status-readonly.json"
session_started_ms="$(date +%s%3N)"
wait_for_matched_evaluations pre-restart-readonly "$readonly_name" EXNESS:EURUSD eur1 eur5 "$session_started_ms"

for _ in $(seq 1 70); do
    second="$(date -u +%S)"
    [ "$second" -le 10 ] && break
    sleep 1
done
[ "$(date -u +%S)" -le 12 ] || fail "could not align the bounded deploy after an M1 boundary"

deploy_started_ms="$(date +%s%3N)"
broker_mutation_possible=true
certification_deadline=$((SECONDS + timeout_seconds))
"$cli" deploy "$armed_strategy" --as "$armed_name" --state-dir "$scenario/state" --json > "$evidence/deploy-armed.json"
jq -e --arg strategy "$armed_name" '.name == $strategy and .state == "running"' \
    "$evidence/deploy-armed.json" >/dev/null || fail "armed strategy did not enter running state"
wait_for_strategy_running "$armed_name" 2 "$evidence/daemon-status-armed.json"

position_seen=false
for _ in $(seq 1 180); do
    gateway_get "/get_positions?magic=$magic" > "$evidence/position-open.json"
    count="$(jq '.data | length' "$evidence/position-open.json")"
    [ "$count" -le 1 ] || fail "armed strategy created more than one position"
    if [ "$count" -eq 1 ]; then
        position_seen=true
        break
    fi
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for fill"
    [ "$SECONDS" -lt "$certification_deadline" ] || break
    sleep 1
done
$position_seen || fail "bounded position did not open"
owned_ticket="$(jq -r '.data[0].ticket' "$evidence/position-open.json")"
jq -e --argjson magic "$magic" --arg symbol "$venue_symbol" '
    .ok == true and
    (.data | length) == 1 and
    .data[0].magic == $magic and
    .data[0].symbol == $symbol and
    .data[0].volume == 0.01 and
    .data[0].price_open > 0 and
    .data[0].sl > 0 and
    .data[0].tp > 0
' "$evidence/position-open.json" >/dev/null || fail "open position violates the bounded contract"
jq --argjson ticket "$owned_ticket" '.ownedPositionTickets = [$ticket] | .status = "position_open"' \
    "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

restart_started_ms="$(date +%s%3N)"
docker restart "$gateway_container" > "$evidence/gateway-container-restart.log"
restart_ready_ms="$(date +%s%3N)"
docker inspect "$gateway_container" | sanitize_container_inspect > "$evidence/gateway-container-restarted.json"
jq -e '.[0].State.Running == true' "$evidence/gateway-container-restarted.json" >/dev/null ||
    fail "gateway container is not running after restart"
wait_for_gateway_health "$evidence/gateway-health-post-restart.json"
wait_for_log_marker 'LiveTickFeed source disconnected; waiting up to' reconnect
wait_for_log_marker 'LiveTickFeed source reconnected; resuming' reconnect

position_persisted_across_restart=false
for _ in $(seq 1 60); do
    gateway_get "/get_positions?magic=$magic" > "$evidence/position-open-post-restart.json"
    if jq -e --argjson ticket "$owned_ticket" --arg symbol "$venue_symbol" '
        .ok == true and
        (.data | length) == 1 and
        .data[0].ticket == $ticket and
        .data[0].symbol == $symbol and
        .data[0].volume == 0.01
    ' "$evidence/position-open-post-restart.json" >/dev/null; then
        position_persisted_across_restart=true
        break
    fi
    mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    if [ "${#transport_journals[@]}" -gt 0 ] &&
        jq -s -e --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
            [ .[] | select(
                .method == "POST" and
                .path == "/close_position" and
                .ts >= $restartStartedMs and
                ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring))
            ) ] | length >= 1
        ' "${transport_journals[@]}" >/dev/null &&
        jq -e '.ok == true and (.data | length) == 0' "$evidence/position-open-post-restart.json" >/dev/null; then
        position_persisted_across_restart=true
        break
    fi
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for post-restart position"
    sleep 1
done
$position_persisted_across_restart ||
    fail "gateway restart did not retain the owned open ticket long enough to observe or close after reconnect"

strategy_closed=false
while [ "$SECONDS" -lt "$certification_deadline" ]; do
    mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    decisions="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    links="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    accepted="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.BrokerEvent.OrderAccepted" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    filled="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    accounted="$(jq -r --arg strategy "$armed_name" '
        select(.eventType == "com.qkt.events.FillAccountedEvent" and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    rejected="$(jq -r --arg strategy "$armed_name" '
        select((.eventType == "com.qkt.events.BrokerEvent.OrderRejected" or .eventType == "com.qkt.events.RiskRejectedEvent") and .strategyId == $strategy) | 1
    ' "${audit_journals[@]}" 2>/dev/null | count_records)"
    close_attempts_after_restart="$(jq -r --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
        select(.method == "POST" and .path == "/close_position" and .ts >= $restartStartedMs and
            ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring))) | 1
    ' "${transport_journals[@]}" 2>/dev/null | count_records)"
    successful_close_posts="$(jq -r --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
        select(.method == "POST" and .path == "/close_position" and .ts >= $restartStartedMs and
            ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
            .responseCode >= 200 and .responseCode < 300 and .error == null) | 1
    ' "${transport_journals[@]}" 2>/dev/null | count_records)"
    failed_close_posts="$(jq -r --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
        select(.method == "POST" and .path == "/close_position" and .ts >= $restartStartedMs and
            ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
            (.responseCode < 200 or .responseCode >= 300 or .error != null)) | 1
    ' "${transport_journals[@]}" 2>/dev/null | count_records)"
    gateway_get "/get_positions?magic=$magic" > "$evidence/positions-magic-final.json"
    if [ "$decisions" -ge 2 ] && [ "$decisions" -le 3 ] &&
        [ "$links" -ge 2 ] && [ "$links" -le 3 ] &&
        [ "$accepted" -eq 2 ] && [ "$filled" -eq 2 ] && [ "$accounted" -eq 2 ] &&
        [ "$rejected" -le 1 ] &&
        [ "$close_attempts_after_restart" -ge 1 ] && [ "$close_attempts_after_restart" -le 2 ] &&
        [ "$successful_close_posts" -eq 1 ] && [ "$failed_close_posts" -le 1 ] &&
        jq -e '.ok == true and (.data | length) == 0' "$evidence/positions-magic-final.json" >/dev/null; then
        strategy_closed=true
        break
    fi
    [ "$decisions" -le 3 ] || fail "armed strategy produced more than one retry close decision after reconnect"
    [ "$links" -le 3 ] || fail "armed strategy linked more than one retry close order after reconnect"
    [ "$accepted" -le 2 ] || fail "armed strategy accepted more than the reviewed entry and exit orders"
    [ "$filled" -le 2 ] || fail "armed strategy filled more than the reviewed entry and exit orders"
    [ "$accounted" -le 2 ] || fail "armed strategy accounted more than the reviewed entry and exit fills"
    [ "$rejected" -le 1 ] || fail "armed strategy retained more than one rejected close after reconnect"
    [ "$close_attempts_after_restart" -le 2 ] || fail "armed strategy issued more than one retry close after reconnect"
    [ "$failed_close_posts" -le 1 ] || fail "armed strategy retained more than one failed close mutation after reconnect"
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited while waiting for the strategy close"
    sleep 1
done
$strategy_closed || fail "strategy-owned close did not complete after reconnect within $timeout_seconds seconds"

wait_for_matched_evaluations post-restart-readonly "$readonly_name" EXNESS:EURUSD eur1 eur5 "$restart_ready_ms"
wait_for_matched_evaluations post-restart-armed "$armed_name" "$armed_symbol" asset1 asset5 "$restart_ready_ms" any

control_port="$(<"$scenario/state/control.port")"
curl --silent --show-error --fail "http://127.0.0.1:$control_port/latency" > "$evidence/latency.json"

"$cli" stop "$armed_name" --state-dir "$scenario/state" --json > "$evidence/stop-armed.json"
"$cli" stop "$readonly_name" --state-dir "$scenario/state" --json > "$evidence/stop-readonly.json"
"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-account-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-account-final.json"
jq -e 'length == 0' "$evidence/positions-account-final.json" >/dev/null || fail "account is not flat after restart validation"
jq -e 'length == 0' "$evidence/orders-account-final.json" >/dev/null || fail "account has a pending order after restart validation"

deals_seen=false
for _ in $(seq 1 60); do
    "$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json > "$evidence/history-during-run.json"
    entry_count="$(jq --argjson ticket "$owned_ticket" '[.[] | select(.positionTicket == $ticket and .entry == "IN" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    exit_count="$(jq --argjson ticket "$owned_ticket" '[.[] | select(.positionTicket == $ticket and .entry == "OUT" and .lots == 0.01)] | length' "$evidence/history-during-run.json")"
    if [ "$entry_count" -eq 1 ] && [ "$exit_count" -eq 1 ]; then
        deals_seen=true
        break
    fi
    sleep 1
done
$deals_seen || fail "venue history did not expose exactly one entry and one exit deal"
jq -e --argjson ticket "$owned_ticket" --arg symbol "$venue_symbol" '
    length == 2 and
    all(.[]; .positionTicket == $ticket and .symbol == $symbol and .lots == 0.01) and
    ([.[] | select(.entry == "IN")] | length) == 1 and
    ([.[] | select(.entry == "OUT")] | length) == 1
' "$evidence/history-during-run.json" >/dev/null || fail "venue history contains foreign or malformed deals"

account_final="$(gateway_get /account)"
jq -e --argjson login "$expected_login" --arg server "$expected_server" '
    .login == $login and .server == $server and .trade_mode == 0 and
    .margin == 0 and .equity == .balance and .trade_allowed == true and .trade_expert == true
' <<< "$account_final" >/dev/null || fail "final account snapshot is inconsistent"
qkt_write_safe_account_snapshot "$evidence/gateway-account-final.json" <<< "$account_final"
unset account_final

mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no engine audit journal"
[ "${#transport_journals[@]}" -gt 0 ] || fail "daemon produced no MT5 transport journal"
readonly_audit_journals=()
armed_audit_journals=()
for journal in "${audit_journals[@]}"; do
    case "$journal" in
        *"/$readonly_name/"*) readonly_audit_journals+=("$journal") ;;
    esac
    case "$journal" in
        *"/$armed_name/"*) armed_audit_journals+=("$journal") ;;
    esac
done
[ "${#readonly_audit_journals[@]}" -gt 0 ] || fail "read-only strategy produced no audit journal"
[ "${#armed_audit_journals[@]}" -gt 0 ] || fail "armed strategy produced no audit journal"
for journal in "${audit_journals[@]}" "${transport_journals[@]}"; do
    jq -c . "$journal" >/dev/null || fail "journal is not valid JSONL: $journal"
done
[ -z "$(find "$scenario/state/state/audit-journal" "$scenario/state/state/mt5-transport-journal" -type f -name '*.dropped' -print -quit)" ] ||
    fail "a live journal reported dropped records"

readonly_order_events="$(jq -r --arg strategy "$readonly_name" 'select(
    .strategyId == $strategy and
    (.eventType == "com.qkt.events.BrokerEvent.OrderAccepted" or
     .eventType == "com.qkt.events.BrokerEvent.OrderFilled" or
     .eventType == "com.qkt.events.BrokerEvent.OrderRejected" or
     .eventType == "com.qkt.events.FillAccountedEvent" or
     .eventType == "com.qkt.events.DecisionOrderLinkedEvent" or
     .eventType == "com.qkt.events.RiskRejectedEvent" or
     .eventType == "com.qkt.events.OrderEvent")
) | 1' "${audit_journals[@]}" | count_records)"
[ "$readonly_order_events" -eq 0 ] || fail "read-only sibling emitted an order, fill, accounting, linkage, or rejection event"

readonly_pre_1m_matches="$(qkt_count_matched_evaluations "$readonly_name" eur1 EXNESS:EURUSD 1m "$session_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
readonly_pre_5m_matches="$(qkt_count_matched_evaluations "$readonly_name" eur5 EXNESS:EURUSD 5m "$session_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
readonly_post_1m_matches="$(qkt_count_matched_evaluations "$readonly_name" eur1 EXNESS:EURUSD 1m "$restart_ready_ms" -1 "${audit_journals[@]}")"
readonly_post_5m_matches="$(qkt_count_matched_evaluations "$readonly_name" eur5 EXNESS:EURUSD 5m "$restart_ready_ms" -1 "${audit_journals[@]}")"
[ "$readonly_pre_1m_matches" -gt 0 ] || fail "pre-restart read-only audit retained no matched 1m evaluation"
[ "$readonly_pre_5m_matches" -gt 0 ] || fail "pre-restart read-only audit retained no matched 5m evaluation"
[ "$readonly_post_1m_matches" -gt 0 ] || fail "post-restart read-only audit retained no matched 1m evaluation"
[ "$readonly_post_5m_matches" -gt 0 ] || fail "post-restart read-only audit retained no matched 5m evaluation"

readonly_pre_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$daemon_started_ms" "$restart_started_ms" "${readonly_audit_journals[@]}")"
readonly_pre_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$daemon_started_ms" "$restart_started_ms" "${readonly_audit_journals[@]}")"
readonly_post_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$restart_started_ms" -1 "${readonly_audit_journals[@]}")"
readonly_post_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$restart_started_ms" -1 "${readonly_audit_journals[@]}")"
[ "$readonly_pre_1m_warmups" -eq 80 ] || fail "pre-restart read-only 1m warmup count was $readonly_pre_1m_warmups; expected 80 pseudo-ticks"
[ "$readonly_pre_5m_warmups" -eq 80 ] || fail "pre-restart read-only 5m warmup count was $readonly_pre_5m_warmups; expected 80 pseudo-ticks"
[ "$readonly_post_1m_warmups" -eq 0 ] || fail "post-restart read-only 1m warmup count was $readonly_post_1m_warmups; expected no reconnect warmup"
[ "$readonly_post_5m_warmups" -eq 0 ] || fail "post-restart read-only 5m warmup count was $readonly_post_5m_warmups; expected no reconnect warmup"

armed_post_1m_matches="$(count_matched_evaluations_any "$armed_name" asset1 "$armed_symbol" 1m "$restart_ready_ms" -1 "${audit_journals[@]}")"
armed_post_5m_matches="$(count_matched_evaluations_any "$armed_name" asset5 "$armed_symbol" 5m "$restart_ready_ms" -1 "${audit_journals[@]}")"
armed_total_1m_warmups="$(qkt_count_warmup_pseudo_ticks "$armed_symbol" 60000 "$deploy_started_ms" -1 "${armed_audit_journals[@]}")"
armed_total_5m_warmups="$(qkt_count_warmup_pseudo_ticks "$armed_symbol" 300000 "$deploy_started_ms" -1 "${armed_audit_journals[@]}")"
armed_post_1m_warmups="$(qkt_count_warmup_pseudo_ticks "$armed_symbol" 60000 "$restart_started_ms" -1 "${armed_audit_journals[@]}")"
armed_post_5m_warmups="$(qkt_count_warmup_pseudo_ticks "$armed_symbol" 300000 "$restart_started_ms" -1 "${armed_audit_journals[@]}")"
[ "$armed_post_1m_matches" -gt 0 ] || fail "post-restart armed audit retained no matched 1m evaluation"
[ "$armed_post_5m_matches" -gt 0 ] || fail "post-restart armed audit retained no matched 5m evaluation"
[ "$armed_total_1m_warmups" -eq 40 ] || fail "armed 1m warmup count was $armed_total_1m_warmups; expected 40 pseudo-ticks"
[ "$armed_total_5m_warmups" -eq 40 ] || fail "armed 5m warmup count was $armed_total_5m_warmups; expected 40 pseudo-ticks"
[ "$armed_post_1m_warmups" -eq 0 ] || fail "post-restart armed 1m warmup count was $armed_post_1m_warmups; expected no reconnect warmup"
[ "$armed_post_5m_warmups" -eq 0 ] || fail "post-restart armed 5m warmup count was $armed_post_5m_warmups; expected no reconnect warmup"

live_tick_events="$(jq -r --arg symbol "$armed_symbol" '
    select(.eventType == "com.qkt.events.TickEvent" and .symbol == $symbol) | 1
' "${audit_journals[@]}" | count_records)"
[ "$live_tick_events" -gt 0 ] || fail "armed runtime retained no live tick evidence"

decisions="$(jq -r --arg strategy "$armed_name" 'select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
links="$(jq -r --arg strategy "$armed_name" 'select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
accepted="$(jq -r --arg strategy "$armed_name" 'select(.eventType == "com.qkt.events.BrokerEvent.OrderAccepted" and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
filled="$(jq -r --arg strategy "$armed_name" 'select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled" and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
accounted="$(jq -r --arg strategy "$armed_name" 'select(.eventType == "com.qkt.events.FillAccountedEvent" and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
rejected="$(jq -r --arg strategy "$armed_name" 'select((.eventType == "com.qkt.events.BrokerEvent.OrderRejected" or .eventType == "com.qkt.events.RiskRejectedEvent") and .strategyId == $strategy) | 1' "${audit_journals[@]}" | count_records)"
[ "$decisions" -ge 2 ] && [ "$decisions" -le 3 ] || fail "armed strategy did not retain the expected entry plus close decision set"
[ "$links" -ge 2 ] && [ "$links" -le 3 ] || fail "armed strategy did not retain the expected order-link set"
[ "$accepted" -eq 2 ] || fail "armed strategy did not accept exactly entry and exit"
[ "$filled" -eq 2 ] || fail "armed strategy did not fill exactly entry and exit"
[ "$accounted" -eq 2 ] || fail "armed strategy did not account exactly two fills"
[ "$rejected" -le 1 ] || fail "armed strategy retained more than one rejected close after reconnect"

order_posts="$(jq -r 'select(.method == "POST" and .path == "/order") | 1' "${transport_journals[@]}" | count_records)"
protection_posts="$(jq -r 'select(.method == "POST" and .path == "/modify_sl_tp") | 1' "${transport_journals[@]}" | count_records)"
close_posts="$(jq -r 'select(.method == "POST" and .path == "/close_position") | 1' "${transport_journals[@]}" | count_records)"
successful_close_posts="$(jq -r --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
    select(.method == "POST" and .path == "/close_position" and .ts >= $restartStartedMs and
        ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
        .responseCode >= 200 and .responseCode < 300 and .error == null) | 1
' "${transport_journals[@]}" | count_records)"
failed_close_posts="$(jq -r --argjson ticket "$owned_ticket" --argjson restartStartedMs "$restart_started_ms" '
    select(.method == "POST" and .path == "/close_position" and .ts >= $restartStartedMs and
        ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
        (.responseCode < 200 or .responseCode >= 300 or .error != null)) | 1
' "${transport_journals[@]}" | count_records)"
failed_entry_or_protection_mutations="$(jq -r '
        def no_changes_protection:
        # A protection modify that lands on the levels already placed is MT5 NO_CHANGES
        # (retcode 10025, HTTP 400): idempotent success for the engine and for this gate.
        .path == "/modify_sl_tp" and ((.responseBody | fromjson? | .mt5_error.retcode? // null) == 10025);
    def mt5_success:
        no_changes_protection or (
            (.responseBody | fromjson? | .result.retcode) as $retcode |
            $retcode == 10008 or $retcode == 10009 or $retcode == 10010);
    select(
        .method == "POST" and
        (.path == "/order" or .path == "/modify_sl_tp" or .path == "/position_close_partial" or .path == "/cancel_order") and
        (no_changes_protection | not) and
        (.responseCode < 200 or .responseCode >= 300 or .error != null or (mt5_success | not))
    ) | 1
' "${transport_journals[@]}" | count_records)"
[ "$order_posts" -eq 1 ] || fail "armed strategy did not issue exactly one entry order"
[ "$protection_posts" -eq 1 ] || fail "armed strategy did not issue exactly one protection update"
[ "$close_posts" -ge 1 ] && [ "$close_posts" -le 2 ] || fail "armed strategy did not retain the expected close-attempt envelope count"
[ "$successful_close_posts" -eq 1 ] || fail "armed strategy did not retain exactly one successful strategy close"
[ "$failed_close_posts" -le 1 ] || fail "armed strategy retained more than one failed close mutation after reconnect"
[ "$failed_entry_or_protection_mutations" -eq 0 ] || fail "armed strategy retained a failed entry or protection mutation"

jq -s -e \
    --argjson magic "$magic" \
    --arg symbol "$venue_symbol" \
    --argjson ticket "$owned_ticket" \
    --argjson restartStartedMs "$restart_started_ms" '
        def request: .requestBody | fromjson;
        [ .[] | select(
            (.path == "/order" and request.magic == $magic and request.symbol == $symbol) or
            (.path == "/modify_sl_tp" and (request.position | tostring) == ($ticket | tostring)) or
            (.path == "/close_position" and .ts >= $restartStartedMs and
                (request.position.ticket | tostring) == ($ticket | tostring))
        ) ] | length == (2 + ([ .[] | select(.path == "/close_position" and .ts >= $restartStartedMs and
            ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring))) ] | length))
    ' "${transport_journals[@]}" >/dev/null ||
    fail "venue mutations do not correlate to the expected magic, symbol, ticket, and restart timing"

disconnect_warnings="$(awk '/LiveTickFeed source disconnected; waiting up to .* for reconnect/ {count++} END {print count + 0}' "$scenario/logs/daemon.log")"
reconnect_infos="$(awk '/LiveTickFeed source reconnected; resuming/ {count++} END {print count + 0}' "$scenario/logs/daemon.log")"
[ "$disconnect_warnings" -gt 0 ] || fail "order gateway restart retained no feed disconnect warning"
[ "$reconnect_infos" -gt 0 ] || fail "order gateway restart retained no feed reconnect info"

stale_events="$(rg -c 'market data .* STALE:' "$scenario/logs/daemon.log" || printf '0\n')"
recovery_events="$(rg -c 'market data .* healthy again' "$scenario/logs/daemon.log" || printf '0\n')"
[ "$recovery_events" -ge "$stale_events" ] || fail "market-data stale episode did not recover before shutdown"

resource_samples="$(($(wc -l < "$evidence/resources.csv") - 1))"
[ "$resource_samples" -gt 0 ] || fail "daemon resource sampling produced no observations"
health_samples="$(awk 'END {print NR + 0}' "$evidence/health.jsonl")"
[ "$health_samples" -eq "$resource_samples" ] || fail "daemon health and resource sample counts differ"
max_inbound_queue="$(jq -s '[.[].perStrategy[]?.inboundQueueDepth] | max // 0' "$evidence/health.jsonl")"
max_dropped_ticks="$(jq -s '[.[].perStrategy[]?.droppedTicks] | max // 0' "$evidence/health.jsonl")"
[ "$max_dropped_ticks" -eq 0 ] || fail "daemon reported $max_dropped_ticks dropped live tick(s)"
tick_latency="$(jq -c --arg strategy "$armed_name" '.[$strategy].strategies[$strategy].TICK_PROCESSING' "$evidence/latency.json")"
max_cpu_percent="$(awk -F, 'NR > 1 && $2 > max {max=$2} END {printf "%.2f", max + 0}' "$evidence/resources.csv")"
max_rss_kb="$(awk -F, 'NR > 1 && $3 > max {max=$3} END {print max + 0}' "$evidence/resources.csv")"
max_threads="$(awk -F, 'NR > 1 && $4 > max {max=$4} END {print max + 0}' "$evidence/resources.csv")"

initial_balance="$(jq -er '.balance' "$evidence/gateway-account-initial.json")"
final_balance="$(jq -er '.balance' "$evidence/gateway-account-final.json")"
balance_delta="$(awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}')"
deal_net="$(
    jq -r --argjson ticket "$owned_ticket" '
        [.[] | select(.positionTicket == $ticket) | ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
    ' "$evidence/history-during-run.json" | awk '{printf "%.2f", $1}'
)"
[ "$balance_delta" = "$deal_net" ] || fail "account balance delta $balance_delta differs from owned deal net $deal_net"

qkt_sanitize_account_transport_journals "$scenario/state/state/mt5-transport-journal"
qkt_redact_account_identity_log "$scenario/logs/daemon.log" "$expected_login" "$expected_server"
qkt_assert_no_retained_account_identity "$scenario" "$expected_login" "$expected_server" ||
    fail "account identity reached retained artifacts"
if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "broker credential was persisted in the scenario artifacts"
fi
if rg --text --pcre2 --quiet 'MT5_PASSWORD=(?!<redacted>)' "$scenario"; then
    fail "gateway password metadata was persisted in the scenario artifacts"
fi

jq --argjson ticket "$owned_ticket" '
    .ownedPositionTickets = [$ticket] | .ownedOrderTickets = [] | .status = "verified_flat"
' "$scenario/cleanup.json" > "$scenario/cleanup.json.tmp"
mv "$scenario/cleanup.json.tmp" "$scenario/cleanup.json"

qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health-post-restart.json")"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktVersion "$qkt_version" \
    --arg qktCommit "$qkt_commit" \
    --argjson qktDirty "$qkt_dirty" \
    --arg gatewayVersion "$gateway_version" \
    --arg readonly "$readonly_name" \
    --arg armed "$armed_name" \
    --arg symbol "$armed_symbol" \
    --argjson magic "$magic" \
    --argjson ticket "$owned_ticket" \
    --arg phaseTimeoutSeconds "$phase_timeout_seconds" \
    --arg timeoutSeconds "$timeout_seconds" \
    --arg restartStartedMs "$restart_started_ms" \
    --arg restartReadyMs "$restart_ready_ms" \
    --arg disconnectWarnings "$disconnect_warnings" \
    --arg reconnectInfos "$reconnect_infos" \
    --arg readonlyPre1mMatches "$readonly_pre_1m_matches" \
    --arg readonlyPre5mMatches "$readonly_pre_5m_matches" \
    --arg readonlyPost1mMatches "$readonly_post_1m_matches" \
    --arg readonlyPost5mMatches "$readonly_post_5m_matches" \
    --arg readonlyPre1mWarmups "$readonly_pre_1m_warmups" \
    --arg readonlyPre5mWarmups "$readonly_pre_5m_warmups" \
    --arg readonlyPost1mWarmups "$readonly_post_1m_warmups" \
    --arg readonlyPost5mWarmups "$readonly_post_5m_warmups" \
    --arg armedPost1mMatches "$armed_post_1m_matches" \
    --arg armedPost5mMatches "$armed_post_5m_matches" \
    --arg armedWarmups1m "$armed_total_1m_warmups" \
    --arg armedWarmups5m "$armed_total_5m_warmups" \
    --arg armedPost1mWarmups "$armed_post_1m_warmups" \
    --arg armedPost5mWarmups "$armed_post_5m_warmups" \
    --arg liveTickEvents "$live_tick_events" \
    --arg decisions "$decisions" \
    --arg links "$links" \
    --arg accepted "$accepted" \
    --arg filled "$filled" \
    --arg accounted "$accounted" \
    --arg rejected "$rejected" \
    --arg orderPosts "$order_posts" \
    --arg protectionPosts "$protection_posts" \
    --arg closePosts "$close_posts" \
    --arg successfulClosePosts "$successful_close_posts" \
    --arg failedClosePosts "$failed_close_posts" \
    --argjson positionPersistedAcrossRestart "$position_persisted_across_restart" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" \
    --arg resourceSamples "$resource_samples" \
    --arg healthSamples "$health_samples" \
    --arg maxInboundQueue "$max_inbound_queue" \
    --arg maxDroppedTicks "$max_dropped_ticks" \
    --argjson tickLatency "$tick_latency" \
    --arg maxCpuPercent "$max_cpu_percent" \
    --arg maxRssKb "$max_rss_kb" \
    --arg maxThreads "$max_threads" \
    --arg balanceDelta "$balance_delta" \
    --arg dealNet "$deal_net" '
        {
          schema:"qkt-live-validation-order-gateway-restart-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          readonlySibling:$readonly,
          ownerStrategy:$armed,
          symbol:$symbol,
          magic:$magic,
          positionTicket:$ticket,
          phaseTimeoutSeconds:($phaseTimeoutSeconds|tonumber),
          timeoutSeconds:($timeoutSeconds|tonumber),
          gatewayRestart:{
            containerRestarted:true,
            restartStartedAtMs:($restartStartedMs|tonumber),
            restartReadyAtMs:($restartReadyMs|tonumber),
            disconnectWarnings:($disconnectWarnings|tonumber),
            reconnectInfos:($reconnectInfos|tonumber)
          },
          bars:{
            readonly:{
              preRestart:{
                m1MatchedEvaluations:($readonlyPre1mMatches|tonumber),
                m5MatchedEvaluations:($readonlyPre5mMatches|tonumber),
                m1WarmupPseudoTicks:($readonlyPre1mWarmups|tonumber),
                m5WarmupPseudoTicks:($readonlyPre5mWarmups|tonumber)
              },
              postRestart:{
                m1MatchedEvaluations:($readonlyPost1mMatches|tonumber),
                m5MatchedEvaluations:($readonlyPost5mMatches|tonumber),
                m1WarmupPseudoTicks:($readonlyPost1mWarmups|tonumber),
                m5WarmupPseudoTicks:($readonlyPost5mWarmups|tonumber)
              }
            },
            armed:{
              warmupPseudoTicks:{
                m1:($armedWarmups1m|tonumber),
                m5:($armedWarmups5m|tonumber)
              },
              postRestart:{
                m1MatchedEvaluations:($armedPost1mMatches|tonumber),
                m5MatchedEvaluations:($armedPost5mMatches|tonumber),
                m1WarmupPseudoTicks:($armedPost1mWarmups|tonumber),
                m5WarmupPseudoTicks:($armedPost5mWarmups|tonumber)
              },
              liveTickEvents:($liveTickEvents|tonumber)
            }
          },
          orders:{
            decisions:($decisions|tonumber),
            decisionOrderLinks:($links|tonumber),
            accepted:($accepted|tonumber),
            filled:($filled|tonumber),
            fillAccounted:($accounted|tonumber),
            rejected:($rejected|tonumber),
            orderPosts:($orderPosts|tonumber),
            protectionPosts:($protectionPosts|tonumber),
            closePosts:($closePosts|tonumber),
            successfulClosePosts:($successfulClosePosts|tonumber),
            failedClosePosts:($failedClosePosts|tonumber),
            closeAfterRestart:true,
            retryCloseSucceeded:(($failedClosePosts|tonumber) > 0),
            positionPersistedAcrossRestart:$positionPersistedAcrossRestart
          },
          accounting:{
            balanceDelta:$balanceDelta,
            dealNet:$dealNet,
            finalFlat:true,
            finalOrders:0
          },
          staleEvents:($staleEvents|tonumber),
          recoveredStaleEvents:($recoveryEvents|tonumber),
          resourceSamples:($resourceSamples|tonumber),
          healthSamples:($healthSamples|tonumber),
          health:{maxInboundQueue:($maxInboundQueue|tonumber),maxDroppedTicks:($maxDroppedTicks|tonumber)},
          resources:{maxCpuPercent:($maxCpuPercent|tonumber),maxRssKb:($maxRssKb|tonumber),maxThreads:($maxThreads|tonumber)},
          latency:{tickProcessing:$tickLatency}
        }
    ' > "$evidence/result.json"

for transient in "$scenario/state/control.token" "$scenario/state/daemon.pid"; do
    if [ -e "$transient" ]; then
        unlink "$transient"
    fi
done

printf 'passed %s\n' "$evidence/result.json"
