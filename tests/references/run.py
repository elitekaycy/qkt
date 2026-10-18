"""Runs the external references: qkt backtests of strategies whose results backtesting.py and backtrader have
already produced, compared trade by trade and to the cent with those engines' own outputs.

    python3 -I tests/references/run.py                     # every case
    python3 -I tests/references/run.py --case b_sma        # one case

The reference data (prices, strategies, configs, the other engines' outputs) is `references/` in the private
qkt-data-corpus repository (--refs, or QKT_REFERENCES). Each expectation is written below, with the convention
difference it records where the engines legitimately differ. Exit 0 only when every case passes.
"""

import argparse
import csv
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
CENT = Decimal("0.01")
PX = Decimal("0.000001")


class Failure(Exception):
    pass


def day(ms: str) -> str:
    return dt.datetime.fromtimestamp(int(ms) / 1000, dt.timezone.utc).strftime("%Y-%m-%d")


def dec(x) -> Decimal:
    return Decimal(str(x))


def expect(what: str, got, want) -> None:
    if got != want:
        raise Failure(f"{what}: qkt {got}, expected {want}")


# --- reading qkt's report and the other engines' outputs ---------------------------------------------------


def fills(rd: Path) -> list[dict]:
    return list(csv.DictReader(open(rd / "trades.csv")))


def total_pnl(rd: Path) -> Decimal:
    return Decimal(json.load(open(rd / "result.json"))["global"]["totalPnL"])


def round_trips(rd: Path) -> list[dict]:
    """qkt's fills paired into round trips by leg (as scripts/compare.py did): dates, side, qty, prices."""
    opens, out = {}, []
    for r in fills(rd):
        if r["legAction"] in ("OPENED", "NETTED") and r["positionEffect"].startswith("OPEN_"):
            opens[r["legId"]] = r
        elif r["legAction"] == "CLOSED":
            o = opens.pop(r["legId"], None) or opens.pop(next(iter(opens)))
            out.append(
                dict(
                    entry_date=day(o["timestamp"]),
                    exit_date=day(r["timestamp"]),
                    side=1 if o["side"] == "BUY" else -1,
                    qty=dec(r["quantity"]),
                    entry_px=dec(o["price"]),
                    exit_px=dec(r["price"]),
                )
            )
        else:
            raise Failure(f"unhandled legAction {r['legAction']}")
    return sorted(out, key=lambda t: (t["entry_date"], t["exit_date"]))


def btpy_trades(path: Path) -> list[dict]:
    out = []
    for r in csv.DictReader(open(path)):
        size = dec(r["Size"])
        out.append(
            dict(
                entry_date=r["EntryTime"][:10],
                exit_date=r["ExitTime"][:10],
                side=1 if size > 0 else -1,
                qty=abs(size),
                entry_px=dec(r["EntryPrice"]),
                exit_px=dec(r["ExitPrice"]),
            )
        )
    return sorted(out, key=lambda t: (t["entry_date"], t["exit_date"]))


def btpy_stat(path: Path, name: str) -> Decimal:
    for line in open(path):
        if line.startswith(name):
            return dec(line[len(name) :].strip())
    raise Failure(f"{path.name} has no '{name}'")


def at_shown_precision(value: Decimal, shown: Decimal) -> Decimal:
    """[value] rounded the way the other engine printed [shown] (backtesting.py prints 4 to 5 decimals)."""
    return value.quantize(Decimal(1).scaleb(shown.as_tuple().exponent), rounding="ROUND_HALF_EVEN")


def compare_trips(q: list[dict], r: list[dict]) -> tuple[int, int, int]:
    """(trades on the same dates and side, of those also the same prices, of those also the same qty)."""
    ref = {(t["entry_date"], t["exit_date"], t["side"]): t for t in r}
    timing = prices = qty = 0
    for t in q:
        o = ref.get((t["entry_date"], t["exit_date"], t["side"]))
        if o is None:
            continue
        timing += 1
        if abs(t["entry_px"] - o["entry_px"]) <= PX and abs(t["exit_px"] - o["exit_px"]) <= PX:
            prices += 1
            qty += t["qty"] == o["qty"]
    return timing, prices, qty


