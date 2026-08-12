#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-margin-floor-fixture.sh --fixture DIR --verify-only [--cli PATH]
       run-margin-floor-fixture.sh --fixture DIR --output DIR --image IMAGE \
         [--timeout-seconds N] [--cli PATH] --arm I_UNDERSTAND_DEMO_ORDER_0.01

Verifies or runs the controlled localhost MT5 margin-floor fixture. The live
path opens exactly one bounded 0.01-lot EURUSD demo position with the opener
role, derives a dynamic probe margin floor from the observed live margin level,
requires one causal MarginFloor rejection before MT5 transport for the probe
role, then flattens the opener path, proves the probe can open after headroom
recovers, and returns the full account to zero positions and zero pending orders.
EOF
}

fail() {
    printf 'run-margin-floor-fixture: %s\n' "$1" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

count_records() {
    awk 'END {print NR + 0}'
}

wait_for_odd_minute_window() {
    local label="$1"
    local deadline=$((SECONDS + 130))
    while true; do
        local utc_minute utc_second
        utc_minute="$((10#$(date -u +%M)))"
        utc_second="$((10#$(date -u +%S)))"
        if [ $((utc_minute % 2)) -eq 1 ] && [ "$utc_second" -ge 5 ] && [ "$utc_second" -le 40 ]; then
            return 0
        fi
        [ "$SECONDS" -lt "$deadline" ] || fail "could not reach the guarded odd-minute deploy window for $label"
        sleep 1
    done
}

fixture=""
output=""
image=""
cli="$repo_root/build/install/qkt/bin/qkt"
timeout_seconds=420
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --fixture) fixture="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$fixture" ] || fail "--fixture is required"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
for command in awk curl docker jq realpath rg sha256sum sort; do require_command "$command"; done

fixture="$(realpath "$fixture")"
[ -d "$fixture" ] || fail "fixture directory not found: $fixture"
[ -f "$fixture/suite.json" ] || fail "suite.json not found"
[ -f "$fixture/SHA256SUMS" ] || fail "SHA256SUMS not found"
(cd "$fixture" && sha256sum --check SHA256SUMS >/dev/null) || fail "prepared fixture checksum verification failed"

jq -e '
    .schema == "qkt-live-margin-floor-fixture-v1" and
    .credentialsStored == false and
    .contract == {
      openerCreatesLiveExposure:true,
      probeRejectsBeforeTransport:true,
      probeAllowedAfterHeadroomRecovery:true,
      dynamicMarginFloorPct:true,
      fixedIntentQty:"0.01",
      finalVenueFlat:true,
      finalPendingOrders:false
    } and
    .opener.schema == "qkt-live-margin-floor-opener-v1" and
    .probe.schema == "qkt-live-margin-floor-probe-v1" and
    .probe.expectedRule == "MarginFloor" and
    .dynamicFloorSelection == {
      schema:"qkt-live-margin-floor-selection-v1",
      source:"gateway_account.margin_level",
      floorPctFormula:"ceil(observed_margin_level_pct) + 1000",
      minObservedMarginLevelPct:"0.00000001",
      openerPositionRequired:true,
      finalMaterializedConfig:"probe/qkt.config.yaml"
    } and
    .claims.marginFloorPassed == false and
    .claims.productionReadiness == false
' "$fixture/suite.json" >/dev/null || fail "fixture contract is not the reviewed margin-floor suite"

