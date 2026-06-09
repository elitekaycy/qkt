# Portfolio account-level drawdown limits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Halt + flatten an entire portfolio book when its combined equity breaches a total or daily drawdown limit (#351).

**Architecture:** A `BookPnLProvider` sums children's `pnlSnapshot`; a `PortfolioRiskAggregator` owns a book-level `RiskState` (initialBalance = portfolio `CAPITAL`) fed by it and runs the existing #348 `MaxDrawdown`/`MaxDailyDrawdown` halt rules; on a trip it flattens then halts every child (latched per deploy). The `PortfolioSupervisor` drives `evaluate()` from a heartbeat thread. No shared `RiskState`; children stay independent.

**Tech Stack:** Kotlin, Gradle, JUnit5 + AssertJ, the `com.qkt.risk` (#348) + `com.qkt.cli.daemon.portfolio` subsystems.

**Spec:** `docs/superpowers/specs/2026-06-09-portfolio-drawdown-design.md`
**Branch:** `feat-portfolio-drawdown` (off dev, already created).
**Conventions:** subject-only conventional commits, scope `risk`/`cli`; `./gradlew ktlintFormat` before each commit; lines ≤120 (ktlint `max-line-length` not auto-fixable). Push, let CI run the full suite.

**Verified facts:**
- `SessionPnl(equity, balance, realized, unrealized)`; `LiveSessionHandle.pnlSnapshot(id)`, `.halt(reason)`, `.flatten()`.
- `PortfolioDeployer.deploy()` builds `childWrappers: List<ChildHandle>` then `PortfolioSupervisor(ast, children, marketSource)` → `start()`. `compiled.children[i].strategyId = "${name}:${alias}"`, in the same order as `childWrappers`.
- `ChildHandle.handle: StrategyHandle` → `.live: LiveSessionHandle`; `ChildHandle.alias`.
- `capitalAllocations(ast)` = per-child `CAPITAL×WEIGHT`; `ast.capital` = book total.
- `RiskState(pnl, strategyPnL, clock, bus, initialBalance, dailyDdBasis)` (#348); `MaxDrawdown(pct, DrawdownBasis, initialBalance)`, `MaxDailyDrawdown(pct)` are `HaltRule`s. `EventBus(clock, MonotonicSequenceGenerator())`.
- Reusable test doubles from #348: `com.qkt.risk.FakePnL`, `com.qkt.risk.TestClock`.

---

### Task 1: `BookPnLProvider`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/portfolio/BookPnLProvider.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/BookPnLProviderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.daemon.portfolio

import com.qkt.app.SessionPnl
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookPnLProviderTest {
    private fun pnl(realized: String, unrealized: String) =
        SessionPnl(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal(realized), BigDecimal(unrealized))

    @Test
    fun `sums realized and unrealized across children`() {
        val book = BookPnLProvider(listOf({ pnl("100", "10") }, { pnl("-30", "5") }))
        assertThat(book.realizedTotal()).isEqualByComparingTo(BigDecimal("70"))
        assertThat(book.unrealizedTotal()).isEqualByComparingTo(BigDecimal("15"))
        assertThat(book.totalPnL()).isEqualByComparingTo(BigDecimal("85"))
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.qkt.cli.daemon.portfolio.BookPnLProviderTest" --console=plain` → FAIL.

- [ ] **Step 3: Create `BookPnLProvider`**

```kotlin
package com.qkt.cli.daemon.portfolio

import com.qkt.app.SessionPnl
import com.qkt.common.Money
import com.qkt.pnl.PnLProvider
import java.math.BigDecimal

/** A [PnLProvider] over a portfolio book: realized/unrealized summed across the children's live snapshots. */
class BookPnLProvider(
    private val children: List<() -> SessionPnl>,
) : PnLProvider {
    override fun realizedTotal(): BigDecimal = children.fold(Money.ZERO) { a, c -> a.add(c().realized) }

    override fun unrealizedTotal(): BigDecimal = children.fold(Money.ZERO) { a, c -> a.add(c().unrealized) }

    override fun unrealizedFor(symbol: String): BigDecimal = Money.ZERO

    override fun totalPnL(): BigDecimal = realizedTotal().add(unrealizedTotal())
}
```

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/BookPnLProvider.kt src/test/kotlin/com/qkt/cli/daemon/portfolio/BookPnLProviderTest.kt
git commit -m "feat(cli): add BookPnLProvider summing children PnL"
```

---

### Task 2: `PortfolioRiskAggregator` (+ `ChildRiskTarget`)

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioRiskAggregator.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioRiskAggregatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli.daemon.portfolio

import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DailyDrawdownBasis
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.FakePnL
import com.qkt.risk.RiskState
import com.qkt.risk.TestClock
import com.qkt.risk.rules.MaxDrawdown
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRiskAggregatorTest {
    private class FakeChild : ChildRiskTarget {
        var flattened = 0
        var halted: String? = null
        override fun flatten() { flattened++ }
        override fun halt(reason: String) { halted = reason }
    }

    private fun bookRiskState(pnl: FakePnL): RiskState {
        val clock = TestClock(0L)
        return RiskState(
            pnl,
            StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
            clock,
            EventBus(clock, MonotonicSequenceGenerator()),
            BigDecimal("100000"),
            DailyDrawdownBasis.BALANCE,
        )
    }

    @Test
    fun `flattens and halts every child on a static total breach, once`() {
        val pnl = FakePnL(BigDecimal("-9000"), BigDecimal.ZERO) // 9% of 100000 > 8%
        val rs = bookRiskState(pnl)
        val a = FakeChild()
        val b = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                children = listOf(a, b),
                bookRiskState = rs,
                haltRules = listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
            )

        agg.evaluate()
        agg.evaluate() // latched — second call is a no-op

        assertThat(a.flattened).isEqualTo(1)
        assertThat(b.flattened).isEqualTo(1)
        assertThat(a.halted).contains("drawdown")
        assertThat(b.halted).isNotNull()
    }

    @Test
    fun `does nothing while under the limit`() {
        val rs = bookRiskState(FakePnL(BigDecimal("-1000"), BigDecimal.ZERO)) // 1% < 8%
        val a = FakeChild()
        val agg =
            PortfolioRiskAggregator(
                listOf(a),
                rs,
                listOf(MaxDrawdown(BigDecimal("0.08"), DrawdownBasis.STATIC, BigDecimal("100000"))),
            )
        agg.evaluate()
        assertThat(a.flattened).isEqualTo(0)
        assertThat(a.halted).isNull()
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Create `PortfolioRiskAggregator`**

```kotlin
package com.qkt.cli.daemon.portfolio

import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltRule
import com.qkt.risk.RiskState
import org.slf4j.LoggerFactory

/** A child the book can act on when the account-level limit trips. */
interface ChildRiskTarget {
    fun flatten()

    fun halt(reason: String)
}

/**
 * Account-level drawdown halt for a portfolio book. Refreshes a book [RiskState] (fed by summed
 * child PnL) and runs the #348 halt rules; on the first breach it flattens then halts every child.
 * Latched per deployment — fires once.
 */
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,
    private val bookRiskState: RiskState,
    private val haltRules: List<HaltRule>,
) {
    private val log = LoggerFactory.getLogger(PortfolioRiskAggregator::class.java)

    @Volatile
    private var tripped = false

    fun evaluate() {
        if (tripped) return
        bookRiskState.onTick()
        val breach =
            haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt } ?: return
        tripped = true
        log.warn("portfolio book drawdown breached: {} — flattening and halting all children", breach.reason)
        for (c in children) {
            runCatching { c.flatten() }.onFailure { log.warn("child flatten failed: {}", it.message) }
            runCatching { c.halt(breach.reason) }.onFailure { log.warn("child halt failed: {}", it.message) }
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioRiskAggregator.kt src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioRiskAggregatorTest.kt
git commit -m "feat(cli): add PortfolioRiskAggregator (book-level drawdown halt)"
```

---

### Task 3: Drive `evaluate()` from a supervisor heartbeat

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt`

- [ ] **Step 1: Read** `PortfolioSupervisor` (ctor ~44, `start()` ~57, `stop()` ~76, `tickLoop` ~152). Note `runFlag`, `thread`.

- [ ] **Step 2: Add an optional aggregator + heartbeat.** Ctor gains `private val riskAggregator: PortfolioRiskAggregator? = null` and `private val riskIntervalMs: Long = 1000L`. Add a second thread field `private var riskThread: Thread? = null`.

In `start()` after the existing `marketSource`/thread block, before the method returns, start the heartbeat when an aggregator is present (independent of `marketSource`, so always-run books with no streams still get checked):

```kotlin
        if (riskAggregator != null) {
            riskThread =
                Thread({
                    org.slf4j.MDC.put("strategy", ast.name)
                    try {
                        while (runFlag.get()) {
                            runCatching { riskAggregator.evaluate() }
                                .onFailure { log.warn("portfolio risk eval failed: {}", it.message) }
                            Thread.sleep(riskIntervalMs)
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        org.slf4j.MDC.remove("strategy")
                    }
                }, "qkt-portfolio-risk-${ast.name}").apply { isDaemon = true; start() }
        }
```

In `stop()` after the existing `thread?.interrupt()/join`, add:

```kotlin
        riskThread?.interrupt()
        riskThread?.join(5000)
        riskThread = null
```

> Note: `start()` returns early when `marketSource == null` (no tick loop). Move that early-return to AFTER the heartbeat block, or guard only the tick-loop thread with it, so the heartbeat still starts for stream-less books. Read the current `start()` and place the heartbeat so it always runs when `riskAggregator != null`.

- [ ] **Step 3: Build** — `./gradlew compileKotlin --console=plain` → SUCCESS.

- [ ] **Step 4: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioSupervisor.kt
git commit -m "feat(cli): supervisor heartbeat drives portfolio risk aggregator"
```

---

### Task 4: Build the aggregator in `PortfolioDeployer`

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt`
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerTest.kt` (or extend the E2E test)

- [ ] **Step 1: Read** `PortfolioDeployer` ctor (35–54) and `deploy()` (55–97), and `createChild`'s return tuple `(StrategyHandle, ChildHandle)`.

- [ ] **Step 2: Add DD config to the ctor** (after `maxDailyLoss`):

```kotlin
        private val maxDrawdownPct: java.math.BigDecimal? = null,
        private val maxDailyDrawdownPct: java.math.BigDecimal? = null,
        private val totalDdBasis: com.qkt.risk.DrawdownBasis = com.qkt.risk.DrawdownBasis.STATIC,
        private val dailyDdBasis: com.qkt.risk.DailyDrawdownBasis = com.qkt.risk.DailyDrawdownBasis.BALANCE,
        private val clock: com.qkt.common.Clock = com.qkt.common.SystemClock(),
```

- [ ] **Step 3: Build the aggregator in `deploy()`** — between building `childWrappers` and constructing the supervisor:

```kotlin
            val riskAggregator = buildRiskAggregator(portfolioName, compiled, childWrappers)
            val supervisor =
                PortfolioSupervisor(
                    ast = compiled.ast,
                    children = childWrappers,
                    marketSource = if (symbols.isEmpty()) null else marketSourceProvider(symbols),
                    riskAggregator = riskAggregator,
                )
```

Add the builder (private method) — returns null (logged) when there's no capital or no DD config:

```kotlin
    private fun buildRiskAggregator(
        portfolioName: String,
        compiled: PortfolioCompiled,
        wrappers: List<ChildHandle>,
    ): PortfolioRiskAggregator? {
        val capital = compiled.ast.capital
        if (capital == null || (maxDrawdownPct == null && maxDailyDrawdownPct == null)) {
            return null
        }
        val pnlSources: List<() -> com.qkt.app.SessionPnl> =
            compiled.children.zip(wrappers).map { (child, w) ->
                { w.handle.live.pnlSnapshot(child.strategyId) }
            }
        val targets: List<ChildRiskTarget> =
            wrappers.map { w ->
                object : ChildRiskTarget {
                    override fun flatten() = w.handle.live.flatten()
                    override fun halt(reason: String) = w.handle.live.halt(reason)
                }
            }
        val bookRiskState =
            com.qkt.risk.RiskState(
                BookPnLProvider(pnlSources),
                com.qkt.pnl.StrategyPnL(com.qkt.positions.StrategyPositionTracker(), com.qkt.marketdata.MarketPriceTracker()),
                clock,
                com.qkt.bus.EventBus(clock, com.qkt.common.MonotonicSequenceGenerator()),
                capital,
                dailyDdBasis,
            )
        val haltRules =
            buildList {
                maxDrawdownPct?.let { add(com.qkt.risk.rules.MaxDrawdown(it, totalDdBasis, capital)) }
                maxDailyDrawdownPct?.let { add(com.qkt.risk.rules.MaxDailyDrawdown(it)) }
            }
        return PortfolioRiskAggregator(targets, bookRiskState, haltRules)
    }
```

> Confirm `ChildHandle.handle` and `.handle.live` are accessible from this package (they are — same package). `compiled.children` and `wrappers` are built in the same order in `deploy()`, so `zip` aligns child→wrapper.

- [ ] **Step 4: Write a deployer test** — construct a `PortfolioDeployer` with `maxDrawdownPct` set, deploy a no-CAPITAL portfolio, assert the aggregator path is skipped (no exception); and (where feasible) that a CAPITAL book builds one. Mirror `PortfolioDeployerE2ETest` setup. If full deploy is too heavy, unit-test `buildRiskAggregator` via a small seam, or rely on the Task 6 integration test — note which.

- [ ] **Step 5: Build + commit**

```bash
./gradlew compileKotlin --console=plain
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerTest.kt
git commit -m "feat(cli): build portfolio risk aggregator in PortfolioDeployer"
```

---

### Task 5: Wire DD config into `PortfolioDeployer` at the daemon

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/DaemonCommand.kt`

- [ ] **Step 1: Read** the `PortfolioDeployer(...)` construction in `DaemonCommand` (~line 199) — it currently passes `maxDailyLoss = cfg.maxDailyLoss`, `persistor`, etc.

- [ ] **Step 2: Pass the DD config** (mirror the `RealFactory` wiring from #350):

```kotlin
                    maxDailyLoss = cfg.maxDailyLoss,
                    maxDrawdownPct = cfg.maxDrawdownPct,
                    maxDailyDrawdownPct = cfg.maxDailyDrawdownPct,
                    totalDdBasis = cfg.totalDdBasis,
                    dailyDdBasis = cfg.dailyDdBasis,
                    persistor = statePersistor,
```

- [ ] **Step 3: Build + commit**

```bash
./gradlew compileKotlin --console=plain
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/DaemonCommand.kt
git commit -m "feat(cli): pass drawdown config to PortfolioDeployer"
```

---

### Task 6: Integration test, docs, verify, PR

**Files:**
- Test: `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDrawdownE2ETest.kt`
- Modify: `docs/reference/config-schema.md` (note book-level DD)

- [ ] **Step 1: Integration test** — mirror `PortfolioDeployerE2ETest`: deploy `portfolio_weighted.qkt` (CAPITAL 100000) via a `PortfolioDeployer` with `maxDrawdownPct = 0.08`, drive a child's PnL negative enough that the **book** crosses 8% (e.g. inject fills / use the test market source the E2E test uses), wait one heartbeat interval, and assert **both** children report `isHalted()` and are flat. If driving real fills is impractical in-test, instead unit-test the aggregator wired to two real (paper) `LiveSession`s with seeded `startingBalances` and a `BookPnLProvider` over their snapshots, calling `evaluate()` directly. Choose the lighter path that still proves "book breach → all children halted+flat".

- [ ] **Step 2: Docs** — in `docs/reference/config-schema.md` Risk section, add a sentence: the `risk.*` drawdown keys also apply at the **portfolio book** level (account = sum of children, basis from `CAPITAL`); on breach the book flattens + halts all children.

- [ ] **Step 3: Full local check** — `./gradlew ktlintFormat test --tests "com.qkt.cli.daemon.portfolio.*" --tests "com.qkt.risk.*" --console=plain`. (`LoadDirTest` is a known @TempDir flake — ignore if it's the only failure and passes in isolation.)

- [ ] **Step 4: Push + PR**

```bash
git push -u origin feat-portfolio-drawdown
gh pr create --base dev --title "Portfolio account-level drawdown limits (#351)" --body "Implements docs/superpowers/specs/2026-06-09-portfolio-drawdown-design.md. Closes #351."
```

- [ ] **Step 5:** Watch CI green; hand back for review/merge.

---

## Notes for the implementer

- **Reuse, don't reinvent:** the book limit is the existing `MaxDrawdown`/`MaxDailyDrawdown` rules over a book `RiskState` fed by `BookPnLProvider`. No new drawdown math.
- **Order alignment:** `compiled.children` and `childWrappers` are built in the same loop order in `deploy()`, so `zip` pairs each child's `strategyId` with its `ChildHandle`.
- **Heartbeat, not per-fill:** the aggregator runs ~1s; a sub-second window before the book halt is the accepted trade-off (spec §out-of-scope). Don't try to thread it into every child's fill path.
- **No-CAPITAL / no-config book:** aggregator is null, supervisor heartbeat doesn't start — book runs with only its existing daily-loss halt. Logged.
- **Latch:** `tripped` makes the flatten+halt fire once; a redeploy makes a fresh aggregator. Operator `resume` on a child does not un-trip it.
- **Test doubles:** reuse `com.qkt.risk.FakePnL` / `com.qkt.risk.TestClock` (added in #348).
