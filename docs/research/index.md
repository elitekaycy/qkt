# Research workflow

This page is the end-to-end path for taking a qkt idea from a `.qkt` file to a governed paper or live candidate without giving up fast research loops. The intended split is simple: qkt is the deterministic research and execution kernel, while qkt-forge can sit above it as an autonomous discovery, gating, and dashboard layer.

## Operating model

The workflow has four stages:

1. **Explore fast.** Use mutable local data, `paper-fast` fills, bars, and parallel sweeps to reject weak ideas cheaply.
2. **Pin evidence.** Snapshot the dataset, record strategy/config hashes, and move promising candidates into immutable experiment records.
3. **Grade honestly.** Re-run with realistic execution, walk-forward validation, cost stress, portfolio/book accounting, and qkt-forge statistical gates when available.
4. **Promote deliberately.** Record paper/live validation metrics, require approval, and let production deploy refuse unpromoted or hash-drifted strategies.

Fast commands stay fast because strict institutional evidence is opt-in for research and enforced only when you ask for it or run production mode.

## 1. Create and parse the strategy

Start with a strategy that parses cleanly before spending any data or sweep time:

```bash
qkt parse strategies/ema_cross.qkt
```

Use `PARAM` or normal `LET` names for values you expect to sweep. Keep market selection separate from parameter tuning unless market selection is truly part of the thesis.

```qkt
STRATEGY ema_cross VERSION 1

SYMBOLS
  gold = BACKTEST:XAUUSD EVERY 15m WARMUP 80 BARS

PARAM fast = 12
PARAM slow = 48
PARAM size = 0.10

RULES
  WHEN ema(gold.close, fast) CROSSES ABOVE ema(gold.close, slow)
   AND POSITION.gold = 0
  THEN BUY gold SIZING size
       BRACKET { STOP_LOSS BY 1.5, TAKE_PROFIT BY 3.0 }
```

## 2. Acquire and verify data

A normal backtest can auto-fetch Dukascopy ticks for supported bare symbols:

```bash
qkt backtest strategies/ema_cross.qkt --from 2021-01-01 --to 2024-01-01 --json
```

For broker-exact bars, prefetch from a configured broker profile:

```bash
qkt fetch EXNESS:XAUUSD --tf 5m --from 2021-01-01 --to 2024-01-01
```

Check the tick store before treating any result as research evidence:

```bash
qkt data verify XAUUSD
```

For repeated large runs, convert cached tick CSV files to binary and optionally build bar caches:

```bash
qkt data convert XAUUSD --from 2021-01-01 --to 2024-01-01
qkt data build-bars XAUUSD --tf 1m --from 2021-01-01 --to 2024-01-01
```

`--bars` is a research tier. It is useful for broad scans because it avoids tick decode, but it uses bar-approximated intrabar fills and should not be used as the final grading run.

## 3. Pin a dataset snapshot

Once an idea is worth preserving, freeze the exact data identity:

```bash
qkt data snapshot XAUUSD \
  --from 2021-01-01 \
  --to 2024-01-01 \
  --vendor dukascopy \
  --quality strict \
  --out research/datasets/xauusd-2021-2024.snapshot.json

qkt data verify --snapshot research/datasets/xauusd-2021-2024.snapshot.json --strict
```

A pinned backtest verifies the snapshot and records dataset evidence in result JSON and reports:

```bash
qkt backtest strategies/ema_cross.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --dataset research/datasets/xauusd-2021-2024.snapshot.json \
  --no-fetch --json
```

Pinned datasets also enforce field capability checks. If a strategy reads `.bid`, `.ask`, `.spread`, or `.volume`, the snapshot must contain those fields for every tick that matters.

## 4. Run the cheap baseline

Use `paper-fast` or the default paper broker for quick screening:

```bash
qkt backtest strategies/ema_cross.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --dataset research/datasets/xauusd-2021-2024.snapshot.json \
  --execution paper-fast \
  --json
```

Read these as optimistic numbers. The report will say when fills ignore spread, slippage, latency, rejections, queue position, or partial fills. A strategy that only works in `paper-fast` is not a live candidate.

## 5. Sweep parameters without serializing research

Run broad grids with `qkt sweep`. Use `--parallelism` to run independent scenario engines concurrently while qkt shares decoded market data per worker.

