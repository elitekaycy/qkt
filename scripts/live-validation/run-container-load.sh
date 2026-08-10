#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-container-load.sh --output DIR --image IMAGE --gateway-url URL \
  --expected-login N --expected-server NAME --expected-balance DECIMAL \
  --expected-leverage N [--duration-seconds N] [--cli PATH]

Runs two isolated, read-only QKT containers against a localhost MT5 demo gateway.
The seven-minute minimum covers M1/M5 routing while one container is restarted.
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
duration_seconds=420
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
[ "$duration_seconds" -ge 420 ] || fail "--duration-seconds must be at least 420"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for command in curl docker find jq rg sha256sum sort; do
    command -v "$command" >/dev/null 2>&1 || fail "required command not found: $command"
done

output="$(realpath -m "$output")"
[ ! -e "$output" ] || fail "output already exists: $output"
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
qkt_short="${qkt_commit:0:8}"
host_version="$("$cli" --version)"
[[ "$host_version" == *"($qkt_short)"* ]] || fail "host CLI is not built from $qkt_short"
image_version="$(docker run --rm --entrypoint qkt "$image" --version)"
[[ "$image_version" == *"($qkt_short)"* ]] || fail "Docker image is not built from $qkt_short"

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
    tick_poll_interval_ms: 100
    poll_interval_ms: 1000
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
        rg --fixed-strings --quiet "$QKT_BROKER_API_KEY"; then
        fail "broker credential was stored in ${containers[$index]} configuration"
    fi
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
    local state="$output/cases/${case_ids[$index]}/state"
    local deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        if ! kill -0 "${exec_pids[$index]}" 2>/dev/null; then
            fail "container ${case_ids[$index]} daemon exited before readiness"
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

restart_second=$((duration_seconds / 2))
restart_completed=false
for second in $(seq 1 "$duration_seconds"); do
    sleep 1
    for index in 0 1; do
        kill -0 "${exec_pids[$index]}" 2>/dev/null || fail "container ${case_ids[$index]} daemon exited during load"
    done
    if [ "$second" -eq "$restart_second" ]; then
        "$cli" daemon status --state-dir "$output/cases/b/state" --json > "$output/cases/b/evidence/status-before-peer-restart.json"
        "$cli" daemon stop --state-dir "$output/cases/a/state" > "$output/cases/a/evidence/stop-for-restart.log"
        wait "${exec_pids[0]}" || fail "container a daemon failed during controlled stop"
        "$cli" daemon status --state-dir "$output/cases/b/state" --json > "$output/cases/b/evidence/status-during-peer-restart.json"
        jq -e '.status == "ok" and .perStrategy[0].running == true' \
            "$output/cases/b/evidence/status-during-peer-restart.json" >/dev/null ||
            fail "container b was disrupted while container a restarted"
        start_daemon 0 2
        wait_ready 0
        restart_completed=true
        jq -n --argjson atSecond "$second" --arg containerA "${containers[0]}" --arg containerB "${containers[1]}" \
            '{status:"passed",atSecond:$atSecond,restarted:$containerA,uninterrupted:$containerB}' \
            > "$output/evidence/restart.json"
    fi
    if [ $((second % 10)) -eq 0 ]; then
        for index in 0 1; do
            case_id="${case_ids[$index]}"
            status="$("$cli" daemon status --state-dir "$output/cases/$case_id/state" --json)"
            jq -c --argjson second "$second" --argjson generation "${generations[$index]}" \
                '. + {sampleSecond:$second,generation:$generation}' <<<"$status" \
                >> "$output/cases/$case_id/evidence/health.jsonl"
            stats="$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}|{{.PIDs}}' "${containers[$index]}")"
            cpu="${stats%%|*}"
            rest="${stats#*|}"
            memory="${rest%% / *}"
            pids="${stats##*|}"
            printf '%s,%s,%s,%s,%s,%s\n' \
                "$second" "$case_id" "${generations[$index]}" "${cpu%%%}" "$(memory_kib "$memory")" "$pids" \
                >> "$output/evidence/resources.csv"
        done
    fi
