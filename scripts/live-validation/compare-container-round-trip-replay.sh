#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
scenario=""
output=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

usage() {
    cat <<'EOF'
Usage: compare-container-round-trip-replay.sh --scenario DIR --out DIR [--cli PATH] [--verify-only]

Offline comparison for a passed qkt-live-container-round-trip-case-v1 capture.
Runs exactly full-ticks-paper, full-ticks-mt5, and bars-paper. The configured
gateway is never contacted and no live credential is used.
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

count_records() {
    awk 'END {print NR + 0}'
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
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$output" ] || fail "--out is required"
[ -d "$scenario" ] || fail "scenario directory not found: $scenario"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"

for command_name in awk cmp cp cut find grep jq sha256sum sort stat unzip; do
    require_command "$command_name"
done

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
live_log="$scenario/logs/container-daemon.log"

for source_file in "$expected" "$scenario_json" "$config" "$live_result" "$bundle" "$external_manifest" "$live_log"; do
    require_file "$source_file"
done

mapfile -d '' strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*.qkt' -print0 | sort -z)
[ "${#strategies[@]}" -eq 1 ] || fail "expected exactly one armed strategy, found ${#strategies[@]}"
strategy_file="${strategies[0]}"

jq -e '
    .schema == "qkt-live-container-round-trip-case-v1" and
    .status == "passed" and .strategyOwnedClose == true and
    .finalPositions == 0 and .finalOrders == 0 and
    .audit.ruleDecisions == 2 and .audit.decisionOrderLinks == 2 and
    .audit.accepted == 2 and .audit.filled == 2 and .audit.accounted == 2 and
    .audit.rejected == 0 and
    .transport.orderPosts == 1 and .transport.protectionPosts == 1 and
    .transport.closePosts == 1 and .transport.mutations == 3 and
    .golden.fills == 2 and .golden.linkedPlacements == 1 and .golden.mutations == 3 and
    .timeframeEvidence.m1StreamAndEvaluation == true and
    .timeframeEvidence.m5StreamAndEvaluation == true and
    .traces.indicatorEntry == true and .traces.indicatorExit == true
' "$live_result" >/dev/null || fail "live result is not a passed, flat, two-fill container round trip"
jq -e '.qktDirty == false and .credentialsStored == false' "$scenario_json" >/dev/null ||
    fail "scenario is not a clean, credential-free preparation"
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null ||
    fail "scenario config does not retain the broker key as an environment reference"

jq -e '
    (.captureMode // "TRADING") == "TRADING" and
    .counts.ticks > 0 and .counts.warmupTicks > 0 and .counts.candles > 0 and
    .counts.streamCandles > 0 and .counts.strategyCandleEvaluations > 0 and
    .counts.fills == 2 and .counts.linkedPlacements == 1 and .counts.mutations == 3
' "$external_manifest" >/dev/null || fail "golden manifest is not a complete two-fill trading capture"

if [ -f "$scenario/RUN-SHA256SUMS" ]; then
    (cd "$scenario" && sha256sum --check RUN-SHA256SUMS >/dev/null) || fail "live-run checksum verification failed"
elif [ -f "$scenario/SHA256SUMS" ]; then
    (cd "$scenario" && sha256sum --check SHA256SUMS >/dev/null) || fail "scenario checksum verification failed"
else
    fail "scenario has no checksum manifest"
fi

source_sha="$(sha256sum "$bundle" | awk '{print $1}')"
[ "$source_sha" = "$(jq -er '.golden.sha256' "$live_result")" ] || fail "golden bundle hash differs from live result"
cmp -s "$external_manifest" <(unzip -p "$bundle" manifest.json) || fail "external golden manifest differs from bundle manifest"

if $verify_only; then
    [ ! -e "$output" ] || fail "output already exists: $output"
    printf 'verified %s\n' "$scenario"
    exit 0
fi

[ ! -e "$output" ] || fail "output already exists: $output"
mkdir -m 700 "$output" "$output/logs" "$output/source" "$output/reports" "$output/comparison" "$output/work"
cp -- "$bundle" "$output/source/golden.zip"
cp -- "$external_manifest" "$output/source/golden-manifest.json"
cp -- "$live_result" "$output/source/live-result.json"
cp -- "$strategy_file" "$output/source/strategy.qkt"

replay_bundle="$output/source/golden.zip"
replay_strategy="$output/source/strategy.qkt"
starting_balance="$(jq -er '.account.startingBalance' "$expected")"

# All replay brokers are local models. Override any caller credential and disable fetching.
export QKT_BROKER_API_KEY=offline-replay-not-used
"$cli" golden materialize --bundle "$replay_bundle" --out "$output/data" >"$output/logs/materialize.log" 2>&1

replay_manifest="$output/data/golden-replay-manifest.json"
require_file "$replay_manifest"
from_utc="$(jq -er '.replayWindow.fromUtc' "$replay_manifest")"
to_utc="$(jq -er '.replayWindow.toUtc' "$replay_manifest")"
jq -e --arg sourceSha "$source_sha" --argjson sourceCounts "$(jq -c '.counts' "$external_manifest")" '
    .sourceBundleSha256 == $sourceSha and
    .counts.ticks == $sourceCounts.ticks and
    .counts.warmupTicks == $sourceCounts.warmupTicks and
    .counts.candles == $sourceCounts.candles and
    .counts.streamCandles == $sourceCounts.streamCandles and
    .counts.strategyCandleEvaluations == $sourceCounts.strategyCandleEvaluations and
    (.symbols | length) == 1 and
    (.timeframes | contains(["1m", "5m"]))
' "$replay_manifest" >/dev/null || fail "materialized replay data differs from the capture manifest"

run_replay() {
    local mode_name="$1"
    local broker_kind="$2"
    shift 2
    local report="$output/reports/$mode_name"
    mkdir -m 700 "$report"
    "$cli" backtest "$replay_strategy" \
        --from "$from_utc" --to "$to_utc" \
        --data-root "$output/data" --no-fetch --allow-incomplete \
        --config "$config" --starting-balance "$starting_balance" \
        --broker "$broker_kind" --report-dir "$report" --json "$@" \
        >"$output/logs/$mode_name.stdout.log" 2>"$output/logs/$mode_name.stderr.log"
}

run_replay full-ticks-paper paper & replay_pid_0=$!
run_replay full-ticks-mt5 mt5-sim & replay_pid_1=$!
run_replay bars-paper paper --bars --bar-tf 1m & replay_pid_2=$!
replay_failed=false
for replay_pid in "$replay_pid_0" "$replay_pid_1" "$replay_pid_2"; do
    if ! wait "$replay_pid"; then replay_failed=true; fi
done
if $replay_failed; then
    for mode_name in full-ticks-paper full-ticks-mt5 bars-paper; do
        printf '%s stderr:\n' "$mode_name" >&2
        tail -n 20 "$output/logs/$mode_name.stderr.log" >&2 || true
    done
    fail "one or more offline replay modes failed"
fi

csv_to_json() {
    jq -Rn '
        [inputs] as $lines |
        ($lines[0] | split(",")) as $header |
        [$lines[1:][] | split(",") as $values |
            reduce range(0; $header | length) as $i ({}; .[$header[$i]] = $values[$i])]
    ' <"$1"
}

for mode_name in full-ticks-paper full-ticks-mt5 bars-paper; do
    report="$output/reports/$mode_name"
    for artifact in result.json trades.csv orders.jsonl rejections.csv; do require_file "$report/$artifact"; done
    [ "$(count_records <"$report/trades.csv")" -eq 3 ] || fail "$mode_name did not retain exactly two fills"
    [ "$(count_records <"$report/orders.jsonl")" -eq 2 ] || fail "$mode_name did not retain exactly two order decisions"
    [ "$(count_records <"$report/rejections.csv")" -eq 1 ] || fail "$mode_name retained a rejection"
    jq -s -e 'length == 2 and all(.[]; .decision == "approved")' "$report/orders.jsonl" >/dev/null ||
        fail "$mode_name order decisions are not exactly two approvals"
    jq -e '
        .global.tradeCount == 2 and .global.unrealizedTotal == "0.00000000" and
        .tradeSummary.fills == 2 and .tradeSummary.rejections == 0 and
        .tradeSummary.unknownPositionFills == 0
    ' "$report/result.json" >/dev/null || fail "$mode_name is not a flat, rejection-free two-fill result"
    csv_to_json "$report/trades.csv" >"$output/comparison/$mode_name-trades.json"
    jq -e '
        length == 2 and
        .[0].reducedExposure == "false" and .[1].reducedExposure == "true" and
        (.[0].positionEffect | startswith("OPEN_")) and
        (.[1].positionEffect | startswith("CLOSE_")) and
        .[1].accountPositionQtyAfter == "" and .[1].strategyPositionQtyAfter == ""
    ' "$output/comparison/$mode_name-trades.json" >/dev/null || fail "$mode_name did not finish flat"
done

source_counts="$(jq -c '.counts' "$external_manifest")"
for mode_name in full-ticks-paper full-ticks-mt5; do
    jq -e --argjson source "$source_counts" '
        .inputSummary.liveTicks == $source.ticks and
        .inputSummary.warmupTicks == $source.warmupTicks and
        (.inputSummary.warmupCandles + .inputSummary.liveCandles) == $source.candles and
        ([.inputSummary.streamCandles[]] | add) == $source.streamCandles and
        ([.inputSummary.strategyCandleEvaluations[]] | add) == $source.strategyCandleEvaluations
    ' "$output/reports/$mode_name/result.json" >/dev/null || fail "$mode_name input/candle/evaluation counts differ from live"
done
jq -e --argjson source "$source_counts" '
    .inputSummary.warmupTicks == $source.warmupTicks and
    (.inputSummary.warmupCandles + .inputSummary.liveCandles) == $source.candles and
    ([.inputSummary.streamCandles[]] | add) == $source.streamCandles and
    ([.inputSummary.strategyCandleEvaluations[]] | add) == $source.strategyCandleEvaluations
' "$output/reports/bars-paper/result.json" >/dev/null || fail "bars-paper candle/evaluation counts differ from live"

canonicalize_report_orders() {
    jq -s '
        map(del(.seq, .ts) | .request |=
            (del(.createdTs) | if has("entry") then .entry |= del(.createdTs) else . end))
    ' "$1"
}
canonicalize_report_orders "$output/reports/full-ticks-paper/orders.jsonl" >"$output/comparison/full-ticks-paper-orders-normalized.json"
canonicalize_report_orders "$output/reports/bars-paper/orders.jsonl" >"$output/comparison/bars-paper-orders-normalized.json"
cmp -s "$output/reports/full-ticks-paper/orders.jsonl" "$output/reports/full-ticks-mt5/orders.jsonl" ||
    fail "full-ticks paper and MT5 order journals are not byte-identical"
cmp -s "$output/comparison/full-ticks-paper-orders-normalized.json" "$output/comparison/bars-paper-orders-normalized.json" ||
    fail "timestamp-normalized bars-paper orders differ from full-tick orders"
jq -s -e '
    [.[] | select(.decision == "approved" and .request.orderType == "Bracket")] |
    if length == 1 then .[0].request else error("expected one approved bracket entry") end
' "$output/reports/full-ticks-paper/orders.jsonl" >"$output/comparison/full-ticks-entry-order.json"

engine_entry="$(jq -er '.entries[] | select(.path | startswith("engine/")) | .path' "$external_manifest")"
transport_entry="$(jq -er '.entries[] | select(.path | startswith("gateway/")) | .path' "$external_manifest")"
unzip -p "$replay_bundle" "$engine_entry" >"$output/work/engine.jsonl"
unzip -p "$replay_bundle" "$transport_entry" >"$output/work/transport.jsonl"
jq -s -e '
    ([.[] | select(.eventType == "com.qkt.events.OrderEvent")] | length) == 2 and
    ([.[] | select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled")] | length) == 2 and
    ([.[] | select(.eventType == "com.qkt.events.FillAccountedEvent")] | length) == 2 and
    ([.[] | select(.eventType | test("RiskRejectedEvent|BrokerEvent.OrderRejected"))] | length) == 0
' "$output/work/engine.jsonl" >/dev/null || fail "captured live engine evidence is not two-fill and rejection-free"

extract_indicator_entry() {
    awk '
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
            printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", side, score, m1fast, m1slow, m5fast, m5slow, closing
        }
    ' "$1"
}

extract_indicator_exit() {
    awk '
        /bounded indicator exit signed_qty=/ {
            quantity = holding = closing = ""
            for (i = 1; i <= NF; i++) {
                split($i, pair, "=")
                if (pair[1] == "signed_qty") quantity = pair[2]
                if (pair[1] == "holding_seconds") holding = pair[2]
                if (pair[1] == "close") closing = pair[2]
            }
            printf "%s\t%s\t%s\n", quantity, holding, closing
        }
    ' "$1"
}

[ "$(grep -c 'bounded indicator entry side=' "$live_log")" -eq 1 ] || fail "live log lacks exactly one indicator entry"
[ "$(grep -c 'bounded indicator exit signed_qty=' "$live_log")" -eq 1 ] || fail "live log lacks exactly one indicator exit"
extract_indicator_entry "$live_log" >"$output/comparison/live-indicator-entry.tsv"
extract_indicator_exit "$live_log" >"$output/comparison/live-indicator-exit.tsv"
for mode_name in full-ticks-paper full-ticks-mt5 bars-paper; do
    replay_log="$output/logs/$mode_name.stdout.log"
    [ "$(grep -c 'bounded indicator entry side=' "$replay_log")" -eq 1 ] || fail "$mode_name lacks exactly one indicator entry"
    [ "$(grep -c 'bounded indicator exit signed_qty=' "$replay_log")" -eq 1 ] || fail "$mode_name lacks exactly one indicator exit"
    extract_indicator_entry "$replay_log" >"$output/comparison/$mode_name-indicator-entry.tsv"
    extract_indicator_exit "$replay_log" >"$output/comparison/$mode_name-indicator-exit.tsv"
    cmp -s "$output/comparison/live-indicator-entry.tsv" "$output/comparison/$mode_name-indicator-entry.tsv" ||
        fail "$mode_name indicator entry values differ from live"
    cut -f1,3 "$output/comparison/$mode_name-indicator-exit.tsv" >"$output/comparison/$mode_name-indicator-exit-normalized.tsv"
    cut -f1,3 "$output/comparison/live-indicator-exit.tsv" >"$output/comparison/live-indicator-exit-normalized.tsv"
    cmp -s "$output/comparison/live-indicator-exit-normalized.tsv" "$output/comparison/$mode_name-indicator-exit-normalized.tsv" ||
        fail "$mode_name indicator exit quantity or close differs from live"
    awk -F '\t' 'NR == 1 && $2 + 0 >= 1 {ok=1} END {exit !ok}' "$output/comparison/$mode_name-indicator-exit.tsv" ||
        fail "$mode_name indicator exit holding_seconds is below one"
done
awk -F '\t' 'NR == 1 && $2 + 0 >= 1 {ok=1} END {exit !ok}' "$output/comparison/live-indicator-exit.tsv" ||
    fail "live indicator exit holding_seconds is below one"

jq -s '
    [ .[] | select(.eventType == "com.qkt.events.BrokerEvent.OrderFilled") ] as $fills |
    [ .[] | select(.eventType == "com.qkt.events.FillAccountedEvent") ] as $accounted |
    [range(0; $fills | length) as $i | {
        strategy:$fills[$i].strategyId,symbol:$fills[$i].symbol,side:$fills[$i].fill.side,
        quantity:$fills[$i].fill.quantity,price:$fills[$i].fill.price,
        netAccountRealized:$accounted[$i].accountedFill.netAccountRealized,
        grossAccountRealized:$accounted[$i].accountedFill.grossAccountRealized,
        totalCostsAccount:$accounted[$i].accountedFill.totalCostsAccount,
        reducedExposure:$accounted[$i].accountedFill.reducedExposure
    }]
' "$output/work/engine.jsonl" >"$output/comparison/live-trades-normalized.json"
jq '[.[] | {
    strategy,symbol,side,quantity,price,netAccountRealized,grossAccountRealized,
    totalCostsAccount:"0.00000000",reducedExposure:(.reducedExposure == "true")
}]' "$output/comparison/full-ticks-mt5-trades.json" >"$output/comparison/full-ticks-mt5-trades-normalized.json"

jq -s '
    [ .[] | select(.method == "POST" and .path == "/order") ] as $orders |
    [ .[] | select(.method == "POST" and .path == "/modify_sl_tp") ] as $mods |
    [ .[] | select(.method == "POST" and .path == "/close_position") ] as $closes |
    if (($orders|length) != 1 or ($mods|length) != 1 or ($closes|length) != 1) then
        error("expected one order, protection update, and close")
    else
        ($orders[0].requestBody|fromjson) as $orderRequest |
        ($orders[0].responseBody|fromjson) as $orderResponse |
        ($mods[0].requestBody|fromjson) as $modRequest |
        ($mods[0].responseBody|fromjson) as $modResponse |
        ($closes[0].requestBody|fromjson) as $closeRequest |
        ($closes[0].responseBody|fromjson) as $closeResponse |
        {
            initialRequest:{symbol:$orderRequest.symbol,side:$orderRequest.type,quantity:($orderRequest.volume|tostring),stopLoss:($orderRequest.sl|tostring),takeProfit:($orderRequest.tp|tostring)},
            entryResponse:{ok:$orderResponse.ok,retcode:$orderResponse.result.retcode,price:($orderResponse.result.price|tostring),volume:($orderResponse.result.volume|tostring)},
            adjustedProtection:{accepted:$modResponse.ok,retcode:$modResponse.result.retcode,stopLoss:($modRequest.sl|tostring),takeProfit:($modRequest.tp|tostring)},
            closeRequest:{ticketTargeted:($closeRequest.position.ticket != null),volume:($closeRequest.position.volume|tostring)},
            closeResponse:{ok:$closeResponse.ok,retcode:$closeResponse.result.retcode,price:($closeResponse.result.price|tostring),volume:($closeResponse.result.volume|tostring)}
        }
    end
' "$output/work/transport.jsonl" >"$output/comparison/live-execution.json"

jq -ne --slurpfile live "$output/comparison/live-trades-normalized.json" \
    --slurpfile replay "$output/comparison/full-ticks-mt5-trades-normalized.json" '
    def norm: ((tonumber * 100000000) | round);
    ($live[0]|length) == 2 and ($replay[0]|length) == 2 and
    all(range(0;2); . as $i |
        $live[0][$i].strategy == $replay[0][$i].strategy and
        $live[0][$i].symbol == $replay[0][$i].symbol and
        $live[0][$i].side == $replay[0][$i].side and
        ($live[0][$i].quantity|norm) == ($replay[0][$i].quantity|norm) and
        ($live[0][$i].price|norm) == ($replay[0][$i].price|norm) and
        ($live[0][$i].netAccountRealized|norm) == ($replay[0][$i].netAccountRealized|norm) and
        ($live[0][$i].grossAccountRealized|norm) == ($replay[0][$i].grossAccountRealized|norm) and
        ($live[0][$i].totalCostsAccount|norm) == ($replay[0][$i].totalCostsAccount|norm) and
        $live[0][$i].reducedExposure == $replay[0][$i].reducedExposure)
' >/dev/null || fail "live and full-ticks-mt5 fills or PnL differ after numeric normalization"
jq -ne --slurpfile live "$output/comparison/live-execution.json" \
    --slurpfile intent "$output/comparison/full-ticks-entry-order.json" \
    --slurpfile mt5 "$output/comparison/full-ticks-mt5-trades.json" '
    def norm: ((tonumber * 100000000) | round);
    ($live[0].entryResponse.ok and $live[0].adjustedProtection.accepted and $live[0].closeResponse.ok) and
    ([$live[0].entryResponse.retcode,$live[0].adjustedProtection.retcode,$live[0].closeResponse.retcode] |
        all(.[]; . == 10008 or . == 10009 or . == 10010)) and
    $live[0].initialRequest.side == $intent[0].side and
    ($live[0].initialRequest.quantity|norm) == ($intent[0].qty|norm) and
    ($live[0].initialRequest.stopLoss|norm) == ($intent[0].stopLoss.price|norm) and
    ($live[0].initialRequest.takeProfit|norm) == ($intent[0].takeProfit|norm) and
    ($live[0].adjustedProtection.stopLoss|norm) == ($mt5[0][0].stopLossPrice|norm) and
    ($live[0].adjustedProtection.takeProfit|norm) == ($mt5[0][0].takeProfitPrice|norm) and
    ($live[0].entryResponse.price|norm) == ($mt5[0][0].price|norm) and
    ($live[0].closeResponse.price|norm) == ($mt5[0][1].price|norm)
' >/dev/null || fail "live request, protection, or fills differ from MT5 simulation"

jq '[.[] | del(
    .timestamp,.fxRateTimestamp,.brokerOrderId,.price,.realized,.netAccountRealized,
    .grossAccountRealized,.nativeRealized,.accountRealized,.fillNotional
)]' "$output/comparison/full-ticks-paper-trades.json" >"$output/comparison/full-ticks-paper-trades-semantic.json"
jq '[.[] | del(
    .timestamp,.fxRateTimestamp,.brokerOrderId,.price,.realized,.netAccountRealized,
    .grossAccountRealized,.nativeRealized,.accountRealized,.fillNotional
)]' "$output/comparison/bars-paper-trades.json" >"$output/comparison/bars-paper-trades-semantic.json"
cmp -s "$output/comparison/full-ticks-paper-trades-semantic.json" "$output/comparison/bars-paper-trades-semantic.json" ||
    fail "bars-paper trade ownership or structure differs from full-ticks-paper"