```bash
qkt sweep strategies/ema_cross.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --dataset research/datasets/xauusd-2021-2024.snapshot.json \
  --param fast=8,12,16,20 \
  --param slow=32,48,64,96 \
  --rank sharpe \
  --parallelism 8
```

Useful ranking metrics are `sharpe`, `calmar`, `profitFactor`, `totalPnL`, and `winRate`. qkt prints the trial count, metric provenance, large-search warnings, and unstable-neighborhood warnings when adjacent grid cells do not support the selected winner.

Pick candidates that have a plateau, not a single lucky point. Favor simpler parameters when multiple choices are close.

## 6. Walk-forward validate

Walk-forward checks whether the selected parameters keep working after each training window:

```bash
qkt walkforward strategies/ema_cross.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --dataset research/datasets/xauusd-2021-2024.snapshot.json \
  --param fast=8,12,16,20 \
  --param slow=32,48,64,96 \
  --train 180d \
  --test 60d \
  --step 60d \
  --rank sharpe \
  --parallelism 8
```

Look for:

- positive mean out-of-sample score
- tolerable degradation from in-sample to out-of-sample
- repeated winner families, not random parameter jumps
- drawdown and turnover that fit the intended account

## 7. Run a governed experiment

Use an experiment plan when you want a durable train/validation/test record and report bundle:

```yaml
name: xau-ema-cross-2024q1
objective: EMA crossover on XAUUSD with fixed train, validation, and test splits.
strategy: strategies/ema_cross.qkt
dataset: research/datasets/xauusd-2021-2024.snapshot.json
primary_metric: sharpe
secondary_metrics: [calmar, profitFactor, maxDrawdown]

splits:
  train: 2021-01-01/2022-07-01
  validation: 2022-07-01/2023-01-01
  test: 2023-01-01/2024-01-01

constraints:
  min_trades: "150"
  max_drawdown: "0.15"

parameter_grid:
  fast: [8, 12, 16, 20]
  slow: [32, 48, 64, 96]

selection:
  method: validation_rank_then_test_once
  top_n: 3
  large_search_threshold: 100

promotion:
  state: candidate
  rationale: locked after validation and tested once on holdout.
```

Run it:

```bash
qkt experiment run --plan research/plans/xau-ema-cross.yaml --parallelism 8
```

The command writes immutable experiment records, split summaries, a test report directory, qkt version/build information, strategy hashes, dataset evidence, selected params, warning metadata, and promotion rationale when present.

## 8. Grade execution realism

Before paper or live promotion, rerun with realistic broker behavior:

```bash
qkt backtest strategies/ema_cross.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --dataset research/datasets/xauusd-2021-2024.snapshot.json \
  --execution mt5-realistic \
  --broker mt5-sim \
  --config qkt.config.yaml \
  --json
```

Execution presets:

| Preset | Use | Behavior |
|---|---|---|
| `paper-fast` | discovery | zero latency, zero slippage, no rejections, optimistic fills |
| `mt5-basic` | broker-shaped screening | MT5 simulator with venue sizing and instrument slippage |
| `mt5-realistic` | promotion grading | fixed latency, bid/ask and stop-distance rules, realistic venue constraints |
| `stress` | robustness | adverse slippage, latency, rejection, and partial-fill assumptions |

Also stress the cost inputs in `instruments.yaml`. A candidate that survives realistic execution and higher transaction costs is much more useful to qkt-forge and portfolio construction than one that depends on perfect fills.

## 9. Evaluate portfolio and book behavior

Portfolio files run children in one attributed account. Configure account currency, FX conversion, and optional book-risk controls, then backtest the portfolio file:

```bash
qkt backtest portfolios/xau-book.qkt \
  --from 2021-01-01 --to 2024-01-01 \
  --config qkt.config.yaml \
  --execution mt5-realistic \
  --json
```

Current `--dataset` pinning is single-symbol. For a multi-symbol book, keep per-symbol snapshots as external evidence and run from the same verified data root until multi-symbol dataset snapshots are added. For a one-symbol portfolio, you can still pass that symbol's snapshot directly.

Use portfolio runs to check:

- per-strategy PnL attribution
- account-currency conversion
- gross and net book exposure
- concentration limits
- de-risk ladder behavior
- whether strategy correlations destroy the standalone edge

