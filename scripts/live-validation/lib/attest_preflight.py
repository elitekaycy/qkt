#!/usr/bin/env python3
"""Every reason this attestation cannot finish, within the first minute, all at once, each by its true name.

    attest_preflight.py --profile FILE --repo-root DIR [--key-file FILE] [--dispatch] [--no-session]
        [--plan FILE] [--schedule FILE] [--also-failed TEXT ...] [--report FILE]

Past stops were mostly a missing tool or a busy machine, found an hour in. "Unreachable" and "not flat" are different
findings: a gateway that does not answer says nothing about the account. Values of secrets are never read into a message.
Thresholds are profile settings (`preflight_min_disk_gb`, `preflight_min_mem_gb`, `preflight_max_latency_ms`,
`session_wait_max_h`). A session lane outside its window is planned, not refused (lib/attest_session.py): the check fails
only when no window the lane fits in opens within `session_wait_max_h`.
Exits 0 when nothing failed; prints the failures on one line at the end, for the stop message.
"""
import argparse, concurrent.futures as cf, datetime as dt, fcntl, json, os, re, shutil, statistics, subprocess, sys, time
import urllib.error, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

TOOLS = ("awk", "cmp", "curl", "docker", "flock", "gh", "git", "java", "jq", "openssl", "python3", "sha256sum",
         "sqlite3", "tar", "timeout", "unzip")
DEFAULT_MIN_DISK_GB, DEFAULT_MIN_MEM_GB, DEFAULT_MAX_LATENCY_MS = 5.0, 4.0, 1500
LATENCY_SAMPLES = 5
GATEWAY_TRIES, TRY_TIMEOUT_S, TRY_PAUSE_S = 3, 5, 2
BUDGET_SECONDS = 50
# label, url variable, login variable, trader key, guardian key
VENUES = (("derivatives", "deriv_gateway_url", "deriv_expected_login", "QKT_DERIV_GATEWAY_KEY", "QKT_DERIV_GUARDIAN_KEY"),
          ("bybit", "bybit_gateway_url", "bybit_expected_login", "QKT_BYBIT_GATEWAY_KEY", "QKT_BYBIT_GUARDIAN_KEY"),
          ("alpaca", "alpaca_gateway_url", "alpaca_expected_login", "QKT_ALPACA_GATEWAY_KEY", "QKT_ALPACA_GUARDIAN_KEY"))


def venues(p):
    """Every gateway lane the profile names; a cross venue (LABEL=URL@LOGIN) enters [p] under its own two variables."""
    found = list(VENUES)
    for venue in (p.get("cross_venues") or "").split():
        label, _, rest = venue.partition("=")
        url, _, login = rest.rpartition("@")
        p[f"cross_{label}_url"], p[f"cross_{label}_login"] = url, login
        found.append(("cross", f"cross_{label}_url", f"cross_{label}_login", f"QKT_CROSS_{label}_KEY", f"QKT_CROSS_{label}_GUARDIAN_KEY"))
    return found


class Finding:
    def __init__(self, check, level, message):
        self.check, self.level, self.message = check, level, message


def ok(check, message=""):
    return [Finding(check, "ok", message)]


def bad(check, message):
    return [Finding(check, "fail", message)]


