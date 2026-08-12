# Exhaustive Live Parity Validation Plan

**Goal:** Produce falsifiable, retained evidence for every supported qkt capability across raw
ticks, ordinary bars, tick-resolved bars, live-paper, the local Exness demo gateway, reporting,
journals, portfolios, and QKT Insights before any external strategy is promoted.

**Spec:** `docs/superpowers/specs/2026-08-09-exhaustive-live-parity-validation-design.md`

## Evidence Snapshot As Of 2026-08-11

Verified retained evidence already exists for these slices:

- localhost-only MT5 read-only catalog certification passed at
  `/var/tmp/qkt-validation/readonly-catalog-live-6913752b-20260810T224349Z/evidence/result.json`
  with warmup, live ticks, live bars, 1m/5m boundaries, stale-stream recovery, final-flat
  reconciliation, and artifact checksums;
- real local MT5 demo order lifecycle plus QKT Insights certification passed at
  `/var/tmp/qkt-validation/insights-4d37ebb4-final/evidence/result.json`
  with retained manifest/checksums and a bounded 0.01-lot entry/exit proving causal
  rule-to-order-to-fill attribution and final-flat cleanup;
- bounded two-container live order parity passed at
  `/var/tmp/qkt-validation/roundtrip-live-20260811/evidence/result.json`
  with two simultaneous localhost MT5 demo strategies across `EXNESS:EURUSD` and
  `EXNESS:GBPUSD`, exact M1/M5 candle/evaluation joins, indicator-entry/exit traces,
  strict per-magic ownership, bounded bracket verification, account reconciliation, and
  final-flat cleanup;
- offline replay comparison for the retained EURUSD capture passed at
  `/var/tmp/qkt-validation/roundtrip-eurusd-20260811-replay/result.json`
  across `full-ticks-paper`, `full-ticks-mt5`, and `bars-paper`;
- offline replay comparison for the retained GBPUSD capture passed at
  `/var/tmp/qkt-validation/roundtrip-gbpusd-20260811-replay/result.json`
  across `full-ticks-paper`, `full-ticks-mt5`, and `bars-paper`;
- sustained read-only multi-container load/restart passed at
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/result.json`
  with two read-only localhost MT5 containers across `EURUSD`, `GBPUSD`, `USDJPY`, and `XAUUSD`,
  exact `1m`/`5m` warmup-plus-live bar joins, a controlled generation-2 restart after persisted
  rule-edge state, zero dropped ticks, zero gateway mutations, zero venue deals, final-flat account
  reconciliation, and retained resource/latency evidence;
- a new read-only already-deployed/resync harness now exists at
  [run-readonly-resync.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-readonly-resync.sh)
  with focused regression coverage in
  [run-readonly-resync-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-readonly-resync-test.sh);
- read-only already-deployed/resync/resume control-plane validation passed at
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/result.json`
  with an empty daemon start, real `deploy`, real `resync`, explicit post-resync `resume`,
  matched `1m`/`5m` live evaluations before and after replacement, exact warmup counts, zero
  mutating gateway calls, zero venue deals, and final-flat account reconciliation;
- read-only gateway restart validation passed at
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/result.json`
  with a real `lab-mt5-gateway` container restart, retained feed disconnect and reconnect proof,
  matched `1m`/`5m` evaluations before and after restart, exact startup warmup counts,
  zero reconnect warmup replay, zero gateway mutations, zero venue deals, redacted container
  evidence, and final-flat account reconciliation;
- read-only already-deployed gateway restart validation passed at
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/result.json`
  with an empty daemon start, real `qkt deploy`, a real `lab-mt5-gateway` container restart,
  retained feed disconnect and reconnect proof, matched `1m`/`5m` evaluations before and after
  restart, exact deploy-time warmup counts, zero reconnect warmup replay, zero gateway mutations,
  zero venue deals, redacted container evidence, and final-flat account reconciliation;
- a new order-bearing gateway restart harness now exists at
  [run-order-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-order-gateway-restart.sh)
  with focused regression coverage in
  [run-order-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-order-gateway-restart-test.sh);
