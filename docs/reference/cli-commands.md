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
| `qkt parse <file>` | Parse-and-validate a `.qkt` file; pretty-print errors. |
| `qkt backtest <file> [--from] [--to] [--data-root] [--broker paper\|mt5-sim] [--param NAME=V] [--enforce-live-breakers] [--chaos]` | Run a one-shot backtest; emits JSON, CSVs, and `report.html`. `--json` emits schema `qkt-backtest-result-v1`, preserves legacy top-level metric keys, and includes canonical `global`, `perStrategy`, and `tradeSummary` objects for dashboards. `--broker mt5-sim` opts into the MT5 fidelity simulator (quantization + ask/bid + spread); default `paper`. `--enforce-live-breakers` halts replay at the same runaway threshold as live; the default observe-only mode reports would-be trips while preserving the full research run. `--chaos` selects the seeded stress preset and cannot be combined with `--execution`. |
| `qkt sweep <file> --from --to --param NAME=v1,v2 [--rank sharpe] [--parallelism N] [--json]` | Grid-search the cartesian product of `--param` axes; ranks runs by `--rank` (`sharpe`\|`calmar`\|`profitFactor`\|`totalPnL`\|`winRate`). JSON rows expose commission-net `totalPnL`, `commissionPaid`, daily P&L, and fill-cost inputs for downstream cost reconciliation. |
| `qkt walkforward <file> --from --to --param NAME=v1,v2 --train 90d --test 30d --step 30d [--rank]` | Rolling in-sample/out-of-sample validation; reports per-fold winners, winner stability, and mean IS-vs-OOS score. |
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
| `qkt soak report <strategy> --testing-sha SHA --image REPO@sha256:DIGEST --started-at UTC --completed-at UTC --trading-days N --health JSONL --reconciliation JSON --golden ZIP --out JSON` | Derive fail-closed paper-soak promotion evidence from health samples, final reconciliation, and a golden bundle. The promotion verifier separately enforces 48 hours or five trading days. |

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

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | User error (bad input, file not found, daemon unreachable) |
| 2 | Argument error (missing required flag, malformed flag) |
