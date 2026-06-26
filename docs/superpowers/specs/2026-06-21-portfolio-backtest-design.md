# Portfolio Backtest — Design Spec

**Goal:** Make `qkt backtest <portfolio.qkt>` co-simulate a *book* of strategies on shared
historical data with backtest=live parity, emitting combined (book-level) metrics plus per-child
attribution, so an external research pipeline (qkt-forge) can validate a portfolio's combined
risk-adjusted performance and read each child's return stream.

**Scope:** Part 2 (v1) of the portfolio-gate program. v1 = co-simulation + per-child metrics +
a backtest=live parity test. Correlation / HRP / strategy-selection live in qkt-forge (Part 3),
computed from the per-child `dailyPnL` this emits. `walkforward <portfolio>` is a fast-follow.

---

## Background and constraint

qkt 0.47.0 has a full portfolio DSL (`PORTFOLIO / IMPORT / RUN [WEIGHT] [OVERRIDE] / CAPITAL`),
an AST (`PortfolioAst`), and a loader (`PortfolioLoader.load → PortfolioCompiled`), but portfolios
run **only in the live daemon** (`deploy`). `backtest` / `sweep` / `walkforward` accept a
`StrategyAst` only (`BacktestCommand.kt:23` calls the strategy-only `Dsl.parseFile`). The
backtest-side portfolio runtime `app/PortfolioStrategy.kt` exists with a test but is **not wired to
any command**, and is explicitly a *"v1 simplification: a single strategyId for the whole portfolio
(children share PnL / positions)"* (`PortfolioStrategy.kt:25-27`) — so it gates children but
produces **no per-child attribution**.

### Existing machinery this design reuses (no rebuild needed)

- **Multi-strategy co-simulation already exists.** `Backtest.fromStore(strategies = List<(name,
  Strategy)>, …)` runs a *list* of strategies on one shared account; `ReplayEngine` keeps a global
  account plus per-strategy PnL and per-strategy reports (`ReplayEngine.kt:95-116`, `:314-334`).
- **Per-strategy capital is already modeled.** `StrategyPnL.setStartingBalance(id, bal)`,
  `equityFor(id)`, `balanceFor(id)` (`StrategyPnL.kt:40-113`). `ReplayEngine` already calls
  `setStartingBalance(id, startingBalance)` per strategy (`:116`) — today every id gets the same
  value.
- **Sizing already reads per-strategy equity.** `SizePctEquity` / `SizeRiskFrac` / `SizePctBalance`
  size off `ec.strategyContext.pnl.equity()` / `.balance()` (`SizingCompiler.kt:65-96`), which is the
  *per-strategy* view (`RiskView.kt:51` → `equityTracker.currentEquityFor(strategyId)`).
- **Live per-child allocation is a map.** `PortfolioDeployer` computes `capitalAllocations(ast)` =
  `WEIGHT × CAPITAL` per child and passes `LiveSession(startingBalances = {childId: allocation})`
  (`PortfolioDeployer.kt:77-80`, `:241-243`; `PortfolioAllocation.kt`).
- **Risk halts are already config-driven and parity-shaped.** `BacktestContext.build` builds
  `HaltRules.standard(…, startingBalance = basis)` from `qkt.config.yaml` — *"the same
  config-driven halt construction the live daemon uses, so a strategy that would halt live halts at
  the same point in its backtest"* (`BacktestContext.kt:220-234`).
- **Parity is already tested for single strategies.** `parity/BacktestLiveParityTest` feeds identical
  ticks to backtest and live-paper and asserts identical trades.

---

## Architecture

Mirror the **live** model exactly: **each child is a separate strategy in the `ReplayEngine` with its
own capital allocation, gated on/off by the portfolio's `WHEN`/`ALWAYS` rules** — the backtest analog
of the live `PortfolioSupervisor` toggling each child's `gateActive`. Because the engine already
sizes each strategy against its own `equityFor(id)`, giving each child `startingBalance =
WEIGHT × CAPITAL` reproduces live sizing trade-for-trade.

