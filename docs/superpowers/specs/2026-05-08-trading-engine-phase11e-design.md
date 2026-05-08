# Phase 11e — Multi-stream, multi-timeframe, multi-broker

**Status:** Design draft.
**Predecessor:** Phase 11d2 (engine equity surface, percent sizing, DEFAULTS).
**Successor:** Phase 11f (external SQL parser).

---

## 1. Mission

After 11d2 the DSL covers the full single-stream production order surface. Phase 11e lifts the strategy surface from "one symbol, one timeframe, one broker" to the multi-asset shape the master spec promises: a single strategy file can drive `btc` 1m and `btc_h1` 1h on Bybit alongside `gold` 15m on Interactive and `aapl` 5m on Alpaca, with cross-stream and cross-timeframe conditions, and a `FOR EACH` macro for repeated rule structures.

The phase is shaped by two architectural decisions taken before the spec was written, recorded here as the prevailing design:

1. **Shared CandleHub.** Multi-timeframe aggregation is hoisted out of individual strategies into a shared, JIT-registered, forward-streaming hub at the pipeline level. Multiple strategies that declare the same `(broker, symbol, timeframe)` share a single aggregator and history ring buffer.
2. **CompositeBroker as the single broker seam.** Backtest stops constructing `PaperBroker` directly. It builds a `CompositeBroker` whose routes are derived from the DSL's broker prefixes. Live mode swaps the leaves; the trunk is identical. Adding a new broker means registering it in `CompositeBroker` — DSL, Strategy, Pipeline, and Backtest do not change.

Both decisions extend cleanly past 11e: the hub is the shape that future per-key forward state lives in; the broker seam is the shape that future live execution plugs into.

---

## 2. Goals

- **Multi-stream DSL.** Multiple `stream(alias, broker, symbol, every)` declarations supported in one strategy. Same symbol may appear under multiple aliases (different timeframes).
- **EVERY \<timeframe\> wired up.** The `every` argument on `stream(...)` (already in `StreamDecl.timeframe`) drives a real per-alias candle aggregator inside the hub, fed from the pipeline's tick stream.
- **Cross-stream conditions.** `btc.close > gold.close * 50` evaluates correctly when the current candle is on either symbol — both reads served from the hub's latest-closed cache.
- **Cross-timeframe references.** `btc.fast` (on 1m) and `btc_h1.fast` (on 1h) coexist in the same rule. Indicators, aggregates, and snapshots rekey from `(symbol, name)` to `(alias, name)` so the same symbol at two timeframes maintains independent histories.
- **`FOR EACH s IN [a, b, c] DO RULE`.** Parse-time / builder-time macro that expands into N independent rules with `s` substituted by the literal stream reference. Pure AST rewrite — no runtime iteration.
- **`SYMBOL` placeholder in `DEFAULTS`.** Bare `SYMBOL` inside a `DEFAULTS` clause is bound at action-expansion time to the rule's stream alias. Lets `STOP_LOSS = BY ATR(SYMBOL, 14) * 2` work uniformly across rules targeting different streams.
- **CompositeBroker wired into Backtest.** `Backtest` constructs a `CompositeBroker` from the strategy's declared broker prefixes. Each prefix gets its own `PaperBroker` instance with the symbols routed to it. The DSL emits symbol-only orders; routing happens in `CompositeBroker`.

## Non-goals

