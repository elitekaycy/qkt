"""A throwaway repository holding the real catalog script and stub case runners, for tests that drive the catalog."""
import json, os, shutil, stat, subprocess, textwrap

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.."))

# Each stub attempt appends to calls.log, then acts on behaviours.json: {"case": ["pass" | "fail:<problem>", ...]},
# one action per attempt; the last repeats. "outage:<problem>" fails with sampled venue-outage evidence in its result,
# "unavailable:<problem>" fails as a case whose venue wait exceeded its maximum (lib/venue_health.py).
RUNNER = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json, os, sys
    args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
    case, out = os.path.basename(args["--case"]), args["--out"]
    root = os.environ["STUB_ROOT"]
    os.makedirs(out)
    with open(f"{root}/calls.log", "a") as log:
        log.write(f"{case} {os.path.basename(out)} magic={args.get('--magic')}\\n")
    plan = json.load(open(f"{root}/behaviours.json")).get(case, ["pass"])
    seen = sum(1 for line in open(f"{root}/calls.log") if line.split()[0] == case)
    act = plan[min(seen, len(plan)) - 1]
    failed = act != "pass"
    kind, _, text = act.partition(":")
    problems = [text] if failed else []
    result = {"status": "failed" if failed else "passed", "problems": problems}
    if kind == "outage":
        result["venueOutage"] = {"samplesTaken": 3, "unavailableSamples": 2, "reason": "gateway has no venue link",
                                 "unavailable": [{"time": 1, "available": False, "reason": "gateway has no venue link"}]}
    if kind == "unavailable":
        result["venueWaits"] = [{"venue": "DERIBIT", "reason": "gateway has no venue link", "seconds": 21700,
                                 "exceeded": True}]
    json.dump(result, open(f"{out}/result.json", "w"))
    print(("failed " if failed else "passed ") + case + (" " + problems[0] if failed else ""))
    sys.exit(1 if failed else 0)
    ''')


def build(tmp, cases, lanes_yaml="lanes: {}\n"):
    """cases: {"lane": ["id", ...]}"""
    fake = os.path.join(tmp, "repo")
    live = os.path.join(fake, "scripts", "live-validation")
    os.makedirs(live); os.makedirs(os.path.join(fake, "attestation", "lib")); os.makedirs(os.path.join(fake, "bin"))
    shutil.copy(os.path.join(ROOT, "scripts/live-validation/run-attestation-catalog.sh"), live)
    shutil.copytree(os.path.join(ROOT, "scripts/live-validation/lib"), os.path.join(live, "lib"))
    open(os.path.join(fake, "attestation/lib/validate.py"), "w").write('print("ok")\n')
    open(os.path.join(fake, "attestation/lanes.yaml"), "w").write(lanes_yaml)
    qkt = os.path.join(fake, "bin/qkt")
    open(qkt, "w").write('#!/usr/bin/env bash\necho "qkt 0.0.0 (test)"\n')
    os.chmod(qkt, os.stat(qkt).st_mode | stat.S_IEXEC)
    for lane, ids in cases.items():
        for cid in ids:
            folder = os.path.join(fake, "attestation/cases", lane, cid)
            os.makedirs(folder)
            open(os.path.join(folder, "case.yaml"), "w").write(f"id: {cid}\nstatus: ready\n")
    for name in ("run-derivatives-lane-case.py", "run-daemon-lane-case.py"):
        open(os.path.join(live, name), "w").write(RUNNER)
    open(os.path.join(live, "run-order-lane-case.sh"), "w").write('exec python3 "$(dirname "$0")/run-daemon-lane-case.py" "$@"\n')
    return fake


def run_catalog(fake, out, lanes, extra=(), behaviours=None, env=None):
    root = os.path.dirname(fake)
    json.dump(behaviours or {}, open(os.path.join(root, "behaviours.json"), "w"))
    full = dict(os.environ, QKT_ATTEST_UTC_DAY_WAIT="off", QKT_BROKER_API_KEY="x",
                QKT_LIVE_DEMO_ORDER_APPROVAL="LOCALHOST_DEMO_ONLY",
                STUB_ROOT=root, QKT_ATTEST_RETRY_BACKOFF="0,0,0", QKT_DERIV_GATEWAY_KEY="x", **(env or {}))
    return subprocess.run(
        ["bash", os.path.join(fake, "scripts/live-validation/run-attestation-catalog.sh"), "--out", out,
         "--gateway-url", "http://127.0.0.1:1", "--expected-login", "1", "--expected-server", "S", "--magic-base", "100",
         "--arm", "I_UNDERSTAND_DEMO_ORDER_0.01", "--lanes", lanes, "--cli", os.path.join(fake, "bin/qkt"), *extra],
        env=full, capture_output=True, text=True)


def calls(fake):
    path = os.path.join(os.path.dirname(fake), "calls.log")
    return open(path).read().splitlines() if os.path.exists(path) else []
