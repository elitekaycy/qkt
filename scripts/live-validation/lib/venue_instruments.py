"""The venue instruments a gateway lane case will trade, resolved the way the runner and the daemon resolve them, so the
venue gate (lib/venue_health.py) waits on and samples the books the case really uses (#1682):

  - a symbol the strategy names literally (venue_health.venue_codes);
  - a continuous stream `ROOT@front`: the current and next contract of its chain, as the case's roll picks them
    (derivatives_roll.contracts_of_roll, with the runner's own "has bars" test when it gives one);
  - a dated contract the runner picks at run time (`dated_from_root`): the runner passes its own choice;
  - an `OPTIONS ON ROOT { ... }` structure: each leg's contract as qkt's OptionSelector picks it - the nearest expiry
    whose days to expiry fall in the leg's DTE window and that quotes the leg's right, then the strike whose Black-76
    |delta| (forward: the expiry's median underlying, rate 0) is nearest the target, ties to the lower strike. Quotes
    (mark IV, underlying) come from the venue; without them a leg falls back to its expiry's median strike.
"""
import math, re, statistics

import derivatives_roll as rolling

DAY_MS = 86_400_000
YEAR_MS = 365 * DAY_MS
_STREAM = re.compile(r"\b[A-Z][A-Z0-9_]*:([A-Za-z0-9_]+)@[A-Za-z]+")
_STRUCTURE = re.compile(r"\bOPTIONS\s+ON\s+[A-Z][A-Z0-9_]*:([A-Za-z0-9_]+)\s*\{(.*?)\}", re.S)
_LEG = re.compile(r"\b(?:BUY|SELL)\s+(CALL|PUT)\s+DELTA\s+([0-9.]+)\s+"
                  r"(?:DTE\s+([0-9.]+)\s+TO\s+([0-9.]+)|SAME\s+EXPIRY)")


def underscored(code):
    return code.replace("-", "_")


def continuous_families(text):
    """The roots (BTC_USDC) of every continuous stream (`DERIBIT:BTC_USDC@front`) the strategy names."""
    return sorted(set(_STREAM.findall(text)))


def front_pair(instruments, family, at_ms, priced=lambda code: True):
    """The gateway codes of the contracts a `family@front` stream trades across a roll at [at_ms]: its current and next
    contract, as the case's roll picks them (only contracts [priced] says the venue has bars of)."""
    catalog = {"contracts": [{"symbol": underscored(i["code"]), "expiryMs": i["expiry"]} for i in instruments
                             if i.get("kind") == "future" and i.get("expiry")
                             and underscored(i["code"]).startswith(family + "_")]}
    pair = rolling.contracts_of_roll(catalog, at_ms, lambda symbol: priced(rolling.code_of(symbol)))
    return [rolling.code_of(c["symbol"]) for c in pair] if pair else []


def structure_legs(text):
    """[(family, [{right, delta, min_days, max_days}])] per `OPTIONS ON` structure; a SAME EXPIRY leg has no window."""
    found = []
    for family, body in _STRUCTURE.findall(text):
        legs = [{"right": right.lower(), "delta": float(delta), "min_days": float(low) if low else None,
                 "max_days": float(high) if high else None} for right, delta, low, high in _LEG.findall(body)]
        if legs:
            found.append((family, legs))
    return found


def black76_delta(right, forward, strike, years, sigma):
    d1 = (math.log(forward / strike) + 0.5 * sigma * sigma * years) / (sigma * math.sqrt(years))
    n = 0.5 * (1 + math.erf(d1 / math.sqrt(2)))
    return n if right == "call" else n - 1


def select_leg(options, quotes, leg, expiry, now_ms):
    """(code, expiry) of the contract [leg] selects among [options] (gateway listings) with [quotes] (instrument ->
    {mark_iv, underlying_price}), at [expiry] for a SAME EXPIRY leg; (None, expiry) when nothing qualifies."""
    usable = [o for o in options if (quotes.get(o["code"]) or {}).get("mark_iv")] or options
    if expiry is None:
        windowed = sorted({o["expiry"] for o in usable if o.get("right") == leg["right"]
                           and leg["min_days"] <= (o["expiry"] - now_ms) / DAY_MS <= leg["max_days"]})
        if not windowed:
            return None, None
        expiry = windowed[0]
    of_expiry = [o for o in usable if o["expiry"] == expiry]
    candidates = sorted((o for o in of_expiry if o.get("right") == leg["right"]), key=lambda o: float(o["strike"]))
    if not candidates:
        return None, expiry
    forwards = [float(quotes[o["code"]]["underlying_price"]) for o in of_expiry
                if (quotes.get(o["code"]) or {}).get("mark_iv") and quotes[o["code"]].get("underlying_price")]
    if not forwards:
        return candidates[(len(candidates) - 1) // 2]["code"], expiry
    forward, years = statistics.median(forwards), (expiry - now_ms) / YEAR_MS
    best = min(candidates, key=lambda o: (abs(abs(black76_delta(leg["right"], forward, float(o["strike"]), years,
                                                                float(quotes[o["code"]]["mark_iv"]) / 100))
                                              - leg["delta"]), float(o["strike"])))
    return best["code"], expiry


def option_legs(instruments, text, now_ms, quotes_of=lambda currency: []):
    """The gateway codes of every structure leg the strategy would open now. [quotes_of](currency) gives the venue's
    option quotes ({instrument_name, mark_iv, underlying_price}) of that currency."""
    codes = []
    for family, legs in structure_legs(text):
        options = [i for i in instruments if i.get("kind") == "option" and (i.get("expiry") or 0) > now_ms
                   and (i.get("underlying") == family or underscored(i["code"]).startswith(family + "_"))]
        if not options:
            continue
        quotes = {}
        for currency in sorted({o.get("currency") for o in options if o.get("currency")}):
            quotes.update({q["instrument_name"]: q for q in quotes_of(currency) or []})
        expiry = None
        for leg in legs:
            code, expiry = select_leg(options, quotes, leg, None if leg["min_days"] is not None else expiry, now_ms)
            if code:
                codes.append(code)
    return codes


def case_codes(text, instruments, at_ms, quotes_of=lambda currency: [], roll_at_ms=None, priced=lambda code: True,
               dated=()):
    """Every gateway code [text]'s case trades beyond its literal symbols: its continuous streams' roll pair (at
    [roll_at_ms], the case's roll, or [at_ms]), the runner's [dated] picks, and its structures' legs."""
    codes = list(dated)
    for family in continuous_families(text):
        codes += front_pair(instruments, family, roll_at_ms or at_ms, priced)
    return codes + option_legs(instruments, text, at_ms, quotes_of)
