#!/usr/bin/env python3
"""A stand-in for the qkt CLI in the gateway lane runner's offline event-contract test (tests/test_event_settlement.py):
`daemon start` plays an event slot's SELL into the bid (NO bought at 1 less the bid) on the contract the fake gateway
lists, held until the venue settles it at its outcome, writing what the real daemon writes (trade log lines on the
real contract's symbol, the order journal, pnl.json); `daemon stop` ends it.

FAKE_SETTLE says how qkt books the outcome: `once` (correct), `twice`, `at_quote` (the last quote, not the outcome)
or `never`.
"""
import json, os, re, sys, time, urllib.request
from decimal import Decimal

QTY = Decimal(1)


def arg(name):
    return sys.argv[sys.argv.index(name) + 1]


def gateway(path):
    request = urllib.request.Request(os.environ["FAKE_GATEWAY"] + path, data=b"{}", method="POST")
    return json.load(urllib.request.urlopen(request, timeout=10))


def main():
    if sys.argv[1] == "fetch":
        return 0
    state = arg("--state-dir")
    if sys.argv[2] == "stop":
        open(f"{state}/stop", "w").close()
        return 0
    loaded = arg("--load-dir")
    text = open(os.path.join(loaded, os.listdir(loaded)[0])).read()
    strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
    venue = re.search(r"=\s*([A-Z_]+):\w+@front\.atm\s+EVERY", text).group(1)  # the slot never names a contract
    os.makedirs(f"{state}/state/{strategy}", exist_ok=True)
    os.makedirs(f"{state}/state/journal/{strategy}", exist_ok=True)
    journal = open(f"{state}/state/journal/{strategy}/journal-2026-11-16.jsonl", "a")
    print("daemon ready", flush=True)
    time.sleep(1)
    sold = gateway("/test/sell")
    symbol = f"{venue}:{sold['symbol'].replace('-', '_')}"
    entry, fee = Decimal(sold["price"]), sum(Decimal(c["amount"]) for c in sold["costs"])
    print(f"INFO [{strategy}] qkt.trade - trade SELL {symbol} qty={QTY} px={entry} realized=0", flush=True)
    journal.write(json.dumps({"kind": "filled", "id": f"ORD-{strategy}-1", "symbol": symbol, "side": "SELL",
                              "price": str(entry), "qty": str(QTY), "venueCosts": str(fee)}) + "\n")
    journal.flush()
    realized = -fee
    time.sleep(2)
    settled = gateway("/test/settle")
    mode = os.environ.get("FAKE_SETTLE", "once")
    if mode != "never":
        price = entry if mode == "at_quote" else Decimal(settled["price"])
        for _ in range(2 if mode == "twice" else 1):
            realized += (entry - price) * QTY
            print(f"INFO [{strategy}] qkt.trade - trade BUY {symbol} qty={QTY} px={price} realized={realized}", flush=True)
            journal.write(json.dumps({"kind": "filled", "id": f"settle:{symbol}:{strategy}", "broker": None,
                                      "symbol": symbol, "side": "BUY", "price": str(price), "qty": str(QTY),
                                      "venueCosts": "0"}) + "\n")
            journal.flush()
    json.dump({"realized": str(realized)}, open(f"{state}/state/{strategy}/pnl.json", "w"))
    while not os.path.exists(f"{state}/stop"):
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
