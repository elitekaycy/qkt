"""Failure drills for a derivatives lane case (`run-derivatives-lane-case.py`), fired while the case holds.

A case declares them in `case.yaml`, timed from its first fill (from its `after_fills`-th fill, when set; with
`flat: true` the case must hold nothing when it fires, as a restart between two round trips):

    drills:
    - {at_s: 60, kind: qkt_restart, down_s: 20}      # `qkt daemon stop`, wait, start on the same state dir
    - {at_s: 60, kind: gateway_outage, seconds: 90}  # the daemon's gateway unreachable, then back
    - {at_s: 60, kind: kill_switch, hold_s: 30}      # the guardian engages the kill switch, then releases it

`gateway_outage` points the daemon at a loopback TCP proxy the runner owns ([TcpProxy]) and stops it
forwarding: open connections are cut and new ones refused, as when the gateway's host drops off the
network. The shared gateway keeps running. `kill_switch` needs the gateway's guardian token
(QKT_DERIV_GUARDIAN_KEY) and releases the switch whatever happens after it engaged it.
Each drill's timeline goes to result.json under `drills`.
"""
import datetime, glob, json, re, socket, threading, time, urllib.error
from decimal import ROUND_FLOOR, Decimal

KINDS = ("qkt_restart", "gateway_outage", "kill_switch")
RECONNECT_WAIT_S = 120  # the stream's reconnect backoff tops out at 30 s; this is four of them


def check(drills):
    """The case's drills, each with its kind known and its time set; raises on a malformed one."""
    for drill in drills:
        if drill.get("kind") not in KINDS:
            raise ValueError(f"drill kind must be one of {KINDS}: {drill}")
        if not isinstance(drill.get("at_s"), int) or drill["at_s"] < 0:
            raise ValueError(f"drill at_s must be whole seconds from the first fill: {drill}")
        if not isinstance(drill.get("after_fills", 1), int) or drill.get("after_fills", 1) < 1:
            raise ValueError(f"drill after_fills must be a fill count from 1: {drill}")
    return [dict(d) for d in drills]


def stamp(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Timeline:
    """One drill's record: what happened when, and what it found wrong."""

    def __init__(self, drill):
        self.record = {**drill, "timeline": [], "problems": []}

    def mark(self, event, **facts):
        ms = int(time.time() * 1000)
        self.record["timeline"].append({"ms": ms, "time": stamp(ms), "event": event, **facts})
        return ms

    def problem(self, text):
        self.record["problems"].append(f"{self.record['kind']}: {text}")


class TcpProxy:
    """Forwards loopback connections to [target]; [down] cuts them all and refuses new ones until [up]."""

    def __init__(self, host, port):
        self.target = (host, port)
        self.lock = threading.Lock()
        self.pairs = set()
        self.listener = self.listen(0)
        self.port = self.listener.getsockname()[1]
        self.closed = False
        self.serving = threading.Thread(target=self.serve, daemon=True)
        self.serving.start()

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}"

    def listen(self, port):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
        listener.listen(64)
        return listener

    def serve(self):
        while not self.closed:
            listener = self.listener
            if listener is None:
                time.sleep(0.1)
                continue
            try:
                client, _ = listener.accept()
            except OSError:  # closed for an outage, or for good
                continue
            try:
                upstream = socket.create_connection(self.target, timeout=10)
                upstream.settimeout(None)
            except OSError:
                client.close()
                continue
            pair = (client, upstream)
            with self.lock:
                self.pairs.add(pair)
            for source, sink in (pair, pair[::-1]):
                threading.Thread(target=self.pipe, args=(source, sink, pair), daemon=True).start()

    def pipe(self, source, sink, pair):
        try:
            while True:
                data = source.recv(65536)
                if not data:
                    break
                sink.sendall(data)
        except OSError:
            pass
        self.drop(pair)

    def drop(self, pair):
        with self.lock:
            self.pairs.discard(pair)
        for end in pair:
            try:
                end.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            end.close()

    def down(self):
        """Refuses new connections and cuts the open ones: the gateway is unreachable."""
        listener, self.listener = self.listener, None
        try:
            listener.shutdown(socket.SHUT_RDWR)  # wakes the blocked accept; close alone leaves it listening
        except OSError:
            pass
        listener.close()
        with self.lock:
            pairs = list(self.pairs)
        for pair in pairs:
            self.drop(pair)

    def up(self):
        """Forwards again, on the same port."""
        self.listener = self.listen(self.port)

    def close(self):
        self.closed = True
        if self.listener is not None:
            self.down()


