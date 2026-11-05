#!/usr/bin/env python3
"""A stand-in for the qkt CLI in the roll tests (tests/test_derivatives_roll.py): writes the catalog and roll history a
fetch would, and plays FAKE_ROLL against the fake gateway, logging what the daemon logs."""
import json, os, re, sys, time, urllib.request

import yaml

DAY = 86_400_000


def roll_ms(data_root):
    """The next time of day the runner wrote into instruments.yaml, as the epoch ms of the roll."""
    entry = yaml.safe_load(open(f"{data_root}/instruments.yaml"))["futures"][0]
    hour, minute = entry["roll"]["atUtc"].split(":")
    now = int(time.time() * 1000)
    at = now // DAY * DAY + (int(hour) * 60 + int(minute)) * 60_000
    return at if at > now else at + DAY


def arg(name):
    return sys.argv[sys.argv.index(name) + 1]


def post(path, body):
    request = urllib.request.Request(os.environ["FAKE_GATEWAY"] + path, data=json.dumps(body).encode(), method="POST")
    return json.load(urllib.request.urlopen(request, timeout=10))


def main():
    if sys.argv[1] == "fetch":
        directory = f"{arg('--data-root')}/contracts/DERIBIT"
        os.makedirs(directory, exist_ok=True)
        at = roll_ms(arg("--data-root"))
        expiry = (at // DAY + 1) * DAY + 8 * 3_600_000
        if "--catalog" in sys.argv:
            json.dump({"root": "DERIBIT:BTC_USDC", "contracts": [{"symbol": "BTC_USDC_3NOV26", "expiryMs": expiry},
                                                                  {"symbol": "BTC_USDC_4NOV26", "expiryMs": expiry + DAY}]},
                      open(f"{directory}/BTC_USDC.json", "w"))
        else:  # qkt fetch --rolls records only rolls that have happened (#1615): the case's is still ahead
            json.dump({"root": "DERIBIT:BTC_USDC", "policy": "x", "rolls": []}, open(f"{directory}/BTC_USDC.rolls.json", "w"))
        return 0
    if sys.argv[1] == "resume":
        print("stands or cleared")
        return int(os.environ.get("FAKE_RESUME_EXIT", "0"))
    state = arg("--state-dir")
    if sys.argv[2] == "stop":
        open(f"{state}/stop", "w").close()
        return 0
    loaded = arg("--load-dir")
    text = open(os.path.join(loaded, os.listdir(loaded)[0])).read()
    strategy = re.search(r"^STRATEGY\s+(\w+)", text, re.M).group(1)
    stream = "DERIBIT:BTC_USDC@front"
    os.makedirs(f"{state}/state/{strategy}", exist_ok=True)
    at = roll_ms(os.environ["QKT_DATA_HOME"])
    history = f"{os.environ['QKT_DATA_HOME']}/contracts/DERIBIT/BTC_USDC.rolls.json"
    # qkt takes no record of a roll that had not happened when it started (PrematureRolls)
    ahead_at_start = [r for r in json.load(open(history))["rolls"] if r["atMs"] >= at]
    print("daemon ready", flush=True)
    time.sleep(1)
    mode = os.environ["FAKE_ROLL"]
    if mode.startswith("far_book"):
        deadline = time.time() + 5
        while not (seeded := [r for r in json.load(open(history))["rolls"] if r["atMs"] == at]) and time.time() < deadline:
            time.sleep(0.2)
        if ahead_at_start or not seeded:
            print(f"ERROR the far reference was seeded before start ({ahead_at_start}) or never ({seeded})", flush=True)
            mode = "unseeded"
    entry = post("/test/deal", {"id": f"dsl-{strategy}--1.x", "code": "FROM", "side": "buy", "price": "78000"})
    print(f"INFO qkt.trade - trade BUY {stream} qty=0.001 px={entry['price']} realized=0", flush=True)
    realized = "0"
    if mode in ("carried", "market_legs"):  # the session measures the roll live and appends its record
        document = json.load(open(history))
        document["rolls"].append({"atMs": at, "from": "BTC_USDC_3NOV26", "to": "BTC_USDC_4NOV26",
                                  "fromPrice": "78000", "toPrice": "78000"})
        json.dump(document, open(history, "w"))
    if mode == "carried":
        post("/test/leg", {"at": at, "strategy": strategy, "kind": "close", "code": "FROM", "side": "sell", "price": "78100", "limit": "58575"})
        post("/test/leg", {"at": at, "strategy": strategy, "kind": "open", "code": "TO", "side": "buy", "price": "78120", "limit": "97500"})
        post("/test/deal", {"id": f"dsl-{strategy}--2.x", "code": "TO", "side": "sell", "price": "78220"})
        print(f"INFO qkt.trade - trade SELL {stream} qty=0.001 px=78200 realized=0.2", flush=True)
        realized = "0.2"
    elif mode == "market_legs":
        post("/test/leg", {"at": at, "strategy": strategy, "kind": "close", "code": "FROM", "side": "sell", "price": "78100", "type": "market"})
        post("/test/leg", {"at": at, "strategy": strategy, "kind": "open", "code": "TO", "side": "buy", "price": "78120", "type": "market"})
        post("/test/deal", {"id": f"dsl-{strategy}--2.x", "code": "TO", "side": "sell", "price": "78220"})
        print(f"INFO qkt.trade - trade SELL {stream} qty=0.001 px=78200 realized=0.2", flush=True)
        realized = "0.2"
    elif mode == "far_book":
        print(f"ERROR {stream} not carried for {strategy}: ask 78000 on DERIBIT:BTC_USDC_TO is beyond 0.25 of its measured 39000", flush=True)
    elif mode == "far_book_filled":
        post("/test/leg", {"at": at, "strategy": strategy, "kind": "close", "code": "FROM", "side": "sell", "price": "78100", "limit": "58575"})
        print(f"ERROR {stream} not carried for {strategy}: ask 78000 is beyond", flush=True)
    json.dump({"realized": realized}, open(f"{state}/state/{strategy}/pnl.json", "w"))
    while not os.path.exists(f"{state}/stop"):
        time.sleep(0.2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
