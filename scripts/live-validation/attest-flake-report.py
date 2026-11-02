#!/usr/bin/env python3
"""How flaky each attestation case is, from every past attempt on disk. Read-only unless --soak.

    attest-flake-report.py [--root /var/tmp/qkt-validation] [--release-log FILE] [--json]
    attest-flake-report.py --soak N --profile FILE --cli PATH [--dry-run]

The report reads attest-*-catalog*/ under --root: attempts/*.jsonl where a run wrote them, and the older format
(<case>[-retry[N]]/result.json with the catalog's result.json) where it did not. Rates are per attempt: `fail` counts
failed attempts, `retry` counts catalog runs in which the case needed more than one. Reasons are grouped by class
(lib/attest_classify.py). --release-log adds what only a release log kept: runs whose directories were replaced.

--soak N runs the catalog N times against the profile's accounts with one CLI, each into its own
attest-soak-<stamp>-<i>-catalog, then prints the report. It places real demo orders for about an hour a round and holds the
account: run it off-peak, with nothing else on the gateways. QKT_BROKER_API_KEY and QKT_LIVE_DEMO_ORDER_APPROVAL as
for an attestation. --dry-run prints the commands and runs nothing.
"""
import argparse, collections, glob, json, os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import attest_classify as classify  # noqa: E402

RETRY = re.compile(r"-retry\d*$")


def attempts_in(catalog):
    """Every attempt in one catalog directory: (case, status, class, summary)."""
    found, attempts_dir = [], os.path.join(catalog, "attempts")
    recorded = set()
    if os.path.isdir(attempts_dir):
        for path in sorted(glob.glob(f"{attempts_dir}/*.jsonl")):
            name = os.path.basename(path)[:-6]
            recorded.add(name)
            for line in open(path):
                entry = json.loads(line)
                found.append((name, entry["status"], entry.get("class"), entry.get("summary", "")))
    verdicts = {}
    try:
        verdicts = {r["name"]: r for r in json.load(open(f"{catalog}/result.json"))["runs"]}
    except (OSError, ValueError, KeyError):
        pass
    for path in sorted(glob.glob(f"{catalog}/*/result.json")):
        folder = os.path.basename(os.path.dirname(path))
        name = RETRY.sub("", folder)
        if name in recorded:
            continue
        try:
            result = json.load(open(path))
        except ValueError:
            found.append((name, "failed", classify.BEHAVIOUR, "unreadable result.json"))
            continue
        problems = result.get("problems") or []
        status = "passed" if result.get("status") == "passed" else "failed"
        found.append((name, status, None if status == "passed" else classify.classify(problems), "; ".join(problems)))
    seen = {item[0] for item in found}
    for name, verdict in verdicts.items():  # a case that wrote no result of its own, or an attempt only the verdict kept
        if name not in seen:
            status = verdict.get("status")
            found.append((name, status, None if status == "passed" else classify.classify([verdict.get("summary", "")]), verdict.get("summary", "")))
    return found


def collect(root):
    per_case = collections.defaultdict(lambda: dict(attempts=0, passed=0, failed=0, retried_runs=0, runs=0, reasons=collections.Counter()))
    catalogs = sorted(d for d in glob.glob(f"{root}/attest-*-catalog*") if os.path.isdir(d))
    for catalog in catalogs:
        per_run = collections.Counter()
        for name, status, cause, summary in attempts_in(catalog):
            item = per_case[name]
            item["attempts"] += 1
            item["passed" if status == "passed" else "failed"] += 1
            per_run[name] += 1
            if status != "passed":
                item["reasons"][f"{cause}: {summary[:100]}"] += 1
        for name, count in per_run.items():
            per_case[name]["runs"] += 1
            per_case[name]["retried_runs"] += 1 if count > 1 else 0
    return catalogs, per_case


def read_release_log(path):
    runs, stops = [], []
    for line in open(path, errors="replace"):
        match = re.search(r"catalog passed runs=(\d+) passed=(\d+).*retried=(\d+)", line)
        if match:
            runs.append({"status": "passed", "runs": int(match[1]), "passed": int(match[2]), "retried": int(match[3]), "failures": []})
        if "attestation catalog failed:" in line:
            failures = re.findall(r"FAILED (\S+): (.*?)(?=\s+FAILED |$)", line.strip())
            runs.append({"status": "failed", "failures": [{"name": n, "class": classify.classify([w]), "reason": w[:120]} for n, w in failures]})
        if "STOPPED:" in line:
            stops.append(line.strip().split("STOPPED: ", 1)[1])
    return runs, stops


