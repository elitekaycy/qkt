# Prop-firm risk management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable total + daily drawdown halt rules for prop-firm accounts, plus per-day backtest metrics (#348, gaps 1–4; gap #5 deferred).

**Architecture:** New `HaltRule`s (`MaxDailyDrawdown`, `MaxStrategyDailyDrawdown`) mirror the existing `MaxDailyLoss` pair; the dormant `MaxDrawdown`/`MaxStrategyDrawdown` get wired and gain a `trailing|static` basis. A new `DailyDrawdownTracker` on `RiskState` (UTC rollover like `DailyPnLTracker`) holds the day-start reference (`balance|equity`). Backtest daily metrics come from a new online `DailyDrawdownAccumulator` in `EquityMetrics` plus trade-bucketed `dailyPnL`.

**Tech Stack:** Kotlin, Gradle, JUnit5 + AssertJ, the `com.qkt.risk` + `com.qkt.backtest` subsystems.

**Spec:** `docs/superpowers/specs/2026-06-09-prop-firm-risk-design.md`
**Branch:** `feat-prop-firm-risk` (off dev, already created).
**Conventions:** subject-only conventional commits, scope `risk`/`backtesting`/`cli`; `./gradlew ktlintFormat` before each commit; keep lines ≤120 (ktlint `max-line-length` is not auto-fixable). Push and let CI run the full suite.

**Key facts (verified):**
- `EquityTracker` equity is **PnL-relative (0-based)**: `currentEquity() = realized + unrealized`. So absolute equity = `initialBalance + currentEquity()`, and **STATIC total DD = `max(0, −currentEquity()) / initialBalance`**.
- `DrawdownTracker.globalDrawdown()`/`strategyDrawdown()` are consumed by `RiskView` (display) — keep them trailing; add **separate static methods** for the rule to dispatch to.
- `StrategyPnL` exposes absolute `balanceFor(id)` (start+realized) and `equityFor(id)` (start+realized+unrealized) — used for the daily reference.
- Rules are `HaltRule`: `evaluate(riskState): HaltDecision` → `Continue | Halt(reason, strategyId?)`.

---

### Task 1: Basis enums

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/DrawdownBasis.kt`
- Create: `src/main/kotlin/com/qkt/risk/DailyDrawdownBasis.kt`

- [ ] **Step 1: Create `DrawdownBasis.kt`**

```kotlin
package com.qkt.risk

/** How total drawdown is measured: from the session high-water mark, or from the initial balance. */
enum class DrawdownBasis {
    TRAILING,
    STATIC,
    ;

    companion object {
        fun fromConfig(value: String?): DrawdownBasis =
            when (value?.lowercase()) {
                null, "static" -> STATIC
                "trailing" -> TRAILING
                else -> throw IllegalArgumentException("unknown total_dd_basis '$value' (valid: trailing, static)")
            }
    }
}
```

- [ ] **Step 2: Create `DailyDrawdownBasis.kt`**

```kotlin
package com.qkt.risk

/** The day-start reference for daily drawdown: closed balance, or equity (includes open-position float). */
enum class DailyDrawdownBasis {
    BALANCE,
    EQUITY,
    ;

    companion object {
        fun fromConfig(value: String?): DailyDrawdownBasis =
            when (value?.lowercase()) {
                null, "balance" -> BALANCE
                "equity" -> EQUITY
                else -> throw IllegalArgumentException("unknown daily_dd_basis '$value' (valid: balance, equity)")
            }
    }
}
```

- [ ] **Step 3: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/DrawdownBasis.kt src/main/kotlin/com/qkt/risk/DailyDrawdownBasis.kt
git commit -m "feat(risk): add drawdown basis enums"
```

---

### Task 2: Static drawdown methods on `DrawdownTracker`

Add static-basis methods; leave the trailing `globalDrawdown()`/`strategyDrawdown()` untouched (RiskView depends on them).

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/DrawdownTracker.kt`
- Test: `src/test/kotlin/com/qkt/risk/DrawdownTrackerTest.kt`

- [ ] **Step 1: Write the failing test** (append to the existing test class if present; else create it)

```kotlin
package com.qkt.risk

