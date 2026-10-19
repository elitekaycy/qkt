"""A worked agent loop over a qkt bot session: a moving-average cross, decided in Python, traded by qkt.

Start a session first (a backtest here; drop --backtest/--from/--to to trade live):

    qkt bot session start --backtest --symbols EXNESS:XAUUSD --tf 15m \\
        --from 2025-01-02 --to 2025-01-31 --identities cross --run demo --json &
    python3 agent_loop.py --run demo --symbol EXNESS:XAUUSD

The decision lives here; sizing, the bracket, risk rules, fills and the report are qkt's. Every intent,
its fate and every bar this loop was served are in the run's journals, so the run replays exactly
(`qkt bot session replay runs/demo`).
"""

from __future__ import annotations

import argparse
import json

from qkt_session import Session


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--run", required=True)
    p.add_argument("--state-dir")
    p.add_argument("--symbol", required=True)
    p.add_argument("--identity", default="cross")
    p.add_argument("--fast", type=int, default=5)
    p.add_argument("--slow", type=int, default=20)
    p.add_argument("--lots", type=float, default=0.1)
    p.add_argument("--max-bars", type=int, default=0, help="stop after this many bars (0: to the end)")
    a = p.parse_args()

    s = Session.find(run=a.run, state_dir=a.state_dir)
    closes: list[float] = []
    side = None
    seen = 0
    while True:
        bar = s.next(a.symbol)
        for e in bar.get("events", []):
            print(json.dumps({"event": e["kind"], "side": e["side"], "price": e["price"], "exit": e["exitReason"]}))
        if bar["type"] == "end" or (a.max_bars and seen >= a.max_bars):
            break
        seen += 1
        closes.append(float(bar["close"]))
        if len(closes) < a.slow:
            continue
        fast = sum(closes[-a.fast:]) / a.fast
        slow = sum(closes[-a.slow:]) / a.slow
        want = "long" if fast > slow else "short"
        if want != side:
            if side is not None:
                s.close(a.identity, a.symbol)
            order = s.buy if want == "long" else s.sell
            order(a.identity, a.symbol, a.lots, sl="by:5", tp="rr:2")
            side = want
    intents = s.intents()
    done = s.finish(flatten=True)
    filled = sum(1 for i in intents if i["state"] == "filled")
    print(json.dumps({"bars": seen, "intents": len(intents), "filled": filled, "report": done.get("reportDir")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
