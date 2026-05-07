# Phase 9 — Risk Engine

**Status:** Shipped. Merged into `main` on (placeholder).
**Spec:** [`../superpowers/specs/2026-05-06-trading-engine-phase9-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase9-design.md)

## Summary

Phase 9 turns the risk engine from "per-symbol position cap" into a real circuit breaker. Equity is tracked over time, drawdown computed online, daily P&L bounded by UTC days, halt rules evaluated continuously on tick/fill, halt state observable via events, halt blocks new submissions while leaving positions open. Strategies see their own filtered risk state via `StrategyContext.risk`. Per-strategy variants of every halt rule fall out for free from the per-strategy attribution Phase 8 shipped.

## What's new

- `EquityTracker` (`com.qkt.risk`) — total + per-strategy equity over time. Updated on tick (unrealized recompute) and fill (realized + unrealized). Tracks peak monotonically.
- `DrawdownTracker` — online fractional drawdown computation. `globalDrawdown()`, `strategyDrawdown(id)`. Returns 0 when no positive peak exists.
- `DailyPnLTracker` — UTC midnight rollover. `globalRealizedToday()`, `realizedToday(strategyId)`. Resets on first read or write that crosses midnight.
- `RiskState` — central component owning the trackers + halt flags. Mutators (`halt`, `haltStrategy`, `resume`, `resumeStrategy`) emit `RiskEvent.Halted` / `RiskEvent.Resumed` on transitions. Idempotent. `RiskState.noOp()` factory for tests that don't care.
- `RiskEvent.Halted(reason, strategyId?)` and `RiskEvent.Resumed(strategyId?)` — new event family in `com.qkt.events`. Sibling of `BrokerEvent`. Subscribers opt-in for alerting.
- `HaltRule` interface + `HaltDecision` sealed class. `HaltDecision.Continue` / `HaltDecision.Halt(reason, strategyId?)`.
- `MaxDrawdown(maxFraction)` — global halt rule. Halts when global drawdown exceeds threshold.
- `MaxDailyLoss(maxLoss)` — global halt rule. Halts when daily realized below threshold (loss expressed as positive number).
- `MaxStrategyDrawdown(strategyId, maxFraction)` — per-strategy halt rule.
- `MaxStrategyDailyLoss(strategyId, maxLoss)` — per-strategy halt rule.
- `KillSwitch(riskState)` — submission rule. Reads `riskState.halted` / `haltedStrategies`, rejects matching submissions.
- `RiskView` interface + `RiskViewImpl` (filtered to a strategyId) + `NoOpRiskView` (test default).
- `StrategyContext.risk: RiskView` — every strategy receives its own filter. Read `ctx.risk.halted`, `ctx.risk.drawdown`, `ctx.risk.realizedToday`, etc.
- `RiskEngine(rules, haltRules, positions, riskState)` — new primary constructor. Backwards-compat shim `RiskEngine(rules, positions)` provided via `RiskState.noOp()` for legacy call sites.
- `RiskEngine.evaluateHaltRules()` — invoked by pipeline on each tick + fill. Skips during warmup (`riskState.warmupComplete`). Per-rule `runCatching` isolates buggy rules.
- `TradingPipeline(..., riskState: RiskState, ...)` — new required constructor parameter. Pipeline subscribes `TickEvent` and `OrderFilled` to drive `riskState.onTick()` / `onFill()` and `evaluateHaltRules()`.
- Application entry points (`Backtest`, `LiveSession`, `Main`) construct `RiskState` and pass to pipeline. `Backtest` and `Main` set `warmupComplete = true` immediately. `LiveSession` sets it after the indicator warmer finishes.

## Migration from previous phase

| 8 | 9 | Notes |
|---|---|---|
| `RiskEngine(rules, positions)` | `RiskEngine(rules, haltRules, positions, riskState)` | Convenience ctor `RiskEngine(rules, positions)` provided for tests via `RiskState.noOp()`. Existing simple call sites compile unchanged. |
| `StrategyContext` had 7 fields | gains `risk: RiskView` (8th) | `testStrategyContext()` provides `NoOpRiskView()` default. |
| `TradingPipeline` had no risk state | gains required `riskState: RiskState` parameter | Application-level wiring. |
| (no halt event) | `RiskEvent.Halted` / `RiskEvent.Resumed` | Subscribers opt-in. |
| `EventBus` exhaustive when had 13 cases | gains `RiskEvent.Halted` / `RiskEvent.Resumed` | Mechanical addition. |
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
riskState.warmupComplete = true
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
    riskState = riskState,                                                    // NEW
    ...,
)
```

## Usage cookbook

### 1. Configure halt rules at startup

```kotlin
val haltRules = listOf<HaltRule>(
    MaxDrawdown(BigDecimal("0.20")),                    // 20% peak-to-trough triggers halt
    MaxDailyLoss(BigDecimal("5000")),                   // $5k daily loss triggers halt
    MaxStrategyDrawdown("ema-cross-btc", BigDecimal("0.30")),  // strategy-only 30% cap
    MaxStrategyDailyLoss("ema-cross-btc", BigDecimal("2000")),
)
```

The engine evaluates these on every tick + fill. The first rule to return `Halt` triggers the halt. Subsequent submissions are blocked at the gate.

### 2. Strategy reads its own drawdown via `ctx.risk`

```kotlin
class ConservativeStrategy(private val symbol: String) : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        if (ctx.risk.drawdown > BigDecimal("0.10")) {
            log.info("Backing off: drawdown {}", ctx.risk.drawdown)
            return
        }
        if (ctx.risk.realizedToday < BigDecimal("-1000")) return
        if (someEntryCondition(tick)) emit(Signal.Buy(symbol, BigDecimal("0.1")))
    }
}
```

The strategy soft-throttles based on its own state. Hard caps still come from halt rules.

### 3. Subscribe to `RiskEvent.Halted` for alerting

```kotlin
bus.subscribe<RiskEvent.Halted> { event ->
    val target = event.strategyId ?: "GLOBAL"
    log.error("RISK HALT [{}]: {}", target, event.reason)
    slackBot.alert("qkt halted: $target → ${event.reason}")
}

bus.subscribe<RiskEvent.Resumed> { event ->
    val target = event.strategyId ?: "GLOBAL"
    log.info("RISK RESUMED [{}]", target)
}
```

Halts fire only on transition (idempotent). No flap loops.

### 4. Operator manually halts and resumes

```kotlin
// Manually halt the entire system (e.g., before a planned outage)
riskState.halt("planned maintenance")

// Block all new submissions until cleared
// existing positions remain open

// After maintenance:
riskState.resume()
```

For a single strategy:

```kotlin
riskState.haltStrategy("ema-cross-btc", "investigating loss spike")
// other strategies continue trading
riskState.resumeStrategy("ema-cross-btc")
```

### 5. Per-strategy halt without affecting others

```kotlin
val haltRules = listOf<HaltRule>(
    MaxStrategyDrawdown("aggressive-strat", BigDecimal("0.25")),
    MaxStrategyDrawdown("conservative-strat", BigDecimal("0.10")),
)
```

When `aggressive-strat` hits 25% drawdown, only it halts. `conservative-strat` continues with its own threshold.

## Testing patterns

- **`RiskState.noOp()`** — constructs a real `RiskState` with internal trackers wired but no halt rules. Used by `RiskEngine(rules, positions)` shim. Tests that don't need risk state don't construct one.
- **`TestRig` helper pattern** — see `HaltRulesTest`. Bundles `state, positions, strategyPositions, pnl, strategyPnL` and provides `applyAndRecord(event)` that calls all four `applyFill` / `recordRealized` paths in the same order the pipeline does.
- **`testStrategyContext(risk = NoOpRiskView())`** — default for strategy tests. Override `risk` to test `ctx.risk` reads.
- **Halt event capture** — subscribe `RiskEvent.Halted` / `RiskEvent.Resumed` to a `MutableList` and assert transitions. Idempotent halts produce one event each.
- **Drawdown setup** — record realized via `applyAndRecord` to lock in profit (sets peak), then realized losses to drop equity (drawdown emerges). Or push price up then down with positions held.

## Known limitations

- **No notional exposure caps.** Requires symbol-level price lookups for unrealized notional. Phase 10 has the data infrastructure; deferred.
- **No leverage caps.** Derivatives margin math; Phase 10+ scope.
- **No volatility-based circuit breakers.** Requires vol indicator integration; Phase 10+.
- **No automatic position flattening on halt.** Industry default leaves positions open in fast markets — auto-close compounds losses. Operator decides; if you want auto-close, build it as a halt-event subscriber that fires cancel/flatten orders.
- **No automatic resume on improving conditions.** Risk transitions are one-way until human input. Auto-resume creates flap loops in volatile markets.
- **No persistence of risk state across JVM restarts.** Same as 7f-8.
- **No per-broker risk gating.** Risk applies at the engine layer, broker-agnostic. If you want venue-specific limits, build a wrapper broker.
- **Peak equity is permanent within a `RiskState` instance.** Once a peak is set, it stays. To reset risk after a halt-and-resume cycle, rebuild the engine with a fresh `RiskState`. Auto-reset on resume would mask the drawdown that triggered the halt — wrong.
- **`DailyPnLTracker` rollover at UTC midnight only.** No timezone configurability yet. NY/London/Asia operators must adjust thresholds for their effective day.
- **Equity curve is in-memory current state, not history.** No time series of `(timestamp, equity)` pairs. Phase 10 backtest reporting consumes the live trackers; longer-term history needs persistence.
- **Halt rules evaluated synchronously on tick/fill.** A slow rule (e.g., one doing I/O — don't do this) blocks the dispatch thread. `runCatching` isolates exceptions but not slowness.
- **`KillSwitch` is one-shot per evaluation.** A submission that arrives during a halt is rejected; the halt itself is permanent until `resume()`. There's no "halt for 5 minutes then auto-resume."
- **`RiskEngine.evaluateHaltRules()` skips during warmup.** Manually set `riskState.warmupComplete = true` after warmup phase. `Backtest` and `Main` set it immediately; `LiveSession` sets it after `IndicatorWarmer.warmup()`.
- **Rule order matters in `RiskEngine.approve()`.** First `Decision.Reject` wins. Place `KillSwitch` first if you want it to short-circuit before slow per-position rules.
- **`HaltDecision.Halt` with non-existent `strategyId` works silently.** No validation. Halts a strategy nobody trades. Documented; rules are in user code.

## References

- Spec: [`../superpowers/specs/2026-05-06-trading-engine-phase9-design.md`](../superpowers/specs/2026-05-06-trading-engine-phase9-design.md)
- Plan: [`../superpowers/plans/2026-05-06-trading-engine-phase9.md`](../superpowers/plans/2026-05-06-trading-engine-phase9.md)
- Phase 8 baseline: [`phase-8-strategy-context-and-pnl-attribution.md`](phase-8-strategy-context-and-pnl-attribution.md)
