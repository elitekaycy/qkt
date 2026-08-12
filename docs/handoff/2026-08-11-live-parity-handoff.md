# Live Parity And Promotion Handoff

## Objective

Primary thread goal:

`lets merge into main and after the next full set of tasks is in ../notes.txt.. lets be thorough.`

The practical meaning of that goal, based on `../notes.txt`, the exhaustive parity spec/plan, and
the repo go-live policy, is:

1. Prove qkt behavior across real generated strategies, indicators, DSL, ticks, bars, fills,
   journals, reports, and QKT Insights.
2. Exercise the actual local MT5 demo gateway, not a remote tunnel.
3. Fix every defect found, add regression coverage, and rerun the required matrix after the final
   fix.
4. After the exhaustive parity matrix is sealed, run a quick sanity QKT strategy proof, then PR to
   `dev` and promote/merge through the repo flow.
5. Only after that promotion is complete, apply the updated `qkt`, `qkt-insights`, and related runtime changes to
   `qkt-forge` on `sshbot2` for the forward-test environment, rerun the strategies there, and run
   the portfolio backtests from the now-proven runtime. After that forward-test stack is clean,
   update bot1 `qkt-quantlive` with the proven `qkt` and `qkt-insights` changes.

This branch is not ready for `main`, `qkt-forge`, or bot1 rollout yet because items 3 and 4 are
still incomplete at full scope. The current work is live parity testing, not downstream deployment.

## Current Branch State

- Repo: `/home/dickson/Desktop/personal/qkt`
- Current branch: `test/exhaustive-live-parity`
- Base branch: `origin/dev`
- Merge-base with `origin/dev`: `b4c99599b0e6cd94a70d9cb654a15f6732602121`
- Current status at handoff update: tracked worktree clean, branch `ahead 149`; two pre-existing
  untracked Kimi/audit docs remain outside this handoff.
- Latest committed work:
  - `fix(scripts): bound market history reconciliation`
  - `test(dsl): cover pending reentry guard`
  - `docs(docs): seal loss-streak reentry live evidence`
  - `feat(scripts): add loss-streak reentry live lifecycle`
  - `docs(docs): seal cooldown reentry live evidence`
  - `feat(scripts): add cooldown reentry live lifecycle`
  - `test(risk): cover portfolio book exposure recovery`
  - `docs(docs): seal margin floor recovery status`
  - `test(risk): cover global daily halt reentry reset`
  - `test(risk): cover daily halt reentry reset`
  - `cd723ab5 test(risk): cover loss streak reentry reset`
  - `cdbf5c38 test(risk): cover cooldown reentry recovery`
  - `298fb1a3 test(risk): cover next-day reentry reset`
  - `322b2e60 test(risk): cover margin floor reentry`
  - `cd07f20f test(risk): cover book exposure reentry`
  - `fff3536a test(marketdata): cover stale reentry gate`

## Authoritative Specs And Plans

- Plan: [2026-08-09-exhaustive-live-parity-validation.md](/home/dickson/Desktop/personal/qkt/docs/superpowers/plans/2026-08-09-exhaustive-live-parity-validation.md)
- Spec: [2026-08-09-exhaustive-live-parity-validation-design.md](/home/dickson/Desktop/personal/qkt/docs/superpowers/specs/2026-08-09-exhaustive-live-parity-validation-design.md)
- Audit remediation plan: [2026-08-09-production-readiness-audit-remediation.md](/home/dickson/Desktop/personal/qkt/docs/superpowers/plans/2026-08-09-production-readiness-audit-remediation.md)
- Go-live policy: [go-live-ramp.md](/home/dickson/Desktop/personal/qkt/docs/operations/go-live-ramp.md)
- User scope note: [../notes.txt](</home/dickson/Desktop/personal/notes.txt:1>)

## Verified Completed Work

### Engine And Lifecycle Fixes

These fixes are already merged into this branch and have focused test coverage:

- persisted market-close intent survives restart;
- engine-held trailing stop / stop-limit restore is safe and capability-aware;
- scale-out ownership is preserved across restart, partial execution, and MT5 ticket recovery;
- composite partial slices preserve ownership, close intent, and attached protection;
- MT5 OCO is explicitly engine-managed, not falsely declared venue-atomic;
- double-filled OCO legs are compensated and alerted;
- scale-out activation after residual cancel uses cumulative executed quantity and retains ticketed
  ownership;
- read-only/live evidence capture, causal order evidence, and rule/fill attribution plumbing were
  added across engine, backtest, CLI, and scripts.

Key commits in this area include:

- `a6c15a5d fix(execution): preserve persisted market close intent`
- `2e42ba44 fix(execution): restore engine-held orders safely`
- `c057f623 fix(execution): preserve scale-out ownership`
- `daf872fd fix(execution): preserve partial composite ownership`
- `9d2a6e6b fix(execution): compensate double-filled oco legs`
- `60cd76f8 fix(execution): arm scale-out after residual cancel`

### Audit-Remediation Hardening

The three environment-sensitive failures called out in the audit notes were addressed in source:

- `gradlew` wrapper mode-bit validation was added in scripts (`662faba3`).
- `StateFileWriterTest` now gives DSYNC writes realistic headroom without weakening the torn-read
  assertion: [StateFileWriterTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/persistence/StateFileWriterTest.kt:39)
- the dead `LocalBarStore` branch is gone: [LocalBarStore.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/marketdata/store/LocalBarStore.kt:32)
- Gradle runs already use `-Dfile.encoding=UTF-8` on the daemon in this environment.

### Focused Regression Coverage Already Verified

Previously run and reported green on this branch:

- `StrategyPositionTrackerStackTest`
- `TradingPipelineOwnershipTest`
- `OrderManagerOcoTest`
- `OrderManagerAttachedBracketTest`
- `OrderManagerOtoTest`
- `OrderManagerScaleOutTest`
- `OrderManagerRestoreTest`
- `MT5BrokerOcoPlacementRollbackTest`
- `MT5ProtocolTest`

Also verified previously:

- `ktlintMainSourceSetCheck`
- `ktlintTestSourceSetCheck`
- `git diff --check`
- `tests/scripts/run-insights-attribution-test.sh`
- `tests/scripts/prepare-live-validation-scenario-test.sh`

Re-verified on Tuesday, August 11, 2026 while tightening the live-order parity handoff:

- `tests/scripts/prepare-live-validation-scenario-test.sh`
- `tests/scripts/prepare-generated-parity-wave-test.sh`
- `tests/scripts/run-container-round-trips-test.sh`
- `tests/scripts/compare-container-round-trip-replay-test.sh`

### Live Read-Only MT5 Catalog Certification

Fresh passing evidence exists for the local MT5 read-only four-container validation:

- Evidence root:
  `/var/tmp/qkt-validation/readonly-catalog-live-6913752b-20260810T224349Z`
- Result:
  `/var/tmp/qkt-validation/readonly-catalog-live-6913752b-20260810T224349Z/evidence/result.json`
- Status in result file: `passed`

This run validated, in parallel:

- numeric scenario;
- cross scenario;
- session scenario;
- volume scenario.

What that passing run proved:

- warmup, live ticks, live bars, and strategy evaluations were observed;
- 1m and 5m live boundaries were exercised;
- stale-stream recovery succeeded;
- no live mutations or orders occurred in the read-only catalog stage;
- final account state was flat with zero pending orders;
- artifact checksums were retained.

### Live QKT Insights Certification With Real Demo Order Lifecycle

Fresh passing evidence exists for a real local MT5 demo 0.01-lot order lifecycle plus Insights:

- Evidence root:
  `/var/tmp/qkt-validation/insights-4d37ebb4-final`
- Result:
  `/var/tmp/qkt-validation/insights-4d37ebb4-final/evidence/result.json`
- Manifest:
  `/var/tmp/qkt-validation/insights-4d37ebb4-final/evidence/artifact-manifest.json`
- Checksums:
  `/var/tmp/qkt-validation/insights-4d37ebb4-final/RUN-SHA256SUMS`
- Status in result file: `passed`

What that run proved:

- warmup on M1 and M5 completed;
- live ticks arrived;
- post-deployment matched M1 and M5 evaluations were observed;
- one bounded entry and one bounded exit completed on the real demo account;
- causal link integrity from rule decision to submitted and accepted order was retained;
- fills and accounting were reconciled;
- the attributed open was observed and the close used the exact strategy-owned ticket;
- an outage replay backlog was drained cleanly;
- final gateway/account state returned to flat with zero pending orders.

### Live Concurrent Two-Container Round Trip Certification

Fresh passing evidence exists for the first bounded shared-account live order-parity slice:

- Aggregate result:
  `/var/tmp/qkt-validation/roundtrip-live-20260811/evidence/result.json`
- EURUSD replay comparison:
  `/var/tmp/qkt-validation/roundtrip-eurusd-20260811-replay/result.json`
- GBPUSD replay comparison:
  `/var/tmp/qkt-validation/roundtrip-gbpusd-20260811-replay/result.json`
- EURUSD scenario checksum manifest:
  `/var/tmp/qkt-validation/roundtrip-eurusd-20260811/RUN-SHA256SUMS`
- GBPUSD scenario checksum manifest:
  `/var/tmp/qkt-validation/roundtrip-gbpusd-20260811/RUN-SHA256SUMS`

What that passing live run proved on Tuesday, August 11, 2026:

- two real localhost MT5 demo strategies were deployed nearly simultaneously with `launchSkewMs=10`
  and `completionSkewMs=52`;
- both strategies traded live on the same demo account at the same time, one on `EXNESS:EURUSD`
  and one on `EXNESS:GBPUSD`;
- each strategy produced one bounded `0.01`-lot entry and one strategy-owned close;
- bracket distances stayed within the reviewed `0.0030` stop, `0.0060` take-profit, and
  `20`-point entry-anchor drift envelope;
- exact M1 and M5 stream/evaluation joins were retained for both strategies;
- indicator-entry and indicator-exit traces were retained for both strategies;
- per-case audit counts were exactly two rule decisions, two decision/order links, two accepts,
  two fills, two accounted events, and zero rejections;
- transport counts were exactly one order placement, one protection placement, one close mutation,
  and three total mutations per case;
- strict magic ownership held through the full lifecycle and final account reconciliation returned
  to flat with zero pending orders;
- Docker/JVM resource restrictions were verified absent;
- one EURUSD stale-market-data gate fired and recovered during the run without breaking ownership or
  reconciliation.

What the paired replay comparisons proved:

- both retained live captures replayed successfully through `full-ticks-paper`, `full-ticks-mt5`,
  and `bars-paper`;
- both comparisons passed with exact indicator-entry parity, exact indicator-exit quantity/close
  parity, exact canonical live-entry intent parity, exact live-vs-mt5-sim fill/protection/PnL
  parity, zero rejections, and final-flat replay state;
- the retained evidence explicitly documents the expected paper-model difference from live/MT5:
  paper omits venue spread, so paper PnL is less negative by the bid/ask delta;
- the retained evidence also documents the expected unsupported modes for this scenario:
  plain bars with `mt5-sim` are unsafe, and tick-resolved bars are unsupported for this
  mixed-timeframe strategy.

### Generated Single-Strategy Live Wave Progress

All four generated order-bearing single-strategy cases are now sealed as clean live-plus-replay
parity passes from the replay-intended clean worktree.

Fresh evidence:

- authoritative generated live pass:
  `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z/evidence/result.json`
- authoritative generated replay comparison:
  `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z-replay-20260811T180849Z/result.json`
- account state immediately after the clean live pass:
  flat, zero open positions, zero pending orders, balance `99996.35`
- prior dirty proving pass retained only as harness-forensics evidence:
  `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean5-20260811T180008Z/evidence/result.json`
- authoritative EURUSD generated live pass:
  `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z/evidence/result.json`
- authoritative EURUSD generated replay comparison:
  `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z-replay-20260811T182759Z/result.json`
- account state immediately after the clean EURUSD live pass:
  flat, zero open positions, zero pending orders, balance `99996.24`

What the authoritative clean pass proved on Tuesday, August 11, 2026:

- generated strategy `generated_gbpusd_rsi_clean_06_market_bracket` traded live on
  `EXNESS:GBPUSD` / venue `GBPUSDm`;
- the generated `rsi_reversion` variant opened one bounded `0.01`-lot live bracket and then
  strategy-owned flatten completed with `flattenVerified:true`;
- final venue reconciliation returned to `finalPositions:0` and `finalOrders:0`;
- retained live capture counts were non-vacuous: `ticks:27`, `warmupTicks:80`, `candles:11`,
  `fills:2`, `gatewayExchanges:137`, and `linkedPlacements:1`;
- one stale-market-data episode was retained and one recovery was retained before the
  pre-halt gate completed.

What the authoritative replay comparison proved:

- the replay source bundle SHA matched the clean live capture SHA
  `a64a157ad476dd6496ab23199561ae64decac6987ca9f980d681673ad100937b`;
- `captureGitSha` and replay `gitSha` both matched clean runner commit `e83df1a6`;
- `fullTickOrderJournalsByteExact`, `barsOrdersTimestampNormalizedExact`,
  `liveInitialProtectionMatchesCanonicalIntent`, and
  `liveFillAndAdjustedProtectionMatchMt5Simulation` all passed;
- the supported replay window was retained from `2026-08-11T18:07:00Z` through
  `2026-08-11T18:08:11.143Z`;
- the retained limitations remained the expected ones: operator flatten is reconciled by the live
  result rather than replayed as a strategy decision, and tick-resolved bars are unsupported for
  mixed-timeframe strategies.

Harness-only false negatives found and fixed while reaching that pass:

1. the runner only accepted long positions, but generated scenarios can validly open either
   `BUY` or `SELL`;
2. the runner assumed the MT5 venue comment would preserve the full strategy prefix, but the venue
   can truncate the comment;
3. the stale-market-data recovery trailer counted shutdown-time stale events that occurred after
   operator kill and flatten, which made a valid final-flat run fail after the real trading work
   was already complete.

Those fixes are now in the branch worktree in
[run-market-bracket.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-market-bracket.sh)
with shell coverage tightened in
[prepare-live-validation-scenario-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-live-validation-scenario-test.sh).

What changed between the dirty proving pass and the authoritative clean pass:

- the dirty pass at `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean5-20260811T180008Z`
  proved the live slice but reported `qktDirty:true`;
- the clean replay worktree was then committed at `e83df1a6 fix(scripts): harden bracket live runner`,
  rebuilt, rerun from a flat account state, and replay-compared successfully;
- `rsi-gbpusd` should now be treated as the first generated single-strategy case that is fully
  sealed across clean live and clean replay.

What the second authoritative clean pass proved:

- generated strategy `generated_eurusd_ema_clean_03_market_bracket` traded live on
  `EXNESS:EURUSD` / venue `EURUSDm`;
- the generated `ema_cross` variant opened one bounded `0.01`-lot live bracket and then
  strategy-owned flatten completed with `flattenVerified:true`;
- final venue reconciliation returned to `finalPositions:0` and `finalOrders:0`;
- retained live capture counts were non-vacuous: `ticks:8`, `warmupTicks:80`, `candles:11`,
  `fills:2`, `gatewayExchanges:50`, and `linkedPlacements:1`;
- this sealed rerun completed with `staleEvents:0` and `recoveredStaleEvents:0`.

What the second authoritative replay comparison proved:

- the replay source bundle SHA matched the clean live capture SHA
  `63045d13341eaed97ba702655f943f9229c415553c8ac6f21a9b94ea9b639223`;
- `captureGitSha` and replay `gitSha` both matched clean runner commit `e90ba0ef`;
- `fullTickOrderJournalsByteExact`, `barsOrdersTimestampNormalizedExact`,
  `liveInitialProtectionMatchesCanonicalIntent`, and
  `liveFillAndAdjustedProtectionMatchMt5Simulation` all passed;
- the supported replay window was retained from `2026-08-11T18:26:00Z` through
  `2026-08-11T18:27:11.299Z`;
- the retained limitations remained the expected ones: operator flatten is reconciled by the live
  result rather than replayed as a strategy decision, and tick-resolved bars are unsupported for
  mixed-timeframe strategies.

What changed to make the EURUSD case pass cleanly:

- the first clean EURUSD attempt at
  `/var/tmp/qkt-validation/generated-eurusd-ema-clean2-20260811T181751Z`
  failed before live trading because deploy hit the known MT5 warmup-history time-base mismatch
  during the unsafe five-minute rollover phase;
- `run-market-bracket.sh` now uses the same bounded broker-safe startup-window guard already proven
  in the read-only and gateway-restart harnesses, and that guard is pinned in
  [prepare-live-validation-scenario-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-live-validation-scenario-test.sh);
- the clean replay worktree was then recommitted at
  `e90ba0ef fix(scripts): gate bracket launch on safe window`, rebuilt, rerun from the flat account
  state, and replay-compared successfully.

What changed in the next generated-wave hardening cycle:

- the clean replay worktree was committed at
  `842e4fae fix(scripts): scope live lock per account`, which changed armed live serialization from
  one global lock to per-account locks at `/var/tmp/qkt-validation/LIVE-LOCK-<server>-<login>` so
  `5001` and `5002` can run independently when they point at different demo accounts;
- the next clean commit
  `59d4da33 fix(scripts): harden live bracket preflight` added two pre-deploy guards:
  CLI git-sha verification against the prepared scenario's `qktCommit`, and a bounded `qkt bot bars`
  history-readiness probe on `1m` and `5m`.

What the new `case-gbpusd` sealed pass proved:

- authoritative live pass:
  `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z/evidence/result.json`
- authoritative replay comparison:
  `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z-replay-20260811T185752Z/result.json`
- generated strategy `generated_gbpusd_case_clean_02_market_bracket` traded live on
  `EXNESS:GBPUSD` / venue `GBPUSDm`;
