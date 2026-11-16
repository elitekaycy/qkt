"""The gateway lane runner on an event contract: the kalshi lane's `kalshi-event-held-to-settlement`, whole, against a
Kalshi-shaped fake gateway and a fake qkt (fake_event_qkt.py). An event slot names no contract, the short holding (NO)
is closed by the venue's settlement at 1 or 0 and never by a deal, the venue serves no funding (501), and the case
declares no futures or options root.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, subprocess, sys, tempfile, threading, time, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
CASE = os.path.join(ROOT, "attestation/cases/kalshi/kalshi-event-held-to-settlement")
RUNNER = os.path.join(ROOT, "scripts/live-validation/run-derivatives-lane-case.py")
MARKET = "KXDEMOFUTHOURLY-26NOV16H14-UP"
BID = Decimal("0.01")
# 0.07 x 1 contract x 0.01 x 0.99 = 0.000693, rounded up to the listing's fee_rounding of 0.0001.
FEE = Decimal("0.0007")


class FakeGateway:
    """A Kalshi-shaped VGP gateway: one hourly event contract with a bid and no offer, then the strategy's NO, then the
    contract settled at its outcome and delisted. The listing, health and settlement shapes are the Kalshi demo
    gateway's (qkt-venue-gateway adapter-kalshi 0.3.2) as read on 2026-11-16."""

    def __init__(self, outcome):
        now = int(time.time() * 1000)
        self.outcome = Decimal(outcome)
        self.instruments = [{"code": MARKET, "kind": "event", "currency": "USD", "contract_size": "1",
                             "tick_size": "0.0100", "volume_step": "0.01", "volume_min": "0.01",
                             "expiry": now + 65 * 60_000, "category": "Test", "series": "KXDEMOFUTHOURLY",
                             "event": "KXDEMOFUTHOURLY-26NOV16H14", "strike_type": "none", "open_at": now - 60_000,
                             "close_at": now + 60 * 60_000, "frequency": "1h", "title": "At or above reference",
                             "fee_model": "probability", "taker_fee_rate": "0.07", "fee_rounding": "0.0001"}]
        self.positions, self.deals, self.settlements = [], [], []
        self.balance = Decimal("997.9528")
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, body, status=200):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                url = urlsplit(self.path)
                if url.path == "/v1/funding":  # an event venue charges none: the gateway says so
                    return self.reply({"error": "unsupported"}, 501)
                self.reply(gateway.answer(url.path, {k: v[0] for k, v in parse_qs(url.query).items()}))

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length") or 0))
                self.reply(gateway.act(urlsplit(self.path).path))

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def answer(self, path, query):
        if path == "/v1/health":
            return {"protocol": "vgp1", "adapter": "kalshi", "account_login": "test-login", "trade_mode": "demo",
                    "venue_connected": True, "kill_switch": {"all": False, "symbols": []},
                    "capabilities": ["bars", "quotes", "settlements", "open_interest", "trades", "depth"]}
        if path == "/v1/instruments":
            return self.instruments
        if path == "/v1/positions":
            return {"accounting": "netting", "positions": self.positions}
        if path == "/v1/orders":
            return {"orders": []}
        if path == "/v1/account":
            return {"currency": "USD", "balance": str(self.balance), "equity": str(self.balance)}
        if path == "/v1/deals":
            return {"deals": [d for d in self.deals if int(query["from"]) <= d["time"] <= int(query["to"])]}
        if path == "/v1/settlements":
            return {"settlements": [s for s in self.settlements if int(query["from"]) <= s["time"] <= int(query["to"])]}
        raise AssertionError(f"unexpected GET {path}")

    def act(self, path):
        now = int(time.time() * 1000)
        if path == "/test/sell":  # one contract sold into the bid: NO bought at 1 - 0.01
            deal = {"client_order_id": "ORD-atto_event_settle-1.mx", "venue_order_id": "o-1", "fill_id": f"f-{now}",
                    "symbol": MARKET, "side": "sell", "quantity": "1", "price": str(BID), "time": now,
                    "costs": [{"kind": "commission", "amount": str(FEE), "currency": "USD"}]}
            self.deals.append(deal)
            self.positions = [{"symbol": MARKET, "quantity": "-1", "avg_price": str(BID)}]
            self.balance -= (1 - BID) + FEE
            return deal
        if path == "/test/settle":  # the event resolved: NO pays 1 less the outcome, and the market leaves the listing
            settled = {"symbol": MARKET, "price": str(self.outcome), "time": now, "costs": []}
            self.settlements.append(settled)
            self.positions = []
            self.instruments = []
            self.balance += 1 - self.outcome
            return settled
        raise AssertionError(f"unexpected POST {path}")


class EventCaseRun(unittest.TestCase):
    def run_case(self, outcome, mode="once", budget=30):
        gateway = FakeGateway(outcome)
        self.addCleanup(gateway.server.server_close)
        self.addCleanup(gateway.server.shutdown)
        out = os.path.join(tempfile.mkdtemp(), "run")
        env = dict(os.environ, QKT_ATTEST_UTC_DAY_WAIT="off", QKT_DERIV_GATEWAY_KEY="token",
                   QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", FAKE_GATEWAY=gateway.url, FAKE_SETTLE=mode)
        done = subprocess.run([sys.executable, RUNNER, "--case", CASE, "--out", out, "--gateway-url", gateway.url,
                               "--expected-login", "test-login", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                               "--cli", os.path.join(HERE, "fake_event_qkt.py"), "--budget-seconds", str(budget)],
                              env=env, capture_output=True, text=True, timeout=budget + 60)
        with open(os.path.join(out, "result.json")) as result:
            return done, json.load(result)

    def passed(self, outcome, expected):
        done, result = self.run_case(outcome)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual((result["status"], result["lane"]), ("passed", "kalshi"))
        self.assertEqual([(f["side"], f["symbol"]) for f in result["fills"]],
                         [("SELL", "KALSHI:KXDEMOFUTHOURLY_26NOV16H14_UP")])  # the settlement close is no order's fill
        self.assertEqual(result["settlements"][0]["holding"], "-1")
        for key in ("venueNet", "qktRealized", "balanceMoved"):
            self.assertEqual(Decimal(result[key]), expected, key)
        self.assertIsNone(result["balanceNote"])

    def test_a_contract_resolved_no_pays_the_seller_its_bid_less_the_fee(self):
        self.passed("0", BID - FEE)

    def test_a_contract_resolved_yes_costs_the_seller_one_less_its_bid_and_the_fee(self):
        self.passed("1", BID - FEE - 1)

    def test_a_settlement_booked_at_the_last_quote_fails(self):
        done, result = self.run_case("1", mode="at_quote")
        self.assertEqual(done.returncode, 1)
        for name in ("settlement-at-venue-price", "deals-net-equals-realized", "account-balance-equals-realized"):
            self.assertTrue(any(p.startswith(name) for p in result["problems"]), (name, result["problems"]))

    def test_a_settlement_never_booked_fails_when_the_budget_ends(self):
        _, result = self.run_case("0", mode="never", budget=12)
        self.assertEqual(result["status"], "failed")
        self.assertTrue(any("booked it 0 times" in p for p in result["problems"]), result["problems"])


if __name__ == "__main__":
    unittest.main()