- order-bearing gateway restart validation now passed at
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/result.json`
  with a real bounded `0.01`-lot open, a real `lab-mt5-gateway` restart while the ticket stayed
  open, retained disconnect/reconnect proof, one strategy-owned close after reconnect, exact
  two-deal venue history, exact post-restart read-only and armed `1m`/`5m` evidence, latency
  retention, and final-flat reconciliation;
- parallel live risk-rejection validation now passed at
  `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/evidence/result.json`
  with five synchronized localhost containers proving `MaxOrderQty`, `MaxOrderNotional`,
  `PriceCollar`, `MeasuredUsage`, and operator-halt rejection before MT5 transport, zero
  mutating gateway requests, zero broker order/fill events, unchanged flat venue state, and
  unchanged account financial state;
- focused engine and broker regressions for restart, OCO compensation, scale-out ownership,
  attached protection, and protocol rollback are already green on this branch;
- `./gradlew test -Pkotlin.compiler.execution.strategy=daemon` and
  `./gradlew build -Pkotlin.compiler.execution.strategy=daemon` both passed on Tuesday,
  August 11, 2026.

These retained passes do not yet justify checking the broader matrix rows below. They prove the
localhost harness works and that one real order lifecycle plus one read-only four-scenario catalog
run are stable enough to extend, but they do not prove exhaustive DSL/runtime parity, shared-account
isolation, full Insights attribution, or the burn-in policy.

The highest-value remaining gaps are:

1. generated non-vacuous live scenarios covering the full DSL and order-lifecycle surface;
2. replay/live/backtest comparison coverage for every applicable scenario across ticks, bars,
   tick-resolved bars, and live-paper;
3. same-account concurrency beyond this first bounded two-symbol/two-strategy slice, including
   broader shared-account attribution and reconciliation proof;
4. sustained reconnect/resync/already-deployed and broader order-bearing restart measurements
   without any JVM restriction beyond the now-passing read-only load/restart slice and the first
   bounded order-bearing restart proof;
5. full post-final-fix rerun and the mandatory 30-day demo burn-in.

The first concrete artifact for gap `1` now exists in-branch:

- [prepare-generated-parity-wave.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-generated-parity-wave.sh)
  generates a four-case bounded order-bearing wave on top of the existing
  `run-readonly.sh`, `run-market-bracket.sh`, and
  `compare-golden-replay.sh` contracts;
- [prepare-generated-parity-wave-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-generated-parity-wave-test.sh)
  verifies that the generated cases remain structurally compatible with those
  runners;
- [prepare-scenario.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-scenario.sh)
  now supports explicit armed variants `ema_cross`, `rsi_reversion`,
  `atr_channel`, and `case_math` while preserving the original default
  scenario contract.

## Ordered Execution For The Next Stage

The next stage is the exhaustive generated parity matrix described in `../notes.txt`. The ordered
execution for that stage is:

1. generate non-vacuous single-strategy live scenarios that prove indicator, math, DSL, bar, tick,
   timed-exit, and order-path semantics on the real local MT5 demo gateway;
2. compare every applicable retained live window back through `full-ticks-paper`,
   `full-ticks-mt5`, `bars-paper`, and any supported tick-resolved bar mode;
3. expand from one bounded concurrency proof into broader same-account multi-strategy and
   multi-book isolation/reconciliation proof;
4. expand QKT Insights proof from one bounded lifecycle into strict strategy/book attribution,
   delivery, and retry coverage across the generated matrix;
5. extend the same proof standard to portfolio and book scenarios, not only individual strategies;
6. fix every defect found, add focused regression coverage, rerun the affected slice, then rerun
   the full required short-form matrix after the final fix;
7. only after those gates pass, start the documented 30-day demo burn-in;
8. only after burn-in passes, promote by PR through `dev -> testing -> main`;
9. only after runtime validation passes, adapt the downloaded external strategies into final
    `qkt-quant-live` books.

Current live-harness constraint discovered on 2026-08-11:

- end-to-end live validation remains config-file driven today; the daemon and bot flows still load
  `qkt.config.yaml`/`Config.resolvePath(...)`, so configless live parity is still an explicit gap and
  must not be treated as proven until the runtime can express the equivalent broker/data/risk state
  without a config file.
- the sustained load/restart harness did need hardening on Tuesday, August 11, 2026: the earlier
  retained attempt at `/var/tmp/qkt-validation/container-load-20260811b` showed healthy
  generation-2 samples but still failed on one health-sample gate. That harness has now been
  hardened and re-proven end to end by the retained passing rerun at
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/result.json`. The broader
  reconnect/already-deployed/resynchronization matrix is still open.
