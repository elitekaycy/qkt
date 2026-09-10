# Forge gate metrics and portfolio finalization — implementation plan

Spec: `docs/superpowers/specs/2026-07-26-forge-gate-metrics-and-portfolio-finalization-design.md`

## Goal

Make qkt-forge show complete, consistent trading/risk/performance statistics for
every gate run, then add a post-promotion portfolio workflow that builds and
verifies a production-ready qkt `PORTFOLIO` book from promoted strategies.

## Task 1 — Add normalized metric-card extraction

Files:

- `src/qkt_forge/metrics.py`
- `tests/test_metrics.py`

Steps:

1. Add pure helpers:
   - `cards_from_backtest(...)`
   - `trade_cards(...)`
   - `risk_cards(...)`
   - `merge_cards(...)`
2. Compute performance, risk, trades, execution, and evidence cards from
   `BacktestResult`, `BacktestReport`, `TradeFill`, and raw qkt JSON.
3. Cover missing values: return `None`, not guessed zeros, when the source does
   not prove the value.
4. Unit-test:
   - long/short fill attribution;
   - gross profit/loss;
   - notional/turnover;
   - risk min/avg/max;
   - rejection rate;
   - missing report behavior.

Acceptance:

- A synthetic report with buys/sells produces deterministic card values.
- Missing report files produce cards with `None` values and evidence warnings,
  not crashes.

## Task 2 — Retrofit existing gates to populate cards

Files:

- `src/qkt_forge/gates/smoke.py`
- `src/qkt_forge/gates/grid.py`
- `src/qkt_forge/gates/validation.py`
- `src/qkt_forge/gates/walkforward.py`
- `src/qkt_forge/gates/significance.py`
- `src/qkt_forge/gates/robustness.py`
- `src/qkt_forge/gates/portfolio.py`
- `src/qkt_forge/gates/verify.py`

Steps:

1. Start with G1, G3, G8 because they already have direct report-backed
   backtests.
2. Add best-config and carried-config cards for G2.
3. Add aggregate OOS and per-fold cards for G4.
4. Add winner development backtest cards to G5.
5. Add baseline, per-cost-multiplier, and chosen-sizing cards to G6.
6. Add candidate/current-book/after-admission cards to G7.
7. Preserve all current gate-specific metric keys for backward compatibility.

Acceptance:

- New gate runs include `metrics.cards`.
- Existing dashboard charts still read old gate-specific fields.
- Gate verdict behavior does not change except where missing required evidence
  is explicitly supposed to fail.

## Task 3 — Add dashboard card components

Files:

- `web/src/api.ts`
- `web/src/components/MetricCards.tsx`
- `web/src/stages/StageDetail.tsx`
- `web/src/theme.css`
- `web/src/ui.css`

Steps:

1. Type `MetricCards` in the API model while preserving loose metric bags.
2. Render standard card rows before gate-specific charts:
   - performance;
   - drawdown/risk;
   - trades;
   - execution/evidence.
3. Add states for:
   - normal value;
   - `n/a`;
   - `not recorded by this gate version`;
   - warning when report linkage is missing.
4. Keep `GateTrades` below the cards and make its selected report match the
   evidence card where possible.

Acceptance:

- Every reached gate tab renders the same card sections.
- No gate tab becomes blank when a card value is missing.
- The UI labels stored gate evidence separately from ad-hoc reruns.

## Task 4 — Add portfolio persistence/API

Files:

- `src/qkt_forge/store.py`
- `run/dashboard.py`
- `web/src/api.ts`
- `web/src/pages/PortfolioPage.tsx`

Steps:

1. Add `portfolios` and `portfolio_gate_runs` tables.
2. Add store methods:
   - `insert_portfolio`
   - `portfolio_runs_for`
   - `update_portfolio_stage`
3. Add read-only API:
   - `GET /api/portfolios`
   - `GET /api/portfolio/<id>`
   - `GET /api/portfolio/<id>/trades`
4. Add a basic portfolio detail page that mirrors strategy gate evidence.

Acceptance:

- Dashboard lists candidate/final books separately from individual strategies.
- Portfolio pages show members, weights, qkt text, gate runs, and cards.

## Task 5 — Generate qkt portfolio artifacts

Files:

- `src/qkt_forge/portfolio_artifact.py`
- `tests/test_portfolio_artifact.py`

Steps:

1. Generate stable aliases from strategy names/IDs.
2. Write `PORTFOLIO <name> VERSION 1 CAPITAL <amount>`.
3. Write relative `IMPORT '<path>' AS <alias>` lines.
4. Write `WHEN TRUE RUN <alias> WEIGHT <w>` lines.
5. Include `OVERRIDE { ... }` for selected params when needed.
6. Validate:
   - every weight is positive;
   - weights sum to `<= 1.0`;
   - import paths exist;
   - alias names are unique.

Acceptance:

- Generated qkt text matches documented portfolio syntax.
- `qkt parse` passes on a generated fixture.

## Task 6 — Implement G9 portfolio construction

Files:

- `src/qkt_forge/gates/portfolio_construct.py`
- `src/qkt_forge/pipeline.py`
- `config/gates.yaml`

Steps:

1. Select eligible promoted sleeves:
   - passed G8;
   - has selected params;
   - has deployable sizing;
   - has report/evidence.
2. Build sleeve daily return streams.
3. Cluster by correlation to avoid duplicate seats.
4. Generate candidate weight sets:
   - equal weight with cap;
   - HRP;
   - fractional Kelly;
   - inverse vol;
   - small bounded local grids.
5. Preview candidate books on returns-level stats.
6. Write qkt portfolio artifacts for finalist books.
7. Run qkt portfolio backtests where available.
8. Rank under hard constraints:
   - max drawdown;
   - max daily drawdown;
   - min trades;
   - min profit factor;
   - max concentration;
   - max gross weight.

Acceptance:

- A two-sleeve fixture produces a portfolio candidate with positive weights.
- A high-PnL but over-drawdown candidate is rejected.
- G9 records cards and generated artifact path.

## Task 7 — Implement G10 final portfolio verify

Files:

- `src/qkt_forge/gates/portfolio_verify.py`
- `config/gates.yaml`

Steps:

1. Run the selected qkt portfolio using final execution settings:
   - `mt5-realistic`;
   - full tick or exact tick-fills when valid.
2. Do not allow returns-level fallback for final approval.
3. Record full-book cards and per-child attribution.
4. Mark portfolio `PROD_READY_BOOK` only when all thresholds pass.

Acceptance:

- A qkt execution/capability failure blocks the book instead of promoting it.
- A passing fixture records full-book PnL/DD/Sharpe/trades/risk cards.

## Task 8 — Update docs and operator workflow

Files:

- `docs/research/index.md`
- `docs/reference/config-schema.md`
- `docs/reference/dsl/portfolio.md` if artifact examples need expansion

Steps:

1. Document the new card schema in operator terms.
2. Document G9/G10 gate responsibilities.
3. Document the generated portfolio artifact location and review process.

Acceptance:

- An operator can tell the difference between:
  - individual strategy promotion;
  - portfolio candidate;
  - production-ready book.

## Required checks

Targeted:

```bash
python -m pytest tests/test_metrics.py tests/test_portfolio_artifact.py
```

Frontend:

```bash
cd web && pnpm test
cd web && pnpm build
```

Full pre-push where appropriate:

```bash
./scripts/precheck.sh
```
