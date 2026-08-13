#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/live-validation/lib/container-load-evidence.sh
source "$repo_root/scripts/live-validation/lib/container-load-evidence.sh"

usage() {
    cat <<'EOF'
Usage: run-container-load.sh --output DIR --image IMAGE --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N [--duration-seconds N] [--cli PATH]

Runs two isolated, read-only QKT containers against a localhost MT5 demo gateway.
The eleven-minute minimum covers M1/M5 routing before and after one container restart.
The broker key is read only from QKT_BROKER_API_KEY and piped to each daemon process;
it is never placed in Docker container configuration or retained artifacts.
EOF
}

fail() {
    printf 'run-container-load: %s\n' "$1" >&2
    exit 1
}

output=""
image=""
gateway_url=""
expected_login=""
expected_server=""
expected_balance=""
expected_leverage=""
duration_seconds=620
cli="$repo_root/build/install/qkt/bin/qkt"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        --gateway-url) gateway_url="${2:-}"; shift 2 ;;
        --expected-login) expected_login="${2:-}"; shift 2 ;;
        --expected-server) expected_server="${2:-}"; shift 2 ;;
        --expected-balance) expected_balance="${2:-}"; shift 2 ;;
        --expected-leverage) expected_leverage="${2:-}"; shift 2 ;;
        --duration-seconds) duration_seconds="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$output" ] || fail "--output is required"
[ -n "$image" ] || fail "--image is required"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}/?$ ]] ||
    fail "--gateway-url must be an explicit http://127.0.0.1:PORT endpoint"
gateway_url="${gateway_url%/}"
[[ "$expected_login" =~ ^[1-9][0-9]*$ ]] || fail "--expected-login must be a positive integer"
[[ "$expected_server" =~ ^[A-Za-z0-9._-]+$ ]] || fail "--expected-server contains unsupported characters"
[[ "$expected_balance" =~ ^[0-9]+([.][0-9]+)?$ ]] || fail "--expected-balance must be a decimal"
[[ "$expected_leverage" =~ ^[1-9][0-9]*$ ]] || fail "--expected-leverage must be a positive integer"
[[ "$duration_seconds" =~ ^[0-9]+$ ]] || fail "--duration-seconds must be an integer"
[ "$duration_seconds" -ge 620 ] || fail "--duration-seconds must be at least 620"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for command in curl docker find jq rg sha256sum sort; do
    command -v "$command" >/dev/null 2>&1 || fail "required command not found: $command"
done
for jvm_env in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS GRADLE_OPTS; do
    if [[ -v "$jvm_env" ]]; then
        fail "$jvm_env must be unset; this run does not restrict or override the JVM"
    fi
done

output="$(realpath -m "$output")"
[ ! -e "$output" ] || fail "output already exists: $output"
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
qkt_short="${qkt_commit:0:8}"
host_version="$("$cli" --version)"
[[ "$host_version" == *"($qkt_short"* || "$host_version" == *"($qkt_commit"* ]] || fail "host CLI is not built from $qkt_short"
docker image inspect "$image" | jq -e '
    (.[0].Config.Env // []) |
    all(.[];
        (startswith("JAVA_TOOL_OPTIONS=") or startswith("JDK_JAVA_OPTIONS=") or
         startswith("_JAVA_OPTIONS=") or startswith("GRADLE_OPTS=")) | not
    )
' >/dev/null || fail "Docker image config restricts or overrides the JVM"
image_version="$(docker run --rm --entrypoint /bin/sh "$image" -lc 'qkt --version')"
[[ "$image_version" == *"($qkt_short"* || "$image_version" == *"($qkt_commit"* ]] || fail "Docker image is not built from $qkt_short"

mkdir -m 700 -p "$output/evidence" "$output/cases/a" "$output/cases/b"
run_started_ms="$(date +%s%3N)"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail-with-body --config - "$gateway_url$path"
}

gateway_get /health > "$output/evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$output/evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"
gateway_get /account > "$output/evidence/account-initial.json"
jq -e \
    --argjson login "$expected_login" \
    --arg server "$expected_server" \
    --arg balance "$expected_balance" \
    --argjson leverage "$expected_leverage" '
        .login == $login and .server == $server and .trade_mode == 0 and .currency == "USD" and
        .balance == ($balance | tonumber) and .equity == ($balance | tonumber) and
        .margin == 0 and .leverage == $leverage and .trade_allowed == true and .trade_expert == true
    ' "$output/evidence/account-initial.json" >/dev/null || fail "account does not match the flat demo allowlist"
gateway_get /get_positions > "$output/evidence/positions-initial.json"
gateway_get /orders > "$output/evidence/orders-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-initial.json" >/dev/null ||
    fail "demo account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-initial.json" >/dev/null ||
    fail "demo account has a pending order"

