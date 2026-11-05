"""A rerun on the same commit keeps what passed on this exact build and reruns the rest, and the bundle still verifies.

Everything live is stubbed; run-attestation.sh, the catalog, the resume ledger, the assembler and the verifier are real.
"""
import datetime as dt, json, os, shutil, stat, subprocess, sys, tempfile, textwrap, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
LIVE = "scripts/live-validation"
sys.path.insert(0, os.path.join(HERE, "../lib"))
sys.path.insert(0, HERE)
import attest_cases as cases  # noqa: E402

CORE = ["orders", "risk", "book", "engine", "daemon", "stress"]

WAVE_STUB = textwrap.dedent('''\
    #!/usr/bin/env python3
    import datetime as dt, hashlib, json, os, sys, zipfile
    args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
    out, root = args["--output"], os.environ["STUB_ROOT"]
    open(f"{root}/calls.log", "a").write("wave x\\n")
    if os.path.exists(f"{root}/wave-fails"):
        print("run-parity-suite: HTTP Error 503: Service Unavailable"); sys.exit(1)
    now = dt.datetime.now(dt.timezone.utc)
    z = lambda t: t.strftime("%Y-%m-%dT%H:%M:%SZ")
    sha = os.environ["STUB_SHA"]
    def dump(path, doc):
        os.makedirs(os.path.dirname(path), exist_ok=True); json.dump(doc, open(path, "w"))
    golden = dict(ticks=5, warmupTicks=5, candles=5, fills=2, gatewayExchanges=2, linkedPlacements=1, streamCandles=3)
    for cid in ("a", "b", "c", "d"):
        c = f"{out}/cases/{cid}"
        dump(f"{c}/evidence/result.json", {"status": "passed", "golden": golden})
        dump(f"{c}/armed-live/evidence/result.json", {"status": "passed", "golden": golden})
        dump(f"{c}/armed-live/replay/result.json", {"status": "passed", "parity": {}})
        dump(f"{c}/scenario.json", {"id": cid, "symbol": "BTCUSD", "variant": "v", "magic": 1})
        dump(f"{c}/armed-live/evidence/gateway-account-final.json", {"balance": 1})
        open(f"{c}/evidence/health.jsonl", "w").write(json.dumps({"perStrategy": [{"droppedTicks": 0}]}) + "\\n")
        os.makedirs(f"{c}/logs"); open(f"{c}/logs/daemon.log", "w").write("warmup: seeded hub x bars=5\\n")
        with zipfile.ZipFile(f"{c}/armed-live/evidence/golden.zip", "w") as bundle: bundle.writestr("x", "y")
        report = f"{c}/armed-live/replay/reports/full-ticks-mt5"; os.makedirs(report)
        open(f"{report}/orders.jsonl", "w").close(); open(f"{report}/trades.csv", "w").write("timestamp,brokerOrderId\\n")
    dump(f"{out}/suite.json", {"qktCommit": sha, "suiteId": "s", "createdAt": z(now - dt.timedelta(minutes=15)),
         "account": {"login": 1, "server": "S", "tradeMode": 0, "currency": "USD", "balance": "1", "leverage": 1},
         "capabilityCatalog": {}, "execution": {"mode": "live", "cases": 4, "completedAt": z(now), "runId": "parity-" + os.urandom(6).hex(),
                                                "inputFingerprint": "0" * 64}})
    with open(f"{out}/SHA256SUMS", "w") as sums:
        for folder, _, names in os.walk(out):
            for n in names:
                if n != "SHA256SUMS":
                    p = os.path.join(folder, n)
                    sums.write(hashlib.sha256(open(p, "rb").read()).hexdigest() + "  " + os.path.relpath(p, out) + "\\n")
    print(out)
    ''')
