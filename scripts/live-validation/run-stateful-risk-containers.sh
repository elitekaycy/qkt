#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-stateful-risk-containers.sh --suite DIR --verify-only [--cli PATH]
       run-stateful-risk-containers.sh --suite DIR --output DIR --image IMAGE \
         [--timeout-seconds N] [--cli PATH]

Verifies or runs four isolated QKT containers in parallel against one explicit
127.0.0.1 demo gateway. Each case restores persisted risk state, proves the real
halt rule trips on live 1m bars/ticks, and then requires one causally linked
RiskRejectedEvent before MT5 transport. The runner requires zero POST/PUT/PATCH/
DELETE gateway exchanges, zero order events, zero fills, and unchanged flat
venue state. The broker credential enters each process over stdin and is never
stored in Docker configuration or retained output.
EOF
}

fail() {
    printf 'run-stateful-risk-containers: %s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

count_records() {
    awk 'END {print NR + 0}'
}

suite=""
output=""
image=""
timeout_seconds=150
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --suite) suite="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$suite" ] || fail "--suite is required"
[ -d "$suite" ] || fail "suite directory not found: $suite"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
for command in awk date find git jq realpath rg sha256sum sort; do require_command "$command"; done

suite="$(realpath "$suite")"
[ -f "$suite/suite.json" ] || fail "suite.json not found"
[ -f "$suite/stateful-deferred.json" ] || fail "stateful-deferred.json not found"
[ -f "$suite/SHA256SUMS" ] || fail "SHA256SUMS not found"
(cd "$suite" && sha256sum --check SHA256SUMS >/dev/null) || fail "prepared artifact checksum verification failed"

gateway_url="$(jq -er '.gatewayUrl' "$suite/suite.json")"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "suite gateway must be a localhost endpoint"
jq -e '
    .schema == "qkt-live-stateful-risk-matrix-v1" and
    .credentialsStored == false and
    .contract == {
      containers:4,parallel:true,financiallyReadOnly:true,fixedIntentQty:"0.01",
      requiredGatewayMutations:0,requiredFills:0,barsObserved:true,restoredStateTripsLiveHalts:true
    } and
    .synchronization == {deployOnOddUtcMinute:true,triggerOnNextEvenUtcMinute:true} and
    ([.cases[].caseId] | sort) ==
      ["global-daily-loss","global-drawdown","loss-streak","strategy-daily-loss"] and
    ([.cases[].magic] | unique | length) == 4 and
    all(.cases[];
      .schema == "qkt-live-stateful-risk-case-v1" and
      .fixedIntentQty == "0.01" and .symbol == "EXNESS:EURUSD" and
      .required == {
        streamCandlesMin:1,evaluatedCandlesMin:1,haltedEvents:1,ruleDecisions:1,
        decisionOrderLinks:1,riskRejections:1,orderEvents:0,fills:0,gatewayMutations:0
      }
    ) and
    .deferredStateful.status == "deferred-not-passed" and
    ([.deferredStateful.cases[].id] | sort) == ["margin-floor"] and
    .claims.marginFloorPassed == false and .claims.productionReadiness == false
' "$suite/suite.json" >/dev/null || fail "suite contract is not the reviewed four-case stateful matrix"
jq -e '
    .schema == "qkt-live-risk-stateful-deferred-v1" and .status == "deferred-not-passed" and
    ([.cases[].id] | sort) == ["margin-floor"] and
    all(.cases[]; (.why | length) > 0 and (.requiredFixture | length) > 0)
' "$suite/stateful-deferred.json" >/dev/null || fail "margin-floor is not explicitly deferred"

