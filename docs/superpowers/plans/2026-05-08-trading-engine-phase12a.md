# Phase 12a — `qkt` CLI Binary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the `qkt` CLI binary with three foreground subcommands — `parse`, `backtest`, `run` — plus `--version` and `--help`. Distribution via Gradle's `application` plugin (`./gradlew installDist` produces a runnable `bin/qkt`). Adds one new dep (`snakeyaml-engine`) for the optional `qkt.config.yaml` loader.

**Architecture:** New package `com.qkt.cli` with `Main` entry point, hand-rolled `Args` parser, and one class per subcommand. Each subcommand is a thin wrapper over already-shipped APIs (`Dsl.parseFile`, `AstCompiler`, `Backtest`). No engine changes. Output is human-readable text by default; `--json` selects machine output. Configuration layers (defaults → YAML → env → flags). Deterministic exit codes (0/1/2).

**Tech Stack:** Kotlin 1.9, JUnit 5 + AssertJ, JDK 21, Gradle. One new external dependency: `org.snakeyaml:snakeyaml-engine`.

**Spec:** `docs/superpowers/specs/2026-05-08-trading-engine-phase12a-design.md`.

**Branch:** `phase12a-cli` — cut from `main` at start of Task 1.

---

## Design notes

### CLI surface

```
qkt parse <file>                         # validate, exit 0/1
qkt backtest <file> --from D --to D ...  # one-shot backtest, print report
qkt run <file> [--source local ...]      # event-by-event replay (12a) or live (12c+)
qkt --version
qkt --help
qkt help <subcommand>
```

### Args parser

Hand-rolled. ~80 LoC. Subcommand is `argv[0]`. Flags are `--name value` for options, `--name` for booleans. Positional args via filtered list.

```kotlin
class Args(private val argv: Array<String>) {
    val subcommand: String = argv.getOrNull(0) ?: "help"
    private val rest = argv.drop(1)

    fun positional(idx: Int): String? = rest.filter { !it.startsWith("--") }.getOrNull(idx)
    fun flag(name: String): Boolean = "--$name" in rest
    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }
    fun requireOption(name: String): String =
        option(name) ?: throw ArgError("missing required flag --$name")
    fun requirePositional(idx: Int, label: String): String =
        positional(idx) ?: throw ArgError("missing required argument: $label")
}

class ArgError(msg: String) : RuntimeException(msg)
```

### Main dispatch

```kotlin
fun main(argv: Array<String>) {
    val args = Args(argv)
    val code = try {
        when (args.subcommand) {
            "parse" -> ParseCommand(args).run()
            "backtest" -> BacktestCommand(args).run()
            "run" -> RunCommand(args).run()
            "--version" -> { println("qkt ${BuildInfo.version}"); 0 }
            "--help", "help" -> { printHelp(args); 0 }
            else -> { System.err.println("qkt: unknown subcommand '${args.subcommand}'"); 2 }
        }
    } catch (e: ArgError) {
        System.err.println("qkt: error: ${e.message}")
        2
    }
    kotlin.system.exitProcess(code)
}
```

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Fixable user failure (parse error, missing data, runtime error) |
| 2 | Argument error (missing flag, unknown subcommand) |

### Configuration layering

1. Compiled-in defaults (e.g. `--source = local`).
2. `qkt.config.yaml` if present at `--config <path>` or `./qkt.config.yaml`.
3. Environment variables (`QKT_SOURCE`, `QKT_DATA_DIR`, `QKT_LOG_LEVEL`).
4. CLI flags.

Each layer overrides the previous.

### Subcommand: `qkt run` semantics in 12a

`qkt run` operates against a **live tick stream** via the existing `TradingViewMarketSource` (default `--source tv`), paper-trading through `PaperBroker`. Each trade is logged to stdout as it happens. SIGINT triggers graceful shutdown via `Runtime.addShutdownHook` (drain bus, optionally flatten positions, print final equity, exit). With `--source bybit | alpaca | interactive` the command rejects with "live broker execution is not yet enabled in 12a." Real-broker placement is gated to Phase 12c+; live tick subscription via TV works today.

### Subcommand: `qkt backtest` data sources

Default is `DefaultDataStore` rooted at `--data-root ./data`. The store reads `.csv.gz` (or `.csv`) files per symbol per day with a `qkt-csv-v1` manifest. With `--fetcher dukascopy --fetcher-script <path>`, missing days are lazy-fetched via `ScriptDataFetcher.dukascopy(scriptPath)`. CLI just wires the existing classes:

