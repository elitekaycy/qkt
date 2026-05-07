# Phase 9 — Risk Engine: Equity, Drawdown, Halts

**Status:** Design draft.
**Predecessor:** Phase 8 (StrategyContext + PnL attribution).
**Successor:** Phase 10 (backtest replay engine; consumes equity curves for proper backtest reporting).

---

## 1. Mission

Replace the per-symbol-only risk surface from Phase 1 with a real risk engine that tracks equity over time, computes drawdown online, and halts trading when configurable thresholds are breached. After this phase, the engine answers "should we keep trading right now?" — globally and per-strategy. The halt is a state, not an event-only signal: blocked submissions are rejected at the gate, existing positions remain open, an operator decides when to resume.

This phase deliberately keeps risk evaluation decoupled from order management. Strategies cannot see other strategies' state; they query a filtered `RiskView` via `StrategyContext.risk`. Halt rules run continuously on tick/fill; submission rules run only on submit. The two are separate interfaces with separate semantics.

---

## 2. Goals

- `EquityTracker` — total equity over time (`realized + unrealized`). Updated on tick (price change → unrealized recompute) and fill (realized increment + position change). Snapshots peak.
- `DrawdownTracker` — online max-drawdown computation. `peak`, `currentEquity`, `drawdown = (peak - currentEquity) / peak`. Both for global view and per-strategy.
- `DailyPnLTracker` — realized PnL since UTC midnight. Resets at calendar boundary. Tracks both global and per-`strategyId`.
- `RiskState` — central component. Owns the trackers + halt flags. Mutators emit `RiskEvent.Halted` / `RiskEvent.Resumed` on transitions.
- `RiskView` — read-only filter. Bound to a strategyId at construction. Strategies access their own state via `StrategyContext.risk`.
- New halt-rule interface `HaltRule` — `fun evaluate(riskState: RiskState): HaltDecision`. Distinct from existing `RiskRule` (submission rule).
- New rules:
  - `MaxDrawdown(maxFraction)` — global halt at threshold (e.g., 0.20 for 20%).
  - `MaxDailyLoss(maxLoss)` — global halt when daily realized below threshold.
  - `MaxStrategyDrawdown(strategyId, maxFraction)` — per-strategy halt.
  - `MaxStrategyDailyLoss(strategyId, maxLoss)` — per-strategy halt.
  - `MaxOpenPositions(count)` — submission rule capping concurrent positions.
  - `KillSwitch` — submission rule reading `riskState.halted` (operator manual halt).
- `RiskEvent` sealed family — `Halted(reason, strategyId?)`, `Resumed(strategyId?)`. Observability.
- `StrategyContext.risk: RiskView` — every strategy receives its own filter.
- `RiskEngine` extended with `haltRules: List<HaltRule>` field; pipeline drives evaluation on tick/fill.
- Halt blocks new submissions only. Existing positions stay open. Operator must manually flatten via direct broker calls if desired.
- Resume is operator-driven: `riskState.resume()` or `riskState.resumeStrategy(id)`. No auto-resume on improving conditions.

## Non-goals

- **No notional exposure caps.** Requires per-symbol price lookups for unrealized notional. Phase 10 has the data; defer.
- **No leverage caps.** Derivatives margin math; Phase 10+ scope.
- **No volatility-based circuit breakers.** Requires vol indicator integration; Phase 10+.
- **No automatic position flattening on halt.** Industry default is to leave positions open in fast markets — auto-close compounds losses. Operator decides.
- **No automatic halt resume.** Risk transitions are one-way until human input. Auto-resume on "drawdown improved" creates flap loops in volatile markets.
- **No persistence of risk state across JVM restarts.** Same as 7f-8.
- **No per-broker risk gating.** Risk applies at the engine layer, broker-agnostic. If you want venue-specific limits, build a wrapper broker (out of scope).
- **No "soft" warnings.** Rules either approve/reject or halt/continue. No graduated severity. Observability events (`RiskEvent.Halted`) ARE the warning surface.
- **No equity curve persistence.** `EquityTracker` keeps an in-memory ring buffer of recent samples; full historical curve is Phase 10 (backtest reporting).

---

