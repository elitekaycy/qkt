# Futures and options in qkt — design

**Date:** 2026-09-30
**Phases:** 42 (futures), 43 (options), 44 (venue gateway client)
**Builds on:** [venue plugin research](../../research/2026-09-18-venue-plugin-architecture.md),
[trading account contracts](2026-09-18-trading-account-contracts-design.md)

## 1. Goal

A strategy trades a futures or options instrument through the same pipeline, the same DSL and the
same reports as a CFD, in backtest and live, with the same parity guarantees. CFD behaviour does
not change: every existing strategy produces byte-identical backtest output, the same strategy
fingerprint, and the same persisted state files.

**In scope**

- Dated futures (linear): exchange-listed contracts that expire and settle — CME-style and crypto
  quarterlies (Binance USDⓈ-M, Bybit linear dated, Deribit USDC linear).
- Continuous futures streams (`ROOT@front`, `ROOT@next`) with point-in-time roll handling.
- European, cash-settled, linear options (Deribit USDC options, CME-style cash options).
- Backtest on free data: Binance USDⓈ-M quarterly futures, Binance delivery prices, Tardis
  first-of-month Deribit option chains.
- Live: a `type: gateway` connector speaking the venue gateway protocol (VGP v1).

**Out of scope (refused with a clear error, not silently mis-booked)**

- Inverse (coin-margined) contracts: Binance COIN-M, Deribit BTC-settled futures/options, Bybit
  inverse. Their P&L is `qty × size × (1/entry − 1/exit)` in the base coin; qkt books linear P&L.
- American options, early exercise and assignment.
- Physical delivery. A position must be flat or rolled before first-notice.
- Negative prices (e.g. CL April 2020): `TickIngest.isValidTick` drops non-positive prices and
  `PositionLeg` requires a positive entry. Contracts that can trade at or below zero are refused.
- Portfolio margin (SPAN). Margin is per contract.

## 2. Constraints this design must satisfy (verified in code)

| Constraint | Where | Consequence |
|---|---|---|
| Strategy fingerprint = `sha256(ast.toString())` | `dsl/compile/AstCompiler.kt:153` | No field may be added to `StreamDecl`, `SeriesDecl` or `StrategyAst`. New syntax lands in existing string fields or in new sealed subtypes only used by new strategies. |
| Exit-hook ids salted with `HubKey.toString()` | `dsl/compile/ExitHookDefinition.kt:77-90` | `HubKey` stays a 3-string data class. |
| New `TokenKind` = reserved word everywhere | `dsl/parse/Keywords.kt:7-43` | No new keywords. New words (`front`, `next`, `PUT`, `DELTA`…) are matched contextually as `IDENT` lexemes. |
| File-size guard 200 lines (220 tests); baselined files may only shrink | `build-logic/.../qkt.file-size.gradle.kts`, `config/file-size-baseline.txt` | New behaviour lives in new files. Baselined files (`ReplayEngine`, `PaperBroker`, `LiveSession`, `Config`, `OrderRequest`, `BacktestContext`…) get extractions, never growth. |
| Connectors may not import `app, cli, risk, observe, dsl, backtest, trade, research`; core may not name `com.qkt.connector.<x>` | `ConnectivityArchitectureTest` | The gateway connector is self-contained; futures core lives outside `connector`. |
| Positions, marks, instruments are keyed by the full symbol string | `positions/StrategyLegBooks.kt`, `marketdata/MarketPriceTracker` | Each contract is its own symbol; continuous streams are their own symbol. No keying change. |
| State file names are `"$symbol-legbook.json"` | `persistence/StateFileLayout.kt:7-10` | Symbols must be file-name safe: `[A-Za-z0-9_.@-]` only. Enforced when a symbol is built. |
| `STATE_SCHEMA_VERSION = 1` must match exactly | `persistence/StateFileLayout.kt:4` | No bump. New persisted fields are nullable with defaults (kotlinx omits null defaults; `ignoreUnknownKeys = true`). |
| Money is `BigDecimal` through `Money.CONTEXT`; `Double` only for non-monetary statistics | skill §8 | Greeks and IV are `Double`; premiums, prices, P&L are `BigDecimal`. |
| Mode symmetry: a component wired in one of `LiveSession`/`ReplayEngine` only is a declared divergence | skill §7 | Every new subscriber is wired in both, or catalogued in `docs/parity/backtest-vs-live.md`. |

