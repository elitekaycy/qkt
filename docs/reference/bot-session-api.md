# Bot session HTTP API

A `qkt bot session` is the qkt engine driven by a program over local HTTP. `qkt bot` verbs are one client
of this API; a program in any language is another. This page is the contract. Responses carry
`"api": 2` where they describe the session; fields are only ever added.

A worked client in Python (standard library only) is in
[`examples/bot/python/`](https://github.com/elitekaycy/qkt/tree/main/examples/bot/python).

## Finding a session

`qkt bot session start` writes a descriptor and prints its path on its first line:

```
<state>/state/bot/sessions/<run>/session.json      mode 0600
{"runId":"demo","port":47213,"token":"<48 hex>","mode":"backtest","pid":91822}
```

`<state>` is `--state-dir`, else `QKT_STATE_DIR`, else `$XDG_STATE_HOME/qkt`, else `~/.local/state/qkt`.
The server binds `127.0.0.1` only. Every request sends `Authorization: Bearer <token>`; anything else
gets `401 {"ok":false,"error":"unauthorized"}`.

## Errors

A request the session refuses answers `400 {"ok":false,"error":"<reason>"}`: an undeclared symbol or
timeframe, an intent the DSL refuses (a one-sided bracket, an indicator), an unknown identity. An unknown
route is `404`. The reason always says what to do instead.

## Reads

| Route | Answer |
|---|---|
| `GET /status` | `{"ok","api":2,"run","simNowMs","equity","identities":[..],"symbols":[..],"finished"}` |
| `POST /next` `{"symbol","tf"?}` | the next closed bar of the series `{"type":"bar","symbol","timeMs","endMs","open","high","low","close","volume","events":[..]}`, or `{"type":"end","events":[..]}` |
| `GET /bars?symbol=&count=&tf=` | the newest `count` closed bars of the series, oldest first, never past the cursor |
| `GET /quote?symbol=` | `{"symbol","bid","ask","timeMs"}` at the cursor |
| `GET /positions` | per symbol `{"symbol","qty","legs":[{"identity","side","qty","entry","ticket","legId"}]}` |
| `GET /orders` | working entry orders intents placed: `[{"intentId","identity","orderId"}]` |
| `GET /intents` | every intent's fate: `[{"intentId","identity","state","orderIds","reason","atMs"}]` |

`next` is the clock. In a backtest it replays to the next close of that series (every tick inside is
replayed in full, so fills, exits and risk are exact); live it blocks until the bar closes. Each series
(`symbol@tf`) has its own cursor: `next` hands out every bar in order and never skips one, whichever
series you read first. `tf` defaults to the session's smallest timeframe.

`events` holds everything that happened to the session's orders since the previous `next`:

```json
{"seq":3,"kind":"fill","identity":"brain","intentId":"int-1","orderId":"dsl-bot0--1-sl",
 "symbol":"EXNESS:XAUUSD","side":"SELL","price":1849.93,"quantity":0.1,"exitReason":"STOP",
 "reason":null,"atMs":1704161160000}
```

`kind` is `fill`, `rejected` (a risk rule or the venue, named in `reason`) or `cancelled`. An exit the
engine placed (a stop, a target, a timed close) carries its `exitReason` (`STOP`, `TAKE_PROFIT`, `CLOSE`)
and the `intentId` of the entry it protects.

## Intents

`POST /intent` `{"identity","source"}` queues one action. `source` is a complete strategy with one
`WHEN true THEN <action>` rule; its `SYMBOLS` alias any session symbols (`x = EXNESS:XAUUSD EVERY 1m`; the
timeframe there is ignored, the action runs on the session's series):

```
STRATEGY bot VERSION 1
SYMBOLS
    x = EXNESS:XAUUSD EVERY 1m
RULES
    WHEN true
    THEN BUY x SIZING 1 PCT RISK BRACKET { STOP LOSS BY 5, TAKE PROFIT RR 2 }
```

The action is any DSL action with literal values: `BUY`/`SELL` with any sizing, any bracket (fixed, `BY`,
`PCT`, `RR`, `STOP LOSS TRAILING d AFTER MFE >= t`, `STEP TO BREAKEVEN`), `EXIT AFTER`, `OCO`, `STACK`,
`ON FILL`, `ON_STOP`/`ON_TP`/`ON_CLOSE`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`. It is compiled by the
identity's own compiled strategy and fired on the next tick, exactly as a strategy's `SCHEDULE` would fire
it (`BotIntentParityTest` pins that the trades are identical). Refused, with the reason: a one-sided
bracket (as in a strategy), indicator calls (compute the number yourself), `OF BOOK` sizing, `STACK_AT`,
`RESIZE`, option structures.

The reply:

```json
{"ok":true,"api":2,"queued":true,"intentId":"int-4","orderId":"int-4","symbol":"EXNESS:XAUUSD",
 "lots":0.5,"sl":2620.5,"tp":2710.5,"sha256":"<hex>"}
```

`lots`/`sl`/`tp` preview what a literal BUY/SELL resolves to at the cursor (null otherwise); the engine
sizes and prices the order when it fires. What became of it is under `/intents` and in `next` events:
`queued` → `fired` or `skipped` (with the reason: no closed bar yet, a size below the minimum, nothing
to close) → `filled`, `rejected` or `cancelled`.

## Finishing

`POST /finish` `{"flatten"?: true}` ends the session. `flatten` first runs `CLOSE_ALL` for every identity
and waits for a flat book. The reply lists what is still open:

```json
{"ok":true,"finished":true,"trades":12,"reportDir":"/…/runs/demo","openLegs":[..],"workingOrders":[..],"note":"…"}
```

A venue keeps the stops it holds; an exit the engine holds (a trail, a timed exit, every bracket on a
gateway venue) runs again only when a session resumes over the same state (`--run` of the same id).

## Records

Under `<state>/state/bot/sessions/<run>/`, copied into the report directory on finish:

| File | Holds |
|---|---|
| `session.meta.json` | the start arguments and identities |
| `intents.jsonl` | every intent in full (source, identity, arrival time, `tickSeq`), each fate change, the finish point |
| `reads.jsonl` | every read and the bars it served |
| `fills.jsonl` | every execution, by identity |
| `summary.json` | live: per identity fills, realized P&L, open legs |
| `ticks.csv` | live: every tick the session was handed (`timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`) |

`qkt bot session replay <dir>` rebuilds the session from these and writes `replay-diff.json`. A backtest
session replays identically over the store. A live session replays over its own `ticks.csv`, the market it
traded on: each intent enters before the first tick stamped after it arrived (where live fired it), and fills
at the quote standing when the venue's round trip ends (`mt5-sim`, with the round trip the record measured as
`--execution-latency`, reported as `latencyMs`). The diff reports `sameTrades` (the same trades in the same
order), `samePrices` (at the same prices too) and `identical` (also the same times; a replay books a fill on
the tick after the round trip, not at the venue's clock). `--trace` enters each filled intent on the last
recorded tick before its fill was booked: each order keeps its own round trip; the reply's `replay` says which.
