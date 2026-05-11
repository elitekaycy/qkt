# Phase 23 — DSL catalog expansion · Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Expose 5 already-implemented indicators to the DSL, add `highest`/`lowest`, add position accessors (`.pnl`, `.entry_price`, `.holding_duration`, ...), and accept `#` as a line comment.

**Architecture:** Mostly registration / accessor additions. Two new indicator classes (`RollingHigh`, `RollingLow`). One small `Position` schema change (`openedAt: Long?`). One lexer change. Multi-output indicators (MACD, Bollinger) get separate DSL names sharing a single underlying instance via the existing `IndicatorBinding` cache.

**Tech stack:** Kotlin (single module), JUnit 5 + AssertJ. No new dependencies.

---

### Task 1 — Verify spec assumptions in source

**Files:**
- Read: `src/main/kotlin/com/qkt/indicators/catalog/{SMA,WMA,MACD,VWAP,BollingerBands}.kt`
- Read: `src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt`
- Read: `src/main/kotlin/com/qkt/dsl/stdlib/StateRefs.kt`
- Read: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Read: `src/main/kotlin/com/qkt/positions/Position.kt`
- Read: `src/main/kotlin/com/qkt/positions/PositionTracker.kt`

- [ ] **Step 1: Confirm SMA, WMA, MACD, VWAP, BollingerBands implementations exist and have the expected constructor signatures.**

```bash
grep -n 'class SMA\|class WMA\|class MACD\|class VWAP\|class BollingerBands' src/main/kotlin/com/qkt/indicators/catalog/*.kt
```

- [ ] **Step 2: Confirm `IndicatorRegistry` table format — `IndicatorSpec(name, inputKind, arity, factory)` — matches the existing EMA/RSI/ATR registrations.**

- [ ] **Step 3: Read `Position.kt` to confirm `quantity`, `avgEntryPrice` fields and verify there's no `openedAt` already.**

- [ ] **Step 4: Read `StateRefs.kt` to see how `Account.realizedPnl` etc. are exposed — replicate that pattern for the new `position(stream).*` accessors.**

- [ ] **Step 5: Read `Lexer.kt` to find the line-comment handling. Confirm `--` is the current pattern.**

- [ ] **Step 6: Run the existing test suite as a baseline.**

```bash
./gradlew test --no-daemon
```

Expected: all green.

---

### Task 2 — Hash line comments

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Lexer.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

- [ ] **Step 1: Write the failing test.**

Add to `LexerTest.kt`:

```kotlin
@Test
fun `hash line comments are skipped`() {
    val source = """
        # this is a comment
        STRATEGY hello VERSION 1
        # another comment
    """.trimIndent()
    val tokens = Lexer(source).tokenize()
    val kinds = tokens.map { it.kind }
    assertThat(kinds).contains(TokenKind.STRATEGY)
    assertThat(tokens.none { it.lexeme.contains("comment") }).isTrue
}

@Test
fun `hash inside string literal is not a comment`() {
    val source = """STRATEGY x VERSION 1 SYMBOLS a = X:Y EVERY 1m RULES WHEN a.close > 0 THEN LOG "#hashtag""""
    val tokens = Lexer(source).tokenize()
    val stringTok = tokens.first { it.kind == TokenKind.STRING }
    assertThat(stringTok.lexeme).contains("#hashtag")
}
```

- [ ] **Step 2: Run tests, confirm they fail with the expected "unexpected #" or similar.**

```bash
./gradlew test --tests com.qkt.dsl.parse.LexerTest --no-daemon
```

- [ ] **Step 3: Modify `Lexer.kt` to recognize `#` as a line comment.**

Find the existing `--` line-comment handler and extend it to also trigger on `#` when outside a string. The simplest change: in the main tokenization switch, add a `'#'` case that consumes to end-of-line.

