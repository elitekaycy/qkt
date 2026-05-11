# Phase 3 — Risk engine + P&L

## Summary

Phase 3 introduces the risk engine, position tracking, and P&L attribution — the three things that turn the Phase 2 pipeline from a toy into something that can manage money. Risk rules gate every signal before it becomes an order; positions track quantity + weighted-average entry price; P&L surfaces realized and unrealized totals. Phase 3 also migrates every monetary value from `Double` to `BigDecimal` via the `Money` object — the standard discipline for systematic trading where floating-point drift over thousands of trades is unacceptable.

This page combines Phase 3 (risk + positions) and Phase 3b (P&L + BigDecimal) since 3b is a hard dependency on 3.

## What's new

### Risk engine (Phase 3)

- `RiskRule` interface — `evaluate(request, positions): Decision`
- `Decision` sealed class with `Approve` / `Reject(reason)` variants
- `RiskEngine(rules)` — evaluates rules in order, first reject wins
- Two built-in rules:
  - `MaxPositionSize(symbol, maxQty)` — caps quantity per symbol
  - `MaxOpenPositions(maxCount)` — caps simultaneous positions
- `RiskRejectedEvent` — emitted when a rule vetoes; rejected orders never hit the broker

### Positions (Phase 3)

- `Position(symbol, quantity, avgEntryPrice)` data class
- `PositionTracker` (writer) + `PositionProvider` (read-only) split
- `PositionTracker.apply(trade): BigDecimal` — updates state, returns realized P&L from the trade

### P&L (Phase 3b)

- `Money` object — single source of truth for monetary math:
  - `Money.CONTEXT` — `DECIMAL64` math context, scale 8, `HALF_EVEN` rounding
  - `Money.ZERO`
  - `Money.of(String)`, `Money.of(Long)`, `Money.of(Int)` — **no `of(Double)`** (enforced)
- `PnLProvider` (read-only) + `PnLCalculator` (writer) split
  - `recordRealized(strategyId, trade, realized)` — write
  - `realizedFor(strategyId)`, `unrealizedFor(strategyId)`, `unrealizedTotal()`, `totalPnL()` — pull-on-demand reads
- Weighted-average position math — buys average into the position; sells reduce; flips (long → short) calculate realized on the closing portion and re-enter on the flipped portion

### Type migration (Phase 3b)

Every monetary `Double` field swapped to `BigDecimal` across:

- `Tick.price`, `Tick.volume`, `Tick.bid`, `Tick.ask`
- `Candle.open` / `.high` / `.low` / `.close` / `.volume`
- `Order.quantity`, `Order.price`
- `Trade.price`, `Trade.quantity`
- `Signal.Buy.size`, `Signal.Sell.size`
- `Position.quantity`, `Position.avgEntryPrice`
- `MarketPriceProvider.lastPrice(symbol): BigDecimal?`
- `MockTickFeed`, `MockBroker`, every test fixture

## Migration

The `Double → BigDecimal` migration is the breaking change. Every literal monetary value becomes a `BigDecimal` via `Money.of`:

```kotlin
// Before:
Tick("BTC", 50000.0, timestamp = 1000L)
Signal.Buy("BTC", size = 0.1)

// After:
Tick("BTC", BigDecimal("50000"), timestamp = 1000L)
Signal.Buy("BTC", size = BigDecimal("0.1"))
```

All arithmetic uses `Money.CONTEXT`:

```kotlin
val total = quantity.multiply(price, Money.CONTEXT)
val pct = profit.divide(equity, Money.CONTEXT)
```

**Lockstep per-file migration:** tests were migrated first, then production code followed file-by-file. Every PR landed with green tests.

## Usage cookbook

### Wire the risk engine

```kotlin
val rules = listOf(
    MaxPositionSize("BTCUSDT", maxQty = BigDecimal("1.0")),
    MaxOpenPositions(maxCount = 3),
)
val risk = RiskEngine(rules)

bus.subscribe<SignalEvent> { e ->
    val request = e.signal.toOrderRequest(...)
    when (val decision = risk.evaluate(request, positions)) {
        is Decision.Approve -> bus.publish(OrderEvent(request))
        is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
    }
}
```

### Read P&L for a strategy

```kotlin
val pnl: PnLProvider = pnlCalculator
println("realized: ${pnl.realizedFor("my_strategy")}")
println("unrealized: ${pnl.unrealizedFor("my_strategy")}")
println("total: ${pnl.totalPnL("my_strategy")}")
```

`unrealized` uses the latest tracker price; it's recomputed on demand, never cached.

### Format BigDecimal for display

The standard pattern across qkt:

```kotlin
val display = value.stripTrailingZeros().setScale(2, RoundingMode.HALF_EVEN)
```

This produces `1234.56` instead of `1234.560000000000`. Used in the FILLED log line and the backtest report.

### Build your own risk rule

```kotlin
class MaxDailyTrades(private val maxPerDay: Int) : RiskRule {
    private val tradesByDay = mutableMapOf<LocalDate, Int>()

    override fun evaluate(request: OrderRequest, positions: PositionProvider): Decision {
        val day = Instant.ofEpochMilli(request.timestamp).atZone(ZoneOffset.UTC).toLocalDate()
        val count = tradesByDay.getOrDefault(day, 0)
        return if (count >= maxPerDay) Decision.Reject("daily-trade-cap")
               else { tradesByDay[day] = count + 1; Decision.Approve }
    }
}
```

Rules are pure (per design) — state is internal, no I/O. Phase 9 introduces a richer halt-as-state risk engine on top of this.

## Testing patterns

- Hand-compute expected P&L for buy-buy-sell sequences and assert exact `BigDecimal` equality (no `.isCloseTo` tolerance — `Money` math is exact)
- Use `MockTickFeed` to drive deterministic price sequences for weighted-average tests
- Test the **flipping** case: a long that gets flipped short by an oversized sell. Realized P&L applies to the closing portion only.

## Known limitations

- No lot-by-lot tracking — positions use weighted-average cost, which is correct for net P&L but loses tax-lot granularity
- No cumulative drawdown analytics (Phase 9 adds this via `EquityTracker`)
- No multi-currency — every account is single-currency
- No FIFO/LIFO position close — weighted-average only

## References

- Spec: [`docs/superpowers/specs/2026-05-03-trading-engine-phase3-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase3-design.md)
- Spec 3b: [`docs/superpowers/specs/2026-05-03-trading-engine-phase3b-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-03-trading-engine-phase3b-design.md)
