"""Failure classes: a transport failure earns bounded retries, an assertion one rerun, a broken machine none."""
import json, os, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib")); sys.path.insert(0, HERE)
import attest_classify as c  # noqa: E402
import catalog_fixture as fx  # noqa: E402


class ClassifyTest(unittest.TestCase):
    def test_transport_failures_are_infrastructure(self):
        for line in ("HTTP Error 503: Service Unavailable", "curl: (28) Operation timed out after 10002 milliseconds",
                     "<urlopen error [Errno 111] Connection refused>", "kill switch: The read operation timed out",
                     "venue-untradeable: DERIBIT:BTC reported no index in 10 minutes"):
            self.assertEqual(c.classify([line]), c.INFRASTRUCTURE, line)

    def test_a_501_is_the_venue_answering_not_a_blip(self):
        self.assertEqual(c.classify(["HTTP Error 501: Not Implemented"]), c.BEHAVIOUR)

    def test_assertions_and_unknown_text_are_behaviour(self):
        for line in ("deals-net-equals-realized: venue net 1 != qkt realized 2", "failed contended stale quote",
                     "step 3: venue never reached 'flat' x1 (held 1)", "something entirely new"):
            self.assertEqual(c.classify([line]), c.BEHAVIOUR, line)

    def test_one_assertion_among_transport_errors_is_behaviour(self):
        # The real 2026-10-30 failure: the flat assertion failed AND the sweep got a 5xx-like error.
        self.assertEqual(c.classify(["flat-by-strategy: the strategy holds a position",
                                     "sweep: HTTP Error 503: Service Unavailable"]), c.BEHAVIOUR)

    def test_the_machine_is_environment(self):
        for line in ("runner error: [Errno 2] No such file or directory: '/x/data/contracts'",
                     "write error: No space left on device", "run-insights-attribution: sqlite3 is required"):
            self.assertEqual(c.classify([line]), c.ENVIRONMENT, line)

    def test_a_vague_problem_looks_in_the_log(self):
        vague = ["runner exited unexpectedly with code 1 near line 90"]
        self.assertEqual(c.classify(vague, ["curl: (28) Operation timed out"]), c.INFRASTRUCTURE)
        self.assertEqual(c.classify(vague, ["fine"]), c.BEHAVIOUR)

    def test_no_evidence_at_all_is_behaviour(self):
        self.assertEqual(c.classify([], []), c.BEHAVIOUR)

    def test_retry_budgets_are_bounded(self):
        self.assertEqual(c.next_step(c.BEHAVIOUR, []), (True, 0))
        self.assertEqual(c.next_step(c.BEHAVIOUR, [c.BEHAVIOUR])[0], False)
        delays = [c.next_step(c.INFRASTRUCTURE, [c.INFRASTRUCTURE] * n) for n in range(4)]
        self.assertEqual(delays, [(True, 30), (True, 60), (True, 120), (False, 0)])
        self.assertEqual(c.next_step(c.ENVIRONMENT, [])[0], False)
        self.assertEqual(c.next_step(c.SESSION, [])[0], False)


class CatalogRetriesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.fake = fx.build(self.tmp.name, {"orders": ["good", "blip", "wrong", "box"]})
        self.out = os.path.join(self.tmp.name, "out")

    def result(self):
        doc = json.load(open(f"{self.out}/result.json"))
        return doc, {r["name"]: r for r in doc["runs"]}

    def test_each_class_gets_its_budget_and_every_attempt_is_kept(self):
        done = fx.run_catalog(self.fake, self.out, "orders", behaviours={
            "blip": ["fail:HTTP Error 503: x", "fail:HTTP Error 503: x", "pass"],
            "wrong": ["fail:venue net differs"],
            "box": ["fail:No space left on device"]})
        self.assertNotEqual(done.returncode, 0)
        doc, runs = self.result()
        self.assertEqual(runs["orders-blip"]["status"], "passed")
        self.assertTrue(runs["orders-blip"]["flaky"])
        self.assertEqual([a["class"] for a in runs["orders-blip"]["attempts"]], ["infrastructure", "infrastructure", None])
        self.assertEqual(len(runs["orders-wrong"]["attempts"]), 2)
        self.assertEqual(runs["orders-wrong"]["class"], "behaviour")
        self.assertEqual(len(runs["orders-box"]["attempts"]), 1)
        self.assertEqual(runs["orders-box"]["class"], "environment")
        self.assertEqual(doc["flaky"], ["orders-blip"])
        self.assertIn("FAILED orders-wrong [behaviour]: venue net differs", done.stdout)
        self.assertIn("retrying after 0s", done.stdout)

    def test_a_flaky_pass_is_never_silent(self):
        done = fx.run_catalog(self.fake, self.out, "orders", behaviours={"wrong": ["fail:stale quote", "pass"]})
        self.assertEqual(done.returncode, 0, done.stderr)
        doc, runs = self.result()
        self.assertEqual(doc["flaky"], ["orders-wrong"])
        self.assertEqual(runs["orders-wrong"]["firstAttempt"], "stale quote")

    def test_a_gateway_lane_case_retries_alone_on_its_account(self):
        tmp2 = tempfile.mkdtemp(dir=self.tmp.name)
        fake = fx.build(tmp2, {"derivatives": ["perp", "opt"]})
        out = os.path.join(tmp2, "out")
        done = fx.run_catalog(fake, out, "derivatives", ("--deriv-gateway-url", "http://127.0.0.1:1", "--deriv-expected-login", "g"),
                              behaviours={"perp": ["fail:HTTP Error 502: x", "pass"]})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual([l.split()[1] for l in fx.calls(fake)],
                         ["derivatives-opt", "derivatives-perp", "derivatives-perp-retry"])


if __name__ == "__main__":
    unittest.main()
