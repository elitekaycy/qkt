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


daemon = start_daemon()
results, problems = [], []
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
        if step.get("expect_log") and not re.search(step["expect_log"], open(f"{a.out}/daemon.log").read()):
            verdict.append(f"daemon log does not match /{step['expect_log']}/")
        results.append({"step": index, "run": line, "exit": code, "output": output[-600:], "problems": verdict,
                        "note": step.get("note", "")})
        problems += [f"step {index} ({line}): {v}" for v in verdict]
        time.sleep(float(step.get("settle_seconds", 1)))
finally:
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
            problems.append(f"could not force-close ticket {position['ticket']}: {error}")
    return closed


if owned():
    problems.append(f"magic still owned a position after the case; force-closed tickets {force_close_leftovers()}")
if pending():
    problems.append("magic still owns a pending order after the case")
log = open(f"{a.out}/daemon.log").read()
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
