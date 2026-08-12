# Live Parity And Promotion Handoff

## Correction - Current Status As Of 2026-08-12 17:47 UTC

The qkt shared-market-source and mt5-gateway timeout work is not fully done, not merged to
`main`, and not running on bot2 as promoted production images.

### Update - 2026-08-12 18:52 UTC

The qkt local fix/validation gate for warmup duplicate-request coalescing and shared MT5 polling
is now clean in this checkout. This does not mean promoted/deployed completion; it means the local
qkt branch is ready for PR update/review after commit and push.

- Kimi/kimi-co processes: none running.
- Final qkt local verification completed with no JVM heap caps, `--no-daemon`, or worker caps:
  - `./gradlew build -Pkotlin.compiler.execution.strategy=daemon` passed
    (`BUILD SUCCESSFUL in 11m 17s`).
  - `./gradlew test -Pkotlin.compiler.execution.strategy=daemon` passed/up-to-date
    (`BUILD SUCCESSFUL in 22s`).
  - `git diff --check` passed.
  - `grep -rEn 'TODO|FIXME|XXX' src/ || true` returned no matches.
- The first full build after the `Clock` injection failed only on ktlint import/signature ordering
  in the new cache wrapper/test. Those style issues were fixed and the full build above passed.
- Warmup duplicate-request coalescing is covered at three levels:
  - focused unit tests for repeated cached requests, concurrent in-flight joining, and sibling
    prefix remapping through the cache;
  - same-symbol local live MT5 run showing two upstream canonical bar loads and two sibling cache
    hits for 1m/5m warmups;
  - full build parity/generation tests, including generated indicators through ticks and bars and
    multi-timeframe warmup full-state parity.
- Shared live polling remains covered by the existing shared-market-source tests plus the local
  same-symbol live run, which showed one `mt5-tick-feed-*` thread serving both sibling prefixes.
- The cache wrapper now uses injected `Clock`/`SystemClock` instead of direct wall-clock reads, so
  `WallClockSourcePolicyTest` passes and deterministic source policy remains intact.

- qkt checkout/PR:
  - Local branch: `fix/mt5-shared-market-source`.
  - Remote PR: qkt PR 981 is still open against `dev`.
  - `origin/main` does not contain `f7e84b27` (`feat(app): make market data gate thresholds
    configurable`) or the later local warmup/config hardening below.
- mt5-gateway:
  - Remote PR 78 is still open against `dev`.
  - The gateway fix has not been promoted to `main`.
- bot2 runtime:
  - qkt is running the validation image `ghcr.io/elitekaycy/qkt:fix-shared-md`, not the final
    promoted `latest` image for this branch.
  - mt5-gateway is running the validation image `elitekaycy/mt5-gateway-api:fix-ipc`, not a
    promoted release image.
  - qkt-insights is running `ghcr.io/elitekaycy/qkt-insights:latest` at revision
    `b27d8e54f831a2c945ad854dba2883eee67982ba`.
  - `qkt status` showed `forward_bench`, `forward_bench_2`, and `forward_bench_3` running with
    25 total child strategies, all `gateActive:true`, `operatorStop:false`, `hold:false`, and
    `trades:0`.
  - Gateway logs still showed repeated `waitress.queue` depth messages during the quick check,
    so long-run stability is not sealed.
- Additional local qkt changes made after PR 981 review:
  - `MarketDataGateConfig` now rejects malformed explicit `market_data` values and unsafe
    thresholds instead of silently defaulting malformed values.
  - `MarketSourceFactory` now wraps grouped MT5 shared market sources in
    `CachedHistoricalMarketSource`, a short-lived historical bar cache/in-flight coalescer.
  - Warmup/history bar requests from sibling MT5 namespaces are remapped to the canonical prefix
    first, then coalesced by canonical symbol/window/range, and restamped back to the caller prefix.
  - New focused tests cover malformed config values, unsafe config values, cached identical bar
    requests, concurrent in-flight joining, and remapped sibling warmup sharing.
- Local verification completed in this checkout:
  - `git diff --check` passed.
  - `./gradlew compileKotlin compileJava testClasses -Pkotlin.compiler.execution.strategy=daemon`
    passed.
  - Focused tests passed:
    `./gradlew test --tests com.qkt.marketdata.source.CachedHistoricalMarketSourceTest --tests com.qkt.marketdata.source.PrefixRemapMarketSourceTest --tests com.qkt.cli.MarketSourceFactoryTest --tests com.qkt.cli.ConfigMarketDataTest --tests com.qkt.marketdata.MarketDataGateTest --tests com.qkt.marketdata.MarketDataGateClockSkewTest -Pkotlin.compiler.execution.strategy=daemon`.
  - The first compile pass was slow in Kotlin/JVM codegen but completed; no JVM heap caps,
    `--no-daemon`, or worker restrictions were used.
- Local MT5 live verification completed against `lab-mt5-gateway` on `127.0.0.1:5001`:
  - Account used: Exness demo login `436804390`, server `Exness-MT5Trial9`; account was flat
    before each run and flat after each run. The gateway API key was read from the container env
    and was not written to artifacts.
  - Multi-prefix, multi-symbol order proof:
    `/var/tmp/qkt-validation/shared-prefix-live-20260812T175026Z`.
    One daemon loaded two MT5 broker profiles (`exness_s0`, `exness_s1`) pointing at the same
    gateway/account with different magics (`26000`, `26001`). `EXNESS_S0:EURUSD` EMA and
    `EXNESS_S1:GBPUSD` RSI strategies both warmed 1m/5m bars, evaluated indicators/DSL,
    submitted real 0.01-lot bracket entries, received broker accepts/fills, submitted strategy
    closes, received close fills, retained engine audit + MT5 transport journals, and ended with
    `positions=[]`, `orders.total=0`. Final status was `qkt: HEALTHY`; replay materialization and
    full-ticks-paper, bars-paper, and full-ticks-mt5 backtests all produced two trade events with
    zero rejections for both captured sessions.
  - Same-symbol duplicate-warmup proof:
    `/var/tmp/qkt-validation/shared-prefix-samesymbol-live-20260812T175843Z`.
    One daemon loaded `EXNESS_S0:EURUSD` and `EXNESS_S1:EURUSD` sibling strategies against the same
    account with magics `26010` and `26011`. Daemon logs showed exactly two upstream historical bar
    loads for canonical `EXNESS_S0:EURUSD` (1m and 5m) and two cache hits for the remapped sibling
    `EXNESS_S1:EURUSD` warmups. Logs showed a single `mt5-tick-feed-1911158859` thread serving both
    prefixes. Both strategies placed and closed real 0.01-lot EURUSD orders and ended flat with no
    pending orders.
  - Golden capture succeeded for the local live strategy sessions and retained warmup ticks, live
    ticks, candles, stream-candle evaluations, linked placements, order/fill/accounting events, and
    MT5 transport mutations.
- Still required before claiming full completion:
  - Commit and push the local qkt fix branch, then verify/update the qkt PR against `dev`.
  - Review/promote mt5-gateway PR 78.
  - PR/merge qkt through `dev -> testing -> main`, update bot2 images/runtime, and monitor logs.

## Current Forward Stack Status - 2026-08-12

This section supersedes older branch/promotion status below for the current bot2 forward-test stage
and the bot1 qkt-quant-live cleanup/update.

- Coordination snapshot:
  - Local qkt checkout is currently on `fix/mt5-shared-market-source`, tracking `origin/dev`.
  - The local worktree contains active timeout/shared-market-source changes plus this handoff file. Treat the stale lower `Current Branch State` section as historical only; it still describes the old `test/exhaustive-live-parity` branch and should not be used as the current checkout/runtime state.
  - No open qkt PR exists for `fix/mt5-shared-market-source` at this snapshot. The only open qkt PR found was PR 973 (`feature/research-docs-and-sample-bars`), unrelated to the timeout pause.
  - Do not make qkt source edits in this checkout until the MT5 timeout investigation branch/worktree owner is clear, to avoid colliding with the other agent's fix attempt.
- Bot1 qkt-quant-live cleanup/update:
  - Stack path: `/opt/qkt-quant-live`.
  - Action taken: stopped the running qkt service, archived the old promoted-book strategies, pulled current configured images, and recreated qkt plus qkt-insights.
  - Old running strategy set before cleanup: `promoted_book` plus 14 child strategies (`audnzd_rv_*`, `eurgbp_rv`, `gold_*`, `nzdaud_rv_*`, `nzdeur_rv`, `nzdxag_rv`), all showing `0` trades after almost 7 days.
  - Broker safety check before cleanup: IC Markets demo login `52969381`, server `ICMarketsSC-Demo`, balance/equity `60,178.43 USD`, margin `0`, margin free `60,178.43`; `/orders` returned `ok=true`, `total=0`.
  - The direct `qkt stop promoted_book` attempt timed out through the older control client, so cleanup used `docker compose stop qkt` and then removed strategy files from the daemon load directory.
  - Strategy archive paths:
    - full copy: `/root/qkt-cleanup-backups/qkt-quant-live-strategies-20260812T142412Z/strategies`;
    - non-loading archive outside `/strategies`: `/opt/qkt-quant-live/archived-strategies/archive-20260812T142412Z`.
  - Current `/opt/qkt-quant-live/strategies` load directory contains no `.qkt` files, only `.gitkeep` and `README.md`.
  - Current bot1 qkt image: `ghcr.io/elitekaycy/qkt:edge`, revision `be4958c662373c5f3cfea6eabc5a4164922460a4`, image id `sha256:786c3c01b10b189ca4194eac7d75db5d5c5318d5a96cb18f24e5b9397193edef`.
  - Current bot1 qkt version: `qkt 0.47.1 (be4958c662373c5f3cfea6eabc5a4164922460a4)`, built `2026-08-12T12:09:11.241422148Z`.
  - Current bot1 qkt-insights image: `ghcr.io/elitekaycy/qkt-insights:latest`, revision `b27d8e54f831a2c945ad854dba2883eee67982ba`, image id `sha256:edb358273ce82074923e86f2267f588fb031ff88ec3a13c86fdc1ee1bfb2d3c0`.
  - Current bot1 mt5-gateway image remains the configured `elitekaycy/mt5-gateway-api:0.3.7`, revision `794c04c661439c400d463e22cbc31e4f72e78bc1`.
  - Current bot1 services after update: qkt healthy, mt5-gateway healthy, qkt-insights healthy.
  - Current qkt status after update: daemon running, control reachable, `STRATEGIES none deployed`.
  - Current broker check after update: same account, margin `0`, `/orders` total `0`.
  - Current qkt-insights `/live/state` after update with no strategies deployed: `accounts=0`, `positions=0`, `orders=0`.
  - Important tag note: bot1 remains on its configured qkt tag `edge`; bot2 forward stack uses `qkt:latest` at revision `443a42fe55d27f0d0a55f620280f8c90df191ba7`.
