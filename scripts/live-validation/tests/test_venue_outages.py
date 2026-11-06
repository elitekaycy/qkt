"""A third-party venue outage never spends a case's attempts and never passes one; an earlier run's leftovers of the
case's own strategy are swept before a new attempt, and nothing else on the shared account is touched.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import datetime as dt, importlib.util, json, os, subprocess, sys, tempfile, threading, time, unittest
from unittest import mock
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib")); sys.path.insert(0, HERE)
import attest_cases  # noqa: E402
import attest_classify as c  # noqa: E402
import catalog_fixture as fx  # noqa: E402
import test_derivatives_settlement as expiry  # noqa: E402
import venue_health  # noqa: E402

CALL = expiry.CALL
RUN_ENV = {"QKT_ATTEST_UTC_DAY_WAIT": "off", "QKT_DERIV_GATEWAY_KEY": "token",
           "QKT_LIVE_DEMO_ORDER_APPROVAL": "LOCALHOST_DEMO_ONLY", "FAKE_SETTLE": "once",
           "QKT_ATTEST_VENUE_POLL_S": "0.3", "QKT_ATTEST_VENUE_WATCH_S": "0.3"}


class FakeDeribit:
    """Deribit's public/ticker: [state] per instrument (`open`, `locked`), [stale] ages every timestamp by ten minutes."""

    def __init__(self):
        self.state, self.stale, self.calls = "open", False, 0
        deribit = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                deribit.calls += 1
                url = urlsplit(self.path)
                name = parse_qs(url.query)["instrument_name"][0]
                stamp = int(time.time() * 1000) - (600_000 if deribit.stale else 0)
                body = json.dumps({"result": {"instrument_name": name, "state": deribit.state, "timestamp": stamp}}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}/api/v2"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


class OutageGateway(expiry.FakeGateway):
    """The expiry case's gateway whose venue link is down until [up_at] (epoch s), and which records every order it is
    sent or asked to cancel. [leftover] seeds an earlier run's holding of the case's own strategy; another user always
    holds the perpetual and rests an order."""

    def __init__(self, up_at=0.0, leftover=False):
        super().__init__()
        self.up_at, self.posted, self.deleted = up_at, [], []
        self.down_from = self.down_until = 0.0  # a window inside the case when the link drops
        self.foreign_position = {"symbol": "BTC_USDC-PERPETUAL", "quantity": "0.5", "avg_price": "80000", "ticket": None}
        self.foreign_order = {"client_order_id": "soak-s01-7", "symbol": "BTC_USDC-PERPETUAL", "side": "buy",
                              "type": "limit", "quantity": "0.1", "limit_price": "40000"}
        self.leftover_orders = []
        if leftover:
            earlier = int(time.time() * 1000) - 3_600_000
            self.deals.append(dict(expiry.deal("buy", "0.01", "3000.0", earlier), client_order_id="dsl-atto_expiry--9.old"))
            self.positions = [{"symbol": CALL, "quantity": "0.01", "avg_price": "3000.0"}]
            self.leftover_orders = [{"client_order_id": "dsl-atto_expiry--9.tp", "symbol": CALL, "side": "sell",
                                     "type": "limit", "quantity": "0.01", "limit_price": "9000"}]
        gateway = self

        def do_delete(handler):
            path = urlsplit(handler.path).path
            gateway.deleted.append(path)
            gateway.leftover_orders = [o for o in gateway.leftover_orders if not path.endswith(o["client_order_id"])]
            handler.reply({})
        self.server.RequestHandlerClass.do_DELETE = do_delete
        original_post = self.server.RequestHandlerClass.do_POST

        def do_post(handler):
            if urlsplit(handler.path).path == "/v1/orders":
                order = json.loads(handler.rfile.read(int(handler.headers.get("Content-Length") or 0)))
                gateway.posted.append(order)
                gateway.fill(order)
                return handler.reply(dict(order, status="filled"))
            return original_post(handler)
        self.server.RequestHandlerClass.do_POST = do_post

    def fill(self, order):
        now = int(time.time() * 1000)
        self.deals.append(dict(expiry.deal(order["side"], order["quantity"], "3000.0", now),
                               symbol=order["symbol"], client_order_id=order["client_order_id"]))
        sign = Decimal(order["quantity"]) * (1 if order["side"] == "buy" else -1)
        held = {p["symbol"]: Decimal(p["quantity"]) for p in self.positions}
        held[order["symbol"]] = held.get(order["symbol"], Decimal(0)) + sign
        self.positions = [{"symbol": s, "quantity": str(q), "avg_price": "3000.0"} for s, q in held.items() if q != 0]

    def answer(self, path, query):
        if path == "/v1/health":
            health = super().answer(path, query)
            now = time.time()
            health["venue_connected"] = now >= self.up_at and not (self.down_from <= now < self.down_until)
            return health
        if path == "/v1/positions":
            return {"accounting": "netting", "positions": self.positions + [self.foreign_position]}
        if path == "/v1/orders":
            return {"orders": [self.foreign_order] + self.leftover_orders}
        return super().answer(path, query)


