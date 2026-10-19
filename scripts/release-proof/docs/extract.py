"""The files a docs example page shows, by fence title: ```qkt title="strategies/x.qkt"``` and the like."""

import re
from pathlib import Path

FENCE = re.compile(r'^```(\w+)\s+title="([^"]+)"\s*$')


def files_on(page: Path) -> dict[str, str]:
    out, lines, i = {}, page.read_text().splitlines(), 0
    while i < len(lines):
        m = FENCE.match(lines[i].strip())
        if m:
            body = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                body.append(lines[i])
                i += 1
            indent = min((len(x) - len(x.lstrip()) for x in body if x.strip()), default=0)
            out[m.group(2)] = "\n".join(x[indent:] for x in body) + "\n"
        i += 1
    return out