- Pause update: qkt strategies were paused at the user's request while another agent investigates/fixes the MT5 timeout issue.
  - Action taken: `ssh bot2 'cd /root/forward-stack && docker compose stop qkt'`.
  - Result: `forward-stack-qkt-1` is stopped.
  - `forward-stack-mt5-gateway-1` remains up/healthy for timeout diagnostics.
  - `forward-stack-qkt-insights-1` remains up/healthy.
  - Account check after pause: login `476422618`, server `Exness-MT5Trial9`, balance/equity `5,000,000 USD`, margin `0`, margin free `5,000,000`.
  - Pending order check after pause: `/orders` returned `ok=true`, `total=0`.
  - Gateway logs after pause repeatedly reported `Retrieved 0 pending orders`.
  - Do not restart qkt/strategies until the timeout work is ready for a controlled rerun.
- qkt was promoted through `dev -> testing -> main`.
- qkt live image on bot2 is `ghcr.io/elitekaycy/qkt:latest`.
- qkt image revision on bot2: `443a42fe55d27f0d0a55f620280f8c90df191ba7`.
- qkt version on bot2: `qkt 0.47.1 (443a42fe55d27f0d0a55f620280f8c90df191ba7)`.
- qkt-insights live image on bot2 is `ghcr.io/elitekaycy/qkt-insights:latest`.
- qkt-insights image revision on bot2: `b27d8e54f831a2c945ad854dba2883eee67982ba`.
- qkt-insights image id on bot2: `sha256:74edde146ab47a152954346afc55da21535995555e68cbdc8ff54a4bb413d48f`.
- qkt-insights includes:
  - preserved live broker attribution;
  - producer-local event sequence handling;
  - qkt causal audit payload acceptance;
  - local-strategy scoped broker-deal backfills;
  - shared-account dedupe by `login + server`;
  - logical portfolio grouping for generated shards such as `forward_bench_2` and `forward_bench_3`;
  - visible `/live/state.positions` and `/live/state.orders` collapse of qkt sibling broker profiles such as `EXNESS_S10` through `EXNESS_S24`.
- qkt-insights dashboard follow-up deployed:
  - Symptom: Insights showed one account row after the account dedupe fix, but still exposed many broker-state buckets for one MT5 account.
  - Verified live `/live/state` before the follow-up fix: `accounts=1`, but `positions=15` and `orders=15`, all keyed by physical qkt broker profiles such as `EXNESS_S10` through `EXNESS_S24`.
  - Root cause: account snapshots were collapsed by `login + server`, but positions/orders were still returned by physical broker profile. Empty full-replace polls from every child therefore made the UI look like multiple broker/account overviews.
  - Dev PR: qkt-insights PR 34, merged to `dev` as `586b85dd42faf0720b32486e624d0d30de0e3c60`.
  - Main PR: qkt-insights PR 36, merged to `main` as `b27d8e54f831a2c945ad854dba2883eee67982ba`.
  - Fix commit on main branch: `1a76add fix(store): collapse live broker state groups`.
  - Fix behavior: keep physical profile state internally, collapse visible `/live/state.positions` and `/live/state.orders` by display broker, merge by ticket, preserve known strategy attribution, and make the web cache refetch grouped position/order state instead of reintroducing physical profile buckets from WebSocket pushes.
  - Local verification: `pnpm build:all` passed; `pnpm test` passed with `201` tests; focused live-state/portfolio regression run passed with `17` tests.
  - CI/publish verification: main workflow `31604381485` completed successfully for `b27d8e54f831a2c945ad854dba2883eee67982ba`; `test`, `docker-smoke`, and `docker` jobs all passed.
  - Bot2 deployment command used: `docker compose pull qkt-insights && docker compose up -d --no-deps qkt-insights`.
  - Bot2 qkt-insights health after deploy: healthy.
  - `/live/state` after deploy while qkt is paused: `accounts=0`, `positions=0`, `orders=0`. This proves no visible stale broker-profile shards remain after the Insights restart, but full live-collapse proof must be repeated after qkt resumes and emits broker state again.
- Runtime account in use: Exness demo login `476422618`, server `Exness-MT5Trial9`, balance/equity `5,000,000 USD`.
- User decision: use one MT5 account/gateway for this forward stage; ignore the second demo account as a run path.
- The temporary `/root/forward-stack-demo2` stack is stopped. Its failure was traced to setup/config: the generated `.env` put the wrong credential-file line into `MT5_SERVER`, so MT5 initialized against an invalid server value. This was not a qkt engine or qkt-insights bug.
- Current bot2 services:
  - `forward-stack-mt5-gateway-1`: healthy;
  - `forward-stack-qkt-1`: stopped/paused;
  - `forward-stack-qkt-insights-1`: healthy.
- Exness namespace audit:
  - Labels such as `EXNESS_S14` and `EXNESS_S22` are qkt broker profile aliases, not Exness accounts.
  - `/root/forward-stack/qkt.config.yaml` defines `exness_s0` through `exness_s24`; each points to `http://mt5-gateway:5001` and uses `${QKT_EXPECTED_ACCOUNT_LOGIN}` / `${QKT_EXPECTED_ACCOUNT_SERVER}`.
  - The active stack resolves those expected values to login `476422618`, server `Exness-MT5Trial9`.
  - The suffix number maps to a distinct magic namespace: `EXNESS_S14 -> magic 20014`, `EXNESS_S22 -> magic 20022`. The intent is per-child order/deal attribution and recovery on one shared MT5 account.
  - Example strategy source:
    - `portfolio_children/14_xauusd_xag_ratio_accel_1h_rollover_catchup_v1.qkt` declares `EXNESS_S14:XAUUSD` and `EXNESS_S14:XAGUSD`;
    - `portfolio_children/22_fx3seed-fx3_AUDUSD_6.qkt` declares `EXNESS_S22:AUDUSD` and `EXNESS_S22:NZDUSD`.
  - Current code already has `SharedLiveMarketSource` and `PrefixRemapMarketSource`, but `MarketSourceFactory` still creates one `SharedLiveMarketSource(Mt5MarketSource(profile))` per MT5 profile. Therefore sharing works within one prefix, but not across sibling prefixes such as `EXNESS_S14:XAUUSD` and `EXNESS_S22:XAUUSD`.
  - Practical impact: the namespace is relevant for execution attribution, but without cross-profile market-source remapping it multiplies live tick and warmup/history requests against the same gateway account. This is a likely contributor to the observed gateway queue/timeouts.
  - Existing broker read sharing is separate: `MT5Client` uses `MT5ReadCache` for snapshot reads, but that does not by itself collapse the market-data pollers created per profile.
  - Required source follow-up for the timeout branch: group MT5 profiles by market-data identity (`gateway_url`, api key identity, symbol policy, tick poll interval, calendars, server time zone), create one canonical `SharedLiveMarketSource(Mt5MarketSource(canonical))`, and route sibling prefixes through `PrefixRemapMarketSource`.
- Last registered qkt deployments before the pause:
  - `forward_bench_2`: running;
  - `forward_bench_3`: running;
  - 15 child strategies total (`EXNESS_S10` through `EXNESS_S24` profiles), zero trades so far.
- Current verified qkt-insights `/live/state` shape after the latest image:
  - qkt is paused, so the restarted in-memory live state is empty: `accounts=0`, `positions=0`, `orders=0`.
  - When qkt resumes, repeat the `/live/state` check. Expected shape with the fix: one logical account row for `forward-bench`, one visible `EXNESS` positions group, and one visible `EXNESS` orders group instead of one bucket per qkt broker profile.
- Remaining blocker before claiming full forward-stack completeness:
  - `forward_bench` first shard (`EXNESS_S0` through `EXNESS_S9`, 10 children) is not running.
  - Deploy attempts partially warmed children but the single MT5 gateway developed queue buildup and qkt control-client timeouts.
  - Gateway stayed connected and account reads succeeded, but logs showed `waitress.queue` depth buildup and intermittent qkt `MT5Client` timeouts.
  - Do not claim all 25 strategies are healthy until `qkt status --deep` shows `forward_bench`, `forward_bench_2`, and `forward_bench_3` all registered/running with all 25 child strategies.

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

This branch is ready for PR/promotion review after the final closeout commit. It is not ready for
`main`, `qkt-forge`, or bot1 rollout until the PR to `dev` is merged and the normal
`dev -> testing -> main` promotion path completes. The current work is closing the live parity
testing branch, not downstream deployment.

## Current Branch State

- Repo: `/home/dickson/Desktop/personal/qkt`
- Current branch: `test/exhaustive-live-parity`
- Base branch: `origin/dev`
- Merge-base with `origin/dev`: `b4c99599b0e6cd94a70d9cb654a15f6732602121`
- Current status at handoff update: tracked worktree clean, branch `ahead 182`; two pre-existing
  untracked Kimi/audit docs remain outside this handoff.
- Promotion PR: https://github.com/elitekaycy/qkt/pull/979
- Latest committed work:
  - `docs(docs): record parity pr status`
  - `test(parity): quiet reentry parity logs`
  - `docs(docs): record closeout gate status`
  - `docs(docs): record strict case sanity proof`
  - `fix(scripts): bound golden replay execution drift`
  - `docs(docs): record strict atr sanity proof`
  - `docs(docs): record strict rsi sanity proof`
  - `docs(docs): record strict ema sanity proof`
  - `fix(scripts): require strategy-owned single close`
  - `test(scripts): align generated wave assertions`
  - `fix(scripts): bound live replay execution drift`
  - `fix(app): retain closed ticket attribution`
  - `fix(scripts): widen gold drift envelope`
  - `fix(app): poll routed broker state for insights`
  - `fix(scripts): use symbol drift bounds`
  - `fix(scripts): support reviewed roundtrip symbols`
  - `docs(docs): seal global daily halt reset live evidence`
  - `feat(scripts): add global daily halt reset lifecycle`
  - `docs(docs): seal daily halt reset live evidence`
  - `feat(scripts): add daily halt reset lifecycle`
  - `docs(docs): seal max-trades reset live evidence`
  - `feat(scripts): add max-trades reset lifecycle`
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

Historical failing condition after the local harness fixes:

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
- this means the local harness was strong enough to expose the remaining defect. This evidence is
  superseded by the later demo2 proof below, which used the newer local QKT image and
  `qkt-insights:validation-live-state-attribution-20260812`.

QKT-side hardening added on Wednesday, August 12, 2026:

- [BrokerStatePoller.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/observe/insights/BrokerStatePoller.kt)
  now preserves account-level deal backfill when no deployed strategy ids are known, but strategy-scoped
  daemons only emit `broker.deal` envelopes whose resolved owner is one of that daemon's deployed ids;
- this prevents shared-account sibling daemons from sending null-owned or foreign deal backfill into
  their own Insights instances before the collector sees the data;
- focused regression:
  [BrokerStatePollerTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/observe/insights/BrokerStatePollerTest.kt)
  includes `deployed strategy poller emits only locally attributed broker deals`;
- verification:
  `./gradlew test --tests com.qkt.observe.insights.BrokerStatePollerTest -Pkotlin.compiler.execution.strategy=daemon`
  and
  `./gradlew test --tests com.qkt.observe.insights.TicketAttributionTest -Pkotlin.compiler.execution.strategy=daemon`
  both passed at `2026-08-12T06:48:53Z`;
- the wrapper contract test
  `bash tests/scripts/run-shared-account-insights-round-trips-test.sh`
  also passed after the QKT emission hardening.

Practical consequence for the next person:

