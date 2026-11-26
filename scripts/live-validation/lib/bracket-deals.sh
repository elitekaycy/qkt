# Counts the venue deals a market-bracket case expects in a `qkt bot history --json` snapshot.
#
# Usage: count_bracket_deals FILE VENUE_SYMBOL ENTRY
# Prints how many 0.01-lot deals in FILE are on VENUE_SYMBOL (the broker's name, e.g. EURUSDm) with
# the given ENTRY (IN or OUT). Deals carry the broker's name in venueSymbol (their symbol is the qkt
# symbol since #1732); a history without venueSymbol is matched on symbol.
count_bracket_deals() {
    local file="$1" venue_symbol="$2" entry="$3"
    jq --arg venueSymbol "$venue_symbol" --arg entry "$entry" \
        '[.[] | select((.venueSymbol // .symbol) == $venueSymbol and .entry == $entry and .lots == 0.01)] | length' "$file"
}
