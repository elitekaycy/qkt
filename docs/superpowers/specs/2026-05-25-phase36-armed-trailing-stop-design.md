# Phase 36 — Armed trailing stop

**Date:** 2026-05-25
**Issue:** [#48](https://github.com/elitekaycy/qkt/issues/48)

## Goal

DSL syntax `STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>` for a bracket-leg stop-loss that sits at a fixed `<distance>` from entry until the trade's MFE crosses `<threshold>`, then begins trailing the running favorable extreme at the same `<distance>`.

```qkt
BUY btc SIZING 0.1
    BRACKET {
      STOP LOSS TRAILING 5 AFTER MFE >= 10,
      TAKE PROFIT BY 50
    }
```

Semantics:
- **Pre-arm:** stop sits at `entry − distance` (BUY) or `entry + distance` (SELL). Same shape as a regular fixed-distance bracket stop.
- **Arming:** when MFE crosses `<threshold>`, stop "arms" and starts tracking the favorable extreme.
- **Post-arm:** stop sits at `hwm − distance` (BUY) or `hwm + distance` (SELL), where `hwm` is the running favorable extreme since fill.
- Arming is one-way — once armed, the stop stays armed.
- `<distance>` is the same value pre and post; only the reference point shifts (entry → hwm).

**Why this semantic (not breakeven pre-arm):** the original draft of this spec said pre-arm = `entry` (breakeven). That was a misread of the reference pa-quant strategy, which uses a wide fixed stop until MFE develops, then trails the same distance behind the favorable extreme. The breakeven-pre-arm interpretation would close GAP 1 in `hedge-straddle.qkt` only in name; the actual stop behavior would diverge from pa-quant's. The fixed-distance-pre-arm interpretation closes the gap honestly and has the secondary benefit that risk-based sizing (`SIZING RISK $ N`) sees a well-defined stop distance — `<distance>` — at all times, with no special case for the armed state.

## Motivation

The reference pa-quant strategy uses armed trailing stops for ~14% of exits — give the trade room to develop, then aggressively protect once a profit cushion forms. Without DSL support an author has to write it as a manual rule (`WHEN POSITION.btc > 0 AND POSITION.btc.mfe >= 10 AND btc.close < running_high - 5 THEN CLOSE btc`), losing the atomic bracket semantics and complicating multi-stream strategies.

## Current state (what already exists)

Per the Explore agent's map:

- **MFE tracking**: `MfeTracker.kt` per PRIMARY leg; `StrategyPositionTracker.primaryMfeTrackers` is fed from `onTick(symbol, price)`. DSL exposes it as `POSITION.<stream>.mfe`.
- **Trailing-stop fallback in OrderManager**: `trailingHwm` map + `updateTrailingHwm` + `trailLevel` + `triggerHit` (lines 1087–1142). Decoupled enough to layer an arming gate over.
- **Bracket parsing**: `parseBracket` (Parser.kt:1069) consumes `STOP LOSS <child>` and `TAKE PROFIT <child>` via `parseChildPrice` (line 659). `ChildPriceAst` sealed hierarchy in `ActionOpts.kt:93-109` has four variants (`ChildAt`, `ChildBy`, `ChildPct`, `ChildRr`).
- **Tokens**: `TRAILING` (44), `MFE` (103), `WITHIN` (32) already exist. `AFTER` is missing.

Gaps to fill:
- New token `AFTER`.
- New AST variant `ChildArmedTrail(trailDistance, mfeThreshold)`.
- Bracket-leg compilation can't return a single static price (the stop is dynamic); needs a new code path.
- OrderManager needs an "armed" state per managed stop order.

## Scope

### In scope

- Parse `STOP LOSS TRAIL <distance> AFTER MFE >= <threshold>` as a bracket leg.
- Validate at parse: `TAKE PROFIT TRAIL …` is rejected (armed trail is stop-only; TP is fundamentally a target, not a stop).
- Validate at compile: `<distance>` and `<threshold>` must be positive numeric literals or expressions evaluating positive.
- Engine: stop sits at entry until MFE crosses threshold, then trails at distance from the favorable extreme.
- OCO with the bracket's TAKE PROFIT — TP fill cancels the armed stop and vice versa.
- Works for both BUY and SELL positions (mirrored arming semantics).
- Tests at unit (parser, compiler, OrderManager) and e2e (Backtest) levels.

### Out of scope (deferred)

- Multiple disarm/rearm cycles — once armed, the stop stays armed.
- Native broker support — even if a broker (MT5) supports server-side trailing stops, the arming logic stays engine-managed for predictability. Brokers don't have a generic "arming" concept.
- `WITHIN <duration>` modifier (e.g. "arm only if MFE crossed threshold in the first hour"). Could layer on later; not in pa-quant's pattern.
- Armed trail as a standalone `ORDER_TYPE = ARMED_TRAIL …` outside a bracket. Bracket-only for now — the operator nearly always wants a TP alongside.
- Re-arming on flips: the trail tracks the favorable side of the original entry; if the position is closed and reopened in the same direction the armed-trail's state resets per the new leg.

## Approach

### 1. New token + AST node

Add `AFTER` to `TokenKind.kt` (between `WITHIN` and `MFE` to keep the grouping coherent). Reuse `TRAILING` for the trail keyword — no need for a distinct `TRAIL` since context disambiguates (bracket-leg position).

Add `ChildArmedTrail(trailDistance: ExprAst, mfeThreshold: ExprAst): ChildPriceAst` in `ActionOpts.kt`.

### 2. Parser

In `parseChildPrice` (Parser.kt:659), add a `TRAILING` branch:

```
TokenKind.TRAILING -> {
    advance()
    val distance = parseExpr()
    expect(AFTER, "expected AFTER after TRAILING <distance>")
    expect(MFE, "expected MFE after AFTER")
    expect(GTE, "expected '>=' after MFE")
    val threshold = parseExpr()
    ChildArmedTrail(distance, threshold)
}
```

Validate at parseBracket: TAKE PROFIT branch rejects ChildArmedTrail.

### 3. Bracket execution model

The existing `OrderRequest.Bracket` carries `stopLoss: BigDecimal` — a single price. That doesn't work for an armed trail (price is dynamic).

Four options for plumbing:

**Option A — Extend `Bracket.stopLoss` with a sum type.**
Add `sealed interface StopLossSpec { Fixed(price), ArmedTrail(distance, mfeThreshold) }`. Bracket carries `stopLoss: StopLossSpec`. OrderManager dispatches on type when spawning the child stop.

**Option B — Two child orders post-fill.**
On bracket entry fill, OrderManager spawns: (1) regular TP limit, (2) regular ArmedTrailingStop pending. Both share the OCO linkage. The "stopLoss" field on Bracket becomes the initial breakeven price; the dynamic behavior lives in a separate ArmedTrailingStop OrderRequest variant.

**Option C — Compile-time emit two requests.**
Compiler emits a regular bracket with a synthetic stop at entry, then schedules an ArmedTrailingStop pending order. OCO grouping happens at compile, not at fill.

**Option D — Optional sidecar field on Bracket. (Rejected, but documented because it's the tempting wrong answer.)**
Keep `Bracket.stopLoss: BigDecimal` and add `val armedTrail: ArmedTrailParams? = null` as a nullable sidecar. Looks like the smallest reasonable change — zero existing call sites need updating; the default value carries every existing test through unchanged. **Don't pick this.** Every future stop variant (volatility-based, time-based, indicator-triggered) adds another nullable field, and mutual exclusivity is no longer type-enforced. Two non-null at once becomes a silent logic error caught only at runtime. The right scaling shape is a sealed sum type where adding a variant forces the compiler to surface every site that must handle it.

Picking **A** — extending the Bracket type. Cleanest invariant: one Bracket request, one place where stop semantics live. Brokers that natively support brackets see only the entry + TP; the armed stop is engine-managed inside OrderManager and OCO-linked to the bracket's group.

**Why A over D specifically:** the upfront cost of A is mechanical — every existing Bracket call site wraps its `BigDecimal` price in `StopLossSpec.Fixed(price)`. ~15 test fixture files, all the same find-and-replace, no logic changes. ~30 min of fixture work. In return, every future stop variant (Volatility, Timed, IndicatorCross, …) becomes one new sealed-class member, and `when` over `StopLossSpec` becomes exhaustive — the compiler tells you every site to update. That's the scaling property we want.

### 4. OrderManager engine

Add `armed: Boolean` per managed stop in the existing `trailingHwm` infrastructure (or a parallel map). On each tick for the order's symbol:

1. Update `hwm` toward favorable side (same as today).
2. Compute MFE: `|hwm - entry|`.
3. If not armed and MFE >= threshold, set `armed = true`. Log it.
4. Stop level:
   - **Not armed:** `stop = entry ± distance` (sign by side — same as a regular fixed-distance bracket stop).
   - **Armed:** `stop = hwm ± distance` (sign by side; same calc as regular trailing).
5. Trigger when price crosses stop level (same as regular trailing).

The arming check happens before the stop-level computation. Once armed, the stop never disarms — even if price retreats back below the threshold, the trail stays active. Pre-arm and post-arm use the same `<distance>` value; only the reference point changes from `entry` to `hwm`.

### 5. Compilation

`ChildPriceResolver` doesn't fit the armed trail (it returns a static price). Compilation path:

- BracketAst.stopLoss is a `ChildPriceAst`. The action compiler currently calls `resolver.compile(stopLoss)` and gets a `CompiledChildPrice` that produces a price at submission.
- For `ChildArmedTrail`, the compiler builds a `StopLossSpec.ArmedTrail(distance, mfeThreshold)` directly — no per-tick resolver needed. The action emits an `OrderRequest.Bracket` carrying that spec.
- Existing variants (`ChildAt`, `ChildBy`, `ChildPct`, `ChildRr`) wrap into `StopLossSpec.Fixed(resolvedPrice)`.

### 6. Fail-safe behaviors

- **MFE never crosses threshold + position runs against trader.** Stop sits at `entry ± distance`; position exits at the fixed wide stop. Worst-case loss is bounded by `distance` regardless of whether the trail ever arms.
- **Arming threshold = 0.** Effectively a regular trailing stop from inception. Allow it — operators might want this for "trailing from inception with explicit syntax intent."
- **Threshold = arbitrarily large.** Stop never arms; behaves as a permanent fixed-distance bracket stop. Sensible degenerate behavior; allow.

### 7. Multi-leg / STACK_AT interaction

A `STACK_AT` leg can declare its own bracket. Each leg gets its own armed-trail state (per-leg arming). The MFE for each leg is computed from THAT leg's fill price, not the strategy's aggregate entry. This composes naturally with the existing per-leg `MfeTracker`.

## Validation rules

- `STOP LOSS TRAILING <distance> AFTER MFE >= <threshold>` parses to `ChildArmedTrail`.
- `TAKE PROFIT TRAILING …` is rejected at parse time with a pointed error.
- `<distance>` resolves to a positive `BigDecimal` at compile time. `<threshold>` resolves to a non-negative `BigDecimal` (zero is allowed and means "trail from inception").
- BUY pre-arm: stop sits at `entry - distance`. Post-arm: stop sits at `hwm - distance`, fires when price drops to it.
- SELL pre-arm: stop sits at `entry + distance`. Post-arm: stop sits at `hwm + distance`, fires when price rises to it.
- Stop arms exactly once; never disarms.
- Bracket OCO holds: TP fill cancels the armed stop and vice versa.
- Risk-based sizing (`SIZING RISK $ N`): the stop distance fed into sizing is `<distance>` itself — well-defined regardless of arming state. No special case needed.

## Testing strategy

### Unit tests

- `ParserArmedTrailingStopTest` — covers parse success, TP-TRAIL rejection, missing AFTER/MFE/>= tokens.
- `ChildPriceResolverTest` (extend) — ChildArmedTrail compiles to StopLossSpec.ArmedTrail.
- `OrderManagerArmedTrailTest` — pre-arm tick that crosses entry triggers; post-arm tick that drops by distance triggers; tick sequence that never crosses threshold stays at entry-stop.

### Integration tests

- `ArmedTrailEndToEndTest` — DSL strategy with BRACKET STOP LOSS TRAIL … AFTER MFE >= …, run through Backtest with a tick sequence that (a) crosses threshold, (b) doesn't. Verify trade behavior.

### Manual smoke

- Deploy a real strategy with armed trail against the paper broker. Confirm logs show "armed" event at the expected MFE.

## Docs targets

- `docs/reference/dsl/bracket.md` — add "Armed trailing stop" subsection with syntax + semantics + worked example.
- `docs/how-to/add-stop-loss.md` — add `STOP LOSS TRAIL … AFTER MFE >= …` to the decision table.
- `docs/phases/phase-36-armed-trailing-stop.md` — phase changelog.

## Backwards compatibility

Pure addition. New token `AFTER` and new AST variant. Existing strategies parse and run unchanged. The Bracket `OrderRequest` shape changes (stopLoss becomes a sum type), which is internal — affects no external API.

## Risk assessment

- **Medium: arming-then-immediate-retrace race.** A tick arms (MFE crosses threshold) AND in the same tick price retreats past the new trail level. The fire check happens in the same loop iteration as the arming check — confirm sequencing so the arm-and-fire-immediately case works (operator expectation: "arm, set trail at high - distance, fire if price already below"). Add explicit test for this.
- **Medium: per-leg MFE in STACK_AT contexts.** Per-leg MFE tracking (vs strategy-aggregate) needs to be checked once `MfeTracker` per-leg is in play. The existing `primaryMfeTrackers` is keyed by `(strategyId, symbol)` — for multi-leg same-symbol that's a question.
- **Low: BUY-side vs SELL-side sign mistakes.** Test both sides explicitly.
- **Low: PERCENT trail distance.** Existing TrailingStop supports both ABSOLUTE and PERCENT modes. Armed trail starts with ABSOLUTE only; PERCENT can layer later.

## References

- Issue: [#48](https://github.com/elitekaycy/qkt/issues/48)
- Predecessor: Phase 25C — `ORDER_TYPE = TRAILING BY` end-to-end ([#126](https://github.com/elitekaycy/qkt/issues/126))
- Related infra: `MfeTracker.kt`, `StrategyPositionTracker.primaryMfeTrackers`, OrderManager trailing-stop fallback.
