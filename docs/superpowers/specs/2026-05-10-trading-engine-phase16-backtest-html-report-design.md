# Phase 16 — Backtest HTML report with DD-days, Monte Carlo, per-trade risk

**Phase:** 16
**Status:** Design
**Author:** elitekaycy
**Date:** 2026-05-10

---

## 1. Goal

Make the backtest report defensible enough to put in front of investors. Phase 10 already computes the headline metrics (Sharpe, Calmar, profit factor, max DD, win/loss, equity curve) and emits them as `result.json` + per-strategy CSVs. Phase 16 ships:

1. **Single bundled HTML artifact** — `report.html` with everything in one self-contained file: no external deps, works offline, emailable, browser-printable to PDF.
2. **Drawdown-period table** — every peak-to-trough segment ≥ a threshold, with start, trough, recovery, depth, duration.
3. **Monte Carlo fan chart** — bootstrap-with-replacement on per-trade returns, 1000 sims, 5/25/50/75/95 percentile equity bands and a max-DD distribution. Probability of negative final equity stated explicitly.
4. **Per-trade risk** — `riskUsd` captured at order-submission time, surfaced in the per-trade table.
5. **Equity + drawdown chart** — SVG line chart with the underwater-equity overlay shaded.

Out of scope (deferred to Phase 17 or later):

- Slippage capture (actual vs expected fill). Requires `PaperBroker` to record both the trigger price and the fill price separately.
- Spread capture. Requires bid/ask data in the tick feed.
- Regime breakdown (volatility-bucketed PnL). Requires a regime classifier.
- DSL log lines woven into the report timeline. Future work after Phase 15 logs are aggregated.
- PDF output as a build artifact. Browser-print works for now.

## 2. Why

Phase 10's report is a JSON + CSV bundle. It's machine-readable and complete for tooling, but not a presentation artifact. A trader showing a strategy to an investor today has to manually assemble the equity curve, the trade list, and any drawdown commentary. Phase 16 collapses that into a single document the operator can hand over.

Three concrete pre-live audit gaps the current report doesn't close:

- **Drawdown duration is invisible.** `maxDrawdown` is a single number — depth only. An investor wants to know how many days a strategy spent underwater and how often. A 5% DD over 14 days is very different from a 5% DD over 6 months.
- **No statistical robustness.** A backtest of 200 trades may be lucky. Monte Carlo bootstrapping over the trade sequence quantifies how dependent the final equity is on path order, and gives a P5/P50/P95 cone for "what if the same trade distribution had landed in a different order".
- **Per-trade risk is invisible.** Operators need to confirm the strategy isn't risking 20% on one trade and 0.1% on another. Today the trade row shows quantity and price; risk-in-USD is computed by the risk engine but not emitted.

## 3. Architecture

### 3.1 HTML output

Single file: `<report-dir>/report.html`. Emitted alongside the existing `result.json` and CSV files. No new directory layout.

Top-level structure:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>qkt backtest — &lt;strategyName&gt;</title>
  <style>/* embedded CSS */</style>
</head>
<body>
  <header>...</header>
  <section class="headline">...</section>     <!-- cards: PnL, Sharpe, etc. -->
  <section class="equity">...</section>       <!-- SVG equity + DD chart -->
  <section class="drawdowns">...</section>    <!-- DD-days table -->
  <section class="trade-stats">...</section>  <!-- avg/largest/streak -->
  <section class="trades">...</section>       <!-- per-trade table -->
  <section class="monte-carlo">...</section>  <!-- MC fan + percentiles -->
  <section class="rejections">...</section>   <!-- collapsed -->
  <footer>...</footer>