- the new history-readiness probe passed on the first attempt after the guarded startup wait, then
  warmup seeded cleanly on both `1m` and `5m`;
- final venue reconciliation returned to `finalPositions:0` and `finalOrders:0`;
- retained live capture counts were non-vacuous: `ticks:9`, `warmupTicks:80`, `candles:11`,
  `fills:2`, `gatewayExchanges:45`, and `linkedPlacements:1`;
- the replay comparison passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`,
  `liveInitialProtectionMatchesCanonicalIntent:true`, and
  `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

What the final `atr-eurusd` sealed pass proved:

- prior live-only gap evidence remains useful for forensics:
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean3-20260811T185752Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean3-20260811T185752Z-replay-20260811T190346Z`
- the clean replay worktree was then committed at
  `fcd1df22 fix(scripts): require fresh live tick before deploy`, rebuilt, and rerun from the
  current demo2 account snapshot;
- authoritative live pass:
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z/evidence/result.json`
- authoritative replay comparison:
  `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z-replay-20260811T191243Z/result.json`
- generated strategy `generated_eurusd_atr_clean_04_market_bracket` traded live on
  `EXNESS:EURUSD` / venue `EURUSDm`;
- the new post-daemon fresh-tick gate held tick age to `1298ms` before deploy, so the first live
  entry opened cleanly with `staleEvents:0` and `recoveredStaleEvents:0`;
- final venue reconciliation returned to `finalPositions:0` and `finalOrders:0`;
- retained live capture counts were non-vacuous: `ticks:2`, `warmupTicks:80`, `candles:11`,
  `fills:2`, `gatewayExchanges:42`, and `linkedPlacements:1`;
- the replay comparison passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`,
  `liveInitialProtectionMatchesCanonicalIntent:true`, and
  `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

### Active-Symbol XAUUSD Live/Replay Extension

Fresh retained evidence now exists for an active-symbol metal order-bearing live/replay slice:

- clean proving worktree:
  `/var/tmp/qkt-xau-clean-20260811T233916Z`
- clean proving commit:
  `c50491a3 fix(scripts): support active metal live scenarios`
- exploratory dirty live pass, retained only as non-authoritative proof-of-path:
  `/var/tmp/qkt-validation/xau-active-prep-20260811T233250Z/evidence/result.json`
- authoritative clean live pass:
  `/var/tmp/qkt-validation/xau-active-clean-20260811T234728Z/evidence/result.json`
- authoritative replay comparison:
  `/var/tmp/qkt-validation/xau-active-clean-20260811T234728Z-replay/result.json`
- post-run primary gateway flatness check:
  `/var/tmp/qkt-validation/xau-active-clean-20260811T234728Z/post-run-flat.json`

What this clean pass proved on Tuesday, August 11, 2026:

- `prepare-scenario.sh` can now generate a bounded `EXNESS:XAUUSD` live scenario using
  `XAUUSDm`, expected contract size `100`, widened notional cap `10000`, and symbol-appropriate
  bracket distances of `3.000` stop and `6.000` take profit;
- `run-market-bracket.sh` no longer assumes every order-bearing single-strategy scenario is an
  FX contract with `trade_contract_size == 100000`; it verifies the scenario's expected contract
  size from retained expected metadata;
- the live runner waited for the broker-safe startup phase and a fresh post-daemon XAU tick before
  deploy;
- generated strategy `xau_active_clean_market_bracket` opened one real `0.01`-lot XAUUSD demo
  bracket and then flattened strategy-owned through QKT;
- retained live evidence was non-vacuous: `ticks:16`, `warmupTicks:80`, `candles:11`, `fills:2`,
  `gatewayExchanges:52`, `linkedPlacements:1`, and `mutations:3`;
- the run had `staleEvents:0`, `recoveredStaleEvents:0`, final positions `0`, final orders `0`,
  and balance delta `-0.39` exactly matched deal net `-0.39`;
- the replay comparison passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`,
  `liveInitialProtectionMatchesCanonicalIntent:true`, and
  `liveFillAndAdjustedProtectionMatchMt5Simulation:true`;
- final primary account state after the clean proof was flat: balance/equity `99992.70`, margin
  `0`, positions `0`, orders `0`.

Why this was added:

- the latest no-mutation tick scout showed `EURUSDm` remained too sparse for honest immediate
  shared-account arming, while `XAUUSDm` had zero samples over the strict 8-second freshness gate
  across both local gateways;
- this keeps real live parity moving without weakening QKT's market-data stale gate or forcing
  EURUSD/GBPUSD scenarios into known-stale conditions.

### Active-Symbol XAUUSD Re-Entry Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing re-entry slice:

- clean proving worktree:
  `/var/tmp/qkt-reentry-clean-20260812T000850Z`
- scenario:
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z`
- live result:
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay5/result.json`
- failed comparator attempts retained for audit:
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay`,
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay2`,
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay3`, and
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay4`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry` emitted a clean, credential-free
  scenario at `qktCommit db7b50a1f684a21418bfe73187c9b2e73430e02a` with `qktDirty:false`;
- the generated strategy used `TRADES.today < 2`, `max_trades_per_day:2`, `maximumEntries:2`, and
  `maximumExits:2`;
- the live run opened and closed two real `0.01`-lot XAUUSDm positions under magic `917108` without
  operator flatten:
  - ticket `3073462199`: BUY fill `4375.383000000001`, strategy-owned SELL close fill `4376.104`;
  - ticket `3073466017`: BUY fill `4376.874000000001`, strategy-owned SELL close fill `4380.037`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`;
- retained live evidence was non-vacuous: `ticks:420`, `warmupTicks:80`, `candles:14`, `fills:4`,
  `gatewayExchanges:460`, `linkedPlacements:2`, and `mutations:6`;
- the run had `staleEvents:0`, `strategyOwnedLifecycle:true`, `transport.orderPosts:2`, and
  `transport.closePosts:2`;
- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, and
  `mt5SimulationUsesSameCanonicalIntent:true`.

Important replay caveat:

- `liveFillAndAdjustedProtectionMatchMt5Simulation:false` for this re-entry proof is expected and
  now explicit. The real broker filled after live MT5/HTTP execution latency, while `mt5-sim`
  deterministically filled at the replay tick/spread model. The retained drift was:
  - entry 0: live `4375.383000000001` vs mt5-sim `4375.40900000`, delta `-0.026`;
  - entry 1: live `4376.874000000001` vs mt5-sim `4376.65800000`, delta `+0.216`.
- This is not an indicator/DSL/order-routing mismatch. The replay proof is exact for generated order
  decisions, bars-vs-full-tick order normalization, live request intent, and protection adjustment
  against the captured broker fill. Exact live fill price equality to deterministic backtest remains
  unproven by design unless a later replay mode consumes captured broker fill events as the fill
  oracle.

### Active-Symbol XAUUSD Blocked Re-Entry Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing blocked re-entry slice:

- clean proving worktree:
  `/var/tmp/qkt-blocked-reentry-clean-20260812T004243Z`
- scenario:
  `/var/tmp/qkt-validation/xau-blocked-reentry-clean-20260812T004730Z`
- live result:
  `/var/tmp/qkt-validation/xau-blocked-reentry-clean-20260812T004730Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-blocked-reentry-clean-20260812T004730Z-replay/result.json`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry_blocked_max_trades` emitted a clean,
  credential-free scenario at `qktCommit 011618031037bbc13e4d750fa549b80a05e7b256` with
  `qktDirty:false`;
- the generated strategy intentionally allowed a second entry signal through the DSL with
  `TRADES.today < 2`, while risk configured `max_trades_per_day:1`;
- the live run opened one real `0.01`-lot XAUUSDm position under magic `917118`, closed it
  strategy-owned, then reached a second qualifying BUY signal;
- the second signal was rejected before transport with exact reason `MaxTradesPerDay`; retained
  transport evidence shows `orderPosts:1` and `closePosts:1`, proving no second `/order` request
  reached the gateway;
- retained live evidence was non-vacuous: `ticks:170`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `gatewayExchanges:315`, `linkedPlacements:1`, and `mutations:3`;
- retained audit evidence included `acceptedEvents:2`, `filledEvents:2`, and `riskRejections:1`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`; balance delta
  `-0.15` matched owned deal net `-0.15`;
- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, and
  `mt5SimulationUsesSameCanonicalIntent:true`.

Important replay caveat:

- `liveFillAndAdjustedProtectionMatchMt5Simulation:false` is expected for this blocked re-entry
  proof for the same reason as the allowed re-entry proof: real broker fill latency differs from
  deterministic replay. The retained entry drift was live `4380.598` vs mt5-sim `4380.49300000`,
  delta `0.10499999999956344`.

### Active-Symbol XAUUSD Operator-Halt Re-Entry Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing operator-halt blocked re-entry
slice:

- clean proving worktree:
  `/var/tmp/qkt-operator-halt-reentry-clean-20260812T010429Z`
- scenario:
  `/var/tmp/qkt-validation/xau-operator-halt-reentry-clean-20260812T010840Z`
- live result:
  `/var/tmp/qkt-validation/xau-operator-halt-reentry-clean-20260812T010840Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-operator-halt-reentry-clean-20260812T010840Z-replay2/result.json`
- first replay attempt retained for audit:
  `/var/tmp/qkt-validation/xau-operator-halt-reentry-clean-20260812T010840Z-replay`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry_blocked_operator_halt` emitted a clean,
  credential-free scenario at `qktCommit 13dbb027273e1ce8715b26b83c068af49876f676` with
  `qktDirty:false`;
- the generated strategy intentionally allowed a second entry signal through the DSL with
  `TRADES.today < 2`, while risk left `max_trades_per_day:2` open so only the operator gate could
  block the second signal;
- the live run opened one real `0.01`-lot XAUUSDm position under magic `917128`, closed it
  strategy-owned, then the runner issued `qkt halt` through the daemon control plane;
- the retained halt response was `state:"halted"` with affected strategy
  `validation_xau_operator_halt_reentry_market_bracket`;
- the next qualifying BUY signal fired at `01:14:00 UTC` and was rejected before transport with
  exact reason `halted: operator`;
- retained transport evidence shows `orderPosts:1` and `closePosts:1`, proving no second `/order`
  request reached the gateway after the operator halt;
- retained live evidence was non-vacuous: `ticks:233`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `gatewayExchanges:317`, `linkedPlacements:1`, and `mutations:3`;
- retained audit evidence included `acceptedEvents:2`, `filledEvents:2`, and `riskRejections:1`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`; balance delta `0.23`
  matched owned deal net `0.23`;
- final primary gateway state after the run was flat: balance/equity `99996.67`, margin `0`,
  positions `0`, orders `0`.

Important replay caveat:

- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, and
  `mt5SimulationUsesSameCanonicalIntent:true`;
- `replayExpectedEntries:2` and `replayExpectedTradeCount:3` are intentional for this slice.
  Operator halt is an external control-plane event, so unhalted replay retains the extra entry that
  live correctly rejected after the halt. The comparison still checks the filled entry intent,
  captured broker-fill protection adjustment, full-tick replay order journals, and normalized bar
  order journals exactly;
- `liveFillAndAdjustedProtectionMatchMt5Simulation:false` is expected because real broker fill
  latency differs from deterministic replay. The retained entry drift was live
  `4390.338000000001` vs mt5-sim `4389.99200000`, delta `0.3460000000004584`.

### Active-Symbol XAUUSD Operator-Halt Recovery Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing operator-halt recovery slice:

- clean proving worktree:
  `/var/tmp/qkt-operator-recovery-reentry-clean-20260812T012403Z`
- scenario:
  `/var/tmp/qkt-validation/xau-operator-recovery-reentry-clean-20260812T012825Z`
- live result:
  `/var/tmp/qkt-validation/xau-operator-recovery-reentry-clean-20260812T012825Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-operator-recovery-reentry-clean-20260812T012825Z-replay/result.json`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry_operator_halt_recovered` emitted a
  clean, credential-free scenario at `qktCommit f0ca7d5b23e434e2f59a2e215e30ca971a43f2e6` with
  `qktDirty:false`;
- the generated strategy intentionally allowed two entries through the DSL with `TRADES.today < 2`,
  while risk left `max_trades_per_day:2` open so the operator gate, not a trade counter, controlled
  the middle block;
- the live run opened and closed one real `0.01`-lot XAUUSDm position under magic `917138`;
- the runner issued `qkt halt` through the daemon control plane, and the next qualifying BUY signal
  was rejected before transport with exact reason `halted: operator`;
- the runner then issued `qkt resume`, a later qualifying SELL signal opened a second real
  `0.01`-lot XAUUSDm position, and the strategy closed that second position through QKT;
- retained transport evidence shows `orderPosts:2` and `closePosts:2`, proving the blocked signal
  did not reach the gateway and the resumed signal did;
- retained live evidence was non-vacuous: `ticks:461`, `warmupTicks:80`, `candles:15`, `fills:4`,
  `gatewayExchanges:601`, `linkedPlacements:2`, and `mutations:6`;
- retained audit evidence included `acceptedEvents:4`, `filledEvents:4`, and `riskRejections:1`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`; balance delta
  `-0.97` matched owned deal net `-0.97`;
- final primary gateway state after the run was flat: balance/equity `99995.70`, margin `0`,
  positions `0`, orders `0`.

Important replay caveat:

- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, and
  `mt5SimulationUsesSameCanonicalIntent:true`;
- `comparisonEntries:1` is intentional for this slice. Operator halt/resume are external
  control-plane events, so unhalted replay takes the second entry at the blocked signal, while live
  takes the recovered entry only after resume. The comparator checks the pre-halt live entry exactly
  and retains the recovered live entry in `liveEntries`;
- `liveFillAndAdjustedProtectionMatchMt5Simulation:false` is expected because real broker fill
  latency differs from deterministic replay. The retained compared-entry drift was live
  `4389.4400000000005` vs mt5-sim `4389.43300000`, delta `0.007000000000516593`.

### Active-Symbol XAUUSD Cooldown Recovery Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing cooldown-after-loss recovery
slice:

- clean proving worktree:
  `/var/tmp/qkt-live-head-5cdfe826-20260812T035920Z`
- scenario:
  `/var/tmp/qkt-validation/xau-cooldown-recovery-reentry-90s-20260812T042800Z`
- live result:
  `/var/tmp/qkt-validation/xau-cooldown-recovery-reentry-90s-20260812T042800Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-cooldown-recovery-reentry-90s-20260812T042800Z-replay/result.json`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry_cooldown_recovered` emitted a clean,
  credential-free scenario at `qktCommit 0b093213f34ca8db07b2ea5c863041d222dfaac6` with
  `qktDirty:false`;
- the generated strategy intentionally allowed up to three entry attempts through the DSL with
  `TRADES.today < 3`, while risk configured per-strategy `cooldown_after_loss: "90000"` so the
  pacing gate, not max-trades, controlled the middle block;
- the live run opened and closed one real `0.01`-lot XAUUSDm SELL under magic `938502`; that close
  realized `-0.69`, arming the cooldown-after-loss gate;
- the next qualifying SELL signal was rejected before transport with exact reason
  `CooldownAfterLoss[validation_cooldown90_live_market_bracket]: 30s remaining`;
- after the runner retained `cooldown-recovery-wait.json` and waited through the 90-second cooldown
  window, a later qualifying BUY signal opened a second real `0.01`-lot XAUUSDm position and QKT
  closed it strategy-owned;
- retained transport evidence shows `orderPosts:2` and `closePosts:2`, proving the blocked signal
  did not reach the gateway and the recovered signal did;
- retained live evidence was non-vacuous: `ticks:241`, `warmupTicks:80`, `candles:15`, `fills:4`,
  `gatewayExchanges:597`, and `linkedPlacements:2`;
- retained audit evidence included `acceptedEvents:4`, `filledEvents:4`, and `riskRejections:1`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`; balance delta
  `-1.54` matched owned deal net `-1.54`;
- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, `mt5SimulationUsesSameCanonicalIntent:true`,
  and `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

Important implementation note:

- an earlier armed attempt with a 30-second cooldown was intentionally treated as failed evidence:
  because the strategy is 1m-bar driven, the next qualifying entry arrived after that short cooldown
  had already elapsed and correctly opened instead of rejecting. The reviewed lifecycle therefore
  uses a 90-second cooldown so the next 1m signal proves the blocked state before recovery.

### Active-Symbol XAUUSD Loss-Streak Halt Live/Replay Extension

Fresh retained evidence now exists for the first real order-bearing loss-streak halt blocked
re-entry slice:

- clean proving worktree:
  `/var/tmp/qkt-live-head-5cdfe826-20260812T035920Z`
- scenario:
  `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z`
- live result:
  `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z/evidence/result.json`
- successful replay comparison:
  `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z-replay/result.json`

What this clean pass proved on Wednesday, August 12, 2026:

- `prepare-scenario.sh --symbol XAUUSD --lifecycle reentry_blocked_loss_streak` emitted a clean,
  credential-free scenario at `qktCommit c8c95ff40bfe11dfc631241443aaf574c858fe6e` with
  `qktDirty:false`;
- demo2 was checked immediately before arming and was healthy, current, flat, tradeable, and at
  balance/equity `99996.94` with leverage `1000`;
- the generated strategy left trade count open with `TRADES.today < 2`, while risk configured
  per-strategy `loss_streak_halt: "1"` and `loss_streak_halt_scope: persistent`, so the second
  entry attempt could only be blocked by the loss-streak gate;
- the live run opened one real `0.01`-lot XAUUSDm BUY under magic `938503` at broker position ticket
  `3074012747`, then closed it strategy-owned by SELL;
