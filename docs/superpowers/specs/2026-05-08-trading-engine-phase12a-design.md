# Phase 12a — `qkt` CLI Binary (Foreground Subcommands)

**Status:** Design draft.
**Predecessor:** Phase 11f (external SQL-like parser).
**Successor:** Phase 12b (single-strategy observability HTTP port), Phase 12c (daemon + multi-strategy + Docker base image).

---

## 1. Mission

After 11f the engine can read `.qkt` files, parse them, compile them, and run them — but only via in-process Kotlin code. Phase 12a adds the file-based front-end that turns qkt from "a library a Kotlin programmer can use" into "a tool a quant can install and use." Three foreground subcommands:

```
qkt run foo.qkt                                        # run live (or paper) — foreground, Ctrl+C to stop
qkt backtest foo.qkt --from 2024-01-01 --to 2024-06-01 # one-shot backtest, prints report, exits
qkt parse foo.qkt                                      # parse + validate, exits
```

`qkt` is the smallest meaningful step toward the Docker-shape vision. It is intentionally single-process and foreground — no daemon, no addressable strategies, no observability port. Those land in 12b/12c on top of this foundation.

Three architectural decisions taken before writing the spec:

1. **Hand-rolled subcommand dispatch.** No picocli, no clikt, no kotlinx-cli. The CLI surface is small (~5 subcommands at peak), each with ≤6 flags. A hand-rolled `Args` class plus per-subcommand argument parsing is ~150 LoC and matches the project's "no unnecessary deps" posture.
2. **Gradle `application` plugin for distribution.** `./gradlew installDist` produces `build/install/qkt/bin/qkt` (a shell launcher + lib jars). Users either build from source or install via a tarball/zip release. No Homebrew tap, no apt package, no Docker image in 12a — those land in 12c/12d.
3. **Configuration via CLI flags + env vars + optional `qkt.config.yaml`.** Strategy files describe the strategy; runtime concerns (data source, broker creds, output format) come from flags, env, or a config file. The strategy file is portable across machines without modification.

---

## 2. Goals

- **`qkt run <strategy>.qkt`** — runs a compiled strategy in foreground mode against a **live tick stream**, paper-trading via `PaperBroker`. Default `--source tv` uses the existing `TradingViewMarketSource`. Logs stream to stdout. `Ctrl+C` triggers graceful shutdown. `--source bybit | alpaca | interactive` is rejected with "live broker execution is not yet enabled in 12a" — the broker-side wiring lands later. (Live tick subscription works; live order placement does not.)
- **`qkt backtest <strategy>.qkt --from … --to … [--source store --data-root <path>] [--fetcher dukascopy --fetcher-script <path>]`** — one-shot backtest. Loads ticks from `DefaultDataStore` for the date range, runs the strategy, prints `BacktestResult` summary. With `--fetcher dukascopy`, missing data is lazy-fetched via `ScriptDataFetcher.dukascopy(scriptPath)`. Exits 0 on success, 1 on parse/runtime/data failure.
- **`qkt parse <strategy>.qkt`** — parses and AST-compiles the file. Prints "ok" on success or the error list on failure. Exits 0 / 1. Useful in editor save-hooks and CI lint passes.
- **`qkt --version`** and **`qkt --help`** — standard.
- **`qkt help <subcommand>`** — per-subcommand help text.
- **Single binary distribution.** `./gradlew installDist` builds a runnable `bin/qkt`. Tarball release published to GitHub Releases (extends the existing 11a release process with a binary artifact step).
- **Deterministic exit codes.** `0` success, `1` user-fixable failure (parse, missing file, bad config), `2` argument error.
- **JSON output mode.** `--json` flag on `backtest` emits the report as JSON; `parse` emits errors as JSON. Pipeable into `jq`, monitoring, CI annotations.

## Non-goals

