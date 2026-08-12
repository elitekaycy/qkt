#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
# shellcheck source=scripts/live-validation/lib/container-load-evidence.sh
source "$repo_root/scripts/live-validation/lib/container-load-evidence.sh"

usage() {
    cat <<'EOF'
Usage: run-readonly-resync.sh --scenario DIR [--cli PATH] [--phase-timeout-seconds N]
       run-readonly-resync.sh --scenario DIR [--cli PATH] --verify-only

Verifies a prepared live-validation scenario, then exercises the already-deployed
daemon control-plane path against the real localhost MT5 gateway. The daemon starts
with no load directory, deploys the prepared read-only strategy through the control
plane, waits for exact M1/M5 bar evidence, resyncs the same deployment to a generated
read-only replacement, and proves exact M1/M5 bar evidence again after replacement.
The account must remain flat and unchanged throughout.
EOF
}

fail() {
    printf 'run-readonly-resync: %s\n' "$1" >&2
    exit 1
}

scenario=""
cli="$repo_root/build/install/qkt/bin/qkt"
phase_timeout_seconds=370
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --phase-timeout-seconds) phase_timeout_seconds="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
scenario="$(realpath "$scenario")"
[ -d "$scenario" ] || fail "scenario directory does not exist: $scenario"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$phase_timeout_seconds" =~ ^[0-9]+$ ]] || fail "--phase-timeout-seconds must be an integer"
[ "$phase_timeout_seconds" -ge 310 ] || fail "--phase-timeout-seconds must be at least 310 to guarantee an M5 close"

bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

