"""The causal chain of every engine order, live against replay, compared step by step (#1523).

    python3 causal_trace.py GOLDEN_ZIP REPORT_DIR OUT_JSON [--tolerance-ms N]

Live comes from a golden bundle: the engine journal (rule decision, decision-order link, order request, venue
acknowledgement, fill, accounted fill), the MT5 transport journal (`gateway/`) and each gateway account's transport
(`venues/<LABEL>/`). Replay comes from a backtest's `--report-dir` (`orders.jsonl`, `trades.csv`). Orders are joined by
engine order id across every leg (`pair_orders`: orders several venues decide on one bar close may take swapped ids,
live numbering them by tick arrival and a replay by tick time; such a pair is matched by intent and explained as
`same-bar-decision-order`), and each step is compared:

  - decision: the order was decided on the same bar close; the live and replay decision times may differ by at most
    the tolerance (tick delivery and processing), never by a bar;
  - risk: the approved request (type, symbol, side, quantity, time in force, leg intent, closed leg, every price);
  - fill: side, quantity, price and costs of each fill;
  - accounted: the realized PnL the fill booked.

A fill price that differs is explained only by a recorded cause: the replay filled at the price the venue was asked
for (the MT5 request price, or the decision tick's quote) and the venue filled at a quote it captured between the
request and the fill (`venue-latency`). A realized PnL that differs by exactly what the explained price differences of its
symbol add up to carries that cause (`follows-explained-fill`). Anything else, and any order on one side only, is a
mismatch. Funding a venue books under no order (an accounted fill of kind FINANCING, `cost:funding <id>`) is not
an order: the replay simulates no venue funding, so it is listed under `financing`, never compared; the lane's funding
and deals-net-equals-realized checks attest it. The trace is the `trace` artifact; its `parityMismatches` counts into the attestation's parity.
"""
import csv
import json
import os
import sys
import zipfile
from decimal import Decimal, InvalidOperation

SCHEMA = "qkt-causal-trace-v1"
TOLERANCE_MS = 2_000
REQUEST_KEYS = ("orderType", "symbol", "side", "qty", "timeInForce", "closesLegId", "partialClose")


def dec(value):
    """A Decimal of [value], or None when it is blank or not a number."""
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except InvalidOperation:
        return None


def same(a, b):
    """Numbers compare by value (`0.0010` is `0.001`), anything else exactly."""
    da, db = dec(a), dec(b)
    return da == db if da is not None and db is not None else a == b


def jsonl(text):
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def live_chains(golden):
    """Every engine order of the bundle: its decision, request, transport, fills and accounting, keyed by order id."""
    orders, ticks, decisions, links, financing = {}, {}, {}, {}, []
    with zipfile.ZipFile(golden) as zf:
        names = sorted(zf.namelist())
        engine = [r for n in names if n.startswith("engine/") for r in jsonl(zf.read(n).decode())]
        transport = [r for n in names if n.startswith(("gateway/", "venues/")) for r in jsonl(zf.read(n).decode())]
    order = lambda oid: orders.setdefault(oid, {"orderId": oid, "fills": [], "accounted": [], "transport": []})
    for r in engine:
        kind = r.get("eventType", "").rsplit(".", 1)[-1]
        oid = r.get("orderId")
        if kind == "TickEvent":
            ticks.setdefault(r["symbol"], []).append(dict(r["tick"], receivedMs=r["ts"]))
        elif kind == "RuleDecisionEvent":
            decisions[r["decisionId"]] = {"ruleId": r.get("ruleId"), "barCloseMs": r.get("candle", {}).get("endTimeMs")}
        elif kind == "DecisionOrderLinkedEvent":
            links[oid] = r["decisionId"]
        elif kind == "OrderEvent" and oid:
            order(oid)["request"] = r.get("order", {})
        elif kind == "RiskRejectedEvent" and oid:
            order(oid)["rejected"] = r.get("reason")
        elif kind in ("OrderFilled", "OrderPartiallyFilled") and oid:
            order(oid)["fills"].append(dict(r["fill"], ts=r["ts"], symbol=r.get("symbol")))
        elif kind == "FillAccountedEvent" and r.get("accountedFill", {}).get("kind") == "FINANCING":
            financing.append({"id": oid, "symbol": r.get("symbol"), "ts": r["ts"],
                              "netRealized": r["accountedFill"].get("netAccountRealized")})
        elif kind == "FillAccountedEvent" and oid:
            order(oid)["accounted"].append(r["accountedFill"])
    for oid, chain in orders.items():
        if oid in links:
            chain["decision"] = dict(decisions.get(links[oid], {}), decisionId=links[oid])
    for r in transport:
        oid = r.get("engineOrderId") or _order_of_untagged(r, orders)
        if oid in orders:
            orders[oid]["transport"].append(r)
    for series in ticks.values():
        series.sort(key=lambda t: t["timestampMs"])
    return orders, ticks, financing