- the already-deployed/resync live slice now has a dedicated harness, but the first real run found
  a harness false negative: after `resync`, the replacement restored `halted:true`, and the first
  gate incorrectly waited for post-resync log re-fires instead of matched audit-journal evidence.
  The harness now resumes explicitly and waits on matched candle/evaluation proof; the corrected
  live pass is retained at `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/result.json`.
- the gateway-restart live slice also needed harness hardening on Tuesday, August 11, 2026 before
  it could produce trustworthy retained evidence: the first attempts exposed a startup-window
  false negative at the MT5 five-minute rollover, an auto-deploy status race, an incorrect
  reconnect-warmup expectation, and a secret-retention bug in raw `docker inspect` evidence.
  The harness now waits for the broker-safe launch window, waits for the strategy to reach
  `running:true`, requires `80/80` warmup counts only before restart with `0/0` after reconnect,
  redacts `API_KEY=` inside retained container inspection, and the corrected live pass is retained
  at `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/result.json`.
- the already-deployed gateway-restart slice now has its own dedicated harness, and the corrected
  live pass is retained at
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/result.json`.
- the order-bearing gateway-restart slice now has its own dedicated harness, and the corrected
  live pass is retained at
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/result.json`.
- the parallel risk-rejection slice is now also backed by a real localhost MT5 passing result at
  `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/evidence/result.json`, but it is
  intentionally limited to pre-transport static risk gates. The separate deterministic
  stateful-risk fixtures below now cover the stateful path.
- a deterministic restored-stateful-risk harness now exists at
  [prepare-stateful-risk-matrix.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-stateful-risk-matrix.sh)
  and
  [run-stateful-risk-containers.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-stateful-risk-containers.sh),
  with focused shell regression coverage in
  [prepare-stateful-risk-matrix-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-stateful-risk-matrix-test.sh)
  and
  [run-stateful-risk-containers-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-stateful-risk-containers-test.sh).
  That harness now generates four deterministic live cases for restored
 `global-daily-loss`, `strategy-daily-loss`, `global-drawdown`, and `loss-streak`
  halts. The clean retained
  localhost MT5 passing result now exists at
  `/var/tmp/qkt-validation/stateful-risk-20260811T162402Z-live-thin/evidence/result.json`.
- a prepared controlled `margin-floor` fixture now also exists at
  [prepare-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-margin-floor-fixture.sh)
  with focused shell coverage in
  [prepare-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-margin-floor-fixture-test.sh).
  It defines the remaining live stateful-risk gap as one real bounded opener
  position plus one probe rejection with a runtime-selected
  `margin_floor_pct = ceil(observed_margin_level_pct) + 1`.
- a real runner scaffold for that fixture now also exists at
  [run-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-margin-floor-fixture.sh)
  with focused shell coverage in
  [run-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-margin-floor-fixture-test.sh).
  The original remaining proof gap was the retained localhost MT5 pass, not missing
  harness structure.
