# Institutional readiness program - design

Date: 2026-06-25
Status: accepted for staged implementation
Issues: #558, #559, #560, #561, #562, #563; umbrella #142

## Context

qkt is already a serious pre-1.0 quant engine: deterministic replay, a shared backtest/live pipeline,
multi-source market data, risk controls, reconciliation, portfolio DSL support, parameter sweeps,
walk-forward tooling, reports, journals, and operational surfaces already exist. The institutional gap
is not "can qkt run a strategy"; it is "can qkt prove that a strategy, dataset, configuration,
execution model, accounting model, and deployment state are suitable for capital allocation."

The companion research system, qkt-forge, depends on qkt as a fast research kernel. It discovers
hypotheses, drafts DSL, runs gauntlets across smoke/grid/validation/walk-forward/significance/
robustness/portfolio gates, and fans out qkt backtests in parallel. This program must therefore improve
research quality without damaging the fast discovery loop.

## Goals

- Make backtest, sweep, walk-forward, report, paper, and live outputs carry enough evidence to explain
  what was run, on which data, with which execution/accounting assumptions, and under which governance
  state.
- Add strict institutional controls for data, execution, research governance, accounting, operations,
  and promotion.
- Keep today's quick research and CI workflows available by default.
- Preserve qkt-forge throughput: broad early-stage search remains cheap and parallel; strict checks run
  on survivors or when explicitly requested.
- Close the six institutional-readiness tickets only when their acceptance criteria are implemented,
  tested, documented, and wired into CLI/API/report surfaces.

## Non-goals

- Replacing qkt-forge. qkt should emit stronger evidence and enforcement primitives; qkt-forge remains
  free to orchestrate its own research funnel.
- Making strict institutional mode the default for every command immediately. The safe rollout is
  opt-in/stage-scoped first, then stricter production defaults after evidence and migration.
- Building a database service as the first governance backend. Stable JSON, JSONL, and content-addressed
  bundles are enough for V1.
- Simulating latency by sleeping. Backtests must stay deterministic and fast; latency is modeled by
  timestamp/event ordering and fill selection, never wall-clock waits.

## Institutional compatibility contract

These constraints are load-bearing. Any implementation that violates them is rejected even if it
appears to satisfy one issue locally.

1. Default fast path is unchanged unless the user selects strict features.
   - `qkt backtest` without institutional flags keeps current behavior and output compatibility.
   - `qkt sweep --parallelism N` remains supported and tested.
   - Existing qkt-forge G1/G2 fast search can continue using bars/ticks and paper/mt5-basic models.

2. Strict evidence is additive.
   - JSON fields are added under named objects such as `dataset`, `execution`, `experiment`,
     `accounting`, and `promotion`.
   - Existing top-level result fields remain backward-compatible.
   - Large per-trial metadata is summarized by hashes/IDs unless verbose output is requested.

3. Expensive validation is amortized.
   - Dataset hashes are computed by `qkt data snapshot` or `qkt data verify`, not inside every sweep
     combo.
   - Backtests reference a snapshot ID/hash and optionally verify the snapshot manifest cheaply.
   - Portfolio return streams and daily PnL are cacheable artifacts for late-stage book evaluation.

4. Parallelism remains first-class.
   - New registries and journals must not serialize the hot compute path.
   - Sweep workers may write per-run artifacts independently, then append one small immutable record.
   - Shared mutable state is isolated to process-level orchestration or append-only files with stable
     locking.

5. Production mode is strict.
   - `runtime.mode=production` fails closed when mandatory controls are absent.
   - Waivers are allowed only when explicit, reasoned, durable, and visible in status/reports.

6. Performance regressions are tested.
   - Add a benchmark/regression fixture for fast-mode `backtest` and `sweep --parallelism`.
   - Institutional metadata off must not materially slow G1/G2 research. The target budget is no more
     than 10 percent slowdown on fixed fixtures unless the spec for a slice explicitly revises it.

## Research-tier policy

qkt-forge maps naturally onto staged strictness:

| Forge stage | qkt mode | Required controls |
|---|---|---|
| G1 smoke | fast research | current fast execution, optional mutable-store warning |
| G2 grid | fast research | `--parallelism`, compact trial counts, no per-combo snapshot hashing |
| G3 validation | pinned research | dataset snapshot ID, explicit split metadata |
| G4 walk-forward | pinned research | trial count, fold provenance, selected metric disclosure |
| G5 significance | governed research | experiment record, multiple-comparison warning, seed provenance |
| G6 robustness | strict simulation | realistic/stress execution, cost stress, data quality policy |
| G7 portfolio | strict portfolio | account-currency accounting, book-level reports, promotion evidence |
| paper/live | governed deployment | preflight, journal, promotion gates, waivers if any |

This means institutional features increase confidence in survivors without turning every rejected idea
into a slow compliance job.

## Architecture overview

The program is six pillars plus one shared evidence layer.

```
strategy/config/data -> qkt command
  -> EvidenceEnvelope
       dataset snapshot / quality policy
       execution model disclosure
       experiment/run provenance
       accounting/cost assumptions
       promotion state
       qkt/build/strategy/config hashes
  -> engine/backtest/live path
  -> result.json + report.html + JSONL registry/journal
```

### Shared evidence layer

Add a small common model used by result generation, experiment records, promotion checks, and incident
bundles:

```kotlin
data class EvidenceEnvelope(
    val qktVersion: String,
    val command: List<String>,
    val strategyHash: String,
    val importedFileHashes: Map<String, String>,
    val configHash: String?,
    val dataset: DatasetEvidence?,
    val execution: ExecutionEvidence?,
    val experiment: ExperimentEvidence?,
    val accounting: AccountingEvidence?,
    val promotion: PromotionEvidence?,
)
```

The envelope is written into:

- `result.json`;
- report bundles;
- sweep and walk-forward summaries;
- experiment records;
- promotion records where applicable.

The envelope is not a runtime dependency for strategy logic. It records assumptions and identities
around a run.

## Pillar 1 - execution realism (#558)

### Problem

Current paper paths can fill too optimistically. `MT5BrokerSimulator` is better, but venue parity is
still incomplete for stop-distance validation, deterministic latency, rejection semantics, partial
fills, OCO edge cases, and adverse execution stress.

### Design

Introduce a deterministic execution simulation layer selected by command/config:

```kotlin
data class ExecutionSimulationConfig(
    val preset: ExecutionPreset,
    val latency: LatencyModel,
    val slippage: SlippageModel,
    val rejection: RejectionModel,
    val liquidity: LiquidityModel,
    val venueRules: VenueRuleSet,
    val costs: CostModelRef?,
    val seed: Long,
)
```

Presets:

- `paper-fast`: current deterministic paper behavior, explicitly disclosed as optimistic.
- `mt5-basic`: current MT5 simulator behavior.
- `mt5-realistic`: MT5 rules plus stop/freeze distance, deterministic latency, slippage, commission,
  rejection/retcode mapping, and ambiguous outcomes.
- `bybit-linear-realistic`: tick/lot sizing, reduce-only rules, maker/taker costs, funding hooks.
- `stress`: adverse slippage, latency spikes, intermittent rejects, and gap-through-stop fixtures.

Implemented first slice:

- `ExecutionSimulationConfig` exposes `paper-fast`, `mt5-basic`, `mt5-realistic`, and `stress`.
- `mt5-realistic` uses event-time latency, instrument slippage, MT5 sizing/price rules, and
  `tradeStopsLevel` validation.
- `stress` adds seeded random adverse slippage, deterministic intermittent rejection, and partial-fill
  slices for robustness runs.
- Execution evidence discloses fill source, latency, slippage, rejection, partial-fill, venue-rule,
  cost, and OCO assumptions in JSON/text/HTML reports.

Backtest latency is logical, not wall-clock: a signal at `t` can become an order event at `t+latency`
and fills are selected from the market state at that simulated timestamp. No sleeps are allowed.