## 3. Concepts and identities

| Term | Example | Meaning |
|---|---|---|
| **Root** | `BINANCE_UM:BTCUSDT`, `CME:ES` | A contract family. Carries the static spec (multiplier, tick, currency, calendar, roll policy). |
| **Contract symbol** | `BINANCE_UM:BTCUSDT_240927`, `CME:ESZ6`, `DERIBIT:BTC_USDC-27SEP24-60000-C` | One listed contract. What orders are sent on at the venue. Has expiry. |
| **Continuous symbol** | `BINANCE_UM:BTCUSDT@front`, `CME:ES@next` | A strategy-facing perpetual view of a root. Never sent to a venue. Positions, marks, candles and P&L for a continuous stream are kept under this symbol, in its price space. |
| **Contract chain** | all BTCUSDT quarterlies ordered by expiry | From the contract catalog. |
| **Roll schedule** | `2024-09-19T08:00Z BTCUSDT_240927 → BTCUSDT_241227, gap +812.4` | The transitions for one root under one roll policy, with the gap measured at each roll. |
| **Offset** | cumulative gap | Maps contract prices to the continuous price space. |

An instrument is a derivative when its metadata carries `DerivativeTerms` (4.1). Absent terms =
today's behaviour (CFD/spot heuristics); only present terms enable any new code path.

## 4. Instrument model

### 4.1 `InstrumentMeta` gains two optional fields

```kotlin
val currency: String? = null,            // explicit quote/settlement currency
val derivative: DerivativeTerms? = null, // exchange-listed terms; absent for CFDs and spot
```

`DerivativeTerms` is a sealed interface (`root`, `margin: MarginTerms?`, `exchangeFeePerContract`,
`takerFeeRate`). Phase 42 adds `FutureTerms(root, expiryMs: Long?, …)` — `expiryMs` null is a
continuous view of the root; phase 43 adds `OptionTerms`. A new kind is a new subtype, so every
consumer's `when` must handle it. `contractSize` is the multiplier (already used that way by nine
call sites). Fields are appended last; every existing constructor uses named arguments (verified),
so no call site changes. `InstrumentsPull` learns `currency` so a round-trip does not drop it.

### 4.2 Where futures meta comes from

Writing one YAML entry per contract does not scale (41 ES contracts since 2019). Two sources:

1. **Roots** in `instruments.yaml`, a new top-level `futures:` list next to `instruments:`
   (old binaries ignore unknown top-level keys — verified the loader reads only `instruments`):

   ```yaml
   futures:
     - root: BINANCE_UM:BTCUSDT
       currency: USDT
       multiplier: 1
       tickSize: 0.1
       volumeStep: 0.001
       volumeMin: 0.001
       calendar: crypto
       exchangeFeePerContract: 0      # linear crypto fees are notional-based; see 4.4
       roll: { daysBeforeExpiry: 8, atUtc: "08:00", adjust: panama }
       margin: { initial: 0.05, maintenance: 0.025, basis: notional }
   ```

2. **The contract catalog** `<dataRoot>/contracts/<VENUE>/<ROOT>.json`, written by `qkt fetch`
   and by the live gateway's `/v1/instruments`: one row per contract (symbol, expiry, optional
   delivery price). Adding a contract never needs a YAML edit.

`ContractCatalogRegistry : InstrumentRegistry` joins the two: `lookup("BINANCE_UM:BTCUSDT_240927")`
returns the root spec with that contract's `expiryMs`; `lookup("BINANCE_UM:BTCUSDT@front")` returns
the root spec with `kind = FUTURE` and no expiry. It is layered **after** the YAML registry, so an
explicit `instruments:` entry always wins, and it only answers symbols whose root is declared.

