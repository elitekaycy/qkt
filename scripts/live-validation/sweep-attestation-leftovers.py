#!/usr/bin/env python3
"""Clear what an earlier attestation left on the demo account, and nothing else.

    sweep-attestation-leftovers.py --gateway-url URL --expected-login N --expected-server NAME
        --magic-from N --magic-to N

A case that dies badly (a saturated gateway, a killed runner) can leave a resting order or a position
under its magic. The next attestation then refuses to start - "demo account has pending orders" - and
an unattended release stops for a reason nobody is there to clear. This cancels pending orders and
closes positions whose magic lies in [--magic-from, --magic-to), the attestation's own range, by ticket.
Anything outside that range is not the attestation's: it is listed and the sweep fails, because a
stranger's order is a decision for a person. Demo accounts on loopback only. Prints one JSON line.
"""
import argparse, fcntl, json, os, re, sys, urllib.request

ap = argparse.ArgumentParser()
for flag in ("gateway-url", "expected-login", "expected-server", "magic-from", "magic-to"):
    ap.add_argument(f"--{flag}", required=True)
a = ap.parse_args()


def die(message):
    print(f"sweep-attestation-leftovers: {message}", file=sys.stderr)
    sys.exit(1)


if os.environ.get("QKT_LIVE_DEMO_ORDER_APPROVAL") != "LOCALHOST_DEMO_ONLY":
    die("QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY is required")
key = os.environ.get("QKT_BROKER_API_KEY") or die("QKT_BROKER_API_KEY must be set")
if not re.fullmatch(r"http://(127\.0\.0\.1|localhost):\d+", a.gateway_url):
    die("only a loopback gateway is allowed")
low, high = int(a.magic_from), int(a.magic_to)


def call(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(a.gateway_url + path, data=data, method=method,
                                 headers={"Authorization": f"Bearer {key}", "content-type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=30))


account = call("/account")
if str(account.get("login")) != a.expected_login or account.get("server") != a.expected_server or account.get("trade_mode") != 0:
    die("gateway is not logged into the expected DEMO account")

# The account's one lock, exclusively and without waiting: a sweep must never run beside a live case,
# whose orders under these magics are not leftovers.
lock_path = f"/var/tmp/qkt-validation/LIVE-LOCK-{re.sub(r'[^A-Za-z0-9._-]', '_', a.expected_server)}-{a.expected_login}"
os.makedirs(os.path.dirname(lock_path), exist_ok=True)
lock = open(lock_path, "a")
try:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    die(f"the account is in use ({lock_path} is held); nothing swept")

ours = lambda item: low <= int(item.get("magic", -1)) < high
orders = call("/orders").get("orders") or []
positions = call("/get_positions").get("data") or []
strangers = [f"order {o['ticket']} magic {o.get('magic')}" for o in orders if not ours(o)] + \
            [f"position {p['ticket']} magic {p.get('magic')}" for p in positions if not ours(p)]
cancelled, closed, errors = [], [], []
for order in filter(ours, orders):
    try:
        call(f"/orders/{order['ticket']}", method="DELETE")
        cancelled.append(order["ticket"])
    except Exception as error:  # noqa: BLE001 - reported, never swallowed
        errors.append(f"cancel {order['ticket']}: {error}")
for position in filter(ours, positions):
    try:
        call("/close_position", method="POST", body={"position": {"ticket": position["ticket"]}})
        closed.append(position["ticket"])
    except Exception as error:  # noqa: BLE001
        errors.append(f"close {position['ticket']}: {error}")
print(json.dumps({"cancelledOrders": cancelled, "closedPositions": closed, "notOurs": strangers, "errors": errors}))
sys.exit(1 if strangers or errors else 0)
