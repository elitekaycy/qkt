# Regime-Adaptive Portfolios — Implementation Plan

**Goal:** add regime-adaptive strategy stacking to QKT's `PORTFOLIO` DSL and book-risk layer, preserving backtest=live parity.

**Prerequisite design docs:**
- `docs/superpowers/specs/2026-08-04-regime-adaptive-portfolio-design.md`
- `docs/superpowers/specs/2026-06-19-portfolio-book-risk-design.md`
- `docs/superpowers/specs/2026-06-21-portfolio-backtest-design.md`
- `docs/superpowers/specs/2026-06-14-macro-series-data-path-design.md`

---

## Phase 0 — Regime-detection primitives

**Objective:** give the DSL enough indicators and math to express simple regime conditions.

- [x] Add `NORMALIZE` / `SOFTMAX` variadic helpers for probability-style weighting.
- [x] Verify `PERCENTILE_RANK`, `STDDEV`, `MOD`, `FLOOR`, `CEIL`, `ROUND`, `ABS`, `MIN`, `MAX` already exist and are usable.
- [ ] Document `MACRO:` point-in-time lag semantics for use in regime conditions.
- [ ] Add rolling-window `VOL_PERCENTILE` convenience indicator only if `percentile_rank(stddev(...), ...)` proves too verbose in practice.

**Acceptance:** A `.qkt` portfolio can express a binary regime condition such as `percentile_rank(stddev(btc.close,20),252) > 0.7 AND adx(btc,14) > 30`. All existing parity tests remain green.

---

## Phase 1 — Indicator-aware live gate

**Objective:** make the live portfolio `WHEN` evaluator indicator-aware by extracting a reusable `PortfolioGate`.

**Important context:** the current `PortfolioSupervisor` evaluates `WHEN` conditions with `EMPTY_SNAPSHOT_STORE` and `EMPTY_CANDLE_HUB`, so indicator calls in portfolio rules resolve to undefined today.

- [ ] Create `dsl/portfolio/PortfolioGate.kt`:
  - own a `CandleHub` for the portfolio's declared streams,
  - bind indicators from `WhenRun` conditions via `IndicatorBinding.Bag`,
  - evaluate `AlwaysRun` / `WhenRun` rules on closed candles,
  - return a `GateState` (active map + optional weights/regime).
- [ ] Refactor `cli/daemon/portfolio/PortfolioSupervisor.kt` to delegate rule evaluation to `PortfolioGate`.
- [ ] Add `PortfolioGateTest` covering:
  - `AlwaysRun` activates a child,
  - `WhenRun` with a simple candle-field condition toggles a child,
  - `WhenRun` with `adx(btc,14) > 30` toggles a child after warmup.
- [ ] Ensure no behavior change for portfolios that only use `AlwaysRun`.

**Acceptance:** Existing live portfolio tests green; new test proves an indicator-based `WHEN` rule correctly toggles a child.

---

## Phase 2 — Portfolio backtest unification with gates

**Objective:** make `qkt backtest <portfolio.qkt>` support `WHEN..RUN` gates using the same `PortfolioGate` as live.

- [ ] Create `backtest/GatedChild.kt` (or `dsl/portfolio/GatedChild.kt`) that wraps a `Strategy` and consults a shared `PortfolioGate`.
- [ ] Modify `BacktestContext.buildPortfolio`:
  - remove the `hasRegimeGates` rejection,
  - build a `PortfolioGate` from the portfolio AST,
  - wrap each child with `GatedChild(alias, gate, hold)`,
  - feed the gate via the engine's `CandleEvent`s.
- [ ] Ensure `GatedChild.flatten()` on deactivation matches live `ChildHandle.flatten()` semantics.
- [ ] Add `PortfolioBacktestLiveParityTest` with a `WHEN` gate: identical ticks → identical trades and per-child PnL.

**Acceptance:** `PortfolioBacktestLiveParityTest` passes; existing single-strategy parity tests stay green.

