#!/usr/bin/env python3
"""Run one `daemon` or `engine` lane case: a scripted operator session against a live daemon.

    run-daemon-lane-case.py --case DIR --out DIR --gateway-url URL --expected-login N
        --expected-server NAME --magic N --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH]

The case's `steps` are executed in order. Each step is a mapping:

    run:            a qkt command line; {strategy} {file} {state} {config} are substituted
    expect_exit:    exit code the command must return (default 0)
    expect_stdout:  regex that must match the command's output (optional)
    expect_status:  jq-free check on `qkt status <strategy>`: {"positions": 1, "halted": true} (optional)
    wait_for:       "position" | "flat" - poll the venue under this case's magic before the step (optional);
                    with `wait_count: N`, wait for at least N positions
    daemon:         "restart" | "kill9_restart" instead of `run`: stop the daemon without flattening
                    (or SIGKILL it mid-flight) and start it again on the same state directory
    expect_log:     regex the daemon log must contain after the step (optional)
    expect_positions: exact number of venue positions under this case's magic after the step (optional)
    expect_pending:   exact number of resting venue orders under this case's magic after the step (optional)
    wait_log:       regex to wait for in the daemon log before the step, up to `wait_seconds` (optional)
    while_down:     with `daemon: restart`, "close_at_venue" closes the magic's positions by ticket
                    while no engine is running

Case-level keys: `copies: N` loads the strategy N times under numbered names and `copies_agree`
(`log` regex, `min_lines`) requires every copy to have logged the same lines and dropped no tick;
`start_offset: {period_seconds, min, max}` delays the daemon start until the wall clock is
that far into a bar, so a mid-bar start is a fact and not luck; `forming_bar_checks` compares bars the
strategy logged with the venue's own (see check_forming_bars). `config` is deep-merged into the run config; `expect_startup_refusal` is a regex
the daemon must print while REFUSING to start (then no step runs and nothing may reach the venue).
    note:           why this step exists

Operator commands rot silently because nobody runs the emergency ones until the emergency.
Every step's command, exit code, output and verdict is recorded. The case always ends with
`kill --flatten` and a venue check that the magic owns nothing. Demo accounts on loopback only.
"""
import argparse, json, os, re, subprocess, sys, time, urllib.request
import yaml

ap = argparse.ArgumentParser()
for flag in ("case", "out", "gateway-url", "expected-login", "expected-server", "magic", "arm"):
    ap.add_argument(f"--{flag}", required=True)
ap.add_argument("--cli", default=os.path.join(os.path.dirname(__file__), "../../build/install/qkt/bin/qkt"))
a = ap.parse_args()


def die(message):
    print(f"run-daemon-lane-case: {message}", file=sys.stderr)
    sys.exit(1)


if a.arm != "I_UNDERSTAND_DEMO_ORDER_0.01":
    die("--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required")
if os.environ.get("QKT_LIVE_DEMO_ORDER_APPROVAL") != "LOCALHOST_DEMO_ONLY":
    die("QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required")
key = os.environ.get("QKT_BROKER_API_KEY") or die("QKT_BROKER_API_KEY must be set")
if not re.fullmatch(r"http://(127\.0\.0\.1|localhost):\d+", a.gateway_url):
    die("only a loopback gateway is allowed")
if os.path.exists(a.out):
    die(f"output already exists: {a.out}")
case = yaml.safe_load(open(f"{a.case}/case.yaml"))
steps = case.get("steps") or die("case.yaml has no steps")
source = open(f"{a.case}/strategy.qkt").read()
strategy = re.search(r"^(?:STRATEGY|PORTFOLIO)\s+(\w+)", source, re.M).group(1)


def gateway(path):
    req = urllib.request.Request(a.gateway_url + path, headers={"Authorization": f"Bearer {key}"})
    return json.load(urllib.request.urlopen(req, timeout=20))


def owned():
    return len(gateway(f"/get_positions?magic={a.magic}").get("data") or [])


def pending():
    # Filtered here as well: the gateway's magic filter on /orders is not relied upon.
    orders = gateway(f"/orders?magic={a.magic}").get("orders") or []
    return len([o for o in orders if str(o.get("magic", a.magic)) == str(a.magic)])