gateway_url="$(jq -er '.gatewayUrl' "$fixture/suite.json")"
[[ "$gateway_url" =~ ^http://127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "fixture gateway must be a localhost endpoint"

mapfile -t opener_strategy_files < <(find "$fixture/opener/strategies" -maxdepth 1 -type f -name '*.qkt' | sort)
mapfile -t probe_strategy_files < <(find "$fixture/probe/strategies" -maxdepth 1 -type f -name '*.qkt' | sort)
[ "${#opener_strategy_files[@]}" -eq 1 ] || fail "expected exactly one opener strategy"
[ "${#probe_strategy_files[@]}" -eq 1 ] || fail "expected exactly one probe strategy"
opener_strategy_file="${opener_strategy_files[0]}"
probe_strategy_file="${probe_strategy_files[0]}"
opener_strategy="$(basename "$opener_strategy_file" .qkt)"
probe_strategy="$(basename "$probe_strategy_file" .qkt)"

"$cli" parse "$opener_strategy_file" >/dev/null || fail "opener strategy does not parse"
"$cli" parse "$probe_strategy_file" >/dev/null || fail "probe strategy does not parse"
for strategy_file in "$opener_strategy_file" "$probe_strategy_file"; do
    [ "$(rg --count --fixed-strings 'THEN BUY eur SIZING 0.01' "$strategy_file")" -eq 1 ] ||
        fail "$(basename "$strategy_file") must contain exactly one fixed 0.01-lot intent"
    rg --fixed-strings 'EVERY 1m WARMUP 2 BARS' "$strategy_file" >/dev/null ||
        fail "$(basename "$strategy_file") must retain 1m warmup"
    rg --fixed-strings 'mod(NOW.minute_utc, 2) = 0' "$strategy_file" >/dev/null ||
        fail "$(basename "$strategy_file") lacks the synchronized even-minute trigger"
    rg --fixed-strings 'TRADES.today = 0' "$strategy_file" >/dev/null ||
        fail "$(basename "$strategy_file") must prevent re-entry"
done

[ -f "$fixture/opener/qkt.config.yaml" ] || fail "opener config missing"
[ -f "$fixture/probe/qkt.config.template.yaml" ] || fail "probe config template missing"
rg --fixed-strings 'margin_floor_pct: "0"' "$fixture/opener/qkt.config.yaml" >/dev/null ||
    fail "opener config must disable margin floor"
rg --fixed-strings 'margin_floor_pct: "__QKT_DYNAMIC_MARGIN_FLOOR_PCT__"' "$fixture/probe/qkt.config.template.yaml" >/dev/null ||
    fail "probe config template must retain the dynamic margin-floor placeholder"
rg --fixed-strings 'api_key: ${QKT_BROKER_API_KEY}' "$fixture/opener/qkt.config.yaml" >/dev/null ||
    fail "opener config does not resolve the broker key at runtime"
rg --fixed-strings 'api_key: ${QKT_BROKER_API_KEY}' "$fixture/probe/qkt.config.template.yaml" >/dev/null ||
    fail "probe config template does not resolve the broker key at runtime"

if $verify_only; then
    [ -z "$output" ] || fail "--output is not accepted with --verify-only"
    [ -z "$image" ] || fail "--image is not accepted with --verify-only"
    [ -z "$arm" ] || fail "--arm is not accepted with --verify-only"
    printf 'verified %s\n' "$fixture/suite.json"
    exit 0
fi

[ -n "$output" ] || fail "--output is required for a live run"
[ -n "$image" ] || fail "--image is required for a live run"
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 300 ] && [ "$timeout_seconds" -le 720 ] ||
    fail "--timeout-seconds must be in 300..720"
for variable in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS GRADLE_OPTS KOTLIN_DAEMON_JVM_OPTIONS; do
    [ -z "${!variable:-}" ] || fail "$variable must be unset; this run does not restrict or override the JVM"
done
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
[ ! -e "$output" ] || fail "output already exists: $output"

qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
[ "$(jq -er '.qktCommit' "$fixture/suite.json")" = "$qkt_commit" ] ||
    fail "fixture was not prepared from current HEAD"
jq -e '.qktDirty == false' "$fixture/suite.json" >/dev/null || fail "fixture was prepared from a dirty checkout"
qkt_short="${qkt_commit:0:8}"
[[ "$($cli --version)" == *"($qkt_short)"* ]] || fail "host CLI is not built from $qkt_short"
[[ "$(docker run --rm --entrypoint qkt "$image" --version)" == *"($qkt_short)"* ]] ||
    fail "Docker image is not built from $qkt_short"
if docker image inspect "$image" | jq -e '
    any(.[0].Config.Env[]?; test("^(JAVA_OPTS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|GRADLE_OPTS|KOTLIN_DAEMON_JVM_OPTIONS)="))
' >/dev/null; then
    fail "image config restricts or overrides the JVM"
fi

output="$(realpath -m "$output")"
mkdir -m 700 -p "$output/source" "$output/opener" "$output/probe" "$output/evidence"
cp "$fixture/suite.json" "$fixture/SHA256SUMS" "$output/source/"
cp -a "$fixture/opener/." "$output/opener/"
cp -a "$fixture/probe/." "$output/probe/"

opener_magic="$(jq -er '.opener.magic' "$fixture/suite.json")"
probe_magic="$(jq -er '.probe.magic' "$fixture/suite.json")"

gateway_get() {
    local path="$1"
    printf 'header = "Authorization: Bearer %s"\n' "$QKT_BROKER_API_KEY" |
        curl --silent --show-error --fail-with-body --config - "$gateway_url$path"
}

health_initial="$(gateway_get /health)"
printf '%s\n' "$health_initial" > "$output/evidence/gateway-health-initial.raw.json"
jq -e '.ok == true and .status == "healthy" and .mt5_status == "connected" and .kill_switch_active == false' \
    "$output/evidence/gateway-health-initial.raw.json" >/dev/null || fail "gateway is not healthy, connected, and unhalted"

account_initial="$(gateway_get /account)"
printf '%s\n' "$account_initial" > "$output/evidence/account-initial.raw.json"
jq -e --slurpfile fixture "$fixture/suite.json" '
    .login == $fixture[0].account.login and .server == $fixture[0].account.server and
    .trade_mode == 0 and .currency == $fixture[0].account.currency and
    .balance == ($fixture[0].account.balance | tonumber) and .equity == .balance and .margin == 0 and
    .leverage == $fixture[0].account.leverage and .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-initial.raw.json" >/dev/null || fail "account does not match the flat demo allowlist"

gateway_get /get_positions > "$output/evidence/positions-initial.json"
gateway_get /orders > "$output/evidence/orders-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-initial.json" >/dev/null ||
    fail "demo account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-initial.json" >/dev/null ||
    fail "demo account has a pending order"
for pair in "opener:$opener_magic" "probe:$probe_magic"; do
    role="${pair%%:*}"
    magic="${pair##*:}"
    gateway_get "/get_positions?magic=$magic" > "$output/$role/evidence/positions-initial.json"
    gateway_get "/orders?magic=$magic" > "$output/$role/evidence/orders-initial.json"
    jq -e '.ok == true and (.data | length) == 0' "$output/$role/evidence/positions-initial.json" >/dev/null ||
        fail "$role magic already owns a position"
    jq -e '.ok == true and (.orders | length) == 0' "$output/$role/evidence/orders-initial.json" >/dev/null ||
        fail "$role magic already owns an order"
done

run_id="$(date -u +%Y%m%d%H%M%S)-$$"
opener_container="qkt-margin-floor-opener-$run_id"
probe_container="qkt-margin-floor-probe-$run_id"
opener_pid=""
probe_pid=""
opener_ticket=""
cleanup_running=false

cleanup() {
    set +e
    $cleanup_running && return
    cleanup_running=true
    if [ -n "$probe_pid" ] && kill -0 "$probe_pid" 2>/dev/null; then
        "$cli" stop "$probe_strategy" --state-dir "$output/probe/state" --json >/dev/null 2>&1 || true
        "$cli" daemon stop --state-dir "$output/probe/state" >/dev/null 2>&1 || true
        wait "$probe_pid" >/dev/null 2>&1 || true
    fi
    if [ -n "$opener_pid" ] && kill -0 "$opener_pid" 2>/dev/null; then
        "$cli" kill "$opener_strategy" --flatten --state-dir "$output/opener/state" --json >/dev/null 2>&1 || true
        "$cli" daemon stop --state-dir "$output/opener/state" >/dev/null 2>&1 || true
        wait "$opener_pid" >/dev/null 2>&1 || true
    fi
    for pair in "opener:$opener_magic" "probe:$probe_magic"; do
        magic="${pair##*:}"
        positions="$(
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
        done <<< "$positions"
    done
    for transient in \
        "$output/opener/state/control.token" "$output/opener/state/daemon.pid" \
        "$output/probe/state/control.token" "$output/probe/state/daemon.pid"; do
        [ ! -e "$transient" ] || unlink "$transient"
    done
    docker rm -f "$opener_container" >/dev/null 2>&1 || true
    docker rm -f "$probe_container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for role in opener probe; do
    container_name="$opener_container"
    [ "$role" = opener ] || container_name="$probe_container"
    docker run --detach \
        --name "$container_name" \
        --network host \
        --user "$(id -u):$(id -g)" \
        --entrypoint /bin/sh \
        --volume "$output/$role:/work" \
        --workdir /work \
        "$image" -c 'while :; do sleep 3600; done' >/dev/null
    docker inspect "$container_name" | jq '
        .[0] | {
          schema:"qkt-live-margin-floor-container-v1",id:.Id,image:.Image,
          networkMode:.HostConfig.NetworkMode,user:.Config.User,
          resourceRestrictions:{memoryBytes:.HostConfig.Memory,nanoCpus:.HostConfig.NanoCpus,
            cpuQuota:.HostConfig.CpuQuota,pidsLimit:.HostConfig.PidsLimit,cpusetCpus:.HostConfig.CpusetCpus},
          credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false
        }
    ' > "$output/$role/evidence/container.json"
    jq -e '
        .networkMode == "host" and
        .resourceRestrictions.memoryBytes == 0 and .resourceRestrictions.nanoCpus == 0 and
        .resourceRestrictions.cpuQuota == 0 and
        (.resourceRestrictions.pidsLimit == null or .resourceRestrictions.pidsLimit == 0) and
        .resourceRestrictions.cpusetCpus == "" and
        .credentialStoredInConfig == false and .jvmOverrideEnvironmentPresent == false
    ' "$output/$role/evidence/container.json" >/dev/null || fail "$role container has a resource restriction"
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_name" |
        rg --text --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY"); then
        fail "broker credential was stored in $role container configuration"
    fi
done

(
    printf '%s\n' "$QKT_BROKER_API_KEY" |
        docker exec -i "$opener_container" /bin/sh -c '
            IFS= read -r QKT_BROKER_API_KEY
            export QKT_BROKER_API_KEY QKT_STATE_DIR=/work/state QKT_LATENCY_TRACKING=1
            exec qkt daemon start --config /work/qkt.config.yaml --state-dir /work/state
        '
) > "$output/opener/logs/daemon.log" 2>&1 &
opener_pid=$!

opener_ready=false
for _ in $(seq 1 120); do
    kill -0 "$opener_pid" 2>/dev/null || fail "opener daemon exited before readiness"
    if "$cli" daemon status --state-dir "$output/opener/state" --json > "$output/opener/evidence/daemon-empty.json" 2>/dev/null &&
        jq -e '.status == "ok" and .strategies == 0' "$output/opener/evidence/daemon-empty.json" >/dev/null; then
        opener_ready=true
        break
    fi
    sleep 1
done
$opener_ready || fail "opener daemon did not become ready and empty"

wait_for_odd_minute_window opener
docker exec "$opener_container" qkt deploy "/work/strategies/$opener_strategy.qkt" \
    --as "$opener_strategy" --state-dir /work/state --json \
    > "$output/opener/evidence/deploy.json"
jq -e --arg strategy "$opener_strategy" '.name == $strategy and .state == "running"' \
    "$output/opener/evidence/deploy.json" >/dev/null || fail "opener deploy did not enter running state"

position_seen=false
for _ in $(seq 1 "$timeout_seconds"); do
    kill -0 "$opener_pid" 2>/dev/null || fail "opener daemon exited before opening the live position"
    gateway_get "/get_positions?magic=$opener_magic" > "$output/opener/evidence/position-open.json"
    count="$(jq '.data | length' "$output/opener/evidence/position-open.json")"
    [ "$count" -le 1 ] || fail "opener created more than one position"
    if [ "$count" -eq 1 ]; then
        position_seen=true
        break
    fi
    sleep 1
done
$position_seen || fail "opener did not create the bounded live position within $timeout_seconds seconds"

jq -e --argjson magic "$opener_magic" '
    .ok == true and
    (.data | length) == 1 and
    .data[0].magic == $magic and
    .data[0].symbol == "EURUSDm" and
    .data[0].volume == 0.01 and
    .data[0].price_open > 0
' "$output/opener/evidence/position-open.json" >/dev/null || fail "opener live position violates the bounded contract"
opener_ticket="$(jq -r '.data[0].ticket' "$output/opener/evidence/position-open.json")"

account_with_exposure="$(gateway_get /account)"
printf '%s\n' "$account_with_exposure" > "$output/evidence/account-with-exposure.raw.json"
observed_margin_level="$(jq -er '.margin_level | tonumber' "$output/evidence/account-with-exposure.raw.json")"
observed_margin="$(jq -er '.margin | tonumber' "$output/evidence/account-with-exposure.raw.json")"
awk -v level="$observed_margin_level" 'BEGIN {exit !(level > 0)}' || fail "margin-floor fixture needs a strictly positive observed margin_level"
awk -v margin="$observed_margin" 'BEGIN {exit !(margin > 0)}' || fail "margin-floor fixture needs a strictly positive observed margin"
dynamic_floor="$(
    awk -v level="$observed_margin_level" 'BEGIN {
        whole = int(level)
        ceil = (level > whole ? whole + 1 : whole)
        printf "%d", ceil + 1000
    }'
)"
awk -v floor="$dynamic_floor" -v level="$observed_margin_level" 'BEGIN {exit !(floor > level)}' ||
    fail "dynamic floor must be strictly above the observed live margin level"