### CLI/config

```bash
qkt backtest strategy.qkt --execution mt5-realistic --seed 42
qkt sweep strategy.qkt --execution paper-fast --parallelism 8
qkt backtest strategy.qkt --execution stress --slippage fixed-points:20
```

```yaml
execution:
  preset: mt5-realistic
  seed: 42
  latency: { model: fixed, millis: 250 }
  slippage: { model: volatility_scaled, atr_fraction: "0.05" }
```

### Reporting

Every result discloses execution model, fill price source, bid/ask availability, slippage, latency,
commission/fee model, partial-fill behavior, venue rule enforcement, and whether OCO is atomic or
engine-managed.

## Pillar 2 - data governance (#563)

### Problem

Manifests and local stores exist, but a backtest report cannot yet cite an immutable dataset identity
with hashes, provenance, quality stats, and fail-fast policy.

### Design

Add dataset snapshots and verification:

```bash
qkt data snapshot --symbol XAUUSD --from 2024-01-01 --to 2024-03-01 \
  --vendor dukascopy --out data/snapshots/xauusd-2024q1.json

qkt data verify --snapshot data/snapshots/xauusd-2024q1.json --strict

qkt backtest strategy.qkt --dataset data/snapshots/xauusd-2024q1.json
```

Snapshot contents:

- dataset ID and schema version;
- qkt version and data schema version;
- symbol mapping and vendor/source;
- range and calendar;
- file list, sizes, SHA-256 hashes;
- tick/bar counts, min/max timestamp, max gap;
- bid/ask/volume capability;
- conversion lineage such as `csv.gz -> bin`;
- bar-build lineage such as `ticks -> 1m -> 15m`;
- quality policy used at snapshot time.

`DataQualityPolicy`:

```yaml
data_quality:
  mode: warn | research | strict | live-parity
  max_gap_minutes: 30
  allow_empty_days: false
  require_bid_ask: true
  require_volume: false
  fail_on_corrupt_day: true
  fail_on_missing_day: true
```

Backtests pinned to snapshots cite the snapshot ID/hash. Backtests without snapshots say clearly that
the local store is mutable and not fully reproducible.

## Pillar 3 - research governance (#561)

### Problem

Sweeps and walk-forward tools can find good-looking results, but hidden trial count, mutable date
ranges, metric selection after the fact, and unrecorded splits create overfitting risk.

### Design

Add research plans and immutable experiment records:

```bash
qkt experiment run --plan research-plans/xau-breakout-v1.yaml
```

```yaml
name: xau-breakout-v1
objective: Evaluate XAUUSD breakout with fixed risk sizing
strategy: strategies/xau_breakout.qkt
dataset: data/snapshots/xauusd-2024q1.json
primary_metric: calmar
secondary_metrics: [max_drawdown, profit_factor, turnover]
splits:
  train: 2024-01-01/2024-02-01
  validation: 2024-02-01/2024-02-15
  test: 2024-02-15/2024-03-01
constraints:
  max_drawdown: "0.08"
  min_trades: 30
selection:
  method: validation_rank_then_test_once
  top_n: 3
grid:
  fast: [5, 10, 20, 50]
  slow: [50, 100, 200]
```

Records include qkt version, git SHA if available, strategy/import hashes, dataset snapshot hash,
parameter grid, trial count, seeds, metrics chosen before run, split ranges, command args, report
paths, selected candidate, and promotion rationale.

Sweep and walk-forward result JSON gains:

- total trial count;
- selected metric provenance;
- best-vs-median summary;
- parameter-neighborhood stability summary when grid topology is known;
- warning for large search spaces and unstable neighborhoods.

## Pillar 4 - production ops and auditability (#560)

### Problem

Live controls, journals, notifications, insights, and state persistence exist, but production safety is
not yet a mandatory contract. CI output can also become noisy enough to hide failures.

### Design

Add runtime mode:

```yaml
runtime:
  mode: dev | paper | production
  waivers:
    alerts:
      reason: "micro account smoke test"
      expires: 2026-07-01T00:00:00Z
```

