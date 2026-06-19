# Portfolio Attribution & Book Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a portfolio backtest report per-strategy P&L/equity/risk in the everyday console output, add the two missing risk-adjusted metrics (Sortino, turnover), and add a thin cross-strategy analytics layer (return correlation, contribution-to-return, contribution-to-drawdown) — all additive and read-only, preserving Backtest=Live parity.

**Architecture:** In a single-process portfolio backtest the existing `global` `PerformanceReport` already *is* the book (it aggregates every strategy on one engine), and `BacktestResult.perStrategy` already holds a full per-strategy `PerformanceReport`. So this is not a new portfolio model — it is (1) stop discarding `perStrategy` at the console print boundary, (2) two new scalar metrics on `PerformanceReport`, and (3) one new online collector (`BookReturnCollector`, mirroring `EquityCurveCollector`/`SharpeAccumulator`) that folds per-strategy returns into running sums so pairwise correlation and risk contribution recover at the end in constant memory. Nothing touches the order/fill/trade path.

**Tech Stack:** Kotlin, JUnit5 + AssertJ (`src/test/kotlin`), Gradle. BigDecimal money math via `com.qkt.common.Money` (`Money.CONTEXT`, `Money.SCALE`, `Money.ROUNDING`, `Money.ZERO`).

## Global Constraints

- **Parity invariant (non-negotiable):** every change is additive reporting/analytics. Do NOT modify `TradingPipeline`, `OrderManager`, broker fill logic, or anything that changes trades/order ids/timestamps. `BacktestLiveParityTest` and `SweepReplayParityTest` must stay green and byte-identical.
- **Branch:** feature branch off `origin/dev` (dev is the GitHub default). PR targets `dev`. Never commit to dev/testing/main directly. Ask elitekaycy before each commit.
- **Commits:** Conventional Commits, subject line only — no body, no footer, no AI attribution. Scope matches `com.qkt.*` package (`feat(backtest): ...`).
- **Money math:** all BigDecimal arithmetic uses `Money.CONTEXT`; final stored/printed values `.setScale(Money.SCALE, Money.ROUNDING)`. Never bare `divide`/`multiply` without a MathContext.
- **New `PerformanceReport` fields are appended at the end with defaults** (matching the existing `commissionPaid`/`dailyPnL`/`maxDailyDrawdown` convention) so existing constructors keep compiling.
- **CI over local build:** push and let CI verify; do not block on a local full `./gradlew build`. Targeted `./gradlew test --tests <Class>` for the TDD loop is fine.

---

## File Structure

**Phase 1 — per-strategy in console + Sortino + turnover**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/Sortino.kt` — `SortinoAccumulator` + free `sortino(curve, annFactor)`, mirroring `Sharpe.kt`.
- Modify: `src/main/kotlin/com/qkt/backtest/EquityMetrics.kt` — fold a `SortinoAccumulator`, expose `sortino(annFactor)`.
- Modify: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt` — append `sortinoRatio: BigDecimal? = null`, `turnover: BigDecimal = Money.ZERO`.
- Modify: `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt` — compute `sortinoR` and `turnover` (from a new `tradedNotional` param), set both fields.
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt` — compute `tradedNotional` (needs `instruments`), pass into both report builders.
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt` — print Sortino + turnover in global; add a per-strategy text block and a `perStrategy` JSON object.
- Modify: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt` — add `sortinoRatio`/`turnover` to `renderReport` (keep `--report` json in sync).
- Test: `src/test/kotlin/com/qkt/backtest/metrics/SortinoTest.kt`, `src/test/kotlin/com/qkt/cli/ReportFormatPerStrategyTest.kt`, extend `src/test/kotlin/com/qkt/backtest/ReportBuilderTest.kt` (if present; else create).

**Phase 2 — cross-strategy (book) analytics**
- Create: `src/main/kotlin/com/qkt/backtest/BookAnalytics.kt` — `BookAnalytics` + `CorrelationPair` data classes.
- Create: `src/main/kotlin/com/qkt/backtest/BookReturnCollector.kt` — online per-strategy/pairwise return sums + book drawdown window; `result()` → `BookAnalytics?`.
- Modify: `src/main/kotlin/com/qkt/backtest/BacktestResult.kt` — append `bookAnalytics: BookAnalytics? = null`.
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt` — construct the collector, call `result()` in `snapshot()`.
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt` — print book analytics (text + JSON) when present.
- Modify: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt` — serialize `bookAnalytics` in `result.json`.
- Test: `src/test/kotlin/com/qkt/backtest/BookReturnCollectorTest.kt`, `src/test/kotlin/com/qkt/backtest/BookAnalyticsIntegrationTest.kt`.

**Deferred (NOT in this plan — folded into the book-risk spec):**
- Time-weighted **gross/net exposure** stats (need per-tick position-notional sampling; gross/net exposure is a book-risk primitive).
- **Live rolled-up portfolio `/status`** (daemon surface across child sessions/ports; separate follow-up).
- Backfilling the `--report` json with the *other* already-computed-but-unserialized fields (dailyPnL, drawdownPeriods, monteCarlo). Tracked but out of scope.

---

## Phase 1

