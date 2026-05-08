# Phase 11d1 — Order Types, Brackets, OCO, Child Prices, Equity-Free Sizing

## Summary

Phase 11d1 makes the DSL usable for real trading. Strategies can submit `LIMIT`, `STOP`, `STOP-LIMIT`, `TRAILING BY`, and `TRAILING PCT` orders. They can attach a `BRACKET { STOP LOSS, TAKE PROFIT }` or an `OCO { STOP, LIMIT }` to any entry. They can size with USD notional, fixed-dollar risk (`RISK $50` with stop-distance lookup), or full-position close. They can specify `TIF` as `GTC`, `IOC`, `FOK`, or `DAY`. Every emitted order goes out as `Signal.Submit(OrderRequest.X(...))` with a unique per-strategy ID.

The headline acceptance test: a DSL strategy with `LIMIT entry + BRACKET { STOP LOSS BY 5, TAKE PROFIT RR 3 } + RISK $50 sizing` emits a `Signal.Submit(OrderRequest.Bracket)` with `entry: OrderRequest.Limit`, `stopLoss = entry - 5`, `takeProfit = entry + 3 * stop_distance`, and `quantity = $50 / stop_distance`.

`% OF EQUITY`, `% OF BALANCE`, `RISK <fraction>` sizing, and the `DEFAULTS` block are deferred to Phase 11d2 — they need engine-side `equity()` / `balance()` surface that doesn't yet exist. `GTD` TIF is also deferred (engine `TimeInForce` enum has 4 variants; adding GTD requires a deadline-bearing order surface).

## What's new

- `TifTranslator` — translates `TifAst` to engine `TimeInForce`. DAY/GTC/IOC/FOK supported; GTD throws "deferred".
- `ChildPriceResolver` — resolves `ChildPriceAst` (`AT`/`BY`/`PCT`/`RR`) given side, entry price, and optional stop distance to an absolute price. Side-aware sign for stop-loss (subtract on long, add on short) and take-profit (add on long, subtract on short). `RR` requires a stop distance.
- `OrderTypeCompiler` — compiles `OrderTypeAst` to a pair of closures: a `BuildRequest` that constructs the engine `OrderRequest` at fire time, and an `EntryPriceRef` that returns the entry-price reference used by BRACKET children. `Market` uses `candle.close` as the reference; `Limit`/`Stop` use the order's price.
- `SizingCompiler` — compiles `SizingAst` to a `(EvalContext, entryPrice) -> BigDecimal` quantity computer. Supports `SizeQty`, `SizeNotional`, `SizeRiskAbs`, `SizePositionFull`. Defers `SizePctEquity`, `SizePctBalance`, `SizeRiskFrac` to 11d2 with explicit "deferred" message.
- `ActionCompiler.compileBuySell` — fast path for plain market+default-TIF emits `Signal.Buy`/`Signal.Sell`; submit path for any non-trivial option emits `Signal.Submit(OrderRequest.X)`. Handles BRACKET and OCO via the new compilers.
- Per-strategy `IdGenerator` — `AstCompiler` builds `SequentialIdGenerator(prefix = "dsl-${ast.name}-")` and threads it into `ActionCompiler`. Every emitted `OrderRequest` gets a unique ID prefixed by strategy name.
- Kotlin DSL helpers:
  - Order types: `market`, `limitAt(price)`, `stopAt(price)`, `stopLimit(stop, limit)`, `trailingBy(distance)`, `trailingPct(frac)`.
  - Child prices: `childAt(price)`, `childBy(distance)`, `childPct(frac)`, `childRr(multiplier)`.
  - Brackets / OCO: `bracket(stopLoss, takeProfit)`, `oco(stop, limit)`.
  - Sizing: `usdNotional(usd)`, `riskAbs(amount)`, `positionFull(stream)`. Plus existing `1.bd` direct-quantity.
  - TIF: `gtc`, `ioc`, `fok`, `day` constants.
  - `ActionScope.buy`/`sell` extended with named `orderType`, `tif`, `bracket`, `oco` parameters; second overload accepts a `SizingAst` instead of a numeric `qty`.

## Migration from previous phase

None breaking. Plain `BUY btc QTY 1` continues to emit `Signal.Buy(symbol, qty)` (the existing fast path). Anything with a non-default option emits `Signal.Submit(OrderRequest.X)`. Existing tests that assert on `Signal.Buy`/`Signal.Sell` for plain-market actions stay green.

## Usage cookbook

### LIMIT entry with BRACKET (RISK $ sizing)

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.bracket
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.limitAt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.riskAbs
import com.qkt.dsl.kotlin.strategy

