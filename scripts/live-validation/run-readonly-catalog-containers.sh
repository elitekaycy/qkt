#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/live-validation/lib/catalog-startup-window.sh
source "$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"
# shellcheck source=scripts/live-validation/lib/catalog-evidence.sh
source "$repo_root/scripts/live-validation/lib/catalog-evidence.sh"

usage() {
    cat <<'EOF'
Usage: run-readonly-catalog-containers.sh --suite DIR --verify-only [--cli PATH]
       run-readonly-catalog-containers.sh --suite DIR --output DIR --image IMAGE \
         [--duration-seconds N] [--cli PATH]

Verifies or runs four isolated, financially read-only QKT containers in parallel.
The cases cover numeric/candle functions, cross-symbol plus multi-timeframe,
session/history, and a volume-capability rejection with a live bars control.
The live run uses only the prepared 127.0.0.1 MT5 gateway and requires
QKT_BROKER_API_KEY through process stdin; it retains no credential.
EOF
}

fail() {
    printf 'run-readonly-catalog-containers: %s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

suite=""
output=""
image=""
duration_seconds=360
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --suite) suite="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        --duration-seconds) duration_seconds="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$suite" ] || fail "--suite is required"
[ -d "$suite" ] || fail "suite directory not found: $suite"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
for command in find jq rg sha256sum sort; do require_command "$command"; done

suite="$(realpath "$suite")"
[ -f "$suite/suite.json" ] || fail "suite.json not found"
[ -f "$suite/SHA256SUMS" ] || fail "SHA256SUMS not found"
(cd "$suite" && sha256sum --check SHA256SUMS >/dev/null) || fail "prepared artifact checksum verification failed"

gateway_url="$(jq -er '.gatewayUrl' "$suite/suite.json")"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "suite gateway must be a localhost endpoint"
jq -e '
    .schema == "qkt-live-readonly-catalog-suite-v2" and
    .credentialsStored == false and
    .contract == {
      containers:4,parallel:true,financiallyReadOnly:true,requiredGatewayMutations:0,
      requiredOrderEvents:0,requiredFills:0,barsFirstClass:true,streamEvaluationRoles:true,
      polling:{tickPollIntervalMs:500,brokerPollIntervalMs:5000,parallelTickSymbols:5}
    } and
    ([.cases[].id] == ["numeric-candle","cross-multi-tf","session-history","volume-negative"]) and
    ([.cases[].magic] | unique | length) == 4 and
    all(.cases[];
        .expectedDeployment == "running" and (.streams | length) > 0 and (.vectors | length) > 0 and
        all(.streams[]; .evaluationRole == "rule-driver" or .evaluationRole == "dependency") and
        ([.streams[] | select(.evaluationRole == "rule-driver")] | length) > 0
    ) and
    (.cases[] | select(.id == "volume-negative") | .negativeDeployment) == "volume-capability-rejected"
' "$suite/suite.json" >/dev/null || fail "suite contract is not the reviewed four-case read-only catalog"

mapfile -t case_ids < <(jq -r '.cases[].id' "$suite/suite.json")
mapfile -t strategies < <(jq -r '.cases[].strategy' "$suite/suite.json")
mapfile -t magics < <(jq -r '.cases[].magic' "$suite/suite.json")
[ "${#case_ids[@]}" -eq 4 ] || fail "expected exactly four cases"

