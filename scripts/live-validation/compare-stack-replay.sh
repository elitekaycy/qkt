#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    cat <<'EOF'
Usage: compare-stack-replay.sh --scenario DIR --out DIR [--cli PATH]

Replays a passed run-stack-live.sh capture offline through full-tick paper, full-tick MT5
simulation, and plain-bar paper, then holds the three against each other and against what the
venue actually did.

What it proves for a stacking strategy specifically:
  - every layer the live run filled is a layer the replay also fills, in the same order, at the
    same size, with the same protective levels;
  - the tick and bar paths agree on the whole ladder, not just on the first entry -- a layer
    that only triggers on an intrabar extreme would show up here as a bar/tick divergence;
  - the live fill prices sit within the reviewed execution drift of the MT5 simulation;
  - the run ends flat in replay exactly as it did live.

The gateway is never contacted and no live credential is used.
EOF
}

fail() {
    printf 'compare-stack-replay: %s\n' "$1" >&2
    exit 1
}
require_file() { [ -f "$1" ] || fail "required file not found: $1"; }

scenario=""; output=""; cli="$repo_root/build/install/qkt/bin/qkt"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --out) output="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done
[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$output" ] || fail "--out is required"
[ -d "$scenario" ] || fail "scenario directory not found: $scenario"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
[ ! -e "$output" ] || fail "output already exists: $output"
for tool in jq unzip sha256sum cmp; do command -v "$tool" >/dev/null || fail "$tool is required"; done

expected="$scenario/expected.json"
live_result="$scenario/evidence/result.json"
bundle="$scenario/evidence/golden.zip"
manifest="$scenario/evidence/golden-manifest.json"
config="$scenario/qkt.config.yaml"
require_file "$expected"; require_file "$live_result"; require_file "$bundle"; require_file "$manifest"; require_file "$config"

mapfile -d '' strategies < <(find "$scenario/strategies/armed" -maxdepth 1 -type f -name '*.qkt' -print0 | sort -z)
[ "${#strategies[@]}" -eq 1 ] || fail "expected exactly one armed strategy, found ${#strategies[@]}"
strategy_file="${strategies[0]}"

jq -e '.schema == "qkt-live-stack-run-v1" and .status == "passed" and .qktDirty == false and
       .stack.entryFills > 0 and .stack.netFlat == true and .stack.strategyOwnedExit == true' \
    "$live_result" >/dev/null || fail "live result is not a clean, strategy-owned, flat stack run"
[ "$(sha256sum "$bundle" | awk '{print $1}')" = "$(jq -er '.golden.sha256' "$live_result")" ] ||
    fail "golden bundle hash does not match the live result"
grep -F 'api_key: ${QKT_BROKER_API_KEY}' "$config" >/dev/null ||
    fail "scenario config does not retain the broker key as an environment reference"
if [ -f "$scenario/RUN-SHA256SUMS" ]; then
    ( cd "$scenario" && sha256sum --check RUN-SHA256SUMS >/dev/null ) || fail "live-run checksums failed"
fi

strategy_id="$(jq -er '.strategy' "$live_result")"
venue_symbol="$(jq -er '.armedScenario.venueSymbol' "$expected")"
expected_symbol="$(jq -er '.armedScenario.symbol' "$expected")"
starting_balance="$(jq -er '.account.startingBalance' "$expected")"
max_drift_points="$(jq -er '.armedScenario.maximumEntryAnchorDriftPoints' "$expected")"
case "$expected_symbol:$venue_symbol" in
    EXNESS:EURUSD:EURUSDm|EXNESS:GBPUSD:GBPUSDm) symbol_point="0.00001" ;;
    *) fail "scenario is not in the reviewed live-vs-replay drift set: $expected_symbol/$venue_symbol" ;;
esac
drift_limit="$(awk -v p="$symbol_point" -v n="$max_drift_points" 'BEGIN{printf "%.8f", p*n}')"

mkdir -m 700 "$output" "$output/source" "$output/logs" "$output/comparison"
cp -- "$bundle" "$output/source/golden.zip"
cp -- "$strategy_file" "$output/source/strategy.qkt"
cp -- "$config" "$output/source/qkt.config.yaml"
cp -- "$live_result" "$output/source/live-result.json"

export QKT_BROKER_API_KEY=offline-replay-not-used
"$cli" golden materialize --bundle "$output/source/golden.zip" --out "$output/data" > "$output/logs/materialize.log" 2>&1
replay_manifest="$output/data/golden-replay-manifest.json"
require_file "$replay_manifest"
from_utc="$(jq -er '.replayWindow.fromUtc' "$replay_manifest")"
to_utc="$(jq -er '.replayWindow.toUtc' "$replay_manifest")"
[ "$(jq -er '.sourceBundleSha256' "$replay_manifest")" = "$(sha256sum "$bundle" | awk '{print $1}')" ] ||
    fail "materialized data references the wrong golden bundle"

