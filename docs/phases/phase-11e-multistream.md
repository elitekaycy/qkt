# Phase 11e — Multi-stream, Multi-timeframe, Multi-broker

## Summary

Phase 11e lifts the DSL surface from "one symbol, one timeframe, one broker" to multi-asset shape. A strategy file can now drive `btc 1m` and `btc_h1 1h` on Bybit alongside `gold 15m` on Interactive and `aapl 5m` on Alpaca, with cross-stream and cross-timeframe conditions, a `forEach` macro for repeated rule structures, and a `SYMBOL` placeholder inside `defaults` blocks. The runtime gains a shared `CandleHub` that lives in `TradingPipeline`: JIT-registered, forward-streaming, deduplicated across strategies. `CompositeBroker` becomes the single broker seam — `Backtest` builds a per-prefix `PaperBroker` route table from the strategy's declared brokers, and live mode swaps the leaves without changing the trunk.

## What's new

- `com.qkt.dsl.compile.HubKey(broker, symbol, timeframe)` — full triple identity for hub-managed series.
- `com.qkt.dsl.compile.CandleHub` — shared candle aggregation hub on `TradingPipeline`. JIT registration at strategy compile time. Forward-only writes. Per-key bounded ring buffer. `register / feed / latest / history / onClosed / retention / historySize / keys` API.
- `com.qkt.dsl.compile.DslCompiledStrategy` — interface marking DSL-compiled strategies. Carries `declaredStreams: Map<String, HubKey>`, `retentionByKey: Map<HubKey, Int>`, and `bindToHub(hub, ctx, emit)` for hub-driven dispatch.
- `EvalContext.streams: Map<String, HubKey>` — replaces the old `streamSymbols: Map<String, String>`. Same alias still maps to a single underlying instrument, but now also carries broker prefix and timeframe.
- `EvalContext.hub: CandleHub` and `EvalContext.currentAlias: String?` — runtime hub reference for cross-stream reads, plus the alias whose candle just closed (drives correct same-symbol-different-timeframe disambiguation).
- `IndicatorBinding.rootAlias` / `IndicatorBinding.Bag.updateForAlias(alias, ctx)` — alias-filtered indicator update path used in hub-driven dispatch.
- `AggregateBinding.ruleAlias` (renamed from `ruleSymbol`) and `AggregateBinding.Bag.bindingsForAlias(alias)`.
- `SnapshotStore` rekeyed: every method's first parameter is `alias` instead of `symbol`. Same symbol with two timeframes maintains independent snapshot histories.
- `CompiledRule` carries both `ruleAlias` (snapshot key) and `ruleSymbol` (position lookup).
- `StrategyBuilder.forEach(vararg streams: StreamRef, block: ForEachScope.(StreamRef) -> Unit)` — builder-time AST rewrite that emits N independent rules with the iteration variable substituted as a literal `StreamRef`. No runtime iteration.
- `com.qkt.dsl.kotlin.SYMBOL` — placeholder constant usable inside `defaults { ... }` expressions. At merge time it is substituted per rule's stream alias as `StreamFieldRef(alias, "candle")`. Used outside `defaults` it errors at compile.
- `TradingPipeline.candleHub: CandleHub` constructor parameter (defaults to a fresh hub). `pipeline.ingest(tick)` now feeds `candleHub` after `engine.onTick(tick)`. DSL strategies are detected via `DslCompiledStrategy` and bound via `bindToHub`; legacy strategies subscribe to `CandleEvent` as before.
- `Backtest` builds a `CompositeBroker` from declared brokers when DSL strategies are present — one `PaperBroker` leaf per declared broker prefix, routed by symbol pattern. Falls back to a single `PaperBroker` when no DSL strategies are registered.
- `CandleAggregator.standalone(window, onClose)` — secondary constructor on the existing aggregator that emits closed candles via callback (no bus). Used internally by `CandleHub`.
- `TimeWindow.parse(spec)` — parses `"1s"`, `"1m"`, `"5m"`, `"15m"`, `"1h"`, `"1d"` strings into `TimeWindow` instances. Used by `CandleHub.register` to instantiate the right aggregator from a `HubKey.timeframe` string.
- `SymbolPattern.exactSet(symbols)` companion helper for routing multi-symbol broker entries.

## Migration from previous phase

`EvalContext.streamSymbols: Map<String, String>` was removed; replaced by `streams: Map<String, HubKey>`. Every callsite that constructed an `EvalContext` directly (mostly tests) was updated to pass a `HubKey` per alias:

