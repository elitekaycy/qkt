#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
scenario=""
output=""
cli="$repo_root/build/install/qkt/bin/qkt"
verify_only=false

usage() {
    cat <<'EOF'
Usage: compare-readonly-replay.sh --scenario DIR --out DIR [--cli PATH] [--verify-only]

Verifies a strict read-only MT5 golden capture, replays it through full-tick paper,
full-tick MT5 simulation, and plain-bar paper modes, then compares warmup, M1/M5
strategy traces, replay input counts, and zero-trade accounting. No gateway is contacted.
EOF
}

fail() {
    printf 'compare-readonly-replay: %s\n' "$*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || fail "required file not found: $1"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --scenario) scenario="${2:-}"; shift 2 ;;
        --out) output="${2:-}"; shift 2 ;;
        --cli) cli="${2:-}"; shift 2 ;;
        --verify-only) verify_only=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) fail "unknown argument: $1" ;;
    esac
done

[ -n "$scenario" ] || fail "--scenario is required"
[ -n "$output" ] || fail "--out is required"
[ -d "$scenario" ] || fail "scenario directory not found: $scenario"
[ -x "$cli" ] || fail "qkt CLI is not executable: $cli"
for command in awk cmp cp find jq sed sha256sum sort unzip; do
    command -v "$command" >/dev/null 2>&1 || fail "required command not found: $command"
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
live_log="$scenario/logs/daemon.log"
for file in "$expected" "$scenario_json" "$config" "$live_result" "$bundle" "$external_manifest" "$live_log"; do
    require_file "$file"
done

mapfile -d '' strategies < <(find "$scenario/strategies/readonly" -maxdepth 1 -type f -name '*.qkt' -print0 | sort -z)
[ "${#strategies[@]}" -eq 1 ] || fail "expected exactly one read-only strategy"
strategy_file="${strategies[0]}"

jq -e '
    .schema == "qkt-live-validation-readonly-v1" and
    .status == "passed" and
    .qktDirty == false and
    .initialPositions == 0 and .initialOrders == 0 and
    .finalPositions == 0 and .finalOrders == 0 and
    .venueDealsDuringRun == 0 and
    .accountIdentityUnchanged == true and
    .financialStateUnchanged == true and
    .accountUnchanged == true and
    .golden.ticks > 0 and .golden.warmupTicks > 0 and
    .golden.candles > 0 and .golden.streamCandles > 0 and
    .golden.fills == 0 and .golden.linkedPlacements == 0 and .golden.mutations == 0
' "$live_result" >/dev/null || fail "live result is not a clean, passed read-only run"
jq -e '.qktDirty == false and .credentialsStored == false' "$scenario_json" >/dev/null ||
    fail "scenario is dirty or retained credentials"
jq -e '
    .schemaVersion == 2 and .kind == "MT5_GOLDEN_CAPTURE" and
    .captureMode == "READ_ONLY" and
    .counts.ticks > 0 and .counts.warmupTicks > 0 and
    .counts.candles > 0 and .counts.streamCandles > 0 and
    .counts.fills == 0 and .counts.linkedPlacements == 0 and .counts.mutations == 0
' "$external_manifest" >/dev/null || fail "golden manifest is not strict read-only evidence"
cmp -s "$external_manifest" <(unzip -p "$bundle" manifest.json) ||
    fail "external golden manifest differs from bundle manifest"

bundle_sha="$(sha256sum "$bundle" | awk '{print $1}')"
[ "$bundle_sha" = "$(jq -er '.golden.sha256' "$live_result")" ] || fail "golden bundle hash differs from live result"
while IFS=$'\t' read -r path expected_sha; do
    actual_sha="$(unzip -p "$bundle" "$path" | sha256sum | awk '{print $1}')"
    [ "$actual_sha" = "$expected_sha" ] || fail "golden entry hash mismatch: $path"
done < <(jq -r '.entries[] | [.path,.sha256] | @tsv' "$external_manifest")
if [ -f "$scenario/RUN-SHA256SUMS" ]; then
    (cd "$scenario" && sha256sum --check RUN-SHA256SUMS >/dev/null) || fail "live-run checksums failed"
fi

if $verify_only; then
    [ ! -e "$output" ] || fail "output already exists: $output"
    printf 'verified %s\n' "$scenario"
    exit 0
fi