---

## Phase 3 — DSL `REGIMES` / `ALLOCATE` blocks

**Objective:** allow a portfolio file to declare discrete regimes and per-regime child weights.

- [ ] Extend `PortfolioAst` with `regimes: RegimeBlock?` and `allocate: AllocateBlock?`.
- [ ] Extend parser/lexer with keywords: `REGIMES`, `STATE`, `DEFAULT`, `ALLOCATE`, `METHOD`, `REBALANCE EVERY`.
- [ ] Add compile validation:
  - regime states are mutually exclusive or explicitly overlapping,
  - per-regime weights sum ≤ 1.0,
  - referenced children exist.
- [ ] Compile `REGIMES` blocks into `PortfolioGate` state logic and `ALLOCATE` blocks into `BookRiskController` input.
- [ ] Add `AllocationMethod.REGIME_WEIGHTED` to `BookRiskController`.

**Example target syntax:**

```qkt
REGIMES
    NAME vol_regime
    STATE high_vol WHEN percentile_rank(stddev(btc.close,20),252) > 0.7
    STATE low_vol  WHEN percentile_rank(stddev(btc.close,20),252) < 0.3
    STATE normal   DEFAULT

ALLOCATE
    METHOD regime_weighted
    REBALANCE EVERY 24h
    high_vol -> cash 1.0
    low_vol  -> trend 0.5, meanrev 0.5
    normal   -> trend 0.33, meanrev 0.33, breakout 0.33
```

**Acceptance:** `qkt parse` and `qkt backtest` accept the new syntax; invalid weight sums are rejected at compile time; regime-adaptive parity test (backtest vs live paper) passes.

---

## Phase 4 — Backtest fidelity harness

**Objective:** make it hard to ship an overfit regime-switching strategy.

- [ ] Extend `WalkForwardHarness` to accept portfolio files and emit per-fold regime-transition counts.
- [ ] Add overfitting diagnostics to backtest report bundle:
  - number of configurations tested,
  - Deflated Sharpe Ratio (DSR),
  - Probability of Backtest Overfitting (PBO) via combinatorial purged cross-validation where sample size allows,
  - Minimum Backtest Length (MinBTL).
- [ ] Add transition-cost attribution: PnL lost to turnover at regime boundaries.
- [ ] Add regime-stress cost multiplier configuration (slippage/impact scaled by vol regime).

**Acceptance:** `qkt walkforward <portfolio.qkt>` emits regime diagnostics and transition-cost attribution.

---

## Phase 5 — Live monitoring

**Objective:** detect live/backtest divergence after deployment.

- [ ] Emit regime events (`RegimeTransitionEvent`, `RegimeProbabilityEvent`) on the event bus.
- [ ] In `PortfolioSupervisor`, log predicted regime and realized post-transition PnL.
- [ ] Insights backend: compare predicted regime distribution vs realized volatility/correlation cluster.
- [ ] Add a paper-trading gate: require N days of shadow trading before allocator weights are applied to real capital.

**Acceptance:** A deployed regime portfolio logs predicted-vs-realized regime quality; operator can pause if divergence exceeds threshold.

---

## Cross-cutting concerns

- **Documentation:** update DSL reference and add an example portfolio under `examples/regime-adaptive/`.
- **Tests:** every new public class gets a focused JUnit 5 test; parity tests for each phase.
- **Performance:**
  - compile rules once,
  - update indicators once per candle close,
  - cache gate state,
  - avoid per-tick allocation,
  - use rolling-window online algorithms.
- **Default-off:** all new behavior is gated by syntax presence; existing portfolios run unchanged.

---

## Definition of done

- [ ] Phase 0–3 merged and green in CI.
- [ ] Regime-adaptive backtest/live parity test passes.
- [ ] Walk-forward harness supports regime portfolios with overfitting diagnostics.
- [ ] Example portfolio and docs are merged.
- [ ] No existing parity tests regress.