```kotlin
// Before
EvalContext(candle, streamSymbols = mapOf("btc" to "BTCUSDT"), ...)

// After
EvalContext(candle, streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")), ...)
```

`SnapshotStore` parameter rename `symbol` → `alias`. Behaviour-preserving for single-stream strategies; same-symbol-different-timeframe strategies now correctly maintain independent histories.

`AggregateBinding.ruleSymbol` was renamed to `ruleAlias`; `bindingsForSymbol` to `bindingsForAlias`. `CompiledRule` gains `ruleAlias` alongside the existing `ruleSymbol`.

`ExprCompiler.compile(ruleSymbol = …)` parameter renamed to `ruleAlias`. Test callsites that named the parameter were updated.

`AstCompiler.compile(ast)` signature unchanged. Compilation now also computes a `retentionByKey` map and packages it on the returned `DslCompiledStrategy`.

`TradingPipeline` gains an optional `candleHub` parameter (defaults to a fresh `CandleHub`). DSL strategies in the strategy list are detected and routed through `bindToHub` instead of `bus.subscribe<CandleEvent>` — the legacy subscription path stays for hand-written strategies.

`Backtest` creates a hub internally and passes it to the pipeline. When the strategy list contains DSL strategies, `CompositeBroker` is built from declared broker prefixes; otherwise a single `PaperBroker` is used (matching prior behavior).

## Usage cookbook

### Multi-timeframe single-broker — btc 1m and btc_h1 1h

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.strategy
import java.math.BigDecimal