mapfile -t case_ids < <(jq -r '.cases[].caseId' "$suite/suite.json")
mapfile -t strategies < <(jq -r '.cases[].strategy' "$suite/suite.json")
mapfile -t magics < <(jq -r '.cases[].magic' "$suite/suite.json")
[ "${#case_ids[@]}" -eq 4 ] || fail "expected exactly four cases"

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    case_dir="$suite/cases/$case_id"
    [ -d "$case_dir" ] || fail "case directory missing: $case_id"
    [ -f "$case_dir/qkt.config.yaml" ] || fail "case config missing: $case_id"
    [ -f "$case_dir/expected.json" ] || fail "case contract missing: $case_id"
    strategy_file="$case_dir/strategies/${strategies[$index]}.qkt"
    [ -f "$strategy_file" ] || fail "strategy missing: $case_id"
    "$cli" parse "$strategy_file" >/dev/null || fail "strategy does not parse: $case_id"
    rg --fixed-strings "gateway_url: $gateway_url" "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "$case_id config differs from the reviewed localhost gateway"
    rg --fixed-strings 'api_key: ${QKT_BROKER_API_KEY}' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "$case_id config does not resolve the key at runtime"
    rg --fixed-strings "magic: ${magics[$index]}" "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "$case_id config magic differs from its contract"
    [ "$(rg --count --fixed-strings 'THEN BUY eur SIZING 0.01' "$strategy_file")" -eq 1 ] ||
        fail "$case_id must contain exactly one fixed 0.01 intent"
    rg --fixed-strings 'EVERY 1m WARMUP 2 BARS' "$strategy_file" >/dev/null ||
        fail "$case_id must retain 1m bar warmup"
    rg --fixed-strings 'mod(NOW.minute_utc, 2) = 0' "$strategy_file" >/dev/null ||
        fail "$case_id lacks the synchronized even-minute trigger"
    rg --fixed-strings 'margin_floor_pct: "0"' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "$case_id enables the deferred margin-floor case"
    rg --fixed-strings 'measured_usage_hours: "0"' "$case_dir/qkt.config.yaml" >/dev/null ||
        fail "$case_id changes the stateful fixture into a measured-usage case"
    case "$case_id" in
        global-daily-loss)
            rg --fixed-strings 'max_daily_loss: "5"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "global-daily-loss does not target the reviewed daily-loss cap"
            ;;
        strategy-daily-loss)
            rg --fixed-strings 'max_daily_loss: "0"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "strategy-daily-loss must disable the global daily-loss rule"
            rg --fixed-strings "      max_daily_loss: \"5\"" "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "strategy-daily-loss does not target the reviewed per-strategy daily-loss cap"
            ;;
        global-drawdown)
            rg --fixed-strings 'max_daily_loss: "0"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "global-drawdown must disable the daily-loss rule"
            rg --fixed-strings 'max_drawdown_pct: "0.005"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "global-drawdown does not target the reviewed drawdown cap"
            ;;
        loss-streak)
            rg --fixed-strings 'max_daily_loss: "0"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "loss-streak must disable the daily-loss rule"
            rg --fixed-strings 'loss_streak_halt: "1"' "$case_dir/qkt.config.yaml" >/dev/null ||
                fail "loss-streak does not target the reviewed loss-streak cap"
            ;;
    esac
done

if $verify_only; then
    [ -z "$output" ] || fail "--output is not accepted with --verify-only"
    [ -z "$image" ] || fail "--image is not accepted with --verify-only"
    printf 'verified %s\n' "$suite/suite.json"
    exit 0
fi

[ -n "$output" ] || fail "--output is required for a live run"
[ -n "$image" ] || fail "--image is required for a live run"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 120 ] && [ "$timeout_seconds" -le 210 ] ||
    fail "--timeout-seconds must be in 120..210"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for variable in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS GRADLE_OPTS KOTLIN_DAEMON_JVM_OPTIONS; do
    [ -z "${!variable:-}" ] || fail "$variable must be unset; this run does not restrict or override the JVM"
done
for command in curl docker stat; do require_command "$command"; done
[ ! -e "$output" ] || fail "output already exists: $output"
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"

qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
[ "$(jq -er '.qktCommit' "$suite/suite.json")" = "$qkt_commit" ] ||
    fail "suite was not prepared from current HEAD"