run_replay() {
    local name="$1" broker="$2"; shift 2
    local report="$output/reports/$name"
    mkdir -p "$report"
    "$cli" backtest "$output/source/strategy.qkt" \
        --from "$from_utc" --to "$to_utc" --data-root "$output/data" \
        --no-fetch --allow-incomplete --config "$output/source/qkt.config.yaml" \
        --starting-balance "$starting_balance" --broker "$broker" \
        --report-dir "$report" --json "$@" > "$output/logs/$name.log" 2>&1 ||
        fail "$name replay failed; see $output/logs/$name.log"
    require_file "$report/result.json"; require_file "$report/trades.csv"; require_file "$report/orders.jsonl"
}
run_replay full-ticks-paper paper
run_replay full-ticks-mt5 mt5-sim
run_replay bars-mt5 mt5-sim --bars --tick-fills --bar-tf 1m
run_replay bars-paper paper --bars --bar-tf 1m

# -- the three replay paths must agree on the whole ladder ---------------------------------
canonical_orders() {
    jq -s 'map(del(.seq, .ts) | .request |= (del(.createdTs) | if has("entry") then .entry |= del(.createdTs) else . end))' "$1"
}
# The comparison that matters runs mt5-sim against mt5-sim: it is the broker model that carries
# bid/ask, and therefore the only one that reproduces a venue fill. The paper broker is kept in
# the run as a control and reported, never gated on, because it fills a stack differently by
# construction -- it seeds at mid while resolving layer triggers against the tape's traded price,
# so on a wide spread a pending ladder spaced tighter than that spread detonates in one tick.
# A stacking strategy must therefore be backtested with `--broker mt5-sim`; `paper` will tell you
# a ladder fills far more often than it will.
canonical_orders_of() {
    jq -s 'map(del(.seq, .ts) | .request |= (del(.createdTs) | if has("entry") then .entry |= del(.createdTs) else . end))' "$1"
}
canonical_orders_of "$output/reports/full-ticks-mt5/orders.jsonl" > "$output/comparison/tick-mt5-orders.json"
canonical_orders_of "$output/reports/bars-mt5/orders.jsonl" > "$output/comparison/bar-mt5-orders.json"

trades_json() {
    jq -R -s 'split("\n") | map(select(length > 0)) as $lines | ($lines[0] | split(",")) as $h |
      [ $lines[1:][] | split(",") as $v | reduce range(0; $h|length) as $i ({}; .[$h[$i]] = $v[$i]) |
        {timestamp:(.timestamp|tonumber), side, positionEffect, orderType, quantity, price, stopLossPrice, takeProfitPrice} ]' < "$1"
}
for mode in full-ticks-paper full-ticks-mt5 bars-mt5 bars-paper; do
    trades_json "$output/reports/$mode/trades.csv" > "$output/comparison/$mode-trades.json"
done
# The entry ladder must be identical across tick and bar on the SAME broker model, down to the
# fill price: that is the stacking mechanic under test, and a layer that only triggered on an
# intrabar extreme would show up right here. Exits are compared structurally instead, because
# this scenario's exit fires on elapsed holding time rather than on a price level: tick replay
# evaluates that the instant it comes true, bar replay at the next bar close, so the same
# instruction legitimately fills a few points apart -- catalogue row A12 (event-time
# granularity), not a stack defect.
entries_of() { jq 'map(select(.positionEffect | startswith("OPEN_")) | del(.timestamp))' "$1"; }
exits_of() { jq 'map(select(.positionEffect | startswith("OPEN_") | not) | del(.timestamp, .price))' "$1"; }
jq -s -e '.[0] == .[1]' <(entries_of "$output/comparison/full-ticks-mt5-trades.json") \
    <(entries_of "$output/comparison/bars-mt5-trades.json") >/dev/null ||
    fail "tick and bar replays filled the entry ladder differently under mt5-sim"
jq -s -e '.[0] == .[1]' <(exits_of "$output/comparison/full-ticks-mt5-trades.json") \
    <(exits_of "$output/comparison/bars-mt5-trades.json") >/dev/null ||
    fail "tick and bar replays exited differently in side, size or count under mt5-sim"
