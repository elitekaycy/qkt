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

## Final Promotion Record (2026-08-13)

The preceding sections are historical snapshots. The authoritative current
release state is:

- `dev` is `7c400906453cf6d75d3de10601cdbf285b3c7a90` and `testing` is
  `c818c0b44d7d4cf7070edc0bc9b22dea12a98a37`. No dev commit is absent from
  testing; the Windows promotion-gate fix and all preceding source/evidence
  fixes are present in both.
- Exact testing image:
  `ghcr.io/elitekaycy/qkt@sha256:9d21d5ec9009c16da94ac376afc5af1fccc221360ab09aac0226e0e59109a664`,
  revision `c818c0b44d7d4cf7070edc0bc9b22dea12a98a37`.
- The exact-image armed EMA run passed against the single local Exness demo
  account `436804390` via `127.0.0.1:5001`: real 0.01-lot entry and timed
  close, two accepted/fill events, one order post and one close post, zero risk
  rejections, and final flat positions/orders. One stale/recovery episode was
  recorded and retained as feed evidence.
- The exact-image read-only soak passed for 310 seconds with 31 health samples,
  105 live ticks, 160 warmup ticks, 29 candles, 11 stream candles, zero dropped
  ticks, zero venue deals, unchanged flat account, and latency tracking enabled
  (tick-processing p99 3.73 ms, max 26.34 ms). M1 and M5 traces were observed.
- Local attestation verification passed for the exact SHA/image. Trusted
  paper-soak workflow `31738623043` passed and uploaded immutable evidence.
- Promotion PR `#1006` passed build, integration, runtime-smoke, build-and-push,
  Windows packaging/install, and GitGuardian checks, then merged to `main`.
  Current main is `8ad1c109fc3231e1f0dab0e94e0b4d475a5b083a`.
- Main post-merge docs, integration, and Docker runtime-image smoke workflows
  passed (`31740355885`, `31740355930`, and `31740355830`).

The local gateway required one restart after an MT5 IPC timeout and recovered to
healthy/connected. This is an environment/session recovery observation, not a
QKT code fix. Future soak reports must retain stale/recovery and restart
evidence rather than relabeling the run as uninterrupted health.

## Notes Matrix Follow-up Evidence (2026-08-13)

The first re-entry/risk-gate slice of the previously unsealed matrix is now
sealed against the exact `c818c0b4` image and the single local demo account:

- `/var/tmp/qkt-validation/reentry-c818`: live `reentry` passed with two
  EMA-driven entries and two timed exits, four accepted/fill events, two entry
  posts, two closes, zero risk rejections, and final flat state. Its golden
  replay passed at `/var/tmp/qkt-validation/reentry-c818-replay`.
- `/var/tmp/qkt-validation/reentry-blocked-c818-2`: live
  `reentry_blocked_max_trades` passed with one real entry/exit and one
  `MaxTradesPerDay` rejection before transport. It produced one entry post, one
  close, one risk rejection, and final flat state.
- Both runs used generated EMA 3/5 M1 plus 5-minute streams, warmup ticks, real
  MT5 fills, engine/transport journals, and final account reconciliation. Each
  recorded stale-feed episodes that recovered before completion; these remain
  gateway polling evidence and are not counted as continuously healthy.

Remaining unsealed notes-matrix work is explicit: live pending limit/stop and
stop-limit trigger fills, cancellation/partial-fill waves, the other re-entry
and risk-halt recovery variants, portfolio/book isolation, and complete
live-vs-backtest coverage for every registered DSL/indicator/math capability.

## Pending Order Boundary Evidence (2026-08-13)

The local QKT CLI was used against the single local Exness demo account with a
unique magic and bounded `0.01`-lot intents. Resting BUY_LIMIT and BUY_STOP orders
were accepted by MT5, observed through the venue orders endpoint, cancelled by
their native tickets, and left zero pending orders, zero positions, and unchanged
balance/equity. A BUY_STOP_LIMIT request initially exposed a real gateway defect:
the gateway rejected the `stoplimit` field as unknown before reaching MT5.

The gateway fix is isolated in `mt5-gateway` PR #81, merged into gateway `dev` as
`17f5fe8f608b910fb1f486832e1c566ba74f060b` after test and Docker CI passed. The
fix accepts both native stop-limit order types, validates the limit price, and
forwards `stoplimit` into the MT5 trade request. Focused gateway tests pass (14),
and the rebuilt local image accepted a valid BUY_STOP_LIMIT (`retcode 10009`),
reported native type `6`, then cancelled it successfully. Final account state was
flat with zero pending orders and unchanged balance `99992.61`.