[ ! -e "$output" ] || fail "output already exists: $output"
mkdir -m 700 "$output" "$output/logs" "$output/reports" "$output/source" "$output/state"
cp -- "$bundle" "$output/source/golden.zip"
cp -- "$external_manifest" "$output/source/golden-manifest.json"
cp -- "$live_result" "$output/source/live-result.json"
cp -- "$expected" "$output/source/expected.json"
cp -- "$scenario_json" "$output/source/scenario.json"
cp -- "$strategy_file" "$output/source/strategy.qkt"
cp -- "$config" "$output/source/qkt.config.yaml"
cp -- "$live_log" "$output/source/live.log"

export QKT_BROKER_API_KEY=offline-replay-not-used
"$cli" golden materialize --bundle "$output/source/golden.zip" --out "$output/data" \
    >"$output/logs/materialize.log" 2>&1
replay_manifest="$output/data/golden-replay-manifest.json"
require_file "$replay_manifest"
[ "$(jq -er '.sourceBundleSha256' "$replay_manifest")" = "$bundle_sha" ] ||
    fail "materialized data references the wrong bundle"
jq -e '
    (.timeframes | index("1m")) != null and
    (.timeframes | index("5m")) != null and
    .counts.ticks > 0 and .counts.warmupTicks > 0 and
    .counts.streamCandles > 0 and .counts.materializedCandles > .counts.candles
' "$replay_manifest" >/dev/null || fail "materialized data lacks timeframe-complete bars"

from_utc="$(jq -er '.replayWindow.fromUtc' "$replay_manifest")"
to_utc="$(jq -er '.replayWindow.toUtc' "$replay_manifest")"
starting_balance="$(jq -er '.account.startingBalance' "$expected")"
source_ticks="$(jq -er '.counts.ticks' "$external_manifest")"
source_warmup_ticks="$(jq -er '.counts.warmupTicks' "$external_manifest")"
live_stream_counts="$({
    while IFS= read -r entry; do
        unzip -p "$bundle" "$entry"
    done < <(unzip -Z1 "$bundle" | sed -nE '/^engine\/.*\.jsonl$/p')
} | jq -sc '
    [ .[] | select(.eventType == "com.qkt.events.StreamCandleEvent") ] |
    group_by(.broker + ":" + (.symbol | split(":")[-1]) + ":" + .timeframe) |
    map({key:(.[0].broker + ":" + (.[0].symbol | split(":")[-1]) + ":" + .[0].timeframe), value:length}) |
    from_entries
')"
[ "$(jq -r '[.[]] | add // 0' <<<"$live_stream_counts")" -eq "$(jq -er '.counts.streamCandles' "$external_manifest")" ] ||
    fail "grouped live stream candles differ from the manifest"

run_replay() {
    local name="$1"
    local broker="$2"
    shift 2
    QKT_STATE_DIR="$output/state/$name" "$cli" backtest "$output/source/strategy.qkt" \
        --from "$from_utc" --to "$to_utc" \
        --data-root "$output/data" --no-fetch --allow-incomplete \
        --config "$output/source/qkt.config.yaml" \
        --starting-balance "$starting_balance" --broker "$broker" \
        --report-dir "$output/reports/$name" --json "$@" \
        >"$output/logs/$name.stdout.log" 2>"$output/logs/$name.stderr.log"
    require_file "$output/reports/$name/result.json"
    require_file "$output/reports/$name/trades.csv"
    jq -e --arg balance "$starting_balance" '
        .global.tradeCount == 0 and .trades == 0 and .halts == 0 and
        .tradeSummary.fills == 0 and .tradeSummary.rejections == 0 and
        .finalRealized == 0 and .finalUnrealized == 0 and .totalPnL == 0 and
        .commissionPaid == 0 and .swapPaid == 0 and .turnover == 0 and
        (.runawayBreaker.trips | length) == 0 and
        (.accounting.warnings | length) == 0 and
        all(.global.equityCurve[]; .equity == ($balance | tonumber))
    ' "$output/reports/$name/result.json" >/dev/null || fail "$name produced non-flat trading or accounting output"
    [ "$(awk 'END {print NR}' "$output/reports/$name/trades.csv")" -eq 1 ] || fail "$name produced a trade row"
}

run_replay full-ticks-paper paper
run_replay full-ticks-mt5 mt5-sim
run_replay bars-paper paper --bars --bar-tf 1m

