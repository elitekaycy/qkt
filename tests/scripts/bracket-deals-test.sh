#!/usr/bin/env bash
# `qkt bot history` names each deal by its qkt symbol (EXNESS:EURUSD) and keeps the broker's name in
# venueSymbol (EURUSDm) since #1732; the market-bracket harness must still find the case's deals.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=scripts/live-validation/lib/bracket-deals.sh
source "$repo_root/scripts/live-validation/lib/bracket-deals.sh"
history="$repo_root/tests/scripts/fixtures/bot-history-qkt-symbols.json"

expect() {
    local venue_symbol="$1" entry="$2" want="$3" got
    got="$(count_bracket_deals "$history" "$venue_symbol" "$entry")"
    if [ "$got" != "$want" ]; then
        echo "count_bracket_deals $venue_symbol $entry: expected $want, got $got" >&2
        exit 1
    fi
}

expect EURUSDm IN 2
expect EURUSDm OUT 2
expect GBPUSDm IN 2
expect GBPUSDm OUT 2
expect XAUUSDm IN 0
echo "bracket-deals: ok"
