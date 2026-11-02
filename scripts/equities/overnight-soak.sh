#!/usr/bin/env bash
# overnight-soak.sh --config FILE --symbol EXNESS:AAPL --out DIR --gateway-url URL --arm I_UNDERSTAND_DEMO_ORDER_0.01
#
# What a stock CFD costs to hold overnight, measured at the venue, for the facts no five-minute attestation case
# can reach: when the swap is booked and how much (a Friday books three nights), and how a dividend on an ex-date
# arrives (a dividend deal, a balance operation, or folded into the swap). Opens 0.01 lot (one share) through
# `qkt bot`, records the position's swap and the account's non-trade deals once a minute, and closes it with
# `qkt bot close` once the next session has been open ten minutes. The evidence is `swap.jsonl` (one line a minute:
# time, the position's swap, the account balance) and `adjustments.json` (the non-trade deals booked meanwhile),
# which `overnight-judge.py` compares with qkt's swap model and with what qkt reported.
#
# Demo accounts only: the account's trade mode is checked first, and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY
# is required as for every order-placing live validation.
set -Eeuo pipefail

config="" symbol="" out="" gateway="" arm="" cli="${QKT_CLI:-qkt}"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --config) config=$2; shift 2 ;;
        --symbol) symbol=$2; shift 2 ;;
        --out) out=$2; shift 2 ;;
        --gateway-url) gateway=$2; shift 2 ;;
        --arm) arm=$2; shift 2 ;;
        --cli) cli=$2; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$config" ] && [ -n "$symbol" ] && [ -n "$out" ] && [ -n "$gateway" ] || { echo "usage: see the header" >&2; exit 2; }
[ "$arm" = "I_UNDERSTAND_DEMO_ORDER_0.01" ] || { echo "--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required" >&2; exit 2; }
[ "${QKT_LIVE_DEMO_ORDER_APPROVAL:-}" = "LOCALHOST_DEMO_ONLY" ] || { echo "QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required" >&2; exit 2; }
[[ "$gateway" =~ ^http://(127\.0\.0\.1|localhost):[0-9]+$ ]] || { echo "only a loopback gateway is allowed" >&2; exit 2; }
mkdir -p "$out"

get() { curl --silent --show-error --fail --max-time 20 "$gateway$1"; }
get /account > "$out/account-initial.json"
jq -e '.trade_mode == 0' "$out/account-initial.json" > /dev/null || { echo "not a demo account" >&2; exit 1; }

broker_symbol="$(echo "${symbol#*:}")m"
start_ms="$(date +%s%3N)"
"$cli" bot buy 0.01 "$symbol" --config "$config" --json > "$out/open.json"
ticket="$(jq -er '.ticket // .positionTicket // .venueTicket' "$out/open.json")"
echo "opened $symbol ticket $ticket" >&2

# The session the position must survive into: closed when the stock trades again and has for ten minutes.
reopened_for=0
while :; do
    now="$(date -u +%FT%TZ)"
    pos="$(get "/get_positions" | jq -c --arg t "$ticket" '[(.data // [])[] | select((.ticket|tostring) == $t)] | first // {}')"
    bal="$(get /account | jq '.balance')"
    printf '{"at":"%s","swap":%s,"profit":%s,"balance":%s}\n' "$now" "$(jq '.swap // null' <<< "$pos")" \
        "$(jq '.profit // null' <<< "$pos")" "$bal" >> "$out/swap.jsonl"
    [ "$(jq 'length' <<< "$pos")" = 0 ] && { echo "position $ticket is gone" >&2; break; }
    tick="$(get "/symbol_info_tick/$broker_symbol" | jq '.time // 0')"
    fresh=$(( $(date +%s) - tick < 120 ))
    elapsed=$(( $(date +%s%3N) - start_ms ))
    # Fresh quotes after a night: the next session is open. Counted only once a night has passed.
    if [ "$fresh" = 1 ] && [ "$elapsed" -gt $((10 * 3600 * 1000)) ]; then reopened_for=$((reopened_for + 1)); fi
    [ "$reopened_for" -ge 10 ] && break
    sleep 60
done

"$cli" bot close "$symbol" --ticket "$ticket" --config "$config" --json > "$out/close.json" || true
end_ms="$(date +%s%3N)"
python3 - "$gateway" "$start_ms" "$end_ms" > "$out/adjustments.json" <<'PY'
import json, sys, urllib.request, datetime as dt
gw, a, b = sys.argv[1], int(sys.argv[2]) // 1000, int(sys.argv[3]) // 1000
iso = lambda s: dt.datetime.fromtimestamp(s, dt.UTC).strftime("%Y-%m-%dT%H:%M:%S")
r = json.load(urllib.request.urlopen(f"{gw}/history_deals_range?from_date={iso(a)}&to_date={iso(b + 60)}", timeout=60))
deals = next((v for v in r.values() if isinstance(v, list)), [])
print(json.dumps([d for d in deals if d.get("type") not in (0, 1)] + [d for d in deals if d.get("type") in (0, 1)], indent=1))
PY
echo "done: $out" >&2