## 3. Background — current state (Phase 8, post-merge)

```kotlin
// risk package
interface RiskRule {
    fun evaluate(request: OrderRequest, positions: PositionProvider): Decision
}

sealed class Decision {
    object Approve : Decision()
    data class Reject(val reason: String) : Decision()
}

class RiskEngine(
    private val rules: List<RiskRule>,
    private val positions: PositionProvider,
) {
    fun approve(request: OrderRequest): Decision = rules.firstNotNullOfOrNull { /* ... */ } ?: Decision.Approve
}

class MaxPositionSize(val symbol: String, val maxQty: BigDecimal) : RiskRule {
    override fun evaluate(request, positions): Decision { ... }
}
```

Limitations forcing this phase:

- **No equity awareness.** Rules can only see the proposed order and current positions. Account-level metrics (P&L, drawdown) invisible.
- **No halt mechanism.** Rejection is per-order. A blow-up scenario (10 consecutive losses) keeps generating orders that get rejected; no global circuit breaker.
- **No time-window logic.** "Max daily loss" requires resetting at calendar boundaries; no infrastructure.
- **Strategies can't see risk state.** No way for a strategy to back off voluntarily during drawdown periods.
- **No per-strategy risk attribution.** A bad strategy can blow up the account; risk engine treats all submissions identically.

---

## 4. Architecture overview

```
┌────────────────────────────────────────────────────────────────────────┐
│  RiskState (NEW — central component)                                   │
│                                                                         │
│    private val equityTracker: EquityTracker                            │
│    private val drawdownTracker: DrawdownTracker                        │
│    private val dailyPnLTracker: DailyPnLTracker                        │
│                                                                         │
│    @Volatile var halted: Boolean = false                               │
│    @Volatile var haltReason: String? = null                            │
│    val haltedStrategies: ConcurrentHashMap<String, String>             │
│                                                                         │
│    fun halt(reason: String): unit                                      │
│    fun haltStrategy(id: String, reason: String): unit                  │
│    fun resume(): unit                                                  │
│    fun resumeStrategy(id: String): unit                                │
│                                                                         │
│    fun snapshot(): RiskSnapshot                                        │
│    fun snapshotFor(strategyId: String): StrategyRiskSnapshot           │
│                                                                         │
└────────────────────────────────────────────────────────────────────────┘
            ▲ subscribed                                ▲ read by
            │                                           │
┌───────────┴──────────────────────────┐    ┌──────────┴──────────────┐
│  TradingPipeline                     │    │  RiskView (in           │
│    bus.subscribe<TickEvent> { e ->   │    │  StrategyContext)       │
│        riskState.onTick(e.tick)      │    │                         │
│        riskEngine.evaluateHaltRules()│    │  positionFor → ctx.pos  │
│    }                                 │    │  drawdown → riskState   │
│    bus.subscribe<OrderFilled> { e -> │    │  ...                    │
│        riskState.onFill(e)           │    │                         │
│        riskEngine.evaluateHaltRules()│    └─────────────────────────┘
│    }                                 │
│                                      │
│  Strategy.onTick → emit Signal       │
│    → riskEngine.approve(request)     │
│      → check halted                  │
│      → check RiskRule list           │
│      → Decision.Approve/Reject       │
└──────────────────────────────────────┘
```

The pipeline already drives the bus — Phase 9 adds two subscriptions (`onTick → riskState.onTick`, `onFill → riskState.onFill`) and a halt-rule eval at the same points.

---

## 5. `EquityTracker`

### Definition

```kotlin
package com.qkt.risk

class EquityTracker(
    private val pnl: PnLProvider,           // global PnL (broker-truth view)
    private val strategyPnL: StrategyPnL,   // per-strategy attribution
) {
    @Volatile private var currentTotalEquity: BigDecimal = Money.ZERO
    @Volatile private var peakTotalEquity: BigDecimal = Money.ZERO

    private val perStrategyPeak: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun update() {
        val total = pnl.realizedTotal().add(pnl.unrealizedTotal())
        currentTotalEquity = total
        if (total > peakTotalEquity) peakTotalEquity = total
    }

    fun updateStrategy(strategyId: String) {
        val total = strategyPnL.totalFor(strategyId)
        val peak = perStrategyPeak[strategyId] ?: Money.ZERO
        if (total > peak) perStrategyPeak[strategyId] = total
    }

    fun currentEquity(): BigDecimal = currentTotalEquity

    fun peakEquity(): BigDecimal = peakTotalEquity

    fun currentEquityFor(strategyId: String): BigDecimal = strategyPnL.totalFor(strategyId)

    fun peakEquityFor(strategyId: String): BigDecimal =
        perStrategyPeak[strategyId] ?: Money.ZERO
}
```

