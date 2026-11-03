"""Offline tests of a lane case across a continuous roll: its planning, its verdict, and the whole runner on the shipped
roll cases against a fake gateway and qkt (fake_roll_qkt.py). No JVM, no venue: none of this says a roll works live.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import hashlib, json, os, shutil, subprocess, sys, tempfile, threading, time, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import derivatives_roll as rolling  # noqa: E402

RUNNER = os.path.join(ROOT, "scripts/live-validation/run-derivatives-lane-case.py")
CASES = os.path.join(ROOT, "attestation/cases/derivatives")
DAY = 86_400_000
AT = 1793635500000  # 2026-11-02T16:05:00Z
EXPIRY_3NOV = 1793692800000  # 2026-11-03T08:00:00Z
CATALOG = {"root": "DERIBIT:BTC_USDC", "contracts": [
    {"symbol": "BTC_USDC_29OCT26", "expiryMs": EXPIRY_3NOV - 5 * DAY},
    {"symbol": "BTC_USDC_2NOV26", "expiryMs": EXPIRY_3NOV - DAY},
    {"symbol": "BTC_USDC_3NOV26", "expiryMs": EXPIRY_3NOV},
    {"symbol": "BTC_USDC_4NOV26", "expiryMs": EXPIRY_3NOV + DAY}]}
FROM, TO = "BTC_USDC-3NOV26", "BTC_USDC-4NOV26"


class Planning(unittest.TestCase):
    def test_the_roll_is_on_a_whole_minute_at_least_the_lead_ahead(self):
        self.assertEqual(rolling.roll_at(AT - 600_000, 600), AT)
        self.assertEqual(rolling.roll_at(AT - 599_000, 600), AT + 60_000)
        self.assertEqual(rolling.hhmm(AT), "16:05")

    def test_the_contracts_are_the_first_expiring_after_the_rolls_day_and_the_next(self):
        frm, to = rolling.contracts_of_roll(CATALOG, AT)
        self.assertEqual((frm["symbol"], to["symbol"]), ("BTC_USDC_3NOV26", "BTC_USDC_4NOV26"))

    def test_a_gap_in_the_listing_is_no_roll(self):
        self.assertIsNone(rolling.contracts_of_roll(CATALOG, AT + 3 * DAY))

    def test_the_leg_prefix_is_the_engines_id(self):
        digest = hashlib.sha256(f"DERIBIT:BTC_USDC@front\n{AT}\ns".encode()).hexdigest()[:12]
        self.assertEqual(rolling.leg_prefix("DERIBIT:BTC_USDC@front", AT, "s"), f"roll:{AT}:{digest}:")

    def test_a_case_keeps_clear_of_the_venues_expiry(self):
        eight = 1793606400000  # 2026-11-02T08:00:00Z
        self.assertEqual(rolling.clear_of_expiry(eight - 8 * 60_000, 300), eight + 15 * 60_000)
        self.assertEqual(rolling.clear_of_expiry(eight + 16 * 60_000, 300), eight + 16 * 60_000)
        self.assertEqual(rolling.clear_of_expiry(eight - 3_600_000, 300), eight - 3_600_000)

    def test_a_far_reference_is_seeded_beyond_the_collar(self):
        contracts = rolling.contracts_of_roll(CATALOG, AT)
        history = rolling.seed_far_reference({"rolls": []}, AT, contracts, Decimal("78000"), 0.5)
        self.assertEqual(Decimal(history["rolls"][0]["toPrice"]), Decimal("39000"))
        again = rolling.seed_far_reference(history, AT, contracts, Decimal("78000"), 0.4)
        self.assertEqual(len(again["rolls"]), 1)

    def test_the_roll_before_is_added_so_the_stream_is_served_ahead_of_the_case(self):
        catalog = {"contracts": [c for c in CATALOG["contracts"] if c["symbol"] != "BTC_USDC_2NOV26"]}
        contracts = rolling.contracts_of_roll(catalog, AT)
        listed, history = rolling.with_previous_roll(catalog, {"rolls": []}, AT, contracts, Decimal("78000"))
        self.assertIn("BTC_USDC_2NOV26", [c["symbol"] for c in listed["contracts"]])
        self.assertEqual(history["rolls"][0]["atMs"], AT - DAY)
        self.assertEqual(history["rolls"][0]["fromPrice"], history["rolls"][0]["toPrice"])

    def test_the_policy_is_written_with_a_guard_inside_the_contracts_life(self):
        written = rolling.with_policy({"futures": [{"root": "DERIBIT:BTC_USDC"}]}, "DERIBIT:BTC_USDC", AT)
        self.assertEqual(written["futures"][0]["roll"], {"daysBeforeExpiry": 1, "atUtc": "16:05", "adjust": "panama"})
        self.assertEqual(written["futures"][0]["expiryGuardHours"], 1)

    def test_contracts_the_venue_serves_no_bars_of_are_skipped(self):
        # 2026-11-03 01:57Z: Deribit testnet charted 3NOV and 6NOV but not 4NOV or 5NOV, listed days before.
        at = 1793671020000
        catalog = {"contracts": CATALOG["contracts"] + [{"symbol": "BTC_USDC_5NOV26", "expiryMs": EXPIRY_3NOV + 2 * DAY},
                                                        {"symbol": "BTC_USDC_6NOV26", "expiryMs": EXPIRY_3NOV + 3 * DAY},
                                                        {"symbol": "BTC_USDC_13NOV26", "expiryMs": EXPIRY_3NOV + 10 * DAY},
                                                        {"symbol": "BTC_USDC_27NOV26", "expiryMs": EXPIRY_3NOV + 24 * DAY}]}
        priced = {"BTC_USDC_3NOV26", "BTC_USDC_6NOV26", "BTC_USDC_27NOV26"}.__contains__
        frm, to = rolling.contracts_of_roll(catalog, at, priced)
        self.assertEqual((frm["symbol"], to["symbol"]), ("BTC_USDC_6NOV26", "BTC_USDC_27NOV26"))
        self.assertEqual(rolling.days_before_expiry(frm, at), 3)
        chain = [c["symbol"] for c in rolling.catalog_of_roll(catalog, (frm, to), priced)["contracts"]]
        self.assertEqual(chain, ["BTC_USDC_6NOV26", "BTC_USDC_27NOV26"])
        self.assertIsNone(rolling.contracts_of_roll(catalog, at, {"BTC_USDC_6NOV26"}.__contains__))

    def test_the_policy_rolls_out_of_the_first_contract_at_the_cases_minute(self):
        at = 1793671020000  # 2026-11-03T01:57Z, 6NOV26 three days ahead
        frm = {"symbol": "BTC_USDC_6NOV26", "expiryMs": EXPIRY_3NOV + 3 * DAY}
        days = rolling.days_before_expiry(frm, at)
        rolled = (frm["expiryMs"] // DAY - days) * DAY + at % DAY  # RollPolicy.rollAtMs
        self.assertEqual(rolled, at)
        self.assertLess(rolled, frm["expiryMs"])

    def test_the_roll_before_is_the_only_one_before_the_case(self):
        contracts = rolling.contracts_of_roll(CATALOG, AT)
        measured = {"rolls": [{"atMs": AT - 2 * DAY, "from": "BTC_USDC_29OCT26", "to": "BTC_USDC_2NOV26"}]}
        _, history = rolling.with_previous_roll(CATALOG, measured, AT, contracts, Decimal("78000"))
        self.assertEqual([r["atMs"] for r in history["rolls"]], [AT - DAY])


class Collar(unittest.TestCase):
    def order(self, **over):
        return {"client_order_id": "x", "side": "sell", "type": "limit", "time_in_force": "ioc", "limit_price": "58500", **over}

    def test_a_roll_recorded_by_the_fetch_or_never_by_the_session_is_not_priced_live(self):
        plan = {"at_ms": AT, "prefetched": []}
        live = {"rolls": [{"atMs": AT, "from": "A", "to": "B", "fromPrice": "1", "toPrice": "1"}]}
        self.assertEqual([], rolling.priced_live(plan, live))
        self.assertTrue(rolling.priced_live(dict(plan, prefetched=live["rolls"]), live))
        self.assertTrue(rolling.priced_live(plan, {"rolls": []}))

    def test_a_limit_ioc_at_the_band_passes(self):
        self.assertEqual(rolling.collared(self.order(), "sell", Decimal("78000")), [])

    def test_a_market_leg_fails(self):
        self.assertTrue(rolling.collared(self.order(type="market", limit_price=None), "sell", Decimal("78000")))

    def test_a_limit_past_the_band_fails(self):
        self.assertTrue(rolling.collared(self.order(limit_price="38000"), "sell", Decimal("78000")))


class Gateway:
    def __init__(self):
        self.deals, self.orders, self.kill = [], {}, {"all": False, "symbols": []}
        gateway = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def reply(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                url = urlsplit(self.path)
                self.reply(200, gateway.answer(url.path, {k: v[0] for k, v in parse_qs(url.query).items()}))

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                self.reply(200, gateway.act(self.path, body))

            def do_DELETE(self):
                self.reply(200, {})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def answer(self, path, query):
        now = int(time.time() * 1000)
        if path == "/v1/health":
            return {"adapter": "paper", "account_login": "test-login", "trade_mode": "demo", "venue_connected": True,
                    "kill_switch": self.kill, "capabilities": ["bars"]}
        if path == "/v1/instruments":
            return [{"code": c, "kind": "future", "contract_size": "1"} for c in (FROM, TO)]
        if path == "/v1/bars":
            return {"bars": [{"start": now - 60_000, "close": "78000", "volume": "1"}]}
        if path == "/v1/account":
            return {"balance": "100000"}
        if path == "/v1/positions":
            return {"positions": []}
        if path == "/v1/orders":
            return {"orders": []}
        if path.startswith("/v1/orders/"):
            return self.orders[unquote(path.rsplit("/", 1)[1])]
        if path == "/v1/deals":
            return {"deals": [d for d in self.deals if int(query["from"]) <= d["time"] <= int(query["to"])]}
        if path == "/v1/settlements":
            return {"settlements": []}
        if path == "/v1/funding":
            return {"funding": []}
        raise AssertionError(path)

    def add(self, cid, code, side, price, quantity="0.001"):
        self.deals.append({"client_order_id": cid, "symbol": code, "side": side, "quantity": quantity, "price": price,
                           "time": int(time.time() * 1000), "costs": [], "fill_id": cid})

    def act(self, path, body):
        codes = {"FROM": FROM, "TO": TO}
        if path == "/test/deal":
            self.add(body["id"], codes[body["code"]], body["side"], body["price"])
            return {"price": body["price"]}
        if path == "/test/leg":
            digest = rolling.leg_prefix("DERIBIT:BTC_USDC@front", body["at"], body["strategy"])
            cid = f"{digest}{body['kind']}.1"
            self.add(cid, codes[body["code"]], body["side"], body["price"])
            self.orders[cid] = {"client_order_id": cid, "side": body["side"], "type": body.get("type", "limit"),
                                "time_in_force": "ioc", "limit_price": body.get("limit")}
            return {}
        if path == "/v1/orders":  # the runner's sweep
            self.add(body["client_order_id"], body["symbol"], body["side"], "78000", body["quantity"])
            return {}
        raise AssertionError(path)


class CaseRun(unittest.TestCase):
    def run_case(self, case, mode, resume_exit="1"):
        gateway = Gateway()
        self.addCleanup(gateway.server.server_close)
        self.addCleanup(gateway.server.shutdown)
        work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, work)
        copied = os.path.join(work, "derivatives", case)
        shutil.copytree(os.path.join(CASES, case), copied)
        document = yaml.safe_load(open(f"{copied}/case.yaml"))
        # The daemon starts at once; the fake qkt lists the contracts around the time the runner chose.
        spec = document["continuous_roll"]
        spec.update(start_before_seconds=100_000, start_late_seconds=1_000_000, post_stop_wait_seconds=0)
        yaml.safe_dump(document, open(f"{copied}/case.yaml", "w"))
        env = dict(os.environ, QKT_DERIV_GATEWAY_KEY="token", QKT_DERIV_GUARDIAN_KEY="guard",
                   QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", FAKE_GATEWAY=gateway.url, FAKE_ROLL=mode,
                   FAKE_RESUME_EXIT=resume_exit)
        out = os.path.join(work, "run")
        subprocess.run([sys.executable, RUNNER, "--case", copied, "--out", out, "--gateway-url", gateway.url,
                        "--expected-login", "test-login", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                        "--cli", os.path.join(HERE, "fake_roll_qkt.py"), "--budget-seconds", "15"],
                       env=env, capture_output=True, text=True, timeout=90)
        with open(f"{out}/result.json") as result:
            return json.load(result), gateway

    def test_a_carried_roll_passes(self):
        result, _ = self.run_case("continuous-roll-carries", "carried")
        self.assertEqual(result["problems"], [])
        self.assertEqual(len(result["rollLegs"]), 2)

    def test_market_legs_fail_the_collar_check(self):
        result, _ = self.run_case("continuous-roll-carries", "market_legs")
        self.assertTrue(any(p.startswith("roll-legs-collared") for p in result["problems"]), result["problems"])

    def test_a_far_book_with_no_leg_passes_and_the_entry_is_swept(self):
        result, gateway = self.run_case("continuous-roll-far-book-stops", "far_book")
        self.assertEqual(result["problems"], [])
        self.assertTrue(any("-sweep-" in d["client_order_id"] for d in gateway.deals))

    def test_a_leg_sent_into_a_far_book_fails(self):
        result, _ = self.run_case("continuous-roll-far-book-stops", "far_book_filled")
        self.assertTrue(any(p.startswith("roll-no-leg") for p in result["problems"]), result["problems"])

    def test_a_resume_that_says_resumed_while_held_fails(self):
        result, _ = self.run_case("continuous-roll-far-book-stops", "far_book", resume_exit="0")
        self.assertTrue(any(p.startswith("resume:") for p in result["problems"]), result["problems"])


if __name__ == "__main__":
    unittest.main()


class DrilledFillsAcrossARollTest(unittest.TestCase):
    """judge_drilled on a drilled roll case: the engine books neither leg of a carried roll, only a refused roll's close."""
    PREFIX = "roll:1793688600000:045166438ebe:"

    def judge(self, outcome, deals, fills):
        import importlib.util
        spec = importlib.util.spec_from_file_location("lane_drilled", RUNNER)
        lane = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(lane)
        case = type("Case", (), {})()
        case.roll = {"prefix": self.PREFIX, "outcome": outcome}
        case.strategy_deals = lambda: deals
        return lane.Run.judge_drilled(case, fills)

    def deal(self, cid, side, price):
        return {"client_order_id": cid, "side": side, "quantity": "0.001", "price": price}

    def test_a_carried_rolls_legs_are_not_engine_fills(self):
        deals = [self.deal("e1.1", "buy", "76545.0"), self.deal(self.PREFIX + "close.a", "sell", "76462.5"),
                 self.deal(self.PREFIX + "open.b", "buy", "76722.5"), self.deal("e2.2", "sell", "76800.0")]
        fills = [{"side": "BUY", "qty": "0.001", "price": "76545.0"}, {"side": "SELL", "qty": "0.001", "price": "79066.075"}]
        self.assertEqual([], self.judge("carried", deals, fills))

    def test_a_refused_rolls_close_is_the_engines_close(self):
        deals = [self.deal("e1.1", "buy", "76545.0"), self.deal(self.PREFIX + "close.a", "sell", "76462.5")]
        fills = [{"side": "BUY", "qty": "0.001", "price": "76545.0"}, {"side": "SELL", "qty": "0.001", "price": "76462.5"}]
        self.assertEqual([], self.judge("leg_refused", deals, fills))