import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerStaticTest {
    private class FakePnL(
        private var realized: BigDecimal,
        private var unrealized: BigDecimal,
    ) : PnLProvider {
        override fun realizedTotal() = realized
        override fun unrealizedTotal() = unrealized
    }

    private fun tracker(realized: String, unrealized: String): DrawdownTracker {
        val et = EquityTracker(FakePnL(BigDecimal(realized), BigDecimal(unrealized)), StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()))
        et.update()
        return DrawdownTracker(et)
    }

    @Test
    fun `static global drawdown is loss over initial balance`() {
        // PnL -800 on a 10000 account => 8% static DD
        assertThat(tracker("-800", "0").globalStaticDrawdown(BigDecimal("10000")))
            .isEqualByComparingTo(BigDecimal("0.08"))
    }

    @Test
    fun `static drawdown is zero when in profit`() {
        assertThat(tracker("500", "0").globalStaticDrawdown(BigDecimal("10000")))
            .isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `static drawdown is zero when initial balance is non-positive`() {
        assertThat(tracker("-800", "0").globalStaticDrawdown(BigDecimal.ZERO))
            .isEqualByComparingTo(BigDecimal.ZERO)
    }
}
```

> Confirm the `PnLProvider` interface members (`realizedTotal()`, `unrealizedTotal()`) before writing the fake — adjust the fake to match. If `PnLProvider` has more members, implement them returning zero.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.risk.DrawdownTrackerStaticTest" --console=plain`
Expected: FAIL — `globalStaticDrawdown` unresolved.

- [ ] **Step 3: Add static methods** (inside `class DrawdownTracker`, after `strategyDrawdown`)

```kotlin
    /** Total drawdown measured from [initialBalance]: `max(0, −pnlEquity) / initialBalance`. */
    fun globalStaticDrawdown(initialBalance: BigDecimal): BigDecimal {
        if (initialBalance.signum() <= 0) return Money.ZERO
        val pnl = equityTracker.currentEquity()
        if (pnl.signum() >= 0) return Money.ZERO
        return pnl.negate().divide(initialBalance, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    /** Per-strategy static drawdown measured from [initialBalance]. */
    fun strategyStaticDrawdown(
        strategyId: String,
        initialBalance: BigDecimal,
    ): BigDecimal {
        if (initialBalance.signum() <= 0) return Money.ZERO
        val pnl = equityTracker.currentEquityFor(strategyId)
        if (pnl.signum() >= 0) return Money.ZERO
        return pnl.negate().divide(initialBalance, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.risk.DrawdownTrackerStaticTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/DrawdownTracker.kt src/test/kotlin/com/qkt/risk/DrawdownTrackerStaticTest.kt
git commit -m "feat(risk): add static-basis drawdown to DrawdownTracker"
```

---

### Task 3: `DailyDrawdownTracker`

UTC-rollover daily drawdown; day-start reference captured lazily on first query of the new day (RiskEngine queries every tick, so this is ~midnight).

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/DailyDrawdownTracker.kt`
- Test: `src/test/kotlin/com/qkt/risk/DailyDrawdownTrackerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.risk

import com.qkt.common.MutableFixedClock
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyDrawdownTrackerTest {
    private class FakePnL(var realized: BigDecimal, var unrealized: BigDecimal) : PnLProvider {
        override fun realizedTotal() = realized
        override fun unrealizedTotal() = unrealized
    }

    @Test
    fun `balance basis ignores open float in the reference`() {
        val clock = MutableFixedClock(0L) // 1970-01-01
        val pnl = FakePnL(BigDecimal("0"), BigDecimal("100")) // balance ref excludes the +100 float
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("10000"), pnl, StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()))
        // ref = 10000 (balance, no float). Now equity drops 400 below balance:
        pnl.realized = BigDecimal("-400"); pnl.unrealized = BigDecimal("0")
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal("0.04"))
    }

    @Test
    fun `rolls over at UTC midnight and recaptures the reference`() {
        val clock = MutableFixedClock(0L)
        val pnl = FakePnL(BigDecimal("-400"), BigDecimal("0"))
        val t = DailyDrawdownTracker(clock, DailyDrawdownBasis.BALANCE, BigDecimal("10000"), pnl, StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()))
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal("0.04")) // ref 10000, equity 9600
        clock.set(86_400_000L) // next UTC day → ref recaptured at current balance 9600
        assertThat(t.globalDrawdownToday()).isEqualByComparingTo(BigDecimal.ZERO)
    }
}
```

> `MutableFixedClock`: if the codebase only has `FixedClock` (immutable), add a tiny test double in the test file implementing `com.qkt.common.Clock` with a settable `now()`. Confirm the `Clock` interface method name (`now(): Long`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.risk.DailyDrawdownTrackerTest" --console=plain`
Expected: FAIL — `DailyDrawdownTracker` unresolved.

- [ ] **Step 3: Create `DailyDrawdownTracker.kt`**

