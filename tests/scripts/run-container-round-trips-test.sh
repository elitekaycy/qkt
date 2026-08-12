#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

runner="$repo_root/scripts/live-validation/run-container-round-trips.sh"
prepare="$repo_root/scripts/live-validation/prepare-scenario.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$runner"
bash "$runner" --help | grep -F 'two prepared, isolated 0.01-lot indicator round trips' >/dev/null
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
prepare_case "$case_a" validation_container_a 436804390 917301 EURUSD
prepare_case "$case_b" validation_container_b 436804390 917302 GBPUSD

bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$case_b" \
    --cli "$cli" \
    --verify-only > "$tmp/verified.out"
grep -F 'verified ' "$tmp/verified.out" >/dev/null

xau_case="$tmp/xau"
prepare_case "$xau_case" validation_container_xau 436804390 917304 XAUUSD
bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$xau_case" \
    --cli "$cli" \
    --verify-only > "$tmp/verified-xau.out"
grep -F 'verified ' "$tmp/verified-xau.out" >/dev/null

if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$case_a" \
    --cli "$cli" \
    --verify-only > "$tmp/same-dir.out" 2>&1; then
    echo 'expected duplicate scenario-directory rejection' >&2
    exit 1
fi
grep -F 'scenario directories must be distinct' "$tmp/same-dir.out" >/dev/null

same_magic="$tmp/same-magic"
prepare_case "$same_magic" validation_same_magic 436804390 917301 GBPUSD
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$same_magic" \
    --cli "$cli" \
    --verify-only > "$tmp/same-magic.out" 2>&1; then
    echo 'expected duplicate magic rejection' >&2
    exit 1
fi
grep -F 'broker magics must be distinct' "$tmp/same-magic.out" >/dev/null

wrong_account="$tmp/wrong-account"
prepare_case "$wrong_account" validation_wrong_account 436804391 917303 GBPUSD
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$wrong_account" \
    --cli "$cli" \
    --verify-only > "$tmp/wrong-account.out" 2>&1; then
    echo 'expected account identity mismatch rejection' >&2
    exit 1
fi
grep -F 'same account login' "$tmp/wrong-account.out" >/dev/null

