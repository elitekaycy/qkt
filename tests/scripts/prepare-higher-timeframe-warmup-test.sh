#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

prepare="$repo_root/scripts/live-validation/prepare-higher-timeframe-warmup.sh"
runner="$repo_root/scripts/live-validation/run-higher-timeframe-warmup.sh"
cli="${QKT_TEST_CLI:-$repo_root/build/install/qkt/bin/qkt}"

bash -n "$prepare"
bash -n "$runner"
bash "$prepare" --help | grep -F 'higher-timeframe live warmup probe' >/dev/null
bash "$runner" --help | grep -F 'higher-timeframe warmup probe' >/dev/null
test -x "$cli"

scenario="$tmp/scenario"
bash "$prepare" \
    --output "$scenario" \
    --id htf_fixture \
    --gateway-url http://127.0.0.1:5001/ \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --symbol XAUUSD >/dev/null

test -f "$scenario/qkt.config.yaml"
test -f "$scenario/expected.json"
test -f "$scenario/scenario.json"
test -f "$scenario/SHA256SUMS"
grep -F 'gateway_url: http://127.0.0.1:5001' "$scenario/qkt.config.yaml" >/dev/null
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$scenario/qkt.config.yaml" >/dev/null
grep -F 'tick_poll_interval_ms: 500' "$scenario/qkt.config.yaml" >/dev/null
grep -F 'poll_interval_ms: 5000' "$scenario/qkt.config.yaml" >/dev/null

jq -e '
    .schema == "qkt-live-higher-timeframe-warmup-expected-v1" and
    .symbol == "EXNESS:XAUUSD" and .venueSymbol == "XAUUSDm" and
    .financiallyReadOnly == true and
    [.probes[] | .timeframe + ":" + (.bars|tostring)] == [
      "15m:4","15m:96","15m:192",
      "1h:1","1h:24","1h:48",
      "4h:1","4h:6","4h:12"
    ] and
    ([.probes[] | .timeframeMs] | unique | sort) == [900000,3600000,14400000] and
    ([.probes[] | .warmupLabel] | unique | sort) == ["four-hours","one-day","one-hour","two-days"]
' "$scenario/expected.json" >/dev/null
jq -e '
    .schema == "qkt-live-higher-timeframe-warmup-scenario-v1" and
    .credentialsStored == false and .executionState == "prepared" and
    (.qktDirty | type) == "boolean"
' "$scenario/scenario.json" >/dev/null
(cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null)
bash "$runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null

if bash "$prepare" \
    --output "$tmp/remote" \
    --id htf_remote \
    --gateway-url https://example.test \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 10000 \
    --expected-leverage 500 >"$tmp/remote.out" 2>&1; then
    echo 'expected remote gateway rejection' >&2
    exit 1
fi
grep -F 'must be an explicit http://127.0.0.1:PORT endpoint' "$tmp/remote.out" >/dev/null

if bash "$prepare" \
    --output "$tmp/bad-symbol" \
    --id htf_bad_symbol \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 1 \
    --expected-server Demo \
    --expected-balance 10000 \
    --expected-leverage 500 \
    --symbol USDJPY >"$tmp/bad-symbol.out" 2>&1; then
    echo 'expected unsupported symbol rejection' >&2
    exit 1
fi
grep -F -- '--symbol must be one of: EURUSD, GBPUSD, XAUUSD' "$tmp/bad-symbol.out" >/dev/null

tampered="$tmp/tampered"
cp -a "$scenario" "$tampered"
jq '.probes[0].bars = 5' "$tampered/expected.json" > "$tampered/expected.tmp"
mv "$tampered/expected.tmp" "$tampered/expected.json"
(
    cd "$tampered"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)
if bash "$runner" --scenario "$tampered" --cli "$cli" --verify-only >"$tmp/tampered.out" 2>&1; then
    echo 'expected reviewed matrix rejection' >&2
    exit 1
fi
grep -F 'expected probes do not match the reviewed M15/H1/H4 matrix' "$tmp/tampered.out" >/dev/null

grep -F 'venueDealsDuringRun:0' "$runner" >/dev/null
grep -F 'daemon control artifacts were created during a read-only bot-bars probe' "$runner" >/dev/null
grep -F 'broker credential was persisted in retained artifacts' "$runner" >/dev/null
grep -F 'warmupPseudoTicks:($bars * 4)' "$runner" >/dev/null
grep -F '15m:4","15m:96","15m:192' "$runner" >/dev/null
grep -F '1h:1","1h:24","1h:48' "$runner" >/dev/null
grep -F '4h:1","4h:6","4h:12' "$runner" >/dev/null
if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory|--cpus|--pids-limit|--cpuset-cpus' "$prepare" "$runner"; then
    echo 'higher-timeframe warmup scripts add a JVM or resource restriction' >&2
    exit 1
fi