def bt_fills_identical(rd: Path, ref_fills: list) -> tuple[int, int, int]:
    """(qkt fills, backtrader fills, identical in order: date, side, size, price)."""
    q = [(day(r["timestamp"]), r["side"], dec(r["quantity"]), dec(r["price"])) for r in fills(rd)]
    b = [(f[0], f[1], abs(dec(f[2])), dec(f[3])) for f in ref_fills]
    same = sum(1 for x, y in zip(q, b) if x[:3] == y[:3] and abs(x[3] - y[3]) <= PX)
    return len(q), len(b), same


# --- the cases ------------------------------------------------------------------------------------------------


def check_btpy(rd: Path, refs: Path, c: dict) -> str:
    """backtesting.py SmaCross on GOOG: trades, final equity, max drawdown and win rate."""
    q, r = round_trips(rd), btpy_trades(refs / c["trades"])
    expect("trade count", (len(q), len(r)), (c["trades_n"], c["trades_n"]))
    timing, prices, qty = compare_trips(q, r)
    expect("trades on backtesting.py's dates and side", timing, c["trades_n"])
    expect("trades also at its prices", prices, c["trades_n"])
    expect("trades also of its size", qty, c["same_qty"])
    if "final" in c:
        stats = refs / c["stats"]
        final = c["start"] + total_pnl(rd)
        want = btpy_stat(stats, "Equity Final [$]")
        expect("final equity", at_shown_precision(final, want), want)
        g = json.load(open(rd / "result.json"))["global"]
        dd = btpy_stat(stats, "Max. Drawdown [%]")
        expect("max drawdown %", at_shown_precision(-Decimal(g["maxDrawdown"]) * 100, dd), dd)
        win = btpy_stat(stats, "Win Rate [%]")
        expect("win rate %", at_shown_precision(Decimal(g["winRate"]) * 100, win), win)
        return f"{c['trades_n']} trades identical, final {final}, max DD {dd}%, win {win}%"
    return f"{c['trades_n']} trades on its dates and prices, {qty} of its size ({c['why']})"


def check_bt_fills(rd: Path, refs: Path, c: dict) -> str:
    """backtrader: every fill identical in order, the final portfolio value to the cent."""
    ref = json.load(open(refs / c["ref"]))[c["key"]]
    nq, nb, same = bt_fills_identical(rd, ref["fills"])
    n = c["fills"] if c["fills"] is not None else len(ref["fills"])
    expect("fills (qkt, backtrader, identical)", (nq, nb, same), (n, n, n))
    final = (c["start"] + total_pnl(rd)).quantize(CENT)
    expect("final value", final, dec(ref["final"]).quantize(CENT))
    if "exact" in c:
        expect("final value, unrounded", (c["start"] + total_pnl(rd)).normalize(), c["exact"].normalize())
        final = c["exact"]
    return f"{same}/{nb} fills identical, final {final}"


def check_bracket(rd: Path, refs: Path, c: dict) -> str:
    """backtrader's bracket sample: entry, stop and target levels, fills, P&L."""
    ref = json.load(open(refs / c["ref"]))
    reqs = [json.loads(line)["request"] for line in open(rd / "orders.jsonl")]
    brackets = [b for b in reqs if b.get("orderType") == "Bracket"]
    levels = sum(
        1
        for b, s in zip(brackets, ref["signals"])
        if abs(dec(b["entry"]["limitPrice"]) - dec(s[1])) <= PX
        and abs(dec(b["stopLoss"]["price"]) - dec(s[2])) <= PX
        and abs(dec(b["takeProfit"]) - dec(s[3])) <= PX
    )
    n = len(ref["signals"])
    expect("bracket levels (qkt, backtrader, identical)", (len(brackets), n, levels), (n, n, n))
    done = [e for e in ref["events"] if e["status"] == "Completed"]
    q = [(day(x["timestamp"]), x["side"], dec(x["price"])) for x in fills(rd)]
    if "pnl" in c:
        same = sum(1 for x, e in zip(q, done) if x[:2] == (e["date"], e["side"]) and abs(x[2] - dec(e["price"])) <= PX)
        expect("fills (qkt, backtrader, identical)", (len(q), len(done), same), (c["fills"], c["fills"], c["fills"]))
        pnl = total_pnl(rd).quantize(Decimal("0.0001"))
        expect("P&L", pnl, (dec(ref["final"]) - c["start"]).quantize(Decimal("0.0001")))
        expect("P&L as published", pnl, c["pnl"])
        return f"{n}/{n} levels, {same}/{len(done)} fills, P&L {pnl}"
    # Round trip by round trip: identical, or the one documented convention difference.
    qt = [(q[i], q[i + 1]) for i in range(0, len(q), 2)]
    bt = [(done[i], done[i + 1]) for i in range(0, len(done), 2)]
    expect("round trips (qkt, backtrader)", (len(qt), len(bt)), (c["trips"], c["trips"]))
    kinds = {"identical": 0, "exit on the entry bar": 0, "other": 0}
    for (qa, qb), (ba, bb) in zip(qt, bt):
        entry_same = qa[0] == ba["date"] and abs(qa[2] - dec(ba["price"])) <= PX
        exit_same = qb[0] == bb["date"] and abs(qb[2] - dec(bb["price"])) <= PX
        if entry_same and exit_same:
            kinds["identical"] += 1
        elif entry_same and qb[0] == qa[0] and bb["date"] > ba["date"]:
            kinds["exit on the entry bar"] += 1
        else:
            kinds["other"] += 1
    expect("round trips by kind", kinds, c["kinds"])
    return f"{n}/{n} levels; {kinds['identical']} round trips identical, {kinds['exit on the entry bar']} exit on the entry bar ({c['why']})"