### Task 1: Sortino metric

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/metrics/Sortino.kt`
- Test: `src/test/kotlin/com/qkt/backtest/metrics/SortinoTest.kt`

**Interfaces:**
- Produces: `class SortinoAccumulator { fun accept(equity: BigDecimal); fun value(annualizationFactor: BigDecimal): BigDecimal? }` and `fun sortino(equityCurve: List<BigDecimal>, annualizationFactor: BigDecimal): BigDecimal?`.
- Semantics: like Sharpe but the denominator is the **downside deviation** — `sqrt(Σ min(r,0)² / count)` (target-0 semi-deviation, divided by the count of returns, not just losing ones). Null when fewer than two readings or no downside (deviation 0 → undefined).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.backtest.metrics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SortinoTest {
    private val daily = BigDecimal("252")

    @Test
    fun `flat or rising equity has no downside so sortino is null`() {
        assertThat(sortino(listOf("100", "110", "120").map(::BigDecimal), daily)).isNull()
    }

    @Test
    fun `fewer than two readings is null`() {
        assertThat(sortino(listOf(BigDecimal("100")), daily)).isNull()
    }

    @Test
    fun `a drawdown produces a finite sortino and it differs from sharpe`() {
        val curve = listOf("100", "120", "90", "130").map(::BigDecimal)
        val s = sortino(curve, daily)
        assertThat(s).isNotNull()
        // Only the 120 -> 90 step is downside, so downside dev < full stddev => |sortino| > |sharpe|.
        assertThat(s!!.abs()).isGreaterThan(sharpe(curve, daily)!!.abs())
    }

    @Test
    fun `accumulator matches one-pass helper`() {
        val curve = listOf("100", "120", "90", "130").map(::BigDecimal)
        val acc = SortinoAccumulator()
        curve.forEach(acc::accept)
        assertThat(acc.value(daily)).isEqualTo(sortino(curve, daily))
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./gradlew test --tests 'com.qkt.backtest.metrics.SortinoTest'`
Expected: FAIL — unresolved reference `sortino` / `SortinoAccumulator`.

- [ ] **Step 3: Implement `Sortino.kt`**

```kotlin
package com.qkt.backtest.metrics

import com.qkt.common.Money
import java.math.BigDecimal
import java.math.MathContext

private val EPSILON = BigDecimal("0.00000001")

/**
 * Online Sortino-ratio accumulator: feed equity readings one at a time, read the ratio at any point.
 *
 * Like [SharpeAccumulator] but the risk term counts only *downside* moves — returns below the target
 * (here 0). Holds running sums (count, Σr, Σ of squared negative returns) so memory is constant.
 * The downside deviation is `sqrt(Σ min(r,0)² / count)` — divided by the total number of returns, the
 * standard target semi-deviation, so a smoother downside reads as higher Sortino than Sharpe.
 *
 * e.g. feed 100, 120, 90, 130 → only the 120→90 step is downside → a higher |ratio| than Sharpe.
 */
class SortinoAccumulator {
    private var prev: BigDecimal? = null
    private var count: Int = 0
    private var sumR: BigDecimal = Money.ZERO
    private var sumDownside2: BigDecimal = Money.ZERO

    fun accept(equity: BigDecimal) {
        val p = prev
        if (p != null) {
            val denom = p.abs().max(EPSILON)
            val r = equity.subtract(p).divide(denom, Money.CONTEXT)
            sumR = sumR.add(r)
            if (r.signum() < 0) sumDownside2 = sumDownside2.add(r.multiply(r, Money.CONTEXT))
            count++
        }
        prev = equity
    }

    /** Annualized Sortino, or null when undefined: fewer than two readings, or no downside. */
    fun value(annualizationFactor: BigDecimal): BigDecimal? {
        if (count < 1) return null
        val n = BigDecimal(count)
        val mean = sumR.divide(n, Money.CONTEXT)
        val downsideVar = sumDownside2.divide(n, Money.CONTEXT)
        if (downsideVar.signum() <= 0) return null
        val downsideDev = downsideVar.sqrt(MathContext.DECIMAL64)
        if (downsideDev.signum() == 0) return null
        val annFactor = annualizationFactor.sqrt(MathContext.DECIMAL64)
        return mean
            .divide(downsideDev, Money.CONTEXT)
            .multiply(annFactor, Money.CONTEXT)
            .setScale(Money.SCALE, Money.ROUNDING)
    }
}

fun sortino(
    equityCurve: List<BigDecimal>,
    annualizationFactor: BigDecimal,
): BigDecimal? {
    if (equityCurve.size < 2) return null
    val acc = SortinoAccumulator()
    equityCurve.forEach(acc::accept)
    return acc.value(annualizationFactor)
}
```

(Note: `EPSILON` is duplicated from `Sharpe.kt` — both are file-private. If a reviewer prefers, hoist to a shared `internal val RETURN_EPSILON` in the metrics package in a follow-up; keeping it local here is the minimal change.)

- [ ] **Step 4: Run the test, verify it passes**

Run: `./gradlew test --tests 'com.qkt.backtest.metrics.SortinoTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/metrics/Sortino.kt src/test/kotlin/com/qkt/backtest/metrics/SortinoTest.kt
git commit -m "feat(backtest): add online Sortino ratio metric"
```

---

### Task 2: Wire Sortino + turnover into the report

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/EquityMetrics.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt:302-327` (`snapshot()`)
- Test: `src/test/kotlin/com/qkt/backtest/ReportBuilderTurnoverTest.kt`

**Interfaces:**
- Consumes: `SortinoAccumulator` (Task 1); `EquityMetrics.sortino(annFactor)`.
- Produces: `PerformanceReport.sortinoRatio: BigDecimal?`, `PerformanceReport.turnover: BigDecimal`; `ReportBuilder.buildGlobal/buildPerStrategy` gain `tradedNotional: BigDecimal = Money.ZERO`. Turnover = `tradedNotional / startingEquity` (a multiple of capital, NOT annualized — compare equal-length runs, same caveat as Calmar).

- [ ] **Step 1: Failing test for turnover + sortino on a report**

```kotlin
package com.qkt.backtest