### 4.3 Explicit currency in accounting

`AccountingEngine.pnlCurrencyFor(symbol)` (`accounting/Accounting.kt:164`) consults
`instruments.lookup(symbol)?.currency` first and falls back to today's suffix guess. CFD YAML has no
`currency`, so the fallback runs and bytes are unchanged. `ES` has no currency suffix: without this
it books in account currency silently — the hole this closes. A futures/options symbol without meta
fails fast at replay/live start (new guard beside `requireReplaySymbolsResolvable`), independent of
the suffix heuristic that `requiresContractSizeMeta` uses.

### 4.4 Fees

`PerLotCommission` stays. A new `ContractFeeCommission` adds `|qty| × exchangeFeePerContract` and,
when the root declares `takerFeeRate`, `|qty| × price × contractSize × rate` (crypto fees are
notional-based). It is selected only for symbols whose meta has `kind = FUTURE | OPTION`; CFD symbols
keep `PerLotCommission`. Fees land in `CommissionBook` as today (account currency after conversion).

## 5. Futures

### 5.1 Contract codes

A contract's expiry always comes from the catalog; qkt never derives it from a contract code at run
time. The only code parser is the Binance quarterly one (`ROOT_YYMMDD`, delivering 08:00 UTC), used to
build Binance catalogs from the public file listing. CME month letters need no parser: a CME catalog
comes from the venue or data vendor with expiries attached.

### 5.2 Roll policy and schedule

`RollPolicy(daysBeforeExpiry, atUtc, adjust)` — calendar rule only in phase 42. Roll time =
`expiry − daysBeforeExpiry` calendar days, at `atUtc`. It depends only on the catalog, so backtest
and live compute the same instant.

`RollSchedule.build(chain, policy)` yields transitions `(atMs, from, to)`. The **gap** at each
transition is the next contract's reference price minus the front contract's reference price, where
the reference price is the last trade at or before `atMs` in each contract (trade price when the
feed has trades, otherwise the tick price). Backtest reads it from stored ticks; live measures it
from the live feed and appends it to `<dataRoot>/contracts/<VENUE>/<ROOT>.rolls.json`, so a later
backtest of the same window reuses the exact live gap.

Edge rules:

- **Next contract has no price by `atMs`** (illiquid, data gap, weekend): the roll waits for the
  first tick of the next contract. If the front expires first, the front position is settled at
  expiry (5.5) and the stream halts with a logged reason; it never trades a contract without a
  price.
- **Roll instant falls in a closed session** (CME weekend): the roll executes on the first tick
  after `atMs` on which both contracts are in session.

### 5.3 Price adjustment — forward, anchored at the catalog

qkt uses **forward adjustment**: history is never rewritten; each new segment is shifted by the
cumulative offset. `continuous = raw + offset` (panama) or `raw × factor` (ratio); `none` keeps raw
prices and shows the roll gap.

Why forward instead of the common backward adjustment:

- **No look-ahead.** A backward series adjusted once, to the last contract, puts future roll gaps
  into past prices. Forward offsets only exist after the roll that creates them.
- **No indicator re-warm.** Past candles never change, so indicator state stays valid across a
  roll. Backward adjustment would force re-warming every indicator at every roll.
- **Deterministic across modes.** The offset of a contract is the sum of gaps from the **first
  contract in the catalog chain**, not from wherever a run started. A backtest from 2024 and a
  live session warmed up yesterday put the same contract at the same continuous level.

`PriceSpace` owns the mapping per contract: `toContinuous(raw)` and `limitToContract`/`stopToContract`
for levels. Distances need no mapping: the engine lowers brackets and trailing stops to absolute
levels before an order reaches a broker.
Contract-space levels are snapped to the tick grid in the direction that never improves the order
(buy limit down, sell limit up, buy stop up, sell stop down). Panama that would take any continuous
price ≤ 0 is refused at load with the advice to use `ratio`; ratio requires positive prices.

### 5.4 The continuous stream — data side

