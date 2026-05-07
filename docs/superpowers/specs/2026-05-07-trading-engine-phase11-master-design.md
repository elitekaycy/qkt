# Phase 11 — DSL Master Design

**Status:** Design draft. Master document spanning sub-phases 11b–11f.
**Predecessor:** Phase 10c (walk-forward), Phase 11a (OSS posture).
**Successor:** Phase 12 (CLI runner with observability port — deferred, out of scope here).

---

## 1. Mission

Phase 11 puts a **declarative strategy language** in front of the engine so a quant can describe *what* a strategy is — symbols, indicators, conditions, orders — without writing imperative event-handler Kotlin. The DSL compiles to the same `Strategy` interface we already run live and in backtest. There is no second runtime.

The language is opinionated and deliberately small. It is rule-based, not procedural: a strategy is a set of `WHEN <condition> THEN <action>` rules over named streams. Cross-symbol, cross-timeframe, cross-broker conditions are first-class. Snapshot semantics (the `@` operator) make capturing values at decision-time natural. Order types (limit, stop, trailing, bracket, OCO) and sizing modes (quantity, notional, percent-of-equity, risk-based) are full citizens. A standard library of constants and a `DEFAULTS` block keep the prose short.

Two front-ends share one AST:
- **Internal Kotlin DSL** — type-safe, IDE-friendly, used in tests and as the canonical bring-up surface.
- **External SQL-like parser** — text files (`.qkt`) loadable from disk, intended for quants who don't write Kotlin.

Both produce a `StrategyAst` value. A single compiler walks that AST and emits a `Strategy` instance bound to streams, indicators, and the runtime engine.

Phase 11 ships in five sub-phases (11b–11f). Each is independently shippable and merged green. Phase 12 (a `qkt run foo.qkt` CLI with an observability port) is **not** in scope and is left for after 11f lands.

---

## 2. Goals and non-goals

### Goals

- A declarative DSL whose surface area maps cleanly onto the engine's existing event types (ticks, candles, signals, orders, fills, trades).
- A single AST shared by both front-ends. The AST is the contract; front-ends are interchangeable.
- Multi-symbol, multi-timeframe, multi-broker strategies expressible in one file.
- First-class snapshot semantics so a quant can write "remember RSI at the moment of buy" without boilerplate.
- Production order surface (limit, stop, stop-limit, trailing, bracket, OCO, TIF) and production sizing (qty, notional, %-of-equity, risk-based with stop distance).
- Compiled DSL strategies are deterministic and bit-identical-to-handwritten given the same inputs and clock.
- Each sub-phase ends in a state where the engine can run real strategies written in the DSL — no half-shipped surfaces.

### Non-goals

- **No iterative loops in user code.** `FOR EACH` exists as a parse-time macro that expands into multiple rules; there is no `WHILE`, no recursion, no DSL-level state machines.
- **No mutable state declared by the user.** `LET` declares aliases and snapshots; it does not create variables the strategy reassigns.
- **No event-handler escape hatch.** A strategy author cannot drop into Kotlin event callbacks from inside a `.qkt` file. If the DSL can't express it, the strategy is hand-written or the DSL grows.
- **No DSL-level orchestration of multiple strategies.** One file, one strategy. Multi-strategy composition stays at the engine level (`TradingPipeline`).
- **No I/O from the DSL.** No HTTP calls, no file reads, no shell-out. Pure expressions over engine state.
- **No turing-completeness.** The DSL is intentionally weaker than a general-purpose language. If you need a turing-complete escape, write Kotlin.
- **No CLI runner / observability port in this phase.** That is Phase 12.
- **No backtest-only or live-only constructs.** Anything you can write in the DSL must work in both.

---

## 3. Worked example

A complete, realistic strategy in the external syntax, exercising most of the surface area:

