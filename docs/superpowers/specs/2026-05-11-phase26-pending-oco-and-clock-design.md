# Phase 26 — Pending-entry OCO and clock accessors

> Spec for the surface that unlocks pending-order straddle strategies. Builds directly on the execution-layer `StandaloneOCO` primitive (already present in `com.qkt.execution.OrderRequest`) by adding a DSL syntax to express it, and on the engine's pervasive `Clock` injection by adding DSL-visible time accessors.

## Goal

Make pending-entry OCO strategies — particularly the production hedge-straddle — expressible as a single `.qkt` file and runnable through the existing live pipeline. The change is bounded: DSL surface only, plus broker capability verification. No changes to the position model, no new strategy runtime concepts.

## Motivation

Reading the actual hedge-straddle implementation at `../fxquant/pa-quant/src/strategies/hedge-straddle/` revealed that the production strategy uses **pending-order entries**, not simultaneous market orders. The state machine is:

```
IDLE → PLACE_PENDING(BUY_STOP + SELL_STOP, OCO-linked, GTD-expiring)
     → one fills → other auto-cancels
     → WINNER (single position with BRACKET set at placement time)
     → CLOSED (target hit, SL hit, or timeout)
```

Only **one leg ever fills**. The "both legs alive" assumption from the legacy market-order mode does not apply. This means **multi-leg positions are not required** for pending-mode hedge-straddle — a finding that reduces the engine work from a multi-week phase to a focused 3-5 day phase.

Three gaps stand between the engine today and a pending-mode hedge-straddle port:

1. **No DSL syntax for entry-pair OCO.** The current `OcoAst` is post-fill (SL/TP on a single position). Submitting two pending entries linked one-cancels-other is not currently expressible.
2. **No DSL-visible clock.** The engine has `Clock` everywhere internally, but `.qkt` files cannot say "the current UTC hour is X". Without this, session-window gating (`07-09 UTC` etc.) is impossible.
3. **MT5 broker OCO routing is unverified.** Phase 17 added MT5 broker support; `StandaloneOCO` lives in the execution layer; whether the MT5 broker translates them into native MT5 order linkage is currently untested.

This phase closes all three with the smallest possible engine change.

## Scope

### In scope

**A. Entry-pair OCO in the DSL** (~150 LOC + tests)

A new top-level action form, `OCO_ENTRY { ... }`, takes exactly two child entry actions and compiles to `OrderRequest.StandaloneOCO`. Each child is a normal `BUY`/`SELL` with all the usual modifiers (SIZING, order-type, BRACKET, TIF). Each child gets its own BRACKET — when the leg that fills wires up its own SL/TP. When either child fills, the broker auto-cancels the other.

DSL surface:

```qkt
THEN OCO_ENTRY {
    BUY  gold SIZING 0.20 STOP AT gold.close + 50 * pip(gold)
         BRACKET { STOP_LOSS BY 1800, TAKE_PROFIT BY 1500 }
         TIF GTD UNTIL now + 10m,
    SELL gold SIZING 0.20 STOP AT gold.close - 50 * pip(gold)
         BRACKET { STOP_LOSS BY 1800, TAKE_PROFIT BY 1500 }
         TIF GTD UNTIL now + 10m
}
```

AST node:

```kotlin
data class OcoEntry(
    val leg1: ActionAst, // must be Buy or Sell
    val leg2: ActionAst, // must be Buy or Sell
) : ActionAst
```

Parser invariants:
- Exactly two children (parse error on 0, 1, or 3+).
- Each child must be `BUY` or `SELL`. `CLOSE`/`CANCEL`/`LOG` inside `OCO_ENTRY` is a parse error — those actions don't have entry-pair semantics.
- Children may be opposite sides on the same stream (the hedge-straddle case) **or** different streams (e.g., long EURUSD vs. long GBPUSD — pairs trading on whichever moves first). The DSL doesn't restrict; the broker decides whether it can route across symbols.

Compiler emits `StandaloneOCO(leg1Request, leg2Request)`. Each leg's BRACKET stays attached to that leg's `OrderRequest`. The broker's order manager handles the cancel-on-fill linkage.

**B. Clock accessors** (~80 LOC + tests)

New DSL primary expressions, accessible via the `NOW.<field>` namespace:

| DSL form | Returns | Source |
| --- | --- | --- |
| `NOW.hour_utc` | Integer 0–23 — current UTC hour | `Clock.now()` interpreted as UTC |
| `NOW.minute_utc` | Integer 0–59 | same |
| `NOW.weekday` | Integer 0–6, Monday=0 (ISO) | same |
| `NOW.date_utc` | Integer days-since-epoch | same |
| `NOW.epoch_ms` | Long millis since epoch | direct `Clock.now()` |