account = gateway("/account")
if str(account.get("login")) != a.expected_login or account.get("server") != a.expected_server or account.get("trade_mode") != 0:
    die("gateway is not logged into the expected DEMO account")
if owned() or pending():
    die(f"magic {a.magic} already owns a position or a pending order")

os.makedirs(f"{a.out}/strategies"); os.makedirs(f"{a.out}/state"); os.makedirs(f"{a.out}/evidence")
# The daemon names a deployment after its file, so the file carries the strategy's own name.
strategy_file = f"{a.out}/{strategy}.qkt"
if case.get("autoload"):
    strategy_file = f"{a.out}/strategies/{strategy}.qkt"
os.makedirs(os.path.dirname(strategy_file), exist_ok=True)
open(strategy_file, "w").write(source)
# `copies: N` loads the same strategy N times under numbered names: identical inputs, so identical outputs.
copy_names = [f"{strategy}_{i:02d}" for i in range(2, int(case.get("copies", 1)) + 1)]
for name in copy_names:
    open(os.path.join(os.path.dirname(strategy_file), f"{name}.qkt"), "w").write(
        re.sub(r"^(STRATEGY\s+)\w+", rf"\g<1>{name}", source, count=1, flags=re.M))
# A portfolio imports its children by relative path, so they travel beside it.
for extra in sorted(os.listdir(a.case)):
    if extra.endswith(".qkt") and extra != "strategy.qkt":
        open(os.path.join(os.path.dirname(strategy_file), extra), "w").write(open(f"{a.case}/{extra}").read())
config = f"{a.out}/qkt.config.yaml"
open(config, "w").write(f"""source: local
data_root: "{a.out}/data"
log_level: info
runtime:
  mode: dev
account:
  currency: USD
brokers:
  exness:
    type: mt5
    extends: exness
    calendars:
      "BTC*": crypto
    gateway_url: {a.gateway_url}
    magic: {a.magic}
    server_time_zone: Etc/UTC
    expected_account_login: {a.expected_login}
    expected_account_server: {a.expected_server}
    expected_trade_mode: demo
    tick_poll_interval_ms: 100
    poll_interval_ms: 1000
risk:
  max_daily_loss: "50"
  max_order_qty: "0.05"
  max_order_notional: "10000"
  price_collar_pct: "5"
  measured_usage_hours: "0"
  max_round_trips_10m: 0
  live_equity_basis: modeled
state:
  enabled: true
  async: true
insights:
  enabled: false
""")


def merge(base, over):
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            merge(base[k], v)
        else:
            base[k] = v
    return base


if case.get("config"):
    yaml.safe_dump(merge(yaml.safe_load(open(config)), case["config"]), open(config, "w"), sort_keys=False)


def qkt(argv, timeout=120):
    run = subprocess.run([a.cli, *argv], capture_output=True, text=True, timeout=timeout)
    return run.returncode, (run.stdout + run.stderr).strip()


def start_daemon():
    """Starts (or restarts) the daemon on the case's state directory and waits until it is ready."""
    seen = open(f"{a.out}/daemon.log").read().count("daemon ready") if os.path.exists(f"{a.out}/daemon.log") else 0
    handle = subprocess.Popen([a.cli, "daemon", "start", "--config", config, "--state-dir", f"{a.out}/state",
                               "--load-dir", f"{a.out}/strategies"], stdout=open(f"{a.out}/daemon.log", "a"),
                              stderr=subprocess.STDOUT)
    for _ in range(150):
        if open(f"{a.out}/daemon.log").read().count("daemon ready") > seen:
            return handle
        if handle.poll() is not None:
            die("daemon exited during startup")
        time.sleep(1)
    die("daemon was not ready within 150 seconds")


