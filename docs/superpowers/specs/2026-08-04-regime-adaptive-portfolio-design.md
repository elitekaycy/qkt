# Regime-Adaptive Portfolios — Research & Design Spec

**Status:** research / design  
**Goal:** extend QKT’s `PORTFOLIO` DSL and book-risk layer so a portfolio can detect market regimes and adapt which child strategies run and how capital is allocated among them, while preserving qkt’s core invariant: **backtest = live**.

---

## 1. What regime-adaptive portfolios are

A **regime-adaptive portfolio** conditions its strategy mix and capital allocation on the current estimated **market regime**, rather than holding a static multi-strategy book. A regime is a persistent state in which asset-return means, volatilities, correlations, liquidity, or risk premia behave differently — e.g. "low-volatility trending", "high-volatility stressed", "ranging", or "contraction".

The flow is:

1. **Observe** market and macro features.
2. **Infer** a regime label or probability distribution.
3. **Select / weight** child strategies that are expected to perform in that regime.
4. **Execute** the resulting orders, accounting for transition costs and risk limits.

This differs from:

- **Static multi-strategy:** fixed weights; no systematic response to conditions.
- **Risk parity:** equal risk contribution under one long-horizon covariance assumption; a regime overlay adds a dynamic layer on top.
- **Tactical asset allocation:** can use any signal, not necessarily a discrete regime model.

Institutional examples include CTA trend overlays, risk-parity recession overlays, multi-strategy hedge-fund capital deployment, and macro regime rotation.

---

## 2. Why this fits QKT now

QKT already has most of the seams a regime-adaptive allocator needs:

| Existing seam | How it maps to regime adaptation |
|---|---|
| `PORTFOLIO` DSL + `PortfolioAst` (`dsl/ast/Portfolio.kt`) | Natural place to declare regimes and adaptive rules. |
| `PortfolioSupervisor` + `ChildHandle.gateActive` (`cli/daemon/portfolio/PortfolioSupervisor.kt`, `ChildHandle.kt`) | Already toggles children on/off via `WHEN … RUN`; can be driven by a regime detector. |
| `BookRiskController` + `BookRiskConfig` (`risk/book/BookRiskController.kt`) | Already supports `FIXED`, `INVERSE_VOL`, `ERC`, vol targeting, and a de-risk ladder; can add regime-aware allocation as a new method. |
| `TradingPipeline.bookScaleFor` (`app/TradingPipeline.kt`) | Applies a per-strategy scale to sized orders in both backtest and live — the parity-safe seam for adaptive weights. |
| `IndicatorRegistry` / `FuncRegistry` (`dsl/stdlib/IndicatorRegistry.kt`, `FuncRegistry.kt`) | Can register regime-detection primitives (HMM, changepoint, vol percentile, macro overlays) and math helpers (softmax, normalization). |
| `CandleHub` (`dsl/compile/CandleHub.kt`) | Provides aligned multi-stream history; a regime detector can observe the same candles as strategies. |
| `MACRO:` series feed (`marketdata/source/MacroMarketSource.kt`) | Already supports point-in-time daily macro data; ideal for macro regime inputs. |
| Portfolio backtest unification (`docs/superpowers/specs/2026-06-21-portfolio-backtest-design.md`) | Children run as N attributed strategies on one `ReplayEngine`; a shared allocator sees the same book snapshot in both modes. |

The main gap is not plumbing — it is **backtestable semantics**: the regime detector, allocator, and transition logic must run identically in replay and live, with no look-ahead, no same-bar fills, and online-only parameter updates.

---

## 3. Regime taxonomy

| Dimension | What changes | Typical features / detectors |
|---|---|---|
| **Volatility** | Risk levels, tail size | Realized vol, VIX, ATR, GARCH/EGARCH, vol-of-vol, `PERCENTILE_RANK(stddev, lookback)` |
| **Trend / momentum** | Directional opportunity | MA crossovers, time-series momentum, ADX, breakouts, Hurst exponent |
| **Correlation / diversification** | Hedge effectiveness | Rolling pairwise correlation, DCC eigenvalue concentration, average correlation |
| **Liquidity** | Execution cost, depth | Bid-ask spread, Amihud illiquidity, turnover, market depth |
| **Macro / economic cycle** | Broad risk appetite | Inflation, unemployment, PMI, yield-curve slope, recession probability models |
| **Funding / credit stress** | Cost of leverage | TED/LIBOR-OIS spread, credit spreads, repo rates |
| **Cross-sectional dispersion** | Alpha opportunity | Dispersion of factor/sector returns, idiosyncratic vol |
| **Tail risk** | Crash probability | Cross-sectional crash frequency, VaR/CVaR breaches, skewness/kurtosis |