This proves placement and cancellation only. Stop-limit trigger-to-fill parity,
partial-fill waves, released gateway image promotion, and QKT image refresh remain
unsealed and must not be inferred from this evidence.

### Limit Trigger Probe

`/var/tmp/qkt-validation/pending-limit-trigger-c818-20260813T220239Z` placed a
BUY_LIMIT at `1.15305` while the quoted ask was `1.15306`. MT5 accepted the
pending order (`ticket 3085335993`), and the next poll observed the pending order
gone and one open `0.01` position at the same price. QKT then closed it and
reconciled the account to zero positions and zero pending orders. The gateway
deal range filtered by magic `948500` contains the entry at `1.15305` and the
exit at `1.15297`, with net demo PnL `-0.08` and zero commission/swap. This is
live trigger evidence, but it is not yet a full QKT golden/replay parity bundle;
the harness cleanup interruption and that replay comparison remain open work.

## Current Exact-SHA Promotion and Four-Case Wave (2026-08-13)

The authoritative current release state supersedes older snapshots above:

- `testing` SHA `e271822d4895cf16a662338c1f38090f77965485` was promoted through
  PR `#1010` and merged to `main` at `f275065947c9b967b85843ba1c12a8e16f90d587`.
- The exact testing runtime image was
  `ghcr.io/elitekaycy/qkt@sha256:1dbef063538c29e37a880e1544c3a07aad66d57c369d9377f86bbad1ca54ac23`,
  whose OCI revision is the exact testing SHA. Main post-merge Docker, integration,
  and docs workflows were dispatched on the merge commit.
- Fresh exact-image live evidence was generated at
  `/var/tmp/qkt-validation/exact-testing-e271822d-20260814-rerun2`. The local
  gateway was `127.0.0.1:5001`, MT5 demo login `436804390`, and the account was
  flat before and after the wave. The first attempted run was interrupted by an
  external probe and is invalid evidence; only `rerun2` is sealed.
- The four generated cases were EMA EURUSD, RSI GBPUSD, ATR EURUSD, and case-math
  GBPUSD. All read-only cases passed warmup, live ticks, M1/M5 bars, stale recovery,
  zero dropped ticks, journals, and flat reconciliation. ATR and case-math each
  produced a real 0.01-lot market entry and strategy-owned close; both had two live
  fills and passed `full-ticks-paper`, `full-ticks-mt5`, and `bars-paper` replay.
  Current result files identify QKT commit `e271822d` and `status: passed`.
- A fresh six-artifact live-parity attestation was generated and locally verified,
  then trusted workflow `31754382341` passed. The attestation is retained on the
  trusted runner at `/var/lib/qkt/soak/attestation.json`; generated artifacts are
  disposable and are not committed to source.

This wave is promotion evidence, not completion of the full notes matrix. Still
unsealed are stop/stop-limit trigger-to-fill golden replay, cancellation races and
partial fills, every re-entry and halt/cooldown recovery variant, H1/H4 live
boundary parity, portfolio/book isolation, QKT Insights attribution for this exact
wave, and exhaustive live-vs-backtest coverage for every registered indicator,
numeric function, DSL construct, order type, and strategy template/deployment mode.

## Higher-Timeframe Warmup Probe (2026-08-14)

The read-only exact-image probe at
`/var/tmp/qkt-validation/htf-e271822d-1786665912` passed against the same local
gateway and demo account. It exercised nine closed-bar warmup requests: M15 at
1-hour/1-day/2-day windows, H1 at 1-hour/1-day/2-day windows, and H4 at
4-hour/1-day/2-day windows. All bars were aligned, unique, and closed; the
account remained unchanged with zero positions, pending orders, and venue deals.
The probe is warmup/bar-ingest evidence only; it does not seal higher-timeframe
strategy signal or live-vs-backtest order parity.

## Parallel Static Risk-Rejection Matrix (2026-08-14)

The exact promoted image `ghcr.io/elitekaycy/qkt:edge` (QKT commit
`e271822d4895cf16a662338c1f38090f77965485`) passed the five-container local
demo matrix at
`/var/tmp/qkt-validation/risk-rejection-e271822d-20260814-run`. Maximum
quantity, maximum notional, far-price collar, measured-usage, and operator-halt
cases each produced a causal risk rejection before broker transport. The run
verified zero mutating gateway requests, zero order events, zero fills, and no
venue-state change while the five containers launched and deployed in parallel.

