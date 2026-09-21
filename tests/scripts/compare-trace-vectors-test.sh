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

# A silent feed is reported as a quiet market (exit 3), never as a pass; a busy feed that produced
# no vector, or a quiet one that still disagrees with its replay, stays a failure.
code=0; python3 "$tool" --live "$tmp/empty.log" --replay ticks="$tmp/empty.log" --live-ticks 3 --quiet-below 16 --out "$tmp/quiet.json" >/dev/null || code=$?
[ "$code" = 3 ] && [ "$(jq -r .status "$tmp/quiet.json")" = market-quiet ] && [ "$(jq -r .liveTicks "$tmp/quiet.json")" = 3 ]
echo "ok no vectors from a feed that barely ticked is market-quiet, exit 3"
code=0; python3 "$tool" --live "$tmp/empty.log" --replay ticks="$tmp/empty.log" --live-ticks 400 --quiet-below 16 --out "$tmp/busy.json" >/dev/null || code=$?
[ "$code" = 1 ] && [ "$(jq -r .status "$tmp/busy.json")" = failed ]
echo "ok no vectors from a feed that ticked normally is a failure"
code=0; python3 "$tool" --live "$tmp/empty.log" --replay ticks="$tmp/same.log" --live-ticks 3 --quiet-below 16 --out "$tmp/ghost.json" >/dev/null || code=$?
[ "$code" = 1 ] && [ "$(jq -r .status "$tmp/ghost.json")" = failed ]
echo "ok a replay that logs what live never did is a failure even on a quiet feed"