def bt(key: str) -> dict:
    """backtrader Quickstart SMA(maperiod) on ORCL 2000: every published Ending Value, every fill."""
    return dict(
        id=f"b_sma_{key}",
        args=["strategies/b_bt_quickstart_sma.qkt", "--from", "2000-01-03", "--to", "2000-12-30", "--data-root", "data-root",
              "--config", "cfg/nohalt_1k.yaml", "--param", f"maperiod={key}"],
        check=check_bt_fills,
        ref="refs/backtrader_orcl_sma_comm0.0.json",
        key=key,
        start=Decimal(1000),
        fills=None,
    )


GOOG = ["--from", "2004-08-19", "--to", "2013-03-02"]
CASES = [
    dict(
        id="a_fixed",
        args=["strategies/a_smacross_fixed.qkt", *GOOG, "--data-root", "data-root", "--config", "cfg/nohalt_1m.yaml"],
        check=check_btpy, trades="refs/btpy_goog_fixed50_trades.csv", stats="refs/btpy_goog_fixed50_stats.txt",
        trades_n=93, same_qty=93, start=Decimal(1_000_000), final=True,
    ),
    dict(
        id="a_fixed_comm",
        args=["strategies/a_smacross_fixed.qkt", *GOOG, "--data-root", "data-root-perp", "--config", "cfg/nohalt_1m.yaml",
              "--funding", "off"],
        check=check_btpy, trades="refs/btpy_goog_fixed50_comm0.002_trades.csv", stats="refs/btpy_goog_fixed50_comm0.002_stats.txt",
        trades_n=93, same_qty=93, start=Decimal(1_000_000), final=True,
    ),
    dict(
        id="a_equity",
        args=["strategies/a_smacross_equity.qkt", *GOOG, "--data-root", "data-root", "--config", "cfg/nohalt.yaml"],
        check=check_btpy, trades="refs/btpy_goog_comm0.0_trades.csv",
        trades_n=93, same_qty=48,
        why="sizing convention: qkt floor(balance after exit / signal-bar close), backtesting.py floor(equity / fill open)",
    ),
    dict(
        id="a_equity_comm",
        args=["strategies/a_smacross_equity.qkt", *GOOG, "--data-root", "data-root-perp", "--config", "cfg/nohalt.yaml",
              "--funding", "off"],
        check=check_btpy, trades="refs/btpy_goog_comm0.002_trades.csv",
        trades_n=93, same_qty=49,
        why="same sizing convention, with commission",
    ),
    *[bt(str(p)) for p in range(10, 31)],
    dict(
        id="c_3lower_comm0",
        args=["strategies/c_bt_quickstart_3lower.qkt", "--from", "2000-01-03", "--to", "2000-12-30", "--data-root", "data-root",
              "--config", "cfg/nohalt_100k.yaml"],
        check=check_bt_fills, ref="refs/backtrader_orcl_3lower.json", key="0.0", start=Decimal(100_000), fills=47,
    ),
    dict(
        id="c_3lower_comm001",
        args=["strategies/c_bt_quickstart_3lower.qkt", "--from", "2000-01-03", "--to", "2000-12-30", "--data-root",
              "data-root-perp-orcl", "--funding", "off", "--config", "cfg/nohalt_100k.yaml"],
        check=check_bt_fills, ref="refs/backtrader_orcl_3lower.json", key="0.001", start=Decimal(100_000), fills=47,
        exact=Decimal("100169.798"),
    ),
    dict(
        id="d_bracket",
        args=["strategies/d_bt_bracket.qkt", "--from", "2005-01-03", "--to", "2006-12-30", "--data-root", "data-root",
              "--config", "cfg/nohalt.yaml"],
        check=check_bracket, ref="refs/backtrader_bracket.json", start=Decimal(10_000), fills=16, pnl=Decimal("-12.4442"),
    ),
    dict(
        id="d_bracket_goog",
        args=["strategies/d_bt_bracket_goog.qkt", *GOOG, "--data-root", "data-root", "--config", "cfg/nohalt.yaml"],
        check=check_bracket, ref="refs/backtrader_bracket_goog.json", start=Decimal(10_000), trips=48,
        kinds={"identical": 33, "exit on the entry bar": 15, "other": 0},
        why="backtrader defers bracket children to the next bar",
    ),
]


