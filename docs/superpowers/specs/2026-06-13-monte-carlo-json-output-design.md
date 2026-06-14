# Monte-Carlo summary in backtest JSON output — design

## Context

Prop-firm risk sizing (the qkt#348 family) and the qkt-forge G6 risk-fit gate need to size a
strategy against its **drawdown tail**, not the single realized drawdown of one backtest path. The
realized equity curve is one sample of the future; sizing against it under-reserves for sequencing
risk (the same trades in a worse order draw down further).

qkt already computes exactly this tail. `ReportBuilder` runs a trade-bootstrap Monte-Carlo
(`MonteCarlo.run`, 1000 simulations, seed 42, when there are ≥ 30 trades) and attaches a
`MonteCarloSummary` to the `PerformanceReport`: final-equity percentiles, **max-drawdown P5/P95**,
probability of a negative final, and an equity fan. But only the HTML report reads it —
`backtest --json` omits it entirely. So a machine consumer (qkt-forge) has no access to the MC tail.

## Change

Serialize the existing `MonteCarloSummary` into `backtest --json` as a `monteCarlo` object, or
`null` when fewer than 30 trades made MC unavailable:

```json
"monteCarlo": {
  "simulations": 1000,
  "finalEquityP5": -120.5, "finalEquityP50": 80.0, "finalEquityP95": 310.0,
  "maxDrawdownP5": -0.22, "maxDrawdownP95": -0.04,
  "probabilityNegativeFinal": 0.18
}
```

- The `maxDrawdownP95` (worst 5% of resampled total drawdowns) is the field risk-fit sizes the
  account **total**-drawdown limit against.
- The equity fan (`equityFanByTradeIndex`) is an HTML-visualization detail and is **not** serialized
  — it is a large per-trade array with no role in sizing.
- Purely additive: no change to backtest execution, the text report, or any existing JSON field.

## Out of scope

- **Daily-drawdown MC.** The prop daily-DD limit is sized against the realized `maxDailyDrawdown`
  already exposed in `--json` — the worst observed intraday daily drawdown, which is a conservative
  tail across all trading days (≥ any per-day P95). A separate per-day intraday-DD distribution /
  Monte-Carlo is a possible later refinement if realized-worst proves too conservative; it is not
  required to unblock G6 risk-fit.
- **Runtime DD halts and the sizing solve itself.** Halts already exist (`halts` in the report);
  the sizing solve lives in qkt-forge G6, consuming this output.

## Risk

Low. Additive output field; the MC summary is already computed on every ≥30-trade backtest, so no
new computation and no behavior change. The only surface touched is `ReportPrinter.printJson`.

## References

- qkt#348 (prop-firm risk management prerequisite).
- Consumer: qkt-forge G6 risk-fit gate (`docs/plans/2026-06-13-robustness-gate.md` in qkt-forge).
