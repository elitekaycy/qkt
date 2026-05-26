# CLI commands

Every `qkt` subcommand. Run `qkt <command> --help` for the authoritative flag list.

!!! tip "Auto-generated reference coming"
    A future enhancement scrapes `qkt --help` output into this page so it never drifts. v1 is hand-maintained — file an issue if you spot a gap.

## Strategy lifecycle (daemon)

| Command | What it does |
|---|---|
| `qkt daemon` | Start the daemon. Binds the control plane on an ephemeral 127.0.0.1 port. |
| `qkt daemon stop` | Stop a running daemon. |
| `qkt daemon status` | Health + uptime of a running daemon. |
| `qkt deploy <file> [--as <name>]` | Deploy a strategy or portfolio. |
| `qkt list` | List deployed strategies + portfolios. |
| `qkt status [<name>]` | Snapshot of one strategy, or all if no name given. |
| `qkt status --deep` | Aggregated health check: daemon + control plane + every deployed strategy. Single-screen human output. Exit 0 if all green, exit 1 with reasons if anything is unhealthy. First-thing-to-run when something feels off. |
| `qkt logs <name> [--lines N] [--follow] [--since <iso8601>]` | Per-strategy log stream. |
| `qkt stop <name> [--flatten]` | Stop a strategy. Cascades for portfolios. |
| `qkt start <portfolio>/<child>` | Resume an operator-stopped child of a portfolio. |

## Project scaffolding

| Command | What it does |
|---|---|
| `qkt create template <path> [--kind mt5\|minimal]` | Scaffold a new qkt project tree (compose + Makefile + sample strategy). Default kind is `mt5`. See [Scaffold a project](../get-started/scaffold.md). |

## Strategy authoring

| Command | What it does |
|---|---|
| `qkt parse <file>` | Parse-and-validate a `.qkt` file; pretty-print errors. |
| `qkt backtest <file> [--from] [--to] [--data-root] [--broker paper\|mt5-sim] [--report]` | Run a one-shot backtest; emits JSON, CSVs, and `report.html`. `--broker mt5-sim` opts into the MT5 fidelity simulator (quantization + ask/bid + spread); default `paper` matches historical behavior. |
| `qkt run <file>` | Foreground paper-trade run. |

## Operations

| Command | What it does |
|---|---|
| `qkt brokers list [--json]` | Resolved broker profiles (defaults + user config + env). |
| `qkt audit-ticks --symbol X --duration N --mt5-profile P` | Capture TV + MT5 ticks, report drift stats. |

## Global flags

Most commands accept:

- `--state-dir <path>` — override `~/.local/state/qkt/`
- `--config <path>` — override `./qkt.config.yaml`
- `--json` — emit machine-readable JSON instead of human-readable text

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | User error (bad input, file not found, daemon unreachable) |
| 2 | Argument error (missing required flag, malformed flag) |