In `production`, startup/preflight fails without:

- append-only JSONL journal;
- state persistence;
- risk limits;
- broker reconciliation capability;
- alert channel or waiver;
- data feed health gate;
- version/build metadata;
- state backup location or waiver.

Add:

```bash
qkt preflight strategy.qkt --config qkt.config.yaml --production
qkt incident collect --strategy xau --since 24h --out incident-xau.zip
```

Journal must include strategy lifecycle, deploy config hash, strategy hash, signals, risk decisions,
order submit/accept/reject/fill/partial/cancel/modify, operator halt/resume/flatten, broker
reconciliation, engine faults, notification failures, and waivers.

CI/log hygiene:

- throttle repeated schedule warnings;
- aggregate historical missed schedule counts;
- add log budget assertions for test suites or noisy fixtures;
- keep important failures visible in CI.

## Pillar 5 - accounting, currency, and costs (#562)

### Problem

qkt correctly refuses unsafe quote-currency cases today, but institutional portfolio research requires
native and account-currency PnL, FX conversion, cost timing, and risk decisions based on account truth.

### Design

Introduce currency-aware accounting primitives:

```kotlin
data class AccountCurrency(val code: String)
data class MoneyAmount(val amount: BigDecimal, val currency: String)
data class FxConversion(
    val from: String,
    val to: String,
    val rate: BigDecimal,
    val timestamp: Long,
    val source: String,
)

data class VenueCost(
    val kind: CostKind,
    val amount: MoneyAmount,
    val timestamp: Long,
)

fun interface FxRateProvider {
    fun rate(qktSymbol: String, timestamp: Long): BigDecimal?
}
```

Rules:

- realized PnL converts at fill timestamp or broker-reported conversion timestamp;
- unrealized PnL converts at mark timestamp;
- fees, swaps, funding, borrow, taxes, and exchange fees convert when charged;
- pre-trade notional, book exposure, and concentration checks compare account-currency notional;
- reports show native and account-currency values;
- strict/production mode fails on missing conversion;
- existing `QuoteCurrencyGuard` stays active until a symbol family is fully supported.
- ordinary USD/stablecoin-quoted runs use identity conversion and keep existing defaults.
- configured FX helper series are appended to the replay feed once, so sweeps keep the shared decode path
  instead of loading conversion data per parameter combo.

Config:

```yaml
account:
  currency: USD

fx_conversion:
  source: local
  missing_policy: fail
  symbols:
    JPYUSD: BACKTEST:JPYUSD
    CHFUSD: BACKTEST:CHFUSD

costs:
  commission:
    model: per_lot
    amount: "3.50"
    currency: USD
  funding:
    source: local
```

Staged rollout starts with one family, such as JPY quote-currency conversion, before broader multi-asset
support.

## Pillar 6 - promotion and deployment governance (#559)

### Problem

qkt can technically deploy a strategy, but institutional workflows require staged promotion,
evidence, approvals, paper/live comparison, and blockers.

### Design

Promotion states:

- `draft`
- `research`
- `candidate`
- `paper`
- `shadow-live`
- `small-capital`
- `production`
- `retired`

Promotion record:

```yaml
strategy: xau_breakout
version_hash: sha256:...
config_hash: sha256:...
state: paper
evidence:
  research_report: reports/xau/research-001/result.json
  walk_forward_report: reports/xau/wf-001/result.json
  execution_report: reports/xau/mt5-realistic/result.json
  dataset_snapshot: data/snapshots/xauusd-2024q1.json
  paper_session: sessions/xau-paper-2026-06
approvals:
  - actor: elitekaycy
    timestamp: 2026-06-25T18:00:00Z
    decision: approve_paper
```

Deploy gates:

```yaml
promotion:
  enforce: true
  production_requires:
    dataset_snapshot: true
    realistic_execution: true
    walk_forward: true
    paper_days: 20
    paper_min_trades: 50
    max_paper_slippage_bps: 15
    approval: true
```

