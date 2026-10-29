#!/usr/bin/env python3
"""One cross attestation case: ONE qkt daemon trading an MT5 demo account and one or more VGP gateway accounts at once.

    run-cross-lane-case.py --case DIR --out DIR --gateway-url URL --expected-login N --expected-server NAME --magic N
        --venue LABEL=URL@LOGIN [--venue ...] --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH] [--budget-seconds N]

The case's `venues:` name each broker prefix its strategy trades through: the `mt5` one is the MT5 demo given by
--gateway-url/--expected-login/--expected-server under --magic (its key in QKT_BROKER_API_KEY), each `gateway` one is
given by a --venue flag (its trader token in QKT_CROSS_<LABEL>_KEY, its guardian token, for a kill-switch drill, in
QKT_CROSS_<LABEL>_GUARDIAN_KEY). Needs QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY; every venue must be on
loopback and in demo mode. Every account may be shared (release attestation and the live soak trade them too), so
the case owns only what is its own: on MT5 what its magic holds, on a gateway what its strategy's order ids hold
(lib/strategy_scope.py). The runner writes one daemon config holding every venue, checks each venue before the case
(logged into the expected demo account, kill switch off, the magic owning nothing, the strategy owning no gateway
order and its gateway deals of the last day netting flat), runs the strategy until each venue has made its `fills`
and is flat (or the budget ends), sweeps only its own orders and holdings, and judges per venue (lib/cross_venue.py):
  - fills-by-venue: each venue made its fills, no fill landed on a venue the case never named;
  - flat-by-magic (MT5) and flat-by-strategy (each gateway): the strategy's own exits left each venue flat;
  - deals-net-by-venue: qkt's realized on each venue's symbols (its trade history) equals that venue's deals net:
    the MT5 deals the strategy's order ids own (lib/owned-deals.jq, a cent a closing deal), the gateway's deals with
    their costs (one qkt money unit a fill);
  - realized-total-equals-venues: the strategy's whole realized equals the venues' nets less the gateways' funding,
    and every funding record was booked;
  - with drills (lib/derivatives_drills.py, each aimed at one gateway venue): each drill did what it claims on that
    venue, the venue filled exactly what qkt booked, and while it held the venue down every other venue still made a
    risk-adding fill (other-venue-trades-during-kill-switch / -gateway-outage) and, a kill switch, its own venue
    still filled a reducing order (reducing-passes-during-kill);
  - replay-same-fills-by-venue (`replay: by-venue`): the session's golden capture (every venue's ticks as qkt
    received them) replayed through one backtest, the MT5 leg on `--broker mt5-sim` and each perpetual leg on the
    exchange simulator, makes the same fills on each venue, in order, on the same sides and sizes; each fill's price
    difference is recorded, not judged.
Writes OUT/result.json and prints one `passed|failed <id> ...` line; exits non-zero on failure.
"""
import argparse, fcntl, glob, importlib.util, json, os, re, shutil, signal, subprocess, sys, time, types
import urllib.error, urllib.parse, urllib.request
from decimal import Decimal

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import cross_venue as cv  # noqa: E402
import derivatives_drills  # noqa: E402

_spec = importlib.util.spec_from_file_location("gateway_lane", os.path.join(HERE, "run-derivatives-lane-case.py"))
gateway_lane = importlib.util.module_from_spec(_spec)  # the gateway lanes' venue client, reused for each gateway leg
_spec.loader.exec_module(gateway_lane)

LOOPBACK = re.compile(r"http://(127\.0\.0\.1|localhost):\d+")
MINUTE = 60_000
RISK = {"max_daily_loss": "50", "max_order_qty": "0.05", "max_order_notional": "10000", "price_collar_pct": "5",
        "measured_usage_hours": "0", "max_round_trips_10m": 0, "live_equity_basis": "modeled"}
ENGINE_FAULT = re.compile(r"engine loop fault|unattributed fill dropped|the book does not hold; nothing booked")
TRADE_HISTORY_CAP = 64  # TradeHistory keeps the newest 64 outcomes a strategy