- the retained localhost MT5 `margin-floor` pass now exists at
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`,
  proving one bounded opener position, runtime-selected live `margin_floor_pct`,
  one exact causal `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent`
  chain for the probe role, zero probe transport mutations, and final-flat
  account reconciliation;
- the generated four-case live/replay wave is now fully sealed with authoritative
  clean retained evidence at:
  - `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z/evidence/result.json`
    and
    `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z-replay-20260811T180849Z/result.json`
  - `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z/evidence/result.json`
    and
    `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z-replay-20260811T182759Z/result.json`
  - `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z/evidence/result.json`
    and
    `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z-replay-20260811T185752Z/result.json`
  - `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z/evidence/result.json`
    and
    `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z-replay-20260811T191243Z/result.json`;
- the final generated-wave hardening commit was `fcd1df22 fix(scripts): require fresh live tick
  before deploy`, which eliminated the remaining stale-pre-entry false negative for the
  replay-authoritative `atr-eurusd` slice.
- subsequent localhost reruns on Tuesday, August 11, 2026 proved two more harness-only false
  negatives rather than engine faults:
  - `/var/tmp/qkt-validation/order-reconnect-live-20260811T133501Z-postpersistfix/scenario`
    proved real open -> restart -> reconnect -> strategy-owned close -> final-flat again, but the
    trailer still counted warmup pseudo-ticks across both strategies because the read-only audit
    check was symbol/timeframe scoped instead of strategy scoped;
  - `/var/tmp/qkt-validation/order-reconnect-live-20260811T134728Z-postwarmupscopefix/scenario`
    proved the exact retry-close path the runner is meant to allow: the first post-restart close
    resolved `unknown-state` during the outage, the strategy retried one minute later, venue
    history retained exactly one entry and one exit on the same ticket, and the account returned
    flat. That run then exposed one final contract bug: the armed strategy warms `10` bars per
    timeframe, so its exact warmup count is `40`, not `80`.
- the final localhost rerun at
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario`
  then proved the full corrected slice end to end, including the final latency-capture ordering
  fix so `state/control.port` was read before daemon shutdown removed it.

- [x] Add a machine-readable capability catalog and a CI test that detects registry/runtime drift.
- [x] Inventory existing evidence without upgrading parser, construction, or vacuous tests to
      behavioral proof.
- [x] Add independent numeric/readiness oracles for every indicator output and DSL numeric function.
- [ ] Add non-vacuous DSL trace scenarios for every expression, state reference, action, schedule,
      sequence, session, basket, and supported interaction.
- [ ] Generate temporary `.qkt` strategies plus config, risk, book-risk, identity, expected-trace,
      and cleanup artifacts for every applicable live scenario, including supported configless use.
- [ ] Run each applicable scenario over raw ticks, independently constructed bars, ordinary bar
      replay, tick-resolved bars, and live-paper at all supported timeframe boundaries.
- [ ] Cover every order type, time in force, partial/rejected fill, cancel, expiry, bracket, OCO, OTO,
      trailing, stack, scale-out, timed exit, close, resize, halt, and recovery lifecycle.
- [ ] Reconcile fills, positions, cash, equity, realized/unrealized PnL, spread, commission, fees,
      swap, reports, manifests, and journals for every applicable scenario.
- [ ] Validate multi-symbol, multi-timeframe, multi-strategy, and portfolio isolation and aggregation.
- [x] Add a localhost-only Exness demo harness with account allowlisting, bounded 0.01-lot exposure,
      deterministic correlation, mandatory cleanup, and final reconciliation.
- [ ] Measure sustained gateway polling, routing, latency, queueing, resource trend, reconnect,
      restart, resynchronization, and already-deployed behavior without restricting JVM heap.
- [ ] Run staged multi-container QKT concurrency with isolated state and prove account-wide
      reconciliation plus strict per-container, strategy, and book ownership.
- [ ] Validate QKT Insights exact delivery, retry behavior, and strict strategy/book attribution.
- [ ] Fix every discovered engine defect with focused regression and adversarial edge-case tests in
      this concern's PR.
- [ ] Repeat the entire required matrix after the final fix and retain checksummed evidence.
- [ ] Complete the documented 30-day demo burn-in with no unexplained state or reconciliation gaps.
- [ ] Adapt the downloaded strategies only after validation passes, then build three or four
      independently monitored books and promote them in measured demo waves.
- [ ] Merge by PR through `dev -> testing -> main` only after every required evidence gate passes.