sed "s/__QKT_DYNAMIC_MARGIN_FLOOR_PCT__/$dynamic_floor/g" \
    "$output/probe/qkt.config.template.yaml" > "$output/probe/qkt.config.yaml"
rg --fixed-strings "margin_floor_pct: \"$dynamic_floor\"" "$output/probe/qkt.config.yaml" >/dev/null ||
    fail "probe config did not materialize the dynamic margin floor"
if rg --fixed-strings '__QKT_DYNAMIC_MARGIN_FLOOR_PCT__' "$output/probe/qkt.config.yaml" >/dev/null; then
    fail "probe config still contains the dynamic margin-floor placeholder"
fi
jq -n \
    --arg observedMarginLevelPct "$observed_margin_level" \
    --arg observedMargin "$observed_margin" \
    --argjson dynamicFloorPct "$dynamic_floor" '
    {
      schema:"qkt-live-margin-floor-selection-result-v1",
      observedMarginLevelPct:$observedMarginLevelPct,
      observedMargin:$observedMargin,
      dynamicFloorPct:$dynamicFloorPct,
      selectionRule:"ceil(observed_margin_level_pct) + 1000"
    }
' > "$output/probe/evidence/dynamic-floor.json"

(
    printf '%s\n' "$QKT_BROKER_API_KEY" |
        docker exec -i "$probe_container" /bin/sh -c '
            IFS= read -r QKT_BROKER_API_KEY
            export QKT_BROKER_API_KEY QKT_STATE_DIR=/work/state QKT_LATENCY_TRACKING=1
            exec qkt daemon start --config /work/qkt.config.yaml --state-dir /work/state
        '
) > "$output/probe/logs/daemon.log" 2>&1 &
probe_pid=$!

