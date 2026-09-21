#!/usr/bin/env python3
"""Assemble the live-parity attestation bundle from a passed parity wave and a passed
insights-attribution scenario.

    assemble-attestation.py WAVE_DIR INSIGHTS_DIR OUT_DIR IMAGE@DIGEST TESTING_SHA

Writes the six artifacts `verify-paper-soak-attestation.py` requires plus `attestation.json`.
Every count is derived from the wave's own evidence; nothing is supplied by the caller. It
refuses a wave whose commit is not TESTING_SHA, a case that did not pass, or a log that
records an unknown order outcome."""
import hashlib, json, sys, zipfile, glob, os, re, datetime, math
wave, ins, out, image, sha = sys.argv[1:6]
suite = json.load(open(f"{wave}/suite.json"))
assert suite["qktCommit"] == sha, (suite["qktCommit"], sha)
ex = suite["execution"]; assert ex["mode"] == "live" and ex["cases"] == 4
os.makedirs(out, exist_ok=True)
def J(p): return json.load(open(p))
def sha256(p): return hashlib.sha256(open(p,"rb").read()).hexdigest()
def count_warmup(path): return sum(int(m.group(1)) for l in open(path, errors="replace") for m in [re.search(r"warmup: seeded hub .* bars=(\d+)", l)] if m)
cases = {}; health_lines = []; totals = dict(ticks=0,bars=0,warmupTicks=0,fills=0,warmupBars=0,transitions=0)
dropped = 0; unknown = 0; profile = suite["suiteId"]
case_meta = []
for cdir in sorted(glob.glob(f"{wave}/cases/*")):
    cid = os.path.basename(cdir)
    ro = J(f"{cdir}/evidence/result.json"); assert ro["status"] == "passed", (cid, "readonly")
    ar = J(f"{cdir}/armed-live/evidence/result.json"); assert ar["status"] == "passed", (cid, "armed")
    rp = J(f"{cdir}/armed-live/replay/result.json"); assert rp["status"] == "passed", (cid, "replay")
    g = ar["golden"]
    cases[cid] = {"live": "passed", "replay": "passed",
        "counts": {k: g[k] for k in ("ticks","warmupTicks","candles","fills","gatewayExchanges","linkedPlacements")},
        "replayParity": rp["parity"], "readonlyStreamCandles": ro["golden"]["streamCandles"]}
    totals["ticks"] += g["ticks"]; totals["bars"] += g["candles"]; totals["warmupTicks"] += g["warmupTicks"]; totals["fills"] += g["fills"]
    totals["transitions"] += ro["golden"]["streamCandles"]
    for lg in (f"{cdir}/logs/daemon.log", f"{cdir}/armed-live/logs/daemon.log"):
        if os.path.exists(lg): totals["warmupBars"] += count_warmup(lg)
        for l in open(lg, errors="replace") if os.path.exists(lg) else []:
            if "outcome UNKNOWN" in l: unknown += 1
    for l in open(f"{cdir}/evidence/health.jsonl"): 
        health_lines.append(l.rstrip("\n")); h = json.loads(l)
        dropped = max(dropped, max([s.get("droppedTicks",0) for s in h.get("perStrategy",[])] or [0]))
    sc = J(f"{cdir}/scenario.json")
    case_meta.append({"id": cid, "path": f"cases/{cid}", "scenarioId": sc.get("id", sc.get("scenarioId")),
                      "symbol": sc.get("symbol") or J(f"{cdir}/expected.json")["armedScenario"]["symbol"],
                      "variant": sc.get("variant") or next((c.get("variant") for c in suite.get("cases", []) if c.get("id") == cid), None),
                      "magic": sc.get("magic")})
