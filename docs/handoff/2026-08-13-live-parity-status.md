# Live Parity Status: 2026-08-13

## Branch and release state

- Working branch: `fix/soak-live-parity`.
- Current head: `5e9169b0` (`fix(scripts): accept full qkt commit in image checks`).
- `dev` and `testing` already contain the prior attestation CLI change at the
  testing SHA `a7cca220e974c819da2d464c328b1c2417341cab`.
- No paper-soak attestation has passed on the trusted GitHub runner yet, so
  promotion to `main` remains correctly blocked.

## Sealed live evidence

Gateway: MT5 demo login `436804390`, server `Exness-MT5Trial9`, gateway `0.3.10`.
The account was flat after every case and the final account query was flat.

Evidence root: `/var/tmp/qkt-validation/parity-a7cca220-full-20260813T0602Z`.

- Four generated cases ran: EMA EURUSD, RSI GBPUSD, ATR EURUSD, and case-math
  GBPUSD.
- All four read-only warmup/tick/bar cases passed.
- All four armed market-bracket cases passed with real 0.01-lot demo orders,
  strategy-owned closes, zero final positions/orders, and balanced account/deal
  deltas.
- Every case has checksum-verified evidence and a sealed `result.json`.
- Every replay comparison passed: full-tick order journals, bar order timing,
  initial and broker-adjusted protection, and MT5-simulation fill parity.
- The generated capability catalog includes all registered indicators and
  numeric functions (including EMA, RSI, ATR, Z-score, lag, correlation,
  percentile/rank, regression, volatility, session, and candle functions).

## Tooling fix

`run-container-round-trips.sh` previously accepted only the short SHA form in
`qkt --version`. The exact testing image emits the full SHA, so a valid image was
rejected before starting containers. The runner now accepts either short or full
commit identity and still fails closed on a mismatch. `bash -n` passes.

The first concurrent Insights attempt also correctly caught two scenarios with
different starting balances. Fresh equal-baseline scenarios were prepared; the
collector health and causal contract probe passed. The armed concurrent run must
be regenerated against the hardened branch/image before it can be called a pass.

## Concurrent Insights evidence

The follow-up harness fix was merged to `dev` as PR #996. The exact local build
and matching container image for commit `f75a089df5626087e0db7631de431f0c1c540cd9`
completed a real two-strategy same-account run:

- Evidence root: `/var/tmp/qkt-validation/insights-f75-live-full`.
- MT5 demo account: `436804390` / `Exness-MT5Trial9`; gateway `0.3.10`.
- EURUSD and GBPUSD deployed concurrently with magic `919600` and `919601`.
- Distinct real MT5 tickets: `3081426457` and `3081426434`.
- Each strategy produced two fills/two deal legs, strategy-owned close, and zero
  final positions/orders.
- The base round-trip passed ownership, M1/M5 stream/evaluation, indicator
  traces, bracket protection, audit and transport checks.
- Insights result passed: two retained instances, isolated strategy attribution,
  no cross-owner causal leakage, no sequence gaps/regressions, and both books
  flat. SQLite retained 20 strategy events plus 8 account-state events per
  instance.
- The freshness evidence records the quiet-market behavior. The final quote was
  fresh and all samples stayed below the 60-second diagnostic ceiling; occasional
  older samples remain visible in `tick-freshness-gate.jsonl` rather than being
  hidden.

## Next execution

1. Build/publish a QKT image for the hardened commit, or run the concurrent
   runner from a clean checkout at that exact commit.
2. Prepare two equal-baseline scenarios and run the armed shared-account
   Insights round-trip. Require both daemons to complete, isolated instance and
   strategy attribution in SQLite, and a flat account.
3. Run the read-only catalog and higher-timeframe warmup suites against the same
   exact commit/image.
4. Assemble canonical `health`, `journal`, `reconciliation`, `coverage`,
   `parity`, and `insights` artifacts from those sealed outputs; run
   `qkt soak report` and `scripts/verify-paper-soak-attestation.py` locally.
5. Obtain a successful exact-SHA paper-soak workflow on the trusted
   `[self-hosted,qkt-paper-soak]` runner, then promote `dev -> testing -> main`
   through the protected PR gates. Do not fabricate or bypass the attestation.

## Safety

The live lock is account-scoped. Keep the gateway on the demo account, arm only
with the explicit local approval token, verify flatness before and after each
run, and never treat a partial runner directory as evidence.