jq -e '.qktDirty == false' "$suite/suite.json" >/dev/null || fail "suite was prepared from a dirty checkout"
qkt_short="${qkt_commit:0:8}"
[[ "$($cli --version)" == *"($qkt_short"* || "$($cli --version)" == *"($qkt_commit"* ]] || fail "host CLI is not built from $qkt_short"
[[ "$(docker run --rm --entrypoint qkt "$image" --version)" == *"($qkt_short"* ]] ||
    fail "Docker image is not built from $qkt_short"
if docker image inspect "$image" | jq -e '
    any(.[0].Config.Env[]?; test("^(JAVA_OPTS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|GRADLE_OPTS|KOTLIN_DAEMON_JVM_OPTIONS)="))
' >/dev/null; then
    fail "image config restricts or overrides the JVM"
fi

output="$(realpath -m "$output")"
mkdir -m 700 -p "$output/cases" "$output/evidence" "$output/source"
cp "$suite/suite.json" "$suite/stateful-deferred.json" "$suite/SHA256SUMS" "$output/source/"
for case_id in "${case_ids[@]}"; do
    mkdir -m 700 -p "$output/cases/$case_id"
    cp -a "$suite/cases/$case_id/." "$output/cases/$case_id/"
done

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail-with-body --config - "$gateway_url$path"
}

materialize_seeded_state() {
    local case_id="$1"
    local strategy="$2"
    local case_dir="$3"
    local epoch_day now_ms strategy_dir
    epoch_day="$(( $(date -u +%s) / 86400 ))"
    now_ms="$(date +%s%3N)"
    strategy_dir="$case_dir/state/state/$strategy"
    mkdir -m 700 -p "$strategy_dir"
    case "$case_id" in
        global-daily-loss|strategy-daily-loss)
            jq -n \
                --arg strategy "$strategy" \
                --argjson epochDay "$epoch_day" '
                {
                  version:1,
                  strategyId:$strategy,
                  epochDay:$epochDay,
                  realizedToday:"-10",
                  perStrategyRealizedToday:{($strategy):"-10"},
                  halted:false,
                  haltReason:null,
                  haltScope:"PERSISTENT",
                  haltEpochDay:0,
                  strategyHalts:[],
                  globalRealizedTotal:"-10"
                }
            ' > "$strategy_dir/risk-state.json"
            ;;
        global-drawdown)
            jq -n \
                --arg strategy "$strategy" \
                --argjson epochDay "$epoch_day" '
                {
                  version:1,
                  strategyId:$strategy,
                  epochDay:$epochDay,
                  realizedToday:"0",
                  perStrategyRealizedToday:{},
                  halted:false,
                  haltReason:null,
                  haltScope:"PERSISTENT",
                  haltEpochDay:0,
                  strategyHalts:[],
                  globalRealizedTotal:"-10"
                }
            ' > "$strategy_dir/risk-state.json"
            ;;
        loss-streak)
            jq -n \
                --arg strategy "$strategy" \
                --argjson epochDay "$epoch_day" \
                --argjson lossAt "$((now_ms - 60000))" '
                {
                  version:1,
                  strategyId:$strategy,
                  epochDay:$epochDay,
                  realizedToday:"0",
                  perStrategyRealizedToday:{},
                  halted:false,
                  haltReason:null,
                  haltScope:"PERSISTENT",
                  haltEpochDay:0,
                  strategyHalts:[],
                  globalRealizedTotal:"0",
                  pacerLossStreakByStrategy:{($strategy):1},
                  pacerLastLossAtByStrategy:{($strategy):$lossAt}
                }
            ' > "$strategy_dir/risk-state.json"
            ;;
        *)
            fail "unknown seed case: $case_id"
            ;;
    esac
    cp "$strategy_dir/risk-state.json" "$case_dir/evidence/seeded-risk-state.json"
}

for index in 0 1 2 3; do
    materialize_seeded_state "${case_ids[$index]}" "${strategies[$index]}" "$output/cases/${case_ids[$index]}"
done