```kotlin
val store = DefaultDataStore.fromRoot(Paths.get(dataRoot))
val fetcher = if (args.option("fetcher") == "dukascopy")
    ScriptDataFetcher.dukascopy(Paths.get(args.requireOption("fetcher-script")))
else null
val source = LocalMarketSource(store, FixedClock(toEpochMs))
val backtest = Backtest.fromSource(strategies, source = source, request = MarketRequest(syms, from, to))
```

### File structure

#### New files

```
src/main/kotlin/com/qkt/cli/
├── Main.kt
├── Args.kt
├── BuildInfo.kt
├── Config.kt
├── ParseCommand.kt
├── BacktestCommand.kt
├── RunCommand.kt
├── ReportFormat.kt          # text + JSON emitters for BacktestResult and ParseError
└── ExitCodes.kt

src/test/kotlin/com/qkt/cli/
├── ArgsTest.kt
├── ParseCommandTest.kt
├── BacktestCommandTest.kt
├── RunCommandTest.kt
├── ConfigTest.kt
└── EndToEndCliTest.kt

src/test/resources/cli/
├── valid_strategy.qkt
├── broken_strategy.qkt
├── tiny_btc_ticks.csv
└── qkt.config.yaml
```

#### Modified files

```
build.gradle.kts                                # application plugin + snakeyaml dep
docs/release-process.md                         # add "publish binary tarball" step
docs/phases/phase-12a-cli.md                    # changelog (Task 11)
```

---

## Tasks

### Task 1: Gradle wiring + minimal `Main`

`application` plugin produces a runnable `bin/qkt`. Smoke-test with `qkt --version`.

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/com/qkt/cli/Main.kt`
- Create: `src/main/kotlin/com/qkt/cli/BuildInfo.kt`

- [ ] **Step 1: Cut branch**

```bash
git checkout -b phase12a-cli
```

- [ ] **Step 2: Add `application` plugin + main class**

Edit `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm")
    application
    // ... existing plugins
}

application {
    mainClass.set("com.qkt.cli.MainKt")
    applicationName = "qkt"
}
```

- [ ] **Step 3: Define `BuildInfo`**

```kotlin
package com.qkt.cli

object BuildInfo {
    const val version: String = "0.11.6"
}
```

(Track this with each phase release; bump in the changelog task.)

- [ ] **Step 4: Minimal `Main`**

```kotlin
package com.qkt.cli

fun main(argv: Array<String>) {
    val sub = argv.getOrNull(0) ?: "help"
    when (sub) {
        "--version", "-v" -> { println("qkt ${BuildInfo.version}"); kotlin.system.exitProcess(0) }
        else -> {
            println("qkt — Kotlin trading-strategy DSL runtime")
            println("Usage: qkt <subcommand> [args]  (subcommands: parse, backtest, run)")
            kotlin.system.exitProcess(if (sub == "help" || sub == "--help") 0 else 2)
        }
    }
}
```

- [ ] **Step 5: Smoke-test the launcher**

```bash
./gradlew installDist
build/install/qkt/bin/qkt --version
# expected: qkt 0.11.6
```

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat
git add build.gradle.kts src/main/kotlin/com/qkt/cli/
git commit -m "feat(cli): bootstrap qkt CLI with application plugin"
```

---

### Task 2: `Args` parser + tests