</body>
</html>
```

Constraints:

- Single file, all CSS inlined in `<style>`, all charts inlined as `<svg>`. No `<script>` (defensive: emails strip JS, some viewers block it).
- File size bound: per-trade table truncated at first 200 + last 200 rows. Full trade list remains in `trades.csv` (already emitted).
- Browser-printable: a top-level `@media print` rule sets page breaks before each section and hides the rejections section by default (operator can toggle in CSS).

### 3.2 Per-trade risk capture

`TradeRecord` (current shape):

```kotlin
data class TradeRecord(
    val trade: Trade,
    val strategyId: String,
    val realized: BigDecimal,
)
```

Add `riskUsd: BigDecimal?`:

```kotlin
data class TradeRecord(
    val trade: Trade,
    val strategyId: String,
    val realized: BigDecimal,
    val riskUsd: BigDecimal?,
)
```

Population:

- When a strategy submits a `Signal.Buy`/`Signal.Sell` paired with a stop-loss (Phase 13a outer bracket, Phase 11d defaults), `OrderManager` already evaluates the bracket and knows both the entry and stop prices.
- Risk = `qty × |entry - stop|`, in the symbol's quote currency.
- For symbols where quote ≠ account currency, no FX conversion in v1 — risk is reported in quote currency. Documented as a known limitation; v2 adds account-currency conversion.
- Unbracketed orders or operator flatten signals → `riskUsd = null`. Rendered as `n/a` in the report; operators see this and know the strategy is not risk-bounded.

Threading the value through:

- `OrderManager` populates `OrderEvent.request` with risk metadata if the bracket is present (a new field `riskUsd: BigDecimal?` on `OrderRequest`'s shared interface, defaulting `null`).
- `TradingPipeline.onFilled` reads `OrderRequest.riskUsd` from the matching open order and emits it on the `TradeRecord`.

### 3.3 Drawdown-period analysis

New module `com.qkt.backtest.metrics.DrawdownAnalyzer`. Input: `List<EquitySample>`. Output: `List<DrawdownPeriod>`.

```kotlin
data class DrawdownPeriod(
    val peakTimestamp: Long,
    val peakEquity: BigDecimal,
    val troughTimestamp: Long,
    val troughEquity: BigDecimal,
    val recoveryTimestamp: Long?,        // null if not recovered by end of run
    val depthPct: BigDecimal,            // negative, e.g. -0.05 for -5%
    val durationMs: Long,                // recovery - peak, or end - peak if not recovered
    val ongoing: Boolean,
)
```

Algorithm (single pass):

1. Track running peak equity.
2. When equity drops below peak, start a candidate drawdown.
3. Track lowest equity seen during the drawdown.
4. When equity returns to ≥ peak, the drawdown closes — emit a `DrawdownPeriod`.
5. At end of run, any open candidate becomes an `ongoing` `DrawdownPeriod` with `recoveryTimestamp = null`.

Filter to `depthPct ≤ threshold`. Default threshold: `BigDecimal("-0.01")` (-1%). A drawdown qualifies when its negative depth is ≤ a negative threshold (deeper). Configurable via `HtmlReportConfig`.

Sort output by depth descending.

### 3.4 Monte Carlo

New module `com.qkt.backtest.metrics.MonteCarlo`. Input: `List<TradeRecord>`, `startingEquity: BigDecimal`, `simulations: Int = 1000`, `seed: Long = 42L`. Output: `MonteCarloSummary`.

```kotlin
data class MonteCarloSummary(
    val simulations: Int,
    val finalEquityP5: BigDecimal,
    val finalEquityP25: BigDecimal,
    val finalEquityP50: BigDecimal,
    val finalEquityP75: BigDecimal,
    val finalEquityP95: BigDecimal,
    val maxDrawdownP5: BigDecimal,        // 5th percentile of {max DD per sim}, near-zero
    val maxDrawdownP95: BigDecimal,       // 95th percentile, deepest
    val probabilityNegativeFinal: BigDecimal,
    val equityFanByTradeIndex: List<EquityFanPoint>,
)

