#!/usr/bin/env bash
# Fetch a single day of tick data for one symbol from Dukascopy and write to qkt CSV format.
#
# Usage: fetch-dukascopy.sh SYMBOL YYYY-MM-DD TARGET_PATH
#   SYMBOL      e.g. EURUSD
#   YYYY-MM-DD  the calendar date (UTC) to fetch
#   TARGET_PATH where to write the gzipped CSV (e.g. ~/.qkt/data/symbols/EURUSD/2024-01-15.csv.gz)
#
# Requires: Node.js 18+, dukascopy-node installed globally:
#   npm i -g dukascopy-node

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: $0 SYMBOL YYYY-MM-DD TARGET_PATH" >&2
    exit 64
fi

symbol="$1"
day="$2"
target="$3"

if ! command -v npx >/dev/null 2>&1; then
    echo "node/npx not found; install Node.js 18+ from https://nodejs.org" >&2
    exit 1
fi

mkdir -p "$(dirname "$target")"

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

next_day=$(date -d "$day + 1 day" +%Y-%m-%d 2>/dev/null || \
           date -j -v+1d -f %Y-%m-%d "$day" +%Y-%m-%d)

sym_lower=$(echo "$symbol" | tr '[:upper:]' '[:lower:]')
npx --yes dukascopy-node@^4 \
    -i "$sym_lower" \
    -from "$day" \
    -to "$next_day" \
    -t tick \
    -f csv \
    -d "$tmpdir" \
    -fn raw >/dev/null

{
    echo "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume"
    if [[ -f "$tmpdir/raw.csv" ]]; then
        awk -F',' -v sym="$symbol" '
            NR > 1 && NF >= 5 {
                mid = ($2 + $3) / 2.0
                printf "%s,%s,%s,,%s,%s,%s,%s\n", $1, sym, mid, $3, $2, $5, $4
            }
        ' "$tmpdir/raw.csv"
    fi
} | gzip > "$target"

echo "wrote $target"
