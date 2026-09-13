# Forge gate metrics and portfolio finalization — design spec

- **Date:** 2026-07-26
- **Status:** proposed
- **Scope:** qkt-forge gate result schema, dashboard panels, trade/risk statistics, and a
  post-promotion portfolio-construction/finalization gate that emits and validates a qkt
  `PORTFOLIO` artifact.
- **Implementation plan:** `docs/superpowers/plans/2026-07-26-forge-gate-metrics-and-portfolio-finalization.md`

## 1. Problem

qkt-forge runs a serious gauntlet, but the operator cannot consistently see the
trading facts for each strategy and each gate:

- trading PnL;
- max drawdown and daily drawdown;
- Sharpe, Calmar, profit factor, win rate;
- long/short trade counts and long/short PnL;
- risk used, risk percent, amount traded, notional, lots/quantity;
- rejection count/rate and execution tier;
- which report/trade tape backs a gate verdict.

Some of this data exists in scattered places. `qkt` backtest JSON reports core
performance fields, qkt-forge `TradeFill` parses `riskUsd`, `fillNotional`,
position before/after, bracket levels, and trade report CSVs, and G1 already
computes a strong sizing/risk audit. But qkt-forge stores gate metrics as loose
JSON bags and the dashboard renders gate-specific charts plus a trade table only
when it can discover a report directory. There is no normalized per-gate
"performance/risk/trades" card layer.

The second gap is portfolio finalization. G7 currently acts as a portfolio
admission gate: it checks whether one candidate improves the current promoted
book and computes candidate weights. That is useful, but the operator wants a
later gate responsible for constructing the actual production-ready portfolio:
select the best set of promoted strategies, choose weights/risk, emit valid qkt
portfolio syntax, run the whole book together, and optimize for book-level PnL
and risk-adjusted performance.

## 2. Current evidence from code inspection

qkt-forge:

- `src/qkt_forge/store.py` stores `gate_runs.metrics` as a free-form JSON text
  column.
- `src/qkt_forge/qkt/runner.py` parses `BacktestResult` fields:
  `trades`, `totalPnL`, `winRate`, `maxDrawdown`, `sharpeRatio`,
  `profitFactor`, and report bundles.
- `TradeFill` parses per-fill fields including symbol, side, quantity, price,
  realized PnL, `riskUsd`, `fillNotional`, stop/take-profit prices, contract
  size, and strategy/account position before/after.
- `src/qkt_forge/gates/smoke.py` already computes rich G1 risk facts:
  opening fills, unaudited risk fills, planned risk/reward, planned RR,
  notional, rejection rate, realized-R campaigns, and cost-aware breakeven.
- `src/qkt_forge/gates/grid.py` carries limited config stats from sweep:
  trades, total PnL, Sharpe, Calmar, max drawdown, win rate.
- `src/qkt_forge/gates/validation.py` records validation results, a survivor
  `report_dir`, and survivor `dailyPnL`.
- `src/qkt_forge/gates/walkforward.py` records fold-level in-sample/out-of-sample
  Sharpe and PnL, but no unified aggregate card.
- `src/qkt_forge/gates/significance.py` records DSR/PBO/MC stats, but not the
  full winner backtest performance as a standard card.
- `src/qkt_forge/gates/robustness.py` records cost-stress PnL/trades and
  risk-fit sizing grid, but not standardized performance/risk cards per stress
  run.
- `src/qkt_forge/gates/portfolio.py` already computes admission Sharpe,
  stress correlation, weights, cash weight, book DSR/PBO/stress-vol ratio, and
  optional co-sim fields. It does not emit a durable qkt portfolio artifact.
- `src/qkt_forge/gates/verify.py` records basic G8 tick metrics:
  trades, total PnL, max drawdown, Sharpe, verification window, execution tier.
- `web/src/api.ts` types gate metrics as `Record<string, unknown>`.
- `web/src/stages/StageDetail.tsx` shows a gate header, gate-specific charts,
  stored equity when `dailyPnL` exists, and `GateTrades`.
- `web/src/stages/GateTrades.tsx` shows fills, realized PnL, risk, positions,
  and chart links, but only after a report directory is linked or found by
  artifact fallback.

qkt:

- Portfolio syntax exists: `PORTFOLIO <name> VERSION <n> CAPITAL <amount>`,
  `IMPORT '<path>' AS <alias> [HOLD]`, and `RUN <alias> WEIGHT <fraction>
  [OVERRIDE {...}]`.
