# Phase 16 — Backtest HTML report with DD-days, Monte Carlo, per-trade risk

**Released:** 2026-05-10
**Version:** 0.18.0

## Summary

Phase 16 turns the backtest output into a presentation-grade artifact. Phase 10 already computed Sharpe, Calmar, profit factor, max DD, win/loss stats, and emitted a JSON + CSV bundle. Phase 16 adds three audit angles on top of that and bundles everything into a single self-contained `report.html`: the equity curve overlaid with shaded drawdown regions, a sortable drawdown-period table, a Monte Carlo fan chart with percentile bands, and a per-trade risk-USD column. The HTML has no external CSS or JavaScript — it's a single file you can email, archive, or browser-print to PDF.

## What's new

- Single `report.html` per backtest run, alongside the existing `result.json` + CSVs.
- `DrawdownAnalyzer` computes peak-to-trough segments from any `EquitySample` curve, with configurable threshold; segments include start, trough, recovery, depth %, duration, and an `ongoing` flag for unrecovered drawdowns at run end.
- `MonteCarlo` bootstrap simulator: 1000 sims of trade-sequence reordering by default, deterministic via fixed seed, returns 5/25/50/75/95 percentiles for final equity and max DD plus probability-of-negative-final.
- `PerformanceReport` carries `drawdownPeriods: List<DrawdownPeriod>` and `monteCarlo: MonteCarloSummary?`; `monteCarlo` is null when the run has fewer than 30 trades.
- `TradeRecord` carries `riskUsd: BigDecimal?`; populated from `OrderManager` when a bracketed entry has a stop-loss, null otherwise.
- `OrderManager.riskUsdFor(clientOrderId)` exposes the per-order risk lookup; populated via `recordRisk` at bracket submission.
- `SvgChart` produces pure-SVG line, line-with-underwater, and fan charts. No external graphics library.
- `HtmlReportConfig` knobs: trade-table head/tail truncation (default 200/200), DD threshold (-1%), MC sims (1000), MC seed (42), min trades for MC (30).
- `trades.csv` gains a `riskUsd` column between `realized` and `brokerOrderId`.

## Migration from Phase 15

**`PerformanceReport` gained two fields.** `drawdownPeriods` and `monteCarlo` have defaults (empty list, null), so existing constructors continue to work. New code that builds reports via `ReportBuilder.buildGlobal`/`buildPerStrategy` automatically gets the populated values.

**`TradeRecord` gained `riskUsd: BigDecimal?` with a default of null.** Existing constructors continue to work. The CSV column is appended; tools using header-aware CSV parsers continue to work, tools using positional column indexing need to update.

**`trades.csv` column order changed.** Old: `timestamp,strategy,symbol,side,quantity,price,realized,brokerOrderId`. New: `timestamp,strategy,symbol,side,quantity,price,realized,riskUsd,brokerOrderId`. The new column is between `realized` and `brokerOrderId`.

**No DSL surface change.**

## Usage cookbook

### Running a backtest and opening the report

```bash
qkt backtest my-strategy.qkt --report ./out
open ./out/report.html      # macOS
xdg-open ./out/report.html  # linux
```

The file is self-contained — no asset directory to ship alongside.

### Reading the headline cards

The top of the report shows seven cards: total PnL (green/red), trades, win rate, Sharpe, Calmar, max DD, profit factor. A glance answers "is this strategy profitable, how stable is it, and how deep did it go".

### Equity + drawdown chart

The equity line is overlaid with shaded red regions marking each drawdown period above the threshold. Wide red regions = long drawdowns; deep dips = severe drawdowns. Both matter — a 5% DD over 200 days is a different risk from a 5% DD over 14 days.

### Drawdown periods table

Sorted by depth (deepest first). Each row: peak timestamp, trough timestamp, recovery timestamp (or "ongoing"), depth %, duration ms, status. The deepest drawdowns are first; ongoing drawdowns at run end are flagged.

### Monte Carlo

Bootstrap-with-replacement on per-trade returns. The percentiles answer "if these same trades had landed in a different random order, what's the spread of final equity?". The fan chart shows P5–P95 (light band), P25–P75 (darker band), median line.