data class EquityFanPoint(
    val tradeIndex: Int,
    val p5: BigDecimal,
    val p25: BigDecimal,
    val p50: BigDecimal,
    val p75: BigDecimal,
    val p95: BigDecimal,
)
```

Procedure:

```
1. tradeReturns = trades.map { it.realized }                       // absolute pnls
2. for sim in 1..simulations:
     equity = startingEquity
     simEquityCurve = [equity]
     simPeak = equity
     simMaxDd = 0
     for i in 0 until tradeReturns.size:
         pick = rng.nextInt(tradeReturns.size)
         pnl = tradeReturns[pick]
         equity += pnl
         simEquityCurve.add(equity)
         if equity > simPeak: simPeak = equity
         dd = (equity - simPeak) / simPeak
         if dd < simMaxDd: simMaxDd = dd
     record sim's final equity, max DD, and equity curve
3. compute percentiles per trade index across all sims
```

Bootstrap with replacement (not block bootstrap, not reorder-without-replacement). Documented choice: simplest model, assumes trade returns are i.i.d. Block bootstrap is a v2 enhancement for when serial correlation matters.

Determinism: fixed seed (default 42, configurable). Same backtest produces the same MC summary across runs.

Performance: 1000 sims × N trades is O(1000 × N). For N=10000, ~10M ops, well under one second. No need for parallelism in v1.

### 3.5 SVG charts

New module `com.qkt.backtest.report.SvgChart` with three helpers:

- `lineChart(points: List<Pair<Long, BigDecimal>>, width: Int, height: Int, title: String): String` — equity curve.
- `lineChartWithUnderwater(equity: List<EquitySample>, drawdowns: List<DrawdownPeriod>, width: Int, height: Int): String` — equity line plus shaded DD regions.
- `fanChart(fan: List<EquityFanPoint>, width: Int, height: Int): String` — Monte Carlo fan: P5–P95 outer band, P25–P75 inner band, P50 line.

Pure SVG strings. No external libraries. Axes drawn manually with `<line>` and `<text>`. Aim for ~800×400 px each, scalable.

### 3.6 PerformanceReport extension

```kotlin
data class PerformanceReport(
    /* existing fields */,
    val drawdownPeriods: List<DrawdownPeriod>,
    val monteCarlo: MonteCarloSummary?,
)
```

`monteCarlo` is nullable: when `tradeCount < 30`, MC isn't statistically meaningful and we emit `null`. The report renders an explanatory note instead of the fan.

### 3.7 BacktestReportWriter integration

`BacktestReportWriter.write(result)` emits in this order:

1. `result.json` (existing — extended with new fields).
2. `equity_global.csv`, `equity_<id>.csv`, `trades.csv` (existing).
3. `trades.csv` gains a `riskUsd` column.
4. `rejections.csv` (existing).
5. `report.html` (new) — references the same data, renders the human view.

The HTML writer takes the same `BacktestResult` plus a `HtmlReportConfig` (max trade rows, DD threshold, MC seed) and produces the file. No I/O outside the configured directory.

### 3.8 Configuration

```kotlin
data class HtmlReportConfig(
    val tradeTableHead: Int = 200,
    val tradeTableTail: Int = 200,
    val drawdownThresholdPct: BigDecimal = BigDecimal("-0.01"),
    val monteCarloSimulations: Int = 1000,
    val monteCarloSeed: Long = 42L,
    val minTradesForMonteCarlo: Int = 30,
)
```

Defaults work for the typical case. Operators tweak via `BacktestReportWriter` construction.

## 4. Migration

**Breaking change to `TradeRecord`.** Existing tests construct `TradeRecord(trade, strategyId, realized)`. Add `riskUsd = null` default to keep compatibility, then update production callers to pass the real value. No DSL or file-format changes; only internal API.

**Breaking change to `PerformanceReport` and `BacktestResult` JSON shape.** Adds `drawdownPeriods` and `monteCarlo` fields. Consumers reading the JSON gain new fields; existing fields unchanged. Forward-compatible for any tool that doesn't strictly validate the schema.

**`trades.csv` gains a column.** `riskUsd` appended after `realized`. Tools parsing the CSV with header-aware readers continue to work; tools using positional column indexing need an update. Documented in the changelog.

**No DSL surface change.**

## 5. Testing

### Unit

- `DrawdownAnalyzerTest`:
  - Empty curve → empty list.
  - Monotone-up curve → empty list.
  - Single peak-trough-recovery cycle → one period with correct depth/duration.
  - Multiple non-overlapping cycles → correct count.
  - Open drawdown at end of run → `ongoing = true`, `recoveryTimestamp = null`.
  - Threshold filtering: shallow DD below threshold dropped.

- `MonteCarloTest`:
  - Fixed seed → deterministic percentiles.
  - All-positive trade list → P5 final > start, probabilityNegativeFinal = 0.
  - All-negative trade list → P95 final < start, probabilityNegativeFinal = 1.
  - Mixed list with known shape → percentiles fall in expected ranges.
  - `tradeCount < minTradesForMonteCarlo` → returns null from the helper that builds `MonteCarloSummary?`.

- `SvgChartTest`:
  - `lineChart` produces well-formed SVG with correct viewBox.
  - `fanChart` renders 5 distinct band paths.

### Integration

- `BacktestReportWriterHtmlTest`:
  - Run a deterministic backtest fixture; assert `report.html` is created and parseable.
  - Assert it contains: strategy name, trade count, all section headers, the SVG equity chart, the DD table, the MC summary numbers, the trade table sample.
  - File size bound: under 1 MB for a 200/200 trade-table sample (defends against accidental embedding of the full trade list).

### End-to-end

- Run an existing fixture backtest with the new writer, manually open the HTML in a browser, verify it renders.
- Print to PDF via headless Chrome from the test environment (optional; if too heavy for CI, skip and document the manual step).

## 6. Risks

**Medium — `riskUsd` accuracy.** The risk amount comes from the bracket evaluation at order-submission. If a strategy uses pyramiding (Phase 13a STACK), risk is per-layer; the report shows risk per fill, which is correct but the operator must understand layered risk aggregates over time. Mitigation: the changelog documents this; future work could add a per-stack aggregated view.

**Medium — Monte Carlo i.i.d. assumption.** Bootstrap with replacement assumes trades are independent. Strategies with clustered wins/losses (momentum strategies in trends) violate this — the MC will be optimistic about path dependence. Mitigation: clear docstring on `MonteCarloSummary`; recommend block bootstrap as a v2 enhancement when momentum strategies become primary.

**Medium — HTML file size on long backtests.** A 100k-trade backtest's full per-trade table could exceed 50 MB. Mitigation: configured head/tail truncation (default 200/200) caps the table at ~400 rows. Full data stays in CSV.

**Low — SVG complexity in equity charts.** A 100k-point equity curve as raw SVG `<line>` elements bloats the file. Mitigation: downsample to ~2000 points before rendering. The downsampling is purely visual; CSV retains full resolution.

**Low — Browser print pagination.** `@media print` page breaks may misalign across browsers. Mitigation: tested in Chrome and Firefox; documented as best-effort. Operators printing critical reports should verify before sending.

**Low — Embedded CSS / no JS regressions.** Removing all `<script>` means no interactive sorting on the trade table. Acceptable trade-off for portability.

## 7. References

- Phase 10 backtest reporting spec/changelog: `docs/phases/phase-10-backtest-reporting.md`
- Existing `PerformanceReport`: `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`
- Existing `BacktestReportWriter`: `src/main/kotlin/com/qkt/backtest/report/BacktestReportWriter.kt`
- Existing `EquityCurveCollector`: `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt`
- Phase 13a STACK (per-layer risk implication): `docs/phases/phase-13a-stack.md`