- Portfolio backtest support exists for full-tick validation, but qkt currently
  rejects `--bars` for portfolio backtests. qkt-forge G7 already records this
  as `cosim_skipped_capability: qkt.portfolio.bar_replay` and falls back to
  returns-level validation.

## 3. Goals

1. Every gate run must persist a normalized metrics envelope that the dashboard
   can render as panel cards without gate-specific guessing.
2. Every strategy detail page must show per-gate cards for performance, risk,
   trade activity, execution/cost, and report evidence.
3. Every gate that runs or selects a qkt backtest must preserve enough report
   linkage to show fills/trades for the exact gate decision.
4. Portfolio admission and portfolio finalization must be separated:
   - existing G7: decide whether a single strategy deserves a seat;
   - new gate: construct and validate the production-ready portfolio book.
5. The final portfolio gate must emit valid qkt `PORTFOLIO` syntax with
   imports, aliases, weights, capital, and parameter overrides.
6. The final portfolio gate must run the whole selected book together, not only
   isolated sleeve returns, before the book is marked production-ready.

## 4. Non-goals

- Do not replace qkt's canonical report bundle. qkt remains the source of raw
  backtest/trade facts.
- Do not hide gate-specific statistics such as DSR, PBO, WFE, or cost-stress.
  The normalized cards sit above those details; they do not replace them.
- Do not optimize for raw PnL alone. The final portfolio gate may maximize PnL
  only inside hard drawdown, risk, concentration, correlation, and tradeability
  constraints.
- Do not promote a portfolio if the co-simulated qkt portfolio backtest cannot
  run at a fidelity tier suitable for production review.

## 5. Normalized gate metrics envelope

Each `gate_runs.metrics` should keep existing gate-specific fields and add a
reserved top-level `cards` object:

```json
{
  "cards": {
    "performance": {},
    "risk": {},
    "trades": {},
    "execution": {},
    "evidence": {},
    "portfolio": {}
  }
}
```

All fields are optional, but names and units are stable. Missing values render
as `n/a`; missing required values for a gate are acceptance-test failures.

### 5.1 `cards.performance`

```json
{
  "starting_balance": 100000.0,
  "ending_balance": 104250.0,
  "total_pnl": 4250.0,
  "return_pct": 0.0425,
  "win_rate": 0.47,
  "profit_factor": 1.38,
  "sharpe": 1.21,
  "calmar": 0.84,
  "expectancy": 18.2,
  "avg_win": 320.0,
  "avg_loss": -180.0,
  "largest_win": 1400.0,
  "largest_loss": -900.0,
  "daily_pnl": {"2021-01-04": 40.0}
}
```

Source preference:

1. qkt `--json` raw fields;
2. computed from `dailyPnL` and `trades.csv`;
3. explicit `null` if not available.

### 5.2 `cards.risk`

```json
{
  "max_drawdown": 3100.0,
  "max_drawdown_pct": 0.031,
  "max_daily_drawdown": 0.012,
  "risk_per_trade_pct_target": 0.005,
  "min_risk_usd": 50.0,
  "avg_risk_usd": 92.0,
  "max_risk_usd": 110.0,
  "avg_planned_rr": 2.4,
  "min_planned_rr": 1.5,
  "max_planned_rr": 5.0,
  "risk_audited_fills": 128,
  "unaudited_opening_fills": 0,
  "protection_audited_fills": 128,
  "invalid_protection_fills": 0
}
```

Source preference:

- existing G1 risk audit fields;
- `TradeFill.risk_usd`;
- `stopLossPrice` / `takeProfitPrice` / entry / quantity / contract size;
- qkt raw drawdown fields.

### 5.3 `cards.trades`

```json
{
  "trades": 220,
  "fills": 440,
  "opening_fills": 220,
  "long_trades": 118,
  "short_trades": 102,
  "long_pnl": 3400.0,
  "short_pnl": 850.0,
  "gross_profit": 12600.0,
  "gross_loss": -8350.0,
  "rejections": 3,
  "rejection_rate": 0.013,
  "tp_exit_rate": 0.42,
  "sl_exit_rate": 0.38,
  "manual_or_time_exit_rate": 0.20,
  "avg_realized_r": 0.12,
  "avg_net_realized_r": 0.08
}
```

Long/short attribution should be based on campaign/opening side when a
flat-to-flat campaign can be reconstructed; otherwise show fill-side counts and
mark `campaign_attribution: "fill_side_fallback"`.