- the close realized `-1.29`, arming `LossStreakHalt[validation_loss_streak_live_market_bracket]`;
- the next qualifying BUY signal was rejected before transport with
  `risk rejected ... halted: LossStreakHalt[validation_loss_streak_live_market_bracket]: 1 consecutive losses, max 1`;
- retained transport evidence shows `orderPosts:1` and `closePosts:1`, proving the blocked second
  signal did not reach the gateway;
- retained live evidence was non-vacuous: `ticks:119`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `gatewayExchanges:315`, and `linkedPlacements:1`;
- retained audit evidence included `acceptedEvents:2`, `filledEvents:2`, and `riskRejections:1`;
- final venue reconciliation returned `finalPositions:0` and `finalOrders:0`; balance delta
  `-1.29` matched owned deal net `-1.29`;
- replay passed with `fullTickOrderJournalsByteExact:true`,
  `barsOrdersTimestampNormalizedExact:true`, `liveInitialProtectionMatchesCanonicalIntent:true`,
  `liveAdjustedProtectionMatchesCapturedBrokerFill:true`, `mt5SimulationUsesSameCanonicalIntent:true`,
  and `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

Important limitation:

- this seals the real order-bearing loss-streak halt block after a real losing trade. It does not
  seal a later live loss-streak reset after a winning trade; keep that exact reset lifecycle open
  unless a deterministic retained fixture is added for it.

### Sustained Read-Only Load And Restart Certification

Fresh passing evidence now exists for the localhost MT5 read-only sustained load/restart slice:

- Aggregate result:
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/result.json`
- Restart checkpoint:
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/restart.json`
- Aggregate resource samples:
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/resources.csv`
- Final account reconciliation:
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/account-final.json`
- Venue history during run:
  `/var/tmp/qkt-validation/container-load-20260811e/evidence/history-during-run.json`

What that passing run proved on Tuesday, August 11, 2026:

- both read-only containers started against the localhost demo gateway with no Docker/JVM resource
  restrictions;
- case `a` auto-deployed `EURUSD`/`GBPUSD` M1+M5 streams and case `b` auto-deployed `USDJPY`/`XAUUSD`
  M1+M5 streams;
- both cases retained healthy samples for the full observation window, with zero dropped ticks and
  drained inbound queues at final status;
- the controlled restart executed at second `315`: case `a` stopped, persisted rule-edge state,
  auto-redeployed as generation `2`, and retained `stateRestoreVerified:true` with
  `postRestartObservationSeconds: 314`;
- case `b` retained live health and matched evaluations before, during, and after its peer restart;
- warmup pseudo-tick counts, live tick events, exact stream candles, and matched strategy candle
  evaluations were retained for all eight configured streams across `1m` and `5m`;
- per-case latency evidence stayed within the current gate, with no order events, fills, or mutating
  gateway requests;
- the run remained financially read-only: final positions/orders were empty, account balance/equity
  matched the initial allowlisted state exactly, and venue history during the run was empty;
- aggregate retained resource evidence showed `52` samples per case, max aggregate CPU `43.29%`,
  max aggregate memory `396288 KiB`, and max aggregate PID count `113`.

What changed to make this slice pass:

- the first retained attempt at `/var/tmp/qkt-validation/container-load-20260811b` exposed a harness
  timing race in the health-sample gate rather than a runtime fault;
- `scripts/live-validation/run-container-load.sh` was then hardened to retry transient
  `daemon status` sampling three times and to resolve image-side `qkt --version` via shell `PATH`
  so the clean rerun could prove the matching patched image/CLI commit;
- the clean proving rerun used:
  - clean checkout: `/var/tmp/qkt-live-run`
  - clean branch: `fix/load-health-sampling`
  - commits:
    - `cc0304a3 fix(scripts): retry load health samples`
    - `37daab60 fix(scripts): resolve image qkt via shell path`
  - image: `qkt:live-validation-37daab60`
  - host CLI shim: `/var/tmp/qkt-shims/qkt-version-shim.sh`

### Live Order-Bearing Gateway Restart Status

Fresh passing retained real-demo gateway-restart evidence now exists:

- Scenario root:
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario`
- Aggregate result:
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/result.json`
- Exact venue history:
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/history-during-run.json`
- Final account snapshot:
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/gateway-account-final.json`
- Latency snapshot:
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/latency.json`

What this passing retained run proved on Tuesday, August 11, 2026:

- the runner waited for the broker-safe startup phase and launched only after the bounded window
  opened, with `totalWaitSeconds: 173`;
- the read-only sibling retained matched `1m` and `5m` evidence before and after restart with
  exact warmup counts of `80/80` before restart and `0/0` after reconnect;
- the armed strategy opened one real `0.01`-lot `EURUSDm` demo position on ticket `3071432055`;
- `lab-mt5-gateway` was restarted while that ticket stayed open, and the retained result shows
  `disconnectWarnings: 2`, `reconnectInfos: 1`, and `positionPersistedAcrossRestart: true`;
- the same strategy closed the same ticket after reconnect with no retry path needed on this run,
  retaining exactly one entry order post, one protection post, one successful close post, two
  accepts, two fills, two accounted events, and zero rejections;
- the armed strategy retained matched post-restart `1m` and `5m` evidence with exact warmup
  counts of `40/40` total and `0/0` after reconnect;
- exact two-deal venue history was retained on the same ticket, and final venue state was flat;
- final account balance delta was `-0.14`, which matched owned deal net `-0.14`;
- retained latency evidence exists for the actual live strategy path, including
  `TICK_PROCESSING` `p50=1026788ns`, `p95=2190219ns`, `p99=3341469ns`, and `max=3894742ns`.

This slice is now proven in retained passing form. The harness-only false negatives found earlier
in the day were:

- password redaction false positive after artifact scrubbing;
- post-restart persistence fallback counting bug;
- read-only warmup counting scoped by symbol/timeframe rather than strategy;
- armed warmup expectation set to `80` instead of the correct `40`;
- latency capture ordered after daemon shutdown, which removed `state/control.port` too early.

Those hardenings are now reflected in:

- [run-order-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-order-gateway-restart.sh)
- [run-order-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-order-gateway-restart-test.sh)

### Live Risk-Rejection Matrix

Fresh passing retained localhost MT5 evidence now exists for the five-case
pre-transport risk-rejection slice:

- Suite root:
  `/var/tmp/qkt-validation/riskreject-20260811T145134Z-suite`
- Aggregate result:
  `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/evidence/result.json`
- Per-case retained results:
  - `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/cases/max-quantity/evidence/result.json`
  - `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/cases/max-notional/evidence/result.json`
  - `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/cases/far-price-collar/evidence/result.json`
  - `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/cases/measured-usage/evidence/result.json`
  - `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/cases/operator-halt/evidence/result.json`
- Explicit deferred stateful-risk contract:
  `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/source/stateful-deferred.json`

What this passing retained run proved on Tuesday, August 11, 2026:

- five unrestricted QKT containers were launched in parallel against the same localhost demo
  gateway with no Docker CPU, memory, PID, or cpuset restriction and no JVM override variables;
- each strategy emitted a synchronized fixed `0.01`-lot intent and was rejected before MT5
  transport, retaining exactly one `RuleDecisionEvent`, one `DecisionOrderLinkedEvent`, and one
  `RiskRejectedEvent`;
- the proven live rejection rules were:
  - `MaxOrderQty`
  - `MaxOrderNotional`
  - `PriceCollar`
  - `MeasuredUsage`
  - `RiskEngineHaltGate`
- all five cases retained zero `OrderEvent`, zero broker accepts/rejects/fills, and zero mutating
  gateway requests;
- the demo account remained financially and operationally flat throughout the full run.

What this slice intentionally does not claim:

- it does not prove stateful risk gates that need controlled financial fixtures;
- the retained deferred contract still marks these as `deferred-not-passed`:
  - `margin-floor`
  - `daily-loss`
  - `drawdown`
  - `loss-streak`

### Stateful Risk Harness Status

New deterministic restored-state harnesses now exist in the branch:

- runner:
  [run-stateful-risk-containers.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-stateful-risk-containers.sh)
- preparer:
  [prepare-stateful-risk-matrix.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-stateful-risk-matrix.sh)
- focused shell regressions:
  [prepare-stateful-risk-matrix-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-stateful-risk-matrix-test.sh)
  and
  [run-stateful-risk-containers-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-stateful-risk-containers-test.sh)

What these new scripts are designed to prove:

- four unrestricted localhost containers run in parallel with no Docker/JVM restriction;
- each case restores deterministic `risk-state.json` state before deploy;
- the real halt rule fires on live `1m` bars/ticks, retaining at least one
  `StreamCandleEvent`, one `StrategyCandleEvaluatedEvent`, and one `RiskEvent.Halted`;
- the same strategy then emits one fixed `0.01`-lot intent which is rejected before MT5
  transport, retaining exactly one `RuleDecisionEvent`, one `DecisionOrderLinkedEvent`,
  one `RiskRejectedEvent`, zero `OrderEvent`, zero fills, and zero mutating gateway
  exchanges;
- account and venue state must remain flat and unchanged.

The currently generated deterministic cases are:

- `global-daily-loss`
- `strategy-daily-loss`
- `global-drawdown`
- `loss-streak`

Fresh retained localhost MT5 passing evidence also exists for the original controlled
`margin-floor` fixture:

- aggregate result:
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`
- opener result:
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/opener/evidence/result.json`
- probe result:
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/probe/evidence/result.json`
- dynamic floor evidence:
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/probe/evidence/dynamic-floor.json`

A prepared controlled fixture for that now-proven slice exists in the branch:

- preparer:
  [prepare-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-margin-floor-fixture.sh)
- runner:
  [run-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-margin-floor-fixture.sh)
- focused shell regression:
  [prepare-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-margin-floor-fixture-test.sh)
  and
  [run-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-margin-floor-fixture-test.sh)

The current hardened fixture now defines:

- an opener role that owns exactly one bounded `0.01`-lot live EURUSD demo position;
- a probe role whose runtime config must materialize
  `margin_floor_pct = ceil(observed_margin_level_pct) + 1000`;
- a required probe-side zero-transport `MarginFloor` rejection chain after live
  exposure exists; and
- a headroom-recovery phase where the same running probe opens after the opener is
  flattened, then returns the full account to zero positions and zero pending orders.

What the current runner proved on Wednesday, August 12, 2026:

- clean proving worktree:
  `/var/tmp/qkt-margin-floor-recovery-clean-20260812T014711Z`
- final aggregate result:
  `/var/tmp/qkt-validation/margin-floor-recovery-clean-20260812T023300Z-live/evidence/result.json`
- source fixture:
  `/var/tmp/qkt-validation/margin-floor-recovery-clean-20260812T023200Z-suite/suite.json`
- exact runner commit: `82adabd8`
- exact image: `qkt:live-validation-82adabd8`
- the opener first hit a real stale-market-data rejection, retried on the next clean even-minute
  bar, and then created one real bounded live position on localhost MT5;
- dynamic probe floor was `8696238`, derived from observed venue `margin_level`;
- the probe rejected `ORD-0` by `MarginFloor` before MT5 transport with one retained causal
  `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent` chain, zero `OrderEvent`,
  zero fills, and zero mutating gateway requests before recovery;
- the opener flatten restored margin headroom;
- the same running probe then opened recovered ticket `3073781855`, flattened it, and retained one
  successful recovered `/order` plus one successful recovered `/close_position`;
- final account was flat with zero pending orders; post-run primary account snapshot was
  balance/equity `99995.28`, margin `0`, positions `0`, orders `0`;
- the runner verified Docker resource restrictions absent and no JVM override variables; and
- harness fixes found during this proof were applied to the branch:
  `333b551e`, `e290ea56`, and `2f270a09`.

Do not use the older `ceil(observed_margin_level_pct) + 1` fixture formula. A live attempt showed
that ordinary small equity drift can move venue `margin_level` above that floor before probe
evaluation. The buffered `+1000` formula is the current validated harness contract.

Fresh retained passing evidence now exists for this stateful matrix:

- aggregate result:
  `/var/tmp/qkt-validation/stateful-risk-20260811T162402Z-live-thin/evidence/result.json`

What that passing retained run proved on Tuesday, August 11, 2026:

- four unrestricted localhost containers ran in parallel with no Docker CPU, memory, PID, or
  cpuset restriction and no JVM override variables;
- each case restored deterministic persisted risk state before deploy and retained one live
  `RiskEvent.Halted` before the synchronized even-minute order-path attempt;
- each case retained at least one `StreamCandleEvent`, one `StrategyCandleEvaluatedEvent`,
  one `RuleDecisionEvent`, one `DecisionOrderLinkedEvent`, and one `RiskRejectedEvent`;
- all four cases retained zero `OrderEvent`, zero fills, and zero mutating gateway exchanges;
- the final account and venue state remained flat and unchanged at `99997.52` USD with zero
  positions and zero pending orders.

Harness-only failures cleared on the way to the passing retained run:

- the first real localhost attempt wrote seeded state to
  `$case_dir/state/$strategy/risk-state.json` instead of
  `$case_dir/state/state/$strategy/risk-state.json`, so the restore fixture never loaded and the
  demo account briefly opened four real positions before the emergency close path flattened it;
- the next retries proved additional runner-only false negatives rather than engine faults:
  `RiskEvent.Halted` was matched as `RiskEvent$Halted`, the retained audit schema uses `seq` and
  `payload` rather than top-level `sequenceId` and `reason`, and retained candle symbols are only
  present in `payload` for the stream/evaluation events.

### Read-Only Already-Deployed Resync Probe

A new focused harness now exists for the daemon control-plane slice:

- runner:
  [run-readonly-resync.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-readonly-resync.sh)
- focused shell regression:
  [run-readonly-resync-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-readonly-resync-test.sh)

What the new runner is designed to prove:

- daemon starts with no `--load-dir` and zero auto-loaded strategies;
- a prepared read-only strategy is deployed through `qkt deploy`;
- exact `1m` and `5m` stream/evaluation joins occur before replacement;
- the same deployed name is replaced through `qkt resync`;
- the replacement is explicitly resumed if resync restores a halted state;
- exact `1m` and `5m` stream/evaluation joins occur again after replacement;
- the account stays flat, emits zero venue deals, and the MT5 transport stays read-only.

Fresh passing real localhost MT5 evidence now exists for this control-plane slice:

- aggregate result:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/result.json`
- deploy response:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/deploy.json`
- resync response:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/resync.json`
- explicit resume response:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/resume-after-resync.json`
- pre-resume daemon status:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/daemon-status-resynced.json`
- post-resume daemon status:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/evidence/daemon-status-resumed.json`
- retained daemon log:
  `/var/tmp/qkt-validation/resync-live-20260811e-3278321/logs/daemon.log`

What that passing live probe proved on Tuesday, August 11, 2026:

- the daemon started empty and accepted a real control-plane `deploy` of the prepared read-only
  strategy;
- exact matched `1m` and `5m` live stream/evaluation evidence was retained before replacement;
- a real control-plane `resync` replaced the running deployment under the same strategy name;
- the replacement restored with `running:true` but `halted:true`, so the harness issued a real
  `qkt resume`, after which daemon status retained `halted:false`;
- exact matched `1m` and `5m` live stream/evaluation evidence was retained again after the
  replacement and explicit resume;
- warmup pseudo-tick counts were exactly `80` per timeframe before and after resync;
- the full slice stayed financially read-only: zero order events, zero mutating gateway calls,
  zero venue deals, final-flat account state, zero dropped ticks, and zero inbound queue buildup;
- retained resource and latency evidence exists for the full deploy/resync/resume window.

What the earlier failed probe now means:

- the first live attempt at `/var/tmp/qkt-validation/resync-live-20260811d-3245880` was a harness
  false negative, not a proven runtime failure;
- the mistaken gate relied on post-resync log lines, but the restored rule-edge state kept those
  `LOG` rules latched `true`, so the replacement continued evaluating without re-emitting the same
  log markers;
- the harness now waits on audit-journal matched candle/evaluation evidence instead, which is the
  correct proof surface for this slice.

## Important Script-Level Fixes Behind The Final Insights Pass

The final Insights certification only passed after these harness corrections:

- read-only zero-signal rule evaluations no longer count as causal strategy decisions;
- candle values are compared canonically instead of failing on decimal scale differences;
- persisted `/live/state` is sanitized to a strict identity-safe projection;
- bracket decision joins use `submit.orderId` or `submit.planOrderId`;
- live validation waits for exact post-deployment M1/M5 evidence instead of racing the feed.

The commits for that final stabilization are:

- `14765f2d fix(scripts): scope insights ownership guard`
- `b21d1c56 fix(scripts): validate insights rule decisions`
- `573a2ebf fix(scripts): canonicalize insights candle values`
- `ed5284d9 fix(scripts): sanitize insights live state`
- `0621edc5 fix(scripts): join bracket decisions through plan ids`
- `4d37ebb4 fix(scripts): await live multi-timeframe evidence`

## What Is Still Incomplete

The branch has strong evidence, but the full program is still incomplete against the governing plan
and the user scope.

### Hard blockers for `main`

These items are still open and prevent honest promotion:

- the exhaustive parity plan still has unchecked matrix rows for:
  - non-vacuous DSL trace scenarios across the full language surface;
  - generated temporary `.qkt` strategies/config/risk/book-risk/identity artifacts for every live
    scenario;
  - full ticks / independent bars / ordinary bar replay / tick-resolved bars / live-paper coverage
    for every applicable scenario;
  - complete order lifecycle coverage across all order types, TIFs, partials, cancels, expiry,
    brackets, OCO, OTO, trailing, stack, scale-out, timed exits, closes, resize, halt, and restart;
  - complete reports/journals/manifests/Insights/portfolio reconciliation for every applicable row;
  - multi-symbol, multi-timeframe, multi-strategy, and portfolio aggregation/isolation proof;
  - staged multi-container QKT concurrency proving account-wide reconciliation and strict
    per-container ownership;
  - post-final-fix rerun of the entire required matrix;
  - the required 30-day demo burn-in.
