# CLI commands

Every `qkt` subcommand. Run `qkt <command> --help` for the authoritative flag list.

!!! tip "Auto-generated reference coming"
    A future enhancement scrapes `qkt --help` output into this page so it never drifts. v1 is hand-maintained — file an issue if you spot a gap.

## Strategy lifecycle (daemon)

| Command | What it does |
|---|---|
| `qkt daemon` | Start the daemon. Binds the control plane on an ephemeral 127.0.0.1 port and requires bearer authentication for mutations. |
| `qkt daemon stop` | Stop a running daemon. |
| `qkt daemon status` | Health + uptime of a running daemon. |
| `qkt deploy <file> [--as <name>]` | Deploy a strategy or portfolio. |
| `qkt resync <file> [--as <name>] [--dry-run]` | Validate and replace a deployed strategy or portfolio under the same daemon name. |
| `qkt list` | List deployed strategies + portfolios. |
| `qkt status [<name>]` | Snapshot of one strategy, or all if no name given. |
| `qkt status --deep` | Aggregated health check: daemon + control plane + every deployed strategy. Single-screen human output. Exit 0 if all green, exit 1 with reasons if anything is unhealthy. First-thing-to-run when something feels off. |
| `qkt logs <name> [--lines N] [--follow] [--since <iso8601>]` | Per-strategy log stream. |
| `qkt stop <name> [--flatten]` | Stop a strategy. Cascades for portfolios. |
| `qkt start <portfolio>/<child>` | Resume an operator-stopped child of a portfolio. |

## Project scaffolding

| Command | What it does |
|---|---|
| `qkt create template <path> [--kind mt5\|mt5-ci\|backtest\|portfolio\|minimal\|bybit]` | Scaffold a deployable or research project tree. Default kind is `mt5`. See [Scaffold a project](../get-started/scaffold.md). |

## Strategy authoring

| Command | What it does |
|---|---|
| `qkt parse <file> [--json]` | Parse and compile a `.qkt` file; every parse and compile error is printed with its line and column. `--json` prints the file's machine-readable description instead of `ok` (schema `qkt-parse-v1`): kind, name, version, and one entry per stream with `alias`, `venue`, `symbol`, `qktSymbol`, `timeframe`, `warmupBars` and the `calendar` qkt applies to it (`fx`, `crypto` or `nyse`, the backtest calendar resolution). Errors keep the same diagnostics and exit codes. |
| `qkt lsp` | Run the language server over stdin/stdout for an editor's LSP client. Diagnostics are the same parse-plus-compile pass as `qkt parse`, with positions; completion and hover read the parser's vocabulary. See [Editor integrations](../how-to/editor-integrations.md). |
| `qkt editor list \| install <vscode\|nvim\|vim\|all> [--yes] \| uninstall <target>` | Install or remove the bundled editor integrations; `list` shows which editors are detected. |
| `qkt editor grammar --format textmate\|vim` | Print the syntax grammar generated from the parser's keywords and the indicator, function and constant registries — the same output as the files checked in under `editor/`. |
| `qkt dsl vocabulary [--json]` | Print every name the DSL accepts, read from the tables the parser, compiler and language server share: keywords, indicators, functions, constants, stream and instrument-meta fields, the `candle`/`tick` series selectors, pseudo-symbol members (`POSITION`, `NOW`, `ACCOUNT`, `EXIT`, `STREAK`, `TRADES`, `COOLDOWN`, `SEQUENCE`, `SEQUENCE_STAGE`) and the rolling shorthands. `--json` prints schema `qkt-vocabulary-v1` with sorted lists (`keywords`, `indicators[{name,arity,variadic,signature,doc}]`, `functions[{name,arity,variadic}]`, `constants[{name,value}]`, `streamFields`, `metaFields`, `seriesSelectors`, `members{OWNER:[...]}`, `shorthands`) so editors and the backtester read qkt's vocabulary instead of keeping a copy. `keywordCategories` groups every keyword by the category the generated grammars highlight it under. |
| `qkt backtest <file> [--from] [--to] [--data-root] [--broker paper\|mt5-sim] [--param NAME=V] [--enforce-live-breakers] [--chaos] [--metrics-window NAME=FROM..TO] [--oos-split FRACTION\|DATE]` | Run a one-shot backtest; emits JSON, CSVs, and `report.html`. `--metrics-window` (repeatable) and `--oos-split` report exact sub-window metrics beside `global` (see below). `--json` emits schema `qkt-backtest-result-v1`, preserves legacy top-level metric keys, and includes canonical `global`, `perStrategy`, and `tradeSummary` objects for dashboards. `--broker mt5-sim` opts into the MT5 fidelity simulator (quantization + ask/bid + spread); default `paper`. `--enforce-live-breakers` halts replay at the same runaway threshold as live; the default observe-only mode reports would-be trips while preserving the full research run. `--chaos` selects the seeded stress preset and cannot be combined with `--execution`. |
| `qkt sweep <file> --from --to --param NAME=v1,v2 [--rank sharpe] [--parallelism N] [--json]` | Grid-search the cartesian product of `--param` axes; ranks runs by `--rank` (`sharpe`\|`calmar`\|`profitFactor`\|`totalPnL`\|`winRate`). JSON rows expose commission-net `totalPnL`, `commissionPaid`, daily P&L, and fill-cost inputs for downstream cost reconciliation. |
| `qkt walkforward <file> --from --to --param NAME=v1,v2 --train 90d --test 30d --step 30d [--rank] [--report-dir DIR] [--json]` | Rolling in-sample/out-of-sample validation; reports per-fold winners, winner stability, and mean IS-vs-OOS score. `--report-dir` writes `walkforward_summary.csv` (one row per fold with `testTotalPnL` and `testMaxDrawdown`), `concatenated_equity.csv` (stitched out-of-sample equity), `winner_counts.csv`, and a full backtest report bundle per fold under `folds/fold_NNN/`. `--json` fold rows carry `winner`, `inSample`, `outOfSample`, `testTotalPnL`, `testMaxDrawdown`, `testTrades`. |
| `qkt run <file>` | Foreground paper-trade run. |