Paper/live validation records expected vs actual fill, rejection rate, average/p95 slippage, missed
fills, feed/broker drift, latency distribution, drawdown vs expected envelope, and rule firing
frequency vs backtest.

Waivers require a reason, are journaled, and appear in status and reports.

## Config surface

The institutional sections are optional unless `runtime.mode=production`, `promotion.enforce=true`, or
the user selects a strict command flag.

```yaml
runtime:
  mode: dev

institutional:
  evidence_dir: .qkt/evidence
  registry_dir: .qkt/experiments
  default_profile: fast | research | strict | production

data_quality:
  mode: warn

execution:
  preset: paper-fast

experiment:
  warn_large_grid_over: 200
  record_trials: summary

account:
  currency: USD

promotion:
  enforce: false
```

`docs/reference/config-schema.md` must eventually document every field, default, strictness level, and
whether it is required in production.

## Command surface

New or extended commands:

- `qkt data snapshot`
- `qkt data verify`
- `qkt experiment run`
- `qkt promotion status`
- `qkt promotion approve`
- `qkt promotion waive`
- `qkt preflight`
- `qkt incident collect`
- existing `backtest`, `sweep`, `walkforward`, `deploy`, and `status --deep` gain evidence fields or
  gate checks.

## Reporting contract

`result.json` should eventually include:

```json
{
  "evidence": {
    "qktVersion": "...",
    "strategyHash": "sha256:...",
    "configHash": "sha256:...",
    "dataset": {"id": "...", "hash": "...", "qualityPolicy": "strict"},
    "execution": {"preset": "mt5-realistic", "seed": 42},
    "experiment": {"id": "...", "trialCount": 480, "primaryMetric": "calmar"},
    "accounting": {"accountCurrency": "USD", "missingPolicy": "fail"},
    "promotion": {"state": "candidate", "eligibleForProduction": false}
  }
}
```

Reports should render the same information in human-readable form and warn clearly when a run is
optimistic, mutable, unpinned, or not production-grade.

## Testing strategy

- Unit tests for each new pure model: execution components, dataset snapshot hashing, quality policy,
  research plan parser, promotion gate evaluator, accounting conversion, cost typing, preflight checks.
- Integration tests for CLI commands and report JSON fields.
- Golden deterministic tests for fixed-seed execution realism.
- Failure fixture tests: gap-through-stop, invalid stop distance, OCO race, partial fill, broker
  rejection, ambiguous send outcome, missing dataset file, hash mismatch, missing FX conversion,
  unpromoted production deploy.
- Parallelism tests: `sweep --parallelism` still fans out and records bounded evidence.
- Performance guard: institutional metadata disabled should keep fixed fast-mode backtest/sweep within
  the accepted regression budget.
- CI/log hygiene test: schedule-miss warnings are aggregated, not emitted per historical fire.

## Build order

1. Shared evidence primitives and compatibility tests.
2. Dataset snapshots and verification (#563) because every other pillar references dataset identity.
3. Fast-mode performance/log guardrails (#560 subset) to protect the research kernel before deeper
   features land.
4. Execution model selection and disclosure (#558), then realistic/stress fixtures.
5. Experiment plans and immutable records (#561), integrating sweep/walk-forward metadata.
6. Production preflight, mandatory journal policy, incident bundle (#560 remaining).
7. Currency/accounting/cost model (#562), one supported currency family first.
8. Promotion records, deploy gates, paper/live validation, waivers (#559).
9. Documentation: full qkt research workflow README and complete config reference README.

## Closure criteria

Do not close an institutional-readiness issue until:

- every acceptance criterion in the GitHub issue has an implemented code path;
- CLI/API/report surfaces are documented;
- tests cover success, failure, and strict/production behavior;
- default-off/backward-compatibility is verified where promised;
- qkt-forge fast-path compatibility is preserved for relevant changes;
- the issue has a final comment with evidence: commit/PR, tests run, docs added, and any remaining
  intentionally deferred follow-up.