def run_case(test, gateway, budget=30, extra_env=None):
    test.addCleanup(gateway.server.server_close)
    test.addCleanup(gateway.server.shutdown)
    out = os.path.join(tempfile.mkdtemp(), "run")
    env = dict(os.environ, FAKE_GATEWAY=gateway.url, **RUN_ENV, **(extra_env or {}))
    started = time.time()
    done = subprocess.run([sys.executable, expiry.RUNNER, "--case", expiry.CASE, "--out", out, "--gateway-url",
                           gateway.url, "--expected-login", "test-login", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                           "--cli", os.path.join(HERE, "fake_qkt.py"), "--budget-seconds", str(budget)],
                          env=env, capture_output=True, text=True, timeout=budget + 90)
    with open(os.path.join(out, "result.json")) as file:
        return done, json.load(file), time.time() - started


class Probe(unittest.TestCase):
    def gateway(self):
        gateway = OutageGateway()
        self.addCleanup(gateway.server.server_close); self.addCleanup(gateway.server.shutdown)
        return gateway

    def test_a_refused_connection_is_unavailable_not_an_error(self):
        sample = venue_health.Probe("D", "http://127.0.0.1:1", "t").sample()
        self.assertFalse(sample["available"])
        self.assertIn("unreachable", sample["reason"])

    def test_a_stale_ticker_is_unavailable_and_an_open_fresh_one_available(self):
        deribit = FakeDeribit(); deribit.stale = True
        self.addCleanup(deribit.server.server_close); self.addCleanup(deribit.server.shutdown)
        probe = venue_health.Probe("D", self.gateway().url, "t", ["BTC_USDC_6OCT26_83000_C", "BTC_USDC"], deribit.url)
        self.assertIn("stale", probe.sample()["reason"])
        deribit.stale = False
        self.assertTrue(probe.sample()["available"])
        self.assertEqual(probe.codes, [CALL])  # the index has nothing to check

    def test_without_a_deribit_url_only_the_venue_link_is_checked(self):
        probe = venue_health.Probe("D", self.gateway().url, "t", ["BTC_USDC_6OCT26_83000_C"], deribit_url="")
        self.assertEqual(probe.sample()["reason"], "venue_connected")

    def test_the_wait_is_bounded_and_reports_its_evidence(self):
        probe = venue_health.Probe("D", "http://127.0.0.1:1", "t")
        with self.assertRaises(venue_health.VenueUnavailable) as raised:
            venue_health.await_available([probe], max_s=0.2, poll_s=0.1)
        self.assertTrue(str(raised.exception).startswith("venue unavailable for 0.0h: D:"))
        self.assertTrue(raised.exception.wait[0]["exceeded"])