A useful QKT regime is **composable**: a user can build a detector from any combination of these dimensions using the DSL, rather than being locked into a single model.

---

## 4. Regime detection methods

| Method | Summary | Strengths | Weaknesses / cost | QKT fit |
|---|---|---|---|---|
| **Hidden Markov Model (HMM)** | Latent discrete state governs return distribution; filtered probabilities updated recursively. | Probabilistic; natural forecast; handles persistence. | Fixed state count; parametric; estimation can be slow/ill-conditioned; path-dependent. | Add as a rolling-window indicator in `IndicatorRegistry`; emit state probabilities. |
| **Changepoint detection (CUSUM, BOCPD)** | CUSUM flags mean/variance shifts; Bayesian online changepoint detection maintains a run-length posterior. | Fast, online, model-agnostic; BOCPD gives full uncertainty. | CUSUM needs thresholds; BOCPD is more CPU-intensive; noisy in volatile markets. | CUSUM is a simple streaming indicator; BOCPD can be an opt-in heavy indicator. |
| **Clustering (K-means, GMM, HDBSCAN)** | Group historical periods by feature similarity; assign current period to nearest cluster. | Flexible, non-parametric, multi-feature. | Look-ahead risk if clustering uses future data; unstable clusters; scaling matters. | Restrict to rolling/expanding windows only; useful offline for regime discovery. |
| **Macro overlays** | Recession/expansion models from macro data (e.g. random forest on FRED-MD). | Intuitive, economically grounded. | Lagged, noisy, revised data; false signals. | Use `MACRO:` point-in-time series as inputs to a DSL rule or indicator. |
| **Volatility targeting** | Scale exposure inversely to recent volatility. | Simple, robust, widely used. | Procyclical; lags spikes; ignores correlations. | Already supported in `BookRiskController` (`INVERSE_VOL`); can be regime-triggered. |
| **Trend-following filters** | MA crossovers or momentum define bull/bear/neutral. | Empirically strong; simple. | Whipsaws; lag; poor in choppy markets. | Expressible today with DSL indicators; can be wrapped as a regime signal. |

Design principle for QKT: **expose primitives, not a single regime model**. A user should be able to write:

```qkt
LET calm = percentile_rank(stddev(btc.close, 20), 252) < 0.3
LET trending = adx(btc.close, 14) > 30
REGIME trend_calm WHEN calm AND trending
```

or plug in a heavy HMM indicator if they choose.

---

## 5. Allocation / switching methods given a regime

| Method | How it works | Cost / constraint interaction |
|---|---|---|
| **Binary on/off** | Child is fully active or flat based on a regime condition. | Simple; abrupt switches generate transaction costs and slippage. This is what `PortfolioSupervisor` already does with `WHEN … RUN`. |
| **Probability-weighted** | Blend children using filtered regime probabilities. | Smoother than binary; naturally uses HMM outputs; still needs turnover control. |
| **Risk-budgeting / risk parity** | Set risk contributions from regime-conditional covariance. | Handles leverage and concentration constraints; needs turnover control and stable covariance estimates. |
| **CPPI / drawdown control** | Maintain a floor; allocate multiplier × cushion to risky strategies. | Explicit capital protection; gap risk and costs matter. |
| **Kelly / growth-optimal** | Size to maximize expected log wealth given regime moments. | Aggressive; estimation-error sensitive; usually constrained. |
| **Ensemble / voting** | Multiple detectors vote; allocate by consensus. | Diversifies detection error but adds complexity. |
| **Meta-labeling / meta-learning** | Classifier predicts which child will outperform; switches accordingly. | Flexible; needs careful train/test separation and cost accounting. |
| **RL-based allocation** | Agent learns a policy from regime observations. | Can incorporate costs in reward; sample-inefficient and hard to interpret. |

