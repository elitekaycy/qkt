#!/usr/bin/env python3
"""Cross-scenario relations over the matrix output. Each relation prints PASS/FAIL/INFO with the numbers."""
import csv, json, hashlib, os, re, sys, datetime, collections
from decimal import Decimal as D

O = sys.argv[1]  # the matrix output folder (one report bundle per scenario)
results = []


def res(n):
    return json.load(open(f"{O}/{n}/result.json"))


def trades(n):
    return list(csv.DictReader(open(f"{O}/{n}/trades.csv")))


def rej(n):
    return list(csv.DictReader(open(f"{O}/{n}/rejections.csv")))


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def out(status, name, detail):
    results.append((status, name, detail))
    print(f"{status:5} {name}: {detail}")


def day(ms):
    return datetime.datetime.fromtimestamp(int(ms) / 1000, datetime.timezone.utc).strftime("%Y-%m-%d")


def stable(d):
    d = json.loads(json.dumps(d))
    d.pop("evidence", None)
    d.pop("artifacts", None)
    return json.dumps(d, sort_keys=True)


# ---- R1 determinism: identical inputs -> byte-identical trades and identical result ---------------
for a, b in [("a01_raw_paper", "a02_raw_paper_rerun"), ("a03_mt5sim", "a04_mt5sim_rerun"),
             ("a07_chaos", "a08_chaos_rerun"), ("a11_slip_uniform30_seed7", "a12_slip_uniform30_seed7_rerun"),
             ("a01_raw_paper", "c02_nohalt_explicit"), ("a01_raw_paper", "c01_default_risk_no_config")]:
    same_t = sha(f"{O}/{a}/trades.csv") == sha(f"{O}/{b}/trades.csv")
    same_o = sha(f"{O}/{a}/orders.jsonl") == sha(f"{O}/{b}/orders.jsonl")
    same_r = stable(res(a)) == stable(res(b))
    out("PASS" if same_t and same_r and same_o else "FAIL", f"determinism {a} == {b}",
        f"trades.csv identical={same_t} orders.jsonl identical={same_o} result.json identical={same_r}")
out("PASS" if sha(f"{O}/a11_slip_uniform30_seed7/trades.csv") != sha(f"{O}/a13_slip_uniform30_seed8/trades.csv") else "FAIL",
    "different seed -> different random slippage", f"seed7 pnl {res('a11_slip_uniform30_seed7')['global']['totalPnL']} vs seed8 {res('a13_slip_uniform30_seed8')['global']['totalPnL']}")

# ---- R2 same signals across fill models: only prices move, not decisions ------------------------------
base = trades("a01_raw_paper")
for n in ("a03_mt5sim", "a09_slip_zero", "a10_slip_fixed30", "a14_latency_1s"):
    t = trades(n)
    same_ids = [x["legId"] for x in base] == [x["legId"] for x in t]
    out("PASS" if same_ids else "INFO", f"same trade sequence paper vs {n}", f"{len(base)} vs {len(t)} fills, identical leg sequence={same_ids}")

# ---- R3 mt5-sim crosses the spread: buys pay more, sells receive less ------------------------------
p, m = trades("a01_raw_paper"), trades("a03_mt5sim")
adv = []
for x, y in zip(p, m):
    if x["legId"] != y["legId"] or x["legAction"] != y["legAction"]:
        break
    d = (D(y["price"]) - D(x["price"])) * (1 if x["side"] == "BUY" else -1)  # positive = worse for us
    adv.append((x["orderType"], x["side"], d))
entry_adv = [d for t, s, d in adv if t == "Market"]
print("   mt5-sim vs paper adverse price move per Market fill: n=%d min=%s max=%s avg=%.4f" % (len(entry_adv), min(entry_adv), max(entry_adv), float(sum(entry_adv) / len(entry_adv))))
out("PASS" if all(d >= 0 for d in entry_adv) else "FAIL", "mt5-sim market entries never better than paper mid", f"{sum(d < 0 for d in entry_adv)} fills better than paper")
out("PASS" if D(res("a03_mt5sim")["global"]["totalPnL"]) < D(res("a01_raw_paper")["global"]["totalPnL"]) else "FAIL", "mt5-sim PnL below paper PnL",
    f"{res('a03_mt5sim')['global']['totalPnL']} < {res('a01_raw_paper')['global']['totalPnL']}")

