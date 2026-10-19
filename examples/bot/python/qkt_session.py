"""A dependency-free client for a running `qkt bot session` (Python 3.9+, standard library only).

A session is the qkt engine (a backtest over your data, or live through your configured accounts)
driven by your program over local HTTP. This module finds a session by its run id, authenticates with
the token in its descriptor, and wraps every route in docs/reference/bot-session-api.md.

    from qkt_session import Session
    s = Session.find(run="demo")
    bar = s.next("EXNESS:XAUUSD")
    s.buy("brain", "EXNESS:XAUUSD", 0.1, sl="by:5", tp="rr:2")
    print(s.intents())
    s.finish()
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


class SessionError(RuntimeError):
    """The session refused a request; the message is its reason."""


def _state_home() -> Path:
    if os.environ.get("QKT_STATE_DIR"):
        return Path(os.environ["QKT_STATE_DIR"])
    if os.environ.get("XDG_STATE_HOME"):
        return Path(os.environ["XDG_STATE_HOME"]) / "qkt"
    return Path.home() / ".local" / "state" / "qkt"


class Session:
    def __init__(self, url: str, token: str, run: str, mode: str):
        self.url, self.token, self.run, self.mode = url.rstrip("/"), token, run, mode

    @classmethod
    def find(cls, run: str | None = None, state_dir: str | Path | None = None) -> "Session":
        """The session `run` under `state_dir` (default: qkt's state home), or the only one running."""
        root = Path(state_dir) if state_dir else _state_home()
        sessions = root / "state" / "bot" / "sessions"
        found = [d for d in sessions.glob("*") if (d / "session.json").exists()] if sessions.exists() else []
        if run is not None:
            found = [d for d in found if d.name == run]
        if len(found) != 1:
            names = ", ".join(d.name for d in found) or "none"
            raise SessionError(f"need exactly one running session (found: {names}); pass run=")
        d = json.loads((found[0] / "session.json").read_text())
        return cls(f"http://127.0.0.1:{d['port']}", d["token"], d["runId"], d["mode"])

    def _call(self, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.url + path, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=1800) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            raise SessionError(json.loads(e.read().decode()).get("error", str(e))) from None

    # reads
    def status(self) -> dict[str, Any]:
        return self._call("GET", "/status")

    def next(self, symbol: str, tf: str | None = None) -> dict[str, Any]:
        """The next closed bar of the series and the events since the previous `next`; type "end" when none."""
        return self._call("POST", "/next", {"symbol": symbol, **({"tf": tf} if tf else {})})

    def bars(self, symbol: str, count: int = 100, tf: str | None = None) -> list[dict[str, Any]]:
        q = {"symbol": symbol, "count": str(count), **({"tf": tf} if tf else {})}
        return self._call("GET", "/bars?" + urllib.parse.urlencode(q))

    def quote(self, symbol: str) -> dict[str, Any]:
        return self._call("GET", "/quote?" + urllib.parse.urlencode({"symbol": symbol}))

    def positions(self) -> list[dict[str, Any]]:
        return self._call("GET", "/positions")

    def orders(self) -> list[dict[str, Any]]:
        return self._call("GET", "/orders")

    def intents(self) -> list[dict[str, Any]]:
        return self._call("GET", "/intents")

    # actions: every action is one DSL action on alias `x` (or the aliases you map in `symbols`)
    def action(self, identity: str, action: str, symbols: dict[str, str]) -> dict[str, Any]:
        streams = "\n".join(f"    {alias} = {sym} EVERY 1m" for alias, sym in symbols.items())
        source = f"STRATEGY bot VERSION 1\n\nSYMBOLS\n{streams}\n\nRULES\n    WHEN true\n    THEN {action}\n"
        return self._call("POST", "/intent", {"identity": identity, "source": source})

    def buy(self, identity: str, symbol: str, lots: float, sl: str | None = None, tp: str | None = None) -> dict[str, Any]:
        return self.action(identity, f"BUY x SIZING {lots}{_bracket(sl, tp)}", {"x": symbol})

    def sell(self, identity: str, symbol: str, lots: float, sl: str | None = None, tp: str | None = None) -> dict[str, Any]:
        return self.action(identity, f"SELL x SIZING {lots}{_bracket(sl, tp)}", {"x": symbol})

    def close(self, identity: str, symbol: str) -> dict[str, Any]:
        return self.action(identity, "CLOSE x", {"x": symbol})

    def cancel(self, identity: str, symbol: str) -> dict[str, Any]:
        return self.action(identity, "CANCEL x", {"x": symbol})

    def finish(self, flatten: bool = False) -> dict[str, Any]:
        return self._call("POST", "/finish", {"flatten": flatten})


def _exit(spec: str) -> str:
    kind, _, value = spec.partition(":")
    if not value:
        return f"AT {kind}"
    return {"at": "AT", "by": "BY", "pct": "PCT", "rr": "RR"}[kind] + f" {value}"


def _bracket(sl: str | None, tp: str | None) -> str:
    if sl is None and tp is None:
        return ""
    if sl is None or tp is None:
        raise SessionError("a bracket needs both sl and tp, as in a strategy")
    return f" BRACKET {{ STOP LOSS {_exit(sl)}, TAKE PROFIT {_exit(tp)} }}"
