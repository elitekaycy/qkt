"""A live continuous-future roll inside one derivatives lane case, and the verdict on what it did.

A roll happens at a root's policy instant: `daysBeforeExpiry` days before a contract's expiry date, at `atUtc`. Deribit
lists a dated BTC_USDC contract for each of the next few days and weeklies after them, so a policy of N days at the next
few minutes, N counted to the first contract the venue has bars of, makes the next roll happen inside the case's budget,
on the venue's real contracts and books. Which contracts have bars changes with the hour (testnet charts only some), so
the pair is chosen from what the venue serves at the case's start (`contracts_of_roll`), never from the calendar. The account, the orders and
the venue's answers are all real; only when the roll happens is the case's choice. Its 08:00 UTC expiry is avoided on
purpose: what the book does then is the bot2 soak's daily roll to prove, and no case can schedule it to misbehave.

A case's `continuous_roll` says what must come of it:

    carried      one closing and one opening leg, both collared limit IOC orders, and the strategy trades on;
    far_book     the new contract's reference is seeded `far_reference` x its measured price, so the book a leg would hit
                 lies beyond the collar: no leg goes, the position stays on its contract, the strategy stops on the
                 stream and `qkt resume` is refused. The venue's book and answers are real, the reference is the fixture;
    leg_refused  the gateway's kill switch covers the new contract only, so the venue's own answer refuses the real
                 opening leg after the closing one filled: the position closes on the stream at that fill, the strategy
                 stops, and `qkt resume` clears the stop (nothing is left of it) and its next order reaches the venue.

Legs are not the engine's orders: their client order ids are `roll:<atMs>:<digest>:<kind>.<submit time>`, the digest
being the first 12 hex digits of the SHA-256 of `stream\\natMs\\nstrategy` (RollLegIds.kt), so a case can still tell its
own apart on a shared account.
"""
import datetime, hashlib, json
from decimal import Decimal

MINUTE_MS = 60_000
DAY_MS = 86_400_000
OUTCOMES = ("carried", "far_book", "leg_refused")
BAND = Decimal("0.25")  # PreTradeControls.DEFAULT_PRICE_COLLAR_FRAC, what ContinuousWiring gives a live lane


def check(spec):
    """What is wrong with a case's `continuous_roll`, for validate.py."""
    found = []
    if spec.get("outcome") not in OUTCOMES:
        found.append(f"continuous_roll.outcome must be one of {OUTCOMES}")
    if not str(spec.get("stream", "")).startswith(str(spec.get("root", "?")) + "@"):
        found.append("continuous_roll.stream must be its root's stream, e.g. DERIBIT:BTC_USDC@front")
    if not int(spec.get("lead_seconds", 0)) > int(spec.get("start_before_seconds", 0)) > 0:
        found.append("continuous_roll needs lead_seconds > start_before_seconds > 0")
    if spec.get("outcome") == "far_book" and not 0 < float(spec.get("far_reference", 0)) < 0.7:
        found.append("continuous_roll.far_reference must put the reference beyond the 25% collar: under 0.7")
    return found


def roll_at(now_ms, lead_seconds):
    """The whole minute a case's roll happens: at least [lead_seconds] from [now_ms]."""
    at = now_ms + lead_seconds * 1000
    return at + (MINUTE_MS - at % MINUTE_MS) % MINUTE_MS


def clear_of_expiry(now_ms, lead_seconds, margin_ms=15 * MINUTE_MS):
    """When a case may plan its roll: not while now or its roll lies within [margin_ms] of the venue's 08:00 UTC expiry."""
    expiry = now_ms // DAY_MS * DAY_MS + 8 * 3_600_000
    if roll_at(now_ms, lead_seconds) < expiry - margin_ms or now_ms > expiry + margin_ms:
        return now_ms
    return expiry + margin_ms


def hhmm(at_ms):
    return datetime.datetime.fromtimestamp(at_ms / 1000, datetime.timezone.utc).strftime("%H:%M")


def leg_prefix(stream, at_ms, strategy):
    """The client order id prefix of [strategy]'s legs of the roll at [at_ms] (RollLegIds.kt)."""
    digest = hashlib.sha256(f"{stream}\n{at_ms}\n{strategy}".encode()).hexdigest()[:12]
    return f"roll:{at_ms}:{digest}:"