for index in 0 1 2 3; do
    case_dir="$suite/cases/${case_ids[$index]}"
    [ -d "$case_dir" ] || fail "case directory missing: ${case_ids[$index]}"
    [ -f "$case_dir/qkt.config.yaml" ] || fail "case config missing: ${case_ids[$index]}"
    mapfile -t controls < <(find "$case_dir/strategies/control" -type f -name '*.qkt' | sort)
    [ "${#controls[@]}" -eq 1 ] || fail "${case_ids[$index]} must have exactly one control strategy"
    [ "$("$cli" parse "${controls[0]}" 2>&1)" = "ok" ] || fail "control strategy does not parse: ${case_ids[$index]}"
    rg --fixed-strings "STRATEGY ${strategies[$index]} VERSION" "${controls[0]}" >/dev/null ||
        fail "${case_ids[$index]} control strategy name differs from its contract"
    if rg --ignore-case --quiet '(^[[:space:]]*|THEN[[:space:]]+)(BUY|SELL|CLOSE|CLOSE_ALL|FLATTEN|RESIZE|CANCEL|CANCEL_ALL|OCO_ENTRY|LATCH)\b' \
        "$case_dir/strategies" -g '*.qkt'; then
        fail "${case_ids[$index]} contains a financial DSL action"
    fi
    rg --fixed-strings "gateway_url: $gateway_url" "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "${case_ids[$index]} config differs from the reviewed localhost gateway"
    rg --fixed-strings 'api_key: ${QKT_BROKER_API_KEY}' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "${case_ids[$index]} config does not resolve the key at runtime"
    rg --fixed-strings 'tick_poll_interval_ms: 500' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "${case_ids[$index]} config does not use the reviewed tick polling interval"
    rg --fixed-strings 'poll_interval_ms: 5000' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "${case_ids[$index]} config does not use the reviewed broker polling interval"
    rg --fixed-strings "magic: ${magics[$index]}" "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "${case_ids[$index]} config magic differs from its contract"
    if [ "${case_ids[$index]}" = volume-negative ]; then
        mapfile -t negatives < <(find "$case_dir/strategies/negative" -type f -name '*.qkt' | sort)
        [ "${#negatives[@]}" -eq 1 ] || fail "volume-negative must have exactly one negative strategy"
        [ "$("$cli" parse "${negatives[0]}" 2>&1)" = "ok" ] || fail "volume-negative strategy does not parse"
        rg --fixed-strings 'vwap(eur.tick' "${negatives[0]}" >/dev/null || fail "volume-negative lacks VWAP"
        rg --fixed-strings 'obv(eur.candle)' "${negatives[0]}" >/dev/null || fail "volume-negative lacks OBV"
    elif find "$case_dir/strategies/negative" -type f -name '*.qkt' -print -quit | rg . >/dev/null; then
        fail "only volume-negative may contain a negative strategy"
    fi
done

if $verify_only; then
    [ -z "$output" ] || fail "--output is not accepted with --verify-only"
    [ -z "$image" ] || fail "--image is not accepted with --verify-only"
    printf 'verified %s\n' "$suite/suite.json"
    exit 0
fi

[ -n "$output" ] || fail "--output is required for a live run"
[ -n "$image" ] || fail "--image is required for a live run"
[[ "$duration_seconds" =~ ^[0-9]+$ ]] || fail "--duration-seconds must be an integer"
[ "$duration_seconds" -ge 330 ] && [ "$duration_seconds" -le 900 ] ||
    fail "--duration-seconds must be in 330..900 so M5 bars close without an unbounded run"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for variable in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS GRADLE_OPTS; do
    [ -z "${!variable:-}" ] || fail "$variable must be unset; this run does not restrict or override the JVM"
done
for command in curl docker stat; do require_command "$command"; done
[ ! -e "$output" ] || fail "output already exists: $output"
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"

qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
prepared_commit="$(jq -er '.qktCommit' "$suite/suite.json")"
[ "$prepared_commit" = "$qkt_commit" ] || fail "suite was not prepared from current HEAD"
qkt_short="${qkt_commit:0:8}"
host_version="$("$cli" --version)"
[[ "$host_version" == *"($qkt_short)"* ]] || fail "host CLI is not built from $qkt_short"
image_version="$(docker run --rm --entrypoint qkt "$image" --version)"
[[ "$image_version" == *"($qkt_short)"* ]] || fail "Docker image is not built from $qkt_short"
if docker image inspect "$image" | jq -e '
    any(.[0].Config.Env[]?; test("^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|GRADLE_OPTS)="))
' >/dev/null; then
    fail "image config restricts or overrides the JVM"
fi

output="$(realpath -m "$output")"
mkdir -m 700 -p "$output/cases" "$output/evidence" "$output/source"
cp "$suite/suite.json" "$suite/SHA256SUMS" "$output/source/"
for case_id in "${case_ids[@]}"; do
    mkdir -m 700 -p "$output/cases/$case_id"
    cp -a "$suite/cases/$case_id/." "$output/cases/$case_id/"
