# Bot CLI — trading through qkt from an agent, a program, or a shell

`qkt bot` is how something that is not a `.qkt` file trades through qkt: an AI agent with a shell, a
program in any language, a person at a terminal. It has two modes, and the same verbs drive both.

| Mode | What runs | Use it for |
|---|---|---|
| **One-shot** (no session running) | each command talks to the venue directly and exits | a single action: look, buy, close |
| **Session** (`qkt bot session start`) | the qkt engine itself, as a backtest or live; the verbs become its inputs | anything managed over time, every guardrail, backtests, parity |

With a session running, the verbs route to it (`--run <id>`, else `QKT_BOT_RUN`, else the only one);
with none, they act one-shot. A program that is not a shell uses the session's
[HTTP API](bot-session-api.md) directly.

```
agent / program ──▶ qkt bot ... ──▶ one-shot: the venue (MT5 gateway, VGP gateway)
                                └──▶ session: the engine ─▶ backtest over your data
                                                         └▶ live, built as `qkt deploy` builds it
```

## Prerequisites

- A `qkt.config.yaml` (`--config <path>`, `./qkt.config.yaml`, `/etc/qkt/qkt.config.yaml`,
  `~/.config/qkt/qkt.config.yaml`, `~/.qkt/qkt.config.yaml`) with the brokers you trade: `type: mt5`
  (CFDs and metals through an mt5-gateway) or `type: gateway` (perpetuals, dated futures and options
  through a VGP venue gateway: paper, Deribit, Bybit; wire format in the qkt-venue-gateway repository).
- Symbols are always `BROKER:SYMBOL`, the prefix being the broker's config name in any case:
  `EXNESS:XAUUSD`, `DERIBIT:SOL_USDC_PERPETUAL`, `DERIBIT:BTC_USDC_25DEC26_92000_C`. An unknown prefix
  fails closed and names the brokers configured.
- `instruments.yaml` (contract size, steps, tick) for backtest sessions, as for `qkt backtest`.
- Optionally an `insights:` block; every command, order and fill is egressed to qkt-insights.

A starter is in `examples/bot/` (config, an agent system prompt, and a Python client); `qkt create
template <path> --kind bot` scaffolds a project.

## Common flags

| Flag | Applies to | Meaning |
|---|---|---|
| `--json` | all | one JSON document on stdout (the agent contract) |
| `--config <path>` | all | explicit config file |
| `--as <name>` | trade verbs | identity: the strategyId in journals, reports and insights (default `manual`) |
| `--dry-run` | buy/sell/close/cancel/submit | render and print the canonical DSL, send nothing |
| `--broker <name>` | account/history | the broker when no symbol names one and several are configured |
| `--state-dir <dir>` | all | the state root (journals, sessions) |
| `--run <id>` | all | the session to route to |

Positional arguments come before flags.

## One-shot trading

```bash
qkt bot buy  0.5 EXNESS:XAUUSD                            # market
qkt bot buy  0.5 EXNESS:XAUUSD --sl by:30 --tp rr:2       # bracket the venue holds (MT5)
qkt bot sell 0.2 EXNESS:XAUUSD --limit 2680 --tif day     # pending limit, ends with the UTC day
qkt bot buy  0.1 EXNESS:XAUUSD --stop-limit 2700:2701
qkt bot buy  EXNESS:XAUUSD --sizing "2 % OF EQUITY" --sl by:30
qkt bot buy  2 DERIBIT:SOL_USDC_PERPETUAL                 # a perpetual on a gateway broker
qkt bot sell 0.1 DERIBIT:BTC_USDC_25DEC26_92000_C --limit 650
qkt bot close  EXNESS:XAUUSD [--ticket N] [--partial 0.2] [--all]
qkt bot modify EXNESS:XAUUSD --ticket N --sl 2605         # keeps the current TP
qkt bot cancel EXNESS:XAUUSD --order N | --all
```

`close --all` and `cancel --all` act on the caller's own positions and orders only (those `qkt bot` placed
under the same `--as`); a deployed strategy's legs and exits on the symbol stay. `--any` widens them to
every position or order on the symbol. A single position, or one named by `--ticket`/`--order`, is acted on
whoever placed it.

- Exits (`--sl` / `--tp`): `2610` or `at:2610`, `by:30` (distance), `pct:1.5`, `rr:2` (target only,
  multiple of the stop distance); `--sl trail:4[@2]` is a stop trailing 4 behind the best price once the
  trade is 2 in profit (session only: it needs the engine).
