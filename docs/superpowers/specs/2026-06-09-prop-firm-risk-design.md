# Prop-firm risk management — daily + total drawdown limits, configurable basis, backtest daily metrics

Date: 2026-06-09
Issue: #348 (gaps 1–4; gap #5 deferred to its own issue)

## Goal

Let qkt enforce the hard limits prop-firm accounts impose, and validate strategies against them
before deployment:

- **Total drawdown halt** with a configurable basis (`trailing` high-water vs `static` from initial balance).
- **Daily drawdown halt** (new) with a configurable basis (day-start `balance` vs `equity`).
- **Backtest daily metrics** — per-day PnL and worst intraday drawdown — in the report and `--json`.

Gap #5 from the issue (sizing off real live broker equity instead of `starting_balance + realized`)
is **out of scope** here — it touches the shared MT5 gateway image and is orthogonal to the
limit-enforcement core. It gets its own issue/PR.

## Background (verified in code)

- Halts use the **`HaltRule`** abstraction: `evaluate(riskState): HaltDecision` →
  `Continue | Halt(reason, strategyId?)` (null strategyId = global). Evaluated by `RiskEngine`
  on every fill/tick; once halted, `RiskEngine.approve()` rejects orders until an operator resumes.
- Existing `HaltRule`s in `com.qkt.risk.rules`: `MaxDailyLoss`, `MaxStrategyDailyLoss` (read
  `riskState.dailyPnLTracker`), and `MaxDrawdown`, `MaxStrategyDrawdown` (read
  `riskState.drawdownTracker`). **`MaxDrawdown`/`MaxStrategyDrawdown` already exist but are never
  instantiated from config** — gap #2 is wiring, not new logic.
- Trackers on `RiskState`: `DailyPnLTracker` (UTC-midnight rollover, per-strategy + global realized),
  `DrawdownTracker` (stateless; `(peak − current)/peak` from `EquityTracker`), `EquityTracker`
  (current + peak equity, global + per-strategy).
- Config: `Config.risk: Map<String,String>` (flat keys) + `perStrategyRisk: Map<String, PerStrategyRisk>`.
  Global halt rules instantiated in `StrategyHandle`; per-strategy in `LiveSession`.
- Backtest metrics: `EquityCurveCollector` samples equity → `EquityMetrics` online accumulators
  (`MaxDrawdownAccumulator`, `SharpeAccumulator`, …) → `ReportBuilder` → `PerformanceReport` →
  `ReportFormat` JSON.

## Design

### 1. Basis enums

```kotlin
enum class DrawdownBasis { TRAILING, STATIC }       // total DD
enum class DailyDrawdownBasis { BALANCE, EQUITY }   // daily DD day-start reference
```

- **Total DD** (`DrawdownBasis`, default **STATIC**):
  - `STATIC` *(default)* — `max(0, (initialBalance − currentEquity) / initialBalance)`. From the
    account's starting balance; never resets to a higher peak. The prop-firm "max loss" rule.
  - `TRAILING` — `(peakEquity − currentEquity) / peakEquity` — current `DrawdownTracker` behavior.
- **Daily DD** (`DailyDrawdownBasis`, default **BALANCE**), reference captured at UTC midnight,
  measured against current **equity** (includes floating P&L):
  - `BALANCE` *(default)* — reference = day-start **balance** (realized only). "Lose ≤X% of your
    starting-day balance."
  - `EQUITY` — reference = day-start **equity** (includes open-position float).

### 2. `DrawdownTracker` gains a basis (live halt only)

`DrawdownTracker` takes a `DrawdownBasis` and, for `STATIC`, the initial balance (global +
per-strategy). `globalDrawdown()` / `strategyDrawdown(id)` branch on the basis. `TRAILING` keeps
today's peak math.

**Scope guard:** this affects only the **live** instance path used by the `MaxDrawdown` halt rule.
The **backtest `maxDrawdown` reporting metric** (`MaxDrawdownAccumulator` / `DrawdownTracker.fromCurve`)
stays trailing peak-to-trough — "max drawdown" as a statistic is basis-independent and must not
change. The enum does **not** thread into `EquityMetrics`/`ReportBuilder`'s `maxDrawdown`.

### 3. `DailyDrawdownTracker` (new)

Mirrors `DailyPnLTracker`'s UTC rollover. At each rollover it captures the day-start reference
(balance or equity per `DailyDrawdownBasis`) for global and per-strategy. Exposes
`globalDrawdownToday()` / `strategyDrawdownToday(id)` = `max(0, (dayStartRef − currentEquity)/dayStartRef)`.
Lives on `RiskState` alongside the other trackers; reads current equity/balance from the same
`EquityTracker` / PnL providers `RiskState` already holds.

### 4. New halt rules

`rules/MaxDailyDrawdown(maxFraction)` and `rules/MaxStrategyDailyDrawdown(strategyId, maxFraction)`
— `HaltRule`s mirroring `MaxDailyLoss` / `MaxStrategyDailyLoss`, reading
`riskState.dailyDrawdownTracker`. Validate `maxFraction ∈ (0, 1]`.