- **Range queries from the hub.** "Give me BTC 1m for yesterday" is and remains a `MarketSource.candles(symbol, range)` call against the persistent store. The hub is forward-streaming, in-memory, and bounded; it does not service arbitrary historical lookups. Anyone wanting historical ranges hits `MarketSource` directly. The two layers meet exactly once: at strategy startup, the hub may be warmed via `MarketSource.candles(...)` so rules don't have to wait N candles before they can evaluate.
- **Live multi-broker integration.** Real `BybitSpotBroker`, `InteractiveBroker`, `AlpacaBroker` instances behind `CompositeBroker` are deferred to a later live-runner phase. 11e wires `CompositeBroker` with `PaperBroker` leaves only — but the wiring shape is the one live mode will use.
- **Tick-level sharing in the hub.** Hub stores closed candles, not raw ticks. Per-strategy access to the live tick is unchanged; ticks pass through `TradingPipeline` to `Strategy.onTick(...)` as today.
- **Hub eviction beyond bounded ring buffer.** Hub retention per key is `max(N for @T-N, max-aggregate-window)` plus a small fixed margin — sized at compile time, never grown after start. No LRU. No "cold key" eviction.
- **DSL-level access to the hub.** Strategies do not call `hub.subscribe(...)` directly. The DSL compiler registers keys on behalf of the strategy at compile time; the runtime is a closed loop.
- **Multi-strategy DSL files.** One file, one strategy. Multi-strategy composition stays at `TradingPipeline` level.
- **`GTD` TIF.** Still deferred from 11d1. 11e is not the phase where GTD lands.
- **`CANCEL` / `CANCEL_ALL` actions.** Still deferred from 11c3. Out of scope here too.
- **`ACCOUNT.drawdown`.** Still deferred (the master spec example uses it; the engine equity-curve drawdown plumbing is a separate phase).
- **Aggregating ticks into a base candle inside the hub when no `every` is declared.** A stream must declare a timeframe; "tick-only" streams are not in 11e.

---

## 3. Worked example

The `momentum_basket` example from the master spec, expressed in the Kotlin DSL after 11e ships:

```kotlin
strategy(name = "momentum_basket", version = 1) {
    val btc    = stream("btc",    broker = "BYBIT",       symbol = "BTCUSDT", every = "1m")
    val btc_h1 = stream("btc_h1", broker = "BYBIT",       symbol = "BTCUSDT", every = "1h")
    val gold   = stream("gold",   broker = "INTERACTIVE", symbol = "XAUUSD",  every = "15m")
    val aapl   = stream("aapl",   broker = "ALPACA",      symbol = "AAPL",    every = "5m")

    defaults {
        sizing     = riskFrac(0.01.bd)
        stopLoss   = childBy(atr(SYMBOL, 14) * 2.bd)
        takeProfit = childRr(3.bd)
        tif        = TifGtc
    }

    forEach(btc, gold, aapl) { s ->
        rule {
            whenever((s.fast crossesAbove s.slow) and (atr(s, 14) gt 0.005.bd * s.close))
            then { buy(s) }
        }
    }

    rule {
        whenever(position(btc) gt 0.bd and (btc.momentum lt 0.bd) and (btc_h1.fast lt btc_h1.slow))
        then { sell(btc, sizing = positionFull(btc)) }
    }
}
```

Key constructs exercised:

- Four streams, three brokers, four timeframes — including the same symbol (`BTCUSDT`) at two timeframes via different aliases.
- `forEach` macro expanding the cross-asset entry rule into three independent rules.
- `SYMBOL` placeholder inside `defaults` (e.g. `atr(SYMBOL, 14)`) bound per-rule at expansion time.
- Cross-stream + cross-timeframe condition (`btc.momentum < 0 AND btc_h1.fast < btc_h1.slow`).
- `position(btc)` reads the strategy's btc position regardless of which candle is current.

After 11e merges, every construct in this strategy compiles and runs deterministically against a backtest fixture.

---

## 4. Architecture

### 4.1 The two seams in one diagram

