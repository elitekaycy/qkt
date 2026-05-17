# Phase 30 — Instrument metadata — Implementation plan

**For agentic workers:** REQUIRED SUB-SKILL — use `superpowers:executing-plans` to implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close GAP 4. Engine gains a first-class `InstrumentMeta` primitive; sizing and PnL math route through it; live and backtest share the registry interface; hedge-straddle's `/100` workaround is removed.

**Architecture:** Per spec at `docs/superpowers/specs/2026-05-15-instrument-metadata-design.md`. Four ordered commits, each ships independently.

**Tech stack:** Kotlin 2.1, JUnit 5, AssertJ, SnakeYAML (already a transitive dep via the broker profile loader).

---

## File structure

**Create:**
- `src/main/kotlin/com/qkt/instrument/InstrumentMeta.kt` — data class
- `src/main/kotlin/com/qkt/instrument/InstrumentRegistry.kt` — interface + companion factories
- `src/main/kotlin/com/qkt/instrument/YamlInstrumentRegistry.kt` — backtest impl
- `src/main/kotlin/com/qkt/instrument/MT5InstrumentRegistry.kt` — live impl
- `src/test/kotlin/com/qkt/instrument/InstrumentMetaTest.kt`
- `src/test/kotlin/com/qkt/instrument/YamlInstrumentRegistryTest.kt`
- `src/test/kotlin/com/qkt/instrument/MT5InstrumentRegistryTest.kt`
- `src/test/resources/instruments/instruments.yaml` — test fixture
- `data/instruments.yaml` — production-style file with the symbols backtest needs (`EXNESS:XAUUSD`, `EXNESS:EURUSD`, `BACKTEST:BTCUSD`)