- treat the shared-account wrapper itself as locally hardened enough to continue with;
- treat shared-account deal attribution as a two-sided contract: QKT must only emit locally attributed
  strategy-scoped deal rows, and QKT Insights must continue to fold/drop shared-account state without
  account-wide bleed-through;
- after this QKT hardening, the next proof step is rebuilding the QKT validation image from the current
  commit and rerunning the shared-account Insights wrapper against
  `qkt-insights:validation-live-state-attribution-20260812`;
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
3. QKT shared-account deal emission has been hardened locally:
   - strategy-scoped daemons no longer emit null-owned or sibling-owned `broker.deal` backfill when
     `deployedIds` is non-empty;
   - account-level pollers with no deployed ids retain the old emit-all behavior;
   - focused tests passed:
     `./gradlew test --tests com.qkt.observe.insights.BrokerStatePollerTest -Pkotlin.compiler.execution.strategy=daemon`
     and
     `./gradlew test --tests com.qkt.observe.insights.TicketAttributionTest -Pkotlin.compiler.execution.strategy=daemon`;
   - wrapper contract test passed:
     `bash tests/scripts/run-shared-account-insights-round-trips-test.sh`.
4. Shared-account QKT Insights live round trip is sealed on demo2 for the previous validation image
   pair and must be rerun after the current QKT emission hardening is rebuilt:
   - two real temporary QKT strategies;
   - real risk config and bracket config;
   - live ticks, M1 bars, M5 bars, warmup evidence, rule decisions, order links, fills, accounting,
     and cleanup;
   - final account flat, zero pending orders;
   - retained QKT Insights state scoped by strategy/book, not account-wide bleed-through;
   - replay comparisons passed for both retained live captures.
5. Higher-timeframe fast JVM parity is now covered for `15m`, `1h`, and `4h` explicit warmup
   counts:
   - [HigherTimeframeWarmupParityTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/dsl/compile/HigherTimeframeWarmupParityTest.kt)
     covers `15m` one hour/day/two days, `1h` one hour/day/two days, and `4h` four
     hours/day/two days;
   - each case asserts the selected `WarmupSpec.Bars`, production `candleToTicks` expansion
     count of `bars * 4`, all warmup ticks before live time, and live-vs-backtest parity;
   - [GeneratedTimeframeParityTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/dsl/compile/GeneratedTimeframeParityTest.kt)
     and the validation capability catalog now include `4h`.
6. If any real bug is found in the remaining matrix, apply the fix to source, add focused
   regression coverage, rebuild, rerun the failed slice, and update this handoff with both the
   failure evidence and the fixed evidence.
7. Add explicit re-entry coverage before promotion:
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

Retained live/replay evidence:

- Live scenario:
  `/var/tmp/qkt-validation/xau-nextday-maxtrades-reentry-20260812T051759Z/evidence/result.json`
- Replay comparison:
  `/var/tmp/qkt-validation/xau-nextday-maxtrades-reentry-20260812T051759Z-replay/result.json`

Live result summary:

- `status:"passed"`, `qktDirty:false`, `magic:938514`, demo2 account `476434211`.
- Seeded state:
  `state/state/xau_nextday_maxtrades_market_bracket/risk-state.json`, kind
  `previous-day-max-trades`, entry fill `1786492740000`.
- Lifecycle: one real XAUUSD strategy-owned entry and one strategy-owned close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"2.81"` and `dealNet:"2.81"`.
- Golden capture: `ticks:188`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `9b0597f670b056234bebaf455defef9e273ae2591e70405e5faaed1da3a520ff`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.

Replay result summary:

- `status:"passed"`, lifecycle `reentry_max_trades_next_day_recovered`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live fill and adjusted protection matched MT5 simulation exactly for the entry:
  live SELL fill `4395.484`, simulated fill `4395.48400000`, delta `0`.

## 2026-08-12 Update: Daily Halt Next-Day Retained-State Harness

Added and sealed a second retained-state live lifecycle,
`reentry_daily_halt_next_day_recovered`, to prove a previous-day DAILY risk halt is not restored as
a current-day live blocker.

What changed:

- `scripts/live-validation/prepare-scenario.sh` can now emit
  `--lifecycle reentry_daily_halt_next_day_recovered`.
- The lifecycle seeds
  `state/state/<scenario>_market_bracket/risk-state.json` with:
  - `halted:true`;
  - `haltReason:"DailyLoss"`;
  - `haltScope:"DAILY"`;
  - prior UTC `haltEpochDay`;
  - a matching prior-day strategy halt for the armed strategy.
- The generated strategy still attempts two entries with `TRADES.today < 2`, while risk config keeps
  `max_trades_per_day: 1`.
- Expected live behavior is: the previous-day DAILY halt is ignored on restore, the first current-day
  real MT5 entry is allowed, the strategy-owned close returns the account flat, and the second
  same-day entry is rejected pre-transport by `MaxTradesPerDay`.
- `scripts/live-validation/run-market-bracket.sh` now verifies both supported seeded-state contracts:
  `previous-day-max-trades` and `previous-day-daily-halt`.
- `scripts/live-validation/compare-golden-replay.sh` accepts the new lifecycle as a replayable
  blocked re-entry variant.
- `tests/scripts/prepare-live-validation-scenario-test.sh` covers generation, parser acceptance,
  seed JSON shape, checksum inclusion, and runner `--verify-only`.

Verification already run:

```bash
bash -n scripts/live-validation/prepare-scenario.sh
bash -n scripts/live-validation/run-market-bracket.sh
bash -n scripts/live-validation/compare-golden-replay.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon
```

Retained live/replay evidence:

- Live scenario:
  `/var/tmp/qkt-validation/xau-daily-halt-reset-reentry-20260812T053231Z/evidence/result.json`
- Replay comparison:
  `/var/tmp/qkt-validation/xau-daily-halt-reset-reentry-20260812T053231Z-replay/result.json`

Live result summary:

- `status:"passed"`, `qktDirty:false`, `magic:938515`, demo2 account `476434211`.
- Seed verification:
  `/var/tmp/qkt-validation/xau-daily-halt-reset-reentry-20260812T053231Z/evidence/seeded-risk-state-verification.json`.
- Seeded state:
  `state/state/xau_daily_halt_reset_market_bracket/risk-state.json`, kind
  `previous-day-daily-halt`, epoch day `20676`, with `halted:true`,
  `haltReason:"DailyLoss"`, `haltScope:"DAILY"`, and matching strategy halt.
- Lifecycle: one real XAUUSD strategy-owned entry and one strategy-owned close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"0.18"` and `dealNet:"0.18"`.
- Golden capture: `ticks:239`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `5b61d4dce1f6bab99940d856bf9051a8b9e1bf4ec34f2a7f439569774828e3d6`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.

Replay result summary:

- `status:"passed"`, lifecycle `reentry_daily_halt_next_day_recovered`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live-vs-MT5-sim entry fill drift is retained as expected venue latency/model difference:
  live SELL fill `4393.303`, simulated fill `4393.58000000`, delta
  `-0.27700000000004366`.

## 2026-08-12 Update: Global Daily Halt Next-Day Retained-State Harness

Added and sealed `reentry_global_daily_halt_next_day_recovered` to prove a previous-day global
DAILY risk halt is not restored as a current-day live blocker.

What changed:

- `scripts/live-validation/prepare-scenario.sh` can now emit
  `--lifecycle reentry_global_daily_halt_next_day_recovered`.
- The lifecycle seeds
  `state/state/<scenario>_market_bracket/risk-state.json` with:
  - `halted:true`;
  - `haltReason:"DailyLoss"`;
  - `haltScope:"DAILY"`;
  - prior UTC `haltEpochDay`;
  - an empty `strategyHalts` list, proving this is the global halt path rather than a
    strategy-scoped halt.
- The generated strategy still attempts two entries with `TRADES.today < 2`, while risk config keeps
  `max_trades_per_day: 1`.
- Expected live behavior is: the previous-day global DAILY halt is ignored on restore, the first
  current-day real MT5 entry is allowed, the strategy-owned close returns the account flat, and the
  second same-day entry is rejected pre-transport by `MaxTradesPerDay`.
- `scripts/live-validation/run-market-bracket.sh` verifies the new `previous-day-global-daily-halt`
  seed before daemon start.
- `scripts/live-validation/compare-golden-replay.sh` accepts the new lifecycle as a replayable
  blocked re-entry variant.
- `tests/scripts/prepare-live-validation-scenario-test.sh` covers generation, parser acceptance,
  seed JSON shape, checksum inclusion, and runner `--verify-only`.

Verification already run:

```bash
bash -n scripts/live-validation/prepare-scenario.sh
bash -n scripts/live-validation/run-market-bracket.sh
bash -n scripts/live-validation/compare-golden-replay.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon
```

Retained live/replay evidence:

- Live scenario:
  `/var/tmp/qkt-validation/xau-global-daily-halt-reset-reentry-20260812T054710Z/evidence/result.json`
- Replay comparison:
  `/var/tmp/qkt-validation/xau-global-daily-halt-reset-reentry-20260812T054710Z-replay/result.json`

Live result summary:

- `status:"passed"`, `qktDirty:false`, `magic:938516`, demo2 account `476434211`.
- Seed verification:
  `/var/tmp/qkt-validation/xau-global-daily-halt-reset-reentry-20260812T054710Z/evidence/seeded-risk-state-verification.json`.
- Seeded state:
  `state/state/xau_global_halt_reset_market_bracket/risk-state.json`, kind
  `previous-day-global-daily-halt`, epoch day `20676`, with `halted:true`,
  `haltReason:"DailyLoss"`, `haltScope:"DAILY"`, and no strategy halt entries.
