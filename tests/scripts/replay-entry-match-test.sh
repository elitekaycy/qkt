#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lib="$repo_root/scripts/live-validation/lib"
bash -n "$repo_root/scripts/live-validation/compare-golden-replay.sh"
grep -q 'include "replay-entry-match"' "$repo_root/scripts/live-validation/compare-golden-replay.sh"
grep -q 'sent_target_matches(' "$repo_root/scripts/live-validation/compare-golden-replay.sh"

matches() {  # intent-json request-json -> exit 0 when the sent target matches
    jq -en -L "$lib" --argjson intent "$1" --argjson request "$2" \
        'include "replay-entry-match"; sent_target_matches($intent; $request)' >/dev/null
}
expect_match() { matches "$1" "$2" || { echo "expected a match: $3" >&2; exit 1; }; }
expect_mismatch() { if matches "$1" "$2"; then echo "expected a mismatch: $3" >&2; exit 1; fi; }

# Recorded by the v0.53.0 attestation on testing f8a99b69 (atr-eurusd): a BY target is attached at
# fill, so the live request carried a stop and no target.
by_intent='{"side":"SELL","qty":0.01,"stopLoss":{"type":"Fixed","price":1.14106},"takeProfit":1.13206,
  "takeProfitAst":{"type":"By","distance":{"type":"NumLit","value":0.0060},"ratchet":null}}'
live_request='{"symbol":"EURUSDm","side":"SELL","quantity":"0.01","stopLossPrice":"1.14106","takeProfitPrice":"null"}'
expect_match "$by_intent" "$live_request" "relative target attached at fill"
expect_match "$by_intent" '{"takeProfitPrice":null}' "relative target, JSON null"

# A relative target sent with the entry would mean attach-at-fill regressed.
expect_mismatch "$by_intent" '{"takeProfitPrice":"1.13206"}' "relative target sent with the entry"

pct_intent='{"takeProfit":1.13206,"takeProfitAst":{"type":"Pct","percent":{"type":"NumLit","value":0.5}}}'
rr_intent='{"takeProfit":1.13206,"takeProfitAst":{"type":"Rr","multiplier":{"type":"NumLit","value":2}}}'
expect_match "$pct_intent" '{"takeProfitPrice":"null"}' "PCT target is relative"
expect_match "$rr_intent" '{"takeProfitPrice":"null"}' "RR target is relative"

# An absolute AT target is still sent with the entry and must equal the intent.
at_intent='{"takeProfit":1.13206,"takeProfitAst":null}'
expect_match "$at_intent" '{"takeProfitPrice":"1.13206"}' "absolute target equal"
expect_mismatch "$at_intent" '{"takeProfitPrice":"1.13306"}' "absolute target differs"
if jq -en -L "$lib" --argjson intent "$at_intent" --argjson request '{"takeProfitPrice":"null"}' \
    'include "replay-entry-match"; sent_target_matches($intent; $request)' >/dev/null 2>&1; then
    echo "expected an absolute target that was not sent to fail" >&2
    exit 1
fi

echo "replay-entry-match tests passed"