```
STRATEGY momentum_basket VERSION 1

DEFAULTS {
    SIZING       = RISK ONE_PERCENT
    STOP_LOSS    = BY ATR(SYMBOL, 14) * 2
    TAKE_PROFIT  = RR 3
    TIF          = GTC
    ORDER_TYPE   = LIMIT AT close - 0.001
}

SYMBOLS
    btc    = BYBIT:BTCUSDT       EVERY 1m,
    btc_h1 = BYBIT:BTCUSDT       EVERY 1h,
    gold   = INTERACTIVE:XAUUSD  EVERY 15m,
    aapl   = ALPACA:AAPL         EVERY 5m

LET
    fast      = EMA(close, 9),
    slow      = EMA(close, 21),
    momentum  = (fast - slow) / slow,
    vol_ok    = ATR(SYMBOL, 14) > 0.005 * close

RULES
    FOR EACH s IN [btc, gold, aapl] DO
        WHEN s.fast CROSSES ABOVE s.slow
         AND s.vol_ok
         AND ACCOUNT.drawdown < FIVE_PERCENT
        THEN BUY s
            SIZING RISK ONE_PERCENT
            BRACKET {
                STOP LOSS    BY ATR(s, 14) * 2,
                TAKE PROFIT  RR 3
            }

    WHEN POSITION.btc > 0
     AND btc.momentum < 0
     AND btc_h1.fast < btc_h1.slow
    THEN SELL btc
        SIZING POSITION.btc

    WHEN ACCOUNT.drawdown >= TEN_PERCENT
    THEN CLOSE_ALL
```

Every construct in this example resolves to AST nodes defined in §6.

---

## 4. Architecture

```
                              +-----------------------------+
   .qkt file (external) ----> |   SqlLikeParser   (Phase 11f) |
                              +--------------+--------------+
                                             |
                                             v
   Kotlin DSL builders ---->  StrategyAst (single shared IR, defined in Phase 11b)
   (Phase 11b)                                |
                                             v
                              +-----------------------------+
                              |   AstCompiler  (Phase 11b)  |
                              +--------------+--------------+
                                             |
                                             v
                              +-----------------------------+
                              |   Strategy (existing)       |
                              +--------------+--------------+
                                             |
                                             v
                              TradingPipeline / Backtest / Engine
```

Components, by package:

- `com.qkt.dsl.ast` — pure data classes and sealed hierarchies forming the AST.
- `com.qkt.dsl.kotlin` — the internal Kotlin DSL (builders, infix operators, type-safe constructors of AST nodes).
- `com.qkt.dsl.compile` — `AstCompiler`, expression evaluators, indicator wiring, rule firing logic. Produces a `Strategy`.
- `com.qkt.dsl.parse` — the external SQL-like parser (lexer, parser, AST emitter). Phase 11f only.
- `com.qkt.dsl.stdlib` — predefined constants (`ONE_PERCENT`, `BPS`, etc.) and indicator name resolution.
- Indicator implementations remain where they are (`com.qkt.indicators.*`); the DSL only references them by name.

The AST is the only contract. Front-ends are isolated from the runtime; the runtime is isolated from the parser.

---

## 5. Language surface

This section enumerates the full DSL surface. AST-level definitions are in §6. Sub-phase decomposition (which slice of this surface ships when) is in §7.

### 5.1 Streams

A stream is a `(broker, symbol, timeframe)` triple bound to a name.

```
SYMBOLS
    btc    = BYBIT:BTCUSDT       EVERY 1m,
    btc_h1 = BYBIT:BTCUSDT       EVERY 1h,
    gold   = INTERACTIVE:XAUUSD  EVERY 15m,
    aapl   = ALPACA:AAPL         EVERY 5m
```

