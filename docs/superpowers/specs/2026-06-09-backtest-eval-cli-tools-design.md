# Backtest evaluation CLI tools — params, sweep, walk-forward, research

Date: 2026-06-09

## Goal

Expose qkt's existing backtest-evaluation engines through the CLI so a user can tune and
validate a strategy without writing Kotlin. Four capabilities, each verified on real data:

- `qkt backtest --param NAME=VALUE` — override a strategy's parameters for a single run.
- `qkt sweep` — grid-search parameters, ranked by a chosen metric.
- `qkt walkforward` — rolling in-sample / out-of-sample validation.
- `qkt research` — already shipped; verify it and close two accuracy gaps.

This is **tools only**. No gate, threshold, auto-deploy, or monitoring workflow — those can be
composed later from these commands. No new metric or backtest engines: reuse `PerformanceReport`,
`BacktestSweep`, and `WalkForwardHarness` as they stand.

## Current state (verified in code)

- `PARAM name = default` is a real DSL construct. `ParamSubstitution.apply(ast)` replaces every
  `PARAM` reference with its declared default before compilation; `AstCompiler.compile` calls it.
  There is **no external override path**, and `--param` is **not** wired into any command. The
  claim in `docs/reference/dsl/let-defaults.md` that `qkt backtest --param fast=12` works is doc
  drift — it does not.
- `BacktestSweep<C>(configs, backtestFactory, parallelism)` runs each config and returns
  `SweepResult`. It does **not** rank — ranking is the caller's job.
- `WalkForwardHarness<C>(configs, backtestFactory(label, config, range), totalRange, trainSize,
  testSize, stepSize, scoreOf, parallelism, topN)` produces `WalkForwardResult` (per-fold winner,
  `winnerCounts`, `meanTrainScore`, `meanTestScore`, concatenated OOS curve). Full IS/OOS already
  implemented.
- `ResearchCommand` (`qkt research`) is wired and runs an interactive `ReplayRepl`, but builds its
  context with `NoopInstrumentRegistry` (the `contractSize = 1` footgun that `backtest` no longer
  has) and `fetcher = null` (no auto-fetch, unlike `backtest`).

## Design

### 1. Parameter override seam (the shared foundation)

Everything else builds on this one change.

- `ParamSubstitution.apply(ast: StrategyAst, overrides: Map<String, String> = emptyMap()): StrategyAst`.
- `AstCompiler.compile(rawAst: StrategyAst, overrides: Map<String, String> = emptyMap()): Strategy`
  threads `overrides` into `ParamSubstitution.apply`.
- **Override semantics:** `NAME=VALUE` replaces the value of a top-level numeric binding named
  `NAME`, declared as either `PARAM` or `LET`. `VALUE` is parsed as a numeric literal
  (`BigDecimal` → `NumLit`). Overriding a `LET` whose right-hand side is an expression (e.g.
  `LET slowMa = ema(btc.close, 21)`) replaces the whole expression with the literal — the caller's
  choice; normal use overrides scalar bindings (`LET fast = 9`).
  - Rationale: existing strategies and all the docs/examples use `LET` for tunables, so overriding
    by name across both `PARAM` and `LET` makes every current strategy sweepable with no rewrite,
    and matches the (currently false) promise in `let-defaults.md`. `PARAM` remains the
    purpose-built declaration; `LET` override is the pragmatic extension.
- **Validation:** every override `NAME` must match a declared `PARAM` or `LET`; otherwise error
  `unknown parameter 'NAME'; strategy declares: [...]`. A non-numeric `VALUE` errors.

### 2. Grid grammar + parser (shared)

- Flag form: `--param NAME=V` (repeatable). `V` is a single value (a fixed override) or a
  comma-list `v1,v2,v3` (a sweep axis).
- A `ParamGrid` helper collects all `--param` into `NAME → [values]`, forms the cartesian product
  across axes, and yields a list of combos. Each combo is a `Map<String,String>` with a
  deterministic label: entries sorted by name, joined `NAME=V` with `,` (e.g. `fast=5,slow=20`).
- `backtest` is the single-combo case (every axis length 1 → exactly one combo = plain overrides).
  `sweep`/`walkforward` use the full product.
- Before running, the command prints the combo count (`sweeping N parameter combinations`). No
  hard cap — the user owns the cost — but the count is always surfaced, never silently truncated.

### 3. `qkt backtest --param`

`BacktestCommand` parses `--param` into an overrides map and passes it to
`AstCompiler.compile(ast, overrides)`. A comma-list value here is an error
(`multiple values for 'NAME'; use 'qkt sweep' to grid-search`). Everything else is unchanged.

### 4. `qkt sweep`

New `SweepCommand`. Flags: positional `<file>`, `--from`, `--to`, `--data-root`, `--broker`,
`--instruments`, `--symbols`, `--param` (grid), `--rank` (default `sharpe`), `--parallelism`
(default 1), `--json`, `--no-fetch`, `--allow-incomplete`.

