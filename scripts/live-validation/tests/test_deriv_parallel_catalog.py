"""The catalog runs the derivatives lane's groups side by side (run-attestation-catalog.sh with --deriv-drill-gateway-url),
each kill_switch case on a gateway of its own, and queues the cross lane's Deribit leg behind the group holding its
instrument; without the drill gateways it runs one case at a time as before. The case runner is a stub that records when
each case ran and against which gateway.

    python3 -m unittest discover -s scripts/live-validation/tests
"""
import json, os, sys, tempfile, threading, unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import catalog_fixture as fx  # noqa: E402

STUB = '''\
#!/usr/bin/env python3
import json, os, sys, time
args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
case, out = os.path.basename(args["--case"]), args["--out"]
os.makedirs(out)
start = time.time()
time.sleep(float(os.environ.get("STUB_SLEEP", "1.5")))
failed = case in os.environ.get("STUB_FAIL", "").split(",")
with open(os.environ["STUB_ROOT"] + "/spans.jsonl", "a") as log:
    log.write(json.dumps({"case": case, "url": args.get("--gateway-url"), "start": start, "end": time.time(),
                          "parallel": os.environ.get("QKT_ATTEST_DERIV_PARALLEL")}) + "\\n")
json.dump({"status": "failed" if failed else "passed", "problems": ["venue net differs"] if failed else []},
          open(out + "/result.json", "w"))
print(("failed " if failed else "passed ") + case)
sys.exit(1 if failed else 0)
'''

CASES = {  # id: (group, symbols, drills)
    "perp-a": ("btc-perp", ["DERIBIT:BTC_USDC_PERPETUAL"], []),
    "perp-b": ("btc-perp", ["DERIBIT:BTC_USDC_PERPETUAL"], [{"kind": "gateway_outage", "at_s": 1}]),
    "perp-eth-kill": ("eth-perp", ["DERIBIT:ETH_USDC_PERPETUAL"], [{"kind": "kill_switch", "at_s": 1}]),
    "dated": ("btc-dated", ["DERIBIT:BTC_USDC_30OCT26"], []),
    "roll-kill": ("eth-front", ["DERIBIT:ETH_USDC@front"], [{"kind": "kill_switch", "at_s": 0}]),
}