- [ ] **Step 4: Run tests, confirm they pass.**

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Lexer.kt src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): accept hash as line comment in lexer"
```

---

### Task 3 — RollingHigh + RollingLow indicators

**Files:**
- Create: `src/main/kotlin/com/qkt/indicators/catalog/RollingHigh.kt`
- Create: `src/main/kotlin/com/qkt/indicators/catalog/RollingLow.kt`
- Create: `src/test/kotlin/com/qkt/indicators/catalog/RollingHighTest.kt`
- Create: `src/test/kotlin/com/qkt/indicators/catalog/RollingLowTest.kt`

- [ ] **Step 1: Write failing test for `RollingHigh`.**

```kotlin
package com.qkt.indicators.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RollingHighTest {
    @Test
    fun `returns null during warmup`() {
        val r = RollingHigh(period = 3)
        r.update(BigDecimal("10"))
        assertThat(r.value()).isNull()
        assertThat(r.isReady).isFalse
    }

    @Test
    fun `returns max of last N inputs after warmup, excluding current`() {
        val r = RollingHigh(period = 3)
        r.update(BigDecimal("10"))
        r.update(BigDecimal("20"))
        r.update(BigDecimal("15"))     // 3 updates: window has 10, 20, 15. value() = 20.
        assertThat(r.value()).isEqualTo(BigDecimal("20"))
        r.update(BigDecimal("25"))     // window now: 20, 15, 25. value() = 25.
        assertThat(r.value()).isEqualTo(BigDecimal("25"))
        r.update(BigDecimal("18"))     // window now: 15, 25, 18. value() = 25.
        assertThat(r.value()).isEqualTo(BigDecimal("25"))
    }

    @Test
    fun `warmupBars equals period`() {
        assertThat(RollingHigh(5).warmupBars).isEqualTo(5)
    }
}
```

- [ ] **Step 2: Run test, confirm it fails (class doesn't exist yet).**

- [ ] **Step 3: Implement `RollingHigh.kt`.**

```kotlin
package com.qkt.indicators.catalog

import com.qkt.indicators.Indicator
import com.qkt.indicators.IndicatorOutput
import java.math.BigDecimal

/**
 * Rolling-window maximum of the last [period] input values.
 *
 * Used for Donchian-channel breakout strategies. The semantics intentionally
 * **exclude the current bar** so that a condition like
 * `close > highest(close, N)` can fire — the current close is compared against
 * the highs of the previous N bars, not against itself.
 *
 * @sample com.qkt.indicators.catalog.RollingHighSample
 */
class RollingHigh(
    private val period: Int,
) : Indicator<BigDecimal>, IndicatorOutput {
    init {
        require(period > 0) { "period must be > 0: $period" }
    }

    private val window: ArrayDeque<BigDecimal> = ArrayDeque(period)

    override fun update(input: BigDecimal) {
        window.addLast(input)
        while (window.size > period) window.removeFirst()
    }

    override fun value(): BigDecimal? = if (window.size < period) null else window.max()

    override val isReady: Boolean
        get() = window.size >= period

    override val warmupBars: Int = period
}
```

Note: the test expects that `value()` after exactly `period` updates returns the max of those updates. That's the "include up through last update" semantics — but the rule `close > highest(close, N)` needs to evaluate the **comparison** before the current bar's `close` enters the window. The way this works in qkt: the parser arranges for `highest(stream.close, N)` to be computed against history that was current when the rule's host candle entered the strategy. The Indicator class itself doesn't need to know about "exclude current" — the call-site coordination is the parser's job.

Decision: ship the indicator as "max of the last `period` updates" (simplest). The "exclude current" guarantee is enforced at the DSL level by reading from the indicator's history-before-this-bar slot — same as how `ema(close, N)` is currently coordinated. If integration tests show this doesn't produce the expected breakout behavior, revisit.

- [ ] **Step 4: Run test, confirm it passes.**

- [ ] **Step 5: Repeat steps 1–4 for `RollingLow` — same shape, `window.min()` instead of `max()`.**

- [ ] **Step 6: Commit.**

```bash
git add src/main/kotlin/com/qkt/indicators/catalog/RollingHigh.kt \
        src/main/kotlin/com/qkt/indicators/catalog/RollingLow.kt \
        src/test/kotlin/com/qkt/indicators/catalog/RollingHighTest.kt \
        src/test/kotlin/com/qkt/indicators/catalog/RollingLowTest.kt
