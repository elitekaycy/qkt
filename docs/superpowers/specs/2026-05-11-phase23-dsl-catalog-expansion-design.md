# Phase 23 — DSL catalog expansion

> Spec for the first of three "docs-to-engine catch-up" phases. Phase 24 and 25 land risk-sizing primitives and operator tooling respectively. This phase exposes the indicator/position surface that's already implemented but not reachable from the DSL, and adds a few small ergonomic primitives.

## Goal

Make every indicator and position accessor referenced in the docs **reachable from the qkt DSL**, by registering existing implementations and adding two small new ones. After this phase, the indicator catalog stops being aspirational and matches what users can actually write.

## Motivation

A recent audit of the docs vs. source found that:

- The DSL's `IndicatorRegistry` only exposes **3 of 8** implemented indicators (EMA, RSI, ATR). The other 5 (SMA, WMA, MACD, VWAP, BollingerBands) are fully implemented Kotlin classes in `com.qkt.indicators.catalog.*` but never registered.
- `position(stream)` returns a value with only `.quantity` accessible from the DSL; the underlying `PositionRef` has more state available (realized P&L, unrealized P&L, entry price, holding duration).
- `highest()` / `lowest()` (rolling-window extremes — the workhorse of breakout strategies) aren't in the catalog at all.
- The lexer accepts `--` line comments and `/* */` block comments but not `#` (a common preference, especially for users coming from Python).

This phase closes all four gaps with the smallest possible engine change.

## Scope

### In scope

**A. Indicator registration** (~30 LOC + tests)

Register the following in `com.qkt.dsl.stdlib.IndicatorRegistry`:

| DSL name | Indicator class | Args |
| --- | --- | --- |
| `SMA(value, period)` | `com.qkt.indicators.catalog.SMA` | period: Int |
| `WMA(value, period)` | `com.qkt.indicators.catalog.WMA` | period: Int |
| `VWAP(stream, period)` | `com.qkt.indicators.catalog.VWAP` | period: Int |
| `MACD(value, fast, slow, signal)` | `com.qkt.indicators.catalog.MACD` | fast, slow, signal: Int |
| `MACD_SIGNAL(value, fast, slow, signal)` | same instance, expose signal line | fast, slow, signal: Int |
| `MACD_HIST(value, fast, slow, signal)` | same instance, expose histogram | fast, slow, signal: Int |
| `BOLLINGER_UPPER(value, period, stddev)` | `com.qkt.indicators.catalog.BollingerBands` upper | period: Int, stddev: BigDecimal |
| `BOLLINGER_MIDDLE(value, period, stddev)` | middle = SMA | period: Int, stddev: BigDecimal |
| `BOLLINGER_LOWER(value, period, stddev)` | lower | period: Int, stddev: BigDecimal |

MACD and BollingerBands are multi-output indicators. The pattern: the underlying object is shared (deduplicated by parameter tuple in the compile cache), and the three DSL function names each pull one of its outputs.

**B. Rolling extremes** (~80 LOC + tests)

Two new indicator classes:

- `com.qkt.indicators.catalog.RollingHigh(period: Int)` — rolling max of input values over the last `period` updates
- `com.qkt.indicators.catalog.RollingLow(period: Int)` — rolling min

Both implement `Indicator<BigDecimal>`. Standard "warmup until period values seen" semantics. Both registered in `IndicatorRegistry` as `HIGHEST` and `LOWEST`.

**C. Position accessors** (~40 LOC + tests)

Extend the DSL `position(stream)` reference with these additional accessors:

| Access | Returns | Source |
| --- | --- | --- |
| `position(stream)` (bare) | Quantity (signed) — exists today | `Position.quantity` |
| `position(stream).quantity` | Same as bare — explicit form | `Position.quantity` |
| `position(stream).entry_price` | Average entry price | `Position.avgEntryPrice` |
| `position(stream).pnl` | Total open P&L (realized + unrealized for this position) | computed from `PnLCalculator` |
| `position(stream).realized_pnl` | Realized P&L for this symbol since strategy start | `PnLCalculator.realizedFor(strategyId)` |
| `position(stream).unrealized_pnl` | Unrealized P&L on current open position | `PnLCalculator.unrealizedFor(strategyId)` |
| `position(stream).holding_duration` | Time since position opened, in milliseconds | `Position.openedAt` (new field) → `clock.now() - openedAt` |

The `holding_duration` accessor requires a small `Position` schema change: add an `openedAt: Long` field that's set on transition from flat to long/short, and reset to `null` when flat. PositionTracker already tracks the relevant state internally; this just exposes it.

**D. Hash line comments** (~5 LOC)

Lexer change to recognize `#` as a line comment delimiter, equivalent to `--`. Single-line only — no `#!shebang` recognition required.

### Out of scope

Deferred to **Phase 24** ("risk-sizing primitives"):

- `SIZING N PCT RISK`
- `WARMUP N BARS`
- `IS NULL` / `IS NOT NULL`
- `FLATTEN` as DSL action

Deferred to **Phase 25** ("operator tooling"):

- `qkt fetch` CLI
- `TRAILING_STOP` wiring
- `per_strategy:` risk config block
- `qkt sweep` / `qkt walkforward` CLI — heavier; possible Phase 26+

## Approach

### Naming convention for multi-output indicators

MACD has three outputs: the MACD line, the signal line, and the histogram. Two design options:

1. **Separate DSL names**: `MACD(value, fast, slow, signal)` returns the MACD line. `MACD_SIGNAL(...)` returns the signal. `MACD_HIST(...)` returns the histogram. Three function names, three return values.
2. **Dotted access**: `macd(value, fast, slow, signal).signal`, `.hist`. One function, dotted accessors.

