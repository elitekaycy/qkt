#!/usr/bin/env bash
# The trace comparator must pass identical vectors and fail on a last-digit difference, a
# missing vector, or a run that compared nothing.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tool="$repo_root/scripts/live-validation/compare-trace-vectors.py"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

line() { printf '%s [%s] INFO  [s] c.q.d.s.demo_strategy - closed bar trace timeframe=1m ema=%s rsi=%s\n' "$1" "$2" "$3" "$4"; }
{ line 22:08:00.623 qkt-live-engine 1.14844592 78.82314074; line 22:09:00.101 qkt-live-engine 1.14845001 61.5; } > "$tmp/live.log"
{ line 23:21:54.560 main 1.14844592 78.82314074; line 23:21:54.566 main 1.14845001 61.5; } > "$tmp/same.log"
{ line 23:21:54.560 main 1.14844592 78.82314074; line 23:21:54.566 main 1.14845002 61.5; } > "$tmp/digit.log"
line 23:21:54.560 main 1.14844592 78.82314074 > "$tmp/short.log"
printf 'no vectors here\n' > "$tmp/empty.log"

python3 "$tool" --live "$tmp/live.log" --replay ticks="$tmp/same.log" --out "$tmp/ok.json" >/dev/null
[ "$(jq -r .status "$tmp/ok.json")" = passed ] && [ "$(jq -r .modes.ticks.valuesCompared "$tmp/ok.json")" = 6 ]
echo "ok identical vectors pass on wall-clock-independent matching"

expect_failure() {  # name replay-log jq-filter
    if python3 "$tool" --live "$tmp/live.log" --replay ticks="$2" --out "$tmp/bad.json" >/dev/null; then echo "FAIL $1: passed"; exit 1; fi
    jq -e "$3" "$tmp/bad.json" >/dev/null || { echo "FAIL $1: $(cat "$tmp/bad.json")"; exit 1; }
    echo "ok $1"
}
expect_failure "last-digit difference" "$tmp/digit.log" '.mismatches[0] | .field == "ema" and .live == "1.14845001" and .replay == "1.14845002"'
expect_failure "missing replay vector" "$tmp/short.log" '.mismatches | any(.problem == "missing in replay")'
expect_failure "nothing compared" "$tmp/empty.log" '.status == "failed"'
