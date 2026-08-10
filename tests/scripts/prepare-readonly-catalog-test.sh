#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

prepare="$repo_root/scripts/live-validation/prepare-readonly-catalog.sh"
cli="$repo_root/build/install/qkt/bin/qkt"

bash -n "$prepare"
bash "$prepare" --help | grep -F 'four financially read-only live catalog cases' >/dev/null
test -x "$cli"

suite="$tmp/suite"
bash "$prepare" \
    --output "$suite" \
    --id wave1_fixture \
    --gateway-url http://127.0.0.1:5001 \
    --expected-login 436804390 \
    --expected-server Exness-MT5Trial9 \
    --expected-balance 100000.22 \
    --expected-leverage 500 >/dev/null

(
    cd "$suite"
    sha256sum --check SHA256SUMS >/dev/null
)
jq -e '
    .schema == "qkt-live-readonly-catalog-suite-v1" and
    .gatewayUrl == "http://127.0.0.1:5001" and .credentialsStored == false and
    .contract.containers == 4 and .contract.parallel == true and
    .contract.financiallyReadOnly == true and .contract.requiredGatewayMutations == 0 and
    .contract.requiredOrderEvents == 0 and .contract.requiredFills == 0 and
    .contract.barsFirstClass == true and
    .contract.polling == {tickPollIntervalMs:500,brokerPollIntervalMs:5000,parallelTickSymbols:5} and
    [.cases[].id] == ["numeric-candle","cross-multi-tf","session-history","volume-negative"] and
    ([.cases[].magic] | unique | length) == 4 and
    all(.cases[]; (.streams | length) > 0 and (.vectors | length) > 0) and
    (.cases[] | select(.id == "volume-negative") | .negativeDeployment) == "volume-capability-rejected"
' "$suite/suite.json" >/dev/null

test "$(find "$suite/cases" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 4
test "$(find "$suite/cases" -type f -name '*.qkt' | wc -l)" -eq 5
for config in "$suite"/cases/*/qkt.config.yaml; do
    grep -F 'gateway_url: http://127.0.0.1:5001' "$config" >/dev/null
    grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null
    grep -F 'tick_poll_interval_ms: 500' "$config" >/dev/null
    grep -F 'poll_interval_ms: 5000' "$config" >/dev/null
done

numeric="$suite/cases/numeric-candle/strategies/control/wave1_fixture_numeric_candle.qkt"
for mapping in ema sma wma dema tema hma rsi atr stddev variance variance_ratio zscore \
    regression_slope percentile_rank skew er lag runlength runlength_where \
    williams_r cci stoch_k stoch_d keltner_upper keltner_middle keltner_lower plus_di minus_di \
    adx macd macd_signal macd_hist bollinger_upper bollinger_middle bollinger_lower highest lowest; do
    grep -F "$mapping" "$numeric" >/dev/null
done
for helper in abs sqrt log exp pow floor ceil round mod round_to min max; do
    grep -F "$helper" "$numeric" >/dev/null
done
grep -F 'CASE WHEN eur1.close >= eur1.open THEN 1 ELSE -1 END' "$numeric" >/dev/null

cross="$suite/cases/cross-multi-tf/strategies/control/wave1_fixture_cross_multi_tf.qkt"
grep -F 'correlation(' "$cross" >/dev/null
grep -F 'beta(' "$cross" >/dev/null
grep -F 'resid(' "$cross" >/dev/null
grep -F 'confirm_ratio(' "$cross" >/dev/null
grep -F 'rank_of(' "$cross" >/dev/null
grep -F 'normalize(' "$cross" >/dev/null
grep -F 'softmax(' "$cross" >/dev/null
grep -F 'EVERY 1m' "$cross" >/dev/null
grep -F 'EVERY 5m' "$cross" >/dev/null

session="$suite/cases/session-history/strategies/control/wave1_fixture_session_history.qkt"
grep -F 'WARMUP 5000 BARS' "$session" >/dev/null
for mapping in session_range_high session_range_low pivot_p pivot_r1 pivot_s1 seasonal_range \
    seasonal_range_stdev session_momentum anchored_return reopen_gap reopen_gap_origin \
    gap_fill_fraction failed_break_high failed_break_low ib_defended_high ib_defended_low \
    session_window calendar_window; do
    grep -F "$mapping" "$session" >/dev/null
done

negative="$suite/cases/volume-negative/strategies/negative/wave1_fixture_volume_requires_data.qkt"
grep -F 'vwap(eur.tick' "$negative" >/dev/null
grep -F 'obv(eur.candle)' "$negative" >/dev/null
grep -F 'vwap_session(eur.candle' "$negative" >/dev/null
grep -F 'vwap_session_stdev(eur.candle' "$negative" >/dev/null

while IFS= read -r name; do
    call="$(printf '%s' "$name" | tr 'A-Z' 'a-z')"
    rg --fixed-strings "${call}(" "$suite/cases" -g '*.qkt' >/dev/null || {
        echo "generated catalog is missing registered indicator $name" >&2
        exit 1
    }
done < <(
    rg -o '^\s+"[A-Z_0-9]+" to' "$repo_root/src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt" |
        sed -E 's/.*"([A-Z_0-9]+)".*/\1/' |
        sort -u
)
while IFS= read -r name; do
    call="$(printf '%s' "$name" | tr 'A-Z' 'a-z')"
    rg --fixed-strings "${call}(" "$suite/cases" -g '*.qkt' >/dev/null || {
        echo "generated catalog is missing registered math function $name" >&2
        exit 1
    }
done < <(
    rg -o 'FuncSpec\("[A-Z_]+' "$repo_root/src/main/kotlin/com/qkt/dsl/stdlib/FuncRegistry.kt" |
        sed 's/.*"//' |
        sort -u
)

if rg --ignore-case --quiet '(^[[:space:]]*|THEN[[:space:]]+)(BUY|SELL|CLOSE|CLOSE_ALL|FLATTEN|RESIZE|CANCEL|CANCEL_ALL|OCO_ENTRY|LATCH)\b' \
    "$suite/cases" -g '*.qkt'; then
    echo 'read-only catalog contains a financial action' >&2
    exit 1
fi
if rg --quiet -- '-Xmx|-Xms|MaxRAMPercentage|MaxRAM=|--memory|--cpus|--pids-limit|--cpuset-cpus' "$prepare"; then
    echo 'catalog preparer adds a JVM or container restriction' >&2
    exit 1
fi
if rg --text --fixed-strings 'fixture-secret' "$suite"; then
    echo 'catalog preparer retained a credential' >&2
    exit 1
fi
