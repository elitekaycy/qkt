#!/usr/bin/env python3
"""One derivatives attestation case on a VGP gateway account (futures, perpetuals, options).

    run-derivatives-lane-case.py --case DIR --out DIR --gateway-url URL --expected-login LOGIN
        --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH]

Needs QKT_DERIV_GATEWAY_KEY (the gateway's trader token) and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY.
The gateway must be on loopback, in demo trade mode, logged into --expected-login, and the account flat:
a netting account cannot tell the case's positions from anyone else's. The case's strategy runs in a
daemon until it has made `fills` fills (or its budget ends), then is judged:
  - flat-account: the account ends with no position and no working order;
  - deals-net-equals-realized: qkt's realized PnL equals the venue's deals net, fees included, exactly;
  - replay-same-fills (`replay: bars`): a backtest of the same minutes on the gateway's own bars makes the
    same fills, in order, on the same sides and sizes (a bar replay fills at the next bar's open, so each
    fill's price difference from live is recorded, as the MT5 order lane does, not judged);
  - replay-same-legs (`replay: chain`): a backtest on the chain the account recorded opens the same legs.
Writes OUT/result.json and prints one `passed|failed <id> ...` line; exits non-zero on failure.
"""
import argparse, csv, datetime, json, os, re, shutil, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from decimal import Decimal

import yaml

TRADE = re.compile(r"qkt\.trade - trade (BUY|SELL) (\S+) qty=(\S+) px=(\S+)")
MINUTE = 60_000


def main():
    ap = argparse.ArgumentParser()
    for name in ("--case", "--out", "--gateway-url", "--expected-login", "--arm"):
        ap.add_argument(name, required=True)
    ap.add_argument("--cli", default=os.path.join(os.path.dirname(__file__), "../../build/install/qkt/bin/qkt"))
    args = ap.parse_args()
    case = yaml.safe_load(open(os.path.join(args.case, "case.yaml")))
    lane = yaml.safe_load(open(os.path.join(os.path.dirname(__file__), "../../attestation/lanes.yaml")))["lanes"]
    budget = int(case.get("budget_seconds", lane["derivatives"]["budget_seconds"]))
    run = Run(args, case)
    try:
        problems = run.execute(budget)
    except Exception as error:  # the verdict is always written, whatever went wrong
        problems = [f"runner error: {error}"]
    finally:
        run.stop_daemon()
        try:
            run.sweep()
        except Exception as error:
            problems = problems + [f"sweep: the account may not be flat: {error}"]
    return run.verdict(problems)