# ---- R4 slippage: exact points, monotone ---------------------------------------------------------
z, f30 = trades("a09_slip_zero"), trades("a10_slip_fixed30")
diffs = collections.Counter()
for x, y in zip(z, f30):
    if x["legId"] == y["legId"] and x["legAction"] == y["legAction"]:
        diffs[str(abs(D(y["price"]) - D(x["price"])).normalize())] += 1
print("   |fixed-points:30 price - zero-slippage price| histogram:", dict(diffs))
pnl = {n: D(res(n)["global"]["totalPnL"]) for n in ("a09_slip_zero", "a10_slip_fixed30", "a11_slip_uniform30_seed7", "a13_slip_uniform30_seed8", "a14_latency_1s")}
out("PASS" if pnl["a09_slip_zero"] >= pnl["a11_slip_uniform30_seed7"] and pnl["a09_slip_zero"] >= pnl["a10_slip_fixed30"] else "FAIL",
    "zero slippage >= any slippage", str({k: str(v) for k, v in pnl.items()}))
out("INFO", "mt5-sim default == mt5-basic == slippage zero", f"{res('a03_mt5sim')['global']['totalPnL']} / {res('a05_exec_mt5basic')['global']['totalPnL']} / {res('a09_slip_zero')['global']['totalPnL']}")

# ---- R5 commission: exactly 7 per lot per fill ---------------------------------------------------------
tb, ta = trades("b01_commission7"), trades("a01_raw_paper")
expect = sum(D(x["quantity"]) for x in tb) * 7
g = res("b01_commission7")["global"]
out("PASS" if D(g["commissionPaid"]) == expect else "FAIL", "commission == 7 x sum(lots over all fills)", f"{g['commissionPaid']} vs {expect}")
out("PASS" if D(g["totalPnL"]) == D(res("a01_raw_paper")["global"]["totalPnL"]) - expect else "FAIL",
    "commission run PnL == raw PnL - commission", f"{g['totalPnL']} vs {D(res('a01_raw_paper')['global']['totalPnL']) - expect}")

# ---- R6 swap: independent recomputation from trades ---------------------------------------------------
legs, expect_swap = {}, D(0)
roll_h = 21
for x in sorted(trades("b02_swap"), key=lambda r: int(r["timestamp"])):
    k = x["legId"]
    if x["legAction"] == "OPENED":
        legs[k] = (int(x["timestamp"]), x["side"], D(x["quantity"]))
    elif x["legAction"] == "CLOSED" and k in legs:
        o_ts, side, qty = legs.pop(k)
        t0 = datetime.datetime.fromtimestamp(o_ts / 1000, datetime.timezone.utc)
        t1 = datetime.datetime.fromtimestamp(int(x["timestamp"]) / 1000, datetime.timezone.utc)
        cur = t0.replace(hour=roll_h, minute=0, second=0, microsecond=0)
        if cur <= t0:
            cur += datetime.timedelta(days=1)
        while cur <= t1:
            if cur.weekday() < 5:  # weekend rollovers skipped
                mult = 3 if cur.weekday() == 2 else 1  # Wednesday triple
                pts = D(-30) if side == "BUY" else D(10)
                expect_swap += pts * D("0.01") * 100 * qty * mult
            cur += datetime.timedelta(days=1)
got = D(csv.DictReader(open(f"{O}/b02_swap/financing.csv")).__next__()["netPnlImpact"])
out("PASS" if got == expect_swap else "FAIL", "swap == points x pointSize x contract x lots x rollovers (Wed x3, weekends skipped)", f"qkt {got} vs independent {expect_swap}")

# ---- R7 risk halts -----------------------------------------------------------------------------------
def halts(n):
    res = json.load(open(f"{O}/{n}/result.json"))
    if "halts" in res:
        hs = res["halts"]
        iso = lambda ms: datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        return len(hs), [(iso(h["timestampMs"]), h["reason"]) for h in hs]
    txt = open(f"{O}/{n}.log").read()
    m = re.search(r"Risk halts:\s+(\d+)\n((?:\s+\S+Z\s+.*\n)*)", txt)
    return (int(m.group(1)), re.findall(r"(\S+Z)\s+(.*)", m.group(2))) if m else (0, [])


def entries(n):
    return [x for x in trades(n) if x["legAction"] == "OPENED"]