- the current live daemon and bot execution path is still config-file driven. The runtime still
  loads `qkt.config.yaml` via `Config.resolvePath(...)`, so configless live parity is an honest
  unsupported gap, not a proven slice.
- the new bounded two-container round-trip pass is strong evidence, but it is still only the first
  shared-account live order-parity slice. It does not yet prove broader same-account concurrency
  across additional symbols, additional order families, restarts, already-deployed state, or
  portfolio/book isolation.
- the sustained load/restart slice is now certified passed, the first bounded order-bearing
  restart slice is now certified passed, and the live risk-rejection matrix is now certified
  passed for static pre-transport cases, but broader same-account load, reconnect,
  already-deployed, resynchronization, wider order-bearing restart measurements, and stateful risk
  fixtures are still open.
- the already-deployed control-plane harness is now implemented, regression-tested, and backed by a
  real localhost MT5 passing deploy/resync/resume read-only proof, but broader reconnect,
  same-account already-deployed, and broader order-bearing restart slices are still open.
- the read-only gateway-restart harness is now implemented, regression-tested, and backed by a
  real localhost MT5 passing proof at
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/result.json`, and the
  read-only already-deployed reconnect slice is now separately backed by
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/result.json`, while
  the first bounded real-order restart slice is now separately backed by
  `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/result.json`;
  broader same-account reconnect and broader restart slices are still open.

- the repo go-live policy explicitly requires a minimum 30-day demo burn-in before advancing past
  the final demo stage:
  [go-live-ramp.md](/home/dickson/Desktop/personal/qkt/docs/operations/go-live-ramp.md:11)

- `../notes.txt` expands scope beyond what is currently certified:
  it calls for full indicator/math/DSL/order/portfolio live-vs-backtest proof, edge-case coverage,
  and only then external strategy-book adaptation.

### Not started yet at the final objective level

- deployment of the updated `qkt`, `qkt-insights`, and related runtime pieces to `qkt-forge` on
  `sshbot2`;
- rerun of the selected strategies in the `qkt-forge` forward-test environment;
- portfolio backtests after live parity is proven, using the updated runtime and Insights
  attribution;
- later bot1 `qkt-quantlive` update with the proven `qkt` and `qkt-insights` changes after the
  `sshbot2` forward-test stack is clean;
- full book-level trade separation in Insights/UI/accounting for same-account multi-book operation.

These are intentionally downstream of the engine/runtime validation gates and should remain
downstream.

## Current Required Checks Status

As of Wednesday, August 12, 2026:

- `git status --short --branch`: branch `test/exhaustive-live-parity`, `ahead 149`, tracked
  worktree clean after the market history reconciliation hardening commit; two pre-existing untracked
  Kimi/audit docs remain.
- Current continuation commits now on the branch:
  - `fix(scripts): bound market history reconciliation`
  - `test(dsl): cover pending reentry guard`
  - `docs(docs): seal loss-streak reentry live evidence`
  - `feat(scripts): add loss-streak reentry live lifecycle`
  - `docs(docs): seal cooldown reentry live evidence`
  - `feat(scripts): add cooldown reentry live lifecycle`
  - `test(risk): cover portfolio book exposure recovery`
  - `docs(docs): seal margin floor recovery status`
  - `test(risk): cover global daily halt reentry reset`
  - `test(risk): cover daily halt reentry reset`
  - `5a041d4a test(dsl): cover higher timeframe warmups`
  - `5d3eb7ae docs(docs): record higher timeframe parity proof`
  - `0e7817fa feat(scripts): add restart and stateful validation`
  - `125098c8 feat(scripts): prepare generated parity wave`
  - `1824d2e4 fix(scripts): harden parity validation checks`
  - `16341606 docs(docs): update parity validation evidence`
- `rg -n 'TODO|FIXME|XXX' src/ || true`: clean
- `./gradlew test --tests 'com.qkt.dsl.compile.HigherTimeframeWarmupParityTest' --tests 'com.qkt.dsl.compile.GeneratedTimeframeParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after adding explicit `15m`, `1h`, and `4h` warmup-count
  parity coverage and adding `4h` to the generated timeframe capability catalog.
- `bash tests/scripts/prepare-stateful-risk-matrix-test.sh && bash tests/scripts/run-stateful-risk-containers-test.sh && bash tests/scripts/run-readonly-resync-test.sh && bash tests/scripts/run-readonly-gateway-restart-test.sh && bash tests/scripts/run-readonly-deployed-gateway-restart-test.sh && bash tests/scripts/run-order-gateway-restart-test.sh`:
  passed on Wednesday, August 12, 2026 before committing the restart/resync/stateful validation
  harness files.
- `bash tests/scripts/prepare-generated-parity-wave-test.sh`: passed on Wednesday, August 12, 2026
  before committing the generated four-case parity wave preparer.
- `./gradlew test --tests 'com.qkt.backtest.TickResolvedParityTest' --tests 'com.qkt.dsl.compile.GeneratedIndicatorStrategyTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after committing the tick-resolved replay-input
  normalization and generated indicator log-budget hardening.
- `bash tests/scripts/run-container-load-test.sh`: passed on Wednesday, August 12, 2026 before
  committing the container-load health/version hardening.
- `./gradlew test --tests 'com.qkt.marketdata.MarketDataGateTest' --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after adding explicit stale-market-data re-entry regression
  coverage. No JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.risk.rules.BookExposureLimitTest' --tests 'com.qkt.parity.GeneratedBookLimitParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after adding explicit book-exposure re-entry recovery
  coverage. No JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.risk.rules.MarginFloorTest' -Pkotlin.compiler.execution.strategy=daemon && bash tests/scripts/prepare-margin-floor-fixture-test.sh && bash tests/scripts/run-margin-floor-fixture-test.sh`:
  passed on Wednesday, August 12, 2026 after adding explicit margin-floor re-entry recovery
  coverage. No JVM heap or worker restrictions were used.
- `bash tests/scripts/run-margin-floor-fixture-test.sh`: passed again on Wednesday, August 12, 2026
  while correcting the handoff to treat the retained live margin-floor recovery fixture as sealed
  rather than open. No JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.cli.daemon.portfolio.PortfolioDeployerE2ETest.book exposure rejects a follow-up order and recovers after exposure clears' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after extending the real portfolio deployer E2E path to prove
  a shared book controller rejects an oversized same-book follow-up order, allows the
  risk-reducing sell, samples the cleared exposure, and then allows a later recovered entry. The
  test also reads the durable order journal and asserts the rejected order was recorded as
  `risk-rejected` with `book gross exposure`, so the cause is audited rather than inferred only from
  fill counts. No JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after adding explicit UTC next-day max-trades re-entry
  recovery coverage, elapsed cooldown-after-loss re-entry recovery coverage, and loss-streak reset
  coverage across ticks, bars, tick-resolved bars, and live-paper. No JVM heap or worker restrictions
  were used.
- `bash tests/scripts/prepare-live-validation-scenario-test.sh`: passed on Wednesday, August 12,
  2026 after adding generated live market-bracket runner and replay-comparator support for
  `reentry_cooldown_recovered`. The first armed attempt with a 30-second cooldown proved that value
  was too short for a 1m-bar re-entry signal, so the reviewed lifecycle now uses a 90-second
  cooldown. The corrected retained armed live run and replay comparison then passed at
  `/var/tmp/qkt-validation/xau-cooldown-recovery-reentry-90s-20260812T042800Z/evidence/result.json`
  and
  `/var/tmp/qkt-validation/xau-cooldown-recovery-reentry-90s-20260812T042800Z-replay/result.json`.
- `bash tests/scripts/prepare-live-validation-scenario-test.sh`: passed again on Wednesday, August
  12, 2026 after adding generated live market-bracket runner and replay-comparator support for
  `reentry_blocked_loss_streak`. The retained armed live run and replay comparison then passed at
  `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z/evidence/result.json` and
  `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z-replay/result.json`.
- `bash -n scripts/live-validation/run-market-bracket.sh && bash -n tests/scripts/prepare-live-validation-scenario-test.sh`:
  passed on Wednesday, August 12, 2026 after adding a process-level timeout around final
  `qkt bot history` reconciliation in the market-bracket runner.
- `bash tests/scripts/prepare-live-validation-scenario-test.sh`: passed again on Wednesday, August
  12, 2026 after pinning the market-bracket runner's bounded history reconciliation helper and
  per-attempt diagnostic stderr artifacts.
- `./gradlew test --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed again on Wednesday, August 12, 2026 after adding explicit generated-DSL strategy daily-loss
  recovery coverage: first live-paper/replay order lifecycle trips a DAILY strategy halt, same-day
  re-entry is rejected with the retained halt reason, and next-UTC-day re-entry opens and closes
  identically across tick, bar, tick-resolved-bar, and live-paper modes. No JVM heap or worker
  restrictions were used.
- `./gradlew test --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed again on Wednesday, August 12, 2026 after adding the sibling generated-DSL strategy
  daily-drawdown recovery proof with `dailyDdBasis = EQUITY`: an open equity drawdown trips a DAILY
  strategy halt, same-day re-entry is rejected with the retained halt reason, and next-UTC-day
  re-entry opens and closes identically across tick, bar, tick-resolved-bar, and live-paper modes. No
  JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed again on Wednesday, August 12, 2026 after adding explicit generated-DSL pending-entry
  duplicate prevention coverage: persistent qualifying entry bars create only one pending limit
  order while `OPEN_ORDERS.x != 0`, the first fill/close cycle completes, and the same guarded
  strategy re-enters exactly once after the close across tick, bar, tick-resolved-bar, and
  live-paper modes. No JVM heap or worker restrictions were used.
- `./gradlew test --tests 'com.qkt.parity.GeneratedReentryParityTest' -Pkotlin.compiler.execution.strategy=daemon`:
  passed again on Wednesday, August 12, 2026 after adding generated-DSL global DAILY halt recovery
  coverage for both `MaxDailyLoss` and `MaxDailyDrawdown`: the account/global halt rejects same-day
  new exposure, allows risk-reducing exits, auto-resumes at the next UTC day boundary, and then
  opens/closes the recovered position identically across tick, bar, tick-resolved-bar, and live-paper
  modes. No JVM heap or worker restrictions were used.
- `./gradlew test -Pkotlin.compiler.execution.strategy=daemon`: passed on Tuesday, August 11, 2026
  after narrowing the `TickResolvedParityTest` comparison to ignore only replay-input counters that
  intentionally differ between full-tick replay and `--bars --tick-fills`:
  - `inputSummary.attemptedFeedTicks`
  - `inputSummary.liveTicks`
- `./gradlew build -Pkotlin.compiler.execution.strategy=daemon`: passed on Tuesday, August 11,
  2026 after adding test-local logger suppression in
  [GeneratedIndicatorStrategyTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/dsl/compile/GeneratedIndicatorStrategyTest.kt:14)
  so the generated indicator parity cases stay within the existing repo log budget guard.
- `tests/scripts/run-readonly-gateway-restart-test.sh`: passed on Tuesday, August 11, 2026 after
  hardening the runner for broker-safe startup, auto-deploy readiness, reconnect warmup semantics,
  and container-inspection secret redaction.
- `tests/scripts/run-readonly-deployed-gateway-restart-test.sh`: passed on Tuesday, August 11, 2026
  after adding the control-plane deployed reconnect slice with broker-safe deploy timing and
  container-inspection secret redaction.
- `tests/scripts/run-order-gateway-restart-test.sh`: passed on Tuesday, August 11, 2026 after
  hardening password-redaction scanning, post-restart persistence fallback, strategy-scoped warmup
  counting, the armed `40`-warmup invariant, transport correlation slurping, and latency capture
  ordering before daemon shutdown.
- `tests/scripts/prepare-risk-rejection-matrix-test.sh`: passed on Tuesday, August 11, 2026.
- `tests/scripts/run-risk-rejection-containers-test.sh`: passed on Tuesday, August 11, 2026.
- `tests/scripts/prepare-stateful-risk-matrix-test.sh`: passed on Tuesday, August 11, 2026.
- `tests/scripts/run-stateful-risk-containers-test.sh`: passed on Tuesday, August 11, 2026.
- `tests/scripts/run-container-round-trips-test.sh`: passed on Tuesday, August 11, 2026 after
  hardening the shared-account runner to record zero timestamp/zero price gateway ticks as invalid
  startup/freshness evidence before any live order can arm.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed on Tuesday, August 11, 2026
  after applying the same invalid-tick startup and post-daemon freshness handling to the generated
  single-strategy bracket runner.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed again after adding the static
  XAUUSD re-entry preparation contract. This proves the preparer can emit a two-entry lifecycle
  scenario with `TRADES.today < 2`, `max_trades_per_day: 2`, `maximumEntries:2`, and
  `maximumExits:2`; the live proof is recorded in the later XAUUSD re-entry evidence block.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed again after extending
  `run-market-bracket.sh` with a `lifecycle=reentry` execution branch. This proves the shell
  contract for waiting on two magic-scoped open/flat cycles, retaining all owned tickets, and
  requiring two entry placements plus two strategy-owned close calls.
- `./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon`: passed in clean proving
  worktree `/var/tmp/qkt-reentry-clean-20260812T000850Z` at `db7b50a1` on Wednesday,
  August 12, 2026. No JVM heap or container resource caps were set.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed in the same clean proving
  worktree after adding multi-entry comparator support and after correcting replay trade-event
  counting for strategy-owned exits.
- `scripts/live-validation/compare-golden-replay.sh`: passed for the clean XAUUSD re-entry capture at
  `/var/tmp/qkt-validation/xau-reentry-clean-20260812T001516Z-replay5/result.json`.
- `./gradlew test --tests com.qkt.parity.GeneratedReentryParityTest -Pkotlin.compiler.execution.strategy=daemon`:
  passed on Wednesday, August 12, 2026 after expanding generated re-entry parity coverage for
  allowed re-entry plus blocked re-entry under max trades, cooldown after loss, loss streak halt,
  strategy daily loss, strategy drawdown, strategy daily drawdown, global daily loss, global
  drawdown, and global daily drawdown.
- `./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon`: passed in clean proving
  worktree `/var/tmp/qkt-blocked-reentry-clean-20260812T004243Z` at `01161803` on Wednesday,
  August 12, 2026. No JVM heap or container resource caps were set.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed in the same clean proving
  worktree after adding the `reentry_blocked_max_trades` scenario, live runner contract, and replay
  comparator contract.
- `scripts/live-validation/compare-golden-replay.sh`: passed for the clean XAUUSD blocked re-entry
  capture at
  `/var/tmp/qkt-validation/xau-blocked-reentry-clean-20260812T004730Z-replay/result.json`.
- `./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon`: passed in clean proving
  worktree `/var/tmp/qkt-operator-halt-reentry-clean-20260812T010429Z` at `13dbb027` on Wednesday,
  August 12, 2026. No JVM heap or container resource caps were set.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed in the same clean proving
  worktree after adding the `reentry_blocked_operator_halt` scenario and live runner contract.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed again in the main checkout after
  hardening replay comparison for external operator-halt control-plane events.
- `scripts/live-validation/compare-golden-replay.sh`: passed for the clean XAUUSD operator-halt
  blocked re-entry capture at
  `/var/tmp/qkt-validation/xau-operator-halt-reentry-clean-20260812T010840Z-replay2/result.json`.
- `./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon`: passed in clean proving
  worktree `/var/tmp/qkt-operator-recovery-reentry-clean-20260812T012403Z` at `f0ca7d5b` on
  Wednesday, August 12, 2026. No JVM heap or container resource caps were set.
- `tests/scripts/prepare-live-validation-scenario-test.sh`: passed in the same clean proving
  worktree after adding the `reentry_operator_halt_recovered` scenario, live runner contract, and
  replay comparator contract.
- `scripts/live-validation/compare-golden-replay.sh`: passed for the clean XAUUSD operator-halt
  recovery capture at
  `/var/tmp/qkt-validation/xau-operator-recovery-reentry-clean-20260812T012825Z-replay/result.json`.

The repo-health checks are green on the current `HEAD`. The remaining blockers are no longer local
build instability; they are the still-open exhaustive live-validation matrix and the required demo
burn-in.

## Current Active Next Slice

The current active slice is no longer the first bounded order-bearing reconnect proof; that slice
is now sealed as a passing retained result. The next active slice is the broader generated
live/backtest parity matrix that `../notes.txt` describes.

The operating model is now one driver agent with two local MT5 demo gateways. The old split-agent
coordination note is historical only and should not drive current work.

Current live-gateway inventory:

- primary gateway:
  - container: `lab-mt5-gateway`
  - host URL: `http://127.0.0.1:5001`
  - login: `436804390`
  - server: `Exness-MT5Trial9`
- secondary gateway:
  - container: `lab-mt5-gateway-demo2`
  - host URL: `http://127.0.0.1:5002`
  - login source: [demo2.txt](</home/dickson/Desktop/personal/demo2.txt:1>)
  - verified login: `476434211`
  - verified server: `Exness-MT5Trial9`
  - verified state on Wednesday, August 12, 2026 before the loss-streak run: healthy, MT5
    connected, flat, trading allowed, leverage `1000`, balance/equity `99996.94`
  - verified state after the loss-streak run by retained reconciliation: final positions `0`, final
    orders `0`, balance delta `-1.29` matched owned deal net `-1.29`

- API key is intentionally not persisted in repo docs; retrieve it locally from container inspect if
  needed.

Any future armed live run must use the account-scoped live lock enforced in
[run-market-bracket.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-market-bracket.sh):
armed runs acquire `flock` on `/var/tmp/qkt-validation/LIVE-LOCK-<server>-<login>`, record holder
metadata including gateway URL and account identity, retain `evidence/live-lock.txt`, and fail fast
if another armed run is already using that same demo account. Different demo accounts can run
independently.

