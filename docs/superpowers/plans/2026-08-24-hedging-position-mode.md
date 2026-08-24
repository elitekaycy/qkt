# Hedging position mode — implementation plan

> Spec: `docs/superpowers/specs/2026-08-24-hedging-position-mode-design.md` (#1071).
> TDD per task; every task ends in a commit. Steps use `- [ ]`.

## Task 1: PositionMode knob through execution config and the sims

**Files:** `backtest/PositionMode.kt` (new), `backtest/ExecutionSimulation.kt`,
`cli/BacktestContext.kt`, `cli/ScenarioFile.kt`, `broker/PaperBroker.kt`,
`broker/MT5BrokerSimulator.kt`, `research/ReplayEngine.kt` (broker factory ~:299).

- [ ] `enum class PositionMode { NETTING, HEDGING }`.
- [ ] `ExecutionSimulationConfig.positionMode` — library default NETTING (embedded
  `Backtest` callers keep semantics); CLI default HEDGING (production venue model).
- [ ] CLI `--position-mode netting|hedging` (backtest + sweep) and config key
  `execution.position_mode`; unknown values error with the valid list. (Scenario-file
  key dropped: scenarios inherit the run's `executionConfig`, which already carries
  the mode — `BacktestContext.scenarioBacktest`.)
- [ ] `PaperBroker` + `MT5BrokerSimulator` take `positionMode`; both override
  `positionAccountingMode()` accordingly (paper loses its hardcoded NETTING; mt5-sim
  loses its UNKNOWN inconsistency with MULTI_POSITION_PER_SYMBOL).
- [ ] ReplayEngine broker factory passes `executionConfig.positionMode`.
- **Tests:** config/CLI/scenario parse (defaults + explicit + error);
  `MT5BrokerSimulatorTest` + a paper sibling pin `positionAccountingMode()` per mode.

## Task 2: hedging entry routing in TradingPipeline

**Files:** `app/TradingPipeline.kt` (emit path ~:374, ctor), `app/LiveSession.kt`,
`backtest/Backtest*` wiring, `research/ReplayEngine.kt`.

- [ ] Pipeline ctor gains `positionMode: () -> PositionAccountingMode` (default
  `{ UNKNOWN }` = netting-compatible). Backtest wiring resolves from
  `executionConfig.positionMode`; live resolves from `broker.positionAccountingMode()`
  so a hedging venue books hedging legs in the engine's own tracker too.
- [ ] New `registerHedgingEntryLegs(strategyId, request)` called next to
  `registerOcoEntryLegs` when mode == HEDGING:
  - `Bracket` → `registerIndependentOpen(strategyId, entry.id, bracket.id)` +
    `registerStackClose(strategyId, "${bracket.id}-tp"/"-sl", bracket.id)`.
  - Plain `Market`/`Limit`/`Stop` entry (no `closesTicket`/`closesLegId`, not an exit
    id) → `registerIndependentOpen(strategyId, request.id, request.id)`.
  - `StandaloneOCO`/stack/close requests: untouched (already leg-routed).
- **Tests:** pipeline-level — emit a bracket and a plain market under HEDGING, assert
  tracker leg maps route the fills to INDEPENDENT legs (model on
  `StrategyPositionTrackerStackTest` fill helper); UNKNOWN/NETTING leaves routing
  unchanged (existing netting tests stay green untouched).

## Task 3: leg-linkage exemption for the #1070 sweep and tripwire

**Files:** `app/OrderManager.kt`, `positions/StrategyPositionTracker.kt`,
`app/TradingPipeline.kt` (wiring).

- [ ] Tracker: `fun hasRegisteredClose(strategyId, clientOrderId): Boolean` over
  `pendingStackCloses` (key `"$strategyId|$clientOrderId"`).
- [ ] OrderManager ctor: optional `hasLegLinkage: (strategyId, clientOrderId) -> Boolean`
  (default `{ _, _ -> false }`). `retireStaleProtectiveExits` skips linked exits;
  `detectExitIncreasedExposure` returns early for linked exits (a short leg's BUY stop
  while net-long is legitimate under hedging).
- [ ] Pipeline wires the predicate from the tracker.
- **Tests:** extend `OrderManagerReduceOnlyExitTest` — linked exit on its own side stays
  silent and un-swept; unlinked behavior byte-identical to today.

## Task 4: end-to-end hedging backtest pins

**Files:** `src/test/kotlin/com/qkt/backtest/HedgingModeBacktestTest.kt` (new).

- [ ] Same synthetic tape as `StaleBracketExitAfterReversalTest`, run with
  `--position-mode hedging` semantics: BUY bracket, then SELL bracket while long.
  Assert: two coexisting INDEPENDENT legs (`POSITION.count == 2`, net = short-long
  delta), the old long's `-sl` closes ONLY the long leg (per-leg realized against that
  leg's entry), no fill ever increases absolute exposure without its own bracket, and
  the book ends flat with both legs individually closed.
- [ ] Netting run of the same tape still produces the #1069-fixed reversal behavior
  (regression pair).
- **Tests:** the file is the test.

## Task 5: leg-aware reporting

**Files:** `backtest/FillState.kt`, `backtest/TradeRecord.kt`, `events`
(`FillAccountedEvent`), `positions/StrategyPositionTracker.kt` (apply* return),
`app/TradingPipeline.kt`, `research/ReplayEngine.kt`,
`backtest/report/TradeAuditSummary.kt`, `backtest/report/BacktestReportWriter.kt`.

- [ ] Tracker `applyFill`/`applyPartialFill` return `FillApplication(realized, legId,
  legAction: OPENED|CLOSED|NETTED)` (call sites updated; NETTED preserves today's
  labels).
- [ ] Thread `legId`/`legAction` through FillAccountedEvent → FillState → TradeRecord;
  trades.csv appends `legId,legAction`.
- [ ] `TradeAuditSummary.positionEffect`: when `legAction` present and not NETTED,
  label from the leg (OPEN_/CLOSE_ + leg side) instead of net before/after.
- **Tests:** `BacktestReportWriterTest` header/row pin; audit-summary unit rows for a
  hedged short-open-while-net-long (labels OPEN_SHORT, not CLOSE_LONG).

## Task 6: venue margin-mode assertion + live wiring

**Files:** `broker/mt5/MT5BrokerProfile.kt`, `MT5BrokerProfileLoader.kt`,
`MT5AccountVerifier.kt`, `cli/PreflightCommand.kt`, `docs/reference/config-schema.md`,
config templates.

- [ ] `expectedMarginMode: netting|hedging|null` profile field (env
  `QKT_EXPECTED_MARGIN_MODE`); verifier hard-requires it against
  `MT5AccountInfo.marginMode` when set; preflight nags when absent.
- [ ] LiveSession resolves the pipeline `positionMode` supplier from the broker.
- **Tests:** loader parse, verifier accept/reject, preflight nag presence.

## Task 7: docs + divergence catalog

- [ ] `docs/parity/backtest-vs-live.md`: netting-vs-hedging divergence entry closed by
  construction (mode is venue-derived), account-level netting approximation documented
  (spec §6).
- [ ] `docs/reference/config-schema.md` + example configs: `--position-mode`,
  `position_mode`, `expected_margin_mode`.
- [ ] KDoc audited across new/changed public surface.

## Task 8: verification wave (outside this repo's tests)

- [ ] Full `./gradlew build` green (modulo the two pre-existing env-dependent classes).
- [ ] PR → dev; testing auto-promotes; pull `qkt:edge` on the research host; reseed the
  five live strategies through the gate funnel under hedging mode.
- [ ] Live-parity wave attestation on the final testing SHA (canary bracket + parity
  suite + insights attribution → assemble → paper-soak → promote-to-main), then the
  staged host rollout with image-revision verification.