paper_entry="$(jq -er '.[0].price' "$output/comparison/full-ticks-paper-trades.json")"
paper_exit="$(jq -er '.[1].price' "$output/comparison/full-ticks-paper-trades.json")"
mt5_entry="$(jq -er '.[0].price' "$output/comparison/full-ticks-mt5-trades.json")"
mt5_exit="$(jq -er '.[1].price' "$output/comparison/full-ticks-mt5-trades.json")"
paper_pnl="$(jq -er '.global.realizedTotal' "$output/reports/full-ticks-paper/result.json")"
mt5_pnl="$(jq -er '.global.realizedTotal' "$output/reports/full-ticks-mt5/result.json")"
bars_exit="$(jq -er '.[1].price' "$output/comparison/bars-paper-trades.json")"
bars_pnl="$(jq -er '.global.realizedTotal' "$output/reports/bars-paper/result.json")"
paper_entry_delta="$(awk -v paper="$paper_entry" -v mt5="$mt5_entry" 'BEGIN {printf "%.8f", paper-mt5}')"
paper_exit_delta="$(awk -v paper="$paper_exit" -v mt5="$mt5_exit" 'BEGIN {printf "%.8f", paper-mt5}')"
paper_pnl_delta="$(awk -v paper="$paper_pnl" -v mt5="$mt5_pnl" 'BEGIN {printf "%.8f", paper-mt5}')"
bars_exit_delta="$(awk -v bars="$bars_exit" -v ticks="$paper_exit" 'BEGIN {printf "%.8f", bars-ticks}')"
bars_pnl_delta="$(awk -v bars="$bars_pnl" -v ticks="$paper_pnl" 'BEGIN {printf "%.8f", bars-ticks}')"
[ "$paper_entry_delta" != "0.00000000" ] || [ "$paper_exit_delta" != "0.00000000" ] ||
    fail "paper fills do not expose the expected spread-model difference"
