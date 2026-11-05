"""A gateway case runs inside one UTC day (run-derivatives-lane-case.py, run-cross-lane-case.py).

The catalog's strategies re-arm on `TRADES.today = 0`, which counts 0 again from 00:00 UTC. derivatives/
perpetual-drill-kill-switch started at 23:55:07 on 2026-11-03, closed its trade at 00:02:01, and while the runner
awaited the funding reconcile with the daemon still running, bought again at the 00:03 bar: qkt fills BUY, SELL, BUY,
the case ended holding (flat-by-strategy) and the venue net counted the open buy's cash (deals-net-equals-realized).
The judging was right; the case must not start where its run crosses the day."""
import datetime, importlib.util, os, tempfile, types, unittest
from decimal import Decimal
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gateway_lane = load("gateway_lane_day", "../run-derivatives-lane-case.py")
cross_lane = load("cross_lane_day", "../run-cross-lane-case.py")

STRATEGY = """STRATEGY atto_drill_kill VERSION 1

SYMBOLS
    btc = DERIBIT:BTC_USDC_PERPETUAL EVERY 1m WARMUP 3 BARS

RULES
    WHEN btc.close > 0 AND POSITION.btc = 0 AND OPEN_ORDERS.btc = 0 AND TRADES.today = 0
    THEN BUY btc SIZING 0.0001 BRACKET { STOP LOSS BY 3000, TAKE PROFIT BY 6000 }

    WHEN POSITION.btc != 0 AND POSITION.btc.holding_duration >= 300
    THEN CLOSE btc
"""


def ms(text):
    return int(datetime.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp() * 1000)


RUN_S = 600 + gateway_lane.RUN_TAIL_S  # the catalog's ten-minute budget and the run's tail


class LiveEnvironment(unittest.TestCase):
    def setUp(self):  # the offline suites switch the wait off; these tests are about the wait itself
        patch = mock.patch.dict(os.environ)
        patch.start()
        self.addCleanup(patch.stop)
        os.environ.pop("QKT_ATTEST_UTC_DAY_WAIT", None)


class UtcDayWaitTest(LiveEnvironment):
    def test_the_failed_rehearsal_start_waits_for_the_new_day(self):
        wait = gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T23:55:07.787Z"), RUN_S)
        self.assertEqual(ms("2026-11-03T23:55:07.787Z") + wait, ms("2026-11-04T00:00:10Z"))

    def test_a_run_that_ends_before_the_day_does_not_wait(self):
        self.assertEqual(gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T19:36:07Z"), RUN_S), 0)
        self.assertEqual(gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T23:44:59Z"), RUN_S), 0)

    def test_a_run_that_would_end_past_the_day_waits(self):
        self.assertGreater(gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T23:45:00Z"), RUN_S), 0)

    def test_a_strategy_that_does_not_count_todays_trades_never_waits(self):
        text = STRATEGY.replace(" AND TRADES.today = 0", "")
        self.assertEqual(gateway_lane.utc_day_wait_ms(text, ms("2026-11-03T23:55:07Z"), RUN_S), 0)

    def test_the_offline_suites_switch_it_off(self):
        os.environ["QKT_ATTEST_UTC_DAY_WAIT"] = "off"
        self.assertEqual(gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T23:55:07Z"), RUN_S), 0)

    def test_a_soak_is_not_held_for_the_day(self):
        self.assertEqual(gateway_lane.utc_day_wait_ms(STRATEGY, ms("2026-11-03T23:55:07Z"), 8 * 3600), 0)


class Clock:
    def __init__(self, start_ms):
        self.now, self.slept = start_ms / 1000, []

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.now += seconds


class Reached(Exception):
    pass


def case_dir(test):
    folder = tempfile.mkdtemp()
    test.addCleanup(lambda: __import__("shutil").rmtree(folder, ignore_errors=True))
    open(os.path.join(folder, "strategy.qkt"), "w").write(STRATEGY)
    return folder


class RunnerWaitsBeforeItStartsTest(LiveEnvironment):
    def test_the_derivatives_runner_waits_before_it_touches_the_account(self):
        run = object.__new__(gateway_lane.Run)
        run.args, run.case = types.SimpleNamespace(case=case_dir(self)), {"id": "perpetual-drill-kill-switch"}
        clock, seen = Clock(ms("2026-11-03T23:55:07.787Z")), []

        def get(path, *rest, **kw):
            seen.append((path, clock.now))
            raise Reached()

        run.get = get
        run.venue_probes, run.await_venue = (lambda: []), (lambda probes: None)  # the venue is up (test_venue_outages)
        with mock.patch("time.time", clock.time), mock.patch("time.sleep", clock.sleep), \
                mock.patch("sys.stderr", new=open(os.devnull, "w")):
            with self.assertRaises(Reached):
                run.execute(600)
        self.assertEqual(seen, [("/v1/health", ms("2026-11-04T00:00:10Z") / 1000)])

    def test_the_cross_runner_waits_before_its_preflight(self):
        run = object.__new__(cross_lane.CrossRun)
        run.args, run.case = types.SimpleNamespace(case=case_dir(self)), {"id": "kill-switch-one-venue"}
        clock, seen = Clock(ms("2026-11-03T23:55:07.787Z")), []

        def preflight():
            seen.append(clock.now)
            raise Reached()

        run.preflight = preflight
        run.await_venues = lambda text: None  # the venues are up (test_venue_outages)
        with mock.patch("time.time", clock.time), mock.patch("time.sleep", clock.sleep), \
                mock.patch("sys.stderr", new=open(os.devnull, "w")):
            with self.assertRaises(Reached):
                run.execute(600)
        self.assertEqual(seen, [ms("2026-11-04T00:00:10Z") / 1000])


class TheJudgingWasRightTest(unittest.TestCase):
    """The failed rehearsal's deals, as the gateway still reports them: the case's window held all three, and the
    re-entry left the strategy long when the case ended, so both problems it reported were true (the runner's sweep
    closed it at 00:04:03, after the verdict)."""
    DEALS = [
        {"client_order_id": "dsl-atto_drill_kill--0.mw1cj5ji", "side": "buy", "quantity": "0.0001", "price": "77573.0",
         "symbol": "BTC_USDC-PERPETUAL", "time": 1793750160191, "costs": []},
        {"client_order_id": "dsl-atto_drill_kill--2.mw1cqvbx", "side": "sell", "quantity": "0.0001", "price": "77353.0",
         "symbol": "BTC_USDC-PERPETUAL", "time": 1793750521211, "costs": []},
        {"client_order_id": "dsl-atto_drill_kill--3.mw1cs5mn", "side": "buy", "quantity": "0.0001", "price": "77436.0",
         "symbol": "BTC_USDC-PERPETUAL", "time": 1793750580191, "costs": []},
    ]

    def test_the_case_window_holds_every_deal_of_the_case_and_it_ended_long(self):
        run = object.__new__(gateway_lane.Run)
        run.strategy, run.roll = "atto_drill_kill", None
        run.started_ms, run.ended_ms = ms("2026-11-03T23:55:08.900Z"), ms("2026-11-04T00:03:41Z")
        run.get = lambda path, *a, **k: {"deals": self.DEALS} if path.startswith("/v1/deals") else \
            {"settlements": []} if path.startswith("/v1/settlements") else {"orders": []}
        with mock.patch("time.time", lambda: ms("2026-11-04T00:03:42Z") / 1000):
            self.assertEqual(len(run.strategy_deals()), 3)
            self.assertEqual(run.holdings(), {"BTC_USDC-PERPETUAL": Decimal("0.0001")})


if __name__ == "__main__":
    unittest.main()
