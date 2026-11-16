"""Writes `scenarios/<name>.json` and `scenarios/<name>.qkt` from the compact definitions in `cases.py`.

    python3 -I author.py

Every scenario is a hand-authored tape of 1-minute bars with four ticks each (at :00, :15, :30, :45), quoted as
mid prices on a 0.01 grid with a fixed two-sided spread, plus the declarative actions the oracle replays and the
`.qkt` that makes qkt do the same thing. A rule fires on the bar whose close equals its trigger price, so every
trigger price appears exactly once as a bar close in its tape.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # -I drops the script directory

import json
from decimal import Decimal

from cases import CASES, Case

HERE = Path(__file__).resolve().parent
SCENARIOS = HERE / "scenarios"

QKT_SYMBOL = "BACKTEST:SYN"


def _dec(v) -> str:
    return str(Decimal(str(v)))


def ticks_of(case: Case) -> list:
    half = Decimal(str(case.spread)) / 2
    out = []
    for i, bar in enumerate(case.bars):
        assert len(bar) == 4, f"{case.name}: bar {i} needs four mids"
        for j, mid in enumerate(bar):
            m = Decimal(str(mid))
            out.append([i * 60 + j * 15, _dec(m - half), _dec(m + half)])
    return out


def sizing_qkt(s: dict) -> str:
    form, v = s["form"], s["value"]
    return {
        "fixed": f"{v}",
        "usd": f"{v} USD",
        "pctEquity": f"{v} PCT OF EQUITY",
        "pctBalance": f"{v} PCT OF BALANCE",
        "pctRisk": f"{v} PCT RISK",
        "riskUsd": f"RISK $ {v}",
    }[form]


def level_qkt(leg: dict) -> str:
    if "by" in leg:
        return f"BY {leg['by']}"
    if "pct" in leg:
        return f"BY {leg['pct']} PCT"
    return f"AT {leg['at']}"


def order_type_qkt(ot: dict | None) -> str:
    if ot is None:
        return ""
    if ot["type"] == "LIMIT":
        return f" ORDER_TYPE = LIMIT AT {ot['price']}"
    if ot["type"] == "STOP":
        return f" ORDER_TYPE = STOP AT {ot['price']}"
    return f" ORDER_TYPE = STOP AT {ot['stop']} LIMIT AT {ot['limit']}"


def exit_qkt(ex: dict | None) -> str:
    if ex is None:
        return ""
    if ex["kind"] == "oco":
        return f" OCO {{ STOP AT {ex['stop']}, LIMIT AT {ex['limit']} }}"
    return f" BRACKET {{ STOP_LOSS {level_qkt(ex['stop'])}, TAKE_PROFIT {level_qkt(ex['target'])} }}"


def rule_qkt(action: dict) -> str:
    when, do = action["when"], action["do"]
    guard = {"flat": " AND POSITION.syn = 0", "long": " AND POSITION.syn > 0", "short": " AND POSITION.syn < 0", None: ""}
    cond = f"WHEN syn.close = {when['barClose']}{guard[when.get('position')]}"
    if do["kind"] == "entry":
        then = f"{do['side']} syn SIZING {sizing_qkt(do['sizing'])}{order_type_qkt(do.get('orderType'))}{exit_qkt(do.get('exit'))}"
    elif do["kind"] == "close":
        then = "CLOSE syn"
    else:
        then = f"{do['side']} syn SIZING {do['fraction']} * ABS(POSITION.syn)"
    return f"    {cond}\n    THEN {then}"


def qkt_of(case: Case) -> str:
    rules = "\n".join(rule_qkt(a) for a in case.actions)
    return (
        f"STRATEGY {case.name.replace('-', '_')} VERSION 1\n"
        f"-- {case.proves}\n"
        f"SYMBOLS\n    syn = {QKT_SYMBOL} EVERY 1m\nRULES\n{rules}\n"
    )


def scenario_of(case: Case) -> dict:
    closes = [Decimal(str(bar[-1])) for bar in case.bars]
    for a in case.actions:
        trigger = Decimal(str(a["when"]["barClose"]))
        assert closes.count(trigger) == 1, f"{case.name}: trigger {trigger} must be exactly one bar close, got {closes}"
    return {
        "name": case.name,
        "proves": case.proves,
        "dimensions": case.dimensions,
        "t0": "2024-01-10T10:00:00Z",
        "timeframeSec": 60,
        "symbol": "SYN",
        "instrument": case.instrument,
        "broker": case.broker,
        "fillMode": case.fill_mode,
        "positionMode": case.position_mode,
        "startingBalance": "10000",
        "ticks": ticks_of(case),
        "actions": case.actions,
        "notes": case.notes,
    }


def main() -> None:
    SCENARIOS.mkdir(exist_ok=True)
    names = [c.name for c in CASES]
    assert len(names) == len(set(names)), "duplicate scenario names"
    for case in CASES:
        (SCENARIOS / f"{case.name}.json").write_text(json.dumps(scenario_of(case), indent=2) + "\n")
        (SCENARIOS / f"{case.name}.qkt").write_text(qkt_of(case))
    print(f"wrote {len(CASES)} scenarios")


if __name__ == "__main__":
    main()
