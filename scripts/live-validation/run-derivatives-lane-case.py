#!/usr/bin/env python3
"""One derivatives attestation case on a VGP gateway account (futures, perpetuals, options).

    run-derivatives-lane-case.py --case DIR --out DIR --gateway-url URL --expected-login LOGIN
        --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH] [--budget-seconds N]

Needs QKT_DERIV_GATEWAY_KEY (the gateway's trader token) and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY.
The gateway must be on loopback, in demo trade mode, logged into --expected-login, and the account flat:
a netting account cannot tell the case's positions from anyone else's. The case's strategy runs in a
daemon until it has made `fills` fills and, when it asserts flat-account, is flat (or its budget ends), then is judged:
  - flat-account: the account ends with no position and no working order;
  - funding-booked (whenever the venue charged funding): the strategy booked every funding record the venue made
    during the run, the one realized at the close included;
  - deals-net-equals-realized: qkt's realized PnL equals the venue's deals net, fees included, less the
    funding the venue charged the account over the run (`/v1/funding`, when the gateway reports it), exactly;
  - funding-charged: the venue charged the account funding at least once during the run (a soak case that
    holds a perpetual long enough, such as `scripts/live-validation/funding-soak`, run with --budget-seconds);
  - replay-same-fills (`replay: bars`): a backtest of the same minutes on the gateway's own bars makes the
    same fills, in order, on the same sides and sizes (a bar replay fills at the next bar's open, so each
    fill's price difference from live is recorded, as the MT5 order lane does, not judged);
  - replay-same-legs (`replay: chain`): a backtest on the chain the account recorded opens the same legs;
  - with `drills` (lib/derivatives_drills.py: a daemon restart, a gateway outage or the kill switch, fired while the
    case holds): each drill did what it claims, the venue's fills of the strategy are exactly the fills qkt booked
    (venue-fills-equal-qkt-fills: none lost, none booked twice), each engine order reached the venue once
    (no-duplicate-order) and no engine id named two orders (order-id-continuity). A kill-switch drill needs the
    gateway's guardian token in QKT_DERIV_GUARDIAN_KEY.
Writes OUT/result.json and prints one `passed|failed <id> ...` line; exits non-zero on failure.
"""
import argparse, csv, datetime, json, os, re, shutil, signal, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from collections import Counter
from decimal import Decimal

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
import derivatives_drills  # noqa: E402

TRADE = re.compile(r"qkt\.trade - trade (BUY|SELL) (\S+) qty=(\S+) px=(\S+)")
MINUTE = 60_000
FUNDING_RECONCILE_S = 90  # a gateway reconciles venue history every minute; one cycle past the last fill, with margin


