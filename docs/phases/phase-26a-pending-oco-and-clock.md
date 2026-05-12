# Phase 26a — Pending-entry OCO DSL surface and clock accessors

## Summary

Phase 26a adds DSL surface for pending-entry OCO (`OCO_ENTRY { leg1, leg2 }`) and DSL-visible clock accessors (`NOW.<field>`), plus the `NOW + duration` relative-deadline form for GTD expiry on pending orders. The execution-layer primitive (`OrderRequest.StandaloneOCO`) was already present in qkt's engine; this phase exposes it to authors writing `.qkt` files.

Hedge-straddle and other pending-OCO strategies are now expressible, parseable, and backtestable in qkt. Live MT5 runtime support ships separately as Phase 26b (the MT5 broker today translates only `Market` and `Bracket` orders).

## What's new

### DSL surface

- **`OCO_ENTRY { leg1, leg2 }`** — two pending entries linked one-cancels-other. Each leg is a normal `BUY`/`SELL` with full modifier set (SIZING, ORDER_TYPE, BRACKET, TIF). Parser invariants: exactly two legs, each must be `BUY` or `SELL`. `LOG`/`CLOSE`/`CANCEL` inside the block is a parse error.
- **`NOW.hour_utc`, `NOW.minute_utc`, `NOW.weekday`, `NOW.date_utc`, `NOW.epoch_ms`** — clock accessors readable from any expression position. Field names are case-insensitive. Backtest reads the simulated clock, so the values are deterministic.
- **`NOW + <duration>`** — relative epoch-ms arithmetic for GTD expiry. Pair with `TIF GTD UNTIL` for self-expiring pending orders. Duration suffixes: `s`, `m`, `h`, `d`.
- **Bare `NOW`** — equivalent to `NOW.epoch_ms`. Lets `NOW + 10m` parse without an explicit field.

### AST additions

- `NowAccessor(field: NowField)` in `ExprAst.kt`
- `NowField` enum: `HOUR_UTC`, `MINUTE_UTC`, `WEEKDAY`, `DATE_UTC`, `EPOCH_MS`
- `OcoEntry(leg1: ActionAst, leg2: ActionAst)` in `RuleAst.kt`

### Compiler

- `ExprCompiler.compileNow(NowAccessor)` reads `ctx.strategyContext.clock.now()`, projects the requested field via `java.time.ZoneOffset.UTC`
- `ActionCompiler.compileOcoEntry(OcoEntry)` compiles each leg (reusing the existing per-action compile path), extracts each leg's `Signal.Submit.request`, wraps both in `OrderRequest.StandaloneOCO`, emits one `Signal.Submit`
- `DefaultsMerge`, `IterVarSubstitution`, and `AstCompiler` recurse into both `OcoEntry` legs

### Engine integration

No engine changes. The `OrderManager` at `src/main/kotlin/com/qkt/app/OrderManager.kt:693` already splits `StandaloneOCO` into two atomic sub-orders, registers them as siblings, and cancels the survivor on fill. The DSL surface added in this phase routes through that pre-existing machinery.

## Migration from previous phase

Pure additions — no breaking changes.

| Before | After |
| --- | --- |
| `NOW` was an unrecognized identifier (parse error) | `NOW.<field>` and bare `NOW` parse as clock accessors |
| `OCO_ENTRY` was an unrecognized identifier (parse error) | `OCO_ENTRY { leg1, leg2 }` parses as a two-leg OCO action |
| `TIF GTD UNTIL <absolute-epoch-ms>` was the only way to express GTD | `TIF GTD UNTIL NOW + 10m` works as a relative deadline |

Existing strategies parse identically. Tests confirm no regression in the existing parser, lexer, and compiler suites.

## Usage cookbook

### Session-hour gated single-direction entry

```qkt
RULES
    WHEN NOW.hour_utc IN [7, 8, 13, 14, 15]
     AND POSITION.btc = 0
    THEN BUY btc SIZING 0.1
```

`NOW.hour_utc` is an integer 0–23 in UTC. Combine with `POSITION.<stream> = 0` to gate entries to specific hours.

### Sub-hour precision

```qkt
WHEN NOW.hour_utc = 14
 AND NOW.minute_utc < 5
 AND POSITION.gold = 0
THEN ...
```

Strategy fires only during the first 5 minutes of hour 14 UTC.

### Pending-pair OCO at session open