### 5. Config

`_pct` values are **percents** (`8` → fraction `0.08`); the loader divides by 100 before
constructing rules. Basis keys parse to the enums (unknown value → clear error).

```yaml
risk:
  max_drawdown_pct: "8"          # wires the (currently dead) MaxDrawdown
  max_daily_drawdown_pct: "4"    # new daily halt
  total_dd_basis: static          # static | trailing   (default static)
  daily_dd_basis: balance         # balance | equity     (default balance)
  per_strategy:
    ema_cross:
      max_drawdown_pct: "5"
      max_daily_drawdown_pct: "3"
```

New `Config` accessors: `maxDrawdownPct`, `maxDailyDrawdownPct`, `totalDdBasis`, `dailyDdBasis`.
New `PerStrategyRisk` fields: `maxDrawdownPct`, `maxDailyDrawdownPct` (basis is account-level, not
per-strategy). Absent keys → rule not instantiated (no behavior change for existing configs).

### 6. Wiring

- **`RiskState`** constructed with the initial balance + the two basis enums, so it builds the
  `DrawdownTracker` (with basis + initial balance) and the new `DailyDrawdownTracker` (with basis).
- **`StrategyHandle`** (global): instantiate `MaxDrawdown(maxDrawdownPct/100)` and
  `MaxDailyDrawdown(maxDailyDrawdownPct/100)` when the keys are set.
- **`LiveSession`** (per-strategy): instantiate `MaxStrategyDrawdown` / `MaxStrategyDailyDrawdown`
  from `perStrategyRisk`, mirroring how `MaxStrategyDailyLoss` is wired today.

### 7. Backtest daily metrics

- `PerformanceReport.dailyPnL: Map<LocalDate, BigDecimal>` — realized PnL bucketed by UTC day,
  computed in `ReportBuilder` from the trade list (fill timestamp → UTC date).
- `PerformanceReport.maxDailyDrawdown: BigDecimal` — worst intraday equity decline from day-open
  across the run, computed by a new online `DailyDrawdownAccumulator` in `EquityMetrics`
  (`accept(timestamp, equity)`: on UTC-date change, finalize the prior day; track day-open equity +
  running intraday min; daily DD = `(dayOpen − min)/dayOpen`; keep the max across days). Equity-basis
  (the reported metric); no full-curve retention.
- `ReportFormat` emits both in `--json` (`dailyPnL` as a `{ "YYYY-MM-DD": pnl }` object,
  `maxDailyDrawdown` as a number). Text report gains a `Max daily drawdown:` line.

## Components

New files:
- `risk/DrawdownBasis.kt`, `risk/DailyDrawdownBasis.kt`
- `risk/DailyDrawdownTracker.kt`
- `risk/rules/MaxDailyDrawdown.kt`, `risk/rules/MaxStrategyDailyDrawdown.kt`
- `backtest/DailyDrawdownAccumulator.kt`

Modified:
- `risk/DrawdownTracker.kt` (basis + initial balance on the live path)
- `risk/RiskState.kt` (own `DailyDrawdownTracker`; take initial balance + bases; build `DrawdownTracker` with basis)
- `cli/Config.kt`, `cli/PerStrategyRisk.kt` (new keys/fields/accessors)
- `cli/daemon/StrategyHandle.kt` (global wiring), `app/LiveSession.kt` (per-strategy wiring)
- `backtest/PerformanceReport.kt`, `backtest/ReportBuilder.kt`, `backtest/EquityMetrics.kt`, `cli/ReportFormat.kt`

## Error handling / validation

- `*_pct` outside `(0, 100]` → config error.
- Unknown `total_dd_basis` / `daily_dd_basis` value → error listing valid values.
- `STATIC` basis with a non-positive initial balance → the tracker returns 0 DD (no spurious halt),
  matching `DrawdownTracker`'s existing "no history → 0" guard.
- Absent keys → rule simply not instantiated.

## Testing

Unit:
- `DrawdownTracker` — TRAILING vs STATIC on the same equity path (static halts earlier when never above start; trailing halts on peak-relative drop).
- `DailyDrawdownTracker` — UTC rollover resets the reference; BALANCE vs EQUITY references; DD math.
- `MaxDailyDrawdown` / `MaxStrategyDailyDrawdown` — Continue under limit, Halt (with/without strategyId) over.
- `Config` — parses new keys, per-strategy fields, `_pct`→fraction, basis enums, invalid values error.
- `DailyDrawdownAccumulator` — multi-day equity path → correct per-day DD + max.

Integration:
- Backtest `--json` emits `dailyPnL` (per-day buckets) + `maxDailyDrawdown` on a multi-day run.
- A synthetic equity path that breaches the total limit (static) and the daily limit halts in each case.

## Out of scope

- Gap #5 — live broker-equity sizing (separate issue; shared-gateway blast radius).
- Changing the backtest `maxDrawdown` **metric** (stays trailing peak-to-trough; only the live halt rule is basis-configurable).
- Per-strategy basis (basis is account-level; per-strategy only sets the pct thresholds).
