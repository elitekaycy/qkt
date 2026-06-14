# Conditional lag-1 autocorrelation metric — design

Issue: #460 (split from #459). Status: **design only — not implemented.** See "Tractability" for why.

## Problem

qkt-forge's session-momentum thesis assumes short-horizon continuation (a bar's return predicting the next bar's return) concentrates in a few UTC hours and in high-volatility bars. Before sizing a strategy on that edge — and to watch it for decay — qkt-forge needs the backtest to report **lag-1 autocorrelation of per-bar close-to-close returns**, conditioned on:

- **hour-of-day (UTC)**, bucketed 0–23, and
- **volatility regime**, a 2-level split (high vs low) of each bar by `|return|` against the median `|return|` over the run.

The number must be deterministic, computed from the same bar stream the backtest already replays, and surfaced in the backtest report (`--json` and printed text) so no custom post-processing step is needed. This is an analytics/reporting capability, distinct from the trading gate `SESSION_WINDOW` (shipped in #459).

## The metric

Let `c_0, c_1, …, c_n` be the close prices of consecutive bars of one symbol on one timeframe, with bar-close timestamps `t_0, …, t_n` (UTC ms). Define per-bar simple returns:

```
r_i = (c_i - c_{i-1}) / c_{i-1}        for i = 1..n
```

Each return `r_i` is attributed to the bar that produced it — its hour-of-day is `hourUtc(t_i)` and its `|r_i|` decides its vol regime.

Lag-1 autocorrelation over a set of returns `S = {r_i}` is the Pearson correlation between each return and the one immediately before it **within the same bucket's ordered stream** — i.e. pairs `(r_i, r_{i-1})` where `r_i ∈ S`:

```
autocorr1(S) = corr( r_i , r_{i-1} )           over i with r_i ∈ S
             = Σ (r_i − μ)(r_{i-1} − ν) / sqrt( Σ (r_i − μ)² · Σ (r_{i-1} − ν)² )
```

where `μ` is the mean of the `r_i` in the bucket and `ν` the mean of their predecessors `r_{i-1}`. Use the lagged-mean form (two means, not one) — it is the standard sample lag-1 autocorrelation and avoids a spurious bias when a bucket's level differs from its predecessors' level (e.g. an hour bucket whose previous bars sit in a different hour).

Buckets:

- **Per hour:** 24 buckets keyed `0..23` by `hourUtc(t_i)`.
- **Per regime:** 2 buckets, `HIGH` (`|r_i| ≥ medianAbsReturn`) and `LOW` (`|r_i| < medianAbsReturn`), where `medianAbsReturn` is the median of `{|r_i| : i = 1..n}` over the whole run. Median (not mean) so the split is robust to fat-tailed return distributions and lands near a 50/50 partition.

Guards:

- A bucket with **fewer than 3 returns** → omit it (null), never `NaN`. Pearson correlation needs at least a few pairs to mean anything; 3 is the minimum that is not degenerate.
- A bucket whose denominator is zero (all returns identical, zero variance) → omit it (null), same as the `EquityMetrics.sharpe` "zero return variance → null" convention.
- All arithmetic in `BigDecimal` with `Money.CONTEXT` / `Money.ROUNDING`, matching `ReportBuilder`, `EquityMetrics`, and `MonteCarlo`. The square root for the denominator must use a `BigDecimal` sqrt (e.g. `BigDecimal.sqrt(Money.CONTEXT)` on JDK 9+, already available to the project) — do **not** drop to `Double`.

### Worked example

Two bars per hour over hours 13 and 14 UTC, perfectly mean-reverting in hour 13 (returns alternate sign) and perfectly trending in hour 14:

- hour 13 returns `+0.01, −0.01, +0.01, −0.01, …` → `autocorr1 ≈ −1.0`
- hour 14 returns `+0.01, +0.01, +0.01, …` → variance 0 → **omitted (null)**, not `+1.0`

A test fixture builds exactly this kind of series (a sign-alternating sequence for a clean `−1`, and a constant-step sequence to exercise the zero-variance guard) so the asserted autocorr per bucket is exact.

## Tractability — why this is spec-only

The blocker is **the report layer has no access to a timed bar/return series.** Traced through the current code:

- `ReplayEngine.snapshot()` (`src/main/kotlin/com/qkt/research/ReplayEngine.kt`) is the only place a `BacktestResult` is assembled. The single timed series it forwards is `collector.global()` → `List<EquitySample>` — `(timestamp, equity)`, i.e. **account equity, not bar close price**, and it is **decimated to ≤10k points** by `DecimatedCurve` (`EquityCurveCollector.DEFAULT_CURVE_CAP`). Equity returns ≠ price returns, and a decimated curve cannot reconstruct per-bar returns.
- The engine's only hook on closed bars is `onCandle = { barsClosed++ }` — it keeps a **counter**, not the closes. `CandleEvent` (carrying a full `Candle` with `close`, `endTime`, `symbol`) is consumed by strategies inside `TradingPipeline`/`CandleHub` and by `EquityCurveCollector` (which uses only `candle.endTime` as a sampling trigger and discards the close). Nothing retains the close series for the report.
- `PerformanceReport` (`src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`) — where the MC summary, daily PnL, etc. live — is a **per-strategy P&L** object. It is built once per strategy plus once globally. The autocorr metric is a property of the **market data**, identical across strategies; putting it on `PerformanceReport` would compute the same market stat N+1 times and misattribute a market-structure number to strategy performance. Wrong home.
- The backtest can replay **multiple symbols** (`MergingTickFeed` in `Backtest.fromSource`). "Per-bar returns" is undefined without choosing which symbol's bars the top-level report describes, or keying the whole metric per symbol. That is a product decision, not a mechanical add.

So surfacing this needs genuinely new plumbing — not a field added to an existing summary — across at least: a new bar-close collector, a new metric type on `BacktestResult`, and new rendering branches in `ReportPrinter` (which today reads only `result.global`). Per the engineering posture (smallest reasonable change; don't fork parallel primitives without a design call) and the issue's P3/backlog status, that warrants this spec and elitekaycy's sign-off on the open questions below before code.

## Where the return series must come from

Mirror the existing online-accumulator pattern (`EquityCurveCollector` + `EquityMetrics`): subscribe to `CandleEvent`, fold each bar's close into a per-symbol accumulator, and read the finished metric at `snapshot()` time. **Do not** retain the full undecimated close series — accumulate online so memory stays bounded under a multi-million-bar run, exactly as `EquityMetrics` does for Sharpe/drawdown.

Concretely:

1. **New collector** — `ReturnAutocorrCollector` (package `com.qkt.backtest`, e.g. `src/main/kotlin/com/qkt/backtest/ReturnAutocorrCollector.kt`). Constructed in `ReplayEngine.init` alongside `EquityCurveCollector`, subscribing `bus.subscribe<CandleEvent>`. For each candle it computes the close-to-close return from the previous close **of the same symbol**, then routes that return into:
   - the hour bucket `hourUtc(candle.endTime)`, and
   - (deferred to finalize) the regime bucket, since the median `|return|` is only known after the full pass.
   It keeps, per (symbol, hour) and per symbol overall, the running sums needed for lag-1 Pearson: `Σr_i`, `Σr_{i-1}`, `Σr_i²`, `Σr_{i-1}²`, `Σ r_i·r_{i-1}`, and count. The regime split needs the median, so the collector must retain per-symbol the `|return|` values (or an online median/quantile estimator) to find `medianAbsReturn`, then a second classification pass. **Open question (A):** exact-median requires retaining returns (O(bars) memory, defeating the bounded-memory goal) vs. a streaming quantile estimate (bounded, approximate). Resolve before implementing.

2. **New metric type** — e.g. `ConditionalAutocorr` (data class) with `perHour: Map<Int, BigDecimal>` (sparse; omit <3-sample hours), `perRegime: Map<Regime, BigDecimal>`, and parallel sample-count maps `perHourCount` / `perRegimeCount`. KDoc with the worked example above. Shape it as **one nested object** so it renders like a self-contained block, the way `MonteCarloSummary` is one nested field.

3. **Thread through the result.** Add the metric to `BacktestResult` (a top-level field, not inside `PerformanceReport`), populated in `ReplayEngine.snapshot()` from the collector. **Open question (B):** single-symbol backtests get one `ConditionalAutocorr`; multi-symbol gets a `Map<String, ConditionalAutocorr>`. Decide whether the report shows per-symbol always (a map, with a single-entry map for the common case) or only the first/primary symbol.

4. **Render in both formats** in `ReportPrinter` (`src/main/kotlin/com/qkt/cli/ReportFormat.kt`):
   - **JSON:** a nested object, e.g.
     `"conditionalAutocorr":{"perHour":{"13":-1.0,"14":0.42},"perRegime":{"high":0.31,"low":-0.05},"hourCounts":{"13":120,…},"regimeCounts":{"high":600,"low":600}}`.
     Follow the existing hand-rolled `StringBuilder` style and the `?: "null"` convention for absent values; sort map keys for deterministic output (as `dailyPnL` already does).
   - **Text:** a labelled block under the metrics, e.g. a `Lag-1 autocorr by hour (UTC)` line per populated hour and a `by vol regime` pair, plus a one-line convention note ("high = |return| ≥ median; buckets with <3 returns omitted"), mirroring the existing assumptions block.

5. **`qkt research`** (optional, deferred) — the same metric could surface in the interactive research session via `ReplayEngine.snapshot()`, since it already produces a `BacktestResult`. No extra work beyond steps 1–4 if the research command prints from the same result.

## Test plan

Mirror `ReportPrinterTest` (`src/test/kotlin/com/qkt/cli/ReportPrinterTest.kt`) and the metric-accumulator tests (`EquityMetricsTest`, `MonteCarloTest`):

- `ReturnAutocorrCollectorTest` — feed a deterministic `CandleEvent` series with bar-close timestamps placed in known UTC hours and closes engineered for a known per-bucket autocorr (sign-alternating → `−1`; verify the <3-sample and zero-variance buckets are omitted, not `NaN`). Assert exact `BigDecimal` values.
- `ReportPrinterTest` additions — a `BacktestResult` carrying a populated `ConditionalAutocorr` renders the nested JSON object and the text block; an empty/absent one renders cleanly (no `NaN`, no dangling commas in JSON).

## Open questions (resolve before implementing)

- **(A) Median strategy:** retain per-symbol returns for an exact median (O(bars) memory) vs. a streaming quantile estimator (bounded, approximate). The rest of the metric is exact; an approximate regime split should be documented as such.
- **(B) Multi-symbol:** per-symbol map always, vs. primary-symbol only. Affects the `BacktestResult` field type and the JSON/text shape.
- **(C) Returns on synthesized-bar feeds:** crypto/bars-only sources replay via `BarTickFeed` (O→L→H→C synthetic ticks), so the aggregated candle's `close` is faithful, but confirm the close-to-close return is taken from `CandleEvent.close`, not from synthetic intra-bar ticks.
- **(D) Regime classifier granularity:** issue says "e.g. high/low realized-vol bars." This spec uses `|return|` as the per-bar vol proxy (simplest, deterministic, no window). A rolling realized-vol window is a richer alternative; only build it if qkt-forge asks — YAGNI otherwise.

## References

- Issue #460; split from #459 (SESSION_WINDOW gate, shipped).
- Read layer: `src/main/kotlin/com/qkt/cli/ReportFormat.kt`, `src/main/kotlin/com/qkt/backtest/PerformanceReport.kt`, `src/main/kotlin/com/qkt/backtest/BacktestResult.kt`.
- Accumulator pattern to mirror: `src/main/kotlin/com/qkt/backtest/EquityMetrics.kt`, `src/main/kotlin/com/qkt/backtest/EquityCurveCollector.kt`, `src/main/kotlin/com/qkt/backtest/MonteCarloSummary.kt`.
- Assembly point: `src/main/kotlin/com/qkt/research/ReplayEngine.kt` (`snapshot()`).
- Bar source: `src/main/kotlin/com/qkt/marketdata/Candle.kt`, `src/main/kotlin/com/qkt/events/Event.kt` (`CandleEvent`).