```
                                ticks
                                  │
                                  ▼
                  ┌──────────────────────────────┐
                  │ TradingPipeline               │
                  │                               │
                  │  ┌─────────────────────────┐  │
                  │  │ CandleHub               │  │   shared, JIT
                  │  │  key=(broker,sym,tf)    │  │
                  │  │   per-key: aggregator   │  │
                  │  │            ring buffer  │  │
                  │  │  feed(tick), latest,    │  │
                  │  │  history(n), onClosed   │  │
                  │  └────────────┬────────────┘  │
                  └───────────────┼───────────────┘
                                  │
                ┌─────────────────┼─────────────────┐
                ▼                 ▼                 ▼
        ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
        │  Strategy A   │ │  Strategy B   │ │  Strategy C   │
        │   alias→key   │ │   alias→key   │ │   alias→key   │
        │   private:    │ │   private:    │ │   private:    │
        │   indicators  │ │   indicators  │ │   indicators  │
        │   aggregates  │ │   aggregates  │ │   aggregates  │
        │   snapshots   │ │   snapshots   │ │   snapshots   │
        └───────┬───────┘ └───────┬───────┘ └───────┬───────┘
                │ Signal (symbol-only)              │
                └─────────┬─────────────────────────┘
                          ▼
                  ┌──────────────────────────────┐
                  │ TradingPipeline               │
                  │  • Risk, Sizing, OrderManager │
                  └──────────────┬───────────────┘
                                 ▼
                  ┌──────────────────────────────┐
                  │ CompositeBroker (single seam) │
                  │  routes by symbol pattern     │
                  │   BYBIT pat  → broker(bybit)  │
                  │   IB pat     → broker(ib)     │
                  │   ALPACA pat → broker(alpaca) │
                  └──────────────────────────────┘

   Backtest:  every leaf is a PaperBroker.
   Live:      leaves are real BybitSpotBroker / InteractiveBroker / AlpacaBroker.
   The trunk is identical. New broker = add a leaf, nothing else changes.
```

### 4.2 CandleHub

**Lifetime.** Hub is constructed by `TradingPipeline` at startup, lives for the run, dies with the pipeline.

**Registration.** Hub starts empty. During strategy compilation the `AstCompiler` enumerates every declared stream and calls `hub.register(key, retention)` for each. If two strategies register the same key with different retentions, the hub keeps `max`. The hub's key set is fixed once the pipeline starts; calling `register` after that is a programmer error.

**Key.**

```kotlin
data class HubKey(val broker: String, val symbol: String, val timeframe: String)
```

The full triple is the key. `(BYBIT, BTCUSDT, 1m)` and `(BYBIT, BTCUSDT, 1h)` are independent. `(BYBIT, BTCUSDT, 1m)` and `(ALPACA, BTCUSDT, 1m)` are also independent — they're different brokers with potentially different price action.

**Per-key state.**

```
HubKey → {
    aggregator     : CandleAggregator (TimeWindow-driven)
    history        : RingBuffer<Candle>   bounded to registered max retention
    latest         : Candle?              == history.last()
    listeners      : List<(Candle) -> Unit>
}
```

**Feed path.** `TradingPipeline` calls `hub.feed(tick)` on every received tick, before any strategy is dispatched. The hub finds every key whose `symbol == tick.symbol` and applies the tick to that key's aggregator. When an aggregator closes a candle, the hub:

1. Pushes the closed candle onto the key's ring buffer.
2. Updates `latest`.
3. Invokes every registered listener with the closed candle.

Strategies subscribe via `hub.onClosed(key, callback)` at compile time and react inside the callback (rule firing for rules whose alias maps to `key`).

**Read API.**

```kotlin
class CandleHub(...) {
    fun register(key: HubKey, retention: Int)
    fun feed(tick: Tick)
    fun latest(key: HubKey): Candle?
    fun history(key: HubKey, n: Int): Candle?    // n = 0 → latest, n = 1 → previous, ...
    fun onClosed(key: HubKey, callback: (Candle) -> Unit)
}
```

**Warmup.** Optional, opt-in per pipeline. Backtest may call `hub.warmup(source, recentRange)` once before the tick feed starts; the hub fetches `MarketSource.candles(...)` for each registered key and pre-populates the ring. This is the *only* point of contact between the hub and `MarketSource`. After warmup, the hub is forward-only.