### When `update()` is called

- Every tick (unrealized changes with price)
- Every fill (realized + unrealized both update)

The pipeline subscribes:
```kotlin
bus.subscribe<TickEvent> { riskState.onTick() }
bus.subscribe<BrokerEvent.OrderFilled> { riskState.onFill() }
```

`riskState.onTick()` calls `equityTracker.update()` + iterates known strategies for `updateStrategy(...)`. Same for `onFill`.

### Why keep both global and per-strategy

Global is the broker-truth equity (sum across all strategies). Per-strategy is attribution. They diverge on `PositionReconciled` events (broker resets the global, attribution stays). For risk, both views matter:
- Global drawdown → portfolio circuit breaker
- Per-strategy drawdown → kill the bad strategy without nuking the account

---

## 6. `DrawdownTracker`

### Definition

```kotlin
class DrawdownTracker(private val equityTracker: EquityTracker) {
    fun globalDrawdown(): BigDecimal {
        val peak = equityTracker.peakEquity()
        if (peak.signum() <= 0) return Money.ZERO    // no positive peak yet → no drawdown
        val current = equityTracker.currentEquity()
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun strategyDrawdown(strategyId: String): BigDecimal {
        val peak = equityTracker.peakEquityFor(strategyId)
        if (peak.signum() <= 0) return Money.ZERO
        val current = equityTracker.currentEquityFor(strategyId)
        return peak.subtract(current).divide(peak, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
}
```

Drawdown is fractional (0.20 = 20%). Always non-negative — current ≥ peak means drawdown = 0. New peaks are tracked by `EquityTracker.update`.

---

## 7. `DailyPnLTracker`

### Definition

```kotlin
class DailyPnLTracker(private val clock: Clock) {
    private val byStrategy: MutableMap<String, BigDecimal> = ConcurrentHashMap()
    private var globalToday: BigDecimal = Money.ZERO
    private var lastResetEpochDay: Long = epochDay()

    fun recordRealized(strategyId: String, realized: BigDecimal) {
        rolloverIfNeeded()
        if (strategyId.isNotBlank()) {
            val current = byStrategy[strategyId] ?: Money.ZERO
            byStrategy[strategyId] = current.add(realized).setScale(Money.SCALE, Money.ROUNDING)
        }
        globalToday = globalToday.add(realized).setScale(Money.SCALE, Money.ROUNDING)
    }

    fun globalRealizedToday(): BigDecimal {
        rolloverIfNeeded()
        return globalToday
    }

    fun realizedToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        return byStrategy[strategyId] ?: Money.ZERO
    }

    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            byStrategy.clear()
            globalToday = Money.ZERO
            lastResetEpochDay = today
        }
    }

    private fun epochDay(): Long =
        java.time.Instant.ofEpochMilli(clock.now())
            .atZone(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toEpochDay()
}
```

Reset on the first read or write that crosses midnight. UTC-based; if the user wants a different timezone, future work.

### Wired by pipeline

```kotlin
bus.subscribe<BrokerEvent.OrderFilled> { e ->
    val realized = positions.applyFill(e)
    pnl.recordRealized(realized)
    val stratRealized = strategyPositions.applyFill(e)
    strategyPnL.recordRealized(e.strategyId, stratRealized)
    riskState.dailyPnLTracker.recordRealized(e.strategyId, stratRealized)   // NEW
    // ...
}
```

---

## 8. `RiskState`

### Definition