done

run_started_ms="$(date +%s%3N)"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail-with-body --config - "$gateway_url$path"
}

wait_for_catalog_startup_window() {
    local evidence="$output/evidence/startup-window.jsonl"
    local total_wait_seconds=0
    local max_total_wait_seconds=260
    local attempt
    : > "$evidence"
    for attempt in 1 2 3; do
        local tick_file="$output/evidence/startup-tick-$attempt.json"
        gateway_get /symbol_info_tick/EURUSDm > "$tick_file"
        local broker_tick_ms
        broker_tick_ms="$(jq -er '(.time_msc // ((.time | tonumber) * 1000)) | tonumber' "$tick_file")" ||
            fail "gateway startup tick did not contain a usable broker timestamp"
        local observed_at_ms="$(date +%s%3N)"
        local tick_age_ms=$((observed_at_ms - broker_tick_ms))
        [ "$tick_age_ms" -ge -5000 ] && [ "$tick_age_ms" -le 60000 ] ||
            fail "gateway startup tick is not current enough to select a safe launch window"
        local phase_clock_ms="$broker_tick_ms"
        [ "$tick_age_ms" -lt 0 ] || phase_clock_ms=$((broker_tick_ms + tick_age_ms))
        local broker_phase_ms=$((broker_tick_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local phase_ms=$((phase_clock_ms % QKT_CATALOG_ROLLOVER_PERIOD_MS))
        local delay_ms
        delay_ms="$(qkt_catalog_startup_delay_ms "$phase_ms")" || fail "invalid broker startup phase: $phase_ms"
        local sleep_seconds=0
        if [ "$delay_ms" -gt 0 ]; then
            # Round up and cross the boundary by one second instead of launching on it.
            sleep_seconds=$(((delay_ms + 999) / 1000 + 1))
        fi
        jq -cn --argjson attempt "$attempt" --argjson observedAtMs "$observed_at_ms" \
            --argjson brokerTickMs "$broker_tick_ms" --argjson phaseMs "$phase_ms" \
            --argjson phaseClockMs "$phase_clock_ms" --argjson brokerPhaseMs "$broker_phase_ms" \
            --argjson tickAgeMs "$tick_age_ms" --argjson delayMs "$delay_ms" --argjson sleepSeconds "$sleep_seconds" \
            '{attempt:$attempt,observedAtMs:$observedAtMs,brokerTickMs:$brokerTickMs,
              tickAgeMs:$tickAgeMs,phaseClockMs:$phaseClockMs,brokerPhaseMs:$brokerPhaseMs,
              phaseMs:$phaseMs,delayMs:$delayMs,sleepSeconds:$sleepSeconds,
              safeToLaunch:($delayMs == 0)}' >> "$evidence"
        if [ "$delay_ms" -eq 0 ]; then
            jq -n --argjson enteredAtBrokerMs "$broker_tick_ms" --argjson enteredAtClockMs "$phase_clock_ms" \
                --argjson enteredAtPhaseMs "$phase_ms" \
                --argjson totalWaitSeconds "$total_wait_seconds" '
                {schema:"qkt-live-readonly-catalog-startup-window-v1",status:"passed",
                 clockSource:"broker-tick-validated-utc",wireSymbol:"EURUSDm",periodMs:300000,
                 safeStartMs:90000,safeEndMs:150000,
                 enteredAtBrokerMs:$enteredAtBrokerMs,enteredAtClockMs:$enteredAtClockMs,
                 enteredAtPhaseMs:$enteredAtPhaseMs,
                 totalWaitSeconds:$totalWaitSeconds,maxWaitSeconds:260,maxObservations:3}
            ' > "$output/evidence/startup-window.json"
            return
        fi
        [ "$((total_wait_seconds + sleep_seconds))" -le "$max_total_wait_seconds" ] ||
            fail "broker tick clock did not enter the catalog startup window within 260 seconds"
        total_wait_seconds=$((total_wait_seconds + sleep_seconds))
        sleep "$sleep_seconds"
    done
    fail "broker tick clock did not enter the bounded catalog startup window after three observations"
}

gateway_get /health > "$output/evidence/gateway-health.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$output/evidence/gateway-health.json" >/dev/null || fail "gateway is not healthy and connected"
gateway_get /account > "$output/evidence/account-initial.json"
jq -e --slurpfile suite "$suite/suite.json" '
    .login == $suite[0].account.login and .server == $suite[0].account.server and
    .trade_mode == 0 and .currency == $suite[0].account.currency and
    .balance == ($suite[0].account.balance | tonumber) and .equity == ($suite[0].account.balance | tonumber) and
    .margin == 0 and .leverage == $suite[0].account.leverage and
    .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-initial.json" >/dev/null || fail "account does not match the flat demo allowlist"
gateway_get /get_positions > "$output/evidence/positions-initial.json"
gateway_get /orders > "$output/evidence/orders-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-initial.json" >/dev/null ||
    fail "demo account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-initial.json" >/dev/null ||
    fail "demo account has a pending order"
wait_for_catalog_startup_window

containers=()
daemon_pids=("" "" "" "")
for index in 0 1 2 3; do
    containers+=("qkt-readonly-catalog-${case_ids[$index]}-$run_id")
done

cleanup() {
    set +e
    for index in 0 1 2 3; do
        "$cli" daemon stop --state-dir "$output/cases/${case_ids[$index]}/state" >/dev/null 2>&1
    done
    for pid in "${daemon_pids[@]}"; do
        [ -z "$pid" ] || wait "$pid" >/dev/null 2>&1
    done
    for container in "${containers[@]}"; do
        docker rm -f "$container" >/dev/null 2>&1
    done
}
trap cleanup EXIT

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    case_dir="$output/cases/$case_id"
    docker run --detach \
        --name "${containers[$index]}" \
        --network host \
        --user "$(id -u):$(id -g)" \
        --entrypoint /bin/sh \
        --volume "$case_dir:/work" \
        --workdir /work \
        "$image" -c 'while :; do sleep 3600; done' >/dev/null
    docker inspect "${containers[$index]}" | jq -e '
        .[0].HostConfig.Memory == 0 and .[0].HostConfig.NanoCpus == 0 and
        .[0].HostConfig.CpuQuota == 0 and
        (.[0].HostConfig.PidsLimit == null or .[0].HostConfig.PidsLimit == 0) and
        .[0].HostConfig.CpusetCpus == ""
    ' >/dev/null || fail "$case_id container has an unexpected resource restriction"
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --text --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY"); then
        fail "broker credential was stored in $case_id container configuration"
    fi
    docker inspect "${containers[$index]}" | jq '.[0] | {
        schema:"qkt-live-readonly-catalog-container-v1",id:.Id,image:.Image,
        resourceRestrictions:{memoryBytes:.HostConfig.Memory,nanoCpus:.HostConfig.NanoCpus,
          cpuQuota:.HostConfig.CpuQuota,pidsLimit:.HostConfig.PidsLimit,cpusetCpus:.HostConfig.CpusetCpus},
        credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false
    }' > "$case_dir/evidence/container.json"