if case.get("expect_startup_refusal"):
    # The whole point is that the daemon must NOT come up: run it to completion and read why.
    run = subprocess.run([a.cli, "daemon", "start", "--config", config, "--state-dir", f"{a.out}/state",
                          "--load-dir", f"{a.out}/strategies"], capture_output=True, text=True, timeout=120)
    text = run.stdout + run.stderr
    open(f"{a.out}/daemon.log", "w").write(text)
    problems = []
    if run.returncode == 0 or "daemon ready" in text:
        problems.append("the daemon started; it must refuse")
    if not re.search(case["expect_startup_refusal"], text):
        problems.append(f"refusal does not match /{case['expect_startup_refusal']}/: {text.strip()[-200:]}")
    if owned() or pending():
        problems.append("something reached the venue under this magic")
    result = {"schema": "qkt-attestation-daemon-case-v1", "id": case["id"], "status": "failed" if problems else "passed",
              "magic": int(a.magic), "steps": [], "refusal": text.strip()[-400:], "problems": problems}
    json.dump(result, open(f"{a.out}/result.json", "w"), indent=2)
    print(f"{result['status']} {case['id']} refusal-check problems={len(problems)}" + ("" if not problems else " :: " + " | ".join(problems)[:400]))
    sys.exit(1 if problems else 0)

failures = []