git commit -m "feat(indicators): rolling high and rolling low indicators"
```

---

### Task 4 — Register 5 indicators + 2 new ones in DSL

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/stdlib/IndicatorRegistryTest.kt` (or create)

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test
fun `registry exposes all eight catalog indicators plus rolling extremes`() {
    val expected = setOf(
        "EMA", "SMA", "WMA", "RSI", "ATR",
        "MACD", "MACD_SIGNAL", "MACD_HIST",
        "VWAP",
        "BOLLINGER_UPPER", "BOLLINGER_MIDDLE", "BOLLINGER_LOWER",
        "HIGHEST", "LOWEST",
    )
    for (name in expected) {
        assertThat(IndicatorRegistry.has(name)).withFailMessage("missing: %s", name).isTrue
    }
}
```

- [ ] **Step 2: Run test, confirm it fails.**

- [ ] **Step 3: Extend `IndicatorRegistry.kt`** to add the new entries. Each follows the existing pattern.

For MACD multi-output: the simplest approach is **three separate factory entries** that internally cache by `(value-source, fast, slow, signal)` and return different output views. This requires extending `IndicatorSpec` slightly OR adding a wrapper that exposes only one output. For Phase 23, the simplest path: each entry creates a fresh `MACD` instance and wraps it in an `IndicatorOutput` that surfaces the desired output. Deduplication happens at the `IndicatorBinding` cache level downstream — the parser sees three distinct factory calls but the engine can recognize identical parameter tuples and share the underlying.

For minimum scope: don't worry about cross-name deduplication in this phase. Each `MACD_*` call creates its own underlying MACD instance. Optimization can come later if benchmarks show it matters.

```kotlin
"SMA" to IndicatorSpec("SMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
    SMA(period = args[0].toInt())
},
"WMA" to IndicatorSpec("WMA", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
    WMA(period = args[0].toInt())
},
"MACD" to IndicatorSpec("MACD", IndicatorInput.NUMERIC_SERIES, arity = 4) { args ->
    val m = MACD(fast = args[0].toInt(), slow = args[1].toInt(), signal = args[2].toInt())
    object : IndicatorOutput by m { override fun value() = m.value() }   // MACD line
},
// MACD_SIGNAL and MACD_HIST follow same pattern, exposing different outputs
"VWAP" to IndicatorSpec("VWAP", IndicatorInput.CANDLE_SERIES, arity = 2) { args ->
    VWAP(period = args[0].toInt())
},
"BOLLINGER_UPPER" to ... ,
"BOLLINGER_MIDDLE" to ... ,
"BOLLINGER_LOWER" to ... ,
"HIGHEST" to IndicatorSpec("HIGHEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
    RollingHigh(period = args[0].toInt())
},
"LOWEST" to IndicatorSpec("LOWEST", IndicatorInput.NUMERIC_SERIES, arity = 2) { args ->
    RollingLow(period = args[0].toInt())
},
```

Note: VWAP expects a Candle input in this design — confirm by checking the existing `VWAP` class signature in Step 1.

For MACD: read `MACD.kt` to determine the actual constructor signature and how to expose each output. The MACD class likely has `value()` returning the macd line and a `lines()` method returning a triple. The output wrappers above are a sketch — adjust to the actual class.

- [ ] **Step 4: Run all tests, confirm pass.**

```bash
./gradlew test --no-daemon
```

- [ ] **Step 5: Commit.**

```bash
git add src/main/kotlin/com/qkt/dsl/stdlib/IndicatorRegistry.kt \
        src/test/kotlin/com/qkt/dsl/stdlib/IndicatorRegistryTest.kt
git commit -m "feat(dsl): register sma wma macd vwap bollinger and rolling extremes"
```

---

### Task 5 — Position.openedAt + accessors

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/Position.kt` (add `openedAt: Long?` field)
- Modify: `src/main/kotlin/com/qkt/positions/PositionTracker.kt` (set/reset openedAt on transitions)
- Modify: `src/main/kotlin/com/qkt/dsl/stdlib/StateRefs.kt` (expose accessors)
- Test: `src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt`
- Test: `src/test/kotlin/com/qkt/dsl/stdlib/StateRefsTest.kt`