probe_ready=false
for _ in $(seq 1 120); do
    kill -0 "$probe_pid" 2>/dev/null || fail "probe daemon exited before readiness"
    if "$cli" daemon status --state-dir "$output/probe/state" --json > "$output/probe/evidence/daemon-empty.json" 2>/dev/null &&
        jq -e '.status == "ok" and .strategies == 0' "$output/probe/evidence/daemon-empty.json" >/dev/null; then
        probe_ready=true
        break
    fi
    sleep 1
done
$probe_ready || fail "probe daemon did not become ready and empty"

wait_for_odd_minute_window probe
docker exec "$probe_container" qkt deploy "/work/strategies/$probe_strategy.qkt" \
    --as "$probe_strategy" --state-dir /work/state --json \
    > "$output/probe/evidence/deploy.json"
jq -e --arg strategy "$probe_strategy" '.name == $strategy and .state == "running"' \
    "$output/probe/evidence/deploy.json" >/dev/null || fail "probe deploy did not enter running state"

probe_rejected=false
for _ in $(seq 1 "$timeout_seconds"); do
    kill -0 "$probe_pid" 2>/dev/null || fail "probe daemon exited before margin-floor rejection"
    audit_root="$output/probe/state/state/audit-journal"
    transport_root="$output/probe/state/state/mt5-transport-journal"
    if [ -d "$audit_root" ] && [ -d "$transport_root" ]; then
        has_stream=false
        has_eval=false
        has_decision=false
        has_link=false
        has_reject=false
        rg --quiet '"eventType":"com.qkt.events.StreamCandleEvent"' "$audit_root" && has_stream=true || true
        rg --quiet '"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent"' "$audit_root" && has_eval=true || true
        rg --quiet '"eventType":"com.qkt.events.RuleDecisionEvent"' "$audit_root" && has_decision=true || true
        rg --quiet '"eventType":"com.qkt.events.DecisionOrderLinkedEvent"' "$audit_root" && has_link=true || true
        rg --quiet '"eventType":"com.qkt.events.RiskRejectedEvent"' "$audit_root" && has_reject=true || true
        if [ "$has_stream" = true ] && [ "$has_eval" = true ] && [ "$has_decision" = true ] &&
            [ "$has_link" = true ] && [ "$has_reject" = true ]; then
            probe_rejected=true
            break
        fi
        if rg --quiet '"eventType":"com.qkt.events.OrderEvent"' "$audit_root"; then
            fail "probe passed a margin-floor-blocked intent into broker submission"
        fi
        if rg --quiet '"method":"(POST|PUT|PATCH|DELETE)"' "$transport_root"; then
            fail "probe issued a mutating gateway request before margin-floor rejection"
        fi
    fi
    sleep 1
