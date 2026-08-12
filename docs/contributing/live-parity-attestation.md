# Live-Parity Attestation Runbook

This runbook is the operating procedure for changes that can affect live execution,
backtesting, market-data handling, indicators, DSL evaluation, risk, orders, fills,
or Insights attribution.

## What Runs When

Every feature starts with deterministic local tests. A live demo run is required when
the change can alter any part of the tick/bar-to-order path, broker integration,
warmup, replay, accounting, risk gates, or reporting. Documentation-only changes,
comments, and isolated tooling changes do not need a new live run unless they change
the promotion workflow or its evidence contract.

The live run is a promotion evidence step, not a replacement for unit and integration
tests. It runs against the exact immutable image built from `testing`, never a moving
`:edge` tag.

## Feature Workflow

1. Branch from `dev` and make one focused change.
2. Add or update deterministic tests for the changed behavior. For runtime changes,
   include both live-paper and replay/backtest assertions where applicable.
3. Run the normal pre-push checks and open a PR to `dev`.
4. After the PR reaches `testing`, resolve `ghcr.io/.../qkt:edge` to its digest and
   run the demo parity wave with that exact image. Keep the account in demo mode and
   use a unique strategy/book namespace for the run.
5. Generate the live-parity evidence bundle and attestation. Do not commit generated
   tick dumps, journals, databases, or account credentials to the repository.
6. Dispatch `paper-soak.yml` with the attestation path. The trusted runner verifies
   the exact SHA, image revision, demo mode, metrics, and every artifact hash, then
   uploads the immutable bundle.
7. Run `promote-to-main.yml`. It creates the `testing -> main` PR only when testing
   integration and the exact live-parity attestation both pass.
8. Merge that promotion PR after review, then verify main integration and Docker
   publishing. Keep the attestation URL, image digest, and evidence manifest in the
   handoff or promotion PR.

## What A Parity Wave Must Prove

The run may use one deliberately comprehensive strategy or several focused
strategies. A comprehensive strategy is acceptable only when its trace proves each
condition independently; a single final trade is not evidence that every branch ran.

The retained coverage artifact must identify the scenario, strategy/book namespace,
symbol, timeframe, input range, warmup range, and evaluation sequence for each case.
The cases must exercise, as applicable:

- raw ticks, ordinary bars, tick-resolved bars, and the supported higher timeframes;
- warmup and first-live evaluation without duplicate requests or state leakage;
- every indicator and math primitive used by the strategy, including combined and
  cross-symbol conditions;
- DSL comparisons, crosses, boolean composition, latches, timed conditions,
  sizing, exits, and re-entry;
- market, limit, stop, bracket/OCO, cancellation, timed exit, repeated submission,
  partial/scale-out, and risk-rejected orders;
- halt, drawdown, cooldown, max-trade, stale-data, clock-skew, and reconnect paths;
- gateway polling under concurrent symbols/strategies without dropped ticks or a
  blocked subscriber; and
- QKT Insights ingestion and per-strategy/book attribution.

Every order-bearing case must retain the causal chain:

`input tick/bar -> warmup state -> indicator/math values -> DSL result -> signal ->
risk decision -> order request -> gateway request -> broker acknowledgement -> fill /
cancel / rejection -> position and PnL accounting -> Insights event`.

The live window is replayed through the applicable `full-ticks-paper`,
`full-ticks-mt5`, and `bars-paper` modes using the same strategy configuration and
normalized input range. Compare event sequence, timestamps, quantity, side, price,
commission, swap, protection, final positions, PnL, and report/journal contents. Any
intentional normalization must be explicit in the parity artifact; unexplained
differences fail the attestation.

### Fast higher-timeframe testing

Do not wait an hour or four hours for a live clock to produce one H1 or H4 bar.
Use the gateway's historical-bar warmup to materialize the required prior state,
then run a short 1m live/tick window that crosses the H1/H4 boundary. The retained
parity fields must include positive `warmupBars`, `warmupTicks`, and
`barBoundaryTransitions`, plus `timeframesTested` containing every claimed
timeframe (for example `1m`, `1h`, and `4h`). The replay must use the same warmup
range and boundary timestamps. A short run without higher-timeframe warmup and a
boundary transition does not prove H1/H4 behavior.

## Attestation Contract

The verifier requires `attestationType: live-parity` and an immutable image digest.
The `parity` object must contain positive counts for strategies, indicators, math,
DSL scenarios, order types, ticks, bars, fills, parity comparisons, and Insights
events. It must contain zero `parityMismatches`, `unexplainedRejections`, and
`unexplainedOrderOutcomes`. Runtime metrics must report zero unreconciled positions,
zero unknown outcomes, and zero dropped ticks.

Six sibling artifacts are required and SHA-256 checked:

`health`, `journal`, `reconciliation`, `coverage`, `parity`, and `insights`.

Artifacts are disposable evidence, not source files. Store them on the trusted runner
and in the retained Actions artifact. Keep only the attestation metadata, hashes,
workflow URL, and image digest in the promotion record. Compress or cap large raw
captures before upload; never put credentials in any artifact.

## Failure Rules

Do not manufacture counts, reuse an attestation from another SHA, or waive a parity
mismatch. Fix the implementation or document a real, reviewed normalization and rerun
the affected matrix plus the regression suite. A failed run is useful evidence and
should remain outside the promotion bundle until the issue is resolved.
