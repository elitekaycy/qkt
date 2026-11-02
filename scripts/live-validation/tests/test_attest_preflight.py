"""The preflight: every failure at once, each by its true name, secrets never printed."""
import json, os, subprocess, sys, tempfile, unittest, urllib.error
import datetime as dt

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../lib"))
import attest_preflight as pf  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
SECRET = "s3cret-value"


class Fake(pf.World):
    pause = 0

    def __init__(self, answers=None, env=None, **kw):
        base = {"QKT_BROKER_API_KEY": SECRET, "QKT_LIVE_DEMO_ORDER_APPROVAL": "LOCALHOST_DEMO_ONLY", "QKT_ALPACA_GATEWAY_KEY": SECRET}
        super().__init__(base if env is None else env)
        self.answers = {"/health": {"status": "healthy", "mt5_status": "connected", "kill_switch_active": False},
                        "/account": {"login": 1, "server": "S", "trade_mode": 0, "trade_allowed": True},
                        "/get_positions": {"data": []}, "/orders": {"orders": []}, **(answers or {})}
        self.calls, self.missing, self.disk, self.mem, self.held = [], [], 100.0, 10.0, False
        self.rc = dict(kw.get("rc", {}))
        self.clock = kw.get("now", dt.datetime(2026, 11, 3, 15, 0, tzinfo=dt.timezone.utc))

    def get(self, url, key=None, timeout=5):
        path = "/" + url.split("/", 3)[3]
        self.calls.append(path)
        answer = self.answers[path]
        if isinstance(answer, Exception):
            raise answer
        return answer

    def which(self, tool):
        return None if tool in self.missing else f"/bin/{tool}"

    def run(self, *command, cwd=None, timeout=25):
        for needle, result in self.rc.items():
            if needle in " ".join(command):
                return result
        return (0, "1") if "runners" in " ".join(command) else (0, "")

    def free_gb(self, path):
        return self.disk

    def available_gb(self):
        return self.mem

    def lock_held(self, path):
        return self.held

    def now(self):
        return self.clock


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        corpus = os.path.join(self.tmp.name, "corpus"); os.makedirs(os.path.join(corpus, ".git"))
        self.p = {"gateway_url": "http://127.0.0.1:1", "expected_login": "1", "expected_server": "S", "magic_base": "1000",
                  "wave_root": self.tmp.name, "bundle_root": self.tmp.name, "insights_image": "img", "data_corpus": corpus,
                  "__repo_root": ROOT}

    def failed(self, world, **kw):
        found = pf.run_checks(self.p, world, no_session=kw.pop("no_session", True), **kw)
        self.assertNotIn(SECRET, json.dumps([vars(f) for f in found]))
        return {f.check: f.message for f in found if f.level == "fail"}

    def test_a_healthy_machine_passes(self):
        self.assertEqual(self.failed(Fake()), {})

    def test_a_timeout_is_unreachable_not_not_flat(self):
        world = Fake({"/health": OSError("timed out")})
        failed = self.failed(world)
        self.assertIn("unreachable", failed["gateway-reachable"])
        self.assertIn("says nothing about the account", failed["gateway-reachable"])
        self.assertNotIn("account-flat", failed)
        self.assertEqual(world.calls.count("/health"), 3)

    def test_positions_read_timing_out_is_unknown_not_not_flat(self):
        failed = self.failed(Fake({"/get_positions": OSError("timed out")}))
        self.assertIn("flatness is unknown", failed["account-flat"])
        self.assertNotIn("account is not flat", failed["account-flat"])

    def test_not_flat_names_the_tickets(self):
        failed = self.failed(Fake({"/get_positions": {"data": [{"ticket": 77, "symbol": "XAUUSDm", "magic": 5}]},
                                   "/orders": {"orders": [{"ticket": 88, "magic": 6}]}}))
        self.assertIn("account is not flat", failed["account-flat"])
        self.assertIn("position 77", failed["account-flat"]); self.assertIn("order 88", failed["account-flat"])

    def test_own_leftovers_are_for_the_sweep(self):
        found = pf.run_checks(self.p, Fake({"/get_positions": {"data": [{"ticket": 77, "magic": 1100}]}}), no_session=True)
        flat = [f for f in found if f.check == "account-flat"]
        self.assertEqual(flat[0].level, "warn"); self.assertIn("sweep", flat[0].message)

    def test_a_busy_gateway_fails_on_latency(self):
        world = Fake()
        real = world.get
        def slow(url, key=None, timeout=5):
            if url.endswith("/get_positions") and world.calls.count("/get_positions") >= 1:
                world.calls.append("/get_positions"); raise OSError("timed out")
            return real(url, key, timeout)
        world.get = slow
        self.assertIn("too slow", self.failed(world)["gateway-latency"])

    def test_everything_is_reported_at_once(self):
        world = Fake({"/account": {"login": 9, "server": "S", "trade_mode": 0, "trade_allowed": True}}, rc={"image inspect": (1, "")})
        world.missing = ["sqlite3"]; world.disk = 1.0; world.mem = 1.0; world.held = True
        failed = self.failed(world)
        self.assertIn("sqlite3", failed["tools"]); self.assertIn("1.0 GB free", failed["disk"])
        self.assertIn("memory", failed); self.assertIn("another live run", failed["account-lock"])
        self.assertIn("logged into 9", failed["account"]); self.assertIn("img", failed["docker-image"])

    def test_missing_tokens_are_named_and_never_valued(self):
        self.p.update(alpaca_gateway_url="http://127.0.0.1:2", alpaca_expected_login="a")
        world = Fake({"/v1/health": {"account_login": "a", "trade_mode": "demo", "venue_connected": True, "kill_switch": {}}})
        failed = self.failed(world)  # the repo's alpaca lane has a kill-switch drill
        self.assertIn("QKT_ALPACA_GUARDIAN_KEY", failed["tokens"])

    def test_a_venue_gateway_with_its_link_down_is_named(self):
        self.p.update(alpaca_gateway_url="http://127.0.0.1:2", alpaca_expected_login="a")
        world = Fake({"/v1/health": {"account_login": "a", "trade_mode": "demo", "venue_connected": False, "kill_switch": {}}})
        self.assertIn("venue link is down", self.failed(world)["alpaca-gateway"])

    def test_a_refused_token_is_not_unreachable(self):
        self.p.update(alpaca_gateway_url="http://127.0.0.1:2", alpaca_expected_login="a")
        world = Fake({"/v1/health": urllib.error.HTTPError("u", 401, "no", {}, None)})
        self.assertIn("refused the token", self.failed(world)["alpaca-gateway"])

    def test_no_runner_only_matters_when_dispatching(self):
        world = Fake(rc={"actions/runners": (0, "0")})
        self.assertNotIn("runner", self.failed(world))
        self.assertIn("no self-hosted runner", self.failed(world, dispatch=True)["runner"])

    def test_the_session_estimate_is_said_up_front(self):
        self.p.update(alpaca_gateway_url="http://127.0.0.1:2", alpaca_expected_login="a")
        world = Fake({"/v1/health": {"account_login": "a", "trade_mode": "demo", "venue_connected": True, "kill_switch": {}}},
                     now=dt.datetime(2026, 11, 3, 20, 10, tzinfo=dt.timezone.utc))
        self.assertIn("closes Tue 21:00Z", self.failed(world, no_session=False)["session-alpaca"])

    def test_a_resume_that_kept_the_lane_needs_no_window(self):
        self.p.update(alpaca_gateway_url="http://127.0.0.1:2", alpaca_expected_login="a")
        names = [f"alpaca-{c}" for c in os.listdir(os.path.join(ROOT, "attestation/cases/alpaca"))]
        world = Fake({"/v1/health": {"account_login": "a", "trade_mode": "demo", "venue_connected": True, "kill_switch": {}}},
                     now=dt.datetime(2026, 11, 7, 3, 0, tzinfo=dt.timezone.utc))
        self.assertNotIn("session-alpaca", self.failed(world, no_session=False, plan={"reusableCases": names, "leadSeconds": 0}))

    def test_the_command_exits_non_zero_and_prints_every_reason_on_its_last_line(self):
        profile = os.path.join(self.tmp.name, "profile")
        open(profile, "w").write("\n".join(f"{k}={v}" for k, v in self.p.items() if not k.startswith("__")) + "\n")
        done = subprocess.run([sys.executable, os.path.join(HERE, "../lib/attest_preflight.py"), "--profile", profile,
                               "--repo-root", ROOT, "--no-session", "--also-failed", "working tree is not clean"],
                              capture_output=True, text=True, env={**os.environ, "QKT_ATTEST_LOCK_DIR": self.tmp.name,
                                                                   "QKT_BROKER_API_KEY": SECRET, "QKT_LIVE_DEMO_ORDER_APPROVAL": "x"})
        self.assertEqual(done.returncode, 1)
        last = done.stdout.strip().splitlines()[-1]
        self.assertIn("run: working tree is not clean", last); self.assertIn("gateway-reachable", last)
        self.assertNotIn(SECRET, done.stdout)


if __name__ == "__main__":
    unittest.main()
