# Phase 8 — StrategyContext and PnL Attribution

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase8-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase8-design.md)

## Summary

Phase 8 makes strategies first-class citizens. Every order, fill, position, and PnL number now carries a `strategyId`. Strategies receive a `StrategyContext` that bundles their identity with session bits and filtered views of their own positions and PnL. Two parallel trackers coexist: `PositionTracker` / `PnLCalculator` (broker-truth global view, reset by reconcile) and `StrategyPositionTracker` / `StrategyPnL` (per-strategy attribution, never auto-reset). After this phase, the engine answers "did Strategy X make money?" — and the DSL prerequisite identified in the audit is closed.

## What's new

- `StrategyContext` data class — replaces `SessionContext`. Carries `strategyId, mode, clock, calendar, source, positions: StrategyPositionView, pnl: StrategyPnLView`.
- `Strategy.onTick(tick, ctx: StrategyContext, emit)` and `Strategy.onCandle(candle, ctx, emit)` — both hooks receive the bundled context.
- `TradingPipeline(strategies: List<Pair<String, Strategy>>, ...)` — named registration. Validates uniqueness and non-blank. Each strategy gets its own `StrategyContext` per fill cycle.
- `StrategyPositionTracker` — per-`(strategyId, symbol)` attribution. `applyFill(event)` keys on `event.strategyId`. Blank strategyId is a noop. `driftFor(symbol, brokerView)` exposes attribution-vs-broker drift.
- `StrategyPnL` — per-strategy realized + unrealized + total. Methods: `realizedFor(strategyId)`, `unrealizedFor(strategyId, symbol)`, `unrealizedTotalFor(strategyId)`, `totalFor(strategyId)`.
- `StrategyPositionView` interface (`positionFor(symbol)`, `allPositions()`) — read-only filter binding to one strategyId. Internal impl `StrategyPositionViewImpl`.
- `StrategyPnLView` interface (`realized()`, `unrealizedFor(symbol)`, `unrealizedTotal()`, `total()`) — same filter pattern.
- `OrderRequest` sealed variants gain `strategyId: String = ""` (default for internal copies; required at strategy boundary via converters).
- `OrderRequest.withStrategyId(strategyId)` — extension function. Per-variant `copy(strategyId = ...)`. Used by `OrderManager` to propagate parent strategyId to bracket / OCO / OTO children.
- `Signal.toOrderRequest(id, ts, timeInForce, strategyId = "")` — converter threads strategyId. `Signal.Submit(request)` overwrites the request's strategyId via `withStrategyId` so strategies can't fake identity.
- `BrokerEvent.OrderEvent` marker gains `strategyId: String`. All variants (`OrderAccepted`, `OrderRejected`, `OrderFilled`, `OrderPartiallyFilled`, `OrderCancelled`) carry it. `BalancesUpdated` and `PositionReconciled` are venue-originated and stay strategyId-less.
- Brokers thread strategyId via `strategyByClientOrderId: ConcurrentHashMap`. On submit: store `request.strategyId`. On WS-driven events (Bybit): look up the map and emit. On terminal events: prune. Recovery-emitted events read from `ManagedOrderView.strategyId`.
- `BybitSpotStateRecovery.ManagedOrderView` gains `strategyId: String = ""` field.
- `TradingPipeline` subscribes `BrokerEvent.OrderFilled` → both global `PositionTracker.applyFill` AND `StrategyPositionTracker.applyFill` AND `StrategyPnL.recordRealized(event.strategyId, ...)`. Same fan-out for `OrderPartiallyFilled`.
- `testStrategyContext()` test helper — replaces `testSessionContext()`. Returns a `StrategyContext` with sensible test defaults (`strategyId = "test"`, no-op position/pnl views).

## Migration from previous phase