def run_case(c: dict, qkt: str, refs: Path, work: Path) -> dict:
    started = time.monotonic()
    rd = work / "runs" / c["id"]
    env = {k: v for k, v in os.environ.items() if k not in ("QKT_DATA_HOME", "QKT_CONFIG", "JAVA_OPTS", "QKT_OPTS")}
    env["JAVA_OPTS"] = f"-Duser.home={work / 'home'}"
    env["XDG_CONFIG_HOME"] = str(work / "home" / ".config")
    cmd = [qkt, "backtest", *c["args"], "--no-fetch", "--report-dir", str(rd)]
    proc = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=refs)
    try:
        if proc.returncode != 0:
            raise Failure(f"qkt exited {proc.returncode}: {(proc.stdout + proc.stderr).strip()[-400:]}")
        detail = c["check"](rd, refs, c)
        return {"id": c["id"], "ok": True, "seconds": round(time.monotonic() - started, 1), "detail": detail}
    except Failure as e:
        return {"id": c["id"], "ok": False, "seconds": round(time.monotonic() - started, 1), "error": str(e),
                "command": " ".join(cmd[1:])}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qkt", default=str(REPO / "build/install/qkt/bin/qkt"))
    ap.add_argument("--refs", default=os.environ.get("QKT_REFERENCES", str(REPO.parent / "qkt-data-corpus" / "references")))
    ap.add_argument("--case", action="append", help="run only these case ids")
    ap.add_argument("--keep", action="store_true", help="keep the work folder")
    ap.add_argument("--report", default=None, help="write the results as JSON here")
    args = ap.parse_args()

    source = Path(args.refs).resolve()
    if not (source / "refs").is_dir():
        print(f"references: no reference data at {source}", file=sys.stderr)
        return 2
    if not Path(args.qkt).is_file():
        print(f"references: no qkt at {args.qkt} (./gradlew installDist)", file=sys.stderr)
        return 2
    cases = CASES
    if args.case:
        unknown = set(args.case) - {c["id"] for c in CASES}
        if unknown:
            print(f"references: unknown case(s) {sorted(unknown)}", file=sys.stderr)
            return 2
        cases = [c for c in CASES if c["id"] in args.case]
    work = Path(tempfile.mkdtemp(prefix="qkt-references-"))
    (work / "home").mkdir()
    # A private copy: qkt writes locks and manifests into a data root, and the corpus stays as checked out.
    refs = work / "refs-copy"
    shutil.copytree(source, refs)
    results = []
    try:
        for c in cases:
            r = run_case(c, str(Path(args.qkt).resolve()), refs, work)
            results.append(r)
            print(f"{'ok  ' if r['ok'] else 'FAIL'} {r['id']:20} {r['seconds']:6.1f}s  {r.get('detail') or r.get('error')}", flush=True)
            if not r["ok"]:
                print(f"       $ qkt {r['command']}")
    finally:
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)
    failed = [r for r in results if not r["ok"]]
    print(f"references: {len(results) - len(failed)}/{len(results)} cases passed")
    if args.report:
        Path(args.report).write_text(json.dumps({"results": results}, indent=1) + "\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