### 5.4 `cards.execution`

```json
{
  "window": {"from": "2020-01-01", "to": "2021-01-01"},
  "execution_tier": "bars|ticks",
  "execution_preset": "paper|mt5-realistic",
  "screen": true,
  "tick_fills": false,
  "cost_model": "instruments.yaml",
  "cost_stress_multiplier": 1.5,
  "estimated_all_in_cost": 123.4,
  "turnover": 2200000.0,
  "max_fill_notional": 10000.0,
  "max_fill_notional_multiple": 0.10,
  "symbols": ["EURUSD", "XAUUSD"]
}
```

### 5.5 `cards.evidence`

```json
{
  "report_dir": "research/.../report",
  "trades_csv": "research/.../report/trades.csv",
  "result_json": "research/.../report/result.json",
  "strategy_path": "strategies/foo.qkt",
  "params": {"fast": "12"},
  "gate": "G3",
  "verdict": "PASS",
  "data_root": "run/data",
  "qkt_image": "ghcr.io/elitekaycy/qkt:edge",
  "qkt_forge_commit": "..."
}
```

Hash fields can be added later; the card schema reserves a stable place for
them.

### 5.6 `cards.portfolio`

For portfolio-related gates:

```json
{
  "book_size_before": 4,
  "book_size_after": 5,
  "candidate_weight": 0.07,
  "weights": {"alpha": 0.10, "beta": 0.08},
  "cash_weight": 0.55,
  "book_sharpe": 1.02,
  "candidate_sharpe": 0.76,
  "stress_corr": 0.12,
  "book_dsr": 0.24,
  "book_pbo": 0.18,
  "stress_vol_ratio": 1.4,
  "cosim_pnl": 2250.0,
  "cosim_dsr": 0.21,
  "portfolio_path": "portfolios/forge_promoted_book.qkt"
}
```

## 6. Metric extraction library

Add a single forge module, e.g. `src/qkt_forge/metrics.py`, with pure helpers:

- `cards_from_backtest(result, *, window, strategy, params, execution_tier,
  execution_preset, starting_balance) -> dict`
- `trade_cards(report, starting_balance) -> dict`
- `risk_cards(report, raw, starting_balance) -> dict`
- `merge_cards(metrics, cards) -> dict`

Gate code should call these helpers instead of hand-writing metric fields.
Existing gate-specific fields remain for compatibility.

Minimum computed fields from `trades.csv`:

- fill count;
- long/short fill counts;
- long/short realized PnL;
- gross profit/loss;
- fill notional total/max;
- risk min/avg/max;
- rejection count/rate when `rejections.csv` exists.

Minimum computed fields from qkt raw JSON:

- `trades`;
- `totalPnL`;
- `winRate`;
- `maxDrawdown`;
- `maxDailyDrawdown`;
- `profitFactor`;
- `sharpeRatio`;
- `calmarRatio`;
- `dailyPnL`;
- `screenCosts`;
- `executionModel`.

## 7. Gate-by-gate requirements

| gate | required normalized cards |
|---|---|
| G1 smoke | performance, risk, trades, execution, evidence |
| G2 grid | performance/trades/risk for best config and top carried configs; grid table remains gate-specific |
| G3 validation | performance, risk, trades, execution, evidence for each carried config; top-level cards for advancing survivor |
| G4 walk-forward | aggregate OOS performance/trades plus per-fold cards; fold chart remains gate-specific |
| G5 significance | winner development backtest cards plus DSR/PBO/MC details |
| G6 robustness | baseline cards, per-cost-multiplier cards, risk-fit cards, chosen sizing card |
| G7 portfolio admission | candidate cards, current-book cards, after-admission book cards, weight/correlation cards |
| G8 verify | tick-fills cards, evidence card, exact execution preset card |
| new portfolio finalization gate | full qkt portfolio co-sim cards, allocation grid/search cards, production portfolio artifact card |

## 8. Dashboard design

Each selected gate page should render the following card rows before charts and
trade table:

1. Performance cards:
   - PnL;
   - return %;
   - Sharpe;
   - Calmar;
   - profit factor;
   - win rate.
2. Drawdown/risk cards:
   - max DD;
   - max daily DD;
   - avg/max risk per trade;
   - avg planned RR;
   - risk-audited fills.
3. Trade cards:
   - trades/fills;
   - longs/shorts;
   - long PnL/short PnL;
   - rejections/rejection rate;
   - total notional/turnover.