- Lifecycle: one real XAUUSD strategy-owned entry and one strategy-owned close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"0.36"` and `dealNet:"0.36"`.
- Golden capture: `ticks:246`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `afbb802802b0638805e036d64db290dd076d79b3676739070b12dbae6e12b3e8`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.

Replay result summary:

- `status:"passed"`, lifecycle `reentry_global_daily_halt_next_day_recovered`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live-vs-MT5-sim entry fill drift is retained as expected venue latency/model difference:
  live SELL fill `4385.776`, simulated fill `4385.85500000`, delta
  `-0.07899999999972351`.

## 2026-08-12 Update: Higher-Timeframe Live Warmup Bot-Bars Probe

Added and sealed a financially read-only M15/H1/H4 live warmup probe so higher-timeframe bar
availability can be checked quickly without waiting for natural 4h daemon closes.

What changed:

- Added `scripts/live-validation/prepare-higher-timeframe-warmup.sh`.
- Added `scripts/live-validation/run-higher-timeframe-warmup.sh`.
- Added `tests/scripts/prepare-higher-timeframe-warmup-test.sh`.
- The runner uses `qkt bot bars` against a localhost MT5 gateway and refuses remote gateway URLs.
- Credentials stay execution-time only through `QKT_BROKER_API_KEY`; retained artifacts are scanned to
  prove the broker key was not persisted.
- The runner verifies the demo account allowlist before and after the probe, and rejects any open
  positions, pending orders, or venue history deals during the run.
- The reviewed warmup matrix is fixed in `expected.json` and in runner validation:
  - `15m`: 4 bars for one hour, 96 bars for one day, 192 bars for two days.
  - `1h`: 1 bar for one hour, 24 bars for one day, 48 bars for two days.
  - `4h`: 1 bar for four hours, 6 bars for one day, 12 bars for two days.
- Each retained bar set is validated for exact count, closed-bar status, timeframe alignment, positive
  OHLC, sorted unique timestamps, and pseudo warmup tick count of `bars * 4`.
- A harness bug found during live proof was fixed in `f742a852`: `qktDirty:false` is now treated as
  data instead of a failing `jq -e` status.

Verification already run:

```bash
bash tests/scripts/prepare-higher-timeframe-warmup-test.sh
./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon
```

Additional generated/backtest/live-paper parity verification:

```bash
./gradlew test --tests com.qkt.dsl.compile.HigherTimeframeWarmupParityTest -Pkotlin.compiler.execution.strategy=daemon
```

The targeted parity test passed all nine retained warmup cases from
`src/test/kotlin/com/qkt/dsl/compile/HigherTimeframeWarmupParityTest.kt`:

- `warmup_15m_one_hour`, `warmup_15m_one_day`, `warmup_15m_two_days`.
- `warmup_1h_one_hour`, `warmup_1h_one_day`, `warmup_1h_two_days`.
- `warmup_4h_four_hours`, `warmup_4h_one_day`, `warmup_4h_two_days`.

For each case the test compiles an explicit DSL stream warmup, verifies the compiled
`PerStreamWarmable` count/window, verifies `bars * 4` generated warmup ticks, then compares
backtest and live-paper snapshots after the same warmup stream is seeded.

Retained live evidence:

- Scenario:
  `/var/tmp/qkt-validation/htf-warmup-xau-20260812T060527Z`
- Result:
  `/var/tmp/qkt-validation/htf-warmup-xau-20260812T060527Z/evidence/result.json`
- Checksums:
  `/var/tmp/qkt-validation/htf-warmup-xau-20260812T060527Z/SHA256SUMS`
  and
  `/var/tmp/qkt-validation/htf-warmup-xau-20260812T060527Z/RUN-SHA256SUMS`

Live result summary:

- `status:"passed"`, `qktDirty:false`, QKT commit
  `f742a852d88feec6195ac91cde75d7fa6bc57236`.
- QKT CLI: `qkt 0.47.1 (f742a852) built 2026-08-12T06:05:15.292363337Z`.
- Gateway: demo2 localhost gateway, version `0.3.4`, account `476434211`,
  server `Exness-MT5Trial9`.
- Symbol: `EXNESS:XAUUSD`.
- Coverage:
  `timeframes:["15m","1h","4h"]`,
  `closedBars:true`,
  `alignedBars:true`,
  `uniqueBars:true`,
  `accountUnchanged:true`,
  `venueDealsDuringRun:0`.
- Final account stayed flat: balance/equity `99999.0`, margin `0.0`.
- Final QKT account ownership: `finalPositions:0`, `finalOrders:0`.
- Venue history during run: `[]`.
- Retained probes:
  - `15m` one-hour: 4 bars, 16 pseudo warmup ticks.
  - `15m` one-day: 96 bars, 384 pseudo warmup ticks.
  - `15m` two-days: 192 bars, 768 pseudo warmup ticks.
  - `1h` one-hour: 1 bar, 4 pseudo warmup ticks.
  - `1h` one-day: 24 bars, 96 pseudo warmup ticks.
  - `1h` two-days: 48 bars, 192 pseudo warmup ticks.
  - `4h` four-hours: 1 bar, 4 pseudo warmup ticks.
  - `4h` one-day: 6 bars, 24 pseudo warmup ticks.
  - `4h` two-days: 12 bars, 48 pseudo warmup ticks.

Scope and remaining work:

- This seals fast live gateway closed-bar availability/alignment for M15/H1/H4 warmup ranges.
- Generated/backtest/live-paper parity for the same M15/H1/H4 warmup matrix is covered by
  `HigherTimeframeWarmupParityTest`.
- Remaining higher-timeframe promotion blockers are: at least one daemon strategy using these
  higher-TF bars, full order-path proof for that strategy, and retained live-vs-backtest comparison
  for the daemon strategy path.

## 2026-08-12 Update: Higher-Timeframe Daemon Order-Path Proof

Added and sealed a real demo daemon scenario proving that a higher-timeframe warmed stream can
participate in the live entry decision and still replay against the captured golden bundle.

What changed:

- `scripts/live-validation/prepare-scenario.sh` now accepts
  `--secondary-timeframe 5m|15m|1h|4h` for the armed market-bracket strategy.
- The armed strategy keeps `asset1` on `1m` for fast evaluation/exit but can put `asset5` on the
  selected secondary timeframe. This lets live validation prove higher-timeframe indicator use
  without waiting for a natural H1/H4 close.
- The generated entry log and score now retain `secondary_fast`/`secondary_slow` values rather than
  assuming every secondary stream is `5m`.
- `expected.json` now records `primaryTimeframe`, `secondaryTimeframe`, and the exact armed stream
  matrix.
- `scripts/live-validation/run-market-bracket.sh` now reads armed history-readiness timeframes from
  `expected.json` instead of hard-coding `1m`/`5m`, and writes `armedTimeframes` to
  `evidence/result.json`.
- `tests/scripts/prepare-live-validation-scenario-test.sh` covers the default `5m` path, a `15m`
  higher-timeframe path, and smoke-verified `1h`/`4h` generation and runner verification.

Verification already run:

```bash
bash -n scripts/live-validation/prepare-scenario.sh
bash -n scripts/live-validation/run-market-bracket.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon
```

Additional smoke verification:

```bash
scripts/live-validation/prepare-scenario.sh ... --secondary-timeframe 1h
build/install/qkt/bin/qkt parse <generated-strategy>
scripts/live-validation/run-market-bracket.sh --verify-only ...

scripts/live-validation/prepare-scenario.sh ... --secondary-timeframe 4h
build/install/qkt/bin/qkt parse <generated-strategy>
scripts/live-validation/run-market-bracket.sh --verify-only ...
```

Retained live/replay evidence:

- Single lifecycle live probe, useful as order-path evidence but not replay-complete because the close
  is an operator kill/flatten rather than a DSL-owned close:
  `/var/tmp/qkt-validation/xau-htf15-bracket-20260812T061608Z/evidence/result.json`
- Replayable strategy-owned higher-timeframe lifecycle:
  `/var/tmp/qkt-validation/xau-htf15-reentry-blocked-20260812T061946Z/evidence/result.json`
- Golden replay comparison:
  `/var/tmp/qkt-validation/xau-htf15-reentry-blocked-20260812T061946Z-replay/result.json`

Live result summary for `xau_htf15_reentry_blocked_market_bracket`:

- `status:"passed"`, `qktDirty:false`, QKT commit
  `00c0e084e7573f77e5e5a2b49b81c5b48e544d3b`.
- QKT CLI: `qkt 0.47.1 (00c0e084) built 2026-08-12T06:15:56.119478601Z`.
- Gateway: demo2 localhost gateway, version `0.3.4`, account `476434211`,
  server `Exness-MT5Trial9`.
- Strategy streams: `asset1` on `1m`, `asset5` on `15m`; retained
  `armedTimeframes:["15m","1m"]`.
- Indicators in the live entry condition:
  `ema(1m,3)`, `ema(1m,5)`, `ema(15m,3)`, `ema(15m,5)`.
- History readiness checked both `15m` and `1m` on attempt `1`.
- Live log retained the actual higher-timeframe decision values before order submission:
  `secondary_fast=4389.59802083`, `secondary_slow=4391.43454403`.
- Lifecycle: one real XAUUSD strategy-owned SELL entry, one strategy-owned BUY close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"1.61"` and `dealNet:"1.61"`.
- Golden capture: `ticks:211`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `ca006ece79a25786d4e8a7ec44decf1633c97ad2146bbbc71ba95aad8ff518b6`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.
- Venue account snapshot note: Exness reported leverage changed from `1000` to `500` during/after the
  run; the runner retained this as `leverage.changed:true`. The account still ended flat and tradeable.

Replay result summary:

- `status:"passed"`, lifecycle `reentry_blocked_max_trades`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live-vs-MT5-sim entry fill drift is retained as expected venue latency/model difference:
  live SELL fill `4389.867`, simulated fill `4389.71300000`, delta
  `0.1540000000004511`.

Remaining higher-timeframe work:

- M15/H1/H4 daemon/order/replay are now sealed for the fast validation pattern:
  `1m` primary execution stream plus warmed higher-timeframe secondary stream.
- Longer natural-close H1/H4 daemon soaks can still be added later, but they are no longer blockers
  for proving that warmed H1/H4 values can participate in the live order path and replay comparison.

## 2026-08-12 Update: H1 And H4 Higher-Timeframe Daemon Evidence

Extended the higher-timeframe daemon proof from M15 to H1 and H4 using the replayable
`reentry_blocked_max_trades` lifecycle.

Verification already run:

```bash
./gradlew installDist -Pkotlin.compiler.execution.strategy=daemon
```

Retained H1 live/replay evidence:

- Live scenario:
  `/var/tmp/qkt-validation/xau-htf1h-reentry-blocked-20260812T062749Z/evidence/result.json`
- Golden replay comparison:
  `/var/tmp/qkt-validation/xau-htf1h-reentry-blocked-20260812T062749Z-replay/result.json`

H1 live result summary:

- `status:"passed"`, `qktDirty:false`, QKT commit
  `dbf9aa6ad2a8646779f050c9d549b313801b75ec`.
- Strategy streams: `asset1` on `1m`, `asset5` on `1h`; retained
  `armedTimeframes:["1h","1m"]`.
