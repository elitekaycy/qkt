#!/usr/bin/env bash
# The derivatives lane's failure drills: the outage proxy cuts and refuses connections then forwards again on
# the same port, a kill-switch drill releases the switch even when it fails while engaged, a drill that never
# fired fails the case, and a drilled case's fills and orders are judged against the venue's.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
python3 - "$repo_root/scripts/live-validation" <<'PY'
import http.server, importlib.util, json, socket, sys, threading, time, types

sys.path.insert(0, sys.argv[1] + "/lib")
import derivatives_drills as drills

spec = importlib.util.spec_from_file_location("lane", sys.argv[1] + "/run-derivatives-lane-case.py")
lane = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lane)

# --- the outage proxy -------------------------------------------------------------------------------
echo = socket.socket()
echo.bind(("127.0.0.1", 0))
echo.listen(8)

def serve_echo():
    while True:
        conn, _ = echo.accept()
        threading.Thread(target=lambda c=conn: [c.sendall(d) for d in iter(lambda: c.recv(1024), b"")], daemon=True).start()

threading.Thread(target=serve_echo, daemon=True).start()
proxy = drills.TcpProxy("127.0.0.1", echo.getsockname()[1])
held = socket.create_connection(("127.0.0.1", proxy.port), timeout=5)
held.sendall(b"ping")
assert held.recv(16) == b"ping"
proxy.down()
assert held.recv(16) == b"", "an open connection survived the outage"
try:
    socket.create_connection(("127.0.0.1", proxy.port), timeout=2)
    raise AssertionError("a new connection was accepted during the outage")
except ConnectionRefusedError:
    pass
proxy.up()
again = socket.create_connection(("127.0.0.1", proxy.port), timeout=5)
again.sendall(b"pong")
assert again.recv(16) == b"pong"
proxy.close()
print("ok the outage proxy cuts and refuses connections, then forwards again on the same port")

# --- a fake gateway for the kill switch and the judgment ------------------------------------------------
calls, kill = [], {"all": False, "symbols": []}
deals = []

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
        if self.path.startswith("/v1/deals"):
            return self.reply({"deals": deals})
        self.reply({"/v1/positions": {"positions": [{"symbol": "BTC_USDC-PERPETUAL", "avg_price": "86000.3"}]},
                    "/v1/instruments/BTC_USDC-PERPETUAL": {"tick_size": "0.1", "volume_min": "0.0001"}}[self.path])

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        calls.append((self.path, self.headers["Authorization"], body))
        if self.path == "/v1/kill":
            kill["all"] = True
            return self.reply(kill)
        if self.path == "/v1/kill/release":
            kill["all"] = False
            return self.reply(kill)
        if self.path == "/v1/orders":
            return self.reply({"error": {"code": "kill_switch", "message": "on"}}, 423)

server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Gateway)
threading.Thread(target=server.serve_forever, daemon=True).start()
run = object.__new__(lane.Run)
run.args, run.key = types.SimpleNamespace(gateway_url=f"http://127.0.0.1:{server.server_port}"), "trader"
run.out, run.strategy, run.started_ms, run.ended_ms = "/nonexistent", "s", 0, 0

kills = drills.Drills(run, drills.check([{"at_s": 0, "kind": "kill_switch", "hold_s": 0}]), "guardian")
kills.heard_kill = lambda: ['{"all":true,"symbols":[]}', '{"all":false,"symbols":[]}']

real_sleep, drills.time.sleep = drills.time.sleep, lambda s: None
kills.tick([{"side": "BUY"}])
record = kills.records[0]
assert [e["event"] for e in record["timeline"]] == \
    ["fired", "kill engaged", "risk-adding order refused", "kill released", "qkt heard"], record
assert record["problems"] == [], record
assert [(p, a) for p, a, _ in calls if p.startswith("/v1/kill")] == \
    [("/v1/kill", "Bearer guardian"), ("/v1/kill/release", "Bearer guardian")], calls
probe = next(b for p, _, b in calls if p == "/v1/orders")
assert probe["limit_price"] == "43000.1" and probe["quantity"] == "0.0001" and not probe["reduce_only"], probe
print("ok a kill-switch drill engages with the guardian token, sees a risk-adding order refused, releases")

calls.clear()
failing = drills.Drills(run, drills.check([{"at_s": 0, "kind": "kill_switch"}]), "guardian")
failing.probe = lambda line, position: (_ for _ in ()).throw(RuntimeError("runner died while engaged"))
try:
    failing.tick([{"side": "BUY"}])
