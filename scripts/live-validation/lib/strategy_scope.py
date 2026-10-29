"""What on a shared VGP gateway account is one case's strategy's, so a case can run beside other users of the account.

The gateway lanes (`run-derivatives-lane-case.py`, and each gateway leg of `run-cross-lane-case.py`) share their
accounts with release attestation and the live soak, so a case judges and touches only what is its strategy's:

- orders and deals carry the client order id the engine gave them: a rule's own orders are `dsl-<strategy>-...`,
  one a signal sized `ORD-<strategy>-...`, so both are attributed exactly, by prefix;
- positions cannot be: a netting account reports one signed position per symbol (`ticket` null) and says nothing
  of who opened it. So the strategy's holding is derived from its own deals (buys less sells per symbol, less what
  the venue settled of it at an expiry), never read from `/v1/positions`, which holds other users' size too;
- funding is charged on the account's whole position (the record's `position`); the strategy's part is the record's
  amount x its own holding at the funding time / that position, the share qkt itself books (FundingBooking.kt).
"""
from decimal import Decimal

from derivatives_settlement import holding_at

PREFIXES = ("dsl-{}-", "ORD-{}-")


def prefixes(strategy):
    return tuple(p.format(strategy) for p in PREFIXES)


def is_own(client_order_id, strategy):
    return str(client_order_id or "").startswith(prefixes(strategy))


def own(entries, strategy):
    """The deals or working orders of [entries] the strategy's orders own."""
    return [e for e in entries if is_own(e.get("client_order_id"), strategy)]


def foreign(entries, strategy):
    """The deals or orders of [entries] some other user of the account made."""
    return [e for e in entries if not is_own(e.get("client_order_id"), strategy)]


def holdings(deals, settled=()):
    """The strategy's signed holding per venue symbol: its [deals] (already its own) less the holdings the venue
    settled at an expiry ([settled]: `held_settlements` entries); symbols it holds nothing of are left out."""
    held = {}
    for deal in deals:
        quantity = Decimal(deal["quantity"])
        held[deal["symbol"]] = held.get(deal["symbol"], Decimal(0)) + (quantity if deal["side"] == "buy" else -quantity)
    for settlement in settled:
        held[settlement["symbol"]] = held.get(settlement["symbol"], Decimal(0)) - settlement["holding"]
    return {symbol: quantity for symbol, quantity in held.items() if quantity != 0}


def is_flat(orders, deals, settled, strategy):
    """flat-by-strategy: no working order of the strategy's, and its own deals (less its settlements) net to nothing."""
    return not own(orders, strategy) and not holdings(own(deals, strategy), settled)


def funding_share(record, deals):
    """The part of a funding [record] the strategy's own [deals] owe: amount x holding at its time / the account's
    position. A venue that realizes funding once the position is gone (position 0) charges the strategy all of it
    when the strategy traded the symbol before then (qkt books it to the holdings closed since); a gateway that
    reports no position charges the strategy all of it whenever it held some."""
    held = holding_at(deals, record["symbol"], record["time"])
    amount = Decimal(record["amount"])
    basis = record.get("position")
    if basis is None:
        return amount if held != 0 else Decimal(0)
    basis = Decimal(basis)
    if basis == 0:
        traded = any(d["symbol"] == record["symbol"] and d["time"] <= record["time"] for d in deals)
        return amount if traded else Decimal(0)
    return amount * held / basis


def strategy_funding(records, deals):
    """The funding records the strategy owes part of, each with its `share`; the rest are other users'."""
    return [dict(r, share=str(funding_share(r, deals))) for r in records if funding_share(r, deals) != 0]


def sweep_orders(held, account_positions, strategy, now_ms):
    """The market orders that close the strategy's own [held] holding and nothing else, each under the strategy's
    own id prefix so the venue attributes the close to it. Reduce-only when the account's position on the symbol
    is at least the strategy's on the same side (it then reduces the account as well); otherwise a plain order,
    since a reduce-only one would be refused or cut short by other users' opposite size."""
    account = {p["symbol"]: Decimal(p["quantity"]) for p in account_positions}
    orders = []
    for n, (symbol, quantity) in enumerate(sorted(held.items())):
        position = account.get(symbol, Decimal(0))
        reduces = position != 0 and (position > 0) == (quantity > 0) and abs(position) >= abs(quantity)
        orders.append({"client_order_id": f"ORD-{strategy}-sweep-{now_ms}-{n}", "symbol": symbol,
                       "side": "sell" if quantity > 0 else "buy", "type": "market", "quantity": str(abs(quantity)),
                       "time_in_force": "gtc", "reduce_only": reduces})
    return orders


def balance_note(window_deals, strategy):
    """Why the account's balance cannot judge the strategy's realized, or None when it can: any other user's deal
    inside the case window moved the same cash."""
    others = foreign(window_deals, strategy)
    if not others:
        return None
    return f"not comparable: {len(others)} deal(s) of other users on the account during the case " \
           f"({sorted({d['symbol'] for d in others})})"