PROOFS_STUB = '''#!/usr/bin/env bash
while [ "$#" -gt 0 ]; do case "$1" in --out) o="$2"; shift 2 ;; *) shift ;; esac; done
mkdir -p "$o"; echo "proofs x" >> "$STUB_ROOT/calls.log"; echo '{"passed":true}' > "$o/proofs.json"; echo "pass all"
'''
INS_STUB = '''#!/usr/bin/env bash
while [ "$#" -gt 0 ]; do case "$1" in --scenario) o="$2"; shift 2 ;; *) shift ;; esac; done
echo "insights x" >> "$STUB_ROOT/calls.log"
[ ! -e "$STUB_ROOT/insights-fails" ] || { echo "insights broke"; exit 1; }
printf '{"status":"passed","telemetry":{"ruleDecisions":1,"submitted":1,"accepted":1,"filled":1,"trades":1,"fillAccounted":1}}' > "$o/result.json"
'''
GH = '''#!/usr/bin/env bash
case "$1 ${2:-}" in
  "run list") echo 4242 ;;
  "run view") echo "pushing manifest for ghcr.io/test/qkt:edge@sha256:$(printf 'a%.0s' {1..64})" ;;
  "release view") echo v0.0.1 ;;
  "api "*) echo 1 ;;
  "workflow run") echo "dispatched" >> "$STUB_ROOT/calls.log" ;;
  *) ;;
esac
'''
GRADLEW = '''#!/usr/bin/env bash
case "$*" in
  *installDist*)
    echo "build x" >> "$STUB_ROOT/calls.log"
    mkdir -p build/install/qkt/bin
    printf '#!/usr/bin/env bash\\necho "qkt 0.0.0 (%s) built %s"\\n' "$(git rev-parse HEAD)" "$(date +%s%N)" > build/install/qkt/bin/qkt
    chmod +x build/install/qkt/bin/qkt ;;
esac
'''


# The preflight's session plan, stubbed: a hold until Monday 13:43:50Z while the test clock is before it.
HOLD_PREFLIGHT = textwrap.dedent('''\
    import json, os, sys
    args = sys.argv[1:]
    now = open(os.environ["QKT_ATTEST_CLOCK_FILE"]).read().strip()
    hold = "2026-11-09T13:43:50Z" if now < "2026-11-09T13:43:50Z" else None
    summary = ("session lanes alpaca at Mon 14:30Z; other lanes with them" if hold
               else "session lanes alpaca first, inside their window; other lanes now")
    json.dump({"lanes": [], "problems": [], "holdUntilUtc": hold, "summary": summary}, open(args[args.index("--schedule") + 1], "w"))
    print("preflight: stub")
    ''')


def write(path, text, executable=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as stream:
        stream.write(text)
    if executable:
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)