val ast =
    strategy("mtf", version = 1) {
        val btc    = stream("btc",    broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
        val btc_h1 = stream("btc_h1", broker = "BACKTEST", symbol = "BTCUSDT", every = "1h")

        rule {
            // Cross-timeframe condition: 1m candle close above 105 AND
            // the most-recently-closed 1h candle's close above 100.
            whenever((btc.close gt 105.bd) and (btc_h1.close gt 100.bd))
            then { buy(stream = btc, qty = BigDecimal("0.5").bd) }
        }
    }
val strategy = AstCompiler().compile(ast)
val result = Backtest(strategies = listOf("mtf" to strategy), ticks = ticks).run()
```

The btc rule fires on every 1m close (after the first 1h has closed). The runtime maintains separate aggregators for `(BACKTEST, BTCUSDT, 1m)` and `(BACKTEST, BTCUSDT, 1h)` inside the hub; cross-stream reads return the most-recently-closed candle of each.

### Multi-broker — different brokers for different assets

```kotlin
val ast =
    strategy("multi_broker", version = 1) {
        val btc  = stream("btc",  broker = "BYBIT",       symbol = "BTCUSDT", every = "1m")
        val gold = stream("gold", broker = "INTERACTIVE", symbol = "XAUUSD",  every = "1m")
        val aapl = stream("aapl", broker = "ALPACA",      symbol = "AAPL",    every = "1m")

        rule { whenever(btc.close  gt 50000.bd); then { buy(stream = btc,  qty = 0.001.bd) } }
        rule { whenever(gold.close gt  2000.bd); then { buy(stream = gold, qty =     1.bd) } }
        rule { whenever(aapl.close gt   200.bd); then { buy(stream = aapl, qty =    10.bd) } }
    }
```

`Backtest` builds a `CompositeBroker` with three `PaperBroker` leaves, each routed by symbol pattern. In live mode the same DSL recompiles unchanged; the only thing that swaps is the leaves — `BybitSpotBroker`, `InteractiveBroker`, `AlpacaBroker` replace the paper leaves.

### forEach — cross-asset entry rule

```kotlin
val ast =
    strategy("basket", version = 1) {
        val btc  = stream("btc",  "BYBIT",       "BTCUSDT", "1m")
        val gold = stream("gold", "INTERACTIVE", "XAUUSD",  "1m")
        val aapl = stream("aapl", "ALPACA",      "AAPL",    "1m")
        forEach(btc, gold, aapl) { s ->
            rule {
                whenever(s.close gt 0.bd)
                then { buy(stream = s, qty = BigDecimal.ONE.bd) }
            }
        }
    }
```

`forEach` runs the lambda once per stream at builder time. The compiler sees three independent `WhenThen` rules — there is no runtime iteration.

### SYMBOL inside defaults

```kotlin
import com.qkt.dsl.kotlin.SYMBOL

val ast =
    strategy("with_defaults", version = 1) {
        val btc  = stream("btc",  "BYBIT",       "BTCUSDT", "1m")
        val gold = stream("gold", "INTERACTIVE", "XAUUSD",  "1m")
        defaults {
            // ATR(SYMBOL, 14) — SYMBOL is bound per rule at merge time.
            stopLoss = childBy(IndicatorCall("atr", listOf(SYMBOL, NumLit(BigDecimal("14")))))
            takeProfit = childRr(3.bd)
        }
        rule { whenever(btc.close  gt 50000.bd); then { buy(btc,  qty = 0.001.bd) } }
        rule { whenever(gold.close gt  2000.bd); then { buy(gold, qty =     1.bd) } }
    }
```

`mergeDefaults` substitutes `SYMBOL` per action's stream alias: btc rule sees `ATR(streamFieldRef("btc", "candle"), 14)`; gold rule sees `ATR(streamFieldRef("gold", "candle"), 14)`. Used outside `defaults`, `SYMBOL` errors at compile.

### Cross-stream condition (different symbols)

```kotlin
val ast =
    strategy("cross", version = 1) {
        val btc  = stream("btc",  "BACKTEST", "BTCUSDT", "1m")
        val gold = stream("gold", "BACKTEST", "XAUUSD",  "1m")
        rule {
            // BTC > 50× gold price
            whenever(btc.close gt (gold.close * 50.bd))
            then { buy(stream = btc, qty = BigDecimal.ONE.bd) }
        }
    }
```

Reads to `gold.close` on a btc candle close go through `hub.latest(goldKey)`. Until gold has emitted its first closed candle, the read returns `Value.Undefined` and the rule is silently skipped — no spurious fires on startup.

## Testing patterns

### CandleHub unit testing

```kotlin
val hub = CandleHub()
val key = HubKey("BYBIT", "BTCUSDT", "1m")
hub.register(key, retention = 5)
for (t in 0L..180_000L step 30_000L) {
    hub.feed(Tick("BTCUSDT", BigDecimal("100"), timestamp = t, volume = BigDecimal.ONE))
}
assertThat(hub.latest(key)).isNotNull
assertThat(hub.history(key, 0)).isEqualTo(hub.latest(key))
```

`CandleHubTest` exercises register-after-feed errors, max-retention semantics, multi-key independence, and `onClosed` listener firing.

### EvalContext for compile-level tests

```kotlin
val key = HubKey("BACKTEST", "BTCUSDT", "1m")
val ec = EvalContext(
    candle = candle,
    streams = mapOf("btc" to key),
    lets = emptyMap(),
    strategyContext = testStrategyContext(),
)
```

Tests that don't exercise cross-stream behaviour can rely on the default empty `CandleHub` and unset `currentAlias`.

### Multi-timeframe e2e

`MultiTimeframeEndToEndTest` runs a btc 1m + btc_h1 1h backtest and asserts that the btc rule fires per 1m close while the btc_h1 rule fires once per hour. The cross-timeframe assertion verifies that `btc.close > 105 AND btc_h1.close > 100` only fires after the first hourly candle has closed.

## Known limitations

- **Hub does not service range queries.** "Give me BTC 1m for yesterday" still goes to `MarketSource.candles(symbol, range)`. The hub is forward-streaming with bounded retention; arbitrary historical lookups are an explicit non-goal.
- **Live multi-broker integration deferred.** Real `BybitSpotBroker`, `InteractiveBroker`, `AlpacaBroker` instances behind `CompositeBroker` will land in a future live-runner phase. 11e wires `CompositeBroker` with `PaperBroker` leaves only, but the wiring shape matches what live mode will use.
- **Hub does not warm up automatically.** A cold-start strategy that needs N candles before its indicators are ready waits N closes after `feed` begins. Optional warmup via `MarketSource.candles` is designed but not auto-applied; opt-in helper deferred.
- **Per-strategy indicator state stays private.** Two strategies that both want `EMA(close, 9)` on `btc 1m` each compute their own EMA. Indicator caching is a future optimization.
- **`GTD` TIF, `CANCEL`/`CANCEL_ALL` actions, `ACCOUNT.drawdown`** still deferred (carried over from earlier sub-phases).
- **One strategy per file.** Multi-strategy DSL composition stays at `TradingPipeline` level.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase11e-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase11e.md`
- Master spec (multi-stream, FOR EACH, broker prefix semantics): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md`
- Phase 7e (CompositeBroker): `docs/superpowers/specs/2026-05-06-trading-engine-phase7e-design.md`
- Merge commit: TBD
