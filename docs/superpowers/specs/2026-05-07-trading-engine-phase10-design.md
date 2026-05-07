# Phase 10 — Backtest Reporting: Equity Curves, Per-Strategy Reports, Metrics, Writers

**Status:** Design draft.
**Predecessor:** Phase 9 (risk engine: equity, drawdown, halts).
**Successor:** Phase 11+ (DSL, parameter sweeps, walk-forward).

---

## 1. Mission

Phase 10 turns the backtest from a single-shot smoke run into a serious reporting tool. After this phase, a backtest produces a structured report with the equity curve over time, per-strategy attribution, and the metrics every quant report carries: profit factor, Sharpe, Calmar, win/loss stats. The data lands in memory for programmatic comparison and on disk as JSON + CSV for charting in any external tool. This is the foundation for the iteration loop that lets a quant validate a strategy *before* risking money.

The phase is deliberately scoped to **measurement, not iteration**. Parameter sweeps and walk-forward are larger features that build on what we ship here; both go to a later phase. Phase 10 is "the equity curve, the metrics, and the file output" — nothing else.

---

## 2. Goals

- New package `com.qkt.backtest`. `Backtest` and `BacktestResult` move out of `com.qkt.app`.
- `Backtest` configurable with `cadence: SampleCadence` (default `CANDLE_CLOSE`).
- `EquityCurveCollector` subscribes to the event bus, emits `EquitySample(timestamp, equity)` at the chosen cadence, separately for global and each strategy.
- `BacktestResult` restructured: `global: PerformanceReport`, `perStrategy: Map<String, PerformanceReport>`, plus the existing trades/rejections/finalPositions and the `cadence` used.
- `PerformanceReport` carries the full metric set: P&L breakdown, win rate, drawdown (fractional, Phase 9 convention), profit factor, avg/largest win+loss, max consecutive losses, Sharpe, Calmar, equity curve.
- Pure-function metrics in `com.qkt.backtest.metrics`: `ProfitFactor`, `WinLossStats`, `Sharpe`, `Calmar`. No state, no I/O.
- Drawdown computed at report-build time from the curve via `DrawdownTracker.fromCurve(curve)` (a new pure function on Phase 9's `DrawdownTracker`). Drops the inline `peakEquity`/`maxDrawdown` mutation in `Backtest.run()`.
- `BacktestReportWriter(dir)` emits `result.json` + `equity_global.csv` + `equity_<strategyId>.csv` + `trades.csv` + `rejections.csv` to a target directory. Hand-rolled JSON serializer — no external dependency.
- `TradingCalendar.tradingPeriodsPerYear(window: TimeWindow): BigDecimal` — calendar-aware annualization factor used by Sharpe.

## Non-goals

- **No parameter sweep / grid search.** That's Phase 10b or 12. Today's deliverable is a single rich run; the harness comes later.
- **No walk-forward / rolling train-test windows.** Same — separate phase.
- **No slippage or latency models.** `PaperBroker` continues to fill at last tracker price. Realistic execution modeling is its own opt-in component.
- **No HTML report.** Presentation layer concern. JSON + CSV are enough to plot in pandas/matplotlib/Excel; a styled HTML report belongs after the DSL lands.
- **No "total return %" or CAGR.** Both require an initial-capital concept the engine doesn't have. P&L starts at zero and is reported as absolute money; percentage returns are caller-computed if they have a notional.
- **No round-trip / hold-time metrics.** Inferring a "completed trade" from a fill stream is ambiguous when strategies scale in and out. Realized P&L per fill is a reasonable proxy and is what the existing `winRate` already uses.
- **No Sortino, Ulcer, recovery factor.** Diminishing returns over Sharpe + Calmar. Add later if a real use case appears.
- **No CLI for running backtests from config.** Phase 11 (DSL) territory.
- **No equity-curve down-sampling / compression.** With candle-close as the default cadence, curves are bounded by candle count (a 1-year 1m crypto backtest = ~525k samples, which fits in memory and on disk easily).
- **No persistence of `BacktestResult` between runs.** The writer dumps to disk; reading back into a typed object is the caller's job (or a later feature).
- **No automatic comparison across runs.** Diffing two reports is a separate tool. Phase 10 produces one self-contained report per run.

---

## 3. Architecture

```
                     ┌────────────────────────┐
                     │  Backtest.run()        │
                     │                        │
                     │  ┌──────────────────┐  │
                     │  │ TradingPipeline  │  │
                     │  │  (unchanged)     │  │
                     │  └────────┬─────────┘  │
                     │           │            │
                     │           ▼            │
                     │  ┌──────────────────┐  │
                     │  │ EventBus         │  │
                     │  └─┬──────┬─────┬───┘  │
                     │    │      │     │      │
                     │    ▼      ▼     ▼      │
                     │  Tick   Candle Fill    │
                     │    │      │     │      │
                     │    └──┬───┴─────┘      │
                     │       ▼                │
                     │  ┌──────────────────┐  │
                     │  │ EquityCurve-     │  │
                     │  │ Collector        │  │
                     │  │  (cadence-aware) │  │
                     │  └────────┬─────────┘  │
                     │           │            │
                     │           ▼            │
                     │   List<EquitySample>   │
                     │   per strategy + global│
                     │           │            │
                     │           ▼            │
                     │  ┌──────────────────┐  │
                     │  │ ReportBuilder    │  │
                     │  │ (pure functions) │  │
                     │  └────────┬─────────┘  │
                     │           │            │
                     │           ▼            │
                     │   BacktestResult       │
                     └───────────┬────────────┘
                                 │
                                 ▼
                     ┌────────────────────────┐
                     │ BacktestReportWriter   │
                     │   (separated I/O)      │
                     └────────────────────────┘
```

**Read/write split.** `EquityCurveCollector` is the only writer of equity samples. `ReportBuilder` is a pure consumer of trades + curves. The writer is a pure consumer of the result. No two components both mutate the same data — matches the qkt architecture invariant.

**Determinism.** Every input is deterministic (`FixedClock`, `MonotonicSequenceGenerator`, `SequentialIdGenerator` already in `Backtest`). Metrics are pure functions of trades + curves. No `Random`, no I/O during compute, no time-of-day logic. Two runs with the same feed produce byte-identical `result.json`.

---

## 4. Package layout

```
com.qkt.backtest/                     # new
├── Backtest.kt                       # moved from com.qkt.app, body unchanged
├── BacktestResult.kt                 # restructured (see §5)
├── PerformanceReport.kt              # new
├── EquitySample.kt                   # new
├── SampleCadence.kt                  # new — TICK | CANDLE_CLOSE | FILL
├── EquityCurveCollector.kt           # new
├── ReportBuilder.kt                  # new — pure assembly of PerformanceReport
├── metrics/
│   ├── ProfitFactor.kt               # fun profitFactor(realizeds: List<BigDecimal>): BigDecimal?
│   ├── WinLossStats.kt               # data class WinLossStats; fun compute(realizeds): WinLossStats
│   ├── Sharpe.kt                     # fun sharpe(curve, annualizationFactor): BigDecimal?
│   └── Calmar.kt                     # fun calmar(totalReturn, maxDrawdown): BigDecimal?
└── report/
    ├── BacktestReportWriter.kt       # writes JSON + CSVs to a directory
    └── ReportSerializer.kt           # internal — BigDecimal/timestamp serialization helpers

com.qkt.common.TradingCalendar       # adds tradingPeriodsPerYear(window: TimeWindow): BigDecimal

com.qkt.risk.DrawdownTracker         # gains companion fun fromCurve(samples): BigDecimal
```

`com.qkt.app.Backtest` moves to `com.qkt.backtest.Backtest`. Imports update across `Main.kt`, `MaxAudit.kt`, every `*BacktestTest`. No alias re-export — qkt has no compat layer.

`com.qkt.app.BacktestResult` moves with it.

---

## 5. Data model

### 5.1 SampleCadence

```kotlin
package com.qkt.backtest

enum class SampleCadence { TICK, CANDLE_CLOSE, FILL }
```

### 5.2 EquitySample

```kotlin
package com.qkt.backtest

import java.math.BigDecimal

data class EquitySample(val timestamp: Long, val equity: BigDecimal)
```

### 5.3 PerformanceReport

```kotlin
package com.qkt.backtest

import java.math.BigDecimal

data class PerformanceReport(
    val realizedTotal: BigDecimal,
    val unrealizedTotal: BigDecimal,
    val totalPnL: BigDecimal,
    val tradeCount: Int,
    val winRate: BigDecimal,            // [0, 1]
    val maxDrawdown: BigDecimal,        // fractional (Phase 9 convention); 0 when no positive peak
    val profitFactor: BigDecimal?,      // null when no losses (callers display "N/A" or "∞")
    val avgWin: BigDecimal,             // 0 when no wins
    val avgLoss: BigDecimal,            // signed (negative); 0 when no losses
    val largestWin: BigDecimal,         // 0 when no wins
    val largestLoss: BigDecimal,        // signed (negative); 0 when no losses
    val maxConsecutiveLosses: Int,      // 0 when no losses
    val sharpeRatio: BigDecimal?,       // null when <2 samples or stddev = 0
    val calmarRatio: BigDecimal?,       // null when maxDrawdown = 0 or curve empty
    val equityCurve: List<EquitySample>,
)
```

### 5.4 BacktestResult

```kotlin
package com.qkt.backtest

import com.qkt.app.TradeRecord
import com.qkt.events.RiskRejectedEvent
import com.qkt.positions.Position

data class BacktestResult(
    val trades: List<TradeRecord>,
    val rejections: List<RiskRejectedEvent>,
    val finalPositions: Map<String, Position>,
    val global: PerformanceReport,
    val perStrategy: Map<String, PerformanceReport>,
    val cadence: SampleCadence,
)
```

This **breaks** the existing `result.maxDrawdown`, `result.totalPnL`, etc. callers. Per qkt rule "no backwards compatibility cruft", we update the call sites and move on. Compile-time failure surfaces every miss.

---

## 6. Sampling mechanics

`EquityCurveCollector` is constructed inside `Backtest.run()` after the bus exists:

```kotlin
val collector = EquityCurveCollector(
    cadence = cadence,
    bus = bus,
    pnl = pnl,                          // PnLProvider — global equity
    strategyPnL = strategyPnL,          // per-strategy equity
    strategyIds = strategies.map { it.first },
)
```

It reads equity directly from `PnLCalculator.realizedTotal() + unrealizedTotal()` for global, and `StrategyPnL.totalFor(id)` for each strategy. We deliberately do **not** depend on `EquityTracker` — that class is built for halt-rule snapshots and requires manual `update()` calls; reading the underlying P&L providers gives us a guaranteed-fresh value at any subscription moment.

The collector subscribes once based on the cadence:

| Cadence | Subscribes to | Sample timestamp |
|---|---|---|
| `CANDLE_CLOSE` | `CandleEvent` | `candle.endTime` |
| `TICK` | `TickEvent` | `tick.timestamp` |
| `FILL` | `BrokerEvent.OrderFilled` | `event.timestamp` |

On each event:
1. Read global equity = `pnl.realizedTotal() + pnl.unrealizedTotal()`.
2. For each strategyId in `strategyIds`, read `strategyPnL.totalFor(id)`.
3. Append one `EquitySample` to the global list and one to each per-strategy list, all with the same timestamp.

`Backtest` constructor enforces cadence prerequisites:

```kotlin
require(cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
    "SampleCadence.CANDLE_CLOSE requires a non-null candleWindow"
}
```

After the run loop completes, the collector's lists are passed to `ReportBuilder.build(...)` along with the trade list and final positions.

---

## 7. ReportBuilder

`ReportBuilder` is a pure object. It assembles one `PerformanceReport` from inputs:

```kotlin
package com.qkt.backtest

object ReportBuilder {
    fun buildGlobal(
        trades: List<TradeRecord>,
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport

    fun buildPerStrategy(
        strategyId: String,
        trades: List<TradeRecord>,                 // already filtered to this strategy
        equityCurve: List<EquitySample>,
        finalRealized: BigDecimal,
        finalUnrealized: BigDecimal,
        annualizationFactor: BigDecimal,
    ): PerformanceReport
}
```

Both functions:
1. Compute `tradeCount` from non-zero-realized records.
2. Compute `winRate` (closing trades only — same definition as today).
3. Compute drawdown via `DrawdownTracker.fromCurve(equityCurve)`.
4. Compute `profitFactor` via `metrics.profitFactor`.
5. Compute `WinLossStats` (avgWin, avgLoss, largestWin, largestLoss, maxConsecutiveLosses).
6. Compute Sharpe via `metrics.sharpe(equityCurve, annualizationFactor)`.
7. Compute Calmar via `metrics.calmar(totalReturn = finalRealized + finalUnrealized, maxDrawdown)`.
8. Assemble the report.

`Backtest.run()` then wraps these into `BacktestResult`.

---

## 8. Metrics math

All metrics live as top-level functions in `com.qkt.backtest.metrics.*`.

### 8.1 ProfitFactor

```kotlin
fun profitFactor(realizeds: List<BigDecimal>): BigDecimal?
```

`Σ(positive) / |Σ(negative)|`. Returns `null` when the loss sum is zero (no losses). Caller decides display.

### 8.2 WinLossStats

```kotlin
data class WinLossStats(
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,             // signed negative
    val largestWin: BigDecimal,
    val largestLoss: BigDecimal,         // signed negative
    val maxConsecutiveLosses: Int,
)

fun winLossStats(realizeds: List<BigDecimal>): WinLossStats
```

`avgWin = mean(positives)`, `avgLoss = mean(negatives)`. `largestWin = max(positives)`, `largestLoss = min(negatives)`. `maxConsecutiveLosses` = longest run of negative realizeds in trade order. Empty inputs return zeros.

### 8.3 Sharpe

```kotlin
fun sharpe(curve: List<EquitySample>, annualizationFactor: BigDecimal): BigDecimal?
```

Returns `null` when `curve.size < 2` or the return-series stddev is zero.

Returns construction:

```
returns[i] = (equity[i+1] - equity[i]) / max(|equity[i]|, ε)
```

with `ε = 1e-8` (configurable in code, but a single constant). Risk-free rate is zero — crypto + no observable benchmark.

```
sharpe = mean(returns) / stddev(returns) × sqrt(annualizationFactor)
```

`stddev` is the sample stddev (denominator `n-1`). `BigDecimal` math throughout, `Money.CONTEXT` for division precision; `sqrt` via `BigDecimal.sqrt(MathContext)` (Java 9+). Final result rounded to `Money.SCALE`.

### 8.4 Calmar

```kotlin
fun calmar(totalReturn: BigDecimal, maxDrawdown: BigDecimal): BigDecimal?
```

Returns `null` when `maxDrawdown == 0`.

```
calmar = totalReturn / maxDrawdown
```

We use total return (not annualized return) because we don't have a notional for CAGR. The metric is documented as "total-return-Calmar" in the report writer's JSON.

### 8.5 Annualization factor

A new method on `TradingCalendar`:

```kotlin
fun tradingPeriodsPerYear(window: TimeWindow): BigDecimal
```

`crypto()` returns `BigDecimal("525960").divide(window.minutes, Money.CONTEXT)` for crypto's 365.25 × 24 × 60 minutes/year. Future calendars (NYSE, FX) override.

When `cadence == TICK` or `cadence == FILL`, the period is irregular — Sharpe becomes approximate. We compute the **average** sample interval as `(lastTimestamp - firstTimestamp) / (sampleCount - 1)` ms, then derive `tradingPeriodsPerYear = (365.25 × 24 × 60 × 60 × 1000) / averageIntervalMs`. The report writer emits `cadence` in the JSON so consumers know whether the Sharpe is rigorous (`CANDLE_CLOSE`) or approximate (`TICK`/`FILL`). Realistic users will pick `CANDLE_CLOSE`.

### 8.6 DrawdownTracker.fromCurve

A new companion function:

```kotlin
companion object {
    fun fromCurve(samples: List<EquitySample>): BigDecimal {
        var peak = Money.ZERO
        var maxDd = Money.ZERO
        for (s in samples) {
            if (s.equity > peak) peak = s.equity
            if (peak > Money.ZERO) {
                val dd = peak.subtract(s.equity).divide(peak, Money.CONTEXT)
                if (dd > maxDd) maxDd = dd
            }
        }
        return maxDd
    }
}
```

Same convention as the live `DrawdownTracker` (returns 0 when no positive peak). This keeps drawdown semantics identical between live and backtest.

---

## 9. Report writer

```kotlin
class BacktestReportWriter(private val dir: Path) {
    fun write(result: BacktestResult)
}
```

### 9.1 Files emitted

```
<dir>/
├── result.json              # full BacktestResult, structured
├── equity_global.csv        # timestamp,equity
├── equity_<strategyId>.csv  # one per strategy in result.perStrategy
├── trades.csv               # ts,strategy,symbol,side,qty,price,realized,brokerOrderId
└── rejections.csv           # ts,reason,strategy,symbol
```

### 9.2 Validation

`write()` first validates:
- `Files.isDirectory(dir)` — fail loud if the dir doesn't exist. Caller mkdirs.
- `Files.isWritable(dir)` — fail loud if not writable.
- Every `strategyId` in `result.perStrategy.keys` matches `[A-Za-z0-9_-]+`. Anything else gets rejected — we don't quote-escape filenames.

### 9.3 CSV format

Plain comma-separated, no quoting. Header row required.

```
equity_*.csv:
timestamp,equity
1714800000000,0.00000000
1714800060000,12.34567890
...

trades.csv:
timestamp,strategy,symbol,side,quantity,price,realized,brokerOrderId
1714800000000,scalp,BTCUSDT,BUY,0.001,30000.00,0.00,b-1
...

rejections.csv:
timestamp,reason,strategy,symbol
1714800000000,KillSwitch,scalp,BTCUSDT
...
```

`BigDecimal` rendered with `toPlainString()` — never scientific notation.

### 9.4 JSON format

Hand-rolled writer in `ReportSerializer`. No external dependency (no Jackson, no kotlinx.serialization). Output is a single object, pretty-printed with 2-space indent for grep-ability.

```json
{
  "cadence": "CANDLE_CLOSE",
  "global": {
    "realizedTotal": "123.45",
    "unrealizedTotal": "0.00",
    "totalPnL": "123.45",
    "tradeCount": 42,
    "winRate": "0.5500",
    "maxDrawdown": "0.1234",
    "profitFactor": "1.8000",
    "avgWin": "5.20",
    "avgLoss": "-3.10",
    "largestWin": "20.00",
    "largestLoss": "-15.00",
    "maxConsecutiveLosses": 4,
    "sharpeRatio": "1.234",
    "calmarRatio": "10.000",
    "equityCurve": [
      {"timestamp": 1714800000000, "iso": "2024-05-04T08:00:00Z", "equity": "0.00"},
      {"timestamp": 1714800060000, "iso": "2024-05-04T08:01:00Z", "equity": "12.35"}
    ]
  },
  "perStrategy": {
    "scalp": { "...": "..." }
  },
  "trades": [
    {"timestamp": 1714800000000, "iso": "...", "strategy": "scalp", "symbol": "BTCUSDT", "side": "BUY", "quantity": "0.001", "price": "30000.00", "realized": "0.00", "brokerOrderId": "b-1"}
  ],
  "rejections": [],
  "finalPositions": {
    "BTCUSDT": {"quantity": "0.001", "avgEntryPrice": "30000.00"}
  }
}
```

`BigDecimal` always rendered as a JSON string (`"123.45"`) to avoid float precision loss. Timestamps appear twice — `timestamp` (epoch ms, machine-readable) + `iso` (UTC ISO-8601, human-readable).

### 9.5 Failure modes

- Permission denied / disk full: `IOException` propagates. The writer makes no attempt to clean up partial files; caller decides.
- Symbol/strategy id with weird characters: `IllegalArgumentException` from the precondition check, before any file is opened.
- The writer is not transactional — if a CSV write fails after JSON wrote, the dir contains a partial result. We could write to a temp dir and rename, but YAGNI: the contract is "succeed completely or call site handles the IOException."

---

## 10. Backtest constructor changes

```kotlin
class Backtest(
    private val strategies: List<Pair<String, Strategy>>,
    private val rules: List<RiskRule> = emptyList(),
    private val feed: TickFeed,
    private val candleWindow: TimeWindow? = null,
    private val initialTimestamp: Long = 0L,
    private val source: MarketSource = NullMarketSource,
    private val calendar: TradingCalendar = TradingCalendar.crypto(),
    private val warmupSpec: WarmupSpec = WarmupSpec.None,
    private val symbols: List<String> = emptyList(),
    private val cadence: SampleCadence = SampleCadence.CANDLE_CLOSE,
)
```

One new param: `cadence`, default `CANDLE_CLOSE`.

The `init {}` block enforces:

```kotlin
require(cadence != SampleCadence.CANDLE_CLOSE || candleWindow != null) {
    "SampleCadence.CANDLE_CLOSE requires candleWindow"
}
```

The secondary constructors (`fromStore`, `fromSource`, the tick-list shorthand) all get an optional `cadence` param defaulting to `CANDLE_CLOSE`.

The body of `run()` changes only in:
- Constructs the `EquityCurveCollector` after the bus + trackers exist.
- Drops the inline `peakEquity`/`maxDrawdown` mutation (now in `DrawdownTracker.fromCurve`).
- Calls `ReportBuilder.buildGlobal` and `buildPerStrategy` for each strategy at the end.
- Returns the new `BacktestResult` shape.

---

## 11. Migration

| File | Change |
|---|---|
| `com.qkt.app.Backtest` | Move to `com.qkt.backtest.Backtest` |
| `com.qkt.app.BacktestResult` | Move + restructured (now in `com.qkt.backtest`) |
| `com.qkt.app.Main` | Update imports; read `result.global.totalPnL` etc. |
| `com.qkt.app.MaxAudit` | Update imports; read new field paths. |
| `com.qkt.app.LiveSession` | No change (doesn't use `BacktestResult`). |
| `src/test/kotlin/com/qkt/app/BacktestTest*` | Move to `com.qkt.backtest`; update imports + assertions on new fields. |
| `com.qkt.common.TradingCalendar` | Add `tradingPeriodsPerYear`. |
| `com.qkt.risk.DrawdownTracker` | Add `companion fun fromCurve`. |

Compile-time fail surfaces every other miss. No deprecations, no aliases.

---

## 12. Testing

### 12.1 Unit tests

- `ProfitFactorTest` — empty, all wins, all losses, mixed, exact arithmetic on small lists.
- `WinLossStatsTest` — empty, single trade, run of losses, alternating, max-consecutive corner cases.
- `SharpeTest` — table-driven from hand-computed reference values; explicit annualization factor parameter. Cases: <2 samples → null, constant equity → null (stddev=0), monotonic up, oscillating.
- `CalmarTest` — null on zero drawdown, basic ratios, negative total return.
- `DrawdownTrackerFromCurveTest` — empty, monotone up (zero drawdown), classic peak-trough-recovery, peak never positive (returns zero).
- `TradingCalendarTradingPeriodsPerYearTest` — crypto + 1m / 5m / 1h, exact decimal values.

### 12.2 Integration tests

- `EquityCurveCollectorTest` — feeds candle / tick / fill events through a real `EventBus`, asserts samples land at correct timestamps with correct equity. One test per cadence.
- `ReportBuilderTest` — fixed trade list + curve → asserts every field of `PerformanceReport`.

### 12.3 End-to-end

- `BacktestEndToEndTest` — a tiny strategy that buys then sells over fixture ticks, full `Backtest.run()`. Asserts:
  - Trade count, win rate, P&L on `result.global` and `result.perStrategy["s1"]`.
  - Equity curve length matches expected candle count.
  - Sharpe / Calmar are non-null and finite.
- `BacktestDeterminismTest` — runs the same setup twice, asserts `result.toString()` byte-identical and `result.global.equityCurve` equals across runs.
- `BacktestReportWriterTest` — runs a tiny backtest, writes to a tmpdir, reads the files back as text, asserts content (CSV header + first/last rows; JSON structure parses, `result.global.totalPnL` matches).

### 12.4 Test conventions

- AssertJ + JUnit 5, no mocks.
- `FixedClock`, `MonotonicSequenceGenerator`, `SequentialIdGenerator` everywhere — no real time/randomness.
- Fixture trade lists declared inline as `listOf(BigDecimal("..."), ...)` for metric tests.
- Tmpdir tests use `@TempDir` from JUnit 5.

---

## 13. Open questions

None blocking. Confirmed during design:

- `StrategyPnL.totalFor(strategyId)` and `unrealizedTotalFor(strategyId)` exist — per-strategy equity is a one-line read.
- `PnLCalculator.realizedTotal()` and `unrealizedTotal()` exist — global equity is two reads + a sum.
- `DrawdownTracker.fromCurve` is a small additive function; matches the existing online drawdown semantics.
- `TradingCalendar.tradingPeriodsPerYear` is one new method on a class with one impl (crypto). Trivial to extend when other calendars land.

---

## 14. Deferred / Phase 10b candidates

These are explicitly **not** in this phase and have NOT been promised. Listed here so the team has a written backlog:

- **Parameter sweep** — `BacktestSweep(strategies: List<StrategyFactory>, configs: List<Config>).run()` returns `Map<Config, BacktestResult>`. Needs strategy-as-factory abstraction.
- **Walk-forward** — rolling train/test windows with `BacktestResult` per fold.
- **Multi-run comparison report** — diff two `BacktestResult`s into a `ComparisonReport`.
- **Slippage model** — opt-in component injected into `PaperBroker`.
- **HTML report** — nice-to-have presentation layer, comes after DSL.
- **CLI** — runs a backtest from a YAML/JSON config. Phase 11 territory.
- **Round-trip / hold-time metrics** — needs a "trade" abstraction richer than per-fill realized.
- **Sortino / Ulcer / recovery factor** — diminishing-return metrics; add only with concrete demand.
- **Equity curve persistence** in live mode (read back later) — orthogonal feature.
- **Initial capital + total-return-percent / CAGR** — needs a notional/capital model.

---

## 15. Risk

**Risk level: Medium.**

- Touches the entry-point class (`Main.kt`) and breaks the public surface of `BacktestResult`. Compile-time failure means every miss surfaces immediately, so the blast radius is bounded to the Kotlin compile step.
- No live-trading impact. `Backtest` is offline-only; `LiveSession` is unchanged.
- New code is pure compute (metrics) plus single-subscriber bus consumption (collector) plus deterministic file writing (writer). Determinism preserved.
- The hand-rolled JSON serializer is the one place that could grow bugs (escaping, BigDecimal precision). Mitigated by the writer test reading back what it wrote.

---

## 16. Success criteria

After Phase 10 merges:

- Running any existing `Backtest` produces a result with `result.global.equityCurve.isNotEmpty()`.
- The phase changelog at `docs/phases/phase-10-backtest-reporting.md` shows worked examples for: configuring cadence, reading per-strategy reports, writing reports to disk, charting the CSV in pandas (one-liner snippet).
- A future Phase 10b parameter-sweep harness can be implemented entirely on top of this surface — the result type is rich enough that comparing 100 runs is just iterating over their `PerformanceReport`s.
- `./gradlew build` is green. All tests deterministic. No new external dependency on the runtime classpath.