### Backtest Report Artifacts

`qkt backtest --report <dir>` writes an audit bundle for downstream tooling:

- `result.json` uses schema `qkt-backtest-result-v1` with `schemaVersion: 1`
  and carries cadence, evidence, accounting, artifact paths, a normalized trade
  summary, global metrics, per-strategy metrics, book analytics, and book-risk
  summary.
- `tradeSummary` is computed from the same `TradeRecord` list as `trades.csv`:
  fill counts and realized PnL by executed side, long/short entry and exit counts
  from strategy-position transitions, gross profit/loss, rejection rate,
  risk-audited fills, risk min/avg/max, traded notional, and max fill notional.
- `pnl_components.csv` decomposes each reported daily PnL value into
  trade-realized PnL and non-trade adjustment PnL for global and per-strategy
  scopes.
- `equity_daily.csv` (`date,open,high,low,close`) is account equity per UTC day
  and `monthly_returns.csv` (`month,return`) the month-over-month equity return,
  both folded from every full-resolution sample the metrics see — not from the
  thinned `equity_global.csv` chart curve. The last `close` is the run's last
  sampled equity; compounding the monthly returns gives the total return. A
  sample stamped exactly at `--to` (the bar that closes there) lands on that day.
- `global` and every per-strategy report record `annualizationFactor`, the
  periods-per-year the Sharpe and Sortino ratios were annualized with.
- `windows` holds one entry per `--metrics-window NAME=FROM..TO` (repeatable,
  clipped to the run) and the `in_sample`/`out_of_sample` pair `--oos-split`
  produces (a fraction such as `0.2` keeps the last 20% out of sample, or an
  instant). Each entry carries `fromMs`/`toMs`, `samples`, `closingFills`,
  `equityStart`, `equityEnd`, and `metrics` with the same fields as `global`,
  computed over the full-resolution samples and closing fills inside `[from, to)`
  — a window ending at `--to` also takes the bar that closes there, so a window
  covering the run reproduces `global`'s drawdown, Sharpe, Sortino, profit factor
  and win rate exactly. A window's `totalPnL` is the equity change between its
  first and last samples.