[ "$paper_pnl_delta" != "0.00000000" ] || fail "paper PnL does not expose the expected execution-model difference"

replay_git_sha="$(jq -er '.evidence.gitSha' "$output/reports/full-ticks-mt5/result.json")"
for mode_name in full-ticks-paper bars-paper; do
    [ "$(jq -er '.evidence.gitSha' "$output/reports/$mode_name/result.json")" = "$replay_git_sha" ] ||
        fail "replay modes used different QKT builds"
done
live_holding="$(awk -F '\t' 'NR == 1 {print $2}' "$output/comparison/live-indicator-exit.tsv")"
full_holding="$(awk -F '\t' 'NR == 1 {print $2}' "$output/comparison/full-ticks-paper-indicator-exit.tsv")"
bars_holding="$(awk -F '\t' 'NR == 1 {print $2}' "$output/comparison/bars-paper-indicator-exit.tsv")"

jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg bundleSha256 "$source_sha" \
    --arg captureGitSha "$(jq -er '.captureGitSha' "$external_manifest")" \
    --arg replayGitSha "$replay_git_sha" \
    --arg strategy "$(jq -er '.strategy' "$live_result")" \
    --arg fromUtc "$from_utc" --arg toUtc "$to_utc" \
    --argjson sourceCounts "$source_counts" \
    --arg liveHolding "$live_holding" --arg fullHolding "$full_holding" --arg barsHolding "$bars_holding" \
    --arg paperEntryDelta "$paper_entry_delta" --arg paperExitDelta "$paper_exit_delta" \
    --arg paperPnl "$paper_pnl" --arg mt5Pnl "$mt5_pnl" --arg paperPnlDelta "$paper_pnl_delta" \
    --arg barsExit "$bars_exit" --arg barsPnl "$bars_pnl" --arg barsExitDelta "$bars_exit_delta" \
    --arg barsPnlDelta "$bars_pnl_delta" '
    {
        schema:"qkt-live-container-round-trip-replay-comparison-v1",status:"passed",finishedAt:$finishedAt,
        source:{bundle:"source/golden.zip",bundleSha256:$bundleSha256,captureGitSha:$captureGitSha,counts:$sourceCounts},
        replay:{gitSha:$replayGitSha,strategy:$strategy,fromUtc:$fromUtc,toUtc:$toUtc,offline:true},
        modes:["full-ticks-paper","full-ticks-mt5","bars-paper"],
        parity:{
            fullTickInputCandleEvaluationCountsExact:true,twoApprovedOrdersAndFills:true,
            zeroRejectionsAndFinalFlat:true,fullTickOrderJournalsByteExact:true,
            barsOrdersTimestampNormalizedExact:true,indicatorEntryExact:true,
            indicatorExitQuantityAndCloseExact:true,liveCanonicalEntryIntentExact:true,
            liveMt5FillsProtectionPnlExact:true
        },
        indicatorHoldingSeconds:{live:($liveHolding|tonumber),fullTicks:($fullHolding|tonumber),barsPaper:($barsHolding|tonumber)},
        paperModelDifferences:{
            entryPriceDeltaFromMt5:$paperEntryDelta,exitPriceDeltaFromMt5:$paperExitDelta,
            paperPnl:$paperPnl,liveAndMt5Pnl:$mt5Pnl,pnlDeltaFromMt5:$paperPnlDelta,
            reason:"Paper fills at the tracked price without bid/ask spread; MT5 simulation and live use ask for BUY and bid for SELL."
        },
        barsPaperModelDifferences:{
            exitPrice:$barsExit,realizedPnl:$barsPnl,exitPriceDeltaFromFullTicksPaper:$barsExitDelta,
            pnlDeltaFromFullTicksPaper:$barsPnlDelta,
            reason:"Bars-paper fills at bar dispatch while full-ticks-paper fills on the tracked tick available at dispatch."
        },
        liveOnlyDifferences:[
            "Live retains venue tickets, gateway retcodes, and network/terminal latency; replay uses deterministic local identifiers and no transport.",
            "Live close targets the venue position ticket while replay close ownership targets the deterministic engine order.",
            "Heartbeat, tick-event, and exact-boundary bar clocks produce different timestamps and holding_seconds while preserving the same exit decision."
        ],
        unsupported:[
            "Plain bars with mt5-sim are unsafe because synthetic bar extremes do not preserve MT5 trigger prices or spread.",
            "Tick-resolved bars are unsupported for this mixed-timeframe strategy."
        ]
    }
' >"$output/result.json"

rm -rf "$output/work"
manifest="$output/artifact-manifest.json"
printf '{"schema":"qkt-live-container-round-trip-replay-artifacts-v1","artifacts":[' >"$manifest"
first=true
while IFS= read -r -d '' artifact; do
    relative="${artifact#"$output/"}"
    [ "$relative" = "artifact-manifest.json" ] && continue
    [ "$relative" = "SHA256SUMS" ] && continue
    if $first; then first=false; else printf ',' >>"$manifest"; fi
    jq -cn --arg path "$relative" --arg sha256 "$(sha256sum "$artifact" | awk '{print $1}')" \
        --argjson size "$(stat -c %s "$artifact")" '{path:$path,size:$size,sha256:$sha256}' >>"$manifest"
done < <(find "$output" -type f -print0 | sort -z)
printf ']}\n' >>"$manifest"
(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)

printf 'passed %s\n' "$output/result.json"
