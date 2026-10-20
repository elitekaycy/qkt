# From any language

A session is the qkt engine behind a small HTTP API on `127.0.0.1`. Anything that can send HTTP drives it the same
way `qkt bot` does: a service in Go or Rust, a notebook, a spreadsheet macro, or `curl`. The contract is the
[session API reference](../reference/bot-session-api.md); this page walks through it with `curl`.

## Find the session

`session start` writes a descriptor, readable only by you, with the port and a bearer token:

```bash
qkt bot session start --backtest --symbols BINANCE:BTCUSDT --tf 15m --from 2026-09-01 --to 2026-09-08 \
  --identities http --run web --out runs/web --json > session.log 2>&1 &
qkt bot session wait --run web --json
D=~/.local/state/qkt/state/bot/sessions/web/session.json
PORT=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['port'])" "$D")
TOKEN=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['token'])" "$D")
```

(`~/.local/state/qkt` is the default state directory; `--state-dir` or `QKT_STATE_DIR` moves it.) Every request
carries the token; without it the session answers `401`.

## Read, decide, act

```bash
curl -s -H "Authorization: Bearer $TOKEN" -X POST "http://127.0.0.1:$PORT/next" -d '{"symbol":"BINANCE:BTCUSDT"}'
curl -s -H "Authorization: Bearer $TOKEN" -X POST "http://127.0.0.1:$PORT/intent" -d '{
  "identity": "http",
  "source": "STRATEGY bot VERSION 1\nSYMBOLS\n    x = BINANCE:BTCUSDT EVERY 1m\nRULES\n    WHEN true\n    THEN BUY x SIZING 0.1 BRACKET { STOP_LOSS BY 1 PCT, TAKE_PROFIT BY 2 PCT }"
}'
curl -s -H "Authorization: Bearer $TOKEN" -X POST "http://127.0.0.1:$PORT/next" -d '{"symbol":"BINANCE:BTCUSDT"}'
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:$PORT/intents"
curl -s -H "Authorization: Bearer $TOKEN" -X POST "http://127.0.0.1:$PORT/finish" -d '{"flatten":true}'
```

- `POST /next` is the clock: the next closed bar, and every fill, rejection or exit since the last call.
- `POST /intent` queues one action, written in qkt's strategy language. That is the point: whatever language your
  code is in, the order it sends is one a person can read, and the engine prices, sizes, risk-checks and journals
  it exactly as it would a `.qkt` strategy's.
- `GET /intents` says what became of each order: `filled`, `rejected` with the rule, `skipped` with the reason.
- `POST /finish` ends the session and writes the report; `qkt bot session replay runs/web` then reruns it.

## A client in your language

[`examples/bot/python/qkt_session.py`](https://github.com/elitekaycy/qkt/blob/main/examples/bot/python/qkt_session.py)
is the whole API in about 120 lines of standard-library Python: find the descriptor, send the token, one method
per route. A client in another language is the same handful of calls.