Why this next slice matters:

- read-only reconnect is now proven in both auto-loaded and control-plane deployed forms;
- bounded real orders are already proven in normal live operation, in concurrent two-container live
  operation, and now in the first real gateway-restart order-bearing slice;
- static live risk rejection is now also proven on the real localhost gateway for five reviewed
  cases without mutating the venue;
- deterministic restored-state harnesses for `global-daily-loss`, `strategy-daily-loss`,
  `global-drawdown`, `loss-streak`, and the controlled `margin-floor` fixture now have retained
  localhost MT5 passing proof, including
  `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`;
- what remains unproven is the larger matrix the user actually asked for: generated non-vacuous
  indicator/math/DSL/order-path scenarios, replay/live/backtest comparison on the same retained
  windows, broader same-account isolation, and QKT Insights attribution across that matrix.

What now exists in the branch:

1. a deterministic restored-state matrix preparer:
   [prepare-stateful-risk-matrix.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-stateful-risk-matrix.sh)
2. a deterministic restored-state matrix runner:
   [run-stateful-risk-containers.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-stateful-risk-containers.sh)
3. focused shell coverage:
   [prepare-stateful-risk-matrix-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-stateful-risk-matrix-test.sh)
   and
   [run-stateful-risk-containers-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-stateful-risk-containers-test.sh)
4. runtime account-identity-safe health snapshot support in
   [account-identity.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/lib/account-identity.sh)
5. a generated four-case order-bearing wave preparer:
   [prepare-generated-parity-wave.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-generated-parity-wave.sh)
6. focused shell coverage for that preparer:
   [prepare-generated-parity-wave-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-generated-parity-wave-test.sh)
7. a shared-account Insights wrapper around the bounded two-container live round-trip proof:
   [run-shared-account-insights-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-shared-account-insights-round-trips.sh)
8. focused shell coverage for that wrapper:
   [run-shared-account-insights-round-trips-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-shared-account-insights-round-trips-test.sh)
9. a runner contract that requires:
   - four parallel unrestricted containers;
   - restored deterministic `risk-state.json` before deploy;
   - live `1m` bar proof via `StreamCandleEvent` and `StrategyCandleEvaluatedEvent`;
   - one real `RiskEvent.Halted` before the order-path rejection;
   - one bounded fixed `0.01`-lot intent;
   - one causal `RuleDecisionEvent -> DecisionOrderLinkedEvent -> RiskRejectedEvent` chain;
   - zero `OrderEvent`, zero fills, zero mutating gateway exchanges;
   - exact post-run final-flat reconciliation and no JVM or Docker resource restriction.

Files to open first for that slice:

- [prepare-stateful-risk-matrix.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-stateful-risk-matrix.sh)
- [run-stateful-risk-containers.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-stateful-risk-containers.sh)
- [prepare-stateful-risk-matrix-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-stateful-risk-matrix-test.sh)
- [run-stateful-risk-containers-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-stateful-risk-containers-test.sh)
- [prepare-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-margin-floor-fixture.sh)
- [run-margin-floor-fixture.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-margin-floor-fixture.sh)
- [prepare-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-margin-floor-fixture-test.sh)
- [run-margin-floor-fixture-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-margin-floor-fixture-test.sh)
- [run-order-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-order-gateway-restart.sh)
- [run-order-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-order-gateway-restart-test.sh)
- [run-insights-attribution.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-insights-attribution.sh)
- [run-shared-account-insights-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-shared-account-insights-round-trips.sh)
- [run-shared-account-insights-round-trips-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-shared-account-insights-round-trips-test.sh)
- [run-market-bracket.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-market-bracket.sh)
- [run-container-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-container-round-trips.sh)
- [compare-container-round-trip-replay.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/compare-container-round-trip-replay.sh)
- [prepare-generated-parity-wave.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-generated-parity-wave.sh)
- [prepare-generated-parity-wave-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/prepare-generated-parity-wave-test.sh)
- [README.md](/home/dickson/Desktop/personal/qkt/scripts/live-validation/README.md)

Fresh retained evidence that should be preserved and treated as upstream dependencies for that next
slice:

- `/var/tmp/qkt-validation/insights-4d37ebb4-final/evidence/result.json`
- `/var/tmp/qkt-validation/insights-4d37ebb4-rerun3-20260811T203600Z/evidence/result.json`
- `/var/tmp/qkt-validation/roundtrip-live-20260811/evidence/result.json`
- `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/result.json`
- `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/result.json`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T143301Z-latencyfix/scenario/evidence/result.json`
- `/var/tmp/qkt-validation/riskreject-20260811T145134Z-live/evidence/result.json`
- `/var/tmp/qkt-validation/stateful-risk-20260811T162402Z-live-thin/evidence/result.json`
- `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`
- `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z/evidence/result.json`
- `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z-replay-20260811T180849Z/result.json`
- `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean5-20260811T180008Z/evidence/result.json`
- `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z/evidence/result.json`
- `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z-replay-20260811T182759Z/result.json`
- `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z/evidence/result.json`
- `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z-replay-20260811T185752Z/result.json`
- `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z/evidence/result.json`
- `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z-replay-20260811T191243Z/result.json`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T115924Z`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T121122Z-retry`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T133501Z-postpersistfix/scenario`
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T134728Z-postwarmupscopefix/scenario`

## Exact Next Steps

1. Treat the generated single-strategy wave as sealed upstream proof, not the active gap:
   - `rsi-gbpusd` live/replay:
     `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z/evidence/result.json`
     and
     `/var/tmp/qkt-validation/generated-gbpusd-rsi-clean6-20260811T180626Z-replay-20260811T180849Z/result.json`
   - `ema-eurusd` live/replay:
     `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z/evidence/result.json`
     and
     `/var/tmp/qkt-validation/generated-eurusd-ema-clean3-20260811T182256Z-replay-20260811T182759Z/result.json`
   - `case-gbpusd` live/replay:
     `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z/evidence/result.json`
     and
     `/var/tmp/qkt-validation/generated-gbpusd-case-clean2-20260811T185144Z-replay-20260811T185752Z/result.json`
   - `atr-eurusd` live/replay:
     `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z/evidence/result.json`
     and
     `/var/tmp/qkt-validation/generated-eurusd-atr-clean4-20260811T191015Z-replay-20260811T191243Z/result.json`
2. Treat the retained `margin-floor` localhost proof as sealed upstream proof, not an open harness
   contract gap:
   `/var/tmp/qkt-validation/margin-floor-20260811T171226Z-live-thin/evidence/result.json`
3. Treat the two-symbol shared-account QKT Insights live slice as sealed for exact delivery,
   producer-local sequence restart tolerance, strict per-instance/per-strategy attribution, and
   live-to-replay parity:
   - live wrapper:
     `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-live/evidence/result.json`
   - base live order proof:
     `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-live/base-roundtrip/evidence/result.json`
   - EURUSD replay:
     `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-eurusd-replay/result.json`
   - GBPUSD replay:
     `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-gbpusd-replay/result.json`
4. Use the existing runner contract in
   [run-insights-attribution.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-insights-attribution.sh)
   only for any remaining single-instance Insights retry/delivery edges not already covered by the
   shared-account wrapper's causal ingest probe and live retained state.
5. Expand from the already-proven first bounded concurrency proof into broader same-account
   multi-strategy and multi-book isolation/reconciliation on one MT5 account, while keeping the
   second local demo account available as an independent lane for unrelated armed runs.
   The first new harness for that slice now exists in
   [run-shared-account-insights-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-shared-account-insights-round-trips.sh):
   it wraps the proven two-container live round-trip runner with a local Insights collector,
   enforces the `/healthz` plus causal-ingest contract before any broker mutation, and verifies
   per-instance/per-strategy order and deal attribution after the shared-account live pass.
   As of Wednesday, August 12, 2026, that wrapper is backed by retained real-demo evidence on
   demo2.
6. Before every new armed live pass, prepare the scenario from the current account snapshot and use
   the lock-enforcing
   [run-market-bracket.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-market-bracket.sh)
   contract, which acquires `flock` on `/var/tmp/qkt-validation/LIVE-LOCK-<server>-<login>`.
7. Treat demo2 as the second ready independent lane. On Wednesday, August 12, 2026, login
   `476434211` was used for the shared-account Insights proof at starting balance `99999.52` and
   leverage `1000`; any new prepared scenario on `http://127.0.0.1:5002` must refresh from the
   latest live account snapshot first.
8. Extend the exhaustive matrix in this priority order:
   - Insights attribution and same-account strategy/book isolation;
   - broader order families and lifecycle paths beyond the first bounded bracket cases;
   - explicit already-deployed, restart, reconnect, and resynchronization slices;
   - generated live scenarios for the remaining DSL/runtime capability gaps;
   - full post-final-fix rerun with retained checksummed evidence.
8. Re-run `git status --short --branch`, `git log --oneline origin/dev..HEAD`, and
   `rg -n 'TODO|FIXME|XXX' src/ || true` after the focused verification passes complete.
9. After the short-form exhaustive matrix is green, run a quick sanity QKT strategy proof against
   the final fixed branch.
10. Promote by PR through `dev -> testing -> main`; after promotion, apply the updated `qkt`,
    `qkt-insights`, and related runtime pieces to `qkt-forge` on `sshbot2`, rerun the selected
    strategies there for forward testing, run the portfolio backtests on the proven stack, and then
    update bot1 `qkt-quantlive` with the proven `qkt` and `qkt-insights` changes.

Fresh bounded Insights rerun on Tuesday, August 11, 2026:

- authoritative retained pass:
  `/var/tmp/qkt-validation/insights-4d37ebb4-rerun3-20260811T203600Z/evidence/result.json`
- clean proving worktree:
  `/var/tmp/qkt-insights-rerun-20260811T202300Z`
- gateway/account lane:
  `http://127.0.0.1:5002`, login `476434211`, server `Exness-MT5Trial9`
- collector image that satisfied both the `/healthz` JSON contract and the causal ingest probe:
  `qkt-insights:validation-9a694c22`
- collector-image false negatives that were rejected before any broker mutation:
  - `qkt-insights:dev` served the SPA shell at `/healthz` instead of the runner's expected JSON
    health response;
  - `qkt-insights:validation-c17703a` passed `/healthz` but rejected the runner's causal execution
    contract probe.
- retained pass facts:
  - `status: passed`
  - `symbol: EXNESS:EURUSD`
  - bars proved: `warmupM1:true`, `warmupM5:true`, `liveTicks:true`,
    `matchedM1Evaluation:true`, `matchedM5Evaluation:true`
  - retained read-only closed bars: `readonlyClosedCandleEvents:22`
  - outage proof: `pendingBeforeRecovery:341`, `replayDrained:true`
  - exact owner telemetry: `ruleDecisions:2`, `decisionOrderLinks:2`, `submitted:2`,
    `accepted:2`, `filled:2`, `trades:2`, `fillAccounted:2`, `rejected:0`,
    `falseSequenceObservations:0`, `dropped:0`
  - ownership proof: `liveState.attributedOpenObserved:true`, `strategyOwnedClose:true`
  - final venue/account state: `flat:true`, `pendingOrders:0`, demo2 final balance `99999.60`,
    leverage `1000`

Fresh shared-account Insights progress on Tuesday, August 11, 2026:

- clean proving worktree remained:
  `/var/tmp/qkt-insights-rerun-20260811T202300Z`
- proving commits added while hardening the shared-account wrapper:
  - `fbd0820a fix(scripts): dedupe shared insights config`
  - `3a9ed6eb fix(scripts): preserve shared scenario checksums`
- local harness regressions added and re-verified after each fix:
  - `bash tests/scripts/run-shared-account-insights-round-trips-test.sh`
  - direct shell check of `strip_top_level_block` behavior against a config that already contains a
    top-level `insights:` block

What the two new harness fixes mean:

1. the wrapper no longer appends a second top-level `insights:` block onto prepared scenarios that
   already contain `insights.enabled: false`; before this fix, SnakeYAML rejected the config before
   any broker mutation;
2. the wrapper now preserves the prepared-scenario checksum contract by excluding `cleanup.json`
   when it regenerates `SHA256SUMS`, matching
   [prepare-scenario.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-scenario.sh);
   before this fix, the live run completed but the wrapper failed afterward because the base runner
   legitimately mutates `cleanup.json` during ownership tracking.

Retained shared-account live evidence roots from this hardening cycle:

- duplicate-`insights` false-negative run:
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T201637Z`
- checksum-contract false-negative run:
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T202050Z`
- post-harness-fix live rerun with manual collector audit:
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T202957Z`

What the live MT5 side already proved in the shared-account reruns:

- two unrestricted QKT containers traded the same localhost MT5 demo account concurrently;
- both strategies opened one real `0.01`-lot bounded bracket and then closed flat with exact
  strategy-owned exits;
- the account returned to flat with zero pending orders after the bounded round trips;
- the wrapper's retained DB already shows per-instance lifecycle order rows are correct:
  [manual-orders-by-instance.json](/var/tmp/qkt-validation/shared-account-insights-live-20260811T202957Z/evidence/manual-orders-by-instance.json)
  contains exactly two `FILLED` rows for `shared_account_insights_a_market_bracket` and exactly two
  `FILLED` rows for `shared_account_insights_b_market_bracket`.

What remains the real failing condition after the local harness fixes:

- the collector image `qkt-insights:validation-9a694c22` still attributes shared-account deal rows
  incorrectly across instances and strategies;
- retained manual audit:
  [manual-deals-by-instance.json](/var/tmp/qkt-validation/shared-account-insights-live-20260811T202957Z/evidence/manual-deals-by-instance.json)
  shows:
  - null-owned `EXNESS:EURUSD` and `EXNESS:GBPUSD` deal rows inside instance
    `qkt-live-shared-shared_account_insights_a`;
  - foreign `shared_account_insights_b_market_bracket` deal rows inside instance
    `qkt-live-shared-shared_account_insights_a`;
  - foreign `shared_account_insights_a_market_bracket` deal rows inside instance
    `qkt-live-shared-shared_account_insights_b`;
- retained manual ingest observations:
  [manual-ingest-observations.json](/var/tmp/qkt-validation/shared-account-insights-live-20260811T202957Z/evidence/manual-ingest-observations.json)
  shows duplicate observations still being recorded during the shared-account slice;
- this means the local harness is now strong enough to expose the remaining defect, but the
  remaining defect is not yet fixed in this repo's live validation flow.

Practical consequence for the next person:

- treat the shared-account wrapper itself as locally hardened enough to continue with;
- treat the remaining blocker in this slice as collector-side shared-account attribution correctness,
  not a runner-only false negative;
- refresh the primary-account starting balance from the gateway immediately before each new prepared
  scenario; after the hardening cycle above, the live primary account balance had moved to
  `99995.99` before the most recent rerun.

## Next Full Task Set Derived From `../notes.txt`

This is the exact next-stage execution order implied by the user scope note. It is broader than the
already-passing harness slices above and narrower than final production promotion. It is the work
that still has to be done before any honest `main` promotion claim.

### Phase 1: Insights Attribution And Shared-Account Isolation

The next active proof target is not more runner plumbing. It is proving that live ownership,
collector delivery, and same-account attribution remain exact once multiple strategies and books
share one MT5 account.

This phase should retain:

1. exact live strategy/book ownership through open position state, order lifecycle, deal
   attribution, and final flat reconciliation;
2. durable Insights journal replay after bounded collector interruption, with no false gaps,
   regressions, or cross-owner causal leakage;
3. explicit same-account separation proof showing one strategy/book cannot steal another
   strategy/book's position, PnL, or order lineage;
4. retained live-state, audit-journal, transport-journal, and collector-database evidence that can
   be replayed or inspected offline afterward.

### Phase 2: Generated Single-Strategy Live/Replay/Backtest Parity Matrix

The next large body of work is not more harness plumbing. It is generated, non-vacuous strategy
coverage that proves the runtime uses the intended DSL, indicators, math, and timing semantics on
real live data and then compares those exact retained windows back through offline modes.

For each generated scenario, retain:

- the generated `.qkt` source;
- the generated config, risk, and book-risk inputs;
- exact expected trace contracts;
- captured live bars/ticks and audit journals;
- live order/fill/accounting evidence when the scenario is order-bearing;
- replay/backtest comparison outputs over the same retained data window.

The minimum scenario families are:

1. indicator-driven entry and exit:
   - EMA
   - SMA
   - RSI
   - ATR
   - mixed-indicator conditions
2. math and numeric expressions:
   - arithmetic
   - comparisons
   - clamping or threshold logic
   - percentage and ratio style transforms already supported by the DSL/runtime
3. bar and timing semantics:
   - tick-triggered logic
   - `1m`
   - `5m`
   - mixed-timeframe joins
   - timed exits
   - close-on-bar semantics
4. action and order-path semantics:
   - market entry
   - close
   - cancel
   - bracket attach
   - OCO/OTO where supported
   - scale-out / resize / stack where supported

For every applicable scenario family, run and compare across:

- live localhost MT5;
- retained replay through `full-ticks-paper`;
- retained replay through `full-ticks-mt5`;
- retained replay through `bars-paper`;
- any supported tick-resolved bar mode for that scenario.

### Phase 3: Shared-Account Concurrency And Isolation Expansion

The first bounded two-container live slice passed, but `../notes.txt` asks for much more than one
proof case. The next expansion has to show that multiple live strategies/books can coexist on the
same demo account without attribution bleed or reconciliation ambiguity.

Required extensions:

1. run multiple bounded live strategies in parallel across more symbols and more timeframe mixes;
2. prove strict per-strategy and per-book ownership of:
   - intents
   - orders
   - fills
   - accounting
   - positions
   - final PnL attribution
3. prove same-account final reconciliation still returns to flat with no orphan positions or
   pending orders;
4. retain side-by-side account-wide versus per-strategy/per-book views so that later `qkt-forge`
   forward testing can trust the separation.

### Phase 4: QKT Insights Attribution Matrix

`../notes.txt` explicitly requires confidence that data reaches QKT Insights correctly and remains
separable by strategy/book.

Required proof areas:

1. retain exact live-to-Insights delivery for the generated scenario matrix, not only the first
   bounded order lifecycle;
2. prove retry and recovery behavior when delivery is delayed or restarted;
3. prove strict strategy and book attribution for:
   - decisions
   - orders
   - fills
   - PnL
   - equity/performance views
4. identify and fix any case where MT5 account-wide trades bleed into unrelated strategy or book
   views.

### Phase 5: Portfolio And Book Validation

The user scope is not limited to single strategies. Before the updated stack is applied to
`qkt-forge` on `sshbot2`, the runtime needs the same kind of retained proof for portfolio and book
behavior.

Required work:

1. generate and run bounded portfolio scenarios using supported DSL composition and book-risk
   configuration;
2. prove per-book isolation and aggregation at the same time;
3. verify backtest/live correspondence for those portfolio cases on retained windows;
4. verify the resulting journals, reports, manifests, and Insights views are coherent at the book
   level and at the account overview level.

### Phase 6: Fix Loop And Full Matrix Rerun

`../notes.txt` requires that all defects found during the exhaustive live audit be fixed in the
same concern stream, with regression coverage added, and then re-proven.

That means:

1. every discovered engine/runtime defect gets a focused source fix;
2. every discovered harness defect gets a focused harness fix;
3. every defect gets focused regression coverage, including adversarial edge cases where warranted;
4. the affected live slice is rerun after the fix;
5. after the final fix in this concern, the entire required short-form matrix is rerun and sealed
   again with checksums.

No slice should be treated as still proven solely by earlier evidence once a relevant runtime path
changes underneath it.

### Phase 7: Sanity, PR, And Promotion Gate

Only after phases 1 through 6 are green should the repo move into quick final sanity and promotion.

The promotion gate remains:

1. exhaustive short-form localhost parity matrix green with retained evidence;
2. full post-final-fix rerun green with retained evidence;
3. quick sanity QKT strategy proof on the final fixed branch;
4. promotion by PR through `dev -> testing -> main`.

That is why `main` is not the current action. The work is still in validation and proof, not in
promotion.

### Phase 8: `qkt-forge` Forward Test And Portfolio Backtests

The forward-test environment comes after runtime proof and promotion, not before it.

Once the runtime is certified and promoted:

1. apply the updated `qkt`, `qkt-insights`, and related runtime changes to `qkt-forge` on
   `sshbot2`;
2. rerun the selected strategies in that forward-test environment and confirm the stack is working
   seamlessly end to end;
3. run portfolio backtests on the updated/proven runtime;
4. after the `sshbot2` forward-test stack is clean, update bot1 `qkt-quantlive` with the proven
   `qkt` and `qkt-insights` changes;
5. keep strategy/book attribution separated in QKT Insights so shared-account performance remains
   measurable by strategy, book, portfolio, and account overview.

## Read-Only Gateway Restart Probe

Fresh passing real localhost MT5 evidence now exists for the gateway reconnect slice:

- runner:
  [run-readonly-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-readonly-gateway-restart.sh)
- focused shell regression:
  [run-readonly-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-readonly-gateway-restart-test.sh)
- aggregate result:
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/result.json`
- startup-window proof:
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/startup-window.json`
- sanitized container inspections:
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/gateway-container-initial.json`
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/evidence/gateway-container-restarted.json`
- retained daemon log:
  `/var/tmp/qkt-validation/reconnect-live-20260811T111608Z/logs/daemon.log`

What that passing live probe proved on Tuesday, August 11, 2026:

- the runner launched only after the broker tick clock entered the bounded safe startup window,
  avoiding the MT5 five-minute rollover warmup false negative;
- the daemon auto-deployed the prepared read-only strategy and reached `running:true` before the
  evidence window opened;
- exact matched `1m` and `5m` stream/evaluation evidence was retained before the gateway restart;
- a real `docker restart lab-mt5-gateway` forced live feed disconnects, MT5 poller failures, and
  a retained `LiveTickFeed source reconnected; resuming` recovery path;
- exact matched `1m` and `5m` stream/evaluation evidence was retained again after reconnect;
- warmup pseudo-tick counts were exactly `80` for `1m` and `80` for `5m` before restart, and
  exactly `0` and `0` after reconnect, proving no strategy reload or warmup replay occurred;
- the full slice stayed financially read-only: zero order events, zero mutating gateway calls,
  zero venue deals, final-flat account state, unchanged balance/equity, zero dropped ticks, and
  bounded queue depth;
- retained container-inspection evidence now redacts the gateway `API_KEY`, so the sealed bundle
  no longer leaks credentials.

What the earlier failed attempts now mean:

- `/var/tmp/qkt-validation/reconnect-live-20260811T103322Z` was a startup-window false negative:
  the daemon launched into an MT5 time-base mismatch during warmup history fetch;
- `/var/tmp/qkt-validation/reconnect-live-20260811T103527Z` exposed an auto-deploy readiness race:
  daemon status answered before the strategy reached `running:true`;
- `/var/tmp/qkt-validation/reconnect-live-20260811T103728Z` exposed the wrong reconnect-warmup
  invariant: the gateway reconnect correctly preserved the running strategy without replaying
  warmup, so post-restart warmup counts had to be `0`, not `80`;
- `/var/tmp/qkt-validation/reconnect-live-20260811T110512Z` reached a passing `result.json` but
  correctly failed the final credential scan because raw `docker inspect` evidence preserved the
  gateway container's `API_KEY=` environment entry.

## Read-Only Already-Deployed Gateway Restart Probe

Fresh passing real localhost MT5 evidence now exists for the control-plane deployed reconnect slice:

- runner:
  [run-readonly-deployed-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-readonly-deployed-gateway-restart.sh)
- focused shell regression:
  [run-readonly-deployed-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-readonly-deployed-gateway-restart-test.sh)
- aggregate result:
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/result.json`
- startup-window proof:
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/startup-window.json`
- deploy response:
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/deploy.json`
- sanitized container inspections:
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/gateway-container-initial.json`
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/evidence/gateway-container-restarted.json`
- retained daemon log:
  `/var/tmp/qkt-validation/deployed-reconnect-live-20260811T113308Z/logs/daemon.log`

What that passing live probe proved on Tuesday, August 11, 2026:

- the daemon started empty with no `--load-dir`, and the deployment set was empty before any
  control-plane action;
- the runner waited for the bounded safe broker startup phase before issuing a real `qkt deploy`
  of the prepared read-only strategy;
- exact matched `1m` and `5m` stream/evaluation evidence was retained after deploy and before the
  gateway restart;
- a real `docker restart lab-mt5-gateway` forced live feed disconnects and retained a successful
  reconnect path without redeploying the strategy;
- exact matched `1m` and `5m` stream/evaluation evidence was retained again after reconnect;
- warmup pseudo-tick counts were exactly `80` for `1m` and `80` for `5m` before restart, and
  exactly `0` and `0` after reconnect, proving the control-plane deployed strategy stayed loaded
  across the gateway outage;
- the full slice stayed financially read-only: zero order events, zero mutating gateway calls,
  zero venue deals, final-flat account state, unchanged balance/equity, zero dropped ticks, and
  zero inbound queue buildup.

## Order-Bearing Gateway Restart Probe

The dedicated real-order reconnect runner now exists, has focused shell coverage, and has already
been exercised against the real localhost MT5 demo gateway:

- runner:
  [run-order-gateway-restart.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-order-gateway-restart.sh)
- focused shell regression:
  [run-order-gateway-restart-test.sh](/home/dickson/Desktop/personal/qkt/tests/scripts/run-order-gateway-restart-test.sh)
- supporting helper hardened this turn:
  [account-identity.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/lib/account-identity.sh)

What the runner requires and proves when it passes:

- a read-only sibling retains exact `1m` and `5m` matched bar/evaluation evidence before and after
  the restart;
- the armed strategy opens exactly one bounded `0.01`-lot real demo position;
- `lab-mt5-gateway` is restarted while that ticket is still open;
- the daemon retains feed disconnect and reconnect proof;
- the same strategy closes the owned ticket after reconnect;
- the retained audit/transport trail proves order intent, acceptance, fill, accounting, exact
  bar/tick evidence, and final flat cleanup;
- retained gateway health and container-inspection evidence no longer persists raw account identity
  after the hardening done this turn.

What the three real attempts established on Tuesday, August 11, 2026:

- `/var/tmp/qkt-validation/order-reconnect-live-20260811T115924Z`
  reached the full live path, but the first close after reconnect got a retryable MT5
  `409` / `10031 CONNECTION` response. That proved the harness was too strict about retryable
  reconnect-close failures.
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T121122Z-retry`
  reached a clean strategy-owned close after reconnect, but the runner then waited forever because
  it incorrectly required `rulesEvaluated == 1` for the armed strategy's dependency `asset5`
  `5m` stream. That proved the post-restart `5m` gate had to accept dependency-stream evidence.
- `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final`
  reached the strongest live state so far:
  - startup-window pass:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/startup-window.json`
  - armed deploy:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/deploy-armed.json`
  - real open position:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/position-open.json`
  - post-restart open proof:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/position-open-post-restart.json`
  - final flat venue state:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/positions-account-final.json`
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/orders-account-final.json`
  - exact two-deal history:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/evidence/history-during-run.json`
  - retained daemon proof:
    `/var/tmp/qkt-validation/order-reconnect-live-20260811T122336Z-final/scenario/logs/daemon.log`

What that final retained attempt proved, even though it is not yet a passing `result.json`:

- real open position ticket `3070525203` survived the gateway restart;
- retained daemon logs prove reconnect at approximately `12:31:51 UTC`;
- the same strategy issued the bounded exit and filled it at approximately `12:32:00 UTC`;
- the owned venue history contains exactly one `IN` deal and one `OUT` deal for the same ticket;
- the final balance delta was `0.04`, which matches the owned deal net `0.04`;
- no extra positions, pending orders, or rejected/duplicate fills were retained in the audited
  lifecycle;
- the run stopped before writing `result.json`, and `cleanup.json` remained at `status:"position_open"`.

Why the latest rerun still does not count as a pass:

- the live trading/accounting invariants passed when re-evaluated from the retained artifacts;
- the harness then failed in the final identity-scrub tail because retained gateway/container
  artifacts still contained account identity:
  - raw `docker inspect` on `lab-mt5-gateway` contains `MT5_LOGIN=` and `MT5_SERVER=`;
  - raw gateway `/health` contains `mt5_account`;
- the final scrub deleted those identity-bearing artifacts and exited before `result.json` and the
  final `cleanup.json` update were written.

What was hardened in source after diagnosing that failure:

- `run-order-gateway-restart.sh` now redacts `MT5_LOGIN=` and `MT5_SERVER=` in retained container
  inspection in addition to `API_KEY=`;
- retained gateway-health snapshots now go through
  `qkt_write_safe_gateway_health_snapshot(...)` instead of storing raw `/health` responses;
- focused shell coverage was updated and re-run:
  - `bash tests/scripts/run-order-gateway-restart-test.sh`
  - `bash tests/scripts/run-readonly-gateway-restart-test.sh`
  - `bash tests/scripts/run-readonly-deployed-gateway-restart-test.sh`

Current status for this slice:

- implemented: yes
- exercised live: yes
- clean retained `result.json`: yes
- replay compared: yes, both retained live captures passed `full-ticks-paper`, `full-ticks-mt5`,
  and `bars-paper`
- next action: commit or PR the qkt-insights attribution fixes after explicit user approval, then
  move to broader same-account isolation, order-family lifecycle, re-entry/risk-gated re-entry, and
  higher-timeframe warmup/bar coverage.

## Current Next Work Queue

This is the source-of-truth order for the next stage as of Wednesday, August 12, 2026.

### Immediate Live-Parity Work

1. Keep the latest live-runner hardening as the current baseline. Focused shell verification has
   passed after the expected-contract, startup-window, and tick-freshness gate fixes:
   - `tests/scripts/run-container-round-trips-test.sh`
   - `tests/scripts/run-shared-account-insights-round-trips-test.sh`
   - `run-market-bracket.sh` now also wraps final `qkt bot history` reconciliation in a
     20-second process timeout per attempt and retains `history-during-run-attempt-N.log`. The MT5
     client already honors profile `http_timeout_ms`; this shell cap protects the live validation
     lane if a CLI/JVM process wedges after the account has already been verified flat.
2. QKT Insights attribution/contract fix is verified locally but not committed in qkt-insights yet:
   - `pnpm build:all && pnpm test` passed with `196` tests across `19` files;
   - local no-cache validation image
     `qkt-insights:validation-live-state-attribution-20260812` passed the collector smoke;
   - qkt-insights source changes still require explicit user approval before commit.
3. Shared-account QKT Insights live round trip is sealed on demo2:
   - two real temporary QKT strategies;
   - real risk config and bracket config;
   - live ticks, M1 bars, M5 bars, warmup evidence, rule decisions, order links, fills, accounting,
     and cleanup;
   - final account flat, zero pending orders;
   - retained QKT Insights state scoped by strategy/book, not account-wide bleed-through;
   - replay comparisons passed for both retained live captures.
4. Higher-timeframe fast JVM parity is now covered for `15m`, `1h`, and `4h` explicit warmup
   counts:
   - [HigherTimeframeWarmupParityTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/dsl/compile/HigherTimeframeWarmupParityTest.kt)
     covers `15m` one hour/day/two days, `1h` one hour/day/two days, and `4h` four
     hours/day/two days;
   - each case asserts the selected `WarmupSpec.Bars`, production `candleToTicks` expansion
     count of `bars * 4`, all warmup ticks before live time, and live-vs-backtest parity;
   - [GeneratedTimeframeParityTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/dsl/compile/GeneratedTimeframeParityTest.kt)
     and the validation capability catalog now include `4h`.
5. If any real bug is found in the remaining matrix, apply the fix to source, add focused
   regression coverage, rebuild, rerun the failed slice, and update this handoff with both the
   failure evidence and the fixed evidence.
6. Add explicit re-entry coverage before promotion:
   - allowed re-entry after a completed position when the indicator/DSL condition becomes true
     again;
   - blocked re-entry while an operator halt, risk halt, daily-loss, drawdown, margin, exposure, or
     circuit-breaker gate is active;
   - recovery behavior after an intentionally lifted halt/gate;
   - retained live and replay evidence proving the exact reason each attempted re-entry was allowed
     or rejected.
7. Add later retained live higher-timeframe evidence without slowing the current fast live loop:
   - live startup evidence for M15, H1, and H4 warmup accuracy;
   - retained warmup/bar artifacts for one-hour, four-hour, one-day, and two-day style ranges where
     applicable;
   - live-vs-backtest parity for those higher-timeframe bars using retained warmup/bar evidence;
   - speed/latency evidence so higher-timeframe validation does not accidentally slow normal QKT
     strategy startup or indicator resolution.

Higher-timeframe fast JVM verification on Wednesday, August 12, 2026:

- `./gradlew test --tests 'com.qkt.dsl.compile.HigherTimeframeWarmupParityTest' --tests 'com.qkt.dsl.compile.GeneratedTimeframeParityTest' -Pkotlin.compiler.execution.strategy=daemon`
  exited green.
- This is not yet a real live MT5 retained H4/M15/H1 startup proof; it closes the deterministic
  fast parity/test gap and leaves the live retained-artifact gate explicit.

### Current In-Progress Evidence From This Continuation

QKT Insights verification and local validation image:

- `qkt-insights` full verification passed after the scoped-deal and `bot.close` contract fixes:
  `pnpm build:all && pnpm test` reported `196` tests passing across `19` files.
- Re-verified in the current dirty qkt-insights worktree on Tuesday, August 11, 2026:
  `PATH=/home/dickson/.local/share/mise/installs/node/22.22.1/bin:$PATH pnpm build:all && PATH=/home/dickson/.local/share/mise/installs/node/22.22.1/bin:$PATH pnpm test`
  again reported `196` tests passing across `19` files.
- Re-verified again on Wednesday, August 12, 2026 in
  `/home/dickson/Desktop/personal/qkt-insights` from branch `fix/live-state-attribution`:
  `PATH=/home/dickson/.local/share/mise/installs/node/22.22.1/bin:$PATH pnpm build:all && PATH=/home/dickson/.local/share/mise/installs/node/22.22.1/bin:$PATH pnpm test`
  reported `196` tests passing across `19` files.
- Rebuilt local validation image:
  `qkt-insights:validation-scoped-deals-20260811`
- Rebuilt a stricter no-cache local-only validation image on Wednesday, August 12, 2026:
  `qkt-insights:validation-live-state-attribution-20260812`
- Current local-only validation image ID:
  `sha256:1e3549c09f0a1d98cccc02a108e280d8ae0bcf4dcd8b53ea95563a3858fa63fc`
- Local tags now pointing at the same image ID `sha256:624b45faf009a96caf30766cc8dd75f329498934fb3978068d87f47be32f1f2e`:
  - `qkt-insights:latest`
  - `qkt-insights:0.0.0`
  - `qkt-insights:sha-59d3215e734c-local`
  - `qkt-insights:validation-scoped-deals-20260811`
- Container-boundary smoke against `qkt-insights:latest` passed:
  `/var/tmp/qkt-insights-smoke-20260811T212318Z`