write_case() {
    local case_id="$1"
    local strategy="$2"
    local magic="$3"
    local first_symbol="$4"
    local second_symbol="$5"
    local first_alias="$6"
    local second_alias="$7"
    local dir="$output/cases/$case_id"
    mkdir -m 700 -p "$dir/strategies" "$dir/state" "$dir/data" "$dir/logs" "$dir/evidence"
    cat > "$dir/qkt.config.yaml" <<EOF
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
    tick_poll_interval_ms: 500
    poll_interval_ms: 5000
    http_timeout_ms: 5000
    retry_attempts: 3
risk:
  max_daily_loss: "25"
  max_order_qty: "0.01"
  max_order_notional: "2500"
  price_collar_pct: "1"
  margin_floor_pct: "500"
  measured_usage_hours: "720"
  measured_usage_max_qty: "0.01"
  max_round_trips_10m: 2
  max_broker_rejections_1m: 2
  max_drawdown_pct: "0.5"
  max_daily_drawdown_pct: "0.25"
  live_equity_basis: venue
  per_strategy:
    $strategy:
      max_daily_loss: "10"
      max_position_size: "0.01"
      max_open_positions: 1
      max_trades_per_day: 1
book_risk:
  capital: "$expected_balance"
  limits:
    max_gross_exposure: "0.05"
    max_net_exposure: "0.05"
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
    cat > "$dir/strategies/$strategy.qkt" <<EOF
STRATEGY $strategy VERSION 1

SYMBOLS
    ${first_alias}1 = EXNESS:$first_symbol EVERY 1m WARMUP 20 BARS,
    ${first_alias}5 = EXNESS:$first_symbol EVERY 5m WARMUP 20 BARS,
    ${second_alias}1 = EXNESS:$second_symbol EVERY 1m WARMUP 20 BARS,
    ${second_alias}5 = EXNESS:$second_symbol EVERY 5m WARMUP 20 BARS

LET first_ema = ema(${first_alias}1.close, 3),
    first_rsi = rsi(${first_alias}5.close, 5),
    second_sma = sma(${second_alias}1.close, 4),
    second_atr = atr(${second_alias}5, 5)

RULES
    WHEN first_ema IS NOT NULL
    THEN LOG "container load trace stream=${first_alias}1 timeframe=1m" value=first_ema

    WHEN first_rsi IS NOT NULL
    THEN LOG "container load trace stream=${first_alias}5 timeframe=5m" value=first_rsi

    WHEN second_sma IS NOT NULL
    THEN LOG "container load trace stream=${second_alias}1 timeframe=1m" value=second_sma

    WHEN second_atr IS NOT NULL
    THEN LOG "container load trace stream=${second_alias}5 timeframe=5m" value=second_atr
EOF
    "$cli" parse "$dir/strategies/$strategy.qkt" >/dev/null
    "$cli" preflight "$dir/strategies/$strategy.qkt" --config "$dir/qkt.config.yaml" > "$dir/evidence/preflight.log" 2>&1
}

case_ids=(a b)
strategies=(container_load_a container_load_b)
magics=(917201 917202)
case_symbols=("EURUSD GBPUSD" "USDJPY XAUUSD")
write_case a "${strategies[0]}" "${magics[0]}" EURUSD GBPUSD eur gbp
write_case b "${strategies[1]}" "${magics[1]}" USDJPY XAUUSD jpy xau

containers=("qkt-load-a-$run_id" "qkt-load-b-$run_id")
exec_pids=("" "")
generations=(0 0)

cleanup() {
    set +e
    for index in 0 1; do
        "$cli" daemon stop --state-dir "$output/cases/${case_ids[$index]}/state" >/dev/null 2>&1
    done
    for pid in "${exec_pids[@]}"; do
        [ -z "$pid" ] || wait "$pid" >/dev/null 2>&1
    done
    for container in "${containers[@]}"; do
        docker rm -f "$container" >/dev/null 2>&1
    done
}
trap cleanup EXIT

for index in 0 1; do
    case_dir="$output/cases/${case_ids[$index]}"
    docker run --detach \
        --name "${containers[$index]}" \
        --network host \
        --user "$(id -u):$(id -g)" \
        --entrypoint /bin/sh \
        --volume "$case_dir:/work" \
        --workdir /work \
        "$image" -c 'while :; do sleep 3600; done' >/dev/null
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY"); then
        fail "broker credential was stored in ${containers[$index]} configuration"
    fi
    docker inspect "${containers[$index]}" | jq -e '
        .[0].HostConfig.Memory == 0 and
        .[0].HostConfig.NanoCpus == 0 and
        .[0].HostConfig.CpuQuota == 0 and
        (.[0].HostConfig.PidsLimit == null or .[0].HostConfig.PidsLimit == 0) and
        .[0].HostConfig.CpusetCpus == ""
    ' >/dev/null || fail "container ${case_ids[$index]} has an unexpected resource restriction"
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --quiet '^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|GRADLE_OPTS)='; then
        fail "container ${case_ids[$index]} config restricts or overrides the JVM"
    fi
    docker inspect "${containers[$index]}" | jq '.[0] | {
        schema:"qkt-live-multi-container-runtime-v1",id:.Id,image:.Image,
        resourceRestrictions:{memoryBytes:.HostConfig.Memory,nanoCpus:.HostConfig.NanoCpus,
          cpuQuota:.HostConfig.CpuQuota,pidsLimit:.HostConfig.PidsLimit,cpusetCpus:.HostConfig.CpusetCpus},
        credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false
    }' > "$case_dir/evidence/container.json"
done