4. Execution/evidence cards:
   - date window;
   - bars vs ticks;
   - execution preset;
   - report directory;
   - selected params;
   - cost model/stress multiplier where relevant.

Interaction rules:

- Clicking a card filters or scrolls the evidence area when applicable.
- PnL/DD/Sharpe cards must indicate `stored gate evidence`, not ad-hoc rerun.
- Missing required metrics show a yellow `not recorded by this gate version`
  state, not a blank card.
- The trade table remains below cards and uses the same report directory listed
  in the evidence card.

## 9. New post-promotion portfolio-finalization gate

### 9.1 Gate placement

Current flow:

```text
G1 -> G2 -> G3 -> G4 -> G5 -> G6 -> G7 -> G8 -> PROMOTED
```

Proposed production-book flow:

```text
G1 -> G2 -> G3 -> G4 -> G5 -> G6 -> G7 admission -> G8 sleeve verify -> PROMOTED_SLEEVE
PROMOTED_SLEEVE set -> G9 portfolio construction -> G10 final portfolio verify -> PROD_READY_BOOK
```

If stage names must stay compact:

- keep individual strategies at `PROMOTED`;
- add a separate `portfolios` table and a portfolio lifecycle:
  `CANDIDATE_BOOK -> PORTFOLIO_GRID -> PORTFOLIO_VERIFY -> PROD_READY_BOOK`.

The separate `portfolios` table is cleaner because a book is not one strategy.

### 9.2 Responsibilities

The portfolio-finalization gate is responsible for:

1. selecting eligible promoted sleeves;
2. generating candidate books and weights;
3. writing valid qkt `PORTFOLIO` files;
4. running qkt portfolio backtests with all children sharing one book/account;
5. choosing the best book under hard risk constraints;
6. persisting the final portfolio artifact and evidence cards.

### 9.3 Inputs

Eligible sleeve:

- individual strategy stage is `PROMOTED` or `PROMOTED_SLEEVE`;
- has passing G8 tick-fills verification;
- has deployable sizing from G6;
- has a selected winner config;
- has report evidence and no unresolved capability gap.

Portfolio config:

```yaml
g9_portfolio_construct:
  research_capital: 100000
  min_sleeves: 2
  max_sleeves: 12
  candidate_pool_limit: 30
  objective: max_calmar_then_pnl
  weight_methods: [fractional_kelly, hrp, inverse_vol, equal_risk_cap]
  weight_grid: [0.02, 0.05, 0.10, 0.15]
  max_weight: 0.15
  max_gross_weight: 1.0
  min_cash_weight: 0.0
  max_book_drawdown_pct: 0.10
  max_daily_drawdown_pct: 0.05
  max_stress_corr: 0.60
  min_book_trades: 100
  min_book_profit_factor: 1.10
  min_book_sharpe: 0.50
  execution_preset: mt5-realistic

g10_portfolio_verify:
  execution_preset: mt5-realistic
  min_final_pnl: 0.0
  min_final_profit_factor: 1.10
  max_final_drawdown_pct: 0.10
```

### 9.4 Portfolio artifact generation

For a selected book, forge writes:

```qkt
PORTFOLIO forge_promoted_book_20260726 VERSION 1 CAPITAL 100000

IMPORT '../strategies/alpha.qkt' AS alpha
IMPORT '../strategies/beta.qkt' AS beta
IMPORT '../strategies/gamma.qkt' AS gamma

RULES
    WHEN TRUE RUN alpha WEIGHT 0.10 OVERRIDE { fast = 12, slow = 48 }
    WHEN TRUE RUN beta  WEIGHT 0.08
    WHEN TRUE RUN gamma WEIGHT 0.05 OVERRIDE { threshold = 1.5 }
```

Rules:

- aliases must be stable, lowercase, and unique;
- relative import paths must be valid from the portfolio file directory;
- weights must be positive and sum to `<= 1.0`;
- leftover weight is cash;
- every selected child receives its G6 baked sizing and winner params via either
  persisted strategy text or `OVERRIDE`;
- generated file path should be under `portfolios/forge/`.

### 9.5 Search methods

Run a bounded portfolio search:

1. Build daily return streams from each sleeve's G8 or G7/G8 report.
2. Drop sleeves with too few active days/trades.
3. Cluster by correlation and keep at most `N` representatives per cluster.
4. Generate weight candidates using:
   - equal weight with cap;
   - HRP;
   - fractional Kelly;
   - inverse volatility;
   - small local grids around the best methods.
