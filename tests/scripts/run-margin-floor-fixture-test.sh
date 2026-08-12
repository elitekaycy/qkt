#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

prepare="$repo_root/scripts/live-validation/prepare-margin-floor-fixture.sh"
runner="$repo_root/scripts/live-validation/run-margin-floor-fixture.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$runner"
bash "$runner" --help | grep -F 'controlled localhost MT5 margin-floor fixture' >/dev/null
test -x "$cli"

fixture="$tmp/fixture"
bash "$prepare" \
    --output "$fixture" \
    --id marginfloor_runner_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 919101 >/dev/null

bash "$runner" --fixture "$fixture" --cli "$cli" --verify-only > "$tmp/verified.out"
grep -F 'verified ' "$tmp/verified.out" >/dev/null

tampered="$tmp/tampered"
cp -a "$fixture" "$tampered"
printf '\n# tampered\n' >> "$tampered/probe/qkt.config.template.yaml"
if bash "$runner" --fixture "$tampered" --cli "$cli" --verify-only > "$tmp/tampered.out" 2>&1; then
    echo 'expected checksum rejection' >&2
    exit 1
fi
grep -F 'prepared fixture checksum verification failed' "$tmp/tampered.out" >/dev/null

remote="$tmp/remote"
cp -a "$fixture" "$remote"
jq '.gatewayUrl = "https://remote.example"' "$remote/suite.json" > "$remote/suite.json.tmp"
mv "$remote/suite.json.tmp" "$remote/suite.json"
(
    cd "$remote"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --fixture "$remote" --cli "$cli" --verify-only > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'fixture gateway must be a localhost endpoint' "$tmp/remote.out" >/dev/null

grep -F 'openerCreatesLiveExposure:true' "$runner" >/dev/null
grep -F 'probeRejectsBeforeTransport:true' "$runner" >/dev/null
grep -F 'probeAllowedAfterHeadroomRecovery:true' "$runner" >/dev/null
grep -F 'dynamicMarginFloorPct:true' "$runner" >/dev/null
grep -F 'QKT_LIVE_DEMO_ORDER_APPROVAL must equal LOCALHOST_DEMO_ONLY' "$runner" >/dev/null
grep -F 'repository must be clean' "$runner" >/dev/null
grep -F 'Docker image is not built from' "$runner" >/dev/null
grep -F -- '--network host' "$runner" >/dev/null
grep -F 'docker exec "$opener_container" qkt deploy "/work/strategies/$opener_strategy.qkt"' "$runner" >/dev/null
grep -F 'docker exec "$probe_container" qkt deploy "/work/strategies/$probe_strategy.qkt"' "$runner" >/dev/null
grep -F 'QKT_LATENCY_TRACKING=1' "$runner" >/dev/null
grep -F 'must be unset; this run does not restrict or override the JVM' "$runner" >/dev/null
grep -F 'margin_floor_pct: "__QKT_DYNAMIC_MARGIN_FLOOR_PCT__"' "$runner" >/dev/null
grep -F 'ceil(observed_margin_level_pct) + 1000' "$runner" >/dev/null
grep -F 'gsub("\\\\u2014"; "—")' "$runner" >/dev/null
grep -F "jq -r -s '" "$runner" >/dev/null
grep -F '"$gateway_url/close_position"' "$runner" >/dev/null
grep -F '"eventType":"com.qkt.events.RiskRejectedEvent"' "$runner" >/dev/null
grep -F 'probe passed a margin-floor-blocked intent into broker submission' "$runner" >/dev/null
grep -F 'probe issued a mutating gateway request before margin-floor rejection' "$runner" >/dev/null
grep -F 'probe did not open after margin headroom recovered' "$runner" >/dev/null
grep -F 'probe could not verify recovered position flatten' "$runner" >/dev/null
grep -F 'account-after-opener-flat.raw.json' "$runner" >/dev/null
grep -F 'bot history --broker exness --since "$run_started_ms"' "$runner" >/dev/null
grep -F 'venue history did not expose opener and recovered probe round trips' "$runner" >/dev/null
grep -F 'preRecoveryFinanciallyReadOnly:true' "$runner" >/dev/null
grep -F 'recoveredOrderAccepted:true' "$runner" >/dev/null
grep -F 'marginFloorPassed:true' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'margin-floor runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --quiet -- '--env[ =]QKT_BROKER_API_KEY|-e[ =]QKT_BROKER_API_KEY' "$runner"; then
    echo 'margin-floor runner stores the broker credential in Docker configuration' >&2
    exit 1
fi