`ContinuousMarketSource : MarketSource` decorates the store (backtest) or the live feed. For a
continuous symbol it:

- reads the active contract's ticks (and the next contract's ticks from 48 hours before a roll, so
  the roll can be priced), per the roll schedule;
- emits each raw contract tick **unchanged** (so `MarketPriceTracker` has contract prices for fills
  and for venue marks);
- emits a continuous tick for the front contract (symbol `ROOT@front`, price/bid/ask mapped by the
  current offset), which candles, indicators, marks and P&L of the continuous stream read;
- for `--bars`, builds continuous bars from per-contract bars the same way.

Warmup reads the same source, so warmup history uses the same offsets. `--tick-fills` is refused
for continuous streams in phase 42 (clear error): tick slices would need contract-aware slicing.

### 5.5 The continuous stream — execution side

`ContinuousContractBroker : Broker` is the **single translation boundary** between the continuous
symbol and contract symbols. It wraps any contract-level broker: the exchange simulator in backtest,
the gateway broker (or Bybit linear broker) live. The same class runs in both modes.

- **Capabilities** are `MARKET, LIMIT, STOP, STOP_LIMIT, MODIFY`. The engine already lowers
  brackets, OCO, trailing and time exits into these when a broker does not declare them, so the
  decorator only ever translates four order shapes.
- **Submit:** resolves the stream's active contract, maps `limitPrice`/`stopPrice` into contract
  space (5.3), submits to the delegate with a derived venue order id, and remembers
  `clientOrderId → continuous request`.
- **Events:** the delegate publishes on a **private bus**; the decorator republishes each event on
  the engine bus with symbol and prices mapped back to continuous space. The engine never sees a
  contract symbol for a continuous stream.
- **Roll (carry):** at the roll instant, for the strategy's position on the old contract: market
  close on old, market open on new, same side and quantity; resting orders on the old contract are
  cancelled and re-submitted on the new one at the same continuous levels (new offset). The roll
  fills are **not** republished as engine fills (the continuous position did not change); the
  difference between fill prices and reference prices is booked as a roll cost through
  `NonExecutionAccounting` (`CostKind.SPREAD_COST`, reason `ROLL`). Continuous-space P&L plus roll
  cost equals the economic P&L of the two contract legs (derivation in the plan; pinned by a test).
- **Roll failure:** if the new leg is rejected or only partly fills, the decorator republishes a
  venue-close fill (`updatesOrderExecution = false`, reason `ROLL_FAILED`) for the quantity that is
  no longer held, so the engine's position equals reality, then raises a halt for that strategy.
- **Positions view:** `getOpenPositions()` maps delegate contract positions to the continuous
  symbol for reconciliation.

Strategies that must be flat through a roll say so in the DSL (`WHEN es.days_to_roll < 1 THEN
CLOSE es`, with a threshold longer than one bar: the bar closing at the roll already follows the new
contract); there is no hidden engine-side close.

### 5.6 Explicit contract streams and expiry

A strategy may name a contract directly (`BINANCE_UM:BTCUSDT_240927`). Then there is no roll:

- **Entry guard:** a pre-trade rule refuses orders that open or increase a position within
  `expiryGuardHours` (default 24) of expiry.
- **Settlement:** the exchange simulator closes any open position at `expiryMs` at the contract's
  delivery price (catalog), cancels resting orders on it, and publishes the close as a venue close
  (`updatesOrderExecution = false`, `ExitReason.EXPIRY`). Live, the venue settles and the gateway
  reports the same event. If no delivery price is known the last trade is used and the report flags
  it.

`ExitReason` gains `EXPIRY` and `ROLL_FAILED`. Existing `when (exitReason)` sites are updated (the
compiler forces it).

### 5.7 Exchange simulator (backtest)

`ExchangeSimulator : Broker` (new `BrokerKind.EXCHANGE_SIM`, `--broker exchange-sim`) composes a
`PaperBroker` on a private bus and adds exchange rules:

- netting only (`positionAccountingMode = NETTING`);
- quantity floored to `volumeStep`, rejected below `volumeMin` or above `volumeMax` (PaperBroker
  already does step/min; max is added here);
