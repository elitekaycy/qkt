#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-readonly.sh --scenario DIR [--cli PATH] [--duration-seconds N] [--verify-only]

Verifies a prepared live-validation scenario. Without --verify-only it connects only
to the scenario's explicit 127.0.0.1 gateway, requires QKT_BROKER_API_KEY, proves a
flat allowlisted demo account, runs the generated read-only strategy in the real QKT
daemon long enough to observe both M1 and M5 closed bars, and retains evidence.
EOF
}

fail() {
    printf 'run-readonly: %s\n' "$1" >&2
    exit 1
}

scenario=""
cli="$repo_root/build/install/qkt/bin/qkt"
duration_seconds=310
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --duration-seconds) duration_seconds="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
scenario="$(realpath "$scenario")"
[ -d "$scenario" ] || fail "scenario directory does not exist: $scenario"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[[ "$duration_seconds" =~ ^[0-9]+$ ]] || fail "--duration-seconds must be an integer"
if ! $verify_only && [ "$duration_seconds" -lt 310 ]; then
    fail "--duration-seconds must be at least 310 to guarantee an M5 close"
fi

for file in qkt.config.yaml expected.json cleanup.json scenario.json SHA256SUMS; do
    [ -f "$scenario/$file" ] || fail "missing prepared artifact: $file"
done

(
    cd "$scenario"
    sha256sum --check SHA256SUMS >/dev/null
) || fail "prepared artifact checksum verification failed"

jq -e '
    .schema == "qkt-live-validation-scenario-v1" and
    .credentialsStored == false and
    .executionState == "prepared" and
    (.gatewayUrl | test("^http://127\\.0\\.0\\.1:[0-9]{1,5}$"))
' "$scenario/scenario.json" >/dev/null || fail "scenario metadata failed safety validation"

mapfile -t readonly_strategies < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' | sort)
mapfile -t armed_strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*.qkt' | sort)
[ "${#readonly_strategies[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
[ "${#armed_strategies[@]}" -ge 1 ] || fail "expected at least one separately armed strategy"

for strategy in "${readonly_strategies[@]}" "${armed_strategies[@]}"; do
    "$cli" parse "$strategy" >/dev/null
done

grep -F 'expected_trade_mode: demo' "$scenario/qkt.config.yaml" >/dev/null ||
    fail "config does not require demo trade mode"
grep -F 'max_order_qty: "0.01"' "$scenario/qkt.config.yaml" >/dev/null ||
    fail "config does not cap order quantity at 0.01"
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$scenario/qkt.config.yaml" >/dev/null ||
    fail "config does not resolve credentials at execution time"

if $verify_only; then
    printf 'verified %s\n' "$scenario"
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
        .trade_allowed == true and
        .trade_expert == true
    ' "$evidence/gateway-account-initial.json" >/dev/null || fail "gateway account does not match the demo allowlist"

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-initial.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-initial.json"
jq -e 'length == 0' "$evidence/positions-initial.json" >/dev/null || fail "demo account has open positions"
jq -e 'length == 0' "$evidence/orders-initial.json" >/dev/null || fail "demo account has pending orders"

"$cli" preflight "${readonly_strategies[0]}" --config "$config" > "$evidence/preflight.log" 2>&1

same_bar=false
for attempt in 1 2 3; do
    "$cli" bot bars EXNESS:EURUSD --tf 1m --count 10 --config "$config" --json > "$evidence/bars-1m.json"
    "$cli" bot eval 'ema(3)' EXNESS:EURUSD --tf 1m --count 10 --config "$config" --json > "$evidence/ema3-1m.json"
    if [ "$(jq -r '.[-1].t' "$evidence/bars-1m.json")" = "$(jq -r '.lastBarStart' "$evidence/ema3-1m.json")" ]; then
        same_bar=true
        break
    fi
done
$same_bar || fail "could not capture bars and EMA evaluation on the same closed-bar set"

"$cli" bot bars EXNESS:EURUSD --tf 5m --count 10 --config "$config" --json > "$evidence/bars-5m.json"
captured_now_ms="$(date +%s%3N)"
jq -e --argjson now "$captured_now_ms" '
    length == 10 and
    all(.[]; (.t % 60000) == 0 and (.t + 60000) <= $now) and
    ([.[].t] == ([.[].t] | sort | unique))
' "$evidence/bars-1m.json" >/dev/null || fail "M1 history contains forming, misaligned, duplicate, or missing bars"
jq -e --argjson now "$captured_now_ms" '
    length == 10 and
    all(.[]; (.t % 300000) == 0 and (.t + 300000) <= $now) and
    ([.[].t] == ([.[].t] | sort | unique))
' "$evidence/bars-5m.json" >/dev/null || fail "M5 history contains forming, misaligned, duplicate, or missing bars"

ema_oracle="$(
    jq -r '.[].c' "$evidence/bars-1m.json" |
        awk '
            NR <= 3 { seed += $1 }
            NR == 3 { ema = seed / 3 }
            NR > 3 { ema = (0.5 * $1) + (0.5 * ema) }
            END { printf "%.8f", ema }
        '
)"
ema_actual="$(jq -r '.value | tonumber' "$evidence/ema3-1m.json" | awk '{ printf "%.8f", $1 }')"
[ "$ema_oracle" = "$ema_actual" ] || fail "independent EMA oracle mismatch: expected $ema_oracle, got $ema_actual"
jq -n \
    --arg oracle "$ema_oracle" \
    --arg actual "$ema_actual" \
    --argjson lastBarStart "$(jq '.lastBarStart' "$evidence/ema3-1m.json")" \
    '{schema:"qkt-live-validation-oracle-v1",indicator:"EMA",period:3,oracle:$oracle,actual:$actual,exactAtEightDecimals:($oracle==$actual),lastBarStart:$lastBarStart}' \
    > "$evidence/ema3-oracle.json"