- History readiness checked both `1h` and `1m` on attempt `1`.
- Lifecycle: one real XAUUSD strategy-owned entry, one strategy-owned close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"-0.28"` and `dealNet:"-0.28"`.
- Golden capture: `ticks:174`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `c402081bb39d3fa16bdb6c46a358b0c64ad2c5bb7dd65720df24a881d024a09a`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.

H1 replay result summary:

- `status:"passed"`, lifecycle `reentry_blocked_max_trades`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live-vs-MT5-sim entry fill drift is retained as expected venue latency/model difference:
  live BUY fill `4389.445000000001`, simulated fill `4389.49300000`, delta
  `-0.047999999999774445`.

Retained H4 live/replay evidence:

- Live scenario:
  `/var/tmp/qkt-validation/xau-htf4h-reentry-blocked-20260812T063543Z/evidence/result.json`
- Golden replay comparison:
  `/var/tmp/qkt-validation/xau-htf4h-reentry-blocked-20260812T063543Z-replay/result.json`

H4 live result summary:

- `status:"passed"`, `qktDirty:false`, QKT commit
  `dbf9aa6ad2a8646779f050c9d549b313801b75ec`.
- Strategy streams: `asset1` on `1m`, `asset5` on `4h`; retained
  `armedTimeframes:["1m","4h"]`.
- History readiness checked both `1m` and `4h` on attempt `1`.
- Lifecycle: one real XAUUSD strategy-owned entry, one strategy-owned close, then one same-day
  `MaxTradesPerDay` rejection before MT5 transport.
- `blockedReentry:{expected:1, reason:"MaxTradesPerDay", rejections:1, preTransport:true}`.
- Final account ownership: `finalPositions:0`, `finalOrders:0`.
- Transport counts: `orderPosts:1`, `closePosts:1`.
- Audit counts: `acceptedEvents:2`, `filledEvents:2`, `riskRejections:1`.
- Venue reconciliation: `balanceDelta:"2.20"` and `dealNet:"2.20"`.
- Golden capture: `ticks:297`, `warmupTicks:80`, `candles:13`, `fills:2`,
  `linkedPlacements:1`, SHA-256
  `71e012e7d725e4a45727514c2f8cbf68334fcc6c52c40f55562fc17b85e86a0d`.
- Stale-data count during this run: `staleEvents:0`, `recoveredStaleEvents:0`.

H4 replay result summary:

- `status:"passed"`, lifecycle `reentry_blocked_max_trades`.
- Full-tick order journals were byte-exact.
- Bar replay order journals were timestamp-normalized exact.
- Live initial protection matched canonical intent.
- Live adjusted protection matched captured broker fill.
- MT5 simulation used the same canonical intent.
- Live-vs-MT5-sim entry fill drift is retained as expected venue latency/model difference:
  live BUY fill `4390.374000000001`, simulated fill `4390.31200000`, delta
  `0.06200000000080763`.

Final demo2 snapshot after H4:

- Account `476434211`, server `Exness-MT5Trial9`.
- Balance/equity `100002.33`, margin `0.0`, leverage `500`.
- `trade_allowed:true`, `trade_expert:true`.
- Magic-scoped positions/orders for the H4 scenario were empty.

## 2026-08-12 Update: QKT Shared-Account Deal Emission Hardening

Closed the upstream QKT side of the shared-account Insights attribution risk.

Problem clarified:

- A live strategy daemon polls whole-account broker deal history from MT5.
- Before this hardening, `BrokerStatePoller` emitted `broker.deal` envelopes even when the deal could
  not be attributed to one of that daemon's deployed strategy ids.
- In a shared MT5 account with multiple independent QKT daemons, that could send null-owned or
  sibling-owned deal rows into the wrong daemon instance before `qkt-insights` had a chance to
  scope/drop them.

Source fix:

- [BrokerStatePoller.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/observe/insights/BrokerStatePoller.kt:147)
  now emits `broker.deal` only when:
  - the poller has no deployed strategy ids, preserving account-level backfill behavior; or
  - the deal is attributed by ticket/comment to one of the poller's deployed strategy ids.
- Skipped foreign/unattributed deals still advance the broker deal timestamp cursor, so a strategy
  daemon does not repeatedly refetch the same irrelevant account history.

Regression coverage:

- [BrokerStatePollerTest.kt](/home/dickson/Desktop/personal/qkt/src/test/kotlin/com/qkt/observe/insights/BrokerStatePollerTest.kt:327)
  now proves a deployed strategy poller emits local ticket-owned and local comment-owned deals while
  suppressing sibling-owned and unknown shared-account deals.

Verification run without JVM heap or worker restrictions:

```bash
./gradlew test --tests com.qkt.observe.insights.BrokerStatePollerTest -Pkotlin.compiler.execution.strategy=daemon
./gradlew test --tests com.qkt.observe.insights.TicketAttributionTest --tests com.qkt.observe.insights.InsightsTranslateTest -Pkotlin.compiler.execution.strategy=daemon
bash tests/scripts/run-shared-account-insights-round-trips-test.sh
git diff --check
```

All four commands passed.

Effect on the live parity plan:

- The retained `qkt-insights:validation-live-state-attribution-20260812` collector proof is still
  valid and remains necessary.
- QKT now also avoids producing wrong-instance `broker.deal` envelopes in the first place for
  deployed live daemons.
- The next live shared-account rerun should rebuild the QKT validation image from this commit before
  claiming the upstream emission hardening in retained live evidence.

## 2026-08-12 Update: Shared-Account Rerun Pre-Arm Gate

Rebuilt and verified the QKT validation runtime after the upstream shared-account deal-emission fix:

- QKT commit under proof: `74a770977db72649543c0c65fa9e3ccd2ec104f6`.
- Host CLI version:
  `qkt 0.47.1 (74a77097) built 2026-08-12T06:51:40.008682291Z`.
- Docker validation image:
  `qkt:live-validation-74a77097`, image id
  `sha256:fa33f20a03c623bbe79cb5ec39d79aa6315948a1724fc10ccfbf4de83ccc90c9`.
- Image version matched the host CLI:
  `qkt 0.47.1 (74a77097) built 2026-08-12T06:51:40.008682291Z`.
- The cold Dockerfile build failed inside Docker's Gradle daemon before runtime image creation; no
  JVM heap/worker restrictions were added to force it through. The retained validation image was
  packaged from the already verified host `installDist` and the previous local validation runtime
  base, preserving the same QKT version check used by the live runner.
- Clean proving worktree used for live runner clean-repo checks:
  `/var/tmp/qkt-live-proof-74a77097-20260812T083643Z`.

Prepared fresh demo2 scenarios from the current account snapshot:

- Prepared scenario root:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-prepare`.
- EURUSD scenario:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-prepare/eurusd`.
- GBPUSD scenario:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-prepare/gbpusd`.
- Account snapshot at preparation: demo2 login `476434211`, server `Exness-MT5Trial9`,
  balance/equity `100002.33`, leverage `500`, margin `0.0`.
- Wrapper verify-only passed:
  `run-shared-account-insights-round-trips.sh --verify-only` against
  `qkt-insights:validation-live-state-attribution-20260812`.

Two guarded armed attempts were made with the fixed QKT image and the August 12 Insights image. Both
stopped before arming live QKT strategy containers because the strict pre-arm tick-freshness gate
failed. No live order mutation occurred.

Attempt 1 retained evidence:

- Output:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-live`.
- Tick gate summary:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-live/base-roundtrip/evidence/tick-freshness-gate-summary.json`.
- Gate result: `status:"failed"`, `maxAllowedAgeMs:8000`.
- EURUSDm: `samples:25`, `invalid:1`, `maxAgeMs:8109`, `overLimit:1`.
- GBPUSDm: `samples:25`, `invalid:0`, `maxAgeMs:4286`, `overLimit:0`.

Attempt 2 retained evidence:

