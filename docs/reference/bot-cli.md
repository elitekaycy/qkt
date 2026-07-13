# Bot CLI — one-shot manual/AI trading

`qkt bot` is a one-shot trading surface: each command loads the config, talks to the
configured MT5 gateway synchronously, prints its result, and exits. It exists so an
external agent (an AI overlay, a script, or a human at a shell) can analyze and act
through qkt with the same tracking the DSL engine gets — every trade is rendered to
canonical DSL, journaled, and egressed to qkt-insights.

```
external AI  ──shell──▶  qkt bot ...  ──HTTP──▶  mt5-gateway  ──▶  broker
                             │
                             ├── journal   <state>/bot/<as>/journal-YYYY-MM-DD.jsonl
                             └── insights  bot.command / order.submit / order.accepted|rejected
```

## Prerequisites

- A `qkt.config.yaml` with an MT5 broker whose gateway is reachable. Resolution order:
  `--config <path>`, `./qkt.config.yaml`, `/etc/qkt/qkt.config.yaml`,
  `~/.config/qkt/qkt.config.yaml`, `~/.qkt/qkt.config.yaml`.
- Optionally an `insights:` block for egress. Without it, trades are journaled locally
  only.
- Symbols are always `BROKER:SYMBOL` (e.g. `EXNESS:XAUUSD`); the prefix must match a
  configured broker or the command fails closed.

A ready-to-copy starter lives in `examples/bot/`, or scaffold a full project with
`qkt create template <path> --kind bot` (MT5 compose stack plus an AI
system-prompt describing this whole surface).

## Common flags

| Flag | Applies to | Meaning |
|---|---|---|
| `--json` | all | print one JSON document on stdout (the AI contract) |
| `--config <path>` | all | explicit config file |
| `--as <name>` | trade verbs | attribution id, default `manual`; becomes the strategyId in journal + insights |
| `--dry-run` | buy/sell | render + validate, print canonical DSL, submit nothing (offline) |
| `--broker <name>` | account/history | pick the broker when no symbol names one and several are configured |
| `--state-dir <dir>` | trade verbs | override the journal state root |

Positional arguments must come before flags (the CLI arg parser is positional-first).

## Trading

```bash
qkt bot buy  0.5 EXNESS:XAUUSD                              # market
qkt bot buy  0.5 EXNESS:XAUUSD --sl by:30 --tp rr:2         # bracket, venue-attached SL/TP
qkt bot sell 0.2 EXNESS:XAUUSD --limit 2680 --tif day       # pending limit, expires end of UTC day
qkt bot buy  0.1 EXNESS:XAUUSD --stop 2700 --expires 2026-07-14T00:00:00Z
qkt bot buy  0.1 EXNESS:XAUUSD --stop-limit 2700:2701       # stop-limit trigger:limit
qkt bot buy  EXNESS:XAUUSD --sizing "2 % OF EQUITY" --sl by:30
qkt bot buy  EXNESS:XAUUSD --sizing "RISK 0.01" --sl by:30 --tp rr:2
```

- Exit specs (`--sl` / `--tp`): `2610` or `at:2610` (absolute), `by:30` (price-unit
  distance from entry), `pct:1.5` (percent of entry), `rr:2` (take-profit only,
  multiple of the stop distance).
- Sizing: positional lots, or `--sizing` with a literal DSL sizing form — `N`,
  `N % OF EQUITY`, `N % OF BALANCE`, `RISK <frac>`, `N PCT RISK`, `RISK $ <amount>`,
  `<usd> USD`. Live equity/balance is read from the venue at compile time. Percent,
  risk, and notional forms require the instrument's quote currency to equal the
  account currency (there is no FX conversion one-shot) — otherwise pass explicit
  lots.
- Volume is quantized down to the instrument's step and validated against its
  minimum; a resolved size below the minimum rejects rather than rounding up.
- Prices for BUY resolve against the ask, SELL against the bid; pending entries
  price their exits from the entry price itself.
- Exit code 0 only after the venue accepts (`retcode` 10008/10009/10010); any
  rejection exits 1 with the venue's reason.

Managing what's open:

```bash
qkt bot close  EXNESS:XAUUSD                      # single open position: closes it
qkt bot close  EXNESS:XAUUSD --ticket 123456      # specific ticket
qkt bot close  EXNESS:XAUUSD --ticket 123456 --partial 0.2
qkt bot close  EXNESS:XAUUSD --all
qkt bot modify EXNESS:XAUUSD --ticket 123456 --sl 2605          # keeps the current TP
qkt bot cancel EXNESS:XAUUSD --order 654321
qkt bot cancel EXNESS:XAUUSD --all                 # every pending order on the symbol
```

