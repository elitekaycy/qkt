# Logging guide

qkt uses SLF4J with logback. This document explains the conventions so logs are predictable across the engine, strategies, child sessions, and brokers.

---

## MDC keys (set by the engine)

| Key | Set by | Meaning |
|---|---|---|
| `strategy` | `LiveSession`, `PortfolioSupervisor`, `StrategyHandle.RealFactory` | The strategy or child name. For a portfolio child, the format is `<portfolio>/<alias>`. Strategies running outside a portfolio use just their name. |
| `parent` | `PortfolioDeployer` | Set on portfolio child sessions only. The parent portfolio's name. |
| `log.<name>` | `ActionCompiler.compileLog` | One MDC entry per structured field declared on a DSL `LOG` action. Cleared after the SLF4J call. Namespaced under `log.` to avoid colliding with engine-set keys. |

The console pattern in `src/main/resources/logback.xml` includes `[%X{strategy:-main}]` so every line is tagged with its source. The file `SiftingAppender` uses a custom `StrategyFilenameDiscriminator` that substitutes `/` with `__` (so `mybook/trend` writes to `mybook__trend.log`).

## Console pattern

```
12:34:56.789 [qkt-live-engine] INFO  [mybook/trend] com.qkt.dsl.strategy - buy at 50125.00
└─ time     └─ thread          level └─ strategy   logger                  message
```

- `time` — local time, sub-second precision.
- `thread` — short thread name. Engine threads are `qkt-live-engine`, daemon thread `main`, observability `qkt-control-plane`, MT5 pollers `qkt-mt5-poller-<profile>`.
- `level` — `INFO` / `WARN` / `ERROR` / `DEBUG`. `INFO` is the default when a `LOG` action omits a level.
- `strategy` — set via MDC. Defaults to `main` when no MDC is set (e.g., daemon-level lines).
- `logger` — typically the FQN of the emitting class. `com.qkt.dsl.strategy` for DSL `LOG` actions; `com.qkt.app.LiveSession`, `com.qkt.cli.daemon.ControlRoutes`, etc. for engine internals.
- `message` — free-text, possibly with placeholder-rendered values from `LOG` action fields.

## File pattern

Per-strategy files at `${QKT_STATE_DIR:-~/.local/state/qkt}/logs/<strategy>.log`:

```
2026-05-10T12:34:56.789 [INFO] buy at 50125.00
```

File names substitute `/` for `__`. Names match the safe-filesystem rules in `StateDir.logFile`.

## Logback configuration

The shipped config at `src/main/resources/logback.xml` is the operational default. Operators override by:

1. Placing a custom `logback.xml` on the classpath (typically `~/.config/qkt/logback.xml` if you wire it through `-Dlogback.configurationFile`).
2. Or setting JVM property `-Dlogback.configurationFile=/path/to/logback.xml`.

Common overrides:

- Drop the per-strategy file appender for tests/CI: replace `STRATEGY_FILE` with a no-op.
- Add a JSON appender for log shipping (e.g., `LogstashEncoder`); structured `log.*` MDC keys flow into the JSON event automatically.
- Bump root level to `DEBUG` to see `LOG DEBUG` lines from strategies.

## DSL `LOG` action

Strategies emit log lines via the DSL:

```
THEN LOG "buy at {price}" price=btc.close
THEN LOG WARN "drawdown high"
THEN LOG ERROR "broker rejected" code=42 retry=3
```

See [phase 15 changelog](phases/phase-15.md) for the full grammar. The compiler:

1. Validates `{name}` placeholders against the trailing `name=expr` kvs at parse time.
2. Evaluates each kv on each tick.
3. Sets MDC `log.<name>` for the duration of the SLF4J call (cleared via `try/finally`).
4. Dispatches at the chosen level.

Operators reading logs see the rendered message; tooling reading via JSON appender sees the structured fields.

## Conventions for strategy authors

- Use `LOG WARN` for state transitions worth investigating (drawdown thresholds crossed, regime flips, manual override expected).
- Use `LOG ERROR` for genuine failure conditions (broker-rejected orders, risk halts firing). These show up on stderr and are typically what oncall pages on.
- Use `LOG INFO` (the default) for routine traces — entry/exit decisions, sizing notes.
- Use `LOG DEBUG` for high-volume traces. Filtered out by default; enable per-investigation.

Keep messages short. Put values in placeholder fields, not embedded text:

```
# good
THEN LOG "entry" symbol=eur.symbol price=eur.close size=0.1

# less good — value is hard to extract for tooling
THEN LOG "entry on EURUSD at 1.05 size 0.1"
```

## Conventions for engine contributors

- Engine code uses `LoggerFactory.getLogger(YourClass::class.java)` in the standard SLF4J pattern.
- Set MDC where you cross a strategy boundary; clear it in a `finally`.
- Don't log at INFO inside hot paths (per-tick, per-fill). DEBUG-level is fine.
- Errors from external services (broker HTTP failures, market data dropouts) are WARN unless they're permanently fatal — then ERROR.

## Where logs go in production

Daemon mode:

- Console (stdout/stderr) — captured by the supervisor (systemd, Docker, etc.) per typical conventions.
- Per-strategy files at `${QKT_STATE_DIR}/logs/<strategy>.log`. Rotation via logback's policies if configured (default: append, no rotation).
- Observability HTTP — `qkt logs <strategy>` streams from the file via the daemon; supports `--follow` and `--lines`.

Backtest / one-shot:

- Console only by default. Per-run files configured via the report directory if needed.

## Troubleshooting

**No `[strategy]` prefix on stdout.** The MDC key `strategy` isn't set. This happens for daemon-level lines (control plane HTTP, profile loading) — `[main]` appears. For strategy-emitted lines, MDC is set by `LiveSession`'s engine thread; if you see strategy logs without a prefix, check that you're running through a `LiveSession` (and not, say, calling `Strategy.onTick` directly in a test).

**Per-strategy log file missing.** The `SiftingAppender` lazily creates the file on first write. If you've deployed a strategy but emitted no logs, no file will exist yet. `StrategyHandle.RealFactory` proactively creates an empty file as a placeholder.

**Slash in strategy name causes subdirectories.** Should be fixed in Phase 15 — the `StrategyFilenameDiscriminator` substitutes `/` with `__`. If you see `logs/parent/child.log`, the discriminator isn't being loaded; check that `logback-classic` is on the classpath (`build.gradle.kts` has `implementation(libs.logback.classic)` since Phase 15).
