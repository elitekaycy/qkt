#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: run-container-round-trips.sh --scenario-a DIR --scenario-b DIR \
  --output DIR --image IMAGE [--cli PATH] [--timeout-seconds N]
       run-container-round-trips.sh --scenario-a DIR --scenario-b DIR \
  --verify-only [--cli PATH]

Runs two prepared, isolated 0.01-lot indicator round trips in unrestricted QKT
containers against one MT5 demo gateway bound to 127.0.0.1. Scenario A must be
EURUSD and scenario B GBPUSD. Live execution additionally requires both:
  --arm I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01
  QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
EOF
}

fail() {
    printf 'run-container-round-trips: %s\n' "$1" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "required file not found: $1"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

count_records() {
    awk 'END {print NR + 0}'
}

has_live_timeframe_evidence() {
    local index="$1"
    local -a audit_files=()
    mapfile -t audit_files < <(find "${states[$index]}/state/audit-journal" -type f -name '*.jsonl' 2>/dev/null | sort)
    [ "${#audit_files[@]}" -gt 0 ] || return 1
    jq -s -e \
        --arg strategy "${strategies[$index]}" \
        --arg symbol "${expected_symbols[$index]}" '
        . as $events |
        [["asset1", "1m"], ["asset5", "5m"]] as $required |
        all(
            $required[];
            .[0] as $alias |
            .[1] as $timeframe |
            any(
                $events[];
                .eventType == "com.qkt.events.StrategyCandleEvaluatedEvent" and
                .strategyId == $strategy and .symbol == $symbol and
                .alias == $alias and .timeframe == $timeframe and
                (. as $evaluation |
                    any(
                        $events[];
                        .eventType == "com.qkt.events.StreamCandleEvent" and
                        .symbol == $symbol and .timeframe == $timeframe and
                        .candle.startTimeMs == $evaluation.candle.startTimeMs and
                        .candle.endTimeMs == $evaluation.candle.endTimeMs
                    )
                )
            )
        )
    ' "${audit_files[@]}" >/dev/null
}

extract_indicator_entry_trace() {
    awk '
        function numeric(value) {
            return value ~ /^-?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$/
        }
        /bounded indicator entry side=/ {
            side = score = m1fast = m1slow = m5fast = m5slow = closing = ""
            for (i = 1; i <= NF; i++) {
                split($i, pair, "=")
                if (pair[1] == "side") side = pair[2]
                if (pair[1] == "score") score = pair[2]
                if (pair[1] == "m1_fast") m1fast = pair[2]
                if (pair[1] == "m1_slow") m1slow = pair[2]
                if (pair[1] == "m5_fast") m5fast = pair[2]
                if (pair[1] == "m5_slow") m5slow = pair[2]
                if (pair[1] == "close") closing = pair[2]
            }
            if ((side == "BUY" || side == "SELL") && numeric(score) &&
                numeric(m1fast) && numeric(m1slow) && numeric(m5fast) &&
                numeric(m5slow) && numeric(closing)) {
                printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", side, score, m1fast, m1slow, m5fast, m5slow, closing
            }
        }
    ' "$1"
}

extract_indicator_exit_trace() {
    awk '
        function numeric(value) {
            return value ~ /^-?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$/
        }
        /bounded indicator exit signed_qty=/ {
            quantity = holding = closing = ""
            for (i = 1; i <= NF; i++) {
                split($i, pair, "=")
                if (pair[1] == "signed_qty") quantity = pair[2]
                if (pair[1] == "holding_seconds") holding = pair[2]
                if (pair[1] == "close") closing = pair[2]
            }
            if (numeric(quantity) && numeric(holding) && numeric(closing)) {
                printf "%s\t%s\t%s\n", quantity, holding, closing
            }
        }
    ' "$1"
}

scenario_a=""
scenario_b=""
output=""
image=""
cli="$repo_root/build/install/qkt/bin/qkt"
timeout_seconds=360
arm=""
verify_only=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario-a) scenario_a="${2:-}"; shift 2 ;;
        --scenario-b) scenario_b="${2:-}"; shift 2 ;;
        --output) output="${2:-}"; shift 2 ;;
        --image) image="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --timeout-seconds) timeout_seconds="${2:-}"; shift 2 ;;
        --arm) arm="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario_a" ] || fail "--scenario-a is required"
[ -n "$scenario_b" ] || fail "--scenario-b is required"
[ -x "$cli" ] || fail "QKT CLI is not executable: $cli"
for command in find git jq realpath rg sha256sum sort; do
    require_command "$command"
done

scenario_a="$(realpath "$scenario_a")"
scenario_b="$(realpath "$scenario_b")"
[ "$scenario_a" != "$scenario_b" ] || fail "scenario directories must be distinct"
scenarios=("$scenario_a" "$scenario_b")
expected_symbols=("EXNESS:EURUSD" "EXNESS:GBPUSD")
venue_symbols=("EURUSDm" "GBPUSDm")
configs=("" "")
states=("" "")
evidences=("" "")
strategies=("" "")
strategy_files=("" "")
scenario_ids=("" "")
magics=("" "")
gateways=("" "")
commits=("" "")

