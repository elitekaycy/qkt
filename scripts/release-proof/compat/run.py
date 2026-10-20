#!/usr/bin/env python3
"""Store compatibility as a release proof: a store written by the previous release (imports, converted tick days,
built bars, a dataset snapshot) is read by the candidate exactly as a store the candidate wrote itself. Every
fleet upgrade puts a new qkt on an old store; this is that upgrade, on real files.

    python3 -I scripts/release-proof/compat/run.py --qkt build/install/qkt/bin/qkt --previous <old>/bin/qkt \\
        --corpus <qkt-data-corpus>

Exit 0 only when the candidate verifies the old store, reads the same bars from it (plain and --bars), accepts
the old snapshot, and backtests it to the same trades as its own store.
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

DAY = "2026-10-14"
STRATEGY = """STRATEGY compat_cross VERSION 1

SYMBOLS
    ada = BACKTEST:ADAUSDT EVERY 5m

RULES
    WHEN ema(ada.close, 5) CROSSES ABOVE ema(ada.close, 20) AND POSITION.ada = 0
    THEN BUY ada SIZING 1000
    WHEN ema(ada.close, 5) CROSSES BELOW ema(ada.close, 20) AND POSITION.ada > 0
    THEN CLOSE ada
"""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qkt", required=True)
    ap.add_argument("--previous", required=True, help="the previous release's bin/qkt")
    ap.add_argument("--corpus", required=True)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    new, old = str(Path(a.qkt).resolve()), str(Path(a.previous).resolve())
    corpus = Path(a.corpus).resolve()
    out = Path(a.out or tempfile.mkdtemp(prefix="qkt-compat-"))
    shutil.rmtree(out, ignore_errors=True)
    (out / "home").mkdir(parents=True)
    env = {k: v for k, v in os.environ.items() if k not in ("QKT_DATA_HOME", "QKT_CONFIG", "JAVA_OPTS")}
    env["JAVA_OPTS"] = f"-Duser.home={out / 'home'}"
    env["XDG_CONFIG_HOME"] = str(out / "home" / ".config")
    (out / "s.qkt").write_text(STRATEGY)
    failures: list[str] = []

    def q(binary, *args):
        p = subprocess.run([binary, *args], cwd=out, env=env, capture_output=True, text=True)
        return p.returncode, p.stdout, p.stderr

    def must(binary, *args):
        rc, so, se = q(binary, *args)
        if rc != 0:
            raise SystemExit(f"compat: `{Path(binary).parent.parent.name} {' '.join(args)}` exited {rc}: {(so + se).strip()[-400:]}")
        return so

    # The same real files, written into one store by each release.
    inputs = [
        # Formats both releases import (Binance trades presets are newer than 0.57.2).
        (f"bybit/ADAUSDT_{DAY}.csv.gz", "ADAUSDT", []),
        (f"binance/spot/BTCUSDT-1m-{DAY}.zip", "BINANCE:BTCUSDT", []),
        # 0.57.2 refuses this month's late Sunday open; both read it on the preset's clock.
        ("histdata/EURUSD-ASCII-M1-2026-03.zip", "HISTDATA:EURUSD", ["--trust-clock"]),
    ]
    stores = {"old": out / "store-old", "new": out / "store-new"}
    for who, binary in (("old", old), ("new", new)):
        root = str(stores[who])
        for rel, symbol, extra in inputs:
            must(binary, "data", "import", str(corpus / rel), "--as", symbol, *extra, "--data-root", root)
        must(binary, "data", "convert", "ADAUSDT", "--data-root", root)
        must(binary, "data", "build-bars", "ADAUSDT", "--tf", "1m", "--data-root", root)
        must(binary, "data", "snapshot", "ADAUSDT", "--from", DAY, "--to", "2026-10-15", "--out", str(out / f"snap-{who}.json"),
             "--data-root", root)

    old_root, new_root = str(stores["old"]), str(stores["new"])
    for series in ("ADAUSDT", "BINANCE:BTCUSDT", "HISTDATA:EURUSD"):
        rc, so, se = q(new, "data", "verify", series, "--data-root", old_root)
        if rc != 0:
            failures.append(f"the candidate's verify flags the old store's {series}: {(so + se).strip()[-300:]}")
    reads = [("ADAUSDT", "1m", False), ("ADAUSDT", "1h", True), ("BINANCE:BTCUSDT", "1h", False), ("HISTDATA:EURUSD", "1d", False)]
    for series, tf, built in reads:
        args = ["data", "bars", series, "--tf", tf, "--from", "2026-03-01", "--to", "2026-10-14"] + (["--bars"] if built else [])
        if series == "ADAUSDT":
            args[5:9] = ["--from", DAY, "--to", DAY]
        if series == "HISTDATA:EURUSD":
            # 0.61.0 reads HistData's clock as London minus 5h (measured; the presets assumed a fixed -05:00), so a
            # file imported by each release differs from the EU summer-time switch on (2026-03-29): the correction,
            # not a reading change. Before it both clocks are UTC-5; data-e2e checks the rest against an oracle.
            args[5:9] = ["--from", "2026-03-01", "--to", "2026-03-28"]
        a_old, a_new = must(new, *args, "--data-root", old_root), must(new, *args, "--data-root", new_root)
        if a_old != a_new:
            failures.append(f"{series} {tf}{' --bars' if built else ''}: the candidate reads different bars from the old store")
    rc, so, se = q(new, "data", "verify", "ADAUSDT", "--snapshot", str(out / "snap-old.json"), "--data-root", old_root)
    if rc != 0:
        failures.append(f"the candidate refuses the previous release's snapshot: {(so + se).strip()[-300:]}")
    for who in ("old", "new"):
        must(new, "backtest", "s.qkt", "--from", DAY, "--to", "2026-10-15", "--data-root", str(stores[who]), "--no-fetch",
             "--dataset", str(out / f"snap-{who}.json"), "--report-dir", str(out / f"run-{who}"))
    for f in ("trades.csv", "orders.jsonl", "equity_daily.csv"):
        if (out / "run-old" / f).read_bytes() != (out / "run-new" / f).read_bytes():
            failures.append(f"backtest on the old store: {f} differs from the candidate's own store")

    print(f"compat: {Path(old).parent.parent.name} store read by the candidate: {'ok' if not failures else 'FAILED'}")
    for f in failures:
        print("FAIL", f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