## 10. Use qkt-forge for the gauntlet

qkt-forge is the autonomous research layer in `../qkt-forge`. It discovers hypotheses, authors qkt DSL, runs gates, records failures, and serves a dashboard. qkt remains the execution kernel and should keep the CLI and JSON contracts stable enough for qkt-forge to call at high volume.

Run the forge stack from its checkout:

```bash
cd ../qkt-forge
run/forge.sh up
run/forge.sh status
```

Expected dashboard default: `http://localhost:8765`.

The qkt-forge gates cover the institutional pieces that are expensive or statistical:

| Forge gate | What it adds on top of qkt |
|---|---|
| Smoke | enough trades and no immediate account blow-up |
| Grid | plateau across neighboring params |
| Validation | held-out development split |
| Walk-forward | rolling train/test consistency |
| Significance | deflated Sharpe, PBO, and Monte Carlo floors |
| Robustness | cost stress and prop-style risk-fit sizing |

Keep qkt-forge pointed at the same qkt binary, data root, dataset policies, and config conventions you use manually. That is what lets a promoted qkt-forge candidate reproduce as a normal qkt experiment or promotion record.

## 11. Promote to paper, shadow, and production

Record evidence as the candidate advances:

```bash
qkt promotion record strategies/ema_cross.qkt \
  --as xau-ema-cross \
  --state paper \
  --reason "passed snapshot, realistic execution, and walk-forward checks" \
  --evidence dataset_snapshot=research/datasets/xauusd-2021-2024.snapshot.json \
  --evidence realistic_execution=mt5-realistic \
  --evidence walk_forward=research/runs/xau-ema-cross/walkforward.json \
  --paper-days 20 \
  --paper-trades 42 \
  --avg-slippage-bps 0.8 \
  --p95-slippage-bps 2.5 \
  --paper-status pass
```

Approve production separately:

```bash
qkt promotion approve strategies/ema_cross.qkt \
  --as xau-ema-cross \
  --state production \
  --reason "operator approval after paper run"
```

Check the gates before deploy:

```bash
qkt promotion status xau-ema-cross --strategy strategies/ema_cross.qkt
qkt status --deep
```

A production daemon can enforce gates through `promotion:` config. Deploy refuses missing promotion records, missing approvals, insufficient paper metrics, and strategy hash drift unless an explicit waiver with a reason is supplied.

```bash
qkt deploy strategies/ema_cross.qkt --as xau-ema-cross
qkt deploy strategies/ema_cross.qkt --as xau-ema-cross --waive paper_days --reason "temporary supervised micro-size run"
```

Waivers are durable and journaled. Treat them as incident-grade exceptions, not a normal workflow.

## Expected artifacts

A serious research run should leave these artifacts:

| Artifact | Produced by | Why it matters |
|---|---|---|
| Strategy file | authoring or qkt-forge | source of executable logic |
| Dataset snapshot | `qkt data snapshot` | immutable data identity and quality policy |
| Backtest JSON/report | `qkt backtest` or experiment | performance, evidence, costs, accounting |
| Sweep table/JSON | `qkt sweep` | parameter search provenance |
| Walk-forward output | `qkt walkforward` | out-of-sample behavior |
| Experiment registry record | `qkt experiment run` | immutable selection and test evidence |
| Promotion record | `qkt promotion record/approve` | paper/live gate state and approval |
| Operator journal | daemon deploy/waiver/actions | audit trail for production operations |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backtest says incomplete data | missing, empty, or gappy tick days | run `qkt data verify`, refetch, or use a pinned snapshot after repair |
| `--dataset` refuses the run | snapshot symbol/range/field capabilities do not match | create a snapshot for the exact symbol/range and required bid/ask/volume fields |
| Sweep is slow | repeated CSV tick decode or too much tick detail | run `qkt data convert`, use `--parallelism`, or prebuild bars for exploration |
| Bar run differs from tick run | `--bars` approximates intrabar movement | use bars for discovery only, then grade with ticks |
| Production deploy returns promotion gate 409 | missing promotion record, approval, evidence, paper metrics, or hash match | run `qkt promotion status` and fill the missing gates |
| qkt-forge dashboard is not reachable | forge process is not running or built app is missing | from `../qkt-forge`, run `run/forge.sh status` then `run/forge.sh up` |