For QKT, the cleanest first step is **binary on/off + probability-weighted scaling**, because it maps directly to the existing `gateActive` and `bookScaleFor` seams. Risk-budgeting by regime is a natural follow-up inside `BookRiskController`.

---

## 6. DSL design options

### Current reality check: portfolio `WHEN` rules are not indicator-aware today

The live `PortfolioSupervisor` (`cli/daemon/portfolio/PortfolioSupervisor.kt`) evaluates `WHEN` conditions with an `EvalContext` that has `snapshotStore = EMPTY_SNAPSHOT_STORE` and `hub = EMPTY_CANDLE_HUB`. It compiles the expression but does **not** bind indicators to a `CandleHub`. A rule like `WHEN adx(btc,14) > 30 RUN trend` therefore resolves `adx(...)` to undefined today. The existing tests only cover `AlwaysRun` rules.

This means a regime-adaptive portfolio implementation must first make the live gate indicator-aware, then reuse that exact evaluator in backtest. The architecture below does exactly that.

### Option A — Extend the `PORTFOLIO` DSL with `REGIMES` / `ALLOCATE` blocks (recommended)

Add first-class regime declarations to `PortfolioAst` and the parser. The runtime compiles them into a `PortfolioGate` that owns its own `CandleHub` and indicator bindings, so `WHEN` rules can use any DSL indicator.

```qkt
PORTFOLIO btc_regimes VERSION 1 CAPITAL 100000
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h WARMUP 200 BARS
IMPORT 'trend.qkt'    AS trend
IMPORT 'meanrev.qkt'  AS meanrev
IMPORT 'breakout.qkt' AS breakout

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

RULES
    RUN trend    HOLD
    RUN meanrev  HOLD
    RUN breakout HOLD
```

Pros: discoverable, versioned, parser-validated, full indicator support, maps cleanly to `PortfolioGate` + `BookRiskController`.  
Cons: touches parser, AST, compiler, loader, and live supervisor.

### Option B — Strategy-level regime detection via DSL indicators

Keep the portfolio syntax minimal; express regimes inside child strategies using indicators. The portfolio still allocates capital, but each child decides whether to take exposure.

```qkt
STRATEGY adaptive_trend VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1h
PARAMS { vol_window = 252, adx_len = 14 }
DEFAULTS { SIZING = 1 PCT RISK }

LET regime = hmm_state(btc.close, 3, 60)   # 3-state HMM, 60-bar rolling window

WHEN regime = 0 AND close CROSSES ABOVE ema(close, 50)
THEN BUY btc

WHEN regime != 0
THEN FLAT btc
```

Pros: no portfolio grammar changes; composable with existing rules; works today.  
Cons: each child decides independently; no book-level coordination; harder to enforce global turnover limits.

### Option C — Meta-strategy inside the engine

Implement a `RegimeSwitchingStrategy : DslCompiledStrategy` that observes the portfolio’s streams, maintains regime state and weight tables, and uses the event bus to influence a shared allocator.

Pros: keeps grammar small; powerful for complex allocators.  
Cons: less discoverable; needs careful design to avoid breaking event-bus determinism.

### Option D — Extend `BookRiskController` with regime-aware allocation

Add a new `AllocationMethod` (e.g. `REGIME_WEIGHTED`) to the existing book-risk config. The controller receives a regime label and selects a per-regime target weight table.

```yaml
book_risk:
  allocation:
    method: REGIME_WEIGHTED
    default_weights: { book:trend: 0.33, book:meanrev: 0.33, book:breakout: 0.33 }
    regimes:
      - name: strong_trend
        detector: portfolio.trend_regime
        weights: { book:trend: 0.6, book:breakout: 0.4, book:meanrev: 0.0 }
```

Pros: smallest engineering change; reuses existing parity-safe `scaleFor` seam.  
Cons: less expressive than a full DSL block; regime definitions live outside `.qkt` files.

### Recommended approach

A **layered combination** designed for long-term parity and flexibility:

1. **Option A** for declaring regimes and target allocations in `.qkt`. The `PortfolioGate` component compiles `WHEN` rules with full indicator binding, so the same expression works live and in backtest.
2. **Option D** (`BookRiskController`) for applying weights through the existing `bookScaleFor` seam. The gate's output is just another input to the controller.
3. **Option B** remains available for strategy-local overrides and for researchers who want to experiment before promoting a regime model to the portfolio level.

