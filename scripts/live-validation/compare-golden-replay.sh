#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
scenario=""
output=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

usage() {
    cat <<'EOF'
Usage: compare-golden-replay.sh --scenario DIR --out DIR [--cli PATH] [--verify-only]

Materialize an MT5 golden capture and compare full-tick, bar, and tick-resolved replay evidence.
The command is offline: it never contacts the configured broker gateway.
EOF
}

fail() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "required file not found: $1"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario)
            [ "$#" -ge 2 ] || fail "--scenario requires a value"
            scenario="$2"
            shift 2
            ;;
        --out)
            [ "$#" -ge 2 ] || fail "--out requires a value"
            output="$2"
            shift 2
            ;;
        --cli)
            [ "$#" -ge 2 ] || fail "--cli requires a value"
            cli="$2"
            shift 2
            ;;
        --verify-only)
            verify_only=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            fail "unknown argument: $1"
            ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$output" ] || fail "--out is required"
[ -d "$scenario" ] || fail "scenario directory not found: $scenario"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"

require_command awk
require_command cmp
require_command cp
require_command find
require_command grep
require_command jq
require_command sha256sum
require_command sort
require_command unzip

scenario="$(cd "$scenario" && pwd)"
case "$output" in
    /*) ;;
    *) output="$(pwd)/$output" ;;
esac

expected="$scenario/expected.json"
scenario_json="$scenario/scenario.json"
config="$scenario/qkt.config.yaml"
live_result="$scenario/evidence/result.json"
bundle="$scenario/evidence/golden.zip"
external_manifest="$scenario/evidence/golden-manifest.json"

require_file "$expected"
require_file "$scenario_json"
require_file "$config"
require_file "$live_result"
require_file "$bundle"
require_file "$external_manifest"

mapfile -d '' strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*.qkt' -print0 | sort -z)
[ "${#strategies[@]}" -eq 1 ] || fail "expected exactly one armed strategy, found ${#strategies[@]}"
strategy_file="${strategies[0]}"

jq -e '
    .schema == "qkt-live-validation-market-bracket-v1" and
    .status == "passed" and
    .qktDirty == false and
    .flattenVerified == true and
    .finalPositions == 0 and
    .finalOrders == 0 and
    .transport.orderPosts == 1 and
    .golden.ticks > 0 and
    .golden.warmupTicks > 0 and
    .golden.candles > 0 and
    .golden.fills >= 1 and
    .golden.linkedPlacements == 1
' "$live_result" >/dev/null || fail "live result is not a clean, passed, replayable market-bracket run"
jq -e '.qktDirty == false and .credentialsStored == false' "$scenario_json" >/dev/null ||
    fail "scenario was not captured from a clean, credential-free preparation"

strategy_id="$(jq -er '.strategy' "$live_result")"
starting_balance="$(jq -er '.account.startingBalance' "$expected")"
source_sha="$(sha256sum "$bundle" | awk '{print $1}')"
[ "$source_sha" = "$(jq -er '.golden.sha256' "$live_result")" ] || fail "golden bundle hash does not match live result"
cmp -s "$external_manifest" <(unzip -p "$bundle" manifest.json) ||
    fail "external golden manifest differs from bundle manifest"
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null ||
    fail "scenario config does not retain the broker key as an environment reference"

if [ -f "$scenario/RUN-SHA256SUMS" ]; then
    (cd "$scenario" && sha256sum --check RUN-SHA256SUMS >/dev/null) || fail "live-run checksums failed"
elif [ -f "$scenario/SHA256SUMS" ]; then
    (cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null) || fail "prepared scenario checksums failed"
fi

if $verify_only; then
    [ ! -e "$output" ] || fail "output already exists: $output"
    printf 'verified %s\n' "$scenario"
    exit 0
fi

[ ! -e "$output" ] || fail "output already exists: $output"
mkdir -m 700 "$output"
mkdir -m 700 "$output/logs" "$output/source"
cp -- "$bundle" "$output/source/golden.zip"
cp -- "$external_manifest" "$output/source/golden-manifest.json"
cp -- "$live_result" "$output/source/live-result.json"
cp -- "$expected" "$output/source/expected.json"
cp -- "$scenario_json" "$output/source/scenario.json"
cp -- "$strategy_file" "$output/source/strategy.qkt"
cp -- "$config" "$output/source/qkt.config.yaml"
replay_bundle="$output/source/golden.zip"
replay_strategy="$output/source/strategy.qkt"
replay_config="$output/source/qkt.config.yaml"

export QKT_BROKER_API_KEY=offline-replay-not-used
"$cli" golden materialize --bundle "$replay_bundle" --out "$output/data" >"$output/logs/materialize.log" 2>&1

replay_manifest="$output/data/golden-replay-manifest.json"
require_file "$replay_manifest"
from_utc="$(jq -er '.replayWindow.fromUtc' "$replay_manifest")"
to_utc="$(jq -er '.replayWindow.toUtc' "$replay_manifest")"
materialized_sha="$(jq -er '.sourceBundleSha256' "$replay_manifest")"
[ "$materialized_sha" = "$source_sha" ] || fail "materialized data references the wrong golden bundle"

run_replay() {
    local name="$1"
    local broker="$2"
    shift 2
    local report="$output/reports/$name"
    mkdir -p "$(dirname "$report")"
    "$cli" backtest "$replay_strategy" \
        --from "$from_utc" \
        --to "$to_utc" \
        --data-root "$output/data" \
        --no-fetch \
        --allow-incomplete \
        --config "$replay_config" \
        --starting-balance "$starting_balance" \
        --broker "$broker" \
        --report-dir "$report" \
        --json \
        "$@" >"$output/logs/$name.stdout.json" 2>"$output/logs/$name.stderr.log"
    require_file "$report/result.json"
    require_file "$report/trades.csv"
    jq -e '.global.tradeCount == 1' "$report/result.json" >/dev/null || fail "$name did not produce exactly one trade"
}

run_replay full-ticks-paper paper
run_replay bars-paper paper --bars --bar-tf 1m
run_replay full-ticks-mt5 mt5-sim
run_replay tick-resolved-bars-mt5 mt5-sim --bars --tick-fills --bar-tf 1m

trade_json() {
    local csv="$1"
    [ "$(awk 'END { print NR }' "$csv")" -eq 2 ] || fail "expected one trade row in $csv"
    jq -Rn '
        [inputs] as $lines |
        ($lines[0] | split(",")) as $header |
        ($lines[1] | split(",")) as $values |
        reduce range(0; $header | length) as $i ({}; .[$header[$i]] = $values[$i]) |
        {
            timestamp: (.timestamp | tonumber),
            strategy: .strategy,
            symbol: .symbol,
            side: .side,
            positionEffect: .positionEffect,
            orderType: .orderType,
            quantity: .quantity,
            price: .price,
            stopLossPrice: .stopLossPrice,
            takeProfitPrice: .takeProfitPrice,
            brokerOrderId: .brokerOrderId
        }
    ' <"$csv"
}

mkdir -m 700 "$output/comparison"
for mode in full-ticks-paper bars-paper full-ticks-mt5 tick-resolved-bars-mt5; do
    trade_json "$output/reports/$mode/trades.csv" >"$output/comparison/$mode-trade.json"
done

jq '{strategy, symbol, side, positionEffect, orderType, quantity, price, stopLossPrice, takeProfitPrice}' \
    "$output/comparison/full-ticks-paper-trade.json" >"$output/comparison/full-ticks-paper-decision.json"
jq '{strategy, symbol, side, positionEffect, orderType, quantity, price, stopLossPrice, takeProfitPrice}' \
    "$output/comparison/bars-paper-trade.json" >"$output/comparison/bars-paper-decision.json"
cmp -s "$output/comparison/full-ticks-paper-decision.json" "$output/comparison/bars-paper-decision.json" ||
    fail "plain-bar decision differs from full-tick paper decision"

cmp -s "$output/reports/full-ticks-mt5/trades.csv" "$output/reports/tick-resolved-bars-mt5/trades.csv" ||
    fail "tick-resolved MT5 trades are not byte-identical to full-tick MT5 trades"
jq -S 'del(.evidence)' "$output/reports/full-ticks-mt5/result.json" \
    >"$output/comparison/full-ticks-mt5-result-canonical.json"
jq -S 'del(.evidence)' "$output/reports/tick-resolved-bars-mt5/result.json" \
    >"$output/comparison/tick-resolved-bars-mt5-result-canonical.json"
cmp -s "$output/comparison/full-ticks-mt5-result-canonical.json" \
    "$output/comparison/tick-resolved-bars-mt5-result-canonical.json" ||
    fail "tick-resolved MT5 result differs semantically from full-tick MT5 result"

engine_entry="$(jq -er '.entries[] | select(.path | startswith("engine/")) | .path' "$external_manifest")"
transport_entry="$(jq -er '.entries[] | select(.path | startswith("gateway/")) | .path' "$external_manifest")"
unzip -p "$replay_bundle" "$engine_entry" >"$output/source/engine.jsonl"
unzip -p "$replay_bundle" "$transport_entry" >"$output/source/gateway-transport.jsonl"

jq -n \
    --slurpfile engine "$output/source/engine.jsonl" \
    --slurpfile transport "$output/source/gateway-transport.jsonl" \
    --arg strategy "$strategy_id" '
    def decoded_request: .requestBody | fromjson;
    def decoded_response: .responseBody | fromjson;
    [
        $transport[] |
        select(
            .method == "POST" and
            .path == "/order" and
            (.engineOrderId | startswith("dsl-" + $strategy)) and
            .responseCode == 200 and
            (decoded_response.ok == true)
        )
    ] as $orders |
    if ($orders | length) != 1 then error("expected exactly one linked successful live order") else . end |
    $orders[0] as $order |
    ($order | decoded_request) as $request |
    ($order | decoded_response) as $response |
    ($response.result.order | tostring) as $ticket |
    [
        $transport[] |
        select(
            .method == "POST" and
            .path == "/modify_sl_tp" and
            .responseCode == 200 and
            ((decoded_request.position | tostring) == $ticket) and
            (decoded_response.ok == true)
        )
    ] as $modifications |
    if ($modifications | length) != 1 then error("expected exactly one successful protection update") else . end |
    ($modifications[0] | decoded_request) as $protection |
    [
        $engine[] |
        select(
            .eventType == "com.qkt.events.BrokerEvent.OrderFilled" and
            .strategyId == $strategy and
            .orderId == $order.engineOrderId
        )
    ] as $fills |
    if ($fills | length) != 1 then error("expected exactly one linked strategy entry fill") else . end |
    {
        engineOrderId: $order.engineOrderId,
        brokerOrderId: $ticket,
        request: {
            symbol: $request.symbol,
            side: $request.type,
            quantity: ($request.volume | tostring),
            stopLossPrice: ($request.sl | tostring),
            takeProfitPrice: ($request.tp | tostring)
        },
        fill: {
            symbol: $fills[0].symbol,
            side: $fills[0].fill.side,
            quantity: $fills[0].fill.quantity,
            price: $fills[0].fill.price
        },
        protection: {
            stopLossPrice: ($protection.sl | tostring),
            takeProfitPrice: ($protection.tp | tostring)
        }
    }
' >"$output/comparison/live-entry.json"

paper_trade="$(jq -c '.' "$output/comparison/full-ticks-paper-trade.json")"
mt5_trade="$(jq -c '.' "$output/comparison/full-ticks-mt5-trade.json")"
live_entry="$(jq -c '.' "$output/comparison/live-entry.json")"
jq -en --argjson paper "$paper_trade" --argjson mt5 "$mt5_trade" --argjson live "$live_entry" '
    def near($a; $b): (($a | tonumber) - ($b | tonumber) | fabs) < 0.000000001;
    ($paper.symbol == $mt5.symbol) and
    ($paper.symbol == $live.fill.symbol) and
    ($paper.side == $live.request.side) and
    ($mt5.side == $live.fill.side) and
    near($paper.quantity; $live.request.quantity) and
    near($mt5.quantity; $live.fill.quantity) and
    near($paper.stopLossPrice; $live.request.stopLossPrice) and
    near($paper.takeProfitPrice; $live.request.takeProfitPrice) and
    near($mt5.price; $live.fill.price) and
    near($mt5.stopLossPrice; $live.protection.stopLossPrice) and
    near($mt5.takeProfitPrice; $live.protection.takeProfitPrice)
' >/dev/null || fail "captured live order, fill, or protection differs from replay"

replay_git_sha="$(jq -er '.evidence.gitSha' "$output/reports/full-ticks-mt5/result.json")"
for mode in full-ticks-paper bars-paper tick-resolved-bars-mt5; do
    [ "$(jq -er '.evidence.gitSha' "$output/reports/$mode/result.json")" = "$replay_git_sha" ] ||
        fail "replay modes used different QKT builds"
done

jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg sourceBundle "source/golden.zip" \
    --arg sourceBundleSha256 "$source_sha" \
    --arg captureGitSha "$(jq -er '.captureGitSha' "$external_manifest")" \
    --arg replayGitSha "$replay_git_sha" \
    --arg strategy "$strategy_id" \
    --arg strategySha256 "$(sha256sum "$replay_strategy" | awk '{print $1}')" \
    --arg configSha256 "$(sha256sum "$replay_config" | awk '{print $1}')" \
    --arg fromUtc "$from_utc" \
    --arg toUtc "$to_utc" \
    --argjson sourceCounts "$(jq -c '.counts' "$external_manifest")" \
    --argjson liveEntry "$live_entry" \
    --argjson paperTrade "$paper_trade" \
    --argjson mt5Trade "$mt5_trade" \
    --arg fullMt5TradesSha256 "$(sha256sum "$output/reports/full-ticks-mt5/trades.csv" | awk '{print $1}')" \
    --arg tickResolvedTradesSha256 \
        "$(sha256sum "$output/reports/tick-resolved-bars-mt5/trades.csv" | awk '{print $1}')" '
    {
        schema: "qkt-live-golden-replay-comparison-v1",
        status: "passed",
        finishedAt: $finishedAt,
        source: {
            bundle: $sourceBundle,
            bundleSha256: $sourceBundleSha256,
            captureGitSha: $captureGitSha,
            counts: $sourceCounts
        },
        replay: {
            gitSha: $replayGitSha,
            strategy: $strategy,
            strategySha256: $strategySha256,
            configSha256: $configSha256,
            fromUtc: $fromUtc,
            toUtc: $toUtc
        },
        parity: {
            paperDecisionExact: true,
            mt5TickResolvedTradesByteExact: ($fullMt5TradesSha256 == $tickResolvedTradesSha256),
            mt5TickResolvedResultSemanticExact: true,
            liveInitialProtectionMatchesPaper: true,
            liveFillAndAdjustedProtectionMatchMt5Simulation: true
        },
        liveEntry: $liveEntry,
        paperTrade: $paperTrade,
        mt5SimTrade: $mt5Trade,
        hashes: {
            fullMt5TradesSha256: $fullMt5TradesSha256,
            tickResolvedTradesSha256: $tickResolvedTradesSha256
        },
        limitations: [
            (
                "The operator flatten fill occurs after the bounded strategy replay window and " +
                "is reconciled by the live result, not replayed as a strategy decision."
            ),
            (
                "Plain OHLCV bars prove the bar-close decision for this scenario; only " +
                "tick-resolved bars carry the exact intrabar fill claim."
            )
        ]
    }
' >"$output/result.json"

manifest="$output/artifact-manifest.json"
printf '{"schema":"qkt-live-golden-replay-artifacts-v1","artifacts":[' >"$manifest"
first=true
while IFS= read -r -d '' artifact; do
    relative="${artifact#"$output/"}"
    [ "$relative" = "artifact-manifest.json" ] && continue
    [ "$relative" = "SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >>"$manifest"; fi
    jq -cn \
        --arg path "$relative" \
        --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" \
        '{path:$path,size:$size,sha256:$sha256}' >>"$manifest"
done < <(find "$output" -type f -print0 | sort -z)
printf ']}\n' >>"$manifest"
(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)

printf 'passed %s\n' "$output/result.json"