for index in 0 1; do
    scenario="${scenarios[$index]}"
    [ -d "$scenario" ] || fail "scenario directory not found: $scenario"
    require_file "$scenario/SHA256SUMS"
    require_file "$scenario/qkt.config.yaml"
    require_file "$scenario/expected.json"
    require_file "$scenario/scenario.json"
    require_file "$scenario/cleanup.json"
    (cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null) ||
        fail "prepared artifact checksum verification failed for scenario $index"
    mapfile -d '' armed < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*_market_bracket.qkt' -print0 | sort -z)
    [ "${#armed[@]}" -eq 1 ] || fail "scenario $index must contain exactly one armed strategy"

    strategy_file="${armed[0]}"
    strategy="$(basename "$strategy_file" .qkt)"
    expected_symbol="${expected_symbols[$index]}"
    jq -e --arg strategy "$strategy" --arg symbol "$expected_symbol" '
        .schema == "qkt-live-validation-expected-v2" and
        .account.tradeMode == "demo" and .account.currency == "USD" and
        .safety.gatewayUrl == (.safety.gatewayUrl | select(startswith("http://127.0.0.1:"))) and
        .safety.maximumLots == "0.01" and .safety.maximumOpenPositions == 1 and
        .safety.maximumTradesPerDay == 1 and
        .armedScenario.strategy == $strategy and .armedScenario.symbol == $symbol and
        (.armedScenario.streams | map(.timeframe) == ["1m", "5m"]) and
        all(.armedScenario.streams[]; .symbol == $symbol and .warmupBars == 10) and
        .armedScenario.quantityLots == "0.01" and
        .armedScenario.maximumEntries == 1 and .armedScenario.maximumExits == 1 and
        .armedScenario.buyWhen == "score>=0" and .armedScenario.sellWhen == "score<0" and
        .armedScenario.exitTimeframe == "1m" and .armedScenario.minimumHoldingSeconds == 1 and
        .armedScenario.maximumEntryAnchorDriftPoints == 20 and
        .armedScenario.stopDistance == "0.0030" and
        .armedScenario.takeProfitDistance == "0.0060"
    ' "$scenario/expected.json" >/dev/null || fail "scenario $index expected contract is not the bounded round trip"
    jq -e --arg gateway "$(jq -er '.safety.gatewayUrl' "$scenario/expected.json")" '
        .schema == "qkt-live-validation-scenario-v1" and
        .credentialsStored == false and .executionState == "prepared" and
        .gatewayUrl == $gateway and (.magic | type) == "number" and .magic > 0
    ' "$scenario/scenario.json" >/dev/null || fail "scenario $index identity document is invalid"
    grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$scenario/qkt.config.yaml" >/dev/null ||
        fail "scenario $index config does not use the runtime broker credential"
    grep -F 'max_order_qty: "0.01"' "$scenario/qkt.config.yaml" >/dev/null ||
        fail "scenario $index config does not cap order quantity"
    [ "$(grep -Fc 'THEN BUY asset1 SIZING 0.01' "$strategy_file")" -eq 1 ] ||
        fail "scenario $index does not contain one bounded BUY branch"
    [ "$(grep -Fc 'THEN SELL asset1 SIZING 0.01' "$strategy_file")" -eq 1 ] ||
        fail "scenario $index does not contain one bounded SELL branch"
    [ "$(grep -Fc 'THEN CLOSE asset1' "$strategy_file")" -eq 1 ] ||
        fail "scenario $index does not contain one strategy close"
    [ "$(grep -Fc 'POSITION.asset1.holding_duration >= 1' "$strategy_file")" -eq 1 ] ||
        fail "scenario $index close is not next-M1 gated"
    [ "$(grep -Fc 'STOP LOSS BY 0.0030, TAKE PROFIT BY 0.0060' "$strategy_file")" -eq 2 ] ||
        fail "scenario $index entry branches do not share the reviewed bracket"
    "$cli" parse "$strategy_file" >/dev/null

    configs[$index]="$scenario/qkt.config.yaml"
    states[$index]="$scenario/state"
    evidences[$index]="$scenario/evidence"
    strategies[$index]="$strategy"
    strategy_files[$index]="$strategy_file"
    scenario_ids[$index]="$(jq -er '.scenarioId' "$scenario/scenario.json")"
    magics[$index]="$(jq -er '.magic' "$scenario/scenario.json")"
    gateways[$index]="$(jq -er '.gatewayUrl' "$scenario/scenario.json")"
    commits[$index]="$(jq -er '.qktCommit' "$scenario/scenario.json")"
done

[ "${scenario_ids[0]}" != "${scenario_ids[1]}" ] || fail "scenario IDs must be distinct"
[ "${strategies[0]}" != "${strategies[1]}" ] || fail "strategy identities must be distinct"
[ "${magics[0]}" != "${magics[1]}" ] || fail "broker magics must be distinct"
[ "${gateways[0]}" = "${gateways[1]}" ] || fail "scenarios must use the same localhost gateway"
[[ "${gateways[0]}" =~ ^http://127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "gateway must be an explicit localhost endpoint"
[ "$(jq -er '.account.login' "${scenarios[0]}/expected.json")" = "$(jq -er '.account.login' "${scenarios[1]}/expected.json")" ] ||
    fail "scenarios must allowlist the same account login"
[ "$(jq -er '.account.server' "${scenarios[0]}/expected.json")" = "$(jq -er '.account.server' "${scenarios[1]}/expected.json")" ] ||
    fail "scenarios must allowlist the same account server"
[ "$(jq -er '.account.startingBalance' "${scenarios[0]}/expected.json")" = "$(jq -er '.account.startingBalance' "${scenarios[1]}/expected.json")" ] ||
    fail "scenarios must declare the same starting balance"
[ "$(jq -er '.account.leverage' "${scenarios[0]}/expected.json")" = "$(jq -er '.account.leverage' "${scenarios[1]}/expected.json")" ] ||
    fail "scenarios must declare the same starting leverage"

if $verify_only; then
    printf 'verified %s %s\n' "$scenario_a" "$scenario_b"
    exit 0
fi

[ -n "$output" ] || fail "--output is required"
[ -n "$image" ] || fail "--image is required"
[[ "$timeout_seconds" =~ ^[0-9]+$ ]] || fail "--timeout-seconds must be an integer"
[ "$timeout_seconds" -ge 330 ] && [ "$timeout_seconds" -le 600 ] ||
    fail "--timeout-seconds must be in 330..600"
[ "$arm" = "I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01" ] || fail "missing exact --arm confirmation"
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] ||
    fail "QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY"
[ -n "${QKT_BROKER_API_KEY:-}" ] || fail "QKT_BROKER_API_KEY is required"
for jvm_env in JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [ -z "${!jvm_env:-}" ] || fail "$jvm_env must be unset; this run does not restrict the JVM"
done
for command in awk curl docker stat unzip; do
    require_command "$command"
done
[ -z "$(git -C "$repo_root" status --porcelain)" ] || fail "repository must be clean"
qkt_commit="$(git -C "$repo_root" rev-parse HEAD)"
[ "${commits[0]}" = "$qkt_commit" ] && [ "${commits[1]}" = "$qkt_commit" ] ||
    fail "prepared scenarios do not match the current QKT commit"
jq -e '.qktDirty == false' "${scenarios[0]}/scenario.json" >/dev/null &&
    jq -e '.qktDirty == false' "${scenarios[1]}/scenario.json" >/dev/null ||
    fail "prepared scenarios must come from a clean checkout"

output="$(realpath -m "$output")"
[ ! -e "$output" ] || fail "output already exists: $output"
for scenario in "${scenarios[@]}"; do
    case "$output/" in
        "$scenario/"*) fail "aggregate output must be outside both scenario directories" ;;
    esac
done
for evidence in "${evidences[@]}"; do
    [ -z "$(find "$evidence" -mindepth 1 -maxdepth 1 -print -quit)" ] ||
        fail "scenario evidence directory is not empty: $evidence"
done

qkt_short="${qkt_commit:0:8}"
host_version="$("$cli" --version)"
[[ "$host_version" == *"($qkt_short)"* ]] || fail "host CLI is not built from $qkt_short"
image_version="$(docker run --rm --entrypoint qkt "$image" --version)"
[[ "$image_version" == *"($qkt_short)"* ]] || fail "Docker image is not built from $qkt_short"