class World:
    """What the checks read of the machine, so a test can hand them another one."""
    tries, pause = GATEWAY_TRIES, TRY_PAUSE_S

    def __init__(self, env=None):
        self.env = env if env is not None else os.environ

    def get(self, url, key=None, timeout=TRY_TIMEOUT_S):
        request = urllib.request.Request(url)
        if key:
            request.add_header("Authorization", f"Bearer {key}")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)

    def which(self, tool):
        return shutil.which(tool)

    def run(self, *command, cwd=None, timeout=25):
        try:
            done = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=timeout)
            return done.returncode, done.stdout.strip()
        except (OSError, subprocess.TimeoutExpired) as error:
            return 1, str(error)

    def free_gb(self, path):
        while not os.path.exists(path):
            path = os.path.dirname(path)
        return shutil.disk_usage(path).free / 2**30

    def available_gb(self):
        with open("/proc/meminfo") as stream:
            for line in stream:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) / 2**20
        return float("inf")

    def lock_held(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(lock, fcntl.LOCK_UN)
                return False
            except BlockingIOError:
                return True

    def now(self):
        import attest_session
        return attest_session.now()


def ask(world, url, key=None):
    """(answer, None), or (None, why it did not answer) after the retries: a busy gateway gets a second chance."""
    last = "no attempt"
    for attempt in range(world.tries):
        try:
            return world.get(url, key), None
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                return None, f"it refused the token (HTTP {error.code})"
            last = f"HTTP {error.code}"
        except (OSError, ValueError) as error:
            last = str(getattr(error, "reason", error)) or type(error).__name__
        if attempt + 1 < world.tries:
            time.sleep(world.pause)
    return None, f"no answer after {world.tries} tries: {last}"


def check_gateway(p, world):
    url, key = p["gateway_url"], world.env.get("QKT_BROKER_API_KEY")
    health, why = ask(world, f"{url}/health", key)
    if why:
        return bad("gateway-reachable", f"MT5 gateway {url} is unreachable: {why}. This says nothing about the account.")
    found = ok("gateway-reachable", url)
    if health.get("mt5_status") != "connected" or health.get("status") != "healthy":
        found += bad("venue-connected", f"MT5 gateway is up but not connected to its venue: status={health.get('status')} "
                                        f"mt5_status={health.get('mt5_status')} last_error={health.get('last_error')}")
    else:
        found += ok("venue-connected", "MT5 connected")
    if health.get("kill_switch_active"):
        found += bad("kill-switch", "the MT5 gateway's kill switch is on")
    account, why = ask(world, f"{url}/account", key)
    if why:
        return found + bad("account", f"could not read the account: {why}")
    if str(account.get("login")) != str(p["expected_login"]) or account.get("server") != p["expected_server"]:
        found += bad("account", f"the gateway is logged into {account.get('login')}@{account.get('server')}, "
                                f"expected {p['expected_login']}@{p['expected_server']}")
    elif account.get("trade_mode") != 0 or account.get("trade_allowed") is not True:
        found += bad("account", f"not a tradable demo: trade_mode={account.get('trade_mode')} trade_allowed={account.get('trade_allowed')}")
    else:
        found += ok("account", f"{p['expected_login']}@{p['expected_server']}")
    return found + check_flat(p, world) + check_latency(p, world)


def check_flat(p, world):
    url, key = p["gateway_url"], world.env.get("QKT_BROKER_API_KEY")
    positions, why = ask(world, f"{url}/get_positions", key)
    if why:
        return bad("account-flat", f"could not read positions, so flatness is unknown (not the same as not flat): {why}")
    orders, why = ask(world, f"{url}/orders", key)
    if why:
        return bad("account-flat", f"could not read orders, so flatness is unknown (not the same as not flat): {why}")
    low, high = int(p["magic_base"]), int(p["magic_base"]) + 1000
    held = [("position", i) for i in positions.get("data") or []] + [("order", i) for i in orders.get("orders") or []]
    name = lambda kind, i: f"{kind} {i.get('ticket')} ({i.get('symbol', '?')}, magic {i.get('magic')})"
    ours = [name(k, i) for k, i in held if low <= int(i.get("magic") or -1) < high]
    strangers = [name(k, i) for k, i in held if not low <= int(i.get("magic") or -1) < high]
    found = []
    if ours:
        found.append(Finding("account-flat", "warn", f"the sweep will clear this attestation's own leftovers: {', '.join(ours)}"))
    if strangers:
        return found + bad("account-flat", f"account is not flat: {', '.join(strangers)} are outside this attestation's magics {low}..{high}")
    return found or ok("account-flat", "no position, no pending order")


def check_latency(p, world):
    url, key = p["gateway_url"], world.env.get("QKT_BROKER_API_KEY")
    limit = float(p.get("preflight_max_latency_ms") or DEFAULT_MAX_LATENCY_MS)
    samples, misses = [], 0
    for _ in range(LATENCY_SAMPLES):
        began = time.monotonic()
        try:
            world.get(f"{url}/get_positions", key)
            samples.append((time.monotonic() - began) * 1000)
        except (OSError, ValueError):
            misses += 1
    if misses or not samples or statistics.median(samples) > limit:
        typical = f"median {statistics.median(samples):.0f} ms" if samples else "no answers"
        return bad("gateway-latency", f"the gateway is too slow to attest on: {typical}, {misses} of {LATENCY_SAMPLES} reads "
                                      f"lost, limit {limit:.0f} ms. Is another process hammering it?")
    return ok("gateway-latency", f"median {statistics.median(samples):.0f} ms")


def check_lock(p, world):
    folder = world.env.get("QKT_ATTEST_LOCK_DIR", "/var/tmp/qkt-validation")
    server = re.sub(r"[^A-Za-z0-9._-]", "_", p["expected_server"])
    path = f"{folder}/LIVE-LOCK-{server}-{p['expected_login']}"
    if world.lock_held(path):
        return bad("account-lock", f"another live run holds {path}; the account is in use")
    return ok("account-lock")


def check_disk_memory(p, world):
    need = float(p.get("preflight_min_disk_gb") or DEFAULT_MIN_DISK_GB)
    found = []
    for label, path in (("wave_root", p["wave_root"]), ("bundle_root", p["bundle_root"]), ("TMPDIR", world.env.get("TMPDIR", "/tmp"))):
        free = world.free_gb(path)
        found += (ok("disk", f"{label} {free:.1f} GB free") if free >= need
                  else bad("disk", f"{label} {path} has {free:.1f} GB free, needs {need:.0f} GB"))
    need = float(p.get("preflight_min_mem_gb") or DEFAULT_MIN_MEM_GB)
    have = world.available_gb()
    return found + (ok("memory", f"{have:.1f} GB available") if have >= need
                    else bad("memory", f"only {have:.1f} GB memory available, needs {need:.0f} GB for the daemons"))


def check_tools(p, world):
    # Gradle finds a JDK through JAVA_HOME as well as PATH; a unit's PATH often has only the former.
    java_home = world.env.get("JAVA_HOME")
    has_java_home = bool(java_home) and os.access(os.path.join(java_home, "bin", "java"), os.X_OK)
    missing = [t for t in TOOLS if not world.which(t) and not (t == "java" and has_java_home)]
    found = bad("tools", f"not on PATH: {', '.join(missing)}") if missing else ok("tools")
    code, _ = world.run("python3", "-c", "import yaml")
    return found + (ok("python-yaml") if code == 0 else bad("python-yaml", "python3 cannot import yaml"))


def check_secrets(p, world, key_file):
    env, found, missing = world.env, [], []
    for name in ("QKT_BROKER_API_KEY", "QKT_LIVE_DEMO_ORDER_APPROVAL"):
        if not env.get(name):
            missing.append(name)
    for lane, url, login, key, guardian in venues(p):
        if p.get(url):
            if not p.get(login):
                found += bad("tokens", f"the profile names {url} but not {login}")
            if not env.get(key):
                missing.append(key)
            if drills_kill_switch(p, lane) and not env.get(guardian):
                missing.append(guardian)
    found += bad("tokens", f"not set: {', '.join(missing)}") if missing else ok("tokens")
    if key_file:
        if not os.path.isfile(key_file) or os.path.getsize(key_file) == 0:
            found += bad("key-file", f"missing or empty: {key_file}")
        elif os.stat(key_file).st_mode & 0o077:
            found += bad("key-file", f"must be mode 0600: {key_file}")
    return found


def drills_kill_switch(p, lane):
    folder = os.path.join(p["__repo_root"], "attestation", "cases", lane)
    for case in os.listdir(folder) if os.path.isdir(folder) else []:
        path = os.path.join(folder, case, "case.yaml")
        if os.path.exists(path):
            text = open(path).read()
            if re.search(r"^status: ready", text, re.M) and "kill_switch" in text:
                return True
    return False


def check_venue(lane, url, login_var, key_name, p, world):
    health, why = ask(world, f"{p[url]}/v1/health", world.env.get(key_name))
    if why:
        return bad(f"{lane}-gateway", f"the {lane} gateway {p[url]} is unreachable: {why}")
    problems = []
    if str(health.get("account_login")) != str(p.get(login_var)):
        problems.append(f"logged into {health.get('account_login')}, expected {p.get(login_var)}")
    if health.get("trade_mode") != "demo":
        problems.append(f"trade_mode {health.get('trade_mode')}")
    if not health.get("venue_connected"):
        problems.append("its venue link is down")
    switch = health.get("kill_switch") or {}
    if switch.get("all") or switch.get("symbols"):
        problems.append(f"its kill switch is on: {switch}")
    return bad(f"{lane}-gateway", f"the {lane} gateway: {'; '.join(problems)}") if problems else ok(f"{lane}-gateway")


def check_docker(p, world):
    code, _ = world.run("docker", "image", "inspect", p["insights_image"])
    return ok("docker-image") if code == 0 else bad("docker-image", f"Insights image not present (or docker is down): {p['insights_image']}")


def check_corpus(p, world):
    corpus = p["data_corpus"]
    if not os.path.isdir(os.path.join(corpus, ".git")):
        return bad("data-corpus", f"not a clone: {corpus}")
    code, dirty = world.run("git", "-C", corpus, "status", "--porcelain")
    if code or dirty:
        return bad("data-corpus", f"{corpus} has local changes; the proofs refuse it")
    found = ok("data-corpus")
    lock = os.path.join(p["__repo_root"], "tests/data-e2e/corpus.lock")
    if os.path.exists(lock):
        commit = open(lock).read().split()[0]
        code, _ = world.run("git", "-C", corpus, "cat-file", "-e", f"{commit}^{{commit}}")
        if code:
            found.append(Finding("data-corpus", "warn", f"{commit[:12]} is not in the clone; the proofs will fetch it"))
    docs = p.get("docs_data_root")
    if docs and not os.path.isdir(docs):
        found += bad("docs-data", f"docs_data_root does not exist: {docs}")
    return found


def check_runner(p, world):
    code, online = world.run("gh", "api", "repos/{owner}/{repo}/actions/runners", "--jq",
                             '[.runners[] | select(.status == "online")] | length', cwd=p["__repo_root"])
    if code or not online.isdigit():
        return bad("runner", f"could not ask GitHub for its runners: {online[:120]}")
    if int(online) == 0:
        return bad("runner", "no self-hosted runner is online; paper-soak would queue forever")
    found = ok("runner", f"{online} online")
    code, _ = world.run("pgrep", "-c", "Runner.Listener")
    if code:  # GitHub's status goes stale when a listener dies; the runner may also live on another host
        found.append(Finding("runner", "warn", "GitHub says online but no Runner.Listener runs on this host"))
    return found


def check_gh(p, world):
    code, _ = world.run("gh", "auth", "status")
    return ok("gh-auth") if code == 0 else bad("gh-auth", "gh is not authenticated")


def session_plan(p, world, plan):
    """When the run reaches each session-bound lane that still has cases to run, and what it does about it."""
    import attest_session as session
    import yaml
    lanes_doc = yaml.safe_load(open(os.path.join(p["__repo_root"], "attestation/lanes.yaml")))["lanes"]
    keep, lead = set(plan.get("reusableCases") or []), int(plan.get("leadSeconds", sum(session.STAGE_SECONDS.values())))
    wanted = [lane for lane, on in (("stocks", p.get("attest_stocks") == "true"), ("alpaca", bool(p.get("alpaca_gateway_url")))) if on]
    work, idle = [], []
    for lane in wanted:
        config = lanes_doc[lane]
        seconds = session.lane_seconds(config, session.lane_cases(p["__repo_root"], lane, keep))
        (work.append((lane, config["session"], seconds)) if seconds else idle.append(lane))
    try:
        wait_h = float(p.get("session_wait_max_h") or session.DEFAULT_WAIT_MAX_H)
    except ValueError:
        wait_h = None
    doc = session.schedule(work, lead, world.now(), wait_h if wait_h is not None else session.DEFAULT_WAIT_MAX_H)
    doc.update(idle=idle, leadSeconds=lead, maxWaitHours=wait_h, plannedAtUtc=session.iso(world.now()))
    if wait_h is None:
        doc["problems"].append(f"session_wait_max_h is not a number: {p.get('session_wait_max_h')}")
    return doc


def check_sessions(p, world, plan, out=None):
    """Says now, not an hour in, when the run will run each session-bound lane; fails only when it never can in time."""
    doc = session_plan(p, world, plan)
    if out:
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        json.dump(doc, open(out, "w"), indent=1)
    found = [f for lane in doc["idle"] for f in ok(f"session-{lane}", "nothing left to run in its window")]
    for item in doc["lanes"]:
        found += ok(f"session-{item['lane']}", item["reason"])
    if doc["problems"]:
        return found + bad("session-plan", "; ".join(doc["problems"]))
    return found + ok("session-plan", doc["summary"]) if doc["lanes"] else found


def load_profile(path):
    text = subprocess.run(["bash", "-c", 'set -a; . "$0"; env -0', path], capture_output=True, text=True, check=True,
                          env={"PATH": os.environ.get("PATH", "")}).stdout
    return dict(item.split("=", 1) for item in text.split("\0") if "=" in item)


def run_checks(p, world, key_file=None, dispatch=False, no_session=False, plan=None, schedule_out=None):
    jobs = [("gateway", lambda: check_gateway(p, world)), ("lock", lambda: check_lock(p, world)),
            ("disk", lambda: check_disk_memory(p, world)), ("tools", lambda: check_tools(p, world)),
            ("secrets", lambda: check_secrets(p, world, key_file)), ("docker", lambda: check_docker(p, world)),
            ("corpus", lambda: check_corpus(p, world)), ("gh", lambda: check_gh(p, world))]
    for lane, url, login, key, _ in venues(p):
        if p.get(url):
            jobs.append((lane, lambda lane=lane, url=url, login=login, key=key: check_venue(lane, url, login, key, p, world)))
    if dispatch:
        jobs.append(("runner", lambda: check_runner(p, world)))
    if not no_session:
        jobs.append(("session", lambda: check_sessions(p, world, plan or {}, schedule_out)))
    pool = cf.ThreadPoolExecutor(max_workers=len(jobs))
    futures = {pool.submit(job): name for name, job in jobs}
    findings = []
    done, unfinished = cf.wait(futures, timeout=BUDGET_SECONDS)
    for future in done:
        try:
            findings += future.result()
        except Exception as error:  # a broken check is a failure with a name, not a crash that hides the others
            findings += bad(futures[future], f"the check itself failed: {error}")
    findings += [Finding(futures[f], "fail", f"did not finish within {BUDGET_SECONDS} s") for f in unfinished]
    pool.shutdown(wait=False, cancel_futures=True)
    return findings


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True); ap.add_argument("--repo-root", required=True)
    ap.add_argument("--key-file"); ap.add_argument("--dispatch", action="store_true")
    ap.add_argument("--no-session", action="store_true"); ap.add_argument("--plan"); ap.add_argument("--report")
    ap.add_argument("--schedule", help="where to write the session plan (lanes, waits, any hold) as JSON")
    ap.add_argument("--also-failed", action="append", default=[])
    a = ap.parse_args()
    began = time.monotonic()
    profile = load_profile(a.profile)
    profile["__repo_root"] = a.repo_root
    plan = json.load(open(a.plan)) if a.plan else {}
    findings = run_checks(profile, World(), a.key_file, a.dispatch, a.no_session, plan, a.schedule)
    findings += [Finding("run", "fail", text) for text in a.also_failed]
    failed = [f for f in findings if f.level == "fail"]
    for finding in sorted(findings, key=lambda f: (f.level != "fail", f.level != "warn", f.check)):
        print(f"  {finding.level.upper():4} {finding.check}: {finding.message}".rstrip(": "))
    print(f"preflight: {len(findings)} checks, {len(failed)} failed, {time.monotonic() - began:.0f}s")
    if a.report:
        os.makedirs(os.path.dirname(a.report) or ".", exist_ok=True)
        json.dump([vars(f) for f in findings], open(a.report, "w"), indent=1)
    if failed:
        print("; ".join(f"{f.check}: {f.message}" for f in failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