The result deliberately reports only `preTransportStaticRejectionsPassed` as
true. Stateful daily-loss, drawdown, loss-streak, and margin-floor fixtures
remain unpassed and are not inferred from this matrix.

## Stateful Risk-Halt Matrix (2026-08-14)

The exact promoted image also passed the four-container stateful matrix at
`/var/tmp/qkt-validation/stateful-risk-e271822d-20260814-run`. Persisted
strategy-daily-loss, global-daily-loss, global-drawdown, and loss-streak state
each tripped on live bars/ticks and produced the required causal halt and risk
rejection before broker transport. The run required zero mutating gateway
requests, zero order events, zero fills, and unchanged flat venue state.

The result reports `dailyLossPassed`, `drawdownPassed`, and `lossStreakPassed`
as true while `marginFloorPassed` remains false. Margin-floor requires an
owned live exposure and deterministic venue margin; it is still an explicit
unsealed fixture rather than an inferred pass.

## Exact-Image Insights Attribution (2026-08-14)

The fixed exact testing image `8d5b0cdde48e956db155793106e4dcf74bb1418c`
(OCI digest `sha256:3e7787c0751c862b0d8f6a039c042aa86515b09c4af5dfe142d6ad7333047bb9`)
passed the local demo attribution runner at
`/var/tmp/qkt-validation/insights-8d5b0cdd-20260814-final3`. The bundle covers
an M1/M5 read-only sibling and one bounded `0.01`-lot armed strategy. It
recorded warmups, live ticks, matched M1/M5 evaluations, a real entry and
strategy-owned exit, and final flat reconciliation.

Insights attribution sealed with two rule decisions, two decision-to-order
links, two submitted/accepted/filled orders, two trades, two accounted fills,
zero rejected events, zero dropped envelopes, and no duplicate event IDs. An
intentional collector outage queued 330 envelopes; restart replay drained the
journal. The open MT5 position was observed with the owning strategy before
the strategy close. Final pending orders and positions were zero.

This is exact-wave Insights and causal round-trip evidence, not exhaustive
coverage of every portfolio, deployment mode, indicator, DSL construct, or
order boundary in `notes.txt`.

## Order-Gateway Restart Evidence (2026-08-14)

The exact testing image `8d5b0cdde48e956db155793106e4dcf74bb1418c` passed the
order-bearing gateway restart runner at
`/var/tmp/qkt-validation/order-restart-8d5b0cdd-20260814-runtime/evidence/result.json`.
The bounded `0.01`-lot owner strategy opened a real EURUSD position, the local
MT5 gateway container was restarted while the position was open, and the same
strategy closed the persisted venue ticket after reconnect. The bundle records
two accepted/fill-accounted orders, six stale events with six recoveries, zero
dropped ticks, final flat state, zero pending orders, and deal-net equality with
the account balance delta (`0.02`). This seals restart/reconnect ownership and
accounting for this exact wave; it does not seal every order type or every replay
mode.

## Native Stop-Limit Boundary Evidence (2026-08-14)

QKT commit `d60ff76f` changes the MT5 protocol capability set to advertise native
`STOP_LIMIT` after the gateway support landed in mt5-gateway. Focused protocol,
translator, and broker integration tests passed, and the change merged into
`dev` as `a82a94d4` through PR #1015. A localhost QKT CLI probe using that build
placed a bounded `BUY_STOP_LIMIT` (`retcode 10009`, ticket `3086018968`), showed
the working order through the MT5 orders endpoint, and cancelled it through
`qkt bot cancel`; the final account was unchanged and flat. The retained probe
artifacts are at
`/var/tmp/qkt-validation/qkt-stop-limit-native-d60ff76f-20260814`.

The probe did not trigger a stop-limit into a fill, and therefore does not claim
stop-limit trigger-to-fill or live-vs-backtest parity. A separate exact-image
strategy run must still capture the trigger, fill, cancellation races/partial
fills, and golden replay before this order boundary can be promoted to `main`.

The temporary deployed-strategy probe at
`/var/tmp/qkt-validation/strategy-stop-limit-native-d60ff76f-20260814` also
confirmed warmup, DSL compilation, and live deployment. Its first stop-limit
decision occurred during a measured stale-feed episode, so QKT rejected it
before broker transport; the feed recovered and the account remained flat with
no pending order. This is evidence that stale-order suppression is fail-closed,
not evidence of uninterrupted feed health or a native stop-limit fill.