def contracts_of_roll(catalog, at_ms, priced=lambda symbol: True):
    """The contracts `(from, to)` the roll at [at_ms] moves between, as `{"symbol", "expiryMs"}`: [catalog]'s first
    contract expiring a calendar day or more after the roll that [priced] says the venue has 1-minute bars of, and the
    next priced one. A venue lists contracts it serves no bars of (Deribit testnet charts only some of its dailies), and
    a live roll is measured from both contracts' bars (LiveRollMeasurer), so an unpriced one is never part of the pair;
    the roll then happens `days_before_expiry` days ahead of the first's expiry, which `with_policy` writes. None when
    the venue lists no such pair."""
    listed = [c for c in sorted(catalog["contracts"], key=lambda c: c["expiryMs"])
              if c["expiryMs"] // DAY_MS > at_ms // DAY_MS and priced(c["symbol"])]
    return (listed[0], listed[1]) if len(listed) > 1 else None


def days_before_expiry(contract, at_ms):
    """The policy's `daysBeforeExpiry` that rolls out of [contract] on [at_ms]'s day."""
    return contract["expiryMs"] // DAY_MS - at_ms // DAY_MS


def catalog_of_roll(catalog, contracts, priced=lambda symbol: True):
    """[catalog] holding only the roll's first contract and the priced ones after it: the chain the stream follows. An
    unpriced contract left in it would be rolled into and never priced, and one expiring before the pair would put a
    roll of its own ahead of the case's."""
    return dict(catalog, contracts=sorted((c for c in catalog["contracts"] if c["expiryMs"] >= contracts[0]["expiryMs"]
                                           and priced(c["symbol"])), key=lambda c: c["expiryMs"]))


def code_of(symbol):
    """The venue's code of a catalog's symbol: BTC_USDC_4NOV26 is BTC_USDC-4NOV26."""
    root, _, rest = symbol.rpartition("_")
    return f"{root}-{rest}"


def with_policy(instruments, root, at_ms, days=1):
    """[instruments] with [root] rolling [days] days before expiry at [at_ms]'s time of day. A panama adjustment, as a live
    order needs, and a one-hour guard: 24 hours would refuse the case's entry, its contract expiring under a day away."""
    for entry in instruments.get("futures") or []:
        if entry["root"] == root:
            entry["roll"] = {"daysBeforeExpiry": days, "atUtc": hhmm(at_ms), "adjust": "panama"}
            entry["expiryGuardHours"] = 1
            return instruments
    raise ValueError(f"{root} is not declared under futures: in the case's instruments.yaml")


def seed_far_reference(history, at_ms, contracts, price, factor):
    """[history] with the roll at [at_ms] measured at [price] on the old contract and [factor] x [price] on the new one."""
    frm, to = contracts[0]["symbol"], contracts[1]["symbol"]
    record = {"atMs": at_ms, "from": frm, "to": to, "fromPrice": str(price), "toPrice": str(Decimal(str(factor)) * price)}
    kept = [r for r in history["rolls"] if not (r["atMs"] == at_ms and r["from"] == frm and r["to"] == to)]
    return dict(history, rolls=sorted(kept + [record], key=lambda r: (r["atMs"], r["from"])))


def previous_symbol(contract):
    """The name of the contract expiring the day before [contract]: BTC_USDC_3NOV26 gives BTC_USDC_2NOV26."""
    family = contract["symbol"].rpartition("_")[0]
    day = datetime.datetime.fromtimestamp((contract["expiryMs"] - DAY_MS) / 1000, datetime.timezone.utc)
    return f"{family}_{day.day}{day.strftime('%b%y').upper()}"


def with_previous_roll(catalog, history, at_ms, contracts, price):
    """[catalog] and [history] with the roll before the case's, and no roll before that one. A stream is served only from
    its first measured roll, and a live session never starts a history (LiveRollMeasurer): the contract before the
    case's pair may have expired, may have no bars, or may have been listed too late to price, so `qkt fetch --rolls` can
    measure nothing at all. This record is the one roll before the case's, whatever the venue lists: a contract expiring
    the day before the pair's first, rolled out of a day before the case's roll, its two prices equal so it moves no
    adjustment. It only says the stream is served from yesterday."""
    earlier = {"symbol": previous_symbol(contracts[0]), "expiryMs": contracts[0]["expiryMs"] - DAY_MS}
    record = {"atMs": at_ms - DAY_MS, "from": earlier["symbol"], "to": contracts[0]["symbol"],
              "fromPrice": str(price), "toPrice": str(price)}
    listed = [c for c in catalog["contracts"] if c["symbol"] != earlier["symbol"]] + [earlier]
    rolls = [r for r in history["rolls"] if r["atMs"] > record["atMs"]] + [record]
    return (dict(catalog, contracts=sorted(listed, key=lambda c: c["expiryMs"])),
            dict(history, rolls=sorted(rolls, key=lambda r: (r["atMs"], r["from"]))))


def read(path):
    with open(path) as source:
        return json.load(source)


def write(path, document):
    with open(path, "w") as out:
        json.dump(document, out)


def kind_of(client_order_id):
    """`close`, `open` or `unwind` of a leg's id: `roll:<atMs>:<digest>:<kind>[-<attempt>].<submit time>`."""
    return client_order_id.rsplit(".", 1)[0].rsplit(":", 1)[1].split("-")[0]  # a retry is `:close-2`


def legs_of(deals, prefix):
    return [d for d in deals if str(d.get("client_order_id", "")).startswith(prefix)]


def collared(order, side, reference):
    """What is wrong with a sent leg [order]: it must be a limit IOC, its limit no further than the collar from
    [reference] (`GET /v1/orders/{id}`, as the gateway sent it to the venue)."""
    found = []
    if order.get("type") != "limit" or order.get("time_in_force") != "ioc":
        found.append(f"{order.get('client_order_id')} went as {order.get('type')}/{order.get('time_in_force')}, not limit/ioc")
    limit = order.get("limit_price")
    if limit is not None and abs(Decimal(limit) - reference) > reference * BAND:
        found.append(f"{order.get('client_order_id')} limit {limit} is beyond the collar of {reference}")
    if order.get("side") != side:
        found.append(f"{order.get('client_order_id')} went {order.get('side')}, not {side}")
    return found


def collared_leg(order, leg, history, plan):
    """What is wrong with venue [order] of the [leg] of a long holding's roll, against [history]'s record of that roll."""
    record = next((r for r in history["rolls"] if r["atMs"] == plan["at_ms"]), None)
    if record is None:
        return [f"no measured record of the roll at {plan['at_ms']}"]
    kind = kind_of(leg["client_order_id"])
    if kind == "close":
        return collared(order, "sell", Decimal(record["fromPrice"]))
    return collared(order, "buy", Decimal(record["toPrice"]))


def priced_live(plan, history):
    """What is wrong with how the roll at plan's `at_ms` was priced: `qkt fetch --rolls`, run minutes ahead of it, must
    record none of it (`prefetched`, read right after the fetch), and the session must have recorded it at its minute."""
    found = []
    if plan.get("prefetched"):
        found.append(f"qkt fetch --rolls recorded a roll that had not happened: {plan['prefetched']}")
    if not any(r["atMs"] == plan["at_ms"] for r in history["rolls"]):
        found.append(f"the session never recorded the roll at {plan['at_ms']}")
    return found


def judge(outcome, legs, holdings, plan, log, strategy):
    """What is wrong with the roll, given its venue [legs], the strategy's [holdings] and the daemon's [log]."""
    frm, to, quantity = plan["from_code"], plan["to_code"], Decimal(plan["quantity"])
    by_kind = {}
    for leg in legs:
        by_kind.setdefault(kind_of(leg["client_order_id"]), []).append(leg)
    found = []
    if outcome == "carried":
        for kind, code in (("close", frm), ("open", to)):
            sent = by_kind.get(kind, [])
            filled = sum((Decimal(d["quantity"]) for d in sent), Decimal(0))
            if filled != quantity or any(d["symbol"] != code for d in sent):
                found.append(f"roll-carried: the {kind} leg should fill {quantity} on {code}, the venue reports {sent}")
        if f"not carried for {strategy}" in log:
            found.append("roll-carried: the daemon logged the position as not carried")
    elif outcome == "far_book":
        if legs:
            found.append(f"roll-no-leg: a far book must send no leg, the venue filled {legs}")
        if holdings != {frm: quantity}:
            found.append(f"roll-position-stays: the strategy should hold {quantity} of {frm}, its deals say {holdings}")
        if f"not carried for {strategy}" not in log or "is beyond" not in log:
            found.append("roll-stopped: the daemon never logged the refusal that names the far quote")
    else:
        closed = sum((Decimal(d["quantity"]) for d in by_kind.get("close", [])), Decimal(0))
        if closed != quantity:
            found.append(f"roll-leg-refused: the closing leg should fill {quantity}, it filled {closed}")
        if by_kind.get("open") or by_kind.get("unwind"):
            found.append(f"roll-leg-refused: the kill switch covered {to}, yet the venue filled {by_kind}")
        if "refused the roll of" not in log:
            found.append("roll-stopped: the daemon never logged the opening leg's refusal")
    return found