class Harness:
    def __init__(self, tmp):
        self.root = tmp
        self.repo = os.path.join(tmp, "repo")
        repo = self.repo
        for script in ("run-attestation.sh", "run-attestation-catalog.sh", "assemble-attestation.py"):
            write(f"{repo}/{LIVE}/{script}", open(f"{ROOT}/{LIVE}/{script}").read(), True)
        shutil.copytree(f"{ROOT}/{LIVE}/lib", f"{repo}/{LIVE}/lib")
        write(f"{repo}/{LIVE}/lib/attest_preflight.py", "import sys; print('preflight: stub'); sys.exit(0)\n")
        write(f"{repo}/scripts/verify-paper-soak-attestation.py", open(f"{ROOT}/scripts/verify-paper-soak-attestation.py").read())
        import catalog_fixture as fx
        write(f"{repo}/{LIVE}/run-daemon-lane-case.py", fx.RUNNER)
        write(f"{repo}/{LIVE}/run-order-lane-case.sh", 'exec python3 "$(dirname "$0")/run-daemon-lane-case.py" "$@"\n')
        write(f"{repo}/{LIVE}/sweep-attestation-leftovers.py", 'print("{}")\n')
        write(f"{repo}/{LIVE}/run-parity-suite.sh", f'exec python3 "$(dirname "$0")/wave_stub.py" "$@"\n')
        write(f"{repo}/{LIVE}/wave_stub.py", WAVE_STUB)
        write(f"{repo}/{LIVE}/prepare-scenario.sh", 'while [ "$#" -gt 0 ]; do case "$1" in --output) mkdir -p "$2"; shift 2 ;; *) shift ;; esac; done\n')
        write(f"{repo}/{LIVE}/run-insights-attribution.sh", INS_STUB)
        write(f"{repo}/scripts/release-proof/run.sh", PROOFS_STUB)
        write(f"{repo}/gradlew", GRADLEW, True)
        write(f"{repo}/attestation/lib/validate.py", 'print("ok")\n')
        write(f"{repo}/attestation/lanes.yaml", "lanes: {}\n")
        write(f"{repo}/tests/data-e2e/corpus.lock", "abc x\n")
        write(f"{repo}/.gitignore", "build/\n__pycache__/\n")
        for lane in CORE:
            write(f"{repo}/attestation/cases/{lane}/one/case.yaml", "id: one\nstatus: ready\n")
        self.bin = f"{tmp}/bin"
        write(f"{self.bin}/gh", GH, True)
        write(f"{self.bin}/docker", "#!/usr/bin/env bash\necho image-1\n", True)
        write(f"{self.bin}/curl", '#!/usr/bin/env bash\necho \'{"balance":1}\'\n', True)
        self.wave_root, self.bundle_root = f"{tmp}/wave", f"{tmp}/bundle"
        self.profile = f"{tmp}/profile"
        write(self.profile, "\n".join(f"{k}={v}" for k, v in dict(
            gateway_url="http://127.0.0.1:1", expected_login=1, expected_server="S", expected_leverage=1, wave_root=self.wave_root,
            bundle_root=self.bundle_root, image_repository="ghcr.io/test/qkt", insights_image="img", magic_base=1000, data_corpus=f"{tmp}/corpus").items()) + "\n")
        write(f"{self.wave_root}/release-0.0.1/qkt-0.0.1/bin/qkt", "#!/usr/bin/env bash\n", True)
        self.git("init", "-q"); self.commit("first")

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", self.repo, *args],
                              check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, message):
        self.git("add", "-A"); self.git("commit", "-qm", message, "--allow-empty")

    @property
    def sha(self):
        return self.git("rev-parse", "HEAD")

    def run(self, behaviours=None, dispatch=False, env_extra=None):
        json.dump(behaviours or {}, open(f"{self.root}/behaviours.json", "w"))
        env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}", STUB_ROOT=self.root, STUB_SHA=self.sha, QKT_BROKER_API_KEY="k",
                   QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", QKT_ATTEST_RETRY_BACKOFF="0,0,0", **(env_extra or {}))
        command = ["bash", f"{self.repo}/{LIVE}/run-attestation.sh", "--profile", self.profile, "--attempts", "1"]
        return subprocess.run(command + (["--dispatch"] if dispatch else []), env=env, capture_output=True, text=True, cwd=self.repo)

    def calls(self):
        path = f"{self.root}/calls.log"
        return open(path).read().splitlines() if os.path.exists(path) else []

    def forget_calls(self):
        if os.path.exists(f"{self.root}/calls.log"):
            os.remove(f"{self.root}/calls.log")

    def stages_called(self):
        return sorted({c.split()[0] for c in self.calls()})

    def bundle(self):
        return f"{self.bundle_root}/build-{self.sha[:8]}/attestation.json"

    def verify(self):
        return subprocess.run([sys.executable, f"{ROOT}/scripts/verify-paper-soak-attestation.py", self.bundle(), "--expected-git-sha", self.sha,
                               "--expected-image-repository", "ghcr.io/test/qkt", "--cases-root", f"{self.repo}/attestation/cases"],
                              capture_output=True, text=True)

    def catalog(self):
        return f"{self.wave_root}/attest-{self.sha[:8]}-catalog"

    def ledger(self):
        return f"{self.wave_root}/attest-{self.sha[:8]}.resume.json"


class ResumeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.h = Harness(self.tmp.name)

    def stopped_on_one_case(self):
        # The stub's plan is by case id, and every lane holds a `one`: `two` is the only case with its own behaviour.
        write(f"{self.h.repo}/attestation/cases/orders/two/case.yaml", "id: two\nstatus: ready\n")
        self.h.commit("two")
        done = self.h.run({"two": ["fail:venue net differs"]})
        self.assertNotEqual(done.returncode, 0)
        return done

    def test_a_straight_run_verifies(self):
        done = self.h.run()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)

    def test_the_stop_names_the_failing_case_and_its_class(self):
        done = self.stopped_on_one_case()
        self.assertIn("orders-two [behaviour] venue net differs", done.stderr)
        status = json.load(open(f"{self.h.wave_root}/attestation-run.json"))
        self.assertIn("orders-two [behaviour]", status["detail"])

    def test_a_resume_reruns_only_the_failed_case_and_its_bundle_verifies(self):
        self.stopped_on_one_case()
        self.h.forget_calls()
        done = self.h.run({"two": ["pass"]})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.h.calls()[:1], ["two orders-two-retry2 magic=1200"], "fresh magic, its own directory")
        self.assertEqual(self.h.stages_called(), ["insights", "two"], "build, proofs and wave were kept; insights never ran before")
        verified = self.h.verify()
        self.assertEqual(verified.returncode, 0, verified.stderr)
        artifacts = json.load(open(self.h.bundle()))["artifacts"]
        evidence = json.load(open(os.path.join(os.path.dirname(self.h.bundle()), artifacts["catalog"])))
        two = next(c for c in evidence["cases"] if c["name"] == "orders-two")
        self.assertTrue(two["flaky"]); self.assertEqual(len(two["attempts"]), 3)
        self.assertEqual({c["reused"] for c in evidence["cases"] if c["name"] != "orders-two"}, {True})

    def test_a_hold_for_a_far_window_is_logged_recorded_and_carried_in_the_bundle(self):
        # A Friday-evening start: the plan holds the whole run until shortly before Monday's window, then plans again.
        write(f"{self.h.repo}/{LIVE}/lib/attest_preflight.py", HOLD_PREFLIGHT)
        self.h.commit("hold preflight")
        clock = f"{self.h.root}/clock"; write(clock, "2026-11-06T21:00:00Z")
        done = self.h.run(env_extra={"QKT_ATTEST_CLOCK_FILE": clock})
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("holding the run until 2026-11-09T13:43:50Z: waiting in the catalog would age the first evidence", done.stdout)
        self.assertIn("session plan: session lanes alpaca first", done.stdout)  # the plan made again after the hold
        self.assertEqual(open(clock).read(), "2026-11-09T13:43:50Z")
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)
        artifacts = json.load(open(self.h.bundle()))["artifacts"]
        evidence = json.load(open(os.path.join(os.path.dirname(self.h.bundle()), artifacts["catalog"])))
        self.assertEqual([(w["lane"], w["plannedStartUtc"], w["recordedAtUtc"]) for w in evidence["sessionWaits"]],
                         [("run", "2026-11-09T13:43:50Z", "2026-11-06T21:00:00Z")])
        # Waiting changes nothing about what must pass: every case still has its own pass on this build.
        self.assertEqual({c["status"] for c in evidence["cases"]}, {"passed"})

    def test_a_new_commit_starts_clean(self):
        self.stopped_on_one_case()
        self.h.commit("new"); self.h.forget_calls()
        done = self.h.run()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.h.stages_called()[:2], ["build", "insights"])
        self.assertIn("proofs", self.h.stages_called()); self.assertIn("wave", self.h.stages_called())
        self.assertEqual(self.h.verify().returncode, 0)

    def rerun_sees_everything_again(self):
        self.h.forget_calls()
        done = self.h.run()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("build", self.h.stages_called())
        self.assertIn("one", self.h.stages_called())
        self.assertEqual(self.h.verify().returncode, 0)

    def test_another_profile_starts_clean(self):
        self.stopped_on_one_case()
        write(self.h.profile, open(self.h.profile).read() + "# edited\n")
        self.rerun_sees_everything_again()

    def test_another_build_starts_clean(self):
        self.stopped_on_one_case()
        open(f"{self.h.wave_root}/cli-{self.h.sha[:8]}/bin/qkt", "a").write("# rebuilt\n")
        self.rerun_sees_everything_again()

    def test_old_case_evidence_reruns_and_old_stages_rerun(self):
        self.stopped_on_one_case()
        old = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=25)).strftime("%Y-%m-%dT%H:%M:%SZ")
        for name in os.listdir(f"{self.h.catalog()}/attempts"):
            path = f"{self.h.catalog()}/attempts/{name}"
            lines = [dict(json.loads(l), finishedAtUtc=old) for l in open(path)]
            open(path, "w").write("".join(json.dumps(l) + "\n" for l in lines))
        ledger = json.load(open(self.h.ledger()))
        ledger["stages"]["proofs"]["completedAtUtc"] = old
        json.dump(ledger, open(self.h.ledger(), "w"))
        self.h.forget_calls()
        done = self.h.run()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("proofs", self.h.stages_called()); self.assertIn("one", self.h.stages_called())
        self.assertNotIn("build", self.h.stages_called()); self.assertNotIn("wave", self.h.stages_called())

    def test_a_tampered_case_result_reruns(self):
        self.stopped_on_one_case()
        path = f"{self.h.catalog()}/risk-one/result.json"
        open(path, "a").write(" ")
        self.h.forget_calls()
        self.h.run()
        self.assertIn("risk-one-retry", "\n".join(self.h.calls()))

    def test_a_damaged_wave_reruns_whole(self):
        self.stopped_on_one_case()
        wave = f"{self.h.wave_root}/attest-{self.h.sha[:8]}"
        open(f"{wave}/cases/a/evidence/result.json", "w").write("{}")
        self.h.forget_calls()
        self.h.run()
        self.assertIn("wave", self.h.stages_called())

    def test_a_dispatched_wave_is_spent(self):
        self.assertEqual(self.h.run(dispatch=True).returncode, 0)
        self.h.forget_calls()
        done = self.h.run()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("wave", self.h.stages_called()); self.assertNotIn("one", self.h.stages_called())

    def test_a_wave_failure_is_named_and_no_partial_wave_is_kept(self):
        write(f"{self.h.root}/wave-fails", "")
        done = self.h.run()
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("HTTP Error 503", done.stderr)

    def test_insights_failing_after_a_full_catalog_keeps_the_whole_catalog(self):
        write(f"{self.h.root}/insights-fails", "")
        self.assertNotEqual(self.h.run().returncode, 0)
        os.remove(f"{self.h.root}/insights-fails")
        self.h.forget_calls()
        self.assertEqual(self.h.run().returncode, 0)
        self.assertEqual(self.h.stages_called(), ["insights"])
        self.assertEqual(self.h.verify().returncode, 0)


