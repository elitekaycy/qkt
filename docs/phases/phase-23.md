# Phase 23 — DSL catalog expansion

## Summary

Phase 23 closes the most-painful gap between the docs and the engine: the indicator catalog. Five indicators that were already implemented as Kotlin classes (`SMA`, `WMA`, `MACD`, `BollingerBands`, `VWAP-adjacent shape`) are now registered with the DSL and callable from `.qkt` files. Two new indicators (`RollingHigh` / `RollingLow`) ship for Donchian-style breakout strategies. `position(stream)` gains dot-accessors for entry price, P&L, and holding duration. `#` joins `--` as a line-comment style.

This is the first of three "docs-to-engine catch-up" phases that bring the documented surface in line with reality. Phase 24 adds risk-sizing primitives; Phase 25 adds operator tooling.

## What's new

### Indicators registered with the DSL

| DSL name | Underlying class | Arity (DSL form) |
| --- | --- | --- |
| `SMA(value, period)` | `SMA` | 2 |
| `WMA(value, period)` | `WMA` | 2 |
| `MACD(value, fast, slow, signal)` | `MACD` (macd line) | 4 |
| `MACD_SIGNAL(value, fast, slow, signal)` | `MACD` (signal line) | 4 |
| `MACD_HIST(value, fast, slow, signal)` | `MACD` (histogram) | 4 |
| `BOLLINGER_UPPER(value, period, stddevK)` | `BollingerBands` upper | 3 |
| `BOLLINGER_MIDDLE(value, period, stddevK)` | `BollingerBands` middle | 3 |
| `BOLLINGER_LOWER(value, period, stddevK)` | `BollingerBands` lower | 3 |
| `HIGHEST(value, period)` | `RollingHigh` (new) | 2 |
| `LOWEST(value, period)` | `RollingLow` (new) | 2 |

### New indicator classes

- `com.qkt.indicators.catalog.RollingHigh(period: Int)` — `Indicator<BigDecimal>`. Rolling maximum of the last `period` updates. Warmup = `period`.
- `com.qkt.indicators.catalog.RollingLow(period: Int)` — same shape, returns minimum.

### Position dot accessors

`POSITION.<stream>` continues to return the signed quantity (existing behavior). New chained accessors:

| Syntax | Returns | Source |
| --- | --- | --- |
| `POSITION.<stream>.quantity` (or `.qty`) | Signed quantity — explicit form | `Position.quantity` |
| `POSITION.<stream>.entry_price` (or `.avg_price`, `.avg_entry_price`) | Average entry price | `Position.avgEntryPrice` |
| `POSITION.<stream>.pnl` | Strategy realized + this-symbol unrealized | `StrategyPnLView.realized() + unrealizedFor(symbol)` |
| `POSITION.<stream>.realized_pnl` | Strategy-level realized P&L (not symbol-scoped — see Limitations) | `StrategyPnLView.realized()` |
| `POSITION.<stream>.unrealized_pnl` | Open P&L on this position | `StrategyPnLView.unrealizedFor(symbol)` |
| `POSITION.<stream>.holding_duration` | Milliseconds since position opened (0 if flat) | `clock.now() - Position.openedAt` |

### Position schema

- `Position` gains `openedAt: Long?` — timestamp (millis since epoch) when this position transitioned from flat to its current side.
- `StrategyPositionTracker` sets `openedAt = trade.timestamp` on flat→non-flat, preserves it on add-to-position, resets to the new fill's timestamp on flip, and removes the position entirely on full close.

### Hash line comments

`#` recognized as a single-line comment by the lexer. Equivalent to `--`. Block comments (`/* ... */`) unchanged. Inside string contexts the lexer still keeps `#` as part of content (verified by existing parser flow).

## Migration from Phase 22

No breaking changes. The indicator registry is a strict superset; the position-accessor extension is purely additive; the `#` comment is a new accepted token where it would previously have been an error.

A small internal shape change: `Position` data class now has 4 fields instead of 3. Anywhere code constructs `Position(symbol, qty, avgPrice)` it should add `openedAt = ...`. The default of `null` keeps existing callers compiling, but `null` means "holding_duration returns 0".

## Usage cookbook

### Donchian breakout strategy

```qkt title="strategies/donchian.qkt"
STRATEGY donchian VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    -- Enter long on close above the 20-bar high
    WHEN btc.close > HIGHEST(btc.close, 20)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
         LOG "donchian breakout long"

    -- Exit when close drops below the 10-bar low
    WHEN btc.close < LOWEST(btc.close, 10)
     AND POSITION.btc > 0
    THEN CLOSE btc
         LOG "donchian breakdown exit"
```

### MACD signal-line cross