start_daemon() {
    local index="$1"
    local generation="$2"
    local case_id="${case_ids[$index]}"
    local log="$output/cases/$case_id/logs/daemon-$generation.log"
    (
        printf '%s\n' "$QKT_BROKER_API_KEY" |
            docker exec -i "${containers[$index]}" /bin/sh -c '
                IFS= read -r QKT_BROKER_API_KEY
                export QKT_BROKER_API_KEY QKT_STATE_DIR=/work/state QKT_LATENCY_TRACKING=1
                exec qkt daemon start --config /work/qkt.config.yaml --state-dir /work/state --load-dir /work/strategies
            '
    ) > "$log" 2>&1 &
    exec_pids[$index]=$!
    generations[$index]="$generation"
}

wait_ready() {
    local index="$1"
    local peer_index="${2:-}"
    local state="$output/cases/${case_ids[$index]}/state"
    local deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "${exec_pids[$index]}" 2>/dev/null; then
            fail "container ${case_ids[$index]} daemon exited before readiness"
        fi
        if [ -n "$peer_index" ]; then
            local peer_id="${case_ids[$peer_index]}"
            local peer_status
            peer_status="$("$cli" daemon status --state-dir "$output/cases/$peer_id/state" --json)" ||
                fail "container $peer_id status failed while its peer restarted"
            jq -e '
                .status == "ok" and .strategies == 1 and
                .perStrategy[0].running == true and .perStrategy[0].halted == false and
                .perStrategy[0].droppedTicks == 0
            ' <<<"$peer_status" >/dev/null || fail "container $peer_id became unhealthy while its peer restarted"
            jq -c --argjson observedAtMs "$(date +%s%3N)" '. + {observedAtMs:$observedAtMs}' <<<"$peer_status" \
                >> "$output/cases/$peer_id/evidence/health-during-peer-restart.jsonl"
        fi
        if "$cli" daemon status --state-dir "$state" --json > "$output/cases/${case_ids[$index]}/evidence/status-ready-${generations[$index]}.json" 2>/dev/null &&
            jq -e --arg strategy "${strategies[$index]}" '
                .status == "ok" and .strategies == 1 and
                .perStrategy[0].name == $strategy and .perStrategy[0].running == true
            ' "$output/cases/${case_ids[$index]}/evidence/status-ready-${generations[$index]}.json" >/dev/null; then
            return
        fi
        sleep 1
    done
    fail "container ${case_ids[$index]} daemon did not become ready"
}

sample_healthy_status() {
    local state_dir="$1"
    local failure_path="$2"
    local status=""
    local attempt
    for attempt in 1 2 3; do
        if status="$("$cli" daemon status --state-dir "$state_dir" --json 2>/dev/null)" &&
            jq -e '
                .status == "ok" and .strategies == 1 and
                .perStrategy[0].running == true and .perStrategy[0].halted == false and
                .perStrategy[0].droppedTicks == 0
            ' <<<"$status" >/dev/null; then
            printf '%s' "$status"
            return 0
        fi
        printf '%s\n' "${status:-}" > "$failure_path"
        sleep 1
    done
    return 1
}

for index in 0 1; do
    start_daemon "$index" 1
done
for index in 0 1; do
    wait_ready "$index"
done

printf 'elapsed_seconds,case,generation,cpu_percent,memory_kib,pids\n' > "$output/evidence/resources.csv"
for case_id in "${case_ids[@]}"; do
    : > "$output/cases/$case_id/evidence/health.jsonl"
done

memory_kib() {
    awk '
        function scale(unit) {
            if (unit == "GiB") return 1048576
            if (unit == "MiB") return 1024
            if (unit == "KiB") return 1
            if (unit == "GB") return 976562.5
            if (unit == "MB") return 976.5625
            if (unit == "kB") return 0.9765625
            return 0.0009765625
        }
        {
            value = $0
            unit = $0
            sub(/[A-Za-z]+$/, "", value)
            sub(/^[0-9.]+/, "", unit)
            printf "%d", value * scale(unit)
        }
    ' <<<"$1"
}

