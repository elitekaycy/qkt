"""Runs the real-data end-to-end suite: real vendor files through the installed `qkt`, checked against
independent readers and the vendors' own data. See docs/superpowers/specs/2026-10-18-data-e2e-suite-design.md.

    python3 -I tests/data-e2e/run.py --tier pr                 # what CI runs on a pull request
    python3 -I tests/data-e2e/run.py --tier full               # every case (the testing branch)
    python3 -I tests/data-e2e/run.py --case <id> --update      # rewrite a case's golden fingerprint

The corpus is the private qkt-data-corpus repository at the commit in corpus.lock (--corpus, or
QKT_DATA_CORPUS). Exit 0 only when every selected case passes.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import tomllib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "lib"))

import checks  # noqa: E402

REPO = HERE.parent.parent
GOLDEN = HERE / "golden"
VOLATILE = {"lastUpdated", "importedAt", "qkt"}  # manifest and provenance fields that name when/which build


class Failure(Exception):
    pass


def fingerprint(root: Path) -> dict[str, str]:
    """sha256 of every stored file, with the fields that name a time or a build removed from JSON files."""
    out = {}
    for p in sorted(root.rglob("*")):
        if not p.is_file() or p.name.endswith((".lock", ".partial", ".tmp")):
            continue
        rel = p.relative_to(root).as_posix()
        # A replaced day's backup folder is named by the time of the import: keep the file, drop the time.
        rel = re.sub(r"\.import-backup/[^/]+/", ".import-backup/<time>/", rel)
        data = p.read_bytes()
        if p.suffix == ".json":
            data = json.dumps(strip(json.loads(data)), sort_keys=True).encode()
        out[rel] = hashlib.sha256(data).hexdigest()
    return out


def strip(value):
    if isinstance(value, dict):
        return {k: strip(v) for k, v in value.items() if k not in VOLATILE}
    if isinstance(value, list):
        return [strip(v) for v in value]
    return value


class Case:
    def __init__(self, spec: dict, ctx: dict):
        self.spec = spec
        self.id = spec["id"]
        self.ctx = ctx
        self.work = Path(tempfile.mkdtemp(prefix=f"qkt-e2e-{self.id}-", dir=ctx["work"]))
        self.root = self.work / "root"
        self.vars = {"root": str(self.root), "work": str(self.work), "corpus": str(ctx["corpus"]), "samples": str(ctx["samples"])}
        (self.work / "home").mkdir()
        self.log: list[str] = []

    def sub(self, text: str) -> str:
        return text.format(**self.vars)

    def qkt(self, args: list[str], env: dict | None = None, cwd: str | None = None) -> subprocess.CompletedProcess:
        # Hermetic: a private home and config folder, so nothing on the machine (~/.qkt, ~/.config/qkt) reaches a run.
        full_env = {k: v for k, v in os.environ.items() if k not in ("QKT_DATA_HOME", "QKT_CONFIG", "JAVA_OPTS", "QKT_OPTS")}
        full_env["JAVA_OPTS"] = f"-Duser.home={self.work / 'home'}"
        full_env["XDG_CONFIG_HOME"] = str(self.work / "home" / ".config")
        full_env.update({k: self.sub(v) for k, v in (env or {}).items()})
        cmd = [self.ctx["qkt"], *[self.sub(a) for a in args]]
        started = time.monotonic()
        proc = subprocess.run(cmd, capture_output=True, text=True, env=full_env, cwd=self.sub(cwd) if cwd else str(self.work))
        self.log.append(f"$ qkt {' '.join(cmd[1:])}  [{proc.returncode}, {time.monotonic() - started:.1f}s]")
        self.log.extend("  " + line for line in (proc.stdout + proc.stderr).splitlines()[:60])
        return proc

    def step(self, s: dict) -> None:
        for path, body in s.get("files", {}).items():
            target = Path(self.sub(path))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(self.sub(body))
        for d in s.get("dirs", []):
            Path(self.sub(d)).mkdir(parents=True, exist_ok=True)
        for path in s.get("remove", []):
            target = Path(self.sub(path))
            if not target.exists():
                raise Failure(f"cannot remove {path}: it does not exist")
            shutil.rmtree(target) if target.is_dir() else target.unlink()
        for path, data in s.get("append", {}).items():
            with open(self.sub(path), "ab") as f:
                f.write(data.encode())
        if "run" not in s:
            return
        proc = self.qkt(s["run"], s.get("env"), s.get("cwd"))
        out = proc.stdout + proc.stderr
        want = s.get("exit", 0)
        if proc.returncode != want:
            raise Failure(f"`qkt {' '.join(s['run'])}` exited {proc.returncode}, expected {want}")
        for text in s.get("contains", []):
            if self.sub(text) not in out:
                raise Failure(f"`qkt {' '.join(s['run'])}` output lacks: {text!r}")
        for text in s.get("lacks", []):
            if self.sub(text) in out:
                raise Failure(f"`qkt {' '.join(s['run'])}` output has: {text!r}")
        if s.get("writes_nothing") and self.root.exists() and any(p.is_file() for p in self.root.rglob("*") if not p.name.endswith(".lock")):
            raise Failure(f"`qkt {' '.join(s['run'])}` refused but wrote into the store")
        if "stdout_to" in s:
            Path(self.sub(s["stdout_to"])).write_text(proc.stdout)
        if s.get("again_noop"):
            before = fingerprint(self.root)
            again = self.qkt(s["run"], s.get("env"), s.get("cwd"))
            if again.returncode != 0 or fingerprint(self.root) != before:
                raise Failure(f"importing again changed the store (exit {again.returncode})")

    def run(self) -> dict:
        started = time.monotonic()
        try:
            for s in self.spec.get("step", []):
                self.step(s)
            results = []
            for c in self.spec.get("check", []):
                results.append(checks.run(c, self))
            got = fingerprint(self.root) if self.root.exists() else {}
            golden = GOLDEN / f"{self.id}.json"
            if self.spec.get("golden", True):
                if self.ctx["update"]:
                    golden.write_text(json.dumps(got, indent=1, sort_keys=True) + "\n")
                elif not golden.is_file():
                    raise Failure(f"no golden fingerprint: run with --case {self.id} --update and review it")
                else:
                    want = json.loads(golden.read_text())
                    if got != want:
                        changed = sorted(k for k in set(got) | set(want) if got.get(k) != want.get(k))
                        raise Failure("stored files differ from the golden fingerprint: " + ", ".join(changed[:8]))
            return {"id": self.id, "ok": True, "seconds": round(time.monotonic() - started, 1), "checks": results}
        except (Failure, checks.CheckFailure) as e:
            return {"id": self.id, "ok": False, "seconds": round(time.monotonic() - started, 1), "error": str(e), "log": self.log[-80:]}
        finally:
            if not self.ctx["keep"]:
                shutil.rmtree(self.work, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qkt", default=str(REPO / "build/install/qkt/bin/qkt"))
    ap.add_argument("--corpus", default=os.environ.get("QKT_DATA_CORPUS", str(REPO.parent / "qkt-data-corpus")))
    ap.add_argument("--tier", choices=["pr", "full"], default="full")
    ap.add_argument("--case", action="append", help="run only these case ids")
    ap.add_argument("--update", action="store_true", help="rewrite golden fingerprints of the cases run")
    ap.add_argument("--keep", action="store_true", help="keep each case's work folder")
    ap.add_argument("--work", default=None)
    ap.add_argument("--report", default=None, help="write the results as JSON here")
    args = ap.parse_args()

    corpus = Path(args.corpus).resolve()
    lock = (HERE / "corpus.lock").read_text().split()[0]
    head = subprocess.run(["git", "-C", str(corpus), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    if head != lock:
        print(f"data-e2e: corpus {corpus} is at {head or 'no commit'}, corpus.lock pins {lock}", file=sys.stderr)
        return 2
    if not Path(args.qkt).is_file():
        print(f"data-e2e: no qkt at {args.qkt} (./gradlew installDist)", file=sys.stderr)
        return 2
    work = Path(args.work or tempfile.mkdtemp(prefix="qkt-data-e2e-"))
    work.mkdir(parents=True, exist_ok=True)
    ctx = {
        "qkt": str(Path(args.qkt).resolve()),
        "corpus": corpus,
        "samples": REPO / "src/test/resources/com/qkt/marketdata/ingest/samples",
        "work": work,
        "update": args.update,
        "keep": args.keep,
    }
    specs = tomllib.loads((HERE / "cases.toml").read_text())["case"]
    ids = [s["id"] for s in specs]
    if len(set(ids)) != len(ids):
        print("data-e2e: duplicate case ids", file=sys.stderr)
        return 2
    if args.case:
        unknown = set(args.case) - set(ids)
        if unknown:
            print(f"data-e2e: unknown case(s) {sorted(unknown)}", file=sys.stderr)
            return 2
        specs = [s for s in specs if s["id"] in args.case]
    else:
        specs = [s for s in specs if args.tier == "full" or s.get("tier", "full") == "pr"]

    results = []
    for spec in specs:
        r = Case(spec, ctx).run()
        results.append(r)
        mark = "ok  " if r["ok"] else "FAIL"
        detail = "; ".join(c for c in r.get("checks", []) if c) if r["ok"] else r["error"]
        print(f"{mark} {r['id']:44} {r['seconds']:6.1f}s  {detail}", flush=True)
        if not r["ok"]:
            for line in r["log"]:
                print("       " + line)
    failed = [r for r in results if not r["ok"]]
    print(f"data-e2e: {len(results) - len(failed)}/{len(results)} cases passed (corpus {lock[:12]})")
    if args.report:
        Path(args.report).write_text(json.dumps({"corpus": lock, "tier": args.tier, "results": results}, indent=1) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
