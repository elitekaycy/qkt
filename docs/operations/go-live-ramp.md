# Go-live ramp policy

The staged path from "the backtest looks good" to trading full size with real
money. Each stage has measurable EXIT CRITERIA — "looked fine for a while" is not
a gate. This policy implements FIA §5.3.9 (restricted limits on substantive
change) and the practitioner norm of never meeting production at full exposure;
Knight Capital is the canonical case of skipping it.

The ramp is the LAST control, not the first: the 2026-06-10 audit's P0 fixes
(#356-#368), the pre-trade caps (#393), the kill switch (#394), and halt
persistence (#380) must be on the deployed image before stage 2.

## Stage 1 — demo burn-in (minimum 30 days, varied regimes)

Run the exact strategy file, engine image, and config intended for real money on
the demo account. The window MUST span:

- one high-impact news week (NFP or FOMC),
- one weekend close and re-open (gap behavior),
- the MT5 daily break for the traded symbols.

Exit criteria — every one, not most:

- [ ] Zero unexplained order states (no order ever needed a manual state fix).
- [ ] Zero reconciliation deltas between engine and broker (positions, realized PnL).
- [ ] Zero unhandled disconnects (every gateway/feed outage produced its alert and recovered).
- [ ] Halt → resume and `qkt kill --flatten` each exercised DELIBERATELY at least once.
      A kill procedure that has never been run is not a control.
- [ ] Live fill data (slippage, spread, swap, commission) fed back into the backtest
      cost model; demo equity lands inside the re-run backtest's expectation band.

## Stage 2 — micro-real (2-4 weeks at 0.01 lots)

Real-money broker behavior differs from demo: commission schedules, swap,
requotes, fill quality. Keep `risk.measured_usage_hours` active and
`risk.max_order_qty` at the venue minimum.

Exit criteria:

- [ ] Per-trade economics (spread + commission + swap per round trip) within the
      modeled bounds used in stage 1's re-run.
- [ ] Clean daily reconciliation for the whole stage.
- [ ] Zero risk-control breaches (no cap rejections that surprised you, no halts
      from engine faults).

## Stage 3 — ramp 25% → 50% → 100% (minimum 1 month per step)

Raise `risk.max_order_qty` / `max_order_notional` and the strategy's sizing to
25% of target, then 50%, then 100%.

Gate for EACH step:

- [ ] Tracking error vs the backtest stays within the band established in stage 1.
- [ ] No system-fault halts (strategy-loss halts are fine — that's the system working).
- [ ] Kill switch exercised once at the CURRENT size.

## The reset rule

Any of the following freezes the ramp and restarts the CURRENT stage after the
root cause is fixed and deployed:

- an order in an unknown/unexplained state,
- a missed alert (something went wrong and nothing paged),
- a reconciliation delta,
- any manual fix-up of engine state.

No exceptions for "it was probably nothing" — the ramp exists precisely because
"probably" is not a number.

## References

- FIA Guide to the Electronic Trading Risk Controls (2015), §5.3.9
- MiFID II RTS 6 (deployment and testing of algorithms)
- 2026-06-10 engine audit: `docs/audits/2026-06-10-full-engine-audit.md`
- Demo-observation gate: #33; measured-usage deploys: #399