```qkt
WHEN NOW.hour_utc IN [6, 7, 12, 13, 14, 15]
 AND NOW.minute_utc = 55
 AND POSITION.gold = 0
THEN OCO_ENTRY {
    BUY  gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD UNTIL NOW + 10m,
    SELL gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close - 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD UNTIL NOW + 10m
}
```

Five minutes before each session-hour open, place `BUY STOP` above and `SELL STOP` below. The first one to trigger fills with its bracket; the other auto-cancels. The 10-minute GTD ensures neither leg outlives the session window if the price stays flat.

### Time-based exit via holding_duration

```qkt
WHEN POSITION.gold != 0
 AND POSITION.gold.holding_duration > 7200
THEN CLOSE gold ; LOG "winner timeout" pnl=POSITION.gold.pnl
```

`holding_duration` is seconds since position open (Phase 23 accessor). Closes the position 2 hours after fill regardless of P&L state.

### Weekday filter

```qkt
WHEN NOW.weekday < 5      -- Monday=0 through Friday=4 (ISO)
 AND ...
```

ISO weekday: Monday = 0, Sunday = 6. Useful for FX strategies that should skip weekend gaps.

## Testing patterns

### NOW eval against a FixedClock

```kotlin
val ctx = EvalContext(
    candle = ...,
    streams = ...,
    lets = emptyMap(),
    strategyContext = testStrategyContext(clock = FixedClock(time = mondayMs)),
)
val compiled = ExprCompiler().compile(NowAccessor(NowField.HOUR_UTC))
assertThat((compiled.evaluate(ctx) as Value.Num).v).isEqualByComparingTo("13")
```

`FixedClock` makes the test deterministic. The compiled expression reads the clock at evaluation time.

### OcoEntry compile assertion

```kotlin
val action = OcoEntry(
    leg1 = Buy("gold", ActionOpts(sizing = ..., orderType = Stop(NumLit(BigDecimal("2010"))))),
    leg2 = Sell("gold", ActionOpts(sizing = ..., orderType = Stop(NumLit(BigDecimal("1990"))))),
)
val sigs = ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
val oco = (sigs[0] as Signal.Submit).request as OrderRequest.StandaloneOCO
assertThat((oco.leg1 as OrderRequest.Stop).stopPrice).isEqualByComparingTo("2010")
assertThat((oco.leg2 as OrderRequest.Stop).stopPrice).isEqualByComparingTo("1990")
```

The compiler builds a `Signal.Submit(StandaloneOCO)` regardless of which underlying order shapes the legs use. The `OrderManager` handles routing in tests like `OrderManagerOcoTest`.

## Known limitations

- **Live MT5 routing — Phase 26b.** `MT5OrderTranslator.kt:12-20` today rejects everything except `Market` and `Bracket`. Pending stops, limits, and `StandaloneOCO` are not yet routed to MT5 native order types. Phase 26b adds `translateStop`, `translateLimit`, and `translateStandaloneOCO` plus cancel-on-fill verification.
- **Stacks — Phase 27.** Hedge-straddle's `stackLevels`/`stackTiers` need per-layer brackets, simultaneous firing on a state transition (cut → WINNER), and MFE+elapsed-time gating. qkt's existing `STACK` clause models pyramid-into-trend with shared brackets and sequential triggering. Different concept. Per the production analysis, stacks contribute ~148% P&L on 6-month windows. See `docs/planned.md` for the full Phase 27 entry.
- **Broker-local time deferred.** `NOW.hour_utc` is the only timezone. There's no `NOW.hour_<broker>`. Strategies that reason in broker-local time translate at strategy-author time.
- **Same-bar dual breach tiebreak.** When a single candle crosses both stop prices, the backtest picks the leg whose trigger is closer to the candle's open. Real-world fills depend on tick sequence; backtest is an approximation.
- **Multi-leg positions not yet modeled.** Pending-OCO mode doesn't need them (only one leg ever fills). Legacy market-mode hedge-straddle (both legs go live, cut loser, ride winner) needs a real position-model change. Deferred indefinitely — production uses pending mode.

## References

- Spec: `docs/superpowers/specs/2026-05-11-phase26-pending-oco-and-clock-design.md`
- Plan: `docs/superpowers/plans/2026-05-11-phase26-pending-oco-and-clock.md`
- Worked example: `examples/hedge-straddle/hedge-straddle.qkt`
- Engine OCO routing: `src/main/kotlin/com/qkt/app/OrderManager.kt:693`
- Phase 26b placeholder: `docs/planned.md` — "Phase 26b — MT5 native pending + OCO routing"
- Phase 27 placeholder: `docs/planned.md` — "Phase 27 — conditional bracketed stacks"
