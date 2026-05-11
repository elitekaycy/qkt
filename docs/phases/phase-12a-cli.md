# Phase 12a — `qkt` CLI Binary

## Summary

Phase 12a turns qkt from "a Kotlin library" into "a tool you install and run." A new `qkt` binary ships via the Gradle `application` plugin (`./gradlew installDist` produces `build/install/qkt/bin/qkt`). Three foreground subcommands cover the dev-iteration loop end-to-end: `qkt parse foo.qkt` (lint), `qkt backtest foo.qkt --from … --to …` (one-shot historical replay against `DefaultDataStore`, optional Dukascopy lazy-fetch), `qkt run foo.qkt` (paper-trade against live TradingView ticks). Errors carry `file:line:col` shape; exit codes are deterministic (0/1/2); `--json` emits machine-readable output for tooling. No daemon, no live-broker execution — those land in 12b/12c.

## What's new

- `com.qkt.cli.Main` — entry point. `runMain(argv): Int` exposed for in-process testing; `main` is a one-liner (`exitProcess(runMain(argv))`).
- `com.qkt.cli.Args` — hand-rolled flag/option/positional parser (no external CLI library).
- `com.qkt.cli.ParseCommand` — `qkt parse <file>`. Exits 0 on success; 1 on parse failure; prints each error on its own line as `<file>:<line>:<col> — <message>`.
- `com.qkt.cli.BacktestCommand` — `qkt backtest <file>` with `--from`, `--to`, `--data-root`, `--fetcher`, `--fetcher-script`, `--starting-balance`, `--symbols`, `--json`, `--config` flags. Wires `Backtest.fromSource(...)` over `DefaultDataStore` + optional `ScriptDataFetcher.dukascopy(scriptPath)`.
- `com.qkt.cli.RunCommand` — `qkt run <file>` with `--source` (default `tv`), `--flatten-on-stop`, `--shutdown-timeout`. Default subscribes to `TradingViewMarketSource` and paper-trades through `PaperBroker` via the existing `LiveSession`. SIGINT triggers graceful shutdown via `Runtime.addShutdownHook`. `--source bybit | alpaca | interactive` is rejected with a clear "live broker execution is not yet enabled in 12a" message.
- `com.qkt.cli.Config` — optional `qkt.config.yaml` loader with `${VAR}` env-var expansion. Adds `org.snakeyaml:snakeyaml-engine` as the only new runtime dep.
- `com.qkt.cli.ReportFormat` / `ReportPrinter` — text and JSON emitters for `BacktestResult`. Hand-rolled JSON, no Jackson dep.
- `com.qkt.cli.ExitCodes` — `SUCCESS = 0`, `USER_ERROR = 1`, `ARG_ERROR = 2`.
- `com.qkt.cli.BuildInfo.VERSION` — released as `0.11.6` (Phase 12a).

Existing classes touched (additive only):

- `Backtest.fromStore(...)` and `Backtest.fromSource(...)` — gain optional `startingBalance: BigDecimal = BigDecimal.ZERO` parameter that threads into the existing primary-constructor field. No prior caller breaks.
- `LiveSession` — gains optional `onTrade: (Trade, BigDecimal, String) -> Unit` callback so `qkt run` can stream trade events to stdout. Default callback is a no-op; existing callers unaffected.
- `build.gradle.kts` — `application.mainClass` swaps to `com.qkt.cli.MainKt`. Old demo entry preserved as `./gradlew runDemo`.

## Migration from previous phase

Single change for downstream code: `application.mainClass` no longer points at the old `com.qkt.app.MainKt` mock-tick demo. Use `./gradlew runDemo` to run that demo; `./gradlew run` (or `qkt`) now invokes the new CLI's help text. The old `com.qkt.app.Main.kt` file is preserved unchanged.

## Usage cookbook

### Build and install

```bash
$ ./gradlew installDist
$ export PATH="$PWD/build/install/qkt/bin:$PATH"
$ qkt --version
qkt 0.11.6
```

For a portable distribution: `./gradlew distTar` produces `build/distributions/qkt-<version>.tar`.

### `qkt parse`

```bash
$ qkt parse strategies/momentum.qkt
ok

$ qkt parse strategies/broken.qkt
strategies/broken.qkt:7:14 — expected '=' after SIZING, got 'BUY'
strategies/broken.qkt:12:3 — unknown stream alias 'btx'
2 errors
$ echo $?
1
```

Useful in editor save-hooks and CI lint passes.

### `qkt backtest`

```bash
$ qkt backtest strategies/momentum.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --data-root ./data \
    --starting-balance 10000

Trades:           47
Final realized:   1247.50
Final unrealized: 0.00
Sharpe (daily):   1.42
Max drawdown:     -340.20
```

