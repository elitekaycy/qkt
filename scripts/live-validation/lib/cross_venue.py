"""The cross lane's rules (`run-cross-lane-case.py`): one daemon trading several venues at once, judged per venue.

A cross case declares its venues (one MT5 account and one or more VGP gateway accounts, each by the broker prefix its
strategy trades through) and is judged venue by venue: the fills each venue made, whether each ends flat, whether
qkt's realized on that venue's symbols equals that venue's own deals net, and whether a replay made the same fills on
each venue. Everything here is pure: the runner reads the venues and the daemon, these functions decide.
"""
import json, re
from collections import Counter
from decimal import Decimal

FILLED = re.compile(r"order filled order_id=(\S+) strategy_id=(\S+) symbol=(\S+) side=([A-Z]+) qty=([0-9.]+) "
                    r"price=([0-9.]+)")
LABEL = re.compile(r"^[A-Z][A-Z0-9_]*$")
QKT_MONEY_UNIT = Decimal("0.00000001")  # qkt Money.SCALE = 8: a gateway leg agrees to within one unit a fill
MT5_CENT = Decimal("0.01")  # the MT5 venue truncates each closing deal's profit to whole cents
WINDOWS = {"kill_switch": ("kill engaged", "kill released"), "gateway_outage": ("outage begins", "outage ends")}


def venue_of(symbol):
    """The broker prefix a qkt symbol trades through: `EXNESS:BTCUSD` -> `EXNESS`."""
    return symbol.split(":", 1)[0].upper() if ":" in symbol else "BACKTEST"


def check_venues(case):
    """What is wrong with a cross case's `venues`, `symbols`, `fills` and drill targets; empty when it is sound."""
    venues = case.get("venues") or []
    problems = []
    labels = [v.get("label") for v in venues]
    if len(venues) < 2:
        problems.append("a cross case declares at least two venues")
    for venue in venues:
        if not LABEL.match(str(venue.get("label", ""))):
            problems.append(f"venue {venue}: label must be an upper-case broker prefix")
        if venue.get("type") not in ("mt5", "gateway"):
            problems.append(f"venue {venue.get('label')}: type must be mt5 or gateway")
    if len(set(labels)) != len(labels):
        problems.append(f"venue labels repeat: {labels}")
    kinds = Counter(v.get("type") for v in venues)
    if kinds["mt5"] > 1:
        problems.append("a cross case holds at most one MT5 venue: it runs under one magic")
    if kinds["gateway"] < 1:
        problems.append("a cross case holds at least one gateway venue")
    for symbol in case.get("symbols") or []:
        if venue_of(symbol) not in labels:
            problems.append(f"symbol {symbol} names no declared venue")
    fills = case.get("fills")
    if not isinstance(fills, dict) or set(fills) != set(labels) or \
            not all(isinstance(n, int) and n >= 0 for n in fills.values()):
        problems.append(f"fills must give every venue a fill count: {fills}")
    gateways = {v.get("label") for v in venues if v.get("type") == "gateway"}
    for drill in case.get("drills") or []:
        if drill.get("venue") not in gateways:
            problems.append(f"drill {drill}: venue must name one of the gateway venues {sorted(gateways)}")
    return problems


def daemon_config(venues, out, risk, case_config=None):
    """The one daemon config of a cross case: a broker entry per venue, named after its prefix.

    [venues] are the runner's resolved venues: `label`, `type`, `url` (what the daemon dials, a drill's proxy for a
    gateway under an outage drill), `login`, plus `server` and `magic` for MT5 and `adapter` and `key_env` for a
    gateway. A case's `config:` is merged over it, as in the other lanes."""
    brokers = {}
    for v in venues:
        if v["type"] == "mt5":
            brokers[v["label"].lower()] = {
                "type": "mt5", "extends": "exness", "calendars": {"BTC*": "crypto", "ETH*": "crypto"},
                "gateway_url": v["url"], "magic": v["magic"], "server_time_zone": "Etc/UTC",
                "expected_account_login": int(v["login"]), "expected_account_server": v["server"],
                "expected_trade_mode": "demo", "tick_poll_interval_ms": 100, "poll_interval_ms": 1000}
        else:
            brokers[v["label"].lower()] = {
                "type": "gateway", "gateway_url": v["url"], "api_key": f"env:{v['key_env']}",
                "expected_adapter": v["adapter"], "expected_account_login": str(v["login"]),
                "expected_trade_mode": "demo", "chain_snapshot_seconds": 60}
    config = {"source": "local", "data_root": f"{out}/data", "log_level": "info", "runtime": {"mode": "dev"},
              "account": {"currency": "USD"}, "starting_balance": 100000, "brokers": brokers, "risk": dict(risk),
              "state": {"enabled": True, "async": True}, "insights": {"enabled": False}}
    return merge(config, case_config or {})