def main():
    ap = argparse.ArgumentParser()
    for name in ("--case", "--out", "--gateway-url", "--expected-login", "--arm"):
        ap.add_argument(name, required=True)
    ap.add_argument("--cli", default=os.path.join(os.path.dirname(__file__), "../../build/install/qkt/bin/qkt"))
    ap.add_argument("--budget-seconds", type=int, help="a soak run's budget, beyond the catalog's ten minutes")
    args = ap.parse_args()
    case = yaml.safe_load(open(os.path.join(args.case, "case.yaml")))
    lane = yaml.safe_load(open(os.path.join(os.path.dirname(__file__), "../../attestation/lanes.yaml")))["lanes"]
    budget = args.budget_seconds or int(case.get("budget_seconds", lane["derivatives"]["budget_seconds"]))
    run = Run(args, case)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))  # a stopped runner still releases and sweeps
    try:
        problems = run.execute(budget)
    except Exception as error:  # the verdict is always written, whatever went wrong
        problems = [f"runner error: {error}"]
    finally:
        try:
            run.drills.release()  # never leave the account's kill switch on, whatever failed
        except Exception as error:
            problems = problems + [f"kill switch: could not release it: {error}"]
        run.stop_daemon()
        if run.proxy:
            run.proxy.close()
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
        self.daemon = self.proxy = None
        self.started_ms = self.ended_ms = 0
        self.drills = derivatives_drills.Drills(self, derivatives_drills.check(case.get("drills") or []),
                                                os.environ.get("QKT_DERIV_GUARDIAN_KEY"))

    def get(self, path, body=None, method=None, key=None):
        for attempt in range(1, 5):
            request = urllib.request.Request(self.args.gateway_url + path, method=method)
            request.add_header("Authorization", f"Bearer {key or self.key}")
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
        if health["kill_switch"].get("all") or health["kill_switch"].get("symbols"):
            raise RuntimeError(f"the gateway's kill switch is on: {health['kill_switch']}")
        if any(d["kind"] == "kill_switch" for d in self.drills.pending) and not self.drills.guardian:
            raise RuntimeError("a kill_switch drill needs the guardian token in QKT_DERIV_GUARDIAN_KEY")
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
        funding = self.venue_funding()
        venue = self.venue_net() - sum((Decimal(f["amount"]) for f in funding), Decimal(0))
        if venue != realized:
            problems.append(f"deals-net-equals-realized: venue net {venue} != qkt realized {realized}")
        if "funding-charged" in self.case["assertions"] and not funding:
            problems.append("funding-charged: the venue charged no funding during the run")
        unbooked = self.unbooked_funding(self.ended_ms + MINUTE) if funding else []
        if unbooked:
            problems.append(f"funding-booked: the venue charged {unbooked} that the strategy never booked")
        if self.drills.records or self.drills.pending:
            problems += self.drills.problems() + self.judge_drilled(fills)
        if fills:
            problems += self.replay(fills)
        self.evidence = {"fills": fills, "qktRealized": str(realized), "venueNet": str(venue), "funding": funding,
                         "replayPriceDrift": getattr(self, "drift", [])}
        return problems

    def judge_drilled(self, fills):
        """After a drill: the venue filled exactly what qkt booked, each engine order placed at the venue once."""
        deals = self.strategy_deals()
        venue = Counter((d["side"].upper(), Decimal(d["quantity"]), Decimal(d["price"])) for d in deals)
        booked = Counter((f["side"], Decimal(f["qty"]), Decimal(f["price"])) for f in fills)
        problems = []
        if venue != booked:
            problems.append(f"venue-fills-equal-qkt-fills: venue only {dict(venue - booked)}, qkt only {dict(booked - venue)}")
        placed = {}
        for deal in deals:  # `<engine id>.<submit time>`: one engine id under two venue ids was sent twice
            placed.setdefault(deal["client_order_id"].rsplit(".", 1)[0], set()).add(deal["client_order_id"])
        for engine, sent in sorted(placed.items()):
            if len(sent) < 2:
                continue
            if venue != booked:  # a fill qkt never booked under a second id: the same order placed again
                problems.append(f"no-duplicate-order: engine order {engine} filled at the venue as {sorted(sent)}")
            else:  # qkt booked both: another order under an id the engine had already used
                problems.append(f"order-id-continuity: engine id {engine} named different orders {sorted(sent)}")
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
        url = self.args.gateway_url
        if any(d["kind"] == "gateway_outage" for d in self.drills.pending):  # the daemon reaches it through a proxy
            self.proxy = derivatives_drills.TcpProxy(*urllib.parse.urlsplit(url).netloc.split(":"))
            url = self.proxy.url
        account = {"type": "gateway", "gateway_url": url, "api_key": "env:QKT_DERIV_GATEWAY_KEY",
                   "expected_adapter": adapter, "expected_account_login": self.args.expected_login,
                   "expected_trade_mode": "demo", "chain_snapshot_seconds": 60}
        config = {"source": "local", "data_root": f"{self.out}/data", "starting_balance": 100000,
                  "brokers": {venue.lower(): account}}
        config.update(self.case.get("config", {}))  # a soak case's demo-only settings, such as risk limits
        yaml.safe_dump(config, open(f"{self.out}/qkt.config.yaml", "w"), sort_keys=False)
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
        # A thin venue (Deribit testnet) lists contracts nobody trades: no bars to warm up on, often no ask.
        # Trade the one that traded in the most minutes over the last six hours, the nearest on a tie.
        traded = [(self.recent_bars(code, now), -expiry, code) for expiry, code in dated]
        bars, _, code = max(traded)
        if bars == 0:
            raise RuntimeError(f"venue-untradeable: {root} has no dated contract 7 to 45 days out that traded in 6 hours")
        return f"{root.split(':')[0]}:{code.replace('-', '_')}"

    def recent_bars(self, code, now):
        """How many minutes [code] traded in over the six hours before [now] (a venue may send flat bars between)."""
        query = {"symbol": code, "window_ms": MINUTE, "from": now - 6 * 3_600_000, "to": now}
        bars = self.get("/v1/bars?" + urllib.parse.urlencode(query))["bars"]
        return sum(1 for bar in bars if Decimal(bar.get("volume") or "0") > 0)

    def cli_run(self, argv, name, env=None):
        log = f"{self.out}/{name}.log"
        with open(log, "a") as out:
            code = subprocess.call([self.cli, *argv], stdout=out, stderr=subprocess.STDOUT, env=env)
        if code != 0:
            raise RuntimeError(f"qkt {argv[0]} failed: {open(log).read().strip().splitlines()[-1:]}")

    def env(self):
        return dict(os.environ, QKT_DATA_HOME=f"{self.out}/data")

    def start_daemon(self, deadline):
        """Starts the daemon on the case's state directory (again, after a restart drill) and waits until it is ready."""
        log = f"{self.out}/daemon.log"
        seen = open(log).read().count("daemon ready") if os.path.exists(log) else 0
        self.daemon = subprocess.Popen(
            [self.cli, "daemon", "start", "--config", f"{self.out}/qkt.config.yaml",
             "--state-dir", f"{self.out}/state", "--load-dir", f"{self.out}/strategies"],
            stdout=open(log, "a"), stderr=subprocess.STDOUT, env=self.env())
        while open(log).read().count("daemon ready") <= seen:
            if self.daemon.poll() is not None or time.time() > deadline:
                raise RuntimeError("the daemon never became ready")
            time.sleep(1)

    def run_daemon(self, budget):
        deadline = time.time() + budget
        self.start_daemon(deadline)
        self.started_ms = int(time.time() * 1000)
        while not self.done() and time.time() < deadline:
            self.drills.tick(self.live_fills())
            time.sleep(3)
        time.sleep(5)  # the last fill's venue events and costs settle
        self.await_funding(deadline)
        if self.case["replay"] == "chain":  # a replay fills on the snapshot after the entry: record one past the fills
            time.sleep(MINUTE / 1000 - time.time() % 60 + 15)
        self.stop_daemon()
        self.ended_ms = int(time.time() * 1000)

    def done(self):
        """The case has made its fills and, when it must end flat, is flat: a partial fill is a fill event, so the
        count alone would end a case whose entry is still working (its remainder filling minutes later)."""
        if len(self.live_fills()) < int(self.case["fills"]):
            return False
        return "flat-account" not in self.case["assertions"] or self.flat()

    def await_funding(self, deadline):
        """Deribit realizes funding at every fill that changes a position, the close included, and a gateway reads it
        from the venue on its reconcile cycle: wait for that cycle to pass the last fill, then for the strategy to book
        every record, so funding realized at the close is judged, not cut off."""
        if "funding" not in self.get("/v1/health").get("capabilities", []):
            return
        last_fill = int(time.time() * 1000)
        time.sleep(FUNDING_RECONCILE_S)
        while time.time() < deadline and self.unbooked_funding(last_fill):
            time.sleep(5)

    def unbooked_funding(self, to_ms):
        """The ids of the venue's funding records from the run's start to [to_ms] the strategy has not booked."""
        charged = {f["funding_id"] for f in self.get(f"/v1/funding?from={self.started_ms}&to={to_ms}")["funding"]}
        path = f"{self.out}/state/state/{self.strategy}/funding.json"
        booked = set(json.load(open(path))["booked"]) if os.path.exists(path) else set()
        return sorted(charged - booked)

    def stop_daemon(self):
        """Stops the daemon with `qkt daemon stop`; False when it had to be killed."""
        if self.daemon and self.daemon.poll() is None:
            subprocess.call([self.cli, "daemon", "stop", "--state-dir", f"{self.out}/state"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=self.env())
            try:
                self.daemon.wait(60)
            except subprocess.TimeoutExpired:
                self.daemon.kill()
                self.daemon.wait()
                return False
        return True

    def live_fills(self):
        text = open(f"{self.out}/daemon.log").read()
        return [{"side": m[0], "symbol": m[1], "qty": m[2], "price": m[3]} for m in TRADE.findall(text)]

    def strategy_deals(self):
        """The venue's fills of the strategy's orders over the run."""
        # From the session's start: an earlier run of the same case placed orders under the same ids' prefix.
        deals = self.get(f"/v1/deals?from={self.started_ms}&to={self.ended_ms + MINUTE}")["deals"]
        # A rule's own orders are `dsl-<strategy>-…`; one a signal sized (a plain BUY) is `ORD-<strategy>-…`.
        return [d for d in deals if d["client_order_id"].startswith((f"dsl-{self.strategy}-", f"ORD-{self.strategy}-"))]

    def venue_net(self):
        sizes = {i["code"]: Decimal(i["contract_size"]) for i in self.get("/v1/instruments")}
        net = Decimal(0)
        for deal in self.strategy_deals():
            value = Decimal(deal["price"]) * Decimal(deal["quantity"]) * sizes.get(deal["symbol"], Decimal(1))
            net += value if deal["side"] == "sell" else -value
            net -= sum(Decimal(cost["amount"]) for cost in deal["costs"])
        return net

    def venue_funding(self):
        """The funding the venue charged the account over the run; the account was flat at its start, so all of it is the case's."""
        if "funding" not in self.get("/v1/health").get("capabilities", []):
            return []
        return self.get(f"/v1/funding?from={self.started_ms}&to={self.ended_ms + MINUTE}")["funding"]

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
        funding = self.replay_funding(data, sorted({f["symbol"] for f in fills}), start, end)
        self.cli_run(["backtest", f"{self.out}/strategies/{self.strategy}.qkt", "--from", when(start), "--to", when(end),
                      "--no-fetch", "--allow-incomplete", "--position-mode", "netting", "--data-root", data,
                      "--config", f"{self.out}/qkt.config.yaml", "--report-dir", report, *funding], "replay", self.env())
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

    def replay_funding(self, data, symbols, start, end):
        """Stores the traded perpetuals' funding rates for the replay from the gateway; without them, replays without funding."""
        perpetual = {i["code"].replace("-", "_") for i in self.get("/v1/instruments") if i["kind"] == "perpetual"}
        perps = [s for s in symbols if s.split(":")[1] in perpetual]
        if not perps:
            return []
        if "funding_rates" not in self.get("/v1/health").get("capabilities", []):
            return ["--funding", "off"]
        days = [when(start - 86_400_000).split("T")[0], when(end + 86_400_000).split("T")[0]]
        for symbol in perps:
            self.cli_run(["fetch", symbol, "--funding", "--from", days[0], "--to", days[1], "--config",
                          f"{self.out}/qkt.config.yaml", "--data-root", data], "funding-fetch", self.env())
        return []

    def store_bars(self, root, symbol, start, end):
        """The gateway's closed 1m bars of [symbol] from its day's start, in the fetch store's layout."""
        venue, name = symbol.split(":")
        code = next(i["code"] for i in self.get("/v1/instruments") if i["code"].replace("-", "_") == name)
        day_start = start // 86_400_000 * 86_400_000
        last_fill_minute = self.ended_ms // MINUTE * MINUTE  # fills follow a bar close; the run ends seconds later
        deadline = time.time() + 180
        while True:  # the venue serves a bar only once its minute has closed
            bars = self.fetch_bars(code, day_start, end)
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

    def fetch_bars(self, code, day_start, end):
        """Every closed 1m bar in [day_start, end): /v1/bars pages at 1000 bars, follow `next`.

        A single page covers ~16h40m, so without this the tail of the day — always
        including the live window the replay must cover — is silently dropped.
        """
        bars, since = [], day_start
        while since < end:
            page = self.get(f"/v1/bars?symbol={code}&window_ms={MINUTE}&from={since}&to={end}")
            bars += page["bars"]
            nxt = page.get("next")
            if nxt is None or nxt <= since:
                break
            since = nxt
        return bars

    def verdict(self, problems):
        """Writes result.json and the one-line summary; the exit code."""
        case_id = self.case["id"]
        result = {"schema": "qkt-attestation-derivatives-case-v1", "id": case_id, "lane": "derivatives",
                  "status": "failed" if problems else "passed", "problems": problems,
                  **getattr(self, "evidence", {})}
        if self.drills.records or self.drills.pending:
            result["drills"] = self.drills.records
        os.makedirs(self.out, exist_ok=True)
        json.dump(result, open(f"{self.out}/result.json", "w"), indent=2)
        print(f"{result['status']} {case_id} " + ("; ".join(problems) if problems else
              f"fills={len(result.get('fills', []))} realized={result.get('qktRealized')} venue={result.get('venueNet')}"))
        return 1 if problems else 0


def when(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main())