**Memory bounds.** Per key, retention is `max` across all consumers. For typical strategies (`@T-N` with N ≤ 50, aggregates with windows ≤ 100), per-key footprint is ~100 × ~80 B = ~8 KB. With 5 keys, the hub is ~40 KB. Hub overhead is not a real concern.

**Determinism.** Hub feeds happen in pipeline tick order before any strategy dispatch. All strategies see consistent hub state at any rule eval point.

### 4.3 Per-strategy state

What was previously keyed by `symbol` rekeys to `alias`:

| State | Old key | New key | Reason |
|---|---|---|---|
| `IndicatorBinding.Bag` lookups | implicit (rule-scoped) | `(alias, IndicatorCall)` | same indicator on `btc` 1m and `btc_h1` 1h must be independent |
| `AggregateBinding.Bag` per-symbol | `symbol` | `alias` | `MAX(s.high) SINCE OPEN` on `btc` and `btc_h1` are different aggregates |
| `SnapshotStore` slots | `(symbol, name, kind)` | `(alias, name, kind)` | snapshot of `fast` at `btc` 1m close vs `btc_h1` 1h close |
| `SnapshotStore` rolling | `(symbol, name)` | `(alias, name)` | `@T-N` history per timeframe |
| `PositionTransitions` | `symbol` | `symbol` *(unchanged)* | positions are per-symbol; two aliases share the same position |
| `aggregates.bindingsForSymbol(...)` | `symbol` | `alias` | follows AggregateBinding |

**Position state stays symbol-keyed.** Two streams referencing the same symbol (`btc`, `btc_h1` both → `BTCUSDT`) share the same position; that's correct. The strategy has *one* BTC position no matter how many aliases observe it.

**Rule firing per alias.** Each compiled rule carries a `ruleAlias`. When the hub closes a candle for `key`, every rule whose `ruleAlias` maps to `key` fires its evaluation. Cross-stream condition reads pull from the hub's latest cache for other aliases.

### 4.4 CompositeBroker in Backtest

`Backtest` extracts the set of distinct broker prefixes from the strategy's declared streams. For each prefix, it instantiates a `PaperBroker` and registers a `SymbolPattern` covering the symbols that prefix routes. The `CompositeBroker` is constructed with that route table. The pipeline takes the `CompositeBroker` as its `Broker`.

Routing key: the symbol on the order. The DSL emits `BTCUSDT` (without prefix); `CompositeBroker` looks up which broker handles `BTCUSDT` from the route table.

**Edge cases.**

- **Same symbol on two brokers.** Disallowed at compile time — the DSL declarations cannot have two streams with the same `symbol` but different `broker`. Caught in `StrategyAst` validation. (Future live mode may relax this with explicit per-order broker selection; not in 11e.)
- **No streams declared.** Already errors in 11d2; unchanged.
- **Single broker prefix** (the common case). `CompositeBroker` has one route + a fallback, behaves identically to the old `PaperBroker` path.

### 4.5 FOR EACH macro

`forEach` is a builder-time AST rewrite. It accepts a `vararg` of `StreamRef` values and a lambda that takes a single `StreamRef` and emits one or more rules. The builder runs the lambda once per stream, substituting the literal `StreamRef` for the iteration variable.

```kotlin
StrategyBuilder.forEach(vararg streams: StreamRef, block: RuleBuilder.(StreamRef) -> Unit)
```

Every rule body added through the lambda is wrapped in a `RuleBuilder` so the resulting `RuleAst` is structurally identical to a hand-written rule. The compiler does not see `forEach` — by the time `AstCompiler` runs, the AST contains `N × len(streams)` ordinary `WhenThen` rules.

**Property test invariant.** `forEach(a, b, c) { s → rule(...) }` produces the same `BacktestResult` as three hand-written copies of the same rule with `s` literally substituted.

### 4.6 SYMBOL placeholder

