#!/usr/bin/env python3
"""The backtest audit as a release proof: 48 scenarios (fill models, costs, risk halts, windows, scale, other
strategies, breakers, two symbols) run on a pinned store of Exness gold and bitcoin ticks, then
  - verify.py recomputes each bundle's headline numbers from its fills and equity files, without qkt's code;
  - analyze.py checks 39 relations between runs (reruns identical, halts stop entries, caps hold, windows add up);
  - each run's trades, orders and equity files must match expected.json, so a change in any number is reviewed.

    python3 -I scripts/release-proof/audit/run.py --qkt build/install/qkt/bin/qkt --store <corpus>/stores/exness-2026-06
    ... --update     rewrite expected.json after reading what changed and why

Exit 0 only when every bundle check, every relation and every fingerprint holds.
"""
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
W1 = ["--from", "2026-06-01", "--to", "2026-06-18"]
W2 = ["--from", "2026-07-06", "--to", "2026-07-31"]
WB = ["--from", "2026-07-01", "--to", "2026-07-28"]
G = "strat/gold_ema_cross.qkt"
# The files a run's numbers live in; result.json and manifest.json also name the report folder.
FINGERPRINTED = ("trades.csv", "orders.jsonl", "rejections.csv", "equity_daily.csv", "equity_global.csv", "monthly_returns.csv", "pnl_components.csv")


def cfg(n):
    return ["--config", f"cfg/{n}.yaml"]


S = {}  # name -> (strategy, window, args)


def add(name, strat=G, win=W1, *args):
    S[name] = (strat, win, list(args))


# --- A. fill models -------------------------------------------------------------------------------
add("a01_raw_paper", G, W1, *cfg("base"))
add("a02_raw_paper_rerun", G, W1, *cfg("base"))
add("a03_mt5sim", G, W1, *cfg("base"), "--broker", "mt5-sim")
add("a04_mt5sim_rerun", G, W1, *cfg("base"), "--broker", "mt5-sim")
add("a05_exec_mt5basic", G, W1, *cfg("base"), "--execution", "mt5-basic")
add("a06_exec_mt5realistic", G, W1, *cfg("base"), "--execution", "mt5-realistic")
add("a07_chaos", G, W1, *cfg("base"), "--chaos")
add("a08_chaos_rerun", G, W1, *cfg("base"), "--chaos")
add("a09_slip_zero", G, W1, *cfg("base"), "--broker", "mt5-sim", "--slippage", "zero")
add("a10_slip_fixed30", G, W1, *cfg("base"), "--broker", "mt5-sim", "--slippage", "fixed-points:30")
add("a11_slip_uniform30_seed7", G, W1, *cfg("base"), "--broker", "mt5-sim", "--slippage", "uniform:30", "--seed", "7")
add("a12_slip_uniform30_seed7_rerun", G, W1, *cfg("base"), "--broker", "mt5-sim", "--slippage", "uniform:30", "--seed", "7")
add("a13_slip_uniform30_seed8", G, W1, *cfg("base"), "--broker", "mt5-sim", "--slippage", "uniform:30", "--seed", "8")
add("a14_latency_1s", G, W1, *cfg("base"), "--broker", "mt5-sim", "--execution-latency", "1s")
# --- B. costs -------------------------------------------------------------------------------------
add("b01_commission7", G, W1, *cfg("base"), "--instruments", "cfg/instr_comm.yaml")
add("b02_swap", G, W1, *cfg("base"), "--instruments", "cfg/instr_swap.yaml")
# --- C. risk halts ----------------------------------------------------------------------------------
add("c01_default_risk_no_config", G, W1)  # no --config: what a user gets out of the box
add("c02_nohalt_explicit", G, W1, *cfg("nohalt"))
add("c03_dailyloss60", G, W1, *cfg("dailyloss60"))
add("c04_dd_static_05", G, W1, *cfg("dd_static_05"))
add("c05_dd_trailing_10", G, W1, *cfg("dd_trailing_10"))
add("c06_ddaily_balance_08", G, W1, *cfg("ddaily_bal_08"))
add("c07_ddaily_equity_08", G, W1, *cfg("ddaily_eq_08"))
add("c08_streak2_daily", G, W1, *cfg("streak2_daily"))
add("c09_streak2_persistent", G, W1, *cfg("streak2_persist"))
add("c10_maxtrades2", G, W1, *cfg("maxtrades2"))
add("c11_cooldown1h", G, W1, *cfg("cooldown1h"))
add("c12_strategy_dailyloss40", G, W1, *cfg("strat_dailyloss40"))
add("c13_roundtrips1_observe", G, W1, *cfg("roundtrips1"))
add("c14_roundtrips1_enforce", G, W1, *cfg("roundtrips1"), "--enforce-live-breakers")
# --- D. reporting windows & scale -----------------------------------------------------------------
add("d01_windows", G, W1, *cfg("base"), "--metrics-window", "wk1=2026-06-01..2026-06-08", "--metrics-window", "wk2=2026-06-08..2026-06-18")
add("d02_oos_split", G, W1, *cfg("base"), "--oos-split", "0.6")
add("d03_balance_100k", G, W1, *cfg("bal100k"))
# --- E. other strategies / instruments / longer window ----------------------------------------------
add("e01_fixed_lot_rsi", "strat/gold_rsi_fixed.qkt", W1, *cfg("base"))
add("e02_fixed_lot_rsi_mt5sim", "strat/gold_rsi_fixed.qkt", W1, *cfg("base"), "--broker", "mt5-sim")
add("e03_btc_paper", "strat/btc_ema.qkt", W2, *cfg("base"), "--instruments", "cfg/instr_btc.yaml")
add("e04_btc_mt5sim", "strat/btc_ema.qkt", W2, *cfg("base"), "--instruments", "cfg/instr_btc.yaml", "--broker", "mt5-sim")
add("e05_long_paper", G, W2, *cfg("base"))
add("e06_long_mt5sim", G, W2, *cfg("base"), "--broker", "mt5-sim")
add("e07_long_default_risk", G, W2)
# --- F. param equivalence (standalone runs the sweep must reproduce) -------------------------------------
for f in (5, 9, 13):
    add(f"f0_param_fast{f}", "strat/gold_ema_param.qkt", W1, *cfg("base"), "--param", f"fast={f}")