- Output:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-live-retry2`.
- Tick gate summary:
  `/var/tmp/qkt-validation/shared-account-insights-74a77097-0812083710-live-retry2/base-roundtrip/evidence/tick-freshness-gate-summary.json`.
- Gate result: `status:"failed"`, `maxAllowedAgeMs:8000`.
- EURUSDm: `samples:25`, `invalid:0`, `maxAgeMs:6705`, `overLimit:0`.
- GBPUSDm: `samples:25`, `invalid:1`, `maxAgeMs:8816`, `overLimit:1`.

Post-attempt account flatness:

- demo2 account remained balance/equity `100002.33`, margin `0.0`, leverage `500`.
- `/get_positions` returned an empty `data` array.
- `/orders` returned an empty `orders` array and `total:0`.
- No QKT strategy containers remained running; only the two local gateway containers remained.

Current consequence:

- The QKT emission fix is source-tested and packaged into a validation image.
- The fixed-image shared-account live rerun is still not sealed because market freshness stopped both
  attempts before arming.
- The next retry should reuse the prepared scenario pair only while the starting balance remains
  `100002.33`; otherwise prepare a fresh pair from the latest account snapshot.
- Do not weaken the freshness gate. A passing live proof must show the same strict gate allows arming
  under genuinely fresh reviewed strategy ticks, then retain the full wrapper result and replay
  comparisons.

## 2026-08-12 Update: Shared-Account Runner Reviewed-Symbol Flexibility

Added harness-only flexibility after the `74a77097` fixed-image attempts were blocked by EURUSD/GBPUSD
feed freshness:

- [run-container-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-container-round-trips.sh)
  no longer hard-codes scenario A to `EXNESS:EURUSD` and scenario B to `EXNESS:GBPUSD`.
- The runner now derives strategy symbol, venue symbol, expected contract size, stop distance, and
  take-profit distance from each prepared scenario's `expected.json`.
- The accepted live set remains explicit and reviewed:
  `EXNESS:EURUSD/EURUSDm/100000`, `EXNESS:GBPUSD/GBPUSDm/100000`, and
  `EXNESS:XAUUSD/XAUUSDm/100`.
- The strict live gates are unchanged: clean repo, matching QKT image/commit, flat demo account,
  localhost gateway, no JVM override env, no Docker resource caps, startup-window alignment, and
  tick-freshness gate before arming.
- The runner's indicator-entry log parser now accepts both the older `m5_fast/m5_slow` labels and
  the current `secondary_fast/secondary_slow` labels emitted by prepared strategies.

Verification:

```bash
bash tests/scripts/run-container-round-trips-test.sh
bash tests/scripts/run-shared-account-insights-round-trips-test.sh
git diff --check
```

All three passed.

Next live retry:

- Rebuild/repackage the QKT validation image from this new commit before claiming retained live
  evidence.
- Prefer a prepared pair using a fresher reviewed symbol such as `XAUUSD` if EURUSD/GBPUSD continue
  to fail the strict pre-arm freshness gate.
- Do not count the prior `74a77097` pre-arm attempts as an armed live proof; they remain valid safety
  evidence only.

## 2026-08-12 Update: XAUUSD Same-Symbol Armed Finding

Prepared and ran a same-account, same-symbol demo2 proof using the current `980a13cc` image:

- Clean proving worktree:
  `/var/tmp/qkt-live-proof-980a13cc-20260812T085823Z`.
- Prepared scenarios:
  `/var/tmp/qkt-validation/shared-account-insights-980a13cc-0812085849-prepare/xau_a` and
  `/var/tmp/qkt-validation/shared-account-insights-980a13cc-0812085849-prepare/xau_b`.
- Armed output:
  `/var/tmp/qkt-validation/shared-account-insights-980a13cc-0812085849-live`.
- Both scenarios used `EXNESS:XAUUSD` / `XAUUSDm` on demo2 with distinct strategy ids and magics
  `980211` and `980212`.
- Wrapper verify-only passed before arming.
- The strict tick-freshness gate passed before arming: `XAUUSDm`, `samples:50`, `invalid:0`,
  `maxAgeMs:1723`, `overLimit:0`, `maxAllowedAgeMs:8000`.

Live behavior observed before the post-run audit failure:

- Two real `0.01`-lot demo orders were opened concurrently on the same XAUUSD symbol and same MT5
  account, with distinct magics and comments.
- The strategies took opposite sides, then both issued strategy-owned closes.
- Final demo2 state was flat: `/get_positions` empty, `/orders` empty, account balance/equity
  `100002.06`, margin `0.0`.
- No QKT strategy containers remained running after the attempt.

The run failed after live trading, during retained evidence validation:

- Failure: `run-container-round-trips: scenario 0 entry drift exceeds the reviewed 80-point bound`.
- Root cause: harness-side FX-sized entry-drift threshold reused for XAUUSD.
- Evidence: the XAUUSD symbol point is `0.001` and the observed spread was about `260` points; the
  strategy decision/bracket anchor was `4405.172`, while the live fills were about `4404.971` to
  `4404.972`, roughly `200` points from the decision anchor and inside the observed spread class.
- The venue protection itself was correctly anchored to the actual fill price; this was not an
  engine fill/protection bug.

Fix applied locally after the finding:

- [prepare-scenario.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/prepare-scenario.sh)
  now emits symbol-specific reviewed entry-anchor drift: `80` points for EURUSD/GBPUSD and `400`
  points for XAUUSD.
- [run-container-round-trips.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-container-round-trips.sh)
  now accepts only the reviewed symbol/contract/drift tuples:
  `EXNESS:EURUSD/EURUSDm/100000/80`, `EXNESS:GBPUSD/GBPUSDm/100000/80`, and
  `EXNESS:XAUUSD/XAUUSDm/100/400`.

Verification after the harness fix:

```bash
bash tests/scripts/prepare-live-validation-scenario-test.sh
bash tests/scripts/run-container-round-trips-test.sh
git diff --check
```

All three passed.

Next step:

- Commit the harness fix, rebuild/repackage a new QKT validation image from that commit, prepare a
  fresh demo2 XAUUSD scenario pair from the latest account snapshot, rerun the same armed
  shared-account Insights proof, and then run replay comparisons on any passing retained live
  captures.

## 2026-08-12 Update: XAUUSD Base Pass, Insights Deal Gap

Rebuilt and reran after `fix(scripts): use symbol drift bounds`:

- QKT commit under proof: `cd345372c7707a0d7ff22ba074fb9632d6bf623d`.
- Docker validation image:
  `qkt:live-validation-cd345372`, image id
  `sha256:8637a90a02df42cbc80081a5e4e2dc457f289d5f7da600c077ab14c8ddfab3ca`.
- Host/image version:
  `qkt 0.47.1 (cd345372) built 2026-08-12T09:11:27.694273378Z`.
- Clean proving worktree:
  `/var/tmp/qkt-live-proof-cd345372-20260812T091226Z`.
- Prepared scenarios:
  `/var/tmp/qkt-validation/shared-account-insights-cd345372-0812091300-prepare/xau_a` and
  `/var/tmp/qkt-validation/shared-account-insights-cd345372-0812091300-prepare/xau_b`.
- Armed output:
  `/var/tmp/qkt-validation/shared-account-insights-cd345372-0812091300-live`.

The base shared-account live round trip passed:

- Base result:
  `/var/tmp/qkt-validation/shared-account-insights-cd345372-0812091300-live/base-roundtrip/evidence/result.json`.
- Status: `passed`.
- Symbols: two same-account `EXNESS:XAUUSD` strategies with distinct magics `345211` and `345212`.
- Synchronized deployment: `launchSkewMs:11`, `completionSkewMs:246`.
- Strict tick gate passed: `XAUUSDm`, `samples:50`, `invalid:0`, `maxAgeMs:1471`, `overLimit:0`.
- Both strategies opened real `0.01`-lot demo positions, saw M1 and M5 stream/evaluation evidence,
  retained indicator entry/exit traces, issued strategy-owned closes, and reconciled to final flat.
- Final base counts per case: two decisions, two decision/order links, two accepts, two fills, two
  accounted fills, zero rejections, three venue mutations.
- XAUUSD entry drift stayed inside the reviewed 400-point envelope:
  scenario A `166` points, scenario B `-111` points.

The wrapper still failed after the base pass:

- Failure:
  `collector did not retain exactly one entry and one exit deal for scd3xaua0812091300_market_bracket`.
- Retained collector DB had two filled order rows per instance and isolated causal event streams, but
  the `deals` table contained only the entry legs.
- There were no `state.account`, `state.positions`, `state.orders`, or `broker.deal` events for the
  live instances, so the collector did not drop the closing deals; QKT never emitted the state/deal
  poller envelopes during the run.
- The live MT5 gateway history does contain the missing OUT deals for both tickets, so this is a
  QKT observability/poller wiring gap rather than a broker-history or order-lifecycle failure.

Source fix applied locally:

- [CompositeBroker.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/broker/CompositeBroker.kt)
  now exposes observer state views from its leaves: account state, deal history, and pending orders.
- [LiveSession.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/app/LiveSession.kt)
  now builds the Insights broker-state poller from `builtBrokers.ifEmpty { listOf(broker) }`, so the
  active routed broker state view is still available if a daemon path does not retain leaf brokers.

Verification after the source fix:

```bash
./gradlew test --tests com.qkt.broker.CompositeBrokerTest --tests com.qkt.observe.insights.BrokerStatePollerTest -Pkotlin.compiler.execution.strategy=daemon
git diff --check
```

Both passed.

Next step:

- Commit the source fix, rebuild/repackage the QKT validation image from the new commit, rerun the
  same same-symbol shared-account Insights proof, and then run replay comparisons after the wrapper
  result passes.

## 2026-08-12 Update: Insights Poller Fix Rerun, XAUUSD Market Drift

Rebuilt and reran after `fix(app): poll routed broker state for insights`:

- QKT commit under proof: `fa917a44`.
- Docker validation image:
  `qkt:live-validation-fa917a44`, image id
  `sha256:3f6dc293fd66583dc6e1cd390e3cf81aa37fbd286aab9bcc20ad030276708941`.
- Host/image version:
  `qkt 0.47.1 (fa917a44) built 2026-08-12T09:29:16.412553483Z`.
- Clean proving worktree:
  `/var/tmp/qkt-live-proof-fa917a44-20260812T092943Z`.
- Prepared scenarios:
  `/var/tmp/qkt-validation/shared-account-insights-fa917a44-0812093002-prepare/xau_a` and
  `/var/tmp/qkt-validation/shared-account-insights-fa917a44-0812093002-prepare/xau_b`.
- Armed output:
  `/var/tmp/qkt-validation/shared-account-insights-fa917a44-0812093002-live`.

Observed:

- Strict tick gate passed: `XAUUSDm`, `samples:50`, `invalid:0`, `maxAgeMs:1401`, `overLimit:0`.
- Both strategies opened real `0.01`-lot XAUUSD demo positions and then closed them.
- Final demo2 state was flat: no positions and no pending orders.
- The QKT Insights poller fix worked far enough to emit state/deal history: scenario A had both IN
  and OUT deals retained in the collector DB.
- The run failed before wrapper-level Insights pass because the base runner rejected scenario B's
  entry drift: intent anchor `4415.338`, fill `4414.738`, point `0.001`, drift `-600` points.
- Venue protection was still fill-anchored correctly for scenario B: fill `4414.738`, SL
  `4417.738`, TP `4408.738`.

Classification:

- This is not an engine protection bug and not residual account exposure.
- The existing XAUUSD 400-point reviewed drift envelope is still too tight for fast market execution
  on this venue. A 600-point fill drift is larger than the observed 260-point spread but still below
  the 3000-point stop distance and was handled correctly by fill-anchored protection.

Harness update applied locally:

- XAUUSD `maximumEntryAnchorDriftPoints` moved from `400` to `1000`.
- EURUSD/GBPUSD remain at `80`.
- The runner still accepts only explicit reviewed tuples:
  `EXNESS:EURUSD/EURUSDm/100000/80`, `EXNESS:GBPUSD/GBPUSDm/100000/80`, and
  `EXNESS:XAUUSD/XAUUSDm/100/1000`.

Next step:

- Run the script regressions for the updated reviewed tuple, commit the harness update, rebuild the
  validation image again, and rerun the same XAUUSD shared-account Insights proof.

## 2026-08-12 Update: Delayed OUT Deal Attribution Race

Rebuilt and reran after `fix(scripts): widen gold drift envelope`:

- QKT commit under proof: `c02c5536`.
- Docker validation image:
  `qkt:live-validation-c02c5536`, image id
  `sha256:4ee081c803851ae97a69db5d2342d2f3042d39fe8839b6aef5f6732eb90e657f`.
- Host/image version:
  `qkt 0.47.1 (c02c5536) built 2026-08-12T09:39:37.292174199Z`.
- Clean proving worktree:
  `/var/tmp/qkt-live-proof-c02c5536-20260812T093956Z`.
- Prepared scenarios:
  `/var/tmp/qkt-validation/shared-account-insights-c02c5536-0812094018-prepare/xau_a` and
  `/var/tmp/qkt-validation/shared-account-insights-c02c5536-0812094018-prepare/xau_b`.
- Armed output:
  `/var/tmp/qkt-validation/shared-account-insights-c02c5536-0812094018-live`.

The base shared-account live round trip passed again:

- Base result:
  `/var/tmp/qkt-validation/shared-account-insights-c02c5536-0812094018-live/base-roundtrip/evidence/result.json`.
- The run opened and closed two real same-account XAUUSD demo positions, retained warmup/tick/bar
  evidence, retained M1/M5 stream/evaluation evidence, and returned demo2 to flat.

The wrapper still failed on QKT Insights deal completeness:

- Failure:
  `collector did not retain exactly one entry and one exit deal for sc02xaua0812094018_market_bracket`.
- During/after the run, the collector DB retained only IN deals for both instances.

Root cause now identified as a QKT race:

- MT5 can report the position as gone before `/history_deals_get` exposes the OUT deal.
- `BrokerStatePoller` previously called `TicketAttribution.retainAll(openTickets)` every cycle.
- If the poller saw no open ticket before the OUT deal appeared, it pruned the ticket owner.
- Later, the OUT deal arrived with an empty venue comment, so the strategy-scoped poller could not
  attribute it and filtered it out.

Source fix applied locally:

- [TicketAttribution.kt](/home/dickson/Desktop/personal/qkt/src/main/kotlin/com/qkt/observe/insights/TicketAttribution.kt)
  now keeps vanished ticket owners for a bounded 300 poll-cycle grace window, resetting the grace
  whenever a ticket is seen live again.
- This preserves attribution for delayed empty-comment OUT deals while still bounding the map in
  long-running daemons.

Verification after the source fix:

```bash
./gradlew test --tests com.qkt.observe.insights.TicketAttributionTest --tests com.qkt.observe.insights.BrokerStatePollerTest -Pkotlin.compiler.execution.strategy=daemon
git diff --check
```

Both passed.

Next step:

- Commit the source fix, rebuild/repackage the validation image from the new commit, rerun the same
  XAUUSD shared-account Insights proof, and confirm the collector now retains both IN and OUT deals
  per instance.

## 2026-08-12 Update: Same-Symbol XAUUSD Shared-Account Slice Sealed

The delayed OUT-deal attribution fix was committed as:

- `961dd705 fix(app): retain closed ticket attribution`.

Validation runtime used for the sealed proof:

- QKT image: `qkt:live-validation-961dd705`.
- Image id:
  `sha256:b133430f70a7524ce6dc45eb43c6f10b4bb48ccf598ad70cf60df3c5f3f8429e`.
- Host/image version:
  `qkt 0.47.1 (961dd705) built 2026-08-12T09:49:21.867467424Z`.
- Clean proving worktree:
  `/var/tmp/qkt-live-proof-961dd705-20260812T095001Z`.
- Prepared scenarios:
  `/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-prepare/xau_a` and
  `/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-prepare/xau_b`.
- Armed live output:
  `/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-live`.

The base same-symbol shared-account live round trip passed:

- Base result:
  `/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-live/base-roundtrip/evidence/result.json`.
- Two independent QKT strategy daemons traded the same real demo2 MT5 account and the same reviewed
  symbol, `EXNESS:XAUUSD` / `XAUUSDm`, under distinct strategy ids and magics.
- Strict tick gate passed before arming: `XAUUSDm`, `samples:50`, `invalid:0`, `maxAgeMs:1413`,
  `overLimit:0`.
- Synchronized deployment launch skew was `13ms`; completion skew was `743ms`.
- Both strategies opened and closed real `0.01`-lot demo positions and returned the account to flat
  with zero pending orders.
- Both retained M1 and M5 stream/evaluation evidence, indicator entry/exit traces, two rule
  decisions, two decision/order links, two accepted orders, two fills, two accounted fills, and zero
  rejections.
- Scenario A: SELL, ticket `3075091234`, entry drift `128` XAUUSD points, stale gates `0`, one feed
  disconnect warning.
- Scenario B: BUY, ticket `3075091412`, entry drift `385` XAUUSD points, stale gates `0`, one feed
  disconnect warning.
- Owned deal net and balance delta were both `-0.72`.

The shared-account QKT Insights wrapper also passed:

- Wrapper result:
  `/var/tmp/qkt-validation/shared-account-insights-961dd705-0812095001-live/evidence/result.json`.
- QKT Insights image id:
  `sha256:1e3549c09f0a1d98cccc02a108e280d8ae0bcf4dcd8b53ea95563a3858fa63fc`.
- Each retained instance had `filledOrders:2`, `dealLegs:2`, and `flat:true`.
- Collector health, causal contract probe, retained instance count, gap/regression checks, and
  cross-owner leakage checks all passed.
- The delayed OUT-deal attribution race is closed for this proof: both IN and OUT deals were retained
  for both strategy instances.

Replay comparison finding and harness correction:

- Replaying the retained live captures through `full-ticks-paper`, `full-ticks-mt5`, and
  `bars-paper` showed exact strategy decision, DSL order, indicator, candle/evaluation, fill-count,
  flatness, and no-rejection parity.
- The old comparator required exact live MT5 fill prices and PnL to equal the local `mt5-sim`
  replay. That is not a valid production expectation for real market orders because live execution
  includes terminal/gateway/venue latency while `mt5-sim` fills deterministically on the recorded
  tick stream at the decision timestamp.
- The comparator now keeps exact checks for strategy intent and adjusted protection distances, then
  records and enforces a reviewed per-symbol live-vs-sim execution drift bound.
- Script regression:

```bash
bash tests/scripts/compare-container-round-trip-replay-test.sh
git diff --check
```

Both passed.

The corrected replay comparisons now pass:

- Scenario A replay:
  `/var/tmp/qkt-validation/replay-961dd705-xau-a-0812095001-bounded/result.json`.
  - Status: `passed`.
  - Exact parity: input/candle/evaluation counts, order journals, bars timestamp-normalized orders,
    indicator entry, indicator exit quantity/close, canonical entry intent, live request/protection
    intent, two fills, zero rejections, final flat.
  - Bounded live-vs-sim drift: entry `387` XAUUSD points, exit approximately `0` points, PnL delta
    `0.387`, within the reviewed `1000`-point / `2.0` account-PnL bound.
- Scenario B replay:
  `/var/tmp/qkt-validation/replay-961dd705-xau-b-0812095001-bounded/result.json`.
  - Status: `passed`.
  - Exact parity: same as scenario A.
  - Bounded live-vs-sim drift: entry `230` XAUUSD points, exit `-213` XAUUSD points, PnL delta
    `-0.443`, within the reviewed `1000`-point / `2.0` account-PnL bound.

What is sealed by this update:

- The current same-symbol XAUUSD, two-daemon, shared-account live order-bearing slice is sealed for
  QKT plus QKT Insights.
- Warmup, live ticks, M1/M5 bars, strategy decisions, DSL order mapping, live order placement,
  protection adjustment, strategy-owned close, fills, accounting, Insights collection, flat cleanup,
  and replay comparison are all covered for this slice.

What is still not the full program-done line:

- every generated variant and indicator family has not yet been rerun under the final committed
  runtime image;
- the retained-state risk lifecycle matrix still needs the final quick live sanity rerun after the
  latest fixes;
- higher-timeframe 15m/1h/4h warmup compression needs its explicit later validation;
- promotion to `dev`, `testing`, `main`, then `qkt-forge`/`qkt-insights`/bot rollout remains blocked
  until the final sanity matrix passes.

Immediate next step:

- Commit the replay comparator hardening, then run the quick final sanity QKT strategy matrix on the
  current image/evidence set before starting PR/promotion work.

## 2026-08-12 Update: Final-Sanity EMA Gate And Single-Close Harness Fix

While starting the final generated strategy sanity matrix, the first EMA/EURUSD clean live run exposed
a validation harness weakness:

- Old-style single lifecycle evidence:
  `/var/tmp/qkt-validation/final-sanity-clean-9999f9c8-ema-eurusd-0812101458/evidence/result.json`.
- That live run opened and flattened safely, but the close fill was produced by
  `qkt kill --flatten` as `operator-kill-*`, not by the strategy `CLOSE` rule.
- Replay comparison rejected the capture:
  `/var/tmp/qkt-validation/final-sanity-clean-9999f9c8-ema-eurusd-0812101458-replay`.
- Failure:
  `full-ticks-paper did not produce 2 trade event(s)`.
- Root cause: [run-market-bracket.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/run-market-bracket.sh)
  treated `single` scenarios as passable after entry plus operator flatten. That proves emergency
  cleanup and broker flattening, but it does not prove strategy-owned live/backtest close parity.

Harness fix committed:

- `e439b14e fix(scripts): require strategy-owned single close`.
- `single` lifecycle now waits for `wait_for_flat_cycle 1` just like the strategy-owned lifecycle
  paths, captures `strategy-status-flat.json`, and reports `strategyOwnedLifecycle:true`.
- `flattenVerified` is now false for passed `single` evidence; emergency flatten remains only in the
  cleanup trap, not in the success path.
- [compare-golden-replay.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/compare-golden-replay.sh)
  now requires `single` captures to have `strategyOwnedLifecycle:true`.
- Static/contract regressions passed:

```bash
bash -n scripts/live-validation/run-market-bracket.sh
bash -n scripts/live-validation/compare-golden-replay.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
bash tests/scripts/prepare-generated-parity-wave-test.sh
git diff --check
```

Strict final-sanity EMA/EURUSD rerun after the fix:

- Clean proving worktree:
  `/var/tmp/qkt-final-sanity-e439b14e-20260812T102319Z`.
- Live scenario:
  `/var/tmp/qkt-validation/final-sanity-clean-e439b14e-ema-eurusd-0812102321`.
- Live result:
  `/var/tmp/qkt-validation/final-sanity-clean-e439b14e-ema-eurusd-0812102321/evidence/result.json`.
- Replay result:
  `/var/tmp/qkt-validation/final-sanity-clean-e439b14e-ema-eurusd-0812102321-replay/result.json`.

What the strict EMA/EURUSD proof sealed:

- QKT commit under proof: `e439b14ee5bca7fe62c8fc0582d821ad7fa7bead`.
- `qktDirty:false`.
- Strategy: `fse439_ema_0812102321_market_bracket`.
- Lifecycle: `single`, `strategyOwnedLifecycle:true`, `flattenVerified:false`.
- Timeframes: `1m` and `5m`.
- Live path: one real `0.01`-lot entry and one strategy-owned close on demo2 through the local
  MT5 gateway.
- Audit: two accepted events, two filled events, zero risk rejections.
- Transport: one `/order` post and one `/close_position` post.
- Golden capture: `ticks:21`, `warmupTicks:80`, `candles:12`, `fills:2`, `linkedPlacements:1`.
- Operational market-data result: `staleEvents:0`, `recoveredStaleEvents:0`.
- Final account state: flat, zero pending orders.
- Reconciliation: `balanceDelta:-0.08`, `dealNet:-0.08`.
- Replay comparison status: `passed`.
- Replay parity flags:
  - `fullTickOrderJournalsByteExact:true`;
  - `barsOrdersTimestampNormalizedExact:true`;
  - `liveInitialProtectionMatchesCanonicalIntent:true`;
  - `liveAdjustedProtectionMatchesCapturedBrokerFill:true`;
  - `mt5SimulationUsesSameCanonicalIntent:true`;
  - `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