gateway_get /health > "$output/evidence/gateway-health-initial.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$output/evidence/gateway-health-initial.json" >/dev/null || fail "gateway is not healthy, connected, and unhalted"
gateway_get /account > "$output/evidence/account-initial.json"
jq -e --slurpfile suite "$suite/suite.json" '
    .login == $suite[0].account.login and .server == $suite[0].account.server and
    .trade_mode == 0 and .currency == $suite[0].account.currency and
    .balance == ($suite[0].account.balance | tonumber) and .equity == .balance and .margin == 0 and
    .leverage == $suite[0].account.leverage and .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-initial.json" >/dev/null || fail "account does not match the flat demo allowlist"
gateway_get /get_positions > "$output/evidence/positions-initial.json"
gateway_get /orders > "$output/evidence/orders-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-initial.json" >/dev/null ||
    fail "demo account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-initial.json" >/dev/null ||
    fail "demo account has a pending order"
for index in 0 1 2 3; do
    gateway_get "/get_positions?magic=${magics[$index]}" > "$output/cases/${case_ids[$index]}/evidence/positions-initial.json"
    gateway_get "/orders?magic=${magics[$index]}" > "$output/cases/${case_ids[$index]}/evidence/orders-initial.json"
    jq -e '.ok == true and (.data | length) == 0' "$output/cases/${case_ids[$index]}/evidence/positions-initial.json" >/dev/null ||
        fail "${case_ids[$index]} magic already owns a position"
    jq -e '.ok == true and (.orders | length) == 0' "$output/cases/${case_ids[$index]}/evidence/orders-initial.json" >/dev/null ||
        fail "${case_ids[$index]} magic already owns an order"
done

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
containers=()
daemon_pids=("" "" "" "")
for case_id in "${case_ids[@]}"; do containers+=("qkt-stateful-risk-$case_id-$run_id"); done

cleanup() {
    set +e
    if [ -n "${QKT_BROKER_API_KEY:-}" ]; then
        for index in 0 1 2 3; do
            magic="${magics[$index]}"
            close_payloads="$(
                gateway_get "/get_positions?magic=$magic" 2>/dev/null |
                    jq -cr '.data[]? | {position:{ticket:.ticket,volume:.volume}}' 2>/dev/null
            )"
            while IFS= read -r payload; do
                [ -n "$payload" ] || continue
                curl --silent --show-error \
                    -H "Authorization: Bearer $QKT_BROKER_API_KEY" \
                    -H 'Content-Type: application/json' \
                    -X POST \
                    --data "$payload" \
                    "$gateway_url/close_position" >/dev/null 2>&1
            done <<< "$close_payloads"
        done
    fi
    for index in 0 1 2 3; do
        "$cli" daemon stop --state-dir "$output/cases/${case_ids[$index]}/state" >/dev/null 2>&1
    done
    for pid in "${daemon_pids[@]}"; do [ -z "$pid" ] || wait "$pid" >/dev/null 2>&1; done
    for container in "${containers[@]}"; do docker rm -f "$container" >/dev/null 2>&1; done
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
    docker inspect "${containers[$index]}" | jq '
        .[0] | {
          schema:"qkt-live-stateful-risk-container-v1",id:.Id,image:.Image,
          networkMode:.HostConfig.NetworkMode,user:.Config.User,
          resourceRestrictions:{memoryBytes:.HostConfig.Memory,nanoCpus:.HostConfig.NanoCpus,
            cpuQuota:.HostConfig.CpuQuota,pidsLimit:.HostConfig.PidsLimit,cpusetCpus:.HostConfig.CpusetCpus},
          credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false
        }
    ' > "$case_dir/evidence/container.json"
    jq -e '
        .networkMode == "host" and
        .resourceRestrictions.memoryBytes == 0 and .resourceRestrictions.nanoCpus == 0 and
        .resourceRestrictions.cpuQuota == 0 and
        (.resourceRestrictions.pidsLimit == null or .resourceRestrictions.pidsLimit == 0) and
        .resourceRestrictions.cpusetCpus == "" and
        .credentialStoredInConfig == false and .jvmOverrideEnvironmentPresent == false
    ' "$case_dir/evidence/container.json" >/dev/null || fail "$case_id container has a resource restriction"
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --text --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY"); then
        fail "broker credential was stored in $case_id container configuration"
    fi
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
                exec qkt daemon start --config /work/qkt.config.yaml --state-dir /work/state
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
        if "$cli" daemon status --state-dir "$state" --json > "$output/cases/$case_id/evidence/daemon-empty.json" 2>/dev/null &&
            jq -e '.status == "ok" and .strategies == 0' "$output/cases/$case_id/evidence/daemon-empty.json" >/dev/null; then
            ready=true
            break
        fi
        sleep 1
    done
    $ready || fail "$case_id daemon did not become ready and empty"