```kotlin
class RiskState(
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
) {
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)

    @Volatile var halted: Boolean = false
        private set

    @Volatile var haltReason: String? = null
        private set

    private val haltedStrategies: MutableMap<String, String> = ConcurrentHashMap()

    fun isStrategyHalted(strategyId: String): Boolean =
        halted || strategyId in haltedStrategies

    fun haltReasonFor(strategyId: String): String? =
        if (halted) haltReason else haltedStrategies[strategyId]

    fun onTick() {
        equityTracker.update()
        // tracked strategy IDs come from strategyPnL — no enumeration needed; per-strategy peak updates lazily on access
    }

    fun onFill(strategyId: String, realized: BigDecimal) {
        equityTracker.update()
        equityTracker.updateStrategy(strategyId)
        dailyPnLTracker.recordRealized(strategyId, realized)
    }

    fun halt(reason: String) {
        if (halted) return
        halted = true
        haltReason = reason
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = null, timestamp = clock.now()))
    }

    fun haltStrategy(strategyId: String, reason: String) {
        if (haltedStrategies.putIfAbsent(strategyId, reason) != null) return
        bus.publish(RiskEvent.Halted(reason = reason, strategyId = strategyId, timestamp = clock.now()))
    }

    fun resume() {
        if (!halted) return
        halted = false
        haltReason = null
        bus.publish(RiskEvent.Resumed(strategyId = null, timestamp = clock.now()))
    }

    fun resumeStrategy(strategyId: String) {
        if (haltedStrategies.remove(strategyId) == null) return
        bus.publish(RiskEvent.Resumed(strategyId = strategyId, timestamp = clock.now()))
    }
}
```

### Why mutable halt fields are `@Volatile var ... private set`

`halted` is read by every submission attempt (high frequency); writes are rare (triggered by halt rules or operator). Volatile read/write is sufficient — we don't need atomic CAS because the halt flag is monotonic (once true, stays true until explicit resume; once false, stays false until explicit halt). `private set` ensures only the class methods can flip it.

---

## 9. `RiskEvent` sealed family

```kotlin
package com.qkt.events

sealed interface RiskEvent : Event {
    data class Halted(
        val reason: String,
        val strategyId: String?,                  // null = global halt
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent

    data class Resumed(
        val strategyId: String?,                  // null = global resume
        override val timestamp: Long = 0L,
        override val sequenceId: Long = 0L,
    ) : RiskEvent
}
```

`EventBus` exhaustive-when gains the variants. Same pattern as previous additions.

### Subscribers

```kotlin
bus.subscribe<RiskEvent.Halted> { e ->
    log.error("RISK HALT: strategy={} reason={}", e.strategyId ?: "GLOBAL", e.reason)
}
```

Operator dashboards / alerting / Slack bots subscribe here.

---

## 10. `HaltRule` interface and rules

### Interface

```kotlin
package com.qkt.risk

interface HaltRule {
    fun evaluate(riskState: RiskState): HaltDecision
}

sealed class HaltDecision {
    object Continue : HaltDecision()
    data class Halt(
        val reason: String,
        val strategyId: String? = null,
    ) : HaltDecision()
}
```

### `MaxDrawdown`

```kotlin
class MaxDrawdown(
    private val maxFraction: BigDecimal,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.globalDrawdown()
        return if (dd > maxFraction) {
            HaltDecision.Halt("global drawdown ${dd.setScale(4)} exceeds max $maxFraction")
        } else {
            HaltDecision.Continue
        }
    }
}
```

### `MaxDailyLoss`

```kotlin
class MaxDailyLoss(
    private val maxLoss: BigDecimal,    // expressed as positive number; loss > this triggers halt
) : HaltRule {
    init {
        require(maxLoss.signum() > 0) { "maxLoss must be > 0: $maxLoss" }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.globalRealizedToday()
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt("daily loss ${realized.negate()} exceeds max $maxLoss")
        } else {
            HaltDecision.Continue
        }
    }
}
```

### `MaxStrategyDrawdown`

```kotlin
class MaxStrategyDrawdown(
    private val strategyId: String,
    private val maxFraction: BigDecimal,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.drawdownTracker.strategyDrawdown(strategyId)
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                reason = "strategy drawdown ${dd.setScale(4)} exceeds max $maxFraction",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
```

