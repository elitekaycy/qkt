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
   `scripts/live-validation/run-attestation.sh --profile <file>` does this end to end from a
   clean checkout of the testing commit: build, wave, insights attribution, bundle, verify.
   It writes `attestation-run.json` after every stage and exits non-zero on any failure.
   Its preflight resolves the commit's `:edge` digest from the docker workflow log before
   the build, so it needs a `gh` that can read run logs (2.55 returns them empty; 2.88 works):
   a run that cannot pin its image stops in seconds with the `gh` version in the reason.
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

The supported indicator and numeric-function inventory is read from the repository's
validation catalog, not guessed from a sample strategy. The suite manifest records
that inventory and each run must classify every capability as covered, not applicable
with a reason, or an explicit gap. A four-case smoke wave is therefore useful for
fast feedback but cannot be called exhaustive when capabilities such as ZSCORE,
CORRELATION, BETA, PERCENTILE_RANK, MACD, BOLLINGER, rank functions, or session and
volume indicators were not exercised.

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

The verifier requires `attestationType: live-parity`, an immutable image digest,
and a fresh opaque `runId` plus `inputFingerprint`. The trusted runner maintains a
locked used-run-id ledger, so replaying or editing a previous attestation cannot
pass as a new run. The fingerprint must be calculated from the exact captured input
window and strategy/config/source checksums; changing only timestamps is invalid.
The `parity` object must contain positive counts for strategies, indicators, math,
DSL scenarios, order types, ticks, bars, fills, parity comparisons, and Insights
events. It must contain zero `parityMismatches`, `unexplainedRejections`, and
`unexplainedOrderOutcomes`. Runtime metrics must report zero unreconciled positions,
zero unknown outcomes, and zero dropped ticks.

Eight sibling artifacts are required and SHA-256 checked:

`health`, `journal`, `reconciliation`, `coverage`, `parity`, `insights`, `catalog`, and `trace`.