- **No daemon.** No long-running background process. No `qkt daemon`, no `qkt deploy`, no `qkt list / logs / status / stop`. Those are 12c.
- **No HTTP observability port.** No `--port` flag. The running strategy logs to stdout only. Phase 12b adds the HTTP port for single-strategy runs.
- **No Docker base image.** No `Dockerfile`. Docker delivery lands in 12c alongside the daemon (the daemon is what makes a Docker image meaningful — without it, "one container per strategy" is just `qkt run` in a JRE container, which works but isn't worth a base image of its own).
- **No live broker execution.** `qkt run` subscribes to live TradingView ticks (the existing `TradingViewMarketSource`) and runs the strategy against a `PaperBroker`. Real order placement on Bybit / Alpaca / Interactive is rejected with "live broker execution is not yet enabled in 12a." Real-broker wiring lands later (12c+).
- **No package manager distribution** (Homebrew, apt, .deb, .rpm). `gradle installDist` + tarball release is the only delivery channel. Phase 12d.
- **No interactive REPL.** No `qkt repl` or `qkt shell`. The DSL is file-based, not interactive.
- **No strategy generator / scaffold.** No `qkt new` or `qkt init`. Templates can land later.
- **No symbol-discovery / data-fetch automation.** If a backtest references a symbol the data source doesn't have, the user gets a clear error and is responsible for fetching the data. No auto-download.
- **No multi-strategy from a single CLI invocation.** `qkt run foo.qkt bar.qkt` is rejected. One file per invocation. (Multi-strategy is the daemon's job in 12c.)

---

## 3. Worked example

```
$ qkt --version
qkt 0.11.6

$ qkt parse strategies/momentum.qkt
ok

$ qkt parse strategies/broken.qkt
strategies/broken.qkt:7:14 — expected '=' after SIZING, got 'BUY'
strategies/broken.qkt:12:3 — unknown stream alias 'btx', did you mean 'btc'?
strategies/broken.qkt:18:9 — RISK requires either a percentage or absolute prefix
3 errors
$ echo $?
1

$ qkt backtest strategies/momentum.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --data-root ./data \
    --starting-balance 10000

Strategy:        momentum_basket v1
Symbols:         BTCUSDT, XAUUSD, AAPL
Period:          2024-01-01 → 2024-06-01 (152d)
Trades:          47
Win rate:        55.3%
Final equity:    11,247.50  (+12.5%)
Max drawdown:    -340.20    (-3.0%)
Sharpe (daily):  1.42
$ echo $?
0

$ qkt backtest strategies/momentum.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --data-root ./data \
    --fetcher dukascopy --fetcher-script ./scripts/duka_fetch.sh
[INFO] missing days: BTCUSDT 2024-03-14..2024-03-16 — running fetcher
[INFO] qkt-csv-v1 manifest updated
Strategy:        momentum_basket v1
...

$ qkt backtest strategies/momentum.qkt \
    --from 2024-01-01 --to 2024-06-01 --data-root ./data \
    --json | jq '.finalRealized'
1247.50

$ qkt run strategies/momentum.qkt
[INFO] qkt 0.11.6 — strategy momentum_basket v1 — paper-trading via TradingView ticks
[INFO] subscribed: BYBIT:BTCUSDT 1m, BYBIT:BTCUSDT 1h, INTERACTIVE:XAUUSD 15m
[INFO] indicator warmup complete (49 bars)
[INFO] running, Ctrl+C to stop
[INFO] 2026-05-08T14:32:01Z BUY BTCUSDT qty=0.001 entry=68234.50
[INFO] 2026-05-08T14:38:05Z STOP-LOSS hit BTCUSDT exit=68000.00 realized=-2.34
^C
[INFO] graceful shutdown initiated
[INFO] open positions flattened
[INFO] final equity: 9,997.66
$

$ qkt run strategies/momentum.qkt --source bybit
qkt: error: live broker execution ('--source bybit') is not yet enabled in 12a;
       use --source tv (default) for paper-trading on live TradingView ticks.
$ echo $?
1
```

---

## 4. Architecture

```
                          $ qkt <subcommand> ...
                                    │
                                    ▼
                       ┌────────────────────────┐
                       │ Main.kt                │  ← entry point (kotlin.application)
                       │  • parses argv         │
                       │  • dispatches          │
                       └────────────┬───────────┘
                                    │
            ┌───────────────────────┼────────────────────────────┐
            ▼                       ▼                            ▼
   ┌─────────────────┐   ┌──────────────────┐         ┌────────────────────┐
   │ ParseCommand    │   │ BacktestCommand  │         │ RunCommand         │
   │  • Dsl.parseFile│   │  • Dsl.parseFile │         │ • Dsl.parseFile    │
   │  • print result │   │  • build feed    │         │ • build pipeline   │
   │  • exit         │   │  • Backtest.run()│         │ • subscribe ticks  │
   │                 │   │  • print report  │         │ • install SIGINT   │
   │                 │   │  • exit          │         │ • run forever      │
   └─────────────────┘   └──────────────────┘         └────────────────────┘
                                    │
                                    ▼
                              existing engine
                              (Backtest, AstCompiler, Dsl, ...)
```

### 4.1 Module placement

A new package `com.qkt.cli` under the existing module. No new Gradle subproject. Files:

```
src/main/kotlin/com/qkt/cli/
├── Main.kt                    # entry point — argv parsing + dispatch
├── Args.kt                    # generic flag parser (no library)
├── ParseCommand.kt            # qkt parse <file>
├── BacktestCommand.kt         # qkt backtest <file> [flags]
├── RunCommand.kt              # qkt run <file> [flags]
├── Config.kt                  # qkt.config.yaml loader
├── ReportFormat.kt            # text vs JSON output
└── ExitCodes.kt               # constants

src/test/kotlin/com/qkt/cli/
├── ArgsTest.kt
├── ParseCommandTest.kt
├── BacktestCommandTest.kt
├── RunCommandTest.kt
└── EndToEndCliTest.kt         # spawns Main.main() with real argv arrays
```

### 4.2 Gradle wiring

Enable the `application` plugin:

```kotlin
plugins {
    application
}

application {
    mainClass.set("com.qkt.cli.MainKt")
    applicationName = "qkt"
}
```

`./gradlew installDist` produces `build/install/qkt/bin/qkt` (Bash launcher) + `build/install/qkt/lib/*.jar`. Adding `build/install/qkt/bin` to `PATH` makes `qkt` callable globally.

`./gradlew distTar` produces `build/distributions/qkt-0.11.6.tar`. This becomes the GitHub Release artifact (extends 11a's release process — Phase 12a updates `docs/release-process.md` to add a "publish binary" step).

### 4.3 Argument parsing

Hand-rolled. The CLI surface is fixed and small. The parser is a single `Args` class:

```kotlin
class Args(private val argv: Array<String>) {
    val subcommand: String = argv.getOrNull(0) ?: "help"
    private val rest = argv.drop(1)

    fun positional(idx: Int): String? = rest.filter { !it.startsWith("--") }.getOrNull(idx)
    fun flag(name: String): Boolean = rest.contains("--$name")
    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }
    fun requireOption(name: String): String =
        option(name) ?: error("missing required flag --$name")
}
```

Subcommands consume the same `Args` instance. No need for picocli or clikt — the surface is too small to justify the dep.

### 4.4 Output formats

Default human-readable. `--json` flag selects machine output. The `ReportFormat` enum controls:

```kotlin
sealed interface ReportFormat {
    data object Text : ReportFormat
    data object Json : ReportFormat
}

fun BacktestResult.format(fmt: ReportFormat, out: PrintStream) =
    when (fmt) {
        ReportFormat.Text -> printTextReport(this, out)
        ReportFormat.Json -> printJsonReport(this, out)  // hand-rolled JSON, no Jackson dep
    }
```

JSON output uses a minimal hand-rolled emitter (BigDecimal → string, no escape edge cases beyond what we control). No new JSON dep.

---

## 5. CLI surface

### 5.1 `qkt run <file> [flags]`

| Flag | Default | Description |
|---|---|---|
| `--source <name>` | `tv` | Live tick source. `tv` (TradingView). `bybit | alpaca | interactive` rejected in 12a. |
| `--starting-balance <num>` | `10000` | Starting cash balance for the strategy (paper-trading). |
| `--config <path>` | `./qkt.config.yaml` | Optional config file with TV creds, defaults. |
| `--flatten-on-stop` | (off) | On SIGINT, flatten open positions before exit. Default leaves them open. |
| `--log-level <level>` | `info` | `debug | info | warn | error`. |
| `--debug` | (off) | Print stack traces on runtime error. Default suppresses them and prints only the message. |

Behaviour: parses the file (errors → exit 1), compiles, constructs a `TradingPipeline` with `TradingViewMarketSource` as the tick feed and `PaperBroker` as the broker, subscribes to declared streams, runs forever. SIGINT triggers a graceful shutdown.

### 5.2 `qkt backtest <file> [flags]`

| Flag | Default | Description |
|---|---|---|
| `--from <date>` | (required) | ISO date or datetime — start of backtest window. |
| `--to <date>` | (required) | ISO date or datetime — end of backtest window. |
| `--data-root <path>` | `./data` | Path to the `DefaultDataStore` root (CSV files + manifest). |
| `--fetcher <name>` | (off) | If set, lazy-fetch missing data. `dukascopy` is the only supported value in 12a. |
| `--fetcher-script <path>` | (off) | Path to the fetcher script (required when `--fetcher` is set). For `dukascopy`, wraps `ScriptDataFetcher.dukascopy(scriptPath)`. |
| `--starting-balance <num>` | `10000` | Starting cash balance. |
| `--symbols <list>` | (auto from .qkt) | Override the symbols to backtest (must be a subset of those declared in the strategy). |
| `--json` | (off) | Emit `BacktestResult` as JSON instead of human text. |
| `--config <path>` | `./qkt.config.yaml` | Optional config file. |

Behaviour: parses, compiles, builds a `Backtest` with the date range and source, runs to completion, prints the report, exits.

### 5.3 `qkt parse <file>`

No flags. Parses and validates. Prints "ok" on success or the error list on failure. Exits 0 or 1.

`--json` (optional): emit errors as a JSON array. Useful for editor save-hooks (LSP-shaped output).

### 5.4 `qkt --version` / `qkt --help` / `qkt help <subcommand>`

Standard. `--version` prints `qkt <semver>` to stdout and exits 0. `--help` and `help <subcommand>` print usage text to stdout and exit 0.

---

## 6. Configuration

Resolution order (later overrides earlier):

1. **Compiled-in defaults.**
2. **`qkt.config.yaml`** (if exists at `--config <path>` or `./qkt.config.yaml`).
3. **Environment variables** (`QKT_SOURCE`, `QKT_DATA_DIR`, etc.).
4. **CLI flags.**

Sample `qkt.config.yaml`:

```yaml
data_root: ./data
starting_balance: 10000
log_level: info

# Live tick source (qkt run)
tv:
  username: ${TV_USERNAME}
  password: ${TV_PASSWORD}

# Lazy-install fetcher (qkt backtest --fetcher dukascopy)
fetchers:
  dukascopy:
    script: ./scripts/duka_fetch.sh

# Reserved — live broker creds (rejected in 12a, used by 12c+)
brokers:
  bybit:
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
```

`${VAR}` references are expanded from env vars. Hand-rolled YAML loader (the existing project doesn't pull a YAML dep — but we need one for this. Either add `org.yaml:snakeyaml-engine` or hand-roll a minimal subset that reads only flat key-value with simple lists).

**Decision**: add `snakeyaml-engine` (~200KB jar, single dep). YAML parsing is non-trivial to hand-roll correctly and the value of YAML > custom format outweighs the marginal dep cost. This is the only new dep in 12a.

---

## 7. Error handling

### 7.1 Parse errors

```
strategies/broken.qkt:7:14 — expected '=' after SIZING, got 'BUY'
strategies/broken.qkt:12:3 — unknown stream alias 'btx', did you mean 'btc'?
3 errors
```

Each error on one line: `<file>:<line>:<col> — <message>`. Final summary `<N> errors`. Exit code 1.

JSON form (`--json`):
```json
[
  {"file": "strategies/broken.qkt", "line": 7, "col": 14, "message": "expected '=' after SIZING, got 'BUY'"},
  {"file": "strategies/broken.qkt", "line": 12, "col": 3, "message": "unknown stream alias 'btx', did you mean 'btc'?"}
]
```

### 7.2 Argument errors

`qkt: error: missing required flag --from`. Exit 2. (Distinct from parse errors so CI can distinguish "tool misuse" from "broken strategy file.")

### 7.3 Runtime errors (during `run` or `backtest`)

Default: print one-line message to stderr, exit 1. With `--debug`: print stack trace.

```
$ qkt backtest foo.qkt --from 2024-01 --to 2024-06
qkt: error: data source 'local' has no data for symbol BTCUSDT in range 2024-01-01..2024-06-01
       (run 'qkt backtest --debug' for stack trace)
$ echo $?
1
```

### 7.4 SIGINT during `run`

Trap `Ctrl+C`, print `[INFO] graceful shutdown initiated`, finish current tick processing, optionally flatten positions, exit 0.

---

## 8. Testing strategy

Per qkt convention: real types, no mocks, JUnit 5 + AssertJ, deterministic fixtures.

### 8.1 Unit tests

- `ArgsTest` — flag parsing, options with values, missing required, positional indices.
- `ParseCommandTest` — invokes `ParseCommand.run()` with a fixture file, asserts stdout/exit-code.
- `BacktestCommandTest` — fixture `.qkt` + tiny `.csv` of ticks → expected report shape.
- `RunCommandTest` — uses a `BoundedTickFeed` that closes after N ticks instead of running forever; asserts `RunCommand.run(args)` exits cleanly with expected log lines.

### 8.2 End-to-end CLI tests

`EndToEndCliTest` invokes `Main.main(arrayOf("backtest", "foo.qkt", "--from", "...", "--to", "..."))` and asserts exit code + captured stdout. No process fork — same JVM. Captures `System.out` / `System.err` via `PrintStream` redirection (set/restore in `@BeforeEach` / `@AfterEach`).

### 8.3 Distribution tests

`DistTest` runs `./gradlew installDist`, then shells out to `build/install/qkt/bin/qkt --version` via `ProcessBuilder`, asserts exit 0 and matching version string. Catches launcher-script bugs.

---

## 9. Risk

**Risk: Low.** The CLI is a thin wrapper around already-shipped APIs (`Dsl.parseFile`, `AstCompiler`, `Backtest`, `TradingPipeline`). No engine changes. No new architectural surface. Mitigations:
- Each subcommand is a separate class — failure modes don't cross-contaminate.
- Tests run the CLI in-process, so `Main.main(...)` is invoked under coverage with deterministic fixtures.
- Distribution test catches launcher-script/classpath issues.

**Risk: YAML dependency.** Adds `snakeyaml-engine` (~200KB). Mitigated by isolating the YAML loader in `Config.kt` so swapping libraries (or hand-rolling) is one file's worth of change.

**Risk: SIGINT handling on the JVM.** Java's signal handling has historical quirks. Mitigated by using `Runtime.getRuntime().addShutdownHook(...)` (well-supported, documented) rather than direct `sun.misc.Signal` (unsupported, varies by JVM).

**Risk: SIGINT during `run` mid-tick.** A position may have a half-emitted broker order at the moment of interrupt. Mitigated by: shutdown hook drains the event bus, waits for in-flight orders to settle (with a 5s timeout), then exits. Document explicitly: graceful shutdown is best-effort; a hard `kill -9` will leave positions in whatever state the broker recorded.

---

## 10. Phase decomposition (preview for the plan)

Approximately 10 tasks.

1. **Gradle `application` plugin wiring.** `installDist` produces `bin/qkt`. End-to-end smoke test: `qkt --version`.
2. **`Args` parser** + tests.
3. **`Main` dispatch.** Routes argv[0] to parse/run/backtest/version/help.
4. **`ParseCommand`.** Parses the file, prints `ok` or error list, exits.
5. **`Config` loader.** YAML + env-var expansion. Adds `snakeyaml-engine` dep.
6. **`BacktestCommand`** — text output. Wires CLI flags to `Backtest` constructor.
7. **`BacktestCommand`** — JSON output. Hand-rolled emitter for `BacktestResult`.
8. **`RunCommand`.** Wires CLI flags to `TradingPipeline`. Shutdown hook for graceful SIGINT.
9. **End-to-end CLI tests.** Spawn `Main.main(...)` in-process. Cover all three subcommands.
10. **Distribution test + release-process docs update.** `./gradlew installDist` + spawn `bin/qkt --version` via `ProcessBuilder`. Update `docs/release-process.md`.
11. **Phase 12a changelog.**

---

## 11. References

- Master spec (Phase 11/12 roadmap): `docs/superpowers/specs/2026-05-07-trading-engine-phase11-master-design.md` §10.
- Phase 11f (parser, `Dsl.parseFile`): `docs/superpowers/specs/2026-05-08-trading-engine-phase11f-design.md`.
- Phase 11a (release process, GitHub Releases): `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`.
- Architecture overview: `docs/architecture.md`.
