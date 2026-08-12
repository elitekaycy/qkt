#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
# shellcheck source=scripts/live-validation/lib/container-load-evidence.sh
source "$repo_root/scripts/live-validation/lib/container-load-evidence.sh"
# shellcheck source=scripts/live-validation/lib/catalog-startup-window.sh
source "$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"

usage() {
    cat <<'EOF'
Usage: run-readonly-deployed-gateway-restart.sh --scenario DIR --gateway-container NAME [--cli PATH]
       run-readonly-deployed-gateway-restart.sh --scenario DIR --gateway-container NAME [--cli PATH] --verify-only

Verifies a prepared live-validation scenario, starts an empty daemon against the real localhost MT5
gateway, deploys the prepared read-only strategy through the control plane, waits for exact
pre-restart M1/M5 evidence, restarts the named Docker gateway container, proves feed disconnect and
reconnect, then requires exact post-restart M1/M5 evidence again with the account still flat and
unchanged.
EOF
}

fail() {
    printf 'run-readonly-deployed-gateway-restart: %s\n' "$1" >&2
    exit 1
}

scenario=""
gateway_container=""
cli="$repo_root/build/install/qkt/bin/qkt"
phase_timeout_seconds=370
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --gateway-container) gateway_container="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --phase-timeout-seconds) phase_timeout_seconds="${2:-}"; shift 2 ;;
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

bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t readonly_strategies < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
[ "${#readonly_strategies[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
readonly_strategy="${readonly_strategies[0]}"
strategy_name="$(basename "$readonly_strategy" .qkt)"
"$cli" parse "$readonly_strategy" >/dev/null

if $verify_only; then
    printf 'verified %s via %s\n' "$readonly_strategy" "$gateway_container"
    exit 0
fi

[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"
command -v docker >/dev/null || fail "docker is required"

gateway_url="$(jq -r '.gatewayUrl' "$scenario/scenario.json")"
expected_login="$(jq -r '.account.login' "$scenario/expected.json")"
expected_server="$(jq -r '.account.server' "$scenario/expected.json")"
expected_leverage="$(jq -r '.account.leverage' "$scenario/expected.json")"
expected_balance="$(jq -r '.account.startingBalance' "$scenario/expected.json")"
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
                  schema:"qkt-live-readonly-deployed-gateway-restart-startup-window-v1",
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
            fail "broker tick clock did not enter the deployed gateway-restart startup window within 260 seconds"
        total_wait_seconds=$((total_wait_seconds + sleep_seconds))
        sleep "$sleep_seconds"
    done
    fail "broker tick clock did not enter the bounded deployed gateway-restart startup window after three observations"
}

gateway_get /health > "$evidence/gateway-health-initial.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$evidence/gateway-health-initial.json" >/dev/null || fail "gateway is not healthy and connected"

docker inspect "$gateway_container" 2>/dev/null | sanitize_container_inspect > "$evidence/gateway-container-initial.json" ||
    fail "gateway container is not inspectable: $gateway_container"
jq -e '.[0].State.Running == true' "$evidence/gateway-container-initial.json" >/dev/null ||
    fail "gateway container is not running"

gateway_get /account > "$evidence/gateway-account-initial.json"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --argjson leverage "$expected_leverage" \
    --arg balance "$expected_balance" '
        .login == $login and .server == $server and .trade_mode == 0 and .currency == "USD" and
        .leverage == $leverage and .balance == ($balance | tonumber) and .equity == ($balance | tonumber) and
        .trade_allowed == true and .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the demo allowlist"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"

"$cli" preflight "$readonly_strategy" --config "$config" > "$evidence/preflight.log" 2>&1

daemon_pid=""
stop_daemon() {
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
}
trap stop_daemon EXIT

QKT_LATENCY_TRACKING=1 QKT_STATE_DIR="$scenario/state" "$cli" daemon start \
    --config "$config" \
    --state-dir "$scenario/state" \
    > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!

ready=false
for _ in $(seq 1 60); do
    if ! kill -0 "$daemon_pid" 2>/dev/null; then
        fail "daemon exited before becoming ready"
    fi
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-empty.json" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail "daemon did not become ready within 60 seconds"

jq -e '.status == "ok" and .strategies == 0 and (.perStrategy | length) == 0' \
    "$evidence/daemon-status-empty.json" >/dev/null ||
    fail "daemon did not start empty; deployed restart validation requires zero auto-loaded strategies"
"$cli" list --state-dir "$scenario/state" --json > "$evidence/list-empty.json"
jq -e 'length == 0' "$evidence/list-empty.json" >/dev/null ||
    fail "daemon list was not empty before control-plane deploy"

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

wait_for_status_running() {
    local output_path="$1"
    local deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited while waiting for running strategy state"
        fi
        if "$cli" daemon status --state-dir "$scenario/state" --json > "$output_path" 2>/dev/null &&
            jq -e --arg strategy "$strategy_name" '
                .status == "ok" and .strategies == 1 and
                .perStrategy[0].name == $strategy and .perStrategy[0].running == true and
                .perStrategy[0].halted == false and .perStrategy[0].droppedTicks == 0
            ' "$output_path" >/dev/null; then
            return
        fi
        sleep 1
    done
    fail "deployed strategy did not reach running state"
}

wait_for_matched_evaluations() {
    local phase="$1"
    local after_ms="$2"
    local deadline=$((SECONDS + phase_timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited during $phase"
        fi
        sample_runtime "$phase"
        local audits
        audits="$(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)"
        if [ -n "$audits" ]; then
            local m1_matches m5_matches
            m1_matches="$(qkt_count_matched_evaluations "$strategy_name" eur1 EXNESS:EURUSD 1m "$after_ms" -1 $audits)"
            m5_matches="$(qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$after_ms" -1 $audits)"
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
    fail "$phase did not retain log marker: $marker"
}

wait_for_gateway_health() {
    local output_path="$1"
    local deadline=$((SECONDS + 180))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if gateway_get /health > "$output_path" 2>/dev/null &&
            jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
                "$output_path" >/dev/null; then
            return
        fi
        sleep 2
    done
    fail "gateway did not return to healthy connected state after container restart"
}

wait_for_journal_action() {
    local action="$1"
    local deadline=$((SECONDS + 30))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if find "$scenario/state/state/journal" -type f -print0 2>/dev/null |
            xargs -0 cat 2>/dev/null |
            rg --fixed-strings --quiet "\"action\":\"$action\""; then
            return
        fi
        sleep 1
    done
    fail "daemon journal did not retain action=$action"
}

wait_for_startup_window
deploy_started_ms="$(date +%s%3N)"
"$cli" deploy "$readonly_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/deploy.json"
jq -e --arg strategy "$strategy_name" '.name == $strategy and .state == "running"' \
    "$evidence/deploy.json" >/dev/null || fail "control-plane deploy did not enter running state"
wait_for_status_running "$evidence/daemon-status-deployed.json"
wait_for_journal_action deploy
wait_for_matched_evaluations deploy "$deploy_started_ms"

restart_started_ms="$(date +%s%3N)"
docker restart "$gateway_container" > "$evidence/gateway-container-restart.log"
restart_ready_ms="$(date +%s%3N)"
docker inspect "$gateway_container" | sanitize_container_inspect > "$evidence/gateway-container-restarted.json"
jq -e '.[0].State.Running == true' "$evidence/gateway-container-restarted.json" >/dev/null ||
    fail "gateway container is not running after restart"
wait_for_gateway_health "$evidence/gateway-health-post-restart.json"
wait_for_log_marker 'LiveTickFeed source disconnected; waiting up to' reconnect
wait_for_log_marker 'LiveTickFeed source reconnected; resuming' reconnect
wait_for_matched_evaluations post-restart "$restart_ready_ms"

"$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-final.json"
control_port="$(<"$scenario/state/control.port")"
curl --silent --show-error --fail "http://127.0.0.1:$control_port/latency" > "$evidence/latency.json"

"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-final.json"
"$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json > "$evidence/history-during-run.json"
gateway_get /account > "$evidence/gateway-account-final.json"
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "deployed gateway-restart run ended with an open position"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "deployed gateway-restart run ended with a pending order"
jq -e 'length == 0' "$evidence/history-during-run.json" >/dev/null || fail "deployed gateway-restart run unexpectedly produced a venue deal"
jq -e --slurpfile initial "$evidence/gateway-account-initial.json" '
    .login == $initial[0].login and
    .server == $initial[0].server and
    .balance == $initial[0].balance and
    .equity == $initial[0].equity and
    .trade_allowed == true and
    .trade_expert == true
' "$evidence/gateway-account-final.json" >/dev/null || fail "deployed gateway-restart run changed the venue account snapshot"

stale_events="$(rg -c 'market data .* STALE:' "$scenario/logs/daemon.log" || printf '0\n')"
recovery_events="$(rg -c 'market data .* healthy again' "$scenario/logs/daemon.log" || printf '0\n')"
[ "$recovery_events" -ge "$stale_events" ] || fail "market-data stale episode did not recover before shutdown"

mapfile -t audit_journals < <(find "$scenario/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t transport_journals < <(find "$scenario/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#audit_journals[@]}" -gt 0 ] || fail "daemon produced no engine audit journal"
[ "${#transport_journals[@]}" -gt 0 ] || fail "daemon produced no MT5 transport journal"
for journal in "${audit_journals[@]}" "${transport_journals[@]}"; do
    jq -c . "$journal" >/dev/null || fail "journal is not valid JSONL: $journal"
done

order_events="$(jq -r 'select(
    ((.eventType // "") | test("BrokerEvent[.]Order(Accepted|Filled|Rejected)$")) or
    ((.eventType // "") | test("[.](RiskRejectedEvent|OrderEvent|FillAccountedEvent|DecisionOrderLinkedEvent)$"))
) | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$order_events" -eq 0 ] || fail "deployed gateway restart emitted an order, fill, accounting, linkage, or rejection event"

warmup_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.WarmupTickEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
live_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.TickEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
stream_candle_events="$(jq -r 'select(.eventType == "com.qkt.events.StreamCandleEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
strategy_evaluations="$(jq -r 'select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$warmup_tick_events" -gt 0 ] || fail "audit journal retained no warmup ticks"
[ "$live_tick_events" -gt 0 ] || fail "audit journal retained no live ticks"
[ "$stream_candle_events" -gt 0 ] || fail "audit journal retained no stream candles"
[ "$strategy_evaluations" -gt 0 ] || fail "audit journal retained no strategy candle evaluations"

pre_1m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur1 EXNESS:EURUSD 1m "$deploy_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
pre_5m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$deploy_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
post_1m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur1 EXNESS:EURUSD 1m "$restart_ready_ms" -1 "${audit_journals[@]}")"
post_5m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$restart_ready_ms" -1 "${audit_journals[@]}")"
[ "$pre_1m_matches" -gt 0 ] || fail "pre-restart audit retained no matched 1m evaluation"
[ "$pre_5m_matches" -gt 0 ] || fail "pre-restart audit retained no matched 5m evaluation"
[ "$post_1m_matches" -gt 0 ] || fail "post-restart audit retained no matched 1m evaluation"
[ "$post_5m_matches" -gt 0 ] || fail "post-restart audit retained no matched 5m evaluation"

pre_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$deploy_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
pre_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$deploy_started_ms" "$restart_started_ms" "${audit_journals[@]}")"
post_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$restart_started_ms" -1 "${audit_journals[@]}")"
post_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$restart_started_ms" -1 "${audit_journals[@]}")"
[ "$pre_1m_warmups" -eq 80 ] || fail "pre-restart 1m warmup count was $pre_1m_warmups; expected 80 pseudo-ticks"
[ "$pre_5m_warmups" -eq 80 ] || fail "pre-restart 5m warmup count was $pre_5m_warmups; expected 80 pseudo-ticks"
[ "$post_1m_warmups" -eq 0 ] || fail "post-restart 1m warmup count was $post_1m_warmups; expected no reconnect warmup"
[ "$post_5m_warmups" -eq 0 ] || fail "post-restart 5m warmup count was $post_5m_warmups; expected no reconnect warmup"

gateway_mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' \
    "${transport_journals[@]}" | awk 'END {print NR + 0}')"
[ "$gateway_mutations" -eq 0 ] || fail "deployed gateway restart issued a mutating gateway request"

disconnect_warnings="$(awk '/LiveTickFeed source disconnected; waiting up to .* for reconnect/ {count++} END {print count + 0}' "$scenario/logs/daemon.log")"
reconnect_infos="$(awk '/LiveTickFeed source reconnected; resuming/ {count++} END {print count + 0}' "$scenario/logs/daemon.log")"
[ "$disconnect_warnings" -gt 0 ] || fail "deployed gateway restart retained no feed disconnect warning"
[ "$reconnect_infos" -gt 0 ] || fail "deployed gateway restart retained no feed reconnect info"

resource_samples="$(($(wc -l < "$evidence/resources.csv") - 1))"
[ "$resource_samples" -gt 0 ] || fail "daemon resource sampling produced no observations"
health_samples="$(awk 'END {print NR + 0}' "$evidence/health.jsonl")"
[ "$health_samples" -eq "$resource_samples" ] || fail "daemon health and resource sample counts differ"
max_inbound_queue="$(jq -s '[.[].perStrategy[]?.inboundQueueDepth] | max // 0' "$evidence/health.jsonl")"
max_dropped_ticks="$(jq -s '[.[].perStrategy[]?.droppedTicks] | max // 0' "$evidence/health.jsonl")"
[ "$max_dropped_ticks" -eq 0 ] || fail "daemon reported $max_dropped_ticks dropped live tick(s)"
tick_latency="$(jq -c --arg strategy "$strategy_name" '.[$strategy].strategies[$strategy].TICK_PROCESSING' "$evidence/latency.json")"
max_cpu_percent="$(awk -F, 'NR > 1 && $2 > max {max=$2} END {printf "%.2f", max + 0}' "$evidence/resources.csv")"
max_rss_kb="$(awk -F, 'NR > 1 && $3 > max {max=$3} END {print max + 0}' "$evidence/resources.csv")"
max_threads="$(awk -F, 'NR > 1 && $4 > max {max=$4} END {print max + 0}' "$evidence/resources.csv")"
qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health-post-restart.json")"
qkt_commit="$(jq -r '.qktCommit' "$scenario/scenario.json")"
qkt_dirty="$(jq -r '.qktDirty' "$scenario/scenario.json")"
finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktVersion "$qkt_version" \
    --arg qktCommit "$qkt_commit" \
    --arg gatewayVersion "$gateway_version" \
    --argjson qktDirty "$qkt_dirty" \
    --arg phaseTimeoutSeconds "$phase_timeout_seconds" \
    --arg restartStartedMs "$restart_started_ms" \
    --arg restartReadyMs "$restart_ready_ms" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" \
    --arg disconnectWarnings "$disconnect_warnings" \
    --arg reconnectInfos "$reconnect_infos" \
    --arg warmupTickEvents "$warmup_tick_events" \
    --arg liveTickEvents "$live_tick_events" \
    --arg streamCandleEvents "$stream_candle_events" \
    --arg strategyEvaluations "$strategy_evaluations" \
    --arg orderEvents "$order_events" \
    --arg gatewayMutations "$gateway_mutations" \
    --arg pre1mMatches "$pre_1m_matches" \
    --arg pre5mMatches "$pre_5m_matches" \
    --arg post1mMatches "$post_1m_matches" \
    --arg post5mMatches "$post_5m_matches" \
    --arg pre1mWarmups "$pre_1m_warmups" \
    --arg pre5mWarmups "$pre_5m_warmups" \
    --arg post1mWarmups "$post_1m_warmups" \
    --arg post5mWarmups "$post_5m_warmups" \
    --arg resourceSamples "$resource_samples" \
    --arg healthSamples "$health_samples" \
    --arg maxInboundQueue "$max_inbound_queue" \
    --arg maxDroppedTicks "$max_dropped_ticks" \
    --argjson tickLatency "$tick_latency" \
    --arg maxCpuPercent "$max_cpu_percent" \
    --arg maxRssKb "$max_rss_kb" \
    --arg maxThreads "$max_threads" '
        {
          schema:"qkt-live-validation-readonly-deployed-gateway-restart-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          phaseTimeoutSeconds:($phaseTimeoutSeconds|tonumber),
          controlPlane:{daemonStartedEmpty:true,deployedViaControlPlane:true},
          gatewayRestart:{
            containerRestarted:true,
            restartStartedAtMs:($restartStartedMs|tonumber),
            restartReadyAtMs:($restartReadyMs|tonumber),
            disconnectWarnings:($disconnectWarnings|tonumber),
            reconnectInfos:($reconnectInfos|tonumber)
          },
          bars:{
            preRestart:{m1MatchedEvaluations:($pre1mMatches|tonumber),m5MatchedEvaluations:($pre5mMatches|tonumber),
              m1WarmupPseudoTicks:($pre1mWarmups|tonumber),m5WarmupPseudoTicks:($pre5mWarmups|tonumber)},
            postRestart:{m1MatchedEvaluations:($post1mMatches|tonumber),m5MatchedEvaluations:($post5mMatches|tonumber),
              m1WarmupPseudoTicks:($post1mWarmups|tonumber),m5WarmupPseudoTicks:($post5mWarmups|tonumber)}
          },
          staleEvents:($staleEvents|tonumber),
          recoveredStaleEvents:($recoveryEvents|tonumber),
          warmupTickEvents:($warmupTickEvents|tonumber),
          liveTickEvents:($liveTickEvents|tonumber),
          streamCandleEvents:($streamCandleEvents|tonumber),
          strategyCandleEvaluations:($strategyEvaluations|tonumber),
          orderEvents:($orderEvents|tonumber),
          gatewayMutations:($gatewayMutations|tonumber),
          venueDealsDuringRun:0,
          accountUnchanged:true,
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
if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$scenario"; then
    fail "broker credential was persisted in the scenario artifacts"
fi

printf 'passed %s\n' "$evidence/result.json"