This ensures every adaptive decision flows through one deterministic evaluator (`PortfolioGate`) and one deterministic allocator (`BookRiskController`), both of which run the same code in backtest and live.

---

## 7. Backtesting accuracy requirements

Regime-switching backtests are especially easy to overfit and lookahead. The runtime must enforce the following by design:

### 7.1 Online-only parameter estimation

Regime models (HMM, clustering, covariance estimation) must be re-estimated each period using only data available at the simulated timestamp. No full-sample fits. QKT should:

- Provide **rolling-window** and **expanding-window** indicators that reject future data.
- Log the training window used at each re-estimation event.
- Refuse to run an HMM/clustering indicator whose window is not bounded.

### 7.2 Point-in-time data

- Macro inputs must use `MACRO:` with the fixed-lag / vintage-aware feed already specified in `docs/superpowers/specs/2026-06-14-macro-series-data-path-design.md`.
- Any series that is revised must be read through the as-of timestamp, not the final revised value.

### 7.3 No same-bar fills on regime transitions

If a regime is inferred from the close of bar `t`, the resulting allocation change may influence decisions only from bar `t+1` onward. Fills at the close of bar `t` are look-ahead. The engine already resolves fills on ticks after the signal; the regime layer must not bypass this.

### 7.4 Explicit latency and transition cost

- Regime-driven rebalances should use the same order pipeline as normal signals, so they inherit `MT5BrokerSimulator` slippage, spread, and partial-fill models.
- The backtest report should expose transition cost attribution: how much PnL was lost to turnover at regime boundaries.
- Consider a **regime-stress cost multiplier**: during high-vol regime transitions, multiply slippage/impact estimates by a configurable factor.

### 7.5 Walk-forward and overfitting diagnostics

- `walkforward <portfolio>` should be the default research path, not a single in-sample backtest.
- The report should emit:
  - Number of independent trials / configurations tested (deflated Sharpe inputs).
  - Deflated Sharpe Ratio (DSR).
  - Probability of Backtest Overfitting (PBO) via combinatorial purged cross-validation where feasible.
  - Minimum Backtest Length (MinBTL).
- Hyperparameters (number of regimes, lookbacks, thresholds) must be chosen inside the walk-forward loop, not on the whole sample.

### 7.6 Paper-trading gate

After a regime-adaptive portfolio passes backtests, require a shadow/paper period where realized regime probabilities and PnL are compared against predictions before live capital is deployed.

---

## 8. Architecture proposal

The centrepiece is a single, indicator-aware **`PortfolioGate`** that compiles and evaluates portfolio `WHEN`/`ALWAYS` rules. It runs the same code in live and backtest; only the candle feed adapter changes.

```text
Live market source              Engine / ReplayEngine
        |                              |
        | ticks                        | ticks
        v                              v
  CandleAggregator              CandleAggregator
        |                              |
        | CandleEvent                  | CandleEvent
        v                              v
  PortfolioGate <================> PortfolioGate
  (same class, live feed)         (same class, backtest feed)
        |
        | GateState { active, weights, regime, transitions }
        v
  +---------------------+    +---------------------+
  | PortfolioSupervisor |    |     GatedChild      |
  | (live: toggles      |    |   (backtest: wraps  |
  |  ChildHandle.gate)  |    |    each child)      |
  +---------------------+    +---------------------+
        |                              |
        |                              |
        +--------------+---------------+
                       |
              BookRiskController
        (REGIME_WEIGHTED allocation
         + de-risk ladder + limits)
                       |
              TradingPipeline
           (gate() × bookScaleFor)
                       |
                 OrderManager
                       |
                    Broker
```

### PortfolioGate — the shared evaluator

