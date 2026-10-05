"""Offline tests of the derivatives runner's expiry settlement: choosing the option, the venue's settlement cash,
and the verdict of a whole case run against a fake gateway and a fake qkt (fake_qkt.py).

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, subprocess, sys, tempfile, threading, time, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import derivatives_settlement as settlement  # noqa: E402

CASE = os.path.join(ROOT, "attestation/cases/derivatives/option-held-through-expiry")
RUNNER = os.path.join(ROOT, "scripts/live-validation/run-derivatives-lane-case.py")
CALL = "BTC_USDC-6OCT26-83000-C"
# Shapes recorded from the Deribit testnet gateway (qkt-venue-gateway 9b728e8) on 2026-10-05.
DELIVERY = {"symbol": CALL, "price": "2120.5", "time": 0, "costs": [{"kind": "delivery_fee", "amount": "0.12768", "currency": "USDC"}]}


def option(code, strike, expiry, right="call"):
    return {"code": code, "kind": "option", "currency": "USDC", "contract_size": "1", "tick_size": "5.0",
            "volume_step": "1.0", "volume_min": "0.01", "expiry": expiry, "strike": strike, "right": right,
            "underlying": "BTC_USDC"}


def deal(side, quantity, price, time_ms, fee="0.25", code=CALL):
    return {"client_order_id": "dsl-atto_expiry--1.x", "venue_order_id": "USDC-1", "fill_id": f"USDC-{time_ms}",
            "symbol": code, "side": side, "quantity": quantity, "price": price, "time": time_ms,
            "costs": [{"kind": "commission", "amount": fee, "currency": "USDC"}]}


class SettlementAccounting(unittest.TestCase):
    def test_settlement_cash_is_the_holding_at_the_price_less_its_fee(self):
        held = settlement.held_settlements([dict(DELIVERY, time=100)], [deal("buy", "0.01", "3060", 50)])
        self.assertEqual(held[0]["holding"], Decimal("0.01"))
        self.assertEqual(settlement.settlement_cash(held, {CALL: Decimal(1)}), Decimal("21.205") - Decimal("0.12768"))

    def test_a_contract_sold_before_it_settled_pays_nothing(self):
        deals = [deal("buy", "0.01", "3060", 50), deal("sell", "0.01", "3000", 60)]
        self.assertEqual(settlement.held_settlements([dict(DELIVERY, time=100)], deals), [])

    def test_a_short_holding_pays_the_settlement(self):
        held = settlement.held_settlements([dict(DELIVERY, time=100, costs=[])], [deal("sell", "0.02", "3000", 50)])
        self.assertEqual(settlement.settlement_cash(held, {}), Decimal("-42.41"))

    def booked(self, price="2120.5", side="SELL", qty="0.01"):
        return {"kind": "filled", "id": "settle:DERIBIT:BTC_USDC_6OCT26_83000_C:atto_expiry",
                "symbol": "DERIBIT:BTC_USDC_6OCT26_83000_C", "side": side, "price": price, "qty": qty}

    def held(self):
        return settlement.held_settlements([dict(DELIVERY, time=100)], [deal("buy", "0.01", "3060", 50)])

    def test_one_booking_at_the_venue_price_passes(self):
        self.assertEqual(settlement.judge(self.held(), [self.booked("2120.50")], "DERIBIT", 1), [])

    def test_a_settlement_booked_twice_fails(self):
        problems = settlement.judge(self.held(), [self.booked(), self.booked()], "DERIBIT", 1)
        self.assertIn("booked it 2 times", problems[0])

    def test_a_settlement_at_another_price_fails(self):
        problems = settlement.judge(self.held(), [self.booked("2125")], "DERIBIT", 1)
        self.assertTrue(problems[0].startswith("settlement-at-venue-price"))

    def test_a_missed_settlement_fails(self):
        self.assertIn("booked it 0 times", settlement.judge(self.held(), [], "DERIBIT", 1)[0])

    def test_a_settlement_the_venue_never_made_fails(self):
        problems = settlement.judge([], [self.booked()], "DERIBIT", 0)
        self.assertIn("the venue never made", problems[0])

    def test_no_settlement_when_one_is_needed_fails(self):
        self.assertIn("settled 0 held contract", settlement.judge([], [], "DERIBIT", 1)[0])


class ExpiringOption(unittest.TestCase):
    SPEC = {"underlying": "BTC_USDC", "right": "call", "min_minutes": 20, "max_minutes": 150,
            "in_the_money": 0.02, "max_spread": 0.15}
    NOW = 1_791_268_200_000  # 2026-10-06 06:30 UTC
    EXPIRY = 1_791_273_600_000  # 08:00

    def books(self, books):
        return lambda code: books.get(code)

    def test_picks_the_nearest_the_money_call_with_a_two_sided_book(self):
        instruments = [option("C-81000", "81000", self.EXPIRY), option("C-83000", "83000", self.EXPIRY),
                       option("C-84000", "84000", self.EXPIRY), option("C-83500", "83500", self.EXPIRY),
                       option("C-82000-NEXT", "82000", self.EXPIRY + 86_400_000)]
        books = {"C-83500": {"bids": [], "asks": [["2500", "1"]]}, "C-83000": {"bids": [["2800", "1"]], "asks": [["3060", "1"]]},
                 "C-81000": {"bids": [["4800", "1"]], "asks": [["4960", "1"]]}}
        code, evidence = settlement.expiring_option(instruments, self.SPEC, Decimal("85900"), self.books(books), self.NOW)
        self.assertEqual(code, "C-83000")  # 84000 is not 2% in the money; 83500 has no bid
        self.assertEqual((evidence["bid"], evidence["ask"], evidence["expiry"]), ("2800", "3060", self.EXPIRY))

    def test_a_wide_book_is_skipped(self):
        instruments = [option("C-83000", "83000", self.EXPIRY)]
        books = {"C-83000": {"bids": [["2000", "1"]], "asks": [["3060", "1"]]}}
        with self.assertRaisesRegex(RuntimeError, "venue-untradeable"):
            settlement.expiring_option(instruments, self.SPEC, Decimal("85900"), self.books(books), self.NOW)

    def test_an_expiry_too_close_is_refused(self):
        instruments = [option("C-83000", "83000", self.NOW + 10 * 60_000)]
        with self.assertRaisesRegex(RuntimeError, "20 to 150 minutes"):
            settlement.expiring_option(instruments, self.SPEC, Decimal("85900"), self.books({}), self.NOW)

    def test_a_put_is_in_the_money_above_the_index(self):
        instruments = [option("P-88000", "88000", self.EXPIRY, "put"), option("P-86000", "86000", self.EXPIRY, "put")]
        books = {"P-88000": {"bids": [["2040", "1"]], "asks": [["2240", "1"]]}}
        spec = dict(self.SPEC, right="put")
        self.assertEqual(settlement.expiring_option(instruments, spec, Decimal("85900"), self.books(books), self.NOW)[0], "P-88000")


class FakeGateway:
    """A Deribit-shaped VGP gateway: flat, then holding the call bought, then flat with the call settled and delisted."""

    def __init__(self):
        now = int(time.time() * 1000)
        self.expiry = now + 90 * 60_000
        self.code = CALL
        self.instruments = [option(CALL, "83000", self.expiry), option("BTC_USDC-6OCT26-84500-C", "84500", self.expiry),
                            dict(option("BTC_USDC-PERPETUAL", None, None), kind="perpetual", contract_size="1")]
        self.positions, self.deals, self.settlements = [], [], []
        self.balance = Decimal("99681.56497625")
        gateway = self

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
                self.reply(gateway.answer(url.path, {k: v[0] for k, v in parse_qs(url.query).items()}))

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length") or 0))
                self.reply(gateway.act(urlsplit(self.path).path))

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def answer(self, path, query):
        now = int(time.time() * 1000)
        if path == "/v1/health":
            return {"protocol": "vgp1", "adapter": "deribit", "account_login": "test-login", "trade_mode": "demo",
                    "venue_connected": True, "kill_switch": {"all": False, "symbols": []},
                    "capabilities": ["bars", "quotes", "settlements", "mark_prices", "depth"]}
        if path == "/v1/instruments":
            return self.instruments
        if path == "/v1/positions":
            return {"accounting": "netting", "positions": self.positions}
        if path == "/v1/orders":
            return {"orders": []}
        if path == "/v1/account":
            return {"currency": "USDC", "balance": str(self.balance), "equity": str(self.balance)}
        if path == "/v1/marks":
            return {"marks": [{"time": now - 30_000, "mark": "85920.1", "index": "85900.4"}]}
        if path == "/v1/depth":
            books = {CALL: {"time": now, "bids": [["2800", "100.0"]], "asks": [["3060.0", "100.0"]]}}
            return {"depth": [books[query["symbol"]]] if query["symbol"] in books else []}
        if path == "/v1/deals":
            return {"deals": [d for d in self.deals if int(query["from"]) <= d["time"] <= int(query["to"])]}
        if path == "/v1/settlements":
            return {"settlements": [s for s in self.settlements if int(query["from"]) <= s["time"] <= int(query["to"])]}
        if path == "/v1/funding":
            return {"funding": []}
        raise AssertionError(f"unexpected GET {path}")

    def act(self, path):
        now = int(time.time() * 1000)
        if path == "/test/buy":
            self.deals.append(dict(deal("buy", "0.01", "3060.0", now), client_order_id="dsl-atto_expiry--1.mx"))
            self.positions = [{"symbol": CALL, "quantity": "0.01", "avg_price": "3060.0"}]
            self.balance -= Decimal("30.60") + Decimal("0.25")
            return {}
        if path == "/test/expire":  # the option settled at 08:00 and left the listing
            settled = dict(DELIVERY, time=now)
            self.settlements.append(settled)
            self.positions = []
            self.instruments = [i for i in self.instruments if i["code"] != CALL]
            self.balance += Decimal("21.205") - Decimal("0.12768")
            return settled
        if path == "/v1/positions/close":
            self.positions = []
            return {}
        raise AssertionError(f"unexpected POST {path}")


class CaseRun(unittest.TestCase):
    """The whole runner, as the timer starts it, on the expiry case."""

    def run_case(self, mode, budget=30):
        gateway = FakeGateway()
        self.addCleanup(gateway.server.server_close)
        self.addCleanup(gateway.server.shutdown)
        out = os.path.join(tempfile.mkdtemp(), "run")
        env = dict(os.environ, QKT_DERIV_GATEWAY_KEY="token", QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY",
                   FAKE_GATEWAY=gateway.url, FAKE_SETTLE=mode)
        done = subprocess.run([sys.executable, RUNNER, "--case", CASE, "--out", out, "--gateway-url", gateway.url,
                               "--expected-login", "test-login", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                               "--cli", os.path.join(HERE, "fake_qkt.py"), "--budget-seconds", str(budget)],
                              env=env, capture_output=True, text=True, timeout=budget + 60)
        with open(os.path.join(out, "result.json")) as result:
            return done, json.load(result)

    def test_a_settlement_booked_once_at_the_venue_price_passes(self):
        done, result = self.run_case("once")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["selected"]["code"], CALL)
        self.assertEqual(len(result["fills"]), 1)  # the BUY; the settlement close is no order's fill
        self.assertEqual(result["settlements"][0]["holding"], "0.01")
        expected = (Decimal("21.205") - Decimal("30.60")) - Decimal("0.25") - Decimal("0.12768")
        self.assertEqual(Decimal(result["venueNet"]), expected)
        self.assertEqual(Decimal(result["qktRealized"]), expected)
        self.assertEqual(Decimal(result["balanceMoved"]), expected)

    def test_a_settlement_booked_twice_fails(self):
        done, result = self.run_case("twice")
        self.assertEqual(done.returncode, 1)
        self.assertTrue(any("booked it 2 times" in p for p in result["problems"]), result["problems"])
        self.assertTrue(any(p.startswith("deals-net-equals-realized") for p in result["problems"]))
        self.assertTrue(any(p.startswith("account-balance-equals-realized") for p in result["problems"]))

    def test_a_settlement_at_another_price_fails(self):
        _, result = self.run_case("at_mark")
        self.assertTrue(any(p.startswith("settlement-at-venue-price") for p in result["problems"]), result["problems"])

    def test_a_settlement_never_booked_fails_when_the_budget_ends(self):
        _, result = self.run_case("never", budget=12)
        self.assertEqual(result["status"], "failed")
        self.assertTrue(any("booked it 0 times" in p for p in result["problems"]), result["problems"])


if __name__ == "__main__":
    unittest.main()