def _body(text):
    try:
        value = json.loads(text or "{}")
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def _order_of_untagged(exchange, orders):
    """The engine order an MT5 exchange carrying no engine id belongs to, by the position ticket its body names: a
    close (`/close_position`, `/position_close_partial`) belongs to the first order filled on that ticket at or after
    it was sent, a protection change (`/modify_sl_tp`) to the order whose fill opened the ticket before it."""
    position = _body(exchange.get("requestBody")).get("position")
    ticket = position.get("ticket") if isinstance(position, dict) else position
    if ticket is None or exchange.get("method") != "POST":
        return None
    sent = int(exchange["ts"])
    on_ticket = [(int(f["ts"]), oid) for oid, c in orders.items() for f in c["fills"]
                 if f.get("brokerOrderId") == str(ticket)]
    if exchange.get("path", "").split("?")[0] in ("/close_position", "/position_close_partial"):
        later = [f for f in on_ticket if f[0] >= sent]
        return min(later)[1] if later else None
    earlier = [f for f in on_ticket if f[0] <= sent]
    return max(earlier)[1] if earlier else None


def replay_chains(report_dir):
    """The replay's orders, keyed by order id: the approved request and each fill with its costs and realized PnL."""
    orders = {}
    with open(os.path.join(report_dir, "orders.jsonl")) as file:
        for r in jsonl(file.read()):
            request = r.get("request", {})
            orders.setdefault(request.get("orderId"), {"fills": []}).update(request=request, decision=r.get("decision"))
    with open(os.path.join(report_dir, "trades.csv")) as file:
        for row in csv.DictReader(file):
            oid = row.get("brokerOrderId") or row.get("legId")
            gross, net = dec(row.get("grossAccountRealized")) or 0, dec(row.get("netAccountRealized")) or 0
            orders.setdefault(oid, {"fills": []})["fills"].append({
                "ts": int(row["timestamp"]), "symbol": row["symbol"], "side": row["side"], "quantity": row["quantity"],
                "price": row["price"], "costs": str(gross - net), "netRealized": row["netAccountRealized"]})
    return orders


def _request_view(request):
    view = {k: request.get(k) for k in REQUEST_KEYS if k in request}
    intent = request.get("legIntent") or {}
    view["legIntent"] = {k: intent.get(k) for k in ("kind", "role")}
    for key, value in request.items():
        if "price" in key.lower() or key.lower().startswith(("stop", "take", "trail")):
            view[key] = value
    return view


def _requested_price(chain):
    """The price the venue was asked to fill at: the MT5 request's price field, when the transport recorded one."""
    for exchange in chain["transport"]:
        result = _body(exchange.get("responseBody")).get("result")
        request = result.get("request") if isinstance(result, dict) else None
        if isinstance(request, list) and len(request) > 5:
            return dec(request[5])
    return None


def _quote_between(ticks, side, price, from_ms, to_ms, received=False):
    """The captured tick stamped (or, [received], that reached qkt) between [from_ms] and [to_ms] whose quote on
    [side] is exactly [price]; None if there is none."""
    fields = ("ask", "price") if side == "BUY" else ("bid", "price")
    for tick in ticks:
        at = (tick["timestampMs"],) + ((tick.get("receivedMs", tick["timestampMs"]),) if received else ())
        if any(from_ms <= t <= to_ms for t in at) and any(same(tick.get(f), price) for f in fields):
            return tick
    return None


def _fill_cause(chain, live_fill, replay_fill, ticks):
    """Why a live and a replay fill price differ, from recorded evidence only; None when nothing recorded explains it."""
    requested = _requested_price(chain)
    decided = int(chain.get("request", {}).get("createdTs") or replay_fill["ts"])
    replay_at_request = requested is not None and same(replay_fill["price"], requested)
    replay_at_decision = _quote_between(ticks, live_fill["side"], replay_fill["price"],
                                        decided - TOLERANCE_MS, decided) is not None
    venue_quote = _quote_between(ticks, live_fill["side"], live_fill["price"], decided, int(live_fill["ts"]),
                                 received=True)
    if (replay_at_request or replay_at_decision) and venue_quote is not None:
        return {"cause": "venue-latency", "requestedPrice": str(requested) if requested is not None else None,
                "venueQuoteAtMs": venue_quote["timestampMs"], "latencyMs": int(live_fill["ts"]) - decided}
    return None