5. For each candidate book:
   - compute returns-level preview metrics;
   - reject if weight/concentration/correlation constraints fail;
   - write qkt portfolio file;
   - run qkt portfolio backtest.
6. Rank by objective:
   - primary: Calmar or DSR-adjusted Sharpe;
   - secondary: total PnL;
   - hard constraints: max DD, daily DD, min trade count, min profit factor,
     max weight, max gross weight, minimum data/report quality.

### 9.6 Final portfolio verification

G10 runs the chosen qkt portfolio at the final fidelity tier:

- full tick or tick-fills if qkt supports the portfolio shape;
- `execution_preset: mt5-realistic`;
- same account capital as the generated portfolio;
- no returns-level fallback for final pass.

If qkt cannot run the portfolio at final fidelity, the book is not
`PROD_READY_BOOK`. It may be saved as `PORTFOLIO_CANDIDATE_BLOCKED` with a
capability note.

Required G10 cards:

- full-book performance/risk/trade/execution/evidence cards;
- per-child attribution cards;
- portfolio weights and cash;
- book exposure/concentration;
- long/short and symbol attribution;
- final qkt portfolio path;
- final report directory.

## 10. Store schema additions

Keep existing `gate_runs` for individual strategies. Add:

```sql
CREATE TABLE IF NOT EXISTS portfolios (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    stage TEXT NOT NULL,
    qkt_path TEXT,
    capital REAL NOT NULL,
    weights TEXT,
    members TEXT,
    objective TEXT,
    metrics TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS portfolio_gate_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    portfolio_id INTEGER NOT NULL,
    gate TEXT NOT NULL,
    verdict TEXT NOT NULL,
    metrics TEXT,
    started_at TEXT,
    finished_at TEXT,
    notes TEXT,
    FOREIGN KEY (portfolio_id) REFERENCES portfolios(id)
);
```

`members` stores strategy IDs, qkt paths, selected params, selected sizing, and
aliases. `weights` stores alias-to-weight plus cash weight.

## 11. API additions

Existing endpoints keep working. Add:

- `GET /api/strategy/<id>` includes `cards` in every gate run.
- `GET /api/strategy/<id>/trades?gate=Gx` remains report-backed.
- `GET /api/portfolios` lists portfolio candidates/books.
- `GET /api/portfolio/<id>` returns qkt text, members, weights, gate runs,
  normalized cards, and child attribution.
- `GET /api/portfolio/<id>/trades?child=<alias>` returns full book or child
  fills.

## 12. Acceptance checks

### Metrics/cards

- A G1 run records `cards.performance.total_pnl`,
  `cards.risk.max_risk_usd`, `cards.trades.long_trades`,
  `cards.trades.short_trades`, `cards.execution.window`, and
  `cards.evidence.report_dir`.
- A G3 survivor shows card values without an ad-hoc rerun.
- A G5 run shows both significance-specific stats and the winner's
  performance cards.
- A G6 run shows baseline and stressed cost cards, plus chosen sizing and
  risk-fit grid.
- A G8 run shows tick-fills execution cards and report-backed trades.
- The dashboard renders the same card layout for G1-G8, with `n/a` or
  `not recorded by this gate version` for unavailable fields.

### Portfolio finalization

- Forge can generate a qkt `PORTFOLIO` file from at least two promoted sleeves.
- Generated weights are positive and sum to `<= 1.0`.
- `qkt parse <generated-portfolio.qkt>` passes.
- The final portfolio gate runs qkt portfolio backtest and records full-book
  cards plus child attribution.
- A book with higher standalone PnL but worse drawdown than the configured cap
  is rejected.
- A book whose qkt portfolio final-fidelity run cannot execute is not marked
  production-ready.

## 13. Implementation order

1. Add `metrics.py` card extraction helpers and retrofit G1/G3/G8 first.
2. Add dashboard card components and render from `cards`.
3. Retrofit G2/G4/G5/G6/G7 to populate cards while preserving existing fields.
4. Add portfolio tables and read-only portfolio API.
5. Implement portfolio artifact generator using qkt `PORTFOLIO` syntax.
6. Implement G9 returns-level portfolio construction and bounded search.
7. Implement G10 final qkt portfolio verification and dashboard view.

This sequence gives immediate visibility into existing gates before adding the
larger portfolio-finalization workflow.