def main():
    ap = argparse.ArgumentParser()
    for name in ("--case", "--out", "--gateway-url", "--expected-login", "--expected-server", "--magic", "--arm"):
        ap.add_argument(name, required=True)
    ap.add_argument("--venue", action="append", default=[], help="a gateway venue: LABEL=URL@LOGIN")
    ap.add_argument("--cli", default=os.path.join(HERE, "../../build/install/qkt/bin/qkt"))
    ap.add_argument("--budget-seconds", type=int)
    args = ap.parse_args()
    case = yaml.safe_load(open(os.path.join(args.case, "case.yaml")))
    lanes = yaml.safe_load(open(os.path.join(HERE, "../../attestation/lanes.yaml")))["lanes"]
    budget = args.budget_seconds or int(case.get("budget_seconds", lanes["cross"]["budget_seconds"]))
    run = CrossRun(args, case)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))  # a stopped runner still releases and sweeps
    problems = []
    try:
        problems = run.execute(budget)
    except Exception as error:  # the verdict is always written, whatever went wrong
        problems = [f"runner error: {error}"]
    finally:
        problems += run.cleanup()
    return run.verdict(problems)


class Mt5Leg:
    """The case's MT5 demo account, owned by the case under its magic."""
    kind = "mt5"

    def __init__(self, label, url, login, server, magic, key):
        self.label, self.url, self.login, self.server, self.magic, self.key = label, url, login, server, magic, key
        self.owned = False

    def get(self, path, body=None, method=None):
        """One gateway call; a timeout or a 5xx is retried, as a busy shared gateway answers late."""
        for attempt in range(1, 5):
            request = urllib.request.Request(self.url + path, method=method)
            request.add_header("Authorization", f"Bearer {self.key}")
            data = None
            if body is not None:
                request.add_header("Content-Type", "application/json")
                data = json.dumps(body).encode()
            try:
                with urllib.request.urlopen(request, data, timeout=20) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code < 500 or attempt == 4:
                    raise
            except OSError:
                if attempt == 4:
                    raise
            time.sleep(attempt)

    def preflight(self):
        account = self.get("/account")
        if not (str(account.get("login")) == str(self.login) and account.get("server") == self.server
                and account.get("trade_mode") == 0 and account.get("trade_allowed") is True):
            raise RuntimeError(f"{self.label}: the MT5 gateway is not logged into the expected DEMO account")
        if not self.flat():
            raise RuntimeError(f"{self.label}: magic {self.magic} already owns a position or a pending order")
        self.owned = True
        return account

    def positions(self):
        return self.get(f"/get_positions?magic={self.magic}").get("data") or []

    def pending(self):  # filtered here as well: the gateway's magic filter on /orders is not relied upon
        return [o for o in self.get(f"/orders?magic={self.magic}").get("orders") or []
                if o.get("magic", self.magic) == self.magic]

    def flat(self):
        return not self.positions() and not self.pending()

    def sweep(self):
        """Closes whatever the magic still owns, by ticket; what it had to close is the problem."""
        if not self.owned:
            return []
        left = [p["ticket"] for p in self.positions()] + [o["ticket"] for o in self.pending()]
        for position in self.positions():
            self.get("/close_position", {"position": {"ticket": position["ticket"]}}, "POST")
        for order in self.pending():
            self.get(f"/orders/{order['ticket']}", method="DELETE")
        return [f"sweep: {self.label} magic {self.magic} still owned {left} after the case; closed"] if left else []


