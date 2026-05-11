# Phase 1 — Core engine MVP

## Summary

Phase 1 shipped the foundational pipeline: ticks flow through an `Engine`, a `Strategy` produces `Signal`s, a `MockBroker` fills them and prints trades. The full event-driven shape of qkt is established here, including the determinism primitives (`Clock`, `IdGenerator`) that every later phase depends on.

## What's new

- `Engine` — single-process orchestrator that wires `TickFeed → Strategy → Broker` together
- `Strategy` interface — `onTick(tick, ctx, emit)` callback shape
- `Signal` sealed class with `Buy` / `Sell` variants
- `Tick` — symbol + price + timestamp + optional bid/ask/volume
- `TickFeed` interface + `MockTickFeed` — deterministic synthetic random-walk generator
- `MarketPriceTracker` + `MarketPriceProvider` — producer/consumer-split price store
- `Order`, `OrderType` enum (`MARKET`), `Trade` — execution value types
- `Broker` interface + `MockBroker` — in-process fills at the tracker's latest price
- `Clock` interface + `SystemClock` / `FixedClock` — time access goes through this; never `System.currentTimeMillis()`
- `IdGenerator` + `SequentialIdGenerator` — deterministic order id generation
- Sample strategy `EveryNthTickBuyStrategy` (buys every Nth tick)
- 31 unit tests, ~150-LOC-per-file ceiling enforced

## Migration

First phase — no migration.

## Usage cookbook

### Run the demo

```bash
./gradlew run
```

You'll see synthetic ticks print, `Strategy` emit `Buy` signals every 5 ticks, `MockBroker` produce `Trade`s. This is the smallest possible end-to-end loop in qkt.

### Write a strategy in Kotlin (pre-DSL)

The DSL comes in Phase 5; in Phase 1 strategies are hand-written:

```kotlin
class MyStrategy : Strategy {
    private var tickCount = 0
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        if (++tickCount % 10 == 0) {
            emit(Signal.Buy(tick.symbol, size = BigDecimal("1.0")))
        }
    }
}
```

### Inject a deterministic clock for tests

```kotlin
val clock = FixedClock(start = 0L)
val engine = Engine(strategy, broker, clock, idGenerator, onTrade = ::println)
engine.onTick(Tick("BTC", BigDecimal("50000"), timestamp = clock.now()))
clock.advance(1_000)  // advance 1 second
```

This is the pattern every test in the codebase uses. `FixedClock` is what makes backtests reproducible.

## Testing patterns

- Use anonymous interface impls instead of mocking frameworks:
  ```kotlin
  val capturedTrades = mutableListOf<Trade>()
  val broker = object : Broker { override fun execute(order: Order): Trade? { ... } }
  ```
- JUnit 5 + AssertJ throughout
- Test names are backtick-quoted sentences: `` `fills MARKET order at tracker last price`() ``

## Known limitations

- No risk validation — strategies can submit any order
- No candles, only ticks (Phase 2b adds candles)
- No event bus, only direct method calls (Phase 2a adds the bus)
- No multi-strategy (Phase 2a adds it)
- No position tracking, P&L, or persistence (Phase 3, 3b)
- No backtest replay engine (Phase 4)
- No DSL — strategies are hand-written Kotlin (Phase 5)
- No concurrency — single-threaded by design

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md)
- Plan: [`docs/superpowers/plans/2026-05-03-trading-engine-phase1.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/plans/2026-05-03-trading-engine-phase1.md)
