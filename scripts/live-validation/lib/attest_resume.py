#!/usr/bin/env python3
"""What a rerun of the same commit may keep, and the manifest that proves the result still covers everything.

Evidence is bound to the full commit, the profile file's hash, the account and the fingerprint of the built CLI. Anything
else, or anything older than MAX_EVIDENCE_AGE_HOURS, is not reused; a different binding starts clean. What a resumed run
always repeats: preflight, sweep, every case without a passed record, bundle and verify. Never reused: a partial wave (its
phases and shadow lane share one session, so it is whole or nothing), a wave already dispatched (paper-soak spends its
runId), the shadow lane in a catalog (one daemon's session, see attest_cases.NEVER_REUSED).

    attest_resume.py plan --ledger F --sha S --profile F --login L --server S --cli-root D --cli-version TEXT
                          --proofs D --wave D --insights D --catalog D --lanes CSV --repo-root D
                          [--previous-tag T] [--corpus-lock C] [--image-id I] --out F
    attest_resume.py begin --ledger F --binding F
    attest_resume.py stage --ledger F --name proofs|wave|insights --evidence FILE [--meta K=V ...] [--attempts FILE]
    attest_resume.py spend-wave --ledger F
    attest_resume.py manifest --ledger F --catalog D --lanes CSV --repo-root D --run-id ID [--waits FILE ...] --out F
"""
import argparse, datetime as dt, hashlib, json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import attest_cases as cases  # noqa: E402
import attest_session as session  # noqa: E402

MAX_EVIDENCE_AGE_HOURS = cases.MAX_EVIDENCE_AGE_HOURS


def fingerprint_tree(root):
    digest = hashlib.sha256()
    for folder, dirs, files in sorted(os.walk(root)):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(folder, name)
            digest.update(os.path.relpath(path, root).encode() + b"\0" + cases.sha256_file(path).encode())
    return digest.hexdigest()


def make_binding(sha, profile, login, server, cli_root, cli_version):
    return {"schema": "qkt-attestation-binding-v1", "testingSha": sha, "profileSha256": cases.sha256_file(profile),
            "account": {"login": str(login), "server": server}, "cliFingerprint": fingerprint_tree(cli_root),
            "cliVersion": cli_version}


def load(path):
    try:
        with open(path) as stream:
            return json.load(stream)
    except (OSError, ValueError):
        return None


def write(path, doc):
    temporary = f"{path}.tmp"
    with open(temporary, "w") as stream:
        json.dump(doc, stream, indent=1)
    os.replace(temporary, path)


def ready_cases(repo_root, lanes):
    """Every case this catalog must have passed, as `<lane>-<id>`: what the verifier recomputes from the tree."""
    names = []
    for lane in lanes:
        folder = os.path.join(repo_root, "attestation", "cases", lane)
        for case_id in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
            path = os.path.join(folder, case_id, "case.yaml")
            if os.path.exists(path) and re.search(r"^status:\s*ready\b", open(path).read(), re.M):
                names.append(f"{lane}-{case_id}")
    return names


def young(stage, now):
    return now - cases.parse_utc(stage["completedAtUtc"]) <= dt.timedelta(hours=MAX_EVIDENCE_AGE_HOURS)


def sums_hold(wave):
    """Every file the wave sealed is still what it sealed: a truncated evidence file is not evidence."""
    path = os.path.join(wave, "SHA256SUMS")
    if not os.path.exists(path):
        return False
    with open(path) as stream:
        for line in stream:
            digest, _, name = line.rstrip("\n").partition("  ")
            target = os.path.join(wave, name)
            if not os.path.isfile(target) or cases.sha256_file(target) != digest:
                return False
    return True


def stage_holds(ledger, name, evidence, now, meta):
    stage = (ledger.get("stages") or {}).get(name)
    if not stage or stage.get("status") != "passed" or not young(stage, now):
        return False
    if not evidence or not os.path.isfile(evidence) or cases.sha256_file(evidence) != stage.get("evidenceSha256"):
        return False
    return all((stage.get("meta") or {}).get(k) == v for k, v in meta.items())


