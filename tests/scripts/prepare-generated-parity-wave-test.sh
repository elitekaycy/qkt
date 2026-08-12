#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -depth -delete' EXIT

prepare="$repo_root/scripts/live-validation/prepare-generated-parity-wave.sh"
readonly_runner="$repo_root/scripts/live-validation/run-readonly.sh"
market_runner="$repo_root/scripts/live-validation/run-market-bracket.sh"
cli="${QKT_TEST_CLI:-$repo_root/build/install/qkt/bin/qkt}"

bash -n "$prepare"
bash "$prepare" --help | grep -F 'first generated order-bearing live/replay parity wave' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id paritywave01 \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 \
    --magic-base 919201 \
    --cli "$cli" >/dev/null

(
    cd "$suite"
    sha256sum --check SHA256SUMS >/dev/null
)
jq -e '
    .schema == "qkt-live-generated-parity-wave-v1" and
    .gatewayUrl == "http://127.0.0.1:5001" and
    .credentialsStored == false and
    .contract == {
      cases:4,
      orderBearing:true,
      boundedLots:"0.01",
      requiredFinalPositions:0,
      requiredFinalOrders:0,
      requiredReplayComparison:true,
      liveRunner:"scripts/live-validation/run-market-bracket.sh",
      readOnlyRunner:"scripts/live-validation/run-readonly.sh",
      replayRunner:"scripts/live-validation/compare-golden-replay.sh"
    } and
    [.cases[].id] == ["ema-eurusd", "rsi-gbpusd", "atr-eurusd", "case-gbpusd"] and
    [.cases[].variant] == ["ema_cross", "rsi_reversion", "atr_channel", "case_math"] and
    [.cases[].symbol] == ["EXNESS:EURUSD", "EXNESS:GBPUSD", "EXNESS:EURUSD", "EXNESS:GBPUSD"] and
    ([.cases[].magic] | unique | length) == 4
' "$suite/suite.json" >/dev/null

test "$(find "$suite/cases" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 4

ema_case="$suite/cases/ema-eurusd"
rsi_case="$suite/cases/rsi-gbpusd"
atr_case="$suite/cases/atr-eurusd"
math_case="$suite/cases/case-gbpusd"

jq -e '.armedScenario.variant == "ema_cross" and .armedScenario.symbol == "EXNESS:EURUSD"' \
    "$ema_case/expected.json" >/dev/null
jq -e '.armedScenario.variant == "rsi_reversion" and .armedScenario.symbol == "EXNESS:GBPUSD"' \
    "$rsi_case/expected.json" >/dev/null
jq -e '.armedScenario.variant == "atr_channel" and .armedScenario.symbol == "EXNESS:EURUSD"' \
    "$atr_case/expected.json" >/dev/null
jq -e '.armedScenario.variant == "case_math" and .armedScenario.symbol == "EXNESS:GBPUSD"' \
    "$math_case/expected.json" >/dev/null

ema_armed="$ema_case/strategies/armed/paritywave01_ema_eurusd_market_bracket.qkt"
rsi_armed="$rsi_case/strategies/armed/paritywave01_rsi_gbpusd_market_bracket.qkt"
atr_armed="$atr_case/strategies/armed/paritywave01_atr_eurusd_market_bracket.qkt"
math_armed="$math_case/strategies/armed/paritywave01_case_gbpusd_market_bracket.qkt"

grep -F 'm1_fast = ema(asset1.close, 3)' "$ema_armed" >/dev/null
grep -F 'm5_slow = ema(asset5.close, 5)' "$ema_armed" >/dev/null

grep -F 'm1_fast = rsi(asset1.close, 5)' "$rsi_armed" >/dev/null
grep -F 'm1_slow = sma(asset1.close, 5)' "$rsi_armed" >/dev/null
grep -F 'm5_fast = rsi(asset5.close, 5)' "$rsi_armed" >/dev/null
grep -F 'm5_slow = sma(asset5.close, 5)' "$rsi_armed" >/dev/null

grep -F 'm1_fast = atr(asset1, 5)' "$atr_armed" >/dev/null
grep -F 'm1_slow = ema(asset1.close, 5)' "$atr_armed" >/dev/null
grep -F 'm5_fast = atr(asset5, 5)' "$atr_armed" >/dev/null
grep -F 'm5_slow = ema(asset5.close, 5)' "$atr_armed" >/dev/null

grep -F 'm1_fast = round_to(asset1.close, 0.0001)' "$math_armed" >/dev/null
grep -F 'm1_slow = lag(asset1.close, 1)' "$math_armed" >/dev/null
grep -F 'm5_fast = highest(asset5.close, 5)' "$math_armed" >/dev/null
grep -F 'm5_slow = lowest(asset5.close, 5)' "$math_armed" >/dev/null
grep -F 'signed_m1_delta = CASE WHEN m1_fast >= m1_slow THEN abs(m1_fast - m1_slow) ELSE -abs(m1_fast - m1_slow) END' "$math_armed" >/dev/null

for scenario in "$ema_case" "$rsi_case" "$atr_case" "$math_case"; do
    (cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null)
    bash "$readonly_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null
    bash "$market_runner" --scenario "$scenario" --cli "$cli" --verify-only >/dev/null
done

if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory(=|[[:space:]])|--cpus(=|[[:space:]])|--pids-limit|--cpuset-cpus' "$prepare"; then
    echo 'generated parity wave preparer adds a JVM or container restriction' >&2
    exit 1
fi
if rg --text --fixed-strings 'fixture-secret' "$suite"; then
    echo 'generated parity wave preparer retained a credential' >&2
    exit 1
fi