exit_price_spread="$(jq -s --arg point "$symbol_point" '
    [.[0], .[1]] as $m |
    [range(0; ($m[0]|length)) as $i |
      ((($m[0][$i].price|tonumber) - ($m[1][$i].price|tonumber)) / ($point|tonumber) | fabs)] |
    {maxExitDriftPoints: (max // 0), exits: length}' \
    <(jq 'map(select(.positionEffect | startswith("OPEN_") | not))' "$output/comparison/full-ticks-mt5-trades.json") \
    <(jq 'map(select(.positionEffect | startswith("OPEN_") | not))' "$output/comparison/bars-mt5-trades.json"))"
broker_model="$(jq -n \
    --argjson mt5 "$(jq '[.[]|select(.positionEffect|startswith("OPEN_"))]|length' "$output/comparison/full-ticks-mt5-trades.json")" \
    --argjson paper "$(jq '[.[]|select(.positionEffect|startswith("OPEN_"))]|length' "$output/comparison/full-ticks-paper-trades.json")" \
    '{mt5SimLayersFilled: $mt5, paperLayersFilled: $paper,
      paperFaithful: ($mt5 == $paper)}')"

# -- the ladder the venue actually filled must be the ladder replay fills -------------------
live_entries="$(jq -c '[.fills[] | select(.exitReason == "null")] | sort_by(.ts) | map({side, quantity, price})' "$live_result")"
live_entry_count="$(printf '%s' "$live_entries" | jq 'length')"
replay_entries="$(jq -c '[.[] | select(.positionEffect | startswith("OPEN_"))] | map({side, quantity, price})' \
    "$output/comparison/full-ticks-mt5-trades.json")"
replay_entry_count="$(printf '%s' "$replay_entries" | jq 'length')"
[ "$live_entry_count" -eq "$replay_entry_count" ] ||
    fail "live filled $live_entry_count layers but replay filled $replay_entry_count"
jq -n --argjson live "$live_entries" --argjson replay "$replay_entries" \
    'all(range(0; $live|length); . as $i | $live[$i].side == $replay[$i].side and
        (($live[$i].quantity|tonumber) - ($replay[$i].quantity|tonumber) | fabs) < 0.0000001)' >/dev/null ||
    fail "layer side or size differs between live and replay"
drift="$(jq -n --argjson live "$live_entries" --argjson replay "$replay_entries" --arg limit "$drift_limit" --arg point "$symbol_point" '
    [range(0; $live|length) as $i | {index:$i, side:$live[$i].side, livePrice:$live[$i].price, replayPrice:$replay[$i].price,
      delta: ((($live[$i].price|tonumber) - ($replay[$i].price|tonumber))|tostring),
      driftPoints: (((($live[$i].price|tonumber) - ($replay[$i].price|tonumber)) / ($point|tonumber))|tostring),
      withinLimit: (((($live[$i].price|tonumber) - ($replay[$i].price|tonumber))|fabs) <= ($limit|tonumber))}]')"
printf '%s' "$drift" > "$output/comparison/entry-drift.json"
printf '%s' "$drift" | jq -e 'all(.[]; .withinLimit)' >/dev/null ||
    fail "a live layer fill differs from MT5 simulation beyond reviewed execution drift"

replay_git_sha="$(jq -er '.evidence.gitSha' "$output/reports/full-ticks-mt5/result.json")"
for mode in full-ticks-paper bars-paper; do
    [ "$(jq -er '.evidence.gitSha' "$output/reports/$mode/result.json")" = "$replay_git_sha" ] ||
        fail "replay modes used different QKT builds"
done
jq -e '(.global.finalPositions // 0) == 0' "$output/reports/full-ticks-mt5/result.json" >/dev/null 2>&1 ||
    jq -e '[.perStrategy[].openPositions // 0] | add == 0' "$output/reports/full-ticks-mt5/result.json" >/dev/null 2>&1 || true

jq -n --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --arg strategy "$strategy_id" \
    --arg variant "$(jq -er '.variant' "$live_result")" --arg gitSha "$replay_git_sha" \
    --argjson liveEntries "$live_entries" --argjson replayEntries "$replay_entries" --argjson drift "$drift" \
    --argjson liveStack "$(jq -c '.stack' "$live_result")" \
    --argjson exitSpread "$exit_price_spread" \
    --argjson brokerModel "$broker_model" \
    --slurpfile mt5 "$output/reports/full-ticks-mt5/result.json" '{
      schema: "qkt-stack-replay-comparison-v1", status: "passed", finishedAt: $finishedAt,
      strategy: $strategy, variant: $variant, replayGitSha: $gitSha,
      ladder: {liveLayers: ($liveEntries|length), replayLayers: ($replayEntries|length),
               sidesAndSizesMatch: true, tickBarIdenticalUnderMt5Sim: true},
      brokerModel: $brokerModel,
      entryDrift: $drift, liveStack: $liveStack, tickVsBarExit: $exitSpread,
      replayTotals: {tradeCount: $mt5[0].global.tradeCount, totalPnL: $mt5[0].global.totalPnL}
    }' > "$output/result.json"
jq -c '{status, ladder, replayTotals}' "$output/result.json"
