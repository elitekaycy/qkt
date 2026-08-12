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

Materialize an MT5 golden capture and compare full-tick and plain-bar replay evidence.
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

if [ "$(jq -r '.captureMode // "TRADING"' "$external_manifest")" = "READ_ONLY" ]; then
    readonly_args=(--scenario "$scenario" --out "$output" --cli "$cli")
    $verify_only && readonly_args+=(--verify-only)
    exec "$repo_root/scripts/live-validation/compare-readonly-replay.sh" "${readonly_args[@]}"
fi

mapfile -d '' strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*.qkt' -print0 | sort -z)
[ "${#strategies[@]}" -eq 1 ] || fail "expected exactly one armed strategy, found ${#strategies[@]}"
strategy_file="${strategies[0]}"

jq -e '
    (.lifecycle // "single") as $lifecycle |
    (.expectedLifecycle.entries // 1) as $entries |
    (.expectedLifecycle.exits // 1) as $exits |
    .schema == "qkt-live-validation-market-bracket-v1" and
    .status == "passed" and
    .qktDirty == false and
    (($lifecycle == "single" and .flattenVerified == true) or
     ($lifecycle == "reentry" and .strategyOwnedLifecycle == true)) and
    .finalPositions == 0 and
    .finalOrders == 0 and
    .transport.orderPosts >= $entries and
    .transport.closePosts >= $exits and
    .golden.ticks > 0 and
    .golden.warmupTicks > 0 and
    .golden.candles > 0 and
    .golden.fills >= ($entries + $exits) and
    .golden.linkedPlacements >= $entries
' "$live_result" >/dev/null || fail "live result is not a clean, passed, replayable market-bracket run"
jq -e '.qktDirty == false and .credentialsStored == false' "$scenario_json" >/dev/null ||
    fail "scenario was not captured from a clean, credential-free preparation"

strategy_id="$(jq -er '.strategy' "$live_result")"
expected_entries="$(jq -er '.expectedLifecycle.entries // 1' "$live_result")"
lifecycle="$(jq -er '.lifecycle // "single"' "$live_result")"
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
    require_file "$report/orders.jsonl"
    jq -e --argjson expectedEntries "$expected_entries" '.global.tradeCount == $expectedEntries' "$report/result.json" >/dev/null ||
        fail "$name did not produce $expected_entries trade(s)"
}

run_replay full-ticks-paper paper
run_replay bars-paper paper --bars --bar-tf 1m
run_replay full-ticks-mt5 mt5-sim

trades_json() {
    local csv="$1"
    [ "$(awk 'END { print NR }' "$csv")" -eq "$((expected_entries + 1))" ] ||
        fail "expected $expected_entries trade row(s) in $csv"
    jq -Rn '
        [inputs] as $lines |
        ($lines[0] | split(",")) as $header |
        [
            $lines[1:][] |
            split(",") as $values |
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
        ]
    ' <"$csv"
}

mkdir -m 700 "$output/comparison"
for mode in full-ticks-paper bars-paper full-ticks-mt5; do
    trades_json "$output/reports/$mode/trades.csv" >"$output/comparison/$mode-trades.json"
done

canonicalize_report_orders() {
    jq -s '
        map(del(.seq, .ts) | .request |=
            (del(.createdTs) | if has("entry") then .entry |= del(.createdTs) else . end))
    ' "$1"
}
canonicalize_report_orders "$output/reports/full-ticks-paper/orders.jsonl" \
    >"$output/comparison/full-ticks-paper-orders-normalized.json"
canonicalize_report_orders "$output/reports/bars-paper/orders.jsonl" \
    >"$output/comparison/bars-paper-orders-normalized.json"
cmp -s "$output/reports/full-ticks-paper/orders.jsonl" "$output/reports/full-ticks-mt5/orders.jsonl" ||
    fail "full-ticks paper and MT5 order journals are not byte-identical"
cmp -s "$output/comparison/full-ticks-paper-orders-normalized.json" \
    "$output/comparison/bars-paper-orders-normalized.json" ||
    fail "timestamp-normalized plain-bar orders differ from full-tick orders"
jq -s -e '
    $expectedEntries as $expectedEntries |
    [.[] | select(.decision == "approved" and .request.orderType == "Bracket")] |
    if length == $expectedEntries then map(.request) else error("expected approved bracket entries") end
' --argjson expectedEntries "$expected_entries" "$output/reports/full-ticks-paper/orders.jsonl" \
    >"$output/comparison/full-ticks-entry-orders.json"

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
    if ($orders | length) != $expectedEntries then error("expected linked successful live orders") else . end |
    [
        $orders[] as $order |
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
        if ($modifications | length) != 1 then error("expected successful protection update") else . end |
        ($modifications[0] | decoded_request) as $protection |
        [
            $engine[] |
            select(
                .eventType == "com.qkt.events.BrokerEvent.OrderFilled" and
                .strategyId == $strategy and
                .orderId == $order.engineOrderId
            )
        ] as $fills |
        if ($fills | length) != 1 then error("expected linked strategy entry fill") else . end |
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
    ]
' --argjson expectedEntries "$expected_entries" >"$output/comparison/live-entries.json"

paper_trades="$(jq -c '.' "$output/comparison/full-ticks-paper-trades.json")"
mt5_trades="$(jq -c '.' "$output/comparison/full-ticks-mt5-trades.json")"
live_entries="$(jq -c '.' "$output/comparison/live-entries.json")"
jq -en --slurpfile intents "$output/comparison/full-ticks-entry-orders.json" \
    --argjson paper "$paper_trades" --argjson mt5 "$mt5_trades" --argjson live "$live_entries" \
    --argjson expectedEntries "$expected_entries" '
    def near($a; $b): (($a | tonumber) - ($b | tonumber) | fabs) < 0.000000001;
    ($paper | length) == $expectedEntries and
    ($mt5 | length) == $expectedEntries and
    ($live | length) == $expectedEntries and
    ($intents[0] | length) == $expectedEntries and
    all(range(0; $expectedEntries); . as $i |
        ($paper[$i].symbol == $mt5[$i].symbol) and
        ($paper[$i].symbol == $live[$i].fill.symbol) and
        ($intents[0][$i].side == $live[$i].request.side) and
        ($mt5[$i].side == $live[$i].fill.side) and
        near($intents[0][$i].qty; $live[$i].request.quantity) and
        near($mt5[$i].quantity; $live[$i].fill.quantity) and
        near($intents[0][$i].stopLoss.price; $live[$i].request.stopLossPrice) and
        near($intents[0][$i].takeProfit; $live[$i].request.takeProfitPrice) and
        near($mt5[$i].price; $live[$i].fill.price) and
        near($mt5[$i].stopLossPrice; $live[$i].protection.stopLossPrice) and
        near($mt5[$i].takeProfitPrice; $live[$i].protection.takeProfitPrice)
    )
' >/dev/null || fail "captured live order, fill, or protection differs from replay"

replay_git_sha="$(jq -er '.evidence.gitSha' "$output/reports/full-ticks-mt5/result.json")"
for mode in full-ticks-paper bars-paper; do
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
    --arg lifecycle "$lifecycle" \
    --argjson expectedEntries "$expected_entries" \
    --argjson liveEntries "$live_entries" \
    --argjson paperTrades "$paper_trades" \
    --argjson mt5Trades "$mt5_trades" '
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
        lifecycle: $lifecycle,
        expectedEntries: $expectedEntries,
        parity: {
            fullTickOrderJournalsByteExact: true,
            barsOrdersTimestampNormalizedExact: true,
            liveInitialProtectionMatchesCanonicalIntent: true,
            liveFillAndAdjustedProtectionMatchMt5Simulation: true
        },
        liveEntry: $liveEntries[0],
        liveEntries: $liveEntries,
        paperTrade: $paperTrades[0],
        paperTrades: $paperTrades,
        mt5SimTrade: $mt5Trades[0],
        mt5SimTrades: $mt5Trades,
        limitations: [
            (if $lifecycle == "single" then
                "The operator flatten fill occurs after the bounded strategy replay window and " +
                "is reconciled by the live result, not replayed as a strategy decision."
             else
                "The live re-entry capture includes strategy-owned close fills; replay comparison " +
                "checks entry intent, fill, and adjusted protection parity for each entry and relies " +
                "on the live result for per-ticket final-flat reconciliation."
             end
            ),
            (
                "Tick-resolved bars are unsupported for mixed-timeframe strategies because " +
                "a finer stream can dispatch before another symbol or timeframe bar resolves."
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