**Choice: option 1 (separate names).** Simpler parser; matches the pattern already documented; common in TA-lib bindings. Option 2 requires generalizing the AST to support method calls on indicator results, which is a bigger change.

For Bollinger Bands the same pattern: `BOLLINGER_UPPER`, `BOLLINGER_MIDDLE`, `BOLLINGER_LOWER` — three names sharing the same underlying instance internally.

### Deduplication

Multiple calls with identical parameter tuples should share one indicator instance to avoid recomputing the same MACD twice. The DSL compiler already does this for EMA/RSI/ATR via the `IndicatorBinding` cache keyed on `(name, value-source, args)`. The multi-output indicators use the same cache: `MACD`, `MACD_SIGNAL`, `MACD_HIST` with identical args produce **one** MACD instance with three `IndicatorOutput` views into it.

### `Position.openedAt` field

`Position` currently tracks `symbol`, `quantity`, `avgEntryPrice`. Adding `openedAt: Long?`:

- Set to `clock.now()` when transitioning from flat (`quantity = 0`) to non-flat.
- Reset to `null` when transitioning back to flat.
- Preserved on weighted-average updates (adding to an open position) — `openedAt` stays the first-open time, not the most-recent-fill time.
- The flip case (long → short via oversized sell): `openedAt` resets to `clock.now()` at the moment of flip, since the new short is a distinct position.

This is a minor schema extension; not user-visible except via the new DSL accessor.

### `RollingHigh` / `RollingLow` semantics

Both follow the same pattern as `SMA`:

```kotlin
class RollingHigh(private val period: Int) : Indicator<BigDecimal> {
    private val window = ArrayDeque<BigDecimal>(period)
    override fun update(input: BigDecimal) {
        window.addLast(input)
        while (window.size > period) window.removeFirst()
    }
    override fun value(): BigDecimal? = if (window.size < period) null else window.max()
    override val isReady: Boolean get() = window.size >= period
    override val warmupBars: Int get() = period
}
```

**Important semantic decision**: does `highest(close, N)` include the current bar or exclude it?

- **Including the current bar**: easiest implementation; useful for "the max over the last N bars". But `close > highest(close, N)` can never be true (the current close can't exceed itself).
- **Excluding the current bar**: needed for breakout strategies. `close > highest(close, N)` = "this bar's close exceeds the last N bars' highs."

The audit found that documented breakout strategies assume the exclude-current semantics. We'll **exclude the current bar** by lagging the input by one tick: the indicator stores values up through the previous `update()` call and returns the max of those.

### Hash comments

`Lexer.kt` already handles `--` line comments at lines ~39-58. Add `#` to the line-comment trigger conditions. Behavior: when the lexer sees `#` outside of a string literal, skip to end of line. Single character — no need to disambiguate from a hash-shebang sequence because qkt files aren't executable.

## Tests

Per-indicator tests follow the existing pattern in `src/test/kotlin/com/qkt/indicators/catalog/`:

- **Hand-computed expected values** for known input sequences. e.g. `RollingHigh(3)` fed `[10, 20, 15, 25, 18]` should produce `[null, null, null, 20, 25]` (3-bar warmup, then peak excluding current).
- **Warmup test**: feed `period - 1` values, assert `!isReady`; feed one more, assert `isReady`.
- **Edge cases**: empty input, single value, all-equal values.

For DSL registration: extend the existing tests in `src/test/kotlin/com/qkt/dsl/compile/IndicatorBindingTest.kt` to exercise each new indicator name and verify the cache deduplication works for multi-output indicators.

For `position(stream).pnl` etc: extend `src/test/kotlin/com/qkt/dsl/compile/StateRefsTest.kt`. Test fixture: buy 10 BTC at 100, mark to 110 — expect `.entry_price = 100`, `.unrealized_pnl = 100`, `.quantity = 10`.

For `holding_duration`: use `FixedClock` to advance time deterministically between open and assertion.

For `#` comments: extend `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` with a strategy that mixes `--`, `#`, and `/* */` comments.

## Acceptance criteria

After this phase merges:

1. **All 8 indicators** (EMA, SMA, WMA, RSI, ATR, MACD/MACD_SIGNAL/MACD_HIST, VWAP, BOLLINGER_UPPER/MIDDLE/LOWER) callable from the DSL.
2. `HIGHEST(value, N)` and `LOWEST(value, N)` callable; semantics exclude current bar.
3. `position(stream)` exposes `.quantity`, `.entry_price`, `.pnl`, `.realized_pnl`, `.unrealized_pnl`, `.holding_duration`.
4. `#` recognized as a line comment by the lexer.
5. `./gradlew build` passes with all new tests.
6. `./gradlew dokkaHtml` clean — every new public type carries KDoc per the SKILL rule.
7. Phase 23 changelog (`docs/phases/phase-23.md`) lands with a usage cookbook for every new surface.

## Known limitations after Phase 23

- `SIZING N PCT RISK` still missing — strategies that want risk-based sizing must compute manually with `SIZING (account.totalPnl * 0.01 / stopDist) USD` style hacks or wait for Phase 24.
- `TRAILING_STOP` not wired — Phase 25.
- `qkt fetch` not present — Phase 25.
- Position-state references like `position(stream).is_long` or `.is_short` not provided. Use `position(stream).quantity > 0` / `< 0`.

## References

- Audit findings: in this session's conversation context
- Phase 5 spec (where the 8 indicators were originally planned): `docs/superpowers/specs/2026-05-03-trading-engine-phase5-design.md`
- Phase 5 changelog (post-merge): `docs/phases/phase-5-indicators.md` — claimed all 8 but only 3 reached the DSL