```kotlin
package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily drawdown vs a reference captured at UTC midnight. The reference is the day-start balance
 * (closed) or equity (incl. open float) per [basis]; drawdown is measured against current equity:
 * `max(0, (reference − currentEquity) / reference)`. Mirrors [DailyPnLTracker]'s UTC rollover.
 *
 * Global current equity is absolute: `initialBalance + realized + unrealized`. Per-strategy uses
 * [StrategyPnL]'s already-absolute `balanceFor` / `equityFor`.
 */
class DailyDrawdownTracker(
    private val clock: Clock,
    private val basis: DailyDrawdownBasis,
    private val initialBalance: BigDecimal,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
) {
    @Volatile private var lastResetEpochDay: Long = epochDay()
    @Volatile private var globalRef: BigDecimal? = null
    private val strategyRef: MutableMap<String, BigDecimal> = ConcurrentHashMap()

    fun globalDrawdownToday(): BigDecimal {
        rolloverIfNeeded()
        val ref = globalRef ?: captureGlobalRef().also { globalRef = it }
        val current = initialBalance.add(pnl.realizedTotal()).add(pnl.unrealizedTotal())
        return ddFraction(ref, current)
    }

    fun strategyDrawdownToday(strategyId: String): BigDecimal {
        rolloverIfNeeded()
        val ref = strategyRef.getOrPut(strategyId) { captureStrategyRef(strategyId) }
        return ddFraction(ref, strategyPnL.equityFor(strategyId))
    }

    private fun captureGlobalRef(): BigDecimal {
        val float = if (basis == DailyDrawdownBasis.EQUITY) pnl.unrealizedTotal() else Money.ZERO
        return initialBalance.add(pnl.realizedTotal()).add(float)
    }

    private fun captureStrategyRef(strategyId: String): BigDecimal =
        if (basis == DailyDrawdownBasis.EQUITY) strategyPnL.equityFor(strategyId) else strategyPnL.balanceFor(strategyId)

    private fun ddFraction(ref: BigDecimal, current: BigDecimal): BigDecimal {
        if (ref.signum() <= 0 || current >= ref) return Money.ZERO
        return ref.subtract(current).divide(ref, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
    }

    @Synchronized
    private fun rolloverIfNeeded() {
        val today = epochDay()
        if (today != lastResetEpochDay) {
            globalRef = null
            strategyRef.clear()
            lastResetEpochDay = today
        }
    }

    private fun epochDay(): Long =
        Instant.ofEpochMilli(clock.now()).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.risk.DailyDrawdownTrackerTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/DailyDrawdownTracker.kt src/test/kotlin/com/qkt/risk/DailyDrawdownTrackerTest.kt
git commit -m "feat(risk): add DailyDrawdownTracker with UTC rollover and configurable basis"
```

---

### Task 4: Wire `DailyDrawdownTracker` into `RiskState`