except RuntimeError:
    pass
assert kill["all"] is False and calls[-1][0] == "/v1/kill/release", calls
print("ok a kill-switch drill that fails while engaged still releases the switch")
drills.time.sleep = real_sleep

late = drills.Drills(run, drills.check([{"at_s": 3600, "kind": "qkt_restart"}]), None)
late.tick([{"side": "BUY"}])
assert late.problems() == ["qkt_restart: never fired (3600 s after fill 1)"], late.problems()
print("ok a drill that never fired fails the case")

deaf = drills.Drills(run, drills.check([{"at_s": 0, "kind": "kill_switch", "hold_s": 0}]), "guardian")
deaf.heard_kill = lambda: ['{"all":true,"symbols":[]}']
drills.time.sleep, clock = (lambda s: None), [0.0]
real_time, drills.time.time = drills.time.time, lambda: clock.__setitem__(0, clock[0] + 1) or clock[0]
deaf.tick([{"side": "BUY"}])
drills.time.sleep, drills.time.time = real_sleep, real_time
assert any("did not log the switch engaging then releasing" in p for p in deaf.problems()), deaf.problems()
print("ok a kill-switch drill fails when the daemon never logs the switch's release from its stream")

fired = []
second = drills.Drills(run, drills.check([{"at_s": 0, "after_fills": 2, "flat": True, "kind": "qkt_restart"}]), None)
second.qkt_restart = lambda drill, line, held: fired.append(held)
second.tick([{"side": "BUY"}])
assert fired == [], "a drill anchored on the second fill fired after the first"
second.tick([{"side": "BUY"}, {"side": "SELL"}])
assert len(fired) == 1 and second.records[0]["problems"] == ["qkt_restart: the case held [{'symbol': 'BTC_USDC-PERPETUAL', 'avg_price': '86000.3'}] when a drill meant for a flat account fired"], second.records
print("ok a drill fires from its after_fills-th fill, and a flat drill fails when the case still holds")

for bad in ({"at_s": 1, "kind": "reboot"}, {"kind": "qkt_restart"}):
    try:
        drills.check([bad])
        raise AssertionError(f"accepted {bad}")
    except ValueError:
        pass
print("ok a malformed drill is refused before the case runs")

fill = lambda cid, side, px: {"client_order_id": cid, "side": side, "quantity": "0.0001", "price": px, "symbol": "BTC_USDC-PERPETUAL", "costs": []}
qkt = [{"side": "BUY", "qty": "0.0001", "price": "86000"}, {"side": "SELL", "qty": "0.0001", "price": "86100"}]
deals[:] = [fill("dsl-s--0.a1", "buy", "86000"), fill("dsl-s--2.a2", "sell", "86100")]
assert run.judge_drilled(qkt) == [], run.judge_drilled(qkt)
deals.append(fill("dsl-s--2.b7", "sell", "86100"))
problems = run.judge_drilled(qkt)
assert len(problems) == 2 and problems[0].startswith("venue-fills-equal-qkt-fills") \
    and problems[1].startswith("no-duplicate-order") and "dsl-s--2" in problems[1], problems
print("ok a drilled case fails on a fill qkt never booked and on an engine order sent twice")
# A restart that restarted the id sequence: the close reuses the entry's engine id (seen live, 0.55.0).
deals[:] = [fill("dsl-s--0.a1", "buy", "86000"), fill("dsl-s--0.b7", "sell", "86100")]
problems = run.judge_drilled(qkt)
assert len(problems) == 1 and problems[0].startswith("order-id-continuity") and "dsl-s--0" in problems[0], problems
print("ok a drilled case fails when one engine id names two different orders")
# Seen live on a flat restart (#1338): two round trips, each under the same engine ids, all fills booked.
deals[:] = [fill("dsl-s--0.a1", "buy", "86000"), fill("dsl-s--2.a2", "sell", "86100"),
            fill("dsl-s--0.b1", "buy", "86000"), fill("dsl-s--2.b2", "sell", "86100")]
problems = run.judge_drilled(qkt + qkt)
assert [p.split(":")[0] for p in problems] == ["order-id-continuity", "order-id-continuity"], problems
print("ok two orders on one side under one engine id, both booked, are id reuse, not a duplicate")
server.shutdown()
PY