**Modify:**
- `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt` — add `contractSize` to `MT5SymbolInfo`
- `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt` — parse `trade_contract_size` in `getSymbolInfo`
- `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — expose `instrumentMeta(qktSymbol)` for the `MT5InstrumentRegistry`
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` — accept and hold `InstrumentRegistry`; resolve at strategy load
- `src/main/kotlin/com/qkt/app/LiveSession.kt` — construct `MT5InstrumentRegistry`, pass to pipeline
- `src/main/kotlin/com/qkt/backtest/Backtest.kt` — construct `YamlInstrumentRegistry`, pass to pipeline
- `src/main/kotlin/com/qkt/cli/BacktestCommand.kt` — `--instruments` flag (default `<data-root>/instruments.yaml`)
- `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` — `SizeRiskAbs` consumes registry, applies contract size
- `src/main/kotlin/com/qkt/broker/PaperBroker.kt` — apply contract size in PnL math
- `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — `tradeStopsLevel` enforcement
- `qkt-prod/strategies/hedge-straddle.qkt` — remove `/100` workaround, update comments
- `qkt-prod/docs/PARITY.md` — mark GAP 4 closed by Phase 30
- `docs/parity/backtest-vs-live.md` — update row 7 (contract size) to "closed in Phase 30"

---

## Commit 1 — `feat(instrument): add InstrumentMeta + Registry primitives`

### Task 1.1 — `InstrumentMeta` data class

**Files:**
- Create: `src/main/kotlin/com/qkt/instrument/InstrumentMeta.kt`
- Create: `src/test/kotlin/com/qkt/instrument/InstrumentMetaTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.qkt.instrument

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InstrumentMetaTest {
    @Test
    fun `constructs with all fields`() {
        val m =
            InstrumentMeta(
                qktSymbol = "EXNESS:XAUUSD",
                contractSize = BigDecimal("100"),
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = BigDecimal("200"),
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
            )
        assertThat(m.qktSymbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(m.contractSize).isEqualByComparingTo("100")
    }

    @Test
    fun `rejects non-positive contractSize`() {
        assertThrows<IllegalArgumentException> {
            InstrumentMeta(
                qktSymbol = "X",
                contractSize = BigDecimal.ZERO,
                volumeStep = BigDecimal("0.01"),
                volumeMin = BigDecimal("0.01"),
                volumeMax = null,
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
            )
        }
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew test --tests com.qkt.instrument.InstrumentMetaTest`
Expected: `UnresolvedReference: InstrumentMeta`

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.instrument

import java.math.BigDecimal

data class InstrumentMeta(
    val qktSymbol: String,
    val contractSize: BigDecimal,
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val volumeMax: BigDecimal?,
    val pointSize: BigDecimal,
    val digits: Int,
    val tradeStopsLevelPoints: Int,
) {
    init {
        require(qktSymbol.isNotBlank()) { "InstrumentMeta.qktSymbol must not be blank" }
        require(contractSize.signum() > 0) { "InstrumentMeta.contractSize must be > 0: $contractSize" }
        require(volumeStep.signum() > 0) { "InstrumentMeta.volumeStep must be > 0: $volumeStep" }
        require(volumeMin.signum() > 0) { "InstrumentMeta.volumeMin must be > 0: $volumeMin" }
        require(pointSize.signum() > 0) { "InstrumentMeta.pointSize must be > 0: $pointSize" }
        require(digits >= 0) { "InstrumentMeta.digits must be >= 0: $digits" }
        require(tradeStopsLevelPoints >= 0) { "InstrumentMeta.tradeStopsLevelPoints must be >= 0" }
    }
}
```

- [ ] **Step 4: Run to confirm pass**

Run: `./gradlew test --tests com.qkt.instrument.InstrumentMetaTest`
Expected: PASS

### Task 1.2 — `InstrumentRegistry` interface

**Files:**
- Create: `src/main/kotlin/com/qkt/instrument/InstrumentRegistry.kt`

- [ ] **Step 1: Implement interface + default `require`**

```kotlin
package com.qkt.instrument

interface InstrumentRegistry {
    fun lookup(qktSymbol: String): InstrumentMeta?

    fun require(qktSymbol: String): InstrumentMeta =
        lookup(qktSymbol)
            ?: error(
                "no InstrumentMeta for $qktSymbol; configure it in data/instruments.yaml " +
                    "for backtest or ensure the live broker can resolve it via /symbol_info",
            )
}
```

No test for the interface alone; tests live with each concrete impl.

### Task 1.3 — `YamlInstrumentRegistry`

**Files:**
- Create: `src/main/kotlin/com/qkt/instrument/YamlInstrumentRegistry.kt`
- Create: `src/test/kotlin/com/qkt/instrument/YamlInstrumentRegistryTest.kt`
- Create: `src/test/resources/instruments/instruments.yaml`

- [ ] **Step 1: Write the fixture**

```yaml
# src/test/resources/instruments/instruments.yaml
instruments:
  - qktSymbol: EXNESS:XAUUSD
    contractSize: 100
    volumeStep: 0.01
    volumeMin: 0.01
    volumeMax: 200
    pointSize: 0.001
    digits: 3
    tradeStopsLevelPoints: 0
  - qktSymbol: EXNESS:EURUSD
    contractSize: 100000
    volumeStep: 0.01
    volumeMin: 0.01
    pointSize: 0.00001
    digits: 5
    tradeStopsLevelPoints: 0
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.qkt.instrument

import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class YamlInstrumentRegistryTest {
    private val fixture = Paths.get("src/test/resources/instruments/instruments.yaml")

    @Test
    fun `loads multiple instruments from yaml`() {
        val r = YamlInstrumentRegistry.load(fixture)
        val gold = r.require("EXNESS:XAUUSD")
        assertThat(gold.contractSize).isEqualByComparingTo("100")
        assertThat(gold.digits).isEqualTo(3)
        val eu = r.require("EXNESS:EURUSD")
        assertThat(eu.contractSize).isEqualByComparingTo("100000")
    }

    @Test
    fun `lookup returns null for unknown symbol`() {
        val r = YamlInstrumentRegistry.load(fixture)
        assertThat(r.lookup("BYBIT:DOGE")).isNull()
    }

    @Test
    fun `require throws with a helpful message for unknown symbol`() {
        val r = YamlInstrumentRegistry.load(fixture)
        val e = assertThrows<IllegalStateException> { r.require("BYBIT:DOGE") }
        assertThat(e.message).contains("BYBIT:DOGE").contains("data/instruments.yaml")
    }

    @Test
    fun `duplicate qktSymbol fails loudly at load`() {
        val dupe = createTempFile("instruments-dupe", ".yaml")
        dupe.toFile().writeText(
            """
            instruments:
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
              - qktSymbol: X
                contractSize: 1
                volumeStep: 0.01
                volumeMin: 0.01
                pointSize: 0.01
                digits: 2
                tradeStopsLevelPoints: 0
            """.trimIndent(),
        )
        assertThrows<IllegalStateException> { YamlInstrumentRegistry.load(dupe) }
    }
}
```

- [ ] **Step 3: Implement**

```kotlin
package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class YamlInstrumentRegistry private constructor(
    private val table: Map<String, InstrumentMeta>,
) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? = table[qktSymbol]

    companion object {
        fun load(path: Path): YamlInstrumentRegistry {
            check(Files.exists(path)) { "instruments.yaml not found at $path" }
            val text = Files.readString(path)
            val raw = Load(LoadSettings.builder().build()).loadFromString(text)
            check(raw is Map<*, *>) { "instruments.yaml: top-level must be a map, got ${raw::class}" }
            val list = raw["instruments"] as? List<*> ?: error("instruments.yaml: missing 'instruments' list")
            val table = mutableMapOf<String, InstrumentMeta>()
            for ((i, entry) in list.withIndex()) {
                check(entry is Map<*, *>) { "instruments.yaml: entry $i must be a map" }
                val meta = parseEntry(entry, i)
                check(meta.qktSymbol !in table) {
                    "instruments.yaml: duplicate qktSymbol ${meta.qktSymbol}"
                }
                table[meta.qktSymbol] = meta
            }
            return YamlInstrumentRegistry(table)
        }

        private fun parseEntry(entry: Map<*, *>, index: Int): InstrumentMeta {
            fun req(key: String): Any = entry[key] ?: error("instruments.yaml: entry $index missing '$key'")
            fun bd(key: String): BigDecimal = BigDecimal(req(key).toString())
            fun bdOpt(key: String): BigDecimal? = entry[key]?.let { BigDecimal(it.toString()) }
            return InstrumentMeta(
                qktSymbol = req("qktSymbol").toString(),
                contractSize = bd("contractSize"),
                volumeStep = bd("volumeStep"),
                volumeMin = bd("volumeMin"),
                volumeMax = bdOpt("volumeMax"),
                pointSize = bd("pointSize"),
                digits = (req("digits") as Number).toInt(),
                tradeStopsLevelPoints = (req("tradeStopsLevelPoints") as Number).toInt(),
            )
        }
    }
}
```

- [ ] **Step 4: Verify tests pass**

Run: `./gradlew test --tests com.qkt.instrument.YamlInstrumentRegistryTest`
Expected: PASS (all 4 tests).

### Task 1.4 — `MT5InstrumentRegistry`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt` — add `contractSize` to `MT5SymbolInfo`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt` — parse `trade_contract_size` in `getSymbolInfo`
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt` — expose `instrumentMeta(qktSymbol): InstrumentMeta?`
- Create: `src/main/kotlin/com/qkt/instrument/MT5InstrumentRegistry.kt`
- Create: `src/test/kotlin/com/qkt/instrument/MT5InstrumentRegistryTest.kt`

- [ ] **Step 1: Add `contractSize` to `MT5SymbolInfo`**

```kotlin
data class MT5SymbolInfo(
    val ask: BigDecimal,
    val bid: BigDecimal,
    val digits: Int,
    val point: BigDecimal,
    val tradeStopsLevel: Int,
    val volumeMin: BigDecimal,
    val volumeStep: BigDecimal,
    val contractSize: BigDecimal,   // new
)
```

- [ ] **Step 2: Parse `trade_contract_size` in `MT5Client.getSymbolInfo`**

Add inside the `MT5SymbolInfo(...)` construction:

```kotlin
contractSize =
    obj["trade_contract_size"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
        ?: BigDecimal.ONE,
```

Default of `1` preserves backward compatibility for unknown instruments.

- [ ] **Step 3: Extend `MT5Client.getSymbolInfo` test**

In `MT5ClientTest`, modify `getSymbolInfo parses volume rules and basic price metadata`:

```kotlin
assertThat(info.contractSize).isEqualByComparingTo("100")
```

- [ ] **Step 4: `MT5Broker.instrumentMeta`**

Add public method to `MT5Broker`:

```kotlin
fun instrumentMeta(qktSymbol: String): InstrumentMeta? {
    val brokerSymbol = mt5Symbol.toBroker(qktSymbol.removePrefix("${profile.name.uppercase()}:"))
    val info = symbolMeta[brokerSymbol] ?: client.getSymbolInfo(brokerSymbol)?.also { symbolMeta[brokerSymbol] = it } ?: return null
    return InstrumentMeta(
        qktSymbol = qktSymbol,
        contractSize = info.contractSize,
        volumeStep = info.volumeStep,
        volumeMin = info.volumeMin,
        volumeMax = null,
        pointSize = info.point,
        digits = info.digits,
        tradeStopsLevelPoints = info.tradeStopsLevel,
    )
}
```

- [ ] **Step 5: `MT5InstrumentRegistry` adapter**

```kotlin
package com.qkt.instrument

import com.qkt.broker.mt5.MT5Broker

class MT5InstrumentRegistry(private val broker: MT5Broker) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? = broker.instrumentMeta(qktSymbol)
}
```

- [ ] **Step 6: Test the adapter with `MockWebServer`**

Mirror the existing `MT5BrokerIntegrationTest` `gateway symbol_info is fetched and cached` pattern. Assert: lookup returns meta with the parsed `contractSize`.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/instrument src/main/kotlin/com/qkt/broker/mt5/MT5WireTypes.kt \
        src/main/kotlin/com/qkt/broker/mt5/MT5Client.kt src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt \
        src/test/kotlin/com/qkt/instrument src/test/resources/instruments \
        src/test/kotlin/com/qkt/broker/mt5/MT5ClientTest.kt
git commit -m "feat(instrument): add InstrumentMeta and registry primitives"
```

---

## Commit 2 — `feat(dsl): route SIZING RISK through contractSize`

### Task 2.1 — Wire registry into `TradingPipeline`

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Modify: `src/main/kotlin/com/qkt/backtest/Backtest.kt`
- Modify: `src/main/kotlin/com/qkt/cli/BacktestCommand.kt`

- [ ] **Step 1: Add `registry: InstrumentRegistry` parameter to `TradingPipeline`**

Pipeline holds a reference and exposes it to the action compiler. At strategy load, iterate the strategy's declared streams and call `registry.require(qktSymbol)` to fail fast on missing meta.

- [ ] **Step 2: Construct `MT5InstrumentRegistry` in `LiveSession`**

When the broker is `MT5Broker`, wrap it. For multi-broker (e.g. Bybit), use a `CompositeInstrumentRegistry` that consults each per-broker impl in turn. (Bybit support can be a follow-up — for now the broker map is single-entry.)

- [ ] **Step 3: Construct `YamlInstrumentRegistry` in `Backtest.fromStore`**

Default path: `<data-root>/instruments.yaml`. Add `--instruments` CLI flag to override.

- [ ] **Step 4: Verify boot**

Existing `BacktestLiveParityTest` must keep passing. Add a new test: strategy declaring an unknown symbol fails strategy load with the "no InstrumentMeta for X" message.

### Task 2.2 — Route SIZING RISK through `contractSize`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/compile/ActionCompilerSizingTest.kt` (or equivalent)

- [ ] **Step 1: Locate the SIZING evaluator**

```bash
grep -n "SizeRiskAbs\|riskAmount.*divide" src/main/kotlin/com/qkt/dsl/compile/*.kt
```

- [ ] **Step 2: Write the failing test**

```kotlin
@Test
fun `SIZING RISK divides by contractSize for XAUUSD`() {
    // 50000 * 0.007 * 1.0 = 350 risk USD
    // SL distance 18 USD
    // XAUUSD contractSize 100 oz/lot
    // lots = 350 / (18 * 100) = 0.1944
    val script = """
        STRATEGY t VERSION 1
        SYMBOLS
            g = EXNESS:XAUUSD EVERY 5m
        RULES
            WHEN g.close > 0 AND POSITION.g = 0
            THEN BUY g SIZING RISK $ (50000 * 0.007 * 1.0) BRACKET { STOP LOSS BY 18, TAKE PROFIT BY 5 }
    """.trimIndent()
    // Compile + evaluate first signal; assert quantity == 0.1944
}
```

- [ ] **Step 3: Implement**

Update `SizeRiskAbs` compile output:

```kotlin
val cs = registry.require(streamDecl.qktSymbol).contractSize
val lots = riskAmount.divide(slDistance.multiply(cs), MathContext.DECIMAL64)
```

- [ ] **Step 4: Migrate `qkt-prod/strategies/hedge-straddle.qkt`**

Both legs:

```diff
- SIZING RISK $ ((50000 * 0.007 * <CASE...>) / 100)
+ SIZING RISK $ (50000 * 0.007 * <CASE...>)
```

Update the comment block to drop the `/100` warning, reference Phase 30.

- [ ] **Step 5: Regression — backtest produces identical lot sizes**

Re-run `qkt backtest qkt-prod/strategies/hedge-straddle.qkt --from 2024-02-05 --to 2024-02-10 --data-root /tmp/qkt-bt/data` (the dataset from earlier today). Expected: trade count and timestamps identical to today's run; lot sizes identical (now via engine math, not strategy hack); PnL number will change — that's commit 3's territory.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/app/TradingPipeline.kt \
        src/main/kotlin/com/qkt/app/LiveSession.kt \
        src/main/kotlin/com/qkt/backtest/Backtest.kt \
        src/main/kotlin/com/qkt/cli/BacktestCommand.kt \
        src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt \
        src/main/kotlin/com/qkt/instrument/CompositeInstrumentRegistry.kt \
        src/test/kotlin/com/qkt/dsl/compile \
        ../qkt-prod/strategies/hedge-straddle.qkt
git commit -m "feat(dsl): route SIZING RISK through contractSize"
```

(Note: `qkt-prod` is a separate repo. That commit happens in `qkt-prod` after the qkt-side change ships; cross-repo coordination is outside this plan's scope.)

---

## Commit 3 — `feat(broker): apply contractSize to PaperBroker fill PnL`

### Task 3.1 — Wire `InstrumentRegistry` into `PaperBroker`

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/PaperBroker.kt`

- [ ] **Step 1: Constructor gains `registry: InstrumentRegistry`**

Pass through from `TradingPipeline`. Existing callers default to a "unit" registry that returns `contractSize=1` for backward compat, to avoid rewriting every test simultaneously.

- [ ] **Step 2: Find PnL computation**

```bash
grep -n "realized\|exitPx\|publishFill" src/main/kotlin/com/qkt/broker/PaperBroker.kt
```

- [ ] **Step 3: Apply contract size**

PnL math changes from `(exitPx - entryPx) * quantity` to `(exitPx - entryPx) * quantity * contractSize`.

- [ ] **Step 4: Update affected tests**

`PaperBrokerTest`, `BacktestLiveParityTest`, and any backtest regression with hardcoded expected PnL numbers. The lot quantities don't change; the PnL magnitudes do.

- [ ] **Step 5: Verify `BacktestLiveParityTest` still passes**

Both backtest and live-paper sides see the same `contractSize` from the registry → identical fills → contract parity holds.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/PaperBroker.kt \
        src/test/kotlin/com/qkt/broker \
        src/test/kotlin/com/qkt/parity
git commit -m "feat(broker): apply contractSize to PaperBroker fill PnL"
```

---

## Commit 4 — `feat(broker): enforce tradeStopsLevel pre-placement in MT5Broker`

### Task 4.1 — Reject orders with SL/TP too close to entry

**Files:**
- Modify: `src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt`
- Modify: `src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `bracket with SL too close to entry is rejected pre-placement`() {
    // tradeStopsLevelPoints = 100, point = 0.001 → min distance 0.1
    // entry 1.1000, SL 1.0995 → distance 0.0005 < 0.1 → reject
    ...
}
```

- [ ] **Step 2: Implement in `quantizeForPlacement` (or a sibling function)**

```kotlin
val minDistance = meta.pointSize.multiply(BigDecimal(meta.tradeStopsLevelPoints))
if (wire.sl != null && wire.price != null && (wire.price - wire.sl).abs() < minDistance) {
    return null  // signals rejection
}
```

(Or refactor `quantizeForPlacement`'s `null = reject` semantics into a sealed result so reasons differ between volume-min and stops-level.)

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/qkt/broker/mt5/MT5Broker.kt \
        src/test/kotlin/com/qkt/broker/mt5/MT5BrokerIntegrationTest.kt
git commit -m "feat(broker): enforce tradeStopsLevel pre-placement in MT5Broker"
```

---

## Post-commit chores

- [ ] **Update `qkt-prod/docs/PARITY.md`** — mark GAP 4 as resolved by Phase 30, link to phase changelog.
- [ ] **Update `docs/parity/backtest-vs-live.md`** — table row 7 status changes to "closed in Phase 30"; remove the "dangerous one" callout (or rephrase).
- [ ] **Write phase changelog** at `docs/phases/phase-30-instrument-metadata.md` per the qkt skill §6 requirements: summary, what's new, migration from previous phase, usage cookbook, testing patterns, known limitations, references.
- [ ] **Open the PR.** One PR with four commits. CI runs each commit's full build via the smoke job's history.
- [ ] **Tag `v0.27.0`** after merge. This is a feature phase, not a patch; minor version bump.
- [ ] **Migrate `qkt-prod`**: bump `QKT_IMAGE_TAG` to `v0.27.0`, commit the hedge-straddle `/100` removal (separate commit from the bump per qkt-prod convention).

## Final verification

After deploy:
- `qkt list` shows `hedge-straddle running`.
- Daemon log: no `OrderRejected` referencing volume or price step.
- At the next placement window, `qkt status hedge-straddle` shows the position when the leg fills, **and** the `positions[]` quantity matches the venue's `/get_positions` ticket volume.
- Compare backtest PnL on Feb 5-9 dataset before and after Phase 30: numbers should change by a factor of `contractSize` (~100×) — that's the proof the math is now in real dollars.