import com.qkt.common.Side
import com.qkt.execution.Trade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ReportBuilderTurnoverTest {
    private fun tr(price: String, qty: String, realized: String) =
        TradeRecord(
            trade = Trade("o1", "BTCUSDT", BigDecimal(price), BigDecimal(qty), Side.BUY, 0L),
            realized = BigDecimal(realized),
            strategyId = "s",
        )

    @Test
    fun `turnover is traded notional over starting equity`() {
        val curve = listOf(EquitySample(0L, BigDecimal("10000")), EquitySample(1L, BigDecimal("10100")))
        // tradedNotional 20000 / startingEquity 10000 = 2.0
        val r =
            ReportBuilder.buildGlobal(
                trades = listOf(tr("100", "1", "100")),
                equityCurve = curve,
                finalRealized = BigDecimal("100"),
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
                tradedNotional = BigDecimal("20000"),
            )
        assertThat(r.turnover).isEqualByComparingTo("2.0")
    }

    @Test
    fun `sortino is populated from the curve when no metrics supplied`() {
        val curve = listOf("100", "120", "90", "130").mapIndexed { i, v -> EquitySample(i.toLong(), BigDecimal(v)) }
        val r =
            ReportBuilder.buildGlobal(
                trades = emptyList(),
                equityCurve = curve,
                finalRealized = BigDecimal("30"),
                finalUnrealized = BigDecimal.ZERO,
                annualizationFactor = BigDecimal("252"),
            )
        assertThat(r.sortinoRatio).isNotNull()
    }
}
```

- [ ] **Step 2: Run, verify it fails** — `./gradlew test --tests 'com.qkt.backtest.ReportBuilderTurnoverTest'` → FAIL (no `turnover`/`tradedNotional`/`sortinoRatio`).

- [ ] **Step 3a: `EquityMetrics.kt` — fold in Sortino**

Add the import and accumulator, mirroring the existing Sharpe wiring:

```kotlin
import com.qkt.backtest.metrics.SortinoAccumulator
```
```kotlin
    private val sharpe = SharpeAccumulator()
    private val sortino = SortinoAccumulator()   // add this line
```
In `accept(...)`, after `sharpe.accept(equity)`:
```kotlin
        sortino.accept(equity)
```
Add the reader next to `sharpe(...)`:
```kotlin
    fun sortino(annualizationFactor: BigDecimal): BigDecimal? = sortino.value(annualizationFactor)
```

- [ ] **Step 3b: `PerformanceReport.kt` — append the two fields**

After `maxDailyDrawdown` (the current last field), append:

```kotlin
    /**
     * Annualized Sortino ratio (downside-deviation analogue of Sharpe). Null when undefined —
     * fewer than two equity samples, or no downside (a curve that never falls).
     */
    val sortinoRatio: BigDecimal? = null,
    /**
     * Gross traded notional over the run as a multiple of starting equity — e.g. 3.2 means the
     * strategy traded 3.2x its capital. NOT annualized; compare across runs of equal length. Zero
     * when there is no capital basis. (Notional uses the instrument contract size.)
     */
    val turnover: BigDecimal = BigDecimal.ZERO,
