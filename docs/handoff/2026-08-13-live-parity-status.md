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

## Higher-timeframe evidence

The exact `f75a089d` local build completed the read-only higher-timeframe probe at
`/var/tmp/qkt-validation/htf-f75-095216`. It passed closed, aligned, unique bar
retrieval for M15, H1, and H4 using one-hour, one-day, and two-day warmup sizes;
the account remained unchanged, with zero positions, orders, and venue deals.

The four-container capability catalog was started at
`/var/tmp/qkt-validation/catalog-f75-run`. All four daemons reached healthy
running state with zero dropped ticks and produced per-case health, evaluation,
volume-rejection, latency, and runtime-log evidence. Its aggregate `result.json`
was not sealed after cleanup, so this catalog run is intentionally incomplete and
must be rerun before it is used for attestation.

## Exact testing-gate attempt

Testing now points to `50f59fc06a8b5f9334800a00022cd0aebde05713`, and the published
edge image was verified as `ghcr.io/elitekaycy/qkt@sha256:aee5fe526f50d47fb1203ad5e93b46a56fa294b81a858ff0ac1776d1fb0d22a5`
with the matching OCI revision. A real two-symbol run against that image opened
and closed both demo positions, but did not seal an aggregate result: the local
gateway produced recovered stale-data windows and both live feeds later logged a
reconnect wait. This run is retained as failed/incomplete evidence, not promoted
into an attestation.

The paper-soak workflow was dispatched on the exact testing SHA with a trusted
runner. It failed closed because `/var/lib/qkt/soak/attestation.json` was absent.
No attestation was fabricated. Main promotion remains blocked until a complete
exact-SHA bundle is generated and placed at that runner path.

## Next execution

## Follow-up evidence (2026-08-13)

The exact `3f1873873acd8d60e02ab45fe09ea30dcdc71537` build has now passed the
following additional local checks against the single local Exness demo gateway
(`127.0.0.1:5001`, account `436804390`):

- The static generated four-case parity verifier passed for `ema-eurusd`,
  `rsi-gbpusd`, `atr-eurusd`, and `case-gbpusd`. The generated catalog contains
  59 indicator capabilities and 15 numeric functions. This is a preparer and
  contract check; it is not a claim that every capability has already been
  exercised with a real order.
- `MT5BrokerIntegrationTest` and `MT5PositionPollerCloseTest` both passed in a
  focused Gradle run. This covers the protection-registration race, pending and
  partial fills, cancellation, ambiguous outcomes, recovery, and poller close
  attribution regressions.
- The isolated static risk matrix passed five causal rejection cases; the
  stateful matrix passed four restored-state halt cases. Both remained flat and
  produced zero transport mutations for rejected decisions.
- The controlled margin-floor fixture passed. It observed a real 0.01-lot demo
  opener, rejected the probe before transport while below the dynamic floor,
  accepted the same probe after headroom recovered, and flattened the recovered
  position. The account was flat after cleanup.
- The exact-build higher-timeframe warmup probe passed M15, H1, and H4 one-hour,
  one-day, and two-day requests with aligned unique closed bars and no account
  mutations.

The follow-up harness change `fix(scripts): accept full qkt image revisions`
(`9c8f13e0`) is merged to `dev` as PR #1002. Dev CI for the current docs head
`865ef81e770dd47182f1b92a85ab363b5932ce32` is green. `testing` and `main` still
point at the prior promoted code (`3f187387...` and `b478062b...` respectively);
any promotion of a newer SHA requires a newly built exact image and regenerated
exact-SHA evidence.

### Remaining notes-matrix gaps

The following are deliberately still open and must not be represented as
attested: explicit live limit/stop-limit/stop-order fills, cancellation and
partial-fill waves, timed exits and re-entry across every strategy family,
cross-book portfolio attribution/isolation, and a complete live-vs-backtest
comparison for every DSL/indicator/math capability. The catalog's prior live
run also recorded a dropped tick during a gateway disconnect; this is retained
as a feed-resilience observation to classify and rerun, not hidden as a pass.