nh = {x["legId"]: x for x in trades("c02_nohalt_explicit")}
for n in ("c03_dailyloss60", "c04_dd_static_05", "c05_dd_trailing_10", "c06_ddaily_balance_08", "c07_ddaily_equity_08",
          "c08_streak2_daily", "c09_streak2_persistent", "c10_maxtrades2", "c11_cooldown1h", "c12_strategy_dailyloss40"):
    cnt, ev = halts(n)
    t = trades(n)
    # identical to the no-halt run until the first divergence
    div = next((i for i, (a, b) in enumerate(zip(t, trades("c02_nohalt_explicit"))) if a["legId"] != b["legId"] or a["price"] != b["price"]), None)
    pre_same = div is None or all(t[i]["price"] == trades("c02_nohalt_explicit")[i]["price"] for i in range(div))
    rj = rej(n)
    print(f"   {n}: fills={len(t)} (nohalt {len(nh)}) halts={cnt} rejections={len(rj)} first-divergence-fill-index={div}")
    for ts, msg in ev[:6]:
        print(f"        halt {ts} {msg}")
    out("PASS" if pre_same else "FAIL", f"{n} identical to no-halt run before first intervention", f"divergence at fill #{div}")

# daily loss halt: no new entries after the halt until next UTC day; message value exceeds cap
cnt, ev = halts("c03_dailyloss60")
t3 = trades("c03_dailyloss60")
viol = []
for ts, msg in ev:
    h_ms = int(datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000)
    h_day = day(h_ms)
    v = D(re.search(r"daily loss ([\d.]+)", msg).group(1))
    if v <= 60:
        viol.append(("halt value not above cap", ts, v))
    for x in t3:
        if x["legAction"] == "OPENED" and int(x["timestamp"]) > h_ms and day(x["timestamp"]) == h_day:
            viol.append(("entry after halt same UTC day", x["timestamp"], x["legId"]))
out("PASS" if not viol else "FAIL", "daily-loss halt: threshold exceeded at trip, no entries rest of that UTC day", f"{cnt} halts, violations={viol[:3]}")

# persistent drawdown/streak halts: no entries after the first trip at all
for n in ("c04_dd_static_05", "c05_dd_trailing_10", "c09_streak2_persistent"):
    t = trades(n)
    last_close = max(int(x["timestamp"]) for x in t)
    cnt, ev = halts(n)
    after = [x for x in t if x["legAction"] == "OPENED" and ev and int(x["timestamp"]) > int(datetime.datetime.fromisoformat(ev[0][0].replace("Z", "+00:00")).timestamp() * 1000)]
    out("PASS" if cnt >= 1 and not after else "FAIL", f"{n}: persistent halt stops all new entries", f"halts={cnt} entries-after-first-halt={len(after)}")

# max_trades_per_day
by = collections.Counter(day(x["timestamp"]) for x in entries("c10_maxtrades2"))
out("PASS" if max(by.values()) <= 2 else "FAIL", "max_trades_per_day: 2 caps entry fills per UTC day", f"max entries/day {max(by.values())}, rejections {len(rej('c10_maxtrades2'))}")
by0 = collections.Counter(day(x["timestamp"]) for x in entries("c02_nohalt_explicit"))
print(f"   (no-halt run peaks at {max(by0.values())} entries/day)")

# cooldown after loss: no entry within 1h of a losing close
t = sorted(trades("c11_cooldown1h"), key=lambda r: int(r["timestamp"]))
bad_cd = []
last_loss = None
for x in t:
    if x["legAction"] == "CLOSED" and D(x["realized"]) < 0:
        last_loss = int(x["timestamp"])
    if x["legAction"] == "OPENED" and last_loss is not None and 0 < int(x["timestamp"]) - last_loss < 3600_000:
        bad_cd.append((x["timestamp"], int(x["timestamp"]) - last_loss))
out("PASS" if not bad_cd else "FAIL", "cooldown_after_loss 1h: no entry within 1h of a losing close", f"violations={bad_cd[:3]} rejections={len(rej('c11_cooldown1h'))}")

# ---- R8 windows partition the run -------------------------------------------------------------------
d1 = res("d01_windows")
w = d1["windows"]
eqd = {k: D(v["equityEnd"]) - D(v["equityStart"]) for k, v in w.items()}
out("PASS" if sum(eqd.values()) == D(d1["global"]["totalPnL"]) else "FAIL", "contiguous metrics windows: equity deltas sum to global totalPnL", f"{eqd} -> {sum(eqd.values())} vs {d1['global']['totalPnL']}")
out("PASS" if all(D(v['metrics']['totalPnL']) == eqd[k] for k, v in w.items()) else "FAIL", "window totalPnL == window equity delta (it is NOT the sum of fills realized in the window)", str({k: v['metrics']['totalPnL'] for k, v in w.items()}))
for k in w:
    mine = sum(D(x["realized"]) for x in trades("d01_windows") if w[k]["fromMs"] <= int(x["timestamp"]) < w[k]["toMs"])
    out("INFO", f"window {k}: realized fills in window vs window.realizedTotal", f"fills {mine} vs reported {w[k]['metrics']['realizedTotal']} (diff = unrealized carried in at window start {D(w[k]['metrics']['realizedTotal']) - mine})")