- Sizing: positional lots, or `--sizing` with a DSL sizing form (`N`, `N % OF EQUITY`, `N % OF BALANCE`,
  `RISK <frac>`, `N PCT RISK`, `RISK $ <amount>`, `<usd> USD`). Percent, risk and notional forms need the
  instrument quoted in the account currency (one-shot has no FX rates); the contract size is the venue's.
- Quantity floors to the venue's step and must meet its minimum; prices land on the venue's tick grid.
- **Pre-trade controls** from the config apply before anything is sent: `risk.max_order_qty`,
  `risk.max_order_notional` (quantity x price x contract size) and `risk.price_collar_pct` (a limit or stop
  too far from the quote). A refusal names the key.
- BUY prices against the ask, SELL against the bid.
- Exit 0 only after the venue accepted. For a gateway order the command reads the order back until the
  venue has decided it (`state`: `filled`, `working`, `cancelled` or `rejected`, up to 10 s).

### Gateway brokers (futures, perpetuals, options)

A VGP gateway holds no stop or target on a position, so one-shot `--sl/--tp` on a gateway broker is
refused before anything is sent: a protected entry there is a session, where the engine holds the bracket.
Also refused one-shot on a gateway: GTD and `--expires` (VGP v1 has no expiry), and `modify` of a
position. `close` sends the gateway's flatten (`POST /v1/positions/close`, idempotent), all of it or
`--partial`; a netting position's ticket is its venue code. Quotes come from the gateway's quote stream.

## Queries

```bash
qkt bot account --json
qkt bot positions [SYMBOL] --json
qkt bot orders [SYMBOL] --json
qkt bot quote SYMBOL --json
qkt bot bars SYMBOL --tf 1h --count 100 --json
qkt bot history --since 30d --json
qkt bot eval "rsi(14)" SYMBOL --tf 15m --json
```

Queries read venue truth (positions opened by deployed strategies included). A failed venue read is an
error, never an empty result. `bot eval` takes one single-series indicator with numeric arguments.

## JSON output (one-shot)

`buy`/`sell`:

```json
{"ok":true,"ticket":123456,"deal":789,"state":"filled","fillPrice":2650.5,"retcode":10009,"error":null,
 "symbol":"EXNESS:XAUUSD","side":"BUY","lots":0.5,"sl":2620.5,"tp":2710.5,"as":"manual",
 "canonicalDsl":"STRATEGY bot VERSION 1\n...","sha256":"<hex>","qktVersion":"..."}
```

An MT5 ticket is a number, a gateway ticket its `client_order_id` string. `close`/`cancel` print an array
(one element per ticket); `modify` prints `{ok,retcode,error}`. `account` prints
`{ok,equity,balance,currency,margin,freeMargin,marginLevel,openProfit,leverage,login,server,hedging}`;
`positions` `[{ticket,symbol,side,lots,entry,current,sl,tp,profit,swap,openedAtMs,comment}]`; `orders`
`[{ticket,symbol,type,lots,price,sl,tp,expiresAt}]`; `quote` `{symbol,bid,ask,spread,timeMs}`; `bars`
`[{t,o,h,l,c,v}]`; `history` `[{dealTicket,positionTicket,symbol,side,entry,lots,price,profit,commission,swap,fee,timeMs,comment}]`.
Every failure prints `{"ok":false,"error":"..."}` and exits 1; a usage error exits 2.

Each trade renders to a complete canonical `.qkt` strategy; the text, its sha256, the qkt version and the
argv ride every journal line (`<state>/state/bot/<as>/journal-YYYY-MM-DD.jsonl`) and insights envelope.

## Sessions: the engine, driven by your program