Inside a `defaults { ... }` block, certain expressions can reference `SYMBOL` (a sentinel `Ref("__SYMBOL__")`). When `mergeDefaults(action, defaults)` (introduced in 11d2) inherits a default for a rule whose action targets stream `α`, every occurrence of the `SYMBOL` placeholder inside the inherited expression is rewritten to a literal `StreamFieldRef(alias = α, ...)` or `IndicatorCall(args = [StreamRef(α), ...])` as appropriate.

Implementation: a `substituteSymbol(expr, alias)` AST-walker called from `mergeDefaults` whenever a `ChildPriceAst`, `OrderTypeAst`, or `SizingAst` contains expressions reachable from `defaults`.

The placeholder is illegal outside `defaults`; the builder rejects it eagerly.

### 4.7 EvalContext changes

`EvalContext` gains a hub view. The runtime swaps the old `streamSymbols: Map<String, String>` for `streams: Map<String, HubKey>` and routes cross-stream reads through the hub:

```kotlin
data class EvalContext(
    val candle: Candle,                   // candle that triggered this eval (the rule's alias)
    val streams: Map<String, HubKey>,
    val hub: CandleHub,
    val lets: Map<String, Value>,
    val strategyContext: StrategyContext,
    val snapshotStore: SnapshotStore,
)
```

`compileStreamField(StreamFieldRef(alias, field))`:

- If `streams[alias].symbol == candle.symbol`, return the field from the live candle (current within-bar values).
- Otherwise read `hub.latest(streams[alias])` and project the field. If the hub has no closed candle yet for that key, return `Value.Undefined` and the rule skips.

`compileAggregate(...)` and snapshot reads use `streams[alias]` to derive the alias-keyed store paths.

---

## 5. Backwards compatibility

11e is additive at the DSL surface but rekeys per-strategy state from `symbol` to `alias`. The Kotlin DSL builder API gains:

- `StrategyBuilder.forEach(...)`
- `SYMBOL` sentinel ref usable inside `defaults { ... }`
- Existing single-stream strategies are unaffected: `streams = mapOf("btc" → HubKey("BYBIT", "BTCUSDT", "1m"))` is a one-entry map; alias-keyed stores collapse to single entries.

Existing tests and Phase 11b–11d2 round-trip fixtures continue to pass. The 11d1 ChildPriceResolver, OrderTypeCompiler, SizingCompiler, ActionCompiler all consume `streams[alias]` instead of `streamSymbols[alias]` — call-sites updated, semantics unchanged for single-stream strategies.

`TradingPipeline.candleWindow` stays as the legacy single-aggregator entry point for hand-written non-DSL strategies. DSL strategies bypass it: their candle data flows through the hub instead. Backtest will set `candleWindow = null` whenever any registered strategy is DSL-compiled.

---

## 6. Testing strategy

Per qkt convention: real types, no mocks; JUnit 5 + AssertJ; deterministic fixtures.

**Per-construct unit tests.**

- `CandleHubTest`: register → feed → close → history(n) for several `n`. Multiple keys, same symbol different timeframes. Two consumers registering the same key with different retentions → max wins. Replay determinism.
- `CompositeBrokerWiringTest`: build CompositeBroker from a DSL with three brokers, submit orders to each symbol, verify each routes to its leaf PaperBroker.
- `ForEachExpansionTest`: `forEach(a, b, c) { s → rule(...) }` produces three structurally-identical `WhenThen` nodes with `s` literally substituted.
- `SymbolPlaceholderTest`: `defaults { stopLoss = childBy(atr(SYMBOL, 14)) }` merged into a rule on alias `btc` produces `childBy(atr(streamRef(btc), 14))`. Same defaults merged into a rule on `gold` produces the gold variant.
- `AliasKeyedSnapshotsTest`: `btc.fast@T-3` and `btc_h1.fast@T-3` produce independent values on a fixture where the two timeframes diverge.
- `CrossStreamConditionTest`: rule `WHEN btc.close > gold.close * 50` evaluates correctly when current candle is btc, when current candle is gold, and skips while either has no closed candle yet.

**End-to-end tests.**