done

launch_ms=()
for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    case_dir="$output/cases/$case_id"
    launch_ms+=("$(date +%s%3N)")
    (
        printf '%s\n' "$QKT_BROKER_API_KEY" |
            docker exec -i "${containers[$index]}" /bin/sh -c '
                IFS= read -r QKT_BROKER_API_KEY
                export QKT_BROKER_API_KEY QKT_STATE_DIR=/work/state QKT_LATENCY_TRACKING=1
                exec qkt daemon start --config /work/qkt.config.yaml --state-dir /work/state --load-dir /work/strategies/control
            '
    ) > "$case_dir/logs/daemon.log" 2>&1 &
    daemon_pids[$index]=$!
done
launch_min="$(printf '%s\n' "${launch_ms[@]}" | sort -n | head -n1)"
launch_max="$(printf '%s\n' "${launch_ms[@]}" | sort -n | tail -n1)"
[ "$((launch_max - launch_min))" -le 1500 ] || fail "parallel daemon launch skew exceeded 1500 ms"

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    state="$output/cases/$case_id/state"
    ready=false
    deadline=$((SECONDS + 120))
    while [ "$SECONDS" -lt "$deadline" ]; do
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "$case_id daemon exited before readiness"
        if rg --quiet 'failed to auto-deploy' "$output/cases/$case_id/logs/daemon.log"; then
            rg --no-heading 'failed to auto-deploy' "$output/cases/$case_id/logs/daemon.log" \
                > "$output/cases/$case_id/evidence/startup-deploy-failure.log"
            fail "$case_id control strategy auto-deploy failed after the guarded startup window"
        fi
        if "$cli" daemon status --state-dir "$state" --json > "$output/cases/$case_id/evidence/status-ready.json" 2>/dev/null &&
            jq -e --arg strategy "${strategies[$index]}" '
                .status == "ok" and .strategies == 1 and .perStrategy[0].name == $strategy and
                .perStrategy[0].running == true and .perStrategy[0].halted == false
            ' "$output/cases/$case_id/evidence/status-ready.json" >/dev/null; then
            ready=true
            break
        fi
        sleep 1
    done
    $ready || fail "$case_id daemon did not become ready"
