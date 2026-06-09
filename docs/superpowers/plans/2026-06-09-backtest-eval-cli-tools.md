# Backtest evaluation CLI tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose qkt's existing backtest-evaluation engines through the CLI: `--param` overrides on `qkt backtest`, new `qkt sweep` and `qkt walkforward` commands, and accuracy fixes to the existing `qkt research`.

**Architecture:** One shared override seam (`ParamSubstitution`/`AstCompiler` gain an `overrides` map) feeds three command paths. A `BacktestContext` factory centralizes store/instruments/provisioning so a single backtest, a sweep run, and a walk-forward fold all use identical data and contract specs. `sweep` wraps `BacktestSweep`; `walkforward` wraps `WalkForwardHarness`; both rank via a shared `RankMetric`.

**Tech Stack:** Kotlin, Gradle, JUnit5 + AssertJ, the existing `com.qkt` engine (`Backtest`, `BacktestSweep`, `WalkForwardHarness`, `PerformanceReport`).

**Spec:** `docs/superpowers/specs/2026-06-09-backtest-eval-cli-tools-design.md`

**Branch:** `feat-backtest-eval-cli` (already created off `dev`).

**Conventions:** subject-only conventional commits; scope `dsl`/`cli`/`backtesting`. Run `./gradlew ktlintFormat` before any commit. Push and let CI run the full suite (do not block on a full local `./gradlew build`).

---

### Task 1: Repeated-option accessor on `Args`