def report(a):
    catalogs, per_case = collect(a.root)
    log_runs, stops = read_release_log(a.release_log) if a.release_log else ([], [])
    if a.json:
        print(json.dumps({"catalogs": catalogs, "cases": {k: {**v, "reasons": dict(v["reasons"])} for k, v in per_case.items()},
                          "releaseLogRuns": log_runs, "stops": stops}, indent=1))
        return 0
    print(f"{len(catalogs)} catalog director(ies) under {a.root}: {' '.join(os.path.basename(c) for c in catalogs)}")
    print(f"{'case':58} {'runs':>4} {'tries':>5} {'pass':>4} {'fail':>4} {'fail%':>6} {'retry%':>6}")
    worst = sorted(per_case.items(), key=lambda kv: (-kv[1]["failed"], kv[0]))
    for name, item in worst:
        print(f"{name:58} {item['runs']:4} {item['attempts']:5} {item['passed']:4} {item['failed']:4} "
              f"{100 * item['failed'] / max(item['attempts'], 1):6.1f} {100 * item['retried_runs'] / max(item['runs'], 1):6.1f}")
    total = sum(i["attempts"] for i in per_case.values()); failed = sum(i["failed"] for i in per_case.values())
    print(f"all cases: {total} attempts, {failed} failed ({100 * failed / max(total, 1):.1f}%)")
    for name, item in worst:
        for reason, count in item["reasons"].items():
            print(f"  {name} x{count} {reason}")
    if a.release_log:
        print(f"release log: {len(log_runs)} catalog runs, {sum(1 for r in log_runs if r['status'] == 'passed')} passed outright")
        for run in log_runs:
            for failure in run["failures"]:
                print(f"  {failure['name']} [{failure['class']}] {failure['reason']}")
        print(f"release log: {len(stops)} stops")
    return 0


def soak(a):
    profile = subprocess.run(["bash", "-c", 'set -a; . "$0"; env -0', a.profile], capture_output=True, text=True, check=True).stdout
    p = dict(item.split("=", 1) for item in profile.split("\0") if "=" in item)
    lanes = "orders,risk,book,engine,daemon,stress"; extra = []
    for lane, url, login, flag in (("derivatives", "deriv_gateway_url", "deriv_expected_login", "deriv"),
                                   ("bybit", "bybit_gateway_url", "bybit_expected_login", "bybit"),
                                   ("alpaca", "alpaca_gateway_url", "alpaca_expected_login", "alpaca")):
        if p.get(url):
            lanes += f",{lane}"
            extra += [f"--{flag}-gateway-url", p[url], f"--{flag}-expected-login", p[login]]
    if p.get("attest_stocks") == "true":
        lanes += ",stocks"
    stamp = time.strftime("%Y%m%d%H%M%S", time.gmtime())
    for round_no in range(1, a.soak + 1):
        command = ["bash", os.path.join(HERE, "run-attestation-catalog.sh"), "--out", f"{p['wave_root']}/attest-soak-{stamp}-{round_no}-catalog",
                   "--gateway-url", p["gateway_url"], "--expected-login", p["expected_login"], "--expected-server", p["expected_server"],
                   "--magic-base", str(int(p["magic_base"]) + 100), "--lanes", lanes, "--cli", a.cli, "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01", *extra]
        print(" ".join(command))
        if not a.dry_run:
            subprocess.run(command)
    a.root = p["wave_root"]
    return 0 if a.dry_run else report(a)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="/var/tmp/qkt-validation"); ap.add_argument("--release-log"); ap.add_argument("--json", action="store_true")
    ap.add_argument("--soak", type=int); ap.add_argument("--profile"); ap.add_argument("--cli"); ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    if a.soak:
        if not (a.profile and a.cli):
            ap.error("--soak needs --profile and --cli")
        return soak(a)
    return report(a)


if __name__ == "__main__":
    sys.exit(main())
