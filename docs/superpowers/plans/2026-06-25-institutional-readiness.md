# Institutional readiness implementation plan

> **For agentic workers:** implement task-by-task and update checkboxes as work lands. Do not close
> GitHub issues from intent or partial progress; close only after the linked acceptance criteria are
> implemented, tested, documented, and verified.

**Goal:** Complete the institutional-readiness tickets #558, #559, #560, #561, #562, and #563 without
damaging qkt's fast research kernel or qkt-forge's parallel gauntlet workflow.

**Spec:** `docs/superpowers/specs/2026-06-25-institutional-readiness-design.md`.

**Core contract:** strict institutional evidence is opt-in or stage-scoped until production mode. Fast
backtest/sweep behavior and `qkt sweep --parallelism` remain compatible and tested.

## Issue map

- #558: execution realism and venue-parity simulation.
- #559: live promotion gates and deployment governance.
- #560: production ops, auditability, and CI log hygiene.
- #561: experiment registry and research governance.
- #562: account-currency PnL, costs, and portfolio accounting parity.
- #563: data governance, immutable snapshots, and strict quality gates.

## Global constraints

- Use JDK 21, Kotlin, Gradle, JUnit 5, and the repo's existing patterns.
- Preserve existing CLI defaults unless a task explicitly changes a production-mode default.
- Keep result JSON changes additive.
- Do not hash every data file inside every sweep combo. Snapshot/verify once, then reference IDs.
- Do not simulate latency with sleeps. Backtest latency is deterministic event-time behavior.
- Keep append-only records compact and stable. Avoid making the hot compute path wait on registry
  writes beyond a small final append.
- Run focused tests for each slice and broader tests before closing any issue.
- Add or update docs in the same slice as user-facing behavior.

## Task 0: Baseline audit and guardrails

- [x] Create institutional-readiness umbrella spec.
- [x] Create this implementation plan.
- [x] Record current open issue list and acceptance criteria in the final verification notes.
- [ ] Add a focused fast-path performance fixture for current `backtest` and `sweep --parallelism`.
- [ ] Add a regression assertion or documented benchmark command that proves metadata-off fast mode is
  within the accepted slowdown budget.
- [x] Add/confirm a test for `sweep --parallelism` so later registry/report changes cannot serialize
  the sweep path by accident.
- [x] Add a `SweepReplay` fast-path regression proving shared market data is decoded once per worker,
  not once per combo.

## Task 1: Shared evidence primitives

Purpose: create the additive evidence surface used by all six issues.

Files to inspect first:

- `src/main/kotlin/com/qkt/backtest/ReportBuilder.kt`
- `src/main/kotlin/com/qkt/backtest/BacktestResult.kt`
- `src/main/kotlin/com/qkt/backtest/sweep/*`
- `src/main/kotlin/com/qkt/cli/*`
- report HTML writers under `src/main/kotlin/com/qkt/backtest/report`

Implementation:

- [x] Add `EvidenceEnvelope` and sub-records for dataset, execution, experiment, accounting, and
  promotion evidence.
- [x] Compute qkt version/build info from existing version surfaces.
- [x] Compute stable SHA-256 hashes for strategy files and imported files.
- [x] Thread optional evidence into backtest result JSON without changing existing field names.
- [x] Render a compact evidence section in report HTML.
- [x] Add tests for stable hashing, absent evidence, and additive JSON serialization.

Done when:

- [x] Existing tests that parse result JSON continue to pass.
- [x] A normal backtest shows no strict behavior change.
- [x] Report/result include evidence when supplied.

## Task 2: Data governance and immutable snapshots (#563)

Purpose: make datasets reproducible and strict-quality capable without slowing sweeps.

Files to inspect first:

- `src/main/kotlin/com/qkt/marketdata/store/DefaultDataStore.kt`
- `src/main/kotlin/com/qkt/marketdata/store/Manifest*.kt`
- `src/main/kotlin/com/qkt/marketdata/store/DayFileIntegrity.kt`
- `src/main/kotlin/com/qkt/marketdata/store/TickCompletenessValidator.kt`
- `src/main/kotlin/com/qkt/cli/DataCommand.kt`
- `src/main/kotlin/com/qkt/backtest/BacktestContext.kt`

Implementation:

- [x] Add deterministic `DatasetSnapshot` model with schema version, files, sizes, SHA-256 hashes,
  counts, min/max timestamp, max gap, capabilities, vendor/source, range, and lineage.
- [x] Add `DataQualityPolicy` with `warn`, `research`, `strict`, and `live-parity` modes.
- [x] Add `qkt data snapshot`.
- [x] Add `qkt data verify --snapshot ... --strict`.
- [x] Let `qkt backtest` accept `--dataset <snapshot.json>` and cite dataset identity in evidence.
- [x] Make backtests able to fail instead of warn on configured quality violations.
- [x] Preserve dataset identity through sweep and walk-forward summaries.
- [x] Make pinned backtests fail when strategies read bid/ask/spread or volume but the snapshot lacks
  those fields.
- [x] Add report warnings for mutable local-store runs.

Tests:

