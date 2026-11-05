"""The flake report reads both attempt formats and never starts a soak by accident."""
import json, os, subprocess, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "../attest-flake-report.py")


def put(path, doc):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as stream:
        stream.write(json.dumps(doc))


class FlakeReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)

    def report(self, *extra):
        done = subprocess.run([sys.executable, SCRIPT, "--root", self.tmp.name, "--json", *extra], capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        return json.loads(done.stdout)

    def test_old_format_with_retry_dirs(self):
        c = f"{self.tmp.name}/attest-aaa-catalog"
        put(f"{c}/orders-x/result.json", {"status": "failed", "problems": ["HTTP Error 503: x"]})
        put(f"{c}/orders-x-retry/result.json", {"status": "passed", "problems": []})
        put(f"{c}/orders-y/result.json", {"status": "passed"})
        put(f"{c}/result.json", {"runs": [{"name": "orders-z", "status": "failed", "summary": "curl: (28) timed out"}]})
        doc = self.report()["cases"]
        self.assertEqual((doc["orders-x"]["passed"], doc["orders-x"]["failed"], doc["orders-x"]["retried_runs"]), (1, 1, 1))
        self.assertIn("infrastructure: HTTP Error 503: x", doc["orders-x"]["reasons"])
        self.assertEqual(doc["orders-y"]["failed"], 0)
        self.assertEqual(doc["orders-z"]["failed"], 1)

    def test_attempt_records_win_over_the_directories(self):
        c = f"{self.tmp.name}/attest-bbb-catalog"
        put(f"{c}/orders-x/result.json", {"status": "failed"})
        os.makedirs(f"{c}/attempts")
        with open(f"{c}/attempts/orders-x.jsonl", "w") as stream:
            stream.write(json.dumps({"status": "failed", "class": "behaviour", "summary": "no"}) + "\n" +
                         json.dumps({"status": "passed", "class": None, "summary": ""}) + "\n")
        doc = self.report()["cases"]["orders-x"]
        self.assertEqual((doc["attempts"], doc["passed"], doc["retried_runs"]), (2, 1, 1))

    def test_a_release_log_adds_runs_replaced_on_disk(self):
        log = f"{self.tmp.name}/release.log"
        with open(log, "w") as stream:
            stream.write("15:08:06 catalog passed runs=41 passed=41 capabilities=0 retried=0 wallClock=2992s\n"
                         "run-attestation: attestation catalog failed:   FAILED a-b: runner error: [Errno 2] No such file   FAILED c-d: venue net differs\n"
                         "19:36:40 STOPPED: attestation failed: see its output above\n")
        doc = self.report("--release-log", log)
        self.assertEqual(doc["releaseLogRuns"][0]["passed"], 41)
        self.assertEqual([(f["name"], f["class"]) for f in doc["releaseLogRuns"][1]["failures"]], [("a-b", "environment"), ("c-d", "behaviour")])
        self.assertEqual(len(doc["stops"]), 1)

    def test_soak_needs_its_arguments_and_dry_run_runs_nothing(self):
        done = subprocess.run([sys.executable, SCRIPT, "--soak", "2"], capture_output=True, text=True)
        self.assertNotEqual(done.returncode, 0)
        profile = f"{self.tmp.name}/p"
        with open(profile, "w") as stream:
            stream.write(f"gateway_url=http://127.0.0.1:1\nexpected_login=1\nexpected_server=S\nmagic_base=1\nwave_root={self.tmp.name}\n")
        done = subprocess.run([sys.executable, SCRIPT, "--soak", "2", "--profile", profile, "--cli", "/x", "--dry-run"], capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(done.stdout.count("run-attestation-catalog.sh"), 2)
        self.assertFalse([d for d in os.listdir(self.tmp.name) if d.startswith("attest-soak")])


if __name__ == "__main__":
    unittest.main()