def force_close_leftovers():
    """Last resort, by ticket: a case must never leave a position behind, whatever its name was."""
    closed = []
    for position in gateway(f"/get_positions?magic={a.magic}").get("data") or []:
        body = json.dumps({"position": {"ticket": position["ticket"]}}).encode()
        req = urllib.request.Request(a.gateway_url + "/close_position", data=body, method="POST",
                                     headers={"Authorization": f"Bearer {key}", "content-type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=30).read()
            closed.append(position["ticket"])
        except Exception as error:  # noqa: BLE001 - reported, never swallowed
            failures.append(f"could not force-close ticket {position['ticket']}: {error}")
    return closed


def check_forming_bars(log):
    """A bar that was already in progress when the daemon started must still be the venue's bar.

    Venue bars are bid-priced and engine bars mid-priced, so the venue's are lifted by half their spread.
    Each check names a log regex with groups (open, high, low) for the FIRST bar the strategy closed,
    the venue symbol and timeframe, and the bar length. The open must equal the venue's, and the
    high/low must contain every whole minute that traded before the start - the minutes a late
    starter never saw as ticks. Seeded minute counts in the log must match the clock.
    """
    found = []
    for check in case.get("forming_bar_checks") or []:
        period = int(check["period_seconds"])
        bar_start = int(started_at // period * period)
        match = re.search(check["log"], log)
        if not match:
            found.append(f"{check['timeframe']}: no logged bar matches /{check['log']}/")
            continue
        opened, high, low = (float(match.group(i)) for i in (1, 2, 3))
        stamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(bar_start))
        bars = gateway(f"/fetch_data_pos?symbol={check['symbol']}&timeframe={check['timeframe']}&num_bars=6").get("data") or []
        venue = next((b for b in bars if b["time"] == stamp), None)
        minutes = [b for b in gateway(f"/fetch_data_pos?symbol={check['symbol']}&timeframe=M1&num_bars=30").get("data") or []
                   if stamp <= b["time"] < time.strftime("%Y-%m-%dT%H:%M:00Z", time.gmtime(started_at))]
        if venue is None or not minutes:
            found.append(f"{check['timeframe']}: venue bar {stamp} or its minutes not available")
            continue
        # The venue's bars are bid prices; the engine's are mid: each bar is lifted by half its own spread.
        point = float(gateway(f"/symbol_info/{check['symbol']}")["point"])
        mid = lambda bar, field: bar[field] + bar["spread"] * point / 2
        if abs(opened - mid(venue, "open")) > point / 2:
            found.append(f"{check['timeframe']}: open {opened} is not the venue's {mid(venue, 'open')} (mid) - the bar was built from the start, not seeded")
        if high < max(mid(b, "high") for b in minutes) - point / 2 or low > min(mid(b, "low") for b in minutes) + point / 2:
            found.append(f"{check['timeframe']}: high/low {high}/{low} do not contain the minutes traded before the start")
    for alias, period in (case.get("expect_forming_minutes") or {}).items():
        match = re.search(rf"seeded forming bar strategy=\S+ alias={alias} minutes=(\d+)", log)
        expected = int(started_at % int(period) // 60)
        if not match or abs(int(match.group(1)) - expected) > 1:
            found.append(f"{alias}: seeded minutes {match.group(1) if match else 'missing'}, the clock says {expected}")
    return found


def check_copies(log):
    """Every copy saw the same ticks, so every copy must have logged the same values and shed nothing."""
    rule = case.get("copies_agree")
    if not rule:
        return []
    found, lines = [], {}
    for name in [strategy, *copy_names]:
        lines[name] = {m for m in re.findall(rf"\[{name}\][^\n]*? - ({rule['log']}[^\n]*)", log)}
    common = set.intersection(*lines.values())
    if len(common) < int(rule.get("min_lines", 1)):
        found.append(f"only {len(common)} line(s) are common to all {len(lines)} copies, expected {rule.get('min_lines', 1)}")
    # A copy may be one bar ahead or behind at the moment the log is read; more than that is disagreement.
    for name, own in sorted(lines.items()):
        if len(own - common) > 2:
            found.append(f"{name} logged {len(own - common)} line(s) no other copy agrees with, e.g. {sorted(own - common)[0][:120]}")
    return found


def dropped_ticks():
    shed = []
    for name in [strategy, *copy_names]:
        _, raw = qkt(["status", name, "--state-dir", f"{a.out}/state"])
        try:
            dropped = json.loads(raw[raw.index("{"):]).get("droppedTicks")
        except ValueError:
            dropped = None
        if dropped != 0:
            shed.append(f"{name}: droppedTicks is {dropped!r}")
    return shed


offset = case.get("start_offset")
while offset and not int(offset["min"]) <= time.time() % int(offset["period_seconds"]) <= int(offset["max"]):
    time.sleep(1)
started_at = time.time()
daemon = start_daemon()
results, problems = [], failures
try:
    for index, step in enumerate(steps, 1):
        if step.get("wait_for") in ("position", "flat"):
            # `wait_count` asks for an exact number of positions under the magic (two children, two legs).
            target = int(step.get("wait_count", 1)) if step["wait_for"] == "position" else 0
            for _ in range(int(step.get("wait_seconds", 150))):
                held = owned()
                if (held >= target and target > 0) or (held == 0 and target == 0):
                    break
                time.sleep(1)
            else:
                problems.append(f"step {index}: venue never reached '{step['wait_for']}' x{target} (held {owned()})")
        verdict = []
        if step.get("wait_log"):
            for _ in range(int(step.get("wait_seconds", 150))):
                if re.search(step["wait_log"], open(f"{a.out}/daemon.log").read()):
                    break
                time.sleep(1)
            else:
                verdict.append(f"daemon log never matched /{step['wait_log']}/")
        if step.get("daemon") in ("restart", "kill9_restart"):
            line = f"@daemon {step['daemon']}"
            if step["daemon"] == "restart":
                qkt(["daemon", "stop", "--state-dir", f"{a.out}/state"])
                try:
                    daemon.wait(timeout=90)
                except subprocess.TimeoutExpired:
                    verdict.append("daemon did not stop within 90 seconds")
                    daemon.kill()
            else:
                daemon.kill()  # SIGKILL: no shutdown hook, no flush, no flatten
                daemon.wait(timeout=30)
            held_while_down = owned()
            if step.get("while_down") == "close_at_venue":
                # The venue acts while nobody is watching: the engine must learn of it from deal history.
                closed = force_close_leftovers()
                if not closed or owned():
                    verdict.append(f"could not close the position at the venue while down (closed {closed})")
            daemon = start_daemon()
            code, output = 0, f"positions held at the venue while the daemon was down: {held_while_down}"
        else:
            line = step["run"].format(strategy=strategy, file=strategy_file, state=f"{a.out}/state", config=config)
            argv = line.split()[1:] if line.split()[0] == "qkt" else line.split()
            code, output = qkt(argv)
        if code != int(step.get("expect_exit", 0)):
            verdict.append(f"exit {code}, expected {step.get('expect_exit', 0)}")
        if step.get("expect_stdout") and not re.search(step["expect_stdout"], output, re.S):
            verdict.append(f"output does not match /{step['expect_stdout']}/")
        if step.get("expect_status"):
            _, raw = qkt(["status", strategy, "--state-dir", f"{a.out}/state"])
            try:
                status = json.loads(raw[raw.index("{"):])
            except ValueError:
                status = {}
            for field, expected in step["expect_status"].items():
                actual = len(status.get(field) or []) if field == "positions" else status.get(field)
                if actual != expected:
                    verdict.append(f"status.{field} is {actual!r}, expected {expected!r}")
        if "expect_positions" in step:
            time.sleep(float(step.get("settle_seconds", 1)))
            held = owned()
            if held != int(step["expect_positions"]):
                verdict.append(f"venue holds {held} position(s) under the magic, expected {step['expect_positions']}")
        if "expect_pending" in step:
            resting = pending()
            if resting != int(step["expect_pending"]):
                verdict.append(f"venue holds {resting} pending order(s) under the magic, expected {step['expect_pending']}")
        if step.get("expect_log") and not re.search(step["expect_log"], open(f"{a.out}/daemon.log").read()):
            verdict.append(f"daemon log does not match /{step['expect_log']}/")
        results.append({"step": index, "run": line, "exit": code, "output": output[-600:], "problems": verdict,
                        "note": step.get("note", "")})
        problems += [f"step {index} ({line}): {v}" for v in verdict]
        time.sleep(float(step.get("settle_seconds", 1)))
    if case.get("copies_agree"):
        problems += dropped_ticks()
finally:
    for name in copy_names:
        qkt(["kill", name, "--flatten", "--state-dir", f"{a.out}/state", "--json"])
    qkt(["kill", strategy, "--flatten", "--state-dir", f"{a.out}/state", "--json"])
    for _ in range(30):
        if not owned() and not pending():
            break
        time.sleep(1)
    qkt(["daemon", "stop", "--state-dir", f"{a.out}/state"])
    try:
        daemon.wait(timeout=60)
    except subprocess.TimeoutExpired:
        daemon.kill()



if owned():
    problems.append(f"magic still owned a position after the case; force-closed tickets {force_close_leftovers()}")
if pending():
    # Reported as a failure AND removed: a resting order left behind makes the next attestation refuse to start.
    removed = []
    for order in gateway(f"/orders?magic={a.magic}").get("orders") or []:
        if str(order.get("magic", a.magic)) != str(a.magic):
            continue
        req = urllib.request.Request(f"{a.gateway_url}/orders/{order['ticket']}", method="DELETE",
                                     headers={"Authorization": f"Bearer {key}"})
        try:
            urllib.request.urlopen(req, timeout=30).read()
            removed.append(order["ticket"])
        except Exception as error:  # noqa: BLE001 - reported, never swallowed
            problems.append(f"could not cancel leftover order {order['ticket']}: {error}")
    problems.append(f"magic still owned a pending order after the case; cancelled tickets {removed}")
log = open(f"{a.out}/daemon.log").read()
problems += check_forming_bars(log) + check_copies(log)
if re.search(r"engine loop fault|unattributed fill dropped", log):
    problems.append("engine fault or unattributed fill in the daemon log")
# A lost acknowledgement is resolved by asking the venue; only one left unresolved is a failure.
unknown, resolved = len(re.findall(r"outcome UNKNOWN", log)), len(re.findall(r"resolved as [A-Z_]+", log))
if unknown > resolved:
    problems.append(f"{unknown} unknown order outcome(s), only {resolved} resolved")
result = {"schema": "qkt-attestation-daemon-case-v1", "id": case["id"], "status": "failed" if problems else "passed",
          "magic": int(a.magic), "steps": results, "problems": problems}
json.dump(result, open(f"{a.out}/result.json", "w"), indent=2)
print(f"{result['status']} {case['id']} steps={len(results)} problems={len(problems)}" +
      ("" if not problems else " :: " + " | ".join(problems)[:400]))
sys.exit(1 if problems else 0)
