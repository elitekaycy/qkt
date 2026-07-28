# Book-basis risk sizing: `SIZING = N PCT RISK OF BOOK`

Issue: [#877](https://github.com/elitekaycy/qkt/issues/877)

## Problem

Portfolio children size risk-based orders off their own equity, which for allocated
children is `WEIGHT x CAPITAL`. On a $50k book with HRP-style weights, 1 PCT RISK of a
$505 allocation is 0.0002 lots on XAUUSD — below every venue's minimum volume, so the
order is rejected at the broker layer. There is no way to express "risk N% of the whole
book per trade": `PCT RISK` uses child equity and `RISK $` is a static dollar amount
that never compounds.

## Decision (with elitekaycy, 2026-07-28)

New sizing form, valid only where a book exists:

```
DEFAULTS { SIZING = 1.0 PCT RISK OF BOOK }
```

Quantity is `bookBalance * frac / (stopDistance * contractSize * quoteToAccountRate)` —
identical to `PCT RISK` except the basis.

**Book balance = portfolio CAPITAL + cumulative net realized PnL of every child**
(balance-style). Chosen over equity-style (needs cross-child mark aggregation, sizing
moves with mid-trade noise) and live broker equity (includes non-book funds, has no
backtest analog). Balance-style is deterministic, restart-safe via the persisted
per-child lifetime realized PnL, and matches the deployed book's `daily_dd_basis:
balance`.

## Design

- **AST/parser.** `SizeRiskFracOfBook(frac)` joins the `SizingAst` sealed interface.
  Grammar: the existing `N PCT RISK` branch optionally consumes `OF BOOK`. Same
  literal/positivity validation as `PCT RISK`, same percent→fraction normalization.
- **Runtime view.** `com.qkt.pnl.BookBalanceView` (`fun balance(): BigDecimal`), an
  optional field on `StrategyContext` (default null = no book bound).
- **SizingCompiler.** Mirrors `SizeRiskFrac` (same resolvable-stop-distance requirement)
  but reads `ec.strategyContext.book.balance()`; a null book fails with an actionable
  error naming the fix (deploy as a portfolio child).
- **Fail-closed capability check.** `DslCompiledStrategy.usesBookSizing` is set when any
  compiled action sizes OF BOOK. `TradingPipeline` rejects at deploy — same pattern as
  `requireMultiPositionCapability` — unless a `bookBalance` provider was supplied. A
  standalone `qkt run`/single-strategy backtest of an OF BOOK strategy therefore fails
  at deploy time with a clear message, never silently at first signal.
- **Live wiring.** `PortfolioDeployer` builds one provider per portfolio:
  `CAPITAL + Σ children pnlSnapshot(id).realized` over the child `LiveSessionHandle`s
  (the same surface the drawdown aggregator reads; `realizedFor` is lifetime realized,
  restored at boot). The provider is late-bound after all children construct and before
  `PortfolioSupervisor.start()` activates any gate, so no signal can size before it is
  bound. Reads go through `engineSnapshot`, so cross-thread reads are safe.
- **Backtest wiring.** The portfolio backtest runs all children on one engine; the
  provider is `CAPITAL + Σ strategyPnL.realizedFor(childId)` over the same pipeline's
  `StrategyPnL`. Same definition as live (sum of per-child realized — NOT the netted
  account realized, which can differ when children cross), so sizing is parity-exact.

## One writer

Book balance is derived, not stored: both modes read the existing per-child realized
accumulators (`StrategyPnL`), which already have exactly one writer (the fill handler)
and already persist/restore. No new mutable state is introduced.

## Acceptance

- Parser: `1.0 PCT RISK OF BOOK` → `SizeRiskFracOfBook(0.01)`; `PCT RISK` unchanged.
- Compiler: OF BOOK with a bound book sizes off book balance; without → deploy-time
  error; missing bracket stop → same compile error as `PCT RISK`.
- Portfolio backtest e2e: child with OF BOOK sizes `capital * frac / (d * cs)` on the
  first trade and re-sizes after realized PnL changes the book balance.
- Live deploy path: `PortfolioDeployer` binds the provider; a standalone deploy of an
  OF BOOK strategy fails with the actionable message.
- `qkt parse` (which compiles since #858/#876 fixes) accepts a portfolio whose children
  use OF BOOK.
