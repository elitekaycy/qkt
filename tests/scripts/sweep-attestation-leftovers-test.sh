#!/usr/bin/env bash
# The sweep clears the attestation's own magic range by ticket, refuses to touch anything else,
# and refuses to run at all against a non-loopback gateway or without the demo approval.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tool="$repo_root/scripts/live-validation/sweep-attestation-leftovers.py"
tmp="$(mktemp -d)"; server=""
trap '[ -z "$server" ] || kill "$server" 2>/dev/null || true; rm -rf "$tmp"' EXIT

cat > "$tmp/gateway.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
state = json.load(open(sys.argv[2])); calls = open(sys.argv[3], "a")
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def reply(self, body):
        data = json.dumps(body).encode(); self.send_response(200); self.send_header("content-length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_GET(self):
        self.reply({"/account": {"login": 7, "server": "Demo-1", "trade_mode": 0}, "/orders": {"orders": state["orders"]},
                    "/get_positions": {"data": state["positions"]}}[self.path])
    def do_DELETE(self):
        calls.write(f"DELETE {self.path}\n"); calls.flush(); self.reply({"ok": True})
    def do_POST(self):
        body = self.rfile.read(int(self.headers["content-length"])).decode()
        calls.write(f"POST {self.path} {body}\n"); calls.flush(); self.reply({"ok": True})
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
port=$(( 20000 + RANDOM % 20000 ))
start_gateway() {  # state-json
    [ -z "$server" ] || { kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; }
    : > "$tmp/calls"; printf '%s' "$1" > "$tmp/state.json"
    python3 "$tmp/gateway.py" "$port" "$tmp/state.json" "$tmp/calls" & server=$!
    for _ in $(seq 1 50); do curl -s "http://127.0.0.1:$port/account" > /dev/null && return; sleep 0.1; done
    echo "FAIL stub gateway did not start"; exit 1
}
sweep() {
    QKT_BROKER_API_KEY=k QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY python3 "$tool" --gateway-url "http://127.0.0.1:$port" \
        --expected-login 7 --expected-server Demo-1 --magic-from 995000 --magic-to 996000
}

start_gateway '{"orders":[{"ticket":11,"magic":995108}],"positions":[{"ticket":22,"magic":995999}]}'
out="$(sweep)"
jq -e '.cancelledOrders == [11] and .closedPositions == [22] and .notOurs == [] and .errors == []' <<<"$out" > /dev/null
grep -qx 'DELETE /orders/11' "$tmp/calls" && grep -q '^POST /close_position {"position": {"ticket": 22}}' "$tmp/calls"
echo "ok leftovers inside the attestation's magic range are removed by ticket"

start_gateway '{"orders":[{"ticket":33,"magic":424242}],"positions":[]}'
if out="$(sweep)"; then echo "FAIL a stranger's order must fail the sweep"; exit 1; fi
jq -e '.notOurs == ["order 33 magic 424242"] and .cancelledOrders == []' <<<"$out" > /dev/null
[ ! -s "$tmp/calls" ] || { echo "FAIL the sweep touched a stranger's order"; exit 1; }
echo "ok anything outside the range is named, left alone, and fails the sweep"

start_gateway '{"orders":[],"positions":[]}'
jq -e '.cancelledOrders == [] and .closedPositions == []' <<<"$(sweep)" > /dev/null
echo "ok a clean account passes and nothing is sent"

if QKT_BROKER_API_KEY=k QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY python3 "$tool" --gateway-url http://10.0.0.5:5001 \
    --expected-login 7 --expected-server Demo-1 --magic-from 1 --magic-to 2 2> "$tmp/err"; then echo "FAIL remote gateway accepted"; exit 1; fi
grep -q 'only a loopback gateway is allowed' "$tmp/err"
echo "ok a non-loopback gateway is refused"
