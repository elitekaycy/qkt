"""The venue gate looks at the books a case really trades (#1682): the `@front` roll's current and next contract, the
dated contract the runner picks, the option legs the daemon would select. On 2026-11-06 Deribit testnet had its dated
books `locked` (`11030 orderbook_disabled`) while the perpetuals were `open`; seven cases failed with 0 fills, class
behaviour and no outage evidence. Their problems and daemon log lines below are copied from that release
(attest-9cbac830-catalog). A healthy venue with 0 fills is still a real failure.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import importlib.util, json, os, shutil, subprocess, tempfile, threading, time, types, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock
from urllib.parse import parse_qs, urlsplit

import sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import attest_cases  # noqa: E402
import attest_classify as c  # noqa: E402
import venue_health  # noqa: E402

RUNNER = os.path.join(ROOT, "scripts/live-validation/run-derivatives-lane-case.py")
CASES = os.path.join(ROOT, "attestation/cases/derivatives")
DAY, HOUR = 86_400_000, 3_600_000
TODAY = int(time.time() * 1000) // DAY * DAY
PERP = "BTC_USDC-PERPETUAL"
D1, D2, D3 = "BTC_USDC-D1", "BTC_USDC-D2", "BTC_USDC-D3"  # dailies; D1 has no bars, so no roll trades it
W10, W21 = "BTC_USDC-W10", "BTC_USDC-W21"  # 10 and 21 days out; W21 traded in more minutes, so the runner picks it
# The put spread's legs (SELL PUT DELTA 0.25 DTE 20 TO 60, BUY PUT DELTA 0.10 SAME EXPIRY), forward 80000, IV 50%:
# the nearest expiry in the window is E30, and Black-76 |delta| is 0.301 at 75000 and 0.064 at 65000.
SHORT_PUT, LONG_PUT = "BTC_USDC-E30-75000-P", "BTC_USDC-E30-65000-P"

REFUSALS = """\
03:15:41.488 [main] WARN  [main] com.qkt.app.LiveSession - measured-usage window active for 24h: entries above 0.01 reject (risk.measured_usage_hours: 0 opts out)
03:16:00.655 [qkt-live-engine] WARN  [atto_dated] com.qkt.app.TradingPipeline - Order rejected: dsl-atto_dated--0 reason=venue_rejected: deribit error 11030: other_reject orderbook_disabled
03:17:02.295 [OkHttp http://127.0.0.1:18444/...] INFO  [main] c.q.connector.gateway.GatewayRouting - gateway event for '' waits for its strategy to attach: OrderRejected(clientOrderId=dsl-atto_dated--2, brokerOrderId=null, reason=deribit error 11030: other_reject orderbook_disabled, strategyId=, timestamp=0, sequenceId=0)
03:17:02.312 [qkt-live-engine] WARN  [atto_dated] com.qkt.app.TradingPipeline - Order rejected: dsl-atto_dated--2 reason=venue_rejected: deribit error 11030: other_reject orderbook_disabled
"""
HEALTHY_LOG = REFUSALS.splitlines()[0] + "\n03:16:00.655 [qkt-live-engine] INFO  [atto_dated] daemon ready\n"
RELEASE_PROBLEMS = {
    "continuous-roll-carries": [
        "expected 2 fills within the budget, saw 0",
        "roll-carried: the close leg should fill 0.001 on BTC_USDC-7NOV26, the venue reports []",
        "roll-carried: the open leg should fill 0.001 on BTC_USDC-8NOV26, the venue reports []"],
    "continuous-roll-far-book-stops": [
        "expected 1 fills within the budget, saw 0",
        "roll-position-stays: the strategy should hold 0.001 of BTC_USDC-7NOV26, its deals say {}",
        "roll-stopped: the daemon never logged the refusal that names the far quote",
        "resume: the strategy never stopped, so no resume was tried"],
    "continuous-roll-refused-leg-resumes": [
        "expected 3 fills within the budget, saw 0", "kill_switch: never fired (0 s after fill 1)",
        "roll-leg-refused: the closing leg should fill 0.001, it filled 0",
        "roll-stopped: the daemon never logged the opening leg's refusal",
        "resume: the strategy never stopped, so no resume was tried"],
    "continuous-roll-restored-moves-collar": [
        "expected 2 fills within the budget, saw 0", "gateway_outage: never fired (0 s after fill 1)",
        "expect_log: the daemon log never matched /rolled for atto_roll_restored/",
        "roll-carried: the close leg should fill 0.001 on BTC_USDC-7NOV26, the venue reports []",
        "roll-carried: the open leg should fill 0.001 on BTC_USDC-8NOV26, the venue reports []"],
    "dated-future-bracket-timed-exit": ["expected 2 fills within the budget, saw 0"],
    "dated-future-margin-floor-refuses-entry": [
        "expected 2 fills within the budget, saw 0", "qkt_restart: never fired (5 s after fill 1)",
        "expect_log: the daemon log never matched /risk rejected atto_margin_floor .*margin level .*below floor/"],
    "option-put-spread-open-close": ["expected 4 fills within the budget, saw 0"],
}


def listing():
    futures = [(D1, TODAY + DAY + 8 * HOUR), (D2, TODAY + 2 * DAY + 8 * HOUR), (D3, TODAY + 3 * DAY + 8 * HOUR),
               (W10, TODAY + 10 * DAY + 8 * HOUR), (W21, TODAY + 21 * DAY + 8 * HOUR)]
    found = [{"code": PERP, "kind": "perpetual", "contract_size": "1"}]
    found += [{"code": code, "kind": "future", "contract_size": "1", "expiry": expiry} for code, expiry in futures]
    for days, tag in ((10, "E10"), (30, "E30"), (45, "E45")):
        for strike in range(60000, 100001, 5000):
            for right in ("put", "call"):
                found.append({"code": f"BTC_USDC-{tag}-{strike}-{right[0].upper()}", "kind": "option", "currency": "USDC",
                              "contract_size": "1", "expiry": TODAY + days * DAY + 8 * HOUR, "strike": str(strike),
                              "right": right, "underlying": "BTC_USDC"})
    return found


def serve(answer):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            url = urlsplit(self.path)
            body = json.dumps(answer(url.path, {k: v[0] for k, v in parse_qs(url.query).items()})).encode()
            self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_port}"


class Venue:
    """A Deribit gateway (listing, bars) and Deribit's public API (ticker state, option book summary). Every dated
    book is `locked` and the perpetual `open`, as on 2026-11-06, until a test opens one."""

    def __init__(self, test):
        self.state = {i["code"]: ("open" if i["kind"] == "perpetual" else "locked") for i in listing()}
        self.tickers = []
        volume = {D2: 3, D3: 2, W10: 1, W21: 5}

        def gateway(path, query):
            if path == "/v1/health":
                return {"adapter": "deribit", "venue_connected": True, "trade_mode": "demo", "account_login": "t"}
            if path == "/v1/instruments":
                return listing()
            if path == "/v1/bars":
                now = int(time.time() * 1000)
                return {"bars": [{"start": now - (i + 1) * 60_000, "close": "80000", "volume": "1"}
                                 for i in range(volume.get(query["symbol"], 0))]}
            raise AssertionError(path)

        def deribit(path, query):
            now = int(time.time() * 1000)
            if path.endswith("/public/ticker"):
                name = query["instrument_name"]
                self.tickers.append(name)
                return {"result": {"instrument_name": name, "state": self.state[name], "timestamp": now}}
            if path.endswith("/public/get_book_summary_by_currency"):
                return {"result": [{"instrument_name": i["code"], "mark_iv": 50.0, "underlying_price": 80000.0}
                                   for i in listing() if i["kind"] == "option" and i["currency"] == query["currency"]]}
            raise AssertionError(path)

        for answer in (gateway, deribit):
            server, url = serve(answer)
            test.addCleanup(server.server_close); test.addCleanup(server.shutdown)
            if answer is gateway:
                self.gateway_url = url
            else:
                self.deribit_url = url + "/api/v2"

    def open(self, *codes):
        for code in codes:
            self.state[code] = "open"


def lane():
    spec = importlib.util.spec_from_file_location("lane_1682", RUNNER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_of(test, venue, case_id, out=None):
    """The runner's own Run for a shipped case, as main() builds it, short of touching an account."""
    folder = os.path.join(CASES, case_id)
    import yaml
    with open(os.path.join(folder, "case.yaml")) as file:
        case = yaml.safe_load(file)
    case["lane"] = "derivatives"
    run = object.__new__(lane().Run)
    run.args = types.SimpleNamespace(gateway_url=venue.gateway_url, case=folder, expected_login="t")
    run.case, run.key, run.out = case, "token", out or tempfile.mkdtemp()
    test.addCleanup(shutil.rmtree, run.out, True)
    with mock.patch.dict(os.environ, {"QKT_ATTEST_DERIBIT_URL": venue.deribit_url}):
        return run, run.venue_probes()


class ResolvesWhatTheCaseTrades(unittest.TestCase):
    def test_a_front_stream_waits_on_the_roll_s_current_and_next_contract(self):
        venue = Venue(self)
        _, probes = run_of(self, venue, "continuous-roll-carries")
        sample = probes[0].sample()
        self.assertFalse(sample["available"], sample)
        self.assertIn("locked", sample["reason"])
        self.assertTrue({D2, D3} <= set(probes[0].codes), probes[0].codes)
        self.assertNotIn(D1, probes[0].codes)  # no bars: the runner's roll never trades it
        venue.open(D2, D3)
        self.assertTrue(probes[0].sample()["available"], probes[0].codes)

    def test_a_dated_case_waits_on_the_contract_the_runner_picks(self):
        venue = Venue(self)
        run, probes = run_of(self, venue, "dated-future-bracket-timed-exit")
        venue.open(W10)
        sample = probes[0].sample()
        self.assertFalse(sample["available"], sample)
        self.assertEqual(sample["reason"], f"Deribit {W21} is locked")
        venue.open(W21)
        self.assertTrue(probes[0].sample()["available"])
        self.assertEqual(run.dated_contract("DERIBIT:BTC_USDC"), "DERIBIT:BTC_USDC_W21")

    def test_an_option_structure_waits_on_the_legs_the_daemon_would_select(self):
        venue = Venue(self)
        _, probes = run_of(self, venue, "option-put-spread-open-close")
        sample = probes[0].sample()
        self.assertFalse(sample["available"], sample)
        self.assertEqual(set(probes[0].codes), {SHORT_PUT, LONG_PUT})
        venue.open(SHORT_PUT, LONG_PUT)  # every other option stays locked: the legs are all that matter
        self.assertTrue(probes[0].sample()["available"])

    def test_the_wait_ends_once_the_dated_books_reopen_and_keeps_the_evidence(self):
        venue = Venue(self)
        _, probes = run_of(self, venue, "continuous-roll-carries")
        threading.Timer(0.5, lambda: venue.open(D2, D3)).start()
        waits = venue_health.await_available(probes, max_s=30, poll_s=0.2)
        self.assertEqual(len(waits), 1)
        self.assertRegex(waits[0]["reason"], r"Deribit BTC_USDC-D[23] is locked")
        self.assertEqual(waits[0]["firstSample"]["instruments"][waits[0]["reason"].split()[1]]["state"], "locked")

    def test_the_attempt_samples_the_resolved_books(self):
        venue = Venue(self)
        _, probes = run_of(self, venue, "dated-future-bracket-timed-exit")
        watch = venue_health.Watch(probes, every_s=60)
        watch.look()
        self.assertEqual(watch.outage()["reason"], f"Deribit {W21} is locked")


class ClosedBookRefusalIsEvidence(unittest.TestCase):
    def ended(self, log, problems, open_codes=()):
        venue = Venue(self)
        venue.open(*open_codes)
        run, probes = run_of(self, venue, "dated-future-bracket-timed-exit")
        with open(os.path.join(run.out, "daemon.log"), "w") as file:
            file.write(log)
        run.watch = venue_health.Watch(probes, every_s=60)
        run.end_watch(problems)
        return run.venue_record()

    def test_a_refusal_naming_a_closed_book_is_recorded_with_the_state_sample(self):
        # The book had reopened by the time the attempt failed: the venue's refusal is the evidence.
        record = self.ended(REFUSALS, ["expected 2 fills within the budget, saw 0"], open_codes=(W10, W21))
        outage = record["venueOutage"]
        self.assertGreater(outage["unavailableSamples"], 0)
        refused = [s for s in outage["unavailable"] if s.get("refusals")]
        self.assertEqual(len(refused), 1)
        self.assertEqual(len(refused[0]["refusals"]), 2)  # each order once, the routing echo aside
        self.assertIn("11030", refused[0]["reason"])
        self.assertIn("orderbook_disabled", refused[0]["reason"])
        self.assertEqual(refused[0]["stateSample"]["instruments"][W21]["state"], "open")

    def test_a_healthy_venue_with_zero_fills_records_no_outage(self):
        record = self.ended(HEALTHY_LOG, ["expected 2 fills within the budget, saw 0"], open_codes=(W10, W21))
        self.assertNotIn("venueOutage", record)


def refused_outage():
    return {"samplesTaken": 3, "unavailableSamples": 1, "venues": ["derivatives"], "from": "2026-11-06T03:16:00Z",
            "to": "2026-11-06T03:16:00Z", "reason": "venue refused 2 order(s): the book is closed (11030 orderbook_disabled)",
            "closedBookRefusals": 2,
            "unavailable": [{"time": 1, "available": False, "venue": "derivatives", "refusals": ["..."],
                             "reason": "venue refused 2 order(s): the book is closed (11030 orderbook_disabled)"}]}


class Ledger(unittest.TestCase):
    def setUp(self):
        self.out = tempfile.mkdtemp()
        self.addCleanup(lambda: subprocess.run(["rm", "-rf", self.out]))

    def record(self, name, problems, **evidence):
        path = os.path.join(self.out, name)
        os.makedirs(path)
        json.dump({"status": "failed", "problems": problems, **evidence}, open(os.path.join(path, "result.json"), "w"))
        args = type("A", (), dict(out=self.out, name=name, dir=path, log="/dev/null", exit=1,
                                  started="2026-11-06T03:00:00Z", no_retry=False, require_result=True,
                                  session_closed=False))
        attest_cases.record(args)
        return attest_cases.attempts_of(self.out, name)[-1]

    def test_the_release_s_seven_attempts_rerun_as_venue_outage_with_the_refusal_recorded(self):
        for case, problems in RELEASE_PROBLEMS.items():
            entry = self.record(f"derivatives-{case}", problems, fills=[], venueOutage=refused_outage())
            self.assertEqual(entry["class"], c.VENUE_OUTAGE, (case, entry))

    def test_without_recorded_evidence_they_stay_real_failures(self):
        for case, problems in RELEASE_PROBLEMS.items():
            self.assertEqual(self.record(f"derivatives-{case}", problems, fills=[])["class"], c.BEHAVIOUR, case)

    def test_the_refusal_s_text_alone_never_makes_an_outage(self):
        problems = ["expected 2 fills within the budget, saw 0", "runner error: deribit error 11030: orderbook_disabled"]
        self.assertNotIn(self.record("derivatives-x", problems, fills=[])["class"], (c.VENUE_OUTAGE,))

    def test_a_case_that_traded_and_got_a_number_wrong_stays_a_real_failure(self):
        problems = ["expected 2 fills within the budget, saw 1", "deals-net-equals-realized: venue net 1 != qkt realized 2"]
        entry = self.record("derivatives-x", problems, fills=[{"side": "BUY"}], venueOutage=refused_outage())
        self.assertEqual(entry["class"], c.BEHAVIOUR)

    def test_follow_on_problems_are_excused_only_when_nothing_traded(self):
        problems = RELEASE_PROBLEMS["continuous-roll-carries"]
        entry = self.record("derivatives-x", problems, fills=[{"side": "BUY"}], venueOutage=refused_outage())
        self.assertEqual(entry["class"], c.BEHAVIOUR)

    def test_a_forbidden_log_line_stays_a_real_failure_even_with_nothing_traded(self):
        problems = ["expected 2 fills within the budget, saw 0", "reject_log: the daemon log matched /not carried for x/"]
        entry = self.record("derivatives-x", problems, fills=[], venueOutage=refused_outage())
        self.assertEqual(entry["class"], c.BEHAVIOUR)


if __name__ == "__main__":
    unittest.main()
