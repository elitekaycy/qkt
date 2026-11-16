"""Regenerate `expected/<scenario>.json` from `scenarios/<scenario>.json` with the reference engine.

    python3 -I gen.py            # rewrite every expectation
    python3 -I gen.py --check    # exit 1 if any checked-in expectation differs from the engine's output
    python3 -I gen.py --only long-market-bracket-by-stop-ticks-paper

Standard library only (decimal, json). Nothing here imports or runs qkt.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # -I drops the script directory

import argparse
import json
from decimal import Decimal

import engine
from model import Scenario

HERE = Path(__file__).resolve().parent
SCENARIOS = HERE / "scenarios"
EXPECTED = HERE / "expected"


def expectation(scenario: Scenario) -> dict:
    st = engine.run(scenario)
    realized = sum((f.realized for f in st.tape), start=Decimal(0))
    fees = sum((engine.rules.commission(f.qty, scenario.instrument) for f in st.tape), start=Decimal(0))
    return {
        "name": scenario.name,
        "proves": scenario.proves,
        "oracle": "verification/oracle/engine (Decimal reference engine written from docs/concepts/backtest-model.md,"
        " docs/reference/dsl/{actions,bracket,sizing}.md)",
        "timestampGranularity": "tick",  # synthetic prints are timed too: backtest-model.md:97-101
        "trades": [f.to_json() for f in st.tape],
        "realized": str(realized),
        "commission": str(fees),
        "rejections": st.rejections,
        "openAtEnd": st.position is not None,
        "notes": scenario.notes,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--only")
    args = ap.parse_args()
    EXPECTED.mkdir(exist_ok=True)
    differing = []
    for path in sorted(SCENARIOS.glob("*.json")):
        if args.only and path.stem != args.only:
            continue
        scenario = Scenario.load(path)
        assert scenario.name == path.stem, f"{path}: name {scenario.name!r} must equal the file stem"
        text = json.dumps(expectation(scenario), indent=2) + "\n"
        target = EXPECTED / path.name
        if args.check:
            if not target.exists() or target.read_text() != text:
                differing.append(path.stem)
        else:
            target.write_text(text)
            print(f"{path.stem}: {len(json.loads(text)['trades'])} fills, realized {json.loads(text)['realized']}")
    if differing:
        print("expectations differ from the engine: " + ", ".join(differing), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