done

sync_deadline=$((SECONDS + 130))
while true; do
    utc_minute="$((10#$(date -u +%M)))"
    utc_second="$((10#$(date -u +%S)))"
    if [ $((utc_minute % 2)) -eq 1 ] && [ "$utc_second" -ge 5 ] && [ "$utc_second" -le 20 ]; then
        break
    fi
    [ "$SECONDS" -lt "$sync_deadline" ] || fail "could not reach the odd-minute deployment window"
    sleep 1
done

deploy_pids=("" "" "" "")
deploy_launch_ms=()
for index in 0 1 2 3; do
    case_dir="$output/cases/${case_ids[$index]}"
    deploy_launch_ms+=("$(date +%s%3N)")
    (
        docker exec "${containers[$index]}" qkt deploy "/work/strategies/${strategies[$index]}.qkt" \
            --as "${strategies[$index]}" --state-dir /work/state --json \
            > "$case_dir/evidence/deploy.json"
    ) &
    deploy_pids[$index]=$!
done
for index in 0 1 2 3; do
    wait "${deploy_pids[$index]}" || fail "${case_ids[$index]} deployment failed"
    jq -e --arg strategy "${strategies[$index]}" '.name == $strategy and .state == "running"' \
        "$output/cases/${case_ids[$index]}/evidence/deploy.json" >/dev/null ||
        fail "${case_ids[$index]} did not deploy in running state"
