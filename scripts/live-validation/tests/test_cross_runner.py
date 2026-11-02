"""The whole cross runner offline: a fake MT5 gateway, a fake VGP gateway and a fake qkt (fake_cross_qkt.py) play one
strategy trading both at once; the runner judges each venue on its own.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, shutil, subprocess, sys, tempfile, threading, time, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
RUNNER = os.path.join(ROOT, "scripts/live-validation/run-cross-lane-case.py")
CASE = os.path.join(ROOT, "attestation/cases/cross/two-venue-pair")
PERP = "BTC_USDC-PERPETUAL"


class Fake:
    """A loopback HTTP server answering GET and POST from [answer]/[act]."""

    def __init__(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, body):
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                url = urlsplit(self.path)
                self.reply(fake.answer(url.path, {k: v[0] for k, v in parse_qs(url.query).items()}))

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                self.reply(fake.act(urlsplit(self.path).path, body))

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class FakeMt5(Fake):
    """An Exness-shaped MT5 gateway: one hedging account, deals in history, positions by magic."""

    def __init__(self):
        super().__init__()
        self.history, self.open = [], {}

    def answer(self, path, query):
        if path == "/account":
            return {"login": 1, "server": "FakeServer", "trade_mode": 0, "trade_allowed": True}
        if path == "/get_positions":
            return {"data": list(self.open.values())}
        if path == "/orders":
            return {"orders": []}
        if path == "/test/history":
            return self.history
        raise AssertionError(f"unexpected MT5 GET {path}")

    def act(self, path, body):
        if path == "/test/deal":
            now = int(time.time() * 1000)
            if not self.open:
                self.open[7] = {"ticket": 7, "price": body["price"], "volume": body["lots"]}
                self.history.append({"positionTicket": 7, "entry": "IN", "side": body["side"], "lots": float(body["lots"]),
                                     "price": float(body["price"]), "profit": 0.0, "commission": 0.0, "swap": 0.0,
                                     "fee": 0.0, "timeMs": now, "comment": body["comment"]})
            else:
                opened = self.open.pop(7)
                profit = (Decimal(body["price"]) - Decimal(opened["price"])) * Decimal(opened["volume"])
                self.history.append({"positionTicket": 7, "entry": "OUT", "side": body["side"], "lots": float(body["lots"]),
                                     "price": float(body["price"]), "profit": float(profit), "commission": 0.0,
                                     "swap": 0.0, "fee": 0.0, "timeMs": now, "comment": ""})
            return {}
        raise AssertionError(f"unexpected MT5 POST {path}")


class FakePaper(Fake):
    """A paper-adapter VGP gateway: one netting perpetual, a fee of 0.0005 of notional on every fill."""

    def __init__(self):
        super().__init__()
        self.deals, self.position = [], Decimal(0)

    def answer(self, path, query):
        if path == "/v1/health":
            return {"adapter": "paper", "account_login": "paper", "trade_mode": "demo", "venue_connected": True,
                    "kill_switch": {"all": False, "symbols": []}, "capabilities": ["bars", "quotes"]}
        if path == "/v1/instruments":
            return [{"code": PERP, "kind": "perpetual", "contract_size": "1"}]
        if path == "/v1/positions":
            held = [{"symbol": PERP, "quantity": str(self.position), "avg_price": "80000"}] if self.position else []
            return {"accounting": "netting", "positions": held}
        if path == "/v1/orders":
            return {"orders": []}
        if path == "/v1/deals":
            return {"deals": [d for d in self.deals if int(query["from"]) <= d["time"] <= int(query["to"])]}
        if path in ("/v1/settlements", "/v1/funding"):
            return {path.rsplit("/", 1)[1]: []}
        raise AssertionError(f"unexpected gateway GET {path}")

    def act(self, path, body):
        if path == "/test/fill":
            fee = Decimal("0.0005") * Decimal(body["price"]) * Decimal(body["quantity"])
            self.deals.append({"client_order_id": body["client_order_id"], "symbol": PERP, "side": body["side"],
                               "quantity": body["quantity"], "price": body["price"], "time": int(time.time() * 1000),
                               "costs": [{"kind": "commission", "amount": str(fee), "currency": "USDC"}]})
            self.position += Decimal(body["quantity"]) * (1 if body["side"] == "buy" else -1)
            return {}
        raise AssertionError(f"unexpected gateway POST {path}")


class CrossRunnerCase(unittest.TestCase):
    def run_case(self, mode):
        mt5, paper = FakeMt5(), FakePaper()
        self.addCleanup(mt5.close)
        self.addCleanup(paper.close)
        work = tempfile.mkdtemp()
        case = os.path.join(work, "two-venue-pair")
        shutil.copytree(CASE, case)
        with open(f"{case}/case.yaml") as file:
            doc = yaml.safe_load(file)
        doc["replay"] = "none"  # a fake qkt has no golden capture; the replay rule is judged in test_cross_venue.py
        with open(f"{case}/case.yaml", "w") as file:
            yaml.safe_dump(doc, file)
        env = dict(os.environ, QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", QKT_BROKER_API_KEY="k",
                   QKT_CROSS_PAPER_KEY="t", FAKE_MT5=mt5.url, FAKE_GATEWAY=paper.url, FAKE_CROSS=mode)
        out = os.path.join(work, "run")
        done = subprocess.run([sys.executable, RUNNER, "--case", case, "--out", out, "--gateway-url", mt5.url,
                               "--expected-login", "1", "--expected-server", "FakeServer", "--magic", "929099",
                               "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01", "--venue", f"PAPER={paper.url}@paper",
                               "--cli", os.path.join(HERE, "fake_cross_qkt.py"), "--budget-seconds", "40"],
                              env=env, capture_output=True, text=True, timeout=120)
        with open(os.path.join(out, "result.json")) as file:
            return done, json.load(file)

    def test_both_venues_booked_as_they_filled_pass_venue_by_venue(self):
        done, result = self.run_case("exact")
        self.assertEqual(result["status"], "passed", done.stdout + done.stderr + str(result.get("problems")))
        self.assertEqual(Decimal(result["venues"]["EXNESS"]["venueNet"]), Decimal("0.05"))
        self.assertEqual(Decimal(result["venues"]["PAPER"]["venueNet"]), Decimal("-0.070005"))
        self.assertEqual(Decimal(result["venues"]["PAPER"]["qktRealized"]), Decimal("-0.070005"))
        self.assertEqual([len(result["venues"][v]["fills"]) for v in ("EXNESS", "PAPER")], [2, 2])

    def test_two_venues_off_in_opposite_directions_fail_although_the_total_agrees(self):
        _, result = self.run_case("drift")
        problems = result["problems"]
        self.assertEqual(result["status"], "failed")
        self.assertTrue(any(p.startswith("deals-net-by-venue: EXNESS") for p in problems), problems)
        self.assertTrue(any(p.startswith("deals-net-by-venue: PAPER") for p in problems), problems)
        self.assertFalse(any(p.startswith("realized-total-equals-venues") for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