class VenueGate(unittest.TestCase):
    def test_venue_down_at_the_start_waits_then_runs_in_the_same_attempt(self):
        gateway = OutageGateway(up_at=time.time() + 2)
        done, result, _ = run_case(self, gateway)
        self.assertEqual(result["status"], "passed", done.stdout + done.stderr)
        waits = result["venueWaits"]
        self.assertEqual(len(waits), 1)
        self.assertIn("venue link", waits[0]["reason"])
        self.assertGreaterEqual(waits[0]["seconds"], 1)
        self.assertIn("waiting up to", done.stderr)

    def test_an_instrument_locked_on_the_venue_holds_the_case_back(self):
        deribit = FakeDeribit(); deribit.state = "locked"
        self.addCleanup(deribit.server.server_close); self.addCleanup(deribit.server.shutdown)
        threading.Timer(2, lambda: setattr(deribit, "state", "open")).start()
        done, result, _ = run_case(self, OutageGateway(), extra_env={"QKT_ATTEST_DERIBIT_URL": deribit.url})
        self.assertEqual(result["status"], "passed", done.stdout + done.stderr)
        self.assertIn(f"Deribit {CALL} is locked", result["venueWaits"][0]["reason"])

    def test_past_the_wait_maximum_the_case_fails_with_a_distinct_reason_and_its_evidence(self):
        gateway = OutageGateway(up_at=time.time() + 3600)
        done, result, _ = run_case(self, gateway, extra_env={"QKT_ATTEST_VENUE_WAIT_MAX_S": "1"})
        self.assertEqual(result["status"], "failed")
        self.assertTrue(result["problems"][0].startswith("venue unavailable for 0.0h:"), result["problems"])
        self.assertTrue(result["venueWaits"][-1]["exceeded"])
        self.assertEqual(gateway.posted, [])  # it never touched the account

    def test_an_outage_during_the_case_is_sampled_into_the_result(self):
        gateway = OutageGateway()
        gateway.down_from, gateway.down_until = time.time() + 2, time.time() + 4
        done, result, _ = run_case(self, gateway)
        outage = result["venueOutage"]
        self.assertGreater(outage["unavailableSamples"], 0, done.stderr)
        self.assertIn("venue link", outage["reason"])
        # The case still passed every assertion, so it passes: an outage never fails a sound attempt, nor passes a bad one.
        self.assertEqual(result["status"], "passed", result["problems"])


class LeftoverSweep(unittest.TestCase):
    def test_an_earlier_run_s_own_holding_and_order_are_swept_then_the_case_runs(self):
        gateway = OutageGateway(leftover=True)
        done, result, _ = run_case(self, gateway)
        self.assertEqual(result["status"], "passed", done.stdout + done.stderr)
        swept = result["leftoversSwept"]
        self.assertEqual(swept["held"], {CALL: "0.01"})
        self.assertEqual(swept["cancelled"], ["dsl-atto_expiry--9.tp"])
        close = gateway.posted[0]
        self.assertEqual((close["symbol"], close["side"], close["quantity"], close["reduce_only"]), (CALL, "sell", "0.01", True))
        self.assertTrue(close["client_order_id"].startswith("ORD-atto_expiry-sweep-"))
        # Another user's position and order are never touched, and the sweep is outside the judged window.
        self.assertTrue(all(o["symbol"] == CALL for o in gateway.posted))
        self.assertEqual(gateway.deleted, ["/v1/orders/dsl-atto_expiry--9.tp"])
        self.assertEqual(len(result["fills"]), 1)
        self.assertEqual(Decimal(result["venueNet"]), Decimal(result["qktRealized"]))


def write_result(folder, problems, **evidence):
    os.makedirs(folder)
    json.dump({"status": "failed" if problems else "passed", "problems": problems, **evidence},
              open(os.path.join(folder, "result.json"), "w"))


OUTAGE = {"samplesTaken": 9, "unavailableSamples": 4, "from": "2026-11-04T10:00:00Z", "to": "2026-11-04T10:02:00Z",
          "venues": ["DERIBIT"], "reason": "gateway has no venue link (venue_connected false)",
          "unavailable": [{"time": 1, "available": False, "reason": "gateway has no venue link", "venue": "DERIBIT"}]}