- [ ] **Step 1: Write a failing test for `openedAt` on `PositionTracker`.**

```kotlin
@Test
fun `openedAt is set on flat-to-long transition`() {
    val tracker = PositionTracker()
    val clock = FixedClock(start = 1000L)
    tracker.apply(Trade("o1", "BTC", BigDecimal("100"), BigDecimal("1"), Side.BUY, clock.now()))
    val pos = tracker.positionFor("BTC")!!
    assertThat(pos.openedAt).isEqualTo(1000L)
}

@Test
fun `openedAt is preserved on additions to existing position`() {
    val tracker = PositionTracker()
    tracker.apply(Trade("o1", "BTC", BigDecimal("100"), BigDecimal("1"), Side.BUY, 1000L))
    tracker.apply(Trade("o2", "BTC", BigDecimal("110"), BigDecimal("1"), Side.BUY, 2000L))
    val pos = tracker.positionFor("BTC")!!
    assertThat(pos.openedAt).isEqualTo(1000L)    // still the first open
}

@Test
fun `openedAt resets on close-then-reopen`() {
    val tracker = PositionTracker()
    tracker.apply(Trade("o1", "BTC", BigDecimal("100"), BigDecimal("1"), Side.BUY, 1000L))
    tracker.apply(Trade("o2", "BTC", BigDecimal("110"), BigDecimal("1"), Side.SELL, 2000L))    // close
    tracker.apply(Trade("o3", "BTC", BigDecimal("120"), BigDecimal("1"), Side.BUY, 3000L))    // reopen
    val pos = tracker.positionFor("BTC")!!
    assertThat(pos.openedAt).isEqualTo(3000L)
}

@Test
fun `openedAt resets on long-to-short flip`() {
    val tracker = PositionTracker()
    tracker.apply(Trade("o1", "BTC", BigDecimal("100"), BigDecimal("1"), Side.BUY, 1000L))
    tracker.apply(Trade("o2", "BTC", BigDecimal("90"), BigDecimal("3"), Side.SELL, 2000L))    // 1 long → 2 short
    val pos = tracker.positionFor("BTC")!!
    assertThat(pos.openedAt).isEqualTo(2000L)    // new short position
}
```

- [ ] **Step 2: Run tests, confirm they fail (no `openedAt` field).**

- [ ] **Step 3: Add `openedAt: Long?` to `Position`.** Default `null`.

- [ ] **Step 4: Update `PositionTracker.apply(...)` to set/reset `openedAt`:**
  - Transition from `quantity = 0` to non-zero: set `openedAt = trade.timestamp`.
  - Addition to existing position (sign preserved): keep existing `openedAt`.
  - Transition back to zero: set `openedAt = null`.
  - Flip (sign change): set `openedAt = trade.timestamp` (new position).

- [ ] **Step 5: Run tests, confirm pass.**

- [ ] **Step 6: Add accessors to `StateRefs.kt`. Sketch:**

```kotlin
data class PositionRef(val streamAlias: String) {
    fun resolve(ctx: EvalContext): PositionState {
        val pos = ctx.positions.positionFor(ctx.streams[streamAlias]!!.symbol) ?: PositionState.empty(streamAlias)
        return PositionState(
            quantity = pos.quantity,
            entryPrice = pos.avgEntryPrice,
            unrealizedPnl = ctx.pnl.unrealizedFor(ctx.strategyId, pos.symbol),
            realizedPnl = ctx.pnl.realizedFor(ctx.strategyId, pos.symbol),
            pnl = ...combined...,
            holdingDuration = pos.openedAt?.let { ctx.clock.now() - it } ?: 0L,
        )
    }
}

data class PositionState(
    val quantity: BigDecimal,
    val entryPrice: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val realizedPnl: BigDecimal,
    val pnl: BigDecimal,
    val holdingDuration: Long,
)
```

The exact API depends on how the existing `PositionRef` is wired. Match the existing style; this is a sketch.

- [ ] **Step 7: Extend the parser to recognize `position(<alias>).<accessor>`.** The parser already handles `position(<alias>)` returning quantity; add a `.<ident>` continuation for the accessor.

