# Phase 2b — Candle aggregator

## Summary

Phase 2b adds a tick-driven candle aggregator that subscribes to `TickEvent` and emits a `CandleEvent` when its time window rolls. `Strategy` gains an `onCandle` callback (default no-op for backward compat). Most production strategies operate on candles, not ticks — this is the layer that makes that possible without losing tick-level fidelity underneath.

## What's new

- `CandleAggregator(window, bus)` — subscribes to `TickEvent`, emits `CandleEvent` on window close
- `TimeWindow` value class with helpers:
  - `ONE_MINUTE`, `FIVE_MINUTES`, `FIFTEEN_MINUTES`, `ONE_HOUR`, `ONE_DAY` constants
  - `windowStartFor(timestamp): Long` — clock-aligned bucket boundary
  - `windowEndFor(timestamp): Long`
  - `durationMs: Long`
- `Candle(symbol, open, high, low, close, volume, startTime, endTime)`
- `CandleEvent` — new sealed-class variant
- `Strategy.onCandle(candle, ctx, emit)` — default no-op extension, backward-compatible
- `MockTickFeed.tickIntervalMs` constructor parameter — controls timestamp progression for realistic windowing
- Depth-first dispatch invariant locked in: aggregator subscribes BEFORE strategy, so strategies see `onCandle` before the next `onTick`
- 21 new tests (65 total)

## Migration

`MockTickFeed` now takes `tickIntervalMs` (default `1_000L`):

```kotlin
// Before (Phase 2a):
MockTickFeed("BTC", BigDecimal("50000"), count = 100, clock = clock)

// After (Phase 2b):
MockTickFeed("BTC", BigDecimal("50000"), count = 100, clock = clock, tickIntervalMs = 1_000L)
```

One Phase 1 test was updated for this. No changes to `Engine`, `EventBus`, or anything else.

## Usage cookbook

### Subscribe to closed candles

```kotlin
val aggregator = CandleAggregator(TimeWindow.ONE_MINUTE, bus)

bus.subscribe<CandleEvent> { event ->
    val c = event.candle
    println("[${c.startTime}] ${c.symbol} O=${c.open} H=${c.high} L=${c.low} C=${c.close}")
}
```

The aggregator handles all subscription wiring internally. You only subscribe to the output.

### A strategy that uses both ticks and candles

```kotlin
class MyStrategy : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        // react to individual ticks (e.g., trailing stop adjustment)
    }

    override fun onCandle(candle: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        // react to closed candles (e.g., indicator update + signal generation)
        if (candle.close > candle.open * BigDecimal("1.01")) {
            emit(Signal.Buy(candle.symbol, size = BigDecimal("0.1")))
        }
    }
}
```

Both callbacks fire in the same tick cycle — `onCandle` first (window close), then `onTick`.

### Multi-symbol aggregation

The aggregator handles per-symbol windows automatically. Drive ticks for multiple symbols into the same bus; you get one `CandleEvent` per symbol per window close:

```kotlin
val aggregator = CandleAggregator(TimeWindow.FIVE_MINUTES, bus)
// drive BTCUSDT + ETHUSDT + XAUUSD ticks → three independent candle streams
```

## Testing patterns

```kotlin
@Test fun `emits candle on window close`() {
    val clock = FixedClock(start = 0L)
    val bus = EventBus(clock, MonotonicSequenceGenerator())
    val agg = CandleAggregator(TimeWindow.ONE_MINUTE, bus)

    val candles = mutableListOf<Candle>()
    bus.subscribe<CandleEvent> { candles += it.candle }

    // ticks within the window
    repeat(59) {
        bus.publish(TickEvent(Tick("BTC", BigDecimal("50000"), it * 1_000L)))
    }
    // tick that crosses the boundary
    bus.publish(TickEvent(Tick("BTC", BigDecimal("50100"), 60_000L)))

    assertThat(candles).hasSize(1)
    assertThat(candles[0].close).isEqualTo(BigDecimal("50000"))
}
```

## Known limitations

- Single window per aggregator instance — multiple timeframes require multiple aggregators (Phase 11e introduces the `CandleHub` that handles this efficiently)
- No synthetic candles for empty windows — silent gaps
- No timer-based close — only tick-driven (a gap could leave a window open indefinitely)

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase2b-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase2b-design.md)