class VerifierRefusesTest(unittest.TestCase):
    """The checks a resumed bundle has to survive; each mutation is what a weakened path would produce."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.h = Harness(cls.tmp.name)
        done = cls.h.run()
        assert done.returncode == 0, done.stdout + done.stderr

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def mutated(self, change):
        import hashlib
        h = self.h
        folder = tempfile.mkdtemp(dir=self.tmp.name)
        for name in os.listdir(os.path.dirname(h.bundle())):
            shutil.copy(os.path.join(os.path.dirname(h.bundle()), name), folder)
        att = json.load(open(f"{folder}/attestation.json"))
        path = f"{folder}/{att['artifacts']['catalog']}"
        doc = json.load(open(path)); change(doc)
        json.dump(doc, open(path, "w"))
        att["artifactSha256"]["catalog"] = hashlib.sha256(open(path, "rb").read()).hexdigest()
        json.dump(att, open(f"{folder}/attestation.json", "w"))
        return subprocess.run([sys.executable, f"{ROOT}/scripts/verify-paper-soak-attestation.py", f"{folder}/attestation.json", "--expected-git-sha", h.sha,
                               "--expected-image-repository", "ghcr.io/test/qkt", "--cases-root", f"{h.repo}/attestation/cases"], capture_output=True, text=True)

    def refused(self, change, text):
        done = self.mutated(change)
        self.assertEqual(done.returncode, 1, done.stdout)
        self.assertIn(text, done.stderr)

    def test_the_workflow_path_accepts_a_real_bundle(self):
        """paper-soak.yml's own copy loop, then both verifier calls, then what prepare-main-promotion.sh runs."""
        import re
        h = self.h
        sys.path.insert(0, f"{ROOT}/scripts")
        workflow = open(f"{ROOT}/.github/workflows/paper-soak.yml").read()
        fields = re.search(r"for field in ([a-z ]+); do", workflow).group(1).split()
        source = open(f"{ROOT}/scripts/verify-paper-soak-attestation.py").read()
        required = re.findall(r'"(\w+)"', re.search(r"REQUIRED_ARTIFACTS = \((.*?)\)", source, re.S).group(1))
        self.assertEqual(sorted(fields), sorted(required), "paper-soak.yml must upload every artifact the verifier requires")
        self.assertIn("--cases-root attestation/cases", workflow)
        attestation = json.load(open(h.bundle()))
        evidence = tempfile.mkdtemp(dir=self.tmp.name)
        for field in fields:
            shutil.copy(os.path.join(os.path.dirname(h.bundle()), attestation["artifacts"][field]), evidence)
        shutil.copy(h.bundle(), f"{evidence}/paper-soak-attestation.json")
        verifier = [sys.executable, f"{ROOT}/scripts/verify-paper-soak-attestation.py", "--expected-git-sha", h.sha, "--expected-image-repository", "ghcr.io/test/qkt"]
        for extra in (["--cases-root", f"{h.repo}/attestation/cases"], []):
            done = subprocess.run([*verifier, f"{evidence}/paper-soak-attestation.json", *extra], capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)

    def test_the_unmutated_bundle_passes(self):
        self.assertEqual(self.mutated(lambda d: None).returncode, 0)

    def test_a_missing_required_case(self):
        self.refused(lambda d: d["cases"].pop(), "no pass on this build")

    def test_a_case_from_another_build(self):
        def change(d): d["cases"][0]["bindingSha256"] = "0" * 64
        self.refused(change, "passed on another build")

    def test_another_commit_cli(self):
        def change(d):
            d["binding"]["cliVersion"] = "qkt (ffff)"; d["bindingSha256"] = cases.binding_sha(d["binding"])
        self.refused(change, "not this commit's build")

    def test_a_hidden_failed_attempt(self):
        def change(d): d["cases"][0]["attempts"].insert(0, {"status": "failed"})
        self.refused(change, "hides a failed attempt")

    def test_a_case_ending_on_a_failure(self):
        def change(d): d["cases"][0]["attempts"][-1]["status"] = "failed"
        self.refused(change, "does not end on a passed attempt")

    def test_evidence_past_the_age_limit(self):
        def change(d): d["cases"][0]["passedAtUtc"] = "2020-01-01T00:00:00Z"
        self.refused(change, "old at assembly")

    def test_a_longer_age_limit(self):
        def change(d): d["maxEvidenceAgeHours"] = 9999
        self.refused(change, "24h limit")

    def test_a_dropped_core_lane(self):
        def change(d): d["lanes"].remove("orders")
        self.refused(change, "must cover the lanes")

    def test_another_wave(self):
        def change(d): d["waveRunId"] = "parity-other"
        self.refused(change, "another wave")

    def test_a_missing_stage(self):
        def change(d): del d["stages"]["proofs"]
        self.refused(change, "stage proofs")


if __name__ == "__main__":
    unittest.main()