Plus one binary form for relative deadlines:

| DSL form | Returns | Source |
| --- | --- | --- |
| `NOW + <duration>` | Long millis = `Clock.now() + duration.toMillis()` | for `TIF GTD UNTIL now + 10m` |

The compile path: `NowAccessor(field: NowField)` AST node → CompiledExpr that reads from the EvalContext's clock. Existing `ZoneOffset.UTC` arithmetic in `RollingHighBreakoutStrategy` is the reference for UTC derivation.

All accessors return engine-determinate values during backtest replay — the backtest already uses `ctx.clock.now()` everywhere, so a clock accessor reads the simulated time, not the wall clock. This preserves determinism.

**C. MT5 broker OCO verification** (~50 LOC test + any small impl bug fixes)

The `StandaloneOCO` order request already routes through the order manager. The MT5 broker (Phase 17) needs to be confirmed to:
1. Submit both legs to MT5 (each as a pending STOP order).
2. Receive the fill event for one leg.
3. Submit a `cancelOrder` call for the unfilled leg's MT5 ticket.
4. Report the OCO group as completed once one leg either fills or both expire.

This is a parity test, not new functionality. The test fixture (`src/test/kotlin/com/qkt/broker/mt5/...`) replays a synthetic OCO submission against the MT5 fake broker and asserts: one fill → cancel call → group closed. If the MT5 broker today rejects `StandaloneOCO` or drops the cancel, the bug fix is part of this phase.

**D. Worked example** (~100 LOC strategy + README + smoke addition)

`examples/hedge-straddle/hedge-straddle.qkt` — a complete port of the production pending-mode straddle. Configuration values (`50p` offset, `1800p` SL, `1500p` TP, `15m` straddle timeout, `2h` winner timeout) are inlined as DSL constants. Time-based winner exit uses `POSITION.gold.holding_duration > 7200` rule. Session-hour gate uses `NOW.hour_utc IN [7, 8, 13, 14, 15, 16]`.

