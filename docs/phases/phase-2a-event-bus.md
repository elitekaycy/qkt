# Phase 2a — Event bus + SLF4J

## Summary

Phase 2a replaced direct method calls with a type-keyed event bus. The Phase 1 `Engine → Strategy → Broker` chain becomes `Engine publishes TickEvent → bus dispatches → subscribers react`. This is the architecture every later phase builds on — strategies, risk engines, position trackers, P&L calculators all subscribe to events instead of being called directly. SLF4J logging is wired at the engine + bus + main entry points; strategies and brokers stay silent.

## What's new

- `EventBus(clock, sequencer)` — synchronous, type-keyed publish/subscribe
  - `subscribe<T : Event>(handler: (T) -> Unit)` — reified inline subscription
  - `publish(event)` — stamps event with bus-assigned timestamp + sequenceId, dispatches to subscribers
- `Event` sealed interface with four variants:
  - `TickEvent` — wraps `Tick`
  - `SignalEvent` — wraps `Signal` produced by a strategy
  - `OrderEvent` — wraps `OrderRequest` (renamed from `Order` in Phase 3)
  - `TradeEvent` — wraps `Trade`
- `SequenceGenerator` interface + `MonotonicSequenceGenerator` — strictly increasing event ids per bus instance
- Signal→order wiring extracted from `Engine` into `main()` via subscribers (depth-first dispatch ordering)
- SLF4J 2.0.16 added; logback config in test resources
- 13 new tests (44 total, Phase 1's 31 unchanged)

## Migration

`Engine` constructor changed — no longer takes `Strategy`, `Broker`, `IdGenerator`, `Clock`, `onTrade`:

```kotlin
// Before (Phase 1):
val engine = Engine(strategy, broker, clock, idGen, onTrade = ::println)

// After (Phase 2a):
val bus = EventBus(clock, sequencer)
val engine = Engine(bus, priceTracker)
bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx) { signal -> bus.publish(SignalEvent(signal)) } }
bus.subscribe<SignalEvent> { e -> /* route signal to broker */ }
bus.subscribe<TradeEvent> { e -> println(e.trade) }
```

The wiring moved from constructor params into explicit subscriptions. Phase 1 tests are unchanged because they only used `Engine.onTick`.

## Usage cookbook

### Subscribe to events

```kotlin
val bus = EventBus(clock, MonotonicSequenceGenerator())

bus.subscribe<TickEvent> { event ->
    println("tick: ${event.tick.symbol} @ ${event.tick.price}")
}

bus.subscribe<TradeEvent> { event ->
    println("filled: ${event.trade.symbol} ${event.trade.quantity}")
}
```

Handlers run synchronously on the publishing thread, in registration order.

### Multi-strategy

Multiple strategies subscribe to the same `TickEvent`:

```kotlin
bus.subscribe<TickEvent> { e -> stratA.onTick(e.tick, ctx) { ... } }
bus.subscribe<TickEvent> { e -> stratB.onTick(e.tick, ctx) { ... } }
```

The bus dispatches to both. This is what enables the daemon's multi-strategy hosting in Phase 12c.

### Inspect event order

Every published event gets a `sequenceId` from the bus's monotonic generator. Tests use this to assert event ordering:

```kotlin
val captured = mutableListOf<Event>()
bus.subscribe<Event> { captured += it }
// ... drive ticks ...
val ids = captured.map { it.sequenceId }
assertThat(ids).isStrictlyIncreasing
```

## Testing patterns

- Anonymous subscribers + capture lists replace mocks
- `MonotonicSequenceGenerator` in tests so event ids are deterministic
- Depth-first dispatch contract: a handler that publishes an event sees its subscribers fire before the next handler in the original subscription runs

## Known limitations

- No async/coroutine dispatch — single-threaded by design
- No candles (Phase 2b)
- No backtest replay (Phase 4)
- No persistence — bus state is in-memory only

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase2a-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase2a-design.md)
