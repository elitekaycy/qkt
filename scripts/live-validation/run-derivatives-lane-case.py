#!/usr/bin/env python3
"""One derivatives attestation case on a VGP gateway account (futures, perpetuals, options).

    run-derivatives-lane-case.py --case DIR --out DIR --gateway-url URL --expected-login LOGIN
        --arm I_UNDERSTAND_DEMO_ORDER_0.01 [--cli PATH] [--budget-seconds N]

Needs QKT_DERIV_GATEWAY_KEY (the gateway's trader token) and QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY.
The gateway must be on loopback, in demo trade mode and logged into --expected-login. The account is shared (release
attestation and the live soak trade it too), so the case judges and touches only its strategy's own (lib/strategy_scope.py):
its orders and deals by their client order ids (`dsl-<strategy>-`, `ORD-<strategy>-`), its holding from its own deals,
never from the account's netted positions, which say nothing of who holds them. Before the case the strategy must own no
working order and its deals of the last day must net flat. The case's strategy runs in a daemon until it has made `fills`
fills and, when it asserts flat-by-strategy, is flat (or its budget ends), then is judged:
  - flat-by-strategy: the strategy ends with no working order and its own deals (less its settlements) net to nothing;
  - funding-booked (whenever the venue charged funding): the strategy booked every funding record it owes part of
    (its holding at the record's time over the account's position), the one realized at the close included;
  - deals-net-equals-realized: qkt's realized PnL equals the venue's deals net, fees included, plus the cash
    of every expiry the venue settled while the strategy held the contract (`/v1/settlements`: the holding
    times the settlement price and contract size, less the settlement's costs), less the strategy's share of the
    funding the venue charged the account over the run (`/v1/funding`, when the gateway reports it), exactly;
  - settlement-booked-once (a case that holds through an expiry, `settles: N`): each contract the strategy held
    that the venue settled was booked by qkt exactly once (its journal's `settle:<symbol>:<strategy>` fill),
    closing the whole holding at the venue's settlement price; qkt booked no settlement the venue never made;
  - account-balance-equals-realized: the account's balance moved over the case by exactly qkt's realized PnL
    (the venue's own cash, independent of how the gateway reports deals, settlements and fees); reported as not
    comparable, neither passed nor failed, when another user's deal on the account falls inside the case window;
  - funding-charged: the venue charged the account funding at least once during the run (a soak case that
    holds a perpetual long enough, such as `scripts/live-validation/funding-soak`, run with --budget-seconds);
  - replay-same-fills (`replay: bars`): a backtest of the same minutes on the gateway's own bars makes the
    same fills, in order, on the same sides and sizes (a bar replay fills at the next bar's open, so each
    fill's price difference from live is recorded, as the MT5 order lane does, not judged);
  - replay-same-legs (`replay: chain`): a backtest on the chain the account recorded opens the same legs
    (`replay: none` skips the replay: a backtest does not reproduce a venue's live expiry settlement);
  - with `drills` (lib/derivatives_drills.py: a daemon restart, a gateway outage or the kill switch, fired while the
    case holds): each drill did what it claims, the venue's fills of the strategy are exactly the fills qkt booked
    (venue-fills-equal-qkt-fills: none lost, none booked twice), each engine order reached the venue once
    (no-duplicate-order) and no engine id named two orders (order-id-continuity). A kill-switch drill needs the
    gateway's guardian token in QKT_DERIV_GUARDIAN_KEY;
  - with `continuous_roll` (lib/derivatives_roll.py): the root's roll is scheduled minutes ahead, the case holds through
    it, and what the venue reports of its legs must be what the case's `outcome` says; flat-by-strategy and
    deals-net-equals-realized apply only to a case that asserts them, since a stopped strategy may still hold;
  - deals-reported-after-restart (a `qkt_restart` drill with `capture_deals`, lib/deal_capture.py): each venue deal and
    settlement of the strategy is reported from the restarted process, past the 60 s grace, with its leg and strategy;
  - `expect_log` / `reject_log`: regexes the daemon log must or must not match when the case ends.
Before it touches the account the case waits for its venue (lib/venue_health.py: the gateway's venue link and, with
QKT_ATTEST_DERIBIT_URL on a Deribit gateway, every instrument the case will trade open with a fresh ticker: the
strategy's own, its `@front` roll's current and next contract, its dated contract or expiring option as this runner
picks it, its option legs as the daemon selects them), at most QKT_ATTEST_VENUE_WAIT_MAX_S, then samples it while it
runs; the waits, any unavailable samples and, for a failed attempt, each order the venue refused because its book was
closed (`11030 orderbook_disabled`) are written to the result (`venueWaits`, `venueOutage`) as the catalog's only
grounds to rerun the attempt for free. Past the maximum it fails
as `venue unavailable for Xh: ...`. A case bound to its lane's session (attestation/lanes.yaml, an alpaca share case)
waits at most until the latest start its window allows, and after any venue wait must pass the catalog's own session
check again (lib/attest_session.py can_start); if it no longer fits, the attempt ends as `session` (`sessionClosed` in
the result) before the judged window opens, for the resumed release to rerun in the next window. Leftovers of an earlier run of the case's own strategy (a working order, a holding
its own deals of the last day attribute to it that the account still holds) are swept before the judged window opens
and written to the result (`leftoversSwept`); nobody else's order or size is touched.
Writes OUT/result.json and prints one `passed|failed <id> ...` line; exits non-zero on failure.
"""
import argparse, csv, datetime, json, os, re, shutil, signal, subprocess, sys, time, urllib.error, urllib.parse, urllib.request
from collections import Counter
from decimal import Decimal

import yaml

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
import deal_capture  # noqa: E402
import derivatives_drills  # noqa: E402
import derivatives_roll as rolling  # noqa: E402
import derivatives_settlement as settlement  # noqa: E402
import attest_session  # noqa: E402
import strategy_scope as scope  # noqa: E402
import venue_health  # noqa: E402
import venue_instruments  # noqa: E402