Hand-rolled flag parser. No deps.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/Args.kt`
- Create: `src/test/kotlin/com/qkt/cli/ArgsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ArgsTest {
    @Test
    fun `extracts subcommand and positional args`() {
        val a = Args(arrayOf("backtest", "foo.qkt"))
        assertThat(a.subcommand).isEqualTo("backtest")
        assertThat(a.positional(0)).isEqualTo("foo.qkt")
    }

    @Test
    fun `extracts boolean flags`() {
        val a = Args(arrayOf("backtest", "foo.qkt", "--json"))
        assertThat(a.flag("json")).isTrue
        assertThat(a.flag("debug")).isFalse
    }

    @Test
    fun `extracts options with values`() {
        val a = Args(arrayOf("backtest", "foo.qkt", "--from", "2024-01-01", "--to", "2024-06-01"))
        assertThat(a.option("from")).isEqualTo("2024-01-01")
        assertThat(a.option("to")).isEqualTo("2024-06-01")
        assertThat(a.option("missing")).isNull()
    }

    @Test
    fun `requireOption throws on missing`() {
        val a = Args(arrayOf("backtest", "foo.qkt"))
        assertThatThrownBy { a.requireOption("from") }
            .isInstanceOf(ArgError::class.java)
            .hasMessageContaining("--from")
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
package com.qkt.cli

class Args(argv: Array<String>) {
    val subcommand: String = argv.getOrNull(0) ?: "help"
    private val rest: List<String> = argv.drop(1)

    fun positional(idx: Int): String? = rest.filter { !it.startsWith("--") }.getOrNull(idx)

    fun flag(name: String): Boolean = "--$name" in rest

    fun option(name: String): String? {
        val i = rest.indexOf("--$name")
        return if (i >= 0 && i + 1 < rest.size) rest[i + 1] else null
    }

    fun requireOption(name: String): String =
        option(name) ?: throw ArgError("missing required flag --$name")

    fun requirePositional(
        idx: Int,
        label: String,
    ): String = positional(idx) ?: throw ArgError("missing required argument: $label")
}

class ArgError(msg: String) : RuntimeException(msg)
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.ArgsTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): hand-rolled Args parser"
```

---

### Task 3: `ParseCommand` + Main dispatch wiring

`qkt parse <file>` exits 0 on parse success, 1 on parse failure. Errors printed `<file>:<line>:<col> — <msg>`.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/ParseCommand.kt`
- Create: `src/main/kotlin/com/qkt/cli/ExitCodes.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt`
- Create: `src/test/kotlin/com/qkt/cli/ParseCommandTest.kt`
- Create: `src/test/resources/cli/valid_strategy.qkt`
- Create: `src/test/resources/cli/broken_strategy.qkt`

- [ ] **Step 1: Author fixtures**

`src/test/resources/cli/valid_strategy.qkt`:
```
STRATEGY example VERSION 1
SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
```

`src/test/resources/cli/broken_strategy.qkt`:
```
STRATEGY example VERSION 1
SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING
```

- [ ] **Step 2: ExitCodes constants**

```kotlin
package com.qkt.cli

object ExitCodes {
    const val SUCCESS = 0
    const val USER_ERROR = 1
    const val ARG_ERROR = 2
}
```

- [ ] **Step 3: Write failing test**

```kotlin
package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class ParseCommandTest {
    private fun runParse(file: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val code = ParseCommand(Args(arrayOf("parse", file))).run()
            code to (out.toString() + err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    fun `valid strategy exits 0`() {
        val (code, out) = runParse("src/test/resources/cli/valid_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out).contains("ok")
    }

    @Test
    fun `broken strategy exits 1 with error list`() {
        val (code, out) = runParse("src/test/resources/cli/broken_strategy.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(out).contains("broken_strategy.qkt:")
        assertThat(out).contains("error")
    }

    @Test
    fun `missing file exits 1`() {
        val (code, _) = runParse("does_not_exist.qkt")
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
```

- [ ] **Step 4: Implement `ParseCommand`**

```kotlin
package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path

class ParseCommand(
    private val args: Args,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        return when (val result = Dsl.parseFile(path)) {
            is ParseResult.Success -> {
                println("ok")
                ExitCodes.SUCCESS
            }
            is ParseResult.Failure -> {
                for (e in result.errors) {
                    System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                }
                System.err.println("${result.errors.size} error${if (result.errors.size != 1) "s" else ""}")
                ExitCodes.USER_ERROR
            }
        }
    }
}
```

- [ ] **Step 5: Wire into `Main`**

Replace the current `Main.kt` body with:

```kotlin
package com.qkt.cli

fun main(argv: Array<String>) {
    val args = Args(argv)
    val code =
        try {
            when (args.subcommand) {
                "parse" -> ParseCommand(args).run()
                "--version", "-v" -> {
                    println("qkt ${BuildInfo.version}")
                    ExitCodes.SUCCESS
                }
                "--help", "help" -> {
                    printHelp()
                    ExitCodes.SUCCESS
                }
                else -> {
                    System.err.println("qkt: unknown subcommand '${args.subcommand}'")
                    ExitCodes.ARG_ERROR
                }
            }
        } catch (e: ArgError) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.ARG_ERROR
        }
    kotlin.system.exitProcess(code)
}

private fun printHelp() {
    println(
        """
        qkt — Kotlin trading-strategy DSL runtime

        USAGE
            qkt <subcommand> [arguments]

        SUBCOMMANDS
            parse <file>            parse and validate a .qkt file
            backtest <file> ...     run a one-shot backtest (Phase 12a Task 6)
            run <file> ...          run a strategy in foreground (Phase 12a Task 8)

        SEE ALSO
            qkt --version
            qkt help <subcommand>
        """.trimIndent(),
    )
}
```

- [ ] **Step 6: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.ParseCommandTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): qkt parse subcommand"
```

---

### Task 4: `Config` loader (YAML + env-var expansion)

Adds the `snakeyaml-engine` dep. Loads `qkt.config.yaml`, expands `${VAR}`, exposes typed accessors.

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/com/qkt/cli/Config.kt`
- Create: `src/test/kotlin/com/qkt/cli/ConfigTest.kt`
- Create: `src/test/resources/cli/qkt.config.yaml`