class Health:
    """Every gateway the lane will use answers /v1/health as the expected demo account."""

    def __init__(self, test):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                body = json.dumps({"account_login": "g-1", "trade_mode": "demo", "venue_connected": True,
                                   "kill_switch": {"all": False, "symbols": []}}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
                self.wfile.write(body)
        server = ThreadingHTTPServer(("0.0.0.0", 0), Handler)  # answers on every loopback address, the drill gateways' names
        threading.Thread(target=server.serve_forever, daemon=True).start()
        test.addCleanup(server.server_close); test.addCleanup(server.shutdown)
        self.port = server.server_port


class ParallelCatalog(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = self.tmp.name
        self.fake = fx.build(self.root, {"derivatives": list(CASES), "cross": ["same-symbol"]})
        live = os.path.join(self.fake, "scripts/live-validation")
        for name in ("run-derivatives-lane-case.py", "run-cross-lane-case.py"):
            open(os.path.join(live, name), "w").write(STUB)
        for cid, (group, symbols, drills) in CASES.items():
            doc = {"id": cid, "status": "ready", "group": group, "symbols": symbols, "drills": drills, "budget_seconds": 60}
            yaml.safe_dump(doc, open(os.path.join(self.fake, "attestation/cases/derivatives", cid, "case.yaml"), "w"))
        cross = {"id": "same-symbol", "status": "ready", "symbols": ["EXNESS:BTCUSD", "DERIBIT:BTC_USDC_PERPETUAL"],
                 "venues": [{"label": "EXNESS", "type": "mt5"}, {"label": "DERIBIT", "type": "gateway", "adapter": "deribit"}]}
        yaml.safe_dump(cross, open(os.path.join(self.fake, "attestation/cases/cross/same-symbol/case.yaml"), "w"))
        health = Health(self)
        self.base = f"http://127.0.0.1:{health.port}"
        self.k1 = f"http://127.0.0.2:{health.port}"
        self.k2 = f"http://127.0.0.3:{health.port}"

    def run_lane(self, lanes="derivatives", drill=True, extra=(), env=None):
        flags = ["--deriv-gateway-url", self.base, "--deriv-expected-login", "g-1",
                 "--cross-venue", f"DERIBIT={self.base}@g-1"]
        if drill:
            flags += ["--deriv-drill-gateway-url", self.k1, "--deriv-drill-gateway-url", self.k2]
        out = os.path.join(self.root, "out")
        done = fx.run_catalog(self.fake, out, lanes, [*flags, *extra], env=env)
        path = os.path.join(out, "result.json")
        return done, (json.load(open(path)) if os.path.exists(path) else None)

    def spans(self):
        path = os.path.join(self.root, "spans.jsonl")
        return {s["case"]: s for s in map(json.loads, open(path).read().splitlines())} if os.path.exists(path) else {}

    @staticmethod
    def overlap(a, b):
        return a["start"] < b["end"] and b["start"] < a["end"]

    def peak(self):
        marks = sorted([(s["start"], 1) for s in self.spans().values()] + [(s["end"], -1) for s in self.spans().values()])
        now = best = 0
        for _, step in sorted(marks, key=lambda m: (m[0], m[1])):
            now += step
            best = max(best, now)
        return best

    def test_groups_run_side_by_side_and_a_group_runs_one_case_at_a_time(self):
        done, result = self.run_lane()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(result["status"], "passed", result)
        self.assertEqual(sorted(r["name"] for r in result["runs"]), sorted(f"derivatives-{c}" for c in CASES))
        spans = self.spans()
        self.assertFalse(self.overlap(spans["perp-a"], spans["perp-b"]))
        self.assertTrue(self.overlap(spans["perp-a"], spans["perp-eth-kill"]), "two groups should overlap")
        self.assertGreaterEqual(self.peak(), 3)
        self.assertTrue(all(s["parallel"] == "1" for s in spans.values()))

    def test_each_kill_switch_case_runs_on_a_gateway_of_its_own_and_the_rest_on_the_base_one(self):
        done, _ = self.run_lane()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        urls = {c: s["url"] for c, s in self.spans().items()}
        self.assertEqual({urls["perp-eth-kill"], urls["roll-kill"]}, {self.k1, self.k2})
        self.assertEqual({urls[c] for c in ("perp-a", "perp-b", "dated")}, {self.base})

    def test_the_cap_bounds_how_many_groups_run_at_once(self):
        done, _ = self.run_lane(extra=("--deriv-parallel", "2"))
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.peak(), 2)

    def test_without_drill_gateways_the_lane_runs_one_case_at_a_time_as_before(self):
        done, result = self.run_lane(drill=False)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.peak(), 1)
        self.assertTrue(all(s["url"] == self.base and s["parallel"] is None for s in self.spans().values()))
        self.assertEqual(result["status"], "passed")

    def test_the_cross_lane_s_deribit_leg_waits_for_the_group_holding_its_perpetual(self):
        done, result = self.run_lane(lanes="derivatives,cross", env={"STUB_SLEEP": "1.2"})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(result["status"], "passed", result)
        spans = self.spans()
        for case in ("perp-a", "perp-b"):
            self.assertFalse(self.overlap(spans["same-symbol"], spans[case]), case)
        self.assertTrue(any(self.overlap(spans["same-symbol"], spans[c]) for c in ("perp-eth-kill", "dated", "roll-kill")),
                        "the cross case should still run beside groups that hold other instruments")

    def test_a_failed_case_fails_the_run_and_the_other_groups_are_still_judged(self):
        done, result = self.run_lane(env={"STUB_FAIL": "dated"})
        self.assertNotEqual(done.returncode, 0)
        verdicts = {r["name"]: r["status"] for r in result["runs"]}
        self.assertEqual(verdicts["derivatives-dated"], "failed")
        self.assertEqual({v for n, v in verdicts.items() if n != "derivatives-dated"}, {"passed"})

    def test_too_few_drill_gateways_runs_nothing_and_fails_each_case_as_never_run(self):
        out = os.path.join(self.root, "out")
        flags = ["--deriv-gateway-url", self.base, "--deriv-expected-login", "g-1", "--deriv-drill-gateway-url", self.k1]
        done = fx.run_catalog(self.fake, out, "derivatives", flags, env={})
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(self.spans(), {})
        result = json.load(open(os.path.join(out, "result.json")))
        self.assertEqual({r["summary"] for r in result["runs"]}, {"the case never ran"})
        self.assertIn("each need a gateway of their own", done.stdout + done.stderr)

    def test_a_drill_gateway_on_another_account_runs_nothing(self):
        out = os.path.join(self.root, "out")
        flags = ["--deriv-gateway-url", self.base, "--deriv-expected-login", "someone-else",
                 "--deriv-drill-gateway-url", self.k1, "--deriv-drill-gateway-url", self.k2]
        done = fx.run_catalog(self.fake, out, "derivatives", flags, env={})
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(self.spans(), {})
        self.assertIn("not someone-else", done.stdout + done.stderr)


if __name__ == "__main__":
    unittest.main()
