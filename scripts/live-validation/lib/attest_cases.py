#!/usr/bin/env python3
"""The attestation catalog's memory: one record per attempt of every case, in OUT/attempts/<name>.jsonl.

A case's verdict is read from its records and nothing else: a case with no record never ran and fails, a retry
that passed stays visible with every failure before it, and a resumed run keeps a passed case only while its
record is bound to this exact build (see `reusable`).

    attest_cases.py record --out DIR --name N --dir CASE_OUT --log FILE --exit CODE --started ISO
                           [--no-retry] [--require-result] [--session-closed [--reason TEXT]]   prints: status class action delay
    attest_cases.py suffix --out DIR --name N                                   prints the next attempt's dir suffix
    attest_cases.py reusable --out DIR --binding FILE [--max-age-hours H]       prints the names to keep
    attest_cases.py finalize --out DIR --expected FILE --started ISO --cli TEXT --max-parallel N [--seconds N]
    attest_cases.py failures --result FILE                                      prints the failed cases, one line
"""
import argparse, datetime as dt, hashlib, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import attest_classify as classify  # noqa: E402
import runtime_fingerprint as fingerprint  # noqa: E402

MAX_EVIDENCE_AGE_HOURS = 24  # the verifier's limit too (scripts/verify-paper-soak-attestation.py)
NEVER_REUSED = {"shadow"}  # one daemon's session is the evidence of all its cases: it reruns whole, never in part


def utc_now():
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc(text):
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def sha256_file(path):
    with open(path, "rb") as stream:
        return hashlib.sha256(stream.read()).hexdigest()


