#!/usr/bin/env python3
"""Validate the attestation case tree and enforce the coverage gate.

    validate.py [--root attestation] [--catalog src/test/resources/validation/oracle-evidence.json]
                [--cli PATH]   # also parse every strategy.qkt with this qkt binary
                [--write-gaps] # rewrite gaps.yaml from what ready cases do not prove yet

Fails when a case breaks the schema, an id is reused, a budget exceeds ten minutes, a `proves`
capability is not in the catalog, a catalog capability is neither proven by a ready case nor
listed in gaps.yaml, or a listed gap is already proven.
"""
import argparse, json, os, re, subprocess, sys
import yaml

REQUIRED = ("id", "title", "why", "status", "symbols", "timeframes", "proves", "assertions")
STATUSES = {"ready", "planned"}
TIMEFRAME = re.compile(r"^\d+(s|m|h|d)$")
MAX_BUDGET = 600


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="attestation")
    ap.add_argument("--catalog", default="src/test/resources/validation/oracle-evidence.json")
    ap.add_argument("--cli")
    ap.add_argument("--write-gaps", action="store_true")
    args = ap.parse_args()

    lanes = yaml.safe_load(open(f"{args.root}/lanes.yaml"))["lanes"]
    catalog = json.load(open(args.catalog))
    capabilities = {c for group in catalog["categories"].values() for entry in group for c in entry["capabilities"]}
    errors, cases, seen = [], [], {}

    for lane in sorted(os.listdir(f"{args.root}/cases")):
        if lane not in lanes:
            errors.append(f"cases/{lane}: no such lane in lanes.yaml")
            continue
        for cid in sorted(os.listdir(f"{args.root}/cases/{lane}")):
            where = f"cases/{lane}/{cid}"
            path = f"{args.root}/{where}/case.yaml"
            if not os.path.isfile(path):
                errors.append(f"{where}: case.yaml is missing")
                continue
            doc = yaml.safe_load(open(path)) or {}
            for field in REQUIRED:
                if field not in doc:
                    errors.append(f"{where}: missing field '{field}'")
            if doc.get("id") != cid:
                errors.append(f"{where}: id '{doc.get('id')}' must equal the directory name")
            if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", cid):
                errors.append(f"{where}: id must be lowercase a-z0-9-")
            if cid in seen:
                errors.append(f"{where}: id already used by {seen[cid]}")
            seen[cid] = where
            if doc.get("status") not in STATUSES:
                errors.append(f"{where}: status must be one of {sorted(STATUSES)}")
            if len(str(doc.get("why", "")).split()) < 12:
                errors.append(f"{where}: 'why' must name the failure this case catches, in a sentence")
            for tf in doc.get("timeframes", []):
                if not TIMEFRAME.match(str(tf)):
                    errors.append(f"{where}: bad timeframe '{tf}'")
            for tf in (doc.get("warmup_bars") or {}):
                if tf not in doc.get("timeframes", []):
                    errors.append(f"{where}: warmup_bars names {tf}, which is not in timeframes")
            budget = doc.get("budget_seconds", lanes[lane]["budget_seconds"])
            if not isinstance(budget, int) or not 0 < budget <= MAX_BUDGET:
                errors.append(f"{where}: budget_seconds must be 1..{MAX_BUDGET}")
            for proof in doc.get("proves", []):
                if not str(proof).startswith("behaviour:") and proof not in capabilities:
                    errors.append(f"{where}: proves '{proof}', which is not a catalog capability")
            for number, step in enumerate(doc.get("steps") or [], 1):
                for field in ("expect_stdout", "expect_log"):
                    if field in step:
                        try:
                            re.compile(str(step[field]))
                        except re.error as error:
                            errors.append(f"{where}: step {number} {field} is not a valid regex: {error}")
            if doc.get("expect_startup_refusal"):
                try:
                    re.compile(str(doc["expect_startup_refusal"]))
                except re.error as error:
                    errors.append(f"{where}: expect_startup_refusal is not a valid regex: {error}")
            strategy = f"{args.root}/{where}/strategy.qkt"
            if doc.get("status") == "ready" and lane in ("shadow", "orders", "risk", "book") and not os.path.isfile(strategy):
                errors.append(f"{where}: a ready {lane} case needs strategy.qkt")
            if args.cli and os.path.isfile(strategy):
                run = subprocess.run([args.cli, "parse", strategy], capture_output=True, text=True)
                if run.returncode != 0:
                    errors.append(f"{where}: strategy.qkt does not parse: {(run.stderr or run.stdout).strip().splitlines()[-1]}")
            cases.append((lane, doc))

    proven = {p for _, d in cases if d.get("status") == "ready" for p in d.get("proves", []) if p in capabilities}
    gaps_path = f"{args.root}/gaps.yaml"
    if args.write_gaps:
        missing = sorted(capabilities - proven)
        yaml.safe_dump({"gaps": [{"capability": c, "reason": "no ready shadow case exercises it yet"} for c in missing]},
                       open(gaps_path, "w"), sort_keys=False)
    gaps = {g["capability"]: g.get("reason", "") for g in (yaml.safe_load(open(gaps_path)) or {}).get("gaps", [])} \
        if os.path.isfile(gaps_path) else {}
    for capability in sorted(capabilities - proven - set(gaps)):
        errors.append(f"coverage: {capability} is neither proven by a ready case nor listed in gaps.yaml")
    for capability in sorted(set(gaps) & proven):
        errors.append(f"coverage: {capability} is listed as a gap but a ready case proves it; remove the gap")
    for capability, reason in gaps.items():
        if capability not in capabilities:
            errors.append(f"gaps.yaml: {capability} is not a catalog capability")
        if not reason.strip():
            errors.append(f"gaps.yaml: {capability} has no reason")

    by_lane = {}
    for lane, doc in cases:
        counts = by_lane.setdefault(lane, {"ready": 0, "planned": 0})
        if doc.get("status") in counts:
            counts[doc["status"]] += 1
    for lane in lanes:
        counts = by_lane.get(lane, {"ready": 0, "planned": 0})
        print(f"{lane:8} ready={counts['ready']:<3} planned={counts['planned']}")
    print(f"capabilities proven {len(proven)}/{len(capabilities)}, declared gaps {len(gaps)}")
    for error in errors:
        print(f"ERROR {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