- `C = Map<String,String>` (the combo's overrides). `backtestFactory(label, config)` =
  `AstCompiler.compile(ast, config)` → `Backtest.fromStore(...)` using the shared context
  (§7). Data is provisioned once over `[from, to]` before the sweep (all combos share it).
- Run `BacktestSweep(configs, factory, parallelism)`, then rank `SweepRun`s by the `--rank`
  metric (§8) on `run.result.global`, descending, nulls last.
- Output: a ranked table — rank, label, the ranked metric, plus `trades`, `totalPnL`, `sharpe`,
  `calmar`, `maxDD`, `winRate`. `--json` emits an array of `{label, params, metrics}`.

### 5. `qkt walkforward`

New `WalkForwardCommand`. Flags: `<file>`, `--from`, `--to`, `--train`, `--test`, `--step`,
`--param` (grid), `--rank` (default `sharpe`), `--data-root`, `--broker`, `--instruments`,
`--symbols`, `--parallelism`, `--topN` (default 3), `--json`, `--no-fetch`, `--allow-incomplete`.

- Durations (`--train 90d`, `--test 30d`, `--step 30d`) parse via
  `Duration.ofMillis(TimeWindow.parse(spec).durationMs)` — reuses the existing `d/h/m/s` parser.
- `backtestFactory(label, config, range)` compiles with `config` and runs over `range`.
- `scoreOf` = the `--rank` metric. Run `WalkForwardHarness(...)`.
- Output: per-fold table (train range, test range, winner label, IS score, OOS metric from
  `testResult`), `winnerCounts` (parameter stability across folds), `meanTrainScore` vs
  `meanTestScore` (the overfit gap), and the concatenated OOS curve summary (final equity, maxDD).
  `--json` emits the full result.
- Data provisioned once over `[from, to]`.

### 6. `qkt research` accuracy fixes

- Replace `NoopInstrumentRegistry` with the same registry `backtest` builds:
  `LayeredInstrumentRegistry([yaml-if-present, StandardInstrumentRegistry])` — so research PnL is
  contract-size-correct.
- Add auto-fetch: build the store with `DukascopyTickFetcher` and provision the requested range,
  honoring `--no-fetch` / `--allow-incomplete`, mirroring `backtest`. Research currently uses
  `fetcher = null` and requires pre-cached data; this makes it seamless like `backtest`.
- Verify on real cached XAUUSD: loads, steps, and shows contract-size-correct PnL.

### 7. Shared component — `BacktestContext`

Extract the common setup currently inline in `BacktestCommand` into one reusable builder used by
`backtest`, `sweep`, `walkforward` (and the store/instruments half by `research`):

given `args` + `ast`, produce `{ store (with fetcher), instruments registry, candleWindow,
brokerKind, dataRoot, from, to, provision() }` plus a builder
`backtest(overrides, range = [from,to])` that compiles the ast with `overrides` and constructs a
`Backtest.fromStore` whose `MarketRequest` is clipped to `range`. `sweep` calls it with the full
range; `walkforward`'s factory calls it per fold range. The store is provisioned once over
`[from, to]` so every fold/combo reads the same cached data.

This removes the duplication that would otherwise be copied across four commands and keeps the
data/instrument/provisioning behavior identical everywhere (so a sweep and a single backtest of the
same combo are guaranteed to use the same data and contract specs).

### 8. `--rank` metric selector (shared)

A `RankMetric` selector maps a flag to a `PerformanceReport` field and a comparator (higher is
better; nulls sort last):

| `--rank` | field |
|---|---|
| `sharpe` (default) | `sharpeRatio` |
| `calmar` | `calmarRatio` |
| `profitFactor` | `profitFactor` |
| `totalPnL` | `totalPnL` |
| `winRate` | `winRate` |

Used by `sweep` (ranking) and `walkforward` (`scoreOf`). An unknown value errors with the valid set.

## Components

New files:

- `cli/ParamGrid.kt` — parse `--param` flags → cartesian combos + labels.
- `cli/RankMetric.kt` — flag → metric selector + comparator.
- `cli/BacktestContext.kt` — shared store/request/instruments/provisioning/candleWindow setup.
- `cli/SweepCommand.kt`, `cli/WalkForwardCommand.kt`.

Modified:

- `dsl/compile/ParamSubstitution.kt`, `dsl/compile/AstCompiler.kt` — `overrides` parameter.
- `cli/BacktestCommand.kt` — `--param` + use `BacktestContext`.
- `cli/ResearchCommand.kt` — registry + auto-fetch.
- `cli/Main.kt` — dispatch `sweep`, `walkforward`.

Docs:

- Fix `reference/dsl/let-defaults.md` (`--param` semantics; PARAM/LET override).
- Update `how-to/parameter-sweep.md` (`qkt sweep` now shipped) and add a `qkt walkforward` note.
- Update `reference/cli-commands.md` (`sweep`, `walkforward`, `--param`).

## Error handling

- Unknown `--param` name → error listing declared `PARAM`/`LET` names.
- Non-numeric `--param` value → error.
- Comma-list value in `qkt backtest --param` → error pointing to `qkt sweep`.
- Unknown `--rank` → error listing valid metrics.
- `walkforward` with a train/test window larger than the range, or zero folds → clear error.
- Bad duration spec → error.
- Empty data / holes → existing provisioning errors (`--allow-incomplete` escape hatch).

## Testing — "working accurately" means verified

Unit:

- `ParamGrid` cartesian product + deterministic labels (incl. single-combo and multi-axis).
- Override seam: a `PARAM` override and a `LET` override both change compiled behavior; unknown
  name and non-numeric value both error.
- `RankMetric` comparator: ordering + nulls-last.
- Duration parsing.

Integration (real cached XAUUSD 2026-06-04 — data already on disk):

- `backtest --param fast=3` vs the file default → different trade count / PnL (override bites).
- `sweep --param fast=3,5,8 --rank sharpe` → ranked table; the top label's standalone `backtest`
  reproduces its sweep row (consistency check).
- `walkforward --train … --test … --step …` → ≥1 fold; reports IS vs OOS score.
- `research` → loads and shows contract-size-correct (×100) PnL.

`ktlintFormat` clean; CI green.

## Out of scope

- Any gate / threshold / auto-deploy / monitoring / lifecycle workflow.
- Per-strategy MT5 magic / venue-native attribution (separate concern; comment+strategyId already
  attributes today).
- Non-numeric parameters; the `sweep.yaml` and `5..25:5` range grammars (YAGNI — revisit only if
  comma-lists prove insufficient).