```

- [ ] **Step 3c: `ReportBuilder.kt` — compute both**

Add import:
```kotlin
import com.qkt.backtest.metrics.sortino
```
Add `tradedNotional` param (default keeps callers compiling) to `buildGlobal`, `buildPerStrategy`, and `build` — e.g. in `build(...)`:
```kotlin
        commissionPaid: BigDecimal,
        tradedNotional: BigDecimal = BigDecimal.ZERO,
    ): PerformanceReport {
```
and thread it through from `buildGlobal`/`buildPerStrategy` (add the same param, pass `tradedNotional = tradedNotional`).
Inside `build`, after `startingEquity` is computed (currently ~line 73):
```kotlin
        val sortinoR = metrics?.sortino(annualizationFactor) ?: sortino(equityCurve.map { it.equity }, annualizationFactor)
        val turnover =
            if (startingEquity.signum() > 0) {
                tradedNotional.divide(startingEquity, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            } else {
                Money.ZERO
            }
```
In the `PerformanceReport(...)` constructor call, set:
```kotlin
            sortinoRatio = sortinoR,
            turnover = turnover,
```

- [ ] **Step 3d: `ReplayEngine.kt` — supply `tradedNotional` (it has `instruments`)**

Add a private helper near `annualizationFactorFor`:
```kotlin
    private fun tradedNotional(trades: List<TradeRecord>): BigDecimal =
        trades.fold(Money.ZERO) { acc, r ->
            val cs = instruments.lookup(r.trade.symbol)?.contractSize ?: BigDecimal.ONE
            acc.add(r.trade.price.multiply(r.trade.quantity.abs(), Money.CONTEXT).multiply(cs, Money.CONTEXT))
        }
```
In `snapshot()`, pass it to both builders:
```kotlin
            ReportBuilder.buildGlobal(
                ...
                commissionPaid = commissionBook.total(),
                tradedNotional = tradedNotional(tradeRecords),
            )
```
```kotlin
                    ReportBuilder.buildPerStrategy(
                        ...
                        commissionPaid = commissionBook.totalFor(id),
                        tradedNotional = tradedNotional(tradeRecords.filter { it.strategyId == id }),
                    )
```
(`Money` is already imported in `ReplayEngine.kt`. If not, add `import com.qkt.common.Money`.)

- [ ] **Step 4: Run, verify it passes** — `./gradlew test --tests 'com.qkt.backtest.ReportBuilderTurnoverTest'` → PASS. Then run the metric + report suites to catch any constructor breakage: `./gradlew test --tests 'com.qkt.backtest.*'`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/EquityMetrics.kt src/main/kotlin/com/qkt/backtest/PerformanceReport.kt src/main/kotlin/com/qkt/backtest/ReportBuilder.kt src/main/kotlin/com/qkt/research/ReplayEngine.kt src/test/kotlin/com/qkt/backtest/ReportBuilderTurnoverTest.kt
git commit -m "feat(backtest): report Sortino ratio and turnover per scope"
```

---

### Task 3: Per-strategy block in console text + JSON

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt` (add sortino/turnover to `renderReport`)
- Test: `src/test/kotlin/com/qkt/cli/ReportFormatPerStrategyTest.kt`

**Interfaces:**
- Consumes: `BacktestResult.perStrategy: Map<String, PerformanceReport>` (already populated); the new `sortinoRatio`/`turnover`.
- Produces: a `Per-strategy` section in text output; a `"perStrategy"` object in `--json`; `sortinoRatio`/`turnover` fields in the `--report` json.

- [ ] **Step 1: Failing test**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal

class ReportFormatPerStrategyTest {
    private fun report(pnl: String, trades: Int) =
        PerformanceReport(
            realizedTotal = BigDecimal(pnl),
            unrealizedTotal = BigDecimal.ZERO,
            totalPnL = BigDecimal(pnl),
            tradeCount = trades,
            winRate = BigDecimal("0.50"),
            maxDrawdown = BigDecimal("0.10"),
            profitFactor = BigDecimal("1.5"),
            avgWin = BigDecimal("10"),
            avgLoss = BigDecimal("-5"),
            largestWin = BigDecimal("20"),
            largestLoss = BigDecimal("-8"),
            maxConsecutiveLosses = 2,
            sharpeRatio = BigDecimal("1.1"),
            calmarRatio = BigDecimal("0.8"),
            equityCurve = emptyList(),
        )

    private fun result() =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global = report("100", 4),
            perStrategy = mapOf("alpha" to report("70", 2), "beta" to report("30", 2)),
            cadence = SampleCadence.TICK,
        )

    private fun render(fmt: ReportFormat): String {
        val baos = ByteArrayOutputStream()
        ReportPrinter.print(result(), fmt, PrintStream(baos), BrokerKind.PAPER)
        return baos.toString()
    }

    @Test
    fun `text output lists each strategy`() {
        val out = render(ReportFormat.Text)
        assertThat(out).contains("Per-strategy")
        assertThat(out).contains("alpha")
        assertThat(out).contains("beta")
    }

    @Test
    fun `json output contains a perStrategy object keyed by id`() {
        val out = render(ReportFormat.Json)
        assertThat(out).contains("\"perStrategy\":{")
        assertThat(out).contains("\"alpha\":{")
        assertThat(out).contains("\"beta\":{")
    }

    @Test
    fun `single-strategy run omits the per-strategy noise in text`() {
        val baos = ByteArrayOutputStream()
        val single =
            result().copy(perStrategy = mapOf("only" to report("100", 4)))
        ReportPrinter.print(single, ReportFormat.Text, PrintStream(baos), BrokerKind.PAPER)
        // A 1-strategy book is just the global line; no per-strategy table.
        assertThat(baos.toString()).doesNotContain("Per-strategy")
    }
}
```

- [ ] **Step 2: Run, verify it fails** — `./gradlew test --tests 'com.qkt.cli.ReportFormatPerStrategyTest'` → FAIL.

- [ ] **Step 3a: `ReportFormat.kt` — text block**

In `printText`, before `printAutocorr(r, out)`, add (only when there is more than one strategy — a single-strategy run already equals `global`):

```kotlin
        printPerStrategy(r, out)
```
Add the helper:
```kotlin
    private fun printPerStrategy(
        r: BacktestResult,
        out: PrintStream,
    ) {
        if (r.perStrategy.size < 2) return
        out.println()
        out.println("Per-strategy")
        for ((id, s) in r.perStrategy.entries.sortedBy { it.key }) {
            out.println(
                "  ${id.padEnd(20)} " +
                    "PnL ${s.totalPnL.toPlainString().padStart(12)}  " +
                    "trades ${s.tradeCount.toString().padStart(5)}  " +
                    "Sharpe ${(s.sharpeRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "Sortino ${(s.sortinoRatio?.toPlainString() ?: "n/a").padStart(7)}  " +
                    "MaxDD ${s.maxDrawdown.toPlainString().padStart(7)}  " +
                    "win ${s.winRate.toPlainString()}",
            )
        }
    }
```

- [ ] **Step 3b: `ReportFormat.kt` — JSON block + global Sortino/turnover**

In `printJson`, after the existing `calmarRatio` append and before `executionModel`, add the two new global fields:
```kotlin
        sb.append("\"sortinoRatio\":").append(g.sortinoRatio?.toPlainString() ?: "null").append(',')
        sb.append("\"turnover\":").append(g.turnover.toPlainString()).append(',')
```
Before the final `sb.append("\"monteCarlo\":")...`, insert the per-strategy object:
```kotlin
        sb.append("\"perStrategy\":{")
        sb.append(
            r.perStrategy.entries
                .sortedBy { it.key }
                .joinToString(",") { (id, s) -> "\"$id\":${strategyJson(s)}" },
        )
        sb.append("},")
```
Add the helper (compact subset — the full per-strategy report lives in the `--report` bundle):
```kotlin
    private fun strategyJson(s: com.qkt.backtest.PerformanceReport): String =
        buildString {
            append("{\"totalPnL\":").append(s.totalPnL.toPlainString())
            append(",\"realized\":").append(s.realizedTotal.toPlainString())
            append(",\"unrealized\":").append(s.unrealizedTotal.toPlainString())
            append(",\"trades\":").append(s.tradeCount)
            append(",\"winRate\":").append(s.winRate.toPlainString())
            append(",\"sharpeRatio\":").append(s.sharpeRatio?.toPlainString() ?: "null")
            append(",\"sortinoRatio\":").append(s.sortinoRatio?.toPlainString() ?: "null")
            append(",\"calmarRatio\":").append(s.calmarRatio?.toPlainString() ?: "null")
            append(",\"maxDrawdown\":").append(s.maxDrawdown.toPlainString())
            append(",\"maxDailyDrawdown\":").append(s.maxDailyDrawdown.toPlainString())
            append(",\"turnover\":").append(s.turnover.toPlainString())
            append(",\"commissionPaid\":").append(s.commissionPaid.toPlainString())
            append("}")
        }
```
Also add `Sortino`/`Turnover` lines to `printText`'s global block (after the Calmar line):
```kotlin
        out.println("Sortino (annual): ${g.sortinoRatio?.toPlainString() ?: "n/a"}")
        out.println("Turnover (x cap): ${g.turnover.toPlainString()}")
```

- [ ] **Step 3c: `BacktestReportWriter.kt` — keep `--report` json in sync**

In `renderReport`, after the `calmarRatio` field line, add:
```kotlin
        field("sortinoRatio", ReportSerializer.jsonNullableBigDecimal(r.sortinoRatio))
        field("turnover", ReportSerializer.jsonBigDecimal(r.turnover))
```

- [ ] **Step 4: Run, verify it passes** — `./gradlew test --tests 'com.qkt.cli.ReportFormatPerStrategyTest'` → PASS. Then `./gradlew test --tests 'com.qkt.cli.*' --tests 'com.qkt.backtest.report.*'`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/cli/ReportFormat.kt src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt src/test/kotlin/com/qkt/cli/ReportFormatPerStrategyTest.kt
git commit -m "feat(cli): surface per-strategy attribution in backtest console output"
```

- [ ] **Step 6: Parity gate before PR**

Run: `./gradlew test --tests 'com.qkt.parity.*' --tests '*SweepReplayParity*' --tests '*BacktestLiveParity*'`
Expected: PASS (these changes are read-only; trades must be byte-identical). This is the Phase 1 PR boundary.

---

## Phase 2 — Book analytics

### Task 4: BookReturnCollector (online correlation + risk + drawdown contribution)

**Files:**
- Create: `src/main/kotlin/com/qkt/backtest/BookAnalytics.kt`
- Create: `src/main/kotlin/com/qkt/backtest/BookReturnCollector.kt`
- Test: `src/test/kotlin/com/qkt/backtest/BookReturnCollectorTest.kt`

**Design decisions (lock these — they make the math exact):**
- Returns are on a **constant capital base** `startingBalance`: strategy i's per-sample return is `(pnl_i_now − pnl_i_prev) / startingBalance`. Then book return = Σ strategy returns exactly, so risk contribution sums to 1.
- `contributionToReturn[i]` = `strategyTotalPnL_i / bookTotalPnL` (uses PnL, which sums exactly; equity does not, because the engine anchors every strategy at the full `startingBalance`).
- `riskContribution[i]` (PCTR) = `cov(r_i, r_book) / var(r_book)` — sums to ~1.
- `returnCorrelation` = pairwise Pearson on the per-sample return series.
- `drawdownContribution[i]` = `(pnl_i_at_trough − pnl_i_at_peak) / (bookPnl_at_trough − bookPnl_at_peak)` over the book's deepest peak→trough window — sums to 1.
- `result()` returns `null` when `< 2` strategies or `startingBalance <= 0` (correlation/contribution are meaningless for a single strategy).

**Interfaces:**
- Consumes: `EventBus`, `PnLProvider`, `StrategyPnL` (same as `EquityCurveCollector`), `SampleCadence`, `strategyIds: List<String>`, `startingBalance: BigDecimal`.
- Produces: `fun result(): BookAnalytics?`.

- [ ] **Step 1: `BookAnalytics.kt`**

```kotlin
package com.qkt.backtest

import java.math.BigDecimal

/** Pearson correlation of two strategies' per-sample return series over the run. */
data class CorrelationPair(
    val a: String,
    val b: String,
    val correlation: BigDecimal,
)

/**
 * Cross-strategy ("book") analytics for a portfolio backtest — the relationships the per-strategy
 * reports cannot show on their own. Null on a single-strategy run.
 *
 * - [contributionToReturn]: each strategy's share of book total PnL (sums to 1; can be negative or >1
 *   when strategies offset).
 * - [returnCorrelation]: pairwise return correlation — the diversification picture. e.g. two trend
 *   strategies at +0.9 are nearly the same bet.
 * - [riskContribution]: percent contribution to book return variance (PCTR); sums to ~1. A strategy
 *   with a small return share but a large risk share is dragging the book's risk budget.
 * - [drawdownContribution]: each strategy's share of the book's worst peak-to-trough drawdown.
 */
data class BookAnalytics(
    val contributionToReturn: Map<String, BigDecimal>,
    val returnCorrelation: List<CorrelationPair>,
    val riskContribution: Map<String, BigDecimal>,
    val drawdownContribution: Map<String, BigDecimal>,
)
```

- [ ] **Step 2: Failing test for the collector**

```kotlin
package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BookReturnCollectorTest {
    // Minimal fakes: feed equity directly by driving StrategyPnL/PnLProvider stand-ins.
    // (Use the real StrategyPnL/PnLCalculator with a hand-built StrategyPositionTracker, or the
    // test doubles already used by EquityCurveCollectorTest — mirror that test's setup.)

    @Test
    fun `single strategy yields null analytics`() {
        // Build a collector with one strategyId; after any number of samples, result() is null.
        // ... (mirror EquityCurveCollectorTest harness, one id) ...
    }

    @Test
    fun `two perfectly correlated strategies report correlation ~1 and equal risk contribution`() {
        // Drive two strategies whose pnl moves identically each sample.
        // Expect returnCorrelation single pair ~1.0 and riskContribution ~0.5 each.
    }

    @Test
    fun `contribution to return matches pnl shares`() {
        // Strategy a ends +70, b ends +30 => contributionToReturn a=0.7, b=0.3.
    }
}
```

> Implementer note: `EquityCurveCollector` is driven purely by bus events and reads `pnl`/`strategyPnL`. Copy the exact harness from `src/test/kotlin/com/qkt/backtest/EquityCurveCollectorTest.kt` (real `StrategyPnL` + `StrategyPositionTracker` + `PnLCalculator`, publish `TickEvent`s, mutate positions via fills). Fill in the three test bodies using that harness before implementing. Do not mock `StrategyPnL` — drive it with real fills (no mocks in metric tests).

- [ ] **Step 3: Run, verify it fails** — `./gradlew test --tests 'com.qkt.backtest.BookReturnCollectorTest'` → FAIL.

- [ ] **Step 4: Implement `BookReturnCollector.kt`**

```kotlin
package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.Money
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal
import java.math.MathContext

class BookReturnCollector(
    cadence: SampleCadence,
    bus: EventBus,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    private val strategyIds: List<String>,
    private val startingBalance: BigDecimal,
) {
    private val n = strategyIds.size
    private var count = 0
    private val prevPnl = HashMap<String, BigDecimal>()
    private val sumR = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumR2 = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumRiRbook = HashMap<String, BigDecimal>().apply { strategyIds.forEach { put(it, Money.ZERO) } }
    private val sumCross = HashMap<Pair<String, String>, BigDecimal>()
    private var sumRBook = Money.ZERO
    private var sumRBook2 = Money.ZERO

    private var bookPeakPnl: BigDecimal? = null
    private var pnlAtRunningPeak: Map<String, BigDecimal> = emptyMap()
    private var maxDd = Money.ZERO
    private var peakBookPnl = Money.ZERO
    private var troughBookPnl = Money.ZERO
    private var pnlAtPeak: Map<String, BigDecimal> = emptyMap()
    private var pnlAtTrough: Map<String, BigDecimal> = emptyMap()

    init {
        for (i in 0 until n) for (j in i + 1 until n) sumCross[strategyIds[i] to strategyIds[j]] = Money.ZERO
        when (cadence) {
            SampleCadence.CANDLE_CLOSE -> bus.subscribe<CandleEvent> { sample() }
            SampleCadence.TICK -> bus.subscribe<TickEvent> { sample() }
            SampleCadence.FILL -> bus.subscribe<BrokerEvent.OrderFilled> { sample() }
        }
    }

    private fun sample() {
        if (n < 2 || startingBalance.signum() <= 0) return
        val cur = strategyIds.associateWith { strategyPnL.totalFor(it) }
        val bookPnl = pnl.realizedTotal().add(pnl.unrealizedTotal())

        if (prevPnl.isNotEmpty()) {
            val r = HashMap<String, BigDecimal>(n)
            var rBook = Money.ZERO
            for (id in strategyIds) {
                val ri = cur.getValue(id).subtract(prevPnl.getValue(id)).divide(startingBalance, Money.CONTEXT)
                r[id] = ri
                rBook = rBook.add(ri)
                sumR[id] = sumR.getValue(id).add(ri)
                sumR2[id] = sumR2.getValue(id).add(ri.multiply(ri, Money.CONTEXT))
            }
            sumRBook = sumRBook.add(rBook)
            sumRBook2 = sumRBook2.add(rBook.multiply(rBook, Money.CONTEXT))
            for (id in strategyIds) sumRiRbook[id] = sumRiRbook.getValue(id).add(r.getValue(id).multiply(rBook, Money.CONTEXT))
            for (i in 0 until n) for (j in i + 1 until n) {
                val key = strategyIds[i] to strategyIds[j]
                sumCross[key] = sumCross.getValue(key).add(r.getValue(strategyIds[i]).multiply(r.getValue(strategyIds[j]), Money.CONTEXT))
            }
            count++
        }

        val peak = bookPeakPnl
        if (peak == null || bookPnl > peak) {
            bookPeakPnl = bookPnl
            pnlAtRunningPeak = cur
        } else if (peak.subtract(bookPnl) > maxDd) {
            maxDd = peak.subtract(bookPnl)
            peakBookPnl = peak
            troughBookPnl = bookPnl
            pnlAtPeak = pnlAtRunningPeak
            pnlAtTrough = cur
        }
        for (id in strategyIds) prevPnl[id] = cur.getValue(id)
    }

    fun result(): BookAnalytics? {
        if (n < 2 || count < 2) return null
        val cnt = BigDecimal(count)
        val meanBook = sumRBook.divide(cnt, Money.CONTEXT)
        val varBook = sumRBook2.subtract(meanBook.multiply(sumRBook, Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)

        val mean = strategyIds.associateWith { sumR.getValue(it).divide(cnt, Money.CONTEXT) }
        val varI =
            strategyIds.associateWith {
                sumR2.getValue(it).subtract(mean.getValue(it).multiply(sumR.getValue(it), Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
            }

        val correlation =
            buildList {
                for (i in 0 until n) for (j in i + 1 until n) {
                    val a = strategyIds[i]
                    val b = strategyIds[j]
                    val cov = sumCross.getValue(a to b).subtract(mean.getValue(a).multiply(sumR.getValue(b), Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
                    val denom = sd(varI.getValue(a)).multiply(sd(varI.getValue(b)), Money.CONTEXT)
                    val corr = if (denom.signum() == 0) Money.ZERO else cov.divide(denom, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
                    add(CorrelationPair(a, b, corr))
                }
            }

        val riskContribution =
            strategyIds.associateWith { id ->
                val cov = sumRiRbook.getValue(id).subtract(mean.getValue(id).multiply(sumRBook, Money.CONTEXT)).divide(BigDecimal(count - 1), Money.CONTEXT)
                if (varBook.signum() == 0) Money.ZERO else cov.divide(varBook, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            }

        val bookTotal = pnl.realizedTotal().add(pnl.unrealizedTotal())
        val contributionToReturn =
            strategyIds.associateWith { id ->
                if (bookTotal.signum() == 0) Money.ZERO else strategyPnL.totalFor(id).divide(bookTotal, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            }

        val bookDdMove = troughBookPnl.subtract(peakBookPnl)
        val drawdownContribution =
            strategyIds.associateWith { id ->
                if (bookDdMove.signum() == 0) {
                    Money.ZERO
                } else {
                    val move = (pnlAtTrough[id] ?: Money.ZERO).subtract(pnlAtPeak[id] ?: Money.ZERO)
                    move.divide(bookDdMove, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
                }
            }

        return BookAnalytics(contributionToReturn, correlation, riskContribution, drawdownContribution)
    }

    private fun sd(variance: BigDecimal): BigDecimal =
        if (variance.signum() <= 0) Money.ZERO else variance.sqrt(MathContext.DECIMAL64)
}
```

- [ ] **Step 5: Run, verify it passes** — `./gradlew test --tests 'com.qkt.backtest.BookReturnCollectorTest'` → PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/BookAnalytics.kt src/main/kotlin/com/qkt/backtest/BookReturnCollector.kt src/test/kotlin/com/qkt/backtest/BookReturnCollectorTest.kt
git commit -m "feat(backtest): add online cross-strategy book analytics collector"
```

---

### Task 5: Wire book analytics into the result + console

**Files:**
- Modify: `src/main/kotlin/com/qkt/backtest/BacktestResult.kt` (append `bookAnalytics`)
- Modify: `src/main/kotlin/com/qkt/research/ReplayEngine.kt` (construct collector in `init`, call in `snapshot()`)
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt` (print it)
- Modify: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt` (`result.json`)
- Test: `src/test/kotlin/com/qkt/backtest/BookAnalyticsIntegrationTest.kt`

**Interfaces:**
- Consumes: `BookReturnCollector.result()`.
- Produces: `BacktestResult.bookAnalytics: BookAnalytics?`.

- [ ] **Step 1: Failing integration test** — run a 2-strategy portfolio backtest end-to-end (mirror an existing `StackBacktestTest`/portfolio backtest harness), assert `result.bookAnalytics != null`, its maps key both strategy ids, and `contributionToReturn` sums to ~1.

```kotlin
package com.qkt.backtest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BookAnalyticsIntegrationTest {
    @Test
    fun `two-strategy backtest produces book analytics keyed by strategy`() {
        // Build a ReplayEngine / Backtest with two compiled strategies over a small tick set
        // (copy the construction from an existing multi-strategy backtest test).
        // val result = engine.run().snapshot()
        // assertThat(result.bookAnalytics).isNotNull
        // val ba = result.bookAnalytics!!
        // assertThat(ba.contributionToReturn.keys).containsExactlyInAnyOrder("a", "b")
        // val sum = ba.contributionToReturn.values.fold(BigDecimal.ZERO) { s, v -> s + v }
        // assertThat(sum).isCloseTo(BigDecimal.ONE, within(BigDecimal("0.01")))
    }

    @Test
    fun `single-strategy backtest has null book analytics`() {
        // assertThat(singleResult.bookAnalytics).isNull()
    }
}
```

- [ ] **Step 2: Run, verify it fails.**

- [ ] **Step 3a: `BacktestResult.kt`** — append:
```kotlin
    /**
     * Cross-strategy analytics (return correlation, contribution to return / risk / drawdown). Null
     * on single-strategy runs, where the per-strategy report already says everything.
     */
    val bookAnalytics: BookAnalytics? = null,
```

- [ ] **Step 3b: `ReplayEngine.kt`** — add a field and construct it next to `collector` (init ~line 192), then call in `snapshot()`:
```kotlin
    private val bookAnalytics: BookReturnCollector
```
```kotlin
        bookAnalytics =
            BookReturnCollector(
                cadence = this.cadence,
                bus = bus,
                pnl = pnl,
                strategyPnL = strategyPnL,
                strategyIds = strategies.map { it.first },
                startingBalance = startingBalance,
            )
```
In `snapshot()`'s `BacktestResult(...)`:
```kotlin
            conditionalAutocorr = autocorr.snapshot(),
            bookAnalytics = bookAnalytics.result(),
```

- [ ] **Step 3c: `ReportFormat.kt`** — text + JSON. In `printText`, after `printPerStrategy`, add `printBookAnalytics(r, out)`:
```kotlin
    private fun printBookAnalytics(
        r: BacktestResult,
        out: PrintStream,
    ) {
        val ba = r.bookAnalytics ?: return
        out.println()
        out.println("Book analytics")
        out.println("  contribution to return:")
        for ((id, v) in ba.contributionToReturn.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        out.println("  risk contribution (PCTR):")
        for ((id, v) in ba.riskContribution.entries.sortedBy { it.key }) {
            out.println("    ${id.padEnd(20)} ${v.toPlainString()}")
        }
        if (ba.returnCorrelation.isNotEmpty()) {
            out.println("  return correlation:")
            for (p in ba.returnCorrelation) {
                out.println("    ${p.a} ~ ${p.b}: ${p.correlation.toPlainString()}")
            }
        }
    }
```
In `printJson`, after the `perStrategy` object, add a `bookAnalytics` field (or `null`):
```kotlin
        sb.append("\"bookAnalytics\":").append(bookAnalyticsJson(r.bookAnalytics)).append(',')
```
(place this before `monteCarlo`), and add:
```kotlin
    private fun bookAnalyticsJson(ba: com.qkt.backtest.BookAnalytics?): String {
        if (ba == null) return "null"
        fun mapJson(m: Map<String, java.math.BigDecimal>) =
            "{" + m.entries.sortedBy { it.key }.joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" } + "}"
        val corr = "[" + ba.returnCorrelation.joinToString(",") { "{\"a\":\"${it.a}\",\"b\":\"${it.b}\",\"correlation\":${it.correlation.toPlainString()}}" } + "]"
        return "{\"contributionToReturn\":${mapJson(ba.contributionToReturn)}," +
            "\"riskContribution\":${mapJson(ba.riskContribution)}," +
            "\"drawdownContribution\":${mapJson(ba.drawdownContribution)}," +
            "\"returnCorrelation\":$corr}"
    }
```

- [ ] **Step 3d: `BacktestReportWriter.kt`** — in `renderJson`, before the closing `}`, append a `bookAnalytics` field reusing the same shape (factor a private helper or inline; mirror `bookAnalyticsJson`). Keep it `null` when absent.

- [ ] **Step 4: Run, verify it passes** — `./gradlew test --tests 'com.qkt.backtest.BookAnalyticsIntegrationTest' --tests 'com.qkt.cli.ReportFormatPerStrategyTest'`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/backtest/BacktestResult.kt src/main/kotlin/com/qkt/research/ReplayEngine.kt src/main/kotlin/com/qkt/cli/ReportFormat.kt src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt src/test/kotlin/com/qkt/backtest/BookAnalyticsIntegrationTest.kt
git commit -m "feat(backtest): report cross-strategy book analytics"
```

- [ ] **Step 6: Parity + full backtest gate before PR**

Run: `./gradlew test --tests 'com.qkt.parity.*' --tests '*SweepReplayParity*' --tests 'com.qkt.backtest.*'`
Expected: PASS, trades byte-identical. Phase 2 PR boundary.

---

## Docs (fold into whichever PR ships the user-facing change)

- [ ] Update `docs/concepts/backtest-model.md` (or `docs/reference/cli-commands.md`) with the new console output: per-strategy block, Sortino, turnover, and the book-analytics section + the metric definitions (PCTR sums to ~1; turnover is a capital multiple, not annualized; correlation on return series). One worked example of a 2-strategy `--json` snippet.

---

## Self-Review

**Spec coverage (against the agreed items 1–3):**
- Item 1 (surface per-strategy): Task 3 (console text + JSON). `--report` bundle already had it; gap was console — covered. Live rolled-up `/status` explicitly deferred (stated).
- Item 2 (book analytics: correlation + contribution-to-return + contribution-to-DD): Tasks 4–5. Added PCTR (risk contribution) as the rigorous companion — covered.
- Item 3 (Sortino + turnover + exposure): Sortino (Task 1–2), turnover (Task 2). Exposure explicitly deferred to the book-risk spec with rationale (it needs per-tick position sampling and is a risk primitive) — stated, not silently dropped.

**Placeholder scan:** the only non-literal test bodies are the `BookReturnCollectorTest` / `BookAnalyticsIntegrationTest` harnesses, which point at the exact existing tests to mirror (`EquityCurveCollectorTest`, a multi-strategy backtest test) — the implementer must read those before writing, because the fake-vs-real wiring must match the codebase's no-mocks rule. Flagged inline.

**Type consistency:** `tradedNotional` param name is identical in `buildGlobal`/`buildPerStrategy`/`build`. `sortinoRatio`/`turnover` field names identical across `PerformanceReport`, `ReportBuilder`, `ReportFormat`, `BacktestReportWriter`. `BookAnalytics` field names identical across collector `result()`, `ReportFormat`, `BacktestReportWriter`. `BookReturnCollector` constructor mirrors `EquityCurveCollector` exactly.

**Parity:** every task ends behind a parity gate (Tasks 3 & 5, Step 6). No task touches the trade path.