`modify` reads the position first and preserves the level you did not pass (the MT5
gateway clears an omitted SL/TP, so sending only one would silently drop the other).

## Queries

```bash
qkt bot account --json
qkt bot positions EXNESS:XAUUSD --json     # omit the symbol for all positions
qkt bot orders EXNESS:XAUUSD --json
qkt bot quote EXNESS:XAUUSD --json
qkt bot bars EXNESS:XAUUSD --tf 1h --count 100 --json
qkt bot history --since 30d --json         # closed deals; also ISO-8601 or epoch ms
qkt bot eval "ema(21)" EXNESS:XAUUSD --tf 1h --json
qkt bot eval "rsi(14)" EXNESS:XAUUSD --tf 15m --count 300 --json
```

Queries read venue truth directly — positions opened by deployed strategies are
visible too. A failed venue read is an error (exit 1), never an empty result.

`bot eval` accepts any single-series indicator from the DSL registry with numeric
literal arguments (`ema`, `sma`, `rsi`, `atr`, `stddev`, ...). Two-series indicators
(`correlation`, `beta`) are not available one-shot.

## JSON output schemas

`bot buy` / `bot sell`:

```json
{"ok":true,"ticket":123456,"deal":789,"fillPrice":2650.5,"retcode":10009,
 "error":null,"symbol":"EXNESS:XAUUSD","side":"BUY","lots":0.5,
 "sl":2620.50,"tp":2710.50,"as":"manual",
 "canonicalDsl":"STRATEGY bot VERSION 1\n...","sha256":"<hex>","qktVersion":"..."}
```

`bot close` / `bot cancel` print a JSON array (one element per ticket acted on);
`bot modify` prints `{ok,retcode,error}`. `bot account` prints
`{ok,equity,balance,currency,margin,freeMargin,marginLevel,openProfit,leverage,login,server,hedging}`.
`bot positions` prints an array of
`{ticket,symbol,side,lots,entry,current,sl,tp,profit,swap,openedAtMs,comment}`;
`bot orders` an array of `{ticket,symbol,type,lots,price,sl,tp,expiresAt}`;
`bot quote` `{symbol,bid,ask,spread,timeMs}`; `bot bars` an array of
`{t,o,h,l,c,v}`; `bot history` an array of
`{dealTicket,positionTicket,symbol,side,entry,lots,price,profit,commission,swap,fee,timeMs,comment}`;
`bot eval`
`{ok,expression,symbol,tf,value,isReady,warmupBars,barsUsed,lastBarStart,lastClose,error}`.

Every failure path prints `{"ok":false,"error":"..."}` and exits 1; unknown verbs or
missing arguments exit 2.

## Versioning and tracking

Each trade renders to a complete canonical `.qkt` strategy (stream declaration plus a
single `WHEN true THEN BUY/SELL ...` rule) that the standard parser validates:

```
STRATEGY bot VERSION 1

SYMBOLS
    x = EXNESS:XAUUSD EVERY 1m

RULES
    WHEN true
    THEN BUY x SIZING 0.5 BRACKET { STOP LOSS BY 30, TAKE PROFIT BY 60 }
```

The canonical text, its sha256, the qkt version, and the raw argv ride every journal
line and insights envelope — the same identity model (name + source hash + version)
deployed strategies use. In qkt-insights the `--as` name appears as the strategyId,
so `--as claude-scalper` and `--as manual` are separately analyzable.

## What one-shot mode refuses (fail-closed)

Anything that needs a persistent engine to manage rejects with an error pointing at
`qkt deploy`:

- trailing entries and armed-trail stops (nothing stays alive to move the stop)
- `STACK` / `STACK_AT`, `OCO`, `ON FILL`
- non-literal expressions in sizing or exit prices (no bar/indicator context exists)
- IOC / FOK time-in-force

## Documented divergences from deployed strategies

- **Post-ack fills are not egressed by this process.** The command exits after the
  venue ack, so a pending order that fills later is observed only by venue-truth
  pollers (the qkt-insights broker state poller), not by a qkt session.
- **Session-stateful risk does not apply.** Daily drawdown, halt state, and book
  de-risking live in a running session. One-shot pre-trade checks are instrument
  validation, volume quantization, and sizing-currency guards.
- **DAY time-in-force** becomes an explicit end-of-UTC-day expiration on the wire
  (MT5's gateway carries expiry only as an absolute deadline).