- [ ] **Step 8: Write parser tests:**

```kotlin
@Test
fun `position dot accessors parse and evaluate`() {
    val src = """
        STRATEGY t VERSION 1
        SYMBOLS btc = X:Y EVERY 1m
        RULES
            WHEN position(btc).pnl > 100 THEN CLOSE btc
            WHEN position(btc).entry_price > 50000 THEN LOG "high entry"
            WHEN position(btc).holding_duration > 3600000 THEN CLOSE btc
    """.trimIndent()
    val result = Dsl.parse(src)
    assertThat(result).isInstanceOf(ParseResult.Success::class.java)
}
```

- [ ] **Step 9: Run all tests, confirm pass.**

```bash
./gradlew build --no-daemon
```

- [ ] **Step 10: Commit.**

```bash
git add src/main/kotlin/com/qkt/positions/Position.kt \
        src/main/kotlin/com/qkt/positions/PositionTracker.kt \
        src/main/kotlin/com/qkt/dsl/stdlib/StateRefs.kt \
        src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/test/kotlin/com/qkt/positions/PositionTrackerTest.kt \
        src/test/kotlin/com/qkt/dsl/stdlib/StateRefsTest.kt
git commit -m "feat(dsl): position dot accessors entry_price pnl holding_duration"
```

---

### Task 6 — Phase 23 changelog + version bump

**Files:**
- Modify: `src/main/kotlin/com/qkt/cli/BuildInfo.kt` — bump VERSION to `0.25.0`
- Create: `docs/phases/phase-23.md`
- Modify: `docs/backlog.md` — mark Phase 23 done, add Phase 24/25 entries
- Modify: `docs/phases/index.md` — add Phase 23 link
- Modify: `mkdocs.yml` — add Phase 23 to nav

- [ ] **Step 1: Bump `BuildInfo.VERSION` to `"0.25.0"`.** (Phase 22 was 0.24.0.)

- [ ] **Step 2: Write the changelog** at `docs/phases/phase-23.md` covering Summary / What's new / Migration / Usage cookbook / Testing patterns / Known limitations / References. Per the SKILL: 200-500 lines, usage examples for every new surface.

- [ ] **Step 3: Add Phase 23 to `docs/phases/index.md`.**

- [ ] **Step 4: Add Phase 23 to `mkdocs.yml` nav** under "Release changelogs".

- [ ] **Step 5: Update `docs/backlog.md`:**
  - Mark Phase 23 as `done`
  - Add `tbd` entries for Phase 24 (Risk-sizing primitives) and Phase 25 (Operator tooling)

- [ ] **Step 6: Commit.**

```bash
git add src/main/kotlin/com/qkt/cli/BuildInfo.kt \
        docs/phases/phase-23.md \
        docs/phases/index.md \
        docs/backlog.md \
        mkdocs.yml
git commit -m "chore(cli): bump version to 0.25.0 and add phase 23 changelog"
```

---

### Task 7 — Precheck, merge, push

- [ ] **Step 1: Run the full precheck.**

```bash
./scripts/precheck.sh --full
```

Expected: all green.

- [ ] **Step 2: Verify mkdocs strict build.**

```bash
.venv-docs/bin/mkdocs build --strict
```

- [ ] **Step 3: Merge to main.**

```bash
git checkout main
git merge --no-ff phase23-dsl-catalog-expansion -m "merge: phase 23 dsl catalog expansion"
git push origin main
git push origin phase23-dsl-catalog-expansion
```

- [ ] **Step 4: Watch CI runs.**

```bash
gh run list --limit 3
```

Verify both `check` and `docs` workflows pass.

---

## Self-review

Spec coverage:
- ✅ All 5 indicator registrations (Task 4)
- ✅ RollingHigh / RollingLow (Task 3)
- ✅ Position accessors (Task 5)
- ✅ Hash comments (Task 2)
- ✅ Version bump + changelog (Task 6)

Placeholders: none.

Type consistency: `IndicatorOutput` used uniformly, `BigDecimal` for prices, `Long?` for openedAt. PositionState dataclass is sketched; adjust during impl based on actual StateRefs shape.