# Leave a full M5-close observation after restart so generation 2 proves its own stream path.
restart_second=$((duration_seconds - 310))
restart_completed=false
load_started_second=$SECONDS
required_end_second=$duration_seconds
next_sample_second=10
while true; do
    sleep 1
    elapsed_seconds=$((SECONDS - load_started_second))
    for index in 0 1; do
        kill -0 "${exec_pids[$index]}" 2>/dev/null || fail "container ${case_ids[$index]} daemon exited during load"
    done
    if ! $restart_completed && [ "$elapsed_seconds" -ge "$restart_second" ]; then
        restart_started_ms="$(date +%s%3N)"
        : > "$output/cases/b/evidence/health-during-peer-restart.jsonl"
        "$cli" daemon status --state-dir "$output/cases/a/state" --json > "$output/cases/a/evidence/status-before-restart.json"
        "$cli" daemon status --state-dir "$output/cases/b/state" --json > "$output/cases/b/evidence/status-before-peer-restart.json"
        jq -e '.status == "ok" and .perStrategy[0].running == true and
            .perStrategy[0].droppedTicks == 0 and .perStrategy[0].inboundQueueDepth == 0' \
            "$output/cases/b/evidence/status-before-peer-restart.json" >/dev/null ||
            fail "container b was not healthy and drained before its peer restarted"
        "$cli" daemon stop --state-dir "$output/cases/a/state" > "$output/cases/a/evidence/stop-for-restart.log"
        wait "${exec_pids[0]}" || fail "container a daemon failed during controlled stop"
        sequence_state="$output/cases/a/state/state/${strategies[0]}/sequences.json"
        [ -f "$sequence_state" ] || fail "case a persisted no rule-edge state before restart"
        jq -e '
            [.sequences[] | select(.name == "__qkt_rule_edges__") | .lastValues[]] as $edges |
            ($edges | length) == 4 and all($edges[]; . == true)
        ' "$sequence_state" >/dev/null || fail "case a did not persist four true rule edges before restart"
        cp "$sequence_state" "$output/cases/a/evidence/rule-edges-before-restart.json"
        "$cli" daemon status --state-dir "$output/cases/b/state" --json > "$output/cases/b/evidence/status-during-peer-restart.json"
        jq -e '.status == "ok" and .perStrategy[0].running == true and
            .perStrategy[0].droppedTicks == 0 and .perStrategy[0].inboundQueueDepth == 0' \
            "$output/cases/b/evidence/status-during-peer-restart.json" >/dev/null ||
            fail "container b was disrupted while container a restarted"
        start_daemon 0 2
        wait_ready 0 1
        restart_ready_ms="$(date +%s%3N)"
        restart_ready_second=$((SECONDS - load_started_second))
        # SECONDS is integer-valued; one extra second guarantees 310,000 ms after readiness.
        required_end_second=$((restart_ready_second + 311))
        [ "$required_end_second" -ge "$duration_seconds" ] || required_end_second=$duration_seconds
        restart_completed=true
        jq -n --argjson atSecond "$elapsed_seconds" \
            --argjson restartStartedAtMs "$restart_started_ms" --argjson restartReadyAtMs "$restart_ready_ms" \
            --argjson requiredPostRestartObservationSeconds 310 \
            --arg containerA "${containers[0]}" --arg containerB "${containers[1]}" \
            '{status:"passed",atSecond:$atSecond,restartStartedAtMs:$restartStartedAtMs,
              restartReadyAtMs:$restartReadyAtMs,
              requiredPostRestartObservationSeconds:$requiredPostRestartObservationSeconds,
              restarted:$containerA,uninterrupted:$containerB,
              sourceAutoRedeploy:true,stateRestoreVerified:true,persistedDeploymentRestore:false}' \
            > "$output/evidence/restart.json"
    fi
    elapsed_seconds=$((SECONDS - load_started_second))
    if [ "$elapsed_seconds" -ge "$next_sample_second" ]; then
        for index in 0 1; do
            case_id="${case_ids[$index]}"
            failure_status_path="$output/cases/$case_id/evidence/status-sample-failed-${generations[$index]}-${elapsed_seconds}.json"
            status="$(sample_healthy_status "$output/cases/$case_id/state" "$failure_status_path")" ||
                fail "$case_id health sample was not ready and unhalted (last sample: $failure_status_path)"
            jq -c --argjson second "$elapsed_seconds" --argjson generation "${generations[$index]}" \
                '. + {sampleSecond:$second,generation:$generation}' <<<"$status" \
                >> "$output/cases/$case_id/evidence/health.jsonl"
            stats="$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}|{{.PIDs}}' "${containers[$index]}")"
            cpu="${stats%%|*}"
            rest="${stats#*|}"
            memory="${rest%% / *}"
            pids="${stats##*|}"
            printf '%s,%s,%s,%s,%s,%s\n' \
                "$elapsed_seconds" "$case_id" "${generations[$index]}" "${cpu%%%}" "$(memory_kib "$memory")" "$pids" \
                >> "$output/evidence/resources.csv"
        done
        while [ "$next_sample_second" -le "$elapsed_seconds" ]; do
            next_sample_second=$((next_sample_second + 10))
        done
    fi
    [ "$elapsed_seconds" -ge "$required_end_second" ] && break
done
$restart_completed || fail "controlled restart did not run"
observation_ended_ms="$(date +%s%3N)"
jq --argjson observationEndedAtMs "$observation_ended_ms" '
    . + {observationEndedAtMs:$observationEndedAtMs,
         postRestartObservationSeconds:((($observationEndedAtMs - .restartReadyAtMs) / 1000) | floor)}
' "$output/evidence/restart.json" > "$output/evidence/restart.json.tmp"
mv "$output/evidence/restart.json.tmp" "$output/evidence/restart.json"
jq -e '.postRestartObservationSeconds >= .requiredPostRestartObservationSeconds' \
    "$output/evidence/restart.json" >/dev/null || fail "generation 2 received less than 310 seconds after readiness"
[ "$(awk 'END {print NR + 0}' "$output/cases/b/evidence/health-during-peer-restart.jsonl")" -gt 0 ] ||
    fail "container b produced no health samples during its peer restart"

