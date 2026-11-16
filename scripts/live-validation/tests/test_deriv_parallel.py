"""The derivatives lane's parallel groups (lib/deriv_parallel.py, lib/claim_lock.py): which cases may share the one Deribit
testnet account at a time, which gateway each runs on, and what keeps two of them off one instrument.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, subprocess, sys, tempfile, threading, time, unittest
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import claim_lock  # noqa: E402
import deriv_parallel as dp  # noqa: E402
import strategy_scope as scope  # noqa: E402

CASES = os.path.join(ROOT, "attestation/cases/derivatives")
CROSS = os.path.join(ROOT, "attestation/cases/cross")
BASE, K1, K2 = "http://127.0.0.1:18444", "http://127.0.0.1:18454", "http://127.0.0.1:18455"


def shipped():
    return dp.ready_cases(CASES)


class Claims(unittest.TestCase):
    def test_each_kind_of_instrument_is_its_own_class(self):
        self.assertEqual(dp.claim_of_symbol("DERIBIT:BTC_USDC@front"), "DERIBIT:BTC_USDC:front")
        self.assertEqual(dp.claim_of_symbol("DERIBIT:ETH_USDC_PERPETUAL"), "DERIBIT:ETH_USDC:perpetual")
        self.assertEqual(dp.claim_of_symbol("DERIBIT:BTC_USDC_27NOV26"), "DERIBIT:BTC_USDC:dated")
        self.assertEqual(dp.claim_of_symbol("DERIBIT:BTC_USDC_6OCT26_83000_C"), "DERIBIT:BTC_USDC:options")
        self.assertEqual(dp.claim_of_symbol("OPTIONS:DERIBIT.BTC_USDC"), "DERIBIT:BTC_USDC:options")
        self.assertEqual(dp.claim_of_symbol("CHAIN:DERIBIT.BTC_USDC.atm_iv.30d"), "DERIBIT:BTC_USDC:options")

    def test_a_dated_case_claims_its_root_s_dated_contracts_whatever_literal_it_carries(self):
        case = yaml.safe_load(open(os.path.join(CASES, "dated-future-bracket-timed-exit/case.yaml")))
        self.assertEqual(dp.claims_of(case), {"DERIBIT:BTC_USDC:dated"})

    def test_a_roll_case_claims_the_front_of_its_root(self):
        case = yaml.safe_load(open(os.path.join(CASES, "continuous-roll-restored-moves-collar/case.yaml")))
        self.assertEqual(dp.claims_of(case), {"DERIBIT:ETH_USDC:front"})

    def test_a_cross_case_claims_only_its_deribit_leg(self):
        case = yaml.safe_load(open(os.path.join(CROSS, "same-symbol-two-venues/case.yaml")))
        self.assertEqual(dp.claims_of(case), {"DERIBIT:BTC_USDC:perpetual"})
        case = yaml.safe_load(open(os.path.join(CROSS, "two-venue-pair/case.yaml")))
        self.assertEqual(dp.claims_of(case), set())

    def test_the_claims_command_prints_arguments_for_the_lock(self):
        out = subprocess.run([sys.executable, os.path.join(HERE, "../lib/deriv_parallel.py"), "claims", "--case",
                              os.path.join(CASES, "perpetual-drill-kill-switch")], capture_output=True, text=True)
        self.assertEqual(out.stdout.split(), ["--claim", "DERIBIT:ETH_USDC:perpetual"])


class ShippedCatalog(unittest.TestCase):
    def test_it_is_a_sound_grouping(self):
        self.assertEqual(dp.problems(shipped()), [])

    def test_no_two_groups_share_an_instrument_class(self):
        held = {}
        for cid, doc in shipped():
            for claim in dp.claims_of(doc):
                self.assertEqual(held.setdefault(claim, doc["group"]), doc["group"], f"{cid}: {claim}")

    def test_every_ready_case_is_in_exactly_one_group_of_the_plan(self):
        entries = dp.plan(CASES, BASE, [K1, K2])
        self.assertEqual(sorted(cid for _, cid, _ in entries), sorted(cid for cid, _ in shipped()))
        self.assertGreaterEqual(len({g for g, _, _ in entries}), 4)

    def test_each_kill_switch_case_has_a_gateway_of_its_own_and_nothing_else_does(self):
        entries = dp.plan(CASES, BASE, [K1, K2])
        cases = dict(shipped())
        own = {cid: url for _, cid, url in entries if url != BASE}
        self.assertEqual(set(own), {cid for cid, doc in cases.items() if dp.kill_switch(doc)})
        self.assertEqual(len(own), 2)
        self.assertEqual(sorted(own.values()), [K1, K2])
        self.assertEqual({cid for _, cid, url in entries if url == BASE} & set(own), set())

    def test_an_outage_or_restart_drill_shares_the_base_gateway(self):
        entries = {cid: url for _, cid, url in dp.plan(CASES, BASE, [K1, K2])}
        for cid in ("perpetual-drill-gateway-outage", "continuous-roll-restored-moves-collar",
                    "perpetual-drill-qkt-restart", "perpetual-drill-flat-restart"):
            self.assertEqual(entries[cid], BASE, cid)

    def test_a_gateway_outage_cuts_the_daemon_s_own_proxy_not_the_gateway(self):
        """The reason gateway_outage cases need no gateway of their own."""
        import derivatives_drills
        source = open(derivatives_drills.__file__).read()
        self.assertIn("proxy.down()", source)
        self.assertNotIn("/v1/kill", source.split("def gateway_outage")[1].split("def links")[0])

    def test_the_heaviest_group_starts_first(self):
        groups = []
        for group, _, _ in dp.plan(CASES, BASE, [K1, K2]):
            if group not in groups:
                groups.append(group)
        self.assertEqual(groups[0], "btc-perp")
        self.assertEqual(groups[-1], "btc-options")

    def test_a_resumed_run_plans_only_what_is_pending_and_keeps_each_case_on_its_gateway(self):
        full = {cid: url for _, cid, url in dp.plan(CASES, BASE, [K1, K2])}
        kept = ["derivatives-continuous-roll-refused-leg-resumes", "derivatives-perpetual-bracket-timed-exit"]
        entries = {cid: url for _, cid, url in dp.plan(CASES, BASE, [K1, K2], kept)}
        self.assertNotIn("continuous-roll-refused-leg-resumes", entries)
        self.assertNotIn("perpetual-bracket-timed-exit", entries)
        self.assertEqual(entries["perpetual-drill-kill-switch"], full["perpetual-drill-kill-switch"])

    def test_too_few_drill_gateways_is_refused_not_shared(self):
        with self.assertRaisesRegex(dp.PlanError, "2 kill_switch cases .* need a gateway of their own"):
            dp.plan(CASES, BASE, [K1])

    def test_a_drill_gateway_that_is_the_base_gateway_is_refused(self):
        with self.assertRaisesRegex(dp.PlanError, "different processes"):
            dp.plan(CASES, BASE, [BASE, K2])
        with self.assertRaisesRegex(dp.PlanError, "different processes"):
            dp.plan(CASES, BASE, [K1, K1])

    def test_the_cli_prints_the_plan_and_refuses_a_bad_one(self):
        tool = os.path.join(HERE, "../lib/deriv_parallel.py")
        ok = subprocess.run([sys.executable, tool, "plan", "--cases", CASES, "--base-url", BASE, "--drill-urls",
                             f"{K1},{K2}"], capture_output=True, text=True)
        self.assertEqual(ok.returncode, 0, ok.stderr)
        self.assertIn(f"eth-perp\tperpetual-drill-kill-switch\t{K2}", ok.stdout.splitlines())
        bad = subprocess.run([sys.executable, tool, "plan", "--cases", CASES, "--base-url", BASE], capture_output=True,
                             text=True)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("each need a gateway of their own", bad.stderr)


def case(group, symbols, **more):
    return {"status": "ready", "group": group, "symbols": symbols, "assertions": [], **more}


class Problems(unittest.TestCase):
    def test_two_groups_on_one_instrument_class_are_refused(self):
        found = dp.problems([("a", case("g1", ["DERIBIT:BTC_USDC_PERPETUAL"])),
                             ("b", case("g2", ["DERIBIT:BTC_USDC_PERPETUAL"]))])
        self.assertEqual(len(found), 1)
        self.assertIn("claims DERIBIT:BTC_USDC:perpetual", found[0])

    def test_one_group_may_hold_a_class_twice_and_two_groups_may_share_a_root(self):
        self.assertEqual(dp.problems([("a", case("g1", ["DERIBIT:BTC_USDC_PERPETUAL"])),
                                      ("b", case("g1", ["DERIBIT:BTC_USDC_PERPETUAL"])),
                                      ("c", case("g2", ["DERIBIT:BTC_USDC@front"], continuous_roll={"stream": "DERIBIT:BTC_USDC@front"}))]), [])

    def test_a_ready_case_without_a_group_is_refused(self):
        found = dp.problems([("a", {"status": "ready", "symbols": ["DERIBIT:BTC_USDC_PERPETUAL"]})])
        self.assertIn("names its `group:`", found[0])

    def test_a_case_reading_the_whole_account_balance_cannot_run_beside_other_groups(self):
        found = dp.problems([("a", case("g1", ["DERIBIT:BTC_USDC_PERPETUAL"], assertions=["account-balance-equals-realized"])),
                             ("b", case("g2", ["DERIBIT:ETH_USDC_PERPETUAL"]))])
        self.assertTrue(any("account-balance-equals-realized" in p for p in found), found)
        self.assertEqual(dp.problems([("a", case("g1", ["DERIBIT:BTC_USDC_PERPETUAL"],
                                                 assertions=["account-balance-equals-realized"]))]), [])


class Gateways(unittest.TestCase):
    def health(self, **over):
        good = {"account_login": "g-1", "trade_mode": "demo", "venue_connected": True, "kill_switch": {"all": False, "symbols": []}}
        return lambda url, key: {**good, **over.get(url, {})}

    def test_healthy_gateways_on_the_expected_account_pass(self):
        self.assertEqual(dp.check_gateways([K1, K2], "g-1", BASE, "k", self.health()), [])

    def test_a_gateway_on_another_account_with_its_switch_on_or_no_link_is_named(self):
        found = dp.check_gateways([K1, K2], "g-1", BASE, "k", self.health(**{
            K1: {"account_login": "g-2"}, K2: {"kill_switch": {"all": True, "symbols": []}}, BASE: {"venue_connected": False}}))
        self.assertEqual(len(found), 3, found)

    def test_an_unreachable_gateway_is_named(self):
        def fetch(url, key):
            raise OSError("refused")
        self.assertIn("/v1/health failed: refused", dp.check_gateways([K1], "g-1", BASE, "k", fetch)[0])


class MarginFloorStaysIndependent(unittest.TestCase):
    """dated-future-margin-floor-refuses-entry asserts a refusal below a floor and shares the account's margin with every
    group beside it. The refusal needs the floor to bind whatever the level is, and the first entry needs it not to."""

    def test_the_first_entry_floor_never_binds_and_the_restart_floor_always_does(self):
        doc = yaml.safe_load(open(os.path.join(CASES, "dated-future-margin-floor-refuses-entry/case.yaml")))
        first = Decimal(doc["config"]["risk"]["margin_floor_pct"])
        restart = Decimal(doc["drills"][0]["config"]["risk"]["margin_floor_pct"])
        self.assertEqual(first, 0)  # a level is never below zero, however much margin the others use
        self.assertGreaterEqual(restart, Decimal("1e12"))  # more margin used by others only lowers the level further
        self.assertIs(doc["shared_account"], True)  # other daemons' holdings on the account do not refuse its entries
        self.assertIn("venue-fills-equal-qkt-fills", doc["assertions"])  # still judged on its own fills only


class FundingAcrossGroups(unittest.TestCase):
    """Several groups holding different instruments at once: a funding record is charged per symbol, so one case owes
    none of another's."""
    BUY = {"symbol": "ETH_USDC-PERPETUAL", "side": "buy", "quantity": "1", "time": 100}
    SELL = {"symbol": "ETH_USDC-PERPETUAL", "side": "sell", "quantity": "1", "time": 200}

    def test_records_on_other_cases_instruments_are_never_owed(self):
        others = [{"funding_id": "b1", "symbol": "BTC_USDC-PERPETUAL", "amount": "5", "time": 150, "position": "0.3"},
                  {"funding_id": "b2", "symbol": "BTC_USDC-PERPETUAL", "amount": "5", "time": 250, "position": "0"},
                  {"funding_id": "b3", "symbol": "BTC_USDC-27NOV26", "amount": "5", "time": 150}]
        mine = {"funding_id": "e1", "symbol": "ETH_USDC-PERPETUAL", "amount": "2", "time": 150, "position": "4"}
        owed = scope.strategy_funding(others + [mine], [self.BUY, self.SELL])
        self.assertEqual([(f["funding_id"], f["share"]) for f in owed], [("e1", "0.5")])