- prices of submitted limits/stops must sit on the tick grid (the decorator snaps them; a direct
  contract stream order off-grid is rejected with the offending price);
- market fills: at the touch (`buyExecPrice`/`sellExecPrice`) when the tick has a quote, else at the
  trade price plus `slippageTicks × tickSize` against the order (default 0, flag
  `--slippage-ticks`);
- expiry settlement (5.6).

`BrokerKind` gains a value: the `when` sites listed in the backtest map
(`ExecutionSimulation.kt:161,203`, `ReplayBroker.kt:32`, `TextReportPrinter.kt`, `ScenarioFile.kt`,
`BacktestContext.kt:317-322`) are updated. `replayBroker` selects per route: routes whose symbols
are futures/options get `ContinuousContractBroker(ExchangeSimulator)` (continuous) or
`ExchangeSimulator` (explicit contracts); every other route keeps today's factory, so CFD routes are
untouched.

### 5.8 Margin

`MarginModel` computes initial and maintenance margin for a position from `MarginTerms`
(`basis: perContract | notional`). A pre-trade rule `MarginRequirement` refuses an order when
`equity − marginUsed(after) < 0`; it sits in the shared pre-trade set of both `ReplayRisk` and live
risk assembly and judges only instruments with margin terms (one lookup otherwise). Would-be margin
calls (equity below total maintenance) are recorded per day in `margin_daily.csv`. Equity itself is
not changed (one writer).

### 5.9 Daily settlement

Equity in qkt is `balance + realized + unrealized`; daily variation margin moves cash between
realized and unrealized but does not change equity. Phase 42 therefore records daily settlement
prices for reporting and margin, and does **not** re-anchor leg entry prices (which would change
realized P&L per trade and the excursion keys). Crypto quarterlies have no daily settlement. The
divergence (cash balance vs venue statement) is catalogued.

### 5.10 Calendars

`CmeGlobexCalendar` (America/Chicago: Sunday 17:00 to Friday 16:00, daily halt 16:00–17:00;
exchange holidays not modelled, documented). The root's `calendar:` key selects the calendar for
its contracts and continuous streams. A run whose streams are **all** futures/options uses their
common calendar; any run containing a CFD keeps today's first-symbol resolution, so no CFD run
changes.

### 5.11 DSL

- **Stream selector:** `es = BINANCE_UM:BTCUSDT@front EVERY 5m` and `@next`. `StreamDeclParser`
  accepts an optional `AT_SIGN IDENT` after the symbol and stores it **inside `symbol`**
  (`"BTCUSDT@front"`). `StreamDecl`, `HubKey` and the AST shape are unchanged, so fingerprints of
  existing strategies are unchanged. Unknown selectors are parse errors.
- **Stream fields** (added to `DslVocabulary.candleFields`, evaluated in `StreamFieldCompiler`,
  `Undefined` on non-futures streams — `DslVocabularyTest` compiles every field on a plain stream):
  `contract` (string), `dte` (days to expiry of the active contract, decimal),
  `days_to_roll` (decimal), `tick_value` (`tick_size × contract_size`).
- **Meta fields:** `multiplier` (alias of `contract_size`).
- Vocabulary JSON, LSP completion, hover docs and the generated editor grammars are regenerated in
  the same change (the drift tests require it).

### 5.12 Reports

Conditional artifacts, following the `bookRisk`/`monteCarlo` pattern (nullable on
`BacktestResult`, emitted in exactly the writer, `ResultJson.renderArtifacts` and
`ReportManifest`), so CFD reports are byte-identical:

- `rolls.csv`: `time,stream,from,to,fromPrice,toPrice,gap,quantity,rollCost`
- `contracts.csv`: per fill of a continuous stream, the contract it executed on
- `margin_daily.csv`: `date,marginUsed,maintenance,equity,marginCall`
- `settlements.csv` (options/expiries): `time,symbol,deliveryPrice,quantity,realized`

`trades.csv` is not changed.

