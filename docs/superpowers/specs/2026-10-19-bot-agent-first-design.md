# qkt bot, agent first: one surface for every venue, sessions that are the engine

Date: 2026-10-19. Supersedes the venue limits in `2026-07-13-bot-trade-cli-design.md` and the intent
path in `2026-08-19-bot-run-sessions-design.md`; both stay as history.

## Intent

`qkt bot` is how something that is not a `.qkt` file trades through qkt: an AI agent with a shell,
a program in any language, a person at a terminal. The promise is that such a caller gets the whole
of qkt, not a side door: the brokers and instruments in `qkt.config.yaml` and `instruments.yaml`,
the risk rules and halts, journals, insights, and a backtest that is the same code as live, so what
an agent did live can be replayed, traced and measured afterwards.

Today the surface falls short of that promise in three ways.

1. **Venues.** Every one-shot verb talks to MT5 only (`BotGateway` loads `type: mt5` entries alone);
   a `type: gateway` broker (the paper, Deribit and Bybit venue gateways, so every future, perpetual
   and option qkt trades) is refused (#1390). A live session starts against a gateway account but
   every buy or sell on it fails, because intents compile against an MT5 quote.
2. **Sessions are not the engine.** Intents are compiled by the one-shot bot compiler, not the DSL,
   so inside a running engine a session still refuses everything the engine exists to manage:
   trailing and stepped stops, `EXIT AFTER`, OCO, STACK, ON FILL, CLOSE, CANCEL. There is no way to
   close a position (backlog P7-5; the lab's walk-forwards were invalidated by "closing" with the
   opposite side). Percent and risk sizing fail in a backtest session (no contract size).
3. **Defects.** A session `buy --sl` without `--tp` enters with no stop while the reply reports
   one. A live session builds its pipeline by hand and skips what `qkt deploy` passes: the trading
   calendar, instrument registry, order-size and notional caps, price collar, margin floor,
   measured usage, per-strategy risk, notifier, order and audit journals. `next` across several
   symbols skips bars and an unknown symbol runs the whole replay to its end. Intents are journaled
   as a hash only, so nothing a session did can be replayed. Risk rejections never reach the agent.

## Goals

- One verb surface, one JSON contract, over every configured venue and every product: CFDs and
  metals on MT5, perpetuals, dated futures and single option contracts on a gateway broker.
- A session intent is a DSL action executed by a compiled DSL strategy inside the engine. Whatever
  a one-rule `.qkt` strategy can do with literal values, an intent can do, with the same fills.
- A live session is built by the same factory `qkt deploy` uses, from the same config.
- Every intent is journaled in full; `qkt bot session replay` re-runs a session's intents as a
  backtest and reports, trade by trade, where it differs from what was recorded.
- The agent sees what happened to its orders: fills, exits, rejections with the reason.
- Programs in any language can drive a session over HTTP with a documented, versioned API; a small
  Python client ships as the worked example.

## Non-goals

- A new order model. Sessions use the engine's; one-shot uses the venue's.
- Multi-leg option structures (`OPEN … = OPTIONS ON …`) through the bot. Their aliases are
  compile-time declarations; a caller trades legs as separate intents or deploys a strategy.
- Indicator expressions inside intents. Indicators are bound when a strategy compiles and fed bar
  by bar; an intent arriving later has no warmed series. `qkt bot eval` and `bars` serve the caller
  the data; the caller passes numbers.
- Modifying a live position's stop through a session. The DSL has no such action; a session uses
  the engine forms instead (`STEP TO BREAKEVEN`, trailing, `EXIT AFTER`), or closes and re-enters.

## Anti-goals (a failure even if it works)

- A bot path that books, sizes or fills differently from a deployed strategy on the same input.
- Any silent degrade: an exit that was asked for and not placed, a symbol that is accepted and
  never traded, a rejection the agent cannot see, a replay that quietly diverges.
- A one-shot gateway order the agent believes is protected when the venue holds no stop.

## Design

### 1. Three backends, one surface

| Mode | How it trades | Guardrails | Who uses it |
|---|---|---|---|
| one-shot | straight to the venue (MT5 gateway or VGP gateway), exits immediately | the config's pre-trade controls (`max_order_qty`, `max_order_notional`, `price_collar_frac`), instrument checks | a person, an agent's single action |
| backtest session | the `qkt backtest` engine over the local store, paced by the caller | everything `qkt backtest` applies | research, walk-forwards, agent rehearsal |
| live session | the `qkt deploy` engine over the configured accounts | everything a deployed strategy gets | an agent or program trading for real |

Routing is unchanged: `--run`, then `QKT_BOT_RUN`, then the one running session; no session means
one-shot. Every verb that has a session meaning routes to the session (now including `close`,
`cancel`, `orders`, `intents`); `account`, `history` and `modify` stay venue-direct live.

### 2. One-shot over every venue (#1390)

`BotGateway` becomes the MT5 implementation of a `BotVenue` interface; a `GatewayBotVenue` over
`GatewayClient` is the second. `BotVenue.forSymbol/forBroker` resolves any configured broker by the
symbol prefix (case-insensitive) and picks the implementation by `type`.

`BotVenue` covers: `quoteContext`, `place`, `close`, `modify`, `cancel`, `account`, `positions`,
`orders`, `deals`, `quote`, `bars`. Output shapes stay; a gateway ticket is the order's
`client_order_id` string (MT5 tickets stay numbers).

Gateway specifics, each from the VGP v1 wire contract:

- **Instruments.** Contract size, tick size, volume step and minimum, currency, expiry, strike and
  right come from `GET /v1/instruments`; the qkt symbol maps through `GatewaySymbols` (Deribit
  `BTC_USDC-25DEC26-92000-C` is `DERIBIT:BTC_USDC_25DEC26_92000_C`). Prices snap to the tick grid,
  quantities floor to the step and must meet the minimum, as on MT5.
- **Quote.** There is no REST quote; one-shot opens `GET /v1/quotes?symbols=<code>` and takes the
  first quote (10 s timeout, then an error, never a made-up price).
- **Placing.** `POST /v1/orders` with a fresh `client_order_id` (`bot-<as>-<ms>-<rand>`); a timeout
  resends the same body (idempotent). The command then reads `GET /v1/orders/{id}` until the order is
  filled, working or ended (10 s), and reports that state. Exit 0 when the venue accepted it.
- **Exits.** VGP v1 has no stop or target attached to a position. One-shot `--sl/--tp` on a gateway
  broker is refused with: "a gateway venue holds no stop for a position; run a session (`qkt bot
  session start`) and the engine holds the bracket". A protected entry on a gateway is a session.
- **Close.** `POST /v1/positions/close` with a `client_order_id` (idempotent), whole or `--partial`.
- **Cancel.** `DELETE /v1/orders/{id}`. **Modify** of a pending order's price or quantity is
  `PATCH /v1/orders/{id}`; a position's SL/TP is refused (none exists on the venue).
- **TIF.** GTC and DAY map; GTD and `--expires` are refused (VGP v1 has no expiry).
- **Sizing.** Percent, risk and notional forms work when the instrument's currency equals the
  account's, the same guard as MT5; contract size is the instrument's.
- **Bars and history.** `GET /v1/bars`, `GET /v1/deals`; `bot eval` works on gateway bars.

One-shot also applies the config's pre-trade controls before sending, on every venue: quantity
above `max_order_qty`, notional above `max_order_notional`, or a limit/stop price outside
`price_collar_frac` of the quote is refused with the rule's name.

### 3. A session intent is a DSL action run by the engine

Each declared identity is a compiled DSL strategy (`BotIntentStrategy`), compiled from a generated
strategy that declares the session's streams (one alias per symbol and timeframe) and no live rules.
It is a real `DslCompiledStrategy` in the engine: bound to the candle hub, attributed by the rule
ledger, its exit hooks and ON FILL children run by the engine as for any strategy.

`CompiledStrategy` gains ad-hoc actions, built on the machinery `SCHEDULE` already uses:

- `compileAction(action)`: compiles one `ActionAst` with the strategy's own `ActionCompiler`
  (defaults merged, complete-bracket and read-only checks as for a rule). It refuses, by name, what
  an ad-hoc action cannot have: a new indicator or aggregate binding, `STACK_AT`, `OF BOOK` sizing,
  option structures. Errors are synchronous: the `/intent` reply is the compile error.
- `fireAction(compiled, ctx, emit, nowMs)`: builds the `EvalContext` exactly as `ScheduleFirer`
  does and emits the signals.

The intent travels as the canonical DSL the CLI renders (`STRATEGY bot … WHEN true THEN <action>`).
The server parses it, takes the action, maps its stream to the session alias for that symbol and
timeframe (an undeclared symbol is refused), compiles it, and queues it on the identity. The
strategy drains its queue on the next tick (the timing sessions have today: the first tick after the
one that closed the bar `next` returned) and fires each action. So:

- BUY/SELL with any sizing the DSL has (lots, `% OF EQUITY`, `RISK`, `RISK $`, `USD`), sized by the
  engine's sizing compiler against the session's equity and instrument registry, in backtest and
  live alike.
- Any BRACKET the DSL accepts with literal values: fixed, `BY`, `PCT`, `RR`, trailing,
  `STEP TO BREAKEVEN`, time-tighten, `EXIT AFTER` (the vertical barrier). One side alone is refused
  as the DSL refuses it ("BRACKET requires both STOP LOSS and TAKE PROFIT").
- Order types MARKET, LIMIT, STOP, STOP LIMIT, TRAILING; TIF as the DSL has them.
- OCO, STACK, ON FILL, ON_STOP/ON_TP/ON_CLOSE.
- `CLOSE <sym>`, `CLOSE_ALL`, `CANCEL <sym>`, `CANCEL_ALL`, scoped to the identity's own positions
  and orders, closing leg by leg by ticket as a strategy does.

New CLI forms render to these: `qkt bot close SYM` → `CLOSE`, `close --all` → `CLOSE_ALL`,
`cancel SYM` / `cancel --all` → `CANCEL` / `CANCEL_ALL`, `buy … --trail by:20`,
`--exit-after 4h`, `--oco …`; `qkt bot submit '<one DSL action>' --as id` sends any action text.
A partial close in a session is `sell <qty> SYM` on a netting book, refused on a hedging book
(where the opposite side opens a leg) with the message to close by ticket.

`--dry-run` still renders offline; `qkt bot submit --dry-run` compiles against nothing and parses.

### 4. What the agent sees

Every intent gets an id (`int-<n>`) in the reply. The engine reports, per intent, what it became:
`fired` (with the order ids it emitted), `skipped` (with the DSL skip reason, e.g. size below the
minimum), `rejected` (risk rule and reason), `filled`, exits (`stop`, `target`, `timed`, `closed`).

- `next` returns the bar plus `events`: everything that happened to this session's orders since the
  previous `next` (fills with price and quantity, exits, rejections, halts).
- `qkt bot intents [--run]` lists every intent and its fate; `orders` lists working orders;
  `positions` lists per-identity legs (side, quantity, entry, unrealized, stop, target, ticket).
- `status` adds `halted`, equity, and counts.

### 5. Live sessions are built like a deploy

The construction `DaemonCommand` does from config (account preflight and identity checks, live-money
warning, calendars, instrument registry, risk caps, journals, persistor, notifier, insights) moves to
one builder used by both. `StrategyHandle.RealFactory` gains `createStrategies(name, strategies,
symbols, candleWindow)` beside `create(name, file)`, and a live session asks it for its
`LiveSession`. Warmup is the engine's own warmup for the declared streams, from the account's
history (MT5 or gateway), and the session's bar history is fed from the same candles.

A live session refuses to start when a symbol's account is not configured, before trading.

### 6. Several symbols and timeframes

`--symbols A,B --tf 5m` declares every symbol at every listed timeframe (`--tf 5m,1h`); the
engine's hub builds each. Bar history is keyed by (symbol, timeframe), fed from the hub's own closed
candles, so the agent sees exactly the candles a strategy would. `next SYM [--tf 1h]` returns the
next bar of that series the caller has not been given yet (a cursor per series): it advances the
replay only when none is waiting, and never skips one. A symbol or timeframe the session did not
declare is refused at once.

### 7. Journals and replay

Each session writes, under `<state>/bot/sessions/<run>/` and copied into the report directory:

- `intents.jsonl`: per intent, the full DSL source, identity, intent id, arrival sim time, the
  engine tick sequence it entered on, the compiled order(s), and its fate as it becomes known.
- `reads.jsonl` (as today) plus the bar values served, so the record holds what the agent saw.
- Live: the deploy order journal and audit journal, `fills.jsonl` from the engine's trade callback,
  and on finish `summary.json` (per identity: round trips, realized, fees, open positions).

`qkt bot session replay <session-dir> [--out dir]` builds the backtest session the record names
(symbols, timeframes, window, config) over the store and re-injects every intent at its recorded
point: by engine tick sequence for a backtest record (the result must be identical, trade for
trade, which is the determinism test), by first stored tick after the arrival time for a live record
(a documented approximation: live ticks are not the stored ticks). It writes the standard report and
`replay-diff.json`: each recorded fill beside its replayed fill (side, quantity, price, time).

### 8. Any language

`docs/reference/bot-session-api.md` documents the HTTP API: discovery
(`<state>/bot/sessions/<run>/session.json`, mode 0600, `{api, url, token, mode, pid}`), bearer
auth, every route with request and response, error codes. Responses carry `"api": 2`.
`session start --json` prints the descriptor path and url on its first line. The server keeps the
routes it has and adds `/close`, `/cancel`, `/orders`, `/intents`, `/submit` (any action text).

`examples/bot/python/qkt_session.py` is a dependency-free client (urllib) with a worked loop; a test
runs it against a real backtest session in CI when `python3` is present.

### 9. Decisions settled after review (builder's calls, recorded for the reader)

- **Restart and finish.** The engine persists a live session's legs and exits as for a deploy. What an
  ad-hoc action registered at runtime (exit hooks, ON FILL children) is rebuilt on start by compiling,
  in order and without firing, every action in the run's `intents.jsonl`; the journal is part of the
  session's persisted state. `finish` leaves positions open (their venue-side or engine-side exits as
  they are) and lists them; `finish --flatten` runs `CLOSE_ALL` for every identity first. When an
  open leg's exits are engine-held (every gateway bracket), the reply says nothing protects it until
  a session resumes over the same state.
- **Process.** A live session stays its own process (`qkt bot session start`), holds a lock per run,
  and runs the same account preflight and production checks as the daemon (moved to a shared
  function). An identity that names a strategy the daemon is running is refused (they would share a
  state directory).
- **Shared netting accounts.** A one-shot order on a symbol an engine also holds changes that
  engine's net position; this is documented as the operator's decision, not detected.
- **Compatibility.** Session routes and fields that exist stay as they are (the lab drives them);
  new fields are additive, `/positions` keeps `symbol` and `qty` per row. Sizing is evaluated when
  the intent fires (as a strategy does), so percent sizing reads equity one tick later than before.
- **Threads.** Compiling an ad-hoc action mutates the strategy's exit-hook catalog and id sequence;
  both become safe for a compile on the HTTP thread while the engine thread reads them.
- **Parity reference.** `BotIntentParityTest` compares a session against the same DSL actions
  compiled by `AstCompiler` into an ordinary strategy and fired at the same ticks by a test harness;
  the existing hand-written-signal parity test stays as the independent check for market orders.
- **Several accounts, one client.** A session may span accounts; sizing, equity and halts behave as
  they do for a deployed strategy that spans brokers. One program drives a session (it may use many
  identities); cursors and event queues are per session.
- **No closed bar yet.** An intent fired before its stream has closed a bar is `skipped` with that
  reason, never retried silently.
- **Capability checks.** Each ad-hoc action runs the deploy-time capability checks for what it uses
  (order types the venue supports, stack/volume/mark requirements) and is refused at compile time.

## Verification (definition of done)

- Unit and CLI tests for every row above; the existing parity tests keep passing.
- Parity: an intent sequence through a session gives the same trades as the same actions written
  as a deployed one-rule-per-action strategy firing at the same ticks (`BotIntentParityTest`),
  including a bracket, a trailing stop, `EXIT AFTER`, a CLOSE and percent sizing.
- Replay of a backtest session is identical; replay of a live session produces a diff.
- Live, on real venues: the Exness demo (MT5, XAUUSD and BTCUSD, one-shot and session: bracket,
  close, cancel, trailing), the paper venue gateway, Deribit testnet (perpetual, a dated future,
  one option contract: one-shot buy, close; session buy with bracket, close) and Bybit testnet.
- Docs, the agent system prompt and the bot example updated; release proofs green.

## Left to the builder

File layout, names of new internal classes, the exact JSON field order, and test fixtures.
