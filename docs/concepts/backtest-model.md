# Backtest model

What the backtest engine assumes — explicit, so you know what you're trusting.

## What a backtest looks like

A complete backtest invocation and its output:

```bash
qkt backtest strategies/momentum.qkt --from 2024-01-01 --to 2024-04-01
```

```text
Trades:                 187
Final realized:         1,420.50
Final unrealized:       35.20            (open position at run end)
Total P&L:              1,455.70
Win rate:               0.583
Avg win:                18.40
Avg loss:               -11.20
Sharpe (daily):         1.34
Sortino (daily):        1.61
Calmar:                 2.18
Turnover (x cap):       3.20
Profit factor:          1.95
Max drawdown:           -185.25          (-3.7% peak-to-trough)
DD duration:            9 trading days
Max consecutive losses: 4

Report: ./reports/momentum-20240501-103245.html
```

Each row corresponds to something defined below. The HTML report unpacks every metric into a chart or table — equity curve, drawdown periods, Monte Carlo fan, per-trade risk table.

Two of the headline ratios beyond Sharpe and Calmar:

- **Sortino** — like Sharpe, but only *downside* moves count as risk (a return below zero). A strategy with smooth gains and rare, shallow losses scores higher on Sortino than on Sharpe.
- **Turnover** — gross traded notional as a multiple of starting capital, e.g. `3.20` means the run traded 3.2x its capital. Not annualized; compare runs of equal length.

## Per-strategy attribution

A portfolio backtest (two or more strategies) prints a per-strategy block under the global summary, and the same breakdown lands in `--json` (a `perStrategy` object) and the `--report` bundle (`result.json`, plus an `equity_<id>.csv` per strategy). Each strategy carries its own full report — P&L, trades, Sharpe, Sortino, max drawdown, win rate, turnover, commission — computed from only that strategy's fills. A single-strategy run omits the block: the global summary already *is* that strategy.

## Book analytics

For a portfolio, the report adds the cross-strategy figures the per-strategy reports can't show on their own (null on single-strategy runs). All are computed online over the run's return samples:

- **Contribution to return** — each strategy's share of book total P&L. Sums to 1; a strategy can read negative or above 1 when strategies offset each other.
- **Risk contribution (PCTR)** — each strategy's percent contribution to book *return variance*. Sums to ~1. A strategy with a small return share but a large risk share is eating the book's risk budget — the first thing to check when sizing a book.
- **Return correlation** — pairwise correlation of the strategies' return series. e.g. two trend strategies at +0.9 are nearly the same bet, so the book is less diversified than it looks.
- **Drawdown contribution** — each strategy's share of the book's worst peak-to-trough drawdown.

Returns are measured on a constant capital base — each strategy's P&L change over the run's starting balance — so the book return is exactly the sum of strategy returns and the risk decomposition adds to one. A run with no capital basis (`--starting-balance 0`) reports no book analytics.


## Fills

- Market orders fill at the `MarketPriceTracker`'s last-known price for the symbol at the moment the order is submitted.
- Stop and Limit orders fill at the trigger price, the moment the trigger condition becomes true.
- Bracket SL/TP triggers when the underlying tick crosses the level. Fill price = trigger price (no gap simulation).

## Slippage

**Not modeled by default.** Strategies should account for spread + slippage in their stop-loss buffers. A future enhancement adds a configurable slippage model.

## Spread

**Not modeled.** The tick stream is mid-price. Real brokers fill on the wrong side of the spread for the strategy's direction.

## Partial fills

**Not modeled.** Every order is either filled fully or rejected.

## Equity

- `equity = balance + Σ unrealized_pnl_per_open_position`
- `realized` accumulates as positions close.
- The equity curve in the HTML report is sampled at the configured cadence (`SampleCadence.TICK` by default; `CANDLE_CLOSE` for candle-driven strategies).

## Drawdown

- Tracked in real-time by `DrawdownTracker` (max DD as a single number) and analyzed post-run by `DrawdownAnalyzer` (full segments with peak/trough/recovery).
- "Underwater" = current equity < running-peak equity.
- An `ongoing` drawdown is one that hasn't recovered by the end of the run.

## Risk engine

Halts apply to the engine itself, not just to logging:

- `MaxDrawdown`: engine refuses new orders when global DD breaches the threshold
- `MaxStrategyDrawdown`: per-strategy halt
- `MaxDailyLoss`: engine halts after a daily-loss boundary
- Halts are stateful — operator manually resumes via the engine's resume API

See [Phase 9 changelog](../phases/index.md) for the full risk-rule catalog.

## Monte Carlo

Phase 16's MC bootstraps **per-trade returns with replacement**:

- Default 1000 simulations
- Each simulation walks the trade sequence with random replacement
- Reports P5/P25/P50/P75/P95 final equity, max DD distribution, P(final < 0)

Assumes trade returns are i.i.d. — strategies with clustered wins/losses (momentum) violate this and the MC will be optimistic about path dependence. Block bootstrap is a future enhancement.

## Per-trade risk

`TradeRecord.riskUsd` is `qty × |entry - stopLoss|` in the symbol's quote currency, captured at order submission. Surfaced in the HTML report's per-trade table. `n/a` when the order had no stop attached.

## What's not in the model

- Funding fees (perpetual swaps)
- Overnight financing (CFDs)
- Commission per trade (configurable in a future enhancement)
- FX conversion of cross-currency positions
- Borrowing costs for shorts
- Tax effects

## See also

- [Determinism](determinism.md) — backtest = live-paper given same ticks
- [Phase 16 changelog](../phases/index.md) — HTML report contents (DD-days, MC, per-trade risk)
- [Phase 10 changelog](../phases/index.md) — original backtest reporting (Sharpe, Calmar, profit factor, win/loss)