done
$restart_completed || fail "controlled restart did not run"

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
    max_dropped="$(jq -s '[.[].perStrategy[].droppedTicks] | max // 0' "$case_dir/evidence/health.jsonl")"
    max_queue="$(jq -s '[.[].perStrategy[].inboundQueueDepth] | max // 0' "$case_dir/evidence/health.jsonl")"
    [ "$max_dropped" -eq 0 ] || fail "$case_id reported $max_dropped dropped live ticks"
    jq -e '.perStrategy[0].inboundQueueDepth == 0 and .perStrategy[0].droppedTicks == 0' \
        "$case_dir/evidence/status-final.json" >/dev/null || fail "$case_id did not finish with a drained queue"
    for stream in $(if [ "$case_id" = a ]; then printf 'eur1 eur5 gbp1 gbp5'; else printf 'jpy1 jpy5 xau1 xau5'; fi); do
        rg --fixed-strings "container load trace stream=$stream" "$case_dir/logs" >/dev/null ||
            fail "$case_id did not trace $stream"
    done
    stale="$({ rg --no-heading --no-filename 'market data .* STALE:' "$case_dir/logs" || true; } |
        awk 'END {print NR + 0}')"
    recovered="$({ rg --no-heading --no-filename 'market data .* healthy again' "$case_dir/logs" || true; } |
        awk 'END {print NR + 0}')"
    [ "$recovered" -ge "$stale" ] || fail "$case_id ended with unrecovered stale market data"
    mapfile -t audits < <(find "$case_dir/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    [ "${#audits[@]}" -gt 0 ] || fail "$case_id produced no engine audit journal"
    for audit in "${audits[@]}"; do jq -c . "$audit" >/dev/null || fail "invalid audit JSONL: $audit"; done
    [ -z "$(find "$case_dir/state/state/audit-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "$case_id audit journal reported dropped records"
    order_events="$(jq -r 'select((.eventType // "") | test("BrokerEvent.Order(Accepted|Filled|Rejected)")) | 1' "${audits[@]}" |
        awk 'END {print NR + 0}')"
    [ "$order_events" -eq 0 ] || fail "$case_id read-only strategy emitted broker order events"
    tick_latency="$(jq -c --arg strategy "$strategy" '.[$strategy].strategies[$strategy].TICK_PROCESSING' "$case_dir/evidence/latency.json")"
    jq -n \
        --arg caseId "$case_id" \
        --arg strategy "$strategy" \
        --arg generations "${generations[$index]}" \
        --arg maxQueue "$max_queue" \
        --arg maxDropped "$max_dropped" \
        --arg stale "$stale" \
        --arg recovered "$recovered" \
        --argjson latency "$tick_latency" '
        {
          caseId:$caseId,strategy:$strategy,generations:($generations|tonumber),
          maxInboundQueue:($maxQueue|tonumber),maxDroppedTicks:($maxDropped|tonumber),
          staleEvents:($stale|tonumber),recoveredStaleEvents:($recovered|tonumber),tickProcessing:$latency
        }
    ' > "$case_dir/evidence/result.json"
done

for index in 0 1; do
    "$cli" daemon stop --state-dir "$output/cases/${case_ids[$index]}/state" \
        > "$output/cases/${case_ids[$index]}/evidence/daemon-stop.log"
    wait "${exec_pids[$index]}" || fail "container ${case_ids[$index]} daemon failed during final stop"
    exec_pids[$index]=""
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
    .balance == $initial[0].balance and .equity == $initial[0].equity and
    .margin == 0 and .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-final.json" >/dev/null || fail "multi-container run changed account financial state"
QKT_BROKER_API_KEY="$QKT_BROKER_API_KEY" "$cli" bot history --broker exness --since "$run_started_ms" \
    --config "$output/cases/a/qkt.config.yaml" --json > "$output/evidence/history-during-run.json"
jq -e 'length == 0' "$output/evidence/history-during-run.json" >/dev/null || fail "venue deals occurred during read-only load"

if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$output"; then
    fail "broker credential was persisted in retained artifacts"
fi

max_cpu="$(awk -F, 'NR > 1 && $4 > max {max=$4} END {printf "%.2f", max + 0}' "$output/evidence/resources.csv")"
max_memory="$(awk -F, 'NR > 1 && $5 > max {max=$5} END {print max + 0}' "$output/evidence/resources.csv")"
max_pids="$(awk -F, 'NR > 1 && $6 > max {max=$6} END {print max + 0}' "$output/evidence/resources.csv")"
jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg qktCommit "$qkt_commit" \
    --arg hostVersion "$host_version" \
    --arg image "$image" \
    --arg imageVersion "$image_version" \
    --arg duration "$duration_seconds" \
    --arg maxCpu "$max_cpu" \
    --arg maxMemory "$max_memory" \
    --arg maxPids "$max_pids" \
    --slurpfile caseA "$output/cases/a/evidence/result.json" \
    --slurpfile caseB "$output/cases/b/evidence/result.json" '
    {
      schema:"qkt-live-multi-container-load-v1",status:"passed",finishedAt:$finishedAt,
      qktCommit:$qktCommit,hostVersion:$hostVersion,image:$image,imageVersion:$imageVersion,
      durationSeconds:($duration|tonumber),containers:2,symbols:4,timeframes:["1m","5m"],streams:8,
      controlledRestart:true,accountUnchanged:true,venueDealsDuringRun:0,
      resources:{samplesPerContainer:(($duration|tonumber)/10),maxCpuPercent:$maxCpu,maxMemoryKiB:($maxMemory|tonumber),maxPids:($maxPids|tonumber)},
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