Reading it:
- **Narrow fan** = the strategy's final equity is robust to trade order.
- **Wide fan with positive median** = path-dependent profitable strategy.
- **Negative-leaning fan** = the underlying trade distribution is unprofitable; favorable runs are luck.
- **Probability of negative final equity** as a single number — the audit summary.

### Per-trade risk

Every row in the trade table shows `riskUsd`: how much the strategy was risking on that trade based on its bracket stop-loss. `n/a` means the entry didn't have a stop attached — operators should interpret this as "uncapped on this trade".

### Printing to PDF

Open the HTML in Chrome or Firefox, hit print, choose "Save as PDF". The `@media print` rule hides the rejections section and adds page breaks before each major section. For investor handoffs, print to PDF and email the single file.

### Composition with sweeps

`SweepReportWriter` (Phase 10b) is unchanged — sweep summaries continue to use the JSON bundle. Each individual sweep run now produces its own `report.html`. To diff two configurations side-by-side, open both report HTMLs in adjacent browser windows.

## Testing patterns

`DrawdownAnalyzer` is tested with synthetic equity curves: empty, monotone, single cycle, multiple cycles, ongoing-at-end, threshold filtering, sort order. The implementation is a single forward pass; tests cover all branches.

```kotlin
val curve = listOf(EquitySample(0L, BigDecimal("100")), EquitySample(1L, BigDecimal("120")),
                   EquitySample(2L, BigDecimal("108")), EquitySample(3L, BigDecimal("120")))
val dd = DrawdownAnalyzer.analyze(curve, BigDecimal("-0.01")).single()
assertThat(dd.depthPct.toDouble()).isCloseTo(-0.10, within(0.001))
assertThat(dd.recoveryTimestamp).isEqualTo(3L)
```

`MonteCarlo` is deterministic via fixed seed:

```kotlin
val a = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
val b = MonteCarlo.run(returns, BigDecimal("100"), simulations = 500, seed = 42L)
assertThat(a).isEqualTo(b)
```

`HtmlReportWriter` is structurally tested — assert the produced HTML contains expected section headers, has `<svg>` elements, and lacks `<script>`. No golden-file comparison; the bytes-level output is deliberately unstable across cosmetic CSS tweaks.

## Known limitations

- **No slippage measurement.** `riskUsd` reflects the bracket's intent at submission, not the actual fill price. A future phase captures expected-vs-actual fill prices through the broker fill path.
- **No spread capture.** Bid-ask spread isn't recorded. Requires bid/ask data in the tick feed.
- **No regime breakdown.** PnL by volatility regime is deferred to a future phase that adds a regime classifier.
- **Monte Carlo i.i.d. assumption.** Bootstrap with replacement assumes trades are independent. Strategies with clustered wins/losses (momentum strategies) violate this; the MC will be optimistic about path dependence. Block bootstrap is a v2 enhancement when momentum strategies become primary.
- **Trade table truncation.** Default 200 head + 200 tail = ~400 rows. Full data stays in `trades.csv`.
- **Equity chart at scale.** A 100k-point equity curve renders as raw SVG `<polyline>`; file sizes can grow. Visual downsampling to ~2000 points is a future enhancement.
- **Print-pagination is best-effort.** `@media print` page breaks tested in Chrome and Firefox; other browsers may misalign.
- **No DSL log lines woven into the report.** Phase 15 LOG output is per-strategy; tying it to specific bars on the equity chart is future work.
- **`riskUsd` for stack pyramiding.** Risk is recorded per fill. For a STACK with multiple layers, the report shows risk per layer, not aggregated stack risk.

## References

- Spec: [`docs/superpowers/specs/2026-05-10-trading-engine-phase16-backtest-html-report-design.md`](../superpowers/specs/2026-05-10-trading-engine-phase16-backtest-html-report-design.md)
- Plan: [`docs/superpowers/plans/2026-05-10-trading-engine-phase16-backtest-html-report.md`](../superpowers/plans/2026-05-10-trading-engine-phase16-backtest-html-report.md)
- Phase 10 backtest reporting: [`docs/phases/phase-10-backtest-reporting.md`](phase-10-backtest-reporting.md)
- Phase 13a STACK (per-layer risk implication): [`docs/phases/phase-13a-stack.md`](phase-13a-stack.md)