`RiskState` gains `initialBalance` + `dailyDdBasis` params and exposes a `dailyDrawdownTracker`. Defaults keep every existing caller compiling and behavior-neutral (initialBalance 0 → daily DD inert until configured).

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/RiskState.kt`
- Test: `src/test/kotlin/com/qkt/risk/RiskStateDailyDrawdownTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.risk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskStateDailyDrawdownTest {
    @Test
    fun `exposes a daily drawdown tracker`() {
        assertThat(RiskState.noOp().dailyDrawdownTracker).isNotNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.risk.RiskStateDailyDrawdownTest" --console=plain` → FAIL (`dailyDrawdownTracker` unresolved).

- [ ] **Step 3: Modify `RiskState`** — add params + the tracker. Change the constructor and add the field:

```kotlin
class RiskState(
    pnl: PnLProvider,
    strategyPnL: StrategyPnL,
    private val clock: Clock,
    private val bus: EventBus,
    initialBalance: BigDecimal = BigDecimal.ZERO,
    dailyDdBasis: DailyDrawdownBasis = DailyDrawdownBasis.BALANCE,
) {
    val equityTracker: EquityTracker = EquityTracker(pnl, strategyPnL)
    val drawdownTracker: DrawdownTracker = DrawdownTracker(equityTracker)
    val dailyPnLTracker: DailyPnLTracker = DailyPnLTracker(clock)
    val dailyDrawdownTracker: DailyDrawdownTracker =
        DailyDrawdownTracker(clock, dailyDdBasis, initialBalance, pnl, strategyPnL)
    // ... rest unchanged
```

The existing `noOp()` companion needs no change (defaults apply).

- [ ] **Step 4: Run to verify it passes** — same command → PASS.

- [ ] **Step 5: Build the module** to catch any caller that passed positional args after `bus` (none expected, but verify):

Run: `./gradlew compileKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/RiskState.kt src/test/kotlin/com/qkt/risk/RiskStateDailyDrawdownTest.kt
git commit -m "feat(risk): RiskState owns a DailyDrawdownTracker"
```

---

### Task 5: Basis-aware total-DD rules

Give `MaxDrawdown` / `MaxStrategyDrawdown` a `basis` + `initialBalance`, dispatching to trailing or static.

**Files:**
- Modify: `src/main/kotlin/com/qkt/risk/rules/MaxDrawdown.kt`, `src/main/kotlin/com/qkt/risk/rules/MaxStrategyDrawdown.kt`
- Test: `src/test/kotlin/com/qkt/risk/rules/MaxDrawdownBasisTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.risk.rules

import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltDecision
import com.qkt.risk.RiskState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxDrawdownBasisTest {
    @Test
    fun `static basis halts when loss exceeds fraction of initial balance`() {
        val rs = RiskState.noOp()
        // Drive PnL to -900 on a 10000 account => 9% static DD; limit 8% => halt.
        rs.onFill("s", BigDecimal("-900"))
        rs.equityTracker.update()
        val rule = MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("10000"))
        assertThat(rule.evaluate(rs)).isInstanceOf(HaltDecision.Halt::class.java)
    }

    @Test
    fun `static basis continues under the limit`() {
        val rs = RiskState.noOp()
        rs.onFill("s", BigDecimal("-500"))
        rs.equityTracker.update()
        val rule = MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("10000"))
        assertThat(rule.evaluate(rs)).isEqualTo(HaltDecision.Continue)
    }
}
```

> Verify how `RiskState.noOp()` reflects PnL: `onFill` records realized into `DailyPnLTracker`, but `EquityTracker.currentEquity()` reads from the `PnLProvider`. In `noOp`, the `PnLProvider` is a real `PnLCalculator` over empty positions, so realized stays 0 and the static DD test won't see -900. **Therefore use a custom `RiskState` built with a fake `PnLProvider`** (as in Task 2's test) rather than `noOp()`. Rewrite the test to construct `RiskState(FakePnL(-900,0), StrategyPnL(...), clock, bus, initialBalance=10000)` and call `rs.onTick()` to refresh the equity tracker. Confirm `EventBus` construction from `RiskState.noOp` for the fake.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Modify `MaxDrawdown`:**

```kotlin
class MaxDrawdown(
    private val maxFraction: BigDecimal,
    private val basis: DrawdownBasis = DrawdownBasis.STATIC,
    private val initialBalance: BigDecimal = BigDecimal.ZERO,
) : HaltRule {
    init {
        require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) {
            "maxFraction must be in (0, 1]: $maxFraction"
        }
    }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd =
            when (basis) {
                DrawdownBasis.TRAILING -> riskState.drawdownTracker.globalDrawdown()
                DrawdownBasis.STATIC -> riskState.drawdownTracker.globalStaticDrawdown(initialBalance)
            }
        return if (dd > maxFraction) {
            HaltDecision.Halt("global drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction")
        } else {
            HaltDecision.Continue
        }
    }
}
```

Add `import com.qkt.risk.DrawdownBasis`. Apply the identical change to `MaxStrategyDrawdown` (use `strategyDrawdown(strategyId)` / `strategyStaticDrawdown(strategyId, initialBalance)`, keep the `strategyId` on the Halt).

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/rules/MaxDrawdown.kt src/main/kotlin/com/qkt/risk/rules/MaxStrategyDrawdown.kt src/test/kotlin/com/qkt/risk/rules/MaxDrawdownBasisTest.kt
git commit -m "feat(risk): basis-aware total drawdown rules"
```

---

### Task 6: Daily-DD halt rules

**Files:**
- Create: `src/main/kotlin/com/qkt/risk/rules/MaxDailyDrawdown.kt`, `src/main/kotlin/com/qkt/risk/rules/MaxStrategyDailyDrawdown.kt`
- Test: `src/test/kotlin/com/qkt/risk/rules/MaxDailyDrawdownTest.kt`

- [ ] **Step 1: Write the failing test** (build a `RiskState` with a fake `PnLProvider` like Task 5; set PnL so daily DD > limit; assert Halt).

```kotlin
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.RiskState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MaxDailyDrawdownTest {
    @Test
    fun `halts when daily drawdown exceeds the limit`() {
        // Construct RiskState with a fake PnLProvider at realized -500 on a 10000 account (5% > 4%).
        // See Task 5 Step 1 note for the fake-PnL RiskState construction.
        val rs = buildRiskState(realized = "-500", initialBalance = "10000")
        rs.onTick()
        assertThat(MaxDailyDrawdown(BigDecimal("0.04")).evaluate(rs)).isInstanceOf(HaltDecision.Halt::class.java)
    }
}
```

> Provide `buildRiskState(realized, initialBalance)` as a small helper in the test mirroring `RiskState.noOp` but with a `FakePnL`. Reuse the `FakePnL` from Task 2 (lift it to a shared `src/test/kotlin/com/qkt/risk/FakePnL.kt` in this task and reuse in Tasks 2/5/6).

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Create the rules** (mirror `MaxDailyLoss` / `MaxStrategyDailyLoss`):

```kotlin
// MaxDailyDrawdown.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

class MaxDailyDrawdown(
    private val maxFraction: BigDecimal,
) : HaltRule {
    init { require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) { "maxFraction must be in (0, 1]: $maxFraction" } }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.dailyDrawdownTracker.globalDrawdownToday()
        return if (dd > maxFraction) {
            HaltDecision.Halt("daily drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction")
        } else {
            HaltDecision.Continue
        }
    }
}
```

```kotlin
// MaxStrategyDailyDrawdown.kt
package com.qkt.risk.rules

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.math.RoundingMode