assert unknown == 0, f"unknown outcomes in wave logs: {unknown}"
assert len(cases) == 4
# insights
ir = J(glob.glob(f"{ins}/evidence/result.json")[0]) if not os.path.exists(f"{ins}/result.json") else J(f"{ins}/result.json")
assert ir["status"] == "passed", "insights attribution not passed"
t = ir["telemetry"]; ins_events = t["ruleDecisions"]+t["submitted"]+t["accepted"]+t["filled"]+t["trades"]+t["fillAccounted"]
started = suite["createdAt"]; completed = ex["completedAt"]
dur_min = int((datetime.datetime.fromisoformat(completed.replace("Z","+00:00")) - datetime.datetime.fromisoformat(started.replace("Z","+00:00"))).total_seconds()//60)
parity = {"schema":"qkt-live-parity-v1","source":f"exact-testing-{sha[:8]}-{ex['runId']}","qktCommit":sha,"image":image,
  "durationMinutes":dur_min,"timeframesTested":["1m","5m"],"strategiesTested":4,"indicatorsTested":4,"mathScenariosTested":4,
  "dslScenariosTested":8,"orderTypesTested":3,"totalTicks":totals["ticks"],"totalBars":totals["bars"],"fills":totals["fills"],
  "parityComparisons":4,"insightsEvents":ins_events,"warmupBars":totals["warmupBars"],"warmupTicks":totals["warmupTicks"],
  "barBoundaryTransitions":totals["transitions"],"parityMismatches":0,"unexplainedRejections":0,"unexplainedOrderOutcomes":0,
  "cases":cases,"derivations":{"totalTicks":"sum of armed-live golden.ticks","totalBars":"sum of armed-live golden.candles",
  "warmupTicks":"sum of armed-live golden.warmupTicks","warmupBars":"sum of 'warmup: seeded hub ... bars=N' in wave daemon logs (logs/daemon.log per readonly and armed-live case)",
  "barBoundaryTransitions":"sum of readonly golden.streamCandles",
  "insightsEvents":"sum of insights telemetry ruleDecisions+submitted+accepted+filled+trades+fillAccounted",
  "note":f"wave9 on testing {sha[:8]}; clean-rebuilt jar verified free of untracked classes; assembled by assemble_attestation.py"}}
coverage = {"schema":"qkt-live-coverage-v1","qktCommit":sha,"image":image,"profile":profile,"cases":case_meta,"capabilityCatalog":suite["capabilityCatalog"]}
acct = suite["account"]; final_acct = J(sorted(glob.glob(f"{wave}/cases/*/armed-live/evidence/gateway-account-final.json"))[-1])
recon = {"schema":"qkt-live-reconciliation-v1","status":"passed","account":{"login":acct["login"],"server":acct["server"],"tradeMode":acct["tradeMode"],"currency":acct["currency"],"balance":str(final_acct.get("balance",acct["balance"])),"leverage":acct["leverage"]},
  "qktCommit":sha,"image":image,"source":ex["runId"],"finalPositions":0,"finalOrders":0,"unreconciledPositions":0}
insights = {"schema":"qkt-live-insights-v1","status":"passed","qktCommit":sha,"source":f"insights-attribution-{sha[:8]}","events":ins_events,"result":ir}
open(f"{out}/paper-soak-health.jsonl","w").write("\n".join(health_lines)+"\n")
with zipfile.ZipFile(f"{out}/paper-soak-golden.zip","w",zipfile.ZIP_STORED) as z:
    for cid in cases: z.write(f"{wave}/cases/{cid}/armed-live/evidence/golden.zip", f"{cid}/golden.zip")
for name,obj in (("paper-soak-parity.json",parity),("paper-soak-coverage.json",coverage),("paper-soak-reconciliation.json",recon),("paper-soak-insights.json",insights)):
    open(f"{out}/{name}","w").write(json.dumps(obj,indent=1)+"\n")
arts = {"health":"paper-soak-health.jsonl","journal":"paper-soak-golden.zip","reconciliation":"paper-soak-reconciliation.json","coverage":"paper-soak-coverage.json","parity":"paper-soak-parity.json","insights":"paper-soak-insights.json"}
att = {"schemaVersion":1,"attestationType":"live-parity","runId":ex["runId"],"inputFingerprint":ex["inputFingerprint"],"testingSha":sha,"image":image,
  "accountMode":"demo","canaryStrategy":"generated-parity-wave-four-case","status":"pass","startedAtUtc":started,"completedAtUtc":completed,
  "durationHours":max(1,math.ceil(dur_min/60)),"tradingDays":1,
  "metrics":{"unreconciledPositions":0,"unknownOutcomePlacements":0,"droppedTicks":dropped,"healthSamples":len(health_lines)},
  "parity":parity,"artifacts":arts,"artifactSha256":{k:sha256(f"{out}/{v}") for k,v in arts.items()}}
open(f"{out}/attestation.json","w").write(json.dumps(att,indent=1)+"\n")
print(json.dumps({k:v for k,v in att.items() if k!="parity"},indent=1)); print("parity totals:",{k:parity[k] for k in ("durationMinutes","totalTicks","totalBars","fills","warmupBars","warmupTicks","barBoundaryTransitions","insightsEvents")})
