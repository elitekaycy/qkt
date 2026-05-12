# Phase 27 — Conditional bracketed stacks

> The ~148% P&L driver for hedge-straddle. Adds independent stacks during a live position's lifecycle, MFE-and-time gated, each with its own bracket. Requires a real model change: multi-position-per-symbol tracking with per-position lifecycles.

## Goal

Make this strategy expressible in the qkt DSL:

> *"When my XAUUSD position has 1000 pips of favorable excursion (within 30 minutes of fill), open three additional independent micro-trades. Each has its own 200-pip stop and 2000-pip target. The micro-trades survive after my primary position closes."*

That sentence is what `stackLevels` does in pa-quant's production hedge-straddle. After Phase 27, it becomes a few lines of `.qkt` code.

## Motivation

pa-quant's analysis (`../fxquant/pa-quant/src/strategies/hedge-straddle/types.ts:214-235`) shows:

```typescript
stackLevels: [
  { mfePips: 1000, slPips: 200, lotFactor: 0.30 },
  { mfePips: 2000, slPips: 200, lotFactor: 0.30 },
  { mfePips: 3000, slPips: 200, lotFactor: 0.30 },
]
```

Production result: 6-month P&L jumps from $1,478 → $3,673 (**+148%**). Not optional polish — it's a primary profit driver.

qkt's existing `STACK` clause (Phase 13a) models pyramid-into-trend (compounding into the same position with shared bracket and sequential triggering). Hedge-straddle's stacks are different:

| Aspect | qkt's STACK (Phase 13a) | hedge-straddle's stacks |
| --- | --- | --- |
| Brackets | Single shared SL+TP for combined position | Per-layer SL + TP, independent |
| Triggering | Sequential (layer 2 waits for layer 1 to fill) | Simultaneous — all fire on the candle after the cut |
| Trigger condition | Spacing from previous fill | MFE-since-position-open + elapsed-time |
| Lifecycle | All layers share fate with seed | Each layer survives independently after main closes |
| Direction | Pyramid (`ABOVE`) or average-down (`BELOW`) | Direction matches winner (only opens stacks on the winning side) |

Trying to bend the existing `STACK` into this shape would break its current users. The right move is a **new DSL construct** with its own AST node, sharing nothing with `STACK` except the word "stack" in the name.

## Scope

### In scope

**A. DSL surface — `STACK_AT` clause** (~250 LOC + tests)

```qkt
BUY gold SIZING 0.20 ORDER_TYPE = STOP AT gold.close + 5
    BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 15 }
    STACK_AT MFE >= 1000 WITHIN 30m
        SIZING 0.30 OF MAIN
        BRACKET { STOP LOSS BY 200, TAKE PROFIT BY 2000 }
    STACK_AT MFE >= 2000 WITHIN 60m
        SIZING 0.30 OF MAIN
        BRACKET { STOP LOSS BY 200, TAKE PROFIT BY 2000 }
    STACK_AT MFE >= 3000 WITHIN 90m
        SIZING 0.30 OF MAIN
        BRACKET { STOP LOSS BY 200, TAKE PROFIT BY 2000 }
```

Each `STACK_AT` clause defines one conditional stack. Multiple clauses allowed on one BUY/SELL action. Semantics:

- **`MFE >= N`** — the stack fires when the position's max favorable excursion crosses N. MFE is in price-units (not pips); convert via `atr` or hard-code as needed.
- **`WITHIN <duration>`** — the trigger window. After this time elapses without MFE reaching the threshold, the stack tier is abandoned for this position lifecycle.
- **`SIZING <factor> OF MAIN`** — stack size as a fraction of the parent action's sizing
- **`BRACKET { ... }`** — per-layer SL+TP. Same syntax as the parent BRACKET, but the resulting bracket is independent
- **Multiple `STACK_AT` clauses** — each is independent. They can fire all at once (if the position MFE crosses multiple thresholds in one candle) or never (if MFE doesn't reach the smallest threshold within its WITHIN window)

The `STACK_AT` clauses live on the entry action. They're not a separate `RULES` block — they're attached to the BUY/SELL that opens the position they're conditional on.

**B. AST + parser** (~200 LOC + tests)

```kotlin
data class StackAtClause(
    val mfeThreshold: ExprAst,           // price units, not pips
    val withinDuration: DurationAst,
    val sizing: SizingAst,                // OF MAIN means parent.sizing × frac
    val bracket: BracketAst,
)

data class ActionOpts(
    // ... existing fields ...
    val stackAts: List<StackAtClause> = emptyList(),
)
```

Parser changes: `parseActionOpts` accepts repeated `STACK_AT` clauses. Lexer adds `STACK_AT`, `MFE`, `OF`, `MAIN` keywords (most already tokens).

**C. Position model — `LegBook`** (~400 LOC + tests)

**The core engine change.** qkt's `Position` today is one net entry per (strategy, symbol). Phase 27 adds:

```kotlin
data class PositionLeg(
    val legId: String,           // qkt-side id, distinct from the broker ticket
    val symbol: String,
    val side: Side,
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val openedAt: Long,
    val role: LegRole,           // PRIMARY or STACK
    val parentLegId: String?,    // for STACK: which primary opened me
    val bracket: BracketSpec?,   // the SL/TP attached to this leg
)

enum class LegRole { PRIMARY, STACK }

class LegBook(
    private val strategy: String,
    private val symbol: String,
) {
    private val legs: MutableMap<String, PositionLeg> = ConcurrentHashMap()

    fun add(leg: PositionLeg)
    fun close(legId: String)
    fun all(): List<PositionLeg>
    fun primary(): PositionLeg?  // the most recent PRIMARY leg
    fun stacks(): List<PositionLeg>
    fun netQuantity(): BigDecimal    // signed sum across all legs
    fun maxMfe(): BigDecimal         // tracked per-position-lifecycle
}
```

`Position` becomes a derived view over the `LegBook`'s sum. The DSL's `POSITION.<stream>` continues to read the net quantity (backward-compatible). New accessors expose leg-level info:

- `POSITION.<stream>.legs.count`
- `POSITION.<stream>.mfe` — max favorable excursion since the PRIMARY leg opened
- `POSITION.<stream>.stacks.count`
- `POSITION.<stream>.holding_duration_primary` — time since the PRIMARY leg opened (existing `.holding_duration` returns time since *any* leg opened)

**D. MFE tracker** (~80 LOC + tests)

A per-LegBook component that updates on every tick:

```kotlin
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

Per-strategy MfeTracker per primary leg. Created when PRIMARY opens, destroyed when LegBook resets to empty.

**E. Stack engine** (~250 LOC + tests)

The component that watches MFE and elapsed time, fires stacks when thresholds cross:

```kotlin
class StackEngine(
    private val legBook: LegBook,
    private val mfeTracker: MfeTracker,
    private val clock: Clock,
    private val emit: (Signal) -> Unit,
) {
    private val pendingTiers: MutableList<PendingTier> = mutableListOf()
    private val firedTiers: MutableSet<Int> = mutableSetOf()

    fun registerTier(tierIndex: Int, clause: StackAtClause, openedAt: Long) { /* ... */ }

    fun onTick(price: BigDecimal) {
        mfeTracker.onTick(price)
        val elapsed = clock.now() - primaryOpenedAt
        for ((idx, tier) in pendingTiers.withIndex()) {
            if (idx in firedTiers) continue
            if (mfeTracker.value() >= tier.threshold && elapsed <= tier.withinMs) {
                emit(buildStackOrder(tier))
                firedTiers.add(idx)
            } else if (elapsed > tier.withinMs) {
                firedTiers.add(idx)  // abandoned, mark as terminal
            }
        }
    }
}
```

Each fire emits a `Signal.Submit(OrderRequest.Market)` (or whatever the BRACKET shape compiles to) with the per-layer sizing and bracket. The stack order routes through the normal `OrderManager.submit` → `broker.submit` path.

**F. Broker capability — multi-leg per symbol** (~100 LOC + tests)

For MT5: each `ticket` is independent; multiple positions on the same symbol "just work." Phase 26b's `MT5Broker` already places each leg as a separate order ticket. Phase 27 just leans on this.

For Bybit Linear: hedge mode allows multiple positions per symbol (long + short simultaneously). One-way mode (default) does not. The broker capability declaration distinguishes:
- `OrderTypeCapability.MULTI_POSITION_PER_SYMBOL` — new value

`BybitLinearBroker` checks the account's mode at startup; declares the capability conditionally. Strategies using `STACK_AT` against a one-way-mode account fail at compile time with a capability mismatch.

For Bybit Spot: netting-only, no hedge mode. Strategies using `STACK_AT` against Bybit Spot fail at compile time.

For PaperBroker (backtest): trivially supports multi-leg. Declare the capability.

**G. Tests**

- Unit: `LegBook` correctly tracks PRIMARY + STACK legs, computes netQuantity
- Unit: `MfeTracker` correctly accumulates max favorable excursion across direction
- Unit: `StackEngine` fires tier at threshold, abandons after WITHIN, doesn't double-fire
- Integration: full strategy with one `STACK_AT` clause → primary opens → MFE rises → stack fires → stack tracked independently → stack hits its own SL → primary still alive
- Integration: primary closes via its own SL → stacks survive independently
- Backtest fidelity: hedge-straddle-with-stacks vs hedge-straddle-without-stacks on a synthetic dataset that triggers all tiers, P&L delta matches expected

### Out of scope

**`stackLevels` (simultaneous fire) vs `stackTiers` (first-qualifying-only)** — pa-quant supports both. Phase 27 ships **`STACK_AT` clauses fire independently** (closer to `stackLevels` semantics). Adding "fire only the first qualifying tier" is a parameter:

```qkt
STACK_AT MFE >= 1500 WITHIN 30m FIRST_OF [TIER1, TIER2]
```

— but this needs more design. Defer to a future phase if strategies actually need it.

**Stack modification.** A live stack's bracket can't be modified by the parent strategy. (Wait for the stack to close, then open a new one.) Adds DSL+engine surface for marginal benefit. Defer.

**Stack on stack.** Stacks themselves can't have `STACK_AT` clauses on them. Only PRIMARY legs spawn stacks. Avoids unbounded recursion and matches pa-quant's design.

**Whipsaw filter (`cutClosePips`).** Different concern — that's an OCO_ENTRY-time filter, not a stack-time filter. Future phase if hedge-straddle ports need it.

**Win-rate circuit breaker on stacks.** Daemon-level concern, not strategy DSL.

## Architecture

### Position model rework

Today:
```
StrategyPositionTracker
└── Map<strategyId, Map<symbol, Position>>
    where Position = (symbol, quantity, avgEntryPrice, openedAt)
```

After Phase 27:
```
StrategyPositionTracker
└── Map<strategyId, Map<symbol, LegBook>>
    where LegBook contains a list of PositionLeg objects
    and Position is a derived view (netQuantity, weighted avgEntryPrice)
```

The DSL's `POSITION.btc` continues to return a signed scalar (the net quantity). All existing strategies parse and run unchanged. New strategies that use `STACK_AT` reach into the LegBook via the new accessors.

### Fill event handling

When a stack fills:
- `BrokerEvent.OrderFilled` arrives with the qkt-side `clientOrderId` (the stack order's id)
- `OrderManager.onFilled` sees the leg metadata (PRIMARY vs STACK, parentLegId)
- Calls `LegBook.add(PositionLeg(role = STACK, parentLegId = primary.legId, ...))`

When a stack closes (its own SL or TP fires):
- `BrokerEvent.OrderFilled` with the closing side
- `LegBook.close(stackLegId)` — that leg removed from the book
- Net position recalculates; other stacks and primary survive

When primary closes:
- Primary leg removed from LegBook
- Stacks continue independently (this is the key design choice)
- The MfeTracker is preserved? Or destroyed?
  - If preserved: stacks keep adding to MFE based on remaining legs' price action — but "MFE since primary opened" no longer makes sense
  - If destroyed: future `STACK_AT` clauses can't fire (their primary is gone)
  - Recommend: destroyed. `STACK_AT` clauses only fire while their primary is alive.

### MFE tick path

Engine receives a tick → tick reaches `LiveSession` → `StackEngine.onTick(price)` for every active leg book → MFE updates → tier conditions evaluated → fires stacks.

Cost: one comparison per tick per active stack tier. For ~10 strategies × 3 tiers each = 30 comparisons per tick. Cheap. No performance concern.

### Backtest fidelity

In backtest, ticks come from historical candles. `StackEngine.onTick(candle.close)` per closed candle is enough — the production strategy uses M5 candles, so MFE is sampled every 5 minutes. Realistic for the hedge-straddle pattern.

For finer-grained stack triggering (sub-candle MFE), `StackEngine.onTick` could be called with the candle's high (for BUY) and low (for SELL) — captures intrabar MFE. Trade-off: backtest accuracy vs. simulation overhead. Recommend: ship with candle-close sampling; document the intrabar option as a tunable.

### Capability declaration

```kotlin
enum class OrderTypeCapability {
    // ... existing ...
    MULTI_POSITION_PER_SYMBOL,
}

// MT5Protocol
val capabilities = setOf(
    /* existing */,
    MULTI_POSITION_PER_SYMBOL,  // MT5 supports natively
)

// BybitLinearBroker, gated on hedge-mode probe
override val capabilities: Set<OrderTypeCapability> = run {
    val base = setOf(MARKET, LIMIT, STOP, /* ... */)
    if (isHedgeMode()) base + MULTI_POSITION_PER_SYMBOL else base
}

// BybitSpotBroker
override val capabilities = setOf(MARKET, LIMIT)  // no MULTI_POSITION_PER_SYMBOL
```

Strategies using `STACK_AT` against a broker without `MULTI_POSITION_PER_SYMBOL` fail at strategy-compile time with a clear error.

### LegId vs broker ticket

Critical distinction:
- **legId**: qkt-internal identifier, stable across the position's lifecycle. Strategy code references this.
- **brokerOrderId / ticket**: the venue's identifier for the *order* that opened this position. The broker uses this for cancel/modify.

`PositionLeg.legId` is the qkt-side primary key. The broker's pendingByTicket → orderId mapping (Phase 26c) bridges legId to ticket on each fill event.

## Test plan

### Unit
- `LegBook.add/close/all/primary/stacks/netQuantity` — small focused tests on the data structure
- `MfeTracker.onTick` for BUY and SELL sides; verify it never decreases
- `StackEngine.registerTier/onTick` fires/abandons tiers correctly under threshold/time conditions
- DSL parser: `STACK_AT MFE >= N WITHIN M` parses; multiple clauses preserved; per-layer BRACKET preserved

### Integration
- Strategy compiles, primary opens, MFE crosses threshold within window → stack signal emitted → broker.submit called → stack fills → LegBook has both legs
- Primary closes → LegBook only has stack → next tick doesn't try to re-fire abandoned tiers
- Stack's own SL fires → stack removed from LegBook → primary unaffected
- MfeTracker resets when LegBook becomes empty

### Backtest fidelity
- Hedge-straddle reference strategy (with stacks) run against pa-quant's deterministic test fixture → trade count and PnL match expected values within tolerance
- Same strategy without stacks → matches pa-quant's "no stacks" baseline
- Delta between the two matches pa-quant's quoted +148% improvement (on the test dataset)

### Capability gating
- Strategy using `STACK_AT` against `BybitSpotBroker` → strategy compile fails with capability mismatch
- Same strategy against `MT5Broker` → compiles and runs
- Same strategy against `BybitLinearBroker` in hedge mode → compiles and runs
- Same strategy against `BybitLinearBroker` in one-way mode → strategy compile fails

## Migration considerations

**Position model rework is a real model change.** Existing strategies that depend on the singular `Position` model continue to work — `POSITION.<stream>` returns the net quantity. But:

- `POSITION.<stream>.entry_price` semantics change: instead of "the weighted average entry price of all fills," it becomes "the weighted average entry price of all active legs." For strategies that only have a PRIMARY leg (no stacks), the value is the same. For strategies with stacks, the average shifts as stacks open/close. Document explicitly.
- `POSITION.<stream>.holding_duration` semantics: today it's time since the position opened. After Phase 27, it's time since the *current* legs opened — which is now ambiguous. Recommend: keep meaning "time since the oldest active leg opened" (= primary's openedAt if primary is alive, = oldest stack's openedAt otherwise). Add `POSITION.<stream>.holding_duration_primary` and `POSITION.<stream>.holding_duration_oldest_stack` for finer-grained access.

**Storage / state-recovery.** The daemon's per-strategy state file (Phase 12c) stores positions on shutdown for state recovery. Today it serializes the singular `Position`; after Phase 27, it serializes the `LegBook`. **One-way migration:** old state files (from before Phase 27) load as a single PRIMARY leg with the existing entry price. No state loss.

**Reconciliation.** The Phase 7g reconciliation flow checks "qkt's local positions match the venue's positions." With multi-leg, "match" needs to be per-leg, not per-symbol. Reconcile by matching `legBook.all().map { it.brokerTicket }` against `client.getPositions().map { it.ticket }`. Different shape; doable but real work — include in Phase 27 or split out.

## Acceptance criteria

- All `LegBook`, `MfeTracker`, `StackEngine` unit tests pass
- DSL parser tests for `STACK_AT` clauses pass
- Integration tests: strategy with stacks runs end-to-end in backtest with realistic fills
- Capability declarations correct: MT5 has `MULTI_POSITION_PER_SYMBOL`, Bybit Spot doesn't, Bybit Linear gates on hedge-mode probe
- Backwards compatibility: existing strategies (no `STACK_AT`) parse and run identically
- Hedge-straddle example updated with `STACK_AT` clauses → backtest delta vs no-stacks matches pa-quant's analysis
- `docs/phases/phase-27-conditional-bracketed-stacks.md` exists with worked examples
- `docs/planned.md` Phase 27 entry removed

## Open questions

1. **MfeTracker on primary close — preserve or destroy?** Recommendation in the spec is "destroy" (stacks fire only while primary is alive). But pa-quant's behavior might be different — check `straddle-engine.ts` for the actual semantics. If pa-quant fires stacks even after primary closes, that needs the MfeTracker to persist, which means storing it in the LegBook itself.

2. **Same-symbol multi-strategy.** Two strategies both using MT5:EXNESS:XAUUSD, both with stacks. The venue tickets are independent. qkt's StrategyPositionTracker keys by strategyId, so the LegBooks don't collide. But the broker's position poller sees all positions on the symbol; the comment-tag correlation (Phase 26b) handles attribution per ticket. Verify this round-trips correctly in integration tests.

3. **Sizing `OF MAIN` semantics.** Does it mean "0.30 × parent.sizing at the time the parent was submitted" (snapshot) or "0.30 × current equity / parent.sizing.frac at the time the stack fires" (live recompute)? Recommend snapshot — predictable, no equity-fluctuation surprises.

4. **Sequencing relative to bracketed primary.** If the primary's own SL fires *in the same candle* the stack threshold crosses, do we fire the stack? Cleaner: no — once the primary terminates, the stack engine is shut down. But this depends on event ordering in the engine's tick loop. Document the rule.

5. **Stack id naming convention.** `clientOrderId` for stack orders: `<primary.legId>-stack-<tierIdx>`? Or UUID? Affects readability of logs and reconciliation.

6. **Bybit Linear hedge-mode probe.** How does the broker discover whether the account is in hedge mode? `/v5/account/info` endpoint? At startup or on every order? Cheap to cache; check at startup.

## References

- pa-quant production source: `../fxquant/pa-quant/src/strategies/hedge-straddle/types.ts:214-235`
- pa-quant analysis: `../fxquant/pa-quant/src/strategies/hedge-straddle/README.md` (search for "stackLevels")
- qkt Phase 13a STACK: `docs/phases/phase-13a-stack.md`
- qkt Phase 26a hedge-straddle example: `examples/hedge-straddle/hedge-straddle.qkt`
- qkt position model: `src/main/kotlin/com/qkt/positions/Position.kt`, `StrategyPositionTracker.kt`
- qkt Phase 7g reconciliation: `docs/phases/phase-7g-reconciliation-and-balances.md`