| 7h | 8 | Notes |
|---|---|---|
| `data class SessionContext(mode, clock, calendar, source)` | deleted | Use `StrategyContext`. Same session bits + strategyId + positions + pnl. |
| `Strategy.onTick(tick, ctx: SessionContext, emit)` | `onTick(tick, ctx: StrategyContext, emit)` | Type rename, same shape. |
| `Strategy.onCandle(...)` ditto | ditto | Type rename. |
| `TradingPipeline(strategies: List<Strategy>, ...)` | `TradingPipeline(strategies: List<Pair<String, Strategy>>, ...)` | Caller registers each strategy with a unique non-blank name. |
| `TradingPipeline(sessionContext = ctx, ...)` | `TradingPipeline(mode = m, calendar = c, source = s, ...)` | Pipeline constructs per-strategy contexts internally. |
| `Backtest`, `LiveSession` accept `List<Strategy>` | accept `List<Pair<String, Strategy>>` | Same shape change. |
| `OrderRequest.Market(id, symbol, side, qty, tif, ts)` | gains `strategyId: String = ""` | Default keeps existing call sites compiling; converters set it explicitly. |
| `Signal.toOrderRequest(id, ts)` | `toOrderRequest(id, ts, timeInForce, strategyId = "")` | Pipeline passes strategyId via this converter. |
| `BrokerEvent.OrderEvent` had clientOrderId, brokerOrderId | gains `strategyId: String` | Default `""` for migration; brokers populate from request. |
| `PositionTracker` only | `PositionTracker` + `StrategyPositionTracker` | Two trackers, two purposes. Both wired in `TradingPipeline`. |
| `PnLCalculator` only | `PnLCalculator` + `StrategyPnL` | Two PnL views. |
| `testSessionContext()` | `testStrategyContext()` | Same defaults plus strategyId = "test", no-op views. |

### Application setup change

```kotlin
// 7h
val positions = PositionTracker()
val pnl = PnLCalculator(positions, prices)
val strategies = listOf(EveryNthTickBuyStrategy("XAUUSD"), EmaCrossoverStrategy("XAUUSD"))
val pipeline = TradingPipeline(bus, clock, calendar, source, mode, strategies, ids, positions, pnl, ...)

// 8
val positions = PositionTracker()
val pnl = PnLCalculator(positions, prices)
val strategyPositions = StrategyPositionTracker()                                  // NEW
val strategyPnL = StrategyPnL(strategyPositions, prices)                           // NEW

val strategies = listOf(
    "every-nth-buy"  to EveryNthTickBuyStrategy("XAUUSD"),                         // named
    "ema-cross-xau"  to EmaCrossoverStrategy("XAUUSD"),                            // named
)

val pipeline = TradingPipeline(
    bus = bus,
    clock = clock,
    ids = ids,
    sequencer = sequencer,
    priceTracker = prices,
    positions = positions,
    pnl = pnl,
    strategyPositions = strategyPositions,                                          // NEW
    strategyPnL = strategyPnL,                                                      // NEW
    broker = broker,
    engine = engine,
    strategies = strategies,
    riskEngine = riskEngine,
    mode = mode,                                                                    // was inside SessionContext
    calendar = calendar,                                                            // was inside SessionContext
    source = source,                                                                // was inside SessionContext
    ...
)
```

## Usage cookbook

### 1. Read your own positions in a strategy

```kotlin
class MyStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val mine = ctx.positions.positionFor(symbol)?.quantity ?: BigDecimal.ZERO
        if (mine >= BigDecimal("1.0")) return    // already at limit
        if (someEntryCondition(tick)) emit(Signal.Buy(symbol, BigDecimal("0.1")))
    }
}
```

`ctx.positions` shows ONLY this strategy's net deltas. Other strategies on the same symbol don't bleed into the view.

### 2. Read your own PnL

```kotlin
class TrailingExitStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val unrealized = ctx.pnl.unrealizedFor(symbol)
        if (unrealized < BigDecimal("-100")) {
            // strategy-level stop loss triggered
            emit(Signal.Sell(symbol, ctx.positions.positionFor(symbol)?.quantity ?: return))
        }
    }
}
```

### 3. Register multiple named strategies in a pipeline

```kotlin
val pipeline = TradingPipeline(
    strategies = listOf(
        "ema-cross-btc"   to EmaCrossoverStrategy("BYBIT_LINEAR:BTCUSDT"),
        "ema-cross-eth"   to EmaCrossoverStrategy("BYBIT_LINEAR:ETHUSDT"),
        "breakout-spy"    to BreakoutOfYesterdayHighStrategy("ALPACA_STOCKS:SPY"),
    ),
    ...
)
```

Names must be unique within the pipeline. Same strategy class with different parameters is fine — they get different IDs.

### 4. Subscribe to drift to detect attribution-vs-broker divergence

```kotlin
bus.subscribe<BrokerEvent.PositionReconciled> { event ->
    val drift = strategyPositions.driftFor(event.symbol, positions)
    if (drift.abs() > BigDecimal("0.01")) {
        log.warn("Attribution drift on {}: strategy-sum vs broker = {}", event.symbol, drift)
    }
}
```