## Exact testing-SHA follow-up (2026-08-13)

The exact testing image was refreshed at `11d5793e4f0ff56a2f1da10918b7e539a17d4ef7`
(`ghcr.io/elitekaycy/qkt@sha256:c285abfb6ddc6748467285d450770936d9c199bd839d038c94858288a125bc08`).
The four-container read-only catalog ran locally against the demo gateway for the
required 330-second window. Numeric/candle, cross-symbol/multi-timeframe,
session/history, and volume-negative cases all emitted their expected traces and
the account stayed flat. The run failed closed because the cross-symbol case
reported one dropped tick after a gateway disconnect/recovery; no catalog result
was sealed and it is not promotion evidence. The metric includes late pipeline
ticks as well as ingress shedding, so this remains an explicit stress observation
to rerun and classify, not a fabricated zero.

A fresh exact-SHA two-container armed wave then placed and closed real 0.01-lot
EURUSD/GBPUSD demo positions and reconciled the account, but the runner failed
closed on a false runtime error: the MT5 position poller observed the expected
fill-anchored SL/TP normalization before the asynchronous broker callback had
registered the protection change. This exposed a real ordering race. The fix is
on the current feature branch: register expected protection before both sync and
async venue requests and remove it on request failure. Focused
`MT5BrokerIntegrationTest` and `MT5PositionPollerCloseTest` suites pass; the live
wave must be rerun from the resulting promoted image before attestation.

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

## Main promotion and follow-up audit (2026-08-13)

The hardened protection-ordering fix is now merged to `main` through promotion
PR #1000 at merge commit `b478062bb63a483bc1d019cd1b2530c8938276d1`.
Required build, integration, Docker/runtime-smoke, Windows packaging/install,
GitGuardian, and exact-image paper-soak checks passed. Main post-merge
integration run `31702307730` also passed.

Exact testing SHA `3f1873873acd8d60e02ab45fe09ea30dcdc71537` evidence:

- Armed EURUSD/GBPUSD live round trip plus full-ticks-paper, full-ticks-MT5,
  and bars-paper replay: `/var/tmp/qkt-validation/insights-3f-live` and
  `/var/tmp/qkt-validation/final-3f-replay-{a,b}`; both strategies opened and
  closed real 0.01-lot demo positions and the account finished flat.
- Insights causal round trip passed with two isolated instances, no sequence
  gaps/regressions, and no cross-owner attribution leakage.
- Restarted local-gateway read-only load soak passed at
  `/var/tmp/qkt-validation/load-3f-122354-610042/evidence/result.json`: 650
  seconds, two containers, four symbols, eight 1m/5m streams, controlled
  restart, state restore, 0 dropped ticks, and zero gateway mutations.
- Paper-soak workflow `31701168665` passed using the immutable QKT digest
  `sha256:f69102f813e79831e79a41f51223c3aae4e1b70c07bd749e8edac7981e097f4c`.

The follow-up four-case catalog was rerun after the gateway restart. Numeric,
cross/multi-timeframe, session/history, and volume-negative cases emitted their
expected traces and remained financially read-only, but the aggregate run again
failed closed because the numeric case recorded one dropped tick during a feed
disconnect. This is not promotion evidence and remains an open gateway/feed
resilience gap. The numeric trace itself covered EMA, SMA, WMA, DEMA, TEMA, HMA,
RSI, standard deviation/variance, z-score, regression, percentile rank, skew,
efficiency ratio, lag, MACD, Bollinger, extrema, math functions, ATR, Williams R,
CCI, stochastic, Keltner, DI, and ADX. Evidence root:
`/var/tmp/qkt-validation/catalog-3f-125922-704966-live`.

The broader notes matrix is therefore still incomplete: order-bearing coverage
for limit/stop/cancel/partial-fill/risk-halt paths, higher-timeframe H1/H4
boundary parity, and portfolio/book isolation remain to be executed and sealed.