daemon_pid=""
stop_daemon() {
    if [ -n "$daemon_pid" ] && kill -0 "$daemon_pid" 2>/dev/null; then
        "$cli" daemon stop --state-dir "$scenario/state" >/dev/null 2>&1 || kill -TERM "$daemon_pid" 2>/dev/null || true
        wait "$daemon_pid" 2>/dev/null || true
    fi
}
trap stop_daemon EXIT

QKT_STATE_DIR="$scenario/state" "$cli" daemon start \
    --config "$config" \
    --state-dir "$scenario/state" \
    --load-dir "$scenario/strategies/readonly" \
    > "$scenario/logs/daemon.log" 2>&1 &
daemon_pid=$!

ready=false
for _ in $(seq 1 60); do
    if ! kill -0 "$daemon_pid" 2>/dev/null; then
        fail "daemon exited before becoming ready"
    fi
    if "$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-initial.json" 2>/dev/null; then
        ready=true
        break
    fi
    sleep 1
done
$ready || fail "daemon did not become ready within 60 seconds"

printf 'elapsed_seconds,cpu_percent,rss_kb,threads\n' > "$evidence/resources.csv"
clock_ticks="$(getconf CLK_TCK)"
previous_ticks="$(awk '{print $14 + $15}' "/proc/$daemon_pid/stat")"
sample_interval=10
for second in $(seq 1 "$duration_seconds"); do
    kill -0 "$daemon_pid" 2>/dev/null || fail "daemon exited during the read-only observation"
    sleep 1
    if [ $((second % sample_interval)) -eq 0 ]; then
        current_ticks="$(awk '{print $14 + $15}' "/proc/$daemon_pid/stat")"
        cpu_percent="$(
            awk -v now="$current_ticks" -v previous="$previous_ticks" -v hz="$clock_ticks" -v seconds="$sample_interval" \
                'BEGIN { printf "%.2f", ((now - previous) / hz / seconds) * 100 }'
        )"
        rss_kb="$(awk '/^VmRSS:/ {print $2}' "/proc/$daemon_pid/status")"
        threads="$(awk '/^Threads:/ {print $2}' "/proc/$daemon_pid/status")"
        printf '%s,%s,%s,%s\n' "$second" "$cpu_percent" "$rss_kb" "$threads" >> "$evidence/resources.csv"
        previous_ticks="$current_ticks"
    fi
done

"$cli" daemon status --state-dir "$scenario/state" --json > "$evidence/daemon-status-final.json"
grep -F 'closed bar trace timeframe=1m' "$scenario/logs/daemon.log" >/dev/null || fail "daemon did not retain an M1 trace"
grep -F 'closed bar trace timeframe=5m' "$scenario/logs/daemon.log" >/dev/null || fail "daemon did not retain an M5 trace"

"$cli" daemon stop --state-dir "$scenario/state" > "$evidence/daemon-stop.log"
wait "$daemon_pid"
daemon_pid=""

"$cli" bot positions --broker exness --config "$config" --json > "$evidence/positions-final.json"
"$cli" bot orders --broker exness --config "$config" --json > "$evidence/orders-final.json"
"$cli" bot history --broker exness --since "$run_started_ms" --config "$config" --json > "$evidence/history-during-run.json"
gateway_get /account > "$evidence/gateway-account-final.json"
jq -e 'length == 0' "$evidence/positions-final.json" >/dev/null || fail "read-only run ended with an open position"
jq -e 'length == 0' "$evidence/orders-final.json" >/dev/null || fail "read-only run ended with a pending order"
jq -e 'length == 0' "$evidence/history-during-run.json" >/dev/null || fail "read-only run unexpectedly produced a venue deal"
jq -e --slurpfile initial "$evidence/gateway-account-initial.json" '
    .login == $initial[0].login and
    .server == $initial[0].server and
    .balance == $initial[0].balance and
    .equity == $initial[0].equity and
    .trade_allowed == true and
    .trade_expert == true