done
deploy_min="$(printf '%s\n' "${deploy_launch_ms[@]}" | sort -n | head -n1)"
deploy_max="$(printf '%s\n' "${deploy_launch_ms[@]}" | sort -n | tail -n1)"
[ "$((deploy_max - deploy_min))" -le 1500 ] || fail "parallel deployment launch skew exceeded 1500 ms"
[ $((10#$(date -u +%M) % 2)) -eq 1 ] ||
    fail "deployments crossed the guarded odd-minute window before the trigger"

deadline=$((SECONDS + timeout_seconds))
all_settled=false
while [ "$SECONDS" -lt "$deadline" ]; do
    all_settled=true
    for index in 0 1 2 3; do
        case_id="${case_ids[$index]}"
        case_dir="$output/cases/$case_id"
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "$case_id daemon exited before stateful rejection"
        audit_root="$case_dir/state/state/audit-journal"
        if ! [ -d "$audit_root" ] || ! rg --quiet '"eventType":"com.qkt.events.RiskRejectedEvent"' "$audit_root"; then
            all_settled=false
        fi
        if ! [ -d "$audit_root" ] || ! rg --quiet '"eventType":"com.qkt.events.RiskEvent.Halted"' "$audit_root"; then
            all_settled=false
        fi
        if ! [ -d "$audit_root" ] || ! rg --quiet '"eventType":"com.qkt.events.StreamCandleEvent"' "$audit_root"; then
            all_settled=false
        fi
        if ! [ -d "$audit_root" ] || ! rg --quiet '"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent"' "$audit_root"; then
            all_settled=false
        fi
        transport_root="$case_dir/state/state/mt5-transport-journal"
        if [ -d "$transport_root" ] && rg --quiet '"method":"(POST|PUT|PATCH|DELETE)"' "$transport_root"; then
            fail "$case_id issued a mutating gateway request before stateful rejection"
        fi
        if [ -d "$audit_root" ] && rg --quiet '"eventType":"com.qkt.events.OrderEvent"' "$audit_root"; then
            fail "$case_id passed a halted intent into broker submission"
        fi
    done
    $all_settled && break
    sleep 0.2
done
$all_settled || fail "all four stateful risk rejections were not observed within $timeout_seconds seconds"

stop_pids=("" "" "" "")
for index in 0 1 2 3; do
    case_dir="$output/cases/${case_ids[$index]}"
    "$cli" daemon status --state-dir "$case_dir/state" --json > "$case_dir/evidence/status-after-rejection.json"
    (
        "$cli" stop "${strategies[$index]}" --state-dir "$case_dir/state" --json \
            > "$case_dir/evidence/stop-strategy.json"
    ) &
    stop_pids[$index]=$!
done
for index in 0 1 2 3; do wait "${stop_pids[$index]}" || fail "${case_ids[$index]} strategy stop failed"; done
for index in 0 1 2 3; do
    case_dir="$output/cases/${case_ids[$index]}"
    "$cli" daemon stop --state-dir "$case_dir/state" > "$case_dir/evidence/daemon-stop.log"
    wait "${daemon_pids[$index]}" || fail "${case_ids[$index]} daemon failed during final stop"
    daemon_pids[$index]=""
done

for index in 0 1 2 3; do
    case_dir="$output/cases/${case_ids[$index]}"
    if [ -f "$case_dir/state/control.token" ]; then unlink "$case_dir/state/control.token"; fi
    if [ -f "$case_dir/state/daemon.pid" ]; then unlink "$case_dir/state/daemon.pid"; fi
done

gateway_get /health > "$output/evidence/gateway-health-final.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$output/evidence/gateway-health-final.json" >/dev/null || fail "gateway did not remain healthy and unhalted"
gateway_get /account > "$output/evidence/account-final.json"
gateway_get /get_positions > "$output/evidence/positions-final.json"
gateway_get /orders > "$output/evidence/orders-final.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-final.json" >/dev/null ||
    fail "stateful matrix ended with an account position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-final.json" >/dev/null ||
    fail "stateful matrix ended with a pending order"
jq -e --slurpfile initial "$output/evidence/account-initial.json" '
    .login == $initial[0].login and .server == $initial[0].server and
    .trade_mode == $initial[0].trade_mode and .currency == $initial[0].currency and
    .balance == $initial[0].balance and .equity == $initial[0].equity and .margin == $initial[0].margin and
    .leverage == $initial[0].leverage and .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-final.json" >/dev/null || fail "account identity or financial state changed"
for index in 0 1 2 3; do
    case_dir="$output/cases/${case_ids[$index]}"
    gateway_get "/get_positions?magic=${magics[$index]}" > "$case_dir/evidence/positions-final.json"
    gateway_get "/orders?magic=${magics[$index]}" > "$case_dir/evidence/orders-final.json"
    jq -e '.ok == true and (.data | length) == 0' "$case_dir/evidence/positions-final.json" >/dev/null ||
        fail "${case_ids[$index]} ended with an owned position"
    jq -e '.ok == true and (.orders | length) == 0' "$case_dir/evidence/orders-final.json" >/dev/null ||
        fail "${case_ids[$index]} ended with an owned order"
done

for index in 0 1 2 3; do
    case_id="${case_ids[$index]}"
    case_dir="$output/cases/$case_id"
    strategy="${strategies[$index]}"
    contract="$case_dir/expected.json"

    mapfile -t audits < <(find "$case_dir/state/state/audit-journal" -type f -name '*.jsonl' | sort)
    mapfile -t transports < <(find "$case_dir/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    [ "${#audits[@]}" -gt 0 ] || fail "$case_id produced no engine audit journal"
    [ "${#transports[@]}" -gt 0 ] || fail "$case_id produced no MT5 transport journal"
    for journal in "${audits[@]}" "${transports[@]}"; do
        jq -c . "$journal" >/dev/null || fail "$case_id retained invalid JSONL: $journal"
    done
    [ -z "$(find "$case_dir/state/state/audit-journal" "$case_dir/state/state/mt5-transport-journal" \
        -type f -name '*.dropped' -print -quit)" ] || fail "$case_id journal reported dropped records"

    jq -s -e --arg strategy "$strategy" --slurpfile contract "$contract" '
        . as $events |
        ($contract[0]) as $c |
        [$events[] | select(.eventType == "com.qkt.events.StreamCandleEvent" and
          .broker == "EXNESS" and .timeframe == $c.timeframe and (.payload | contains("symbol=" + $c.symbol)))] as $streams |
        [$events[] | select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
          .strategyId == $strategy and .timeframe == $c.timeframe and (.payload | contains("symbol=" + $c.symbol)))] as $evaluations |
        [$events[] | select(.eventType == "com.qkt.events.RiskEvent.Halted" and
          (if $c.expectedHalt.strategyId == null then .strategyId == null else .strategyId == $c.expectedHalt.strategyId end))] as $halts |
        [$events[] | select(.eventType == "com.qkt.events.RuleDecisionEvent" and
          .strategyId == $strategy and .conditionResult == true and .signalCount == 1)] as $decisions |
        [$events[] | select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy)] as $links |
        [$events[] | select(.eventType == "com.qkt.events.RiskRejectedEvent" and .strategyId == $strategy)] as $rejects |
        [$events[] | select(.eventType == "com.qkt.events.OrderEvent" and .strategyId == $strategy)] as $orders |
        [$events[] | select(
          (.eventType | test("BrokerEvent[.]Order(Accepted|PartiallyFilled|Filled|Rejected)$")) or
          .eventType == "com.qkt.events.FillAccountedEvent"
        )] as $brokerEvents |
        ($streams | length) >= $c.required.streamCandlesMin and
        ($evaluations | length) >= $c.required.evaluatedCandlesMin and
        ($halts | length) == $c.required.haltedEvents and
        ($decisions | length) == $c.required.ruleDecisions and
        ($links | length) == $c.required.decisionOrderLinks and
        ($rejects | length) == $c.required.riskRejections and
        ($orders | length) == $c.required.orderEvents and ($brokerEvents | length) == 0 and
        (if $c.expectedHalt.reason.kind == "exact"
         then (($halts[0].payload | capture("reason=(?<reason>.*), strategyId=").reason) == $c.expectedHalt.reason.value)
         else ($halts[0].payload | capture("reason=(?<reason>.*), strategyId=").reason | test($c.expectedHalt.reason.value))
         end) and
        $halts[0].seq < $decisions[0].seq and
        $decisions[0].decisionId == $links[0].decisionId and
        $decisions[0].ruleId == $links[0].ruleId and
        $links[0].orderId == $rejects[0].orderId and
        $decisions[0].seq < $links[0].seq and
        $links[0].seq < $rejects[0].seq and
        $rejects[0].orderSchemaVersion == 1 and
        $rejects[0].order.orderId == $rejects[0].orderId and
        $rejects[0].order.strategyId == $strategy and
        $rejects[0].order.symbol == $c.symbol and
        $rejects[0].order.qty == 0.01 and
        (if $c.expectedRejection.reason.kind == "exact"
         then $rejects[0].reason == $c.expectedRejection.reason.value
         else $rejects[0].reason | test($c.expectedRejection.reason.value)
         end)
    ' "${audits[@]}" >/dev/null || fail "$case_id halt, bar, or causal rejection contract failed"

    jq -s --arg caseId "$case_id" --arg strategy "$strategy" --slurpfile contract "$contract" '
        . as $events |
        ($contract[0]) as $c |
        ($events[] | select(.eventType == "com.qkt.events.RiskEvent.Halted" and
          (if $c.expectedHalt.strategyId == null then .strategyId == null else .strategyId == $c.expectedHalt.strategyId end))) as $halt |
        ($events[] | select(.eventType == "com.qkt.events.RuleDecisionEvent" and
          .strategyId == $strategy and .conditionResult == true)) as $decision |
        ($events[] | select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy)) as $link |
        ($events[] | select(.eventType == "com.qkt.events.RiskRejectedEvent" and .strategyId == $strategy)) as $rejection |
        {
          schema:"qkt-live-stateful-risk-chain-v1",
          caseId:$caseId,
          strategy:$strategy,
          halt:{
            rule:$c.expectedHalt.rule,
            strategyId:$halt.strategyId,
            reason:($halt.payload | capture("reason=(?<reason>.*), strategyId=").reason),
            sequenceId:$halt.seq
          },
          decision:{decisionId:$decision.decisionId,ruleId:$decision.ruleId,sequenceId:$decision.seq},
          rejection:{orderId:$link.orderId,reason:$rejection.reason,sequenceId:$rejection.seq,order:$rejection.order},
          verified:{
            restoredStateTripObserved:true,
            barsObserved:true,
            haltBeforeDecision:true,
            decisionToOrder:true,
            rejectedBeforeOrderEvent:true
          }
        }
    ' "${audits[@]}" > "$case_dir/evidence/risk-chain.json"

    mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' \
        "${transports[@]}" | count_records)"
    [ "$mutations" -eq 0 ] || fail "$case_id issued a mutating gateway exchange"
    gateway_reads="$(jq -r 'select(.method == "GET") | 1' "${transports[@]}" | count_records)"
    [ "$gateway_reads" -gt 0 ] || fail "$case_id retained no live gateway reads"
    jq -n \
        --arg caseId "$case_id" \
        --arg strategy "$strategy" \
        --arg haltRule "$(jq -er '.expectedHalt.rule' "$contract")" \
        --arg haltReason "$(jq -er '.halt.reason' "$case_dir/evidence/risk-chain.json")" \
        --arg rejectReason "$(jq -er '.rejection.reason' "$case_dir/evidence/risk-chain.json")" \
        --argjson gatewayReads "$gateway_reads" '
        {
          schema:"qkt-live-stateful-risk-case-result-v1",status:"passed",caseId:$caseId,strategy:$strategy,
          halt:{rule:$haltRule,reason:$haltReason,restoredStateTripObserved:true},
          rejection:{reason:$rejectReason,fixedIntentQty:"0.01",causalChainVerified:true},
          counts:{streamCandlesMinObserved:true,evaluatedCandlesMinObserved:true,haltedEvents:1,
            ruleDecisions:1,decisionOrderLinks:1,riskRejections:1,orderEvents:0,fills:0,
            gatewayReads:$gatewayReads,gatewayMutations:0},
          financiallyReadOnly:true,venueStateUnchanged:true,barsObserved:true
        }
    ' > "$case_dir/evidence/result.json"
done

jq -n \
    --slurpfile suite "$output/source/suite.json" \
    --slurpfile deferred "$output/source/stateful-deferred.json" \
    --argjson cases "$(jq -s . "$output"/cases/*/evidence/result.json)" '
    {
      schema:"qkt-live-stateful-risk-matrix-result-v1",status:"passed",qktCommit:$suite[0].qktCommit,
      cases:$cases,
      aggregate:{containers:4,parallel:true,fixedIntentQty:"0.01",barsObserved:true,haltedEvents:4,
        riskRejections:4,orderEvents:0,fills:0,gatewayMutations:0,initialVenueFlat:true,
        finalVenueFlat:true,accountFinancialStateUnchanged:true},
      deferredStateful:$deferred[0],
      claims:{
        dailyLossPassed:true,
        drawdownPassed:true,
        lossStreakPassed:true,
        marginFloorPassed:false,
        productionReadiness:false
      },
      credentialStored:false,dockerResourceRestrictionsVerifiedAbsent:true,
      publicationSafe:false,containsPrivateAccountMetadata:true
    }
' > "$output/evidence/result.json"

if rg --text --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY") "$output"; then
    fail "broker credential was persisted in retained artifacts"
fi
if find "$output" -type f -name 'control.token' -print -quit | rg . >/dev/null; then
    fail "daemon control token was retained"
fi
(
    cd "$output"
    find . -type f ! -name RUN-SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > RUN-SHA256SUMS
)
chmod 600 "$output/RUN-SHA256SUMS"
printf 'passed %s\n' "$output/evidence/result.json"