TRADE = re.compile(r"qkt\.trade - trade (BUY|SELL) (\S+) qty=(\S+) px=(\S+)")
MINUTE = 60_000
QKT_MONEY_UNIT = Decimal("0.00000001")  # qkt Money.SCALE = 8
FUNDING_RECONCILE_S = 90  # a gateway reconciles venue history every minute; one cycle past the last fill, with margin
PREFLIGHT_LOOKBACK_MS = 86_400_000  # a strategy's deals over the last day must net flat before a case starts
DAY_MS = 86_400_000
LEFTOVER_FLAT_WAIT_S = 60  # a swept leftover's close must show in the venue's deals and positions before the case starts
# Past its budget a case still runs: prepare, the settle and funding-reconcile waits, a chain replay's extra bar and the
# daemon's stop. A run longer than an hour (a soak) is not held back for the day's end.
RUN_TAIL_S = 300
LONGEST_DAY_BOUND_RUN_S = 3_600
DAY_START_MARGIN_MS = 10_000


def main():
    ap = argparse.ArgumentParser()
    for name in ("--case", "--out", "--gateway-url", "--expected-login", "--arm"):
        ap.add_argument(name, required=True)
    ap.add_argument("--cli", default=os.path.join(os.path.dirname(__file__), "../../build/install/qkt/bin/qkt"))
    ap.add_argument("--budget-seconds", type=int, help="a soak run's budget, beyond the catalog's ten minutes")
    args = ap.parse_args()
    case = yaml.safe_load(open(os.path.join(args.case, "case.yaml")))
    lanes = yaml.safe_load(open(os.path.join(os.path.dirname(__file__), "../../attestation/lanes.yaml")))["lanes"]
    lane = os.path.basename(os.path.dirname(os.path.abspath(args.case)))  # its catalog lane; a soak runs as derivatives
    case["lane"] = lane if lanes.get(lane, {}).get("venue_gateway") else "derivatives"
    budget = args.budget_seconds or int(case.get("budget_seconds", lanes[case["lane"]]["budget_seconds"]))
    run = Run(args, case)
    run.lane_config = lanes.get(lane, {})
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))  # a stopped runner still releases and sweeps
    try:
        problems = run.execute(budget)
    except SessionClosed as error:  # a venue wait took the case past its window: nothing was touched
        run.venue_waits, run.session_closed = error.wait, {"reason": str(error), "at": venue_health.utc(time.time() * 1000)}
        problems = [str(error)]
    except venue_health.VenueUnavailable as error:  # the venue never came back: nothing was touched
        run.venue_waits = error.wait
        problems = [str(error)]
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
        if run.capture:
            run.capture.close()
        try:
            run.sweep()
        except Exception as error:
            problems = problems + [f"sweep: the account may not be flat: {error}"]
        run.end_watch(problems)
    return run.verdict(problems)


def shared_account(case):
    """The soak and release attestation trade these accounts by design, so others' size must not refuse a case's entries.
    A case whose point is that refusal says `shared_account: false`."""
    return bool(case.get("shared_account", True))