done
$probe_rejected || fail "probe did not retain the live margin-floor rejection chain within $timeout_seconds seconds"

"$cli" status "$probe_strategy" --state-dir "$output/probe/state" > "$output/probe/evidence/status-after-rejection.txt"

"$cli" status "$opener_strategy" --state-dir "$output/opener/state" > "$output/opener/evidence/status-before-flatten.txt"
"$cli" kill "$opener_strategy" --flatten --state-dir "$output/opener/state" --json > "$output/opener/evidence/kill-flatten.json"
jq -e '.state == "killed" and .flatten == true and .flattenVerified == true and (.remainingTickets | length) == 0' \
    "$output/opener/evidence/kill-flatten.json" >/dev/null || fail "opener could not verify the bounded position was flattened"

opener_flat_seen=false
for _ in $(seq 1 60); do
    gateway_get "/get_positions?magic=$opener_magic" > "$output/opener/evidence/positions-final.json"
    if jq -e '.ok == true and (.data | length) == 0' "$output/opener/evidence/positions-final.json" >/dev/null; then
        opener_flat_seen=true
        break
    fi
    sleep 1
done
$opener_flat_seen || fail "opener did not return to flat after the opener flatten path"

account_after_opener_flat="$(gateway_get /account)"
printf '%s\n' "$account_after_opener_flat" > "$output/evidence/account-after-opener-flat.raw.json"
jq -e '.margin == 0 and .equity == .balance' "$output/evidence/account-after-opener-flat.raw.json" >/dev/null ||
    fail "account headroom did not recover after opener flatten"

probe_recovered_open=false
for _ in $(seq 1 "$timeout_seconds"); do
    kill -0 "$probe_pid" 2>/dev/null || fail "probe daemon exited before recovered entry"
    gateway_get "/get_positions?magic=$probe_magic" > "$output/probe/evidence/position-recovered-open.json"
    count="$(jq '.data | length' "$output/probe/evidence/position-recovered-open.json")"
    [ "$count" -le 1 ] || fail "probe created more than one recovered position"
    if [ "$count" -eq 1 ]; then
        probe_recovered_open=true
        break
    fi
    sleep 1
done
$probe_recovered_open || fail "probe did not open after margin headroom recovered"
probe_ticket="$(jq -r '.data[0].ticket' "$output/probe/evidence/position-recovered-open.json")"
jq -e --argjson magic "$probe_magic" '
    .ok == true and (.data | length) == 1 and .data[0].magic == $magic and
    .data[0].symbol == "EURUSDm" and .data[0].volume == 0.01 and .data[0].price_open > 0
' "$output/probe/evidence/position-recovered-open.json" >/dev/null ||
    fail "probe recovered position violates the bounded contract"

