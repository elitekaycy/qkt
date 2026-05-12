# Phase 27 — Conditional bracketed stacks · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `STACK_AT MFE >= N WITHIN M BRACKET { ... }` DSL clauses that fire independent micro-trades during a live position's lifecycle. Each stack has its own bracket and tracks independently. Unlocks hedge-straddle's ~148% P&L driver.

**Architecture:** Replace singular `Position` with `LegBook` (collection of `PositionLeg`s). Existing `POSITION.<stream>` continues to return net quantity for backward-compat. New DSL accessor `POSITION.<stream>.mfe`. New `StackEngine` watches MFE per tick, fires conditional orders. New broker capability `MULTI_POSITION_PER_SYMBOL`.

**Tech stack:** Kotlin, JUnit 5 + AssertJ. No new dependencies. ~3 weeks of focused work. Plan is broken into 22 tasks each ~1-3 hours.

> Read the spec first: `docs/superpowers/specs/2026-05-12-phase27-conditional-bracketed-stacks-design.md`.

---

## Status & known gaps (2026-05-12)

Tasks 1-12 (compile-time pipeline, runtime orchestrator wiring, PaperBroker close detection) and Task 14 (capability gate) have shipped on `phase-27-impl`. The happy path — DSL `STACK_AT` → engine constructed on parent fill → tier fires on MFE crossing → stack signal flows through standard emit → parent-bracket close terminates engine — works end-to-end against PaperBroker.

Known gaps that follow-up tasks must close:

1. **`StrategyPositionTracker.applyFill` does not call `addStackLeg`.** All fills (including stack tier fills) get averaged into the PRIMARY leg via `apply()`. `LegBook`'s multi-leg schema is correct but inert. Future per-leg PnL, leg-level MFE, drift reconciliation are all wrong until fill routing learns to distinguish stack fills from primary fills (needs `parent_leg_id` correlation on the fill event).
2. **Parent-close detection only covers PaperBroker bracket-fallback.** The `closeWatchIds` mechanism in `ActionCompiler.closeWatchIdsFor` hardcodes the `${b.id}-tp` / `${b.id}-sl` naming from `OrderManager.submitBracketFallback`. Plain Market parents, native MT5 brackets, and strategy-emitted manual closes are not detected — engines keep firing after parent close. Clean fix needs a higher-level `BrokerEvent.LegClosed(strategyId, parentLegId, …)` driven by `parent_leg_id` correlation.
3. **STACK_AT + OCO and STACK_AT + STACK now reject at compile time** (Task 12 fixed Gaps #3 and #4 from the architecture review) — both combinations would have silently dropped the conditional clauses.
4. **No retroactive fire** — the engine first evaluates on the tick after `onPrimaryFilled`. If the parent fills already past a threshold, the tier won't fire until the next tick.
5. **Engine state is in-memory only** — no persistence across session restart.

Remaining tasks (Phase 27a):

- Task 13 — `POSITION.<stream>.mfe` DSL accessor (depends on tracker-side `MfeTracker` integration with `applyFill`)
- Task 15 — Backtest fidelity test
- Task 16 — End-to-end backtest test
- Task 17 — Update hedge-straddle example
- Task 18 — DSL docs
- Task 19 — Phase 27 changelog
- Task 20-22 — Pre-merge verification, P&L sanity check, production scaffold

---

### Task 1 — Recon: read position model surface

**Files:**
- Read: `src/main/kotlin/com/qkt/positions/Position.kt`
- Read: `src/main/kotlin/com/qkt/positions/PositionProvider.kt`
- Read: `src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt`
- Read: `src/main/kotlin/com/qkt/positions/StrategyPositionView.kt`
- Read: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` (find `PositionRef`, `StateAccessor`)
- Read: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` (find `compilePositionRef`, `compileStateAccessor`)
- Read: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (find POSITION.<stream>.<accessor> parser branch)

- [ ] Map every call site that constructs or mutates `Position`. List them in `findings/position-callsites.md` for reference during the migration.
- [ ] Baseline: `./gradlew test --no-daemon` green.

---

### Task 2 — `PositionLeg` data class

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/Position.kt` (or new `PositionLeg.kt`)

- [ ] Add `PositionLeg` (separate file, new):

```kotlin
package com.qkt.positions

import java.math.BigDecimal

/** Role of a position leg within its [LegBook]. */
enum class LegRole { PRIMARY, STACK }

/**
 * A single leg of a multi-leg position. PRIMARY legs open via normal BUY/SELL signals;
 * STACK legs open via `STACK_AT` conditional clauses and survive independently.
 *
 * [legId] is the qkt-side stable identifier (distinct from the broker ticket).
 * [parentLegId] is set for STACK legs to identify which primary spawned them.
 */
data class PositionLeg(
    val legId: String,
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
    val role: LegRole,
    val parentLegId: String? = null,
)
```

- [ ] Keep `Position` data class unchanged for backward compat. It becomes a derived view of `LegBook.netView()`.

---

### Task 3 — `LegBook` container

**Files:**
- Create: `src/main/kotlin/com/qkt/positions/LegBook.kt`
- Test: `src/test/kotlin/com/qkt/positions/LegBookTest.kt`

- [ ] Implement:

```kotlin
class LegBook(
    val symbol: String,
) {
    private val legs: MutableMap<String, PositionLeg> = ConcurrentHashMap()

    fun add(leg: PositionLeg): Unit { legs[leg.legId] = leg }
    fun close(legId: String): PositionLeg? = legs.remove(legId)
    fun all(): List<PositionLeg> = legs.values.toList()
    fun primary(): PositionLeg? = legs.values.firstOrNull { it.role == LegRole.PRIMARY }
    fun stacks(): List<PositionLeg> = legs.values.filter { it.role == LegRole.STACK }
    fun isEmpty(): Boolean = legs.isEmpty()
    fun netQuantity(): BigDecimal {
        var sum = BigDecimal.ZERO
        for (leg in legs.values) {
            sum = if (leg.side == Side.BUY) sum.add(leg.quantity) else sum.subtract(leg.quantity)
        }
        return sum
    }
    fun netView(): Position? { /* compute weighted avg entry, return Position or null if empty */ }
}
```

- [ ] Unit tests:
  - empty book → null netView, zero netQuantity
  - one PRIMARY BUY 0.1 @ 1.10 → netQuantity = +0.1, netView.avgEntryPrice = 1.10
  - PRIMARY BUY 0.1 @ 1.10 + STACK BUY 0.2 @ 1.12 → netQuantity = +0.3, weighted avg = 1.1133...
  - PRIMARY closed, STACK survives → netQuantity = +0.2, view's entryPrice = stack's
  - stacks() filters correctly

---

### Task 4 — `MfeTracker`

**Files:**
- Create: `src/main/kotlin/com/qkt/positions/MfeTracker.kt`
- Test: `src/test/kotlin/com/qkt/positions/MfeTrackerTest.kt`

- [ ] Implement:

```kotlin
/**
 * Tracks max favorable excursion (MFE) of a position since [entryPrice].
 *
 * For a BUY position, MFE = max(price - entry, 0) over all observed prices.
 * For a SELL position, MFE = max(entry - price, 0).
 *
 * MFE never decreases. Reset by constructing a new instance.
 */
class MfeTracker(
    private val side: Side,
    private val entryPrice: BigDecimal,
) {
    private var mfe: BigDecimal = BigDecimal.ZERO

    fun onTick(price: BigDecimal) {
        val excursion = when (side) {
            Side.BUY -> price - entryPrice
            Side.SELL -> entryPrice - price
        }
        if (excursion > mfe) mfe = excursion
    }

    fun value(): BigDecimal = mfe
}
```

- [ ] Tests:
  - BUY entry 1.10, ticks 1.11 → MFE = 0.01
  - BUY 1.10, ticks 1.05 then 1.08 → MFE = 0
  - BUY 1.10, ticks 1.15 then 1.12 → MFE stays 0.05 (never decreases)
  - SELL entry 1.10, ticks 1.05 → MFE = 0.05
  - Zero/negative excursion → MFE stays 0

---

### Task 5 — `StrategyPositionTracker` rework

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/StrategyPositionTracker.kt`
- Test: extend existing test

- [ ] Internally store `Map<strategyId, Map<symbol, LegBook>>` instead of `Map<strategyId, Map<symbol, Position>>`.
- [ ] `positionFor(strategyId, symbol): Position?` → `legBook[strategyId][symbol]?.netView()`.
- [ ] Add `legBookFor(strategyId, symbol): LegBook?` (new public method for accessor compile).
- [ ] On `addFill` (or whatever the existing fill-handling method is): create a PRIMARY leg if the book is empty for that symbol, OR update the existing PRIMARY's quantity (averaging the entry price) for BUY/SELL of the same direction. For opposite-direction fills, reduce the existing leg's quantity; if it reaches zero, remove the leg.
- [ ] Add `addStackLeg(strategyId, symbol, leg: PositionLeg)` for the stack engine to call directly.
- [ ] Existing tests for `Position.quantity` and `avgEntryPrice` continue to pass — verify.

---

### Task 6 — DSL accessor `POSITION.<stream>.mfe`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ExprAst.kt` — extend `StateSource` enum
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — `POSITION.<stream>.mfe` branch
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` — `compileStateAccessor` MFE case
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserPositionAccessorTest.kt`

- [ ] Add to the `StateSource` enum: `POSITION_MFE`.
- [ ] Parser accepts `POSITION.<stream>.mfe` → `StateAccessor(StateSource.POSITION_MFE, streamAlias)`.
- [ ] Compiler implements `POSITION_MFE` evaluator: read the leg book's primary leg, look up the MfeTracker for that primary, return its current value as `BigDecimal`.
- [ ] If no primary leg exists or no MfeTracker is attached, return `Value.Undefined`.
- [ ] Tests:
  - `LET m = POSITION.gold.mfe` parses
  - Eval against a strategy with an open primary leg and a known MFE returns the value
  - Eval against an empty position returns Undefined

---

### Task 7 — Lex new keywords: `STACK_AT`, `MFE`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Test: extend `LexerTest`

- [ ] Add `STACK_AT`, `MFE` to the enum. (`OF`, `MAIN` are already valid identifiers; no new token needed unless we want them reserved.)
- [ ] Verify keyword case-insensitivity (handled by existing `KEYWORDS` table).

---

### Task 8 — AST: `StackAtClause`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/ActionOpts.kt`

- [ ] Add:

```kotlin
data class StackAtClause(
    val mfeThreshold: ExprAst,           // e.g. NumLit(BigDecimal("1000")) or a more complex expr
    val withinDuration: DurationAst,
    val sizing: SizingAst,
    val bracket: BracketAst,
)

data class ActionOpts(
    // ... existing fields ...
    val stackAts: List<StackAtClause> = emptyList(),
)
```

---

### Task 9 — Parser: `STACK_AT MFE >= <expr> WITHIN <duration> ...`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserStackAtTest.kt`

- [ ] In `parseActionOpts`, add a case for `STACK_AT`. Parser consumes:

```
STACK_AT MFE >= <expr> WITHIN <duration>
    SIZING <sizing>
    BRACKET { <bracket> }
```

- [ ] Accumulate multiple `STACK_AT` clauses into `actionOpts.stackAts`.
- [ ] Tests:
  - Single STACK_AT parses with correct threshold/duration/sizing/bracket
  - Multiple STACK_AT clauses on one BUY action
  - STACK_AT must include MFE keyword (not just any expr)
  - Missing WITHIN → parse error

---

### Task 10 — Compile path: leg ID generation

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`

- [ ] When BUY/SELL emits a `Signal.Submit`, propagate a generated `legId`. Reuse the existing `IdGenerator`:
  - PRIMARY leg's id = the order's clientOrderId (or a derived "<orderId>-primary")
  - STACK leg's id = "<parentLegId>-stack-<tierIdx>"
- [ ] No behavior change yet — just adds a field that downstream tracking will use.

---

### Task 11 — `StackEngine`

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/StackEngine.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/StackEngineTest.kt`

- [ ] Implement:

```kotlin
class StackEngine(
    private val parentLegId: String,
    private val parentSymbol: String,
    private val parentSide: Side,
    private val parentSizing: BigDecimal,  // resolved at parent-fill time
    private val tiers: List<CompiledStackTier>,
    private val clock: Clock,
    private val emit: (Signal) -> Unit,
) {
    private val mfeTracker: MfeTracker
    private val firedTierIndices = mutableSetOf<Int>()
    private val abandonedTierIndices = mutableSetOf<Int>()
    private val openedAt = clock.now()

    fun onTick(price: BigDecimal) {
        mfeTracker.onTick(price)
        val mfe = mfeTracker.value()
        val elapsed = clock.now() - openedAt
        for ((idx, tier) in tiers.withIndex()) {
            if (idx in firedTierIndices || idx in abandonedTierIndices) continue
            if (mfe >= tier.mfeThreshold && elapsed <= tier.withinMs) {
                emit(buildStackOrder(idx, tier, price))
                firedTierIndices += idx
            } else if (elapsed > tier.withinMs) {
                abandonedTierIndices += idx
            }
        }
    }

    fun mfe(): BigDecimal = mfeTracker.value()

    private fun buildStackOrder(idx: Int, tier: CompiledStackTier, price: BigDecimal): Signal {
        val stackLegId = "$parentLegId-stack-$idx"
        return Signal.Submit(
            OrderRequest.Bracket(
                id = stackLegId,
                symbol = parentSymbol,
                side = parentSide,
                quantity = parentSizing.multiply(tier.sizingFactor),
                entry = OrderRequest.Market(/* fields */),
                stopLoss = tier.computeSL(price, parentSide),
                takeProfit = tier.computeTP(price, parentSide),
                timeInForce = TimeInForce.GTC,
                timestamp = clock.now(),
            ),
        )
    }
}

data class CompiledStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val sizingFactor: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)
```

- [ ] Unit tests:
  - Single tier fires when MFE crosses threshold
  - Tier doesn't re-fire on subsequent ticks
  - Tier abandoned when WITHIN elapses without MFE reaching threshold
  - Multiple tiers fire in correct order as MFE crosses thresholds
  - Stack order's `quantity` = `parentSizing × sizingFactor`

---

### Task 12 — Stack engine lifecycle in `AstCompiler` / `CompiledStrategy`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CompiledStrategy.kt` (or equivalent runtime container)

- [ ] When a BUY/SELL fills (a PRIMARY leg opens), if its `actionOpts.stackAts.isNotEmpty()`:
  - Compile the tiers (resolve expressions to `CompiledStackTier`)
  - Construct a `StackEngine` keyed by `parentLegId`
  - Register the engine in a per-strategy map: `Map<legId, StackEngine>`
- [ ] On every tick: invoke `engine.onTick(currentPrice)` for each active stack engine.
- [ ] When the PRIMARY leg closes (`OrderFilled` of opposite side, or `OrderCancelled` for the position), remove the engine from the map.
- [ ] Stack legs themselves do NOT spawn nested stack engines (matches the spec's "stack-on-stack disallowed" decision).

---

### Task 13 — Broker capability `MULTI_POSITION_PER_SYMBOL`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/OrderTypeCapability.kt`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Protocol.kt`
- Modify: `src/main/kotlin/com/qkt/broker/PaperBroker.kt` (or wherever its capabilities are declared)
- Modify: `src/main/kotlin/com/qkt/broker/bybit/spot/BybitSpotBroker.kt`
- Modify: `src/main/kotlin/com/qkt/broker/bybit/linear/BybitLinearBroker.kt`

- [ ] Add to enum:

```kotlin
/** The broker supports multiple positions on a single symbol simultaneously. */
MULTI_POSITION_PER_SYMBOL,
```

- [ ] Declare for MT5 unconditionally (MT5 supports per-ticket positions).
- [ ] Declare for PaperBroker unconditionally (trivially supports multi-leg).
- [ ] Declare for BybitLinearBroker conditional on a `isHedgeMode()` probe at startup (or via config).
- [ ] Do NOT declare for BybitSpotBroker.

---

### Task 14 — Capability check at strategy compile time

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`

- [ ] If a `BUY`/`SELL` action has `stackAts.isNotEmpty()`, verify the routing broker declares `MULTI_POSITION_PER_SYMBOL`. If not, fail compile with a clear error including the broker name and the stream alias.
- [ ] This requires knowing which broker handles each stream — already wired through `BrokerFactory`/`CompositeBroker`.

---

### Task 15 — Backtest fidelity: PaperBroker multi-leg support

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- Test: extend `PaperBrokerTest`

- [ ] Verify the PaperBroker can hold multiple distinct positions on the same symbol. It probably already does (it's just a list of working/filled orders) but confirm and add explicit tests.

---

### Task 16 — End-to-end backtest test

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/compile/StackAtBacktestTest.kt`

- [ ] Strategy with a primary BUY and one STACK_AT MFE >= 50 WITHIN 30m clause. Run against synthetic candles that:
  - Open primary at 1.10
  - Move to 1.151 (MFE = 0.051 > 0.050 threshold) within window → stack fires
  - Stack opens as its own leg, tracked independently
  - Primary's bracket closes at 1.20 → primary leg removed
  - Stack continues; its own bracket hits → stack leg removed

Assert exactly:
- 2 OrderAccepted events (primary + stack)
- 2 OrderFilled events
- 2 close events (primary + stack)
- Final LegBook is empty

---

### Task 17 — Example: hedge-straddle with stacks

**Files:**
- Modify: `examples/hedge-straddle/hedge-straddle.qkt`
- Modify: `examples/hedge-straddle/README.md`

- [ ] Add 3 `STACK_AT` clauses to the BUY and SELL legs of the OCO_ENTRY:

```qkt
OCO_ENTRY {
    BUY  gold ORDER_TYPE = STOP AT gold.close + 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD UNTIL NOW + 10m
         STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 },
    SELL gold ORDER_TYPE = STOP AT gold.close - 5
         BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
         TIF GTD UNTIL NOW + 10m
         STACK_AT MFE >= 10 WITHIN 30m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 20 WITHIN 60m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
         STACK_AT MFE >= 30 WITHIN 90m SIZING 0.06 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
}
```

- [ ] Update README: remove the "what's not here yet — stacking" caveat; add a "stacking semantics" subsection.

---

### Task 18 — DSL docs

**Files:**
- Modify: `docs/reference/dsl/actions.md` — add `STACK_AT` section
- Create: `docs/reference/dsl/stack-at.md` (or extend `stack.md` with the new variant)
- Modify: `docs/reference/dsl/expressions.md` — add `POSITION.<stream>.mfe`

- [ ] STACK_AT page covers: syntax, semantics, lifecycle (fire-once-per-position), broker capability requirement, common gotchas (stack on stack disallowed, primary closes don't auto-close stacks).
- [ ] MFE accessor page covers: BUY vs SELL definition, returns 0 before any tick, returns Undefined when no position.

---

### Task 19 — Phase 27 changelog

**Files:**
- Create: `docs/phases/phase-27-conditional-bracketed-stacks.md`
- Modify: `docs/phases/index.md`
- Modify: `docs/planned.md` — remove Phase 27 entry; promote multi-leg-positions to next exploratory bucket

- [ ] Per qkt skill §6 template. Cookbook examples:
  - Basic single-tier stack on a momentum strategy
  - Hedge-straddle with three tiers (cross-reference the example)
  - Stack with elapsed-time abandon
  - Stack on a SELL position (verify direction handling)

---

### Task 20 — Pre-merge verification

- [ ] `./gradlew build --no-daemon` — BUILD SUCCESSFUL incl. ktlint
- [ ] `bash tests/smoke-install.sh` — green (the hedge-straddle parse step now exercises stack clauses)
- [ ] Read every commit message; confirm conventions per qkt skill §3
- [ ] Push branch, open PR

---

### Task 21 — Backtest P&L sanity check

**Files:**
- New test or manual: run hedge-straddle-with-stacks vs without on a fixed synthetic dataset

- [ ] Confirm the stack version's P&L is materially higher than the no-stack version (the spec's quoted ~+148% from pa-quant is on the production dataset; on a synthetic test set it just needs to be measurably positive).

---

### Task 22 — Production scaffold update

**Files (outside repo):**
- Modify: `~/Desktop/personal/qkt-strategies-live/strategies/hedge-straddle.qkt`
- Modify: `~/Desktop/personal/qkt-strategies-live/README.md`

- [ ] Mirror the in-repo example's stack clauses.
- [ ] Update the README to note Phase 27 has shipped; recommend re-running paper for a week before live exposure on the stack-enabled version (the multi-leg fill-event flow is new).

---

## Self-review checklist

- [ ] Every spec section maps to one or more tasks
- [ ] LegBook tests cover: empty, single-PRIMARY, PRIMARY+STACK, PRIMARY-closed-STACK-survives, multiple stacks
- [ ] MfeTracker tests cover: BUY direction, SELL direction, never-decreases, no-favorable-moves
- [ ] StackEngine tests cover: fire-on-threshold, no-re-fire, abandon-on-timeout, multi-tier ordering
- [ ] Backtest fidelity test exercises the end-to-end flow at least once
- [ ] Capability gating fails at compile time with a useful error
- [ ] No placeholders in any task — every step has concrete code or a verification command
- [ ] DSL backward-compat: existing strategies (no STACK_AT) parse and run identically
- [ ] State recovery: old daemon state files load as single-PRIMARY LegBooks (migration described in spec)