def ledger_attempt(entry):
    """An attempt as the bundle shows it, with its venue evidence (waits, a sampled outage, leftovers swept) when any."""
    item = {k: entry.get(k) for k in ("attempt", "run", "status", "class", "summary", "finishedAtUtc")}
    if entry.get("venue"):
        item["venue"] = entry["venue"]
    return item


def insights_result(folder):
    for path in (f"{folder}/result.json", f"{folder}/evidence/result.json"):
        if os.path.exists(path):
            return path
    return None


def plan(a):
    now = dt.datetime.now(dt.timezone.utc)
    lanes = a.lanes.split(",")
    ledger = load(a.ledger)
    required = ready_cases(a.repo_root, lanes)
    fresh = {"fresh": True, "reuseCli": False, "reuseProofs": False, "reuseWave": False, "reuseInsights": False,
             "reusableCases": [], "pendingCases": required, "leadSeconds": sum(session.STAGE_SECONDS.values())}
    def out(doc, reason):
        write(a.out, {**doc, "reason": reason})
        print(reason)
    if ledger is None:
        return out(fresh, "no earlier evidence for this commit: a full run")
    if not os.path.isdir(a.cli_root) or a.sha not in a.cli_version:
        return out(fresh, "the CLI of the earlier run is gone or from another commit: a full run")
    binding = make_binding(a.sha, a.profile, a.login, a.server, a.cli_root, a.cli_version)
    if ledger.get("binding") != binding:
        differ = [k for k in binding if (ledger.get("binding") or {}).get(k) != binding[k]]
        return out(fresh, f"earlier evidence is bound to something else ({', '.join(differ)}): a full run")
    proofs = stage_holds(ledger, "proofs", os.path.join(a.proofs, "proofs.json"), now,
                         {"previousTag": a.previous_tag, "corpusLock": a.corpus_lock})
    stage = (ledger.get("stages") or {}).get("wave") or {}
    wave = stage_holds(ledger, "wave", os.path.join(a.wave, "SHA256SUMS"), now, {}) and not stage.get("spent") and sums_hold(a.wave)
    insights = stage_holds(ledger, "insights", insights_result(a.insights), now, {"imageId": a.image_id})
    keep = [n for n in required if cases.is_reusable(a.catalog, n, binding, now)]
    lead = (0 if proofs else session.STAGE_SECONDS["proofs"]) + (0 if wave else session.STAGE_SECONDS["wave"])
    pending = [n for n in required if n not in keep]
    doc = {"fresh": False, "reuseCli": True, "reuseProofs": proofs, "reuseWave": wave, "reuseInsights": insights,
           "reusableCases": keep, "pendingCases": pending, "leadSeconds": lead, "binding": binding}
    out(doc, f"resuming {a.sha[:8]}: proofs {'kept' if proofs else 'rerun'}, wave {'kept' if wave else 'rerun'}, insights "
             f"{'kept' if insights else 'rerun'}, {len(keep)}/{len(required)} cases kept; rerunning: {' '.join(pending) or 'no case'}")


def begin(a):
    write(a.ledger, {"schema": "qkt-attestation-ledger-v1", "binding": load(a.binding), "createdAtUtc": cases.utc_now(), "stages": {}})


def stage(a):
    ledger = load(a.ledger)
    entry = {"status": "passed", "completedAtUtc": cases.utc_now(), "evidenceSha256": cases.sha256_file(a.evidence),
             "meta": dict(item.split("=", 1) for item in a.meta)}
    if a.attempts and os.path.exists(a.attempts):
        entry["attempts"] = [json.loads(line) for line in open(a.attempts) if line.strip()]
    ledger["stages"][a.name] = entry
    write(a.ledger, ledger)


def spend_wave(a):
    ledger = load(a.ledger)
    ledger["stages"]["wave"]["spent"] = True
    write(a.ledger, ledger)


