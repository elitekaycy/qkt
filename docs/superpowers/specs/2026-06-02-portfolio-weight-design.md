# Portfolio WEIGHT — capital allocation across child strategies (#69)

## Motivation

A `PORTFOLIO` today `IMPORT`s and `RUN`s child strategies (fan-out) with no capital
allocation — each child self-sizes off its own, often hardcoded, capital basis. When
several strategies share one live account (qkt-prod runs `hedge-straddle` and
`pairs_xau_xag` against one account), there is no way to control how much of the book
each strategy gets. `WEIGHT` makes that split explicit and controllable from the portfolio.

## Syntax

```
PORTFOLIO book VERSION 1 CAPITAL 100000
IMPORT 'hedge-straddle.qkt'  AS hs
IMPORT 'pairs_xau_xag.qkt'   AS pairs
RULES
    RUN hs     WEIGHT 0.6
    RUN pairs  WEIGHT 0.4
```

- `CAPITAL <number>` — new clause on the `PORTFOLIO` header. The total book size the
  weights divide. Declared explicitly (not read from live equity) so backtest == live and
  the allocation is deterministic.
- `WEIGHT <fraction>` — new clause on the `RUN <child>` action. The child's share of `CAPITAL`.

## Semantics

- Each child runs with an allocated capital of `CAPITAL × weight` (hs → 60000, pairs → 40000).
- A child's `ACCOUNT.equity` resolves to its allocated capital under the portfolio (60000
  for hs), and to the real/configured account when the child is run standalone — so the
  same child file is portable between the two contexts.
- Children drive their `SIZING RISK` off `ACCOUNT.equity` rather than a hardcoded number.
  Migration example: hedge-straddle's `SIZING RISK $ (50000 * 0.007 * …)` becomes
  `SIZING RISK $ (ACCOUNT.equity * 0.007 * …)`.
- **Validation (at compile):** weights are fractions in `(0, 1]` and must sum to **≤ 1.0**
  (a sum > 1.0 is rejected — no implicit leverage). A sum < 1.0 is allowed; the remainder
  is unallocated reserve.
- **All-or-none:** if any child carries `WEIGHT`, every child must, and `CAPITAL` must be
  present. A portfolio with no `WEIGHT`/`CAPITAL` is unchanged — children self-size exactly
  as today. Fully backward compatible.

## Nested portfolios

A child that is itself a `PORTFOLIO` receives `CAPITAL = parent_allocated_capital`
(= parent `CAPITAL` × its weight) and splits that among its own weighted children,
recursively. A nested portfolio's own `CAPITAL` header is ignored when it runs as a child
(the parent's allocation wins); when run standalone, its declared `CAPITAL` applies.

## Implementation sketch

- **Parse / AST:** add `CAPITAL` to the portfolio-header AST and `WEIGHT` to the `RUN`
  action AST (`src/main/kotlin/com/qkt/dsl/...`).
- **Validate (compile):** weights-sum-≤-1, all-or-none, and CAPITAL-present-if-weighted;
  emit clear compile errors (a strategy author should understand the failure cold).
- **`PortfolioDeployer`** (`src/main/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployer.kt`):
  compute each child's allocated capital, then build the child `LiveSession` with an
  account-equity basis equal to that allocated capital, so `ACCOUNT.equity` and `SIZING RISK`
  resolve to the allocated amount. Recurse for nested portfolios.
- **`ACCOUNT.equity` wiring:** introduce/confirm a seam in `LiveSession` / `StrategyContext`
  so the deployer can override the child session's equity source (deployer supplies
  `allocatedCapital`; standalone supplies the real account). Exact seam finalized in planning.

## Testing

- Compile: sum ≤ 1.0 passes; > 1.0 rejected; partial `WEIGHT` rejected; `CAPITAL` missing
  with weights rejected.
- A weighted portfolio: each child's `ACCOUNT.equity` equals `CAPITAL × its weight`, and
  RISK sizing scales accordingly.
- Nested: a grandchild's allocated capital equals `parent_capital × child_weight × grandchild_weight`.
- Backward-compat: a no-`WEIGHT` portfolio runs unchanged (children self-size).

## Out of scope

- **Import-time overrides** (injecting arbitrary child parameters) — filed separately as
  #223. `WEIGHT` covers the capital basis via `ACCOUNT.equity`; arbitrary param overrides
  are orthogonal.
- **Live-equity allocation** (splitting real account equity instead of a declared `CAPITAL`)
  — a possible future mode; v3 uses explicit `CAPITAL` for determinism.

## Open questions (to settle in planning)

- The exact equity-provider seam in `LiveSession` / `StrategyContext` used to override
  `ACCOUNT.equity` per child.
- Whether a child's `ACCOUNT.equity` stays static at the allocated capital for the run, or
  tracks the child's realized P&L. v3 starts **static** (deterministic); dynamic tracking
  is a later refinement.

---
Issue: #69. Follow-up: #223 (import-time overrides). Backlog: `docs/backlog.md` — Future.