- The same physical symbol can appear under multiple stream names with different timeframes.
- The same instrument can appear under multiple brokers.
- The keyword `SYMBOLS` is preserved (per elitekaycy's preference) even though semantically each entry is a stream.
- Timeframes use the existing `TimeWindow` vocabulary: `1s`, `1m`, `5m`, `15m`, `1h`, `1d`, etc.
- Brokers (`BYBIT`, `INTERACTIVE`, `ALPACA`, `BACKTEST`) resolve through the `CompositeBroker` registry from Phase 7e.

### 5.2 Series accessors

A stream `s` exposes time series:

| Accessor | Meaning |
|---|---|
| `s.close` | most recent close (realtime if subscribed at tick cadence; latest closed candle otherwise) |
| `s.open` | open of the current candle |
| `s.high` | high so far in the current candle |
| `s.low` | low so far in the current candle |
| `s.volume` | volume in the current candle |
| `s.bid` | best bid (tick-cadence streams only) |
| `s.ask` | best ask (tick-cadence streams only) |
| `s.price` | shorthand for `s.close` |

Bare accessor without stream prefix (`close`, `high`, `low`, `volume`) is legal **only** inside a `FOR EACH s IN [...] DO` block, where it refers to the iteration variable.

### 5.3 Indicators

Indicators are named functions over series.

```
EMA(s.close, 9)
RSI(s.close, 14)
ATR(s, 14)                       -- whole-stream form (uses HLC)
RSI(ATR(s, 14), 5)               -- composition: indicator over indicator
```

Composition rules:
- Any indicator that consumes a single series can take another indicator's output as input.
- The compiler validates this at AST → Strategy time, not at parse time.
- Indicators are resolved by name through a registry. Adding a new indicator requires registering it in `com.qkt.dsl.stdlib`; no parser change.

### 5.4 References, snapshots, and the `@` operator

`LET` introduces a name for an expression:

```
LET fast = EMA(s.close, 9)
```

Reading the name evaluates the expression at the point of use:

| Form | Semantics |
|---|---|
| `fast` | realtime — re-evaluated every time the rule is checked |
| `@fast` | realtime — same as bare; the `@` is purely cosmetic emphasis |
| `fast@buy` | snapshot — the value of `fast` captured at the moment the most recent `BUY` action fired for the relevant symbol |
| `fast@sell` | snapshot — same, but for `SELL` |
| `fast@open` | snapshot — value at the moment the current position opened (works for any direction) |
| `fast@T-N` | snapshot — value `N` candles ago on the relevant stream |

Snapshots are stored per-symbol per-rule-firing in compiler-managed state; the user never declares storage.

### 5.5 Running aggregates

```
MAX(s.high) SINCE OPEN
MIN(s.low)  SINCE OPEN
MEAN(s.close) SINCE T-20
SUM(s.volume) SINCE OPEN
```

- `SINCE OPEN` resets when a position opens on the relevant symbol.
- `SINCE T-N` is a trailing window of `N` candles.
- Compiler validates that `MIN` / `MAX` operate over a numeric series.

### 5.6 Constants

Scalar literals: integers, decimals, strings (for symbols/labels only).

Named constants from the standard library (`com.qkt.dsl.stdlib.Constants`):

| Name | Value |
|---|---|
| `HALF_PERCENT` | 0.005 |
| `ONE_PERCENT` | 0.01 |
| `TWO_PERCENT` | 0.02 |
| `THREE_PERCENT` | 0.03 |
| `FIVE_PERCENT` | 0.05 |
| `TEN_PERCENT` | 0.10 |
| `QUARTER_PERCENT` | 0.0025 |
| `BPS` | 0.0001 |

User-defined constants live in the same `LET` namespace as snapshots and indicator aliases.

### 5.7 Conditions

Comparison operators: `>`, `<`, `>=`, `<=`, `==`, `!=`.

Range and membership: `BETWEEN <lo> AND <hi>`, `IN [list]`.

Cross detection: `CROSSES ABOVE`, `CROSSES BELOW` — operate on two series, fire on the bar where the lhs transitions across the rhs.

Boolean composition: `AND`, `OR`, `NOT`. Operator precedence follows SQL conventions: `NOT` > `AND` > `OR`. Parentheses override.

Conditional expressions:

```
CASE WHEN cond1 THEN expr1
     WHEN cond2 THEN expr2
     ELSE       exprN
END
```

The `CASE WHEN` form is an expression, not a statement. It evaluates to a numeric or boolean value usable in any expression context.

### 5.8 Math

Operators: `+`, `-`, `*`, `/`. Standard precedence. No integer division — `/` always produces a decimal.

Functions (`com.qkt.dsl.stdlib`): `ABS(x)`, `MIN(x, y, ...)`, `MAX(x, y, ...)`, `LOG(x)`, `SQRT(x)`. The variadic forms of `MIN`/`MAX` are distinct from the running-aggregate form (which takes a series and a `SINCE` clause).

### 5.9 Engine state accessors

The DSL exposes a read-only view of engine state:

| Accessor | Meaning |
|---|---|
| `ACCOUNT.equity` | current total equity (cash + unrealized P&L) |
| `ACCOUNT.balance` | cash balance |
| `ACCOUNT.realized_pnl` | sum of closed-trade P&L since start |
| `ACCOUNT.unrealized_pnl` | mark-to-market on open positions |
| `ACCOUNT.drawdown` | current drawdown as a fraction of peak equity |
| `POSITION.<sym>` | signed quantity of position on stream `sym` (long positive, short negative, flat zero) |
| `POSITION_AVG_PRICE.<sym>` | volume-weighted average entry price for the open position |
| `OPEN_ORDERS.<sym>` | count of working orders on stream `sym` |

These are pure reads. The DSL never writes to engine state directly; it does so through actions.

### 5.10 Rules

A rule is `WHEN <condition> THEN <action>`. The block under `RULES` is an unordered list of rules. Rules fire on each engine tick (or candle close, depending on the strategy's cadence) in declaration order; the order matters only for actions with shared side effects (e.g. `CLOSE_ALL` must run last if it's mixed with `BUY` rules, or it cancels them).

`FOR EACH` is a parse-time macro:

```
FOR EACH s IN [btc, gold, aapl] DO
    WHEN s.fast CROSSES ABOVE s.slow
    THEN BUY s
```

This expands at AST construction time into three independent rules — one per stream — each with `s` substituted by the literal stream reference. There is no runtime iteration.

### 5.11 Actions

| Action | Effect |
|---|---|
| `BUY <stream> [SIZING ...] [ORDER_TYPE ...] [TIF ...] [BRACKET {...}] [OCO {...}]` | submit a buy |
| `SELL <stream> [SIZING ...] [ORDER_TYPE ...] [TIF ...] [BRACKET {...}] [OCO {...}]` | submit a sell |
| `CLOSE <stream>` | flatten the position on `<stream>` (market order at full size) |
| `CLOSE_ALL` | flatten every open position |
| `CANCEL <stream>` | cancel all working orders on `<stream>` |
| `CANCEL_ALL` | cancel every working order |
| `LOG "<message>"` | append a structured log entry (uses the engine's existing SLF4J logger) |

Actions that are missing optional clauses inherit from the strategy's `DEFAULTS` block.

### 5.12 Order types

```
ORDER_TYPE = MARKET                                        -- default if neither set
ORDER_TYPE = LIMIT AT <expr>
ORDER_TYPE = STOP  AT <expr>
ORDER_TYPE = STOP  AT <stop-expr>  LIMIT AT <limit-expr>   -- stop-limit
ORDER_TYPE = TRAILING BY <expr>                            -- trailing stop, distance from current price
ORDER_TYPE = TRAILING PCT <expr>                           -- trailing stop, fractional distance
```

`AT` takes an absolute price expression. `BY` takes a distance from the entry/current price. `PCT` takes a fraction. These three modes are the universal forms the compiler validates against.

### 5.13 Bracket and OCO

```
BRACKET {
    STOP LOSS    AT <expr>              -- absolute price
    STOP LOSS    BY <expr>              -- distance from entry
    STOP LOSS    PCT <expr>             -- fractional distance
    TAKE PROFIT  AT <expr>
    TAKE PROFIT  BY <expr>
    TAKE PROFIT  PCT <expr>
    TAKE PROFIT  RR <multiplier>        -- risk:reward multiple of stop distance
}

OCO {
    STOP   AT <expr>
    LIMIT  AT <expr>
}
```

`BRACKET` attaches a stop-loss and a take-profit child to the parent fill. `OCO` is two opposing orders linked so the fill of one cancels the other.

`RR <m>` computes take-profit price as `entry + sign * m * stop_distance`, where `sign` is `+1` for longs and `-1` for shorts.

### 5.14 Sizing

```
SIZING <expr>                       -- direct quantity
SIZING <expr> USD                   -- notional
SIZING <expr> % OF EQUITY           -- percent of equity
SIZING <expr> % OF BALANCE          -- percent of cash balance
SIZING RISK <fraction>              -- fraction of equity at risk if stop hits
SIZING RISK $ <amount>              -- absolute dollars at risk
SIZING POSITION.<sym>               -- close at full position size
```

Risk-based sizing requires a stop-loss to be defined (either via `BRACKET STOP LOSS` or via the `DEFAULTS.STOP_LOSS` clause). The compiler errors at AST → Strategy time if `RISK` is used without a resolvable stop distance.

Quantity computation for `RISK r`:
```
qty = (equity * r) / abs(entry_price - stop_price)
```

### 5.15 Time-in-force

```
TIF = GTC      -- good till cancelled (default)
TIF = IOC      -- immediate or cancel
TIF = FOK      -- fill or kill
TIF = DAY      -- expires at end of session
TIF = GTD <timestamp-expr>
```

### 5.16 DEFAULTS block

The optional `DEFAULTS` block sets fallbacks inherited by every action that doesn't override them.

```
DEFAULTS {
    SIZING       = RISK ONE_PERCENT
    STOP_LOSS    = BY ATR(SYMBOL, 14) * 2
    TAKE_PROFIT  = RR 3
    TIF          = GTC
    ORDER_TYPE   = LIMIT AT close - 0.001
    TRAILING     = PCT HALF_PERCENT
}
```

Within `DEFAULTS`, the bare identifier `SYMBOL` is a placeholder bound at expansion time to the stream the action targets.

### 5.17 Strategy header

```
STRATEGY <name> VERSION <integer>
```

`STRATEGY <name>` becomes the `strategyId` field on emitted trades, the same field Phase 9 already attributes per-strategy P&L to. `VERSION <integer>` is a monotonic integer; bumping it is the convention for "this strategy file changed in a way that invalidates prior backtests." The compiler does nothing with it at runtime; reporting and tooling can use it.

---

## 6. AST sketch

The AST lives in `com.qkt.dsl.ast`. All nodes are `data class` or `sealed interface`. Sketch only — exact shape is finalized in 11b.

```kotlin
data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
)

data class StreamDecl(val alias: String, val broker: String, val symbol: String, val timeframe: String)
data class ConstantDecl(val name: String, val value: Number)
data class LetDecl(val name: String, val expr: ExprAst)

sealed interface ExprAst
data class NumLit(val value: BigDecimal) : ExprAst
data class BoolLit(val value: Boolean) : ExprAst
data class Ref(val name: String, val snapshot: SnapshotKind?) : ExprAst
data class StreamFieldRef(val stream: String, val field: String) : ExprAst
data class IndicatorCall(val name: String, val args: List<ExprAst>) : ExprAst
data class BinaryOp(val op: BinOp, val lhs: ExprAst, val rhs: ExprAst) : ExprAst
data class UnaryOp(val op: UnOp, val arg: ExprAst) : ExprAst
data class CmpOp(val op: Cmp, val lhs: ExprAst, val rhs: ExprAst) : ExprAst
data class Between(val v: ExprAst, val lo: ExprAst, val hi: ExprAst) : ExprAst
data class InList(val v: ExprAst, val members: List<ExprAst>) : ExprAst
data class Crosses(val direction: CrossDir, val lhs: ExprAst, val rhs: ExprAst) : ExprAst
data class CaseWhen(val branches: List<Pair<ExprAst, ExprAst>>, val elseExpr: ExprAst) : ExprAst
data class Aggregate(val fn: AggFn, val series: ExprAst, val window: Window) : ExprAst
data class AccountRef(val field: String) : ExprAst
data class PositionRef(val stream: String) : ExprAst
data class StateAccessor(val source: StateSource, val key: String) : ExprAst

sealed interface SnapshotKind
data object SnapshotBuy : SnapshotKind
data object SnapshotSell : SnapshotKind
data object SnapshotOpen : SnapshotKind
data class SnapshotTPast(val n: Int) : SnapshotKind

sealed interface RuleAst
data class WhenThen(val cond: ExprAst, val action: ActionAst) : RuleAst

sealed interface ActionAst
data class Buy(val stream: String, val opts: ActionOpts) : ActionAst
data class Sell(val stream: String, val opts: ActionOpts) : ActionAst
data class Close(val stream: String) : ActionAst
data object CloseAll : ActionAst
data class Cancel(val stream: String) : ActionAst
data object CancelAll : ActionAst
data class Log(val message: String) : ActionAst

data class ActionOpts(
    val sizing: SizingAst?,
    val orderType: OrderTypeAst?,
    val tif: TifAst?,
    val bracket: BracketAst?,
    val oco: OcoAst?,
)

sealed interface SizingAst
data class SizeQty(val expr: ExprAst) : SizingAst
data class SizeNotional(val usd: ExprAst) : SizingAst
data class SizePctEquity(val frac: ExprAst) : SizingAst
data class SizePctBalance(val frac: ExprAst) : SizingAst
data class SizeRiskFrac(val frac: ExprAst) : SizingAst
data class SizeRiskAbs(val usd: ExprAst) : SizingAst
data class SizePositionFull(val stream: String) : SizingAst

sealed interface OrderTypeAst
data object Market : OrderTypeAst
data class Limit(val price: ExprAst) : OrderTypeAst
data class Stop(val price: ExprAst) : OrderTypeAst
data class StopLimit(val stopPrice: ExprAst, val limitPrice: ExprAst) : OrderTypeAst
data class TrailingBy(val distance: ExprAst) : OrderTypeAst
data class TrailingPct(val frac: ExprAst) : OrderTypeAst

sealed interface ChildPriceAst
data class ChildAt(val price: ExprAst) : ChildPriceAst
data class ChildBy(val distance: ExprAst) : ChildPriceAst
data class ChildPct(val frac: ExprAst) : ChildPriceAst
data class ChildRr(val multiplier: ExprAst) : ChildPriceAst

data class BracketAst(val stopLoss: ChildPriceAst?, val takeProfit: ChildPriceAst?)
data class OcoAst(val stop: ChildPriceAst, val limit: ChildPriceAst)

sealed interface TifAst
data object Gtc : TifAst
data object Ioc : TifAst
data object Fok : TifAst
data object Day : TifAst
data class Gtd(val until: ExprAst) : TifAst

data class DefaultsBlock(
    val sizing: SizingAst?,
    val orderType: OrderTypeAst?,
    val tif: TifAst?,
    val stopLoss: ChildPriceAst?,
    val takeProfit: ChildPriceAst?,
    val trailing: OrderTypeAst?,
)

enum class BinOp { ADD, SUB, MUL, DIV, AND, OR }
enum class UnOp  { NEG, NOT }
enum class Cmp   { GT, LT, GE, LE, EQ, NE }
enum class CrossDir { ABOVE, BELOW }
enum class AggFn { MIN, MAX, MEAN, SUM }
enum class StateSource { ACCOUNT, POSITION, POSITION_AVG_PRICE, OPEN_ORDERS }

sealed interface Window
data object SinceOpen : Window
data class SinceTPast(val n: Int) : Window
```

This is a sketch — final names and shapes are settled in 11b.

---

## 7. Sub-phase decomposition

Each sub-phase ends with a working engine. Tests at the boundary of each sub-phase exercise full DSL strategies end-to-end against the existing backtest harness.

### Phase 11b — Foundation (~25 tasks)

**Goal:** Internal Kotlin DSL → AST → compiler → runnable Strategy. Single broker, single timeframe, single symbol. Market orders only. Fixed-quantity sizing. The minimal slice that proves the architecture.

**Surface:**
- `STRATEGY <name>` header
- `SYMBOLS` block with one stream
- `LET` for indicator aliases (no snapshots yet)
- Series accessors: `close`, `open`, `high`, `low`, `volume`
- One-symbol indicator calls: `EMA`, `RSI`, `ATR` (the three already implemented)
- Comparisons (`>`, `<`, `>=`, `<=`, `==`, `!=`)
- Boolean composition (`AND`, `OR`, `NOT`)
- Math (`+`, `-`, `*`, `/`)
- Numeric literals
- `WHEN ... THEN BUY <s>` and `WHEN ... THEN SELL <s>`
- Direct quantity sizing (`SIZING <expr>`)
- Market-order execution only

**Deliverables:**
- `com.qkt.dsl.ast` package with the full AST sketch from §6.
- `com.qkt.dsl.kotlin` package with the Kotlin DSL builders (no `LetDecl` snapshots yet).
- `com.qkt.dsl.compile.AstCompiler` returning `Strategy`.
- `com.qkt.dsl.stdlib.Constants` with `ONE_PERCENT` family.
- `com.qkt.dsl.stdlib.IndicatorRegistry` with `EMA`, `RSI`, `ATR` registered.
- Tests: round-trip a Kotlin DSL → AST → Strategy → backtest result equal to the equivalent hand-written Strategy.

**Out of scope for 11b:** snapshots, `CASE WHEN`, `CROSSES`, aggregates, multiple streams, multiple timeframes, multiple brokers, limit/stop/bracket/OCO, sizing modes other than direct, `DEFAULTS`, `FOR EACH`, parser.

### Phase 11c — Expressive core

The expressive-core phase is split into three sub-phases. Each ships independently. Together they cover the surface originally scoped to Phase 11c in earlier drafts of this spec.

#### Phase 11c1 — Engine state, range, conditional, cross, indicator composition, stdlib math (~18 tasks)

**Goal:** Make conditions expressive without yet introducing per-rule runtime state for snapshots and running aggregates.

**Adds:**
- Engine state accessors backed by existing views: `ACCOUNT.realized_pnl`, `ACCOUNT.unrealized_pnl`, `ACCOUNT.total_pnl`, `POSITION.<sym>` (signed quantity), `POSITION_AVG_PRICE.<sym>`. (Equity/balance/drawdown/`OPEN_ORDERS.<sym>` deferred — they need engine-side surface that does not yet exist.)
- `BETWEEN <lo> AND <hi>`, `IN [list]`.
- `CASE WHEN ... THEN ... ELSE ... END`.
- `CROSSES ABOVE` / `CROSSES BELOW` with per-node previous-bar state.
- Indicator composition (`RSI(ATR(s, 14), 5)` and similar).
- Stdlib math: `ABS`, `SQRT`, `LOG` (single-arg), `MIN`, `MAX` (variadic, distinct from running-aggregate forms in 11c2).

**Out of scope for 11c1:** snapshots, running aggregates (`SINCE OPEN`/`SINCE T-N`), new actions (`Log`, `CLOSE`, `CLOSE_ALL`, `CANCEL`, `CANCEL_ALL`), engine-side state surface for equity/balance/drawdown/open-orders.

#### Phase 11c2 — Snapshots and running aggregates (~12 tasks)

**Goal:** Stateful runtime — capture values at decision time, summarise series over windows.

**Adds:**
- Snapshot semantics (`@buy`, `@sell`, `@open`, `@T-N`) with a per-rule, per-symbol snapshot store.
- Running aggregates: `MIN`/`MAX`/`MEAN`/`SUM` over a `SINCE OPEN` or `SINCE T-N` window. `SINCE OPEN` resets when a position opens on the relevant symbol; `SINCE T-N` is a trailing window of `N` candles.

#### Phase 11c3 — New actions (~6 tasks)

**Goal:** Round out the action surface beyond `BUY`/`SELL`.

**Adds:**
- `Log "<message>"` — emits a structured log entry via the engine's existing SLF4J logger.
- `CLOSE <stream>` — flatten the position on `<stream>` (market order at full size, direction inferred from `POSITION.<sym>` sign).
- `CLOSE_ALL` — flatten every open position.
- `CANCEL <stream>` / `CANCEL_ALL` — surface working-order cancellation. (Cancellation requires broker-side support that may need engine work; if so, this slice is downsized to `CLOSE`/`CLOSE_ALL` only and the cancel actions defer to a later phase.)

### Phase 11d — Production order surface (~16 tasks)

**Goal:** Make the DSL usable for real trading by exposing the full order taxonomy.

**Adds:**
- `LIMIT`, `STOP`, `STOP-LIMIT`, `TRAILING BY`, `TRAILING PCT`.
- `BRACKET { STOP LOSS, TAKE PROFIT }`.
- `OCO { STOP, LIMIT }`.
- Child-price modes: `AT`, `BY`, `PCT`, `RR`.
- TIF (`GTC`, `IOC`, `FOK`, `DAY`, `GTD`).
- Sizing modes: `USD` notional, `% OF EQUITY`, `% OF BALANCE`, `RISK <frac>`, `RISK $<amt>`, `POSITION.<sym>`.
- `DEFAULTS` block with template inheritance into all actions.
- Validation: `RISK` requires resolvable stop distance; compile-time error otherwise.

### Phase 11e — Multi-timeframe and multi-broker (~10 tasks)

**Goal:** Allow a single strategy file to drive multiple instruments across multiple timeframes from multiple brokers.

**Adds:**
- Multiple stream declarations under `SYMBOLS` (already in AST since 11b — exposed and validated here).
- `EVERY <timeframe>` clause.
- Cross-stream conditions in rules (e.g. `btc.close > gold.close * 50`).
- Cross-timeframe references (e.g. `btc.fast` and `btc_h1.fast` in the same rule).
- `FOR EACH s IN [list] DO` macro expansion.
- Broker routing through `CompositeBroker` (Phase 7e). The `BYBIT:`, `INTERACTIVE:`, `ALPACA:` prefixes resolve through the existing registry; no new broker code in this sub-phase.

### Phase 11f — External SQL-like parser (~22 tasks)

**Goal:** Load `.qkt` files from disk. Same AST, same compiler, same runtime.

**Adds:**
- `com.qkt.dsl.parse.Lexer` — tokenizes the SQL-like syntax.
- `com.qkt.dsl.parse.Parser` — recursive-descent over the lexer, emits `StrategyAst`.
- Error reporting: line/column on every parse error; multiple errors per file (don't stop at the first).
- File entry point: `Dsl.parseFile(Path) : StrategyAst`.
- A worked-example fixture in `src/test/resources/dsl/` covering every construct from §5.
- Round-trip test: every fixture parses, compiles, runs against a deterministic backtest, and produces the same `BacktestResult` as the equivalent Kotlin DSL.

After 11f merges, the engine can run `.qkt` files end-to-end. The CLI runner (`qkt run foo.qkt`) is **not** in 11f — that is Phase 12.

---

## 8. Testing strategy

- **Per-construct unit tests** at the AST level: each `ExprAst` node has tests that exercise its compiler path.
- **Round-trip equivalence tests** at every sub-phase boundary: a Kotlin-DSL strategy and its hand-written `Strategy` counterpart must produce identical `BacktestResult` over the same fixture.
- **Parser fixture tests** in 11f: every example in §3 and §5 lives under `src/test/resources/dsl/` and is exercised end-to-end.
- **No mocks.** Tests run against the real backtest harness with a deterministic tick fixture and `FixedClock`.
- **Property-style tests** for the macro expansion: a `FOR EACH s IN [a, b, c] DO RULE` strategy must produce the same backtest result as three hand-written copies of `RULE` with `s` substituted.

---

## 9. Risk

**Risk: Medium-High.** A DSL is a long surface, and surface size is the dominant complexity driver. The mitigations are structural:

- **Per-sub-phase shippability.** Each of 11b–11f ends with a working, tested engine. We never carry half-built constructs across phase boundaries.
- **AST as the contract.** Front-ends and runtime are isolated. A parser bug cannot break the runtime; a runtime change cannot break the parser, as long as the AST is stable.
- **Runtime engine unchanged.** The DSL emits the same `Strategy` interface that hand-written code uses. There is no second runtime to maintain.
- **No new market-data, broker, or risk code in Phase 11.** Multi-broker routing reuses Phase 7e's `CompositeBroker`. Risk-based sizing reuses Phase 6's risk infrastructure. P&L attribution reuses Phase 9's `strategyId`. The DSL is a front-end over existing capabilities.

**Risk: Test combinatorics.** The Cartesian product of {sizing modes} × {order types} × {child price modes} × {TIF} × {brokers} is huge. We mitigate by testing each axis independently and one realistic combination per sub-phase end-to-end, rather than fully exhausting the matrix.

**Risk: Snapshot semantics misfires.** The compiler-managed per-symbol-per-rule snapshot store is the subtlest piece of state in the design. We mitigate by isolating it in a single named class with a small interface (`record(symbol, name, value, kind)` / `read(symbol, name, kind)`) and exhaustive unit tests covering every `SnapshotKind`.

---

## 10. Out of scope (explicit)

- **Phase 12 — CLI runner with observability port.** A `qkt run foo.qkt` command that runs a single strategy and exposes logs, performance, and live state on an HTTP port. Purely additive on top of the parser. Designed-but-deferred.
- **Multi-strategy orchestration in one DSL file.** One file, one strategy. Multi-strategy stays at `TradingPipeline` level.
- **Strategy-level config injection.** No `--param fast=9` from the command line. If a quant wants to sweep a parameter, they use Phase 10b's `BacktestSweep` with multiple compiled strategies.
- **DSL-level event hooks** (`ON_FILL`, `ON_REJECTED`). The DSL is rule-based; reactions to fills are expressed by reading `POSITION.<sym>` and `OPEN_ORDERS.<sym>` in subsequent rule firings.
- **Live-mode-only constructs.** Anything in the DSL must work in backtest. No DSL construct that depends on live wall-clock or live exchange ack semantics.
- **Backtesting helpers in DSL.** The DSL describes a strategy; how that strategy is exercised (single backtest, sweep, walk-forward) is a host-side concern.

---

## 11. References

- Phase 7e (CompositeBroker, multi-broker routing): `docs/superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`
- Phase 9 (per-strategy P&L attribution): `docs/superpowers/specs/2026-05-06-trading-engine-phase9-design.md`
- Phase 10 / 10b / 10c (backtest reporting, sweep, walk-forward): `docs/superpowers/specs/2026-05-07-trading-engine-phase10*.md`
- Phase 11a (OSS posture): `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`
- Architecture overview: `docs/architecture.md`
