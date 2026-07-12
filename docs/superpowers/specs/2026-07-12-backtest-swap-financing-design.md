# Backtest Swap Financing Design

## Scope

Implement deterministic overnight swap accrual for backtests and expose the configured
long/short rates to strategies. This resolves the shared financing gap in #644, #694,
#695, #697, #698, and the swap portion of #699. Issue #696 also requires a separate
point-in-time central-bank policy-rate feed; that data capability remains open.

## Instrument Contract

`InstrumentMeta` gains these backtest fields:

- `swapLongPoints`: signed points per lot per rollover for a long position.
- `swapShortPoints`: signed points per lot per rollover for a short position.
- `swapRolloverHourUtc`: UTC hour at which the broker applies financing, default `21`.
- `swapTripleDay`: weekday whose rollover uses a 3x multiplier, default `WEDNESDAY`.

Positive points are credits and negative points are debits. Zero preserves existing
behavior. YAML uses the same camel-case names. Invalid rollover hours and non-finite
configuration fail at load/construction time.

## Accrual Semantics

For each open strategy leg held across a configured rollover boundary:

```text
native cash = signed swap points
            * pointSize
            * contractSize
            * absolute lot quantity
            * day multiplier
```

The multiplier is `3` on `swapTripleDay`, `1` on other Monday-Friday rollovers, and
`0` on Saturday/Sunday. This avoids double-counting weekends already represented by
the triple rollover. The native quote-currency cash is converted through the existing
`AccountingEngine` at the rollover timestamp and the last pre-boundary mark.

Accrual runs before processing a tick at or after the boundary. A position opened on
the boundary tick does not receive that rollover; a position closed on the boundary
tick does. Data gaps may cross several boundaries, which are applied in chronological
order. A leg must have `openedAt < boundary` to qualify.

Normal tick cost is bounded: only configured rollover-hour bucket comparisons run per
tick. Strategy leg books are scanned only when a boundary is crossed.

## Accounting And Risk

The shared `TradingPipeline` remains the single writer for account and strategy PnL.
The backtest rollover engine sends each converted cash flow through a dedicated pipeline
method that updates:

- account realized PnL;
- per-strategy realized PnL;
- daily PnL and drawdown anchors;
- halt-rule evaluation.

Financing is not a trade close, so it does not increment trade count, win/loss streaks,
or pacing outcomes.

## Reporting

`PerformanceReport.swapPaid` is a signed cost bridge:

- positive: net financing paid;
- negative: net financing credited.

Reported PnL is already net of swap, so pre-financing PnL is
`totalPnL + swapPaid`. Daily PnL includes rollover cash on its UTC boundary date.
Text, CLI JSON, report-bundle JSON, and HTML surfaces disclose swap separately from
commission.

## DSL

Stream metadata exposes `swap_long_points` and `swap_short_points`. These resolve from
the same validated `InstrumentRegistry` as `tick_size` and `contract_size`, allowing a
strategy to gate carry direction on the configured broker rate sign.

## Verification

Focused tests must prove:

- YAML parsing, defaults, and validation;
- long credit and short debit formulas;
- ordinary, triple-day, weekend, gap, open-at-boundary, and close-at-boundary behavior;
- independent/hedged leg attribution without netting away financing;
- account-currency conversion;
- daily-loss risk includes swap;
- report and DSL surfaces carry the configured values;
- zero-rate configurations remain byte-compatible with current backtests.