- Smoke proof:
  - `/healthz` returned `{"ok":true,"mode":"run"}`;
  - `/ingest` accepted `strategy.started`, `decision.rule_evaluated`, `decision.order_linked`,
    `fill.accounted`, `bot.close`, and `broker.deal`;
  - SQLite retained one local deal for `local_live`;
  - foreign and unattributed shared-account deal backfill was dropped.
- Container-boundary smoke against
  `qkt-insights:validation-live-state-attribution-20260812` passed on Wednesday,
  August 12, 2026:
  `/var/tmp/qkt-insights-image-smoke-phAoqg`
- Smoke proof for the August 12 image:
  - `/healthz` returned `ok=true` in `run` mode;
  - `/ingest` accepted `decision.rule_evaluated`, `decision.order_linked`, `order.submit`,
    `order.filled`, `bot.close`, and two `state.positions` snapshots;
  - SQLite folded order `o1` to `FILLED`;
  - SQLite preserved strategy `s1` ownership for ticket `t1` after a sibling null-attribution
    position poll;
  - `ingest_observations` recorded zero `gap` or `regression` rows for the producer-local
    sequence pattern.

QKT proving runner state:

- Proving worktree:
  `/var/tmp/qkt-insights-rerun-20260811T202300Z`
- Current proving commits added during this continuation:
  - `5ae86d1c fix(scripts): widen live entry drift gate`
  - `6afba917 fix(scripts): retain expected contracts for drift checks`
  - `fcb39c18 fix(scripts): gate live runs on tick freshness`
  - `41b89070 fix(scripts): wait for live startup window`
- Focused shell tests passed in both main and proving worktrees:
  - `tests/scripts/run-container-round-trips-test.sh`
  - `tests/scripts/run-shared-account-insights-round-trips-test.sh`
- Re-verified in the main qkt worktree on Tuesday, August 11, 2026:
  `bash tests/scripts/run-container-round-trips-test.sh && bash tests/scripts/run-shared-account-insights-round-trips-test.sh`
  exited green; the shared-account wrapper printed `run-shared-account-insights-round-trips-test:
  passed`.
- Matching host CLI and local validation image were rebuilt from `41b89070`:
  - host CLI: `qkt 0.47.1 (41b89070)`
  - image: `qkt:live-validation-41b89070`
  - floating local alias: `qkt:live-validation-current`
  - image ID: `sha256:2c4c5378ef9e2f72a1d44446ac0b32b66e107867ea0c79efdb84b1b32638ff9a`
  - the local image was built from the already-built `installDist`; the cold Dockerfile build was
    interrupted before broker mutation because it was too slow for this iteration.

Live shared-account attempts after the image/smoke fix:

- Demo2 gateway `http://127.0.0.1:5002`, output
  `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-live`:
  - passed on Wednesday, August 12, 2026 with local QKT image
    `qkt:live-validation-41b89070` and local Insights image
    `qkt-insights:validation-live-state-attribution-20260812`;
  - QKT commit/image version under proof:
    `41b8907001c9e00d8cf9dcb2308a174236e114be`;
  - Insights image under proof:
    `sha256:1e3549c09f0a1d98cccc02a108e280d8ae0bcf4dcd8b53ea95563a3858fa63fc`;
  - base round-trip result:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-live/base-roundtrip/evidence/result.json`;
  - wrapper result:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-live/evidence/result.json`;
  - two unrestricted QKT containers ran concurrently against the same demo2 hedging account;
  - account login `476434211`, server `Exness-MT5Trial9`, expected starting balance
    `99999.52`, leverage `1000`;
  - EURUSD strategy `sidem2eur0812025553_market_bracket`, magic `938201`, opened real
    strategy-owned ticket `3073826254`, side `SELL`, then closed strategy-owned;
  - GBPUSD strategy `sidem2gbp0812025553_market_bracket`, magic `938202`, opened real
    strategy-owned ticket `3073826242`, side `BUY`, then closed strategy-owned;
  - both cases retained exact `1m` and `5m` stream-candle/evaluation evidence;
  - both cases retained indicator entry and indicator exit traces;
  - each case retained `2` rule decisions, `2` decision/order links, `2` accepted orders,
    `2` filled orders, `2` accounted fills, and `0` rejected orders;
  - each case retained exactly `1` order POST, `1` protection POST, `1` close POST, and
    `3` total mutating gateway requests;
  - aggregate final account state was flat with `0` positions and `0` pending orders;
  - aggregate balance delta was `-0.15`, matching owned deal net `-0.15`;
  - Insights retained both daemon instances, exactly two filled lifecycle rows per strategy,
    exactly one `IN` and one `OUT` deal per strategy, final flat `positions_current`, no
    cross-owner causal leakage, and zero `gap`/`regression` observations;
  - replay comparison passed for the EURUSD live capture:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-eurusd-replay/result.json`;
  - replay comparison passed for the GBPUSD live capture:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-0812025553-gbpusd-replay/result.json`;
  - both replay comparisons ran exactly `full-ticks-paper`, `full-ticks-mt5`, and
    `bars-paper`;
  - both replay comparisons proved exact candle/evaluation counts, two approved orders and
    fills, zero rejections, final-flat replay state, byte-identical full-tick paper/MT5 order
    journals, timestamp-normalized bars-paper order parity, exact indicator entry, exact
    indicator exit quantity/close, exact live canonical entry intent, and live-vs-MT5 fill,
    protection, and PnL parity after numeric normalization.
- This seals the current two-symbol shared-account QKT Insights live order-bearing slice. It
  does not close same-symbol shared-account serialization, every order family, higher-TF
  warmup coverage, broader generated strategy matrices, or the longer demo burn-in.

- Primary gateway `http://127.0.0.1:5001`, output
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T214632Z`:
  - both real strategies reached live order lifecycle and then flattened;
  - EURUSD SELL opened ticket `3073131942` and closed strategy-owned;
  - GBPUSD BUY opened ticket `3073131941` and closed strategy-owned;
  - final account was flat with zero pending orders;
  - runner failed before `result.json` because the new drift check referenced an unset
    `expecteds[$index]` array;
  - source fix and shell regression were added after this failure.
- Primary gateway `http://127.0.0.1:5001`, output
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T215238Z`:
  - failed before broker mutation during EURUSD warmup history fetch;
  - cause: MT5 time-base mismatch at an unsafe startup phase;
  - final account remained flat with zero pending orders.
- Primary gateway `http://127.0.0.1:5001`, output
  `/var/tmp/qkt-validation/shared-account-insights-live-20260811T215641Z-safe`:
  - launched inside the safe startup phase;
  - both strategies warmed and evaluated;
  - both entries were blocked by QKT's market-data safety gate because no fresh tick had arrived
    inside the 10-second minimum stale threshold at the first entry bar close;
  - final account remained flat with zero pending orders;
  - Insights retained the `risk.rejected` and `marketdata.stale` events.
- Primary gateway tick freshness sample:
  `/var/tmp/qkt-validation/tick-freshness-20260811T215945Z.jsonl`
  - over `75` samples, `EURUSDm` exceeded the 10-second stale threshold `12` times with max age
    `43554ms`;
  - `GBPUSDm` exceeded the threshold `3` times with max age `13029ms`.
- Demo2 gateway `http://127.0.0.1:5002`, output
  `/var/tmp/qkt-validation/shared-account-insights-demo2-live-20260811T220639Z-safe`:
  - EURUSD strategy completed a real bounded open/close on demo2;
  - ticket `3073163459` opened BUY and closed strategy-owned;
  - GBPUSD strategy was blocked by the same market-data stale safety gate;
  - final account was flat with zero pending orders;
  - demo2 balance moved from `99999.60` to `99999.52`, matching the retained EURUSD loss;
  - Insights retained the EURUSD order/fill/trade/deal path and kept the deal scoped to
    `sdta20260811220246_market_bracket`.
- Active tick-window scan across both gateways:
  `/var/tmp/qkt-validation/tick-window-scan-20260811T221008Z`
  - scanned `8` primary windows and `8` demo2 windows;
  - no 25-second window kept both `EURUSDm` and `GBPUSDm` under the strict 8-second freshness gate;
  - repeated observed max ages ranged into roughly 15-43 seconds depending on symbol/lane;
  - conclusion: at the time of this scan, current MT5 tick conditions were too sparse to honestly
    retry the clean two-symbol order-bearing pass without expecting QKT's stale-data safety gate to
    suppress at least one entry.
