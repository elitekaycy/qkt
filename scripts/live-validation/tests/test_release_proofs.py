"""Release proofs side by side: same report, same order, and no silent pass from a proof that died."""
import json, os, shutil, stat, subprocess, tempfile, time, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.."))


class ReleaseProofsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        t = self.tmp.name
        self.repo = f"{t}/repo"
        os.makedirs(f"{self.repo}/scripts/release-proof"); os.makedirs(f"{self.repo}/tests/data-e2e")
        shutil.copy(f"{ROOT}/scripts/release-proof/run.sh", f"{self.repo}/scripts/release-proof/")
        self.corpus = f"{t}/corpus"; os.makedirs(f"{self.corpus}/tools")
        git = lambda *a: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-C", self.corpus, *a], check=True, capture_output=True, text=True).stdout.strip()
        git("init", "-q")
        self.proof("tools/provenance.py", "corpus", self.corpus)
        git("add", "-A"); git("commit", "-qm", "c")
        open(f"{self.repo}/tests/data-e2e/corpus.lock", "w").write(git("rev-parse", "HEAD") + " x\n")
        for path in ("tests/data-e2e/run.py", "tests/references/run.py", "scripts/release-proof/audit/run.py"):
            self.proof(path, os.path.dirname(path).split("/")[-1])
        self.cli = f"{t}/qkt"
        open(self.cli, "w").write('#!/usr/bin/env bash\necho "qkt x"\n')
        os.chmod(self.cli, os.stat(self.cli).st_mode | stat.S_IEXEC)

    def proof(self, path, label, root=None, body="time.sleep(1); print('ok')"):
        full = os.path.join(root or self.repo, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        open(full, "w").write(f"import time, sys\n{body}\n")

    def run_it(self):
        return subprocess.run(["bash", f"{self.repo}/scripts/release-proof/run.sh", "--cli", self.cli, "--corpus", self.corpus, "--out", f"{self.tmp.name}/out"],
                              capture_output=True, text=True)

    def report(self):
        return json.load(open(f"{self.tmp.name}/out/proofs.json"))

    def test_they_overlap_and_the_report_keeps_its_order(self):
        began = time.monotonic()
        done = self.run_it()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertLess(time.monotonic() - began, 3.0, "three one-second proofs after the corpus check overlapped")
        self.assertEqual([p["proof"] for p in self.report()["proofs"]], ["corpus", "data-e2e", "references", "audit"])
        self.assertTrue(self.report()["passed"])

    def test_one_failed_proof_fails_the_whole(self):
        self.proof("tests/references/run.py", "refs", body="print('bad'); sys.exit(1)")
        self.assertNotEqual(self.run_it().returncode, 0)
        statuses = {p["proof"]: p["status"] for p in self.report()["proofs"]}
        self.assertEqual(statuses["references"], "fail"); self.assertEqual(statuses["audit"], "pass")

    def test_a_proof_that_changes_the_corpus_fails_the_whole(self):
        self.proof("scripts/release-proof/audit/run.py", "audit", body=f"open({self.corpus + '/stray'!r}, 'w').close()")
        self.assertNotEqual(self.run_it().returncode, 0)
        self.assertIn("corpus-unchanged", [p["proof"] for p in self.report()["proofs"]])


if __name__ == "__main__":
    unittest.main()
