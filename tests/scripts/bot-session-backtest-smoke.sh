#!/usr/bin/env bash
# Backtest smoke for bot run sessions over the real local data store.
#
# Usage: ./tests/scripts/bot-session-backtest-smoke.sh [SYMBOL] [FROM] [TO]
# Defaults to EXNESS:EURUSD over the locally cached 2024-01 window. A python
# client drives the whole loop through `qkt bot` verbs (prime -> next/decide ->
# buy/sell -> finish) and the script asserts the standard report artifacts.
set -euo pipefail

QKT_BIN="${QKT_BIN:-./build/install/qkt/bin/qkt}"
SYMBOL="${1:-EXNESS:EURUSD}"
FROM="${2:-2024-01-08}"
TO="${3:-2024-01-12}"
RUN_ID="bt-smoke-$$"

WORK="$(mktemp -d)"
trap 'kill "${SESSION_PID:-0}" 2>/dev/null || true; rm -rf "$WORK"' EXIT

cat > "$WORK/qkt.config.yaml" <<'EOF'
account_currency: USD
risk:
  max_daily_loss: 500
EOF

echo "[1/4] session start (backtest ${SYMBOL} ${FROM}..${TO})"
"$QKT_BIN" bot session start --backtest \
  --symbols "$SYMBOL" --tf 15m --from "$FROM" --to "$TO" \
  --identities pybrain --run "$RUN_ID" --out "$WORK/report" \
  --state-dir "$WORK/state" --config "$WORK/qkt.config.yaml" \
  --no-fetch --json > "$WORK/session.log" 2>&1 &
SESSION_PID=$!
DESCRIPTOR="$WORK/state/state/bot/sessions/$RUN_ID/session.json"
for _ in $(seq 1 120); do
  [[ -f "$DESCRIPTOR" ]] && break
  kill -0 "$SESSION_PID" 2>/dev/null || { echo "session died:"; cat "$WORK/session.log"; exit 1; }
  sleep 1
done
[[ -f "$DESCRIPTOR" ]] || { echo "no session.json"; cat "$WORK/session.log"; exit 1; }

echo "[2/4] python client drives the loop"
QKT_BIN="$QKT_BIN" RUN_ID="$RUN_ID" SYMBOL="$SYMBOL" STATE_DIR="$WORK/state" CFG="$WORK/qkt.config.yaml" \
python3 - <<'PYEOF'
import json, os, subprocess, sys

qkt, run, sym = os.environ["QKT_BIN"], os.environ["RUN_ID"], os.environ["SYMBOL"]
common = ["--run", run, "--state-dir", os.environ["STATE_DIR"], "--config", os.environ["CFG"], "--json"]

def bot(*args):
    p = subprocess.run([qkt, "bot", *args, *common], capture_output=True, text=True)
    if p.returncode != 0:
        print("FAIL:", args, p.stdout, p.stderr); sys.exit(1)
    return json.loads(p.stdout)

prime = bot("bars", sym, "--count", "50")
print(f"  primed with {len(prime)} warmup bars")
closes, bars, trades = [], 0, 0
while True:
    bar = bot("next", sym)
    if bar.get("type") == "end":
        break
    bars += 1
    closes.append(float(bar["close"]))
    if len(closes) >= 5 and trades < 6:
        fast = sum(closes[-3:]) / 3
        slow = sum(closes[-5:]) / 5
        if fast > slow and bars % 20 == 0:
            r = bot("buy", "1", sym, "--sl", "by:0.002", "--tp", "rr:2", "--as", "pybrain")
            assert r.get("queued"), r
            trades += 1
        elif fast < slow and bars % 20 == 10:
            r = bot("sell", "1", sym, "--as", "pybrain")
            assert r.get("queued"), r
            trades += 1
print(f"  drove {bars} bars, submitted {trades} intents")
assert bars > 50, "expected a real window of bars"
assert trades > 0, "expected at least one intent"
status = bot("session", "status")
print(f"  status: equity={status['equity']} simNowMs={status['simNowMs']}")
PYEOF

echo "[3/4] finish -> report"
FIN=$("$QKT_BIN" bot session finish --run "$RUN_ID" --state-dir "$WORK/state" --config "$WORK/qkt.config.yaml" --json)
echo "  $FIN"
grep -q '"finished":true' <<<"$FIN"
wait "$SESSION_PID" || true

echo "[4/4] report artifacts"
for f in result.json trades.csv equity_global.csv equity_pybrain.csv pnl_components.csv rejections.csv orders.jsonl manifest.json report.html; do
  [[ -f "$WORK/report/$f" ]] || { echo "MISSING $f"; ls "$WORK/report"; exit 1; }
  echo "  ok: $f"
done
grep -q pybrain "$WORK/report/trades.csv"
python3 -c "import json;r=json.load(open('$WORK/report/result.json'));print('  trades:',r['global']['tradeCount'],'pnl:',r['global']['totalPnL'])"
echo "BACKTEST SMOKE OK"
