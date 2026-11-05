"""The catalog orders lanes by market hours: session lanes inside their window, every other lane now, and no case outside
its window whatever the plan says. Driven on the real catalog script with stub runners and a test clock."""
import json, os, stat, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import catalog_fixture as fx  # noqa: E402

LANES_YAML = """lanes:
  stocks:
    session: nyse[09:40-15:45]
    budget_seconds: 420
  alpaca:
    session: nyse[09:30-16:00]
    venue_gateway: true
    budget_seconds: 1200
"""
# 2026-11-05 is the Thursday of the v0.66.0 release: 09:30-16:00 New York is 14:30-21:00 UTC.
THU_START = "2026-11-05T20:48:00Z"
STUB_FLAGS = ("--alpaca-gateway-url", "http://127.0.0.1:1", "--alpaca-expected-login", "a")


class SessionCatalogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.fake = fx.build(self.tmp.name, {"orders": ["o1", "o2"], "stocks": ["s1", "s2"], "alpaca": ["a1", "a2"]}, LANES_YAML)
        live = os.path.join(self.fake, "scripts/live-validation")
        # Each stub attempt also notes the test clock, so a test can see when each case ran.
        runner = fx.RUNNER.replace("magic={args.get('--magic')}", "magic={args.get('--magic')} at={open(os.environ['QKT_ATTEST_CLOCK_FILE']).read().strip()}")
        for name in ("run-derivatives-lane-case.py", "run-daemon-lane-case.py"):
            open(os.path.join(live, name), "w").write(runner)
        # The venue's AAPL quote, fresh: the gateway answers with this second.
        self.bin = os.path.join(self.tmp.name, "bin"); os.makedirs(self.bin)
        curl = os.path.join(self.bin, "curl")
        open(curl, "w").write('#!/usr/bin/env bash\nprintf \'{"time": %s}\' "$(date +%s)"\n')
        os.chmod(curl, os.stat(curl).st_mode | stat.S_IEXEC)
        self.clock = os.path.join(self.tmp.name, "clock")
        self.out = os.path.join(self.tmp.name, "catalog")

    def run_at(self, start, lanes="orders,stocks,alpaca", extra=(), behaviours=None):
        open(self.clock, "w").write(start)
        env = {"QKT_ATTEST_CLOCK_FILE": self.clock, "QKT_ALPACA_GATEWAY_KEY": "x", "QKT_ATTEST_STOCK_QUOTE_TRIES": "1",
               "PATH": f"{self.bin}:{os.environ['PATH']}"}
        return fx.run_catalog(self.fake, self.out, lanes, (*STUB_FLAGS, "--max-parallel", "1", *extra), behaviours, env)

    def ran(self):
        """[(case, clock when it ran)] in the order the stubs ran."""
        return [(line.split()[0], line.split("at=")[1]) for line in fx.calls(self.fake)]

    def attempts(self, name):
        path = os.path.join(self.out, "attempts", f"{name}.jsonl")
        return [json.loads(line) for line in open(path)] if os.path.exists(path) else []

    def waits(self):
        path = os.path.join(self.out, "session-waits.jsonl")
        return [json.loads(line) for line in open(path)] if os.path.exists(path) else []

    def test_the_v0660_start_runs_the_other_lanes_now_and_the_session_lanes_at_fridays_open(self):
        done = self.run_at(THU_START)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        ran = dict(self.ran())
        self.assertEqual({ran["o1"], ran["o2"]}, {THU_START})
        for case in ("a1", "a2"):
            self.assertGreaterEqual(ran[case], "2026-11-06T14:30:00Z")
        for case in ("s1", "s2"):
            self.assertEqual(ran[case], "2026-11-06T14:40:00Z")
        order = [c for c, _ in self.ran()]
        self.assertEqual(order[:2], ["o1", "o2"])
        # Every wait is in the log with its planned time and reason, and recorded for the bundle.
        self.assertEqual([(w["lane"], w["plannedStartUtc"]) for w in self.waits()],
                         [("alpaca", "2026-11-06T14:30:00Z"), ("stocks", "2026-11-06T14:40:00Z")])
        alpaca, stocks = self.waits()
        self.assertIn("only 12 min of the window are left when the run reaches it (closes Thu 21:00Z)", alpaca["reason"])
        self.assertIn("the stocks lane waits for its window Fri 14:40Z", stocks["reason"])
        self.assertIn("session plan: stocks wait: the stocks lane waits for its window Fri 14:40Z", done.stdout)
        self.assertIn("waiting until 2026-11-06T14:30:00Z for the alpaca lane", done.stdout)
        result = json.load(open(os.path.join(self.out, "result.json")))
        self.assertEqual(result["status"], "passed")

    def test_inside_the_window_the_session_lanes_run_first(self):
        done = self.run_at("2026-11-03T15:00:00Z")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual([c for c, _ in self.ran()], ["a1", "a2", "s1", "s2", "o1", "o2"])
        self.assertEqual(self.waits(), [])

    def test_a_window_beyond_the_wait_limit_fails_the_session_cases_by_name_and_runs_the_rest(self):
        done = self.run_at(THU_START, extra=("--session-wait-max-h", "6"))
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(sorted(c for c, _ in self.ran()), ["o1", "o2"])
        for name in ("stocks-s1", "stocks-s2", "alpaca-a1", "alpaca-a2"):
            last = self.attempts(name)[-1]
            self.assertEqual((last["status"], last["class"]), ("failed", "session"))
            self.assertIn("past the session wait limit", last["summary"])
        self.assertEqual(self.waits(), [])

    def test_the_per_case_refusal_still_fires_when_the_plan_is_wrong(self):
        # A scheduler that always says "now" at Thu 20:48Z: not one session case may run, each fails as `session`.
        path = os.path.join(self.fake, "scripts/live-validation/lib/attest_session.py")
        source = open(path).read()
        open(path, "w").write(source.replace("def plan_lane(", "def plan_lane(*a, **k):\n    return 'now', a[3], 'wrong'\n\n\ndef _plan_lane(", 1))
        done = self.run_at(THU_START)
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(sorted(c for c, _ in self.ran()), ["o1", "o2"])
        for name in ("stocks-s1", "stocks-s2", "alpaca-a1", "alpaca-a2"):
            last = self.attempts(name)[-1]
            self.assertEqual((last["status"], last["class"]), ("failed", "session"), name)
            self.assertIn("window", last["summary"])
        self.assertIn("failed", json.load(open(os.path.join(self.out, "result.json")))["status"])

    def test_a_window_cut_short_fails_the_cases_that_cannot_finish_and_a_resume_reruns_only_them(self):
        binding = os.path.join(self.tmp.name, "binding.json")
        json.dump({"testingSha": "abc", "cliVersion": "qkt abc"}, open(binding, "w"))
        # 20:25Z: the alpaca lane's ~31 min fit before 21:00Z by the plan, but each case needs its 1200 s budget whole.
        # The first case starts, the clock moves past the point the second could finish, and the second must not run.
        live = os.path.join(self.fake, "scripts/live-validation")
        runner = open(os.path.join(live, "run-derivatives-lane-case.py")).read()
        open(os.path.join(live, "run-derivatives-lane-case.py"), "w").write(runner.replace(
            "sys.exit(1 if failed else 0)",
            "open(os.environ['QKT_ATTEST_CLOCK_FILE'], 'w').write('2026-11-03T20:45:00Z')\nsys.exit(1 if failed else 0)"))
        done = self.run_at("2026-11-03T20:25:00Z", lanes="orders,alpaca", extra=("--binding", binding))
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(self.attempts("alpaca-a1")[-1]["status"], "passed")
        last = self.attempts("alpaca-a2")[-1]
        self.assertEqual((last["status"], last["class"]), ("failed", "session"))
        self.assertIn("not enough of the alpaca window was left", last["summary"])
        # Resumed later on the same binding: what passed is kept, the cut case waits for the next window and runs there.
        os.remove(os.path.join(os.path.dirname(self.fake), "calls.log"))
        done = self.run_at("2026-11-03T22:00:00Z", lanes="orders,alpaca", extra=("--binding", binding))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual([c for c, _ in self.ran()], ["a2"])
        self.assertEqual(self.ran()[0][1], "2026-11-04T14:30:00Z")
        self.assertEqual([(w["lane"], w["plannedStartUtc"]) for w in self.waits()], [("alpaca", "2026-11-04T14:30:00Z")])
        result = json.load(open(os.path.join(self.out, "result.json")))
        self.assertEqual(result["status"], "passed")
        self.assertTrue(next(c for c in result["runs"] if c["name"] == "alpaca-a1").get("reused"))

    def test_a_resume_that_kept_every_session_case_does_not_wait(self):
        binding = os.path.join(self.tmp.name, "binding.json")
        json.dump({"testingSha": "abc", "cliVersion": "qkt abc"}, open(binding, "w"))
        done = self.run_at("2026-11-03T15:00:00Z", extra=("--binding", binding), behaviours={"o2": ["fail:assertion"]})
        self.assertNotEqual(done.returncode, 0)
        os.remove(os.path.join(os.path.dirname(self.fake), "calls.log"))
        done = self.run_at("2026-11-07T03:00:00Z", extra=("--binding", binding), behaviours={"o2": ["pass"]})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual([c for c, _ in self.ran()], ["o2"])
        self.assertEqual(self.waits(), [])


if __name__ == "__main__":
    unittest.main()