val ast = strategy("limit_with_bracket", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
        then {
            buy(
                stream = btc,
                sizing = riskAbs(50.bd),
                orderType = limitAt(99.bd),
                bracket = bracket(
                    stopLoss = childBy(5.bd),
                    takeProfit = childRr(3.bd),
                ),
            )
        }
    }
}
```

Emits `Signal.Submit(OrderRequest.Bracket(entry=Limit@99, stopLoss=94, takeProfit=114, quantity=10))`.

### STOP-LIMIT order on cross down

```kotlin
import com.qkt.dsl.kotlin.crossesBelow
import com.qkt.dsl.kotlin.stopLimit

rule {
    whenever(fast crossesBelow slow)
    then {
        sell(
            stream = btc,
            qty = 1.bd,
            orderType = stopLimit(stop = 95.bd, limit = 94.bd),
        )
    }
}
```

### Trailing stop with percent trailing

```kotlin
import com.qkt.dsl.kotlin.trailingPct

rule {
    whenever(position(btc) gt 0.bd)
    then {
        sell(
            stream = btc,
            sizing = positionFull(btc),
            orderType = trailingPct(0.05.bd),  // 5% trail
        )
    }
}
```

The DSL fraction (0.05) is auto-scaled to the engine's PERCENT trail format (5.0).

### OCO exits on entry

```kotlin
import com.qkt.dsl.kotlin.childAt
import com.qkt.dsl.kotlin.oco

rule {
    whenever(entryCondition)
    then {
        buy(
            stream = btc,
            qty = 1.bd,
            oco = oco(
                stop = childAt(95.bd),
                limit = childAt(110.bd),
            ),
        )
    }
}
```

Emits a parent BUY plus an `OrderRequest.StandaloneOCO` with two opposite-side legs (`Stop@95` and `Limit@110`); whichever fills first cancels the other.

### USD notional sizing

```kotlin
import com.qkt.dsl.kotlin.usdNotional

rule {
    whenever(condition)
    then { buy(stream = btc, sizing = usdNotional(1000.bd)) }
}
```

`qty = $1000 / current_close`. At a price of 100, this is 10 units.

### IOC order

```kotlin
import com.qkt.dsl.kotlin.ioc

rule {
    whenever(crossingCondition)
    then { buy(stream = btc, qty = 1.bd, tif = ioc) }
}
```

Emits `Signal.Submit(OrderRequest.Market(timeInForce=IOC))`.

## Testing patterns

Unit tests for each new compiler (`TifTranslator`, `ChildPriceResolver`, `OrderTypeCompiler`, `SizingCompiler`) cover all the variants in isolation. The end-to-end test (`OrderSurfaceEndToEndTest`) exercises the full pipeline by driving the compiled `Strategy` against synthetic candles and asserting on the captured `Signal.Submit(OrderRequest.X)` output.

For new strategies, prefer asserting on the *emitted OrderRequest shape* rather than the resulting `BacktestResult`, because the `PaperBroker` may not fully simulate every order type (limit fills, bracket child placement, OCO cancellation) the way a live broker would.

## Known limitations

- `% OF EQUITY` / `% OF BALANCE` / `RISK <fraction>` sizing modes deferred to 11d2 — needs engine `equity()` / `balance()` surface.
- `DEFAULTS` block deferred to 11d2 — natural pairing with %-of-equity sizing.
- `GTD` TIF deferred — engine enum has 4 variants only; adding GTD needs a deadline field on the order surface.
- `RISK $abs` sizing only resolves with **static** stop distance: `BY <numeric literal>` works; `BY <expression>`, `PCT`, `AT` don't (depend on entry price, only available at fire time). Compile-time error if you try.
- The `RR` child-price mode is take-profit-only. Errors at compile time if used for stop-loss.
- The `RR` resolver requires a `BY <numeric literal>` stop loss (the static distance) at compile time. To use `RR` with a non-static stop, lift the bracket's stop into a runtime evaluation — out of scope for 11d1.
- Fast path optimization: plain market actions with default TIF and no brackets still emit `Signal.Buy`/`Signal.Sell` (cheaper, smaller diff). Anything else uses `Signal.Submit`. The pipeline downstream handles both.
- Mixing `BRACKET` and `OCO` on the same action is a compile-time error.
- `FOR EACH`, multi-stream / multi-timeframe / multi-broker: Phase 11e.
- External `.qkt` parser: Phase 11f.
- CLI runner: Phase 12.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11d1
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11d1.md`
- Merge commit: `8cc6811`