def _same_cost_rate(costs_a, price_a, costs_b, price_b):
    """Whether two fills' costs are one rate of their prices (a fee on notional), to a billionth."""
    ca, pa, cb, pb = dec(costs_a), dec(price_a), dec(costs_b), dec(price_b)
    if None in (ca, pa, cb, pb) or not pa or not pb:
        return False
    return abs(ca / pa - cb / pb) <= Decimal("0.000000001")


def compare_order(oid, live, replay, ticks, tolerance_ms):
    """The step-by-step comparison of one engine order; each step carries `match` and, when explained, `explained`."""
    steps = []
    request_l, request_r = live.get("request", {}), replay.get("request", {})
    decided_l, decided_r = request_l.get("createdTs"), request_r.get("createdTs")
    drift = abs(int(decided_l) - int(decided_r)) if decided_l is not None and decided_r is not None else None
    # A bracket's entry child carries no request of its own on either side: its parent holds the decision.
    undecided = decided_l is None and decided_r is None and bool(live) and bool(replay)
    steps.append({"step": "decision", "live": {"ms": decided_l, **live.get("decision", {})}, "replay": {"ms": decided_r},
                  "driftMs": drift, "match": undecided or (drift is not None and drift <= tolerance_ms)})
    view_l, view_r = _request_view(request_l), _request_view(request_r)
    differs = sorted(k for k in set(view_l) | set(view_r) if not same(view_l.get(k), view_r.get(k)))
    approved_l, approved_r = "rejected" not in live, replay.get("decision", "approved") == "approved"
    steps.append({"step": "risk", "live": view_l, "replay": view_r, "differs": differs,
                  "match": not differs and approved_l == approved_r})
    for exchange in live.get("transport", []):
        steps.append({"step": "transport", "live": {k: exchange.get(k) for k in
                      ("ts", "method", "path", "responseCode", "durationMs") if k in exchange}, "match": True})
    explained_price = False
    fills_l, fills_r = live.get("fills", []), replay.get("fills", [])
    for i in range(max(len(fills_l), len(fills_r))):
        fl = fills_l[i] if i < len(fills_l) else None
        fr = fills_r[i] if i < len(fills_r) else None
        step = {"step": "fill", "index": i, "live": fl, "replay": fr}
        if fl is None or fr is None:
            step["match"] = False
        else:
            accounted = live["accounted"][i] if i < len(live.get("accounted", [])) else {}
            costs_l = accounted.get("totalCostsAccount")
            step["live"] = dict(fl, costs=costs_l, contractSize=accounted.get("contractSize"),
                                reducedExposure=accounted.get("reducedExposure"))
            shape = same(fl["side"], fr["side"]) and same(fl["quantity"], fr["quantity"])
            step["match"] = shape and same(fl["price"], fr["price"]) and same(costs_l, fr["costs"])
            # Costs charged at one rate on a different price differ only because the price does.
            same_rate = same(costs_l, fr["costs"]) or _same_cost_rate(costs_l, fl["price"], fr["costs"], fr["price"])
            if shape and same_rate and not same(fl["price"], fr["price"]):
                cause = _fill_cause(live, fl, fr, ticks.get(fl.get("symbol") or request_l.get("symbol"), []))
                if cause:
                    step["explained"], explained_price = cause, True
        steps.append(step)
    for i, accounted in enumerate(live.get("accounted", [])):
        fr = fills_r[i] if i < len(fills_r) else None
        step = {"step": "accounted", "index": i, "live": {"netRealized": accounted.get("netAccountRealized")},
                "replay": {"netRealized": fr and fr["netRealized"]},
                "match": fr is not None and same(accounted.get("netAccountRealized"), fr["netRealized"])}
        steps.append(step)
    symbol = request_l.get("symbol") or request_r.get("symbol") or next(
        (f.get("symbol") for f in fills_l + fills_r if f.get("symbol")), None)
    return {"orderId": oid, "symbol": symbol, "steps": steps}