### `MaxStrategyDailyLoss`

```kotlin
class MaxStrategyDailyLoss(
    private val strategyId: String,
    private val maxLoss: BigDecimal,
) : HaltRule {
    override fun evaluate(riskState: RiskState): HaltDecision {
        val realized = riskState.dailyPnLTracker.realizedToday(strategyId)
        return if (realized.negate() > maxLoss) {
            HaltDecision.Halt(
                reason = "strategy daily loss ${realized.negate()} exceeds max $maxLoss",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
```

---

## 11. `RiskRule` additions (submission rules)

### `MaxOpenPositions`

```kotlin
class MaxOpenPositions(
    private val maxCount: Int,
) : RiskRule {
    init {
        require(maxCount > 0) { "maxCount must be > 0: $maxCount" }
    }

    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision {
        val openCount = positions.allPositions().count { it.value.quantity.signum() != 0 }
        // If the request would open a NEW symbol AND we're at the cap, reject.
        val isNewSymbol = positions.positionFor(request.symbol)?.quantity?.signum() == 0 ||
            positions.positionFor(request.symbol) == null
        return if (isNewSymbol && openCount >= maxCount) {
            Decision.Reject("max open positions reached: $openCount")
        } else {
            Decision.Approve
        }
    }
}
```

### `KillSwitch`

```kotlin
class KillSwitch(
    private val riskState: RiskState,
) : RiskRule {
    override fun evaluate(
        request: OrderRequest,
        positions: PositionProvider,
    ): Decision =
        if (riskState.isStrategyHalted(request.strategyId)) {
            val reason = riskState.haltReasonFor(request.strategyId) ?: "halted"
            Decision.Reject("kill switch: $reason")
        } else {
            Decision.Approve
        }
}
```

### Why `KillSwitch` is a `RiskRule` not a special case

Treating "halted" as just another rejection reason keeps the engine logic uniform. `RiskEngine.approve` walks the rule list, the kill-switch rule short-circuits with reject when state says halted. No special engine code path.

The alternative (engine checks `riskState.halted` before walking rules) is also fine but less composable — having KillSwitch as a rule means users can configure WHICH rule order they want (kill switch first vs. last), or omit it entirely (e.g., for backtest testing).

---

## 12. `RiskView` and `StrategyContext.risk`

### Interface

```kotlin
package com.qkt.risk

interface RiskView {
    val halted: Boolean                   // true if THIS strategy is halted (incl. global)
    val haltReason: String?
    val currentEquity: BigDecimal         // for this strategy
    val drawdown: BigDecimal              // for this strategy (fractional)
    val realizedToday: BigDecimal         // for this strategy
    val globalHalted: Boolean             // true if global halt is on
    val globalDrawdown: BigDecimal        // global drawdown for visibility
}
```

### Impl

```kotlin
internal class RiskViewImpl(
    private val riskState: RiskState,
    private val strategyId: String,
) : RiskView {
    override val halted: Boolean
        get() = riskState.isStrategyHalted(strategyId)

    override val haltReason: String?
        get() = riskState.haltReasonFor(strategyId)

    override val currentEquity: BigDecimal
        get() = riskState.equityTracker.currentEquityFor(strategyId)

    override val drawdown: BigDecimal
        get() = riskState.drawdownTracker.strategyDrawdown(strategyId)

    override val realizedToday: BigDecimal
        get() = riskState.dailyPnLTracker.realizedToday(strategyId)

    override val globalHalted: Boolean
        get() = riskState.halted

    override val globalDrawdown: BigDecimal
        get() = riskState.drawdownTracker.globalDrawdown()
}
```

### `StrategyContext` extension

```kotlin
data class StrategyContext(
    val strategyId: String,
    val mode: Mode,
    val clock: Clock,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val positions: StrategyPositionView,
    val pnl: StrategyPnLView,
    val risk: RiskView,                 // NEW
)
```

Strategies that ignore risk simply don't read `ctx.risk`. Test fixtures get a no-op `RiskView` from `testStrategyContext()`.

### Strategy usage