Next generated final-sanity items still open:

- run the same strict live-plus-replay proof for `atr_channel`;
- run the same strict live-plus-replay proof for `case_math`;
- then decide whether the final generated sanity matrix is enough to move into PR/promotion, or
  whether the higher-timeframe and retained-risk slices must be rerun first.

## 2026-08-12 Update: Final-Sanity RSI Gate Sealed

Strict final-sanity RSI/GBPUSD proof after the single-close harness fix:

- Clean proving worktree:
  `/var/tmp/qkt-final-sanity-8998ad2f-20260812T103032Z`.
- Live scenario:
  `/var/tmp/qkt-validation/final-sanity-clean-8998ad2f-rsi-gbpusd-0812103034`.
- Live result:
  `/var/tmp/qkt-validation/final-sanity-clean-8998ad2f-rsi-gbpusd-0812103034/evidence/result.json`.
- Replay result:
  `/var/tmp/qkt-validation/final-sanity-clean-8998ad2f-rsi-gbpusd-0812103034-replay/result.json`.

What the strict RSI/GBPUSD proof sealed:

- QKT commit under proof: `8998ad2f253d818c6478e2d89f8912a35e4ff224`.
- `qktDirty:false`.
- Strategy: `fs8998_rsi_0812103034_market_bracket`.
- Lifecycle: `single`, `strategyOwnedLifecycle:true`, `flattenVerified:false`.
- Timeframes: `1m` and `5m`.
- Live path: one real `0.01`-lot entry and one strategy-owned close on demo2 through the local
  MT5 gateway.