mkdir -m 700 -p "$output/evidence"
gateway_url="${gateways[0]}"
expected_login="$(jq -er '.account.login' "${scenarios[0]}/expected.json")"
expected_server="$(jq -er '.account.server' "${scenarios[0]}/expected.json")"
expected_balance="$(jq -er '.account.startingBalance' "${scenarios[0]}/expected.json")"
expected_leverage="$(jq -er '.account.leverage' "${scenarios[0]}/expected.json")"

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
        .balance == ($balance | tonumber) and .equity == ($balance | tonumber) and .margin == 0 and
        .leverage == $leverage and .trade_allowed == true and .trade_expert == true
    ' "$output/evidence/account-initial.json" >/dev/null || fail "account does not match the flat demo allowlist"
"$cli" bot account --broker exness --config "${configs[0]}" --json > "$output/evidence/qkt-account-initial.json"
jq -e '.ok == true and .hedging == true' "$output/evidence/qkt-account-initial.json" >/dev/null ||
    fail "concurrent validation requires an MT5 hedging account"
gateway_get /get_positions > "$output/evidence/positions-initial.json"
gateway_get /orders > "$output/evidence/orders-initial.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-initial.json" >/dev/null ||
    fail "demo account has an open position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-initial.json" >/dev/null ||
    fail "demo account has a pending order"
for index in 0 1; do
    gateway_get "/get_positions?magic=${magics[$index]}" > "$output/evidence/positions-magic-$index-initial.json"
    gateway_get "/orders?magic=${magics[$index]}" > "$output/evidence/orders-magic-$index-initial.json"
    jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-magic-$index-initial.json" >/dev/null ||
        fail "scenario $index magic already owns a position"
    jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-magic-$index-initial.json" >/dev/null ||
        fail "scenario $index magic already owns an order"
    gateway_get "/symbol_info/${venue_symbols[$index]}" > "$output/evidence/symbol-$index.json"
    jq -e --arg symbol "${venue_symbols[$index]}" '
        .name == $symbol and .trade_mode == 4 and
        .volume_min == 0.01 and .volume_step == 0.01 and .trade_contract_size == 100000 and
        .point > 0 and .digits > 0
    ' "$output/evidence/symbol-$index.json" >/dev/null || fail "scenario $index venue metadata is not the reviewed FX contract"
    "$cli" preflight "${strategy_files[$index]}" --config "${configs[$index]}" \
        > "${evidences[$index]}/preflight.log" 2>&1
done

run_started_ms="$(date +%s%3N)"
run_id="$(date -u +%Y%m%d%H%M%S)-$$"
containers=("qkt-roundtrip-a-$run_id" "qkt-roundtrip-b-$run_id")
daemon_pids=("" "")
deploy_pids=("" "")
tickets=("" "")
position_types=("" "")
entry_prices=("" "")
protection_seen=(false false)
flat_seen=(false false)
control_tokens=()
cleanup_running=false

cleanup() {
    $cleanup_running && return
    cleanup_running=true
    set +e
    for index in 0 1; do
        positions="$(gateway_get "/get_positions?magic=${magics[$index]}" 2>/dev/null)"
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot close "${expected_symbols[$index]}" --ticket "$ticket" \
                --config "${configs[$index]}" --json >/dev/null 2>&1
        done < <(jq -r '.data[]?.ticket' <<<"$positions" 2>/dev/null)
        orders="$(gateway_get "/orders?magic=${magics[$index]}" 2>/dev/null)"
        while IFS= read -r ticket; do
            [ -n "$ticket" ] || continue
            "$cli" bot cancel "${expected_symbols[$index]}" --order "$ticket" \
                --config "${configs[$index]}" --json >/dev/null 2>&1
        done < <(jq -r '.orders[]?.ticket' <<<"$orders" 2>/dev/null)
        if [ -n "${daemon_pids[$index]}" ] && kill -0 "${daemon_pids[$index]}" 2>/dev/null; then
            "$cli" daemon stop --state-dir "${states[$index]}" >/dev/null 2>&1
            wait "${daemon_pids[$index]}" >/dev/null 2>&1
        fi
    done
    for container in "${containers[@]}"; do
        docker rm -f "$container" >/dev/null 2>&1
    done
}
trap cleanup EXIT

for index in 0 1; do
    scenario="${scenarios[$index]}"
    docker run --detach \
        --name "${containers[$index]}" \
        --network host \
        --user "$(id -u):$(id -g)" \
        --entrypoint /bin/sh \
        --volume "$scenario:$scenario" \
        --workdir "$scenario" \
        "$image" -c 'while :; do sleep 3600; done' >/dev/null
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --fixed-strings --quiet -f <(printf '%s\n' "$QKT_BROKER_API_KEY"); then
        fail "broker credential was stored in container $index configuration"
    fi
    if docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${containers[$index]}" |
        rg --quiet '^(JAVA_OPTS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)='; then
        fail "container $index image config restricts or overrides the JVM"
    fi
    docker inspect "${containers[$index]}" | jq -e '
        .[0].HostConfig.Memory == 0 and
        .[0].HostConfig.NanoCpus == 0 and
        .[0].HostConfig.CpuQuota == 0 and
        (.[0].HostConfig.PidsLimit == null or .[0].HostConfig.PidsLimit == 0) and
        .[0].HostConfig.CpusetCpus == ""
    ' >/dev/null || fail "container $index has an unexpected resource restriction"
done

for index in 0 1; do
    (
        printf '%s\n' "$QKT_BROKER_API_KEY" |
            docker exec -i "${containers[$index]}" /bin/sh -c '
                IFS= read -r QKT_BROKER_API_KEY
                export QKT_BROKER_API_KEY QKT_LATENCY_TRACKING=1 QKT_STATE_DIR="$2"
                exec qkt daemon start --config "$1" --state-dir "$2"
            ' sh "${configs[$index]}" "${states[$index]}"
    ) > "${scenarios[$index]}/logs/container-daemon.log" 2>&1 &
    daemon_pids[$index]=$!
done

for index in 0 1; do
    ready=false
    deadline=$((SECONDS + 90))
    while [ "$SECONDS" -lt "$deadline" ]; do
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "container $index daemon exited before readiness"
        if "$cli" daemon status --state-dir "${states[$index]}" --json \
            > "${evidences[$index]}/daemon-ready.json" 2>/dev/null &&
            jq -e '.status == "ok" and .strategies == 0' "${evidences[$index]}/daemon-ready.json" >/dev/null; then
            ready=true
            break
        fi
        sleep 1
    done
    $ready || fail "container $index daemon did not become ready and empty"
done

deploy_started_ms="$(date +%s%3N)"
deploy_launch_ms=("" "")
for index in 0 1; do
    deploy_launch_ms[$index]="$(date +%s%3N)"
    (
        "$cli" deploy "${strategy_files[$index]}" --as "${strategies[$index]}" \
            --state-dir "${states[$index]}" --json > "${evidences[$index]}/deploy.json"
        date +%s%3N > "${evidences[$index]}/deploy-completed-ms"
    ) &
    deploy_pids[$index]=$!