class Ledger(unittest.TestCase):
    def setUp(self):
        self.out = tempfile.mkdtemp()
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", self.out]))

    def record(self, name, folder, problems, **evidence):
        path = os.path.join(self.out, folder)
        write_result(path, problems, **evidence)
        args = type("A", (), dict(out=self.out, name=name, dir=path, log="/dev/null", exit=1 if problems else 0,
                                  started="2026-11-04T10:00:00Z", no_retry=False, require_result=True,
                                  session_closed=False))
        attest_cases.record(args)
        return attest_cases.attempts_of(self.out, name)[-1]

    def test_zero_fills_with_the_venue_healthy_is_a_real_failure(self):
        entry = self.record("derivatives-x", "derivatives-x", ["expected 1 fills within the budget, saw 0"])
        self.assertEqual(entry["class"], c.BEHAVIOUR)

    def test_a_timeout_with_no_outage_sampled_is_not_excused(self):
        entry = self.record("derivatives-x", "derivatives-x", ["runner error: The read operation timed out"])
        self.assertEqual(entry["class"], c.INFRASTRUCTURE)

    def test_zero_fills_during_a_sampled_outage_reruns_and_keeps_its_evidence(self):
        entry = self.record("derivatives-x", "derivatives-x", ["expected 1 fills within the budget, saw 0"],
                            venueOutage=OUTAGE)
        self.assertEqual(entry["class"], c.VENUE_OUTAGE)
        self.assertEqual(entry["status"], "failed")  # recorded as a failed attempt, never dropped
        self.assertEqual(entry["venue"]["outage"]["unavailableSamples"], 4)
        self.assertIn("retryAfterSeconds", entry)

    def test_a_wrong_number_during_an_outage_stays_a_real_failure(self):
        entry = self.record("derivatives-x", "derivatives-x", ["deals-net-equals-realized: venue net 1 != qkt realized 2"],
                            venueOutage=OUTAGE)
        self.assertEqual(entry["class"], c.BEHAVIOUR)

    def test_the_wait_maximum_is_its_own_final_class(self):
        waits = [{"venue": "DERIBIT", "reason": "gateway has no venue link", "seconds": 21700, "exceeded": True}]
        entry = self.record("derivatives-x", "derivatives-x", ["venue unavailable for 6.0h: DERIBIT: gateway has no venue link"],
                            venueWaits=waits)
        self.assertEqual(entry["class"], c.VENUE_UNAVAILABLE)
        self.assertNotIn("retryAfterSeconds", entry)
        self.assertTrue(entry["venue"]["waits"][0]["exceeded"])

    def test_the_text_alone_never_makes_an_outage(self):
        entry = self.record("derivatives-x", "derivatives-x", ["venue unavailable for 6.0h: DERIBIT: down"])
        self.assertNotIn(entry["class"], (c.VENUE_OUTAGE, c.VENUE_UNAVAILABLE))

    def test_a_failed_attempt_that_left_leftovers_stays_failed_after_the_next_sweeps_and_passes(self):
        first = self.record("derivatives-x", "derivatives-x", ["flat-by-strategy: the strategy holds a position"])
        self.assertEqual((first["status"], first["class"]), ("failed", c.BEHAVIOUR))
        swept = {"held": {CALL: "0.01"}, "cancelled": [], "orders": []}
        second = self.record("derivatives-x", "derivatives-x-retry", [], leftoversSwept=swept)
        self.assertEqual(second["venue"]["leftoversSwept"], swept)
        verdict = attest_cases.verdict(self.out, "derivatives-x", 1)
        self.assertEqual(verdict["status"], "passed")
        self.assertTrue(verdict["flaky"])
        self.assertEqual(verdict["attempts"][0]["status"], "failed")


class CatalogBudget(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.fake = fx.build(self.tmp.name, {"derivatives": ["perp"]})
        self.out = os.path.join(self.tmp.name, "out")

    def run_lane(self, plan):
        return fx.run_catalog(self.fake, self.out, "derivatives",
                              ("--deriv-gateway-url", "http://127.0.0.1:1", "--deriv-expected-login", "g"),
                              behaviours={"perp": plan}, env={"QKT_ATTEST_VENUE_RERUN_DELAY": "0"})

    def runs(self):
        return {r["name"]: r for r in json.load(open(f"{self.out}/result.json"))["runs"]}

    def test_attempts_failed_during_an_outage_do_not_spend_the_case_s_budget(self):
        done = self.run_lane(["outage:expected 1 fills within the budget, saw 0",
                              "outage:runner error: gateway has no venue link", "fail:stale quote", "pass"])
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        attempts = self.runs()["derivatives-perp"]["attempts"]
        self.assertEqual([a["class"] for a in attempts], [c.VENUE_OUTAGE, c.VENUE_OUTAGE, c.BEHAVIOUR, None])
        self.assertIn("without spending an attempt", done.stdout)

    def test_zero_fills_on_a_healthy_venue_still_spends_the_budget(self):
        done = self.run_lane(["fail:expected 1 fills within the budget, saw 0"])
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(len(self.runs()["derivatives-perp"]["attempts"]), 2)

    def test_a_venue_unavailable_past_the_maximum_fails_by_name_once(self):
        done = self.run_lane(["unavailable:venue unavailable for 6.0h: DERIBIT: gateway has no venue link"])
        self.assertNotEqual(done.returncode, 0)
        run = self.runs()["derivatives-perp"]
        self.assertEqual((run["class"], len(run["attempts"])), (c.VENUE_UNAVAILABLE, 1))
        self.assertIn("FAILED derivatives-perp [venue-unavailable]: venue unavailable for 6.0h", done.stdout)


_spec = importlib.util.spec_from_file_location("gateway_lane_session", expiry.RUNNER)
gateway_lane = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gateway_lane)