- `monte_carlo_fan.csv` (only when the run has a Monte Carlo, i.e. at least 30
  closed trades) holds the equity percentiles across every resampled path after
  each trade: `tradeIndex,p5,p25,p50,p75,p95`. It is the fan `report.html`
  draws; its last row is the final-equity P5/P25/P50/P75/P95.
- Futures runs add up to four files, each only when it has rows (a run without
  futures writes none of them): `rolls.csv`
  (`timestamp,stream,strategy,from,to,quantity,multiplier,fromReference,toReference,gap,fromFill,toFill,fees,rollCost`;
  signed quantities, prices and costs in the root's currency)
  lists every position carried across a roll; `contracts.csv`
  (`timestamp,strategy,stream,orderId,contract,side,quantity,contractPrice,streamPrice`) the contract and
  price behind every fill of a continuous stream; `settlements.csv`
  (`timestamp,strategy,contract,side,quantity,price,deliveryPriceKnown`) every position settled at a
  contract's expiry; `margin_daily.csv` (`date,marginUsed,maintenance,equity,marginCall`) each UTC
  day's futures margin at its last sample, for days that ended holding positions with margin terms;
  `structures.csv` (`openedAt,closedAt,strategy,structure,alias,outcome,legs,credit,realized`) every
  option structure, with its legs as `SIDE quantity symbol @ entry`, its outcome (`CLOSED`, `UNWOUND`
  or `SETTLED`), its credit and its premium P&L before fees; times, outcome and amounts stay empty
  while unknown, such as for a structure still open at the end. The reports then also show `rollCostsPaid`, the fourth term of the gross-to-net
  bridge: `preCostPnL = totalPnL + commissionPaid + swapPaid + rollCostsPaid`. Timestamps are epoch
  milliseconds, as in `trades.csv`.
- Each report metric includes daily PnL, max daily drawdown, drawdown periods,
  Monte Carlo tail stats when available, and the retained equity curve used for
  charts.
- `report.html` shows the same trade audit summary before the trade tape, so the
  human-readable report and machine-readable evidence expose the same numbers.
- `manifest.json` records the schema version plus SHA-256 and byte size for every
  generated artifact except itself, so downstream tools can detect stale,
  missing, or edited report files.
- `trades.csv`, `rejections.csv`, `pnl_components.csv`, and `equity_*.csv`
  remain the full tapes for independent audit and graph reconstruction.
- In `trades.csv`, `realized` and `netAccountRealized` are the canonical net
  account-currency PnL used by summaries, risk, and daily PnL. `realized` is
  retained as the legacy alias. `grossAccountRealized` and `accountRealized`
  disclose gross converted PnL before modeled and venue-reported costs, with
  `accountRealized` retained as the legacy gross alias. Dashboards should graph
  and aggregate the net fields unless explicitly showing a gross-vs-cost
  reconciliation.
- Commission-bearing reports satisfy `sum(grossAccountRealized) -
  sum(netAccountRealized) = commissionPaid + venue fill costs` when every fill has conversion
  evidence. Entry commissions therefore appear as zero gross PnL and negative net PnL; the gross
  field must not be populated with the already-net amount.
- `trades.csv` keeps `side` as the executed fill side and separately exports
  `positionEffect` (`OPEN_*`, `INCREASE_*`, `REDUCE_*`, `CLOSE_*`, or
  `REVERSE_TO_*`) plus the actual atomic `orderType`. Consumers must not rename
  buy fills to long trades or sell fills to short trades: a buy may close a
  short, and a sell may close a long.

Before qkt-forge, dashboards, or promotion tooling trust a report directory, run:

```bash
scripts/audit_qkt_report_bundle.py <report-dir> --json
```

The verifier checks both schema versions, manifest hashes and byte sizes,
recomputes `tradeSummary` from `trades.csv` and `rejections.csv`, verifies core
PnL arithmetic, reconciles retained JSON equity curves against `equity_*.csv`,
and verifies daily PnL components against `trades.csv` plus `result.json`. A
non-zero exit means the bundle is stale, malformed, edited, or internally
inconsistent.

`qkt backtest --json` uses the same result schema and includes retained equity
curves in `global.equityCurve` plus per-strategy canonical metrics. Prefer
`--report` bundles for audit gates because the bundle includes full CSV tapes
and manifest hashes; use `--json` for piping compact, schema-tagged summaries.

## Operations

| Command | What it does |
|---|---|
| `qkt brokers list [--json]` | Resolved broker profiles (defaults + user config + env). |
| `qkt instruments verify [--broker NAME] [--instruments PATH] [--json]` | Compare static instrument metadata with each matching MT5 profile's live `/symbol_info`; exits non-zero on any mismatch. |
| `qkt audit-ticks --symbol X --duration N --mt5-profile P [--reference tradingview\|mt5-history]` | Compare TV with MT5, or reconcile live MT5 quotes against raw venue history. |
| `qkt golden capture --session <strategy> [--state-dir DIR] [--out ZIP] [--read-only]` | Export retained live ticks, warmup ticks, completed candles, fills, orders, and raw MT5 exchanges as a checksummed ZIP. Market records must contain structured replay data. Trading mode requires a filled order linked to a successful MT5 `/order` exchange by explicit engine ID or venue ticket. `--read-only` instead requires zero fills and zero order/position mutations while retaining gateway reads. Both modes fail when required evidence is missing or a journal reports dropped records. The manifest records the enforced capture mode and mutation count. Manifest `capture*` build fields identify the CLI that created the ZIP; a supervising run manifest must separately identify the daemon build that produced the session. |
| `qkt golden materialize --bundle ZIP --out DATA_ROOT` | Verify every manifest entry hash and record count, reject unsafe or incomplete structured market evidence, then write the captured market input into normal QKT tick CSV plus inspectable and binary bar stores. `golden-replay-manifest.json` records the source bundle hash, build identities, counts, symbols, timeframes, and the recommended replay window. The output directory must not already exist. |
| `qkt soak report <strategy> --testing-sha SHA --image REPO@sha256:DIGEST --started-at UTC --completed-at UTC --trading-days N --health JSONL --reconciliation JSON --golden ZIP --coverage JSON --parity JSON --insights JSON --out JSON` | Derive fail-closed live-parity promotion evidence from health samples, reconciliation, golden journal, coverage, parity, and Insights artifacts. |

## Global flags

Most commands accept:

- `--state-dir <path>` — override `~/.local/state/qkt/`
- `--config <path>` — override `./qkt.config.yaml`
- `--json` — emit machine-readable JSON instead of human-readable text

Daemon clients automatically read the mutation bearer token from
`<state-dir>/control.token`. Set `QKT_CONTROL_TOKEN` for secret-managed deployments;
the daemon and CLI both prefer it over the state file. Read-only health, list, status,
logs, latency, reconcile, and metrics routes remain unauthenticated on loopback.

`qkt resync` also accepts `--dry-run`, `--reconcile=ignore-mismatches`, and the
same production-gate waiver form as deploy: `--waive <gate> --reason <text>`.
Use `--dry-run` before applying an edited live strategy.

Reconcile distinguishes a position that closed while the daemon was down from a real
mismatch: a persisted leg whose venue ticket is no longer in the venue's open-position list
is retired automatically (its realized result is booked from the venue's closing deals when
the venue keeps deal history) and the deploy continues. A venue position with no persisted
leg, a quantity/side disagreement on a ticket that still exists, or a leg without a ticket
still fails closed and needs `--reconcile=ignore-mismatches`.

Ticket ownership is read from the venue comment (`dsl-<STRATEGY name>`, truncated by MT5).
A portfolio child runs under `<portfolio>:<slot>` but stamps its `STRATEGY` name, so the
session registers that name as an alias of the child id — otherwise a restart with open
legs disowns every child position and wipes its leg book while the venue stays long.
`--reconcile=ignore-mismatches` applies to portfolios too: every child adopts its unmatched
venue positions and starts under the adoption halt. Both `--flag=value` and `--flag value`
spellings are accepted by every subcommand.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | User error (bad input, file not found, daemon unreachable) |
| 2 | Argument error (missing required flag, malformed flag) |