def utc_day_wait_ms(strategy_text, now_ms, run_s):
    """How long a case must wait to run inside one UTC day: a strategy that re-arms on `TRADES.today` counts 0 again
    from 00:00 UTC, so a case crossing it re-enters after its own exit and ends holding (perpetual-drill-kill-switch on
    2026-11-03: started 23:55:07, closed 00:02:01, bought again at the 00:03 bar while the runner awaited funding).
    0 when the strategy does not read the count, the run fits before 00:00, or the run is a soak too long to wait for.
    The offline suites set QKT_ATTEST_UTC_DAY_WAIT=off: their fake venue has no day to cross."""
    if os.environ.get("QKT_ATTEST_UTC_DAY_WAIT") == "off":
        return 0
    if not re.search(r"\bTRADES\.today\b", strategy_text) or run_s > LONGEST_DAY_BOUND_RUN_S:
        return 0
    day_end = (now_ms // DAY_MS + 1) * DAY_MS
    return 0 if now_ms + run_s * 1000 < day_end else day_end + DAY_START_MARGIN_MS - now_ms


def await_one_utc_day(strategy_text, run_s, label):
    """Sleeps until a run of [run_s] seconds stays inside one UTC day (utc_day_wait_ms), saying why on stderr."""
    wait = utc_day_wait_ms(strategy_text, int(time.time() * 1000), run_s)
    if wait:
        print(f"{label}: the strategy re-arms on TRADES.today and the run would cross 00:00 UTC; waiting "
              f"{wait // 1000}s for the new day", file=sys.stderr, flush=True)
        time.sleep(wait / 1000)


class SessionClosed(RuntimeError):
    """A venue wait took a session-bound case past the latest start its window allows."""

    def __init__(self, reason, wait=()):
        super().__init__(reason)
        self.wait = list(wait)


class Run:
    roll = capture = resumed = stopped_at = None  # class defaults: the cross lane's runner has its own __init__
    watch = leftovers_swept = session_closed = None
    dated_pick = expiring_pick = None  # what the venue gate resolved: the contract the case then trades
    venue_waits = ()
    lane_config = {}

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
        # A venue blip makes the gateway answer 503 until its link is back (Deribit testnet drops a socket for
        # ~10-20 s now and then), so a 5xx or a refused connection is retried for up to 90 s; a 4xx is the answer.
        deadline, attempt = time.time() + 90, 0
        while True:
            attempt += 1
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
                # 501 is the gateway saying the venue has no such thing, not a blip.
                if error.code < 500 or error.code == 501 or time.time() > deadline:
                    raise
            except OSError:
                if time.time() > deadline:
                    raise
            time.sleep(min(attempt, 5))

    def sweep(self):
        """Leaves the strategy flat once the case has started it, however the case ended (a venue outage mid-trade):
        cancels its own working orders and closes its own holding, never another user's (lib/strategy_scope.py)."""
        if not getattr(self, "owns_strategy", False):
            return
        for order in self.own(self.get("/v1/orders")["orders"]):
            try:
                self.get(f"/v1/orders/{urllib.parse.quote(order['client_order_id'])}", method="DELETE")
            except urllib.error.HTTPError as error:
                if error.code != 404:  # not_found: the order already ended (the venue cancelled it)
                    raise
        held = self.holdings()
        if held:
            for order in scope.sweep_orders(held, self.get("/v1/positions")["positions"], self.strategy,
                                            int(time.time() * 1000)):
                self.get("/v1/orders", order, "POST")
        deadline = time.time() + 30
        while not self.flat():
            if time.time() > deadline:
                raise RuntimeError("the strategy still holds a position or a working order")
            time.sleep(2)

    def own(self, entries):
        """The orders or deals of [entries] that are the strategy's, its legs of the case's roll included."""
        prefix = self.roll["prefix"] if self.roll else None
        return [e for e in entries if scope.is_own(e.get("client_order_id"), self.strategy)
                or (prefix and str(e.get("client_order_id", "")).startswith(prefix))]

    def window_to(self):
        """The end of the venue history a read covers: a minute past the case's end, or past now while it runs."""
        return max(self.ended_ms, int(time.time() * 1000)) + MINUTE

    def own_deals(self, since_ms=None):
        """The venue's fills of the strategy's orders from [since_ms] (the case's start by default)."""
        since = self.started_ms if since_ms is None else since_ms
        to = self.window_to()
        return scope.in_window(self.own(self.get(f"/v1/deals?from={since}&to={to}")["deals"]), since, to)

    def holdings(self, since_ms=None):
        """The strategy's own signed holding per venue symbol, from its deals less what the venue settled of it."""
        deals = self.own_deals(since_ms)
        since = self.started_ms if since_ms is None else since_ms
        settled = settlement.held_settlements(
            self.get(f"/v1/settlements?from={since}&to={self.window_to()}")["settlements"], deals) if deals else []
        return scope.holdings(deals, settled)

    def flat(self, since_ms=None):
        """flat-by-strategy: no working order of the strategy's, and its own deals (less its settlements) net flat."""
        return not self.own(self.get("/v1/orders")["orders"]) and not self.holdings(since_ms)

    def leftovers(self, since_ms):
        """The strategy's working orders, and what its deals since [since_ms] attribute to it that the account still
        holds. A drill's close often carries another label (the kill switch, the guardian, a sweep), so the deals alone
        may never net; the venue's empty position settles it (strategy_scope.account_held)."""
        working = self.own(self.get("/v1/orders")["orders"])
        held = self.holdings(since_ms)
        positions = self.get("/v1/positions")["positions"]
        return working, (scope.account_held(held, positions) if held else {}), positions

    def preflight_clear(self, since_ms):
        """Before a case: no working order of the strategy's, and nothing its deals since [since_ms] attribute to it
        that the account still holds. What an earlier run of the case left (one an outage cut off before its own sweep)
        is swept first: its own orders cancelled, its own holding closed under its own id (strategy_scope.sweep_orders),
        then the account must show it flat. The strategy is the case's alone, so its deals are earlier runs of this case;
        nobody else's order or size is touched. Once clear, the case owns the strategy and judges from now, never from
        the older deals or the sweep, so a sweep can never count toward this attempt or excuse an earlier one."""
        working, held, positions = self.leftovers(since_ms)
        if (working or held) and not self.sweep_leftovers(since_ms, working, held, positions):
            return False
        self.owns_strategy = True
        self.started_ms = int(time.time() * 1000)  # the run's own start replaces it; a sweep before then reads from here
        return True

    def sweep_leftovers(self, since_ms, working, held, positions):
        """Cancels [working] and closes [held] (the strategy's own, from earlier runs), waits until the account shows
        the strategy clear, and records what it did in `leftovers_swept`. False when it is still not clear."""
        now = int(time.time() * 1000)
        record = {"at": venue_health.utc(now), "lookbackFromMs": since_ms, "held": {k: str(v) for k, v in held.items()},
                  "cancelled": [], "orders": []}
        label = getattr(self, "label", None) or (getattr(self, "case", None) or {}).get("id", "case")
        venue_health.notice(f"{label}: strategy {self.strategy} still holds {record['held'] or 'nothing'} and "
                            f"{len(working)} working order(s) from an earlier run; sweeping them before the case")
        for order in working:
            try:
                self.get(f"/v1/orders/{urllib.parse.quote(order['client_order_id'])}", method="DELETE")
            except urllib.error.HTTPError as error:
                if error.code != 404:  # not_found: the order already ended
                    raise
            record["cancelled"].append(order["client_order_id"])
        for order in scope.sweep_orders(held, positions, self.strategy, now) if held else []:
            self.get("/v1/orders", order, "POST")
            record["orders"].append(order)
        self.leftovers_swept = record
        deadline = time.time() + LEFTOVER_FLAT_WAIT_S
        while True:
            working, held, _ = self.leftovers(since_ms)
            if not working and not held:
                venue_health.notice(f"{label}: strategy {self.strategy} swept clear")
                return True
            if time.time() > deadline:
                record["left"] = {"held": {k: str(v) for k, v in held.items()}, "working": len(working)}
                return False
            time.sleep(2)

    def venue_probes(self):
        """The case's venue, as lib/venue_health.py samples it: every instrument the case will trade."""
        text = open(os.path.join(self.args.case, "strategy.qkt")).read()
        return [venue_health.Probe(getattr(self, "label", None) or self.case.get("lane", "gateway"),
                                   self.args.gateway_url, self.key, venue_health.strategy_symbols(text),
                                   resolve=lambda listing, at_ms, quotes_of: self.traded_codes(text, listing, at_ms,
                                                                                               quotes_of))]

    def traded_codes(self, text, listing, at_ms, quotes_of):
        """The codes the case picks at run time (lib/venue_instruments.py), as prepare() and the daemon pick them: its
        roll's pair, its dated contract or expiring option (kept, so the case trades what the gate waited on), and its
        option structures' legs."""
        roll, picks = self.case.get("continuous_roll"), []
        if self.case.get("dated_from_root"):
            self.dated_pick = self.dated_pick or self.dated_contract(self.case["dated_from_root"])
            picks.append(self.dated_pick)
        if self.case.get("expiring_option"):
            self.expiring_pick = self.expiring_pick or self.expiring_option(self.case["expiring_option"])
            picks.append(self.expiring_pick)
        names = {pick.split(":", 1)[1] for pick in picks}
        dated = [i["code"] for i in listing if venue_instruments.underscored(i["code"]) in names]
        return venue_instruments.case_codes(
            text, listing, at_ms, quotes_of, roll_at_ms=rolling.roll_at(at_ms, roll["lead_seconds"]) if roll else None,
            priced=lambda code: self.recent_bars(code, at_ms) >= 0, dated=dated)

    def session_bound(self):
        """(seconds a venue wait may last, whether the session sets it): for a case bound to its lane's window, no
        longer than the latest start that still finishes inside the window; None for a 24/7 case."""
        session = self.lane_config.get("session")
        if not session or self.case.get("session") == "none":
            return None, False
        now = datetime.datetime.fromtimestamp(time.time(), datetime.timezone.utc)
        _, closes = attest_session.current_or_next_window(session, now)
        latest = closes - datetime.timedelta(seconds=attest_session.budget_of(self.case, self.lane_config))
        left = max((latest - now).total_seconds(), 0)
        most = venue_health.env_seconds("QKT_ATTEST_VENUE_WAIT_MAX_S", venue_health.DEFAULT_WAIT_MAX_S)
        return (left, True) if left < most else (most, False)

    def session_fits(self):
        """The catalog's own per-case check (attest_session.can_start), now."""
        now = datetime.datetime.fromtimestamp(time.time(), datetime.timezone.utc)
        return attest_session.can_start(self.case, self.lane_config, now)

    def await_venue(self, probes):
        """Waits for the venue before the attempt touches anything, then samples it for the rest of the attempt. A
        session-bound case waits no longer than its window allows and, after a wait, must still fit its window."""
        bound, by_session = self.session_bound()
        session = self.lane_config.get("session")
        try:
            self.venue_waits = venue_health.await_available(probes, max_s=bound)
        except venue_health.VenueUnavailable as error:
            if by_session:
                venue_health.notice(f"{self.case.get('id', 'case')}: the venue wait reached the {session} window's latest "
                                    "start; ending the attempt as session")
                raise SessionClosed(f"session: the venue was unavailable until past the latest start of the "
                                    f"{session} window ({error}); rerun in the next window", error.wait)
            raise
        if self.venue_waits and session and self.case.get("session") != "none" and not self.session_fits():
            venue_health.notice(f"{self.case.get('id', 'case')}: no longer fits its {session} window after the venue "
                                "wait; ending the attempt as session")
            raise SessionClosed(f"session: after a venue wait the case no longer fits its {session} window; "
                                "rerun in the next window", self.venue_waits)
        self.watch = venue_health.Watch(probes).start()

    def end_watch(self, problems):
        """Stops the sampling; a failed attempt takes one more sample, at the time it failed, and records each order the
        venue refused because its book was closed (venue_health.Watch.record_refusals)."""
        if self.watch:
            self.watch.stop()
            if problems:
                self.watch.look()
                path = f"{self.out}/daemon.log"
                self.watch.record_refusals(open(path, errors="replace").read() if os.path.exists(path) else "")

    def venue_record(self):
        """The attempt's venue evidence for its result: the waits, an outage sampled during it, what it swept first."""
        record = {}
        if self.venue_waits:
            record["venueWaits"] = list(self.venue_waits)
        outage = self.watch.outage() if self.watch else None
        if outage:
            record["venueOutage"] = outage
        if self.leftovers_swept:
            record["leftoversSwept"] = self.leftovers_swept
        if self.session_closed:
            record["sessionClosed"] = self.session_closed
        return record

    def own_positions(self):
        """The account's positions on the symbols the strategy holds now (a drill's evidence and probe price)."""
        held = self.holdings()
        return [p for p in self.get("/v1/positions")["positions"] if p["symbol"] in held]

    def execute(self, budget):
        text = open(os.path.join(self.args.case, "strategy.qkt")).read()
        await_one_utc_day(text, budget + RUN_TAIL_S, self.case.get("id", "case"))
        self.await_venue(self.venue_probes())
        await_one_utc_day(text, budget + RUN_TAIL_S, self.case.get("id", "case"))  # the venue wait may have crossed it
        health = self.get("/v1/health")
        if health["trade_mode"] != "demo" or str(health["account_login"]) != self.args.expected_login:
            raise RuntimeError("gateway is not the expected DEMO account")
        if not health["venue_connected"]:
            raise RuntimeError("gateway has no venue link")
        if health["kill_switch"].get("all") or health["kill_switch"].get("symbols"):
            raise RuntimeError(f"the gateway's kill switch is on: {health['kill_switch']}")
        if any(d["kind"] == "kill_switch" for d in self.drills.pending) and not self.drills.guardian:
            raise RuntimeError("a kill_switch drill needs the guardian token in QKT_DERIV_GUARDIAN_KEY")
        self.strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
        # An earlier run's roll legs carry that roll's own digest, so its deals alone do not net flat: a roll case checks
        # working orders only, and its own sweep has closed whatever that run left.
        lookback = 0 if self.case.get("continuous_roll") else PREFLIGHT_LOOKBACK_MS
        if not self.preflight_clear(int(time.time() * 1000) - lookback):
            raise RuntimeError(f"the strategy {self.strategy} already holds a position or a working order on the account")
        balance = Decimal(self.get("/v1/account")["balance"])
        self.prepare(health["adapter"])
        self.run_daemon(budget)
        problems = []
        flat = self.flat()
        if not flat and "flat-by-strategy" in self.case["assertions"]:
            problems.append("flat-by-strategy: the strategy holds a position or a working order after the case")
        fills = self.order_fills()
        if len(fills) < int(self.case["fills"]):
            problems.append(f"expected {self.case['fills']} fills within the budget, saw {len(fills)}")
        # qkt writes pnl.json at the strategy's first fill; a case whose entry the venue never filled (an empty
        # testnet book cancels a market order outright) has none, and is judged by the fill count above.
        pnl = f"{self.out}/state/state/{self.strategy}/pnl.json"
        if os.path.exists(pnl):
            realized = Decimal(json.load(open(pnl))["realized"])
        else:
            realized = Decimal(0)
            if fills:
                problems.append(f"qkt wrote no pnl.json for {self.strategy} although the venue reports {len(fills)} fill(s)")
        funding = scope.strategy_funding(self.venue_funding(), self.strategy_deals())
        held = settlement.held_settlements(self.venue_settlements(), self.strategy_deals())
        venue = self.venue_net() + settlement.settlement_cash(held, self.contract_sizes()) \
            - sum((Decimal(f["share"]) for f in funding), Decimal(0))
        # qkt keeps money at 8 decimals and books each fill's trade and costs at that scale, so two roundings of at
        # most half a unit each may part it from the venue's exact net by up to 1e-8 a fill; anything more is a
        # difference in what was booked.
        units = max(len(fills), len(self.strategy_deals()), 1) + len(funding)
        if "deals-net-equals-realized" in self.case["assertions"] and abs(venue - realized) > QKT_MONEY_UNIT * units:
            problems.append(f"deals-net-equals-realized: venue net {venue} != qkt realized {realized}")
        self.net_gap = str(venue - realized)
        booked = settlement.booked_settlements(f"{self.out}/state/state", self.strategy)
        if held or booked or self.case.get("settles"):
            problems += settlement.judge(held, booked, self.venue, int(self.case.get("settles", 0)))
        moved = Decimal(self.get("/v1/account")["balance"]) - balance
        if "account-balance-equals-realized" in self.case["assertions"] and flat:
            problems += self.judge_balance(moved, realized, fills)
        if "funding-charged" in self.case["assertions"] and not funding:
            problems.append("funding-charged: the venue charged no funding during the run")
        unbooked = self.unbooked_funding(self.ended_ms + MINUTE) if funding else []
        if unbooked:
            problems.append(f"funding-booked: the venue charged {unbooked} that the strategy never booked")
        if self.drills.records or self.drills.pending:
            problems += self.drills.problems() + self.judge_drilled(fills)
        if fills and self.case.get("replay", "none") != "none":
            problems += self.replay(fills)
        problems += self.judge_log() + self.judge_roll()
        if "deals-reported-after-restart" in self.case["assertions"]:
            problems += deal_capture.judge(self.capture.deals if self.capture else [], self.strategy_deals(), held, self.strategy)
        self.evidence = {"fills": fills, "qktRealized": str(realized), "venueNet": str(venue), "netGap": self.net_gap,
                         "funding": funding, "balanceMoved": str(moved),
                         "balanceNote": getattr(self, "balance_note", None),
                         "replayPriceDrift": getattr(self, "drift", [])}
        if self.roll:
            self.evidence.update(roll=self.roll, rollLegs=rolling.legs_of(self.strategy_deals(), self.roll["prefix"]),
                                 resume=self.resumed)
        if held or booked or self.case.get("settles"):
            self.evidence.update(settlements=[dict(s, holding=str(s["holding"])) for s in held], booked=booked,
                                 balanceMoved=str(moved), balanceNote=getattr(self, "balance_note", None),
                                 selected=getattr(self, "selected", None))
        return problems

    def judge_balance(self, moved, realized, fills):
        """account-balance-equals-realized, or a note saying why the balance is not comparable: another user's deal
        inside the case window moved the same cash, or Deribit's daily 08:00 settlement booked the session's funding
        and fees on contracts the case never traded (it books them on every contract the account traded that day)."""
        window = scope.in_window(self.get(f"/v1/deals?from={self.started_ms}&to={self.ended_ms + MINUTE}")["deals"],
                                 self.started_ms, self.ended_ms + MINUTE)
        traded = {f["symbol"].split(":")[-1].replace("-", "_") for f in fills}
        others = sorted({f["symbol"] for f in self.account_funding() if f["symbol"].replace("-", "_") not in traded})
        note = scope.balance_note(window, self.strategy)
        if note is None and moved != realized and others:
            note = f"not comparable: the venue also settled {others} during the case"
        if note:
            self.balance_note = note
            return []
        if moved != realized:
            return [f"account-balance-equals-realized: the balance moved {moved}, qkt realized {realized}"]
        return []

    def judge_drilled(self, fills):
        """After a drill: the venue filled exactly what qkt booked, each engine order placed at the venue once."""
        deals = self.strategy_deals()
        if self.roll:  # the engine never sees a leg; it books a refused roll's close at the closing leg's fill, while a
            # carried roll's close is followed by its open on the new contract and neither is an engine fill
            refused = self.roll["outcome"] != "carried"
            deals = [d for d in deals if not d["client_order_id"].startswith(self.roll["prefix"])
                     or (refused and rolling.kind_of(d["client_order_id"]) == "close")]
        # A continuous stream books its adjusted prices, so a roll's fills are compared by side and size; its money is
        # judged to the digit by deals-net-equals-realized.
        price = (lambda _: None) if self.roll else Decimal
        venue = Counter((d["side"].upper(), Decimal(d["quantity"]), price(d["price"])) for d in deals)
        booked = Counter((f["side"], Decimal(f["qty"]), price(f["price"])) for f in fills)
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
            text = text.replace(self.case["symbols"][0], self.dated_pick or self.dated_contract(root))
        if self.case.get("expiring_option"):  # hold an option through its expiry: the one about to expire now
            text = text.replace(self.case["symbols"][0],
                                self.expiring_pick or self.expiring_option(self.case["expiring_option"]))
        self.strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
        open(f"{self.out}/strategies/{self.strategy}.qkt", "w").write(text)
        shutil.copy(os.path.join(self.args.case, "instruments.yaml"), f"{self.out}/data/instruments.yaml")
        venue = self.venue = self.case["symbols"][0].split(":")[1].split(".")[0] if self.case["symbols"][0].startswith(
            ("OPTIONS:", "CHAIN:")) else self.case["symbols"][0].split(":")[0]
        self.contract_sizes()  # a contract leaves the listing once it expires: remember its size now
        url = self.args.gateway_url
        for drill in self.drills.pending:
            if drill.get("capture_deals"):
                self.capture = deal_capture.DealCapture()
                drill["config"] = merged(drill.get("config", {}), self.capture.config())
        if any(d["kind"] == "gateway_outage" for d in self.drills.pending):  # the daemon reaches it through a proxy
            self.proxy = derivatives_drills.TcpProxy(*urllib.parse.urlsplit(url).netloc.split(":"))
            url = self.proxy.url
        account = {"type": "gateway", "gateway_url": url, "api_key": "env:QKT_DERIV_GATEWAY_KEY",
                   "expected_adapter": adapter, "expected_account_login": self.args.expected_login,
                   "expected_trade_mode": "demo", "chain_snapshot_seconds": 60,
                   "shared_account": shared_account(self.case)}
        config = {"source": "local", "data_root": f"{self.out}/data", "starting_balance": 100000,
                  "brokers": {venue.lower(): account}}
        config.update(self.case.get("config", {}))  # a soak case's demo-only settings, such as risk limits
        yaml.safe_dump(config, open(f"{self.out}/qkt.config.yaml", "w"), sort_keys=False)
        roll = self.case.get("continuous_roll")
        if roll:  # its minute first; its contracts, and the policy's days, from the catalog and the venue's bars
            time.sleep(max(0, rolling.clear_of_expiry(int(time.time() * 1000), roll["lead_seconds"]) / 1000 - time.time()))
            at_ms = rolling.roll_at(int(time.time() * 1000), roll["lead_seconds"])
            path = f"{self.out}/data/instruments.yaml"
            yaml.safe_dump(rolling.with_policy(yaml.safe_load(open(path)), roll["root"], at_ms), open(path, "w"), sort_keys=False)
        roots = {r["root"] for kind in ("futures", "options") for r in
                 (yaml.safe_load(open(f"{self.out}/data/instruments.yaml")).get(kind) or [])}
        for root in sorted(roots):
            self.cli_run(["fetch", root, "--catalog", "--config", f"{self.out}/qkt.config.yaml",
                          "--data-root", f"{self.out}/data"], "catalog")
        if roll:
            self.prepare_roll(roll, at_ms)

    def prepare_roll(self, spec, at_ms):
        """Measures the root's rolls under the case's policy, picks the contracts of the case's roll, seeds a far
        reference if the case asks for one, and waits until the daemon should start, `start_before_seconds` ahead."""
        venue, family = spec["root"].split(":")
        catalog_path = f"{self.out}/data/contracts/{venue}/{family}.json"
        priced = {}  # venue code -> its last 1m close in six hours, None when the venue serves no bar of it

        def close_of(symbol):
            code = rolling.code_of(symbol)
            if code not in priced:
                now = int(time.time() * 1000)
                bars = self.get("/v1/bars?" + urllib.parse.urlencode(
                    {"symbol": code, "window_ms": MINUTE, "from": now - 6 * 3_600_000, "to": now}))["bars"]
                priced[code] = Decimal(bars[-1]["close"]) if bars else None
            return priced[code]

        listing = rolling.read(catalog_path)
        contracts = rolling.contracts_of_roll(listing, at_ms, lambda symbol: close_of(symbol) is not None)
        if not contracts:
            raise RuntimeError(f"venue-untradeable: {spec['root']} lists no two contracts with 1m bars in six hours "
                               f"expiring after {rolling.hhmm(at_ms)}'s day")
        if os.environ.get("QKT_ATTEST_DERIV_PARALLEL") and not rolling.is_front_pair(contracts, at_ms):
            # Groups beside this one hold dated contracts 7+ days out: a roll reaching one could share an instrument.
            raise RuntimeError(f"venue-untradeable: {spec['root']}'s roll pair {contracts[0]['symbol']} -> "
                               f"{contracts[1]['symbol']} reaches {rolling.FRONT_MAX_DAYS}+ days out, where a dated case "
                               "running beside this one trades; rerun when the venue prices its dailies")
        catalog = rolling.catalog_of_roll(listing, contracts, lambda symbol: close_of(symbol) is not None)
        rolling.write(catalog_path, catalog)
        path = f"{self.out}/data/instruments.yaml"  # the roll leaves the pair's first contract at the case's minute
        yaml.safe_dump(rolling.with_policy(yaml.safe_load(open(path)), spec["root"], at_ms,
                                           rolling.days_before_expiry(contracts[0], at_ms)), open(path, "w"), sort_keys=False)
        self.cli_run(["fetch", spec["root"], "--rolls", "--config", f"{self.out}/qkt.config.yaml",
                      "--data-root", f"{self.out}/data"], "rolls")
        history_path = f"{self.out}/data/contracts/{venue}/{family}.rolls.json"
        history = rolling.read(history_path)
        prefetched = [r for r in history["rolls"] if r["atMs"] >= at_ms]
        from_code, to_code = rolling.code_of(contracts[0]["symbol"]), rolling.code_of(contracts[1]["symbol"])
        last = close_of(contracts[0]["symbol"])
        catalog, history = rolling.with_previous_roll(catalog, history, at_ms, contracts, last)
        rolling.write(catalog_path, catalog)
        rolling.write(history_path, history)
        # A daemon takes no record dated ahead of its start (#1615): the far reference goes in once it runs, as another
        # session's live measurement of the roll would.
        self.far_seed = (history_path, at_ms, contracts, last, spec["far_reference"]) if "far_reference" in spec else None
        self.roll = {"at_ms": at_ms, "at_utc": rolling.hhmm(at_ms), "outcome": spec["outcome"], "quantity": str(spec["quantity"]),
                     "prefix": rolling.leg_prefix(spec["stream"], at_ms, self.strategy), "from_code": from_code,
                     "to_code": to_code, "reference": str(last), "prefetched": prefetched}
        for probe in self.watch.probes if self.watch else []:  # the venue gate samples the pair the roll trades
            probe.track([from_code, to_code])
        for drill in self.drills.pending:
            if "before_roll_s" in drill:
                drill["fire_at"] = at_ms / 1000 - drill["before_roll_s"]
                drill["symbols"] = [to_code]
        start = at_ms / 1000 - spec["start_before_seconds"]
        if time.time() > start + int(spec.get("start_late_seconds", 30)):
            raise RuntimeError(f"preparing took too long: the daemon should have started {int(time.time() - start)} s ago")
        time.sleep(max(0, start - time.time()))

    def seed_far_reference(self):
        """Writes a far_book case's seeded record of its roll into the history the running daemon appends to."""
        if not getattr(self, "far_seed", None):
            return
        path, at_ms, contracts, last, factor = self.far_seed
        rolling.write(path, rolling.seed_far_reference(rolling.read(path), at_ms, contracts, last, factor))
        self.far_seed = None

    def merge_config(self, over):
        """Deep-merges [over] into the daemon's config: the settings its next start runs with."""
        path = f"{self.out}/qkt.config.yaml"
        yaml.safe_dump(merged(yaml.safe_load(open(path)), over), open(path, "w"), sort_keys=False)

    def roll_tick(self):
        """Once the roll has stopped the strategy (and any kill switch is released), the operator resumes it, once."""
        if not self.roll or self.roll["outcome"] == "carried" or self.resumed is not None:
            return
        if self.drills.pending or self.drills.engaged or not self.roll_stopped():
            return
        self.stopped_at = time.time()
        run = subprocess.run([self.cli, "resume", self.strategy, "--state-dir", f"{self.out}/state"],
                             capture_output=True, text=True, env=self.env())
        self.resumed = {"exit": run.returncode, "output": (run.stdout + run.stderr).strip()[-600:]}

    def roll_stopped(self):
        log = open(f"{self.out}/daemon.log").read()
        return f"not carried for {self.strategy}" in log or "refused the roll of" in log

    def roll_done(self):
        """Whether what the case's roll is to show has happened, beyond its fills."""
        if not self.roll or self.roll["outcome"] == "carried":
            return True
        wait = int(self.case["continuous_roll"].get("post_stop_wait_seconds", 0))
        return self.resumed is not None and time.time() >= self.stopped_at + wait

    def judge_roll(self):
        if not self.roll:
            return []
        spec, log = self.case["continuous_roll"], open(f"{self.out}/daemon.log").read()
        deals = self.strategy_deals()
        legs = rolling.legs_of(deals, self.roll["prefix"])
        found = rolling.judge(self.roll["outcome"], legs, scope.holdings(deals), self.roll, log, self.strategy)
        venue, family = spec["root"].split(":")
        history = rolling.read(f"{self.out}/data/contracts/{venue}/{family}.rolls.json")
        for leg in legs:  # the collar is measured from the roll's own record: the session may have measured it live
            order = self.get("/v1/orders/" + urllib.parse.quote(leg["client_order_id"], safe=""))
            found += [f"roll-legs-collared: {p}" for p in rolling.collared_leg(order, leg, history, self.roll)]
        if "roll-priced-live" in self.case["assertions"]:
            found += [f"roll-priced-live: {p}" for p in rolling.priced_live(self.roll, history)]
        if "resume_exit" in spec:
            if self.resumed is None:
                found.append("resume: the strategy never stopped, so no resume was tried")
            elif self.resumed["exit"] != int(spec["resume_exit"]):
                found.append(f"resume: exit {self.resumed['exit']}, expected {spec['resume_exit']}: {self.resumed['output']}")
        return found

    def judge_log(self):
        log = open(f"{self.out}/daemon.log").read()
        return [f"expect_log: the daemon log never matched /{p}/" for p in self.case.get("expect_log", []) if not re.search(p, log)] \
            + [f"reject_log: the daemon log matched /{p}/" for p in self.case.get("reject_log", []) if re.search(p, log)]

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
        # A quiet hour (a weekend night) can leave every one untraded: then the nearest the venue serves bars of.
        traded = [(self.recent_bars(code, now), -expiry, code) for expiry, code in dated]
        bars, _, code = max(traded)
        if bars < 0:
            raise RuntimeError(f"venue-untradeable: {root} has no dated contract 7 to 45 days out with bars in 6 hours")
        return f"{root.split(':')[0]}:{code.replace('-', '_')}"

    def expiring_option(self, spec):
        """The qkt symbol of the option [spec] picks to hold through its expiry (lib/derivatives_settlement.py)."""
        now = int(time.time() * 1000)
        venue, name = spec["index_symbol"].split(":")
        instruments = self.get("/v1/instruments")
        index_code = next(i["code"] for i in instruments if i["code"].replace("-", "_") == name)
        query = {"symbol": index_code, "window_ms": MINUTE, "from": now - 10 * MINUTE, "to": now}
        marks = [m for m in self.get("/v1/marks?" + urllib.parse.urlencode(query))["marks"] if m.get("index")]
        if not marks:
            raise RuntimeError(f"venue-untradeable: {spec['index_symbol']} reported no index in 10 minutes")

        def book(code):
            snapshots = self.get(f"/v1/depth?symbol={urllib.parse.quote(code)}&from={now - MINUTE}&to={now + 1000}")["depth"]
            return snapshots[-1] if snapshots else None

        code, self.selected = settlement.expiring_option(instruments, spec,
                                                         Decimal(marks[-1]["index"]), book, now)
        return settlement.qkt_symbol(venue, code)

    def contract_sizes(self):
        """Each contract's size by venue code: the listing now, over the sizes remembered from earlier reads."""
        sizes = getattr(self, "sizes", {})
        sizes.update({i["code"]: Decimal(i["contract_size"]) for i in self.get("/v1/instruments")})
        self.sizes = sizes
        return sizes

    def recent_bars(self, code, now):
        """How many minutes [code] traded in over the six hours before [now] (a venue may send flat bars between), -1
        when the venue serves no bar of it at all."""
        query = {"symbol": code, "window_ms": MINUTE, "from": now - 6 * 3_600_000, "to": now}
        bars = self.get("/v1/bars?" + urllib.parse.urlencode(query))["bars"]
        return sum(1 for bar in bars if Decimal(bar.get("volume") or "0") > 0) if bars else -1

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
        self.seed_far_reference()
        while not self.done() and time.time() < deadline:
            self.drills.tick(self.live_fills())
            self.roll_tick()
            time.sleep(3)
        time.sleep(5)  # the last fill's venue events and costs settle
        self.await_funding(deadline)
        if self.case.get("replay") == "chain":  # a replay fills on the snapshot after the entry: record one past the fills
            time.sleep(MINUTE / 1000 - time.time() % 60 + 15)
        self.stop_daemon()
        self.ended_ms = int(time.time() * 1000)

    def done(self):
        """The case has made its fills, booked its `settles` expiry settlements and, when it must end flat, is flat: a
        partial fill is a fill event, so the count alone would end a case whose entry is still working (its remainder
        filling minutes later), and a case holding through an expiry is not done until qkt has booked the settlement."""
        if len(self.order_fills()) < int(self.case["fills"]) or self.drills.pending or not self.roll_done():
            return False
        settles = int(self.case.get("settles", 0))
        if settles and len(settlement.booked_settlements(f"{self.out}/state/state", self.strategy)) < settles:
            return False
        return "flat-by-strategy" not in self.case["assertions"] or self.flat()

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
        """The ids of the funding records from the run's start to [to_ms] the strategy owes part of and has not booked."""
        records = self.get(f"/v1/funding?from={self.started_ms}&to={to_ms}")["funding"]
        charged = {f["funding_id"] for f in scope.strategy_funding(records, self.strategy_deals())}
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
        """Every fill the daemon logged, an expiry settlement's close (a fill of no order) included."""
        text = open(f"{self.out}/daemon.log").read()
        return [{"side": m[0], "symbol": m[1], "qty": m[2], "price": m[3]} for m in TRADE.findall(text)]

    def order_fills(self):
        """The fills of the strategy's orders: the logged fills less the settlement closes its journal names."""
        fills = self.live_fills()
        for booked in settlement.booked_settlements(f"{self.out}/state/state", self.strategy):
            close = next((f for f in fills if f["side"] == booked["side"] and f["symbol"] == booked["symbol"]
                          and Decimal(f["qty"]) == Decimal(booked["qty"])
                          and Decimal(f["price"]) == Decimal(booked["price"])), None)
            if close:
                fills.remove(close)
        return fills

    def venue_settlements(self):
        """The expiries the venue settled over the run (`/v1/settlements`, served from the gateway's journal)."""
        return self.get(f"/v1/settlements?from={self.started_ms}&to={self.ended_ms + MINUTE}")["settlements"]

    def strategy_deals(self):
        """The venue's fills of the strategy's orders over the run."""
        # From the session's start: an earlier run of the same case placed orders under the same ids' prefix.
        to = self.window_to()
        deals = scope.in_window(self.get(f"/v1/deals?from={self.started_ms}&to={to}")["deals"], self.started_ms, to)
        # A rule's own orders are `dsl-<strategy>-…`; one a signal sized (a plain BUY) is `ORD-<strategy>-…`.
        return self.own(deals)

    def venue_net(self):
        sizes = self.contract_sizes()
        net = Decimal(0)
        for deal in self.strategy_deals():
            value = Decimal(deal["price"]) * Decimal(deal["quantity"]) * sizes.get(deal["symbol"], Decimal(1))
            net += value if deal["side"] == "sell" else -value
            net -= sum(cost_value(cost, deal) for cost in deal["costs"])
        return net

    def account_funding(self):
        """Every funding record the gateway holds for the run, its capabilities aside; none from a venue that serves no
        funding at all (Alpaca answers `/v1/funding` 501)."""
        try:
            return self.get(f"/v1/funding?from={self.started_ms}&to={self.ended_ms + MINUTE}")["funding"]
        except urllib.error.HTTPError as error:
            if error.code in (404, 501):
                return []
            raise

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
            copy_catalog(f"{self.out}/data", root)
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
        result = {"schema": "qkt-attestation-derivatives-case-v1", "id": case_id,
                  "lane": self.case.get("lane", "derivatives"),
                  "status": "failed" if problems else "passed", "problems": problems,
                  **getattr(self, "evidence", {}), **self.venue_record()}
        if self.drills.records or self.drills.pending:
            result["drills"] = self.drills.records
        os.makedirs(self.out, exist_ok=True)
        json.dump(result, open(f"{self.out}/result.json", "w"), indent=2)
        print(f"{result['status']} {case_id} " + ("; ".join(problems) if problems else
              f"fills={len(result.get('fills', []))} realized={result.get('qktRealized')} venue={result.get('venueNet')}"))
        return 1 if problems else 0


def merged(base, over):
    for key, value in over.items():
        base[key] = merged(base[key], value) if isinstance(value, dict) and isinstance(base.get(key), dict) else value
    return base


def copy_catalog(data, root):
    """A replay's instrument files: the contract catalog when the case has one (futures, options; a share or a coin
    pair has none) and instruments.yaml."""
    if os.path.isdir(f"{data}/contracts"):
        shutil.copytree(f"{data}/contracts", f"{root}/contracts")
    shutil.copy(f"{data}/instruments.yaml", root)


def cost_value(cost, deal):
    """What a deal's cost took from the account's cash. A spot pair's fee paid in its coin (`BTC` on `BTCUSD`, the VGP
    rule for a base-asset cost) took none: the deal's quantity was paid for and less of it was received, so the deal's
    own value already holds it. Any other cost is cash."""
    code = deal["symbol"].replace("_", "").replace("-", "")
    coin = cost["currency"]
    if code.startswith(coin) and len(code) > len(coin):
        return Decimal(0)
    return Decimal(cost["amount"])


def when(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main())