done
for index in 0 1; do
    wait "${deploy_pids[$index]}" || fail "scenario $index deployment failed"
    jq -e --arg strategy "${strategies[$index]}" '.name == $strategy and .state == "running"' \
        "${evidences[$index]}/deploy.json" >/dev/null || fail "scenario $index did not enter running state"
done
deploy_launch_skew_ms="$((${deploy_launch_ms[0]} > ${deploy_launch_ms[1]} ? ${deploy_launch_ms[0]} - ${deploy_launch_ms[1]} : ${deploy_launch_ms[1]} - ${deploy_launch_ms[0]}))"
[ "$deploy_launch_skew_ms" -le 1000 ] || fail "concurrent deployment launch skew exceeded 1000 ms"
deploy_a_ms="$(<"${evidences[0]}/deploy-completed-ms")"
deploy_b_ms="$(<"${evidences[1]}/deploy-completed-ms")"
deploy_skew_ms="$((deploy_a_ms > deploy_b_ms ? deploy_a_ms - deploy_b_ms : deploy_b_ms - deploy_a_ms))"

deadline=$((SECONDS + timeout_seconds))
while [ "$SECONDS" -lt "$deadline" ]; do
    for index in 0 1; do
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "container $index daemon exited during the round trip"
        if rg --quiet 'Order rejected:|Risk rejected:' "${scenarios[$index]}/logs/container-daemon.log"; then
            fail "scenario $index emitted an order rejection"
        fi
        audit_root="${states[$index]}/state/audit-journal"
        if [ -d "$audit_root" ] &&
            rg --quiet '"eventType":"com.qkt.events.(RiskRejectedEvent|BrokerEvent.OrderRejected)"' "$audit_root"; then
            fail "scenario $index retained an order rejection during execution"
        fi
        latest="${evidences[$index]}/positions-magic-latest.json"
        gateway_get "/get_positions?magic=${magics[$index]}" > "$latest"
        count="$(jq -er '.data | length' "$latest")"
        [ "$count" -le 1 ] || fail "scenario $index created more than one position"
        if [ "$count" -eq 1 ]; then
            jq -e \
                --arg symbol "${venue_symbols[$index]}" \
                --argjson magic "${magics[$index]}" \
                --arg point "$(jq -er '.point' "$output/evidence/symbol-$index.json")" \
                --argjson maximumEntryAnchorDriftPoints 20 \
                --arg strategyPrefix "dsl-${strategies[$index]}" '
                    .ok == true and .data[0].symbol == $symbol and .data[0].magic == $magic and
                    .data[0].volume == 0.01 and .data[0].price_open > 0 and
                    .data[0].sl > 0 and .data[0].tp > 0 and
                    (
                        (
                            .data[0].type == 0 and
                            .data[0].sl < .data[0].price_open and .data[0].tp > .data[0].price_open and
                            (.data[0].price_open - .data[0].sl) <=
                                (0.0030 + (($point | tonumber) * $maximumEntryAnchorDriftPoints))
                        ) or
                        (
                            .data[0].type == 1 and
                            .data[0].tp < .data[0].price_open and .data[0].sl > .data[0].price_open and
                            (.data[0].sl - .data[0].price_open) <=
                                (0.0030 + (($point | tonumber) * $maximumEntryAnchorDriftPoints))
                        )
                    ) and
                    (.data[0].comment as $comment | ($strategyPrefix | startswith($comment)))
                ' "$latest" >/dev/null || fail "scenario $index venue position violates the bounded contract"
            if jq -e \
                --arg point "$(jq -er '.point' "$output/evidence/symbol-$index.json")" '
                    (
                        .data[0].type == 0 and
                        ((((.data[0].price_open - .data[0].sl) - 0.0030) | fabs) <= ($point | tonumber)) and
                        ((((.data[0].tp - .data[0].price_open) - 0.0060) | fabs) <= ($point | tonumber))
                    ) or
                    (
                        .data[0].type == 1 and
                        ((((.data[0].sl - .data[0].price_open) - 0.0030) | fabs) <= ($point | tonumber)) and
                        ((((.data[0].price_open - .data[0].tp) - 0.0060) | fabs) <= ($point | tonumber))
                    )
                ' "$latest" >/dev/null; then
                protection_seen[$index]=true
                cp "$latest" "${evidences[$index]}/position-fill-anchored-protection.json"
            fi
            current_ticket="$(jq -er '.data[0].ticket' "$latest")"
            if [ -z "${tickets[$index]}" ]; then
                tickets[$index]="$current_ticket"
                position_types[$index]="$(jq -er '.data[0].type' "$latest")"
                entry_prices[$index]="$(jq -er '.data[0].price_open' "$latest")"
                cp "$latest" "${evidences[$index]}/position-open.json"
                jq --argjson ticket "$current_ticket" \
                    '.ownedPositionTickets = [$ticket] | .status = "position_open"' \
                    "${scenarios[$index]}/cleanup.json" > "${scenarios[$index]}/cleanup.json.tmp"
                mv "${scenarios[$index]}/cleanup.json.tmp" "${scenarios[$index]}/cleanup.json"
            else
                [ "$current_ticket" = "${tickets[$index]}" ] || fail "scenario $index changed its owned position ticket"
            fi
        elif [ -n "${tickets[$index]}" ]; then
            flat_seen[$index]=true
        fi
    done
    if ${flat_seen[0]} && ${flat_seen[1]}; then
        break
    fi
    sleep 0.2
done
[ -n "${tickets[0]}" ] && [ -n "${tickets[1]}" ] || fail "both bounded entry positions were not observed"
${flat_seen[0]} && ${flat_seen[1]} || fail "both strategies did not close their positions within $timeout_seconds seconds"
${protection_seen[0]} && ${protection_seen[1]} ||
    fail "both positions did not expose fill-anchored bracket distances before closing"
[ "${tickets[0]}" != "${tickets[1]}" ] || fail "concurrent scenarios reported the same venue ticket"

timeframe_evidence=(false false)
while [ "$SECONDS" -lt "$deadline" ]; do
    for index in 0 1; do
        kill -0 "${daemon_pids[$index]}" 2>/dev/null || fail "container $index daemon exited while waiting for M5 evidence"
        gateway_get "/get_positions?magic=${magics[$index]}" > "${evidences[$index]}/positions-post-flat-latest.json"
        gateway_get "/orders?magic=${magics[$index]}" > "${evidences[$index]}/orders-post-flat-latest.json"
        jq -e '.ok == true and (.data | length) == 0' "${evidences[$index]}/positions-post-flat-latest.json" >/dev/null ||
            fail "scenario $index reopened a position while waiting for M5 evidence"
        jq -e '.ok == true and (.orders | length) == 0' "${evidences[$index]}/orders-post-flat-latest.json" >/dev/null ||
            fail "scenario $index opened an order while waiting for M5 evidence"
        if has_live_timeframe_evidence "$index"; then
            timeframe_evidence[$index]=true
        fi
    done
    if ${timeframe_evidence[0]} && ${timeframe_evidence[1]}; then
        break
    fi
    sleep 1