A separate QKT CLI trigger observation at
`/var/tmp/qkt-validation/qkt-stop-limit-trigger-d60ff76f-20260814` placed a
native `BUY_STOP_LIMIT` at `1.15392` with `1.15382` stop-limit price and polled
the real venue for 180 seconds. The market remained below the trigger, so no
deal or position occurred; QKT cancelled ticket `3086032248` and the account
finished unchanged and flat. This seals the resting-order/cancel boundary only;
the trigger-to-fill path is still unsealed.

## Exact Testing-Image Four-Case Parity Wave (2026-08-14)

The corrected generated wave was run against testing revision
`47e64f9372a32e611c0680e99123763e743848e4`, image
`ghcr.io/elitekaycy/qkt@sha256:d27dd965850866ea1ac4374b86c77a6fa3ccb9bb2551846c4631b8150662258b`,
and local gateway `0.3.9` on demo account `436804390` /
`Exness-MT5Trial9`. Evidence root:
`/var/tmp/qkt-validation/parity-47e6-retry-gb2`.

All four read-only cases passed with 310-second captures, exact symbol routing,
warmup/live tick and M1/M5 candle journals, golden captures, zero fills, zero
dropped ticks, zero queue depth, and unchanged flat account state:

- `atr-eurusd`: 156 live ticks, 160 warmup ticks, 2/2 stale episodes recovered.
- `ema-eurusd`: 159 live ticks, 160 warmup ticks, 2/2 stale episodes recovered.
- `case-gbpusd`: 263 live ticks, 160 warmup ticks, 2/2 stale episodes recovered;
  warmup and audit symbols are `EXNESS:GBPUSD`.
- `rsi-gbpusd`: 259 live ticks, 160 warmup ticks, 1/1 stale episode recovered;
  warmup and audit symbols are `EXNESS:GBPUSD`.

Each armed case then passed with one real strategy-owned entry and one strategy-
owned exit, two accepted and filled events, zero final positions/orders, and
golden replay comparison passed:

- `atr-eurusd/armed-live`: ticket `3086526183`, balance/deal delta `+0.04`.
- `ema-eurusd/armed-live`: ticket `3086540418`, balance/deal delta `-0.11`.
- `rsi-gbpusd/armed-live`: ticket `3086556383`, balance/deal delta `-0.22`.

Replay results are under each `armed-live/replay/result.json` and all report
`status: passed` with the same testing SHA. The final gateway account snapshot
was flat with zero margin. This wave proves the corrected four-case warmup,
tick/bar, indicator, DSL order path, fill accounting, stale recovery, and
golden replay on the exact image. It does not replace the remaining notes matrix
for stop-limit trigger fills, cancellation/partial-fill races, risk/margin
fixtures, portfolios/books, full indicator/math/DSL catalog, or Insights
attribution.

## Insights Verifier Follow-Up (2026-08-14)

The exact-image Insights run at
`/var/tmp/qkt-validation/insights-47e6-wave/cases/atr-eurusd` completed the
collector contract probe, read-only sibling, outage/restart replay, real entry,
strategy-owned exit, and ticket/deal attribution checks. It was not sealed
because the verifier wrote an empty `duplicate-event-ids.json` when SQLite
returned no duplicate rows; the subsequent integer comparison treated the empty
string as an error. This is a test-harness defect, not a trading-runtime or
collector finding. The partial run is explicitly not promotion evidence.

PR #1019 (`fix(scripts): normalize empty insights duplicate output`) normalizes
that empty query result to `[]`. Linux CI is green and Windows CI is pending;
the exact-image Insights run must be rerun after the fix is in the promoted
testing image before Insights attestation is claimed.

## Exact Testing-Image Higher-Timeframe Warmup (2026-08-14)

Clean testing-worktree probes passed for both `EXNESS:EURUSD` and
`EXNESS:GBPUSD` at `/var/tmp/qkt-validation/htf-47e6-clean-EURUSD/evidence/result.json`
and `/var/tmp/qkt-validation/htf-47e6-clean-GBPUSD/evidence/result.json`.
Both report testing SHA `47e64f9372a32e611c0680e99123763e743848e4`,
`qktDirty: false`, nine probes, and zero final positions/orders. Every M15/H1/H4
one-hour, one-day, and two-day request returned closed, aligned, unique bars.

