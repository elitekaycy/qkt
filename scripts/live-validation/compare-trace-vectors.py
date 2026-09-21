#!/usr/bin/env python3
"""Hold the values a strategy logged live against the values a replay of the same input logged.

    compare-trace-vectors.py --live LOG --replay NAME=LOG [--replay NAME=LOG ...]
        [--marker TEXT ...] [--catalog oracle-evidence.json] --out RESULT.json

A trace line is any strategy log line whose message carries `key=value` pairs, for example

    ... c.q.d.s.my_strategy - closed bar trace timeframe=1m ema=1.14844592 rsi=78.82314074

Lines are matched by strategy, message text and position in the run, never by wall-clock time,
because a replay logs at replay time. Values are compared as the exact text the engine printed:
a live indicator that differs from the backtest in the last printed digit is a mismatch. The
run fails when a replay has a different number of vectors, any value differs, or nothing was
compared. With --catalog, the capabilities the vectors exercised are reported by name.
"""
import argparse
import json
import re
import sys

LINE = re.compile(r" (?:c\.q\.d\.s\.|com\.qkt\.dsl\.strategy\.)(?P<strategy>\S+) - (?P<message>.*)$")
PAIR = re.compile(r"(?<!\S)([A-Za-z_][A-Za-z0-9_]*)=(\S*)")


def vectors(path, markers):
    found = []
    with open(path, errors="replace") as handle:
        for raw in handle:
            match = LINE.search(raw.rstrip("\n"))
            if not match:
                continue
            message = match.group("message")
            if markers and not any(marker in message for marker in markers):
                continue
            pairs = PAIR.findall(message)
            if not pairs:
                continue
            label = message[: PAIR.search(message).start()].strip()
            found.append({"strategy": match.group("strategy"), "label": label, "values": dict(pairs)})
    # Position within (strategy, label) is the join key: the n-th "closed bar trace" live is the
    # n-th in the replay.
    seen = {}
    for vector in found:
        key = (vector["strategy"], vector["label"])
        vector["ordinal"] = seen.get(key, 0)
        seen[key] = vector["ordinal"] + 1
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--live", required=True)
    parser.add_argument("--replay", action="append", required=True, metavar="NAME=LOG")
    parser.add_argument("--marker", action="append", default=[])
    parser.add_argument("--catalog")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    live = vectors(args.live, args.marker)
    live_index = {(v["strategy"], v["label"], v["ordinal"]): v for v in live}
    mismatches, modes = [], {}
    for spec in args.replay:
        name, _, path = spec.partition("=")
        if not path:
            parser.error(f"--replay expects NAME=LOG, got: {spec}")
        replay = vectors(path, args.marker)
        replay_index = {(v["strategy"], v["label"], v["ordinal"]): v for v in replay}
        compared = 0
        for key in sorted(set(live_index) | set(replay_index)):
            a, b = live_index.get(key), replay_index.get(key)
            where = {"mode": name, "strategy": key[0], "label": key[1], "ordinal": key[2]}
            if a is None or b is None:
                mismatches.append({**where, "problem": "missing in live" if a is None else "missing in replay"})
                continue
            for field in sorted(set(a["values"]) | set(b["values"])):
                compared += 1
                if a["values"].get(field) != b["values"].get(field):
                    mismatches.append({**where, "field": field, "live": a["values"].get(field),
                                       "replay": b["values"].get(field)})
        modes[name] = {"vectors": len(replay), "valuesCompared": compared}

    fields = sorted({field for v in live for field in v["values"]})
    result = {
        "schema": "qkt-trace-vector-parity-v1",
        "liveVectors": len(live),
        "strategies": sorted({v["strategy"] for v in live}),
        "fields": fields,
        "modes": modes,
        "mismatches": mismatches,
    }
    if args.catalog:
        catalog = json.load(open(args.catalog))
        names = {cap for group in catalog["categories"].values() for entry in group for cap in entry["capabilities"]}
        exercised = sorted(names & {field.upper() for field in fields})
        result["capabilitiesExercised"] = exercised
        result["capabilitiesNotExercised"] = sorted(names - set(exercised))
    nothing = not live or any(mode["valuesCompared"] == 0 for mode in modes.values())
    result["status"] = "failed" if mismatches or nothing else "passed"
    if nothing and not mismatches:
        result["mismatches"].append({"problem": "no trace values were compared"})
    with open(args.out, "w") as handle:
        json.dump(result, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(f"{result['status']} liveVectors={len(live)} " +
          " ".join(f"{n}={m['valuesCompared']}" for n, m in modes.items()) + f" mismatches={len(mismatches)}")
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