```kotlin
package com.qkt.dsl.portfolio

class PortfolioGate(
    private val ast: PortfolioAst,
    private val clock: Clock,
    private val calendar: TradingCalendar,
) {
    private val hub = CandleHub()
    private val bindingBag = IndicatorBinding.Bag()
    private val whenRules: List<Pair<WhenRun, CompiledExpr>>
    private val streamMap: Map<String, HubKey>
    private var lastState: GateState = GateState.empty()

    /** Call once before evaluation starts. Registers streams and binds indicators. */
    fun prepare()

    /** Advance on a closed candle, update indicators, evaluate rules, return the new state. */
    fun onCandle(candle: Candle): GateState

    /** For tick-fed indicators (e.g. VWAP), called on every raw tick. */
    fun onTick(tick: Tick)

    data class GateState(
        val activeByAlias: Map<String, Boolean>,
        val weightByAlias: Map<String, BigDecimal>,
        val regimeName: String?,
        val changed: Boolean,
    )
}
```

Design points:

- **Own `CandleHub`:** The gate maintains its own aggregators for the portfolio's declared streams. In live this is fed by the supervisor's market source; in backtest it is fed by subscribing to the engine's `CandleEvent`s. Duplicating aggregation is bounded, deterministic, and keeps the gate independent of child-strategy hub lifecycle.
- **Indicator binding:** `PortfolioGate` discovers all `IndicatorCall`s inside `WhenRun` conditions and binds them via `IndicatorBinding.Bag`. On each closed candle it builds an `EvalContext` (with the hub and a snapshot store) and calls `bindingBag.updateAll(ctx)` before evaluating rules.
- **Online-only:** All indicators used in `WHEN` rules must be rolling-window / online. The gate never sees future data.
- **Deterministic output:** `GateState` is a pure function of the candle stream and the compiled rules.

### Mode-specific feed adapters

- **Live:** `PortfolioSupervisor` owns a `PortfolioGate`. Its existing tick loop feeds ticks into `gate.onTick(tick)` and closed candles into `gate.onCandle(candle)`. The supervisor reads `GateState` and calls `applyDesired(state.activeByAlias)`.
- **Backtest:** `BacktestContext.buildPortfolio` creates a `PortfolioGate` and subscribes to the engine's `CandleEvent` bus. Each child strategy is wrapped in a `GatedChild` that reads the latest `GateState`.

### GatedChild wrapper (backtest)

```kotlin
class GatedChild(
    private val inner: Strategy,
    private val alias: String,
    private val gate: PortfolioGate,
    private val hold: Boolean,
) : Strategy {
    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        if (gate.currentState().activeByAlias[alias] != false) {
            inner.onTick(tick, ctx, emit)
        }
    }

    override fun onCandle(candle: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val prior = gate.currentState()
        val next = gate.onCandle(candle)
        if (next.activeByAlias[alias] == false && prior.activeByAlias[alias] == true && !hold) {
            flatten(ctx)
        }
        if (next.activeByAlias[alias] != false) {
            inner.onCandle(candle, ctx, emit)
        }
    }
}
```

The flatten-on-deactivation must match live's `ChildHandle.flatten()` semantics.

### Live supervisor refactor

`PortfolioSupervisor` delegates rule evaluation to `PortfolioGate`:

- `applyAlwaysRunRules()` → `PortfolioGate` returns active state for `AlwaysRun` aliases.
- `onCandle()` → call `gate.onCandle(candle)` and `applyDesired(state.activeByAlias)`.
- Indicator binding and expression compilation move out of the supervisor into the gate.

### BookRiskController integration

`BookRiskController` gains an `AllocationMethod.REGIME_WEIGHTED`. The controller receives `GateState` (or just the active/weight map) and uses it to compute `allocationWeight(strategyId)`. Combined with the existing de-risk ladder:

```kotlin
scaleFor(strategyId) = deRiskFactor * allocationWeight(strategyId)
```

In backtest, the same `BookRiskController` instance is shared by all children in the single `ReplayEngine`. In live, one shared controller aggregates child snapshots.

### Components to add / extend

