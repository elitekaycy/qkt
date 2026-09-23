#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
helper="$repo_root/scripts/live-validation/lib/attached-protection.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

bash -n "$helper"
for runner in run-market-bracket.sh run-order-gateway-restart.sh run-insights-attribution.sh run-container-round-trips.sh; do
    bash -n "$repo_root/scripts/live-validation/$runner"
    grep -q 'wait_for_attached_protection' "$repo_root/scripts/live-validation/$runner"
done

# shellcheck source=scripts/live-validation/lib/attached-protection.sh
source "$helper"

position() { printf '{"ok":true,"data":[{"ticket":7,"sl":%s,"tp":%s}]}' "$1" "$2"; }
sleep() { :; }

# The fake gateway answers each read with the next queued response and counts the reads.
queue="$tmp/queue"; reads="$tmp/reads"
gateway_get() {
    local next
    next="$(head -n 1 "$queue")"
    tail -n +2 "$queue" > "$queue.rest" && mv "$queue.rest" "$queue"
    echo x >> "$reads"
    printf '%s\n' "$next"
}
reset() { : > "$queue"; : > "$reads"; }

# A target attached by the fill-time modify two reads after the fill is waited for.
reset
position 1.1415 0 > "$tmp/position.json"
{ position 1.1415 0; echo; position 1.1415 1.1325; echo; } > "$queue"
wait_for_attached_protection 42 "$tmp/position.json" 15
jq -e '.data[0].tp == 1.1325' "$tmp/position.json" >/dev/null
[ "$(wc -l < "$reads")" -eq 2 ]

# Protection already on the first read returns at once without another read.
reset
position 1.1415 1.1325 > "$tmp/position.json"
wait_for_attached_protection 42 "$tmp/position.json" 15
[ "$(wc -l < "$reads")" -eq 0 ]

# A target that never arrives times out, leaving the last read for the caller's contract check.
reset
position 1.1415 0 > "$tmp/position.json"
for _ in 1 2 3; do position 1.1415 0; echo; done > "$queue"
if wait_for_attached_protection 42 "$tmp/position.json" 3; then
    echo 'expected a missing target to time out' >&2
    exit 1
fi
[ "$(wc -l < "$reads")" -eq 3 ]
jq -e '.data[0].tp == 0' "$tmp/position.json" >/dev/null

# No position at all is not protection.
reset
printf '{"ok":true,"data":[]}' > "$tmp/position.json"
printf '{"ok":true,"data":[]}\n' > "$queue"
if wait_for_attached_protection 42 "$tmp/position.json" 1; then
    echo 'expected an empty position list to fail' >&2
    exit 1
fi

echo "attached-protection tests passed"
