# Logging

For the canonical logging guide — MDC keys, console + file patterns, logback overrides, conventions for strategy authors and engine contributors — see [`docs/logging.md`](../logging.md). It was written under Phase 19 and is the single source of truth.

This page exists in the operations section to surface the guide for operators looking under "operations" in the docs nav.

## Quick reference

| MDC key | Set by | Meaning |
|---|---|---|
| `strategy` | `LiveSession`, `PortfolioSupervisor` | The strategy or child name (`<portfolio>/<alias>` for portfolio children) |
| `parent` | `PortfolioDeployer` | The parent portfolio's name (children only) |
| `log.<name>` | `ActionCompiler.compileLog` | One MDC entry per structured field on a DSL `LOG` action — cleared after the SLF4J call |

## Default file location

`${QKT_STATE_DIR:-~/.local/state/qkt}/logs/<safeName>.log`

`safeName` substitutes `/` with `__` so portfolio children produce filesystem-safe names (`mybook__trend.log`).

## Override logback

Set `-Dlogback.configurationFile=/path/to/logback.xml` to use a custom logback config. Common reasons:

- Add a JSON appender for log shipping (the `log.*` MDC keys flow into JSON event fields automatically)
- Bump root level to DEBUG for an investigation
- Drop the file appender for tests / CI

See [`docs/logging.md`](../logging.md) for full examples.