mapfile -t readonly_strategies < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
[ "${#readonly_strategies[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
readonly_strategy="${readonly_strategies[0]}"
strategy_name="$(basename "$readonly_strategy" .qkt)"

generated_dir="$(mktemp -d)"
cleanup_generated() {
    rm -rf "$generated_dir"
}
trap cleanup_generated EXIT

replacement_strategy="$generated_dir/${strategy_name}_resync.qkt"
cat > "$replacement_strategy" <<EOF
STRATEGY ${strategy_name} VERSION 2

SYMBOLS
    eur1 = EXNESS:EURUSD EVERY 1m WARMUP 20 BARS,
    eur5 = EXNESS:EURUSD EVERY 5m WARMUP 20 BARS

LET eur1_ema = ema(eur1.close, 3),
    eur1_rsi = rsi(eur1.close, 5),
    eur5_ema = ema(eur5.close, 3),
    eur5_atr = atr(eur5, 5)

RULES
    WHEN eur1_ema IS NOT NULL AND eur1_rsi IS NOT NULL
    THEN LOG "resync trace generation={generation} timeframe={timeframe} ema={ema} rsi={rsi} close={bar_close}"
         generation="2" timeframe="1m" ema=eur1_ema rsi=eur1_rsi bar_close=eur1.close

    WHEN eur5_ema IS NOT NULL AND eur5_atr IS NOT NULL
    THEN LOG "resync trace generation={generation} timeframe={timeframe} ema={ema} atr={atr} close={bar_close}"
         generation="2" timeframe="5m" ema=eur5_ema atr=eur5_atr bar_close=eur5.close
EOF

"$cli" parse "$readonly_strategy" >/dev/null
"$cli" parse "$replacement_strategy" >/dev/null

if $verify_only; then
    printf 'verified %s -> %s\n' "$readonly_strategy" "$replacement_strategy"
    exit 0
fi

[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[ -z "$(find "$scenario/evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
    fail "evidence directory is not empty; prepare a fresh scenario"

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

gateway_get /health > "$evidence/gateway-health.json"
jq -e '
    .ok == true and
    .status == "healthy" and
    .mt5_status == "connected" and
    .kill_switch_active == false
' "$evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"

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
        .equity == ($balance | tonumber) and
        .trade_allowed == true and
        .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the demo allowlist"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"

"$cli" preflight "$readonly_strategy" --config "$config" > "$evidence/preflight-original.log" 2>&1
"$cli" preflight "$replacement_strategy" --config "$config" > "$evidence/preflight-replacement.log" 2>&1

daemon_pid=""
stop_daemon() {
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
}
trap 'stop_daemon; cleanup_generated' EXIT

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
    fail "daemon did not start empty; already-deployed validation requires zero auto-loaded strategies"
"$cli" list --state-dir "$scenario/state" --json > "$evidence/list-empty.json"
jq -e 'length == 0' "$evidence/list-empty.json" >/dev/null ||
    fail "daemon list was not empty before control-plane deploy"

resource_samples_path="$evidence/resources.csv"
health_samples_path="$evidence/health.jsonl"
printf 'elapsed_seconds,cpu_percent,rss_kb,threads,strategies\n' > "$resource_samples_path"
: > "$health_samples_path"
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
        >> "$resource_samples_path"
    jq -c --arg label "$label" '. + {sampleLabel:$label}' <<<"$status_json" >> "$health_samples_path"
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
                .status == "ok" and
                .strategies == 1 and
                .perStrategy[0].name == $strategy and
                .perStrategy[0].running == true and
                .perStrategy[0].halted == false and
                .perStrategy[0].droppedTicks == 0
            ' "$output_path" >/dev/null; then
            return
        fi
        sleep 1
    done
    fail "strategy did not reach running state"
}

wait_for_status_present() {
    local output_path="$1"
    local deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited while waiting for resynced strategy presence"
        fi
        if "$cli" daemon status --state-dir "$scenario/state" --json > "$output_path" 2>/dev/null &&
            jq -e --arg strategy "$strategy_name" '
                .status == "ok" and
                .strategies == 1 and
                .perStrategy[0].name == $strategy and
                .perStrategy[0].running == true
            ' "$output_path" >/dev/null; then
            return
        fi
        sleep 1
    done
    fail "resynced strategy did not return to daemon status"
}

wait_for_log_markers() {
    local marker_a="$1"
    local marker_b="$2"
    local phase="$3"
    local deadline=$((SECONDS + phase_timeout_seconds))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "$daemon_pid" 2>/dev/null; then
            fail "daemon exited during $phase"
        fi
        sample_runtime "$phase"
        if rg --fixed-strings --quiet "$marker_a" "$scenario/logs/daemon.log" &&
            rg --fixed-strings --quiet "$marker_b" "$scenario/logs/daemon.log"; then
            return
        fi
        sleep 10
    done
    fail "$phase lacked exact post-deploy matched M1/M5 bars and evaluations within $phase_timeout_seconds seconds"
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
    fail "$phase lacked exact post-deploy matched M1/M5 bars and evaluations within $phase_timeout_seconds seconds"
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

deploy_started_ms="$(date +%s%3N)"
"$cli" deploy "$readonly_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/deploy.json"
jq -e --arg strategy "$strategy_name" '.name == $strategy and .state == "running"' \
    "$evidence/deploy.json" >/dev/null || fail "control-plane deploy did not enter running state"
wait_for_status_running "$evidence/daemon-status-deployed.json"
wait_for_journal_action deploy
wait_for_matched_evaluations deploy "$deploy_started_ms"

"$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/status-before-resync.txt"
"$cli" list --state-dir "$scenario/state" > "$evidence/list-before-resync.txt"
resync_started_ms="$(date +%s%3N)"
"$cli" resync "$replacement_strategy" --as "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/resync.json"
resync_ready_ms="$(date +%s%3N)"
jq -e --arg strategy "$strategy_name" '.name == $strategy and .state == "running"' \
    "$evidence/resync.json" >/dev/null || fail "control-plane resync did not return running state"
wait_for_status_present "$evidence/daemon-status-resynced.json"
wait_for_journal_action resync
"$cli" status "$strategy_name" --state-dir "$scenario/state" > "$evidence/status-after-resync-pre-resume.txt"
"$cli" resume "$strategy_name" --state-dir "$scenario/state" --json > "$evidence/resume-after-resync.json"
jq -e '.state == "resumed"' "$evidence/resume-after-resync.json" >/dev/null ||
    fail "control-plane resume did not clear the post-resync halt state"
wait_for_journal_action resume
wait_for_status_running "$evidence/daemon-status-resumed.json"
resume_ready_ms="$(date +%s%3N)"
wait_for_matched_evaluations resync "$resume_ready_ms"

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
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "read-only resync run ended with an open position"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "read-only resync run ended with a pending order"
jq -e 'length == 0' "$evidence/history-during-run.json" >/dev/null || fail "read-only resync run unexpectedly produced a venue deal"
jq -e --slurpfile initial "$evidence/gateway-account-initial.json" '
    .login == $initial[0].login and
    .server == $initial[0].server and
    .balance == $initial[0].balance and
    .equity == $initial[0].equity and
    .trade_allowed == true and
    .trade_expert == true
' "$evidence/gateway-account-final.json" >/dev/null || fail "read-only resync run changed the venue account snapshot"

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
[ "$order_events" -eq 0 ] || fail "read-only resync emitted an order, fill, accounting, linkage, or rejection event"

warmup_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.WarmupTickEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
live_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.TickEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
stream_candle_events="$(jq -r 'select(.eventType == "com.qkt.events.StreamCandleEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
strategy_evaluations="$(jq -r 'select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$warmup_tick_events" -gt 0 ] || fail "audit journal retained no warmup ticks"
[ "$live_tick_events" -gt 0 ] || fail "audit journal retained no live ticks"
[ "$stream_candle_events" -gt 0 ] || fail "audit journal retained no stream candles"
[ "$strategy_evaluations" -gt 0 ] || fail "audit journal retained no strategy candle evaluations"

pre_1m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur1 EXNESS:EURUSD 1m "$deploy_started_ms" "$resync_started_ms" "${audit_journals[@]}")"
pre_5m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$deploy_started_ms" "$resync_started_ms" "${audit_journals[@]}")"
post_1m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur1 EXNESS:EURUSD 1m "$resync_ready_ms" -1 "${audit_journals[@]}")"
post_5m_matches="$(qkt_count_matched_evaluations "$strategy_name" eur5 EXNESS:EURUSD 5m "$resync_ready_ms" -1 "${audit_journals[@]}")"
[ "$pre_1m_matches" -gt 0 ] || fail "pre-resync audit retained no matched 1m evaluation"
[ "$pre_5m_matches" -gt 0 ] || fail "pre-resync audit retained no matched 5m evaluation"
[ "$post_1m_matches" -gt 0 ] || fail "post-resync audit retained no matched 1m evaluation"
[ "$post_5m_matches" -gt 0 ] || fail "post-resync audit retained no matched 5m evaluation"