```qkt title="strategies/macd-cross.qkt"
STRATEGY macd_cross VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

RULES
    WHEN MACD(btc.close, 12, 26, 9) CROSSES ABOVE MACD_SIGNAL(btc.close, 12, 26, 9)
    THEN BUY btc SIZING 0.1
         LOG "macd cross up"

    WHEN MACD_HIST(btc.close, 12, 26, 9) < 0
     AND POSITION.btc > 0
    THEN CLOSE btc
         LOG "histogram turned red — exit"
```

### Bollinger Bands mean-reversion

```qkt title="strategies/bbands-fade.qkt"
STRATEGY bbands_fade VERSION 1

SYMBOLS
    eur = BACKTEST:EURUSD EVERY 15m

RULES
    -- Buy when close drops below the lower band
    WHEN eur.close < BOLLINGER_LOWER(eur.close, 20, 2.0)
     AND POSITION.eur = 0
    THEN BUY eur SIZING 0.1
         BRACKET {
           STOP_LOSS BY 30,
           TAKE_PROFIT AT BOLLINGER_MIDDLE(eur.close, 20, 2.0)
         }

    -- Sell at the upper band
    WHEN eur.close > BOLLINGER_UPPER(eur.close, 20, 2.0)
     AND POSITION.eur = 0
    THEN SELL eur SIZING 0.1
         BRACKET {
           STOP_LOSS BY 30,
           TAKE_PROFIT AT BOLLINGER_MIDDLE(eur.close, 20, 2.0)
         }
```

### Time-stop using `holding_duration`

```qkt title="strategies/time-stop.qkt"
STRATEGY timed VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN EMA(btc.close, 9) CROSSES ABOVE EMA(btc.close, 21)
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1

    -- Exit if the position has been open more than 4 hours and isn't profitable
    WHEN POSITION.btc > 0
     AND POSITION.btc.holding_duration > 4 * 60 * 60 * 1000
     AND POSITION.btc.unrealized_pnl < 0
    THEN CLOSE btc
         LOG "time stop — losing position aged out"
```

### Multi-style signals + LET aliases

```qkt title="strategies/multi-style.qkt"
STRATEGY multi VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h

# LET works with the new indicators just like the old ones
LET trendUp = SMA(btc.close, 50) > SMA(btc.close, 200)
LET breakout = btc.close > HIGHEST(btc.close, 20)
LET overbought = RSI(btc.close, 14) > 80

RULES
    # Trend-confirmed breakout
    WHEN trendUp AND breakout AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1

    # Take profit on overbought
    WHEN POSITION.btc > 0 AND overbought
    THEN CLOSE btc
         LOG "overbought — booking profit"
```

Note both `#` and `--` comments work in the same file.

## Testing patterns

The indicators ship with hand-computed expected values + warmup tests, following the same pattern as the Phase 5 catalog. Example:

```kotlin
@Test
fun `max of last N values`() {
    val r = RollingHigh(3)
    listOf("10", "20", "15").forEach { r.update(Money.of(it)) }
    assertThat(r.isReady).isTrue()
    assertThat(r.value()).isEqualByComparingTo(Money.of("20"))
}

@Test
fun `window slides — older values drop out`() {
    val r = RollingHigh(3)
    // ... feed values, assert peak migrates as window slides
}
```

The position tracker's `openedAt` semantics ship with five regression tests covering: flat→long set, add-to-position preservation, full close clear, reopen reset, and long-to-short flip reset.

## Known limitations

- **`POSITION.<stream>.realized_pnl` is strategy-level**, not symbol-scoped. `StrategyPnL.realizedFor(strategyId)` doesn't currently break out realized by symbol. Tracked for Phase 24+ — needs lot-level accounting.
- **VWAP not yet registered** — it takes `Indicator<Tick>` which requires extending `IndicatorInput` to add a `TICK_SERIES` kind plus parser wiring. Deferred. Workaround: use SMA on `stream.close` as a proxy until then.
- **MACD/Bollinger sub-outputs don't deduplicate** — calling `MACD(...)`, `MACD_SIGNAL(...)`, and `MACD_HIST(...)` with the same parameters today creates three independent MACD instances inside the strategy. A future enhancement could share one underlying via parameter-tuple memoization in `IndicatorBinding`. Performance-wise this is irrelevant at qkt's scale; correctness-wise the outputs are identical.
- **`POSITION.<stream>.holding_duration` returns 0 when flat.** Could be `-1` or null instead; chose 0 to avoid downstream null handling.

## References

- Spec: [`docs/superpowers/specs/2026-05-11-phase23-dsl-catalog-expansion-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-11-phase23-dsl-catalog-expansion-design.md)
- Plan: [`docs/superpowers/plans/2026-05-11-phase23-dsl-catalog-expansion.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/plans/2026-05-11-phase23-dsl-catalog-expansion.md)
- Audit that motivated this phase: see commit messages on `docs-tutorials-ops-audit-fixes` branch