1. **`dsl/ast/Portfolio.kt`** — add `REGIMES` and `ALLOCATE` blocks to `PortfolioAst`.
2. **`dsl/parse/Parser.kt`, `Lexer.kt`, `TokenKind.kt`** — grammar for `REGIMES`, `STATE`, `DEFAULT`, `ALLOCATE`, `METHOD`, `REBALANCE EVERY`.
3. **`dsl/portfolio/PortfolioLoader.kt`** — compile regime blocks; validate weight sums per regime.
4. **`dsl/portfolio/PortfolioCompiled.kt`** — carry compiled `PortfolioGate` config.
5. **`dsl/portfolio/PortfolioGate.kt`** (new) — indicator-aware evaluator, reusable live/backtest.
6. **`risk/book/BookRiskController.kt`** — add `REGIME_WEIGHTED` allocation method.
7. **`cli/daemon/portfolio/PortfolioSupervisor.kt`** — refactor to use `PortfolioGate`; remove inline expression compilation.
8. **`cli/BacktestContext.kt`** — remove `hasRegimeGates` rejection; wrap children with `GatedChild`.
9. **`dsl/stdlib/IndicatorRegistry.kt` / `indicators/catalog/`** — add rolling-window regime indicators (`HMMState`, `CUSUMRegime`, `VolPercentileRegime`, `MacroRegime`) as needed.
10. **`backtest/walkforward/`** — add regime-aware fold diagnostics and PBO/DSR reporting.

### Key invariants

- **One writer per derived quantity:** `PortfolioGate` owns regime state; `BookRiskController` owns weights; no other component recomputes them.
- **Backtest = live:** `PortfolioGate` and `BookRiskController` run the same code in both modes. Mode-specific pieces are only the candle feed adapter and `BookStateSource`, both of which produce the same deterministic inputs.
- **Deterministic:** identical inputs (clock, feeds, sequence generator, config, compiled rules) → identical `GateState` and trades.
- **No look-ahead:** every indicator used by the gate is rolling-window; macro data uses point-in-time lags; fills occur on the bar after the regime-inference bar.
- **Hot-path efficiency:** rules are compiled once; indicators update once per candle close; gate state is cached and only recalculated on candle close; allocations are constant between rebalances.

---

## 9. Phased build plan

The plan is reordered to first make the **live** gate indicator-aware, because backtest parity is meaningless unless the live evaluator is correct.

| Phase | Deliverable | Files / components | Parity gate |
|---|---|---|---|
| **0. Foundation** | Regime-detection primitives in DSL | `FuncRegistry`: `SOFTMAX`, `NORMALIZE`; verify `PERCENTILE_RANK`, `STDDEV`, `MOD`, etc. already exist; `MACRO:` feed documented. | Existing parity tests green. |
| **1. Indicator-aware live gate** | `PortfolioGate` extracts and replaces inline supervisor evaluation; live `WHEN` rules can use indicators | New `dsl/portfolio/PortfolioGate.kt`; refactor `PortfolioSupervisor.kt`; add `PortfolioGateTest`. | Existing live portfolio tests green; new test proves `adx(btc,14) > 30` toggles a child. |
| **2. Portfolio backtest unification** | `qkt backtest <portfolio.qkt>` supports `WHEN..RUN` gates via the same `PortfolioGate` | `BacktestContext.buildPortfolio` removes rejection; new `GatedChild`; `PortfolioAttribution`. | `PortfolioBacktestLiveParityTest` with `WHEN` gate. |
| **3. DSL `REGIMES` / `ALLOCATE` blocks** | First-class regime syntax compiled into `PortfolioGate` + `BookRiskController` | `PortfolioAst`, parser, `PortfolioLoader`, `PortfolioCompiled`, `BookRiskController.REGIME_WEIGHTED`. | Regime-adaptive parity test (backtest vs live paper). |
| **4. Backtest fidelity harness** | Walk-forward + overfitting diagnostics | Extend `WalkForwardHarness`; add DSR/PBO/MinBTL; transition-cost attribution. | Walk-forward unit tests on synthetic regimes. |
| **5. Live monitoring** | Realized-vs-predicted regime tracking | `PortfolioSupervisor` emits regime events; insights backend compares predicted vs realized. | Shadow/paper period validation. |

Each phase is additive and default-off until explicitly enabled, matching the `BookRiskController` pattern. Phase 1 does **not** change any child-strategy behavior when a portfolio has no `WHEN` rules.

---

## 10. Risks and open questions