- Audit: two accepted events, two filled events, zero risk rejections.
- Transport: one `/order` post and one `/close_position` post.
- Golden capture: `ticks:55`, `warmupTicks:80`, `candles:12`, `fills:2`, `linkedPlacements:1`.
- Operational market-data result: `staleEvents:0`, `recoveredStaleEvents:0`.
- Final account state: flat, zero pending orders.
- Reconciliation: `balanceDelta:-0.13`, `dealNet:-0.13`.
- Replay comparison status: `passed`.
- Replay parity flags:
  - `fullTickOrderJournalsByteExact:true`;
  - `barsOrdersTimestampNormalizedExact:true`;
  - `liveInitialProtectionMatchesCanonicalIntent:true`;
  - `liveAdjustedProtectionMatchesCapturedBrokerFill:true`;
  - `mt5SimulationUsesSameCanonicalIntent:true`;
  - `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

Generated final-sanity matrix status after this update:

- `ema_cross` on EURUSD: sealed strict live-plus-replay.
- `rsi_reversion` on GBPUSD: sealed strict live-plus-replay.
- `atr_channel`: still open.
- `case_math`: still open.

## 2026-08-12 Update: Final-Sanity ATR Gate Sealed

Strict final-sanity ATR/EURUSD proof:

- Clean proving worktree:
  `/var/tmp/qkt-final-sanity-88d170ea-20260812T103611Z`.
- Live scenario:
  `/var/tmp/qkt-validation/final-sanity-clean-88d170ea-atr-eurusd-0812103613`.
- Live result:
  `/var/tmp/qkt-validation/final-sanity-clean-88d170ea-atr-eurusd-0812103613/evidence/result.json`.
- Replay result:
  `/var/tmp/qkt-validation/final-sanity-clean-88d170ea-atr-eurusd-0812103613-replay/result.json`.

What the strict ATR/EURUSD proof sealed:

- QKT commit under proof: `88d170eaca0da91205c10f137eb4ddbc6fcc69a9`.
- `qktDirty:false`.
- Strategy: `fs88d_atr_0812103613_market_bracket`.
- Lifecycle: `single`, `strategyOwnedLifecycle:true`, `flattenVerified:false`.
- Timeframes: `1m` and `5m`.
- Live path: one real `0.01`-lot entry and one strategy-owned close on demo2 through the local
  MT5 gateway.
- Audit: two accepted events, two filled events, zero risk rejections.
- Transport: one `/order` post and one `/close_position` post.
- Golden capture: `ticks:11`, `warmupTicks:80`, `candles:12`, `fills:2`, `linkedPlacements:1`.
- Operational market-data result: `staleEvents:2`, `recoveredStaleEvents:2`; stale episodes
  recovered before shutdown and did not break the live lifecycle or replay comparison.
- Final account state: flat, zero pending orders.
- Reconciliation: `balanceDelta:-0.13`, `dealNet:-0.13`.
- Replay comparison status: `passed`.
- Replay parity flags:
  - `fullTickOrderJournalsByteExact:true`;
  - `barsOrdersTimestampNormalizedExact:true`;
  - `liveInitialProtectionMatchesCanonicalIntent:true`;
  - `liveAdjustedProtectionMatchesCapturedBrokerFill:true`;
  - `mt5SimulationUsesSameCanonicalIntent:true`;
  - `liveFillAndAdjustedProtectionMatchMt5Simulation:true`.

Generated final-sanity matrix status:

- `ema_cross` on EURUSD: sealed strict live-plus-replay.
- `rsi_reversion` on GBPUSD: sealed strict live-plus-replay.
- `atr_channel` on EURUSD: sealed strict live-plus-replay.
- `case_math` on GBPUSD: sealed strict live-plus-replay with bounded one-point live-vs-sim fill
  drift.

## 2026-08-12 Update: Final-Sanity CASE/Math Gate Sealed

First CASE/math attempt:

- Scenario:
  `/var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104017`.
- Result: pre-trade failure only.
- Failure:
  `gateway tick did not become fresh enough after daemon startup`.
- Evidence showed `GBPUSDm` tick age reached about `39310ms` after daemon startup.
- No strategy was deployed, no order was placed, and the account remained flat with zero pending
  orders.

Strict final-sanity CASE/math GBPUSD retry:

- Clean proving worktree:
  `/var/tmp/qkt-final-sanity-630224f7-20260812T104015Z`.
- Live scenario:
  `/var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104802`.
- Live result:
  `/var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104802/evidence/result.json`.
- Replay result with bounded drift comparator:
  `/var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104802-replay-bounded/result.json`.

What the strict CASE/math GBPUSD proof sealed:

- QKT commit under live proof: `630224f7c1e656d736b8be27524c8076aa68609d`.
- `qktDirty:false`.
- Strategy: `fs6302_case_0812104802_market_bracket`.
- Lifecycle: `single`, `strategyOwnedLifecycle:true`, `flattenVerified:false`.
- Timeframes: `1m` and `5m`.
- DSL/math surface covered in the live/replay loop: `round_to`, `lag`, `highest`, `lowest`, `CASE`,
  and `abs`.
- Live path: one real `0.01`-lot entry and one strategy-owned close on demo2 through the local
  MT5 gateway.
- Audit: two accepted events, two filled events, zero risk rejections.
- Transport: one `/order` post and one `/close_position` post.
- Golden capture: `ticks:33`, `warmupTicks:80`, `candles:12`, `fills:2`, `linkedPlacements:1`.
- Operational market-data result on the passing retry: `staleEvents:0`, `recoveredStaleEvents:0`.
- Final account state: flat, zero pending orders.
- Reconciliation: `balanceDelta:-0.08`, `dealNet:-0.08`.
- Replay comparison status: `passed`.
- Replay parity flags:
  - `fullTickOrderJournalsByteExact:true`;
  - `barsOrdersTimestampNormalizedExact:true`;
  - `liveInitialProtectionMatchesCanonicalIntent:true`;
  - `liveAdjustedProtectionMatchesCapturedBrokerFill:true`;
  - `mt5SimulationUsesSameCanonicalIntent:true`;
  - `liveFillAndAdjustedProtectionMatchMt5SimulationExact:false`;
  - `liveFillAndAdjustedProtectionWithinReviewedDrift:true`.
- Live-vs-MT5-sim fill drift was one GBPUSD point:
  - live SELL fill `1.35156`;
  - MT5-sim SELL fill `1.35157000`;
  - delta `-0.00001`, about `-1` point;
  - reviewed bound `80` points / `0.00080000`.

Comparator hardening committed after this finding:

- `b912c16b fix(scripts): bound golden replay execution drift`.
- [compare-golden-replay.sh](/home/dickson/Desktop/personal/qkt/scripts/live-validation/compare-golden-replay.sh)
  now fails if live-vs-MT5-sim fill drift exceeds the reviewed per-symbol bound.
- The result now exposes exact fill equality separately from bounded drift acceptance:
  `liveFillAndAdjustedProtectionMatchMt5SimulationExact` and
  `liveFillAndAdjustedProtectionWithinReviewedDrift`.
- The stale limitation text that still mentioned operator flatten for `single` lifecycle was removed;
  single lifecycle parity now explicitly requires strategy-owned close capture and replay.
- Verification:

```bash
bash -n scripts/live-validation/compare-golden-replay.sh
bash tests/scripts/prepare-live-validation-scenario-test.sh
bash scripts/live-validation/compare-golden-replay.sh --scenario /var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104802 --out /var/tmp/qkt-validation/final-sanity-clean-630224f7-case-gbpusd-0812104802-replay-bounded --cli /home/dickson/Desktop/personal/qkt/build/install/qkt/bin/qkt
git diff --check
```

Generated final-sanity matrix is now sealed:

- `ema_cross` on EURUSD: strict live-plus-replay sealed.
- `rsi_reversion` on GBPUSD: strict live-plus-replay sealed.
- `atr_channel` on EURUSD: strict live-plus-replay sealed.
- `case_math` on GBPUSD: strict live-plus-replay sealed.

This closes the quick final generated strategy sanity matrix. It does not by itself close the whole
go-live program: full pre-push checks, PR to `dev`, promotion, and downstream
`qkt-forge`/`qkt-insights` rollout are still separate gates.

## 2026-08-12 Update: Closeout Gate Recheck

Status recheck at `2026-08-12T11:03:07Z`:

- The generated final-sanity live-plus-replay matrix is sealed for `ema_cross`, `rsi_reversion`,
  `atr_channel`, and `case_math`.
- Higher-timeframe proof is already sealed for the fast validation pattern: M15, H1, and H4 use a
  `1m` primary execution stream plus warmed higher-timeframe secondary stream, with real daemon
  order-path evidence and replay comparison retained.
- Retained-risk proof is already sealed for the reviewed restored-state matrix and controlled
  margin-floor fixture. The focused script tests were rerun from this checkout and passed:

```bash
bash tests/scripts/prepare-stateful-risk-matrix-test.sh
bash tests/scripts/run-stateful-risk-containers-test.sh
```

No JVM heap, CPU, worker, or Docker resource restrictions were used for this recheck.

## 2026-08-12 Update: Final Pre-Push Gates Green

Final closeout checks from this branch passed after test-local log-budget hardening.

What changed during closeout:

- `GeneratedReentryParityTest` now runs its generated tick/bar/live-paper parity assertions with
  quiet parity log levels for the known high-volume pipeline/order/session/trade loggers.
- This is test-only noise control. It does not change production code, strategy behavior, broker
  behavior, risk gates, order generation, fills, indicators, DSL mapping, replay, or the live path.
- The first final `build` failure was only `:checkTestLogBudget` for generated reentry logs:
  `1274` nonblank lines and `218654` bytes, over the `1000` line and `131072` byte budget.
- The final build rerun passed the same behavioral suite plus the log-budget gate.

Final checks run:

```bash
./gradlew test --tests com.qkt.parity.GeneratedReentryParityTest -Pkotlin.compiler.execution.strategy=daemon
./gradlew checkTestLogBudget -Pkotlin.compiler.execution.strategy=daemon
./gradlew ktlintTestSourceSetFormat -Pkotlin.compiler.execution.strategy=daemon
./gradlew ktlintTestSourceSetCheck -Pkotlin.compiler.execution.strategy=daemon
./gradlew build -Pkotlin.compiler.execution.strategy=daemon
git diff --check
rg -n 'TODO|FIXME|XXX' src/ || true
git log --oneline origin/dev..HEAD | head -n 50
git status --short --branch
```

Important constraint honored:

- No JVM heap cap, worker cap, Docker cap, `--no-daemon`, or other resource restriction was used.
- The only Gradle property used was the repo-recommended Kotlin compiler daemon strategy:
  `-Pkotlin.compiler.execution.strategy=daemon`.

Final closeout status:

- Generated final-sanity live-plus-replay matrix: sealed.
- Higher-timeframe warmup/daemon validation: sealed for M15, H1, and H4 through the fast validation
  pattern.
- Retained-risk restored-state matrix and controlled margin-floor fixture: sealed and rechecked.
- Final full `build`: passed.
- Final full `test`: passed as part of the successful `build`; it also passed in the earlier
  explicit `checkTestLogBudget` run after the reentry log hardening.
- Final `checkTestLogBudget`: passed.
- Final ktlint checks: passed.
- Final `TODO|FIXME|XXX` scan under `src/`: clean.

Remaining after this branch closeout:

1. Open the PR from `test/exhaustive-live-parity` to `dev` with the retained evidence linked.
2. Merge/promote through `dev -> testing -> main` using the repo promotion rules.
3. After promotion, apply the proven `qkt`, `qkt-insights`, images/tags, and related runtime changes
   to `qkt-forge` on `sshbot2`, rerun the selected strategies there, then run portfolio backtests
   from the promoted/proven runtime.
4. Only after `qkt-forge` forward-test is clean, update bot1 `qkt-quantlive`.

## 2026-08-12 Update: PR Opened To Dev

PR opened at `2026-08-12T11:54:06Z`:

- PR: https://github.com/elitekaycy/qkt/pull/979
- Title: `test(parity): seal exhaustive live parity`
- Base: `dev`
- Head: `test/exhaustive-live-parity`
- Draft: no
- Initial merge state: `BLOCKED` because required checks were still in progress at creation time.
- Initial checks in progress:
  - `check / build`
  - `windows-ci / build-windows`
  - `GitGuardian Security Checks`

Next gate is to watch PR 979 checks to completion, fix any CI-only failure on this branch, and then
merge to `dev` when the PR is green and review requirements are satisfied.