```bash
# backtest over the store (every qkt backtest flag applies)
qkt bot session start --backtest --symbols EXNESS:XAUUSD,EXNESS:XAGUSD --tf 5m,1h \
  --from 2025-01-01 --to 2025-06-30 --identities trend,meanrev --run demo --json &

# live over the configured accounts: the same command without --backtest/--from/--to
qkt bot session start --symbols DERIBIT:SOL_USDC_PERPETUAL --tf 1m --identities agent --run live1 --json &

qkt bot next EXNESS:XAUUSD [--tf 1h]       # the next closed bar of the series + events since the last next
qkt bot bars EXNESS:XAUUSD --count 200 [--tf 1h]
qkt bot buy 0.5 EXNESS:XAUUSD --sl by:5 --tp rr:2 --as trend
qkt bot sell 0.2 EXNESS:XAUUSD --sl trail:4@2 --tp by:15 --as meanrev
qkt bot buy 0.1 EXNESS:XAUUSD --exit-after 4h --as trend
qkt bot submit 'BUY x SIZING 1 PCT RISK BRACKET { STOP LOSS BY 5 STEP TO BREAKEVEN AFTER MFE >= 8, TAKE PROFIT RR 2 }' \
  --symbol EXNESS:XAUUSD --as trend         # any DSL action
qkt bot close EXNESS:XAUUSD --as trend      # CLOSE: this identity's legs, by ticket
qkt bot close --all --as trend              # CLOSE_ALL
qkt bot cancel EXNESS:XAUUSD --as trend     # CANCEL (cancel --all: CANCEL_ALL)
qkt bot intents --json                      # every intent's fate
qkt bot orders --json                       # working entries intents placed (backtest; live reads the venue)
qkt bot positions --json                    # net per symbol + each identity's legs
qkt bot session status --json
qkt bot session finish [--flatten] --json   # writes the report; --flatten closes everything first
qkt bot session replay runs/demo --out runs/demo-replay --json
```

**A session intent is a DSL action run by the engine.** Each identity is a compiled DSL strategy; an
intent is compiled by it and fired on the next tick, exactly as the same action in a strategy's
`SCHEDULE` would fire (`BotIntentParityTest`). So everything a strategy can do with literal values, an
intent can: any sizing (sized by the engine against the session's equity and instruments), any bracket
including trailing and stepped stops, `EXIT AFTER`, OCO, STACK, ON FILL, exit hooks, CLOSE and CANCEL.
Refused with the reason: a bracket missing its stop or target (as in a strategy), indicator calls in an
intent (compute the value and pass a number; `bars` and `eval` give you the data), `OF BOOK` sizing,
`STACK_AT`, `RESIZE`, option structures (trade the legs).

**What the agent sees.** Every intent gets an id; its fate goes `queued` → `fired`/`skipped` →
`filled`/`rejected`/`cancelled`, with the risk rule or venue reason when refused. `next` carries the
events since the previous `next`: fills (price, quantity, and for exits `STOP`/`TAKE_PROFIT`/`CLOSE`),
rejections and cancels. Reads never see past the cursor.

**Several symbols and timeframes.** `--symbols A,B --tf 5m,1h` declares every symbol at every timeframe
(each a multiple of the smallest); `next SYMBOL --tf` reads a series with its own cursor, so no bar is
skipped. An undeclared symbol or timeframe is refused at once.

**Live sessions are built like a deploy.** The same pipeline factory `qkt deploy` uses: account identity
checks, the production preflight in production mode, every risk rule, halt, collar, margin floor and
measured-usage cap in the config, the order and audit journals, the state persistor, the notifier and
insights. `per_strategy_risk` applies to a session's only identity. An identity the daemon on the same
state directory runs is refused (they would share state). `positions`, `account` and `orders` read the
venue in a live session; everything else goes through the engine.

**Restart and finish.** Legs and exits persist as for a deploy. Starting a live session with the `--run`
of an earlier one over the same state recompiles every intent it journaled before trading, so its
engine-held exits restore; reconcile is fail-closed as for `qkt deploy` (`--reconcile=ignore-mismatches`
is the operator escape hatch). `finish` leaves positions open and lists them: a venue keeps the stops it
holds (MT5), but an exit the engine holds (a trail, a timed exit, any gateway bracket) runs only while a
session runs. `finish --flatten` closes everything first.

**Records and replay.** Each session writes `session.meta.json`, `intents.jsonl` (every intent in full,
with where it entered the replay, every fate change and the finish point), `reads.jsonl` (what the agent
was served), `fills.jsonl`, and live `summary.json`, all copied into `--out`. `qkt bot session replay`
rebuilds the session from them over the store and writes the report plus `replay-diff.json`: a backtest
session replays identically, trade for trade; a live session replays over stored ticks and the diff shows
each fill that moved.

In a session, `modify`, `close --partial`, `close --ticket` and `cancel --order` are refused with the
session way to do it (set stops with the entry, reduce with the opposite order on a netting book, close
or cancel by symbol).

## Limits

- One-shot runs no session state: daily drawdown, halts and book de-risking need a session or a deploy.
  One-shot does apply the pre-trade controls.
- One-shot never reports a pending order's later fill; read `positions`/`orders`, or use a session.
- A one-shot order on a symbol an engine also holds changes that engine's net position on a netting
  account; that is the operator's call.
- One program drives a session (it may use many identities); bar cursors and events are per session.