def merge(base, over):
    for key, value in over.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            merge(base[key], value)
        else:
            base[key] = value
    return base


def parse_fills(text):
    """Every `order filled` line of a daemon or replay log, in order, with the venue it filled on."""
    return [{"orderId": m[0], "strategy": m[1], "symbol": m[2], "side": m[3], "qty": m[4], "price": m[5],
             "venue": venue_of(m[2])} for m in FILLED.findall(text)]


def journal_fills(lines):
    """The `filled` entries of a strategy's order journal (JSON lines), in order: the fills with their epoch ms."""
    fills = []
    for line in lines:
        entry = json.loads(line) if line.strip() else {}
        if entry.get("kind") == "filled":
            fills.append({"ts": entry["ts"], "orderId": entry["id"], "symbol": entry["symbol"], "side": entry["side"],
                          "qty": entry["qty"], "price": entry["price"], "venue": venue_of(entry["symbol"])})
    return fills


def by_venue(fills, labels):
    """[fills] grouped by venue, every label in [labels] present; a fill on an undeclared venue keeps its own key."""
    grouped = {label: [] for label in labels}
    for fill in fills:
        grouped.setdefault(fill["venue"], []).append(fill)
    return grouped


def judge_fill_counts(grouped, expected):
    """fills-by-venue: each venue made at least its expected fills, and no fill landed on a venue the case never named."""
    problems = [f"fills-by-venue: {label} expected {want} fill(s), saw {len(grouped.get(label, []))}"
                for label, want in expected.items() if len(grouped.get(label, [])) < want]
    stray = sorted(label for label, fills in grouped.items() if label not in expected and fills)
    if stray:
        problems.append(f"fills-by-venue: fills on venues the case never declared: {stray}")
    return problems


def judge_flat(flat, venues):
    """flat-by-magic (the MT5 venue) and flat-by-strategy (each gateway), as the strategy's own exits left them."""
    names = {"mt5": "flat-by-magic", "gateway": "flat-by-strategy"}
    return [f"{names[v['type']]}: {v['label']} still held a position or a working order when the case ended"
            for v in venues if not flat.get(v["label"], False)]


def realized_by_venue(outcomes, labels):
    """qkt's realized from executions, per venue: the strategy's `trade-history.json` outcomes summed by prefix.

    Each outcome is one fill's net realized (costs included), the amount qkt's one fill fold wrote to the strategy's
    PnL; funding and other non-execution amounts are not outcomes."""
    realized = {label: Decimal(0) for label in labels}
    for outcome in outcomes:
        label = venue_of(outcome["symbol"])
        realized[label] = realized.get(label, Decimal(0)) + Decimal(outcome["pnl"])
    return realized


def tolerance(kind, closing_deals, fills):
    """How far qkt may sit from a venue's deals net: a cent a closing MT5 deal, one qkt money unit a gateway fill."""
    return MT5_CENT * closing_deals if kind == "mt5" else QKT_MONEY_UNIT * max(fills, 1)


def judge_deals_net(label, kind, qkt, venue, closing_deals, fills):
    """deals-net-by-venue: qkt's execution realized on [label]'s symbols equals that venue's deals net."""
    allowed = tolerance(kind, closing_deals, fills)
    if abs(Decimal(qkt) - Decimal(venue)) > allowed:
        return [f"deals-net-by-venue: {label} venue net {venue} != qkt realized {qkt} (allowed {allowed})"]
    return []