The example is added to `tests/smoke-install.sh` as a parse-only step (it requires Exness data the smoke can't provision).

### Out of scope

**Multi-leg positions (legacy market-mode hedge-straddle).** The README's "both legs go live, cut loser, ride winner" design needs a real position-model change. Production doesn't use this mode, so we don't pay for it. If a future strategy needs simultaneous live opposite-side positions, that's a separate phase.

**Win-rate circuit breaker.** Hedge-straddle's `wrCircuitWindow` / `wrCircuitPause` features. This belongs at the daemon layer, not the strategy DSL — a daemon-level kill switch that pauses any strategy whose recent win-rate drops below a threshold. Out of scope here.

**Stack / pyramid in WINNER phase — known omission.** Hedge-straddle's `stackLevels` and `stackTiers` (`types.ts:214-235`) cannot be modeled by qkt's existing `STACK` clause. Four concrete gaps:

1. **Per-layer brackets.** qkt's STACK uses one shared SL+TP for the combined position; hedge-straddle's stacks each have their own SL (200p) and TP (varies by tier) because each stack is an independent micro-trade that must stop out fast on reversal.
2. **Simultaneous triggering.** qkt's STACK layers fire in order (layer 2 waits for layer 1 to fill, then watches for its spacing); hedge-straddle's `stackLevels` all fire on the same candle after the cut.
3. **Triggered during WINNER phase.** qkt's STACK exists from the moment the seed fires, modulated only by price/time; hedge-straddle's stacks fire conditionally on a strategy state transition (cut event) that qkt's stack lifecycle doesn't model.
4. **MFE-and-elapsed-time gating.** `stackTiers` fires when `winnerMfe >= tier.mfePips AND elapsed_minutes <= tier.maxMinutes`; qkt's STACK has spacing-based price triggers and a single `WITHIN` cancellation deadline.

**P&L impact:** the README's analysis shows `stackLevels=[{1000,200,0.30},{2000,200,0.30},{3000,200,0.30}]` boosts 6-month P&L from $1,478 → $3,673 (+148%). Stacking is a primary profit driver, not optional polish.

**Phasing:** Phase 26 ships hedge-straddle *without stacking*. The pre-stack version is still a real strategy (~$3,697 over 6 years at 0.01L per the README) and exercises the full live pipeline. Phase 27 (separate spec, written when Phase 26 is closer to shipping) adds **conditional bracketed stacks** as a real model change: `STACK_AT MFE >= N PIPS WITHIN M MINUTES BRACKET { ... }`, with each stack tracked as a sibling position with its own broker tickets and bracket. That's a multi-leg-shaped change in constrained form (1 primary + N satellites), 2-3 weeks of work, and depends on broker capability surface (MT5: yes; Bybit Linear hedge-mode: yes; Bybit Spot: no).

**Adaptive ATR thresholds.** Hedge-straddle's `cutAtrMultiple`, `pendingRegimes`, etc. Easily expressed in the DSL today via `atr(gold, 14)` arithmetic. No new engine work needed.

**Order-flow / delta-divergence exit.** Hedge-straddle's `ofDivergenceExit`. Requires tick-by-tick order-flow data that qkt's market-data layer does not yet ingest. Future phase.

**Quality filters at placement time.** `pendingMinPrevRangePips`, `pendingMinAtrPips`, `pendingMinVolRatio`. All expressible via standard WHEN conditions in the DSL.

**Per-hour profile** (`pendingHourProfile`). Configuration sugar; the strategy author can express the same logic with `CASE WHEN` on `NOW.hour_utc`.

## Architecture

### DSL layer

Two new AST nodes:

```kotlin
// In RuleAst.kt (next to existing ActionAst variants):
data class OcoEntry(
    val leg1: ActionAst, // Buy | Sell
    val leg2: ActionAst, // Buy | Sell
) : ActionAst

// In ExprAst.kt:
data class NowAccessor(
    val field: NowField,
) : ExprAst

enum class NowField {
    HOUR_UTC,
    MINUTE_UTC,
    WEEKDAY,
    DATE_UTC,
    EPOCH_MS,
}
```

Parser changes:
- `OCO_ENTRY` token added to `TokenKind` (lexer registers the keyword via the existing case-folded keyword table).
- `parseAction()` dispatches `OCO_ENTRY → parseOcoEntry()` which expects `LBRACE`, two comma-separated child actions, `RBRACE`. Each child is parsed via the existing `parseAction()` recursion. Validation: exactly two children, both `Buy` or `Sell`.
- `NOW` token added. The primary-expression parser sees `NOW.<field>` and produces `NowAccessor(field)`. `NOW + <duration>` is handled in the additive parser by recognizing `NowAccessor` + `Duration` as a special form.

Compiler changes:
- `ActionCompiler.compile(OcoEntry)` → builds two `OrderRequest` values from leg1 and leg2 (reusing the existing per-leg compile path), wraps them in `StandaloneOCO`, and emits `Signal.Submit(oco)`.
- `ExprCompiler.compile(NowAccessor)` returns a `CompiledExpr` that reads `ctx.clock.now()` and projects the field.
- The `NOW + duration` form compiles to a binary-op that adds `duration.toMillis()` to the clock value. Returns a Long (epoch ms), suitable for `TIF GTD UNTIL <ms>`.

Engine: no changes. `StandaloneOCO` and the order-manager OCO routing already exist.

### Broker layer

MT5 broker (`com.qkt.broker.mt5.Mt5Broker`) needs verified behavior for `StandaloneOCO`:

1. **Submit:** translate `OrderRequest.StandaloneOCO(leg1, leg2)` into two MT5 `OrderSendRequest`s, both as `ORDER_TYPE_BUY_STOP` or `ORDER_TYPE_SELL_STOP` per leg. Tag both with the same `groupId`.
2. **Fill event:** when MT5 sends an `OnTradeTransaction` for one leg, the order manager marks it filled and emits a `cancelOrder` for the other leg's MT5 ticket.
3. **Both-expire event:** GTD on both legs handles the "no fill before window closes" case. MT5 auto-cancels both.

Other brokers (`BybitSpotBroker`, `BybitLinearBroker`) need a capability declaration: `supportsOco: Boolean`. Bybit Spot does not support pending-stop OCO; declaring `false` means a strategy that uses `OCO_ENTRY` against a Bybit Spot stream fails at compile time with a clear error. Bybit Linear supports conditional orders and could implement this in a future phase — for now, declare `false` and don't gate on it.

### Determinism in backtest

`NOW.<field>` reads from `ctx.clock`. In backtest, the clock is the simulated time, advancing as candles close. So `NOW.hour_utc` evaluates to the UTC hour of the current simulated candle, not the wall clock. This is the right behavior — strategies should test at the time they think they're at.

`OCO_ENTRY` in backtest: both legs are tracked by the backtest order book. When the simulated price triggers one leg's stop price, that leg fills, the other is removed from the working-orders set. Same as a real broker, just simulated. The mock broker's OCO handling already does this for `StandaloneOCO` (per Phase 1 spec).

## Test plan

### Parser tests (DSL surface)

- `OCO_ENTRY` with two BUYs / two SELLs / one BUY + one SELL — all parse.
- `OCO_ENTRY` with zero, one, or three legs — parse error.
- `OCO_ENTRY` containing `CLOSE` or `LOG` — parse error.
- `NOW.hour_utc` parses as `NowAccessor(HOUR_UTC)`.
- `NOW + 10m` parses as a binary add of `NowAccessor(EPOCH_MS)` and a duration literal.
- `TIF GTD UNTIL now + 10m` parses end-to-end.

### Compiler tests

- `OcoEntry` compiles to a `Signal.Submit(StandaloneOCO(...))` with both legs preserved.
- Each child's BRACKET is attached to that child's `OrderRequest`, not to the OCO wrapper.
- `NowAccessor(HOUR_UTC)` evaluated against a fixed clock returns the right integer.
- A WHEN condition gated on `NOW.hour_utc IN [7, 8]` fires at hour 7 and 8, not 6 or 9.

### Backtest fidelity tests

- A strategy with `OCO_ENTRY { BUY @ +50, SELL @ -50 }` and a simulated candle that breaks +50 first → only the BUY fills, SELL is removed from working orders.
- Same strategy, candle that breaks -50 first → only the SELL fills.
- Candle that breaks neither → both pending orders remain. After GTD expiry → both auto-cancel.
- Candle that breaks both within the same bar (rare but possible on huge wicks) → the order-manager's deterministic tiebreak rule chooses one. Document the rule (e.g., "BUY checked first").

### Broker integration tests

- MT5 broker fake: submit `StandaloneOCO`, deliver fill for leg1, assert cancel-order request for leg2.
- MT5 broker fake: submit `StandaloneOCO`, deliver GTD-expiry for both, assert group closed without fills.
- Bybit Spot broker: submit `StandaloneOCO`, assert capability rejection at compile/submit time.

### End-to-end test

- `examples/hedge-straddle/hedge-straddle.qkt` parses, backtests against a synthetic data set with one hour-7 spike up and one hour-13 spike down, produces exactly two trades, one BUY direction, one SELL direction, both with the configured TP/SL.

## Migration considerations

**No breaking changes.** This is purely additive at the DSL surface. Existing strategies that don't use `OCO_ENTRY` or `NOW` are unaffected. The engine-level `StandaloneOCO` already existed; this phase adds a DSL way to reach it.

**The legacy `OcoAst`** (post-fill OCO between SL and TP on a single position) is unchanged. Some renaming may be helpful for clarity — `OcoAst` becomes `OcoExit`, the new `OcoEntry` is the additive surface — but this is style, not semantics.

## Acceptance criteria

- All parser, compiler, backtest, and broker tests above pass.
- `examples/hedge-straddle/hedge-straddle.qkt` parses and backtests cleanly.
- The MT5 broker has verified OCO routing (group of two pending → one fills → other auto-cancels).
- `docs/reference/dsl/actions.md` has an `OCO_ENTRY` section.
- `docs/reference/dsl/now.md` (new page) documents `NOW.<field>` accessors.
- Phase 26 changelog at `docs/phases/phase-26-pending-oco-and-clock.md` covers the new surface with worked examples.
- The hedge-straddle example is added to `tests/smoke-install.sh` as a parse-only validation step.

## Open questions

1. **Tie-break on same-bar dual fill.** Both stop prices breached within one candle. Current options: (a) check BUY first deterministically, (b) check whichever price the candle's open is closer to (most realistic), (c) error and require finer timeframe. Recommendation: (b) — model realistic execution. Needs explicit test coverage.

2. **`NOW.hour_local` (broker timezone) vs. only `NOW.hour_utc`.** Some brokers report timestamps in their local timezone. Should the DSL expose a `NOW.hour_<broker_alias>` form that reads the broker profile's timezone? For now: ship UTC-only, defer broker-local to a future phase if requested.

3. **Pluralizing `OCO_ENTRY` to OCO_GROUP for 3+ legs.** Some strategies want three pending entries linked OCO (e.g., a regime-detection strategy that places three direction probes). Current spec: exactly two. Generalization to N is straightforward (`leg1, leg2` → `legs: List<ActionAst>`) — but YAGNI for now.

4. **Cancel-on-strategy-stop semantics.** When a strategy is stopped (daemon shutdown, strategy-level kill), should outstanding OCO pending orders auto-cancel? Current order manager: probably yes via the strategy's existing lifecycle hook. Confirm and document.

## References

- `src/main/kotlin/com/qkt/execution/OrderRequest.kt:184` — `StandaloneOCO` (the engine primitive this phase exposes)
- `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt:90` — `OcoAst` (the existing post-fill OCO; not changed by this phase)
- `src/main/kotlin/com/qkt/broker/mt5/` — Phase 17 MT5 broker (where the OCO verification work happens)
- `../fxquant/pa-quant/src/strategies/hedge-straddle/types.ts:134-173` — the production hedge-straddle pending-mode config
- `../fxquant/pa-quant/src/engine/straddle-engine.ts:563-585` — the resilient pending-order placement code (reference for what the qkt-side needs to handle)