```
qkt backtest book.qkt
  BacktestCommand:
    Dsl.parseFileAny(path)                       // was parseFile (strategy-only)
      StrategyFile  -> existing single-strategy path (unchanged)
      PortfolioFile -> BacktestContext.buildPortfolio(args, PortfolioLoader.load(path))

  buildPortfolio:
    symbols           = union(portfolio.streams + every child.streams)
    provisionStreams  = union of child provision streams
    bookCapital       = ast.capital ?: --starting-balance
    allocations       = capitalAllocations(ast)          // childId -> WEIGHT*CAPITAL (empty if no WEIGHT)
    haltRules         = HaltRules.standard(..., basis = bookCapital)   // book-level halt parity

  backtest():
    strategies        = children, each registered under its own strategyId, wrapped so its
                        onTick/onCandle is a no-op while the portfolio rule gating it is inactive
                        (and it flattens on deactivation unless IMPORT ... HOLD)
    startingBalances  = allocations (per child)           // NEW map param, default = book/equal
    gate              = portfolio WHEN/ALWAYS evaluator (extracted from PortfolioStrategy)
    Backtest.fromStore(strategies, startingBalances, haltRules, symbols, ...)
    -> ReplayEngine co-sims; child sizes against equityFor(childId) = its own allocation

  result.portfolio = { book-level metrics (global), per-child attribution (perStrategy + weight) }
  ReportPrinter adds a "portfolio" block to the JSON.
```

### The gate layer

`PortfolioStrategy` already contains the gate logic — `computeActiveChildren` (evaluate each rule's
`WHEN` condition) and `applyTransitions` (cancel pending + flatten non-HOLD children on
deactivation) (`PortfolioStrategy.kt:132-187`). v1's single-id model is the part we drop. Extract the
gate evaluation into a reusable `PortfolioGate` that toggles a per-child `active` flag, so each child
can stay a **separate** registered strategy (for attribution + per-child capital) while still being
gated. A thin `GatedChild` wrapper around each child consults its flag before delegating to the inner
strategy and performs the flatten-on-deactivation. The parity test is the guard that this reproduces
live gating exactly.

---

## Components

**New**
- `cli/PortfolioBacktestContext.kt` (or a `buildPortfolio` companion on `BacktestContext`): portfolio
  build path — load, union symbols/provision, allocations, book halt basis.
- `app/PortfolioGate.kt` + `app/GatedChild.kt`: extract gate evaluation from `PortfolioStrategy`;
  wrap each child so it is gated yet registered as its own strategy.
- `backtest/PortfolioAttribution.kt`: `PortfolioAttribution(name, capital, children:
  List<ChildAttribution>)`, `ChildAttribution(alias, strategyId, weight, allocatedCapital, report)`.
- `parity/PortfolioBacktestLiveParityTest.kt`.

**Modify**
- `cli/BacktestCommand.kt` — `parseFileAny` + branch StrategyFile vs PortfolioFile (`:22-30`, `:52-54`).
- `cli/BacktestContext.kt` — portfolio-aware build (symbols/provision/candleWindow from children),
  per-strategy `startingBalances`, attach `PortfolioGate` (`:43-86`, `:110-250`).
- `backtest/Backtest.kt` + `research/ReplayEngine.kt` — accept `startingBalances: Map<String,
  BigDecimal>` (default: every id = global `startingBalance`, preserving current behavior)
  (`ReplayEngine.kt:67`, `:116`).
- `backtest/BacktestResult.kt` — add `portfolioAttribution: PortfolioAttribution?`.
- `cli/ReportFormat.kt` — emit the `portfolio` JSON block.

**Reuse unchanged**
- `dsl/portfolio/PortfolioLoader.kt`, `PortfolioCompiled.kt`, `PortfolioAllocation.capitalAllocations`,
  `pnl/StrategyPnL.kt`, the per-strategy report builders, `risk/HaltRules`.

---

## Capital model (per-child parity — the crux)

| | Live (`deploy`) | Backtest (this design) |
|---|---|---|
| Child isolation | separate `LiveSession` per child | separate strategyId per child in one `ReplayEngine` |
| Child capital | `startingBalances[childId] = WEIGHT×CAPITAL` | `setStartingBalance(childId, WEIGHT×CAPITAL)` |
| Sizing basis | child's own equity | `equityFor(childId)` — already per-strategy |
| Book equity basis | book `CAPITAL` | global account, `startingBalance = CAPITAL` (≥ Σ allocations) |
| Book risk halt | `PortfolioRiskAggregator` on book equity | `HaltRules.standard(basis = CAPITAL)` on the global account |