- [x] Snapshot JSON is deterministic for a fixed fixture.
- [x] Verify fails on missing, changed, corrupt, empty, or over-gap files.
- [x] Backtest pinned to a snapshot emits dataset evidence.
- [x] Sweep/walk-forward preserve dataset identity.
- [x] Strategy field usage requires bid/ask or volume-bearing data for pinned snapshots.
- [x] No per-combo hash recomputation in sweep fast path. `SweepCommand` builds and verifies the
  pinned dataset once through `BacktestContext`, computes the pinned dataset JSON once, and
  `SweepReplay` keeps shared feed decode at once per worker rather than once per combo.

Close #563 only after every GitHub acceptance criterion is proven.

## Task 3: Production ops, auditability, and log hygiene (#560)

Purpose: make production mode fail closed and keep incident evidence durable.

Files to inspect first:

- live session and daemon code under `src/main/kotlin/com/qkt/app`
- broker/order journal classes under `src/main/kotlin/com/qkt/broker` and `src/main/kotlin/com/qkt/persistence`
- notification code under `src/main/kotlin/com/qkt/notify`
- schedule runner/logging code under `src/main/kotlin/com/qkt/dsl/compile` and `src/main/kotlin/com/qkt/app`
- CLI command registration under `src/main/kotlin/com/qkt/cli`

Implementation:

- [x] Add `runtime.mode: dev | paper | production` to typed config.
- [x] Add `qkt preflight ... --production` with broker, state, journal, risk, data, alerts, and symbol
  metadata checks.
- [x] Make production live startup refuse missing append-only journal.
- [x] Ensure journal records risk decisions and operator actions.
- [x] Journal notification failures durably.
- [x] Aggregate/throttle schedule-miss warnings.
- [x] Add a CI/test log budget guard for the formerly noisy schedule fixture.
- [x] Add `qkt incident collect`.

Tests:

- [x] Production mode refuses startup without journal.
- [x] Preflight reports pass/fail checks with non-zero exit on failure.
- [x] Notification failure creates a journal event.
- [x] Schedule warning aggregation emits one bounded summary instead of many lines.
- [x] Incident bundle includes journal slice, logs/state when available, strategy/config hashes, and
  qkt version info.

Close #560 only after every GitHub acceptance criterion is proven.

## Task 4: Execution realism and venue-parity simulation (#558)

Purpose: make grading execution assumptions explicit and stricter without slowing discovery.

Files to inspect first:

- `src/main/kotlin/com/qkt/broker/PaperBroker.kt`
- `src/main/kotlin/com/qkt/broker/MT5BrokerSimulator.kt`
- order manager and broker event models under `src/main/kotlin/com/qkt/app` and `src/main/kotlin/com/qkt/broker`
- `src/main/kotlin/com/qkt/backtest/BacktestContext.kt`
- `src/main/kotlin/com/qkt/backtest/Backtest.kt`

Implementation:

- [x] Add execution simulation config and model interfaces.
- [x] Add presets `paper-fast`, `mt5-basic`, `mt5-realistic`, and `stress`.
- [x] Wire CLI/config selection for backtest, sweep, and walk-forward.
- [x] Preserve current default as `paper-fast` or existing broker default, with clear report warning.
- [x] Implement deterministic latency by event-time ordering.
- [x] Implement stop-distance/freeze-level validation for MT5 realistic mode.
- [x] Implement deterministic slippage, rejection, and partial-fill components; retain existing MT5
  ambiguous-send outcome coverage.
- [x] Serialize execution evidence into result JSON and report HTML.

Tests:

- [x] Fixed-seed realistic execution is deterministic.
- [x] Gap-through-stop fixture fills at adverse price.
- [x] Invalid stop distance rejects.
- [x] OCO race behavior is deterministic and disclosed.
- [x] Partial fill and broker rejection fixtures are covered.
- [x] Sweep fast path can still use `paper-fast` with `--parallelism`.

Close #558 only after every GitHub acceptance criterion is proven.

## Task 5: Experiment registry and research governance (#561)

Purpose: make qkt research reproducible and overfit-aware while remaining useful to qkt-forge.

Files to inspect first:

- `src/main/kotlin/com/qkt/backtest/sweep/*`
- `src/main/kotlin/com/qkt/backtest/walkforward/*`
- `src/main/kotlin/com/qkt/backtest/report/*`
- `src/main/kotlin/com/qkt/cli/*`

Implementation:

- [x] Add research plan YAML model: dataset, splits, metrics, constraints, parameter grid, selection.
- [x] Add `qkt experiment run --plan`.
- [x] Create immutable JSONL/content-addressed experiment records.
- [x] Include qkt version, strategy hash, imports, dataset hash, params, seed, command, reports, and
  promotion rationale.
- [x] Add train/validation/test period disclosure to reports.
- [x] Add sweep/walk-forward trial count and selected metric provenance.
- [x] Add large-search and unstable-neighborhood warnings.
- [x] Allow selected candidate to be marked with promotion state and rationale.

Tests:

- [x] Plan parser accepts valid plans and rejects invalid split/grid definitions.
- [x] Experiment run writes an immutable record.
- [x] Sweep output includes trial count and metric provenance.
- [x] Reports identify train/validation/test periods.
- [x] Large grid warning triggers above configured threshold.
- [x] qkt-forge can still call ordinary backtest/sweep without using the experiment command.

