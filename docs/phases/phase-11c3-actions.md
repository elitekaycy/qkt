# Phase 11c3 — Log, CLOSE, CLOSE_ALL Actions

## Summary

Phase 11c3 rounds out the action surface beyond `BUY`/`SELL`. DSL strategies can now emit log lines (`log "msg"`), flatten a single position by inferring direction from current quantity (`closeStream(s)`), or flatten every open position the strategy holds (`closeAll()`). One ripple change: every action callable now returns `List<Signal>` instead of `Signal`, because `CLOSE_ALL` may emit multiple signals per fire.

`CANCEL` and `CANCEL_ALL` are **deferred**. The engine has per-broker `cancel(orderId)` but no "cancel-all-for-symbol" surface, and DSL strategies don't track their own orderIds. The AST nodes remain declared so 11d (production order surface) can pick them up; the compiler currently rejects them with a clear "deferred" message.

## What's new

- `ActionCompiler.compile(action): (EvalContext) -> List<Signal>` — every action returns a list. Buy/Sell wrap their existing single signal in a singleton list. Log returns `emptyList()`. CLOSE returns 0 or 1 signals depending on current position. CLOSE_ALL returns 0..N signals.
- `Log` action — emits via SLF4J at `INFO` on a logger named `com.qkt.dsl.strategy.<name>`. Returns no signals.
- `CLOSE <stream>` action — reads `positionFor(symbol).quantity`. Long → `Sell(qty)`. Short → `Buy(qty.abs())`. Flat → no signal.
- `CLOSE_ALL` action — iterates `positions.allPositions()` for the strategy and emits one closing signal per non-zero position. Other strategies' positions in the same engine are not affected.
- `CompiledRule.fire(...)` returns `List<Signal>`. Empty when condition false or action emits nothing. `CompiledStrategy.onCandle` iterates the list and emits each.
- `AstCompiler` recognises Buy/Sell/Close/Cancel as stream-targeted actions. CloseAll/CancelAll/Log are stream-agnostic — they default `ruleSymbol` to the strategy's first declared stream (single-stream world).
- Kotlin DSL helpers: `ActionScope.log(msg)`, `ActionScope.closeStream(stream)`, `ActionScope.closeAll()`.

## Migration from previous phase

Internal-only ripple: `ActionCompiler.compile`, `CompiledRule.fire`, and `CompiledStrategy.onCandle` shifted from `Signal?` to `List<Signal>`. No external callers — the change is invisible to anyone who stays on `AstCompiler().compile(ast)`.

## Usage cookbook

### Logging on entry

```kotlin
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.crossesAbove
import com.qkt.dsl.kotlin.ema
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.strategy

val ast = strategy("logged_entry", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    val fast by letting(ema(btc.close, period = 9))
    val slow by letting(ema(btc.close, period = 21))
    rule {
        whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever((fast crossesAbove slow) and (position(btc) eq 0.bd))
        then { log("[logged_entry] cross-above on BTCUSDT") }
    }
}
```

Both rules fire on the same candle. The buy emits its signal; the log rule emits nothing (just an SLF4J line).

### CLOSE on price stop

```kotlin
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.lt

val ast = strategy("price_stop", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
        then { buy(btc, qty = 1.bd) }
    }
    rule {
        whenever((position(btc) gt 0.bd) and (btc.close lt 90.bd))
        then { closeStream(btc) }
    }
}
```

`closeStream(btc)` infers the direction from the current position. If long, it emits `Sell(qty)`. The same DSL works unchanged for short positions — emits `Buy(qty.abs())`.

### CLOSE_ALL on equity stop

```kotlin
import com.qkt.dsl.kotlin.Account
import com.qkt.dsl.kotlin.lt

val ast = strategy("equity_stop", version = 1) {
    val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
    rule {
        whenever(Account.totalPnl lt (-100).bd)
        then { closeAll() }
    }
    // ... entry rules elsewhere
}
```

When account-level total P&L drops below -100, every position the strategy holds is flattened in one rule fire. `closeAll()` reads `positions.allPositions()` and emits one closing signal per non-zero position.

## Testing patterns

The action callable now returns `List<Signal>`. Tests that previously asserted `assertThat(sig).isEqualTo(Signal.Buy(...))` change to `assertThat(sigs).containsExactly(Signal.Buy(...))`.

For `Log`, behaviour is hard to assert directly (logger output isn't captured in unit tests by default). The pragmatic check is that `compile + invoke` returns `emptyList()` and doesn't throw. If you need to assert log lines, add a custom Logback appender — out of scope for 11c3.

For `CLOSE` direction inference, build a `StrategyPositionView` with a non-zero quantity and assert the resulting Sell/Buy signal direction matches.

End-to-end equivalence tests for 11c3 are weaker than 11c1/11c2 — there's no clean hand-written reference for `closeAll()` semantics. Instead, the e2e test verifies that a strategy with entry + CLOSE rules produces both BUY and SELL trades on a price-drop fixture.

## Known limitations

- `CANCEL` and `CANCEL_ALL` deferred. Engine has per-broker `cancel(orderId)` but no symbol-level cancellation API; DSL strategies don't track orderIds. Will revisit alongside Phase 11d's order-lifecycle work.
- `CLOSE` on flat is a silent no-op. Guard with `position(stream) gt 0.bd` if you want to confirm something to close exists; otherwise the empty-list semantics is fine.
- `CLOSE_ALL` only flattens positions held by **this strategy**. Other strategies in the same engine are unaffected (this is the intended single-strategy isolation, not a bug).
- `Log` uses SLF4J `INFO`. Other levels (`debug`, `warn`, `error`) and structured fields are not exposed yet — out of scope.
- Snapshot capture (`@buy`/`@sell`/`@open`) fires on Buy/Sell rules only. CLOSE emits Sell/Buy signals but does NOT trigger snapshot capture, because the rule's `isBuy`/`isSell` flags are set from the source AST action type, not the emitted signal type. If you want to capture a snapshot at close-time, use a separate Sell rule with the same condition.
- `LIMIT`, `STOP`, `BRACKET`, `OCO`, `TRAILING`, TIF, advanced sizing modes, `DEFAULTS` block: Phase 11d.
- `FOR EACH`, multi-stream / multi-timeframe / multi-broker: Phase 11e.
- External `.qkt` parser: Phase 11f.

## References

- Spec: `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §7 Phase 11c3
- Plan: `docs/superpowers/plans/2026-05-07-trading-engine-phase11c3.md`
- Merge commit: `ba71eb3`