### 5.13 Free data

| Source | What | Command |
|---|---|---|
| `data.binance.vision` (no key) | per-contract klines (1m–1d) for USDⓈ-M quarterlies since 2021 | `qkt fetch BINANCE_UM:BTCUSDT_240927 --tf 1m --from … --to …` |
| `fapi.binance.com/futures/data/delivery-price` (no key) | delivery price per quarterly, matched by UTC date | `qkt fetch BINANCE_UM:BTCUSDT --catalog` |
| S3 listing of `data.binance.vision` | which quarterlies exist | same `--catalog` command |

Bars are the phase-42 data: the continuous series and the roll gap are defined on 1m bars. Trades →
ticks (`--ticks`) is deferred to the phase that adds tick fills for futures. Findings from the first
real fetch (delivery timestamps at 00:00 UTC, flat bars after delivery, headerless pre-2022 files) are
in `docs/research/2026-09-30-binance-quarterly-free-data.md`; bars stamped at or after a contract's
expiry are ignored everywhere.

## 6. Options

### 6.1 Instruments

`OptionTerms(underlying: String, strike: BigDecimal, right: CALL|PUT, style: EUROPEAN,
settlement: CASH)`. Option contract symbols come from the chain catalog
(`<dataRoot>/chains/<VENUE>/<UNDERLYING>/...`); roots declared under a new `options:` list in
`instruments.yaml` (multiplier, tick, currency, pricing model, settlement index).

### 6.2 Pricing (core, deterministic, `derivatives/options/pricing`)

- `Black76` (options on futures/forwards) and `BlackScholes` (spot underlying with a forward):
  price, delta, gamma, vega, theta, rho. `Double`, closed form, with `NormalDistribution` (CDF by
  a published rational approximation with absolute error < 7.5e-8, cited in KDoc).
- `ImpliedVolatility`: Newton on vega with a bisection fallback inside `[1e-4, 5.0]`; returns
  `null` for prices outside no-arbitrage bounds or non-convergence. Never 0.
- Time to expiry in years = `(expiryMs − nowMs) / (365 × 24 × 3600 × 1000)`; rates are an explicit
  input (default 0 for USDC crypto options, stated).

### 6.3 Chain snapshots

A `ChainSnapshot` is every contract of one underlying at one instant: bid, ask, sizes, mark,
underlying price, open interest. Stored per day as
`<dataRoot>/chains/<VENUE>/<UNDERLYING>/<YYYY-MM-DD>.csv.gz` with a fixed header. Free source:
Tardis `options_chain` CSVs for the first day of each month (no key). Live: the gateway streams
quotes and a `ChainSnapshotter` samples "last quote at or before t" on the same boundaries, then
writes the file — so live builds its own history.

### 6.4 DSL

- **Chain analytics as a stream:** `iv = DERIBIT:BTC_USDC.atm_iv.30d EVERY 1h`. The parser already
  accepts dotted names for `HUB`; it accepts them for any broker (an existing file never has a dot
  after the symbol, so this is additive). The resolver maps `<underlying>.<metric>.<tenor>` to a
  synthetic candle stream fed by the chain (same pattern as `SERIES ACCOUNT.EQUITY`). Metrics:
  `atm_iv`, `skew_25d`, `put_call_oi`. Every existing indicator applies.
- **Structures:** `OPEN ps = OPTIONS ON btc { SELL PUT DELTA 0.25 DTE 30 TO 45, BUY PUT DELTA 0.10
  SAME EXPIRY } SIZING 1 PCT RISK` — a new action node (new sealed subtype; existing ASTs are
  unaffected), words matched contextually. Legs are resolved at bar close by one deterministic
  selector over the latest snapshot (nearest expiry in the DTE range, then nearest delta, ties to
  the lower strike).
- **Position fields:** `POSITION.ps.delta|gamma|vega|theta|dte|credit|max_loss|pnl_pct`.

### 6.5 Execution, expiry, risk

- Legs are submitted as separate contract orders in phase 43 (no native combo), in one engine
  step, with an exposure check that flattens filled legs if a later leg rejects.
