#!/usr/bin/env python3
"""A stand-in for the qkt CLI in the derivatives runner's offline tests: `fetch` succeeds, `daemon start` plays an
option bought and held through its expiry against the fake gateway (tests/test_derivatives_settlement.py), writing
what the real daemon writes (trade log lines, the order journal, pnl.json), and `daemon stop` ends it.

FAKE_SETTLE says how qkt books the expiry: `once` (correct), `twice`, `at_mark` (a wrong price) or `never`.
"""
import json, os, re, sys, time, urllib.request
from decimal import Decimal

ENTRY, QTY, ENTRY_FEE = Decimal("3060"), Decimal("0.01"), Decimal("0.25")


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
    symbol = re.search(r"=\s*(DERIBIT:\S+)\s+EVERY", text).group(1)
    os.makedirs(f"{state}/state/{strategy}", exist_ok=True)
    os.makedirs(f"{state}/state/journal/{strategy}", exist_ok=True)
    journal = open(f"{state}/state/journal/{strategy}/journal-2026-10-06.jsonl", "a")
    print("daemon ready", flush=True)
    time.sleep(1)
    gateway("/test/buy")
    print(f"INFO [{strategy}] qkt.trade - trade BUY {symbol} qty={QTY} px={ENTRY} realized=0", flush=True)
    journal.write(json.dumps({"kind": "filled", "id": f"dsl-{strategy}--1", "symbol": symbol, "side": "BUY",
                              "price": str(ENTRY), "qty": str(QTY), "venueCosts": str(ENTRY_FEE)}) + "\n")
    journal.flush()
    realized = -ENTRY_FEE
    time.sleep(2)
    settled = gateway("/test/expire")
    mode = os.environ.get("FAKE_SETTLE", "once")
    if mode != "never":
        price = Decimal(settled["price"]) + (5 if mode == "at_mark" else 0)
        fee = sum(Decimal(c["amount"]) for c in settled["costs"])
        for _ in range(2 if mode == "twice" else 1):
            realized += (price - ENTRY) * QTY - fee
            print(f"INFO [{strategy}] qkt.trade - trade SELL {symbol} qty={QTY} px={price} realized={realized}", flush=True)
            journal.write(json.dumps({"kind": "filled", "id": f"settle:{symbol}:{strategy}", "broker": None,
                                      "symbol": symbol, "side": "SELL", "price": str(price), "qty": str(QTY),
                                      "venueCosts": str(fee)}) + "\n")
            journal.flush()
    json.dump({"realized": str(realized)}, open(f"{state}/state/{strategy}/pnl.json", "w"))
    while not os.path.exists(f"{state}/stop"):
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