The attempted XAUUSD probe was not sealed: the local demo gateway returned no
reviewed closed M15 one-hour bar set. This is classified as symbol/data
availability, not a QKT warmup pass or code failure; XAUUSD remains unproven.

## Exact Testing-Image Read-Only Catalog (2026-08-14)

The four-container catalog was rerun against the same testing image and local
gateway at `/var/tmp/qkt-validation/catalog-47e6-final-live/evidence/result.json`.
It passed with revision `47e64f9372a32e611c0680e99123763e743848e4`, image digest
`sha256:d27dd965850866ea1ac4374b86c77a6fa3ccb9bb2551846c4631b8150662258b`,
360 seconds, four parallel containers, five parallel tick symbols, 500 ms tick
polling, and no JVM or Docker resource restrictions.

The catalog covered numeric/candle indicators and math, cross-symbol M1/M5
mapping, session/history/stateful functions, and the volume-capability negative
case. All four cases passed their warmup bars, readiness vectors, live ticks,
constructed bars, joined evaluations, and financially read-only assertions.
Aggregate counts were zero gateway mutations, order events, fills, and venue
deals; account state stayed unchanged. The run recorded 7 stale episodes and
recovered all 7, with zero in-window disconnect warnings, zero unexpected
errors, and no dropped-tick failure. The earlier catalog dropped-tick result is
therefore superseded for this exact image, while the gateway stale/recovery
events remain an operational observation.

## Current Promotion Reconciliation (2026-08-14)

The remote refs were refreshed after the historical sections above were written.
The currently authoritative release refs are:

- `dev`: `f8d91366f413331a8d208c2c4cb73aa2c7262555`, including PR #1019,
  `fix(scripts): normalize empty insights duplicate output`.
- `testing`: `daf5b4639c53d3244672207d710db78f38c91e3c`, promoted from that
  `dev` revision. Its exact QKT image is
  `ghcr.io/elitekaycy/qkt@sha256:e7eb41cfe6300b7ec599b83db7c642e1aa66b7a18ef8f506d0e59abacbf15b6c`.
- `main`: `f275065947c9b967b85843ba1c12a8e16f90d587`, the last completed
  testing-to-main promotion. It does not yet contain PR #1019.

The corrected Insights verifier must be rerun against the immutable `testing`
image above before another promotion. Earlier Insights evidence generated from a
different image is historical and cannot attest this revision. The notes matrix
also remains open for stop-limit trigger/fill replay, cancellation and partial-fill
races, margin-floor, portfolio/book isolation, and exhaustive capability coverage.

## Corrected Exact-Testing Insights Attestation (2026-08-14)

The corrected verifier passed against QKT testing revision `daf5b463` and the
immutable QKT image
`ghcr.io/elitekaycy/qkt@sha256:e7eb41cfe6300b7ec599b83db7c642e1aa66b7a18ef8f506d0e59abacbf15b6c`.
Evidence is retained at
`/var/tmp/qkt-validation/insights-daf5-prep2/evidence/result.json`.

The run recorded M1/M5 warmup, live ticks, matched evaluations, a real bounded
entry and strategy-owned exit, and final flat reconciliation. Insights retained
two rule decisions, two decision-to-order links, two submitted/accepted/filled
orders, two trades, two accounted fills, zero rejected events, zero dropped
envelopes, and a maximum duplicate-attempt count of one. An intentional collector
outage queued 338 envelopes and restart replay drained them completely. The final
state had zero pending orders and zero positions.

This seals the Insights verifier fix for `daf5b463`; it does not close the other
notes-matrix gaps listed above.

## Focused Order and Persistence Regression (2026-08-14)

On the current checkout, the focused JUnit suite passed for MT5 simulator order
boundaries, stop-limit activation/fill, partial-fill slicing, cancellation races,
DSL stop-limit compilation/rendering, and concurrent state-file reads/writes.
`StateFileWriterTest.concurrent reads while writing never see a torn file` passed;
the run emitted expected slow-write warnings on the contended filesystem but no
torn reads or failed writes. Command:
`./gradlew test --tests 'com.qkt.trade.BotActionCompilerTest' --tests
'com.qkt.trade.BotDslRendererTest' --tests
'com.qkt.persistence.StateFileWriterTest' --tests
'com.qkt.broker.MT5BrokerSimulator*'`.

