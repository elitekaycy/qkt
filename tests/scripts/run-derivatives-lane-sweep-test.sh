#!/usr/bin/env bash
# A derivatives case that started its strategy leaves that strategy flat however it ended (a venue outage mid-trade
# left a position and its bracket), touching nothing another user of the shared account holds or works; a case
# that never started its strategy touches nothing at all.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$repo_root/scripts/live-validation/run-derivatives-lane-case.py" <<'PY'
import http.server, importlib.util, json, sys, threading, types, urllib.parse

spec = importlib.util.spec_from_file_location("lane", sys.argv[1])
lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lane)
calls = []
# The venue already cancelled the strategy's stop (its cancel answers not_found); its take-profit and its 0.001 of
# the account's 0.003 position remain. Another user of the account works an order and holds the other 0.002.
symbol = "BTC_USDC-30OCT26"
account = {
    "orders": {"dsl-s--1-tp.a": 200, "dsl-s--1-sl.a": 404, "dsl-other--2.a": 200},
    "positions": {symbol: "0.003"},
    "deals": [{"client_order_id": "dsl-s--1.a", "symbol": symbol, "side": "buy", "quantity": "0.001", "time": 1},
              {"client_order_id": "dsl-other--2.a", "symbol": symbol, "side": "buy", "quantity": "0.002", "time": 2}],
}

class Gateway(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, body, code=200):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        calls.append(("GET", path))
        self.reply({"/v1/orders": {"orders": [{"client_order_id": id} for id in account["orders"]]},
                    "/v1/positions": {"positions": [{"symbol": s, "quantity": q} for s, q in account["positions"].items()]},
                    "/v1/deals": {"deals": account["deals"]},
                    "/v1/settlements": {"settlements": []}}[path])

    def do_DELETE(self):
        calls.append(("DELETE", self.path))
        self.reply({}, account["orders"].pop(self.path.rsplit("/", 1)[1], 404))

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        calls.append(("POST", self.path, body))
        signed = float(body["quantity"]) * (1 if body["side"] == "buy" else -1)
        account["positions"][body["symbol"]] = f"{float(account['positions'][body['symbol']]) + signed:.3f}"
        account["deals"].append(dict(body, time=3))
        self.reply({})

server = http.server.HTTPServer(("127.0.0.1", 0), Gateway)
threading.Thread(target=server.serve_forever, daemon=True).start()
run = object.__new__(lane.Run)
run.args, run.key = types.SimpleNamespace(gateway_url=f"http://127.0.0.1:{server.server_port}"), "k"
run.strategy, run.started_ms, run.ended_ms = "s", 0, 0

run.sweep()
assert calls == [], f"a case that never started its strategy touched the account: {calls}"
print("ok a case that never started its strategy leaves the account alone")

run.owns_strategy = True
run.sweep()
assert ("DELETE", "/v1/orders/dsl-s--1-tp.a") in calls, calls
assert calls.count(("DELETE", "/v1/orders/dsl-s--1-sl.a")) == 1, f"a not_found cancel was retried: {calls}"
assert ("DELETE", "/v1/orders/dsl-other--2.a") not in calls, f"another user's order was cancelled: {calls}"
closes = [c[2] for c in calls if c[0] == "POST"]
assert len(closes) == 1, closes
assert closes[0]["symbol"] == symbol and closes[0]["side"] == "sell" and closes[0]["quantity"] == "0.001", closes
assert closes[0]["client_order_id"].startswith("ORD-s-sweep-") and closes[0]["reduce_only"], closes
assert account["orders"] == {"dsl-other--2.a": 200}, account["orders"]
assert account["positions"] == {symbol: "0.002"}, account["positions"]
print("ok a case that started its strategy cancels its orders and closes only its own holding")
server.shutdown()
PY
