#!/usr/bin/env bash
# A holiday (US Thanksgiving 2026-11-26) falls inside every session lane's window by the clock, and only the venue's own
# quote can tell: each session-bound share case, on every lane, must record `session` (quote stale) and not run, while a
# `session: none` coin case runs as always; on an ordinary day with a fresh quote the share cases run.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fake="$tmp/repo"; live="$fake/scripts/live-validation"
mkdir -p "$live" "$fake/attestation/lib" "$fake/bin" "$tmp/path"
cp "$repo_root/scripts/live-validation/run-attestation-catalog.sh" "$live/"
cp -r "$repo_root/scripts/live-validation/lib" "$live/"
printf 'print("ok")\n' > "$fake/attestation/lib/validate.py"
printf '#!/usr/bin/env bash\necho "qkt 0.0.0 (test)"\n' > "$fake/bin/qkt"; chmod +x "$fake/bin/qkt"
cat > "$fake/attestation/lanes.yaml" <<'YAML'
lanes:
  stocks:
    session: nyse[09:40-15:45]
    budget_seconds: 300
  alpaca:
    session: nyse[09:30-16:00]
    venue_gateway: true
    budget_seconds: 420
YAML
for case in stocks/stock-share alpaca/alpaca-share alpaca/alpaca-coin; do
    mkdir -p "$fake/attestation/cases/$case"
    printf 'id: %s\nstatus: ready\n' "${case#*/}" > "$fake/attestation/cases/$case/case.yaml"
done
echo 'session: none' >> "$fake/attestation/cases/alpaca/alpaca-coin/case.yaml"
# Stub runners: note the case, pass.
cat > "$live/run-derivatives-lane-case.py" <<'STUB'
#!/usr/bin/env python3
import json, os, sys
args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
case, out = os.path.basename(args["--case"]), args["--out"]
os.makedirs(out)
open(os.environ["STUB_CALLS"], "a").write(case + "\n")
json.dump({"status": "passed", "problems": []}, open(f"{out}/result.json", "w"))
print("passed " + case)
STUB
cp "$live/run-derivatives-lane-case.py" "$live/run-daemon-lane-case.py"
printf 'exec python3 "$(dirname "$0")/run-daemon-lane-case.py" "$@"\n' > "$live/run-order-lane-case.sh"
# The venues: the MT5 gateway's AAPLm tick and the VGP gateway's AAPL 1m bars, as of a day ago (holiday) or now.
cat > "$tmp/path/curl" <<'CURL'
#!/usr/bin/env bash
url="${*: -1}"; now="$(date +%s)"; [ "$QUOTE" = fresh ] || now=$((now - 86400))
case "$url" in
    */symbol_info_tick/AAPLm) printf '{"time": %s}' "$now" ;;
    */v1/bars\?symbol=AAPL\&window_ms=60000\&*)
        [ "$QUOTE" = fresh ] && printf '{"bars": [{"start": %s, "close": "1"}]}' "$(( (now - 120) * 1000 ))" ||
            printf '{"bars": []}' ;;
    *) exit 22 ;;
esac
CURL
chmod +x "$tmp/path/curl"

run() {  # out-dir quote(fresh|holiday) clock
    echo "$3" > "$tmp/clock"; : > "$tmp/calls"
    PATH="$tmp/path:$PATH" QUOTE="$2" STUB_CALLS="$tmp/calls" QKT_ATTEST_CLOCK_FILE="$tmp/clock" \
        QKT_ATTEST_STOCK_QUOTE_TRIES=1 QKT_ATTEST_UTC_DAY_WAIT=off QKT_ATTEST_RETRY_BACKOFF=0,0,0 \
        QKT_BROKER_API_KEY=x QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY QKT_ALPACA_GATEWAY_KEY=x \
        bash "$live/run-attestation-catalog.sh" --out "$1" --gateway-url http://127.0.0.1:1 --expected-login 1 \
        --expected-server S --magic-base 100 --arm I_UNDERSTAND_DEMO_ORDER_0.01 --lanes stocks,alpaca \
        --alpaca-gateway-url http://127.0.0.1:2 --alpaca-expected-login a --cli "$fake/bin/qkt" > "$1.txt" 2>&1
}
last() { tail -n 1 "$1/attempts/$2.jsonl"; }

# Thanksgiving 15:30Z: inside both windows by the clock, every venue quote a day old.
if run "$tmp/holiday" holiday 2026-11-26T15:30:00Z; then echo "FAIL a holiday must not pass the session cases"; exit 1; fi
for name in stocks-stock-share alpaca-alpaca-share; do
    last "$tmp/holiday" "$name" | jq -e '.class == "session" and (.summary | test("quote is stale"))' > /dev/null ||
        { echo "FAIL $name on a holiday: $(last "$tmp/holiday" "$name")"; cat "$tmp/holiday.txt"; exit 1; }
done
if grep -qx 'alpaca-share' "$tmp/calls"; then echo "FAIL the alpaca share case ran on a holiday"; exit 1; fi
grep -qx 'alpaca-coin' "$tmp/calls" || { echo "FAIL the 24/7 coin case must still run"; exit 1; }
last "$tmp/holiday" alpaca-alpaca-coin | jq -e '.status == "passed"' > /dev/null
echo "ok a holiday records every lane's share cases as session (quote stale) and still runs the coin case"

run "$tmp/open" fresh 2026-11-25T15:30:00Z || { echo "FAIL an open session: $(cat "$tmp/open.txt")"; exit 1; }
for case in stock-share alpaca-share alpaca-coin; do
    grep -qx "$case" "$tmp/calls" || { echo "FAIL $case did not run on an open session"; exit 1; }
done
echo "ok an open session with a fresh quote runs every case"
