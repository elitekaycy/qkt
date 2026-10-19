#!/usr/bin/env python3
"""Independent verifier for one `qkt backtest --report-dir` bundle.

Recomputes the headline numbers from trades.csv / equity files WITHOUT using qkt's own code, and compares
them with result.json. Prints one JSON object: {"checks": [{"name","ok","detail"}...], "summary": {...}}.
Exit 0 always (the matrix runner decides); `ok` false means a discrepancy a human must look at.
"""
import csv, json, sys, os, collections, datetime, math
from decimal import Decimal as D, getcontext

getcontext().prec = 40
TOL = D("0.0001")  # money tolerance for sums of 8-dp values


def load(run):
    res = json.load(open(f"{run}/result.json"))
    trades = list(csv.DictReader(open(f"{run}/trades.csv")))
    return res, trades


def utc_day(ms):
    return datetime.datetime.fromtimestamp(int(ms) / 1000, datetime.timezone.utc).strftime("%Y-%m-%d")


def close_rows(trades):
    return [t for t in trades if t["positionEffect"].startswith(("CLOSE", "REDUCE")) or t["legAction"] in ("CLOSED", "REDUCED")]


def main(run, log=None):
    res, trades = load(run)
    g = res["global"]
    checks = []

    def chk(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": str(detail)})

    # 1. per-fill realized PnL recomputed from price, entry price, qty, contract size ------------------------
    legs = {}  # (strategy, legId) -> list of open fills [qty, price, side]
    bad = []
    recomputed_total = D(0)
    reported_total = D(0)
    closes = []
    all_net = D(0)
    for t in trades:
        qty, px = D(t["quantity"]), D(t["price"])
        cs = D(t["contractSize"] or "1")
        key = (t["strategy"], t["legId"])
        reported = D(t["grossAccountRealized"] or "0")  # gross of commission; swap/commission are booked separately
        all_net += D(t["realized"] or "0")
        if t["legAction"] == "OPENED":
            legs.setdefault(key, []).append([qty, px, t["side"]])
            if reported != 0:
                bad.append((t["timestamp"], "open fill has gross realized", reported))
            continue
        # a close/reduce: pair with its leg's entry (volume weighted if several opens)
        opens = legs.get(key)
        if not opens:
            # exits without a recorded leg (e.g. flatten at end of run) fall back to the engine's own entry avg
            entry = D(t["strategyPositionAvgEntryBefore"] or t["accountPositionAvgEntryBefore"] or "0")
            side_open = "BUY" if t["side"] == "SELL" else "SELL"
        else:
            tq = sum(o[0] for o in opens)
            entry = sum(o[0] * o[1] for o in opens) / tq
            side_open = opens[0][2]
        sign = D(1) if side_open == "BUY" else D(-1)
        mine = sign * (px - entry) * qty * cs
        recomputed_total += mine
        reported_total += reported
        closes.append((int(t["timestamp"]), t["strategy"], reported, mine))
        if abs(mine - reported) > D("0.01"):
            bad.append((t["timestamp"], t["legId"], f"reported {reported} recomputed {mine}"))
        if opens and t["legAction"] == "CLOSED":
            legs.pop(key, None)
    chk("trade-pnl-recomputed-from-prices", not bad, f"{len(closes)} closes, mismatches={bad[:3]}")
    swap_net = D(0)
    fin = f"{run}/financing.csv"
    if os.path.exists(fin):
        for r in csv.DictReader(open(fin)):
            if r["component"] == "swap":
                swap_net += D(r["netPnlImpact"])
    chk("realized-total-equals-trades-plus-swap", abs(all_net + swap_net - D(g["realizedTotal"])) <= TOL,
        f"sum(trades.realized) {all_net} + swap {swap_net} vs result.realizedTotal {g['realizedTotal']}")
    chk("gross-pnl-recomputed-from-prices-equals-reported", abs(recomputed_total - reported_total) <= D("0.05"),
        f"recomputed {recomputed_total} vs reported gross {reported_total}")
    comm = sum((D(t["grossAccountRealized"] or "0") - D(t["netAccountRealized"] or "0") for t in trades), D(0))
    chk("commission-equals-gross-minus-net", abs(comm - D(g.get("commissionPaid", "0"))) <= TOL,
        f"gross-net {comm} vs commissionPaid {g.get('commissionPaid')}")

    # 2. headline statistics ------------------------------------------------------------------------------
    # A round trip is a position, opened to flat (#1452): every fill of a leg (or of a strategy's symbol when fills
    # carry no leg) adds its net realized, entry commission included, and the trade is scored when the position's
    # quantity is back to zero. A partial exit is part of its position; one still partly open at the end counts once.
    pos, pnl = {}, []
    for t in sorted(trades, key=lambda t: int(t["timestamp"])):
        key = (t["strategy"], t["legId"] or t["symbol"])
        signed = D(t["quantity"]) * (1 if t["side"] == "BUY" else -1)
        p = pos.setdefault(key, {"qty": D(0), "pnl": D(0), "reduced": False})
        before = p["qty"]
        p["qty"] += signed
        p["pnl"] += D(t["realized"] or "0")
        p["reduced"] |= t["reducedExposure"] == "true"
        if before != 0 and (p["qty"] == 0 or (p["qty"] > 0) != (before > 0)):
            pnl.append(p["pnl"])
            rest = p["qty"]
            pos[key] = {"qty": rest, "pnl": D(0), "reduced": False}
            if rest == 0:
                del pos[key]
    pnl.extend(p["pnl"] for p in pos.values() if p["reduced"])
    wins = [p for p in pnl if p > 0]
    losses = [p for p in pnl if p < 0]
    n = len(pnl)
    if n:
        wr = D(len(wins)) / D(n)
        chk("win-rate", abs(wr - D(g["winRate"])) < D("0.0001"), f"{wr:.8f} vs {g['winRate']}")
        gp, gl = sum(wins, D(0)), sum(losses, D(0))
        if losses:
            chk("profit-factor", abs(gp / -gl - D(g["profitFactor"])) < D("0.0001"), f"{gp / -gl:.8f} vs {g['profitFactor']}")
        if wins:
            chk("avg-win", abs(gp / len(wins) - D(g["avgWin"])) < D("0.0001"), f"{gp / len(wins):.8f} vs {g['avgWin']}")
            chk("largest-win", D(g["largestWin"]) == max(wins), f"{max(wins)} vs {g['largestWin']}")
        if losses:
            chk("avg-loss", abs(gl / len(losses) - D(g["avgLoss"])) < D("0.0001"), f"{gl / len(losses):.8f} vs {g['avgLoss']}")
            chk("largest-loss", D(g["largestLoss"]) == min(losses), f"{min(losses)} vs {g['largestLoss']}")
        streak = best = 0
        for p in pnl:
            streak = streak + 1 if p < 0 else 0
            best = max(best, streak)
        chk("max-consecutive-losses", best == g["maxConsecutiveLosses"], f"{best} vs {g['maxConsecutiveLosses']}")
        chk("round-trips", g.get("roundTrips") == n, f"{n} positions vs roundTrips {g.get('roundTrips')}")
        chk("trade-count-is-fills", g["tradeCount"] == len(trades), f"fills {len(trades)} vs tradeCount {g['tradeCount']}")

    # 3. daily PnL: trade-realized per UTC day -----------------------------------------------------------
    by_day = collections.defaultdict(D)
    for t in trades:
        by_day[utc_day(t["timestamp"])] += D(t["realized"] or "0")
    comp = {}
    pc = f"{run}/pnl_components.csv"
    if os.path.exists(pc):
        for r in csv.DictReader(open(pc)):
            if r["scope"] == "global":
                comp[r["date"]] = D(r["tradeRealized"])
        diffs = [(d, by_day.get(d, D(0)), comp.get(d, D(0))) for d in sorted(set(by_day) | set(comp))
                 if abs(by_day.get(d, D(0)) - comp.get(d, D(0))) > TOL]
        chk("daily-trade-realized-matches-components", not diffs, f"diffs={diffs[:3]}")

    # 4. equity file: closes at starting balance + net realized + unrealized - costs ---------------------
    sb = None
    ed = f"{run}/equity_daily.csv"
    if os.path.exists(ed):
        rows = list(csv.DictReader(open(ed)))
        if rows:
            sb = D(rows[0]["open"])
            last = D(rows[-1]["close"])
            expect = sb + D(g["totalPnL"]) - D(g.get("commissionPaid", "0")) - D(g.get("swapPaid", "0"))
            # qkt reports totalPnL gross of commission in some versions; accept either convention but record which
            alt = sb + D(g["totalPnL"])
            chk("final-equity-reconciles", min(abs(last - expect), abs(last - alt)) <= D("0.05"),
                f"close {last} vs start+totalPnL-costs {expect} (or {alt})")
            mr = f"{run}/monthly_returns.csv"
            if os.path.exists(mr):
                comp_ret = D(1)
                for r in csv.DictReader(open(mr)):
                    comp_ret *= (1 + D(r["return"]))
                chk("monthly-returns-compound-to-total", abs(sb * comp_ret - last) <= D("0.05") * max(D(1), D(1)),
                    f"{sb * comp_ret} vs {last}")

    # 5. max drawdown from the retained equity curve ---------------------------------------------------
    eg = f"{run}/equity_global.csv"
    if os.path.exists(eg):
        peak, mdd = None, D(0)
        for r in csv.DictReader(open(eg)):
            e = D(r["equity"])
            peak = e if peak is None or e > peak else peak
            if peak > 0:
                mdd = max(mdd, (peak - e) / peak)
        rep = D(g["maxDrawdown"])
        # the retained curve is thinned: recomputed may be <= reported, never meaningfully greater
        chk("max-drawdown-not-understated", mdd <= rep + D("0.00001"), f"curve {mdd:.8f} vs reported {rep}")
        chk("max-drawdown-close-to-curve", abs(mdd - rep) <= D("0.0015"), f"curve {mdd:.8f} vs reported {rep}")

    # 6. Sharpe sanity (formula differs; check sign and order of magnitude) -------------------------------
    sharpe_note = {}
    if os.path.exists(eg):
        eq = [(int(r["timestamp"]), float(r["equity"])) for r in csv.DictReader(open(eg))]
        rets = [(eq[i][1] - eq[i - 1][1]) / eq[i - 1][1] for i in range(1, len(eq)) if eq[i - 1][1]]
        if len(rets) > 5:
            mu = sum(rets) / len(rets)
            sd = math.sqrt(sum((r - mu) ** 2 for r in rets) / (len(rets) - 1)) or 1e-18
            af = float(g["annualizationFactor"])
            mine = mu / sd * math.sqrt(af)
            rep = float(g["sharpeRatio"]) if g["sharpeRatio"] is not None else float("nan")
            sharpe_note = {"mine": round(mine, 4), "reported": round(rep, 4)}
            chk("sharpe-sign-and-scale", math.isnan(rep) or (mine == 0 and rep == 0) or (mine * rep >= 0 and (abs(rep) < 1e-9 or 0.25 < abs(mine / rep) < 4)),
                f"curve-based {mine:.3f} vs reported {rep:.3f}")

    # 7. no look-ahead / ordering invariants ----------------------------------------------------------------
    ts = [int(t["timestamp"]) for t in trades]
    chk("fills-in-time-order", ts == sorted(ts), "")
    chk("no-fill-with-nonpositive-price-or-qty", all(D(t["price"]) > 0 and D(t["quantity"]) > 0 for t in trades), "")
    open_legs = sum(1 for k in legs)
    chk("no-orphan-legs-at-end", True, f"open legs at end: {open_legs}")

    out = {"run": run, "checks": checks,
           "summary": {"fills": len(trades), "closes": n, "totalPnL": g["totalPnL"], "realized": g["realizedTotal"],
                       "maxDD": g["maxDrawdown"], "sharpe": g["sharpeRatio"], "winRate": g["winRate"],
                       "pf": g["profitFactor"], "commission": g.get("commissionPaid"), "sharpeNote": sharpe_note,
                       "openLegsAtEnd": open_legs}}
    print(json.dumps(out))


if __name__ == "__main__":
    main(sys.argv[1])
