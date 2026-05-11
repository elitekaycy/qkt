# Phase 4 — Backtest harness

## Summary

Phase 4 ships the backtest replay engine — the thing that runs your strategy against a `List<Tick>` and produces a metrics-rich result. The crucial design move: `TradingPipeline` is extracted from `Main.kt` so it can be reused identically by `Backtest` and by live deployments. This is what makes the parity contract possible: same pipeline, different tick source, different clock.

## What's new

- `TradingPipeline(...)` — reusable wiring of bus + engine + risk + broker + observation callbacks. The single source of truth for "what a qkt run looks like."
- `HistoricalTickFeed(ticks: List<Tick>)` — `TickFeed` wrapper for in-memory sequences
- `Backtest(strategies, rules, ticks, candleWindow?, initialTimestamp).run(): BacktestResult` — single-call backtest entry point
- `BacktestResult` — every metric a backtest produces:
  - `trades: List<Trade>`, `rejections: List<OrderRequest>`
  - `finalPositions: Map<String, Position>`
  - `realizedTotal`, `unrealizedTotal`, `totalPnL: BigDecimal`
  - `tradeCount: Int`, `winRate: BigDecimal`, `maxDrawdown: BigDecimal`
- `TradeRecord(trade, realized)` — pairs each fill with its realized-P&L slice for win-rate math
- Mark-to-market drawdown subscriber — registered LAST among `TickEvent` subscribers (so equity is computed after all strategies have processed the tick)
- `Main.kt` restructured to use `TradingPipeline` — observable behavior unchanged
- 9 new tests (116 total)

## Migration

`Main.kt` no longer constructs the wiring inline; it uses `TradingPipeline`. Custom main entry points should follow suit:

```kotlin
// Before (Phase 3b):
val bus = EventBus(clock, sequencer)
val priceTracker = MarketPriceTracker()
val engine = Engine(bus, priceTracker)
// ...20 lines of subscriptions...

// After (Phase 4):
val pipeline = TradingPipeline(
    strategies = listOf(myStrategy),
    rules = listOf(MaxPositionSize("BTC", BigDecimal("1.0"))),
    clock = clock,
    onTrade = ::println,
)
for (tick in ticks) pipeline.ingest(tick)
```

`Engine`, `EventBus`, `Strategy`, and test fixtures are unchanged. `EndToEndTest` is unchanged.

## Usage cookbook

### Run a backtest

```kotlin
val ticks = HistoricalTickFeed.fromCsv(Path.of("data/btc-2024-jan.csv"))
val result = Backtest(
    strategies = listOf(MyStrategy()),
    rules = listOf(MaxPositionSize("BTCUSDT", BigDecimal("1.0"))),
    ticks = ticks.toList(),
    candleWindow = TimeWindow.ONE_MINUTE,
    initialTimestamp = 1_704_067_200_000L,  // 2024-01-01 UTC
).run()

println("trades=${result.tradeCount} winRate=${result.winRate} pnl=${result.totalPnL}")
```

### Read the result

```kotlin
val r: BacktestResult = backtest.run()

// Trade-by-trade
r.trades.forEach { println("${it.timestamp} ${it.side} ${it.quantity}@${it.price}") }

// Risk rejections (orders the rules vetoed)
r.rejections.forEach { println("rejected: ${it.symbol} reason=...") }

// Aggregate metrics
println("realized=${r.realizedTotal} unrealized=${r.unrealizedTotal} totalPnL=${r.totalPnL}")
println("winRate=${r.winRate} maxDD=${r.maxDrawdown}")
```

`winRate` counts trades with non-zero realized P&L. Empty trade lists return `Money.ZERO` (not `NaN`).

### Loop for a parameter sweep (manual, pre-Phase-10b)

```kotlin
val results = (5..50 step 5).map { fastPeriod ->
    fastPeriod to Backtest(
        strategies = listOf(EmaCrossoverStrategy(fastPeriod, slowPeriod = 100)),
        rules = emptyList(),
        ticks = ticks.toList(),
        candleWindow = TimeWindow.ONE_MINUTE,
        initialTimestamp = 0L,
    ).run()
}
val best = results.maxBy { it.second.totalPnL }
println("best fast=${best.first} pnl=${best.second.totalPnL}")
```

Phase 10b ships a proper parallel sweep harness on top of this primitive.

### Same pipeline, live execution

The TradingPipeline `Backtest` uses is the same one `LiveSession` uses in later phases. Compare:

```kotlin
// Backtest: clock is FixedClock advanced per tick
val pipeline = TradingPipeline(strategies, rules, clock = FixedClock(0L), ...)
for (tick in historicalTicks) pipeline.ingest(tick)

// Live (Phase 7+): clock is SystemClock, ticks arrive from a vendor feed
val pipeline = TradingPipeline(strategies, rules, clock = SystemClock(), ...)
liveFeed.subscribe { tick -> pipeline.ingest(tick) }
```

This symmetry is the parity contract. Phase 19's `BacktestLiveParityTest` enforces it.

## Testing patterns

- Hand-compute expected `totalPnL` for known tick sequences and assert exact equality
- Use `HistoricalTickFeed` with hand-crafted tick lists to test specific scenarios
- Drawdown is computed mark-to-market on every tick — test by driving prices up then down and asserting peak/trough

## Known limitations

- No event-log replay — backtest is from a `List<Tick>`, not from a serialized event log
- No CSV/JSON loader yet (Phase 6 adds `CsvTickFeed`)
- No Sharpe/Sortino/Calmar (Phase 10 adds the metrics suite)
- No parameter sweep harness — callers do this with a `for` loop (Phase 10b adds proper sweep)
- No position persistence — backtests start fresh; no resume

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase4-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase4-design.md)
