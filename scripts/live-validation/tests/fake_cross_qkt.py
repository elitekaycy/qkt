#!/usr/bin/env python3
"""A stand-in for the qkt CLI in the cross runner's offline test (tests/test_cross_runner.py): `daemon start` plays one
strategy opening and closing a BTC leg on the fake MT5 gateway and a perpetual leg on the fake VGP gateway, writing what
the real daemon writes (`order filled` lines, the order journal, trade-history.json, pnl.json); `bot history` prints
the fake MT5 account's deals; `fetch` and `instruments pull` succeed.

FAKE_CROSS says what qkt books: `exact` (what the venues did) or `drift` (two cents too much on the perpetual's close,
while the MT5 close is two cents short, so the strategy's total still agrees).
"""
import json, os, re, sys, time, urllib.request
from decimal import Decimal

FEE = Decimal("0.0005")


def arg(name):
    return sys.argv[sys.argv.index(name) + 1]


def post(url, path, body):
    request = urllib.request.Request(url + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(request, timeout=10))


def main():
    if sys.argv[1] == "fetch":
        return 0
    if sys.argv[1:3] == ["instruments", "pull"]:
        open(arg("--out"), "w").write("instruments:\n  - qktSymbol: EXNESS:BTCUSD\n    contractSize: 1.0\n")
        return 0
    if sys.argv[1:3] == ["bot", "history"]:
        print(json.dumps(json.load(urllib.request.urlopen(os.environ["FAKE_MT5"] + "/test/history"))))
        return 0
    if sys.argv[1] == "stop":
        return 0
    state = arg("--state-dir")
    if sys.argv[2] == "stop":
        open(f"{state}/stop", "w").close()
        return 0
    loaded = arg("--load-dir")
    strategy = re.search(r"^STRATEGY\s+(\w+)", open(os.path.join(loaded, os.listdir(loaded)[0])).read(), re.M).group(1)
    os.makedirs(f"{state}/state/{strategy}", exist_ok=True)
    os.makedirs(f"{state}/state/journal/{strategy}", exist_ok=True)
    journal = open(f"{state}/state/journal/{strategy}/journal-2026-10-28.jsonl", "a")
    print("daemon ready", flush=True)
    time.sleep(1)
    drift = Decimal("0.02") if os.environ.get("FAKE_CROSS") == "drift" else Decimal(0)
    legs = [("EXNESS:BTCUSD", "BUY", "0.01", "80000", f"ORD-{strategy}-0", Decimal(0)),
            ("PAPER:BTC_USDC_PERPETUAL", "SELL", "0.001", "80010", f"ORD-{strategy}-1", -FEE * Decimal("80.01")),
            ("EXNESS:BTCUSD", "SELL", "0.01", "80005", f"dsl-{strategy}--0", Decimal("0.05") - drift),
            ("PAPER:BTC_USDC_PERPETUAL", "BUY", "0.001", "80000", f"dsl-{strategy}--1",
             Decimal("0.01") - FEE * Decimal("80") + drift)]
    outcomes = []
    for n, (symbol, side, qty, price, order_id, realized) in enumerate(legs):
        if symbol.startswith("EXNESS:"):
            post(os.environ["FAKE_MT5"], "/test/deal", {"side": side, "lots": qty, "price": price, "comment": order_id})
        else:
            post(os.environ["FAKE_GATEWAY"], "/test/fill", {"side": side.lower(), "quantity": qty, "price": price,
                                                            "client_order_id": f"{order_id}.{n}"})
        print(f"INFO [{strategy}] com.qkt.app.OrderManager - order filled order_id={order_id} strategy_id={strategy} "
              f"symbol={symbol} side={side} qty={qty} price={price}", flush=True)
        journal.write(json.dumps({"ts": int(time.time() * 1000), "kind": "filled", "id": order_id, "symbol": symbol,
                                  "side": side, "price": price, "qty": qty}) + "\n")
        journal.flush()
        if realized != 0:
            outcomes.append({"timestamp": int(time.time() * 1000), "pnl": str(realized), "symbol": symbol})
        time.sleep(0.3)
    json.dump({"outcomes": outcomes}, open(f"{state}/state/{strategy}/trade-history.json", "w"))
    total = sum((Decimal(o["pnl"]) for o in outcomes), Decimal(0))
    json.dump({"realized": str(total)}, open(f"{state}/state/{strategy}/pnl.json", "w"))
    while not os.path.exists(f"{state}/stop"):
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