def explain_realized(orders):
    """A realized PnL that differs by exactly what explained fills account for carries their cause: a fill that adds
    exposure owes its price difference (times size and contract size) to the fill that later reduces it, and every
    fill's realized moves by its own cost difference (a fee on an explained price)."""
    fills = sorted(((s["live"]["ts"], o, s) for o in orders for s in o["steps"]
                    if s["step"] == "fill" and s.get("live") and s.get("replay")), key=lambda f: int(f[0]))
    owed = {}
    for _, order, step in fills:
        live, replay = step["live"], step["replay"]
        symbol = order["symbol"]
        explained = "explained" in step or step["match"]
        if not explained:
            owed[symbol] = None  # an unexplained fill: nothing downstream of it can be explained
            continue
        sign = 1 if live["side"] == "BUY" else -1
        size = Decimal(str(live["quantity"])) * (dec(live.get("contractSize")) or Decimal(1))
        moved = sign * (Decimal(replay["price"]) - Decimal(live["price"])) * size
        cost_gap = (dec(live.get("costs")) or 0) - (dec(replay.get("costs")) or 0)
        carried = owed.get(symbol, Decimal(0))
        if carried is None:
            continue
        if live.get("reducedExposure"):
            expected, owed[symbol] = carried + moved - cost_gap, Decimal(0)
        else:
            expected, owed[symbol] = -cost_gap, carried + moved
        accounted = next((s for s in order["steps"] if s["step"] == "accounted" and s["index"] == step["index"]), None)
        if accounted is None or accounted["match"]:
            continue
        gap = dec(accounted["live"]["netRealized"]) - (dec(accounted["replay"]["netRealized"]) or 0)
        if abs(gap - expected) <= Decimal("0.00000001"):
            accounted["explained"] = {"cause": "follows-explained-fill", "realizedGap": str(gap)}


def _intent(chain):
    request = chain.get("request") or {}
    intent = request.get("legIntent") or {}
    qty = dec(request.get("qty"))
    return (request.get("symbol"), request.get("side"), qty.normalize() if qty is not None else None,
            request.get("orderType"), intent.get("kind"), request.get("closesLegId"))


def pair_orders(live, replay, tolerance_ms):
    """Live order id -> the replay order it is compared with. The same id when both sides placed the same order under
    it; otherwise the replay order of the same intent (symbol, side, size, type, leg intent, closed leg) decided within
    the tolerance of it. Orders the engine decides on one bar close for several venues take their ids in the order
    their closing ticks are processed: live by arrival, a replay by tick time, so two venues' ids may swap."""
    pairs = {oid: oid for oid in live if oid in replay and _intent(live[oid]) == _intent(replay[oid])}
    taken = set(pairs.values())
    for oid in sorted(set(live) - set(pairs)):
        decided = (live[oid].get("request") or {}).get("createdTs")
        for other in sorted(set(replay) - taken):
            created = (replay[other].get("request") or {}).get("createdTs")
            if _intent(replay[other]) == _intent(live[oid]) and decided is not None and created is not None \
                    and abs(int(decided) - int(created)) <= tolerance_ms:
                pairs[oid] = other
                taken.add(other)
                break
    return pairs


def _same_bar_peers(live, oid, other):
    """Whether live order [oid] and the live order holding id [other] were decided on the same bar close."""
    bar = lambda chain: (chain.get("decision") or {}).get("barCloseMs")
    return other in live and bar(live[oid]) is not None and bar(live[oid]) == bar(live[other])


def trace(golden, report_dir, tolerance_ms=TOLERANCE_MS):
    """The causal trace of a golden bundle against its replay; `parityMismatches` counts unexplained differences."""
    live, ticks, financing = live_chains(golden)
    replay = replay_chains(report_dir)
    pairs = pair_orders(live, replay, tolerance_ms)
    unpaired = sorted(set(replay) - set(pairs.values()))
    orders = []
    for oid in sorted(set(live) | set(unpaired)):
        other = pairs.get(oid, oid if oid not in live else None)
        order = compare_order(oid, live.get(oid, {}), replay.get(other, {}) if other else {}, ticks, tolerance_ms)
        if other and other != oid:
            same_bar = _same_bar_peers(live, oid, other)
            order["replayOrderId"] = other
            order["steps"].insert(0, {"step": "order-id", "live": oid, "replay": other, "match": False, **(
                {"explained": {"cause": "same-bar-decision-order"}} if same_bar else {})})
        order["liveOnly"], order["replayOnly"] = oid in live and other is None, oid not in live
        orders.append(order)
    explain_realized(orders)
    mismatches, explained = [], []
    for o in orders:
        for s in o["steps"]:
            if not s["match"]:
                (explained if "explained" in s else mismatches).append(
                    {"orderId": o["orderId"], "step": s["step"], "index": s.get("index"),
                     **({"cause": s["explained"]["cause"]} if "explained" in s else {})})
    return {"schema": SCHEMA, "toleranceMs": tolerance_ms, "orders": orders, "parityMismatches": len(mismatches),
            "mismatches": mismatches, "explained": explained, "financing": financing}


def main(argv):
    golden, report_dir, out = argv[:3]
    tolerance = int(argv[argv.index("--tolerance-ms") + 1]) if "--tolerance-ms" in argv else TOLERANCE_MS
    result = trace(golden, report_dir, tolerance)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w") as file:
        json.dump(result, file, indent=1, default=str)
    print(f"parityMismatches={result['parityMismatches']} explained={len(result['explained'])} "
          f"orders={len(result['orders'])}")
    return 1 if result["parityMismatches"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