- `MultiTimeframeEndToEndTest`: a strategy with `btc 1m` and `btc_h1 1h` runs to completion against a deterministic tick fixture, produces the expected trade count.
- `MultiBrokerEndToEndTest`: a strategy with three brokers across three symbols runs to completion. Trade records contain the correct strategy id and symbol; CompositeBroker logs show per-leaf submissions.
- `ForEachEndToEndEquivalenceTest`: a `forEach(a, b, c) { rule(...) }` strategy and a hand-expanded three-rule strategy produce identical `BacktestResult` (trade-by-trade, equity-by-equity).

---

## 7. Risk

**Risk: Medium.** Larger than 11d2 because of the rekey from `symbol` to `alias`. Mitigations:

- The rekey is a mechanical rename touching `IndicatorBinding`, `AggregateBinding`, `SnapshotStore`, `EvalContext`, `AstCompiler`, `PositionTransitions` callsites. Each is unit-tested; the diff is large but the conceptual change is local.
- The hub lives behind one class with a small surface (`register`, `feed`, `latest`, `history`, `onClosed`). Bugs there surface at the unit level.
- `CompositeBroker` already exists since Phase 7e, with its own test surface. We're a new caller, not a new implementation.
- `forEach` is an AST rewrite. The property invariant test (expanded == hand-written) catches any divergence.

**Risk: Two streams pointing at the same broker leaf in CompositeBroker.** A strategy declares `BYBIT:BTCUSDT 1m` and `BYBIT:ETHUSDT 1m`. Both route to the same `PaperBroker(bybit)`. CompositeBroker's pattern table needs to handle this — the route is one pattern (multi-symbol) per broker, not one pattern per stream. Caught at construction time.

**Risk: Forward-only hub fails the strategy on cold start.** A rule that needs `btc.fast` has to wait until btc has accumulated enough candles for EMA(9). No different from today; the fix is `hub.warmup(...)` from `MarketSource` at startup. 11e ships warmup as opt-in but doesn't make it default — Backtest typically has long enough fixtures that cold-start delay is acceptable.

---

## 8. Phase decomposition (preview for the plan)

Approximately ten tasks, all in `com.qkt.dsl` plus one Backtest wiring task. Order:

1. **CandleHub.** Class, ring buffer, `register / feed / latest / history / onClosed`. Unit tests.
2. **HubKey + StreamSpec.** Replace `streamSymbols: Map<String, String>` with `streams: Map<String, HubKey>` in `EvalContext` and `AstCompiler`.
3. **Alias-keyed indicator/aggregate/snapshot bindings.** Mechanical rename + tests.
4. **Pipeline → hub feed + per-key dispatch.** `TradingPipeline` constructs and feeds the hub; DSL strategies subscribe via `hub.onClosed(...)`.
5. **Multi-timeframe end-to-end test.** Same-symbol two-timeframe strategy runs deterministically.
6. **Cross-stream condition support.** `compileStreamField` reads through the hub when `candle.symbol != streams[alias].symbol`. Tests.
7. **`forEach` macro builder + AST rewrite.** Tests including property-style equivalence.
8. **`SYMBOL` placeholder substitution in `mergeDefaults`.** Tests.
9. **CompositeBroker wiring in `Backtest`.** Per-broker `PaperBroker` instantiation; route table built from declared streams. Multi-broker end-to-end test.
10. **Phase 11e changelog under `docs/phases/`.**

---

## 9. References

- Master spec (multi-stream, FOR EACH, broker prefix semantics): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`
- Phase 7e (CompositeBroker): `docs/superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`
- Phase 11b (StrategyAst, StreamDecl, single-stream pipeline): `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`
- Phase 11c2 (snapshots / running aggregates): `docs/superpowers/plans/2026-05-07-trading-engine-phase11c2.md`
- Phase 11d2 (mergeDefaults, SizingCompiler equity wiring): `docs/superpowers/plans/2026-05-08-trading-engine-phase11d2.md`
- Architecture overview: `docs/architecture.md`