done
${timeframe_evidence[0]} && ${timeframe_evidence[1]} ||
    fail "both scenarios did not retain matched live M1/M5 stream and strategy evaluations within $timeout_seconds seconds"

for index in 0 1; do
    gateway_get "/get_positions?magic=${magics[$index]}" > "${evidences[$index]}/positions-magic-final.json"
    gateway_get "/orders?magic=${magics[$index]}" > "${evidences[$index]}/orders-magic-final.json"
    jq -e '.ok == true and (.data | length) == 0' "${evidences[$index]}/positions-magic-final.json" >/dev/null ||
        fail "scenario $index is not flat after its strategy close"
    jq -e '.ok == true and (.orders | length) == 0' "${evidences[$index]}/orders-magic-final.json" >/dev/null ||
        fail "scenario $index retained a pending order"
    "$cli" stop "${strategies[$index]}" --state-dir "${states[$index]}" --json \
        > "${evidences[$index]}/stop-strategy.json"
done
for index in 0 1; do
    "$cli" daemon stop --state-dir "${states[$index]}" > "${evidences[$index]}/daemon-stop.log"
    wait "${daemon_pids[$index]}" || fail "container $index daemon failed during final stop"
    daemon_pids[$index]=""
done

gateway_get /get_positions > "$output/evidence/positions-final.json"
gateway_get /orders > "$output/evidence/orders-final.json"
jq -e '.ok == true and (.data | length) == 0' "$output/evidence/positions-final.json" >/dev/null ||
    fail "concurrent run ended with an account position"
jq -e '.ok == true and (.orders | length) == 0' "$output/evidence/orders-final.json" >/dev/null ||
    fail "concurrent run ended with a pending order"

history_ready=false
for _ in $(seq 1 30); do
    "$cli" bot history --broker exness --since "$run_started_ms" --config "${configs[0]}" --json \
        > "$output/evidence/history-during-run.json"
    if jq -e --argjson ticketA "${tickets[0]}" --argjson ticketB "${tickets[1]}" '
        ([.[] | select(.positionTicket == $ticketA and .entry == "IN")] | length) == 1 and
        ([.[] | select(.positionTicket == $ticketA and .entry == "OUT")] | length) == 1 and
        ([.[] | select(.positionTicket == $ticketB and .entry == "IN")] | length) == 1 and
        ([.[] | select(.positionTicket == $ticketB and .entry == "OUT")] | length) == 1
    ' "$output/evidence/history-during-run.json" >/dev/null; then
        history_ready=true
        break
    fi
    sleep 1
done
$history_ready || fail "venue history did not expose both complete round trips"
jq -e \
    --argjson ticketA "${tickets[0]}" \
    --argjson ticketB "${tickets[1]}" \
    --arg symbolA "${venue_symbols[0]}" \
    --arg symbolB "${venue_symbols[1]}" '
        length == 4 and
        all(.[]; .positionTicket == $ticketA or .positionTicket == $ticketB) and
        ([.[] | select(.positionTicket == $ticketA)] | all(.symbol == $symbolA and .lots == 0.01)) and
        ([.[] | select(.positionTicket == $ticketB)] | all(.symbol == $symbolB and .lots == 0.01))
    ' "$output/evidence/history-during-run.json" >/dev/null || fail "venue history contains foreign or malformed deals"

entry_a_ms="$(jq -er --argjson ticket "${tickets[0]}" '.[] | select(.positionTicket == $ticket and .entry == "IN") | .timeMs' "$output/evidence/history-during-run.json")"
entry_b_ms="$(jq -er --argjson ticket "${tickets[1]}" '.[] | select(.positionTicket == $ticket and .entry == "IN") | .timeMs' "$output/evidence/history-during-run.json")"
exit_a_ms="$(jq -er --argjson ticket "${tickets[0]}" '.[] | select(.positionTicket == $ticket and .entry == "OUT") | .timeMs' "$output/evidence/history-during-run.json")"
exit_b_ms="$(jq -er --argjson ticket "${tickets[1]}" '.[] | select(.positionTicket == $ticket and .entry == "OUT") | .timeMs' "$output/evidence/history-during-run.json")"
latest_entry_ms="$((entry_a_ms > entry_b_ms ? entry_a_ms : entry_b_ms))"
earliest_exit_ms="$((exit_a_ms < exit_b_ms ? exit_a_ms : exit_b_ms))"
[ "$latest_entry_ms" -lt "$earliest_exit_ms" ] || fail "venue deal intervals do not prove concurrent exposure"

gateway_get /account > "$output/evidence/account-final.json"
initial_balance="$(jq -er '.balance' "$output/evidence/account-initial.json")"
final_balance="$(jq -er '.balance' "$output/evidence/account-final.json")"
balance_delta="$(awk -v initial="$initial_balance" -v final="$final_balance" 'BEGIN {printf "%.2f", final - initial}')"
deal_net="$(jq -r '[.[] | ((.profit // 0) + (.commission // 0) + (.swap // 0) + (.fee // 0))] | add // 0' \
    "$output/evidence/history-during-run.json" | awk '{printf "%.2f", $1}')"
[ "$balance_delta" = "$deal_net" ] || fail "account balance delta $balance_delta does not reconcile to owned deal net $deal_net"
jq -e --slurpfile initial "$output/evidence/account-initial.json" '
    .login == $initial[0].login and .server == $initial[0].server and
    .trade_mode == $initial[0].trade_mode and .currency == $initial[0].currency and
    .trade_allowed == true and .trade_expert == true and .margin == 0 and .equity == .balance
' "$output/evidence/account-final.json" >/dev/null || fail "final account identity is not flat and tradeable"

