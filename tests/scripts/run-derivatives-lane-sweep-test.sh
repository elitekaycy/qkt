#!/usr/bin/env bash
# A derivatives case that took the account leaves it flat however it ended (a venue outage mid-trade
# left a position and its bracket), and a case that never took it touches nothing on it.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$repo_root/scripts/live-validation/run-derivatives-lane-case.py" <<'PY'
import http.server, importlib.util, json, sys, threading, types

spec = importlib.util.spec_from_file_location("lane", sys.argv[1])
lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lane)
calls = []
# The venue already cancelled the stop (its cancel answers not_found); the take-profit and the position remain.
account = {"orders": {"dsl-s--1-tp.a": 200, "dsl-s--1-sl.a": 404}, "positions": ["BTC_USDC-30OCT26"]}

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
        calls.append(("GET", self.path))
        self.reply({"/v1/orders": {"orders": [{"client_order_id": id} for id in account["orders"]]},
                    "/v1/positions": {"positions": [{"symbol": s} for s in account["positions"]]}}[self.path])

    def do_DELETE(self):
        calls.append(("DELETE", self.path))
        self.reply({}, account["orders"].pop(self.path.rsplit("/", 1)[1], 404))

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        calls.append(("POST", self.path, body))
        account["positions"].remove(body["symbol"])
        self.reply({})

server = http.server.HTTPServer(("127.0.0.1", 0), Gateway)
threading.Thread(target=server.serve_forever, daemon=True).start()
run = object.__new__(lane.Run)
run.args, run.key = types.SimpleNamespace(gateway_url=f"http://127.0.0.1:{server.server_port}"), "k"

run.sweep()
assert calls == [], f"a case that never took the account touched it: {calls}"
print("ok a case that never took the account leaves it alone")

run.owns_account = True
run.sweep()
assert ("DELETE", "/v1/orders/dsl-s--1-tp.a") in calls, calls
assert calls.count(("DELETE", "/v1/orders/dsl-s--1-sl.a")) == 1, f"a not_found cancel was retried: {calls}"
assert ("POST", "/v1/positions/close", {"symbol": "BTC_USDC-30OCT26"}) in calls, calls
assert account == {"orders": {}, "positions": []}, account
print("ok a case that took the account cancels its orders, closes its positions and checks it is flat")
server.shutdown()
PY