The **only** engine addition for capital parity is letting `Backtest.fromStore` accept a
`startingBalances` map (mirroring `LiveSession`, which already takes one). Same per-child equity →
same `SizingCompiler` output → same orders → trade-for-trade parity.

When a portfolio declares no `WEIGHT` (allocations empty), every child starts at the book capital
(equal footing) — matching live's behavior for unweighted books.

---

## Risk-halt parity

Book-level halts reuse `HaltRules.standard` from `qkt.config.yaml` with basis = book `CAPITAL`, the
identical construction `BacktestContext.build` already uses and the live daemon uses. A book that
breaches a drawdown / daily-loss limit flattens and halts all children at the same tick in backtest
and live. (Per-strategy halts continue to operate per child via the existing per-strategy
`RiskView`.)

---

## Output — `portfolio` JSON block

Alongside the existing global fields (which become the **book-level** combined metrics):

```json
"portfolio": {
  "name": "book",
  "capital": "100000",
  "children": [
    {
      "alias": "trend", "strategyId": "book:trend",
      "weight": "0.6", "allocatedCapital": "60000",
      "trades": 412, "totalPnL": "...", "sharpeRatio": "...",
      "maxDrawdown": "...", "winRate": "...",
      "dailyPnL": { "2021-01-04": "...", "...": "..." }
    },
    { "alias": "meanrev", "weight": "0.4", "...": "..." }
  ]
}
```

Per-child reports already exist as `BacktestResult.perStrategy`; this surfaces them keyed by portfolio
alias with the child's weight/allocation. `dailyPnL` per child is the stream qkt-forge consumes for
correlation / HRP / incremental-Sharpe.

---

## Testing

- **`PortfolioBacktestLiveParityTest`** (mirrors `BacktestLiveParityTest`): a portfolio over two
  child strategies; feed identical ticks to (a) `qkt backtest book.qkt` and (b) the live
  `PortfolioDeployer` paper path; assert identical trades, per-child realized PnL, book equity curve,
  and the tick at which a book-level halt fires.
- **Unit:** `buildPortfolio` registers N children with correct per-child allocations; a `WHEN` rule
  activates/deactivates its child (and flattens it on deactivation unless `HOLD`); a book-level
  drawdown halt fires on book equity; provisioning covers the union of child symbols; equal-footing
  when no `WEIGHT`.
- **Output:** `portfolio` block shape; per-child `dailyPnL` present and summing to the book daily PnL.
- **Regression:** the existing single-strategy `BacktestCommand` path and `BacktestLiveParityTest`
  stay green; `PortfolioStrategy`'s gate behavior remains covered (by the extracted `PortfolioGate`
  test if refactored).

---

## Out of scope (v1)

- Correlation matrix, HRP weighting, incremental-Sharpe selection — qkt-forge, Part 3 (from per-child
  `dailyPnL`).
- `walkforward <portfolio>` — fast-follow; same wiring driven over `fold_ranges`.
- Per-child `OVERRIDE` semantics beyond what `PortfolioLoader` already applies at load.

---

## Risks / to confirm during implementation

- **Candle window for multi-timeframe books.** `BacktestContext` resolves one `candleWindow` from the
  first stream's timeframe (`:160-164`). Children may declare different timeframes; `CandleHub`
  already supports multiple via `retentionByKey`, but the single `candleWindow` field needs to become
  the union (or be derived per stream). Confirm the aggregation path handles a mixed-timeframe book.
- **Gate extraction vs. reuse.** Extracting `PortfolioGate` from `PortfolioStrategy` must preserve its
  tested transition/flatten behavior. Alternative: keep `PortfolioStrategy` as the gate and add
  per-child attribution by tagging emitted signals with their child id; pick whichever the parity test
  validates with the smaller change.
- **One calendar per run.** The pipeline takes a single trading calendar resolved from the first
  symbol (`BacktestContext.kt:70-72`); a mixed-asset-class book inherits that existing limitation.