class Drills:
    """Fires [run]'s drills on time while it holds, and judges what each one left behind."""

    def __init__(self, run, drills, guardian):
        self.run, self.pending, self.guardian = run, list(drills), guardian
        self.records, self.fill_seen_at, self.engaged = [], {}, False

    def tick(self, fills):
        """Called while the case runs: fires every drill whose time from its anchoring fill has come."""
        for count in range(1, len(fills) + 1):
            self.fill_seen_at.setdefault(count, time.time())
        while self.pending:
            anchored = self.fill_seen_at.get(int(self.pending[0].get("after_fills", 1)))
            if anchored is None or time.time() < anchored + self.pending[0]["at_s"]:
                return
            self.fire(self.pending.pop(0))

    def fire(self, drill):
        line = Timeline(drill)
        self.records.append(line.record)
        held = self.run.get("/v1/positions")["positions"]
        line.mark("fired", positions=held)
        if drill.get("flat") and held:
            line.problem(f"the case held {held} when a drill meant for a flat account fired")
        elif not drill.get("flat") and not held:
            line.problem("the case held no position when the drill fired; it proves nothing")
        getattr(self, drill["kind"])(drill, line, held)

    def problems(self):
        """What the drills found wrong, a drill that never fired included."""
        found = [p for r in self.records for p in r["problems"]]
        return found + [f"{d['kind']}: never fired ({d['at_s']} s after fill {d.get('after_fills', 1)})"
                        for d in self.pending]

    def qkt_restart(self, drill, line, held):
        line.mark("daemon stop")
        stopped = self.run.stop_daemon()
        line.mark("daemon stopped", graceful=stopped, positions=self.run.get("/v1/positions")["positions"],
                  orders=[o["client_order_id"] for o in self.run.get("/v1/orders")["orders"]])
        if not stopped:
            line.problem("the daemon did not stop within 60 s of `qkt daemon stop`")
        time.sleep(int(drill.get("down_s", 15)))
        line.mark("daemon start")
        self.run.start_daemon(time.time() + 150)
        line.mark("daemon ready")

    def gateway_outage(self, drill, line, held):
        proxy = self.run.proxy
        began = line.mark("outage begins", seconds=int(drill.get("seconds", 90)))
        proxy.down()
        time.sleep(int(drill.get("seconds", 90)))
        proxy.up()
        ended = line.mark("outage ends")
        down = up = None
        deadline = time.time() + RECONNECT_WAIT_S
        while time.time() < deadline:
            down = next((ms for ms, state in self.links() if state == "DISCONNECTED" and ms >= began), None)
            up = next((ms for ms, state in self.links() if state == "CONNECTED" and ms >= ended), None)
            if up:
                break
            time.sleep(2)
        if down:
            line.record["timeline"].append({"ms": down, "time": stamp(down), "event": "qkt reports the link down"})
        else:
            line.problem("qkt never reported the gateway link down during the outage")
        if up:
            line.record["timeline"].append({"ms": up, "time": stamp(up), "event": "qkt reports the link up"})
        else:
            line.problem(f"qkt did not report the gateway link up within {RECONNECT_WAIT_S} s of the outage's end")
        line.record["timeline"].sort(key=lambda e: e["ms"])

    def links(self):
        """The daemon's link reports, in order: (epoch ms, CONNECTED|DISCONNECTED), from its audit journal."""
        found = []
        for path in sorted(glob.glob(f"{self.run.out}/state/state/audit-journal/*/audit-*.jsonl")):
            for text in open(path):
                if "ConnectionChanged" in text:
                    entry = json.loads(text)
                    state = re.search(r"state=(\w+)", entry["payload"])
                    found.append((entry["ts"], state.group(1) if state else "?"))
        return sorted(set(found))

    def kill_switch(self, drill, line, held):
        if not self.guardian:
            return line.problem("QKT_DERIV_GUARDIAN_KEY is not set: the drill needs the gateway's guardian token")
        try:
            self.engaged = True  # set first: a failure mid-request may still have engaged it
            state = self.guardian_post("/v1/kill", {"scope": "all"})
            line.mark("kill engaged", kill_switch=state)
            if not state.get("all"):
                line.problem(f"the kill switch did not engage: {state}")
            if held:
                self.probe(line, held[0])
            time.sleep(int(drill.get("hold_s", 30)))
        finally:
            self.release(line)
        # The gateway sends each change of the switch on the stream (qkt-venue-gateway#55); the daemon logs it.
        deadline = time.time() + 15
        while time.time() < deadline and not self.heard_flip(self.heard_kill()):
            time.sleep(1)
        heard = self.heard_kill()
        line.mark("qkt heard", kill_events=heard)
        if not self.heard_flip(heard):
            line.problem(f"the daemon did not log the switch engaging then releasing from its stream: {heard}")

    @staticmethod
    def heard_flip(heard):
        """Whether [heard] holds the switch on for every symbol, then off again."""
        on = next((i for i, state in enumerate(heard) if '"all":true' in state.replace(" ", "")), None)
        return on is not None and any('"all":false' in state.replace(" ", "") for state in heard[on + 1:])

    def probe(self, line, position):
        """A risk-adding order (a buy far under the market, smallest size) must be refused with 423 kill_switch."""
        code = position["symbol"]
        instrument = self.run.get(f"/v1/instruments/{code}")
        tick = Decimal(instrument["tick_size"])
        price = (Decimal(position["avg_price"]) / 2 / tick).to_integral_value(ROUND_FLOOR) * tick
        body = {"client_order_id": f"drill-probe-{int(time.time() * 1000)}", "symbol": code, "side": "buy",
                "type": "limit", "quantity": instrument["volume_min"], "limit_price": str(price),
                "time_in_force": "gtc", "reduce_only": False}
        try:
            self.run.get("/v1/orders", body, "POST")
        except urllib.error.HTTPError as error:
            reply = error.read().decode(errors="replace")
            line.mark("risk-adding order refused", status=error.code, reply=reply)
            if error.code != 423 or "kill_switch" not in reply:
                line.problem(f"the risk-adding probe was refused with {error.code}, not 423 kill_switch: {reply}")
            return
        line.mark("risk-adding order ACCEPTED", order=body["client_order_id"])
        line.problem("the gateway accepted a risk-adding order while the kill switch was on")
        self.run.get(f"/v1/orders/{body['client_order_id']}", method="DELETE")

    def release(self, line=None):
        """Releases the kill switch this drill engaged; a no-op once released."""
        if not self.engaged:
            return
        state = self.guardian_post("/v1/kill/release", {"scope": "all"})
        self.engaged = False
        if line is not None:
            line.mark("kill released", kill_switch=state)
            if state.get("all") or state.get("symbols"):
                line.problem(f"the kill switch is still on after its release: {state}")

    def guardian_post(self, path, body):
        return self.run.get(path, body, "POST", key=self.guardian)

    def heard_kill(self):
        """The kill-switch states the daemon heard on its event stream, in order."""
        return re.findall(r"gateway kill switch: (.*)", open(f"{self.run.out}/daemon.log").read())