- Fills at the **next** snapshot's bid/ask after the decision (never the snapshot the decision was
  made on, which would be look-ahead). Never at mid.
- Expiry: cash settlement at the venue delivery price (Deribit `get_delivery_prices`), published
  as a venue close with `ExitReason.EXPIRY`.
- `PCT RISK` for a structure divides by its max loss from `StructurePayoff` (piecewise-linear
  expiry payoff). Undefined-risk structures (naked short options) are refused for `PCT RISK` sizing.
- Marks: mid clamped to `[bid, ask]`; when a quote is missing or one-sided (bid 0), the model price
  from the last IV. Option quotes do not pass through `TickIngest` (bid 0 is legal for deep OTM
  options and would be dropped as malformed).

## 7. Live: venue gateway client (phase 44)

`connector/gateway` implements `Connector`/`TradingAccount` for `type: gateway`, with
`gateway_url`, `api_key`, `expected_adapter`, `expected_account_login`, `expected_trade_mode`
settings (same names as MT5 entries where they mean the same thing). It speaks VGP v1 (the
2026-09-18 research doc, §6): REST for commands and snapshots, one WebSocket stream with sequence
numbers and `since=` resume, idempotent submits on `client_order_id`. One translator
(`GatewayEventTranslator`) builds engine events from wire events. Tested against an in-process fake
gateway (`MockWebServer`). Futures routes wrap it in `ContinuousContractBroker` exactly as in
backtest.

## 8. CFD protection (phase 42.0, before any futures code)

- **Golden backtests:** a CI-run test replays a fixed corpus (the Dukascopy EURUSD fixture day,
  seeded synthetic tapes, the shipped example and attestation strategies that run offline) through
  `paper` and `mt5-sim`, and compares a SHA-256 of the normalized `qkt-backtest-result-v1` JSON and
  the trade tape against committed hashes. Normalization removes only `gitSha`, `version` and
  wall-clock fields. Any change fails CI and names the strategy.
- **Fingerprint pin:** the same corpus asserts each strategy's `strategyFingerprint` equals its
  committed value.
- **Parse pin:** every `.qkt` in the repo parses to an AST whose `toString()` hash equals its
  committed value.
- Hashes are generated from `origin/dev` before phase 42.1 starts and never regenerated inside
  this program without an explicit, reviewed reason.

## 9. Code layout

```
derivatives/
  futures/    RollSchedule, RollPrices, AdjustmentChain, PriceSpace
  options/    OptionTerms, OptionRight, ChainSnapshot, ChainSnapshotStore, OptionSelector,
              Structure, StructurePayoff
  options/pricing/  NormalDistribution, Black76, BlackScholes, ImpliedVolatility, Greeks
instrument/   DerivativeTerms, MarginTerms, FuturesRoot, RollPolicy, PriceAdjustment, ContractCatalog,
              ContractCatalogRegistry, ContinuousSelector
accounting/margin/  MarginModel, MarginRequirement (risk rule lives in risk/rules)
broker/exchange/    ExchangeSimulator, ExpirySettlement
broker/continuous/  ContinuousContractBroker, ContinuousOrderMap, RollExecutor
marketdata/source/  ContinuousMarketSource, ContinuousTickStitcher
marketdata/store/binance/  BinanceVisionClient, BinanceKlineCsv, BinanceQuarterly, BinanceContractCatalog
cli/fetch/                 BinanceUmFetcher, CatalogFetch
marketdata/store/tardis/   TardisChainFetcher
connector/gateway/  (phase 44)
```

Each name is one file under 200 lines, one concept, KDoc on every public member.

## 10. Phases and acceptance

