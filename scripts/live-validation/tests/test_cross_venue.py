"""The cross lane's rules (lib/cross_venue.py), offline: one daemon on an MT5 account and a gateway account, judged
per venue.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import os, subprocess, sys, unittest
from decimal import Decimal

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import cross_venue as cv  # noqa: E402

CASES = os.path.join(ROOT, "attestation/cases/cross")
LOG = """\
17:31:00.887 [qkt-live-engine] INFO  [atto_xpair] com.qkt.app.OrderManager - order filled order_id=ORD-atto_xpair-0 \
strategy_id=atto_xpair symbol=EXNESS:BTCUSD side=BUY qty=0.01 price=80515.92
17:31:00.990 [qkt-live-engine] INFO  [atto_xpair] com.qkt.app.OrderManager - order filled order_id=ORD-atto_xpair-1 \
strategy_id=atto_xpair symbol=PAPER:BTC_USDC_PERPETUAL side=SELL qty=0.001 price=80540.5
17:32:00.410 [qkt-live-engine] INFO  [atto_xpair] com.qkt.app.OrderManager - order filled order_id=dsl-atto_xpair--0 \
strategy_id=atto_xpair symbol=EXNESS:BTCUSD side=SELL qty=0.01 price=80520.11
"""


def fill(venue, side, qty, ts, symbol=None):
    symbol = symbol or {"EXNESS": "EXNESS:BTCUSD", "PAPER": "PAPER:BTC_USDC_PERPETUAL"}[venue]
    return {"ts": ts, "symbol": symbol, "side": side, "qty": qty, "price": "80000", "venue": venue}


def load(path):
    with open(path) as file:
        return yaml.safe_load(file)


def case(**over):
    base = load(os.path.join(CASES, "two-venue-pair/case.yaml"))
    base.update(over)
    return base


class CaseShape(unittest.TestCase):
    def test_every_cross_case_in_the_catalog_is_sound(self):
        for cid in sorted(os.listdir(CASES)):
            with self.subTest(cid):
                self.assertEqual(cv.check_venues(load(os.path.join(CASES, cid, "case.yaml"))), [])

    def test_a_symbol_on_an_undeclared_venue_is_refused(self):
        problems = cv.check_venues(case(symbols=["EXNESS:BTCUSD", "BYBIT_LINEAR:BTCUSDT"]))
        self.assertTrue(any("names no declared venue" in p for p in problems), problems)

    def test_two_mt5_venues_are_refused(self):
        venues = [{"label": "EXNESS", "type": "mt5"}, {"label": "EXNESS_B", "type": "mt5"}]
        problems = cv.check_venues(case(venues=venues, fills={"EXNESS": 1, "EXNESS_B": 1}))
        self.assertTrue(any("at most one MT5" in p for p in problems))
        self.assertTrue(any("at least one gateway" in p for p in problems))

    def test_fills_must_name_every_venue(self):
        self.assertTrue(cv.check_venues(case(fills={"EXNESS": 2})))
        self.assertTrue(cv.check_venues(case(fills=2)))

    def test_a_drill_must_aim_at_a_gateway_venue(self):
        problems = cv.check_venues(case(drills=[{"venue": "EXNESS", "kind": "kill_switch", "at_s": 5}]))
        self.assertTrue(any("must name one of the gateway venues" in p for p in problems))


class DaemonConfig(unittest.TestCase):
    VENUES = [{"label": "EXNESS", "type": "mt5", "url": "http://127.0.0.1:5001", "login": "436804390",
               "server": "Exness-MT5Trial9", "magic": 929010},
              {"label": "PAPER", "type": "gateway", "url": "http://127.0.0.1:8460", "login": "paper",
               "adapter": "paper", "key_env": "QKT_CROSS_PAPER_KEY"}]

    def test_one_config_holds_a_broker_per_venue_named_by_its_prefix(self):
        config = cv.daemon_config(self.VENUES, "/out", {"max_order_qty": "0.05"})
        self.assertEqual(sorted(config["brokers"]), ["exness", "paper"])
        mt5, gateway = config["brokers"]["exness"], config["brokers"]["paper"]
        self.assertEqual((mt5["type"], mt5["magic"], mt5["expected_account_login"], mt5["expected_trade_mode"]),
                         ("mt5", 929010, 436804390, "demo"))
        self.assertEqual((gateway["type"], gateway["api_key"], gateway["expected_adapter"],
                          gateway["expected_account_login"]), ("gateway", "env:QKT_CROSS_PAPER_KEY", "paper", "paper"))

    def test_a_case_config_merges_over_it(self):
        config = cv.daemon_config(self.VENUES, "/out", {"max_order_qty": "0.05"},
                                  {"risk": {"max_order_qty": "1"}, "brokers": {"paper": {"chain_snapshot_seconds": 30}}})
        self.assertEqual(config["risk"]["max_order_qty"], "1")
        self.assertEqual(config["brokers"]["paper"]["chain_snapshot_seconds"], 30)
        self.assertEqual(config["brokers"]["paper"]["expected_adapter"], "paper")


class Fills(unittest.TestCase):
    def test_log_lines_split_by_the_venue_they_filled_on(self):
        grouped = cv.by_venue(cv.parse_fills(LOG), ["EXNESS", "PAPER"])
        self.assertEqual([f["side"] for f in grouped["EXNESS"]], ["BUY", "SELL"])
        self.assertEqual([(f["side"], f["qty"]) for f in grouped["PAPER"]], [("SELL", "0.001")])

    def test_a_venue_short_of_its_fills_fails_by_name(self):
        grouped = cv.by_venue(cv.parse_fills(LOG), ["EXNESS", "PAPER"])
        self.assertEqual(cv.judge_fill_counts(grouped, {"EXNESS": 2, "PAPER": 2}),
                         ["fills-by-venue: PAPER expected 2 fill(s), saw 1"])

    def test_a_fill_on_a_venue_the_case_never_named_fails(self):
        grouped = cv.by_venue([fill("EXNESS", "BUY", "0.01", 1, symbol="BYBIT:BTCUSDT") | {"venue": "BYBIT"}],
                              ["EXNESS", "PAPER"])
        self.assertTrue(any("never declared" in p for p in cv.judge_fill_counts(grouped, {"EXNESS": 0, "PAPER": 0})))

    def test_the_journal_gives_each_fill_its_time(self):
        lines = ['{"ts":5,"kind":"submit","id":"ORD-a-0"}',
                 '{"ts":6,"kind":"filled","id":"ORD-a-0","symbol":"PAPER:BTC_USDC_PERPETUAL","side":"SELL",'
                 '"price":"1","qty":"0.001","venueCosts":"0"}', ""]
        self.assertEqual([(f["ts"], f["venue"]) for f in cv.journal_fills(lines)], [(6, "PAPER")])


class Flat(unittest.TestCase):
    VENUES = [{"label": "EXNESS", "type": "mt5"}, {"label": "PAPER", "type": "gateway"}]

    def test_each_venue_is_judged_flat_by_its_own_rule(self):
        self.assertEqual(cv.judge_flat({"EXNESS": True, "PAPER": True}, self.VENUES), [])
        self.assertTrue(cv.judge_flat({"EXNESS": False, "PAPER": True}, self.VENUES)[0].startswith("flat-by-magic"))
        self.assertTrue(cv.judge_flat({"EXNESS": True, "PAPER": False}, self.VENUES)[0].startswith("flat-by-strategy"))


class DealsNet(unittest.TestCase):
    OUTCOMES = [{"pnl": "-0.04", "symbol": "PAPER:BTC_USDC_PERPETUAL"}, {"pnl": "0.07", "symbol": "EXNESS:BTCUSD"},
                {"pnl": "-0.0401", "symbol": "PAPER:BTC_USDC_PERPETUAL"}]

    def test_realized_splits_by_venue(self):
        self.assertEqual(cv.realized_by_venue(self.OUTCOMES, ["EXNESS", "PAPER"]),
                         {"EXNESS": Decimal("0.07"), "PAPER": Decimal("-0.0801")})

    def test_mt5_agrees_to_a_cent_a_closing_deal(self):
        self.assertEqual(cv.judge_deals_net("EXNESS", "mt5", "0.074", "0.07", 1, 2), [])
        self.assertTrue(cv.judge_deals_net("EXNESS", "mt5", "0.09", "0.07", 1, 2))

    def test_a_gateway_agrees_to_one_money_unit_a_fill(self):
        self.assertEqual(cv.judge_deals_net("PAPER", "gateway", "-0.08010001", "-0.0801", 0, 2), [])
        self.assertTrue(cv.judge_deals_net("PAPER", "gateway", "-0.0802", "-0.0801", 0, 2)[0]
                        .startswith("deals-net-by-venue: PAPER"))

    def test_a_venue_off_while_the_total_agrees_still_fails_that_venue(self):
        # +0.01 on one venue and -0.01 on the other cancel in the total; the per-venue rule still sees both.
        problems = cv.judge_deals_net("EXNESS", "mt5", "0.09", "0.07", 1, 2) + \
            cv.judge_deals_net("PAPER", "gateway", "-0.1001", "-0.0801", 0, 2)
        self.assertEqual(len(problems), 2)
        self.assertEqual(cv.judge_total(Decimal("-0.0101"), {"EXNESS": "0.09", "PAPER": "-0.1001"},
                                        {"EXNESS": "0.07", "PAPER": "-0.0801"}, Decimal(0), Decimal("0.01")), [])

    def test_the_total_holds_the_funding_the_gateways_charged(self):
        executions = {"EXNESS": Decimal("0.07"), "PAPER": Decimal("-0.0801")}
        nets = {"EXNESS": "0.07", "PAPER": "-0.0801"}
        booked = Decimal("-0.0101") - Decimal("0.0003")  # executions plus the funding qkt booked
        self.assertEqual(cv.judge_total(booked, executions, nets, Decimal("0.0003"), Decimal("0.01")), [])
        unbooked = cv.judge_total(Decimal("-0.0101"), executions, nets, Decimal("0.0003"), Decimal("0.0001"))
        self.assertTrue(any("outside executions" in p for p in unbooked), unbooked)


class DuringDrill(unittest.TestCase):
    KILL = {"kind": "kill_switch", "timeline": [{"event": "fired", "ms": 90}, {"event": "kill engaged", "ms": 100},
                                                 {"event": "kill released", "ms": 300}]}
    OUTAGE = {"kind": "gateway_outage", "timeline": [{"event": "outage begins", "ms": 100},
                                                      {"event": "outage ends", "ms": 300}]}

    def opened(self):
        return [fill("EXNESS", "BUY", "0.01", 10), fill("PAPER", "SELL", "0.001", 11)]

    def test_the_other_venue_adding_risk_and_the_killed_venue_reducing_pass(self):
        fills = self.opened() + [fill("EXNESS", "SELL", "0.01", 150), fill("EXNESS", "BUY", "0.01", 200),
                                 fill("PAPER", "BUY", "0.001", 250)]
        self.assertEqual(cv.judge_during(self.KILL, fills, "PAPER"), [])

    def test_a_close_alone_on_the_other_venue_proves_no_risk_passed(self):
        fills = self.opened() + [fill("EXNESS", "SELL", "0.01", 150), fill("PAPER", "BUY", "0.001", 250)]
        problems = cv.judge_during(self.KILL, fills, "PAPER")
        self.assertEqual(problems, ["other-venue-trades-during-kill-switch: EXNESS made no risk-adding fill while "
                                    "PAPER was down"])

    def test_a_killed_venue_that_never_reduced_fails(self):
        fills = self.opened() + [fill("EXNESS", "SELL", "0.01", 150), fill("EXNESS", "BUY", "0.01", 200),
                                 fill("PAPER", "BUY", "0.001", 400)]
        self.assertTrue(cv.judge_during(self.KILL, fills, "PAPER")[0].startswith("reducing-passes-during-kill"))

    def test_an_outage_needs_only_the_other_venue_trading(self):
        fills = self.opened() + [fill("EXNESS", "SELL", "0.01", 150), fill("EXNESS", "BUY", "0.01", 200),
                                 fill("PAPER", "BUY", "0.001", 400)]
        self.assertEqual(cv.judge_during(self.OUTAGE, fills, "PAPER"), [])

    def test_a_drill_that_never_held_the_venue_down_judges_nothing(self):
        self.assertIn("never held", cv.judge_during({"kind": "kill_switch", "timeline": []}, self.opened(), "PAPER")[0])

    def test_a_flip_adds_risk(self):
        marked = cv.with_risk([fill("EXNESS", "BUY", "0.01", 1), fill("EXNESS", "SELL", "0.02", 2),
                               fill("EXNESS", "BUY", "0.01", 3)])
        self.assertEqual([f["adds"] for f in marked], [True, True, False])


class Replay(unittest.TestCase):
    def test_the_same_fills_on_each_venue_pass_and_record_the_price_drift(self):
        live = cv.parse_fills(LOG)
        replayed = [dict(f, qty=f["qty"] + "00", price=str(Decimal(f["price"]) + 1)) for f in live]
        problems, drift = cv.judge_replay_by_venue(live, replayed, ["EXNESS", "PAPER"])
        self.assertEqual(problems, [])
        self.assertEqual({k: [Decimal(d) for d in v] for k, v in drift.items()}, {"EXNESS": [1, 1], "PAPER": [1]})

    def test_a_venue_whose_replay_differs_fails_alone(self):
        live = cv.parse_fills(LOG)
        replayed = [f for f in live if not (f["venue"] == "PAPER")]
        problems, _ = cv.judge_replay_by_venue(live, replayed, ["EXNESS", "PAPER"])
        self.assertEqual(len(problems), 1)
        self.assertTrue(problems[0].startswith("replay-same-fills-by-venue: PAPER"))

    def test_fills_interleaved_differently_across_venues_still_match_per_venue(self):
        live = cv.parse_fills(LOG)
        replayed = [live[1], live[0], live[2]]
        self.assertEqual(cv.judge_replay_by_venue(live, replayed, ["EXNESS", "PAPER"])[0], [])


class Runner(unittest.TestCase):
    def test_a_gateway_venue_without_its_flag_is_refused_before_anything_runs(self):
        env = dict(os.environ, QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", QKT_BROKER_API_KEY="k",
                   QKT_CROSS_PAPER_KEY="t")
        done = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/live-validation/run-cross-lane-case.py"),
                               "--case", os.path.join(CASES, "two-venue-pair"), "--out", "/nonexistent/cross-out",
                               "--gateway-url", "http://127.0.0.1:5001", "--expected-login", "1",
                               "--expected-server", "S", "--magic", "929010", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01"],
                              env=env, capture_output=True, text=True, timeout=60)
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("needs --venue PAPER=URL@LOGIN", done.stderr)

    def test_a_venue_off_loopback_is_refused(self):
        env = dict(os.environ, QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", QKT_BROKER_API_KEY="k",
                   QKT_CROSS_PAPER_KEY="t")
        done = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/live-validation/run-cross-lane-case.py"),
                               "--case", os.path.join(CASES, "two-venue-pair"), "--out", "/nonexistent/cross-out",
                               "--gateway-url", "http://127.0.0.1:5001", "--expected-login", "1",
                               "--expected-server", "S", "--magic", "929010", "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01",
                               "--venue", "PAPER=http://10.0.0.5:8460@paper"],
                              env=env, capture_output=True, text=True, timeout=60)
        self.assertIn("only a loopback gateway is allowed", done.stderr)


if __name__ == "__main__":
    unittest.main()