Close #561 only after every GitHub acceptance criterion is proven.

## Task 6: Account-currency PnL, costs, and portfolio accounting parity (#562)

Purpose: support institutional multi-currency accounting while preserving current safety guards.

Files to inspect first:

- `src/main/kotlin/com/qkt/pnl/*`
- `src/main/kotlin/com/qkt/risk/*`
- `src/main/kotlin/com/qkt/instrument/*`
- `src/main/kotlin/com/qkt/backtest/*`
- portfolio/book-risk code and tests

Implementation:

- [x] Add `AccountCurrency`, `MoneyAmount`, `FxConversion`, and typed `VenueCost`.
- [x] Add historical/live FX conversion provider interface.
- [x] Convert realized PnL at fill timestamp.
- [x] Convert unrealized PnL at mark timestamp.
- [x] Represent fees, swaps, funding, borrow, exchange fees, spread cost, and taxes as typed costs.
- [x] Use account-currency values in risk controls.
- [x] Convert pre-trade notional, book exposure, and concentration checks through the shared
  `AccountingEngine`.
- [x] Fail on missing conversion in strict/production mode.
- [x] Keep `QuoteCurrencyGuard` active for unsupported symbols.
- [x] Show conversion source, rate timestamp, native PnL, and account-currency PnL in reports.

Tests:

- [x] USDJPY-style realized PnL converts correctly.
- [x] Unrealized PnL updates with mark-time FX conversion.
- [x] Fees/costs convert at their own timestamps.
- [x] Risk controls consume account-currency values.
- [x] USDJPY-style order-notional and book-exposure caps compare USD account values, not raw JPY.
- [x] Missing conversion fails in strict mode.
- [x] Existing USD-quoted behavior remains unchanged.

Close #562 only after every GitHub acceptance criterion is proven.

## Task 7: Live promotion gates and deployment governance (#559)

Purpose: prevent accidental jumps from research to production capital.

Files to inspect first:

- deploy/daemon/status CLI code
- runtime config loading
- live session startup
- journal/state persistence code
- evidence and experiment records from earlier tasks

Implementation:

- [x] Add promotion record model with states `draft`, `research`, `candidate`, `paper`,
  `shadow-live`, `small-capital`, `production`, and `retired`.
- [x] Add `qkt promotion status`.
- [x] Add `qkt promotion approve`.
- [x] Add `qkt promotion waive` with required reason and optional expiry.
- [x] Add deploy gate evaluator with dataset snapshot, realistic execution, walk-forward,
  paper days/trades, slippage, strategy hash, and approval checks.
- [ ] Extend promotion gates to config-hash, risk-config, and incident-history checks.
- [x] Production deploy checks strategy hash against promoted artifact.
- [x] Collect paper/live validation metrics.
- [x] Show promotion state and missing gates in `qkt status --deep`.
- [x] Refuse production deploy for unpromoted strategies unless a waiver is supplied.

Tests:

- [x] Promotion state can be recorded and queried.
- [x] Production deploy refuses unpromoted strategy.
- [x] Strategy hash drift blocks deploy.
- [x] Waiver requires a reason and is journaled.
- [x] Paper/live validation summary is recorded.
- [x] `status --deep` lists missing gates.

Close #559 only after every GitHub acceptance criterion is proven.

## Task 8: Documentation deliverables requested by the user

Purpose: make the final system understandable without reverse-engineering code.

- [x] Add a full research workflow README that explains using qkt end-to-end:
  - strategy creation;
  - data acquisition;
  - dataset snapshots;
  - fast bar/tick research;
  - sweeps;
  - validation;
  - walk-forward;
  - significance checks;
  - robustness and execution realism;
  - portfolio/book evaluation;
  - experiment registry;
  - promotion to paper/live;
  - qkt-forge integration assumptions;
  - troubleshooting and expected artifacts.
- [x] Add a complete config README/reference that explains:
  - every config section;
  - where it is used;
  - default values;
  - optional vs strict vs production-required fields;
  - examples for research, paper, production, qkt-forge, and portfolio workflows.
- [x] Link the READMEs from existing docs indexes.
- [x] Ensure examples match current CLI/config names after implementation.

## Task 9: Final verification and issue closure

- [x] Run focused test classes for every pillar.
- [x] Run the full relevant Gradle suite or document any unavailable external dependency.
- [x] Run CLI smoke tests for data snapshot/verify, experiment run, preflight, promotion status, and
  representative backtest/sweep.
- [x] Run or document the fast-path performance benchmark.
- [x] Inspect generated `result.json` and report HTML for evidence sections.
- [x] Verify qkt-forge compatibility assumptions against `../qkt-forge` runner/config.
- [x] Update each GitHub issue with evidence and close only when fully satisfied.
- [ ] Confirm `git status` contains only intentional changes. Not checked: the worktree still includes
  a pre-existing untracked single-backslash file containing unrelated allocation-audit notes plus the
  broad institutional-readiness change set; left untouched for operator review.