| Phase | Delivers | Accepted when |
|---|---|---|
| 42.0 | golden backtest, fingerprint and parse pins | CI job green on current dev; a deliberate one-line behaviour change makes it fail |
| 42.1 | instrument model, catalog, explicit currency, fee model | pins unchanged; unit tests for registry layering, currency, fees |
| 42.2 | free data: Binance fetchers, catalog builder, delivery prices | `qkt fetch` writes per-contract bars/ticks and a catalog from live endpoints; completeness checks pass |
| 42.3 | contract codes, chain, roll policy/schedule, price adjustment, resolver | property tests: offsets anchored, no look-ahead, snapping never improves |
| 42.4 | exchange simulator, expiry settlement, margin rule, CME calendar | simulator unit tests incl. expiry; pins unchanged |
| 42.5 | continuous market source + continuous contract broker + roll carry | end-to-end backtest of a BTCUSDT@front strategy across ≥3 rolls on real free data; P&L identity test (continuous + roll cost = contract legs) |
| 42.6 | DSL selector and fields, reports | parser/vocabulary/grammar tests; report files present only for futures runs |
| 43.x | options pricing, chains, DSL, execution, expiry | pricing matches published reference values; a structure backtest on a Tardis day |
| 44.x | gateway connector | fake-gateway tests: submit, fill, reject, cancel race, reconnect with `since`, kill switch |

Each phase: `./gradlew build` green (ktlint, file-size guard, all tests), the pins unchanged, the
divergence catalog updated, KDoc on public API, phase changelog on merge.

## 11. Edge-case register

| # | Case | Resolution |
|---|---|---|
| E1 | Futures symbol without meta | fail fast at start (kind-based guard, not suffix-based) |
| E2 | Symbol with characters unsafe for file names | refused when the symbol is built |
| E3 | Contract with no data in the window | continuous source skips to the next contract with data; explicit stream fails with the window |
| E4 | Next contract unpriced at roll time | roll waits for first tick; settle-and-halt if front expires first (5.2) |
| E5 | Roll instant in closed session | first tick with both contracts in session (5.2) |
| E6 | Resting orders at roll | cancelled and re-placed on the new contract at the same continuous level (5.5) |
| E7 | Roll open leg rejected / partial | venue-close fill for the unheld quantity + halt (5.5) |
| E8 | Panama drives prices ≤ 0 | refused at load; use ratio (5.3) |
| E9 | Ratio adjustment and distances | distances scale by 1/factor; snapping never improves (5.3) |
| E10 | Off-grid price after mapping | snapped conservatively; direct off-grid order rejected (5.3, 5.7) |
| E11 | Position reaches expiry | settled at delivery price, `ExitReason.EXPIRY` (5.6) |
| E12 | New entry on a contract near expiry | refused within the guard window (5.6) |
| E13 | Size rounds to zero contracts | rejected with the existing "below venue volumeMin" reason; never rounded up |
| E14 | Mixed CFD + futures run | CFD routes keep today's broker and calendar; futures routes get the exchange stack (5.7, 5.10) |
| E15 | Multiple strategies on one continuous stream | one decorator per strategy (the broker factory is per strategy); each rolls its own position |
| E16 | Warmup starts before the first catalog contract | fails with the first available date |
| E17 | `--tick-fills` with a continuous stream | refused with a clear error (5.4) |
| E18 | Inverse or American contracts | refused at load (1) |
| E19 | Option quote with bid 0 | legal; not routed through `TickIngest`; mark falls back to model price (6.5) |
| E20 | IV solver fails | `Undefined` in the DSL, never 0 (6.2) |
| E21 | Structure leg rejected after others filled | filled legs flattened in the same step (6.5) |
| E22 | Live roll gap differs from later stored data | live gap persisted to `.rolls.json` and reused (5.2) |
| E23 | CFD strategy fingerprint or report bytes change | CI golden pins fail (8) |
| E24 | New YAML keys read by an old binary | ignored (verified loader behaviour) |
| E25 | Persisted legbook read after adding fields | nullable fields omitted when null; old files load (2) |

## 12. Divergences to catalogue

- Futures daily variation margin not booked as cash (5.9).
- Quarterly crypto backtests fill at trade price + slippage ticks; live fills at the book.
- Live roll gap measured from the live feed vs stored trades (mitigated by E22).
- Exchange holidays not modelled in `CmeGlobexCalendar`.
