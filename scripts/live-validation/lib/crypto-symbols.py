#!/usr/bin/env python3
"""Move .qkt strategies to 24/7 crypto for an attestation while FX and metals are closed.

Each FX or metals symbol a file names (EXNESS:EURUSD, EXNESS:XAUUSD, ...), in order of first use, becomes the
next 24/7 symbol that file does not already trade: EXNESS:EURUSD + EXNESS:XAUUSD -> EXNESS:BTCUSD +
EXNESS:ETHUSD, and a third becomes EXNESS:XRPUSD. Strategies keep their logic; only the market they observe changes. Edits the files in place.
"""
import re
import sys

# Each streams ticks on the Exness demo at the weekend; a lane that only observes needs nothing more.
CRYPTO = ["BTCUSD", "ETHUSD", "XRPUSD"]


def remap(path):
    text = open(path).read()
    seen = []
    for sym in re.findall(r"EXNESS:([A-Z]{6})\b", text):
        if sym not in seen:
            seen.append(sym)
    free = [c for c in CRYPTO if c not in seen]
    for sym in seen:
        if sym in CRYPTO:
            continue
        if not free:
            sys.exit(f"{path}: more FX and metals symbols than 24/7 replacements")
        text = re.sub(rf"EXNESS:{sym}\b", f"EXNESS:{free.pop(0)}", text)
    open(path, "w").write(text)


for arg in sys.argv[1:]:
    remap(arg)