- [ ] **Step 1: Add dep**

`build.gradle.kts`:

```kotlin
dependencies {
    // ... existing
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
}
```

- [ ] **Step 2: Author fixture config**

`src/test/resources/cli/qkt.config.yaml`:

```yaml
source: bybit
data_dir: ./fixtures
starting_balance: 10000
log_level: info
brokers:
  bybit:
    api_key: ${TEST_BYBIT_API_KEY}
```

- [ ] **Step 3: Write failing test**

```kotlin
package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ConfigTest {
    @Test
    fun `loads top-level fields with env-var expansion`() {
        System.setProperty("TEST_BYBIT_API_KEY", "secret_value")  // not actual env, but Config should consult both
        val c = Config.load(Path.of("src/test/resources/cli/qkt.config.yaml"))
        assertThat(c.source).isEqualTo("bybit")
        assertThat(c.dataDir).isEqualTo("./fixtures")
        assertThat(c.startingBalance).isEqualByComparingTo("10000")
        assertThat(c.brokers["bybit"]?.get("api_key")).isEqualTo("secret_value")
    }

    @Test
    fun `missing config returns defaults`() {
        val c = Config.load(Path.of("nonexistent.yaml"))
        assertThat(c.source).isEqualTo("local")
        assertThat(c.dataDir).isEqualTo("./data")
        assertThat(c.startingBalance).isEqualByComparingTo("0")
    }
}
```

- [ ] **Step 4: Implement `Config`**

```kotlin
package com.qkt.cli

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

data class Config(
    val source: String = "tv",
    val dataRoot: String = "./data",
    val startingBalance: BigDecimal = BigDecimal("10000"),
    val logLevel: String = "info",
    val tv: Map<String, String> = emptyMap(),
    val fetchers: Map<String, Map<String, String>> = emptyMap(),
    val brokers: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        fun load(path: Path): Config {
            if (!Files.exists(path)) return Config()
            val raw = Files.readString(path)
            val expanded = expandVars(raw)
            @Suppress("UNCHECKED_CAST")
            val map =
                Load(LoadSettings.builder().build())
                    .loadFromString(expanded) as? Map<String, Any?>
                    ?: return Config()
            return Config(
                source = (map["source"] as? String) ?: "local",
                dataDir = (map["data_dir"] as? String) ?: "./data",
                startingBalance =
                    (map["starting_balance"]?.toString() ?: "0").let(::BigDecimal),
                logLevel = (map["log_level"] as? String) ?: "info",
                brokers = parseBrokers(map["brokers"]),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseBrokers(raw: Any?): Map<String, Map<String, String>> {
            val outer = raw as? Map<String, Any?> ?: return emptyMap()
            return outer.mapValues { (_, v) ->
                (v as? Map<String, Any?> ?: emptyMap())
                    .mapValues { (_, vv) -> vv?.toString() ?: "" }
            }
        }

        private val varRegex = Regex("\\$\\{([A-Z_][A-Z_0-9]*)}")

        private fun expandVars(s: String): String =
            varRegex.replace(s) { m ->
                val name = m.groupValues[1]
                System.getenv(name) ?: System.getProperty(name) ?: m.value
            }
    }
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew test --tests com.qkt.cli.ConfigTest
./gradlew ktlintFormat
git add build.gradle.kts src/main src/test
git commit -m "feat(cli): qkt.config.yaml loader with env-var expansion"
```

---

### Task 5: `BacktestCommand` — text output

