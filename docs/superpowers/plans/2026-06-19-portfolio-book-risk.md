# Portfolio Book-Risk Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. Spec: `docs/superpowers/specs/2026-06-19-portfolio-book-risk-design.md`.

**Goal:** Make a qkt portfolio a book-as-risk-object — cross-strategy measurement, book exposure limits, graduated drawdown de-risking, and dynamic capital allocation (vol-targeting + ERC) — modeled identically in backtest and live.

**Architecture:** One deterministic `BookRiskController` fed aggregated state via a `BookStateSource` (backtest = one engine; live = summed sessions), consulted at two already-shared points: the `RiskEngine` (a `BookExposureLimit` rule) and the shared `TradingPipeline` (a per-strategy sizing scale = deRisk × allocationWeight). Everything is config-gated; a null controller = today's exact behavior.

**Tech Stack:** Kotlin, JUnit5 + AssertJ, Gradle. `com.qkt.common.Money` for BigDecimal math.

## Global Constraints

- **Parity (non-negotiable):** book-risk decisions are computed by one component fed identical inputs in both modes. The sizing scale lives in the shared `TradingPipeline`; the limit is a shared `RiskRule`. `BacktestLiveParityTest` + `SweepReplayParityTest` stay byte-identical with the layer OFF; a new `BookRiskParityTest` proves byte-identical with it ON.
- **Default-off:** absent `book_risk` config ⇒ null controller ⇒ no behavior change. Verify every phase.
- **Branch/PR:** `feat-portfolio-book-risk` (stacked on the Phase-A branch / PR #499), PRs target `dev`. Ask before commit (blanket approval already given this session). Conventional Commits, subject-only.
- **Money math:** `Money.CONTEXT` for arithmetic, `.setScale(Money.SCALE, Money.ROUNDING)` for stored values.
- **ktlint:** run `ktlintCheck`/`ktlintFormat` before each commit; 120-char limit; imports lexicographic (com → java → org); nested local funs need a blank line before.
- **CI over local build:** targeted `./gradlew test --tests <Class>` for the TDD loop; full parity gate at each phase boundary; push and let CI run the whole suite.

---

## File Structure

New package `com.qkt.risk.book`:
- `BookSnapshot.kt` — immutable per-sample aggregated book state.
- `BookStateSource.kt` — interface `sample(timestampMs): BookSnapshot`.
- `EngineBookStateSource.kt` — backtest impl (global PnL + per-strategy positions + prices + instruments).
- `BookExposure.kt` — pure exposure math (gross/net/per-symbol) over position legs.
- `BookRiskMonitor.kt` — subscribes to cadence, accumulates the measurement time series + reuses `BookReturnCollector` for covariance → `BookRiskReport`.
- `BookRiskReport.kt` — decimated series + event log (the reported dataset).
- `BookRiskConfig.kt` — typed config (limits, de-risk ladder, allocation). [S1+]
- `BookRiskController.kt` — consumes `BookSnapshot` + config → `BookRiskState`. [S1+]
- `BookRiskState.kt` — immutable decision snapshot read by gate + pipeline. [S1+]
- `rules/BookExposureLimit.kt` (in `com.qkt.risk.rules`) — book limit `RiskRule`. [S1]
- `Erc.kt` — ERC / inverse-vol / vol-target solvers (pure). [S3]

Modified:
- `research/ReplayEngine.kt` — store `strategyPositions` as a field; construct `EngineBookStateSource` + `BookRiskMonitor` (F) and `BookRiskController` (S1+); thread controller into `TradingPipeline` + `RiskEngine`.
- `backtest/BacktestResult.kt` — `bookRisk: BookRiskReport? = null`.
- `cli/ReportFormat.kt`, `backtest/report/BacktestReportWriter.kt` — serialize `bookRisk`.
- `app/TradingPipeline.kt` — apply `controller.state.scaleFor(strategyId)` to sized qty (risk-increasing only). [S2/S3]
- `cli/Config.kt` — `bookRisk: BookRiskConfig?`. [S1]
- `cli/daemon/portfolio/PortfolioDeployer.kt` + `PortfolioRiskAggregator.kt` — share the controller; refactor binary halt onto the ladder bottom rung. [S2]
- `cli/BacktestContext.kt` / `backtest/Backtest.kt` — run a `PortfolioFile` as N attributed strategies. [§8]

---

## Phase F — Measurement foundation

### Task F1: Exposure math (pure)

**Files:** Create `src/main/kotlin/com/qkt/risk/book/BookExposure.kt`; Test `src/test/kotlin/com/qkt/risk/book/BookExposureTest.kt`

**Interfaces — Produces:**
- `data class Leg(val strategyId: String, val symbol: String, val signedQty: BigDecimal, val price: BigDecimal, val contractSize: BigDecimal)`
- `data class Exposure(val gross: BigDecimal, val net: BigDecimal, val perSymbolNet: Map<String, BigDecimal>)`
- `fun bookExposure(legs: List<Leg>): Exposure` — `gross = Σ |signedQty·price·cs|` (every leg, no netting); `perSymbolNet[sym] = Σ signedQty·price·cs` over that symbol; `net = Σ_sym |perSymbolNet[sym]|` (nets opposing legs within a symbol, not across).

- [ ] **Step 1: Failing test**

```kotlin
package com.qkt.risk.book

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BookExposureTest {
    private fun leg(s: String, sym: String, q: String, px: String, cs: String = "1") =
        Leg(s, sym, BigDecimal(q), BigDecimal(px), BigDecimal(cs))

    @Test
    fun `gross counts every leg, net cancels opposing same-symbol legs`() {
        val e = bookExposure(listOf(leg("a", "X", "1", "100"), leg("b", "X", "-1", "100")))
        assertThat(e.gross).isEqualByComparingTo("200")
        assertThat(e.net).isEqualByComparingTo("0")
        assertThat(e.perSymbolNet.getValue("X")).isEqualByComparingTo("0")
    }

    @Test
    fun `net sums absolute per-symbol nets across symbols`() {
        val e = bookExposure(listOf(leg("a", "X", "1", "100"), leg("a", "Y", "-2", "50")))
        assertThat(e.gross).isEqualByComparingTo("200") // 100 + 100
        assertThat(e.net).isEqualByComparingTo("200")   // |100| + |-100|
    }

    @Test
    fun `contract size scales notional`() {
        val e = bookExposure(listOf(leg("a", "XAU", "1", "2000", "100")))
        assertThat(e.gross).isEqualByComparingTo("200000")
    }
}
```

- [ ] **Step 2: Run, verify fail** — `./gradlew test --tests 'com.qkt.risk.book.BookExposureTest'` → FAIL (unresolved).
- [ ] **Step 3: Implement `BookExposure.kt`**

```kotlin
package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal

data class Leg(
    val strategyId: String,
    val symbol: String,
    val signedQty: BigDecimal,
    val price: BigDecimal,
    val contractSize: BigDecimal,
)

data class Exposure(
    val gross: BigDecimal,
    val net: BigDecimal,
    val perSymbolNet: Map<String, BigDecimal>,
)

/** Gross = Σ|notional| over every leg; per-symbol net = Σ signed notional; net = Σ|per-symbol net|. */
fun bookExposure(legs: List<Leg>): Exposure {
    var gross = Money.ZERO
    val perSymbol = HashMap<String, BigDecimal>()
    for (l in legs) {
        val notional = l.signedQty.multiply(l.price, Money.CONTEXT).multiply(l.contractSize, Money.CONTEXT)
        gross = gross.add(notional.abs())
        perSymbol[l.symbol] = (perSymbol[l.symbol] ?: Money.ZERO).add(notional)
    }
    val net = perSymbol.values.fold(Money.ZERO) { acc, v -> acc.add(v.abs()) }
    return Exposure(
        gross = gross.setScale(Money.SCALE, Money.ROUNDING),
        net = net.setScale(Money.SCALE, Money.ROUNDING),
        perSymbolNet = perSymbol.mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) },
    )
}
```

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `feat(risk): add book exposure math`

### Task F2: BookSnapshot + BookStateSource + EngineBookStateSource

**Files:** Create `BookSnapshot.kt`, `BookStateSource.kt`, `EngineBookStateSource.kt`; Test `EngineBookStateSourceTest.kt`

**Interfaces:**
- Consumes: `bookExposure`, `Leg`, `Exposure` (F1); `PnLProvider`, `StrategyPositionTracker`, `MarketPriceTracker`, `InstrumentRegistry`.
- Produces:
  - `data class BookSnapshot(timestampMs: Long, bookEquity: BigDecimal, exposure: Exposure, perStrategyPnl: Map<String,BigDecimal>)`
  - `interface BookStateSource { fun sample(timestampMs: Long): BookSnapshot }`
  - `class EngineBookStateSource(strategyIds, pnl, strategyPnL, strategyPositions, prices, instruments, startingBalance): BookStateSource` — builds legs from each strategy's open positions (`strategyPositions`), prices via `prices.lastPrice`, contract size via `instruments.lookup`, `bookEquity = startingBalance + pnl.realizedTotal + pnl.unrealizedTotal`, `perStrategyPnl[id] = strategyPnL.totalFor(id)`.

**Implementation note:** verify `StrategyPositionTracker`'s read API for enumerating (strategyId, symbol, qty) legs; if it lacks a public enumerator, add a read-only `legs(): List<...>`/`positionsFor(strategyId)` accessor (read-only, no behavior change). Test drives it with the `EquityCurveCollectorTest`/`BookReturnCollectorTest` rig (real `StrategyPnL`/`StrategyPositionTracker`, apply fills, assert gross/net/equity).

- [ ] Steps: failing test (2-strategy fills on one symbol → gross/net as expected) → implement → pass → `feat(risk): book state source from engine`.

### Task F3: BookRiskMonitor + BookRiskReport + report wiring

**Files:** Create `BookRiskMonitor.kt`, `BookRiskReport.kt`; Modify `ReplayEngine.kt`, `BacktestResult.kt`, `ReportFormat.kt`, `BacktestReportWriter.kt`; Test `BookRiskMonitorTest.kt` + extend `BookAnalyticsIntegrationTest`.

**Interfaces:**
- `data class BookRiskReport(series: List<BookRiskSample>, events: List<BookRiskEvent>)`, `data class BookRiskSample(timestampMs, grossExposure, netExposure, bookVol, deRiskFactor, bookEquity)`, `sealed BookRiskEvent { LimitBreach, DeRiskChange, Rebalance }` (events empty in F; populated S1+).
- `class BookRiskMonitor(cadence, bus, source: BookStateSource, curveCap)` — subscribes like `EquityCurveCollector`; on each sample folds book return (Δ bookEquity / startingBalance) into an online variance (annualized → bookVol) and decimates the exposure/equity series; `result(): BookRiskReport`. Null when `< 2` strategies or no capital basis (consistent with `BookReturnCollector`).
- `BacktestResult.bookRisk: BookRiskReport? = null`; `ReplayEngine.snapshot()` sets it; serialize in `--json` (`bookRisk`) and `--report` (`book_risk.csv`).

- [ ] Steps: failing integration test (2-strategy backtest → `result.bookRisk != null`, series non-empty, gross ≥ net) → implement monitor + wiring → pass → ktlint → **parity gate** (`SweepReplayParity`, `BacktestLiveParity`, layer measurement-only is read-only) → `feat(backtest): book-risk measurement report`.

---

## Phase S1 — Book exposure limits  (detail on arrival)

- **S1a `BookRiskConfig` + `Config` parse:** `book_risk:` YAML → `BookRiskConfig(limits: Limits?, deRisk: DeRisk?, allocation: Allocation?)`; `Limits(maxGross, maxNet, maxSymbolConcentration: BigDecimal?)` as ×capital. Test: parse a fixture config.
- **S1b `BookRiskController` + `BookRiskState`:** controller holds config + latest `BookSnapshot`; `state(): BookRiskState` exposing `wouldBreach(symbol, side, addedNotional): Boolean` using current exposure + caps×capital. (deRiskFactor=1, allocationWeight=1 until S2/S3.)
- **S1c `BookExposureLimit : RiskRule`:** reject risk-increasing orders that breach; `isRiskReducing` always passes; emit `LimitBreach` event. Wire into `RiskEngine` rule list in `ReplayEngine` (+ live in S2/§8). Config-gated.
- **Parity gate + default-off check.** Commit per task. PR after S1.

## Phase S2 — Graduated de-risking  (detail on arrival)

- **S2a ladder:** `DeRisk(ladder: List<Rung(drawdown, factor, cooldownBars?)>)`; `BookRiskController` maps current book DD → `deRiskFactor` with hysteresis (relax only after recovering past the rung below) + cooldown on the `factor 0` rung. Pure ladder unit-tested (rung selection, hysteresis, cooldown).
- **S2b pipeline scale:** `TradingPipeline` multiplies risk-increasing sized qty by `controller.state.scaleFor(strategyId)` (deRisk × allocWeight). Shared path ⇒ both modes. Default-off when controller null. Parity gate (ON-case via `BookRiskParityTest`).
- **S2c live unify:** refactor `PortfolioRiskAggregator` to drive flatten/halt from the controller's bottom rung (no parallel logic).

## Phase S3 — Dynamic allocation  (detail on arrival)

- **S3a solvers (`Erc.kt`, pure):** `inverseVol(vars): weights`; `erc(cov): weights` (cyclical coordinate descent / fixed-point, bounded iters, deterministic; fallback inverse-vol on degenerate cov); `volTarget(weights, cov, annualization, targetVol, maxLeverage): scaledWeights`. Unit-test ERC on a known 2×2 covariance → equal risk contributions; vol-target hits target within tolerance.
- **S3b controller allocation:** on `rebalanceEveryBars` boundary, recompute weights from `BookReturnCollector` covariance (lookback) per `method`; constant between rebalances; expose via `allocationWeight(strategyId)`; emit `Rebalance` events. `FIXED` = today's static weights.
- **S3c reporting:** allocation weights + rebalances in `BookRiskReport`. Parity gate.

## Phase §8 — Portfolio backtest unification  (detail on arrival)

- `Backtest`/`BacktestContext` load a `PortfolioFile`: compile each child to `("<portfolio>:<alias>", Strategy)`, seed per-child starting balance from `CAPITAL×WEIGHT`, preserve `WHEN…RUN` gate behavior (wrap each child to match the live `PortfolioStrategy` gate), construct the controller from `book_risk`. Integration test: `qkt backtest <portfolio>` produces per-child attribution + book-risk report. (Needed for the SSH end-to-end validation; explicit multi-strategy backtests cover earlier phases meanwhile.)

---

## Self-Review

- **Spec coverage:** F→§3 measurement (F1–F3, + exposure closes Phase-A gap); S1→§4 limits; S2→§5 de-risk; S3→§6 allocation; §8→§8 unification; reporting→§3 "data we need" (F3 + S1c/S3c events); parity→§7 (`BookRiskParityTest` in S2b); config→§9 (S1a). All spec sections mapped.
- **Placeholder scan:** Phase F is full TDD with real code. S1–S3/§8 are task lists with concrete interfaces (types/signatures named), detailed-on-arrival per executing-plans — not vague "handle it" steps. Each names its files, deliverable, and parity gate.
- **Type consistency:** `BookSnapshot`, `BookStateSource.sample`, `bookExposure`/`Exposure`/`Leg`, `BookRiskController.state()`, `BookRiskState.scaleFor`/`wouldBreach`, `allocationWeight(strategyId)`, `BookRiskReport`/`BookRiskSample`/`BookRiskEvent` are used consistently across phases.
