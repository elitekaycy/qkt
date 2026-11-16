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

from cases import CASES as CASES_A
from cases import Case
from cases_b import CASES as CASES_B

CASES = CASES_A + CASES_B

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


def duration_qkt(sec: int) -> str:
    for unit, size in (("h", 3600), ("m", 60)):
        if sec % size == 0:
            return f"{sec // size}{unit}"
    return f"{sec}s"


def offset_qkt(off: str) -> str:
    return f"entry - {off[1:]}" if off.startswith("-") else f"entry + {off}"


def order_type_qkt(ot: dict | None) -> str:
    if ot is None:
        return ""
    if ot["type"] == "TRAILING":
        return f" ORDER_TYPE = TRAILING BY {ot['by']}" if "by" in ot else f" ORDER_TYPE = TRAILING PCT {ot['pct']}"
    if "entryOffset" in ot:
        return f" ORDER_TYPE = {ot['type']} AT {offset_qkt(ot['entryOffset'])}"
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
    if "trail" in ex["stop"]:
        stop = f"STOP LOSS TRAILING {ex['stop']['trail']} AFTER MFE >= {ex['stop']['afterMfe']}"
        return f" BRACKET {{ {stop}, TAKE PROFIT {level_qkt(ex['target'])} }}"
    return f" BRACKET {{ STOP_LOSS {level_qkt(ex['stop'])}, TAKE_PROFIT {level_qkt(ex['target'])} }}"


def stack_qkt(st: dict | None) -> str:
    if st is None or "layers" in st:
        return ""
    within = f" WITHIN {duration_qkt(st['withinSec'])}" if "withinSec" in st else ""
    return f" STACK {st['n']} SPACING {st['spacing']} {st['dir']}{within}"


def layers_qkt(st: dict) -> str:
    out = [st["layers"][0]["size"]]
    for layer in st["layers"][1:]:
        kind = "LIMIT AT" if layer.get("limit") else "AT"
        out.append(f"{layer['size']} {kind} {offset_qkt(layer['offset'])}")
    return "STACK [ " + ", ".join(out) + " ]"


def tiers_qkt(tiers: list) -> str:
    out = ""
    for t in tiers:
        cond = f"MFE >= {t['threshold']}" if t["kind"] == "mfe" else f"MAE >= {t['threshold']} RECOVER {t['recover']}"
        size = t["size"] if "size" in t else f"ENTRY_QTY * {t['entryQtyMul']}"
        bracket = f"BRACKET {{ STOP LOSS BY {t['stop']}, TAKE PROFIT BY {t['target']} }}"
        out += f"\n        STACK_AT {cond} WITHIN {duration_qkt(t['withinSec'])} SIZING {size} {bracket}"
    return out


def entry_qkt(do: dict) -> str:
    st = do.get("stack")
    if st is not None and "layers" in st:
        head = f"{do['side']} syn {layers_qkt(st)}"
    else:
        head = f"{do['side']} syn SIZING {sizing_qkt(do['sizing'])}"
    text = head + order_type_qkt(do.get("orderType")) + stack_qkt(st) + exit_qkt(do.get("exit"))
    text += tiers_qkt(do.get("stackAt", []))
    if "exitAfterSec" in do:
        text += f" EXIT AFTER {duration_qkt(do['exitAfterSec'])}"
    if do.get("onFill"):
        kids = " ; ".join(f"{c['side']} syn SIZING {c['size']}{order_type_qkt(c.get('orderType'))}" for c in do["onFill"])
        text += f" ON_FILL {{ {kids} }}"
    return text


def rule_qkt(action: dict) -> str:
    when, do = action["when"], action["do"]
    guard = {"flat": " AND POSITION.syn = 0", "long": " AND POSITION.syn > 0", "short": " AND POSITION.syn < 0", None: ""}
    cond = f"WHEN syn.close = {when['barClose']}{guard[when.get('position')]}"
    if do["kind"] == "entry":
        then = entry_qkt(do)
    elif do["kind"] == "ocoEntry":
        legs = ",\n        ".join(entry_qkt(leg) for leg in do["legs"])
        then = f"OCO_ENTRY {{\n        {legs}\n    }}"
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