# --- G. breakers that actually trip, multi-symbol ------------------------------------------------------
WS = ["--from", "2026-06-02", "--to", "2026-06-04"]
add("g01_churn_observe", "strat/churn.qkt", WS, *cfg("churn3"))
add("g02_churn_enforce", "strat/churn.qkt", WS, *cfg("churn3"), "--enforce-live-breakers")
add("g05_churn_mt5sim", "strat/churn.qkt", WS, *cfg("base"), "--broker", "mt5-sim")
add("g03_multi_xau_btc", "strat/multi_xau_btc.qkt", W2, *cfg("base"), "--instruments", "cfg/instr_btc.yaml")
add("g04_multi_xau_btc_mt5sim", "strat/multi_xau_btc.qkt", W2, *cfg("base"), "--instruments", "cfg/instr_btc.yaml", "--broker", "mt5-sim")



def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else "absent"


def run_one(name, qkt, store, out, home):
    strat, win, args = S[name]
    env = {k: v for k, v in os.environ.items() if k not in ("QKT_DATA_HOME", "QKT_CONFIG", "JAVA_OPTS")}
    env["JAVA_OPTS"] = f"-Duser.home={home}"
    env["XDG_CONFIG_HOME"] = str(home / ".config")
    cmd = [qkt, "backtest", strat, *win, "--data-root", str(store), "--no-fetch", *args, "--report-dir", str(out / name)]
    t0 = time.time()
    with open(out / f"{name}.log", "w") as log:
        rc = subprocess.run(cmd, cwd=HERE, stdout=log, stderr=subprocess.STDOUT, env=env).returncode
    return name, rc, round(time.time() - t0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--qkt", required=True)
    ap.add_argument("--store", required=True, help="the pinned tick store (qkt-data-corpus/stores/exness-2026-06)")
    ap.add_argument("--out", default=None, help="keep the bundles here (default: a temporary folder)")
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--report", default=None)
    a = ap.parse_args()
    out = Path(a.out or tempfile.mkdtemp(prefix="qkt-audit-"))
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    home = out / "home"
    home.mkdir()
    qkt = str(Path(a.qkt).resolve())
    store = Path(a.store).resolve()
    failures = []
    with ThreadPoolExecutor(a.jobs) as ex:
        for name, rc, secs in ex.map(lambda n: run_one(n, qkt, store, out, home), S):
            print(f"  ran {name:34} rc={rc} {secs}s", flush=True)
            if rc != 0:
                failures.append(f"{name}: qkt backtest exited {rc} (see {out}/{name}.log)")

    checks_total = checks_bad = 0
    for name in S:
        proc = subprocess.run([sys.executable, "-I", str(HERE / "verify.py"), str(out / name)], capture_output=True, text=True)
        try:
            checks = json.loads(proc.stdout)["checks"]
        except (json.JSONDecodeError, KeyError):
            failures.append(f"{name}: verify.py could not read the bundle: {proc.stderr.strip()[-200:]}")
            continue
        checks_total += len(checks)
        for c in checks:
            if not c["ok"]:
                checks_bad += 1
                failures.append(f"{name}: {c['name']}: {c['detail'][:200]}")
    rel = subprocess.run([sys.executable, "-I", str(HERE / "analyze.py"), str(out)], capture_output=True, text=True, cwd=HERE)
    rel_lines = [line for line in rel.stdout.splitlines() if line.startswith(("PASS", "FAIL", "INFO"))]
    rel_fail = [line for line in rel_lines if line.startswith("FAIL")]
    if rel.returncode != 0:
        failures.append(f"analyze.py exited {rel.returncode}: {rel.stderr.strip()[-300:]}")
    failures += [f"relation {line}" for line in rel_fail]

    got = {name: {f: sha(out / name / f) for f in FINGERPRINTED} for name in S}
    moved = 0
    expected_path = HERE / "expected.json"
    if a.update:
        expected_path.write_text(json.dumps(got, indent=1, sort_keys=True) + "\n")
    else:
        want = json.loads(expected_path.read_text())
        for name in S:
            changed = [f for f in FINGERPRINTED if got[name].get(f) != want.get(name, {}).get(f)]
            if changed:
                moved += 1
                failures.append(f"{name}: {', '.join(changed)} differ from expected.json")

    passed = sum(1 for line in rel_lines if line.startswith("PASS"))
    for f in failures[:60]:
        print("  FAIL", f)
    # Last, so the release proofs (which keep a proof's last line) report the counts, not one failure.
    print(f"audit: {len(S)} scenarios, bundle checks {checks_total - checks_bad}/{checks_total}, relations {passed} PASS / "
          f"{len(rel_fail)} FAIL, {moved} scenario(s) differ from expected.json")
    if a.report:
        Path(a.report).write_text(json.dumps({"scenarios": len(S), "checks": checks_total, "checksFailed": checks_bad, "relationsPassed": passed, "relationsFailed": len(rel_fail), "failures": failures}, indent=1) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