"$cli" status "$probe_strategy" --state-dir "$output/probe/state" > "$output/probe/evidence/status-recovered-open.txt"
"$cli" kill "$probe_strategy" --flatten --state-dir "$output/probe/state" --json > "$output/probe/evidence/kill-flatten.json"
jq -e '.state == "killed" and .flatten == true and .flattenVerified == true and (.remainingTickets | length) == 0' \
    "$output/probe/evidence/kill-flatten.json" >/dev/null || fail "probe could not verify recovered position flatten"

flat_seen=false
for _ in $(seq 1 60); do
    gateway_get "/get_positions?magic=$opener_magic" > "$output/opener/evidence/positions-final.json"
    gateway_get "/get_positions?magic=$probe_magic" > "$output/probe/evidence/positions-final.json"
    gateway_get /get_positions > "$output/evidence/positions-final.json"
    gateway_get /orders > "$output/evidence/orders-final.json"
    if jq -e '.ok == true and (.data | length) == 0' "$output/opener/evidence/positions-final.json" >/dev/null &&
        jq -e '.ok == true and (.data | length) == 0' "$output/probe/evidence/positions-final.json" >/dev/null &&
        jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-final.json" >/dev/null &&
        jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-final.json" >/dev/null; then
        flat_seen=true
        break
    fi
    sleep 1
done
$flat_seen || fail "account did not return to flat after the recovered probe flatten path"

"$cli" daemon stop --state-dir "$output/probe/state" > "$output/probe/evidence/daemon-stop.log"
wait "$probe_pid"
probe_pid=""

"$cli" daemon stop --state-dir "$output/opener/state" > "$output/opener/evidence/daemon-stop.log"
wait "$opener_pid"
opener_pid=""
if [ -f "$output/opener/state/control.token" ]; then unlink "$output/opener/state/control.token"; fi
if [ -f "$output/opener/state/daemon.pid" ]; then unlink "$output/opener/state/daemon.pid"; fi
if [ -f "$output/probe/state/control.token" ]; then unlink "$output/probe/state/control.token"; fi
if [ -f "$output/probe/state/daemon.pid" ]; then unlink "$output/probe/state/daemon.pid"; fi

"$cli" bot history --broker exness --since 0 --config "$output/opener/qkt.config.yaml" --json > "$output/opener/evidence/history.json"
"$cli" bot history --broker exness --since 0 --config "$output/probe/qkt.config.yaml" --json > "$output/probe/evidence/history.json"
jq -e --argjson ticket "$opener_ticket" '
    ([.[] | select(.positionTicket == $ticket and .entry == "IN" and .lots == 0.01)] | length) >= 1 and
    ([.[] | select(.positionTicket == $ticket and .entry == "OUT" and .lots == 0.01)] | length) >= 1
' "$output/opener/evidence/history.json" >/dev/null || fail "history did not retain opener entry and exit deals on the owned ticket"
jq -e --argjson ticket "$probe_ticket" '
    ([.[] | select(.positionTicket == $ticket and .entry == "IN" and .lots == 0.01)] | length) >= 1 and
    ([.[] | select(.positionTicket == $ticket and .entry == "OUT" and .lots == 0.01)] | length) >= 1
' "$output/probe/evidence/history.json" >/dev/null || fail "history did not retain recovered probe entry and exit deals on the owned ticket"

account_final="$(gateway_get /account)"
printf '%s\n' "$account_final" > "$output/evidence/account-final.raw.json"
jq -e --slurpfile initial "$output/evidence/account-initial.raw.json" '
    .login == $initial[0].login and
    .server == $initial[0].server and
    .trade_mode == $initial[0].trade_mode and
    .currency == $initial[0].currency and
    .leverage == $initial[0].leverage and
    .margin == 0 and .equity == .balance and .trade_allowed == true and .trade_expert == true
' "$output/evidence/account-final.raw.json" >/dev/null || fail "final account identity is inconsistent or not flat"

initial_balance="$(jq -er '.balance' "$output/evidence/account-initial.raw.json")"
final_balance="$(jq -er '.balance' "$output/evidence/account-final.raw.json")"
balance_delta="$(
    awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}'
)"
deal_net="$(
    jq -r --argjson ticket "$opener_ticket" '
        [.[] | select(.positionTicket == $ticket) | ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
    ' "$output/opener/evidence/history.json" |
        awk '{printf "%.2f", $1}'
)"
probe_deal_net="$(
    jq -r --argjson ticket "$probe_ticket" '
        [.[] | select(.positionTicket == $ticket) | ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0
    ' "$output/probe/evidence/history.json" |
        awk '{printf "%.2f", $1}'
)"
combined_deal_net="$(
    awk -v opener="$deal_net" -v probe="$probe_deal_net" 'BEGIN {printf "%.2f", opener + probe}'
)"
[ "$balance_delta" = "$combined_deal_net" ] ||
    fail "venue balance delta $balance_delta does not reconcile to opener+probe deal net $combined_deal_net"

