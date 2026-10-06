"""Expiry settlement for the derivatives lane: choosing a contract to hold through its expiry, the venue's
settlement cash, and whether qkt booked each settlement once at the venue's price.

A settlement is not a deal: the venue closes a held contract at its expiry at the settlement price (an
option's intrinsic value, a future's delivery price) and reports it on `/v1/settlements`, never on
`/v1/deals`. So the venue side of a case that holds through an expiry is its deals net plus, for every
settlement of a contract the strategy held, the holding times the price times the contract size, less
the costs the venue charged on it (Deribit's delivery fee). qkt books the same settlement as a fill that
is no order's, id `settle:<symbol>:<strategy>`, written to the strategy's order journal.
"""
import glob, json, os
from decimal import Decimal


def qkt_symbol(venue, code):
    """The qkt symbol of venue [code]: `BTC_USDC-6OCT26-83000-C` on DERIBIT is `DERIBIT:BTC_USDC_6OCT26_83000_C`."""
    return f"{venue}:{code.replace('-', '_')}"


def holding_at(deals, code, time_ms):
    """The signed quantity of [code] the strategy's [deals] left it holding at [time_ms]."""
    held = Decimal(0)
    for deal in deals:
        if deal["symbol"] == code and deal["time"] <= time_ms:
            held += Decimal(deal["quantity"]) if deal["side"] == "buy" else -Decimal(deal["quantity"])
    return held


def held_settlements(settlements, deals):
    """The [settlements] of contracts the strategy's [deals] held when they settled, each with that holding."""
    held = []
    for settlement in settlements:
        quantity = holding_at(deals, settlement["symbol"], settlement["time"])
        if quantity != 0:
            held.append(dict(settlement, holding=quantity))
    return held


def settlement_cash(held, sizes):
    """What the venue paid the strategy for its [held] settlements, their costs deducted, in the account currency.

    E.g. a long 0.01 call settled at 2120 with a 0.13 delivery fee pays 0.01 x 2120 x 1 - 0.13 = 21.07.
    """
    cash = Decimal(0)
    for settlement in held:
        size = sizes.get(settlement["symbol"], Decimal(1))
        cash += settlement["holding"] * Decimal(settlement["price"]) * size
        cash -= sum((Decimal(cost["amount"]) for cost in settlement["costs"]), Decimal(0))
    return cash


def booked_settlements(state_dir, strategy):
    """The settlements qkt booked for [strategy]: its journal's fills of no order (`settle:<symbol>:<strategy>`)."""
    booked = []
    for path in sorted(glob.glob(os.path.join(state_dir, "journal", strategy, "journal-*.jsonl"))):
        for line in open(path):
            if not line.strip():
                continue
            entry = json.loads(line)
            prefix, suffix = "settle:", f":{strategy}"
            if entry.get("kind") == "filled" and entry["id"].startswith(prefix) and entry["id"].endswith(suffix):
                booked.append(entry)
    return booked


def judge(held, booked, venue, settles):
    """The problems with qkt's settlement bookings [booked] against the venue's [held] settlements.

    Each held settlement must be booked exactly once, closing the whole holding at the venue's price; qkt
    must book no settlement the venue never made; and at least [settles] contracts must have settled.
    """
    problems = []
    if len(held) < settles:
        problems.append(f"settlement-booked-once: the venue settled {len(held)} held contract(s), the case needs {settles}")
    by_symbol = {}
    for entry in booked:
        by_symbol.setdefault(entry["symbol"], []).append(entry)
    for settlement in held:
        symbol = qkt_symbol(venue, settlement["symbol"])
        mine = by_symbol.pop(symbol, [])
        if len(mine) != 1:
            problems.append(f"settlement-booked-once: {symbol} settled once at the venue, qkt booked it {len(mine)} times")
            continue
        entry, holding = mine[0], settlement["holding"]
        side = "SELL" if holding > 0 else "BUY"
        if entry["side"] != side or Decimal(entry["qty"]) != abs(holding):
            problems.append(f"settlement-booked-once: qkt closed {entry['side']} {entry['qty']} of {symbol}, "
                            f"the venue settled a holding of {holding}")
        if Decimal(entry["price"]) != Decimal(settlement["price"]):
            problems.append(f"settlement-at-venue-price: qkt settled {symbol} at {entry['price']}, "
                            f"the venue at {settlement['price']}")
    for symbol, entries in sorted(by_symbol.items()):
        problems.append(f"settlement-booked-once: qkt booked {len(entries)} settlement(s) of {symbol} the venue never made")
    return problems


def expiring_option(instruments, spec, index, depth, now_ms):
    """The option to hold through its expiry, chosen by [spec] (the case's `expiring_option`), or an error.

    Among [instruments], the options of `spec.underlying` and `spec.right` expiring soonest within
    `min_minutes`..`max_minutes` of [now_ms], in the money by at least `in_the_money` of [index] (the
    underlying's index), nearest the money first: the first whose book ([depth] of its code: the newest
    snapshot or None) has both sides, with the ask within `max_spread` of it. Returns (code, evidence).
    """
    lo, hi = now_ms + spec.get("min_minutes", 30) * 60_000, now_ms + spec.get("max_minutes", 180) * 60_000
    options = [i for i in instruments if i["kind"] == "option" and i.get("underlying") == spec["underlying"]
               and i.get("right") == spec["right"] and lo <= i["expiry"] <= hi]
    if not options:
        raise RuntimeError(f"venue-untradeable: no {spec['underlying']} {spec['right']} expires "
                           f"{spec.get('min_minutes', 30)} to {spec.get('max_minutes', 180)} minutes from now")
    expiry = min(i["expiry"] for i in options)
    margin = Decimal(str(spec.get("in_the_money", 0.02)))
    if spec["right"] == "call":
        itm = sorted((i for i in options if i["expiry"] == expiry and Decimal(i["strike"]) <= index * (1 - margin)),
                     key=lambda i: -Decimal(i["strike"]))
    else:
        itm = sorted((i for i in options if i["expiry"] == expiry and Decimal(i["strike"]) >= index * (1 + margin)),
                     key=lambda i: Decimal(i["strike"]))
    spread = Decimal(str(spec.get("max_spread", 0.15)))
    seen = []
    for option in itm:
        book = depth(option["code"])
        if not book or not book["bids"] or not book["asks"]:
            seen.append(f"{option['code']} one-sided")
            continue
        bid, ask = Decimal(book["bids"][0][0]), Decimal(book["asks"][0][0])
        if ask - bid > ask * spread:
            seen.append(f"{option['code']} {bid}/{ask}")
            continue
        return option["code"], {"code": option["code"], "expiry": expiry, "strike": option["strike"],
                                "index": str(index), "bid": str(bid), "ask": str(ask)}
    raise RuntimeError(f"venue-untradeable: no {spec['right']} {margin} in the money has a two-sided book within "
                       f"{spread} of its ask: {seen[:8]}")