' "$evidence/gateway-account-final.json" >/dev/null || fail "read-only run changed the venue account snapshot"

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
audit_events="$(awk 'END {print NR}' "${audit_journals[@]}")"
transport_events="$(awk 'END {print NR}' "${transport_journals[@]}")"
candle_events="$(jq -r 'select(.eventType == "com.qkt.events.CandleEvent") | 1' "${audit_journals[@]}" | awk 'END {print NR + 0}')"
[ "$candle_events" -ge 2 ] || fail "audit journal did not retain closed candle events"

resource_samples="$(($(wc -l < "$evidence/resources.csv") - 1))"
[ "$resource_samples" -gt 0 ] || fail "daemon resource sampling produced no observations"
max_cpu_percent="$(awk -F, 'NR > 1 && $2 > max {max=$2} END {printf "%.2f", max + 0}' "$evidence/resources.csv")"
max_rss_kb="$(awk -F, 'NR > 1 && $3 > max {max=$3} END {print max + 0}' "$evidence/resources.csv")"
max_threads="$(awk -F, 'NR > 1 && $4 > max {max=$4} END {print max + 0}' "$evidence/resources.csv")"
qkt_version="$("$cli" --version)"
gateway_version="$(jq -r '.version' "$evidence/gateway-health.json")"
qkt_commit="$(jq -r '.qktCommit' "$scenario/scenario.json")"
qkt_dirty="$(jq -r '.qktDirty' "$scenario/scenario.json")"
initial_leverage="$(jq -r '.leverage' "$evidence/gateway-account-initial.json")"
final_leverage="$(jq -r '.leverage' "$evidence/gateway-account-final.json")"
leverage_changed=false
[ "$initial_leverage" = "$final_leverage" ] || leverage_changed=true

finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
    --arg finishedAt "$finished_at" \
    --arg durationSeconds "$duration_seconds" \
    --arg ema "$ema_actual" \
    --arg staleEvents "$stale_events" \
    --arg recoveryEvents "$recovery_events" \
    --arg auditEvents "$audit_events" \
    --arg candleEvents "$candle_events" \
    --arg transportEvents "$transport_events" \
    --arg resourceSamples "$resource_samples" \
    --arg maxCpuPercent "$max_cpu_percent" \
    --arg maxRssKb "$max_rss_kb" \
    --arg maxThreads "$max_threads" \
    --arg qktVersion "$qkt_version" \
    --arg gatewayVersion "$gateway_version" \
    --arg qktCommit "$qkt_commit" \
    --argjson qktDirty "$qkt_dirty" \
    --arg initialLeverage "$initial_leverage" \
    --arg finalLeverage "$final_leverage" \
    --argjson leverageChanged "$leverage_changed" '
        {
          schema:"qkt-live-validation-readonly-v1",
          status:"passed",
          finishedAt:$finishedAt,
          qktVersion:$qktVersion,
          qktCommit:$qktCommit,
          qktDirty:$qktDirty,
          gatewayVersion:$gatewayVersion,
          durationSeconds:($durationSeconds|tonumber),
          m1Trace:true,
          m5Trace:true,
          ema3:$ema,
          staleEvents:($staleEvents|tonumber),
          recoveredStaleEvents:($recoveryEvents|tonumber),
          auditEvents:($auditEvents|tonumber),
          candleEvents:($candleEvents|tonumber),
          transportEvents:($transportEvents|tonumber),
          resources:{samples:($resourceSamples|tonumber),maxCpuPercent:($maxCpuPercent|tonumber),maxRssKb:($maxRssKb|tonumber),maxThreads:($maxThreads|tonumber)},
          initialPositions:0,
          initialOrders:0,
          finalPositions:0,
          finalOrders:0,
          venueDealsDuringRun:0,
          accountIdentityUnchanged:true,
          financialStateUnchanged:true,
          accountUnchanged:($leverageChanged|not),
          leverage:{
            initial:($initialLeverage|tonumber),
            final:($finalLeverage|tonumber),
            changed:$leverageChanged
          }
        }
    ' \
    > "$evidence/result.json"

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
    cd "$evidence"
    find . -type f ! -path './SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > SHA256SUMS
)
(
    cd "$scenario"
    find . -type f ! -path './RUN-SHA256SUMS' -print0 |
        sort -z |
        xargs -0 sha256sum > RUN-SHA256SUMS
    sha256sum --check RUN-SHA256SUMS >/dev/null
)

trap - EXIT
printf 'passed %s\n' "$evidence/result.json"