def at(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


class Clock:
    def __init__(self, start):
        self.now = start

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class DownUntil:
    """A venue unavailable until [up] on the test's clock."""

    def __init__(self, clock, up):
        self.label, self.clock, self.up = "ALPACA", clock, up

    def sample(self):
        ms = int(self.clock.now * 1000)
        if self.clock.now >= self.up:
            return {"time": ms, "available": True, "reason": "venue_connected"}
        return {"time": ms, "available": False, "reason": "gateway has no venue link (venue_connected false)"}


class SessionBound(unittest.TestCase):
    """An alpaca share case (nyse[09:30-16:00], 420 s budget) on Wednesday 2026-11-04: 16:00 New York is 21:00Z, so the
    latest start is 20:53Z."""

    def run_at(self, start, up, session=None):
        clock = Clock(at(start))
        run = object.__new__(gateway_lane.Run)
        run.case = {"id": "alpaca-share-x", "lane": "alpaca", **({"session": session} if session else {})}
        run.lane_config = {"session": "nyse[09:30-16:00]", "budget_seconds": 420, "venue_gateway": True}
        patches = [mock.patch("time.time", clock.time), mock.patch("time.sleep", clock.sleep),
                   mock.patch.dict(os.environ, {"QKT_ATTEST_VENUE_POLL_S": "60"}),
                   mock.patch("sys.stderr", new=open(os.devnull, "w"))]
        for p in patches:
            p.start(); self.addCleanup(p.stop)
        return run, clock, [DownUntil(clock, at(up))]

    def test_the_venue_wait_is_bounded_by_the_window_and_ends_the_attempt_as_session(self):
        run, clock, probes = self.run_at("2026-11-04T20:50:00Z", "2026-11-04T20:55:00Z")
        self.assertEqual(run.session_bound(), (180.0, True))
        with self.assertRaises(gateway_lane.SessionClosed) as raised:
            run.await_venue(probes)
        self.assertTrue(str(raised.exception).startswith("session: the venue was unavailable until past the latest start"))
        self.assertLessEqual(clock.now, at("2026-11-04T20:54:00Z"))  # it gave up at the latest start, not at 6 h
        self.assertTrue(raised.exception.wait[0]["exceeded"])
        self.assertIsNone(run.watch)  # the judged window never opened

    def test_after_any_venue_wait_the_catalog_s_session_check_runs_again(self):
        run, _, probes = self.run_at("2026-11-04T20:50:00Z", "2026-11-04T20:55:00Z")
        run.session_bound = lambda: (None, False)  # whatever bounded the wait, the window decides
        with self.assertRaises(gateway_lane.SessionClosed) as raised:
            run.await_venue(probes)
        self.assertIn("no longer fits its nyse[09:30-16:00] window", str(raised.exception))

    def test_a_venue_back_in_time_lets_the_case_start(self):
        run, _, probes = self.run_at("2026-11-04T20:40:00Z", "2026-11-04T20:51:00Z")
        run.await_venue(probes)
        self.addCleanup(run.watch.stop)
        self.assertEqual(run.venue_waits[0]["venue"], "ALPACA")

    def test_a_24_7_case_is_bounded_only_by_the_profile(self):
        run, _, _ = self.run_at("2026-11-04T20:50:00Z", "2026-11-04T20:55:00Z", session="none")
        self.assertEqual(run.session_bound(), (None, False))

    def test_the_attempt_is_recorded_as_session_with_its_evidence_and_not_retried(self):
        out = tempfile.mkdtemp(); self.addCleanup(lambda: subprocess.run(["rm", "-rf", out]))
        folder = os.path.join(out, "alpaca-share-x")
        closed = {"reason": "session: after a venue wait ...", "at": "2026-11-04T20:55:00Z"}
        write_result(folder, ["session: after a venue wait the case no longer fits its window"],
                     sessionClosed=closed, venueWaits=[{"venue": "ALPACA", "seconds": 300, "exceeded": True}])
        args = type("A", (), dict(out=out, name="alpaca-share-x", dir=folder, log="/dev/null", exit=1,
                                  started="2026-11-04T20:50:00Z", no_retry=False, require_result=True,
                                  session_closed=False))
        attest_cases.record(args)
        entry = attest_cases.attempts_of(out, "alpaca-share-x")[-1]
        self.assertEqual(entry["class"], c.SESSION)
        self.assertNotIn("retryAfterSeconds", entry)
        self.assertEqual(entry["venue"]["sessionClosed"], closed)


if __name__ == "__main__":
    unittest.main()