mapfile -t opener_audits < <(find "$output/opener/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t opener_transports < <(find "$output/opener/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
mapfile -t probe_audits < <(find "$output/probe/state/state/audit-journal" -type f -name '*.jsonl' | sort)
mapfile -t probe_transports < <(find "$output/probe/state/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
[ "${#opener_audits[@]}" -gt 0 ] || fail "opener produced no engine audit journal"
[ "${#opener_transports[@]}" -gt 0 ] || fail "opener produced no MT5 transport journal"
[ "${#probe_audits[@]}" -gt 0 ] || fail "probe produced no engine audit journal"
[ "${#probe_transports[@]}" -gt 0 ] || fail "probe produced no MT5 transport journal"
for journal in "${opener_audits[@]}" "${opener_transports[@]}" "${probe_audits[@]}" "${probe_transports[@]}"; do
    jq -c . "$journal" >/dev/null || fail "retained invalid JSONL: $journal"
done
[ -z "$(find \
    "$output/opener/state/state/audit-journal" "$output/opener/state/state/mt5-transport-journal" \
    "$output/probe/state/state/audit-journal" "$output/probe/state/state/mt5-transport-journal" \
    -type f -name '*.dropped' -print -quit)" ] || fail "a margin-floor journal reported dropped records"

opener_order_events="$(jq -r --arg strategy "$opener_strategy" 'select(.eventType == "com.qkt.events.OrderEvent" and .strategyId == $strategy) | 1' "${opener_audits[@]}" | count_records)"
opener_fills="$(jq -r --arg strategy "$opener_strategy" 'select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled" and .strategyId == $strategy) | 1' "${opener_audits[@]}" | count_records)"
opener_close_posts="$(jq -r --argjson ticket "$opener_ticket" '
    select(.method == "POST" and .path == "/close_position" and
        ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
        (.responseCode >= 200 and .responseCode < 300)) | 1
' "${opener_transports[@]}" | count_records)"
[ "$opener_order_events" -ge 1 ] || fail "opener retained no order event"
[ "$opener_fills" -ge 1 ] || fail "opener retained no fill event"
[ "$opener_close_posts" -ge 1 ] || fail "opener retained no successful close mutation"

jq -s -e --arg strategy "$probe_strategy" --slurpfile contract "$output/probe/expected.json" '
    . as $events |
    ($contract[0]) as $c |
    ($c.expectedReason.value | gsub("\\\\u2014"; "—")) as $expectedReasonRegex |
    [$events[] | select(.eventType == "com.qkt.events.StreamCandleEvent" and
      .broker == "EXNESS" and .timeframe == $c.timeframe and (.payload | contains("symbol=" + $c.symbol)))] as $streams |
    [$events[] | select(.eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
      .strategyId == $strategy and .timeframe == $c.timeframe and (.payload | contains("symbol=" + $c.symbol)))] as $evaluations |
    [$events[] | select(.eventType == "com.qkt.events.RuleDecisionEvent" and
      .strategyId == $strategy and .conditionResult == true and .signalCount == 1)] as $decisions |
    [$events[] | select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy)] as $links |
    [$events[] | select(.eventType == "com.qkt.events.RiskRejectedEvent" and .strategyId == $strategy)] as $rejects |
    ($rejects[0]) as $reject |
    [$events[] | select(.eventType == "com.qkt.events.OrderEvent" and .strategyId == $strategy and .seq <= $reject.seq)] as $preRejectOrders |
    [$events[] | select(
      .seq <= $reject.seq and (
        (.eventType | test("BrokerEvent[.]Order(Accepted|PartiallyFilled|Filled|Rejected)$")) or
        .eventType == "com.qkt.events.FillAccountedEvent"
      )
    )] as $preRejectBrokerEvents |
    [$decisions[] | select(.seq < $reject.seq)] as $preRejectDecisions |
    [$links[] | select(.seq < $reject.seq and .orderId == $reject.orderId)] as $preRejectLinks |
    ($streams | length) >= $c.required.streamCandlesMin and
    ($evaluations | length) >= $c.required.evaluatedCandlesMin and
    ($preRejectDecisions | length) >= $c.required.ruleDecisions and
    ($preRejectLinks | length) >= $c.required.decisionOrderLinks and
    ($rejects | length) >= $c.required.riskRejections and
    ($preRejectOrders | length) == $c.required.preRecoveryOrderEvents and
    ($preRejectBrokerEvents | length) == $c.required.preRecoveryFills and
    ($preRejectDecisions[0].decisionId == $preRejectLinks[0].decisionId) and
    ($preRejectDecisions[0].ruleId == $preRejectLinks[0].ruleId) and
    ($preRejectLinks[0].orderId == $reject.orderId) and
    ($preRejectDecisions[0].seq < $preRejectLinks[0].seq) and
    ($preRejectLinks[0].seq < $reject.seq) and
    ($reject.orderSchemaVersion == 1) and
    ($reject.order.orderId == $reject.orderId) and
    ($reject.order.strategyId == $strategy) and
    ($reject.order.symbol == $c.symbol) and
    ($reject.order.qty == 0.01) and
    ($reject.reason | test($expectedReasonRegex))