These are regression/unit results and do not substitute for the still-open
localhost MT5 stop-limit trigger/fill and live partial-fill evidence.

## Margin-Floor Rerun Status (2026-08-14)

A clean exact-`daf5b463` localhost fixture was exercised with the single Exness
demo account. It opened a real `0.01` EURUSD position, derived the dynamic floor
from the live margin level, rejected the probe before broker transport, and
recovered a probe position after the opener was flattened. The runner did not
finish its final journal/result sealing phase, so these captures remain unsealed
and are not promotion evidence. The account was explicitly verified flat after
cleanup. A sealed margin-floor result is still required.

## Sealed Exact-Testing Margin-Floor Fixture (2026-08-14)

The corrected runner passed against testing revision `ba1809217ad8f6c9db494054d8f0641a48a6704c` and immutable image
`ghcr.io/elitekaycy/qkt@sha256:35c82798dab413fe39dde48ff2dc81f8c62fd5f5e40c2a028d564887fdce6636`.
Evidence is retained at
`/var/tmp/qkt-validation/margin-ba1809-live/evidence/result.json`.

The fixture opened one bounded `0.01` EURUSD position with the opener role,
derived the floor from observed live margin level, rejected the probe before any
mutating gateway request, then allowed and filled the probe after opener flattening
and headroom recovery. Both strategy-owned positions were flattened; venue history
and account balance reconciled, with zero final positions and pending orders.
The aggregate result reports `marginFloorPassed: true` and
`productionReadiness: false` (the latter remains intentionally conservative).

## Exact d55 Live Matrix and Insights (2026-08-14)

All evidence below uses testing revision `d55f8f51d70831006615132cf86a36938b3a46f3`
and QKT image
`ghcr.io/elitekaycy/qkt@sha256:083052c7ce1467b01fe604b4634ce2ed33d081f1d3e4f33e71801295ee74402d`.
Generated evidence is disposable and retained outside the source tree.

- Four-case ATR, case-math, EMA, and RSI parity wave passed read-only warmup,
  live demo bracket, and full-tick/bar/MT5 replay comparisons at
  `/var/tmp/qkt-validation/parity-d55-live`. The account finished flat.
- Four-case catalog passed at
  `/var/tmp/qkt-validation/catalog-d55-live/evidence/result.json`, covering
  numeric/candle, cross-symbol and multi-timeframe, session/history, and volume
  capability rejection with zero mutations and zero dropped ticks.
- Five-case pre-transport risk rejection passed in isolation at
  `/var/tmp/qkt-validation/riskreject-d55-retry/evidence/result.json`.
- Four-case restored-state risk matrix passed in isolation at
  `/var/tmp/qkt-validation/stateful-d55-retry/evidence/result.json`.
- QKT Insights attribution passed at
  `/var/tmp/qkt-validation/insights-d55-scenario/evidence/result.json`: 341
  outage-queued envelopes replayed completely; two decisions/orders/fills were
  attributed to the owning strategy; no drops or duplicate attempts; final flat.

The intentional nine-daemon aggregate probe failed because the local gateway's
Waitress queue saturated and live tick feeds disconnected, causing deploy
timeouts. Each matrix passed when run alone. This is an explicit gateway
capacity limit, not a claimed production-scale pass, and needs a gateway/load
fix or an enforced concurrency bound before unrestricted multi-container use.

## d55 Attestation Dispatch

A six-artifact d55 attestation passed the local verifier and is retained at
`/var/tmp/qkt-validation/attest-d55-final`. Trusted workflow `31800477867` was
dispatched against testing d55, but its sole `qkt-paper-soak` runner is offline,
so it remains queued. Local verification cannot substitute for the trusted
workflow; main promotion remains blocked until the runner executes successfully.

## Main Promotion and Post-Merge Runtime (2026-08-14)

The exact testing revision `4ec9a9aa5d1a40f07d9dafa0ad3fd4bbacb6318e` passed the
trusted paper-soak workflow `31807997155` with image
`ghcr.io/elitekaycy/qkt@sha256:00d21e346e31206e28e88233bc144cdaf8a166c566390132afda8ad8df656d74`.
Promotion PR #1023 merged to `main` as `f73f404a5f0884fd6bb5d8a9853169a52a815810`.
Post-merge integration, Docker publishing, and docs workflows all passed.

Fresh exact-4ec evidence is retained outside the source tree:

