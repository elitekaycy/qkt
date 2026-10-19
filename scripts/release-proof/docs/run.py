#!/usr/bin/env python3
"""The docs examples as a release proof: every example page's files are written out as a user would copy them,
its backtest runs on the local store, and the run must match expected.json (trades, orders, equity), so a qkt
change that alters what a docs page shows is reviewed, and a page whose strategy stops running is caught.

    python3 -I scripts/release-proof/docs/run.py --qkt build/install/qkt/bin/qkt --data ~/.qkt/data
    ... --update     rewrite expected.json and the store pins after reading what changed and why

The store is the local one (the examples read eight months of gold and bitcoin ticks, too much to ship), pinned
by qkt data snapshot files beside this script: a store that changed under a pinned window fails as such, not as
a wrong result. Where the store lacks the data an example names, the stand-in is written down in EXAMPLES.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))

from extract import files_on  # noqa: E402

GOLD = ("2026-07-06", "2026-08-29")
BTC = ("2026-01-02", "2026-09-15")
# The local store holds Exness BTCUSD ticks, not Bybit spot bars: the Bybit examples run on them.
BYBIT_STANDIN = {"BYBIT_SPOT:BTCUSDT": "BACKTEST:BTCUSD"}
INSTRUMENTS = ("docs/examples/breakout.md", "instruments.yaml")

EXAMPLES = [
    dict(id="breakout", page="docs/examples/breakout.md", run="strategies/breakout-donchian.qkt", window=BTC,
         args=["--instruments", "instruments.yaml"]),
    dict(id="cross-btc", page="docs/examples/cross-broker.md", run="strategies/btc-bybit.qkt", window=BTC,
         args=["--instruments", "instruments.yaml"], extra=[INSTRUMENTS], standin=BYBIT_STANDIN),
    dict(id="cross-gold", page="docs/examples/cross-broker.md", run="strategies/gold-exness.qkt", window=GOLD),
    dict(id="fullstack-btc", page="docs/examples/full-stack/index.md", run="strategies/btc-trend.qkt", window=BTC,
         args=["--instruments", "instruments.yaml"], extra=[INSTRUMENTS], standin=BYBIT_STANDIN),
    dict(id="fullstack-gold", page="docs/examples/full-stack/index.md", run="strategies/gold-meanrev.qkt", window=GOLD),
    dict(id="gold-multi-tf", page="docs/examples/gold-multi-timeframe.md", run="gold_multi_tf.qkt", window=GOLD),
    dict(id="mean-reversion", page="docs/examples/mean-reversion.md", run="strategies/mean-rev-rsi.qkt", window=GOLD),
    dict(id="portfolio", page="docs/examples/portfolio.md", run="strategies/portfolio.qkt", window=BTC),
    dict(id="pyramiding", page="docs/examples/pyramiding.md", run="strategies/pyramid.qkt", window=BTC),
    dict(id="risk-managed", page="docs/examples/risk-managed.md", run="strategies/risk-managed.qkt", window=GOLD,
         skip=["qkt.config.yaml"]),
    dict(id="risk-managed-config", page="docs/examples/risk-managed.md", run="strategies/risk-managed.qkt",
         window=GOLD, args=["--config", "qkt.config.yaml"]),
    dict(id="trend-follow", page="docs/examples/trend-follow.md", run="strategies/trend-follow.qkt",
         window=("2026-07-01", "2026-08-01")),
]
PINS = [("XAUUSD", "2026-07-01", "2026-08-30"), ("BTCUSD", "2026-01-01", "2026-09-16")]
FINGERPRINTED = ("trades.csv", "orders.jsonl", "equity_daily.csv", "monthly_returns.csv", "rejections.csv")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "absent"


def project(ex: dict, into: Path) -> None:
    """Write the example's page files (only the ones a backtest reads: .qkt and .yaml) as a user would."""
    files = {k: v for k, v in files_on(REPO / ex["page"]).items() if k.endswith((".qkt", ".yaml"))}
    for page, name in ex.get("extra", []):
        files[name] = files_on(REPO / page)[name]
    for name in ex.get("skip", []):
        files.pop(name, None)
    for name in ("docker-compose.yml",):
        files.pop(name, None)
    for name, body in files.items():
        for real, standin in ex.get("standin", {}).items():
            body = body.replace(real, standin)
        target = into / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body)
    if ex["run"] not in files:
        raise SystemExit(f"{ex['id']}: {ex['page']} no longer shows {ex['run']}")
    if not any(a == "--config" for a in ex.get("args", [])) and (into / "qkt.config.yaml").exists():
        (into / "qkt.config.yaml").unlink()  # a backtest picks up a config in its folder; only some examples use it


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qkt", required=True)
    ap.add_argument("--data", required=True, help="the local store the examples read")
    ap.add_argument("--out", default=None)
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--report", default=None)
    a = ap.parse_args()
    qkt, data = str(Path(a.qkt).resolve()), Path(a.data).expanduser().resolve()
    out = Path(a.out or tempfile.mkdtemp(prefix="qkt-docs-proof-"))
    shutil.rmtree(out, ignore_errors=True)
    (out / "home").mkdir(parents=True)
    env = {k: v for k, v in os.environ.items() if k not in ("QKT_DATA_HOME", "QKT_CONFIG", "JAVA_OPTS")}
    env["JAVA_OPTS"] = f"-Duser.home={out / 'home'}"
    env["XDG_CONFIG_HOME"] = str(out / "home" / ".config")
    failures = []

    def qkt_run(args, cwd):
        return subprocess.run([qkt, *args], cwd=cwd, env=env, capture_output=True, text=True)

    for symbol, frm, to in PINS:
        pin = HERE / f"pin-{symbol}.json"
        if a.update:
            p = qkt_run(["data", "snapshot", symbol, "--from", frm, "--to", to, "--out", str(pin), "--allow-missing-days",
                         "--quality", "research", "--data-root", str(data)], out)
            if p.returncode != 0:
                failures.append(f"pin {symbol}: {p.stderr.strip()[-300:]}")
        p = qkt_run(["data", "verify", symbol, "--snapshot", str(pin), "--data-root", str(data)], out)
        if p.returncode != 0:
            failures.append(f"the store changed under the pinned {symbol} days: {(p.stdout + p.stderr).strip()[-300:]}")
    if failures:
        print("\n".join("FAIL " + f for f in failures))
        return 1

    expected_path = HERE / "expected.json"
    want = {} if a.update else json.loads(expected_path.read_text())
    got = {}
    for ex in EXAMPLES:
        work = out / ex["id"]
        project(ex, work)
        frm, to = ex["window"]
        p = qkt_run(["backtest", ex["run"], "--from", frm, "--to", to, "--data-root", str(data), "--no-fetch",
                     *ex.get("args", []), "--report-dir", str(work / "report")], work)
        (work / "run.log").write_text(p.stdout + p.stderr)
        if p.returncode != 0:
            failures.append(f"{ex['id']}: qkt backtest exited {p.returncode}: {(p.stdout + p.stderr).strip()[-300:]}")
            continue
        got[ex["id"]] = {f: sha(work / "report" / f) for f in FINGERPRINTED}
        result = json.loads((work / "report" / "result.json").read_text())["global"]
        line = f"{ex['id']:22} totalPnL {result.get('totalPnL')}"
        if not a.update:
            changed = [f for f in FINGERPRINTED if got[ex["id"]][f] != want.get(ex["id"], {}).get(f)]
            if changed:
                failures.append(f"{ex['id']}: {', '.join(changed)} differ from expected.json")
                line += "  CHANGED: " + ", ".join(changed)
        print(line, flush=True)
    if a.update and not failures:
        expected_path.write_text(json.dumps(got, indent=1, sort_keys=True) + "\n")
    print(f"docs examples: {len(got)}/{len(EXAMPLES)} ran" + ("" if a.update else f", {len(got) - len([f for f in failures if 'differ' in f])} as expected"))
    for f in failures:
        print("FAIL", f)
    if a.report:
        Path(a.report).write_text(json.dumps({"examples": len(EXAMPLES), "ran": len(got), "failures": failures}, indent=1) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