pre_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$deploy_started_ms" "$resync_started_ms" "${audit_journals[@]}")"
pre_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$deploy_started_ms" "$resync_started_ms" "${audit_journals[@]}")"
post_1m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 60000 "$resync_started_ms" -1 "${audit_journals[@]}")"
post_5m_warmups="$(qkt_count_warmup_pseudo_ticks EXNESS:EURUSD 300000 "$resync_started_ms" -1 "${audit_journals[@]}")"
[ "$pre_1m_warmups" -eq 80 ] || fail "pre-resync 1m warmup count was $pre_1m_warmups; expected 80 pseudo-ticks"
[ "$pre_5m_warmups" -eq 80 ] || fail "pre-resync 5m warmup count was $pre_5m_warmups; expected 80 pseudo-ticks"
[ "$post_1m_warmups" -eq 80 ] || fail "post-resync 1m warmup count was $post_1m_warmups; expected 80 pseudo-ticks"
[ "$post_5m_warmups" -eq 80 ] || fail "post-resync 5m warmup count was $post_5m_warmups; expected 80 pseudo-ticks"

gateway_mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' \
    "${transport_journals[@]}" | awk 'END {print NR + 0}')"
[ "$gateway_mutations" -eq 0 ] || fail "read-only resync issued a mutating gateway request"

resource_samples="$(($(wc -l < "$resource_samples_path") - 1))"
[ "$resource_samples" -gt 0 ] || fail "daemon resource sampling produced no observations"
health_samples="$(awk 'END {print NR + 0}' "$health_samples_path")"
[ "$health_samples" -eq "$resource_samples" ] || fail "daemon health and resource sample counts differ"
max_inbound_queue="$(jq -s '[.[].perStrategy[]?.inboundQueueDepth] | max // 0' "$health_samples_path")"
max_dropped_ticks="$(jq -s '[.[].perStrategy[]?.droppedTicks] | max // 0' "$health_samples_path")"
[ "$max_dropped_ticks" -eq 0 ] || fail "daemon reported $max_dropped_ticks dropped live tick(s)"
tick_latency="$(jq -c --arg strategy "$strategy_name" '.[$strategy].strategies[$strategy].TICK_PROCESSING' "$evidence/latency.json")"
max_cpu_percent="$(awk -F, 'NR > 1 && $2 > max {max=$2} END {printf "%.2f", max + 0}' "$resource_samples_path")"
max_rss_kb="$(awk -F, 'NR > 1 && $3 > max {max=$3} END {print max + 0}' "$resource_samples_path")"
max_threads="$(awk -F, 'NR > 1 && $4 > max {max=$4} END {print max + 0}' "$resource_samples_path")"
qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health.json")"
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
    --arg deployStartedMs "$deploy_started_ms" \
    --arg resyncStartedMs "$resync_started_ms" \
    --arg resyncReadyMs "$resync_ready_ms" \
    --arg resumeReadyMs "$resume_ready_ms" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" \
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
          schema:"qkt-live-validation-readonly-resync-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          phaseTimeoutSeconds:($phaseTimeoutSeconds|tonumber),
          controlPlane:{
            daemonStartedEmpty:true,
            deploy:true,
            resync:true,
            resumeAfterResync:true,
            deployStartedAtMs:($deployStartedMs|tonumber),
            resyncStartedAtMs:($resyncStartedMs|tonumber),
            resyncReadyAtMs:($resyncReadyMs|tonumber),
            resumeReadyAtMs:($resumeReadyMs|tonumber)
          },
          bars:{
            preResync:{m1MatchedEvaluations:($pre1mMatches|tonumber),m5MatchedEvaluations:($pre5mMatches|tonumber),
              m1WarmupPseudoTicks:($pre1mWarmups|tonumber),m5WarmupPseudoTicks:($pre5mWarmups|tonumber)},
            postResync:{m1MatchedEvaluations:($post1mMatches|tonumber),m5MatchedEvaluations:($post5mMatches|tonumber),
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