Lazy-install missing days via Dukascopy:

```bash
$ qkt backtest strategies/momentum.qkt \
    --from 2024-01-01 --to 2024-06-01 \
    --data-root ./data \
    --fetcher dukascopy --fetcher-script ./scripts/duka_fetch.sh
```

JSON output for `jq` / monitoring / CI:

```bash
$ qkt backtest strategies/momentum.qkt --from … --to … --data-root ./data --json | jq '.finalRealized'
1247.50
```

### `qkt run`

```bash
$ qkt run strategies/momentum.qkt
[INFO] qkt 0.11.6 — strategy momentum_basket v1 — paper-trading
[INFO] subscribed: BYBIT:BTCUSDT, INTERACTIVE:XAUUSD, ALPACA:AAPL
[INFO] 2026-05-08T14:32:01Z BUY BTCUSDT qty=0.001 px=68234.50 realized=0.00
[INFO] 2026-05-08T14:38:05Z SELL BTCUSDT qty=0.001 px=68000.00 realized=-2.34
^C
[INFO] graceful shutdown initiated
[INFO] terminated; 2 trades
```

Live broker execution (`--source bybit | alpaca | interactive`) is rejected — paper-trading on TradingView ticks is the only live mode in 12a.

### `qkt.config.yaml`

```yaml
data_root: ./data
starting_balance: 10000
log_level: info

tv:
  username: ${TV_USERNAME}
  password: ${TV_PASSWORD}

fetchers:
  dukascopy:
    script: ./scripts/duka_fetch.sh

# Reserved for 12c+ live broker execution
brokers:
  bybit:
    api_key: ${BYBIT_API_KEY}
    api_secret: ${BYBIT_API_SECRET}
```

Resolution order (later overrides earlier): compiled-in defaults → YAML file → environment variables → CLI flags.

## Testing patterns

### In-process Main invocation

`Main.runMain(argv: Array<String>): Int` is the testable entry point. Tests redirect `System.out`/`System.err` to a buffer, invoke `runMain(...)`, and assert exit code + captured output:

```kotlin
val out = ByteArrayOutputStream()
val orig = System.out
System.setOut(PrintStream(out))
try {
    val code = runMain(arrayOf("parse", "src/test/resources/cli/valid_strategy.qkt"))
    assertThat(code).isEqualTo(0)
    assertThat(out.toString()).contains("ok")
} finally {
    System.setOut(orig)
}
```

### `RunCommand` test fixture

`RunCommand`'s default `sourceFactory` constructs `TradingViewMarketSource.connect()`. Tests override it with a bounded in-memory `MarketSource` so no real websocket connections happen. The same constructor parameter is used for production (default) and tests (override).

### Distribution test

`DistTest` shells out to `./gradlew installDist` and forks `build/install/qkt/bin/qkt --version` via `ProcessBuilder`. Catches launcher-script bugs that in-process tests miss.

## Known limitations

- **No daemon.** No `qkt deploy / list / logs / status / stop`. Phase 12c.
- **No HTTP observability port.** Phase 12b.
- **No live broker execution.** Live tick subscription via TradingView works; placing real orders on Bybit/IB/Alpaca is rejected. Phase 12c+.
- **No Docker base image.** Phase 12c (the daemon makes the image meaningful).
- **No package-manager distribution** (Homebrew, apt, .deb, .rpm). Phase 12d. Use the tarball release or `./gradlew installDist`.
- **No `qkt new` / scaffold.** Author `.qkt` files by hand (or copy a fixture from `src/test/resources/dsl/`).
- **No `qkt fmt`.** Phase 13+ if there's demand.
- **No source-position carry-through into runtime errors.** Parse errors point at line:col; runtime errors at compile/exec time do not yet trace back to source.
- **Exchange-prefixed symbols.** `qkt run` subscribes to `BYBIT:BTCUSDT` form on TradingView, but DSL-compiled strategies key off the raw `BTCUSDT`. The `LiveSession` wires this end-to-end; in tests we sidestep with a fake source. Real cross-broker symbol-mapping is a 12c concern.

## References

- Spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`
- Plan: `docs/superpowers/plans/2026-05-08-trading-engine-phase12a.md`
- Phase 11f (parser, `Dsl.parseFile`): `docs/superpowers/specs/2026-05-08-trading-engine-phase11f-design.md`
- Phase 11a (release process, GitHub Releases): `docs/superpowers/specs/2026-05-07-trading-engine-phase11a-design.md`
- Architecture overview: `docs/architecture.md`
- Merge commit: 2277c5a