for mode in full-ticks-paper full-ticks-mt5; do
    jq -e --argjson ticks "$source_ticks" --argjson warmup "$source_warmup_ticks" --argjson streams "$live_stream_counts" '
        .inputSummary.liveTicks == $ticks and
        .inputSummary.warmupTicks == $warmup and
        .inputSummary.streamCandles == $streams and
        .inputSummary.malformedTicks == 0 and
        .inputSummary.droppedLateTicks == 0
    ' "$output/reports/$mode/result.json" >/dev/null || fail "$mode input counts differ from captured live evidence"
done
jq -e --argjson warmup "$source_warmup_ticks" --argjson streams "$live_stream_counts" '
    .inputSummary.warmupTicks == $warmup and
    .inputSummary.liveTicks > 0 and
    .inputSummary.streamCandles == $streams and
    .inputSummary.malformedTicks == 0 and
    .inputSummary.droppedLateTicks == 0
' "$output/reports/bars-paper/result.json" >/dev/null || fail "bar replay input accounting failed"

extract_warmup() {
    sed -nE 's/.*warmup: seeded hub for strategy=[^ ]+ alias=([^ ]+) symbol=([^ ]+) bars=([0-9]+).*/\1\t\2\t\3/p' "$1" | sort -u
}

extract_traces() {
    awk '
        /closed bar trace timeframe=/ {
            tf = ema = kind = value = close = ""
            for (i = 1; i <= NF; i++) {
                split($i, pair, "=")
                if (pair[1] == "timeframe") tf = pair[2]
                if (pair[1] == "ema") ema = pair[2]
                if (pair[1] == "rsi" || pair[1] == "atr") { kind = pair[1]; value = pair[2] }
                if (pair[1] == "close") close = pair[2]
            }
            if (tf != "" && ema != "" && kind != "" && value != "" && close != "") {
                printf "%s\t%.8f\t%s\t%.8f\t%.8f\n", tf, ema, kind, value, close
            }
        }
    ' "$1" | sort -u
}

extract_warmup "$output/source/live.log" >"$output/live-warmup.tsv"
extract_traces "$output/source/live.log" >"$output/live-traces.tsv"
[ "$(awk 'END {print NR}' "$output/live-warmup.tsv")" -eq 2 ] || fail "live evidence lacks two warmup streams"
[ "$(awk 'END {print NR}' "$output/live-traces.tsv")" -eq 2 ] || fail "live evidence lacks exact M1/M5 traces"
for mode in full-ticks-paper full-ticks-mt5 bars-paper; do
    extract_warmup "$output/logs/$mode.stdout.log" >"$output/$mode-warmup.tsv"
    extract_traces "$output/logs/$mode.stdout.log" >"$output/$mode-traces.tsv"
    cmp -s "$output/live-warmup.tsv" "$output/$mode-warmup.tsv" || fail "$mode warmup differs from live"
    cmp -s "$output/live-traces.tsv" "$output/$mode-traces.tsv" || fail "$mode M1/M5 traces differ from live"
done

capture_sha="$(jq -er '.captureGitSha' "$external_manifest")"
replay_sha="$(jq -er '.evidence.gitSha' "$output/reports/full-ticks-paper/result.json")"
for mode in full-ticks-mt5 bars-paper; do
    [ "$(jq -er '.evidence.gitSha' "$output/reports/$mode/result.json")" = "$replay_sha" ] ||
        fail "replay modes used different builds"
done
[ "$capture_sha" = "$replay_sha" ] || fail "capture and replay builds differ: $capture_sha != $replay_sha"

jq -n \
    --arg finishedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg bundleSha "$bundle_sha" \
    --arg captureSha "$capture_sha" \
    --arg replaySha "$replay_sha" \
    --arg fromUtc "$from_utc" \
    --arg toUtc "$to_utc" \
    --argjson counts "$(jq -c '.counts' "$external_manifest")" '
    {
      schema:"qkt-live-readonly-replay-comparison-v1",
      status:"passed",
      finishedAt:$finishedAt,
      source:{bundleSha256:$bundleSha,captureGitSha:$captureSha,counts:$counts},
      replay:{gitSha:$replaySha,fromUtc:$fromUtc,toUtc:$toUtc},
      parity:{warmupExact:true,m1TraceExact:true,m5TraceExact:true,inputCountsExact:true,accountingFlat:true},
      modes:["full-ticks-paper","full-ticks-mt5","bars-paper"]
    }
' >"$output/result.json"

(
    cd "$output"
    find . -type f ! -path './SHA256SUMS' -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
    sha256sum --check SHA256SUMS >/dev/null
)
printf 'passed %s\n' "$output/result.json"
