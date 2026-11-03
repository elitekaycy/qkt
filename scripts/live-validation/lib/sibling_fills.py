"""Which unattributed fills in a daemon log fail a case.

Strategies sharing one magic each see the others' closes on that magic's poller, and each logs the close as an
unattributed fill under its own name. With `sibling_closes` such a line passes only if a different session accepted
that exact broker order. Every other unattributed line fails, including one that does not parse.
"""
import re

_ACCEPTED = re.compile(r"order accepted order_id=\S* strategy_id=(\S+) broker_order_id=(\S+)")
_DROPPED = re.compile(r"\[(\w+)\] \S+ - unattributed fill dropped: order_id=\S* broker_order_id=(\S+)")
_NO_SESSION = "main"  # the log pattern's name for a line with no strategy context
_NO_ORDER = "null"  # how a missing broker id prints


def strays(log, sibling_closes=False):
    """The unattributed-fill lines of `log` that fail the case."""
    accepted = {}
    for session, order in _ACCEPTED.findall(log):
        accepted.setdefault(order, set()).add(session)
    found = []
    for line in log.splitlines():
        if "unattributed fill dropped" not in line:
            continue
        dropped = _DROPPED.search(line)
        if not (sibling_closes and dropped and _is_siblings(*dropped.groups(), accepted)):
            found.append(line)
    return found


def _is_siblings(session, order, accepted):
    return session != _NO_SESSION and order != _NO_ORDER and bool(accepted.get(order, set()) - {session})