def gateway_leg(run, label, url, login, adapter):
    """A gateway venue's client: the gateway lanes' own Run (run-derivatives-lane-case.py), bound to this venue."""
    leg = object.__new__(gateway_lane.Run)
    leg.args = types.SimpleNamespace(gateway_url=url, expected_login=login, case=run.args.case)
    leg.key = os.environ.get(f"QKT_CROSS_{label}_KEY") or sys.exit(f"QKT_CROSS_{label}_KEY must be set")
    leg.guardian = os.environ.get(f"QKT_CROSS_{label}_GUARDIAN_KEY")
    leg.case, leg.out, leg.cli, leg.strategy = run.case, run.out, run.cli, None
    leg.started_ms = leg.ended_ms = 0
    leg.proxy = leg.daemon = None
    leg.kind, leg.label, leg.adapter, leg.url = "gateway", label, adapter, url
    leg.stop_daemon, leg.start_daemon = run.stop_daemon, run.start_daemon  # a drill restarts the one daemon
    return leg


class CrossRun:
    def __init__(self, args, case):
        if args.arm != "I_UNDERSTAND_DEMO_ORDER_0.01":
            sys.exit("--arm I_UNDERSTAND_DEMO_ORDER_0.01 is required")
        if os.environ.get("QKT_LIVE_DEMO_ORDER_APPROVAL") != "LOCALHOST_DEMO_ONLY":
            sys.exit("QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required")
        problems = cv.check_venues(case)
        if problems:
            sys.exit("the case is not a sound cross case: " + "; ".join(problems))
        if os.path.exists(args.out):
            sys.exit(f"output already exists: {args.out}")
        self.args, self.case, self.out, self.cli = args, case, os.path.abspath(args.out), os.path.abspath(args.cli)
        self.daemon, self.lock, self.evidence, self.drills = None, None, {}, []
        given = dict(self.venue_flag(v) for v in args.venue)
        self.legs = []
        for venue in case["venues"]:
            label = venue["label"]
            if venue["type"] == "mt5":
                key = os.environ.get("QKT_BROKER_API_KEY") or sys.exit("QKT_BROKER_API_KEY must be set")
                url = args.gateway_url
                leg = Mt5Leg(label, url, args.expected_login, args.expected_server, int(args.magic), key)
            else:
                if label not in given:
                    sys.exit(f"venue {label} needs --venue {label}=URL@LOGIN")
                url, login = given[label]
                leg = gateway_leg(self, label, url, login, venue["adapter"])
            if not LOOPBACK.fullmatch(url):
                sys.exit(f"venue {label}: only a loopback gateway is allowed")
            self.legs.append(leg)
        self.labels = [leg.label for leg in self.legs]

    @staticmethod
    def venue_flag(text):
        label, _, rest = text.partition("=")
        url, _, login = rest.rpartition("@")
        if not (label and url and login):
            sys.exit(f"--venue must be LABEL=URL@LOGIN: {text}")
        return label.upper(), (url, login)

    def gateways(self):
        return [leg for leg in self.legs if leg.kind == "gateway"]

    def mt5(self):
        return next((leg for leg in self.legs if leg.kind == "mt5"), None)

    def execute(self, budget):
        self.preflight()
        self.prepare()
        self.run_daemon(budget)
        return self.judge()

    def preflight(self):
        text = open(os.path.join(self.args.case, "strategy.qkt")).read()
        self.strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
        for leg in self.gateways():
            leg.strategy = self.strategy
        mt5 = self.mt5()
        if mt5:  # shared with every other lane on the demo; an exclusive run on it excludes this case
            path = f"/var/tmp/qkt-validation/LIVE-LOCK-{re.sub(r'[^A-Za-z0-9._-]', '_', mt5.server)}-{mt5.login}"
            os.makedirs(os.path.dirname(path), exist_ok=True)
            self.lock = open(path, "a")
            try:
                fcntl.flock(self.lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except OSError:
                raise RuntimeError(f"an exclusive live run holds {path}")
            self.evidence["mt5AccountInitial"] = mt5.preflight()
        for leg in self.gateways():
            health = leg.get("/v1/health")
            if health["trade_mode"] != "demo" or str(health["account_login"]) != leg.args.expected_login:
                raise RuntimeError(f"{leg.label}: the gateway is not the expected DEMO account")
            if health["adapter"] != leg.adapter:
                raise RuntimeError(f"{leg.label}: the gateway runs the {health['adapter']} adapter, not {leg.adapter}")
            if not health["venue_connected"]:
                raise RuntimeError(f"{leg.label}: the gateway has no venue link")
            if health["kill_switch"].get("all") or health["kill_switch"].get("symbols"):
                raise RuntimeError(f"{leg.label}: the gateway's kill switch is on: {health['kill_switch']}")
            if not leg.flat(int(time.time() * 1000) - gateway_lane.PREFLIGHT_LOOKBACK_MS):
                raise RuntimeError(f"{leg.label}: the strategy {self.strategy} already holds a position or a working order")
            leg.owns_strategy = True  # it was flat, so whatever the strategy holds from here on is this case's
        for leg in self.gateways():
            mine = [d for d in self.case.get("drills") or [] if d["venue"] == leg.label]
            if any(d["kind"] == "kill_switch" for d in mine) and not leg.guardian:
                raise RuntimeError(f"a kill_switch drill on {leg.label} needs QKT_CROSS_{leg.label}_GUARDIAN_KEY")
            if mine:
                self.drills.append((leg, derivatives_drills.Drills(
                    leg, derivatives_drills.check([{k: v for k, v in d.items() if k != "venue"} for d in mine]),
                    leg.guardian, broker=leg.label)))

    def prepare(self):
        for sub in ("strategies", "data", "evidence"):
            os.makedirs(f"{self.out}/{sub}", exist_ok=True)
        text = open(os.path.join(self.args.case, "strategy.qkt")).read()
        open(f"{self.out}/strategies/{self.strategy}.qkt", "w").write(text)
        instruments = os.path.join(self.args.case, "instruments.yaml")
        if os.path.exists(instruments):
            shutil.copy(instruments, f"{self.out}/data/instruments.yaml")
        venues = []
        for leg in self.legs:
            if leg.kind == "mt5":
                venues.append({"label": leg.label, "type": "mt5", "url": leg.url, "login": leg.login,
                               "server": leg.server, "magic": leg.magic})
                continue
            url = leg.url
            pending = [d for drilled, drills in self.drills if drilled is leg for d in drills.pending]
            if any(d["kind"] == "gateway_outage" for d in pending):
                leg.proxy = derivatives_drills.TcpProxy(*urllib.parse.urlsplit(url).netloc.split(":"))
                url = leg.proxy.url  # the daemon reaches this gateway through a proxy the drill cuts
            venues.append({"label": leg.label, "type": "gateway", "url": url, "login": leg.args.expected_login,
                           "adapter": leg.adapter, "key_env": f"QKT_CROSS_{leg.label}_KEY"})
        config = cv.daemon_config(venues, self.out, RISK, self.case.get("config"))
        yaml.safe_dump(config, open(f"{self.out}/qkt.config.yaml", "w"), sort_keys=False)
        cfg = f"{self.out}/qkt.config.yaml"
        roots = {r["root"] for kind in ("futures", "options") for r in
                 ((yaml.safe_load(open(instruments)) or {}).get(kind) or [])} if os.path.exists(instruments) else set()
        for root in sorted(roots):
            self.cli_run(["fetch", root, "--catalog", "--config", cfg, "--data-root", f"{self.out}/data"], "catalog")
        for leg in self.gateways():
            leg.contract_sizes()  # a contract leaves the listing once it expires: remember its size now
        mt5 = self.mt5()
        if mt5:  # a replay has no venue to ask for contract specs: copy them from the live account now
            symbols = ",".join(s for s in self.case["symbols"] if cv.venue_of(s) == mt5.label)
            self.cli_run(["instruments", "pull", "--config", cfg, "--symbols", symbols, "--out",
                          f"{self.out}/evidence/instruments-mt5.yaml"], "instruments-pull")

    def cli_run(self, argv, name, env=None):
        log = f"{self.out}/evidence/{name}.log"
        with open(log, "a") as out:
            code = subprocess.call([self.cli, *argv], stdout=out, stderr=subprocess.STDOUT, env=env or self.env())
        if code != 0:
            raise RuntimeError(f"qkt {argv[0]} failed: {open(log).read().strip().splitlines()[-1:]}")

    def env(self):
        return dict(os.environ, QKT_DATA_HOME=f"{self.out}/data")

    def start_daemon(self, deadline):
        """Starts the daemon on the case's state directory (again, after a restart drill) and waits until it is ready."""
        log = f"{self.out}/daemon.log"
        seen = open(log).read().count("daemon ready") if os.path.exists(log) else 0
        self.daemon = subprocess.Popen(
            [self.cli, "daemon", "start", "--config", f"{self.out}/qkt.config.yaml", "--state-dir", f"{self.out}/state",
             "--load-dir", f"{self.out}/strategies"], stdout=open(log, "a"), stderr=subprocess.STDOUT, env=self.env())
        while open(log).read().count("daemon ready") <= seen:
            if self.daemon.poll() is not None or time.time() > deadline:
                raise RuntimeError(f"the daemon never became ready: {open(log).read().strip().splitlines()[-1:]}")
            time.sleep(1)

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

    def log(self):
        path = f"{self.out}/daemon.log"
        return open(path).read() if os.path.exists(path) else ""

    def fills(self):
        return cv.parse_fills(self.log())

    def flat(self):
        return {leg.label: leg.flat() for leg in self.legs}

    def done(self):
        """Every venue made its fills and, once it has, every venue is flat: a case ends on its own exits."""
        grouped = cv.by_venue(self.fills(), self.labels)
        if any(len(grouped[label]) < want for label, want in self.case["fills"].items()):
            return False
        return all(self.flat().values())

    def run_daemon(self, budget):
        deadline = time.time() + budget
        self.start_daemon(deadline)
        self.started_ms = int(time.time() * 1000)
        for leg in self.gateways():
            leg.started_ms = self.started_ms
        while not self.done() and time.time() < deadline:
            for _, drills in self.drills:
                drills.tick(self.fills())
            time.sleep(3)
        time.sleep(5)  # the last fills' venue events and costs settle
        for leg in self.gateways():
            leg.await_funding(deadline)  # a funding record the gateway reconciles after the close is still booked
        self.flat_at_end = self.flat()
        self.own_log = self.log()  # the strategy's fills end here; a flatten below is the runner's
        if not all(self.flat_at_end.values()):
            subprocess.call([self.cli, "stop", self.strategy, "--flatten", "--state-dir", f"{self.out}/state",
                             "--json"], stdout=open(f"{self.out}/evidence/stop.json", "w"), stderr=subprocess.STDOUT,
                            env=self.env())
            time.sleep(10)
        self.stop_daemon()
        self.ended_ms = int(time.time() * 1000)
        for leg in self.gateways():
            leg.ended_ms = self.ended_ms

    def judge(self):
        live = cv.parse_fills(self.own_log)
        grouped = cv.by_venue(live, self.labels)
        problems = cv.judge_fill_counts(grouped, self.case["fills"])
        problems += cv.judge_flat(self.flat_at_end, [{"label": leg.label, "type": leg.kind} for leg in self.legs])
        if ENGINE_FAULT.search(self.log()):
            problems.append("no-engine-fault: an engine fault or an unattributed fill in the daemon log")
        state = f"{self.out}/state/state/{self.strategy}"
        realized = Decimal(json.load(open(f"{state}/pnl.json"))["realized"]) if os.path.exists(f"{state}/pnl.json") \
            else Decimal(0)
        outcomes = json.load(open(f"{state}/trade-history.json"))["outcomes"] \
            if os.path.exists(f"{state}/trade-history.json") else []
        if len(outcomes) >= TRADE_HISTORY_CAP:
            problems.append(f"deals-net-by-venue: {len(outcomes)} outcomes reach the trade history's cap; "
                            "the per-venue split is not complete")
        executions = cv.realized_by_venue(outcomes, self.labels)
        nets, funding, allowed, venues = {}, Decimal(0), Decimal(0), {}
        everything = cv.parse_fills(self.log())
        for leg in self.legs:
            mine = cv.by_venue(everything, self.labels)[leg.label]
            if leg.kind == "mt5":
                net, closing = self.mt5_deal_net(leg)
                record = {"closingDeals": closing}
            else:
                net, closing = leg.venue_net(), 0
                charged = gateway_lane.scope.strategy_funding(leg.venue_funding(), leg.strategy_deals())
                funding += sum((Decimal(f["share"]) for f in charged), Decimal(0))
                unbooked = leg.unbooked_funding(self.ended_ms + MINUTE) if charged else []
                if unbooked:
                    problems.append(f"realized-total-equals-venues: {leg.label} charged {unbooked}, never booked")
                record = {"funding": charged}
            nets[leg.label] = net
            allowed += cv.tolerance(leg.kind, closing, len(mine))
            problems += cv.judge_deals_net(leg.label, leg.kind, executions[leg.label], net, closing, len(mine))
            venues[leg.label] = dict(record, type=leg.kind, fills=grouped[leg.label], qktRealized=str(executions[leg.label]),
                                     venueNet=str(net), flat=self.flat_at_end[leg.label])
        problems += cv.judge_total(realized, executions, nets, funding, allowed)
        journal = cv.journal_fills(line for path in sorted(glob.glob(f"{self.out}/state/state/journal/{self.strategy}/"
                                                                     "journal-*.jsonl")) for line in open(path))
        for leg, drills in self.drills:
            problems += drills.problems()
            ours = [{"side": f["side"], "qty": f["qty"], "price": f["price"]} for f in grouped[leg.label]]
            problems += leg.judge_drilled(ours)
            for record in drills.records:
                if record["kind"] in cv.WINDOWS:
                    problems += cv.judge_during(record, journal, leg.label)
        self.evidence.update(venues=venues, qktRealized=str(realized), funding=str(funding),
                             total={"venuesNet": str(sum(nets.values(), Decimal(0)) - funding), "allowed": str(allowed)})
        if self.case.get("replay") == "by-venue" and live:
            problems += self.replay(live)
        return problems

    def mt5_deal_net(self, leg):
        """The MT5 deals the strategy's order ids own since the case began (lib/owned-deals.jq): (net, closing deals)."""
        history = f"{self.out}/evidence/mt5-history.json"
        with open(history, "w") as out:
            code = subprocess.call([self.cli, "bot", "history", "--broker", leg.label.lower(), "--since",
                                    str(self.started_ms), "--config", f"{self.out}/qkt.config.yaml", "--json"],
                                   stdout=out, stderr=subprocess.DEVNULL, env=self.env())
        if code != 0:
            raise RuntimeError(f"{leg.label}: the venue's deal history could not be read")
        prefixes = json.dumps([f"dsl-{self.strategy}", f"ORD-{self.strategy}-"])
        ask = lambda expr: subprocess.run(
            ["jq", "-r", "-L", os.path.join(HERE, "lib"), "--argjson", "prefixes", prefixes,
             f'include "owned-deals"; {expr}', history], check=True, capture_output=True, text=True).stdout.strip()
        return Decimal(ask("deal_net($prefixes)")), int(ask("closing_deals($prefixes)"))

    def replay(self, live):
        """One backtest of the golden capture, every leg on its own venue's captured ticks, judged per venue."""
        ev, data = f"{self.out}/evidence", f"{self.out}/replay-data"
        try:
            self.cli_run(["golden", "capture", "--session", self.strategy, "--state-dir", f"{self.out}/state",
                          "--out", f"{ev}/golden.zip"], "golden-capture")
            self.cli_run(["golden", "materialize", "--bundle", f"{ev}/golden.zip", "--out", data], "materialize",
                         dict(self.env(), QKT_BROKER_API_KEY="offline"))
        except RuntimeError as error:
            return [f"replay-same-fills-by-venue: capture or materialize failed: {error}"]
        manifest = json.load(open(f"{data}/golden-replay-manifest.json"))
        missing = [s for s in self.case["symbols"] if not os.path.isdir(f"{data}/symbols/{s.split(':', 1)[1]}")]
        if missing:
            return [f"replay-same-fills-by-venue: the capture holds no ticks of {missing}"]
        if os.path.isdir(f"{self.out}/data/contracts"):
            shutil.copytree(f"{self.out}/data/contracts", f"{data}/contracts")
        merged = {}
        for path in (f"{self.out}/data/instruments.yaml", f"{ev}/instruments-mt5.yaml"):
            if os.path.exists(path):
                for key, value in (yaml.safe_load(open(path)) or {}).items():
                    merged.setdefault(key, []).extend(value or [])
        yaml.safe_dump(merged, open(f"{data}/instruments.yaml", "w"), sort_keys=False)
        window = manifest["replayWindow"]
        funding = []
        for leg in self.gateways():
            symbols = sorted({f["symbol"] for f in live if f["venue"] == leg.label})
            funding = funding or leg.replay_funding(data, symbols, window["fromMs"], window["toMs"])
        report = f"{self.out}/replay.log"
        with open(report, "w") as out:
            subprocess.call([self.cli, "backtest", f"{self.out}/strategies/{self.strategy}.qkt", "--from",
                             window["fromUtc"], "--to", window["toUtc"], "--data-root", data, "--no-fetch",
                             "--allow-incomplete", "--config", f"{self.out}/qkt.config.yaml", "--instruments",
                             f"{data}/instruments.yaml", "--broker", "mt5-sim", "--verbose", "--json", *funding],
                            stdout=out, stderr=subprocess.STDOUT,
                            env=dict(self.env(), QKT_BROKER_API_KEY="offline", QKT_STATE_DIR=f"{self.out}/replay-state"))
        replayed = cv.parse_fills(open(report).read())
        problems, drift = cv.judge_replay_by_venue(live, replayed, self.labels)
        self.evidence["replay"] = {"window": window, "fills": replayed, "priceDrift": drift, "funding": funding}
        if not replayed:
            problems.append(f"replay-same-fills-by-venue: the replay filled nothing: "
                            f"{open(report).read().strip().splitlines()[-1:]}")
        return problems

    def cleanup(self):
        """Releases every kill switch, stops the daemon, closes the proxies and sweeps every venue it owns."""
        problems = []
        for leg, drills in self.drills:
            try:
                drills.release()
            except Exception as error:
                problems.append(f"kill switch: could not release it on {leg.label}: {error}")
        self.stop_daemon()
        for leg in self.legs:
            if getattr(leg, "proxy", None):
                leg.proxy.close()
            try:
                if leg.kind == "mt5":
                    problems += leg.sweep()
                elif getattr(leg, "owns_strategy", False) and not leg.flat():
                    leg.sweep()
                    problems.append(f"sweep: the strategy still held a position or an order on {leg.label} after "
                                    "the case; closed")
            except Exception as error:
                problems.append(f"sweep: {leg.label} may not be flat: {error}")
        return problems

    def verdict(self, problems):
        """Writes result.json and the one-line summary; the exit code."""
        result = {"schema": "qkt-attestation-cross-case-v1", "id": self.case["id"], "lane": "cross",
                  "status": "failed" if problems else "passed", "problems": problems, "magic": int(self.args.magic),
                  **self.evidence}
        if self.drills:
            result["drills"] = {leg.label: drills.records for leg, drills in self.drills}
        os.makedirs(self.out, exist_ok=True)
        json.dump(result, open(f"{self.out}/result.json", "w"), indent=2, default=str)
        per = " ".join(f"{label}:fills={len(v['fills'])},realized={v['qktRealized']},venue={v['venueNet']}"
                       for label, v in result.get("venues", {}).items())
        print(f"{result['status']} {self.case['id']} " + ("; ".join(problems) if problems else per))
        return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