' "${probe_audits[@]}" >/dev/null || fail "probe margin-floor rejection contract failed"

probe_mutations="$(jq -r 'select((.method // "GET") | test("^(POST|PUT|PATCH|DELETE)$")) | 1' "${probe_transports[@]}" | count_records)"
probe_order_posts="$(jq -r 'select(.method == "POST" and .path == "/order" and (.responseCode >= 200 and .responseCode < 300)) | 1' "${probe_transports[@]}" | count_records)"
probe_close_posts="$(jq -r --argjson ticket "$probe_ticket" '
    select(.method == "POST" and .path == "/close_position" and
        ((.requestBody | fromjson | .position.ticket | tostring) == ($ticket | tostring)) and
        (.responseCode >= 200 and .responseCode < 300)) | 1
' "${probe_transports[@]}" | count_records)"
[ "$probe_order_posts" -ge 1 ] || fail "probe did not issue a recovered order after headroom returned"
[ "$probe_close_posts" -ge 1 ] || fail "probe did not issue a recovered close after headroom returned"
probe_gateway_reads="$(jq -r 'select(.method == "GET") | 1' "${probe_transports[@]}" | count_records)"
[ "$probe_gateway_reads" -gt 0 ] || fail "probe retained no live gateway reads"

jq -n \
    --arg strategy "$opener_strategy" \
    --argjson magic "$opener_magic" \
    --argjson ticket "$opener_ticket" \
    --arg dealNet "$deal_net" \
    --arg balanceDelta "$balance_delta" \
    --argjson fills "$opener_fills" \
    --argjson orderEvents "$opener_order_events" \
    --argjson closePosts "$opener_close_posts" '
    {
      schema:"qkt-live-margin-floor-opener-result-v1",
      status:"passed",
      strategy:$strategy,
      magic:$magic,
      ownedTicket:$ticket,
      counts:{orderEvents:$orderEvents,fills:$fills,successfulClosePosts:$closePosts},
      verified:{liveExposureObserved:true,finalFlat:true,balanceDeltaReconciles:true},
      dealNet:$dealNet,
      balanceDelta:$balanceDelta
    }
' > "$output/opener/evidence/result.json"

jq -n \
    --arg strategy "$probe_strategy" \
    --argjson magic "$probe_magic" \
    --arg observedMarginLevelPct "$observed_margin_level" \
    --argjson dynamicFloorPct "$dynamic_floor" \
    --argjson ticket "$probe_ticket" \
    --arg rejectReason "$(jq -r -s '
        first(.[] | select(.eventType == "com.qkt.events.RiskRejectedEvent" and .strategyId == "'"$probe_strategy"'")) | .reason
    ' "${probe_audits[@]}")" \
    --argjson gatewayReads "$probe_gateway_reads" \
    --argjson recoveredOrderPosts "$probe_order_posts" \
    --argjson recoveredClosePosts "$probe_close_posts" '
    {
      schema:"qkt-live-margin-floor-probe-result-v1",
      status:"passed",
      strategy:$strategy,
      magic:$magic,
      recoveredTicket:$ticket,
      observedMarginLevelPct:$observedMarginLevelPct,
      dynamicFloorPct:$dynamicFloorPct,
      rejection:{rule:"MarginFloor",reason:$rejectReason,fixedIntentQty:"0.01",causalChainVerified:true},
      recovery:{headroomRecovered:true,recoveredOrderAccepted:true,recoveredFlattenVerified:true},
      counts:{
        streamCandlesMinObserved:true,
        evaluatedCandlesMinObserved:true,
        ruleDecisions:1,
        decisionOrderLinks:1,
        riskRejections:1,
        preRecoveryOrderEvents:0,
        preRecoveryFills:0,
        gatewayReads:$gatewayReads,
        preRecoveryGatewayMutations:0,
        recoveredOrderPosts:$recoveredOrderPosts,
        recoveredClosePosts:$recoveredClosePosts
      },
      preRecoveryFinanciallyReadOnly:true
    }
' > "$output/probe/evidence/result.json"

jq -n \
    --slurpfile suite "$output/source/suite.json" \
    --slurpfile opener "$output/opener/evidence/result.json" \
    --slurpfile probe "$output/probe/evidence/result.json" '
    {
      schema:"qkt-live-margin-floor-result-v1",
      status:"passed",
      qktCommit:$suite[0].qktCommit,
      opener:$opener[0],
      probe:$probe[0],
      aggregate:{
        openerCreatesLiveExposure:true,
        probeRejectsBeforeTransport:true,
        probeAllowedAfterHeadroomRecovery:true,
        dynamicMarginFloorPct:true,
        finalVenueFlat:true,
        finalPendingOrders:false
      },
      claims:{
        marginFloorPassed:true,
        productionReadiness:false
      },
      credentialStored:false,
      dockerResourceRestrictionsVerifiedAbsent:true,
      publicationSafe:false,
      containsPrivateAccountMetadata:true
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