`qkt backtest <file> --from D --to D [--source local --data-dir ./data]`. Wires CLI → existing `Backtest.fromStore`.

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt`
- Create: `src/main/kotlin/com/qkt/cli/ReportFormat.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` (add backtest dispatch)
- Create: `src/test/kotlin/com/qkt/cli/BacktestCommandTest.kt`

- [ ] **Step 1: Use `DefaultDataStore` directly**

Author `src/test/resources/cli/data/symbols/BTCUSDT/2024-01-01.csv` (or `.csv.gz`) with a few rows of tick data plus the matching `manifest.json` (schema `qkt-csv-v1`). Mirror the format in any existing `DefaultDataStoreTest` fixtures.

The CLI wires `--data-root <path>` straight into `DefaultDataStore.fromRoot(...)`. No new code under `com.qkt.cli` for storage — just consume the existing class. If the existing `DefaultDataStore` doesn't have a `fromRoot` factory, add it (single-line forwarding constructor) at execution time.

- [ ] **Step 2: Implement `ReportFormat`**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.dsl.parse.ParseError
import java.io.PrintStream

sealed interface ReportFormat {
    data object Text : ReportFormat
    data object Json : ReportFormat
}

object ReportPrinter {
    fun print(
        result: BacktestResult,
        fmt: ReportFormat,
        out: PrintStream,
    ) {
        when (fmt) {
            ReportFormat.Text -> printText(result, out)
            ReportFormat.Json -> printJson(result, out)
        }
    }

    private fun printText(r: BacktestResult, out: PrintStream) {
        out.println("Trades:          ${r.trades.size}")
        out.println("Final realized:  ${r.global.summary.finalRealized}")
        out.println("Final unrealized: ${r.global.summary.finalUnrealized}")
        out.println("Sharpe (daily):  ${r.global.summary.sharpe ?: "n/a"}")
        out.println("Max drawdown:    ${r.global.summary.maxDrawdown}")
    }

    private fun printJson(r: BacktestResult, out: PrintStream) {
        // Hand-rolled JSON. Use double quotes, no escapes beyond control chars.
        out.println(
            """{"trades":${r.trades.size},""" +
                """"finalRealized":${r.global.summary.finalRealized},""" +
                """"finalUnrealized":${r.global.summary.finalUnrealized},""" +
                """"sharpe":${r.global.summary.sharpe ?: "null"},""" +
                """"maxDrawdown":${r.global.summary.maxDrawdown}}""",
        )
    }
}
```

(Field names tracked at execution time against actual `BacktestResult` shape.)

- [ ] **Step 3: Implement `BacktestCommand`**

Wires `--from`, `--to`, `--source`, `--data-dir`, `--starting-balance`, `--json`, `--config` flags through to `Backtest.fromStore` (or equivalent factory). Returns 0 on success, 1 on parse/runtime error.

- [ ] **Step 4: Wire into Main dispatch**

Add `"backtest" -> BacktestCommand(args).run()` to the dispatch `when`.

- [ ] **Step 5: Tests + commit**

```bash
./gradlew test --tests com.qkt.cli.BacktestCommandTest
./gradlew ktlintFormat
git add src/main src/test
git commit -m "feat(cli): qkt backtest subcommand with text output"
```

---

### Task 6: `BacktestCommand` — `--json` output

Already half-implemented in Task 5's `ReportPrinter`. This task hardens the JSON output: covers all `BacktestResult` fields, asserts shape via tests, ensures BigDecimal serialization is consistent (no scientific notation, fixed scale).

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/ReportFormat.kt`
- Modify: `src/test/kotlin/com/qkt/cli/BacktestCommandTest.kt`

- [ ] **Step 1: Tests** asserting `--json` output is parseable and contains expected fields.
- [ ] **Step 2: Iterate** on the hand-rolled emitter until tests pass.
- [ ] **Step 3: Commit:** `feat(cli): qkt backtest JSON output`.

---

### Task 7: `RunCommand` — replay mode + SIGINT

`qkt run <file>` runs the strategy in foreground replay mode against historical data (12a scope). Logs each trade as it happens. SIGINT triggers graceful shutdown via `Runtime.addShutdownHook`. With `--source bybit | alpaca | interactive` rejects with "live trading is not yet enabled in 12a."

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/RunCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` (add run dispatch)
- Create: `src/test/kotlin/com/qkt/cli/RunCommandTest.kt`

- [ ] **Step 1: Tests** — use a `BoundedTickFeed` fixture that closes after N ticks (substituting for `TradingViewMarketSource` in tests). Assert each trade prints to stdout in order, exit code 0 when feed closes.