- Four-case live/replay parity: `/var/tmp/qkt-validation/parity-4ec-live`.
- Fresh Insights attribution: `/var/tmp/qkt-validation/insights-4ec-fresh2`, with
  warmup M1/M5, 332 outage-pending envelopes fully replayed, two causal decisions,
  two fills, zero drops, and a flat account.
- Fresh catalog after gateway upgrade:
  `/var/tmp/qkt-validation/catalog-4ec-live-run3/evidence/result.json`.
  All four read-only containers passed warmup, indicators/math vectors, M1/M5
  bars, cross-symbol routing, volume-capability rejection, and zero venue deals.

The first exact-4ec catalog attempt failed for the correct reason: local gateway
`0.3.9` returned historical EURUSD bars ending at 14:16 while live ticks were at
14:31+, so QKT rejected warmup on a time-base mismatch. Restarting alone did not
refresh the stale history; replacing the local test gateway with released
`0.3.10` did. The failed and passing captures remain distinct evidence.

The persistent `sshbot2` forward stack was updated to immutable QKT image
`ghcr.io/elitekaycy/qkt:sha-f73f404` and verified with QKT revision
`f73f404a5f0884fd6bb5d8a9853169a52a815810`, healthy gateway `0.3.10`, healthy
Insights, 22 recovered strategies, zero dropped ticks, and zero inbound queue
depth. Its existing account and state volume were preserved. A restart logged one
persisted-state reconciliation for a strategy whose broker position was already
absent; no venue position or order was created by the rollout.

A fresh post-upgrade ATR live read-only run also passed at
`/var/tmp/qkt-validation/gateway0310-atr/evidence/result.json`: gateway `0.3.10`,
QKT `4ec9a9aa`, 160 warmup ticks, 259 live ticks, M1/M5 candles and evaluations,
464 audit events, zero dropped ticks, zero queue depth, and unchanged account,
positions, orders, and venue deals. Two short stale/recovery events were captured
and recovered; they did not produce orders or corrupt the audit.

The requested Kimi strategy-book archive named in `notes.txt` is not present in the
workspace or on bot2, so the three/four-book qkt-quant-live integration and its
per-book Insights separation remain unproven and are not claimed complete. Bot2's
forward stack also continues to emit intermittent stale-data suppression/recovery
events despite a healthy gateway; this remains an operational follow-up rather than
a completed months-long stability claim.

A bot2 log sample covering the 15 minutes ending 2026-08-14T15:04Z contained 75
stale-gate events and 75 matching recoveries, with zero unrecovered symbols at the
end of the sample. Recovery completed in roughly two seconds for the captured
batch. Gateway logs contained no disconnect, timeout, or polling error in the same
window. This confirms the gate is failing closed and recovering, but does not prove
continuous quote freshness or long-duration capacity under the full forward load.

The standalone `qkt-quant-live` deployment defaults were then aligned with the
verified runtime: PR #7 (`d184503`) pinned QKT `sha-f73f404` and gateway `0.3.10` in
the example environment, Compose default, and deploy workflow. It merged to that
repository's `main` as `ae9e3a8e5e80a61635cca61c1211b2ef76964fe3` at 15:07Z; its
Compose configuration check and GitGuardian check passed.

The bot2 Insights database was also inspected read-only: one instance
`forward-bench`, 40 distinct strategy identities, and 20,485 retained events. The
three running portfolio namespaces (`forward_bench`, `forward_bench_2`, and
`forward_bench_3`) are present as separate strategy IDs, so strategy-level
attribution is separated. No portfolio aggregate rows are currently populated;
that is a remaining book-level analytics gap, not evidence of account-wide trade
bleed. The 22 historical `gateway.unreachable` events are dated August 12, before
the 0.3.10 rollout; the current post-rollout sample had no gateway transport
errors.

Bot2 preflight was rerun against the promoted QKT image for all three deployed
portfolio files (`forward_bench.qkt`, `forward_bench_2.qkt`, and
`forward_bench_3.qkt`); each passed broker identity, symbol metadata, and data-field
checks. This is deployment/configuration proof only, not a substitute for the
missing live order-bearing proof of every book.

Existing shared-account Insights evidence at
`/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-live/evidence/result.json`
does prove two simultaneous live strategy owners can each complete two fills and
return flat on one account: the collector retained both instances, its causal
contract probe passed, and cross-owner causal leakage was false. This closes the
strategy-owner isolation case, but it does not create portfolio aggregate rows for
the three bot2 books.
