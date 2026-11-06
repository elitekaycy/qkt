"""Evidence of an earlier commit stands on a later one only when what ships, the case's own definition, the profile, the
account, the environment and the age all match; anything else reruns. The verifier recomputes every claim from git.

Everything live is stubbed (see test_attest_resume.Harness); the runtime fingerprint, the carry, the manifest and the verifier are real.
"""
import hashlib, json, os, shutil, subprocess, sys, tempfile, unittest, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "../../.."))
sys.path.insert(0, os.path.join(HERE, "../lib"))
sys.path.insert(0, HERE)
import runtime_fingerprint as fp  # noqa: E402
from test_attest_resume import Harness, write, CORE, LIVE  # noqa: E402


class CarryHarness(Harness):
    """A tree the fingerprint accepts (Kotlin sources, a build file, VERSION), a JDK and a declared gateway image."""

    def __init__(self, tmp):
        super().__init__(tmp)
        write(f"{self.repo}/VERSION", "0.0.1\n")
        write(f"{self.repo}/build.gradle.kts", "// build\n")
        write(f"{self.repo}/src/main/kotlin/A.kt", "class A\n")
        write(f"{self.repo}/src/test/kotlin/ATest.kt", "class ATest\n")
        write(f"{self.repo}/docs/index.md", "docs\n")
        write(f"{self.bin}/java", '#!/usr/bin/env bash\necho \'openjdk version "21.0.1"\' >&2\n', True)
        write(self.profile, open(self.profile).read() + 'gateway_images="gw:1"\n')
        self.commit("shippable tree")

    def verify(self, repo_root=True, bundle=None):
        command = [sys.executable, f"{ROOT}/scripts/verify-paper-soak-attestation.py", bundle or self.bundle(), "--expected-git-sha", self.sha,
                   "--expected-image-repository", "ghcr.io/test/qkt", "--cases-root", f"{self.repo}/attestation/cases"]
        return subprocess.run(command + (["--repo-root", self.repo] if repo_root else []), capture_output=True, text=True)

    def ran(self):
        """The cases the stub runner actually ran in the last run, by name."""
        return sorted({c.split()[1].split("-retry")[0] for c in self.calls() if c.startswith("one ")})

    def catalog_evidence(self):
        att = json.load(open(self.bundle()))
        return json.load(open(os.path.join(os.path.dirname(self.bundle()), att["artifacts"]["catalog"])))

    def all_cases(self):
        return sorted(f"{lane}-one" for lane in CORE)


class CarryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.h = CarryHarness(self.tmp.name)
        self.first = self.attested()

    def attested(self, **kwargs):
        done = self.h.run(**kwargs)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        return done

    def next_commit(self, message, files):
        for path, text in files.items():
            write(f"{self.h.repo}/{path}", text)
        self.h.commit(message)
        self.h.forget_calls()

    def test_the_first_commit_runs_everything_and_verifies_with_a_v2_binding(self):
        self.assertEqual(self.h.ran(), self.h.all_cases())
        evidence = self.h.catalog_evidence()
        self.assertEqual(evidence["binding"]["schema"], "qkt-attestation-binding-v2")
        self.assertEqual(evidence["binding"]["runtimeFingerprint"], fp.runtime_fingerprint(self.h.repo, self.h.sha))
        self.assertEqual(evidence["binding"]["carryMode"], "source")
        self.assertEqual(evidence["carry"]["summary"], {"carried": 0, "keptFromThisCommit": 0, "freshlyRun": 6})
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)

    def test_a_v2_bundle_is_refused_without_a_repo_to_check_it_against(self):
        done = self.h.verify(repo_root=False)
        self.assertEqual(done.returncode, 1)
        self.assertIn("can only be verified with --repo-root", done.stderr)

    def test_a_tests_and_docs_only_commit_carries_every_case(self):
        first_sha = self.h.sha
        self.next_commit("tests and docs", {"src/test/kotlin/BTest.kt": "class B\n", "docs/more.md": "more\n"})
        done = self.attested()
        self.assertEqual(self.h.ran(), [], "no case ran again")
        self.assertIn(f"evidence carried from commit {first_sha[:8]} (same runtime fingerprint", done.stdout)
        self.assertIn("orders-one", done.stdout)
        # the wave, the proofs and the insights scenario are never carried
        self.assertTrue({"wave", "proofs", "insights", "build"} <= set(self.h.stages_called()), self.h.stages_called())
        evidence = self.h.catalog_evidence()
        self.assertEqual(evidence["carry"]["summary"], {"carried": 6, "keptFromThisCommit": 0, "freshlyRun": 0})
        self.assertEqual({c["carriedFrom"]["sha"] for c in evidence["cases"]}, {first_sha})
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)

    def test_a_ci_and_script_only_commit_carries_too(self):
        self.next_commit("ci", {".github/workflows/x.yml": "on: push\n", "tests/scripts/t.sh": "true\n"})
        self.attested()
        self.assertEqual(self.h.ran(), [])
        self.assertEqual(self.h.verify().returncode, 0)

    def test_a_runtime_change_reruns_every_case(self):
        self.next_commit("runtime", {"src/main/kotlin/A.kt": "class A { val changed = 1 }\n"})
        done = self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())
        self.assertNotIn("evidence carried", done.stdout)
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)

    def test_a_build_input_change_reruns_every_case(self):
        self.next_commit("dependency bump", {"build.gradle.kts": "// build, new dependency\n"})
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_a_case_definition_change_reruns_only_that_case(self):
        self.next_commit("orders one changed", {"attestation/cases/orders/one/case.yaml": "id: one\nstatus: ready\n# steps changed\n"})
        done = self.attested()
        self.assertEqual(self.h.ran(), ["orders-one"])
        self.assertIn("carried from commit", done.stdout)
        self.assertEqual(self.h.catalog_evidence()["carry"]["summary"], {"carried": 5, "keptFromThisCommit": 0, "freshlyRun": 1})
        self.assertEqual(self.h.verify().returncode, 0, self.h.verify().stderr)

    def test_a_lane_entry_change_reruns_that_lanes_cases(self):
        write(f"{self.h.repo}/attestation/lanes.yaml", "lanes:\n  orders:\n    budget_seconds: 360\n  risk:\n    budget_seconds: 300\n")
        self.h.commit("lanes"); self.attested(); self.h.forget_calls()
        self.next_commit("orders budget", {"attestation/lanes.yaml": "lanes:\n  orders:\n    budget_seconds: 900\n  risk:\n    budget_seconds: 300\n"})
        self.attested()
        self.assertEqual(self.h.ran(), ["orders-one"])

    def test_a_harness_change_reruns_every_case(self):
        self.next_commit("harness", {"scripts/live-validation/run-order-lane-case.sh": 'exec python3 "$(dirname "$0")/run-daemon-lane-case.py" "$@" # edited\n'})
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_another_profile_reruns_everything(self):
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        write(self.h.profile, open(self.h.profile).read() + "# edited\n")
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_another_gateway_image_reruns_everything(self):
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        write(f"{self.h.bin}/docker", "#!/usr/bin/env bash\necho image-2\n", True)
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_another_jdk_reruns_everything(self):
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        write(f"{self.h.bin}/java", '#!/usr/bin/env bash\necho \'openjdk version "25"\' >&2\n', True)
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_evidence_older_than_24_hours_is_not_carried(self):
        import datetime as dt
        old = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=25)).strftime("%Y-%m-%dT%H:%M:%SZ")
        folder = f"{self.h.catalog()}/attempts"
        for name in os.listdir(folder):
            lines = [dict(json.loads(l), finishedAtUtc=old) for l in open(f"{folder}/{name}")]
            open(f"{folder}/{name}", "w").write("".join(json.dumps(l) + "\n" for l in lines))
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        self.attested()
        self.assertEqual(self.h.ran(), self.h.all_cases())

    def test_a_tampered_donor_result_is_not_carried(self):
        open(f"{self.h.catalog()}/risk-one/result.json", "a").write(" ")
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        self.attested()
        self.assertEqual(self.h.ran(), ["risk-one"])

    def test_no_carry_runs_every_case_and_says_so(self):
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        done = self.h.run(env_extra={})  # plain run first would carry; the flag run below must not
        self.assertEqual(done.returncode, 0)
        self.h.commit("again"); self.h.forget_calls()
        command = ["bash", f"{self.h.repo}/{LIVE}/run-attestation.sh", "--profile", self.h.profile, "--attempts", "1", "--no-carry"]
        env = dict(os.environ, PATH=f"{self.h.bin}:{os.environ['PATH']}", STUB_ROOT=self.h.root, STUB_SHA=self.h.sha, QKT_BROKER_API_KEY="k",
                   QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY", QKT_ATTEST_RETRY_BACKOFF="0,0,0")
        done = subprocess.run(command, env=env, capture_output=True, text=True, cwd=self.h.repo)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("carry disabled (--no-carry)", done.stdout)
        self.assertEqual(self.h.ran(), self.h.all_cases())
        evidence = self.h.catalog_evidence()
        self.assertTrue(evidence["carry"]["disabled"])
        self.assertEqual(evidence["carry"]["summary"]["carried"], 0)
        self.assertEqual(self.h.verify().returncode, 0)

    def test_without_a_declared_gateway_image_the_binding_is_the_exact_commit(self):
        write(self.h.profile, open(self.h.profile).read().replace('gateway_images="gw:1"\n', ""))
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        done = self.attested()
        self.assertIn("binding: exact commit", done.stdout)
        self.assertEqual(self.h.ran(), self.h.all_cases())
        evidence = self.h.catalog_evidence()
        self.assertEqual(evidence["binding"]["schema"], "qkt-attestation-binding-v1")
        self.assertEqual(evidence["carry"]["mode"], "exact-commit")
        self.assertEqual(self.h.verify(repo_root=False).returncode, 0)

    def test_a_tree_the_fingerprint_cannot_name_falls_back_to_the_exact_commit(self):
        subprocess.run(["git", "-C", self.h.repo, "rm", "-rq", "src/main"], check=True)
        self.h.commit("no sources"); self.h.forget_calls()
        done = self.attested()
        self.assertIn("binding: exact commit", done.stdout)
        self.assertEqual(self.h.catalog_evidence()["binding"]["schema"], "qkt-attestation-binding-v1")

    def test_a_resume_of_the_same_commit_still_keeps_its_own_cases(self):
        self.next_commit("tests only", {"src/test/kotlin/BTest.kt": "class B\n"})
        self.attested()
        self.h.forget_calls()
        self.attested()
        self.assertEqual(self.h.ran(), [])


class VerifierRefusesCarryTest(unittest.TestCase):
    """What a forged or careless carry would look like; each must be refused by the verifier alone, from git."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.h = CarryHarness(cls.tmp.name)
        assert cls.h.run().returncode == 0
        cls.first = cls.h.sha
        write(f"{cls.h.repo}/docs/more.md", "more\n"); cls.h.commit("docs")
        cls.docs = cls.h.sha
        done = cls.h.run()
        assert done.returncode == 0, done.stdout + done.stderr
        write(f"{cls.h.repo}/src/main/kotlin/A.kt", "class A { val runtime = 2 }\n"); cls.h.commit("runtime")
        cls.changed_runtime = cls.h.sha

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def bundle_for(self, sha):
        return f"{self.h.bundle_root}/build-{sha[:8]}/attestation.json"

    def mutated(self, change, sha):
        folder = tempfile.mkdtemp(dir=self.tmp.name)
        src = os.path.dirname(self.bundle_for(sha))
        for name in os.listdir(src):
            shutil.copy(os.path.join(src, name), folder)
        att = json.load(open(f"{folder}/attestation.json"))
        path = f"{folder}/{att['artifacts']['catalog']}"
        doc = json.load(open(path)); change(doc)
        json.dump(doc, open(path, "w"))
        att["artifactSha256"]["catalog"] = hashlib.sha256(open(path, "rb").read()).hexdigest()
        json.dump(att, open(f"{folder}/attestation.json", "w"))
        return folder

    def verify(self, folder, sha):
        return subprocess.run([sys.executable, f"{ROOT}/scripts/verify-paper-soak-attestation.py", f"{folder}/attestation.json", "--expected-git-sha", sha,
                               "--expected-image-repository", "ghcr.io/test/qkt", "--repo-root", self.h.repo], capture_output=True, text=True)

    def docs_commit(self):
        return self.docs

    def refused(self, change, text, sha=None):
        sha = sha or self.docs_commit()
        done = self.verify(self.mutated(change, sha), sha)
        self.assertEqual(done.returncode, 1, done.stdout)
        self.assertIn(text, done.stderr)

    def test_the_real_carried_bundle_verifies(self):
        sha = self.docs_commit()
        done = self.verify(self.mutated(lambda d: None, sha), sha)
        self.assertEqual(done.returncode, 0, done.stderr)
        evidence = json.load(open(os.path.join(os.path.dirname(self.bundle_for(sha)), "paper-soak-catalog.json")))
        self.assertEqual({c["ranOnSha"] for c in evidence["cases"]}, {self.first})

    def test_a_forged_fingerprint(self):
        def change(d):
            d["binding"]["runtimeFingerprint"] = "0" * 64; d["bindingSha256"] = __import__("attest_cases").binding_sha(d["binding"])
        self.refused(change, "runtime fingerprint mismatch")

    def test_a_case_that_ran_on_a_commit_shipping_something_else(self):
        def change(d):
            d["cases"][0]["ranOnSha"] = self.changed_runtime; d["cases"][0]["carriedFrom"]["sha"] = self.changed_runtime
        self.refused(change, "does not ship the fingerprint")

    def test_a_carry_that_is_not_logged_as_one(self):
        def change(d): d["cases"][0]["carriedFrom"] = None
        self.refused(change, "not log it as carried")

    def test_a_case_with_no_commit(self):
        def change(d): d["cases"][0]["ranOnSha"] = None
        self.refused(change, "does not say which commit it ran on")

    def test_a_changed_definition(self):
        def change(d): d["cases"][0]["definitionSha256"] = "1" * 64
        self.refused(change, "defined differently")

    def test_a_carry_record_that_disagrees(self):
        def change(d): d["cases"][0]["carriedFrom"]["sha"] = "2" * 40
        self.refused(change, "carry record disagrees")

    def test_a_case_keyed_to_another_environment(self):
        def change(d): d["cases"][0]["bindingSha256"] = "3" * 64
        self.refused(change, "another build, profile or account")

    def test_an_unnamed_environment(self):
        def change(d):
            d["binding"]["environment"] = {}; d["bindingSha256"] = __import__("attest_cases").binding_sha(d["binding"])
        self.refused(change, "must name the JDK and the gateway images")

    def test_artifact_mode_needs_an_artifact_hash(self):
        def change(d):
            d["binding"]["carryMode"] = "artifact+source"; d["bindingSha256"] = __import__("attest_cases").binding_sha(d["binding"])
        self.refused(change, "artifactHash")

    def test_evidence_for_another_runtime_is_refused_on_that_commit(self):
        # the carried bundle presented for a commit that ships different sources
        sha = self.docs_commit()
        folder = self.mutated(lambda d: None, sha)
        done = self.verify(folder, self.changed_runtime)
        self.assertEqual(done.returncode, 1)


class FingerprintTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.repo = f"{self.tmp.name}/r"
        for path, text in {"VERSION": "1\n", "build.gradle.kts": "x\n", "src/main/kotlin/A.kt": "a\n", "src/test/kotlin/T.kt": "t\n",
                           "docs/a.md": "d\n", ".github/workflows/c.yml": "c\n", "README.md": "r\n", "scripts/live-validation/run.sh": "h\n",
                           "attestation/cases/orders/one/case.yaml": "id: one\nstatus: ready\n", "scripts/launcher/find-java.sh": "j\n"}.items():
            write(f"{self.repo}/{path}", text)
        self.git("init", "-q"); self.commit("base")

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", self.repo, *args], check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, message):
        self.git("add", "-A"); self.git("commit", "-qm", message)

    def fingerprint(self):
        fp._trees.clear()
        return fp.runtime_fingerprint(self.repo, "HEAD")

    def changes(self, path, text="changed\n"):
        before = self.fingerprint(); write(f"{self.repo}/{path}", text); self.commit("c")
        return self.fingerprint() != before

    def test_files_known_not_to_ship_do_not_change_it(self):
        for path in ("src/test/kotlin/T.kt", "docs/a.md", ".github/workflows/c.yml", "README.md", "scripts/live-validation/run.sh",
                     "attestation/cases/orders/one/case.yaml", "docs/new/page.md", "tests/new.sh"):
            self.assertFalse(self.changes(path), path)

    def test_shipped_files_change_it(self):
        for path in ("src/main/kotlin/A.kt", "src/main/resources/x.properties", "build.gradle.kts", "VERSION", "scripts/launcher/find-java.sh",
                     "gradle/libs.versions.toml", "Dockerfile", "build-logic/src/main/kotlin/x.kts", "examples/bot/agent.py"):
            self.assertTrue(self.changes(path), path)

    def test_a_new_unlisted_file_changes_it(self):
        """A deny-list: a path nobody classified is treated as shipped, so it invalidates evidence."""
        for path in ("brand-new-top-level.txt", "newdir/file.kt", "scripts/brand-new-script.sh", "config-new.yaml", "src/other/x.kt"):
            self.assertTrue(self.changes(path), path)

    def test_a_mode_change_changes_it(self):
        before = self.fingerprint()
        os.chmod(f"{self.repo}/scripts/launcher/find-java.sh", 0o755); self.commit("exec")
        self.assertNotEqual(self.fingerprint(), before)

    def test_it_is_the_same_from_a_clone(self):
        clone = f"{self.tmp.name}/clone"
        subprocess.run(["git", "clone", "-q", "--bare", self.repo, clone], check=True)
        self.assertEqual(fp.runtime_fingerprint(clone, "HEAD"), self.fingerprint())

    def test_what_it_cannot_name_is_none(self):
        self.assertIsNone(fp.runtime_fingerprint(self.repo, "0" * 40))
        self.assertIsNone(fp.runtime_fingerprint(self.tmp.name, "HEAD"))
        self.git("rm", "-rq", "src/main"); self.commit("no kotlin")
        self.assertIsNone(self.fingerprint())

    def test_a_case_definition_follows_its_files_lane_and_harness(self):
        write(f"{self.repo}/attestation/lanes.yaml", "lanes:\n  orders:\n    budget_seconds: 1\n  risk:\n    budget_seconds: 2\n"); self.commit("lanes")
        def definition(): fp._trees.clear(); return fp.definition_sha(self.repo, "HEAD", "orders-one")
        base = definition(); self.assertIsNotNone(base)
        write(f"{self.repo}/attestation/lanes.yaml", "lanes:\n  orders:\n    budget_seconds: 1\n  risk:\n    budget_seconds: 9\n"); self.commit("risk lane")
        self.assertEqual(definition(), base, "another lane's entry is not this case's")
        write(f"{self.repo}/attestation/lanes.yaml", "lanes:\n  orders:\n    budget_seconds: 5\n  risk:\n    budget_seconds: 9\n"); self.commit("orders lane")
        self.assertNotEqual(definition(), base)
        base = definition()
        write(f"{self.repo}/scripts/live-validation/run.sh", "edited\n"); self.commit("harness")
        self.assertNotEqual(definition(), base)
        base = definition()
        write(f"{self.repo}/scripts/live-validation/tests/t.py", "x\n"); self.commit("harness tests")
        self.assertEqual(definition(), base, "the harness's own tests are not part of what a case runs")
        self.assertIsNone(fp.definition_sha(self.repo, "HEAD", "orders-missing"))


class ArtifactHashTest(unittest.TestCase):
    def jar(self, path, entries, stamp):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with zipfile.ZipFile(path, "w") as z:
            for name, data in entries:
                z.writestr(zipfile.ZipInfo(name, date_time=stamp), data)

    def tree(self, root, entries, stamp=(2026, 1, 1, 0, 0, 0), script="#!/bin/sh\n"):
        shutil.rmtree(root, ignore_errors=True)
        self.jar(f"{root}/lib/qkt.jar", entries, stamp)
        write(f"{root}/bin/qkt", script)

    def test_time_and_commit_do_not_matter_but_code_does(self):
        with tempfile.TemporaryDirectory() as tmp:
            code = [("com/qkt/A.class", b"\xca\xfe")]
            a = f"{tmp}/a"; self.tree(a, code + [("build-info.properties", b"gitSha=1\nbuildTimestamp=1\n")])
            b = f"{tmp}/b"; self.tree(b, [("build-info.properties", b"gitSha=2\nbuildTimestamp=2\n")] + code, stamp=(2026, 6, 1, 1, 1, 2))
            c = f"{tmp}/c"; self.tree(c, [("com/qkt/A.class", b"\xca\xff"), ("build-info.properties", b"gitSha=1\n")])
            d = f"{tmp}/d"; self.tree(d, code, script="#!/bin/sh\n# other launcher\n")
            self.assertEqual(fp.artifact_hash(a), fp.artifact_hash(b))
            self.assertNotEqual(fp.artifact_hash(a), fp.artifact_hash(c))
            self.assertNotEqual(fp.artifact_hash(a), fp.artifact_hash(d))
            self.assertNotEqual(fp.artifact_hash(a), "")

    def test_no_jar_means_no_artifact_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            write(f"{tmp}/bin/qkt", "x")
            self.assertEqual(fp.artifact_hash(tmp), "")
            self.assertEqual(fp.artifact_hash(f"{tmp}/missing"), "")


if __name__ == "__main__":
    unittest.main()