for index in 0 1; do
    mapfile -t audits < <(find "${states[$index]}/state/audit-journal" -type f -name '*.jsonl' | sort)
    mapfile -t transports < <(find "${states[$index]}/state/mt5-transport-journal" -type f -name '*.jsonl' | sort)
    [ "${#audits[@]}" -gt 0 ] || fail "scenario $index produced no engine audit journal"
    [ "${#transports[@]}" -gt 0 ] || fail "scenario $index produced no transport journal"
    for journal in "${audits[@]}" "${transports[@]}"; do
        jq -c . "$journal" >/dev/null || fail "invalid JSONL journal for scenario $index: $journal"
    done
    [ -z "$(find "${states[$index]}/state/audit-journal" "${states[$index]}/state/mt5-transport-journal" -type f -name '*.dropped' -print -quit)" ] ||
        fail "scenario $index journal reported dropped records"
    strategy="${strategies[$index]}"
    expected_wire_side=BUY
    [ "${position_types[$index]}" -eq 0 ] || expected_wire_side=SELL
    decisions="$(jq -r --arg strategy "$strategy" 'select(.eventType == "com.qkt.events.RuleDecisionEvent" and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    links="$(jq -r --arg strategy "$strategy" 'select(.eventType == "com.qkt.events.DecisionOrderLinkedEvent" and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    accepted="$(jq -r --arg strategy "$strategy" 'select(.eventType == "com.qkt.events.BrokerEvent.OrderAccepted" and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    filled="$(jq -r --arg strategy "$strategy" 'select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled" and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    accounted="$(jq -r --arg strategy "$strategy" 'select(.eventType == "com.qkt.events.FillAccountedEvent" and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    rejected="$(jq -r --arg strategy "$strategy" 'select((.eventType == "com.qkt.events.BrokerEvent.OrderRejected" or .eventType == "com.qkt.events.RiskRejectedEvent") and .strategyId == $strategy) | 1' "${audits[@]}" | count_records)"
    [ "$decisions" -eq 2 ] || fail "scenario $index did not produce exactly two rule decisions"
    [ "$links" -eq 2 ] || fail "scenario $index did not link both decisions to orders"
    [ "$accepted" -eq 2 ] || fail "scenario $index did not accept exactly entry and exit"
    [ "$filled" -eq 2 ] || fail "scenario $index did not fill exactly entry and exit"
    [ "$accounted" -eq 2 ] || fail "scenario $index did not account exactly two fills"
    [ "$rejected" -eq 0 ] || fail "scenario $index retained a rejected order"
    for timeframe_ms in 60000 300000; do
        jq -e --arg symbol "${expected_symbols[$index]}" --argjson timeframeMs "$timeframe_ms" '
            select(.eventType == "com.qkt.events.WarmupTickEvent" and .symbol == $symbol and .sourceTimeframeMs == $timeframeMs)
        ' "${audits[@]}" >/dev/null || fail "scenario $index lacks warmup evidence for $timeframe_ms ms"
    done
    jq -e --arg symbol "${expected_symbols[$index]}" '
        select(.eventType == "com.qkt.events.TickEvent" and .symbol == $symbol)
    ' "${audits[@]}" >/dev/null || fail "scenario $index lacks live tick evidence"
    jq -e \
        --arg strategy "$strategy" \
        --arg symbol "${expected_symbols[$index]}" \
        --arg side "$expected_wire_side" '
        select(
            .eventType == "com.qkt.events.OrderEvent" and
            .strategyId == $strategy and .symbol == $symbol and
            .orderSchemaVersion == 1 and
            .order.orderType == "Bracket" and .order.qty == 0.01 and
            .order.side == $side and
            .order.entry.orderType == "Market" and
            .order.entry.side == $side and .order.entry.qty == 0.01 and
            .order.stopLossAst.type == "By" and .order.stopLossAst.distance.type == "NumLit" and
            .order.stopLossAst.distance.value == 0.0030 and
            .order.takeProfitAst.type == "By" and .order.takeProfitAst.distance.type == "NumLit" and
            .order.takeProfitAst.distance.value == 0.0060
        )
    ' "${audits[@]}" >/dev/null || fail "scenario $index lacks canonical bounded bracket order evidence"
    has_live_timeframe_evidence "$index" || fail "scenario $index lacks matched live M1/M5 stream and strategy evaluation evidence"

    daemon_log="${scenarios[$index]}/logs/container-daemon.log"
    raw_entry_traces="$(awk '/bounded indicator entry side=/ {count++} END {print count + 0}' "$daemon_log")"
    raw_exit_traces="$(awk '/bounded indicator exit signed_qty=/ {count++} END {print count + 0}' "$daemon_log")"
    [ "$raw_entry_traces" -eq 1 ] || fail "scenario $index did not retain exactly one indicator-entry trace"
    [ "$raw_exit_traces" -eq 1 ] || fail "scenario $index did not retain exactly one indicator-exit trace"
    extract_indicator_entry_trace "$daemon_log" > "${evidences[$index]}/indicator-entry-trace.tsv"
    extract_indicator_exit_trace "$daemon_log" > "${evidences[$index]}/indicator-exit-trace.tsv"
    [ "$(count_records < "${evidences[$index]}/indicator-entry-trace.tsv")" -eq 1 ] ||
        fail "scenario $index indicator-entry trace is not parseable"
    [ "$(count_records < "${evidences[$index]}/indicator-exit-trace.tsv")" -eq 1 ] ||
        fail "scenario $index indicator-exit trace is not parseable"

    order_posts="$(jq -r 'select(.method == "POST" and .path == "/order") | 1' "${transports[@]}" | count_records)"
    protection_posts="$(jq -r 'select(.method == "POST" and .path == "/modify_sl_tp") | 1' "${transports[@]}" | count_records)"
    close_posts="$(jq -r 'select(.method == "POST" and .path == "/close_position") | 1' "${transports[@]}" | count_records)"
    mutation_posts="$(jq -r 'select(.method == "POST" and (.path == "/order" or .path == "/modify_sl_tp" or .path == "/close_position" or .path == "/position_close_partial" or .path == "/cancel_order")) | 1' "${transports[@]}" | count_records)"
    failed_mutations="$(jq -r '
        def mutation:
            .method == "POST" and
            (.path == "/order" or .path == "/modify_sl_tp" or .path == "/close_position" or
                .path == "/position_close_partial" or .path == "/cancel_order");
        def mt5_success:
            (.responseBody | fromjson? | .result.retcode) as $retcode |
            $retcode == 10008 or $retcode == 10009 or $retcode == 10010;
        select(mutation and (.responseCode < 200 or .responseCode >= 300 or .error != null or (mt5_success | not))) | 1
    ' "${transports[@]}" | count_records)"
    [ "$order_posts" -eq 1 ] || fail "scenario $index did not issue exactly one entry order"
    [ "$protection_posts" -eq 1 ] || fail "scenario $index did not issue exactly one protection update"
    [ "$close_posts" -eq 1 ] || fail "scenario $index did not issue exactly one strategy close"
    [ "$mutation_posts" -eq 3 ] || fail "scenario $index emitted an unexpected venue mutation"
    [ "$failed_mutations" -eq 0 ] || fail "scenario $index retained a failed venue mutation"
    jq -e \
        --argjson magic "${magics[$index]}" \
        --arg symbol "${venue_symbols[$index]}" \
        --argjson ticket "${tickets[$index]}" '
            def request: .requestBody | fromjson;
            select(
                (.path == "/order" and request.magic == $magic and request.symbol == $symbol) or
                (.path == "/modify_sl_tp" and (request.position | tostring) == ($ticket | tostring)) or
                (.path == "/close_position" and (request.position.ticket | tostring) == ($ticket | tostring))
            )
    ' "${transports[@]}" | jq -s -e 'length == 3' >/dev/null ||
        fail "scenario $index mutation correlation does not match its magic, symbol, and ticket"
    mapfile -t canonical_stops < <(jq -r --arg strategy "$strategy" '
        select(.eventType == "com.qkt.events.OrderEvent" and .strategyId == $strategy and .order.orderType == "Bracket") |
        .order.stopLoss.price
    ' "${audits[@]}")
    mapfile -t canonical_targets < <(jq -r --arg strategy "$strategy" '
        select(.eventType == "com.qkt.events.OrderEvent" and .strategyId == $strategy and .order.orderType == "Bracket") |
        .order.takeProfit
    ' "${audits[@]}")
    [ "${#canonical_stops[@]}" -eq 1 ] && [ "${#canonical_targets[@]}" -eq 1 ] ||
        fail "scenario $index canonical bracket evidence is not unique"
    point="$(jq -er '.point' "$output/evidence/symbol-$index.json")"
    read -r intent_anchor entry_anchor_drift_points < <(
        awk \
            -v side="$expected_wire_side" \
            -v stop="${canonical_stops[0]}" \
            -v target="${canonical_targets[0]}" \
            -v entry="${entry_prices[$index]}" \
            -v point="$point" '
            function abs(value) { return value < 0 ? -value : value }
            BEGIN {
                if (side == "BUY") {
                    stopAnchor = stop + 0.0030
                    targetAnchor = target - 0.0060
                } else {
                    stopAnchor = stop - 0.0030
                    targetAnchor = target + 0.0060
                }
                if (abs(stopAnchor - targetAnchor) > point) exit 1
                driftPoints = (entry - stopAnchor) / point
                if (abs(driftPoints) > 20.000001) exit 1
                printf "%.8f %.8f\n", stopAnchor, driftPoints
            }
        '
    ) || fail "scenario $index entry drift exceeds the reviewed 20-point bound"
    jq -e \
        --arg strategy "$strategy" \
        --arg side "$expected_wire_side" \
        --argjson ticket "${tickets[$index]}" \
        --arg entryPrice "${entry_prices[$index]}" \
        --arg point "$point" '
        select(
            .eventType == "com.qkt.events.BrokerEvent.OrderFilled" and
            .strategyId == $strategy and .fill.side == $side and
            (.fill.brokerOrderId | tonumber) == $ticket and
            (((.fill.price | tonumber) - ($entryPrice | tonumber)) | fabs) <= ($point | tonumber)
        )
    ' "${audits[@]}" >/dev/null || fail "scenario $index entry fill does not match its venue position"
    jq -s -e \
        --argjson magic "${magics[$index]}" \
        --arg symbol "${venue_symbols[$index]}" \
        --arg side "$expected_wire_side" \
        --arg stopLoss "${canonical_stops[0]}" \
        --arg takeProfit "${canonical_targets[0]}" \
        --arg entryPrice "${entry_prices[$index]}" \
        --argjson ticket "${tickets[$index]}" \
        --arg point "$point" '
            [.[] | select(.method == "POST" and .path == "/order") |
                {request:(.requestBody | fromjson), response:(.responseBody | fromjson)}] as $placements |
            $placements[0] as $placement |
            ($point | tonumber) as $tolerance |
            ($placements | length) == 1 and
            $placement.request.magic == $magic and
            $placement.request.symbol == $symbol and
            $placement.request.type == $side and
            $placement.request.volume == 0.01 and
            $placement.request.deviation == 20 and
            ((($placement.request.sl - ($stopLoss | tonumber)) | fabs) <= $tolerance) and
            ((($placement.request.tp - ($takeProfit | tonumber)) | fabs) <= $tolerance) and
            ((($placement.response.result.price - ($entryPrice | tonumber)) | fabs) <= $tolerance) and
            ($placement.response.result.order == $ticket or $placement.response.result.deal == $ticket) and
            ($placement.response.result.retcode == 10008 or
                $placement.response.result.retcode == 10009 or
                $placement.response.result.retcode == 10010)
        ' "${transports[@]}" >/dev/null ||
        fail "scenario $index initial venue bracket does not match canonical order evidence"
    jq -s -e \
        --arg entryPrice "${entry_prices[$index]}" \
        --arg point "$point" \
        --argjson positionType "${position_types[$index]}" '
            [.[] | select(.method == "POST" and .path == "/modify_sl_tp") | (.requestBody | fromjson)] as $updates |
            ($entryPrice | tonumber) as $entry |
            ($point | tonumber) as $tolerance |
            $updates[0] as $update |
            ($updates | length) == 1 and
            (
                if $positionType == 0 then
                    (((($entry - $update.sl) - 0.0030) | fabs) <= $tolerance) and
                    (((($update.tp - $entry) - 0.0060) | fabs) <= $tolerance)
                else
                    (((($update.sl - $entry) - 0.0030) | fabs) <= $tolerance) and
                    (((($entry - $update.tp) - 0.0060) | fabs) <= $tolerance)
                end
            )
        ' "${transports[@]}" >/dev/null ||
        fail "scenario $index protection update was not anchored to the venue fill"
    foreign_magic_reads="$(jq -r --arg magic "${magics[$index]}" '
        select(
            (.path | test("^/(orders|get_positions)[?]magic=")) and
            .path != ("/orders?magic=" + $magic) and .path != ("/get_positions?magic=" + $magic)
        ) | 1
    ' "${transports[@]}" | count_records)"
    [ "$foreign_magic_reads" -eq 0 ] || fail "scenario $index transport crossed magic ownership"

    golden_zip="${evidences[$index]}/golden.zip"
    golden_manifest="${evidences[$index]}/golden-manifest.json"
    "$cli" golden capture --session "$strategy" --state-dir "${states[$index]}" --out "$golden_zip" \
        > "${evidences[$index]}/golden-capture.log"
    unzip -p "$golden_zip" manifest.json > "$golden_manifest"
    jq -e --arg strategy "$strategy" --arg commit "$qkt_commit" '
        .schemaVersion == 2 and .kind == "MT5_GOLDEN_CAPTURE" and .session == $strategy and
        (.captureGitSha as $capture | ($commit | startswith($capture))) and
        .counts.ticks > 0 and .counts.warmupTicks > 0 and
        .counts.candles > 0 and .counts.streamCandles > 0 and
        .counts.fills == 2 and .counts.linkedPlacements == 1 and .counts.mutations == 3
    ' "$golden_manifest" >/dev/null || fail "scenario $index golden capture is incomplete"
    while IFS=$'\t' read -r path expected_sha; do
        actual_sha="$(unzip -p "$golden_zip" "$path" | sha256sum | awk '{print $1}')"
        [ "$actual_sha" = "$expected_sha" ] || fail "scenario $index golden entry hash mismatch: $path"
    done < <(jq -r '.entries[] | [.path,.sha256] | @tsv' "$golden_manifest")

    side=BUY
    [ "${position_types[$index]}" -eq 0 ] || side=SELL
    [ "$(awk -F '\t' 'NR == 1 {print $1}' "${evidences[$index]}/indicator-entry-trace.tsv")" = "$side" ] ||
        fail "scenario $index indicator trace side differs from the venue position"
    jq -n \
        --arg scenarioId "${scenario_ids[$index]}" \
        --arg strategy "$strategy" \
        --arg symbol "${expected_symbols[$index]}" \
        --arg side "$side" \
        --argjson magic "${magics[$index]}" \
        --argjson ticket "${tickets[$index]}" \
        --arg intentAnchor "$intent_anchor" \
        --arg entryAnchorDriftPoints "$entry_anchor_drift_points" \
        --argjson entryTimeMs "$(if [ "$index" -eq 0 ]; then printf '%s' "$entry_a_ms"; else printf '%s' "$entry_b_ms"; fi)" \
        --argjson exitTimeMs "$(if [ "$index" -eq 0 ]; then printf '%s' "$exit_a_ms"; else printf '%s' "$exit_b_ms"; fi)" \
        --argjson decisions "$decisions" --argjson links "$links" \
        --argjson accepted "$accepted" --argjson filled "$filled" --argjson accounted "$accounted" \
        --argjson mutations "$mutation_posts" \
        --arg goldenSha256 "$(sha256sum "$golden_zip" | awk '{print $1}')" '
        {
          schema:"qkt-live-container-round-trip-case-v1",status:"passed",
          scenarioId:$scenarioId,strategy:$strategy,symbol:$symbol,side:$side,magic:$magic,
          positionTicket:$ticket,lots:"0.01",entryTimeMs:$entryTimeMs,exitTimeMs:$exitTimeMs,
          bracket:{stopDistance:"0.0030",takeProfitDistance:"0.0060",maximumEntryAnchorDriftPoints:20,
            intentAnchor:$intentAnchor,entryAnchorDriftPoints:$entryAnchorDriftPoints,symbolPointToleranceVerified:true},
          strategyOwnedClose:true,finalPositions:0,finalOrders:0,
          timeframeEvidence:{m1StreamAndEvaluation:true,m5StreamAndEvaluation:true},
          traces:{indicatorEntry:true,indicatorExit:true},
          audit:{ruleDecisions:$decisions,decisionOrderLinks:$links,accepted:$accepted,filled:$filled,accounted:$accounted,rejected:0},
          transport:{orderPosts:1,protectionPosts:1,closePosts:1,mutations:$mutations},
          golden:{fills:2,linkedPlacements:1,mutations:3,sha256:$goldenSha256}
        }
    ' > "${evidences[$index]}/result.json"
    jq --argjson ticket "${tickets[$index]}" '
        .ownedPositionTickets = [$ticket] | .ownedOrderTickets = [] | .status = "verified_flat"
    ' "${scenarios[$index]}/cleanup.json" > "${scenarios[$index]}/cleanup.json.tmp"
    mv "${scenarios[$index]}/cleanup.json.tmp" "${scenarios[$index]}/cleanup.json"
done

for index in 0 1; do
    token_path="${states[$index]}/control.token"
    if [ -f "$token_path" ]; then
        control_tokens+=("$(<"$token_path")")
        unlink "$token_path"
    fi
    [ ! -e "${states[$index]}/daemon.pid" ] || unlink "${states[$index]}/daemon.pid"
done

for root in "$output" "${scenarios[0]}" "${scenarios[1]}"; do
    if printf '%s' "$QKT_BROKER_API_KEY" | rg --text --fixed-strings --quiet -f - "$root"; then
        fail "broker credential was persisted in retained artifacts"
    fi
    for token in "${control_tokens[@]}"; do
        if [ -n "$token" ] && printf '%s\n' "$token" | rg --text --fixed-strings --quiet -f - "$root"; then
            fail "daemon control token was persisted in retained artifacts"
        fi
    done
done

finished_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
    --arg finishedAt "$finished_at" \
    --arg qktCommit "$qkt_commit" \
    --arg hostVersion "$host_version" \
    --arg image "$image" \
    --arg imageVersion "$image_version" \
    --arg gatewayVersion "$(jq -er '.version' "$output/evidence/gateway-health.json")" \
    --argjson deployStartedMs "$deploy_started_ms" \
    --argjson deployLaunchSkewMs "$deploy_launch_skew_ms" \
    --argjson deploySkewMs "$deploy_skew_ms" \
    --argjson overlapStartMs "$latest_entry_ms" \
    --argjson overlapEndMs "$earliest_exit_ms" \
    --arg balanceDelta "$balance_delta" \
    --arg dealNet "$deal_net" \
    --slurpfile caseA "${evidences[0]}/result.json" \
    --slurpfile caseB "${evidences[1]}/result.json" '
    {
      schema:"qkt-live-multi-container-round-trip-v1",status:"passed",finishedAt:$finishedAt,
      qktCommit:$qktCommit,hostVersion:$hostVersion,image:$image,imageVersion:$imageVersion,
      gatewayVersion:$gatewayVersion,containers:2,symbols:["EXNESS:EURUSD","EXNESS:GBPUSD"],
      timeframes:["1m","5m"],maximumAggregateLots:"0.02",
      synchronizedDeployment:{startedAtMs:$deployStartedMs,launchSkewMs:$deployLaunchSkewMs,completionSkewMs:$deploySkewMs},
      overlap:{verified:true,startMs:$overlapStartMs,endMs:$overlapEndMs},
      ownershipVerified:true,strategyOwnedCloseVerified:true,accountReconciled:true,
      bracketDistancesVerified:true,timeframePathsVerified:true,indicatorTracesVerified:true,
      finalPositions:0,finalOrders:0,balanceDelta:$balanceDelta,ownedDealNet:$dealNet,
      dockerResourceRestrictionsVerifiedAbsent:true,cases:[$caseA[0],$caseB[0]]
    }
' > "$output/evidence/result.json"

for scenario in "${scenarios[@]}"; do
    (
        cd "$scenario"
        find . -type f ! -path './RUN-SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > RUN-SHA256SUMS
        sha256sum --check RUN-SHA256SUMS >/dev/null
    )
done
(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)

cleanup
trap - EXIT
printf 'passed %s\n' "$output/evidence/result.json"
