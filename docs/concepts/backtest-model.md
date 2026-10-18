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

## Round trips and win rate

A **round trip** is one position, from its first entry fill until it is flat again. A position
entered or exited in several fills (partial fills, `--partial-fill`, the `stress` preset, an exit
split over two take-profits) is one round trip, and its outcome is the net P&L of all its fills,
entry and exit commissions included. A leg-routed position (each bracket, `TIMES` or `STACK` entry
is its own leg) is tracked per leg; a netted position per strategy and symbol, and a netted exit
larger than the position reverses it, which closes this round trip and opens the next. A position
still partly open at the end counts once if it had exits.

Win rate, profit factor, average and largest win/loss, max consecutive losses and the Monte Carlo
are all computed over round trips. Win rate is `won / (round trips - break-even)`: one that closed
at exactly zero after costs counts in neither. The console summary, `--verbose`, `--json`,
`result.json` (`roundTrips`, `roundTripsWon`, `roundTripsBreakEven`, `winRate`) and `report.html`
show the same numbers. `tradeCount` (`Trades:` in `--verbose`) counts fills.

## Per-strategy attribution

A portfolio backtest (two or more strategies) prints a per-strategy block under the global summary, and the same breakdown lands in `--json` (a `perStrategy` object) and the `--report-dir` bundle (`result.json`, plus an `equity_<id>.csv` per strategy). Each strategy carries its own full report — P&L, trades, Sharpe, Sortino, max drawdown, win rate, turnover, commission, and swap — computed from that strategy's fills and financing cash flows. A single-strategy run omits the block: the global summary already *is* that strategy.

## Book analytics

For a portfolio, the report adds the cross-strategy figures the per-strategy reports can't show on their own (null on single-strategy runs). All are computed online over the run's return samples:

- **Contribution to return** — each strategy's share of book total P&L. Sums to 1; a strategy can read negative or above 1 when strategies offset each other.
- **Risk contribution (PCTR)** — each strategy's percent contribution to book *return variance*. Sums to ~1. A strategy with a small return share but a large risk share is eating the book's risk budget — the first thing to check when sizing a book.
- **Return correlation** — pairwise correlation of the strategies' return series. e.g. two trend strategies at +0.9 are nearly the same bet, so the book is less diversified than it looks.
- **Drawdown contribution** — each strategy's share of the book's worst peak-to-trough drawdown.

Returns are measured on a constant capital base — each strategy's P&L change over the run's starting balance — so the book return is exactly the sum of strategy returns and the risk decomposition adds to one. A run with no capital basis (`--starting-balance 0`) reports no book analytics.


## Fills

Two simulated brokers, chosen with `--broker`:

- **`paper`** (default): fills at mid. A market order fills at the `MarketPriceTracker`'s last-known
  price for the symbol. A stop, limit or bracket exit triggers on the first tick whose quote for its
  side reaches the level (the ask for a buy, the bid for a sell, as at the venue) and fills at that
  tick's mid, so it can fill up to half a spread better than its level, or beyond it on a gap. No
  spread, slippage or latency. Volume rounds down to `volumeStep` and an order below `volumeMin` is
  rejected (`volumeMax` is not checked). `--execution-latency`, `--reject-every` and `--partial-fill`
  apply to `mt5-sim` only. A symbol replayed from
  bars (`--bars`, or one the store has only bars for, such as fetched crypto or broker bars) has no
  prints between a bar's open, low, high and close: an exit the bar trades through fills at its
  level, and one the next bar opens beyond (a gap) fills at that open.