for index in 0 1; do
    case_id="${case_ids[$index]}"
    strategy="${strategies[$index]}"
    case_dir="$output/cases/$case_id"
    "$cli" daemon status --state-dir "$case_dir/state" --json > "$case_dir/evidence/status-final.json"
    port="$(<"$case_dir/state/control.port")"
    curl --silent --show-error --fail "http://127.0.0.1:$port/latency" > "$case_dir/evidence/latency.json"
    jq -e --arg strategy "$strategy" '
        .[$strategy].enabled == true and
        .[$strategy].strategies[$strategy].TICK_PROCESSING.count > 0 and
        .[$strategy].strategies[$strategy].TICK_PROCESSING.p99Nanos < 100000000 and
        .[$strategy].strategies[$strategy].TICK_PROCESSING.maxNanos < 1000000000
    ' "$case_dir/evidence/latency.json" >/dev/null || fail "$case_id tick-processing latency gate failed"
    "$cli" daemon stop --state-dir "$case_dir/state" > "$case_dir/evidence/daemon-stop.log"
    wait "${exec_pids[$index]}" || fail "container $case_id daemon failed during final stop"
    exec_pids[$index]=""
    mapfile -t status_evidence < <(find "$case_dir/evidence" -type f -name 'status-*.json' | sort)
    max_dropped="$(jq -s '[.[].perStrategy[]?.droppedTicks] | max // 0' \
        "$case_dir/evidence/health.jsonl" "${status_evidence[@]}")"
    max_queue="$(jq -s '[.[].perStrategy[]?.inboundQueueDepth] | max // 0' \
        "$case_dir/evidence/health.jsonl" "${status_evidence[@]}")"
    [ "$max_dropped" -eq 0 ] || fail "$case_id reported $max_dropped dropped live ticks"
    jq -e '.perStrategy[0].inboundQueueDepth == 0 and .perStrategy[0].droppedTicks == 0' \
        "$case_dir/evidence/status-final.json" >/dev/null || fail "$case_id did not finish with a drained queue"
    first_log="$case_dir/logs/daemon-1.log"
    for stream in $(if [ "$case_id" = a ]; then printf 'eur1 eur5 gbp1 gbp5'; else printf 'jpy1 jpy5 xau1 xau5'; fi); do
        rg --fixed-strings "container load trace stream=$stream" "$first_log" >/dev/null ||
            fail "$case_id generation 1 did not trace $stream"
    done
    if [ "$case_id" = a ] && rg --quiet 'container load trace stream=' "$case_dir/logs/daemon-2.log"; then
        fail "case a generation 2 re-fired a restored true rule edge"
    fi
    stale="$({ rg --no-heading --no-filename 'market data .* STALE:' "$case_dir/logs" || true; } |
        awk 'END {print NR + 0}')"
    recovered="$({ rg --no-heading --no-filename 'market data .* healthy again' "$case_dir/logs" || true; } |
        awk 'END {print NR + 0}')"
    [ "$recovered" -ge "$stale" ] || fail "$case_id ended with unrecovered stale market data"
    unexpected_errors="$({ rg --no-heading --no-filename '\bERROR\b' "$case_dir/logs" || true; } |
        { rg -v 'MarketDataGate.*STALE' || true; })"
    [ -z "$unexpected_errors" ] || fail "$case_id logs contain an unexpected ERROR"
    mapfile -t audits < <(find "$case_dir/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    [ "${#audits[@]}" -gt 0 ] || fail "$case_id produced no engine audit journal"
    for audit in "${audits[@]}"; do jq -c . "$audit" >/dev/null || fail "invalid audit JSONL: $audit"; done
    [ -z "$(find "$case_dir/state/state/audit-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "$case_id audit journal reported dropped records"
    order_events="$(jq -r 'select(
        ((.eventType // "") | test("BrokerEvent[.]Order(Accepted|Filled|Rejected)$")) or
        ((.eventType // "") | test("[.](RiskRejectedEvent|OrderEvent|FillAccountedEvent|DecisionOrderLinkedEvent)$"))
    ) | 1' "${audits[@]}" |
        awk 'END {print NR + 0}')"
    [ "$order_events" -eq 0 ] || fail "$case_id emitted an order, fill, accounting, linkage, or rejection event"
    warmup_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.WarmupTickEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    live_tick_events="$(jq -r 'select(.eventType == "com.qkt.events.TickEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    stream_candle_events="$(jq -r 'select(.eventType == "com.qkt.events.StreamCandleEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    strategy_evaluations="$(jq -r 'select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent") | 1' "${audits[@]}" |
        awk 'END {print NR + 0}')"
    [ "$warmup_tick_events" -gt 0 ] || fail "$case_id retained no warmup ticks"
    [ "$live_tick_events" -gt 0 ] || fail "$case_id retained no live ticks"
    [ "$stream_candle_events" -gt 0 ] || fail "$case_id retained no exact stream candles"
    [ "$strategy_evaluations" -gt 0 ] || fail "$case_id retained no completed strategy candle evaluations"
    stream_counts_file="$case_dir/evidence/stream-counts.json"
    printf '[]\n' > "$stream_counts_file"
    for symbol in ${case_symbols[$index]}; do
        alias_prefix=""
        case "$symbol" in
            EURUSD) alias_prefix=eur ;;
            GBPUSD) alias_prefix=gbp ;;
            USDJPY) alias_prefix=jpy ;;
            XAUUSD) alias_prefix=xau ;;
            *) fail "unsupported container-load symbol: $symbol" ;;
        esac
        jq -e --arg symbol "EXNESS:$symbol" 'select(.eventType == "com.qkt.events.TickEvent" and .symbol == $symbol)' \
            "${audits[@]}" >/dev/null || fail "$case_id retained no live ticks for $symbol"
        for timeframe_ms in 60000 300000; do
            timeframe="1m"
            [ "$timeframe_ms" -eq 60000 ] || timeframe="5m"
            alias="${alias_prefix}1"
            [ "$timeframe_ms" -eq 60000 ] || alias="${alias_prefix}5"
            warmup_tick_count="$(qkt_count_warmup_pseudo_ticks \
                "EXNESS:$symbol" "$timeframe_ms" -1 -1 "${audits[@]}")"
            expected_warmup_ticks=$((20 * 4 * generations[index]))
            [ "$warmup_tick_count" -eq "$expected_warmup_ticks" ] ||
                fail "$case_id $alias warmup count was $warmup_tick_count; expected $expected_warmup_ticks pseudo-ticks for configured 20 bars"
            jq -e --arg symbol "EXNESS:$symbol" --arg timeframe "$timeframe" '
                select(
                    .eventType == "com.qkt.events.StreamCandleEvent" and
                    .symbol == $symbol and .timeframe == $timeframe
                )
            ' "${audits[@]}" >/dev/null || fail "$case_id retained no $timeframe stream candles for $symbol"
            matched_evaluations="$(qkt_count_matched_evaluations \
                "$strategy" "$alias" "EXNESS:$symbol" "$timeframe" -1 -1 "${audits[@]}")"
            [ "$matched_evaluations" -gt 0 ] ||
                fail "$case_id retained no matched $alias strategy evaluation for $symbol $timeframe"
            jq --arg alias "$alias" --arg symbol "EXNESS:$symbol" --arg timeframe "$timeframe" \
                --argjson configuredWarmupBars 20 --argjson warmupPseudoTicks "$warmup_tick_count" \
                --argjson matchedEvaluations "$matched_evaluations" \
                '. + [{alias:$alias,symbol:$symbol,timeframe:$timeframe,
                    configuredWarmupBars:$configuredWarmupBars,warmupPseudoTicks:$warmupPseudoTicks,
                    matchedEvaluations:$matchedEvaluations}]' "$stream_counts_file" > "$stream_counts_file.tmp"
            mv "$stream_counts_file.tmp" "$stream_counts_file"
        done
    done
    if [ "$case_id" = a ]; then
        restart_start_ms="$(jq -er '.restartStartedAtMs' "$output/evidence/restart.json")"
        restart_ready_ms="$(jq -er '.restartReadyAtMs' "$output/evidence/restart.json")"
        jq -e '
            [.sequences[] | select(.name == "__qkt_rule_edges__") | .lastValues[]] as $edges |
            ($edges | length) == 4 and all($edges[]; . == true)
        ' "$sequence_state" >/dev/null || fail "case a did not restore four true rule edges"
        cp "$sequence_state" "$case_dir/evidence/rule-edges-after-restart.json"
        for alias in eur1 eur5 gbp1 gbp5; do
            case "$alias" in
                eur1) symbol=EURUSD; timeframe=1m; timeframe_ms=60000 ;;
                eur5) symbol=EURUSD; timeframe=5m; timeframe_ms=300000 ;;
                gbp1) symbol=GBPUSD; timeframe=1m; timeframe_ms=60000 ;;
                gbp5) symbol=GBPUSD; timeframe=5m; timeframe_ms=300000 ;;
            esac
            post_restart_warmups="$(qkt_count_warmup_pseudo_ticks \
                "EXNESS:$symbol" "$timeframe_ms" "$restart_start_ms" -1 "${audits[@]}")"
            [ "$post_restart_warmups" -eq 80 ] ||
                fail "case a generation 2 $alias warmup count was $post_restart_warmups; expected 80 pseudo-ticks"
            post_restart_matches="$(qkt_count_matched_evaluations "$strategy" "$alias" \
                "EXNESS:$symbol" "$timeframe" "$restart_ready_ms" -1 "${audits[@]}")"
            [ "$post_restart_matches" -gt 0 ] ||
                fail "case a generation 2 lacks a post-ready matched candle/evaluation for $alias"
        done
    else
        restart_start_ms="$(jq -er '.restartStartedAtMs' "$output/evidence/restart.json")"
        restart_ready_ms="$(jq -er '.restartReadyAtMs' "$output/evidence/restart.json")"
        peer_restart_ticks="$(qkt_count_events_in_window \
            "com.qkt.events.TickEvent" "$restart_start_ms" "$restart_ready_ms" "${audits[@]}")"
        [ "$peer_restart_ticks" -gt 0 ] || fail "case b retained no live tick while case a restarted"
        for alias in jpy1 jpy5 xau1 xau5; do
            case "$alias" in
                jpy1) symbol=USDJPY; timeframe=1m ;;
                jpy5) symbol=USDJPY; timeframe=5m ;;
                xau1) symbol=XAUUSD; timeframe=1m ;;
                xau5) symbol=XAUUSD; timeframe=5m ;;
            esac
            for boundary in before after; do
                after_ms=-1
                before_ms="$restart_start_ms"
                if [ "$boundary" = after ]; then
                    after_ms="$restart_ready_ms"
                    before_ms=-1
                fi
                boundary_matches="$(qkt_count_matched_evaluations "$strategy" "$alias" \
                    "EXNESS:$symbol" "$timeframe" "$after_ms" "$before_ms" "${audits[@]}")"
                [ "$boundary_matches" -gt 0 ] ||
                    fail "case b lacks a $boundary-restart matched candle/evaluation for $alias"
            done
        done
    fi
    mapfile -t transports < <(find "$case_dir/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    [ "${#transports[@]}" -gt 0 ] || fail "$case_id produced no MT5 transport journal"
    for transport in "${transports[@]}"; do jq -c . "$transport" >/dev/null || fail "invalid transport JSONL: $transport"; done
    [ -z "$(find "$case_dir/state/state/mt5-transport-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "$case_id transport journal reported dropped records"
    gateway_mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' \
        "${transports[@]}" | awk 'END {print NR + 0}')"
    [ "$gateway_mutations" -eq 0 ] || fail "$case_id issued a mutating gateway request"
    magic="${magics[$index]}"
    # MT5Client may use account-wide shared snapshots and apply magic ownership
    # locally when the shared read cache is active. Query-scoped reads remain a
    # valid alternative for gateways that support them.
    jq -e --arg orders "/orders?magic=$magic" --arg positions "/get_positions?magic=$magic" '
        select(.path == "/orders" or .path == "/get_positions" or
            .path == $orders or .path == $positions)
    ' "${transports[@]}" >/dev/null || fail "$case_id retained no ownership reads"
    foreign_magic_reads="$(jq -r --arg orders "/orders?magic=$magic" --arg positions "/get_positions?magic=$magic" '
        select((.path | test("^/(orders|get_positions)[?]magic=")) and .path != $orders and .path != $positions) | 1
    ' "${transports[@]}" | awk 'END {print NR + 0}')"
    [ "$foreign_magic_reads" -eq 0 ] || fail "$case_id transport crossed magic ownership"
    resource_samples="$(awk -F, -v caseId="$case_id" 'NR > 1 && $2 == caseId {count++} END {print count + 0}' \
        "$output/evidence/resources.csv")"
    minimum_resource_samples=$((duration_seconds / 10 - 10))
    [ "$minimum_resource_samples" -gt 0 ] || minimum_resource_samples=1
    [ "$resource_samples" -ge "$minimum_resource_samples" ] ||
        fail "$case_id retained only $resource_samples resource samples; expected at least $minimum_resource_samples"
    tick_latency="$(jq -c --arg strategy "$strategy" '.[$strategy].strategies[$strategy].TICK_PROCESSING' "$case_dir/evidence/latency.json")"
    jq -n \
        --arg caseId "$case_id" \
        --arg strategy "$strategy" \
        --arg generations "${generations[$index]}" \
        --arg maxQueue "$max_queue" \
        --arg maxDropped "$max_dropped" \
        --arg warmupTicks "$warmup_tick_events" \
        --arg liveTicks "$live_tick_events" \
        --arg streamCandles "$stream_candle_events" \
        --arg strategyEvaluations "$strategy_evaluations" \
        --arg magic "$magic" \
        --arg stale "$stale" \
        --arg recovered "$recovered" \
        --arg resourceSamples "$resource_samples" \
        --arg minimumResourceSamples "$minimum_resource_samples" \
        --slurpfile streams "$stream_counts_file" \
        --argjson latency "$tick_latency" '
        {
          schema:"qkt-live-multi-container-case-v2",status:"passed",
          caseId:$caseId,strategy:$strategy,generations:($generations|tonumber),
          maxInboundQueue:($maxQueue|tonumber),maxDroppedTicks:($maxDropped|tonumber),
          warmupTickEvents:($warmupTicks|tonumber),liveTickEvents:($liveTicks|tonumber),
          streamCandleEvents:($streamCandles|tonumber),strategyCandleEvaluations:($strategyEvaluations|tonumber),
          magic:($magic|tonumber),
          gatewayMutations:0,orderEvents:0,fills:0,
          staleEvents:($stale|tonumber),recoveredStaleEvents:($recovered|tonumber),
          resourceSamples:($resourceSamples|tonumber),minimumResourceSamples:($minimumResourceSamples|tonumber),
          polling:{tickPollIntervalMs:500,brokerPollIntervalMs:5000},stateAsync:true,
          bars:{configuredWarmupCounts:true,liveTicks:true,constructedBars:true,evaluationsJoined:true},
          streams:$streams[0],tickProcessing:$latency
        }
    ' > "$case_dir/evidence/result.json"
done

control_tokens=()
for case_id in "${case_ids[@]}"; do
    state_dir="$output/cases/$case_id/state"
    if [ -f "$state_dir/control.token" ]; then
        control_tokens+=("$(<"$state_dir/control.token")")
        unlink "$state_dir/control.token"
    fi
    [ ! -e "$state_dir/daemon.pid" ] || unlink "$state_dir/daemon.pid"
done

gateway_get /get_positions > "$output/evidence/positions-final.json"
gateway_get /orders > "$output/evidence/orders-final.json"
gateway_get /account > "$output/evidence/account-final.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-final.json" >/dev/null ||
    fail "multi-container run ended with an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-final.json" >/dev/null ||
    fail "multi-container run ended with a pending order"
jq -e --slurpfile initial "$output/evidence/account-initial.json" '
    .login == $initial[0].login and .server == $initial[0].server and
    .trade_mode == $initial[0].trade_mode and .currency == $initial[0].currency and
    .leverage == $initial[0].leverage and
    .balance == $initial[0].balance and .equity == $initial[0].equity and
    .margin == $initial[0].margin and
    .trade_allowed == $initial[0].trade_allowed and .trade_expert == $initial[0].trade_expert
' "$output/evidence/account-final.json" >/dev/null || fail "multi-container run changed account financial state"
QKT_BROKER_API_KEY="$QKT_BROKER_API_KEY" "$cli" bot history --broker exness --since "$run_started_ms" \
    --config "$output/cases/a/qkt.config.yaml" --json > "$output/evidence/history-during-run.json"
jq -e 'length == 0' "$output/evidence/history-during-run.json" >/dev/null || fail "venue deals occurred during read-only load"

if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$output"; then
    fail "broker credential was persisted in retained artifacts"
fi
for control_token in "${control_tokens[@]}"; do
    if [ -n "$control_token" ] && printf '%s\n' "$control_token" | rg --text --fixed-strings --quiet -f - "$output"; then
        fail "daemon control token was persisted in retained artifacts"
    fi
done

read -r max_cpu max_memory max_pids < <(
    awk -F, '
        NR > 1 { cpu[$1] += $4; memory[$1] += $5; pids[$1] += $6 }
        END {
            for (sample in cpu) {
                if (cpu[sample] > maxCpu) maxCpu = cpu[sample]
                if (memory[sample] > maxMemory) maxMemory = memory[sample]
                if (pids[sample] > maxPids) maxPids = pids[sample]
            }
            printf "%.2f %d %d\n", maxCpu + 0, maxMemory + 0, maxPids + 0
        }
    ' "$output/evidence/resources.csv"
)
jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg qktCommit "$qkt_commit" \
    --arg hostVersion "$host_version" \
    --arg image "$image" \
    --arg imageVersion "$image_version" \
    --arg requestedDuration "$duration_seconds" \
    --arg actualDuration "$elapsed_seconds" \
    --arg maxCpu "$max_cpu" \
    --arg maxMemory "$max_memory" \
    --arg maxPids "$max_pids" \
    --slurpfile restart "$output/evidence/restart.json" \
    --slurpfile caseA "$output/cases/a/evidence/result.json" \
    --slurpfile caseB "$output/cases/b/evidence/result.json" '
    {
      schema:"qkt-live-multi-container-load-v2",status:"passed",finishedAt:$finishedAt,
      qktCommit:$qktCommit,hostVersion:$hostVersion,image:$image,imageVersion:$imageVersion,
      requestedDurationSeconds:($requestedDuration|tonumber),actualDurationSeconds:($actualDuration|tonumber),
      containers:2,symbols:4,timeframes:["1m","5m"],streams:8,
      controlledRestart:true,restart:$restart[0],
      sourceAutoRedeploy:true,stateRestoreVerified:true,persistedDeploymentRestore:false,
      financiallyReadOnly:true,accountUnchanged:true,venueDealsDuringRun:0,
      gatewayMutations:0,orderEvents:0,fills:0,
      polling:{tickPollIntervalMs:500,brokerPollIntervalMs:5000,parallelTickSymbols:4,
        estimatedGatewayRequestsPerSecond:9.2},
      bars:{configuredWarmupCounts:true,liveTicks:true,constructedBars:true,evaluationsJoined:true,
        preAndPostRestart:true},
      stateAsync:true,dockerResourceRestrictionsVerifiedAbsent:true,jvmOverridesVerifiedAbsent:true,
      resources:{samplesPerCase:{a:$caseA[0].resourceSamples,b:$caseB[0].resourceSamples},
        maxAggregateCpuPercent:($maxCpu|tonumber),maxAggregateMemoryKiB:($maxMemory|tonumber),
        maxAggregatePids:($maxPids|tonumber)},
      publicationSafe:false,containsPrivateAccountMetadata:true,
      cases:[$caseA[0],$caseB[0]]
    }
' > "$output/evidence/result.json"

manifest="$output/evidence/artifact-manifest.json"
printf '{"schema":"qkt-live-multi-container-artifacts-v1","artifacts":[' > "$manifest"
first=true
while IFS= read -r -d '' artifact; do
    relative="${artifact#"$output/"}"
    [ "$relative" = "evidence/artifact-manifest.json" ] && continue
    [ "$relative" = "SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >> "$manifest"; fi
    jq -cn --arg path "$relative" --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" '{path:$path,size:$size,sha256:$sha256}' >> "$manifest"
done < <(find "$output" -type f -print0 | sort -z)
printf ']}\n' >> "$manifest"
(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)

trap - EXIT
cleanup
printf 'passed %s\n' "$output/evidence/result.json"