The two views can diverge when the broker liquidates a position externally. Strategies that care surface the warning; the engine doesn't auto-correct.

### 5. Per-broker per-strategy reporting via symbol-prefix grouping

```kotlin
fun reportPerBrokerPerStrategy(strategyPnL: StrategyPnL, strategyPositions: StrategyPositionTracker) {
    for ((strategyId, positions) in strategyPositions.allByStrategy()) {
        val byBroker =
            positions.entries.groupBy { it.key.substringBefore(":") }
        for ((brokerPrefix, syms) in byBroker) {
            val realized = strategyPnL.realizedFor(strategyId)
            log.info("strategy={} broker={} symbols={} realized={}",
                strategyId, brokerPrefix, syms.size, realized)
        }
    }
}
```

Symbol prefix carries broker attribution from Phase 7e. Combine with strategyId for full pivoting.

## Testing patterns

- **`testStrategyContext()`** — top-level helper in `com.qkt.strategy`. Returns a `StrategyContext` with no-op position/pnl views and `strategyId = "test"`. Use as a one-liner in strategy unit tests.
- **`StrategyPositionTracker.applyFill(event)`** — feed `BrokerEvent.OrderFilled` events with `strategyId` to test attribution. Different strategyIds produce independent positions.
- **Anonymous strategy in tests:** `listOf("test" to object : Strategy { ... })`. The name is required; `"test"` is fine for one-strategy tests.
- **Multiple-strategy tests:** `listOf("a" to strategyA, "b" to strategyB)`. Names must differ. Pipeline validates at construction.
- **Drift testing:** construct `StrategyPositionTracker` + a fake `PositionProvider` (broker view) → `tracker.driftFor("BTCUSDT", brokerView)` returns the difference.

## Known limitations

- **No automatic strategy-view drift correction.** When `PositionReconciled` resets `PositionTracker`, `StrategyPositionTracker` stays unchanged. Strategies subscribing to drift see it; engine never picks a strategy to "blame" the change on. Per spec: attribution is informational; broker is reality.
- **No equity curve / drawdown time series.** Phase 9 (risk) builds equity-over-time. Today only point-in-time `realized + unrealized` is available.
- **No persistence across JVM restarts.** Same as 7f-7h; recovery from broker truth on restart, attribution starts fresh.
- **Anonymous strategies forbidden.** Empty / blank `strategyId` causes `IllegalArgumentException` at pipeline construction. Auto-numbering hides intent and was rejected.
- **`OrderRequest.strategyId` defaults to `""` for migration safety.** This means an internally-constructed OrderRequest (e.g., during OrderManager bracket decomposition) without explicit propagation will produce a blank strategyId, which then becomes a noop in `StrategyPositionTracker.applyFill`. `OrderManager.submitBracketFallback` propagates via `withStrategyId`; future composite types must do the same.
- **`Signal.Submit(request)` overwrites the request's strategyId.** Strategies can't fake identity to the engine. Documented behavior, not a bug.
- **`BrokerEvent.PositionReconciled` and `BalancesUpdated` carry no strategyId.** They're venue-originated. Subscribers infer attribution from `source` and symbol prefix.
- **`StrategyPnLViewImpl` accesses `StrategyPnL` directly, not via interface.** The view is internal-only; future refactor could expose a generic `PnLAttribution` interface if needed.
- **Realized PnL is event-stream-derived only.** `recordRealized` is called from each fill. No reconciliation against broker-reported realized PnL (which Bybit's V5 API exposes for derivatives via `cumRealisedPnl`). Future phase.
- **`Signal.toOrderRequest(strategyId = "")` default works at the call site but is wrong semantically** — pipeline always overrides. The default exists only because `Signal.Submit` carries an OrderRequest with its own (likely blank) strategyId, which is overwritten regardless. Tests that call the converter directly without strategyId are the legitimate consumers of the default.
- **No multi-account.** A single pipeline owns all attribution. Multi-account is a future phase.
- **`StrategyContext` is a data class with no defaults.** Constructing one requires all 7 fields. Use `testStrategyContext()` in tests.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase8-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase8-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase8.md`](../superpowers/plans/2026-05-06-trading-engine-phase8.md)
- Phase 7h baseline: [`phase-7h-derivatives-and-rate-limit.md`](phase-7h-derivatives-and-rate-limit.md)
- Strategy/indicator/session audit: spec §3 of this phase