class Run:
    def __init__(self, args, case):
        if args.arm != "I_UNDERSTAND_DEMO_ORDER_0.01":
            sys.exit("--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required")
        if os.environ.get("QKT_LIVE_DEMO_ORDER_APPROVAL") != "LOCALHOST_DEMO_ONLY":
            sys.exit("QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required")
        if not re.fullmatch(r"http://(127\.0\.0\.1|localhost):\d+", args.gateway_url):
            sys.exit("only a loopback gateway is allowed")
        self.key = os.environ.get("QKT_DERIV_GATEWAY_KEY") or sys.exit("QKT_DERIV_GATEWAY_KEY must be set")
        if os.path.exists(args.out):
            sys.exit(f"output already exists: {args.out}")
        self.args, self.case, self.out = args, case, os.path.abspath(args.out)
        self.cli = os.path.abspath(args.cli)
        self.daemon = None
        self.started_ms = self.ended_ms = 0

    def get(self, path, body=None, method=None):
        for attempt in range(1, 5):
            request = urllib.request.Request(self.args.gateway_url + path, method=method)
            request.add_header("Authorization", f"Bearer {self.key}")
            data = None
            if body is not None:
                request.add_header("Content-Type", "application/json")
                data = json.dumps(body).encode()
            try:
                with urllib.request.urlopen(request, data, timeout=30) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code < 500 or attempt == 4:  # a refusal is the gateway's answer; only 5xx may pass
                    raise
                time.sleep(attempt)
            except OSError:
                if attempt == 4:
                    raise
                time.sleep(attempt)

    def sweep(self):
        """Leaves the account flat once the case has taken it, however the case ended (a venue outage mid-trade)."""
        if not getattr(self, "owns_account", False):
            return
        for order in self.get("/v1/orders")["orders"]:
            try:
                self.get(f"/v1/orders/{urllib.parse.quote(order['client_order_id'])}", method="DELETE")
            except urllib.error.HTTPError as error:
                if error.code != 404:  # not_found: the order already ended (the venue cancelled it)
                    raise
        for position in self.get("/v1/positions")["positions"]:
            self.get("/v1/positions/close", {"symbol": position["symbol"]}, "POST")
        deadline = time.time() + 30
        while not self.flat():
            if time.time() > deadline:
                raise RuntimeError("still holds a position or a working order")
            time.sleep(2)

    def flat(self):
        return not self.get("/v1/positions")["positions"] and not self.get("/v1/orders")["orders"]

    def execute(self, budget):
        health = self.get("/v1/health")
        if health["trade_mode"] != "demo" or str(health["account_login"]) != self.args.expected_login:
            raise RuntimeError("gateway is not the expected DEMO account")
        if not health["venue_connected"]:
            raise RuntimeError("gateway has no venue link")
        if not self.flat():
            raise RuntimeError("the account is not flat: a netting account cannot attribute the case's positions")
        self.owns_account = True  # it was flat, so whatever is on it from here on is this case's
        self.prepare(health["adapter"])
        self.run_daemon(budget)
        problems = []
        if not self.flat():
            problems.append("flat-account: the account holds a position or a working order after the case")
        fills = self.live_fills()
        if len(fills) < int(self.case["fills"]):
            problems.append(f"expected {self.case['fills']} fills within the budget, saw {len(fills)}")
        realized = Decimal(json.load(open(f"{self.out}/state/state/{self.strategy}/pnl.json"))["realized"])
        venue = self.venue_net()
        if venue != realized:
            problems.append(f"deals-net-equals-realized: venue net {venue} != qkt realized {realized}")
        if fills:
            problems += self.replay(fills)
        self.evidence = {"fills": fills, "qktRealized": str(realized), "venueNet": str(venue),
                         "replayPriceDrift": getattr(self, "drift", [])}
        return problems

    def prepare(self, adapter):
        os.makedirs(f"{self.out}/strategies")
        os.makedirs(f"{self.out}/data")
        text = open(os.path.join(self.args.case, "strategy.qkt")).read()
        root = self.case.get("dated_from_root")
        if root:  # the case's contract expires; trade the root's dated contract the venue lists now
            text = text.replace(self.case["symbols"][0], self.dated_contract(root))
        self.strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
        open(f"{self.out}/strategies/{self.strategy}.qkt", "w").write(text)
        shutil.copy(os.path.join(self.args.case, "instruments.yaml"), f"{self.out}/data/instruments.yaml")
        venue = self.case["symbols"][0].split(":")[1].split(".")[0] if self.case["symbols"][0].startswith(
            ("OPTIONS:", "CHAIN:")) else self.case["symbols"][0].split(":")[0]
        account = {"type": "gateway", "gateway_url": self.args.gateway_url, "api_key": "env:QKT_DERIV_GATEWAY_KEY",
                   "expected_adapter": adapter, "expected_account_login": self.args.expected_login,
                   "expected_trade_mode": "demo", "chain_snapshot_seconds": 60}
        yaml.safe_dump({"source": "local", "data_root": f"{self.out}/data", "starting_balance": 100000,
                        "brokers": {venue.lower(): account}},
                       open(f"{self.out}/qkt.config.yaml", "w"), sort_keys=False)
        roots = {r["root"] for kind in ("futures", "options") for r in
                 (yaml.safe_load(open(f"{self.out}/data/instruments.yaml")).get(kind) or [])}
        for root in sorted(roots):
            self.cli_run(["fetch", root, "--catalog", "--config", f"{self.out}/qkt.config.yaml",
                          "--data-root", f"{self.out}/data"], "catalog")

    def dated_contract(self, root):
        family = root.split(":")[1]
        now = int(time.time() * 1000)
        dated = sorted((i["expiry"], i["code"]) for i in self.get("/v1/instruments") if i["kind"] == "future"
                       and i["code"].replace("-", "_").startswith(family + "_")
                       and 7 * 86_400_000 <= i["expiry"] - now <= 45 * 86_400_000)
        if not dated:
            raise RuntimeError(f"{root} lists no dated contract 7 to 45 days from expiry")
        return f"{root.split(':')[0]}:{dated[0][1].replace('-', '_')}"

    def cli_run(self, argv, name, env=None):
        log = f"{self.out}/{name}.log"
        with open(log, "a") as out:
            code = subprocess.call([self.cli, *argv], stdout=out, stderr=subprocess.STDOUT, env=env)
        if code != 0:
            raise RuntimeError(f"qkt {argv[0]} failed: {open(log).read().strip().splitlines()[-1:]}")

    def env(self):
        return dict(os.environ, QKT_DATA_HOME=f"{self.out}/data")

    def run_daemon(self, budget):
        log = open(f"{self.out}/daemon.log", "w")
        self.daemon = subprocess.Popen(
            [self.cli, "daemon", "start", "--config", f"{self.out}/qkt.config.yaml",
             "--state-dir", f"{self.out}/state", "--load-dir", f"{self.out}/strategies"],
            stdout=log, stderr=subprocess.STDOUT, env=self.env())
        deadline = time.time() + budget
        while "daemon ready" not in open(f"{self.out}/daemon.log").read():
            if self.daemon.poll() is not None or time.time() > deadline:
                raise RuntimeError("the daemon never became ready")
            time.sleep(1)
        self.started_ms = int(time.time() * 1000)
        while len(self.live_fills()) < int(self.case["fills"]) and time.time() < deadline:
            time.sleep(3)
        time.sleep(5)  # the last fill's venue events and costs settle
        if self.case["replay"] == "chain":  # a replay fills on the snapshot after the entry: record one past the fills
            time.sleep(MINUTE / 1000 - time.time() % 60 + 15)
        self.stop_daemon()
        self.ended_ms = int(time.time() * 1000)

    def stop_daemon(self):
        if self.daemon and self.daemon.poll() is None:
            subprocess.call([self.cli, "daemon", "stop", "--state-dir", f"{self.out}/state"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=self.env())
            try:
                self.daemon.wait(60)
            except subprocess.TimeoutExpired:
                self.daemon.kill()

    def live_fills(self):
        text = open(f"{self.out}/daemon.log").read()
        return [{"side": m[0], "symbol": m[1], "qty": m[2], "price": m[3]} for m in TRADE.findall(text)]

    def venue_net(self):
        sizes = {i["code"]: Decimal(i["contract_size"]) for i in self.get("/v1/instruments")}
        # From the session's start: an earlier run of the same case placed orders under the same ids' prefix.
        deals = self.get(f"/v1/deals?from={self.started_ms}&to={self.ended_ms + MINUTE}")["deals"]
        net = Decimal(0)
        for deal in deals:
            if not deal["client_order_id"].startswith(f"dsl-{self.strategy}-"):
                continue
            value = Decimal(deal["price"]) * Decimal(deal["quantity"]) * sizes.get(deal["symbol"], Decimal(1))
            net += value if deal["side"] == "sell" else -value
            net -= sum(Decimal(cost["amount"]) for cost in deal["costs"])
        return net

    def replay(self, fills):
        start = self.started_ms // MINUTE * MINUTE
        end = (self.ended_ms // MINUTE + 2) * MINUTE
        root = f"{self.out}/replay"
        if self.case["replay"] == "bars":
            os.makedirs(root)
            shutil.copytree(f"{self.out}/data/contracts", f"{root}/contracts")
            shutil.copy(f"{self.out}/data/instruments.yaml", root)
            for symbol in sorted({f["symbol"] for f in fills}):
                self.store_bars(root, symbol, start, end)
            data = root
        else:
            data = f"{self.out}/data"
        report = f"{self.out}/replay-report"
        self.cli_run(["backtest", f"{self.out}/strategies/{self.strategy}.qkt", "--from", when(start), "--to", when(end),
                      "--no-fetch", "--allow-incomplete", "--position-mode", "netting", "--data-root", data,
                      "--config", f"{self.out}/qkt.config.yaml", "--report-dir", report], "replay", self.env())
        trades = list(csv.DictReader(open(f"{report}/trades.csv")))
        replayed = [{"side": t["side"], "symbol": t["symbol"], "qty": t["quantity"], "price": t["price"]} for t in trades]
        if self.case["replay"] == "bars":
            shape = lambda xs: [(x["side"], x["symbol"], Decimal(x["qty"])) for x in xs]
            self.drift = [str(Decimal(t["price"]) - Decimal(f["price"])) for f, t in zip(fills, replayed)]
            return [] if shape(fills) == shape(replayed) else [f"replay-same-fills: live {fills} != replay {replayed}"]
        legs = lambda xs: sorted((x["side"], x["symbol"], Decimal(x["qty"])) for x in xs)
        opening = fills[: len(fills) // 2]
        return [] if legs(opening) == legs(replayed[: len(opening)]) \
            else [f"replay-same-legs: live opened {opening}, replay {replayed[: len(opening)]}"]

    def store_bars(self, root, symbol, start, end):
        """The gateway's closed 1m bars of [symbol] from its day's start, in the fetch store's layout."""
        venue, name = symbol.split(":")
        code = next(i["code"] for i in self.get("/v1/instruments") if i["code"].replace("-", "_") == name)
        day_start = start // 86_400_000 * 86_400_000
        last_fill_minute = self.ended_ms // MINUTE * MINUTE  # fills follow a bar close; the run ends seconds later
        deadline = time.time() + 180
        while True:  # the venue serves a bar only once its minute has closed
            bars = self.get(f"/v1/bars?symbol={code}&window_ms={MINUTE}&from={day_start}&to={end}")["bars"]
            if any(b["start"] >= last_fill_minute for b in bars) or time.time() > deadline:
                break
            time.sleep(10)
        store = f"{root}/bars/{venue}/{name}/1m"
        os.makedirs(store, exist_ok=True)
        days = {}
        for bar in bars:
            days.setdefault(datetime.datetime.fromtimestamp(bar["start"] / 1000, datetime.timezone.utc).date(), []).append(bar)
        for day, rows in days.items():
            with open(f"{store}/{day}.csv", "w") as out:
                out.write("timestamp,open,high,low,close,volume\n")
                out.writelines(f"{b['start']},{b['open']},{b['high']},{b['low']},{b['close']},{b['volume']}\n" for b in rows)
        json.dump({"broker": venue, "symbol": name, "timeframe": "1m",
                   "ranges": [{"from": str(min(days)), "to": str(max(days))}],
                   "lastUpdated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")},
                  open(f"{store}/manifest.json", "w"))

    def verdict(self, problems):
        """Writes result.json and the one-line summary; the exit code."""
        case_id = self.case["id"]
        result = {"schema": "qkt-attestation-derivatives-case-v1", "id": case_id, "lane": "derivatives",
                  "status": "failed" if problems else "passed", "problems": problems,
                  **getattr(self, "evidence", {})}
        os.makedirs(self.out, exist_ok=True)
        json.dump(result, open(f"{self.out}/result.json", "w"), indent=2)
        print(f"{result['status']} {case_id} " + ("; ".join(problems) if problems else
              f"fills={len(result.get('fills', []))} realized={result.get('qktRealized')} venue={result.get('venueNet')}"))
        return 1 if problems else 0


def when(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main())