- `qkt backtest --bars` resolves fills on the recorded ticks inside each bar by default (tick-resolved
  fills), wherever the store has them: signals come from bars, fills from real prints. On a golden XAUUSD
  run this reproduced a full tick replay fill for fill (170 of 170), where the synthetic bar path matched
  23 and overstated P&L by about 500. A bar the store has no ticks for replays its synthetic open,
  extremes (the open position's adverse one first) and close, and fills exactly as `--no-tick-fills`
  fills it: an exit it trades through fills at its level, one it opens beyond fills at that open. This
  is per bar, so a window partly covered by ticks fills from ticks wherever they exist. The run says so
  once per symbol, e.g. `qkt: note: BACKTEST:EURUSD: 12 of 340 fill-possible bars had no ticks; orders
  on them filled at their levels (bar fills), not from ticks`, and `result.json` records the counts
  under `evidence.execution.barsWithoutTicks`. The default turns
  off, with a note, where tick-resolved fills are not valid (execution latency, order spacing, stop
  latency, mixed timeframes, continuous futures); `--no-tick-fills` keeps the synthetic path everywhere,
  and an explicit `--tick-fills` refuses instead of turning off. `sweep`, `walkforward`, `research` and
  `experiment` keep the synthetic path unless asked.
- `mt5-sim` needs ticks: it refuses a symbol it would replay from bars on the synthetic path
  (`--no-tick-fills`, or where tick-resolved fills are off). Under tick-resolved fills it runs, and a
  bar without ticks fills at its levels as above (stops then slip by `slippagePoints`), never at the
  bar's synthetic extreme.
- **`mt5-sim`**: mirrors an MT5 venue. Volume is rounded down to `volumeStep` and orders below
  `volumeMin` are rejected; fill prices round to `digits`. A BUY fills at the ask and a SELL at the
  bid (see [Spread](#spread)), plus slippage. The execution preset (`--execution`, or `execution.preset`
  in `qkt.config.yaml`) adds the rest:

| Preset | Latency | Slippage | Other |
|---|---|---|---|
| `mt5-basic` (default for `mt5-sim`) | none | `slippagePoints` per instrument | |
| `mt5-realistic` | 250 ms | `slippagePoints` per instrument | `tradeStopsLevel` enforced |
| `stress` | 500 ms | uniform random up to 20 points (seeded) | every 10th order rejected, 50% partial fills, `tradeStopsLevel` enforced |

Each knob can be set on its own, on the command line or under `execution:` in the config:
`--execution-latency` (entry latency), `--stop-latency` (a crossed protective stop fills at the first
quote at or after trigger + latency), `--tp-fill print|level` (pricing of a gap-crossed take-profit),
`--slippage`, `--reject-every`, `--partial-fill`, `--order-spacing`. The run's `result.json` records the
model used in `evidence.execution`.

## Slippage

`paper` fills have none. `mt5-sim` slips market and stop fills against you by the instrument's
`slippagePoints` (`instruments.yaml`, each point one `pointSize` wide) under `mt5-basic` and
`mt5-realistic`, or as the `--slippage` option states. A limit, take-profit included, is never filled
worse than its price, so it is not slipped.

## Spread

`paper` fills at the tracked price, so it pays no spread.

`mt5-sim` fills at the tick's own bid/ask when the feed carries them; a mid-only feed gets a synthetic
two-point spread around mid. A vendor's history is quoted at the vendor's spread, which may not be your
venue's (median XAUUSD across the same 2026 days: Dukascopy $0.78, Exness $0.26). An instrument can
state its venue's spread in `instruments.yaml`, one of:

- `spreadPoints: 260` fills at the tick's mid ± 130 points, whatever spread the tick carries.
- `minSpreadPoints: 260` only widens: a tick quoting at least 260 points fills at its own bid/ask, a
  thinner one is widened to 260 around its mid. Use it to make a thin feed conservative.

Either also replaces the synthetic spread on mid-only ticks. The fill price is the only thing they
change: stops, limits and the strategy's own `bid`/`ask` still see the feed's quotes. The symbols
priced this way are listed in `evidence.execution.fillPriceSource`. Live runs ignore both fields.

## Partial fills

`paper` and the `mt5-basic` and `mt5-realistic` presets fill every order fully or reject it.
`--partial-fill <fraction>` (on by default in `stress`) fills each order in two slices on the same
tick: that fraction, then the rest. A bracket's exits protect the whole filled quantity.

## Overnight swap

Backtests model broker swap from signed `swapLongPoints` / `swapShortPoints` values in
`instruments.yaml`. At each configured UTC rollover, every qualifying open leg accrues:

`points * pointSize * contractSize * absolute lots * day multiplier`

The multiplier is three on `swapTripleDay`, one on other weekdays, and zero on weekends.
Positive points credit PnL; negative points debit it. A position opened on the boundary
tick starts accruing on the next rollover, while one closed on that tick pays the current
rollover. Gaps traverse every boundary in chronological order. Native quote-currency cash
is converted through the accounting engine at the last pre-boundary mark.

`swapPaid` is positive for a net charge and negative for a net credit. Realized and total
PnL are already net of swap, and daily PnL assigns the cash to its UTC rollover date.
Configured rates are a point-in-time input; they are not fetched historically from a broker.

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
- `P(final < 0)` counts paths whose equity ended below zero (ruin), not paths that ended below the starting balance
- Needs at least 30 closed trades; otherwise `global.monteCarlo` is `null`
- The per-trade equity fan (P5/P25/P50/P75/P95 after each resampled trade) is written to `monte_carlo_fan.csv`

Assumes trade returns are i.i.d. — strategies with clustered wins/losses (momentum) violate this and the MC will be optimistic about path dependence. Block bootstrap is a future enhancement.

## Per-trade risk

`TradeRecord.riskUsd` is `qty × |entry - stopLoss|` in the symbol's quote currency, captured at order submission. Surfaced in the HTML report's per-trade table. `n/a` when the order had no stop attached.

## What's not in the model

- FX conversion of cross-currency positions
- Borrowing costs for shorts
- Tax effects

## See also

- [Determinism](determinism.md) — backtest = live-paper given same ticks
- [Phase 16 changelog](../phases/index.md) — HTML report contents (DD-days, MC, per-trade risk)
- [Phase 10 changelog](../phases/index.md) — original backtest reporting (Sharpe, Calmar, profit factor, win/loss)