def binding_sha(binding):
    return hashlib.sha256(json.dumps(binding, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def evidence_key_sha(binding):
    """What a case record's bindingSha256 is: see runtime_fingerprint.evidence_key_sha."""
    return fingerprint.evidence_key_sha(binding)


def attempts_of(out, name):
    path = os.path.join(out, "attempts", f"{name}.jsonl")
    if not os.path.exists(path):
        return []
    with open(path) as stream:
        return [json.loads(line) for line in stream if line.strip()]


def current_run(out):
    path = os.path.join(out, "runs")
    return int(open(path).read().strip() or 1) if os.path.exists(path) else 1


def read_result(case_dir):
    try:
        with open(os.path.join(case_dir, "result.json")) as stream:
            return json.load(stream)
    except (OSError, ValueError):
        return {}


def venue_evidence(result):
    """What the attempt recorded of its venue: the waits before it started, the outage sampled during it, the earlier
    runs' leftovers it swept first. Kept in the attempt's ledger entry and the bundle's catalog evidence."""
    keys = {"waits": "venueWaits", "outage": "venueOutage", "leftoversSwept": "leftoversSwept",
            "sessionClosed": "sessionClosed"}
    return {k: result[v] for k, v in keys.items() if result.get(v)}


def read_problems(case_dir):
    path = os.path.join(case_dir, "result.json")
    try:
        with open(path) as stream:
            result = json.load(stream)
        return result.get("status"), result.get("problems") or []
    except (OSError, ValueError):
        return None, []


def log_tail(path, lines=40):
    try:
        with open(path, errors="replace") as stream:
            return [line.rstrip("\n") for line in stream.readlines()[-lines:]]
    except OSError:
        return []


def record(a):
    status_in_result, problems = read_problems(a.dir)
    tail = log_tail(a.log)
    if a.session_closed:
        status, cause, summary = "failed", classify.SESSION, a.reason or "the market closed before this case could run"
    else:
        wrote_none = a.require_result and status_in_result is None
        passed = a.exit == 0 and status_in_result in (None, "passed") and not wrote_none
        status = "passed" if passed else "failed"
        if wrote_none:
            problems = problems or ["the case wrote no result"]
        cause = None if passed else (classify.venue_class(read_result(a.dir), problems, tail)
                                     or classify.classify(problems, tail))
        summary = "; ".join(problems) if problems and not passed else (tail[-1] if tail else "")
    run = current_run(a.out)
    earlier = attempts_of(a.out, a.name)
    this_run = [e["class"] for e in earlier if e["run"] == run and e["status"] != "passed"]
    retry, delay = (False, 0) if status == "passed" or a.no_retry else classify.next_step(cause, this_run)
    result_path = os.path.join(a.dir, "result.json")
    entry = {"attempt": len(earlier) + 1, "run": run, "status": status, "class": cause, "summary": summary[:600],
             "dir": os.path.basename(a.dir), "startedAtUtc": a.started, "finishedAtUtc": utc_now(),
             "resultSha256": sha256_file(result_path) if os.path.exists(result_path) else None}
    binding_path = os.path.join(a.out, "binding.json")
    if os.path.exists(binding_path):
        binding = json.load(open(binding_path))
        entry["bindingSha256"] = evidence_key_sha(binding)
        if binding.get("schema") == fingerprint.BINDING_V2:
            # What ran, and on which commit: the verifier recomputes both from git, so a carry is never self-declared.
            repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../.."))
            entry["ranOnSha"] = binding.get("testingSha")
            entry["definitionSha256"] = fingerprint.definition_sha(repo, binding.get("testingSha"), a.name)
    venue = venue_evidence(read_result(a.dir))
    if venue:
        entry["venue"] = venue
    if retry:
        entry["retryAfterSeconds"] = delay
    os.makedirs(os.path.join(a.out, "attempts"), exist_ok=True)
    with open(os.path.join(a.out, "attempts", f"{a.name}.jsonl"), "a") as stream:
        stream.write(json.dumps(entry) + "\n")
    print(status, cause or "-", "retry" if retry else "final", delay)


def suffix(a):
    """The next attempt's directory: NAME, NAME-retry, NAME-retry2..., past any a killed run left unrecorded."""
    n = len(attempts_of(a.out, a.name))
    while True:
        tail = "" if n == 0 else "-retry" if n == 1 else f"-retry{n}"
        if not os.path.exists(os.path.join(a.out, a.name + tail)):
            print(tail)
            return
        n += 1


def is_reusable(out, name, binding, now, max_age_hours=MAX_EVIDENCE_AGE_HOURS):
    """Why a passed case may stand on a resumed run: bound to this build, young enough, its result unchanged."""
    if name.split("-")[0] in NEVER_REUSED or binding is None:
        return False
    attempts = attempts_of(out, name)
    if not attempts or attempts[-1]["status"] != "passed":
        return False
    last = attempts[-1]
    result = os.path.join(out, last["dir"], "result.json")
    if last.get("bindingSha256") != evidence_key_sha(binding) or not os.path.exists(result):
        return False
    if sha256_file(result) != last.get("resultSha256") or read_problems(os.path.dirname(result))[0] != "passed":
        return False
    return now - parse_utc(last["finishedAtUtc"]) <= dt.timedelta(hours=max_age_hours)


def reusable(a):
    binding = json.load(open(a.binding))
    now = dt.datetime.now(dt.timezone.utc)
    folder = os.path.join(a.out, "attempts")
    for entry in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        name = entry.removesuffix(".jsonl")
        if is_reusable(a.out, name, binding, now, a.max_age_hours):
            print(name)


def verdict(out, name, run):
    attempts = attempts_of(out, name)
    if not attempts:
        return {"name": name, "status": "failed", "class": classify.ENVIRONMENT, "summary": "the case never ran", "attempts": []}
    last = attempts[-1]
    item = {"name": name, "status": last["status"], "summary": last["summary"], "attempts": attempts}
    if last["status"] != "passed":
        item["class"] = last["class"]
    if len(attempts) > 1:
        item["retried"] = True
        item["firstAttempt"] = attempts[0]["summary"]
    if last["status"] == "passed" and any(e["status"] != "passed" for e in attempts):
        item["flaky"] = True
    if last["run"] < run:
        item["reused"] = True
    return item


def finalize(a):
    names = [n for n in open(a.expected).read().split() if n]
    run = current_run(a.out)
    runs = [verdict(a.out, name, run) for name in names]
    shadow = os.path.join(a.out, "shadow", "result.json")
    capabilities = json.load(open(shadow)).get("capabilities", []) if os.path.exists(shadow) else []
    result = {"schema": "qkt-attestation-catalog-run-v1", "startedAtUtc": a.started, "finishedAtUtc": utc_now(),
              "wallClockSeconds": a.seconds, "cli": a.cli, "maxParallel": a.max_parallel, "run": run, "runs": runs,
              "capabilitiesProvenLive": len(capabilities), "capabilities": capabilities,
              "retried": [r["name"] for r in runs if r.get("retried")],
              "flaky": [r["name"] for r in runs if r.get("flaky")],
              "reused": [r["name"] for r in runs if r.get("reused")],
              "status": "passed" if all(r["status"] == "passed" for r in runs) else "failed"}
    json.dump(result, open(os.path.join(a.out, "result.json"), "w"), indent=1)
    passed = [r for r in runs if r["status"] == "passed"]
    print(f"{result['status']} runs={len(runs)} passed={len(passed)} capabilities={len(capabilities)} "
          f"retried={len(result['retried'])} reused={len(result['reused'])} wallClock={a.seconds}s")
    for item in runs:
        if item.get("retried"):
            print(f"  RETRIED {item['name']}: first attempt: {item['firstAttempt']}")
    for item in runs:
        for e in item["attempts"]:
            venue = e.get("venue") or {}
            for wait in venue.get("waits") or []:
                print(f"  VENUE-WAIT {item['name']} attempt {e['attempt']}: {wait.get('venue')} unavailable "
                      f"{wait.get('seconds')}s from {wait.get('from')} ({wait.get('reason')})"
                      + (" - past the maximum" if wait.get("exceeded") else ""))
            if venue.get("outage") and e.get("class") == classify.VENUE_OUTAGE:
                o = venue["outage"]
                print(f"  VENUE-OUTAGE {item['name']} attempt {e['attempt']}: {o.get('unavailableSamples')} of "
                      f"{o.get('samplesTaken')} samples unavailable {o.get('from')}..{o.get('to')} ({o.get('reason')}); "
                      "rerun without spending an attempt")
            if venue.get("leftoversSwept"):
                print(f"  SWEPT {item['name']} attempt {e['attempt']}: {json.dumps(venue['leftoversSwept'])[:300]}")
    for item in runs:
        if item["status"] != "passed":
            print(f"  FAILED {item['name']} [{item.get('class')}]: {item['summary']}")
    return 0 if result["status"] == "passed" else 1


def failures(a):
    result = json.load(open(a.result))
    bad = [r for r in result["runs"] if r["status"] != "passed"]
    print(f"{len(bad)} of {len(result['runs'])} case(s) failed: " +
          "; ".join(f"{r['name']} [{r.get('class')}] {r['summary'][:140]}" for r in bad))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("record")
    for flag in ("--out", "--name", "--dir", "--log", "--started"):
        p.add_argument(flag, required=True)
    p.add_argument("--exit", type=int, required=True)
    for flag in ("--no-retry", "--require-result", "--session-closed"):
        p.add_argument(flag, action="store_true")
    p.add_argument("--reason", help="with --session-closed: why the case could not run in its window")
    p = sub.add_parser("suffix"); p.add_argument("--out", required=True); p.add_argument("--name", required=True)
    p = sub.add_parser("reusable"); p.add_argument("--out", required=True); p.add_argument("--binding", required=True)
    p.add_argument("--max-age-hours", type=float, default=MAX_EVIDENCE_AGE_HOURS)
    p = sub.add_parser("finalize")
    for flag in ("--out", "--expected", "--started", "--cli"):
        p.add_argument(flag, required=True)
    p.add_argument("--max-parallel", type=int, required=True); p.add_argument("--seconds", type=int, default=0)
    p = sub.add_parser("failures"); p.add_argument("--result", required=True)
    a = ap.parse_args()
    return {"record": record, "suffix": suffix, "reusable": reusable, "finalize": finalize, "failures": failures}[a.command](a) or 0


if __name__ == "__main__":
    sys.exit(main())