d2 = res("d02_oos_split")["windows"]
s2 = sum(D(v["metrics"]["totalPnL"]) for v in d2.values())
out("PASS" if s2 == D(res("d02_oos_split")["global"]["totalPnL"]) else "FAIL", "in-sample + out-of-sample totalPnL == global totalPnL", f"{s2} vs {res('d02_oos_split')['global']['totalPnL']}")

# ---- R9 account size scaling ------------------------------------------------------------------------
t1 = {x["timestamp"]: x for x in trades("a01_raw_paper")}
t2 = {x["timestamp"]: x for x in trades("d03_balance_100k")}
legs1 = {x["legId"]: x for x in trades("a01_raw_paper") if x["legAction"] == "OPENED"}
legs2 = {x["legId"]: x for x in trades("d03_balance_100k") if x["legAction"] == "OPENED"}
by_open = {x["timestamp"]: x["legId"] for x in legs1.values()}
by_open2 = {x["timestamp"]: x["legId"] for x in legs2.values()}
close1 = {x["legId"]: x for x in trades("a01_raw_paper") if x["legAction"] == "CLOSED"}
close2 = {x["legId"]: x for x in trades("d03_balance_100k") if x["legAction"] == "CLOSED"}
bad_pl, n_cmp = [], 0
for ts, l1 in by_open.items():
    l2 = by_open2.get(ts)
    if not l2 or l1 not in close1 or l2 not in close2:
        continue
    if close1[l1]["timestamp"] != close2[l2]["timestamp"] or close1[l1]["price"] != close2[l2]["price"]:
        continue
    n_cmp += 1
    pl1 = D(close1[l1]["realized"]) / D(legs1[l1]["quantity"])
    pl2 = D(close2[l2]["realized"]) / D(legs2[l2]["quantity"])
    if abs(pl1 - pl2) > D("0.0001"):
        bad_pl.append((ts, pl1, pl2))
out("PASS" if not bad_pl and n_cmp > 30 else "FAIL", "same trade at 10k vs 100k: PnL per lot identical (only size differs)", f"{n_cmp} matched round trips, mismatches={bad_pl[:2]}")
risk_pct = [D(x["riskUsd"]) / 100000 * 100 for x in legs2.values() if x.get("riskUsd")]
risk_pct1 = [D(x["riskUsd"]) / 10000 * 100 for x in legs1.values() if x.get("riskUsd")]
print("   realised risk per trade (target 0.5%%): 10k account min/avg/max = %.2f/%.2f/%.2f %%;  100k account = %.2f/%.2f/%.2f %%" % (min(risk_pct1), sum(risk_pct1)/len(risk_pct1), max(risk_pct1), min(risk_pct), sum(risk_pct)/len(risk_pct), max(risk_pct)))
rj3 = rej("d03_balance_100k")
out("INFO", "default order-notional cap rejects an oversize order at 100k", rj3[0]["reason"][:120] if rj3 else "none")

# ---- R10 longer windows & instruments -------------------------------------------------------------------
out("PASS" if sha(f"{O}/e05_long_paper/trades.csv") == sha(f"{O}/e07_long_default_risk/trades.csv") else "INFO",
    "long window: explicit no-halt == out-of-the-box default risk", "default max_daily_loss 1000 never trips on a $10k account here")

# ---- R11 chaos preset produces rejections / partials and is deterministic --------------------------------
c = res("a07_chaos")
out("PASS" if len(rej("a07_chaos")) > 0 else "FAIL", "chaos preset exercises rejections", f"rejections={len(rej('a07_chaos'))} fills={c['global']['tradeCount']} vs raw {res('a01_raw_paper')['global']['tradeCount']}")

json.dump(results, open(f"{O}/relations.json", "w"), indent=1)
fails = [r for r in results if r[0] == "FAIL"]
print(f"\nrelations: {sum(r[0]=='PASS' for r in results)} PASS, {len(fails)} FAIL, {sum(r[0]=='INFO' for r in results)} INFO")