def manifest(a):
    """The bundle's account of the whole catalog; refuses, by name, anything a release may not carry."""
    ledger = load(a.ledger)
    binding, lanes = ledger["binding"], a.lanes.split(",")
    required = ready_cases(a.repo_root, lanes)
    run = cases.current_run(a.catalog)
    entries, problems = [], []
    for name in required:
        attempts = cases.attempts_of(a.catalog, name)
        last = attempts[-1] if attempts else None
        if not last or last["status"] != "passed":
            problems.append(f"{name} did not pass")
            continue
        if last.get("bindingSha256") != cases.binding_sha(binding):
            problems.append(f"{name} passed on another build")
        entries.append({"name": name, "status": "passed", "passedAtUtc": last["finishedAtUtc"], "reused": last["run"] < run,
                        "flaky": any(e["status"] != "passed" for e in attempts), "resultSha256": last["resultSha256"],
                        "bindingSha256": last.get("bindingSha256"),
                        "attempts": [ledger_attempt(e) for e in attempts]})
    stages = ledger["stages"]
    for name in ("proofs", "wave", "insights"):
        if stages.get(name, {}).get("status") != "passed":
            problems.append(f"stage {name} did not pass")
    if problems:
        print("manifest refused: " + "; ".join(problems), file=sys.stderr)
        return 1
    write(a.out, {"schema": "qkt-attestation-catalog-evidence-v1", "testingSha": binding["testingSha"], "binding": binding,
                  "bindingSha256": cases.binding_sha(binding), "maxEvidenceAgeHours": MAX_EVIDENCE_AGE_HOURS,
                  "assembledAtUtc": cases.utc_now(), "lanes": lanes, "catalogRuns": run, "waveRunId": a.run_id,
                  "stages": {k: {f: v.get(f) for f in ("status", "completedAtUtc", "evidenceSha256", "attempts")} for k, v in stages.items()},
                  "cases": entries, "sessionWaits": session_waits(a.waits)})
    return 0


def session_waits(paths):
    """Every planned wait for a market window (lib/attest_session.py wait-until), in the order recorded: the audit of
    when and why the run waited. Waiting changes nothing about what must pass."""
    found = []
    for path in paths or []:
        if path and os.path.exists(path):
            found += [json.loads(line) for line in open(path) if line.strip()]
    return sorted(found, key=lambda w: w.get("recordedAtUtc", ""))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    for flag in ("ledger", "sha", "profile", "login", "server", "cli-root", "cli-version", "proofs", "wave", "insights",
                 "catalog", "lanes", "repo-root", "out"):
        p.add_argument(f"--{flag}", required=True)
    for flag in ("previous-tag", "corpus-lock", "image-id"):
        p.add_argument(f"--{flag}", default="")
    p = sub.add_parser("binding")
    for flag in ("sha", "profile", "login", "server", "cli-root", "cli-version", "out"):
        p.add_argument(f"--{flag}", required=True)
    p = sub.add_parser("begin"); p.add_argument("--ledger", required=True); p.add_argument("--binding", required=True)
    p = sub.add_parser("stage")
    for flag in ("ledger", "name", "evidence"):
        p.add_argument(f"--{flag}", required=True)
    p.add_argument("--meta", action="append", default=[]); p.add_argument("--attempts")
    p = sub.add_parser("spend-wave"); p.add_argument("--ledger", required=True)
    p = sub.add_parser("manifest")
    for flag in ("ledger", "catalog", "lanes", "repo-root", "run-id", "out"):
        p.add_argument(f"--{flag}", required=True)
    p.add_argument("--waits", action="append", default=[])
    a = ap.parse_args()
    if a.command == "binding":
        return write(a.out, make_binding(a.sha, a.profile, a.login, a.server, a.cli_root, a.cli_version)) or 0
    return {"plan": plan, "begin": begin, "stage": stage, "spend-wave": spend_wave, "manifest": manifest}[a.command](a) or 0


if __name__ == "__main__":
    sys.exit(main())
