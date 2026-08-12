#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

runner="$repo_root/scripts/live-validation/run-shared-account-insights-round-trips.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$runner"
bash "$runner" --help | grep -F 'same-account live round-trip runner with a local' >/dev/null
test -x "$cli"

prepare_case() {
    local output="$1"
    local id="$2"
    local login="$3"
    local magic="$4"
    local symbol="$5"
    bash "$prepare" \
        --output "$output" \
        --id "$id" \
        --gateway-url http://127.0.0.1:5001 \
        --expected-login "$login" \
        --expected-server Exness-MT5Trial9 \
        --expected-balance 100000.22 \
        --expected-leverage 500 \
        --magic "$magic" \
        --symbol "$symbol" >/dev/null
}

case_a="$tmp/case-a"
case_b="$tmp/case-b"
prepare_case "$case_a" shared_insights_a 436804390 927301 EURUSD
prepare_case "$case_b" shared_insights_b 436804390 927302 GBPUSD

bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$case_b" \
    --insights-image qkt-insights:validation-test \
    --cli "$cli" \
    --verify-only > "$tmp/verified.out"
grep -F 'verified ' "$tmp/verified.out" >/dev/null
grep -F 'qkt-insights:validation-test' "$tmp/verified.out" >/dev/null

same_magic="$tmp/same-magic"
prepare_case "$same_magic" shared_insights_same_magic 436804390 927301 GBPUSD
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$same_magic" \
    --insights-image qkt-insights:validation-test \
    --cli "$cli" \
    --verify-only > "$tmp/same-magic.out" 2>&1; then
    echo 'expected duplicate magic rejection' >&2
    exit 1
fi
grep -F 'broker magics must be distinct' "$tmp/same-magic.out" >/dev/null

wrong_account="$tmp/wrong-account"
prepare_case "$wrong_account" shared_insights_wrong_account 436804391 927303 GBPUSD
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$wrong_account" \
    --insights-image qkt-insights:validation-test \
    --cli "$cli" \
    --verify-only > "$tmp/wrong-account.out" 2>&1; then
    echo 'expected same-account rejection' >&2
    exit 1
fi
grep -F 'same account login' "$tmp/wrong-account.out" >/dev/null

grep -F -- '--insights-image' "$runner" >/dev/null
grep -F 'Wraps the bounded two-container same-account live round-trip runner with a local' "$runner" >/dev/null
grep -F 'QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY' "$runner" >/dev/null
grep -F 'docker image inspect "$insights_image"' "$runner" >/dev/null
grep -F 'token: "\${QKT_INSIGHTS_TOKEN}"' "$runner" >/dev/null
grep -F 'docker run -d --name "$collector" --network host' "$runner" >/dev/null
grep -F 'Insights did not become healthy' "$runner" >/dev/null
grep -F 'Insights image rejected the causal execution contract' "$runner" >/dev/null
grep -F 'Insights does not fold lifecycle events after a producer sequence restart' "$runner" >/dev/null
grep -F 'Insights does not preserve known position attribution across sibling state polls' "$runner" >/dev/null
grep -F 'Insights treats producer-local sequences as global delivery continuity' "$runner" >/dev/null
grep -F 'strip_top_level_block "$scenario/qkt.config.yaml" insights' "$runner" >/dev/null
grep -F "! -path './cleanup.json'" "$runner" >/dev/null
grep -F 'bash "$base_runner"' "$runner" >/dev/null
grep -F 'QKT_INSIGHTS_TOKEN' "$repo_root/scripts/live-validation/run-container-round-trips.sh" >/dev/null
grep -F "select count(*) from strategies where instance_id='\$instance' and strategy_id='\$strategy';" "$runner" >/dev/null
grep -F "state='FILLED'" "$runner" >/dev/null
grep -F "entry in ('IN','OUT')" "$runner" >/dev/null
grep -F "count(distinct instance_id)" "$runner" >/dev/null
grep -F "coalesce(strategy_id,'') != '\$strategy'" "$runner" >/dev/null
grep -F 'positions_current where instance_id=' "$runner" >/dev/null
grep -F "kind in ('gap','regression')" "$runner" >/dev/null
grep -F 'a runtime credential reached retained artifacts' "$runner" >/dev/null
grep -F 'qkt-live-shared-account-insights-round-trips-v1' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'shared-account insights runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi

printf 'run-shared-account-insights-round-trips-test: passed\n'