class MaxStrategyDailyDrawdown(
    private val strategyId: String,
    private val maxFraction: BigDecimal,
) : HaltRule {
    init { require(maxFraction.signum() > 0 && maxFraction <= BigDecimal.ONE) { "maxFraction must be in (0, 1]: $maxFraction" } }

    override fun evaluate(riskState: RiskState): HaltDecision {
        val dd = riskState.dailyDrawdownTracker.strategyDrawdownToday(strategyId)
        return if (dd > maxFraction) {
            HaltDecision.Halt(
                reason = "strategy daily drawdown ${dd.setScale(4, RoundingMode.HALF_UP)} exceeds max $maxFraction",
                strategyId = strategyId,
            )
        } else {
            HaltDecision.Continue
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/risk/rules/MaxDailyDrawdown.kt src/main/kotlin/com/qkt/risk/rules/MaxStrategyDailyDrawdown.kt src/test/kotlin/com/qkt/risk/rules/MaxDailyDrawdownTest.kt src/test/kotlin/com/qkt/risk/FakePnL.kt
git commit -m "feat(risk): add daily drawdown halt rules"
```

---

### Task 7: Config keys + per-strategy fields

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/Config.kt`, `src/main/kotlin/com/qkt/cli/PerStrategyRisk.kt`
- Test: `src/test/kotlin/com/qkt/cli/ConfigRiskTest.kt` (create or extend the existing Config test)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigRiskTest {
    @Test
    fun `reads drawdown pct as fractions, bases, and per-strategy overrides`() {
        val cfg =
            Config(
                risk =
                    mapOf(
                        "max_drawdown_pct" to "8",
                        "max_daily_drawdown_pct" to "4",
                        "total_dd_basis" to "static",
                        "daily_dd_basis" to "balance",
                    ),
                perStrategyRisk = mapOf("ema" to PerStrategyRisk(maxDrawdownPct = BigDecimal("0.05"))),
            )
        assertThat(cfg.maxDrawdownPct).isEqualByComparingTo(BigDecimal("0.08"))
        assertThat(cfg.maxDailyDrawdownPct).isEqualByComparingTo(BigDecimal("0.04"))
        assertThat(cfg.totalDdBasis).isEqualTo(DrawdownBasis.STATIC)
        assertThat(cfg.dailyDdBasis).isEqualTo(DailyDrawdownBasis.BALANCE)
        assertThat(cfg.perStrategyRisk["ema"]!!.maxDrawdownPct).isEqualByComparingTo(BigDecimal("0.05"))
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Add to `Config`** (accessors; `_pct` parsed as percent → fraction, validated `(0,100]`):

```kotlin
    /** Total-DD halt threshold as a fraction (config `max_drawdown_pct` is a percent), or null if unset. */
    val maxDrawdownPct: BigDecimal?
        get() = pctFraction(risk["max_drawdown_pct"])

    val maxDailyDrawdownPct: BigDecimal?
        get() = pctFraction(risk["max_daily_drawdown_pct"])

    val totalDdBasis: com.qkt.risk.DrawdownBasis
        get() = com.qkt.risk.DrawdownBasis.fromConfig(risk["total_dd_basis"])

    val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis
        get() = com.qkt.risk.DailyDrawdownBasis.fromConfig(risk["daily_dd_basis"])
```

Add a private helper (top-level or companion):

```kotlin
        private fun pctFraction(raw: String?): BigDecimal? {
            if (raw == null) return null
            val pct = BigDecimal(raw)
            require(pct.signum() > 0 && pct <= BigDecimal(100)) { "drawdown pct must be in (0, 100]: $raw" }
            return pct.divide(BigDecimal(100))
        }
```

In `parsePerStrategyRisk` (line ~254), add the two fields:

```kotlin
                    maxDrawdownPct = m["max_drawdown_pct"]?.toString()?.let { pctFraction(it) },
                    maxDailyDrawdownPct = m["max_daily_drawdown_pct"]?.toString()?.let { pctFraction(it) },
```

- [ ] **Step 4: Add the fields to `PerStrategyRisk`:**

```kotlin
data class PerStrategyRisk(
    val maxDailyLoss: BigDecimal? = null,
    val maxPositionSize: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxDrawdownPct: BigDecimal? = null,
    val maxDailyDrawdownPct: BigDecimal? = null,
) {
    init {
        // ... existing checks ...
        if (maxDrawdownPct != null) require(maxDrawdownPct.signum() > 0 && maxDrawdownPct <= BigDecimal.ONE) { "maxDrawdownPct must be in (0, 1]" }
        if (maxDailyDrawdownPct != null) require(maxDailyDrawdownPct.signum() > 0 && maxDailyDrawdownPct <= BigDecimal.ONE) { "maxDailyDrawdownPct must be in (0, 1]" }
    }
}
```

> Note: `PerStrategyRisk.maxDrawdownPct` stores a **fraction** (the config parse divides by 100), so its validation is `(0,1]`. `Config.pctFraction` is the single conversion point.

- [ ] **Step 5: Run to verify it passes.**

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/Config.kt src/main/kotlin/com/qkt/cli/PerStrategyRisk.kt src/test/kotlin/com/qkt/cli/ConfigRiskTest.kt
git commit -m "feat(cli): config keys for drawdown limits and basis"
```

---

### Task 8: Global wiring in `StrategyHandle`

Pass `initialBalance` + bases into `RiskState`, and add `MaxDrawdown` / `MaxDailyDrawdown` to the global halt rules.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt`

- [ ] **Step 1: Read** `StrategyHandle.kt` around lines 70–160: the `RealFactory` params (`maxDailyLoss`), the `haltRules` list construction (~line 118), and where `RiskState` is constructed (it may be inside `LiveSession`, not `StrategyHandle` — trace it). Identify (a) where the config thresholds arrive and (b) where `RiskState` and the global `haltRules` are built.

- [ ] **Step 2: Thread the new config values** from `Config` → `StrategyHandle.RealFactory` (add ctor params `maxDrawdownPct: BigDecimal?`, `maxDailyDrawdownPct: BigDecimal?`, `totalDdBasis: DrawdownBasis`, `dailyDdBasis: DailyDrawdownBasis`, `startingBalance: BigDecimal`) mirroring how `maxDailyLoss` is threaded. Update the call site that builds `RealFactory` (likely in a daemon command) to pass `config.maxDrawdownPct`, etc., and the account starting balance.

- [ ] **Step 3: Extend the global `haltRules` construction** (the block at ~line 118):

```kotlin
            val haltRules: List<com.qkt.risk.HaltRule> =
                buildList {
                    if (maxDailyLoss.signum() > 0) add(com.qkt.risk.rules.MaxDailyLoss(maxDailyLoss))
                    maxDrawdownPct?.let { add(com.qkt.risk.rules.MaxDrawdown(it, totalDdBasis, startingBalance)) }
                    maxDailyDrawdownPct?.let { add(com.qkt.risk.rules.MaxDailyDrawdown(it)) }
                }
```

- [ ] **Step 4: Pass `startingBalance` + `dailyDdBasis`** into the `RiskState(...)` constructor wherever it's built for this strategy (so the daily tracker has the right reference + basis). If `RiskState` is constructed in `LiveSession`, thread these through to it (see Task 9).

- [ ] **Step 5: Build** — `./gradlew compileKotlin --console=plain` → SUCCESS.

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt
git commit -m "feat(cli): wire global drawdown halt rules from config"
```

---

### Task 9: Per-strategy wiring in `LiveSession`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`

- [ ] **Step 1: Read** `LiveSession.kt` lines ~440–490 (the per-strategy halt-rule construction from `perStrategyMaxDailyLoss`) and where `RiskState` is constructed.

- [ ] **Step 2: Construct `RiskState` with the new args** — pass `initialBalance = startingBalance` and `dailyDdBasis = dailyDdBasis` (threaded from `StrategyHandle`).

- [ ] **Step 3: Add per-strategy rules** mirroring `MaxStrategyDailyLoss`:

```kotlin
        perStrategyMaxDrawdownPct?.let {
            perStrategyHaltRules.add(com.qkt.risk.rules.MaxStrategyDrawdown(riskOwnerStrategyId, it, totalDdBasis, perStrategyStartingBalance))
        }
        perStrategyMaxDailyDrawdownPct?.let {
            perStrategyHaltRules.add(com.qkt.risk.rules.MaxStrategyDailyDrawdown(riskOwnerStrategyId, it))
        }
```

> Thread `perStrategyMaxDrawdownPct` / `perStrategyMaxDailyDrawdownPct` from `PerStrategyRisk` through `StrategyHandle` into `LiveSession`, mirroring `perStrategyMaxDailyLoss`. `perStrategyStartingBalance` = the strategy's allocated starting balance (already available where `StrategyPnL.setStartingBalance` is called).

- [ ] **Step 4: Build + run the existing risk/live tests** — `./gradlew test --tests "com.qkt.risk.*" --tests "com.qkt.app.*" --console=plain` → PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/app/LiveSession.kt
git commit -m "feat(app): wire per-strategy drawdown halt rules"
```

---

### Task 10: `DailyDrawdownAccumulator` + `EquityMetrics` integration

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/DailyDrawdownAccumulator.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/EquityMetrics.kt`
- Test: `src/test/kotlin/com/qkt/backtest/DailyDrawdownAccumulatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DailyDrawdownAccumulatorTest {
    private val day = 86_400_000L

    @Test
    fun `worst intraday decline from day-open across days`() {
        val acc = DailyDrawdownAccumulator()
        // Day 0: open 100, dips to 90 => 10% intraday DD
        acc.accept(0L, BigDecimal("100"))
        acc.accept(1_000L, BigDecimal("90"))
        // Day 1: open 90, dips to 81 => 10% intraday DD; recovers
        acc.accept(day, BigDecimal("90"))
        acc.accept(day + 1_000L, BigDecimal("81"))
        acc.accept(day + 2_000L, BigDecimal("95"))
        assertThat(acc.maxDailyDrawdown()).isEqualByComparingTo(BigDecimal("0.10"))
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Create `DailyDrawdownAccumulator.kt`** (online; equity-basis intraday DD from day-open):

```kotlin
package com.qkt.backtest

import com.qkt.common.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Online worst-intraday-drawdown accumulator. For each UTC day it tracks the day-open equity and the
 * running intraday minimum; the day's drawdown is `(open − min) / open`. [maxDailyDrawdown] is the
 * largest such value across all days. Constant memory.
 *
 * e.g. day opens 100, dips to 90 → 0.10 for that day.
 */
class DailyDrawdownAccumulator {
    private var currentDay: Long = Long.MIN_VALUE
    private var dayOpen: BigDecimal = Money.ZERO
    private var dayMin: BigDecimal = Money.ZERO
    private var worst: BigDecimal = Money.ZERO

    fun accept(timestamp: Long, equity: BigDecimal) {
        val day = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        if (day != currentDay) {
            currentDay = day
            dayOpen = equity
            dayMin = equity
        }
        if (equity < dayMin) {
            dayMin = equity
            if (dayOpen.signum() > 0 && dayMin < dayOpen) {
                val dd = dayOpen.subtract(dayMin).divide(dayOpen, Money.CONTEXT)
                if (dd > worst) worst = dd
            }
        }
    }

    fun maxDailyDrawdown(): BigDecimal = worst.setScale(Money.SCALE, Money.ROUNDING)
}
```

- [ ] **Step 4: Wire into `EquityMetrics`** — add the accumulator field, feed it in `accept`, expose it:

```kotlin
    private val dailyDrawdown = DailyDrawdownAccumulator()
    // in accept(timestamp, equity), after episodes.accept(...):
        dailyDrawdown.accept(timestamp, equity)
    // new read method:
    fun maxDailyDrawdown(): BigDecimal = dailyDrawdown.maxDailyDrawdown()
```

- [ ] **Step 5: Run to verify it passes** (`DailyDrawdownAccumulatorTest`).

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/backtest/DailyDrawdownAccumulator.kt src/main/kotlin/com/qkt/backtest/EquityMetrics.kt src/test/kotlin/com/qkt/backtest/DailyDrawdownAccumulatorTest.kt
git commit -m "feat(backtesting): online daily-drawdown accumulator in EquityMetrics"
```

---

### Task 11: `PerformanceReport` daily metrics + `ReportBuilder`

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`, `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`
- Test: `src/test/kotlin/com/qkt/backtest/ReportBuilderDailyTest.kt`

- [ ] **Step 1: Add fields to `PerformanceReport`** (defaults keep existing constructors working):

```kotlin
    val dailyPnL: Map<java.time.LocalDate, BigDecimal> = emptyMap(),
    val maxDailyDrawdown: BigDecimal = BigDecimal.ZERO,
```

- [ ] **Step 2: Read `ReportBuilder.build`/`buildGlobal`** to see the `trades` + `metrics` (EquityMetrics) in scope. Then:
  - Compute `dailyPnL` by bucketing `trades` by the UTC date of each trade's fill timestamp:
    ```kotlin
    val dailyPnL = trades.groupBy {
        java.time.Instant.ofEpochMilli(it.trade.timestamp).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    }.mapValues { (_, ts) -> ts.fold(BigDecimal.ZERO) { a, r -> a.add(r.realized) } }
    ```
    > Confirm the timestamp field on `com.qkt.execution.Trade` (likely `timestamp`/`time`/`closedAt`); use the fill/close time. If the trade has no usable timestamp, fall back to bucketing the equity curve's realized deltas by day.
  - Pull `maxDailyDrawdown` from `metrics?.maxDailyDrawdown() ?: BigDecimal.ZERO`.
  - Pass both into the `PerformanceReport(...)` constructor.

- [ ] **Step 3: Write the test** — feed `ReportBuilder.buildGlobal` (or `build`) trades spanning two UTC days + an `EquityMetrics` with a multi-day curve; assert `dailyPnL` has two dated buckets summing correctly and `maxDailyDrawdown > 0`. Match the existing `ReportBuilder` test's construction style.

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/backtest/PerformanceReport.kt src/main/kotlin/com/qkt/backtest/ReportBuilder.kt src/test/kotlin/com/qkt/backtest/ReportBuilderDailyTest.kt
git commit -m "feat(backtesting): per-day PnL and max daily drawdown in PerformanceReport"
```

---

### Task 12: Emit daily metrics in the report output

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt`
- Test: `src/test/kotlin/com/qkt/cli/ReportPrinterTest.kt` (extend)

- [ ] **Step 1: Read `ReportFormat.kt`** — the text block and `printJson`.

- [ ] **Step 2: Text report** — add a line near `Max drawdown:`:
  ```kotlin
  out.println("Max daily drawdown: ${g.maxDailyDrawdown.toPlainString()}")
  ```

- [ ] **Step 3: JSON** — add to `printJson`:
  ```kotlin
  sb.append("\"maxDailyDrawdown\":").append(g.maxDailyDrawdown.toPlainString()).append(',')
  val daily = g.dailyPnL.entries.sortedBy { it.key }.joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" }
  sb.append("\"dailyPnL\":{").append(daily).append('}')
  ```
  > Place these so the surrounding commas stay valid JSON (check the field order in the existing builder).

- [ ] **Step 4: Extend `ReportPrinterTest`** — build a `PerformanceReport` with a non-empty `dailyPnL` + `maxDailyDrawdown`; assert the text contains `Max daily drawdown:` and the JSON contains `"maxDailyDrawdown"` and `"dailyPnL"`.

- [ ] **Step 5: Run to verify it passes.**

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/ReportFormat.kt src/test/kotlin/com/qkt/cli/ReportPrinterTest.kt
git commit -m "feat(cli): emit daily PnL and max daily drawdown in backtest report"
```

---

### Task 13: Docs, full verification, PR

**Files:**
- Modify: `docs/reference/config-schema.md` (risk section), `docs/concepts/backtest-model.md` or `docs/operations/*` (daily-DD metric note).

- [ ] **Step 1: Document** the new `risk` keys (`max_drawdown_pct`, `max_daily_drawdown_pct`, `total_dd_basis`, `daily_dd_basis`, per-strategy) in `config-schema.md`, and note the new `--json` fields (`dailyPnL`, `maxDailyDrawdown`).

- [ ] **Step 2: Full local sanity** — `./gradlew ktlintFormat test --tests "com.qkt.risk.*" --tests "com.qkt.backtest.*" --tests "com.qkt.cli.*" --console=plain`. Fix any failures.

- [ ] **Step 3: Real backtest emits the metrics** — run a multi-day XAUUSD backtest with `--json` (cached data or auto-fetch) and confirm `dailyPnL` + `maxDailyDrawdown` appear:
  ```bash
  qkt backtest strat.qkt --from 2026-06-02 --to 2026-06-05 --json | grep -o '"maxDailyDrawdown":[^,]*'
  ```

- [ ] **Step 4: Push + PR**

```bash
git push -u origin feat-prop-firm-risk
gh pr create --base dev --title "Prop-firm risk: daily + total drawdown limits (configurable basis) + backtest daily metrics" --body "Implements docs/superpowers/specs/2026-06-09-prop-firm-risk-design.md. Closes #348 (gaps 1-4; gap #5 — live broker-equity sizing — deferred to its own issue)."
```

- [ ] **Step 5:** Watch CI green; hand back for review/merge.

---

## Notes for the implementer

- **Static total DD math:** absolute equity = `initialBalance + EquityTracker.currentEquity()` (the tracker is PnL-relative, 0-based). So static DD = `max(0, −currentEquity()) / initialBalance`. Don't double-count the initial balance.
- **Don't change `DrawdownTracker.globalDrawdown()`/`strategyDrawdown()`** — `RiskView` displays them as trailing. Static is additive methods only.
- **Daily reference timing:** captured lazily on first query after UTC rollover; RiskEngine evaluates every tick, so it lands ~midnight. Acceptable and matches `DailyPnLTracker`.
- **`FakePnL` test double:** several risk tests need a `PnLProvider` with settable realized/unrealized; create it once at `src/test/kotlin/com/qkt/risk/FakePnL.kt` (Task 2 or 6) and reuse. Confirm the `PnLProvider` interface members first.
- **The backtest `maxDrawdown` metric stays trailing** — `maxDailyDrawdown` is the new, separate stat. Do not alter `MaxDrawdownAccumulator`.
- **`#348` references in commits** are fine; the PR `Closes #348` (dev is the default branch, so it auto-closes on merge — acceptable since gap #5 becomes its own new issue, not a reopen of #348).