class ClaimLock(unittest.TestCase):
    TOOL = os.path.join(HERE, "../lib/claim_lock.py")

    def hold(self, directory, claims, *command):
        args = [sys.executable, self.TOOL, "hold", "--dir", directory]
        for claim in claims:
            args += ["--claim", claim]
        return subprocess.Popen([*args, "--", *command], stderr=subprocess.PIPE, text=True)

    def test_the_command_runs_and_its_exit_code_is_returned(self):
        with tempfile.TemporaryDirectory() as directory:
            run = self.hold(directory, ["DERIBIT:BTC_USDC:perpetual"], sys.executable, "-c", "raise SystemExit(7)")
            self.assertEqual(run.wait(30), 7)
            self.assertEqual(self.hold(directory, [], sys.executable, "-c", "pass").wait(30), 0)

    def test_a_second_holder_of_the_same_claim_waits_for_the_first(self):
        with tempfile.TemporaryDirectory() as directory:
            stamp = os.path.join(directory, "order")
            body = "import time; open(%r,'a').write('start\\n'); time.sleep(%s); open(%r,'a').write('end\\n')"
            first = self.hold(directory, ["DERIBIT:BTC_USDC:perpetual"], sys.executable, "-c", body % (stamp, 2, stamp))
            time.sleep(0.7)
            second = self.hold(directory, ["DERIBIT:BTC_USDC:perpetual"], sys.executable, "-c", body % (stamp, 0, stamp))
            self.assertEqual((first.wait(30), second.wait(30)), (0, 0))
            self.assertEqual(open(stamp).read().split(), ["start", "end", "start", "end"])
            self.assertIn("waiting", second.stderr.read())

    def test_other_claims_do_not_wait(self):
        with tempfile.TemporaryDirectory() as directory:
            slow = self.hold(directory, ["DERIBIT:BTC_USDC:perpetual"], sys.executable, "-c", "import time; time.sleep(3)")
            time.sleep(0.5)
            began = time.time()
            quick = self.hold(directory, ["DERIBIT:ETH_USDC:perpetual", "DERIBIT:BTC_USDC:front"], sys.executable, "-c", "pass")
            self.assertEqual(quick.wait(30), 0)
            self.assertLess(time.time() - began, 2.5)
            slow.wait(30)

    def test_a_killed_holder_releases_its_claim(self):
        with tempfile.TemporaryDirectory() as directory:
            first = self.hold(directory, ["X:Y:z"], sys.executable, "-c", "import time; time.sleep(60)")
            time.sleep(0.7)
            first.kill()
            first.wait(10)
            held = claim_lock.acquire(directory, ["X:Y:z"], poll_s=0.1, say=lambda _: None)
            self.assertEqual(len(held), 1)


class Lockstep(unittest.TestCase):
    """Two cases sharing a claim must not overlap even when grouped apart: the wrapper the catalog puts on each."""

    def test_the_same_claim_serialises_the_cross_leg_with_a_group(self):
        cross = yaml.safe_load(open(os.path.join(CROSS, "same-symbol-two-venues/case.yaml")))
        perp = yaml.safe_load(open(os.path.join(CASES, "perpetual-bracket-timed-exit/case.yaml")))
        self.assertTrue(dp.claims_of(cross) <= dp.claims_of(perp))


if __name__ == "__main__":
    unittest.main()
