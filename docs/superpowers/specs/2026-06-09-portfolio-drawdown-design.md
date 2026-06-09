# Portfolio account-level drawdown limits

Date: 2026-06-09
Issue: #351 (follow-up to #348 / PR #350)

## Goal

Enforce total + daily **drawdown** limits on a portfolio **book** — measured across the combined
equity of all its children — and on breach **flatten and halt every child**. Reuses the #348
risk primitives (`DrawdownBasis`, `DailyDrawdownBasis`, `DailyDrawdownTracker`, `MaxDrawdown`,
`MaxDailyDrawdown`, `RiskState`) at the book level via one aggregation adapter.

**Scope:** book-level (account-wide) only. Per-child drawdown *within* a portfolio is out of scope
(children have no DD today; the account-level halt is the prop-critical one).

## Background (verified in code)

- A portfolio runs as **one `LiveSession` (and `RiskState`/`EventBus`) per child** —
  `PortfolioDeployer.createChild()` builds each independently; there is no shared risk state or
  cross-child aggregation today.
- `PortfolioSupervisor` runs a thread that re-evaluates on each tick and toggles each child's
  `gateActive` — it already holds `children: List<ChildHandle>` and a tick cadence.
- `LiveSessionHandle` exposes per child: `pnlSnapshot(strategyId): SessionPnl`
  (`equity, balance, realized, unrealized`), `halt(reason)` (→ `riskState.halt`), `flatten()`.
- A book's total capital is `PortfolioAst.capital`; per-child allocation is `CAPITAL × WEIGHT`
  (`capitalAllocations`). `CompiledChild.strategyId = "${portfolio.name}:${alias}"`.
- #348 primitives are on dev: `RiskState(pnl, strategyPnL, clock, bus, initialBalance, dailyDdBasis)`
  builds `DrawdownTracker` + `DailyDrawdownTracker`; `MaxDrawdown(pct, basis, initialBalance)` and
  `MaxDailyDrawdown(pct)` are `HaltRule`s reading that state.

## Design

### 1. `BookPnLProvider` — the one aggregation primitive

Implements the existing `PnLProvider` by summing the children's live snapshots:

```kotlin
class BookPnLProvider(private val children: List<() -> SessionPnl>) : PnLProvider {
    override fun realizedTotal() = children.sumOf { it().realized }
    override fun unrealizedTotal() = children.sumOf { it().unrealized }
    override fun unrealizedFor(symbol: String) = Money.ZERO   // book aggregate has no per-symbol view
    override fun totalPnL() = realizedTotal() + unrealizedTotal()
}
```

Each `() -> SessionPnl` closes over a child's `LiveSessionHandle.pnlSnapshot(strategyId)`. The book's
absolute equity is `CAPITAL + realizedTotal() + unrealizedTotal()` — exactly the shape `RiskState`
expects (its equity is PnL-relative; `initialBalance` carries the capital).

### 2. `PortfolioRiskAggregator` — book RiskState + #348 rules

Owns a **book-level** `RiskState` fed by `BookPnLProvider`, plus the same #348 halt rules, and
flattens+halts every child when a rule trips. **Latched per deployment** — it flattens+halts once
and does not re-arm; a redeploy builds a fresh aggregator. (Operator `resume` on an individual child
is a deliberate override and does not un-trip or re-trip the book latch.)

```kotlin
class PortfolioRiskAggregator(
    private val children: List<ChildRiskTarget>,   // { flatten(), halt(reason) } per child
    private val bookRiskState: RiskState,          // built with BookPnLProvider + initialBalance=CAPITAL
    private val haltRules: List<HaltRule>,         // MaxDrawdown(pct, basis, CAPITAL) + MaxDailyDrawdown(pct)
) {
    @Volatile private var tripped = false

    fun evaluate() {
        if (tripped) return
        bookRiskState.onTick()                     // refresh book equity from summed children
        val breach = haltRules.firstNotNullOfOrNull { it.evaluate(bookRiskState) as? HaltDecision.Halt }
            ?: return
        tripped = true
        for (c in children) { runCatching { c.flatten() }; runCatching { c.halt(breach.reason) } }
    }
}
```