done

volume_index=3
volume_dir="$output/cases/${case_ids[$volume_index]}"
negative_source="$(find "$volume_dir/strategies/negative" -type f -name '*.qkt' -print -quit)"
set +e
"$cli" deploy "$negative_source" \
    --as "$(jq -er '.cases[] | select(.id == "volume-negative") | .negativeStrategy' "$suite/suite.json")" \
    --state-dir "$volume_dir/state" --json > "$volume_dir/evidence/volume-deploy.stdout" \
    2> "$volume_dir/evidence/volume-deploy.stderr"
volume_deploy_code=$?
set -e
[ "$volume_deploy_code" -ne 0 ] || fail "volume-requiring strategy unexpectedly deployed"
rg --ignore-case 'does not supply volume|volume-bearing feed|VOLUME' \
    "$volume_dir/evidence/volume-deploy.stdout" "$volume_dir/evidence/volume-deploy.stderr" >/dev/null ||
    fail "volume deployment failed without the expected capability diagnostic"
jq -n --argjson exitCode "$volume_deploy_code" \
    '{schema:"qkt-live-volume-capability-rejection-v1",status:"passed",exitCode:$exitCode,
      expected:"data feed does not supply volume",financialMutationAttempted:false}' \
    > "$volume_dir/evidence/volume-capability-rejection.json"

printf 'elapsed_seconds,case,cpu_percent,memory_usage,pids\n' > "$output/evidence/resources.csv"
observation_started=$SECONDS
next_sample=10
while true; do
    sleep 1
    elapsed=$((SECONDS - observation_started))
    for index in 0 1 2 3; do
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "${case_ids[$index]} daemon exited during observation"
    done
    if [ "$elapsed" -ge "$next_sample" ]; then
        for index in 0 1 2 3; do
            case_id="${case_ids[$index]}"
            state="$output/cases/$case_id/state"
            status_file="$output/cases/$case_id/evidence/status-sample-latest.json"
            "$cli" daemon status --state-dir "$state" --json > "$status_file" ||
                fail "$case_id control status sample failed"
            jq -e '
                .status == "ok" and .strategies == 1 and .perStrategy[0].running == true and
                .perStrategy[0].halted == false and .perStrategy[0].droppedTicks == 0
            ' "$status_file" >/dev/null || fail "$case_id health sample failed"
            jq -c --argjson second "$elapsed" '. + {sampleSecond:$second}' "$status_file" \
                >> "$output/cases/$case_id/evidence/health.jsonl"
            stats="$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}|{{.PIDs}}' "${containers[$index]}")"
            printf '%s,%s,%s,%s,%s\n' "$elapsed" "$case_id" "${stats%%|*}" "$(cut -d '|' -f2 <<<"$stats")" "${stats##*|}" \
                >> "$output/evidence/resources.csv"
        done
        next_sample=$((next_sample + 10))
    fi
    [ "$elapsed" -ge "$duration_seconds" ] && break