- Demo2 guarded retry with the `41b89070` runner:
  `/var/tmp/qkt-validation/shared-account-insights-demo2-live-20260811T222929Z-guarded`
  - startup-window guard passed before deployment:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-live-20260811T222929Z-guarded/base-roundtrip/evidence/startup-window.json`
  - tick-freshness gate failed before arming/deploying:
    `/var/tmp/qkt-validation/shared-account-insights-demo2-live-20260811T222929Z-guarded/base-roundtrip/evidence/tick-freshness-gate-summary.json`
  - the failure was pre-mutation by design: `EURUSDm` and `GBPUSDm` both had stale samples beyond
    the strict 8-second validation gate during the readiness window;
  - demo2 remained flat with zero pending orders and balance/equity `99999.52`.
- Fresh gateway flatness evidence after the guarded attempts:
  `/var/tmp/qkt-validation/gateway-health-20260811T223647Z/summary.json`
  - primary `436804390`: balance/equity `99993.81`, margin `0`, positions `0`, orders `0`;
  - demo2 `476434211`: balance/equity `99999.52`, margin `0`, positions `0`, orders `0`.
- Fresh no-mutation tick-freshness samples:
  - `/var/tmp/qkt-validation/tick-freshness-quick-20260811T223710Z/summary.json`
    showed all four lane/symbol pairs crossing the strict 8-second gate during the sample window;
  - `/var/tmp/qkt-validation/tick-freshness-quick-20260811T224146Z/summary.json`
    again failed the strict 8-second gate, with primary `EURUSDm` stale for all `12` samples and
    demo2 `EURUSDm` peaking at `41079ms`;
  - because both samples were pre-run external reads only, no broker mutation occurred.
- Fresh no-mutation active-symbol scout after the invalid-tick runner hardening:
  `/var/tmp/qkt-validation/tick-freshness-symbol-scout-20260811T232815Z/summary.json`
  - primary and demo2 `EURUSDm` remained sparse, with only `2` distinct tick timestamps across
    `30` samples and `25/30` samples over the strict 8-second gate;
  - primary and demo2 `GBPUSDm` were better but still crossed the strict 8-second gate once during
    the `30`-second sample;
  - `USDJPYm` remained intermittently sparse;
  - `XAUUSDm` was active on both gateways with zero samples over the strict 8-second gate and
    `29-30` distinct tick timestamps across `30` samples.
- Bounded no-mutation tick-window watch:
  `/var/tmp/qkt-validation/tick-window-watch-20260811T224408Z/summary.json`
  - watched both gateways for `720` one-second samples per symbol;
  - no lane produced a clean rolling 25-sample window where both `EURUSDm` and `GBPUSDm` stayed
    under the strict 8-second pre-arm gate;
  - primary `EURUSDm`: max age `45362ms`, `354/720` samples over 8 seconds;
  - primary `GBPUSDm`: max age `57065ms`, `246/720` samples over 8 seconds;
  - demo2 `EURUSDm`: max age `45362ms`, `351/720` samples over 8 seconds;
  - demo2 `GBPUSDm`: max age `55549ms`, `242/720` samples over 8 seconds;
  - no live scenario was armed from this evidence.
- Controlled demo2 gateway restart diagnostic:
  `/var/tmp/qkt-validation/gateway-restart-diagnostic-20260811T230156Z/summary.json`
  - demo2 was checked flat before restart, restarted, then checked flat again;
  - account remained unchanged at balance/equity `99999.52`, margin `0`, positions `0`, orders `0`;
  - restart did not fix EURUSD freshness: `23/40` post-restart EURUSD samples were over 8 seconds,
    with max age `25623ms`;
  - first GBPUSD sample after restart returned `time_msc=0`, `bid=0`, `ask=0`, then normal ticks
    resumed. Treat zero timestamp/zero price startup ticks as invalid gateway readiness data in
    diagnostics and harness gates.
- Fresh read-only sample from the current continuation:
  `/var/tmp/qkt-validation/tick-freshness-now-20260811T225723Z`
  - primary `EURUSDm`: `20` samples, min age `870ms`, max age `23354ms`, average age `11689ms`,
    `13` samples over the strict 8-second gate and `11` over QKT's 10-second minimum stale gate;
  - primary `GBPUSDm`: `20` samples, min age `-187ms`, max age `22297ms`, average age `10762ms`,
    `12` samples over 8 seconds and `10` over 10 seconds;
  - demo2 `EURUSDm`: `20` samples, min age `149ms`, max age `27134ms`, average age `6495ms`,
    `4` samples over 8 seconds and `3` over 10 seconds;
  - demo2 `GBPUSDm`: `20` samples, min age `49ms`, max age `27399ms`, average age `8081ms`,
    `4` samples over 8 seconds and `4` over 10 seconds.
- Fresh read-only tick-history cross-check from the current continuation:
  `/var/tmp/qkt-validation/stale-gap-audit-20260811T225825Z/summary.json`
  - sampled `/symbol_info_tick` once per second for `30` observations on both local live gateways,
    then checked `/copy_ticks_range` over the surrounding three-minute window;
  - primary `EURUSDm`: only `3` distinct latest-tick timestamps across `30` samples, max age
    `37482ms`, `18/30` samples over QKT's `10s` minimum stale gate;
  - primary `GBPUSDm`: only `2` distinct latest-tick timestamps across `30` samples, max age
    `48421ms`, `28/30` samples over QKT's `10s` minimum stale gate;
  - demo2 `EURUSDm`: `4` distinct latest-tick timestamps across `30` samples, max age `27469ms`,
    `15/30` samples over QKT's `10s` minimum stale gate;
  - demo2 `GBPUSDm`: `6` distinct latest-tick timestamps across `30` samples, max age `11063ms`,
    `1/30` sample over QKT's `10s` minimum stale gate;
  - `/copy_ticks_range` showed the same sparse quote cadence rather than hidden fresher ticks:
    `15` EURUSDm ticks and `19-20` GBPUSDm ticks in the sampled three-minute windows.
- Controlled primary-gateway restart diagnostic:
  `/var/tmp/qkt-validation/stale-gap-restart-primary-20260811T230209Z/summary.json`
  - verified primary account flat before restart: balance/equity `99993.81`, margin `0`, positions
    `0`, orders `0`;
  - restarted only `lab-mt5-gateway`, waited for health, then sampled `/symbol_info_tick` for
    another `30` one-second observations;
  - primary `EURUSDm` remained sparse after restart: `3` distinct latest-tick timestamps, max age
    `25145ms`, `13/30` samples over QKT's `10s` minimum stale gate;
  - primary `GBPUSDm` improved but still showed gaps: `9` distinct latest-tick timestamps, max age
    `18088ms`, `4/30` samples over QKT's `10s` minimum stale gate;
  - account remained flat after restart: balance/equity `99993.81`, margin `0`, positions `0`,
    orders `0`.
- Post-restart both-gateway flatness check:
  `/var/tmp/qkt-validation/post-restart-flat-check-20260811T230635Z/summary.json`
  - primary: balance/equity `99993.81`, margin `0`, positions `0`, orders `0`;
  - demo2: balance/equity `99999.52`, margin `0`, positions `0`, orders `0`.

Current interpretation:

- The qkt-insights contract/scoped-deal issue is fixed locally and verified.
- The `expecteds[$index]` runner bug was a real harness defect and is fixed in source.
- The startup-window and tick-freshness gates are now part of the live-runner baseline so sparse
  gateway conditions fail before real orders when possible.
- The shared-account and generated single-strategy live runners now explicitly classify gateway
  samples with `time_msc=0`, missing/zero timestamp, or zero bid/ask as invalid evidence and retry
  bounded startup probes instead of treating those startup artifacts as usable broker clocks.
- The active-symbol XAUUSD single-strategy path is now cleanly live/replay sealed. Use that pattern
  for active-symbol expansion, but do not treat it as a substitute for the still-open EURUSD/GBPUSD
  shared-account Insights pass.
- The remaining blocker for a clean two-strategy shared-account pass is live feed sparsity around
  the first entry bar close. QKT is correctly suppressing new exposure when the tick stream is stale;
  the next retry should wait for a demonstrably active tick window or add explicit harness semantics
  for retained stale-gate rejections before eventual clean order completion. Do not weaken the
  production market-data safety gate just to force a pass.
- Current stale-gap diagnosis:
  - a stale gap is the difference between the wall-clock observation time and the broker timestamp on
    the newest gateway tick for the venue symbol;
  - the QKT live runtime uses `MarketDataGate` to suppress NEW exposure after stale/outlier/clock-skew
    data, while still allowing risk-reducing orders;
  - current samples show gateway/broker feed sparsity, not a proven QKT math, DSL, or timestamp
    parsing bug;
  - the gateway implementation currently exposes MetaTrader5's `symbol_info_tick` directly after
    symbol validation/selection; current `/copy_ticks_range` evidence does not show newer hidden MT5
    ticks that the endpoint is failing to expose;
  - a controlled primary-gateway restart did not remove the stale gaps, so the current evidence does
    not support treating this as a wedged gateway process;
  - restarting a gateway is still a valid recovery step when the process is unhealthy, stuck, or
    returning invalid startup ticks, but the current EURUSD/GBPUSD evidence says restart alone does
    not fix broker/feed sparsity and must not be used to bypass QKT's stale-data gate;
  - answer to the restart question: if staleness is caused by a gateway/terminal session wedge,
    restarting that one flat account's gateway can fix it and is a useful diagnostic. If the
    broker's newest `symbol_info_tick` is genuinely old or the market/feed is sparse, restart will
    not create fresher ticks. The retained primary-gateway restart evidence supports the second
    case for the observed EURUSD/GBPUSD stale gaps;
  - a QKT fix is appropriate only in the harness/diagnostic/scenario-selection layer unless later
    evidence proves the gateway has fresher MT5 ticks that it is failing to expose.
- No downstream `qkt-forge`, bot1, strategy-promotion, or image-publish rollout should start from
  this state. Those actions belong after the live/replay parity evidence is sealed, fixes are
  applied and rerun, and the quick final sanity strategy proof passes.

### Re-Entry And Gate-Recovery Coverage

The next live/backtest parity expansion must explicitly cover re-entry, not only first entry and
close. Keep the semantics split:

- allowed re-entry: a strategy opens, closes, the indicator/DSL condition becomes false and then true
  again, and QKT opens a second strategy-owned position with a new causal chain;
- duplicate-entry prevention: while the strategy is already positioned or has an open pending order,
  same-condition repeats must not create unintended duplicate exposure unless the strategy is
  explicitly testing scale-in/resize semantics;
- blocked re-entry: after the first close or failed entry, a second qualifying signal must be blocked
  under each active gate with exact retained reasons:
  - operator halt;
  - strategy/global risk halt;
  - daily-loss and daily-drawdown;
  - total drawdown;
  - loss-streak/circuit/runaway breaker;
  - margin floor;
  - gross/net exposure limits;
  - stale-market-data health gate;
- recovery: where the gate is intentionally recoverable, prove the re-entry remains blocked before the
  lift/reset and is allowed only after the lift/reset or next valid UTC day boundary. Operator halt
  recovery is now proven, and UTC next-day reset is now proven locally; the remaining recovery gaps
  are retained live/state-time-bound gates.

Existing partial coverage:

- JVM parity already covers a second entry attempt after a loss/risk event in
  `src/test/kotlin/com/qkt/parity/GeneratedRiskLifecycleParityTest.kt`;
- JVM parity coverage now proves the allowed re-entry path plus blocked re-entry under max trades,
  cooldown after loss, loss streak halt, strategy daily loss, strategy drawdown, strategy daily
  drawdown, global daily loss, global drawdown, and global daily drawdown across tick, bars,
  tick-resolved bars, and live-paper in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves `MaxTradesPerDay` UTC reset behavior for re-entry: a same-day
  qualifying re-entry is rejected, a later qualifying signal after the UTC day boundary is allowed,
  and the second position closes flat across tick, bar, tick-resolved-bar, and live-paper modes in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves `CooldownAfterLoss` elapsed-duration recovery for re-entry: a
  same-day qualifying re-entry is rejected while cooldown remains active, a later qualifying signal
  after the configured duration is allowed, and the recovered position closes flat across tick, bar,
  tick-resolved-bar, and live-paper modes in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves loss-streak reset behavior for re-entry: a loss increments the
  streak, a later winning lifecycle resets it, a subsequent loss does not falsely trip
  `LossStreakHalt(maxLosses=2)`, and the following re-entry opens and closes flat across tick, bar,
  tick-resolved-bar, and live-paper modes in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves DAILY-scoped strategy daily-loss recovery for generated DSL
  strategies: a first open/close loss trips `MaxStrategyDailyLoss`, a same-day qualifying BUY is
  rejected with `halted: strategy daily loss`, and a qualifying signal after the UTC day boundary
  opens and closes a second position across tick, bar, tick-resolved-bar, and live-paper modes in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves DAILY-scoped strategy daily-drawdown recovery for generated DSL
  strategies: an open intraday equity drawdown trips `MaxStrategyDailyDrawdown`, a same-day
  qualifying BUY is rejected with `halted: strategy daily drawdown`, and a qualifying signal after
  the UTC day boundary opens and closes a second position across tick, bar, tick-resolved-bar, and
  live-paper modes in `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- JVM parity coverage now proves global DAILY loss and daily-drawdown recovery for generated DSL
  strategies: `MaxDailyLoss` and `MaxDailyDrawdown` each trip an account/global halt, same-day new
  exposure is rejected while risk-reducing exits remain allowed, and a qualifying signal after the
  UTC day boundary opens and closes a second position across tick, bar, tick-resolved-bar, and
  live-paper modes in `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- focused market-data gate coverage now proves the stale-data re-entry contract at the same
  pre-trade rule used by live sessions: the first entry is allowed on fresh data, a later same-side
  re-entry is rejected while the symbol is stale, protective exits remain allowed, and a fresh tick
  reopens the gate. See
  `src/test/kotlin/com/qkt/marketdata/MarketDataGateTest.kt`;
- JVM parity coverage now proves generated-DSL duplicate-entry prevention for pending re-entry
  guards: repeated qualifying bars with `POSITION.x = 0` do not create duplicate pending limit
  entries while `OPEN_ORDERS.x != 0`, the first pending entry fills and closes, and the same guarded
  strategy later re-enters exactly once across tick, bar, tick-resolved-bar, and live-paper modes in
  `src/test/kotlin/com/qkt/parity/GeneratedReentryParityTest.kt`;
- focused book-exposure coverage now proves the exposure-limit re-entry contract at the same
  pre-trade rule used by live sessions: an entry at the cap boundary is allowed, a later same-side
  re-entry is rejected while sibling book exposure consumes headroom, close-by-ticket remains
  allowed, and a fresh book-risk sample with cleared exposure reopens the gate. See
  `src/test/kotlin/com/qkt/risk/rules/BookExposureLimitTest.kt`;
- portfolio deployer E2E coverage now proves the same-book controller recovery path through real
  deployed child `LiveSession`s: after drawdown scaling, a follow-up order is rejected by book gross
  exposure, a risk-reducing sell remains allowed, a fresh book-risk sample clears exposure, and a
  later recovered entry is accepted. The same test now asserts the durable order journal contains a
  `risk-rejected` record with the `book gross exposure` reason, which gives explicit cause
  attribution for the blocked order. See
  `src/test/kotlin/com/qkt/cli/daemon/portfolio/PortfolioDeployerE2ETest.kt`;
- focused margin-floor coverage now proves the margin-floor re-entry contract at the same pre-trade
  rule used by live sessions: fresh margin headroom allows entry, collapsed margin blocks same-side
  re-entry, risk-reducing exits remain allowed, and restored margin headroom reopens the gate. See
  `src/test/kotlin/com/qkt/risk/rules/MarginFloorTest.kt`;
- `prepare-scenario.sh` can now emit a static generated `--lifecycle reentry` scenario. The focused
  shell regression proves the generated armed strategy uses `TRADES.today < 2`, keeps the 0.01-lot
  cap, widens only the intended risk counters to two entries, and parses through the QKT CLI;
- `run-market-bracket.sh` now branches on `.armedScenario.lifecycle`. For `single`, it keeps the
  existing operator-flattened one-entry proof. For `reentry`, it waits for two strategy-owned
  open/flat cycles, retains `owned-tickets.jsonl`, requires the retained history to show one `IN`
  and one `OUT` deal for each ticket, and requires audit/transport counts for two entries and two
  closes;
- `prepare-scenario.sh`, `run-market-bracket.sh`, and `compare-golden-replay.sh` now support a
  `reentry_cooldown_recovered` lifecycle. The generated strategy leaves trade count open with
  `TRADES.today < 3`, configures per-strategy `cooldown_after_loss: "90000"`, requires one
  pre-transport `CooldownAfterLoss` rejection after the first live close, waits for the cooldown
  window to elapse, then requires a second strategy-owned open/flat cycle. Static shell regression,
  retained armed live evidence, and replay comparison are now sealed for this lifecycle;
- clean live evidence now proves the allowed XAUUSD re-entry path end-to-end through the real local
  MT5 gateway, real broker fills, golden capture, full-tick replay, plain-bar replay, and MT5-sim
  replay. See `Active-Symbol XAUUSD Re-Entry Live/Replay Extension` above;
- clean live evidence now proves one blocked XAUUSD re-entry path end-to-end through the real local
  MT5 gateway: the second qualifying signal was rejected pre-transport by `MaxTradesPerDay`, no
  second gateway order was posted, the account finished flat, and replay retained exact order-journal
  parity. See `Active-Symbol XAUUSD Blocked Re-Entry Live/Replay Extension` above;
- clean live evidence now proves one operator-halt blocked XAUUSD re-entry path end-to-end through
  the real local MT5 gateway: the second qualifying signal was rejected pre-transport by
  `halted: operator`, no second gateway order was posted, the account finished flat, and replay
  retained exact order-journal parity while explicitly documenting that unhalted replay keeps the
  extra entry that the live operator halt suppressed. See
  `Active-Symbol XAUUSD Operator-Halt Re-Entry Live/Replay Extension` above;
- clean live evidence now proves one operator-halt recovery XAUUSD re-entry path end-to-end through
  the real local MT5 gateway: the second qualifying signal was rejected pre-transport while halted,
  `qkt resume` lifted the gate, the next qualifying signal opened and closed a second real position,
  the account finished flat, and replay retained exact order-journal parity while explicitly
  documenting that halt/resume are external control-plane events. See
  `Active-Symbol XAUUSD Operator-Halt Recovery Live/Replay Extension` above;
- static and stateful live rejection runners already prove several pre-transport and restored-state
  risk blocks, but they are rejection-only and do not prove an order-bearing re-entry lifecycle;
- margin-floor retained-live recovery is sealed by the controlled two-role fixture above: opener
  creates bounded live exposure, probe rejects pre-transport by `MarginFloor`, opener flatten
  restores headroom, the same running probe opens and flattens after recovery, and the account ends
  flat with zero pending orders;

Concrete next work:

- extend the existing risk rejection/stateful/margin runners into re-entry-specific blocked and
  recovered retained-live variants, especially live stale-market-data gate recovery, live same-book
  exposure limits, retained live loss-streak reset after a winning lifecycle, and retained live
  next-day reset behavior. The real order-bearing loss-streak halt block after a losing trade is now
  sealed by `/var/tmp/qkt-validation/xau-loss-streak-reentry-20260812T044701Z/evidence/result.json`;
- decide whether production needs a broker-fill-oracle replay mode. Current replay proves exact order
  decisions and live protection adjustment, but deterministic backtest fill prices can drift from
  real broker fills because live execution latency is real;
- add blocked/recovered variants by extending the existing risk rejection/stateful/margin runners
  instead of weakening the base market-data gate.

### Higher-Timeframe Warmup Coverage

Higher timeframes are later than the current fast order-bearing loop, but they are now an explicit
promotion blocker before production rollout.

Existing partial coverage:

- `1m`/`5m` live read-only and order-bearing harnesses already retain warmup ticks, stream candles, and
  strategy-candle evaluations;
- generated JVM parity includes `1h` indicator strategies;
- tick-resolved backtest parity includes a `15m` EMA-cross/bracket strategy;
- same-symbol multi-timeframe warmup parity exists for `1m`/`5m`.
- generated timeframe parity now includes `4h` in
  `src/test/kotlin/com/qkt/dsl/compile/GeneratedTimeframeParityTest.kt` and
  `src/test/resources/validation/capability-catalog.json`;
- new deterministic warmup parity coverage now proves explicit `15m`, `1h`, and `4h` warmup specs
  for one-hour, one-day, and two-day-equivalent windows where applicable in
  `src/test/kotlin/com/qkt/dsl/compile/HigherTimeframeWarmupParityTest.kt`.

Gaps to close before claiming full coverage:

- generalize the read-only live validation scripts away from hard-coded `1m`/`5m` assumptions so they
  can retain exact M15/H1/H4 warmup counts and bar/evaluation joins;
- do not wait for a natural 4-hour close in the fast loop. Use `bot bars`, aligned closed-bar checks,
  exact warmup pseudo-tick counts, and offline replay; reserve long natural-close checks for a later
  soak/forward-test lane.

### Gateway Parallelism

Use two local MT5 demo gateways only after both are authenticated and health-checked locally. The
second gateway must be verified from `../personal/demo2.txt` or the running container config before
use. Do not rely on the old two-agent live lock protocol; that was removed from the operating plan.

The intended use is one agent orchestrating two independent gateway lanes:

- lane A runs one armed live scenario or scenario pair;
- lane B runs a different armed live scenario or scenario pair;
- each lane must use unique strategy IDs, comments, magic numbers, output directories, and Insights
  instance IDs;
- both lanes must still finish with flat account state and complete retained evidence.

### Exhaustive Matrix Still To Run

The broader stage is not complete until the following are all covered live and replayed:

- indicator families: EMA, RSI, ATR, crossover, session/time, volume, composite math, and every DSL
  mapping used by generated strategies;
- timeframes: tick stream plus at least M1 and M5 bars, with mixed-timeframe dependency evidence;
- strategy shapes: single-indicator, combined-indicator, multi-symbol, multi-timeframe, stateful,
  timed exit, rapid order, cancel/reject, stop-loss, take-profit, trailing/adjusted protection, OCO,
  OTO, scale-out, reconnect while flat, reconnect while in position, and read-only deployed mode;
- accounting: fills, commissions, swaps when available from venue history, realized PnL, open/close
  attribution, position ownership, final flatness, and no cross-strategy leakage;
- QKT Insights: every emitted event type used by live runtime, strategy/book scoping, causal links
  from condition to order to fill to accounting, outage replay, and retained query/API evidence;
- backtest/live parity: same captured data range, same strategy config, same DSL/indicator path, and
  documented expected venue differences such as spread and fill model differences.
- re-entry and risk-gated re-entry: prove both allowed re-entry and intentionally blocked re-entry
  under halts, daily loss, drawdown, margin/exposure, stale-data, and circuit-breaker conditions,
  with exact causal evidence explaining why the order path did or did not continue.
- higher timeframes after the fast loop is stable: M15, H1, and H4 warmup/bar parity, including
  one-hour, four-hour, one-day, and two-day warmup windows where useful, with explicit startup-speed
  and indicator-resolution evidence.

### Promotion Gate

Strategy promotion, `testing`, `main`, and `qkt-forge` forward-test deployment are later gates. They
should not start until runtime parity, Insights attribution, and the live/replay matrix above are
cleanly sealed. After that, run a quick sanity QKT strategy proof, PR/promote/merge through the repo
flow, apply the updated stack to `qkt-forge` on `sshbot2`, rerun the strategies there, and then run
portfolio backtests on the proven stack. After that environment is clean, update bot1
`qkt-quantlive` with the proven `qkt` and `qkt-insights` changes.

## Operational Cautions

- Do not use any remote tunnel gateway for live validation. The spec requires localhost-only MT5.
- Do not cap the JVM heap for validation runs. The user explicitly rejected that and the spec forbids
  introducing artificial heap restrictions.
- Do not describe the current branch as production-ready. The 30-day burn-in and full repeated
  matrix are still missing.
- Do not collapse “focused live certification passed” into “all DSL/indicator/order/accounting
  paths are proven.” That broader claim is not supported yet.
- Do not promote external strategy books before the runtime proof program is complete.

## Useful Commands

```bash
git status --short --branch
git log --oneline origin/dev..HEAD
./gradlew build -Pkotlin.compiler.execution.strategy=daemon
./gradlew test -Pkotlin.compiler.execution.strategy=daemon
rg -n 'TODO|FIXME|XXX' src/ || true
```

Live evidence files already on disk:

```bash
jq . /var/tmp/qkt-validation/readonly-catalog-live-6913752b-20260810T224349Z/evidence/result.json
jq . /var/tmp/qkt-validation/insights-4d37ebb4-final/evidence/result.json
```

## Bottom Line

The branch contains real progress and real passing live evidence. It does not yet satisfy the
stated end condition for `main`. Another agent should treat this as a partially completed but
well-instrumented validation program: preserve the existing evidence, finish the remaining matrix,
run the mandatory burn-in, then handle strategy-book adaptation and promotion.

## 2026-08-12 Update: Next-Day Max-Trades Retained-State Harness

Added a new generated live lifecycle, `reentry_max_trades_next_day_recovered`, to prove the retained
max-trades pacer reset path without waiting for UTC midnight during the live loop.

What changed:

- `scripts/live-validation/prepare-scenario.sh` can now emit
  `--lifecycle reentry_max_trades_next_day_recovered`.
- The lifecycle seeds
  `state/state/<scenario>_market_bracket/risk-state.json` with one previous-UTC-day pacer entry fill
  for the armed strategy.
- The generated strategy still attempts two entries with `TRADES.today < 2`, while risk config keeps
  `max_trades_per_day: 1`.
- Expected live behavior is: first current-day real MT5 entry is allowed, the strategy-owned close
  returns the account flat, and the second same-day entry is rejected pre-transport by
  `MaxTradesPerDay`.
- `scripts/live-validation/run-market-bracket.sh` now verifies the seeded risk-state file before
  daemon start. It fails if the seed is missing, tied to the wrong strategy, outside the previous UTC
  day, or not declared in `expected.json`.
- Live results now retain `seededRiskState` metadata, and the runner copies the exact seed into
  `evidence/seeded-risk-state.json` plus a verification summary.
- `scripts/live-validation/compare-golden-replay.sh` now accepts this lifecycle as a replayable
  blocked re-entry variant and documents the live/replay limitation.
- `tests/scripts/prepare-live-validation-scenario-test.sh` covers generation, parser acceptance,
  seed JSON shape, checksum inclusion, and runner `--verify-only`.

Verification already run:

```bash
bash -n scripts/live-validation/prepare-scenario.sh
bash -n scripts/live-validation/run-market-bracket.sh
bash -n scripts/live-validation/compare-golden-replay.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
```

Still not sealed:

- The new lifecycle has not yet been armed against MT5 in this branch state.
- Next step is a clean build, fresh scenario preparation on one localhost demo gateway, armed
  `run-market-bracket.sh`, and `compare-golden-replay.sh` on the retained golden capture.
- If the live run passes, add the evidence paths here and commit a docs sealing note.