```kotlin
class ConservativeStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        if (ctx.risk.drawdown > BigDecimal("0.10")) return    // back off above 10% drawdown
        if (ctx.risk.realizedToday < BigDecimal("-1000")) return    // back off after $1k daily loss
        // ... actual entry logic
    }
}
```

This is opt-in. Strategies don't HAVE to read risk; the engine still enforces hard limits via halt rules. But strategies that want to soft-throttle their own activity have the surface to do so.

---

## 13. `RiskEngine` extensions

### New shape

```kotlin
class RiskEngine(
    private val rules: List<RiskRule>,                  // existing
    private val haltRules: List<HaltRule> = emptyList(), // NEW
    private val positions: PositionProvider,            // existing
    private val riskState: RiskState,                   // NEW
) {
    fun approve(request: OrderRequest): Decision {
        if (riskState.isStrategyHalted(request.strategyId)) {
            return Decision.Reject(
                riskState.haltReasonFor(request.strategyId)?.let { "halted: $it" } ?: "halted",
            )
        }
        for (rule in rules) {
            val decision = rule.evaluate(request, positions)
            if (decision is Decision.Reject) return decision
        }
        return Decision.Approve
    }

    fun evaluateHaltRules() {
        for (rule in haltRules) {
            when (val decision = rule.evaluate(riskState)) {
                is HaltDecision.Continue -> Unit
                is HaltDecision.Halt -> {
                    if (decision.strategyId != null) {
                        riskState.haltStrategy(decision.strategyId, decision.reason)
                    } else {
                        riskState.halt(decision.reason)
                    }
                }
            }
        }
    }
}
```

### Backwards compatibility

Existing `RiskEngine(rules, positions)` constructor breaks because `riskState` is required. To avoid touching every test: provide a secondary constructor that creates a no-op `RiskState`:

```kotlin
constructor(rules: List<RiskRule>, positions: PositionProvider) :
    this(rules, emptyList(), positions, RiskState.noOp())
```

Where `RiskState.noOp()` is a static factory returning a tracker-less instance for tests that don't care about risk state.

### Pipeline integration

```kotlin
init {
    // ... existing wiring ...

    bus.subscribe<TickEvent> { e ->
        riskState.onTick()
        riskEngine.evaluateHaltRules()
    }

    bus.subscribe<BrokerEvent.OrderFilled> { e ->
        // existing fill processing ...
        riskState.onFill(e.strategyId, stratRealized)
        riskEngine.evaluateHaltRules()
    }
}
```

`evaluateHaltRules()` is called continuously; the moment a threshold is breached, `riskState.halt()` fires, the next submission is rejected at the gate.

---

## 14. Testing approach

### Unit tests

| Class | Scope |
|---|---|
| `EquityTrackerTest` | Tracks total + per-strategy; peak monotonic-non-decreasing |
| `DrawdownTrackerTest` | Returns 0 when peak ≤ 0; correct fractional computation; per-strategy independence |
| `DailyPnLTrackerTest` | Resets at UTC midnight; per-strategy attribution; `globalRealizedToday` sums |
| `RiskStateTest` | Halt + resume transitions; idempotent halt; bus emissions for transitions |
| `MaxDrawdownTest` | Halts at threshold; ignores when no peak |
| `MaxDailyLossTest` | Halts on threshold breach; ignores positive PnL |
| `MaxStrategyDrawdownTest` | Halts only that strategy, not globally |
| `MaxStrategyDailyLossTest` | Same |
| `MaxOpenPositionsTest` | Allows existing-symbol orders at cap; rejects new-symbol at cap |
| `KillSwitchTest` | Reads `riskState.halted`; reject when halted |
| `RiskEngineHaltRulesTest` | `evaluateHaltRules` cascades through rule list; first halting rule wins |
| `RiskViewTest` | Strategy view filters correctly; visible global halt status |

### Integration

`RiskEnginePipelineTest`:
- Pipeline + halt rule + simulated drawdown breach → next submission rejected
- Resume restores submission flow
- Per-strategy halt blocks one strategy, others continue

### Fakes

No new fakes. Use `FixedClock` for daily reset tests. Existing `MarketPriceTracker` updates drive equity recalculation.

---

## 15. Race conditions and edge cases