- [ ] **Step 2: Implement `RunCommand`** — for `--source tv` (default), construct `TradingViewMarketSource` (or test fixture), build `TradingPipeline` with `PaperBroker` as the broker, attach a per-trade `onFilled` callback that prints to stdout. For `--source bybit | alpaca | interactive`, error with the rejection message.

- [ ] **Step 3: SIGINT handling** — `Runtime.getRuntime().addShutdownHook(Thread { ... })` that drains the bus, optionally flattens positions if `--flatten-on-stop`, prints final equity. Drain timeout 5s (configurable via `--shutdown-timeout`).

- [ ] **Step 4: Reject live broker sources** explicit error message:
```kotlin
if (config.source != "tv") error(
    "live broker execution ('--source ${config.source}') is not yet enabled in 12a; " +
    "use --source tv (default) for paper-trading on live TradingView ticks."
)
```

- [ ] **Step 5: Run + commit:** `feat(cli): qkt run subcommand with TradingView paper-trading`.

---

### Task 8: End-to-end CLI tests

Spawn `Main.main(...)` in-process with real argv arrays. Capture `System.out` / `System.err`. Assert exit codes via a custom `SecurityManager` that blocks `exitProcess` and records the code (or via process-fork — but in-process is faster).

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/EndToEndCliTest.kt`

- [ ] **Step 1: Test harness** — captures stdout/stderr and exit code (intercept `kotlin.system.exitProcess` via a `Main.runWithoutExit(argv)` helper for testability).

Refactor `Main.kt` slightly:

```kotlin
fun main(argv: Array<String>) = kotlin.system.exitProcess(runMain(argv))

internal fun runMain(argv: Array<String>): Int {
    // (existing dispatch body, returning the code instead of calling exitProcess)
}
```

- [ ] **Step 2: Tests** — `qkt --version`, `qkt parse <ok>`, `qkt parse <broken>`, `qkt backtest …`, `qkt run …`, `qkt unknown` (arg error).

- [ ] **Step 3: Commit:** `test(cli): end-to-end CLI tests`.

---

### Task 9: Distribution test + release-process docs update

Verify `./gradlew installDist` produces a runnable launcher. Update `docs/release-process.md` with the binary publish step.

**Files:**
- Create: `src/test/kotlin/com/qkt/cli/DistTest.kt` (gated to skip on CI if necessary — runs `gradlew installDist` then forks `bin/qkt --version`)
- Modify: `docs/release-process.md`

- [ ] **Step 1: Implement DistTest** — uses `ProcessBuilder` to invoke `build/install/qkt/bin/qkt --version`, asserts exit 0 + matching version.

- [ ] **Step 2: Update release-process.md** — add steps:
  - `./gradlew distTar` builds `build/distributions/qkt-<version>.tar`.
  - `gh release upload <tag> build/distributions/qkt-<version>.tar`.
  - Document install instructions in the GitHub Release body.

- [ ] **Step 3: Commit:** `chore(cli): distribution test + release-process docs`.

---

### Task 10: Phase 12a changelog

Per qkt SKILL.md §6.

**Files:**
- Create: `docs/phases/phase-12a-cli.md`

Sections:
1. Summary (3 sentences).
2. What's new (bullets — every new public surface).
3. Migration (none — purely additive).
4. Usage cookbook (terminal sessions for each subcommand).
5. Testing patterns (in-process Main invocation).
6. Known limitations (no daemon, no live, no Docker, no package managers).
7. References.

- [ ] **Step 1: Write the changelog**.
- [ ] **Step 2: Commit:** `docs: phase 12a changelog`.

---

## Self-review checklist

After all tasks complete:

- [ ] `./gradlew build` green.
- [ ] All commit messages match `<type>(<scope>): <subject>`.
- [ ] No AI footers, no emoji.
- [ ] `./gradlew installDist && build/install/qkt/bin/qkt --version` works.
- [ ] `qkt parse src/test/resources/cli/valid_strategy.qkt` exits 0.
- [ ] `qkt backtest …` produces text + JSON output as specified.
- [ ] `qkt run …` prints trade events and handles SIGINT cleanly.
- [ ] Phase 12a changelog `Merge commit:` filled in after merge.

---

## Merge

```bash
git checkout main
git merge --no-ff phase12a-cli -m "merge: phase 12a qkt CLI binary"
./gradlew build   # verify
# Update changelog with merge SHA
git add docs/phases/phase-12a-cli.md
git commit -m "docs: link phase 12a changelog to merge commit"
git branch -d phase12a-cli
```