def judge_total(pnl_realized, executions, venue_nets, funding, allowed):
    """realized-total-equals-venues: the strategy's whole realized (`pnl.json`) equals the venues' deals nets less the
    funding the gateways charged, and what is not an execution in it is exactly that funding."""
    problems = []
    venues_total = sum((Decimal(n) for n in venue_nets.values()), Decimal(0)) - funding
    if abs(Decimal(pnl_realized) - venues_total) > allowed:
        problems.append(f"realized-total-equals-venues: qkt realized {pnl_realized} != venues' net {venues_total}")
    other = Decimal(pnl_realized) - sum((Decimal(e) for e in executions.values()), Decimal(0))
    if abs(other + funding) > QKT_MONEY_UNIT * max(len(executions), 1):
        problems.append(f"realized-total-equals-venues: qkt booked {other} outside executions, the venues charged "
                        f"{-funding} funding")
    return problems


def with_risk(fills):
    """[fills] in order, each marked `adds` when it grew its symbol's position (an entry or a flip), else reducing."""
    held, marked = {}, []
    for fill in fills:
        before = held.get(fill["symbol"], Decimal(0))
        after = before + (Decimal(fill["qty"]) if fill["side"] == "BUY" else -Decimal(fill["qty"]))
        held[fill["symbol"]] = after
        flipped = before != 0 and after != 0 and (before > 0) != (after > 0)
        marked.append(dict(fill, adds=abs(after) > abs(before) or flipped))
    return marked


def drill_window(record):
    """(start ms, end ms) a kill-switch or outage drill held its venue down, from its timeline; None if it never did."""
    names = WINDOWS.get(record.get("kind"))
    marks = {e["event"]: e["ms"] for e in record.get("timeline", [])}
    if not names or names[0] not in marks or names[1] not in marks:
        return None
    return marks[names[0]], marks[names[1]]


def judge_during(record, fills, drilled):
    """While the drill held [drilled] down, every other venue still made a fill that added risk, and (a kill switch)
    [drilled] itself still filled a reducing order: the switch or the outage is the venue's, not the daemon's."""
    window = drill_window(record)
    kind = record.get("kind")
    if window is None:
        return [f"{kind} on {drilled}: the drill never held the venue down, so nothing was judged during it"]
    inside = [f for f in with_risk(fills) if window[0] <= f["ts"] <= window[1]]
    problems = []
    for venue in sorted({f["venue"] for f in fills} - {drilled}):
        if not any(f["venue"] == venue and f["adds"] for f in inside):
            problems.append(f"other-venue-trades-during-{kind.replace('_', '-')}: {venue} made no risk-adding fill "
                            f"while {drilled} was down")
    if kind == "kill_switch" and not any(f["venue"] == drilled and not f["adds"] for f in inside):
        problems.append(f"reducing-passes-during-kill: {drilled} filled no reducing order while its switch was on")
    return problems


def judge_replay_by_venue(live, replayed, labels):
    """replay-same-fills-by-venue: on each venue the replay made the same fills, in order, on the same sides and sizes.

    Returns (problems, drift): drift is each matched fill's replay price less its live price, per venue, recorded and
    not judged (a gateway leg replays its own bars or quotes, an MT5 leg the captured ticks)."""
    live_by, replay_by = by_venue(live, labels), by_venue(replayed, labels)
    problems, drift = [], {}
    shape = lambda fills: [(f["side"], f["symbol"], Decimal(f["qty"])) for f in fills]
    for label in sorted(set(live_by) | set(replay_by)):
        ours, theirs = live_by.get(label, []), replay_by.get(label, [])
        if shape(ours) != shape(theirs):
            problems.append(f"replay-same-fills-by-venue: {label} live {shape(ours)} != replay {shape(theirs)}")
        drift[label] = [str(Decimal(t["price"]) - Decimal(o["price"])) for o, t in zip(ours, theirs)]
    return problems, drift