`flatten()` then `halt()` per child: close open positions to cap the loss, then block new orders.
Wrapped in `runCatching` so one child's broker error doesn't stop the rest from halting.

### 3. Supervisor drives it

`PortfolioSupervisor` takes an optional `riskAggregator` and calls `riskAggregator?.evaluate()` once
per cycle (its existing tick loop). No new thread.

### 4. Wiring

- **`PortfolioDeployer`** builds the aggregator after creating the children: a `BookPnLProvider` over
  `children.map { { it.handle.live.pnlSnapshot(it.strategyId) } }`, a book `RiskState`
  (`initialBalance = ast.capital`, `dailyDdBasis`), and the two halt rules from the daemon DD config.
  Passes it to the `PortfolioSupervisor`. Skips (logs) when `ast.capital == null` or both pcts unset.
- **`DaemonCommand`** passes the DD config (`maxDrawdownPct`, `maxDailyDrawdownPct`, `totalDdBasis`,
  `dailyDdBasis`) into `PortfolioDeployer`, mirroring the `RealFactory` wiring from #350.

### 5. Config — reuse the daemon-global `risk.*` keys

The book uses `risk.max_drawdown_pct`, `risk.max_daily_drawdown_pct`, `total_dd_basis`,
`daily_dd_basis` (the #348 keys), with the book's `initialBalance` = the portfolio's `CAPITAL`. One
account per daemon — consistent with `risk.*` being daemon-global. No new config surface.

### 6. Breach action — flatten + halt, both total and daily

A breach of either the total or daily book limit flattens all children and halts them. This makes
the limit binding (caps loss on open positions), per the design decision. The latch is
per-deployment (see §2): the book flatten+halt fires once; a redeploy starts a fresh aggregator.
Operator `resume`/`start` on a child is a manual override and does not re-trigger the book halt.

## Components

New:
- `cli/daemon/portfolio/BookPnLProvider.kt`
- `cli/daemon/portfolio/PortfolioRiskAggregator.kt` (+ a small `ChildRiskTarget` interface: `flatten()`, `halt(reason)`)

Modified:
- `cli/daemon/portfolio/PortfolioSupervisor.kt` — optional aggregator, called each cycle
- `cli/daemon/portfolio/PortfolioDeployer.kt` — build + pass the aggregator; ctor gains the DD config
- `cli/DaemonCommand.kt` — pass DD config into `PortfolioDeployer`

## Error handling

- `ast.capital == null` (unweighted book) or both pcts unset → aggregator not built; log
  `portfolio <name>: no CAPITAL/drawdown config — book drawdown halt disabled`.
- A child `pnlSnapshot` / `flatten` / `halt` throwing is caught per child (`runCatching`) so one bad
  child doesn't block the book halt; logged.
- Static basis with non-positive capital → `globalStaticDrawdown` already returns 0 (no spurious halt).

## Testing

Unit:
- `BookPnLProvider` sums children's realized/unrealized.
- `PortfolioRiskAggregator` — trips on a static total breach and on a daily breach; on trip calls
  `flatten()` **and** `halt()` on **every** child (fakes recording calls); latched (one trip only);
  no-op when rules don't breach.
- No-capital / no-config book → aggregator absent (deployer test).

Integration:
- Deploy a weighted book (`portfolio_weighted.qkt`, CAPITAL 100000) with a `risk.max_drawdown_pct`;
  drive combined equity below the limit; assert both children end halted and flat.

## Out of scope

- Per-child drawdown within a portfolio (secondary layer; children get the book halt only).
- Per-fill cadence — evaluation rides the supervisor's tick cadence; a few-ms window before the
  book-wide halt is acceptable for a drawdown limit (same trade-off #348 noted).
- Per-portfolio config overrides — the book uses the daemon-global `risk.*`.