`--param` is repeatable; `Args.option()` only returns the first occurrence. Add `options()`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/Args.kt`
- Test: `src/test/kotlin/com/qkt/cli/ArgsTest.kt`

- [ ] **Step 1: Write the failing test** (append inside the existing `ArgsTest` class; if the file doesn't exist, create it with the package + imports shown)

```kotlin
package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArgsTest {
    @Test
    fun `options collects every occurrence of a repeated flag in order`() {
        val args = Args(arrayOf("sweep", "s.qkt", "--param", "fast=5", "--param", "slow=10,20"))
        assertThat(args.options("param")).containsExactly("fast=5", "slow=10,20")
    }

    @Test
    fun `options is empty when the flag is absent`() {
        assertThat(Args(arrayOf("backtest", "s.qkt")).options("param")).isEmpty()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.ArgsTest" --console=plain`
Expected: FAIL — `options` unresolved reference.

- [ ] **Step 3: Add the method** (inside `class Args`, after `option`)

```kotlin
    /** Returns the value of every `--[name] <value>` occurrence, in argv order. */
    fun options(name: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            if (rest[i] == "--$name" && i + 1 < rest.size) {
                out.add(rest[i + 1])
                i += 2
            } else {
                i += 1
            }
        }
        return out
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.ArgsTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/Args.kt src/test/kotlin/com/qkt/cli/ArgsTest.kt
git commit -m "feat(cli): add repeated-option accessor to Args"
```

---

### Task 2: Parameter override seam

`ParamSubstitution.apply` and `AstCompiler.compile` gain an `overrides` map. An override replaces a `PARAM` value or a `LET` right-hand side by name, parsed as a numeric literal.

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ParamSubstitution.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt:27-28`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ParamSubstitutionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.ParamDecl
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.ast.StrategyAst
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamSubstitutionTest {
    private fun ast(
        params: List<ParamDecl> = emptyList(),
        lets: List<LetDecl> = emptyList(),
    ) = StrategyAst(
        name = "t",
        version = 1,
        streams = emptyList(),
        constants = emptyList(),
        lets = lets,
        params = params,
        defaults = null,
        rules = emptyList(),
    )

    @Test
    fun `override replaces a PARAM value`() {
        val out = ParamSubstitution.apply(
            ast(params = listOf(ParamDecl("fast", NumLit(BigDecimal("9"))))),
            overrides = mapOf("fast" to "12"),
        )
        // PARAMs are consumed; the override is what later refs resolve to. Re-declare via a LET to inspect.
        assertThat(out.params).isEmpty()
    }

    @Test
    fun `override replaces a LET right-hand side with the literal`() {
        val out = ParamSubstitution.apply(
            ast(lets = listOf(LetDecl("fast", Ref("somethingElse")))),
            overrides = mapOf("fast" to "12"),
        )
        assertThat(out.lets.single().expr).isEqualTo(NumLit(BigDecimal("12")))
    }

    @Test
    fun `unknown override name fails loudly`() {
        assertThatThrownBy {
            ParamSubstitution.apply(ast(lets = listOf(LetDecl("fast", NumLit(BigDecimal("9"))))), mapOf("nope" to "1"))
        }.hasMessageContaining("unknown parameter 'nope'")
    }

    @Test
    fun `non-numeric override fails loudly`() {
        assertThatThrownBy {
            ParamSubstitution.apply(ast(lets = listOf(LetDecl("fast", NumLit(BigDecimal("9"))))), mapOf("fast" to "abc"))
        }.hasMessageContaining("must be numeric")
    }

    @Test
    fun `no params and no overrides returns the ast unchanged`() {
        val a = ast(lets = listOf(LetDecl("fast", NumLit(BigDecimal("9")))))
        assertThat(ParamSubstitution.apply(a)).isSameAs(a)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.dsl.compile.ParamSubstitutionTest" --console=plain`
Expected: FAIL — `apply` takes only one argument.

- [ ] **Step 3: Rewrite `ParamSubstitution.apply`** (replace the whole `object ParamSubstitution { ... }` body)

```kotlin
object ParamSubstitution {
    fun apply(
        ast: StrategyAst,
        overrides: Map<String, String> = emptyMap(),
    ): StrategyAst {
        if (ast.params.isEmpty() && overrides.isEmpty()) return ast

        val paramNames = ast.params.map { it.name }.toSet()
        val letNames = ast.lets.map { it.name }.toSet()
        val parsed: Map<String, NumLit> =
            overrides.mapValues { (name, raw) ->
                require(name in paramNames || name in letNames) {
                    "unknown parameter '$name'; strategy declares: ${(paramNames + letNames).sorted()}"
                }
                val v =
                    raw.toBigDecimalOrNull()
                        ?: throw IllegalArgumentException("parameter '$name' must be numeric, got '$raw'")
                NumLit(v)
            }

        val paramValues: Map<String, ExprAst> =
            ast.params.associate { it.name to (parsed[it.name] ?: it.value) }
        val t = ExprTransform { ref -> paramValues[ref.name] ?: ref }

        return ast.copy(
            params = emptyList(),
            lets =
                ast.lets.map { let ->
                    parsed[let.name]?.let { lit -> let.copy(expr = lit) } ?: let.copy(expr = t.expr(let.expr))
                },
            rules =
                ast.rules.map { rule ->
                    when (rule) {
                        is WhenThen -> WhenThen(cond = t.expr(rule.cond), action = t.action(rule.action))
                    }
                },
            schedules = ast.schedules.map { it.copy(action = t.action(it.action)) },
            defaults = ast.defaults?.let { t.defaultsBlock(it) },
        )
    }
}
```

Add the import `import com.qkt.dsl.ast.NumLit` at the top of the file (next to the existing AST imports).

- [ ] **Step 4: Thread overrides through `AstCompiler.compile`** (modify `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`)

Change:
```kotlin
    fun compile(rawAst: StrategyAst): Strategy {
        val ast = ParamSubstitution.apply(rawAst)
```
to:
```kotlin
    fun compile(
        rawAst: StrategyAst,
        overrides: Map<String, String> = emptyMap(),
    ): Strategy {
        val ast = ParamSubstitution.apply(rawAst, overrides)
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.dsl.compile.ParamSubstitutionTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/dsl/compile/ParamSubstitution.kt src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/ParamSubstitutionTest.kt
git commit -m "feat(dsl): override PARAM/LET values via ParamSubstitution overrides"
```

---

### Task 3: `ParamGrid` — parse repeated `--param` into cartesian combos

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/ParamGrid.kt`
- Test: `src/test/kotlin/com/qkt/cli/ParamGridTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamGridTest {
    @Test
    fun `single-value flags produce one combo of plain overrides`() {
        val combos = ParamGrid.parse(listOf("fast=5", "slow=20"))
        assertThat(combos).hasSize(1)
        assertThat(combos.single().overrides).containsEntry("fast", "5").containsEntry("slow", "20")
        assertThat(combos.single().label).isEqualTo("fast=5,slow=20")
    }

    @Test
    fun `comma lists form the cartesian product with deterministic labels`() {
        val combos = ParamGrid.parse(listOf("fast=5,10", "slow=20,40"))
        assertThat(combos).hasSize(4)
        assertThat(combos.map { it.label }).containsExactlyInAnyOrder(
            "fast=5,slow=20", "fast=5,slow=40", "fast=10,slow=20", "fast=10,slow=40",
        )
    }

    @Test
    fun `no flags yields a single default combo`() {
        assertThat(ParamGrid.parse(emptyList())).containsExactly(ParamGrid.Combo("default", emptyMap()))
    }

    @Test
    fun `duplicate axis values are de-duplicated`() {
        assertThat(ParamGrid.parse(listOf("fast=5,5,10"))).hasSize(2)
    }

    @Test
    fun `a malformed token fails loudly`() {
        assertThatThrownBy { ParamGrid.parse(listOf("fast")) }.hasMessageContaining("expected NAME=VALUE")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.ParamGridTest" --console=plain`
Expected: FAIL — `ParamGrid` unresolved.

- [ ] **Step 3: Create `ParamGrid`**

```kotlin
package com.qkt.cli

/** Parses repeated `--param NAME=v1,v2,...` flags into cartesian-product combos. */
object ParamGrid {
    data class Combo(
        val label: String,
        val overrides: Map<String, String>,
    )

    /** NAME -> ordered, de-duplicated value list. */
    fun parseAxes(tokens: List<String>): Map<String, List<String>> {
        val axes = LinkedHashMap<String, MutableList<String>>()
        for (tok in tokens) {
            val eq = tok.indexOf('=')
            require(eq > 0) { "bad --param '$tok'; expected NAME=VALUE" }
            val name = tok.substring(0, eq).trim()
            require(name.isNotEmpty()) { "bad --param '$tok'; empty name" }
            val values = tok.substring(eq + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }
            require(values.isNotEmpty()) { "bad --param '$tok'; no value" }
            axes.getOrPut(name) { mutableListOf() }.addAll(values)
        }
        return axes.mapValues { (_, v) -> v.distinct() }
    }

    fun expand(axes: Map<String, List<String>>): List<Combo> {
        if (axes.isEmpty()) return listOf(Combo(label = "default", overrides = emptyMap()))
        var combos = listOf(emptyMap<String, String>())
        for ((name, values) in axes) {
            val next = mutableListOf<Map<String, String>>()
            for (acc in combos) for (v in values) next.add(acc + (name to v))
            combos = next
        }
        return combos.map { ov ->
            Combo(
                label = ov.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
                overrides = ov,
            )
        }
    }

    fun parse(tokens: List<String>): List<Combo> = expand(parseAxes(tokens))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.ParamGridTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/ParamGrid.kt src/test/kotlin/com/qkt/cli/ParamGridTest.kt
git commit -m "feat(cli): add ParamGrid cartesian-product parser"
```

---

### Task 4: `RankMetric` — `--rank` selector + comparator

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/RankMetric.kt`
- Test: `src/test/kotlin/com/qkt/cli/RankMetricTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SampleCadence
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RankMetricTest {
    private fun resultWithSharpe(sharpe: BigDecimal?): BacktestResult =
        BacktestResult(
            trades = emptyList(),
            rejections = emptyList(),
            finalPositions = emptyMap(),
            global =
                PerformanceReport(
                    realizedTotal = BigDecimal.ZERO,
                    unrealizedTotal = BigDecimal.ZERO,
                    totalPnL = BigDecimal.ZERO,
                    tradeCount = 0,
                    winRate = BigDecimal.ZERO,
                    maxDrawdown = BigDecimal.ZERO,
                    profitFactor = null,
                    avgWin = BigDecimal.ZERO,
                    avgLoss = BigDecimal.ZERO,
                    largestWin = BigDecimal.ZERO,
                    largestLoss = BigDecimal.ZERO,
                    maxConsecutiveLosses = 0,
                    sharpeRatio = sharpe,
                    calmarRatio = null,
                    equityCurve = emptyList(),
                ),
            perStrategy = emptyMap(),
            cadence = SampleCadence.TICK,
        )

    @Test
    fun `fromFlag defaults to sharpe and rejects unknown`() {
        assertThat(RankMetric.fromFlag(null)).isEqualTo(RankMetric.SHARPE)
        assertThat(RankMetric.fromFlag("calmar")).isEqualTo(RankMetric.CALMAR)
        assertThatThrownBy { RankMetric.fromFlag("bogus") }.hasMessageContaining("unknown --rank")
    }

    @Test
    fun `score sends a null metric strictly last`() {
        val present = RankMetric.SHARPE.score(resultWithSharpe(BigDecimal("1.5")))
        val absent = RankMetric.SHARPE.score(resultWithSharpe(null))
        assertThat(present).isGreaterThan(absent)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.RankMetricTest" --console=plain`
Expected: FAIL — `RankMetric` unresolved.

- [ ] **Step 3: Create `RankMetric`**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.PerformanceReport
import java.math.BigDecimal

/** Maps a `--rank` flag to a PerformanceReport metric. Higher is better; an undefined metric ranks last. */
enum class RankMetric(
    val flag: String,
) {
    SHARPE("sharpe"),
    CALMAR("calmar"),
    PROFIT_FACTOR("profitFactor"),
    TOTAL_PNL("totalPnL"),
    WIN_RATE("winRate"),
    ;

    fun valueOf(report: PerformanceReport): BigDecimal? =
        when (this) {
            SHARPE -> report.sharpeRatio
            CALMAR -> report.calmarRatio
            PROFIT_FACTOR -> report.profitFactor
            TOTAL_PNL -> report.totalPnL
            WIN_RATE -> report.winRate
        }

    /** Ranking score: the metric, or a sentinel that sorts strictly last when undefined. */
    fun score(result: BacktestResult): BigDecimal = valueOf(result.global) ?: NULLS_LAST

    companion object {
        private val NULLS_LAST: BigDecimal = BigDecimal("-1E18")

        fun fromFlag(flag: String?): RankMetric =
            when {
                flag == null -> SHARPE
                else ->
                    entries.firstOrNull { it.flag.equals(flag, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "unknown --rank '$flag'; valid: ${entries.joinToString(", ") { it.flag }}",
                        )
            }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.RankMetricTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/RankMetric.kt src/test/kotlin/com/qkt/cli/RankMetricTest.kt
git commit -m "feat(cli): add RankMetric selector for sweep/walk-forward ranking"
```

---

### Task 5: `BacktestContext` — shared setup, extracted from `BacktestCommand`

Move the store/instruments/provisioning/candleWindow/brokerKind setup out of `BacktestCommand` into a reusable factory with a `backtest(overrides, range)` builder. `BacktestCommand` keeps working (existing `BacktestCommandDataTest` is the regression guard).

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/BacktestContext.kt`
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt`
- Test (regression): `src/test/kotlin/com/qkt/cli/BacktestCommandDataTest.kt` (unchanged — must still pass)

- [ ] **Step 1: Read the current `BacktestCommand.run()`** in full so the extraction preserves behavior exactly (symbols resolution, fetcher selection, instruments layering, provisioning, candleWindow, brokerKind, the PAPER frictionless warning, error handling).

- [ ] **Step 2: Create `BacktestContext`** capturing that setup. It exposes `from`, `to`, `brokerKind`, `provision()`, and `backtest(overrides, range)`.

```kotlin
package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ProvisionStream
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.compile.AstCompiler
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DataFetcher
import com.qkt.marketdata.store.DataRoot
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.LocalBarStore
import com.qkt.marketdata.store.dukascopy.DukascopyInstrument
import com.qkt.marketdata.store.dukascopy.DukascopyTickFetcher
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shared backtest wiring used by `backtest`, `sweep`, `walkforward`, and (store/instruments only)
 * `research`. Centralizes data store, dukascopy auto-fetch, completeness provisioning, instrument
 * specs, candle window, and broker kind so every path produces identical results for the same combo.
 */
class BacktestContext private constructor(
    val ast: StrategyAst,
    val from: Instant,
    val to: Instant,
    val brokerKind: BrokerKind,
    val symbols: List<String>,
    val store: DefaultDataStore,
    val instruments: InstrumentRegistry,
    val barStore: LocalBarStore,
    private val candleWindow: TimeWindow?,
    private val startingBalance: BigDecimal,
    private val provisioner: () -> Unit,
) {
    fun provision() = provisioner()

    fun backtest(
        overrides: Map<String, String>,
        range: TimeRange = TimeRange(from, to),
    ): Backtest {
        val strategy = AstCompiler().compile(ast, overrides)
        return Backtest.fromStore(
            strategies = listOf(ast.name to strategy),
            store = store,
            request = MarketRequest(symbols = symbols, from = range.from, to = range.to),
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            instruments = instruments,
            barStore = barStore,
            brokerKind = brokerKind,
        )
    }

    companion object {
        /** Thrown for user-facing setup errors; commands catch it and print `e.message`. */
        class SetupError(
            message: String,
        ) : RuntimeException(message)

        fun build(
            args: Args,
            ast: StrategyAst,
            fetcherOverride: DataFetcher? = null,
        ): BacktestContext {
            val from = parseInstant(args.requireOption("from"))
            val to = parseInstant(args.requireOption("to"))
            val dataRoot = args.option("data-root") ?: "./data"
            val startingBalance = args.option("starting-balance")?.let(::BigDecimal) ?: BigDecimal("10000")

            val declaredSymbols = ast.streams.map { it.qktSymbol }.distinct()
            val symbolsOverride =
                args.option("symbols")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val symbols =
                if (symbolsOverride != null) {
                    val unknown = symbolsOverride.filter { it !in declaredSymbols }
                    if (unknown.isNotEmpty()) {
                        throw SetupError("--symbols contains unknown symbols $unknown; strategy declares $declaredSymbols")
                    }
                    symbolsOverride
                } else {
                    declaredSymbols
                }

            val noFetch = args.flag("no-fetch")
            val fetcher: DataFetcher? =
                when {
                    noFetch -> null
                    fetcherOverride != null -> fetcherOverride
                    else -> DukascopyTickFetcher()
                }
            val store = DefaultDataStore(root = Paths.get(dataRoot), fetcher = fetcher)

            val candleWindow = ast.streams.firstOrNull()?.timeframe?.let { TimeWindow.parse(it) }

            val instrumentsPath: Path =
                args.option("instruments")?.let(Paths::get) ?: Paths.get(dataRoot).resolve("instruments.yaml")
            val instruments: InstrumentRegistry =
                if (Files.exists(instrumentsPath)) {
                    LayeredInstrumentRegistry(listOf(YamlInstrumentRegistry.load(instrumentsPath), StandardInstrumentRegistry))
                } else {
                    if (args.option("instruments") != null) {
                        throw SetupError("--instruments file not found: $instrumentsPath")
                    }
                    StandardInstrumentRegistry
                }

            val brokerKind =
                when (val raw = args.option("broker")) {
                    null, "paper" -> BrokerKind.PAPER
                    "mt5-sim" -> BrokerKind.MT5_SIM
                    else -> throw SetupError("unknown --broker '$raw' (valid: paper, mt5-sim)")
                }

            val provisioner: () -> Unit = {
                val provisionStreams =
                    ast.streams
                        .filter { it.qktSymbol in symbols && DukascopyInstrument.ofOrNull(it.symbol) != null }
                        .map { ProvisionStream(broker = it.broker, bareSymbol = it.symbol) }
                val provisionFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
                val provisionTo = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC)
                if (!provisionTo.isBefore(provisionFrom) && provisionStreams.isNotEmpty()) {
                    com.qkt.backtest.BacktestDataProvisioner(store).ensure(
                        streams = provisionStreams,
                        from = provisionFrom,
                        to = provisionTo,
                        fetchEnabled = !noFetch,
                        allowIncomplete = args.flag("allow-incomplete"),
                        calendarFor = { defaultCalendars().calendarFor(it) },
                    )
                }
            }

            return BacktestContext(
                ast, from, to, brokerKind, symbols, store, instruments,
                LocalBarStore(root = DataRoot.forDataRoot(args.option("data-root"))), candleWindow,
                startingBalance, provisioner,
            )
        }

        private fun defaultCalendars(): com.qkt.broker.mt5.SymbolCalendars =
            com.qkt.broker.mt5.SymbolCalendars(
                rules =
                    listOf(
                        com.qkt.broker.mt5.SymbolCalendars.Rule("BTC*", com.qkt.common.TradingCalendar.crypto()),
                        com.qkt.broker.mt5.SymbolCalendars.Rule("ETH*", com.qkt.common.TradingCalendar.crypto()),
                        com.qkt.broker.mt5.SymbolCalendars.Rule("*USDT", com.qkt.common.TradingCalendar.crypto()),
                    ),
                default = com.qkt.common.TradingCalendar.fxDefault(),
            )

        fun parseInstant(s: String): Instant =
            if (s.contains('T')) {
                Instant.parse(if (s.endsWith("Z")) s else "${s}Z")
            } else {
                LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant()
            }
    }
}
```

> Note: confirm the `DukascopyInstrument` provisioning import path matches your tree (`com.qkt.marketdata.store.dukascopy.DukascopyInstrument`) — it's the same one `BacktestCommand` already uses. Copy the calendar/provisioner block verbatim from the current `BacktestCommand` to preserve behavior.

- [ ] **Step 3: Rewrite `BacktestCommand.run()`** to use the context, add `--param`, keep the PAPER warning and report output.

```kotlin
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    System.err.println("${parsed.errors.size} error${if (parsed.errors.size != 1) "s" else ""}")
                    return ExitCodes.USER_ERROR
                }
            }

        val format: ReportFormat = if (args.flag("json")) ReportFormat.Json else ReportFormat.Text

        // --param here is single-valued (one run). A comma-list means "sweep" -> point the user there.
        val overrides =
            args.options("param").associate { tok ->
                val eq = tok.indexOf('=')
                if (eq <= 0) {
                    System.err.println("qkt: error: bad --param '$tok'; expected NAME=VALUE")
                    return ExitCodes.USER_ERROR
                }
                val name = tok.substring(0, eq).trim()
                val value = tok.substring(eq + 1).trim()
                if (value.contains(',')) {
                    System.err.println("qkt: error: multiple values for '$name'; use 'qkt sweep' to grid-search")
                    return ExitCodes.USER_ERROR
                }
                name to value
            }

        val ctx =
            try {
                BacktestContext.build(args, ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        return try {
            val result = ctx.backtest(overrides).run()
            ReportPrinter.print(result, format, System.out, ctx.brokerKind)
            if (ctx.brokerKind == BrokerKind.PAPER) {
                System.err.println(
                    "qkt: note: paper broker fills at mid with no spread/slippage — results are optimistic. " +
                        "Use --broker mt5-sim and set commissionPerLot in instruments.yaml for cost-realistic backtests.",
                )
            }
            ExitCodes.SUCCESS
        } catch (e: IllegalStateException) {
            System.err.println("qkt: error: ${e.message}")
            if (args.flag("debug")) e.printStackTrace(System.err)
            ExitCodes.USER_ERROR
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: error: ${e.message}")
            if (args.flag("debug")) e.printStackTrace(System.err)
            ExitCodes.USER_ERROR
        }
```

Delete the now-duplicated setup (symbols, fetcher, store, instruments, brokerKind, provision block) from `BacktestCommand` — it lives in `BacktestContext` now. Keep the `--fetcher`/`--fetcher-script` legacy path only if `BacktestCommandDataTest` exercises it; otherwise drop it (the `fetcherOverride` ctor param covers test injection). Confirm against the test before deleting.

- [ ] **Step 4: Run the regression + a `--param` smoke**

Run: `./gradlew test --tests "com.qkt.cli.BacktestCommandDataTest" --console=plain`
Expected: PASS (behavior preserved).

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/BacktestContext.kt src/main/kotlin/com/qkt/cli/BacktestCommand.kt
git commit -m "refactor(cli): extract BacktestContext and add qkt backtest --param"
```

---

### Task 6: `qkt sweep`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/SweepCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt:12` (add dispatch)
- Test: `src/test/kotlin/com/qkt/cli/SweepCommandTest.kt`

- [ ] **Step 1: Write the failing test** (inject a deterministic fetcher — copy the fake-fetcher pattern from `BacktestCommandDataTest`; it writes a known tick day so no network is needed)

```kotlin
package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandTest {
    // Reuse the same fake DataFetcher BacktestCommandDataTest uses (writes a synthetic XAUUSD day).
    // See that test for the exact fetcher; it is injected via the command's fetcherOverride ctor arg.

    @Test
    fun `sweep over a two-point grid runs both combos and ranks them`(
        @TempDir dir: Path,
    ) {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        val args =
            Args(
                arrayOf(
                    "sweep", strat.toString(),
                    "--from", "2026-06-04", "--to", "2026-06-05",
                    "--data-root", dir.toString(),
                    "--param", "fast=2,3",
                    "--rank", "totalPnL",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }
}
```

> Create `src/test/kotlin/com/qkt/cli/FakeXauFetcher.kt` — a new shared helper that writes **per-minute** XAUUSD ticks with an **oscillating** price so EMAs cross and trades fire (the existing `FullDayFetcher` in `BacktestCommandDataTest` writes constant hourly prices — no crossings, so it can't exercise sweeps). Use this object in Tasks 6–8:
>
> ```kotlin
> package com.qkt.cli
>
> import com.qkt.marketdata.CsvTickFeed
> import com.qkt.marketdata.store.DataFetcher
> import java.nio.file.Files
> import java.nio.file.Path
> import java.time.LocalDate
> import java.time.ZoneOffset
> import java.util.zip.GZIPOutputStream
> import kotlin.math.sin
>
> /** One full day of per-minute XAUUSD ticks with an oscillating mid so EMAs cross and trades fire. */
> object FakeXauFetcher : DataFetcher {
>     override fun fetch(symbol: String, day: LocalDate, target: Path) {
>         Files.createDirectories(target.parent)
>         val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
>         val bare = symbol.substringAfter(':')
>         val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
>         for (m in 0 until 24 * 60) {
>             val ts = dayStart + m * 60_000L
>             val mid = 2000.0 + 20.0 * sin(m / 30.0)
>             sb.append("$ts,$bare,,,${"%.3f".format(mid - 0.1)},${"%.3f".format(mid + 0.1)},1.0,1.0\n")
>         }
>         GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
>     }
> }
> ```
>
> Per-minute ticks cover all 24 hours, so the completeness validator passes. The overridden `fast` value feeds `ema(gold.close, fast)`; it compiles to a `NumLit` integer period.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.SweepCommandTest" --console=plain`
Expected: FAIL — `SweepCommand` unresolved.

- [ ] **Step 3: Create `SweepCommand`**

```kotlin
package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path

/** `qkt sweep <file> --from --to --param NAME=v1,v2 [--rank sharpe] [--parallelism N] [--json]`. */
class SweepCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    return ExitCodes.USER_ERROR
                }
            }

        val rank =
            try {
                RankMetric.fromFlag(args.option("rank"))
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val combos =
            try {
                ParamGrid.parse(args.options("param"))
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val parallelism = args.option("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        val ctx =
            try {
                BacktestContext.build(args, ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        System.err.println("qkt: sweeping ${combos.size} parameter combination(s), ranked by ${rank.flag}")

        val sweep =
            BacktestSweep(
                configs = combos.map { it.label to it },
                backtestFactory = { _, combo -> ctx.backtest(combo.overrides) },
                parallelism = parallelism,
            )
        val ranked =
            try {
                sweep.run().rankedBy { rank.score(it) }
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        if (args.flag("json")) printJson(ranked, rank) else printTable(ranked, rank)
        return ExitCodes.SUCCESS
    }

    private fun printTable(
        ranked: List<com.qkt.backtest.sweep.SweepRun<ParamGrid.Combo>>,
        rank: RankMetric,
    ) {
        println("rank  ${rank.flag.padEnd(10)} trades  totalPnL     sharpe   calmar   maxDD     winRate  label")
        ranked.forEachIndexed { i, run ->
            val r = run.result.global
            println(
                "%-5d %-10s %-7d %-12s %-8s %-8s %-9s %-8s %s".format(
                    i + 1,
                    rank.valueOf(r)?.toPlainString() ?: "—",
                    r.tradeCount,
                    r.totalPnL.toPlainString(),
                    r.sharpeRatio?.toPlainString() ?: "—",
                    r.calmarRatio?.toPlainString() ?: "—",
                    r.maxDrawdown.toPlainString(),
                    r.winRate.toPlainString(),
                    run.label,
                ),
            )
        }
    }

    private fun printJson(
        ranked: List<com.qkt.backtest.sweep.SweepRun<ParamGrid.Combo>>,
        rank: RankMetric,
    ) {
        val rows =
            ranked.joinToString(",") { run ->
                val r: BacktestResult = run.result
                val params = run.config.overrides.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
                """{"label":"${run.label}","params":{$params},"rank":"${rank.flag}",""" +
                    """"trades":${r.global.tradeCount},"totalPnL":${r.global.totalPnL.toPlainString()},""" +
                    """"sharpe":${r.global.sharpeRatio?.toPlainString() ?: "null"},""" +
                    """"calmar":${r.global.calmarRatio?.toPlainString() ?: "null"},""" +
                    """"maxDrawdown":${r.global.maxDrawdown.toPlainString()},"winRate":${r.global.winRate.toPlainString()}}"""
            }
        println("[$rows]")
    }
}
```

- [ ] **Step 4: Wire dispatch** in `Main.kt` — add after the `backtest` line:

```kotlin
            "sweep" -> SweepCommand(args).run()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.SweepCommandTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/SweepCommand.kt src/main/kotlin/com/qkt/cli/Main.kt src/test/kotlin/com/qkt/cli/SweepCommandTest.kt src/test/kotlin/com/qkt/cli/FakeXauFetcher.kt
git commit -m "feat(cli): add qkt sweep command"
```

---

### Task 7: `qkt walkforward`

**Files:**
- Create: `src/main/kotlin/com/qkt/cli/WalkForwardCommand.kt`
- Modify: `src/main/kotlin/com/qkt/cli/Main.kt` (add dispatch)
- Test: `src/test/kotlin/com/qkt/cli/WalkForwardCommandTest.kt`

- [ ] **Step 1: Write the failing test** (same fake fetcher; a short window with `--train`/`--test`/`--step` that fits inside one fetched day)

```kotlin
package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WalkForwardCommandTest {
    @Test
    fun `walkforward produces folds over a fitting window`(
        @TempDir dir: Path,
    ) {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN ema(gold.close, 3) CROSSES ABOVE ema(gold.close, 9)
                THEN BUY gold SIZING 0.1
                WHEN ema(gold.close, 3) CROSSES BELOW ema(gold.close, 9)
                THEN CLOSE gold
            """.trimIndent(),
        )
        val args =
            Args(
                arrayOf(
                    "walkforward", strat.toString(),
                    "--from", "2026-06-04T00:00:00", "--to", "2026-06-04T12:00:00",
                    "--data-root", dir.toString(),
                    "--param", "fast=2,3",
                    "--train", "6h", "--test", "3h", "--step", "3h",
                    "--rank", "totalPnL",
                ),
            )
        val code = WalkForwardCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.WalkForwardCommandTest" --console=plain`
Expected: FAIL — `WalkForwardCommand` unresolved.

- [ ] **Step 3: Create `WalkForwardCommand`**

```kotlin
package com.qkt.cli

import com.qkt.backtest.walkforward.WalkForwardHarness
import com.qkt.candles.TimeWindow
import com.qkt.common.TimeRange
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** `qkt walkforward <file> --from --to --train 90d --test 30d --step 30d --param NAME=v1,v2 [--rank]`. */
class WalkForwardCommand(
    private val args: Args,
    private val fetcherOverride: DataFetcher? = null,
) {
    fun run(): Int {
        val file = args.requirePositional(0, "<strategy.qkt>")
        val path = Path.of(file)
        if (!Files.exists(path)) {
            System.err.println("qkt: error: file not found: $file")
            return ExitCodes.USER_ERROR
        }
        val ast =
            when (val parsed = Dsl.parseFile(path)) {
                is ParseResult.Success -> parsed.value
                is ParseResult.Failure -> {
                    for (e in parsed.errors) System.err.println("$file:${e.line}:${e.col} — ${e.message}")
                    return ExitCodes.USER_ERROR
                }
            }

        val rank =
            try {
                RankMetric.fromFlag(args.option("rank"))
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val combos =
            try {
                ParamGrid.parse(args.options("param"))
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        val train = parseDuration(args.requireOption("train")) ?: return badDuration("train")
        val test = parseDuration(args.requireOption("test")) ?: return badDuration("test")
        val step = parseDuration(args.requireOption("step")) ?: return badDuration("step")
        val parallelism = args.option("parallelism")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val topN = args.option("topN")?.toIntOrNull()?.coerceAtLeast(1) ?: 3

        val ctx =
            try {
                BacktestContext.build(args, ast, fetcherOverride)
            } catch (e: BacktestContext.Companion.SetupError) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }
        try {
            ctx.provision()
        } catch (e: com.qkt.backtest.IncompleteDataException) {
            System.err.println("qkt: error: ${e.message}")
            return ExitCodes.USER_ERROR
        }

        val harness =
            WalkForwardHarness(
                configs = combos.map { it.label to it },
                backtestFactory = { _, combo, range -> ctx.backtest(combo.overrides, range) },
                totalRange = TimeRange(ctx.from, ctx.to),
                trainSize = train,
                testSize = test,
                stepSize = step,
                scoreOf = { rank.score(it) },
                parallelism = parallelism,
                topN = topN,
            )
        val result =
            try {
                harness.run()
            } catch (e: IllegalArgumentException) {
                System.err.println("qkt: error: ${e.message}")
                return ExitCodes.USER_ERROR
            }

        println("folds: ${result.folds.size}   mean IS ${rank.flag}: ${result.meanTrainScore.toPlainString()}   mean OOS ${rank.flag}: ${result.meanTestScore.toPlainString()}")
        println("winner stability: ${result.winnerCounts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}×${it.value}" }}")
        result.folds.forEachIndexed { i, f ->
            println(
                "fold ${i + 1}: train ${Instant.ofEpochMilli(f.trainRange.from.toEpochMilli())}..${Instant.ofEpochMilli(f.trainRange.to.toEpochMilli())}  " +
                    "winner ${f.winnerLabel}  IS ${f.trainScore.toPlainString()}  OOS ${rank.valueOf(f.testResult.global)?.toPlainString() ?: "—"}",
            )
        }
        return ExitCodes.SUCCESS
    }

    private fun badDuration(name: String): Int {
        System.err.println("qkt: error: --$name must be a duration like 90d, 12h, 30m")
        return ExitCodes.USER_ERROR
    }

    private fun parseDuration(spec: String): Duration? =
        runCatching { Duration.ofMillis(TimeWindow.parse(spec).durationMs) }.getOrNull()
}
```

> `--json` for walk-forward: add a `printJson(result, rank)` mirroring `SweepCommand.printJson` if `args.flag("json")`; emit `folds`, `winnerCounts`, `meanTrainScore`, `meanTestScore`. Keep it a thin string-builder like the sweep one. (Add when implementing; the table path is the test's success criterion.)

- [ ] **Step 4: Wire dispatch** in `Main.kt`:

```kotlin
            "walkforward" -> WalkForwardCommand(args).run()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.WalkForwardCommandTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/WalkForwardCommand.kt src/main/kotlin/com/qkt/cli/Main.kt src/test/kotlin/com/qkt/cli/WalkForwardCommandTest.kt
git commit -m "feat(cli): add qkt walkforward command"
```

---

### Task 8: `qkt research` accuracy fixes

Use the same instrument registry + auto-fetch as `backtest` (via `BacktestContext`), instead of `NoopInstrumentRegistry` + `fetcher=null`.

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/ResearchCommand.kt`
- Test: `src/test/kotlin/com/qkt/cli/ResearchCommandTest.kt`

- [ ] **Step 1: Write the failing test** (drive the REPL with an immediate `quit`; assert contract-size-correct load on the fake day. Inspect `ReplayRepl`'s quit command — likely `q`/`quit`/EOF — and feed it via the `BufferedReader`.)

```kotlin
package com.qkt.cli

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ResearchCommandTest {
    @Test
    fun `research loads ticks with standard instrument specs and exits on EOF`(
        @TempDir dir: Path,
    ) {
        val strat = dir.resolve("s.qkt")
        Files.writeString(
            strat,
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0
                THEN BUY gold SIZING 0.1
            """.trimIndent(),
        )
        val out = ByteArrayOutputStream()
        val code =
            ResearchCommand(
                Args(arrayOf("research", strat.toString(), "--from", "2026-06-04", "--to", "2026-06-05", "--data-root", dir.toString())),
                fetcherOverride = FakeXauFetcher,
                input = ByteArrayInputStream(ByteArray(0)), // empty -> EOF -> REPL exits
                output = PrintStream(out),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("loaded")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.qkt.cli.ResearchCommandTest" --console=plain`
Expected: FAIL — `ResearchCommand` ctor has no `fetcherOverride`/`input`/`output`.

- [ ] **Step 3: Modify `ResearchCommand`** — add testable ctor params, build the store/instruments via `BacktestContext`, provision, then load ticks and run the REPL.

Key changes:
- Ctor: `class ResearchCommand(private val args: Args, private val fetcherOverride: DataFetcher? = null, private val input: java.io.InputStream = System.`in`, private val output: java.io.PrintStream = System.out)`.
- Replace the `DefaultDataStore(..., fetcher = null)` + `NoopInstrumentRegistry` construction with `val ctx = BacktestContext.build(args, ast, fetcherOverride); ctx.provision()`.
- Keep `ResearchCommand`'s existing tick-loading (its `LocalMarketSource` + the bare stream symbols it already uses), but source store/barStore/instruments from the context: `LocalMarketSource(ctx.store, FixedClock(time = ctx.to.toEpochMilli()), barStore = ctx.barStore)`. Build `ReplaySession(ticks, strategyPath = path, startingBalance, instruments = ctx.instruments)` — passing `ctx.instruments` instead of `NoopInstrumentRegistry` is the contractSize fix; building the store via `BacktestContext` (which wires `DukascopyTickFetcher`) is the auto-fetch fix.
- Run `ReplayRepl(session).run(BufferedReader(InputStreamReader(input)), output)`.

> `BacktestContext` (Task 5) exposes `store`, `barStore`, `instruments`, `symbols`, `from`, `to` as public vals. Confirm `ReplaySession`'s constructor accepts an `InstrumentRegistry` (it currently takes `NoopInstrumentRegistry`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.qkt.cli.ResearchCommandTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
./gradlew ktlintFormat --console=plain
git add src/main/kotlin/com/qkt/cli/ResearchCommand.kt src/main/kotlin/com/qkt/cli/BacktestContext.kt src/test/kotlin/com/qkt/cli/ResearchCommandTest.kt
git commit -m "fix(cli): qkt research uses standard instrument specs and auto-fetch"
```

---

### Task 9: Documentation

**Files:**
- Modify: `docs/reference/dsl/let-defaults.md` — the `--param` section now matches reality; clarify it overrides a `PARAM` or `LET` by name with a numeric literal.
- Modify: `docs/how-to/parameter-sweep.md` — replace "isn't shipped yet / drive from Kotlin" with the `qkt sweep` + `qkt walkforward` CLI usage.
- Modify: `docs/reference/cli-commands.md` — add `sweep`, `walkforward`, and the `--param` flag on `backtest`.

- [ ] **Step 1:** Edit the three docs to describe the shipped commands. Use a real example for each:

```bash
qkt backtest strategy.qkt --from 2024-01-01 --to 2024-02-01 --param fast=12 --param slow=26
qkt sweep    strategy.qkt --from 2024-01-01 --to 2024-02-01 --param fast=8,12,16 --param slow=20,40 --rank sharpe
qkt walkforward strategy.qkt --from 2024-01-01 --to 2024-06-01 --param fast=8,12,16 --train 60d --test 20d --step 20d
```

- [ ] **Step 2: Commit**

```bash
git add docs/reference/dsl/let-defaults.md docs/how-to/parameter-sweep.md docs/reference/cli-commands.md
git commit -m "docs(cli): document --param, qkt sweep, and qkt walkforward"
```

---

### Task 10: End-to-end verification on real data + open PR

**Files:** none (verification only).

- [ ] **Step 1: Verify the four behaviors against the cached XAUUSD day** (`2026-06-04`; if not cached, omit `--no-fetch` to auto-fetch once)

```bash
# 1. --param changes the run vs the file default
qkt backtest strategy.qkt --from 2026-06-04 --to 2026-06-05 --data-root ./data --param fast=3
# 2. sweep ranks a small grid; the top label's standalone backtest reproduces its row
qkt sweep strategy.qkt --from 2026-06-04 --to 2026-06-05 --data-root ./data --param fast=3,5,8 --rank sharpe
# 3. walkforward produces folds with IS vs OOS scores
qkt walkforward strategy.qkt --from 2026-06-04 --to 2026-06-05 --data-root ./data --param fast=3,5 --train 8h --test 4h --step 4h --rank totalPnL
# 4. research loads and steps (type 'quit' or Ctrl-D to exit)
qkt research strategy.qkt --from 2026-06-04 --to 2026-06-05 --data-root ./data
```

Expected: each runs cleanly; sweep's #1 row matches a standalone backtest of those params; walk-forward prints ≥1 fold with separate IS and OOS numbers.

- [ ] **Step 2: Full local check is optional — push and let CI run the suite**

```bash
./gradlew ktlintFormat --console=plain
git push -u origin feat-backtest-eval-cli
gh pr create --base dev --title "Backtest eval CLI: --param, sweep, walkforward; research accuracy fixes" --body "Implements docs/superpowers/specs/2026-06-09-backtest-eval-cli-tools-design.md"
```

- [ ] **Step 3:** Watch CI green (`gh pr checks`), then hand back for review/merge.

---

## Notes for the implementer

- **TDD order matters:** Tasks 1–4 are pure and fully unit-tested; 5–8 are orchestration verified by injected-fetcher integration tests plus the real-data run in Task 10.
- **The fake fetcher** (`FakeXauFetcher`, created in Task 6) writes per-minute oscillating prices so EMAs cross and trades fire; reuse it in Tasks 7–8. Do **not** use `BacktestCommandDataTest`'s `FullDayFetcher` — its constant hourly price produces no crossings, so no trades.
- **PARAM vs LET override:** the same `--param fast=3` works whether the strategy declares `PARAM fast = …` or `LET fast = …` — the override matches by name across both (Task 2).
- **Don't** re-introduce per-command duplication of store/instrument/provisioning setup — it all lives in `BacktestContext` (Task 5).
