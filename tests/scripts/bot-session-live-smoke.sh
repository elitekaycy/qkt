#!/usr/bin/env bash
# Live smoke for bot run sessions against a local MT5 gateway (demo account).
#
# Usage:
#   EXNESS_GATEWAY_URL=http://127.0.0.1:5001 \
#   EXNESS_API_KEY=secret \
#   ./tests/scripts/bot-session-live-smoke.sh
#
# Flow: start a live session -> pull a bar with `bot next` -> submit a 0.01-lot
# buy through the session pipeline -> verify the venue position -> close it ->
# finish the session. Exits non-zero on any step failing.
set -euo pipefail

QKT_BIN="${QKT_BIN:-./build/install/qkt/bin/qkt}"
GATEWAY_URL="${EXNESS_GATEWAY_URL:?EXNESS_GATEWAY_URL required}"
API_KEY="${EXNESS_API_KEY:?EXNESS_API_KEY required}"
SYMBOL="${SYMBOL:-EXNESS:XAUUSD}"
TF="${TF:-1m}"
RUN_ID="live-smoke-$$"

WORK="$(mktemp -d)"
trap 'kill "${SESSION_PID:-0}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

cat > "$WORK/qkt.config.yaml" <<EOF
account_currency: USD
brokers:
  exness:
    type: mt5
    gateway_url: $GATEWAY_URL
    api_key: $API_KEY
    server_time_zone: UTC
EOF

echo "[1/7] gateway health"
curl -fsS -m 10 -H "Authorization: Bearer $API_KEY" "$GATEWAY_URL/health" > /dev/null

echo "[2/7] session start (live)"
"$QKT_BIN" bot session start \
  --symbols "$SYMBOL" --tf "$TF" \
  --identities smoke --run "$RUN_ID" \
  --config "$WORK/qkt.config.yaml" --state-dir "$WORK/state" --json \
  > "$WORK/session.log" 2>&1 &
SESSION_PID=$!

DESCRIPTOR="$WORK/state/state/bot/sessions/$RUN_ID/session.json"
for _ in $(seq 1 120); do
  [[ -f "$DESCRIPTOR" ]] && break
  kill -0 "$SESSION_PID" 2>/dev/null || { echo "session died:"; cat "$WORK/session.log"; exit 1; }
  sleep 1
done
[[ -f "$DESCRIPTOR" ]] || { echo "session.json never appeared"; cat "$WORK/session.log"; exit 1; }

COMMON=(--run "$RUN_ID" --state-dir "$WORK/state" --config "$WORK/qkt.config.yaml" --json)

echo "[3/7] bot next (waits for the next $TF bar close)"
NEXT=$("$QKT_BIN" bot next "$SYMBOL" "${COMMON[@]}")
echo "  $NEXT"
grep -q '"type":"bar"' <<<"$NEXT"

echo "[4/7] bot buy 0.01 through the session pipeline"
BUY=$("$QKT_BIN" bot buy 0.01 "$SYMBOL" --as smoke "${COMMON[@]}")
echo "  $BUY"
grep -q '"queued":true' <<<"$BUY"
sleep 5

echo "[5/7] venue positions show the fill"
POS=$("$QKT_BIN" bot positions "$SYMBOL" "${COMMON[@]}")
echo "  $POS"
grep -q 'ticket' <<<"$POS"

echo "[6/7] close the position (venue-direct)"
CLOSE=$("$QKT_BIN" bot close "$SYMBOL" --all "${COMMON[@]}")
echo "  $CLOSE"

echo "[7/7] session finish"
FIN=$("$QKT_BIN" bot session finish "${COMMON[@]}")
echo "  $FIN"
grep -q '"finished":true' <<<"$FIN"
wait "$SESSION_PID" || true

echo "reads journal:"
head -3 "$WORK/state/state/bot/sessions/$RUN_ID/reads.jsonl" 2>/dev/null || echo "  (removed with session)"
echo "LIVE SMOKE OK"