remote="$tmp/remote"
cp -a "$case_b" "$remote"
jq '.safety.gatewayUrl = "https://remote.example"' "$remote/expected.json" > "$remote/expected.json.tmp"
mv "$remote/expected.json.tmp" "$remote/expected.json"
jq '.gatewayUrl = "https://remote.example"' "$remote/scenario.json" > "$remote/scenario.json.tmp"
mv "$remote/scenario.json.tmp" "$remote/scenario.json"
(
    cd "$remote"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$remote" \
    --cli "$cli" \
    --verify-only > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -E 'expected contract|localhost endpoint' "$tmp/remote.out" >/dev/null

tampered="$tmp/tampered"
cp -a "$case_b" "$tampered"
printf '\n# tampered\n' >> "$tampered/strategies/armed/validation_container_b_market_bracket.qkt"
if bash "$runner" \
    --scenario-a "$case_a" \
    --scenario-b "$tampered" \
    --cli "$cli" \
    --verify-only > "$tmp/tampered.out" 2>&1; then
    echo 'expected checksum rejection' >&2
    exit 1
fi
grep -F 'prepared artifact checksum verification failed' "$tmp/tampered.out" >/dev/null

grep -F 'I_UNDERSTAND_TWO_CONCURRENT_DEMO_ORDERS_0.01' "$runner" >/dev/null
grep -F 'QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY' "$runner" >/dev/null
grep -F '.hedging == true' "$runner" >/dev/null
grep -F 'repository must be clean' "$runner" >/dev/null
grep -F 'Docker image is not built from' "$runner" >/dev/null
grep -F 'source "$repo_root/scripts/live-validation/lib/catalog-startup-window.sh"' "$runner" >/dev/null
grep -F 'wait_for_startup_window' "$runner" >/dev/null
grep -F 'qkt-live-startup-window-v1' "$runner" >/dev/null
grep -F 'tickInvalid: (' "$runner" >/dev/null
grep -F 'missing-or-zero-timestamp' "$runner" >/dev/null
grep -F 'missing-or-zero-price' "$runner" >/dev/null
grep -F 'status:"invalid"' "$runner" >/dev/null
grep -F 'validObservations' "$runner" >/dev/null
grep -F 'broker tick clock did not enter the bounded startup window' "$runner" >/dev/null
grep -F -- '--network host' "$runner" >/dev/null
grep -F 'QKT_LATENCY_TRACKING=1' "$runner" >/dev/null
grep -F 'QKT_STATE_DIR="$2"' "$runner" >/dev/null
grep -F '.memoryBytes == 0 and .nanoCpus == 0 and .cpuQuota == 0' "$runner" >/dev/null
grep -F '(.pidsLimit == null or .pidsLimit == 0) and .cpusetCpus == ""' "$runner" >/dev/null
grep -F 'qkt-live-container-resources-v1' "$runner" >/dev/null
grep -F 'credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false' "$runner" >/dev/null
grep -F 'must be unset; this run does not restrict the JVM' "$runner" >/dev/null
grep -F 'image config restricts or overrides the JVM' "$runner" >/dev/null
grep -F 'trap cleanup EXIT' "$runner" >/dev/null
grep -F 'get_positions?magic=' "$runner" >/dev/null
grep -F 'write_tick_freshness_gate "$output/evidence"' "$runner" >/dev/null
grep -F '/symbol_info_tick/$symbol' "$runner" >/dev/null
grep -F 'qkt-live-tick-freshness-gate-v1' "$runner" >/dev/null
grep -F 'invalidReason' "$runner" >/dev/null
grep -F 'future-tick-clock-skew' "$runner" >/dev/null
grep -F 'stale-tick' "$runner" >/dev/null
grep -F 'gateway tick freshness gate failed before arming live orders' "$runner" >/dev/null
grep -F 'expecteds=("" "")' "$runner" >/dev/null
grep -F 'expecteds[$index]="$scenario/expected.json"' "$runner" >/dev/null
grep -F 'bot close "${expected_symbols[$index]}" --ticket' "$runner" >/dev/null
grep -F 'bot cancel "${expected_symbols[$index]}" --order' "$runner" >/dev/null
grep -F '[ "$order_posts" -eq 1 ]' "$runner" >/dev/null
grep -F '[ "$protection_posts" -eq 1 ]' "$runner" >/dev/null
grep -F '[ "$close_posts" -eq 1 ]' "$runner" >/dev/null
grep -F '[ "$mutation_posts" -eq 3 ]' "$runner" >/dev/null
grep -F '[ "$decisions" -eq 2 ]' "$runner" >/dev/null
grep -F '[ "$links" -eq 2 ]' "$runner" >/dev/null
grep -F '[ "$accepted" -eq 2 ]' "$runner" >/dev/null
grep -F '[ "$filled" -eq 2 ]' "$runner" >/dev/null
grep -F '[ "$accounted" -eq 2 ]' "$runner" >/dev/null
grep -F '[ "$rejected" -eq 0 ]' "$runner" >/dev/null
grep -F 'RiskRejectedEvent|BrokerEvent.OrderRejected' "$runner" >/dev/null
grep -F '[ "$deploy_launch_skew_ms" -le 1000 ]' "$runner" >/dev/null
grep -F 'timeout_seconds=360' "$runner" >/dev/null
grep -F 'must be in 330..600' "$runner" >/dev/null
grep -F '.armedScenario.maximumEntryAnchorDriftPoints == 80' "$runner" >/dev/null
grep -F 'entry drift exceeds the reviewed $maximum_entry_anchor_drift_points-point bound' "$runner" >/dev/null
grep -F 'EXNESS:XAUUSD:XAUUSDm:100' "$runner" >/dev/null
grep -F 'symbol contract is not in the reviewed live set' "$runner" >/dev/null
grep -F 'expected_contract_sizes[$index]="$expected_contract_size"' "$runner" >/dev/null
grep -F 'stop_distances[$index]="$stop_distance"' "$runner" >/dev/null
grep -F 'take_profit_distances[$index]="$take_profit_distance"' "$runner" >/dev/null
grep -F '$stopDistanceNumber + (($point | tonumber) * $maximumEntryAnchorDriftPoints)' "$runner" >/dev/null
grep -F 'takeProfitDistance:$takeProfitDistance' "$runner" >/dev/null
grep -F '<= ($point | tonumber)' "$runner" >/dev/null
grep -F 'protection_seen=(false false)' "$runner" >/dev/null
grep -F 'position-fill-anchored-protection.json' "$runner" >/dev/null
grep -F 'did not expose fill-anchored bracket distances before closing' "$runner" >/dev/null
grep -F 'lacks canonical bounded bracket order evidence' "$runner" >/dev/null
grep -F '.orderSchemaVersion == 1' "$runner" >/dev/null
grep -F '.responseBody | fromjson? | .result.retcode' "$runner" >/dev/null
grep -F '$placement.request.deviation == 20' "$runner" >/dev/null
grep -F 'initial venue bracket does not match canonical order evidence' "$runner" >/dev/null
grep -F 'protection update was not anchored to the venue fill' "$runner" >/dev/null
grep -F 'has_live_timeframe_evidence' "$runner" >/dev/null
grep -F '[["asset1", "1m"], ["asset5", "5m"]]' "$runner" >/dev/null
grep -F 'StreamCandleEvent' "$runner" >/dev/null
grep -F 'StrategyCandleEvaluatedEvent' "$runner" >/dev/null
grep -F 'positions-post-flat-latest.json' "$runner" >/dev/null
grep -F 'indicator-entry-trace.tsv' "$runner" >/dev/null
grep -F 'indicator-exit-trace.tsv' "$runner" >/dev/null
grep -F '[ "$raw_entry_traces" -eq 1 ]' "$runner" >/dev/null
grep -F '[ "$raw_exit_traces" -eq 1 ]' "$runner" >/dev/null
grep -F 'side = score = m1fast = m1slow = m5fast = m5slow = closing' "$runner" >/dev/null
grep -F 'secondary_fast' "$runner" >/dev/null
grep -F 'secondary_slow' "$runner" >/dev/null
grep -F 'indicator trace side differs from the venue position' "$runner" >/dev/null
grep -F 'symbolPointToleranceVerified:true' "$runner" >/dev/null
grep -F 'm5StreamAndEvaluation:true' "$runner" >/dev/null
grep -F 'indicatorTracesVerified:true' "$runner" >/dev/null
grep -F 'unexpected runtime error' "$runner" >/dev/null
grep -F 'operationalWarnings:{staleMarketDataGates:' "$runner" >/dev/null
grep -F '[ "$latest_entry_ms" -lt "$earliest_exit_ms" ]' "$runner" >/dev/null
grep -F '[ "$balance_delta" = "$deal_net" ]' "$runner" >/dev/null
grep -F '.counts.fills == 2' "$runner" >/dev/null
grep -F '.counts.linkedPlacements == 1' "$runner" >/dev/null
grep -F '.counts.mutations == 3' "$runner" >/dev/null
grep -F 'strategyOwnedClose:true' "$runner" >/dev/null
grep -F 'schema:"qkt-live-multi-container-round-trip-v1"' "$runner" >/dev/null
grep -F 'dockerResourceRestrictionsVerifiedAbsent:true' "$runner" >/dev/null
grep -F 'caseArtifactManifests:[' "$runner" >/dev/null
grep -F 'sha256sum --check SHA256SUMS' "$runner" >/dev/null
grep -F 'publicationSafe:false,containsPrivateAccountMetadata:true' "$runner" >/dev/null
grep -F 'broker credential was persisted in retained artifacts' "$runner" >/dev/null
grep -F 'daemon control token was persisted in retained artifacts' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'container round-trip runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --quiet -- '--env[ =]QKT_BROKER_API_KEY|-e[ =]QKT_BROKER_API_KEY' "$runner"; then
    echo 'container round-trip runner stores the broker credential in Docker configuration' >&2
    exit 1
fi
if rg --quiet 'kill .*--flatten' "$runner"; then
    echo 'container round-trip runner uses operator flatten on the success path' >&2
    exit 1
fi