| Risk | Mitigation |
|---|---|
| **Current live `PortfolioSupervisor` does not bind indicators for `WHEN` rules** | Phase 1 extracts `PortfolioGate` and binds indicators to a `CandleHub` before any backtest parity work. |
| **Overfitting from too many regime specifications** | Require walk-forward by default; report DSR/PBO/MinBTL; encourage pre-registration of regime hypotheses in the portfolio file. |
| **Look-ahead via full-sample HMM** | Enforce rolling/expanding windows; fail closed if an indicator cannot prove it is online. |
| **Same-bar fills at regime boundaries** | Document and enforce that regime-inferred allocation changes take effect on the next bar/tick, not the triggering close. |
| **Transition costs destroying paper gains** | Add regime-stress cost multiplier and transition-cost attribution in reports. |
| **Macro data revisions** | Use `MACRO:` point-in-time feed; vintage-exact mode deferred but architected for. |
| **Live/backtest divergence** | Add regime-probability tracking and shadow/paper gate before real capital. |
| **Complexity explosion** | Start with binary on/off + static per-regime weights; add probabilistic / risk-budgeting allocators only after parity is proven. |

### Open questions

1. Should `REGIMES` be allowed inside a `STRATEGY` file, or only inside a `PORTFOLIO`? (Leaning: portfolio-level for coordination, but strategy-level indicators for local logic.)
2. Should the runtime allow **overlapping regimes** (probabilistic) or only **mutually exclusive states**? (Leaning: support both; `STATE … DEFAULT` for exclusive, `REGIME PROBABILITIES` for overlapping.)
3. How should rebalancing cadence interact with the existing `BookRiskController` rebalance? (Leaning: regime changes are events; allocator rebalances on regime events + periodic rebalance boundary.)
4. Should the DSL expose **train/test windows** directly, or leave that to `qkt walkforward`? (Leaning: walkforward CLI owns it; DSL declares the model, not the validation.)

---

## 11. References

- Ang & Timmermann, “Regime Changes and Financial Markets,” *Annual Review of Financial Economics* 4:313-337 (2012) — https://www.nber.org/papers/w17182
- Kritzman, Page & Turkington, “Regime Shifts: Implications for Dynamic Strategies,” *Financial Analysts Journal* 68(3), 22-39 (2012) — https://rpc.cfainstitute.org/research/financial-analysts-journal/2012/regime-shifts-implications-for-dynamic-strategies-corrected
- Oliveira et al., “Tactical Asset Allocation with Macroeconomic Regime Detection,” arXiv:2503.11499 (2025) — https://arxiv.org/abs/2503.11499
- Verma, Putri & Lesupi, “Regime-Based Portfolio Allocation Using Hidden Markov Models and Reinforcement Learning,” arXiv:2605.27848 (2026) — https://arxiv.org/abs/2605.27848
- Kelly & Jiang, “Tail Risk and Asset Prices,” *Review of Financial Studies* 27(10), 2841-2871 (2014) — https://www.nber.org/papers/w19375
- Harvey et al., “The Impact of Volatility Targeting,” *Journal of Portfolio Management* 45(1) (2018) — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=3175538
- Hurst, Ooi & Pedersen, “A Century of Evidence on Trend-Following Investing,” *Journal of Portfolio Management* 44(1) (2017) — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2993026
- Wood, Roberts & Zohren, “Slow Momentum with Fast Reversion,” arXiv:2105.13727 (2021) — https://arxiv.org/abs/2105.13727
- Adams & MacKay, “Bayesian Online Changepoint Detection,” arXiv:0710.3742 (2007) — https://arxiv.org/abs/0710.3742
- Bailey et al., “Pseudo-Mathematics and Financial Charlatanism: The Effects of Backtest Overfitting,” SSRN 2308659 (2014) — https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2308659
- Bailey & López de Prado, “The Deflated Sharpe Ratio,” SSRN 2460551 (2014) — https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf
- Bailey et al., “The Probability of Backtest Overfitting,” *Journal of Computational Finance* (2017) — https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf
- ALFRED point-in-time macro data — https://alfred.stlouisfed.org/
- Philadelphia Fed Real-Time Data Research Center — https://www.philadelphiafed.org/surveys-and-data/real-time-data-research
- QKT: Portfolio Book-Risk Layer — `docs/superpowers/specs/2026-06-19-portfolio-book-risk-design.md`
- QKT: Portfolio Backtest Unification — `docs/superpowers/specs/2026-06-21-portfolio-backtest-design.md`
- QKT: Macro Series Data Path — `docs/superpowers/specs/2026-06-14-macro-series-data-path-design.md`