### Race-1: tick fires, halt rule evaluates, new submission lands simultaneously

JVM bus is single-threaded today. No race. If parallelism is added later, halt-flag write happens-before submit-side read via volatile. Worst case: one extra submission slips through before the halt is visible — minor.

### Race-2: per-strategy halt + global halt both active

`isStrategyHalted(id)` returns true if either is true. Submissions blocked. `haltReasonFor(id)` returns global reason if global halt set, else strategy-specific. Documented.

### Race-3: halt rule references a strategy that has no fills yet

Per-strategy drawdown / daily loss for an unknown strategyId returns 0 (no positions tracked). Rule evaluates, sees no breach, no halt. Correct.

### Race-4: `DailyPnLTracker` rollover lag

If a tick arrives at 23:59:59.999 and the fill arrives at 00:00:00.001, the rollover happens between them. Realized for the new day starts at 0; old day's PnL is gone. This is intentional — daily means daily, not last-24-hours.

### Race-5: equity tracker update races with PnL update

`equityTracker.update()` reads `pnl.realizedTotal()` and `pnl.unrealizedTotal()`. These are updated by the pipeline subscription on `OrderFilled` BEFORE `riskState.onFill` runs. Order is sequential within bus dispatch — no race.

### Race-6: peak equity reset on operator action

There's no API to reset peak equity. Once a peak is set, it's permanent. To "reset risk after a halt", operator must rebuild the engine with a fresh `RiskState`. Documented limitation; auto-reset on resume is wrong (would mask the drawdown that triggered the halt).

### Race-7: user calls `riskState.halt()` and `riskState.resume()` rapidly

Both are idempotent. `halt()` returns early if already halted. `resume()` returns early if already running. No flap loop in events — at most one Halted then one Resumed per logical transition.

### Edge: halt fires during warmup

Warmup ticks update unrealized PnL. If a strategy has a synthetic position that drops 30% during warmup, MaxDrawdown halts before live trading even starts. Two options:
- Skip halt evaluation during warmup (need a flag)
- Let it halt and require operator resume

Recommendation: skip during warmup. Add `riskState.warmupComplete: Boolean = false` and check in `evaluateHaltRules()`. Set true after warmup phase. Documented.

### Edge: HaltRule throws during evaluation

`evaluateHaltRules()` wraps each `rule.evaluate(riskState)` in `runCatching` and logs failure. A buggy rule must not stop the engine. Other rules continue.

---

## 16. Multi-broker scaling

Risk applies at the engine layer, broker-agnostic. The PnL components Phase 8 ships are already multi-broker (per-`(strategyId, symbol)` with prefix attribution). No new work for multi-broker.

When Phase 10+ adds margin-aware brokers (derivatives), `MaxLeverage` and `MaxExposure` rules can be added without touching the engine — they just read different fields off the broker / `BybitTransport.balances`. Risk's contract is "halt or reject"; what triggers it expands.

---

## 17. Configuration

### Constructor parameters

`RiskState`:
- `pnl: PnLProvider` — required
- `strategyPnL: StrategyPnL` — required
- `clock: Clock` — required
- `bus: EventBus` — required

`RiskEngine`:
- `rules: List<RiskRule>` — required (was already)
- `haltRules: List<HaltRule> = emptyList()` — new with default
- `positions: PositionProvider` — required (was already)
- `riskState: RiskState` — required (new), or use no-op factory for tests

### No new env vars

Risk thresholds are constructor parameters on each rule. Operator decides defaults at application startup.

### Recommended defaults (production)

These are not engine defaults — they're suggestions for application authors:

```kotlin
val haltRules = listOf(
    MaxDrawdown(BigDecimal("0.20")),                         // 20% peak-to-trough
    MaxDailyLoss(BigDecimal("5000")),                        // $5k daily loss
)
val submitRules = listOf(
    MaxOpenPositions(20),                                    // 20 concurrent symbols max
    KillSwitch(riskState),
    // existing per-symbol caps...
)
```

---

## 18. Out of scope (deferred)

