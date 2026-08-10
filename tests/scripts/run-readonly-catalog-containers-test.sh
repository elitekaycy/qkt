#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

prepare="$repo_root/scripts/live-validation/prepare-readonly-catalog.sh"
runner="$repo_root/scripts/live-validation/run-readonly-catalog-containers.sh"
cli="$repo_root/build/install/qkt/bin/qkt"
verify_cli="$tmp/qkt-verify-fixture"

cat > "$verify_cli" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
test "${1:-}" = parse
printf 'ok\n'
EOF
chmod +x "$verify_cli"

bash -n "$runner"
bash "$runner" --help | grep -F 'four isolated, financially read-only QKT containers in parallel' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id wave1_runner \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 >/dev/null

bash "$runner" --suite "$suite" --verify-only --cli "$verify_cli" > "$tmp/verified.out"
grep -F 'verified ' "$tmp/verified.out" >/dev/null

tampered="$tmp/tampered"
cp -a "$suite" "$tampered"
printf '\n# tampered\n' >> "$tampered/cases/numeric-candle/strategies/control/wave1_runner_numeric_candle.qkt"
if bash "$runner" --suite "$tampered" --verify-only --cli "$verify_cli" > "$tmp/tampered.out" 2>&1; then
    echo 'expected checksum rejection' >&2
    exit 1
fi
grep -F 'prepared artifact checksum verification failed' "$tmp/tampered.out" >/dev/null

remote="$tmp/remote"
cp -a "$suite" "$remote"
jq '.gatewayUrl = "https://remote.example"' "$remote/suite.json" > "$remote/suite.tmp"
mv "$remote/suite.tmp" "$remote/suite.json"
(
    cd "$remote"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --suite "$remote" --verify-only --cli "$verify_cli" > "$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'suite gateway must be a localhost endpoint' "$tmp/remote.out" >/dev/null

financial="$tmp/financial"
cp -a "$suite" "$financial"
cat >> "$financial/cases/numeric-candle/strategies/control/wave1_runner_numeric_candle.qkt" <<'EOF'

    WHEN eur1.close > 0
    THEN BUY eur1 SIZING 0.01
EOF
(
    cd "$financial"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --suite "$financial" --verify-only --cli "$verify_cli" > "$tmp/financial.out" 2>&1; then
    echo 'expected financial DSL action rejection' >&2
    exit 1
fi
grep -F 'contains a financial DSL action' "$tmp/financial.out" >/dev/null

grep -F 'QKT_BROKER_API_KEY through process stdin' "$runner" >/dev/null
grep -F 'contains a financial DSL action' "$runner" >/dev/null
grep -F -- '--network host' "$runner" >/dev/null
grep -F 'QKT_LATENCY_TRACKING=1' "$runner" >/dev/null
grep -F 'parallel daemon launch skew exceeded 1500 ms' "$runner" >/dev/null
grep -F 'must be unset; this run does not restrict or override the JVM' "$runner" >/dev/null
grep -F 'image config restricts or overrides the JVM' "$runner" >/dev/null
grep -F '.HostConfig.Memory == 0' "$runner" >/dev/null
grep -F '.HostConfig.NanoCpus == 0' "$runner" >/dev/null
grep -F 'credentialStoredInConfig:false,jvmOverrideEnvironmentPresent:false' "$runner" >/dev/null
grep -F 'volume-requiring strategy unexpectedly deployed' "$runner" >/dev/null
grep -F 'does not supply volume|volume-bearing feed|VOLUME' "$runner" >/dev/null
grep -F 'WarmupTickEvent' "$runner" >/dev/null
grep -F 'configured $warmup_bars-bar warmup evidence' "$runner" >/dev/null
grep -F 'TickEvent' "$runner" >/dev/null
grep -F 'StreamCandleEvent' "$runner" >/dev/null
grep -F 'StrategyCandleEvaluatedEvent' "$runner" >/dev/null
grep -F 'matched constructed bar/evaluation evidence' "$runner" >/dev/null
grep -F 'did not emit readiness vector' "$runner" >/dev/null
grep -F 'emitted an order, fill, accounting, or rejection event' "$runner" >/dev/null
grep -F 'issued a mutating gateway request' "$runner" >/dev/null
grep -F 'venue deals occurred during read-only catalog run' "$runner" >/dev/null
grep -F 'gatewayMutations:0,orderEvents:0,fills:0' "$runner" >/dev/null
grep -F 'accountUnchanged:true,venueDealsDuringRun:0' "$runner" >/dev/null
grep -F 'bars:{warmupBars:true,readinessVectors:true,liveTicks:true,constructedBars:true,evaluationsJoined:true}' "$runner" >/dev/null
grep -F 'publicationSafe:false,containsPrivateAccountMetadata:true' "$runner" >/dev/null
grep -F 'broker credential was persisted in retained artifacts' "$runner" >/dev/null
grep -F 'daemon control token was persisted in retained artifacts' "$runner" >/dev/null
grep -F 'sha256sum --check SHA256SUMS' "$runner" >/dev/null

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$runner"; then
    echo 'catalog runner adds a JVM or Docker resource restriction' >&2
    exit 1
fi
if rg --quiet -- '--env[ =]QKT_BROKER_API_KEY|-e[ =]QKT_BROKER_API_KEY' "$runner"; then
    echo 'catalog runner stores the broker credential in Docker configuration' >&2
    exit 1
fi
if rg --quiet 'gateway_(post|put|patch|delete)|curl[^\n]*(--request|-X)[[:space:]]*(POST|PUT|PATCH|DELETE)' "$runner"; then
    echo 'catalog runner contains an explicit mutating gateway call' >&2
    exit 1
fi