`trace` is the causal chain of every engine order, live against replay
(`scripts/live-validation/lib/causal_trace.py`): for each wave case its golden bundle (engine journal, MT5 transport
under `gateway/`, each gateway account's transport under `venues/<LABEL>/`) is joined by engine order id with its
`full-ticks-mt5` replay report, and the decision bar, the approved request, each fill's side, size, price and costs
and the realized PnL it booked are compared exactly. A price difference passes only with a recorded cause: the replay
filled at the price the venue was asked for and the venue filled at a quote it captured after the request
(`venue-latency`), within its venue kind's bounds on latency after the decision and price distance from the replay
(`VENUE_LATENCY_BOUNDS`: MT5 2500 ms and 5 bps, a gateway 1000 ms and 3 bps, recorded in the trace); a realized difference passes only when it is exactly what those explained prices add up to. Every
other difference, and an order on one side only, is a parity mismatch: the trace's count is `parity.parityMismatches`,
and the verifier refuses a bundle whose trace and parity disagree.

`catalog` lists every required case and stage with each attempt that led there. The verifier refuses a bundle in which
a ready case at this commit has no pass, a case or stage is bound to another commit (or, for a carried case, another runtime
fingerprint or definition, see below), profile, account or CLI build,
a failed attempt is hidden, any evidence is over 24 hours old at assembly, or a core lane is missing.

## Evidence carried between commits

`testing` moves with every merge into `dev`, and a release is of one named candidate commit, so the attestation of the
candidate stays valid however far `testing` has moved. Carry exists for the other case: restarting on a *newer*
candidate whose shipped artifact is the same.

A passed catalog case is carried from commit X to commit N only when **all** of these hold, each checked by the run and again
by the verifier, which recomputes them from git and trusts no declaration in the bundle:

- **The runtime fingerprint is equal.** `scripts/live-validation/lib/runtime_fingerprint.py` hashes every tracked file
  except an explicit deny-list of paths known not to affect what is built or shipped: `src/test`, `tests`, `docs`, `.github`,
  `config`, `data`, `strategies`, `examples` (except `examples/bot`), `attestation`, the harness
  (`scripts/live-validation`, `scripts/release-proof`), the promotion scripts by name, and root metadata. A path nobody
  classified (a new directory, a new script) is in the fingerprint, so it invalidates carried evidence until someone
  reviews and adds it to the list. File identity is git's blob id and mode.
- **The artifact hash is equal, when both builds have one.** The built CLI is not byte-reproducible across commits:
  `build-info.properties` carries the git sha and the build time, and `qkt --version` must name the commit. So the CLI's own
  hash cannot be the key. `artifact_hash` instead hashes the installed tree by content (every jar entry's name and bytes, never
  timestamps or order, minus `build-info.properties`). It catches drift the source list cannot see (a dependency or toolchain
  change). It is an extra gate, not a replacement: the verifier cannot rebuild, so the source fingerprint is what it proves
  independently. The mode is recorded in the binding (`carryMode`: `artifact+source` or `source`).
- **The case's own definition is equal**: its files under `attestation/cases/<lane>/<id>/`, its lane's entry in
  `attestation/lanes.yaml`, and the harness that runs it. A change to one case reruns that case; a harness change reruns all.
- **The profile file, the account and the environment are equal**: the JDK and the image ids of the profile's
  `gateway_images`. A profile without `gateway_images` carries nothing.
- **The evidence is under 24 hours old**, as everywhere.

A commit that changes anything runtime reruns ALL cases. There is no per-lane guess from code paths: the engine is one
Kotlin program with no sound map from a source file to the lanes it affects.

**Never carried:** the parity wave (its run id is spent by paper-soak and stamped with the commit), the release proofs, the
insights scenario, and the shadow lane. These run on every candidate. If the fingerprint or the environment cannot be
computed, the binding is the exact-commit one and nothing is carried.

Every carried case is logged (`evidence carried from commit X (same runtime fingerprint F): <case>`), recorded in the
ledger, and shown in the bundle's catalog evidence: `ranOnSha`, `definitionSha256` and `carriedFrom` per case, and
`carry.summary` with the counts carried, kept from this commit and freshly run. The verifier refuses a v2 bundle (no
`--repo-root`), a fingerprint that is not the verified commit's, a case that ran on a commit shipping something else, a
changed definition, and a carry that is not logged as one.

**Backstop.** `run-attestation.sh --no-carry` (and `release-unattended.sh --no-carry`) runs every case on the commit. Run
it on the testing head on a schedule: the carry rules are then audited by real runs, and a carry that would have hidden a
failure shows up as a fresh failure.

Artifacts are disposable evidence, not source files. Store them on the trusted runner
and in the retained Actions artifact. Keep only the attestation metadata, hashes,
workflow URL, and image digest in the promotion record. Compress or cap large raw
captures before upload; never put credentials in any artifact.

## Failing Fast And Recovering

No attestation against live venues is fully deterministic: a venue can drop its link, a demo server can stall, a
quote can go quiet. These rules do not hide that. They make a failure quick, correctly named and cheap to recover from.

**Preflight.** `scripts/live-validation/lib/attest_preflight.py` runs in the first minute of `run-attestation.sh`, and
before the CI wait in `release-unattended.sh`. It reports every failure at once: gateways (with retries), account flat
(naming tickets, and distinct from "unreachable"), latency, disk, memory, tools, tokens, key file, Docker image,
data corpus, runner, venue links, the account lock and the plan for each session-bound lane's window. Thresholds are
proposals, not measured limits; set `preflight_min_disk_gb`, `preflight_min_mem_gb`, `preflight_max_latency_ms` or
`session_wait_max_h` in the profile.

**Failure classes** (`lib/attest_classify.py`). `infrastructure` (timeouts, 5xx, refused connections, a venue link
down) earns up to three reruns after 30, 60 and 120 s. `behaviour`, the default and anything with a failed assertion among
its problems, earns one. `environment` (full disk, a missing tool or file) and `session` (the market closed) earn none. A
retry reruns the whole case with all its assertions. Every attempt stays in the result and the bundle, and a case that needed
one is marked `flaky`. There is no quarantine and no skip.

**Resume.** Rerunning `run-attestation.sh` on the same commit keeps evidence bound to the same full SHA, profile file,
account and CLI fingerprint, and at most 24 hours old (`lib/attest_resume.py`). The age allows the next day's US session
to recover a lane while keeping one release's evidence from one working day. Anything else starts clean.

| Part of a run | On a rerun |
|---|---|
| Preflight, sweep, bundle, verify | Always repeat |
| CLI build | Kept while its fingerprint matches; a rebuild invalidates everything |
| Proofs | Kept unless the corpus lock or the latest release changed |
| Parity wave | Kept whole, with its `SHA256SUMS` intact, unless paper-soak was dispatched (that spends its `runId`); a partial wave never, because its phases and shadow lane share one session |
| Catalog cases | Each passed case kept while its record, binding and `result.json` hash hold; the rest rerun under fresh magics |
| Shadow lane in a catalog | Never kept: one daemon's session is the evidence of all its cases |
| Insights | Kept unless the image changed |

**Sessions.** A release can start at any hour; the session-bound lanes (stocks, alpaca: a `session` window in
`attestation/lanes.yaml`) are planned into their windows, not refused (`lib/attest_session.py`). The preflight says when
the run reaches each one. Reached inside a window it fits in, a lane starts first (Alpaca, the catalog's longest path,
then stocks), so it finishes before the close. Reached outside, every other lane runs now and the catalog waits in process
for the next window the lane fits in. When that wait would age the first evidence past the 24-hour limit before the bundle
(a weekend), the whole run is held until shortly before the window instead. Only a window further away than the profile's
`session_wait_max_h` (default 72) fails the preflight. Each wait is logged with its reason and planned time, written to
`attestation-run.json` (stage `hold`) or `<catalog>/session-waits.jsonl`, and carried in the bundle's catalog evidence as
`sessionWaits`. The plan only orders and times lanes: no session-bound case starts unless it can finish inside its
window, one that cannot fails as `session` by name, and the release still needs every case passed on this binding. An
event only the venue can trigger at a fixed time is a `scheduled` case, never required by a
release, and it must name its `release_proof` (see `attestation/README.md`).

**Flakes.** `scripts/live-validation/attest-flake-report.py` reports per-case pass, fail and retry rates and reasons
from every catalog directory. `--soak N` repeats the catalog for off-peak measurement: it places real demo orders.

## Failure Rules

Do not manufacture counts, reuse an attestation from another SHA, or waive a parity
mismatch. Fix the implementation or document a real, reviewed normalization and rerun
the affected matrix plus the regression suite. A failed run is useful evidence and
should remain outside the promotion bundle until the issue is resolved.