| Feature | Phase | Rationale |
|---|---|---|
| Notional exposure caps | 10 | Needs symbol-level price data infrastructure. |
| Leverage caps (derivatives) | 10+ | Needs margin math. |
| Volatility-based circuit breakers | 10+ | Needs vol indicator integration. |
| Auto-position-flatten on halt | (never) | Industry default leaves positions open; auto-close compounds losses in fast markets. |
| Auto-resume on improving conditions | (never) | Creates flap loops; human-in-loop is correct. |
| Equity curve persistence | 10 | Backtest reporting. |
| Risk dashboards / UI | 12+ | Out of engine scope. |
| Per-broker risk gating | future | Build a wrapper broker if needed; engine stays venue-agnostic. |
| Soft warnings / graduated severity | future | Halt-or-not is sufficient; observability via events. |
| Margin call simulation | 10+ | Derivatives-specific. |
| Time-of-day halt (e.g., halt outside market hours) | future | TradingCalendar already exposes session boundaries; trivial to add later. |
| `RiskState` persistence across JVM restarts | future | Same as 7f-8. |

---

## 19. Migration

### From 8 → 9

| 8 | 9 | Notes |
|---|---|---|
| `RiskEngine(rules, positions)` | `RiskEngine(rules, haltRules, positions, riskState)` | New required params. Convenience ctor `RiskEngine(rules, positions)` provided for tests via `RiskState.noOp()`. |
| `StrategyContext` had 7 fields | gains `risk: RiskView` (8th) | `testStrategyContext()` adds no-op default. |
| (no halt event) | `RiskEvent.Halted` / `RiskEvent.Resumed` | Subscribers opt-in. |
| `EventBus` had 8 event types in exhaustive when | gains `RiskEvent.Halted` / `RiskEvent.Resumed` | Mechanical addition. |
| (no equity tracking) | `EquityTracker`, `DrawdownTracker`, `DailyPnLTracker` | New subsystem. |
| Pipeline subscribed `TickEvent` only for price update | adds `riskState.onTick()` + `riskEngine.evaluateHaltRules()` | Same subscription, more work. |

### Application setup change

```kotlin
// 8
val pipeline = TradingPipeline(
    ...,
    riskEngine = RiskEngine(rules = listOf(MaxPositionSize(...)), positions = positions),
    ...,
)

// 9
val riskState = RiskState(pnl, strategyPnL, clock, bus)
val haltRules = listOf<HaltRule>(
    MaxDrawdown(BigDecimal("0.20")),
    MaxDailyLoss(BigDecimal("5000")),
)
val submitRules = listOf<RiskRule>(
    MaxPositionSize("BYBIT_LINEAR:BTCUSDT", BigDecimal("1.0")),
    MaxOpenPositions(10),
    KillSwitch(riskState),
)
val pipeline = TradingPipeline(
    ...,
    riskEngine = RiskEngine(submitRules, haltRules, positions, riskState),
    riskState = riskState,                                                    // NEW pipeline param
    ...,
)
```

### Backwards-compat shim for existing tests

`RiskEngine(rules, positions)` continues to compile and work. The shim wires a no-op `RiskState` that has tracker methods returning zero and halt methods that don't fire events. Tests that don't care about risk pass through unaffected.

---

## 20. Summary

Phase 9 turns the risk engine from "per-symbol position cap" into a real circuit breaker. Equity tracked over time, drawdown computed online, daily P&L bounded by UTC days, halt rules evaluated continuously on tick/fill, halt state observable via events, halt blocks new submissions while leaving positions open. Strategies see their own filtered risk state via `StrategyContext.risk`. Per-strategy variants of every halt rule fall out for free from the per-strategy attribution Phase 8 shipped.

Surface area is moderate — three trackers, one central state, two new rule interfaces (HaltRule + HaltDecision), one new event family (Halted/Resumed), one StrategyContext extension. Existing tests stay green via the no-op RiskState shim. After Phase 9, the engine is "live ready" in the operational sense: it can detect blow-ups and stop trading without human intervention, and it surfaces enough state for humans to observe and resume.

Phase 10 (backtest replay) consumes the equity curve directly: backtest reports become "drawdown over time" charts, and equity-based comparisons across strategies become trivial. Phase 11 (DSL) gets `ctx.risk` as a first-class read in the `strategy { ... }` block.