done

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    case_dir="$output/cases/$case_id"
    "$cli" daemon status --state-dir "$case_dir/state" --json > "$case_dir/evidence/status-final.json"
    jq -e '.strategies == 1 and .perStrategy[0].running == true and .perStrategy[0].halted == false and
        .perStrategy[0].droppedTicks == 0 and .perStrategy[0].inboundQueueDepth == 0' \
        "$case_dir/evidence/status-final.json" >/dev/null || fail "$case_id did not finish healthy and drained"
    port="$(<"$case_dir/state/control.port")"
    curl --silent --show-error --fail "http://127.0.0.1:$port/latency" > "$case_dir/evidence/latency.json"
    cp "$case_dir/logs/daemon.log" "$case_dir/evidence/runtime-before-shutdown.log"
    "$cli" daemon stop --state-dir "$case_dir/state" > "$case_dir/evidence/daemon-stop.log"
    wait "${daemon_pids[$index]}" || fail "$case_id daemon failed during final stop"
    daemon_pids[$index]=""
    if ! qkt_catalog_runtime_log_summary "$case_dir/evidence/runtime-before-shutdown.log" \
        "$case_dir/logs/daemon.log" > "$case_dir/evidence/runtime-log-summary.json"; then
        fail "$case_id runtime log policy failed"
    fi
    stale_events="$(jq -er '.staleEvents' "$case_dir/evidence/runtime-log-summary.json")"
    recovered_stale_events="$(jq -er '.recoveredStaleEvents' "$case_dir/evidence/runtime-log-summary.json")"
    shutdown_disconnect_warnings="$(jq -er '.shutdownDisconnectWarnings' "$case_dir/evidence/runtime-log-summary.json")"

    mapfile -t audits < <(find "$case_dir/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    [ "${#audits[@]}" -gt 0 ] || fail "$case_id produced no engine audit journal"
    for audit in "${audits[@]}"; do jq -c . "$audit" >/dev/null || fail "invalid audit JSONL: $audit"; done
    [ -z "$(find "$case_dir/state/state/audit-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "$case_id audit journal reported dropped records"
    order_events="$(jq -r 'select(
        ((.eventType // "") | test("BrokerEvent.Order(Accepted|Filled|Rejected)")) or
        ((.eventType // "") | test("RiskRejectedEvent|OrderEvent|FillAccountedEvent"))
    ) | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    [ "$order_events" -eq 0 ] || fail "$case_id emitted an order, fill, accounting, or rejection event"

    rule_driver_streams=0
    dependency_streams=0
    while IFS=$'\t' read -r alias symbol timeframe warmup_bars evaluation_role; do
        timeframe_ms=60000
        [ "$timeframe" = "1m" ] || timeframe_ms=300000
        warmup_tick_count="$(jq -s --arg symbol "$symbol" --argjson timeframeMs "$timeframe_ms" '[.[] |
            select(.eventType == "com.qkt.events.WarmupTickEvent" and
                .symbol == $symbol and .sourceTimeframeMs == $timeframeMs)] | length
        ' "${audits[@]}")"
        [ "$warmup_tick_count" -ge "$warmup_bars" ] ||
            fail "$case_id lacks the configured $warmup_bars-bar warmup evidence for $alias"
        jq -e --arg symbol "$symbol" '
            select(.eventType == "com.qkt.events.TickEvent" and .symbol == $symbol)
        ' "${audits[@]}" >/dev/null || fail "$case_id lacks $alias live tick evidence"
        matched_evaluations="$(qkt_catalog_matched_evaluation_count "${strategies[$index]}" "$alias" \
            "$symbol" "$timeframe" "$evaluation_role" "${audits[@]}")" ||
            fail "$case_id has an invalid evaluation role for $alias"
        [ "$matched_evaluations" -gt 0 ] ||
            fail "$case_id lacks matched constructed bar/evaluation evidence for $alias ($evaluation_role)"
        if [ "$evaluation_role" = rule-driver ]; then
            rule_driver_streams=$((rule_driver_streams + 1))
        else
            dependency_streams=$((dependency_streams + 1))
        fi
    done < <(jq -r --arg id "$case_id" '.cases[] | select(.id == $id) | .streams[] |
        [.alias,.symbol,.timeframe,(.warmupBars|tostring),.evaluationRole] | @tsv' "$suite/suite.json")

    rg --no-heading --fixed-strings "catalog vector case=$case_id" "$case_dir/logs/daemon.log" \
        > "$case_dir/evidence/evaluation-vectors.log" || fail "$case_id produced no evaluation vector"
    while IFS= read -r vector; do
        rg --fixed-strings "catalog vector case=$case_id $vector" "$case_dir/evidence/evaluation-vectors.log" >/dev/null ||
            fail "$case_id did not emit readiness vector $vector"
    done < <(jq -r --arg id "$case_id" '.cases[] | select(.id == $id) | .vectors[]' "$suite/suite.json")
    if [ "$case_id" = cross-multi-tf ]; then
        for consumed_alias in eur1 eur5 gbp1 gbp5; do
            rg --fixed-strings "$consumed_alias=" "$case_dir/evidence/evaluation-vectors.log" >/dev/null ||
                fail "$case_id dependency proof vector did not consume $consumed_alias"
        done
    fi

    mapfile -t transports < <(find "$case_dir/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    [ "${#transports[@]}" -gt 0 ] || fail "$case_id produced no MT5 transport journal"
    for transport in "${transports[@]}"; do jq -c . "$transport" >/dev/null || fail "invalid transport JSONL: $transport"; done
    [ -z "$(find "$case_dir/state/state/mt5-transport-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "$case_id transport journal reported dropped records"
    mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' "${transports[@]}" |
        awk 'END {print NR + 0}')"
    [ "$mutations" -eq 0 ] || fail "$case_id issued a mutating gateway request"
    magic="${magics[$index]}"
    jq -e --arg orders "/orders?magic=$magic" --arg positions "/get_positions?magic=$magic" '
        select(.path == $orders or .path == $positions)
    ' "${transports[@]}" >/dev/null || fail "$case_id retained no magic-scoped ownership reads"

    warmups="$(jq -r 'select(.eventType == "com.qkt.events.WarmupTickEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    ticks="$(jq -r 'select(.eventType == "com.qkt.events.TickEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    candles="$(jq -r 'select(.eventType == "com.qkt.events.StreamCandleEvent") | 1' "${audits[@]}" | awk 'END {print NR + 0}')"
    evaluations="$(jq -r 'select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent") | 1' "${audits[@]}" |
        awk 'END {print NR + 0}')"
    vectors="$(awk 'END {print NR + 0}' "$case_dir/evidence/evaluation-vectors.log")"
    jq -n --arg caseId "$case_id" --arg strategy "${strategies[$index]}" \
        --argjson warmups "$warmups" --argjson ticks "$ticks" --argjson candles "$candles" \
        --argjson evaluations "$evaluations" --argjson vectors "$vectors" \
        --argjson ruleDriverStreams "$rule_driver_streams" --argjson dependencyStreams "$dependency_streams" \
        --argjson staleEvents "$stale_events" --argjson recoveredStaleEvents "$recovered_stale_events" \
        --argjson shutdownDisconnectWarnings "$shutdown_disconnect_warnings" '
        {schema:"qkt-live-readonly-catalog-case-result-v2",status:"passed",caseId:$caseId,strategy:$strategy,
          counts:{warmupTicks:$warmups,liveTicks:$ticks,constructedBars:$candles,evaluations:$evaluations,
            readinessVectors:$vectors,gatewayMutations:0,orderEvents:0,fills:0},
          bars:{warmupBars:true,readinessVectors:true,liveTicks:true,constructedBars:true,evaluationsJoined:true},
          evaluationRoles:{ruleDriverStreams:$ruleDriverStreams,dependencyStreams:$dependencyStreams,
            positiveRuleCountRequiredOnlyForDrivers:true},
          runtimeLogs:{staleEvents:$staleEvents,recoveredStaleEvents:$recoveredStaleEvents,
            inWindowDisconnectWarnings:0,shutdownDisconnectWarnings:$shutdownDisconnectWarnings,
            postBoundaryStaleEvents:0,unexpectedErrors:0,allStaleEpisodesRecovered:true},
          financiallyReadOnly:true}
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
    fail "read-only catalog run ended with an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-final.json" >/dev/null ||
    fail "read-only catalog run ended with a pending order"
jq -e --slurpfile initial "$output/evidence/account-initial.json" '
    .login == $initial[0].login and .server == $initial[0].server and .trade_mode == $initial[0].trade_mode and
    .currency == $initial[0].currency and .balance == $initial[0].balance and .equity == $initial[0].equity and
    .margin == $initial[0].margin and .leverage == $initial[0].leverage and
    .trade_allowed == $initial[0].trade_allowed and .trade_expert == $initial[0].trade_expert
' "$output/evidence/account-final.json" >/dev/null || fail "read-only catalog run changed account financial state"
QKT_BROKER_API_KEY="$QKT_BROKER_API_KEY" "$cli" bot history --broker exness --since "$run_started_ms" \
    --config "$output/cases/${case_ids[0]}/qkt.config.yaml" --json > "$output/evidence/history-during-run.json"
jq -e 'length == 0' "$output/evidence/history-during-run.json" >/dev/null || fail "venue deals occurred during read-only catalog run"

if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$output"; then
    fail "broker credential was persisted in retained artifacts"
fi
for control_token in "${control_tokens[@]}"; do
    if [ -n "$control_token" ] && printf '%s\n' "$control_token" |
        rg --text --fixed-strings --quiet -f - "$output"; then
        fail "daemon control token was persisted in retained artifacts"
    fi
done

jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg qktCommit "$qkt_commit" --arg hostVersion "$host_version" \
    --arg image "$image" --arg imageVersion "$image_version" \
    --argjson durationSeconds "$duration_seconds" \
    --slurpfile startupWindow "$output/evidence/startup-window.json" \
    --slurpfile suite "$suite/suite.json" \
    --slurpfile numeric "$output/cases/numeric-candle/evidence/result.json" \
    --slurpfile cross "$output/cases/cross-multi-tf/evidence/result.json" \
    --slurpfile session "$output/cases/session-history/evidence/result.json" \
    --slurpfile volume "$output/cases/volume-negative/evidence/result.json" '
    {schema:"qkt-live-readonly-catalog-run-v2",status:"passed",finishedAt:$finishedAt,
      qktCommit:$qktCommit,hostVersion:$hostVersion,image:$image,imageVersion:$imageVersion,
      durationSeconds:$durationSeconds,containers:4,parallelLaunch:true,
      startupWindow:$startupWindow[0],
      financiallyReadOnly:true,accountUnchanged:true,venueDealsDuringRun:0,
      gatewayMutations:0,orderEvents:0,fills:0,volumeCapabilityRejected:true,
      polling:$suite[0].contract.polling,
      bars:{warmupBars:true,readinessVectors:true,liveTicks:true,constructedBars:true,evaluationsJoined:true},
      evaluationRoles:{positiveRuleCountRequiredOnlyForDrivers:true},
      runtimeLogs:{
        staleEvents:([$numeric[0],$cross[0],$session[0],$volume[0]] | map(.runtimeLogs.staleEvents) | add),
        recoveredStaleEvents:([$numeric[0],$cross[0],$session[0],$volume[0]] | map(.runtimeLogs.recoveredStaleEvents) | add),
        inWindowDisconnectWarnings:0,
        shutdownDisconnectWarnings:([$numeric[0],$cross[0],$session[0],$volume[0]] | map(.runtimeLogs.shutdownDisconnectWarnings) | add),
        postBoundaryStaleEvents:0,unexpectedErrors:0,allStaleEpisodesRecovered:true},
      dockerResourceRestrictionsVerifiedAbsent:true,jvmOverridesVerifiedAbsent:true,
      publicationSafe:false,containsPrivateAccountMetadata:true,
      cases:[$numeric[0],$cross[0],$session[0],$volume[0]]}
' > "$output/evidence/result.json"

manifest="$output/evidence/artifact-manifest.json"
printf '{"schema":"qkt-live-readonly-catalog-artifacts-v1","artifacts":[' > "$manifest"
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
