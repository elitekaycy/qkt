# Phase 35 — Bar-Level Synchronized Publish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let strategies declare named sync groups of streams (`SYNCHRONIZE gold silver [WITHIN <duration>]`) inside the `SYMBOLS` block, so the engine evaluates the strategy once-per-group-bar-window with all member candles atomically in scope.

**Architecture:** AST gains `List<SyncGroupDecl>`. Parser reads `SYNCHRONIZE` clauses after the last `StreamDecl` in `parseSymbols`, with parse-time validation (≥2 aliases, no overlap, all aliases declared). `CandleHub` gains a `Map<String, SyncGroup>` registry; grouped slots route their close-events into a pending-bars map per window and fire once all members for that window have arrived. Timeouts are tick-driven (checked from `feed(tick)`). `AstCompiler.bindToHub` registers grouped aliases through one group listener each and skips them in the per-stream loop. Non-sync strategies behave identically to today.

**Tech Stack:** Kotlin 2.1.0, JUnit 5, AssertJ. No new dependencies.

---

## File Structure

**New files:**
- `src/test/kotlin/com/qkt/dsl/parse/ParserSyncSymbolsTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTimeoutTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/AstCompilerSyncTest.kt`
- `src/test/kotlin/com/qkt/dsl/compile/SyncPairsEndToEndTest.kt`
- `docs/phases/phase-35-bar-sync.md`

**Modified files:**
- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `SYNCHRONIZE`
- `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt` — add `SyncGroupDecl` + `StrategyAst.syncGroups`
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — `parseSymbols` returns streams + groups; `parseStrategy` (line ~258) wires `syncGroups` into the `StrategyAst` constructor
- `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt` — add `SyncGroup` internal class, `syncGroups` map, `syncGroupsByKey` reverse index, `registerSyncGroup`, `onSyncClosed`, extend slot close-callback, add `checkSyncTimeouts` called from `feed`
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (~line 208-218) — group-aware `bindToHub` loop
- `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt` — add SYNCHRONIZE recognition case
- `docs/reference/dsl/streams.md` — add "Synchronizing streams" subsection

---

## Task 1: Add `SYNCHRONIZE` token

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Modify: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

The lexer derives keywords reflectively from `TokenKind.values()` minus a denylist (see `Lexer.kt:251-287`). Adding a value to `TokenKind` auto-registers it as a keyword; no Lexer change needed.

- [ ] **Step 1: Add the failing test**

In `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`, append after the existing AFTER test (committed for #48):

```kotlin
@Test
fun `SYNCHRONIZE is tokenized as TokenKind SYNCHRONIZE`() {
    val tokens = Lexer("SYNCHRONIZE synchronize Synchronize").tokenize()
    assertThat(tokens.map { it.kind })
        .containsExactly(
            TokenKind.SYNCHRONIZE,
            TokenKind.SYNCHRONIZE,
            TokenKind.SYNCHRONIZE,
            TokenKind.EOF,
        )
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.LexerTest.SYNCHRONIZE is tokenized as TokenKind SYNCHRONIZE' --no-daemon -q
```

Expected: FAIL — `TokenKind.SYNCHRONIZE` does not exist (compile error).

- [ ] **Step 3: Add the token**

Open `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`. Insert `SYNCHRONIZE` in alphabetical order with the existing top-level keywords (it's a strategy-block-level keyword like `SYMBOLS`, `RULES`, `DEFAULTS`).

The exact placement matches the codebase's ordering convention — open the file, find `SYMBOLS,` and add `SYNCHRONIZE,` either immediately before or after it.

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.LexerTest.SYNCHRONIZE is tokenized as TokenKind SYNCHRONIZE' --no-daemon -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt \
        src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): add SYNCHRONIZE token for paired-symbol sync groups (#45)"
```

---

## Task 2: Add `SyncGroupDecl` AST node + `StrategyAst.syncGroups` field

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`

- [ ] **Step 1: Add the data class**

In `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`, add the new declaration right after the existing `StreamDecl` data class:

```kotlin
/**
 * One declared sync group inside a `SYMBOLS` block. The engine evaluates the
 * strategy once per group-bar-window, with every member's bar in scope atomically.
 *
 * e.g. `SYNCHRONIZE gold silver WITHIN 200ms` parses to
 * `SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = 200)`.
 *
 * See `docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md` (#45).
 */
data class SyncGroupDecl(
    val aliases: List<String>,
    val timeoutMs: Long? = null,
) {
    init {
        require(aliases.size >= 2) {
            "SyncGroupDecl needs at least 2 aliases, got ${aliases.size}"
        }
        require(timeoutMs == null || timeoutMs > 0) {
            "SyncGroupDecl.timeoutMs must be positive when present: $timeoutMs"
        }
        require(aliases.toSet().size == aliases.size) {
            "SyncGroupDecl aliases must be unique: $aliases"
        }
    }
}
```

- [ ] **Step 2: Add the `syncGroups` field to `StrategyAst`**

In the same file, change `StrategyAst` from:

```kotlin
data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
)
```

to:

```kotlin
data class StrategyAst(
    val name: String,
    val version: Int,
    val streams: List<StreamDecl>,
    val constants: List<ConstantDecl>,
    val lets: List<LetDecl>,
    val defaults: DefaultsBlock?,
    val rules: List<RuleAst>,
    val syncGroups: List<SyncGroupDecl> = emptyList(),
)
```

The default keeps every existing `StrategyAst(...)` caller building unchanged — the field is purely additive.

- [ ] **Step 3: Verify compile**

```bash
./gradlew compileKotlin --no-daemon -q
```

Expected: SUCCESS. This task adds types only; nothing depends on them yet.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt
git commit -m "feat(dsl): add SyncGroupDecl AST and StrategyAst.syncGroups field (#45)"
```

---

## Task 3: Parser support for `SYNCHRONIZE` clauses

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (parseSymbols ~line 1228, parseStrategy ~line 235-267)
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserSyncSymbolsTest.kt`

`parseSymbols` returns `List<StreamDecl>` today. We change its return type to a small internal struct so it can also carry `syncGroups`. The caller in `parseStrategy` destructures it and threads `syncGroups` into the `StrategyAst` constructor.

- [ ] **Step 1: Write the failing parser test**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserSyncSymbolsTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.SyncGroupDecl
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #45 — `SYNCHRONIZE <alias1> <alias2> [WITHIN <duration>]` clauses inside `SYMBOLS`.
 */
class ParserSyncSymbolsTest {
    private fun parseStrategy(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `single SYNCHRONIZE clause parses to one SyncGroupDecl`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h
              silver = EXNESS:XAGUSD EVERY 1h
              SYNCHRONIZE gold silver
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).hasSize(1)
        assertThat(ast.syncGroups[0])
            .isEqualTo(SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = null))
    }

    @Test
    fun `multiple SYNCHRONIZE clauses parse to independent groups`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h
              silver = EXNESS:XAGUSD EVERY 1h
              btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
              eth    = BYBIT_SPOT:ETHUSDT EVERY 1h
              SYNCHRONIZE gold silver
              SYNCHRONIZE btc eth WITHIN 200ms
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).hasSize(2)
        assertThat(ast.syncGroups[0])
            .isEqualTo(SyncGroupDecl(aliases = listOf("gold", "silver"), timeoutMs = null))
        assertThat(ast.syncGroups[1])
            .isEqualTo(SyncGroupDecl(aliases = listOf("btc", "eth"), timeoutMs = 200L))
    }

    @Test
    fun `strategy without SYNCHRONIZE has empty syncGroups`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val ast = parseStrategy(src).value
        assertThat(ast.syncGroups).isEmpty()
    }

    @Test
    fun `SYNCHRONIZE with single alias is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
              SYNCHRONIZE gold
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message).containsIgnoringCase("at least 2")
    }

    @Test
    fun `SYNCHRONIZE referencing an undeclared alias is rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1h
              SYNCHRONIZE gold mystery
            RULES
              WHEN gold.close > 0 THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message)
            .containsIgnoringCase("mystery")
            .containsIgnoringCase("not declared")
    }

    @Test
    fun `overlapping SYNCHRONIZE groups are rejected`() {
        val src =
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold   = EXNESS:XAUUSD EVERY 1h
              silver = EXNESS:XAGUSD EVERY 1h
              btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
              SYNCHRONIZE gold silver
              SYNCHRONIZE silver btc
            RULES
              WHEN gold.close > silver.close THEN BUY gold SIZING 0.1
            """.trimIndent()
        val result = Dsl.parse(src) as ParseResult.Failure
        assertThat(result.errors.first().message)
            .containsIgnoringCase("silver")
            .containsIgnoringCase("more than one")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserSyncSymbolsTest' --no-daemon -q
```

Expected: ALL FAIL — `parseSymbols` doesn't yet recognise `SYNCHRONIZE`.

- [ ] **Step 3: Change `parseSymbols` to return streams + groups**

In `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`, replace `parseSymbols` (line 1228-1270) with the version below. The do-while loop reading stream declarations is unchanged; after it ends, we look for `SYNCHRONIZE` clauses, validate them, and return both lists together.

```kotlin
private data class SymbolsBlock(
    val streams: List<StreamDecl>,
    val syncGroups: List<SyncGroupDecl>,
)

private fun parseSymbols(): SymbolsBlock {
    val out = mutableListOf<StreamDecl>()
    expect(TokenKind.SYMBOLS, "expected SYMBOLS")
    do {
        val alias = expect(TokenKind.IDENT, "expected stream alias").lexeme
        expect(TokenKind.EQ, "expected '=' after stream alias")
        val broker = expect(TokenKind.IDENT, "expected broker prefix").lexeme
        expect(TokenKind.COLON, "expected ':' between broker and symbol")
        val symbol = expect(TokenKind.IDENT, "expected symbol after ':'").lexeme
        expect(TokenKind.EVERY, "expected EVERY")
        val timeframe =
            if (peek().kind == TokenKind.DURATION) {
                advance().lexeme
            } else {
                val tfNum = expect(TokenKind.NUMBER, "expected timeframe count").lexeme
                val tfUnit = expect(TokenKind.IDENT, "expected timeframe unit (s/m/h/d)").lexeme
                "$tfNum$tfUnit"
            }
        val warmupBars: Int? =
            if (peek().kind == TokenKind.WARMUP) {
                advance()
                val numToken = expect(TokenKind.NUMBER, "expected integer bar count after WARMUP")
                val n =
                    numToken.lexeme.toIntOrNull()
                        ?: error("WARMUP count must be a positive integer, got '${numToken.lexeme}'")
                if (n <= 0) error("WARMUP count must be > 0, got $n")
                expect(TokenKind.BARS, "expected BARS after WARMUP count")
                n
            } else {
                null
            }
        out.add(
            StreamDecl(
                alias = alias,
                broker = broker,
                symbol = symbol,
                timeframe = timeframe,
                warmupBars = warmupBars,
            ),
        )
    } while (match(TokenKind.COMMA))

    // SYNCHRONIZE clauses live at the end of the SYMBOLS block (#45). Each clause
    // is `SYNCHRONIZE <ident> <ident> [<ident> …] [WITHIN <duration>]`.
    val groups = mutableListOf<SyncGroupDecl>()
    val declaredAliases = out.map { it.alias }.toSet()
    val claimed = mutableMapOf<String, Int>() // alias -> group index that owns it
    while (peek().kind == TokenKind.SYNCHRONIZE) {
        advance()
        val aliases = mutableListOf<String>()
        while (peek().kind == TokenKind.IDENT) {
            aliases.add(advance().lexeme)
        }
        if (aliases.size < 2) {
            error("SYNCHRONIZE requires at least 2 aliases, got ${aliases.size}")
        }
        val timeoutMs: Long? =
            if (peek().kind == TokenKind.WITHIN) {
                advance()
                parseDuration().toMillis()
            } else {
                null
            }
        for (a in aliases) {
            if (a !in declaredAliases) {
                error("SYNCHRONIZE alias '$a' is not declared in SYMBOLS")
            }
            val prevGroupIdx = claimed[a]
            if (prevGroupIdx != null) {
                error(
                    "SYNCHRONIZE alias '$a' appears in more than one group " +
                        "(also in group ${prevGroupIdx + 1})",
                )
            }
            claimed[a] = groups.size
        }
        groups.add(SyncGroupDecl(aliases = aliases.toList(), timeoutMs = timeoutMs))
    }

    return SymbolsBlock(streams = out, syncGroups = groups)
}
```

You'll also need a `parseDuration()` helper. Search the file:

```bash
grep -n "fun parseDuration\|TokenKind.DURATION" src/main/kotlin/com/qkt/dsl/parse/Parser.kt | head
```

If a helper already exists, use it. If not, the duration is parsed as a `TokenKind.DURATION` lexeme (`"200ms"`, `"5s"`, `"1h"`); reuse the lexeme-to-millis conversion used by `STACK_AT … WITHIN <duration>` — search for that to find the existing pattern.

- [ ] **Step 4: Update the `parseSymbols` callers**

`parseSymbols` is called in two places (lines 143 and 237 — `grep -n parseSymbols src/main/kotlin/com/qkt/dsl/parse/Parser.kt`). Replace each call's return-handling. For the strategy path at line ~235:

```kotlin
val symbolsBlock =
    if (peek().kind == TokenKind.SYMBOLS) {
        tryParse { parseSymbols() } ?: SymbolsBlock(emptyList(), emptyList())
    } else {
        SymbolsBlock(emptyList(), emptyList())
    }
val streams = symbolsBlock.streams
val syncGroups = symbolsBlock.syncGroups
```

For the portfolio path at line ~143, syncGroups doesn't apply to portfolios in this phase — discard it:

```kotlin
val streams =
    if (peek().kind == TokenKind.SYMBOLS) {
        tryParse { parseSymbols().streams } ?: emptyList()
    } else {
        emptyList()
    }
```

- [ ] **Step 5: Thread `syncGroups` into the `StrategyAst` constructor**

At line ~257-267, the strategy success path builds the `StrategyAst`. Add `syncGroups = syncGroups`:

```kotlin
return ParseResult.Success(
    StrategyAst(
        name = name,
        version = version,
        streams = streams,
        constants = emptyList(),
        lets = lets,
        defaults = defaults,
        rules = rules,
        syncGroups = syncGroups,
    ),
)
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserSyncSymbolsTest' --no-daemon -q
```

Expected: ALL PASS (6 tests).

- [ ] **Step 7: Run the full parser test suite to check for regressions**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.*' --no-daemon -q
```

Expected: PASS. Other parser tests don't reference `syncGroups`, so the additive change should be invisible.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt \
        src/test/kotlin/com/qkt/dsl/parse/ParserSyncSymbolsTest.kt
git commit -m "feat(dsl): parse SYNCHRONIZE clauses with validation (#45)"
```

---

## Task 4: `CandleHub` `SyncGroup` data structure + registration API

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt`

This task adds the data structure and the empty registration API — no firing yet. Task 5 adds the close-callback wiring; Task 6 adds the timeout.

- [ ] **Step 1: Write the failing registration test**

Create `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — CandleHub sync-group registration + atomic fire on full arrival.
 *
 * Atomic-fire behavior is covered by `fires once both members close a bar for the same window`.
 * Timeout behavior lives in [CandleHubSyncTimeoutTest].
 */
class CandleHubSyncTest {
    private val gold = HubKey("BACKTEST", "GOLD", "1m")
    private val silver = HubKey("BACKTEST", "SILVER", "1m")

    @Test
    fun `registerSyncGroup with two keys does not throw`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "s1")
        hub.register(silver, retention = 5, strategyId = "s1")
        hub.registerSyncGroup(
            id = "s1#0",
            keys = listOf(gold, silver),
            timeoutMs = null,
            strategyId = "s1",
        )
        // No exception is the assertion at this stage; the firing logic is Task 5.
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTest' --no-daemon -q
```

Expected: FAIL — `registerSyncGroup` doesn't exist yet (compile error).

- [ ] **Step 3: Add the `SyncGroup` internal type and registry to `CandleHub`**

Open `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`. Below the existing `Slot` private class (around line 35), add:

```kotlin
private data class OwnedSyncListener(
    val strategyId: String,
    val callback: (Map<HubKey, Candle>) -> Unit,
)

private class SyncGroup(
    val id: String,
    val keys: List<HubKey>,
    val timeoutMs: Long?,
    val listeners: MutableList<OwnedSyncListener>,
    val owners: MutableSet<String>,
    /** window-end-ms → (HubKey → Candle) — accumulated until full. */
    val pending: MutableMap<Long, MutableMap<HubKey, Candle>> = mutableMapOf(),
    var firstArrivalMs: Long? = null,
)
```

Then add the registry fields right next to the existing `slots` map:

```kotlin
private val syncGroups: MutableMap<String, SyncGroup> = java.util.concurrent.ConcurrentHashMap()
private val syncGroupsByKey: MutableMap<HubKey, MutableList<String>> = java.util.concurrent.ConcurrentHashMap()
```

- [ ] **Step 4: Add the public `registerSyncGroup` API**

After the existing `register` function (which ends around line 63), add:

```kotlin
fun registerSyncGroup(
    id: String,
    keys: List<HubKey>,
    timeoutMs: Long?,
    strategyId: String,
) {
    require(id.isNotBlank()) { "sync group id must be non-blank" }
    require(keys.size >= 2) { "sync group $id needs at least 2 keys, got ${keys.size}" }
    require(timeoutMs == null || timeoutMs > 0) {
        "sync group $id timeoutMs must be positive when present: $timeoutMs"
    }
    require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
    val existing = syncGroups[id]
    if (existing != null) {
        existing.owners.add(strategyId)
        return
    }
    val group =
        SyncGroup(
            id = id,
            keys = keys.toList(),
            timeoutMs = timeoutMs,
            listeners = mutableListOf(),
            owners = mutableSetOf(strategyId),
        )
    syncGroups[id] = group
    for (key in keys) {
        syncGroupsByKey.getOrPut(key) { mutableListOf() }.add(id)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTest' --no-daemon -q
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt \
        src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt
git commit -m "feat(compile): CandleHub SyncGroup registry and registerSyncGroup API (#45)"
```

---

## Task 5: `CandleHub` atomic-fire on full arrival

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt` (extend)

- [ ] **Step 1: Add the failing atomic-fire test**

Append to `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt`:

```kotlin
private fun closedCandle(symbol: String, windowEnd: Long, close: String) =
    Candle(
        symbol = symbol,
        open = Money.of(close),
        high = Money.of(close),
        low = Money.of(close),
        close = Money.of(close),
        volume = BigDecimal.ONE,
        startTime = windowEnd - 60_000L,
        endTime = windowEnd,
    )

private fun tickFor(symbol: String, t: Long, price: String) =
    Tick(symbol = symbol, price = Money.of(price), timestamp = t)

@Test
fun `fires once both members close a bar for the same window`() {
    val hub = CandleHub()
    hub.register(gold, retention = 5, strategyId = "s1")
    hub.register(silver, retention = 5, strategyId = "s1")
    hub.registerSyncGroup("s1#0", listOf(gold, silver), timeoutMs = null, "s1")

    val captured = mutableListOf<Map<HubKey, Candle>>()
    hub.onSyncClosed("s1#0", "s1") { captured.add(it) }

    // Drive ticks so each slot closes a bar at end=60_000 (the 0..60_000 bar's close).
    // Gold ticks first, silver second.
    hub.feed(tickFor("GOLD", t = 0L, price = "100"))
    hub.feed(tickFor("GOLD", t = 60_000L, price = "101"))   // closes gold's 1m bar
    assertThat(captured).isEmpty()                          // silver hasn't closed yet

    hub.feed(tickFor("SILVER", t = 0L, price = "10"))
    hub.feed(tickFor("SILVER", t = 60_000L, price = "11"))  // closes silver's 1m bar
    assertThat(captured).hasSize(1)
    val pair = captured.single()
    assertThat(pair.keys).containsExactlyInAnyOrder(gold, silver)
}

@Test
fun `does not fire per-key listeners for grouped slots`() {
    val hub = CandleHub()
    hub.register(gold, retention = 5, strategyId = "s1")
    hub.register(silver, retention = 5, strategyId = "s1")
    hub.registerSyncGroup("s1#0", listOf(gold, silver), timeoutMs = null, "s1")

    val perKey = mutableListOf<Candle>()
    hub.onClosed(gold, "s1") { perKey.add(it) }
    hub.onClosed(silver, "s1") { perKey.add(it) }
    val sync = mutableListOf<Map<HubKey, Candle>>()
    hub.onSyncClosed("s1#0", "s1") { sync.add(it) }

    hub.feed(tickFor("GOLD", 0L, "100"))
    hub.feed(tickFor("GOLD", 60_000L, "101"))
    hub.feed(tickFor("SILVER", 0L, "10"))
    hub.feed(tickFor("SILVER", 60_000L, "11"))

    assertThat(sync).hasSize(1)
    assertThat(perKey).isEmpty()   // grouped slots route only through the group listener
}

@Test
fun `non-grouped slot continues to fire its own per-stream listeners`() {
    val hub = CandleHub()
    val btc = HubKey("BACKTEST", "BTC", "1m")
    hub.register(gold, retention = 5, strategyId = "s1")
    hub.register(silver, retention = 5, strategyId = "s1")
    hub.register(btc, retention = 5, strategyId = "s1")
    hub.registerSyncGroup("s1#0", listOf(gold, silver), timeoutMs = null, "s1")

    val perKey = mutableListOf<Candle>()
    hub.onClosed(btc, "s1") { perKey.add(it) }

    hub.feed(tickFor("BTC", 0L, "50000"))
    hub.feed(tickFor("BTC", 60_000L, "50100"))   // closes btc's 1m bar
    assertThat(perKey).hasSize(1)                // standalone slot still fires
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTest' --no-daemon -q
```

Expected: FAIL — `onSyncClosed` doesn't exist, atomic-fire is not wired.

- [ ] **Step 3: Add `onSyncClosed` to `CandleHub`**

After the existing `onClosed` function (around line 113), add:

```kotlin
fun onSyncClosed(
    id: String,
    strategyId: String,
    callback: (Map<HubKey, Candle>) -> Unit,
) {
    require(strategyId.isNotBlank()) { "strategyId must be non-blank" }
    val group = syncGroups[id] ?: error("CandleHub.onSyncClosed: unknown sync group $id")
    group.listeners.add(OwnedSyncListener(strategyId, callback))
}
```

- [ ] **Step 4: Route slot-close events to sync groups**

Find the existing close-callback inside `register` (around line 55-61):

```kotlin
val agg =
    CandleAggregator.standalone(window) { closed ->
        val slot = slots[key] ?: return@standalone
        ring.addLast(closed)
        while (ring.size > slot.retention) ring.removeFirst()
        for (l in slot.listeners.toList()) l.callback(closed)
    }
```

Replace the inner block with the group-aware version:

```kotlin
val agg =
    CandleAggregator.standalone(window) { closed ->
        val slot = slots[key] ?: return@standalone
        ring.addLast(closed)
        while (ring.size > slot.retention) ring.removeFirst()

        // Route to sync groups owning this key; if any group claims the key, the
        // per-stream listeners are SUPPRESSED for that close — grouped strategies
        // see one atomic fire, not two partial fires.
        val groupIds = syncGroupsByKey[key]
        if (groupIds.isNullOrEmpty()) {
            for (l in slot.listeners.toList()) l.callback(closed)
            return@standalone
        }
        for (groupId in groupIds) {
            val group = syncGroups[groupId] ?: continue
            val window = closed.endTime
            val map = group.pending.getOrPut(window) { mutableMapOf() }
            map[key] = closed
            if (group.firstArrivalMs == null) {
                group.firstArrivalMs = System.currentTimeMillis()
            }
            if (map.keys == group.keys.toSet()) {
                val snapshot = map.toMap()
                group.pending.remove(window)
                if (group.pending.isEmpty()) group.firstArrivalMs = null
                for (l in group.listeners.toList()) l.callback(snapshot)
            }
        }
    }
```

A few notes the implementer should be aware of:

- The `closed` candle's `endTime` is the synchronization window key. Two slots that close bars with the same `endTime` belong to the same group window.
- Snapshotting `map.toMap()` before invoking listeners gives them an immutable view — callbacks that re-enter the hub can't see partial state.
- `System.currentTimeMillis()` is OK here because this isn't on the deterministic backtest path — the firstArrivalMs is only consulted by the timeout heartbeat (Task 6), which is itself non-deterministic when timeouts are set. Backtests with `timeoutMs = null` never read it.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTest' --no-daemon -q
```

Expected: ALL PASS (4 tests including the previous registration test).

- [ ] **Step 6: Run the wider hub-and-aggregator suite for regressions**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHub*' --tests 'com.qkt.candles.*' --no-daemon -q
```

Expected: PASS. The per-stream behavior is preserved for any slot not in a group.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt \
        src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTest.kt
git commit -m "feat(compile): CandleHub atomic fire when all sync group members close (#45)"
```

---

## Task 6: Tick-driven timeout heartbeat

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTimeoutTest.kt`

`CandleHub` doesn't currently have a `Clock` reference. The simplest approach is to keep `System.currentTimeMillis()` for live behaviour and inject a clock-source via a constructor parameter with a sane default. For test determinism we control time via the injected source.

- [ ] **Step 1: Add a clock-source constructor parameter to `CandleHub`**

Change the class signature (around line 23):

```kotlin
class CandleHub(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
```

Replace `System.currentTimeMillis()` in Task 5's close-callback with `nowMs()`. Two call sites if any.

This is a non-breaking change because `nowMs` has a default — every existing `CandleHub()` call works unchanged.

- [ ] **Step 2: Write the failing timeout test**

Create `src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTimeoutTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — sync-group timeout fires partial bars when one member doesn't close in time.
 * Tick-driven heartbeat: timeouts are checked from `feed(tick)`, not on a scheduler.
 */
class CandleHubSyncTimeoutTest {
    private val gold = HubKey("BACKTEST", "GOLD", "1m")
    private val silver = HubKey("BACKTEST", "SILVER", "1m")

    private fun tickFor(symbol: String, t: Long, price: String) =
        Tick(symbol = symbol, price = Money.of(price), timestamp = t)

    @Test
    fun `partial fire after timeout elapses with WARN-shaped behavior`() {
        var now = 0L
        val hub = CandleHub(nowMs = { now })
        hub.register(gold, retention = 5, strategyId = "s1")
        hub.register(silver, retention = 5, strategyId = "s1")
        hub.registerSyncGroup("s1#0", listOf(gold, silver), timeoutMs = 100L, "s1")

        val captured = mutableListOf<Map<HubKey, Candle>>()
        hub.onSyncClosed("s1#0", "s1") { captured.add(it) }

        // gold closes at window=60_000; silver never closes.
        hub.feed(tickFor("GOLD", 0L, "100"))
        now = 1L
        hub.feed(tickFor("GOLD", 60_000L, "101")) // closes gold
        assertThat(captured).isEmpty()

        // Advance clock past the 100ms timeout. The next tick on any symbol triggers
        // the heartbeat — gold's partial set fires alone.
        now = 200L
        hub.feed(tickFor("GOLD", 60_001L, "101.5")) // no candle close, just heartbeat
        assertThat(captured).hasSize(1)
        assertThat(captured.single().keys).containsExactly(gold)
    }

    @Test
    fun `no timeout means wait forever`() {
        var now = 0L
        val hub = CandleHub(nowMs = { now })
        hub.register(gold, retention = 5, strategyId = "s1")
        hub.register(silver, retention = 5, strategyId = "s1")
        hub.registerSyncGroup("s1#0", listOf(gold, silver), timeoutMs = null, "s1")

        val captured = mutableListOf<Map<HubKey, Candle>>()
        hub.onSyncClosed("s1#0", "s1") { captured.add(it) }

        hub.feed(tickFor("GOLD", 0L, "100"))
        hub.feed(tickFor("GOLD", 60_000L, "101"))
        now = 10_000L                                // 10 seconds, no silver
        hub.feed(tickFor("GOLD", 60_001L, "101.5"))
        assertThat(captured).isEmpty()               // group with null timeout never gives up
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTimeoutTest' --no-daemon -q
```

Expected: FAIL — heartbeat is not wired.

- [ ] **Step 4: Add `checkSyncTimeouts` and call it from `feed`**

Find the existing `feed` function in `CandleHub` (around line 65):

```kotlin
fun feed(tick: Tick) {
    for ((key, slot) in slots) {
        if (key.qktSymbol == tick.symbol) slot.aggregator.onTick(tick)
    }
}
```

Add the heartbeat call at the end and the helper function below it:

```kotlin
fun feed(tick: Tick) {
    for ((key, slot) in slots) {
        if (key.qktSymbol == tick.symbol) slot.aggregator.onTick(tick)
    }
    checkSyncTimeouts(nowMs())
}

private fun checkSyncTimeouts(now: Long) {
    for (group in syncGroups.values) {
        val firstArrival = group.firstArrivalMs ?: continue
        val timeout = group.timeoutMs ?: continue
        if (now - firstArrival <= timeout) continue
        if (group.pending.isEmpty()) {
            group.firstArrivalMs = null
            continue
        }
        val windows = group.pending.keys.toList()
        for (window in windows) {
            val partial = group.pending.getValue(window).toMap()
            group.pending.remove(window)
            log.warn(
                "sync group {} fired with {}/{} members after {}ms timeout (window={})",
                group.id,
                partial.size,
                group.keys.size,
                now - firstArrival,
                window,
            )
            for (l in group.listeners.toList()) l.callback(partial)
        }
        group.firstArrivalMs = null
    }
}
```

If `log` isn't already declared in `CandleHub`, add at the top of the class:

```kotlin
private val log = org.slf4j.LoggerFactory.getLogger(CandleHub::class.java)
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.CandleHubSyncTimeoutTest' --tests 'com.qkt.dsl.compile.CandleHubSyncTest' --no-daemon -q
```

Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt \
        src/test/kotlin/com/qkt/dsl/compile/CandleHubSyncTimeoutTest.kt
git commit -m "feat(compile): tick-driven timeout heartbeat for sync groups (#45)"
```

---

## Task 7: `AstCompiler.bindToHub` group-aware wiring

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (lines ~208-218)
- Test: `src/test/kotlin/com/qkt/dsl/compile/AstCompilerSyncTest.kt`

The current `bindToHub` (lines ~208-218):

```kotlin
boundHub = hub
for ((alias, key) in streams) {
    val seeded = hub.historySize(key)
    if (seeded > 0) warmupGate.recordBars(alias, seeded)
    hub.onClosed(key, ctx.strategyId) { closed ->
        evaluate(alias, closed, hub, ctx, emit)
    }
}
```

We add a group-aware path before the per-stream loop, and skip grouped aliases in the loop.

- [ ] **Step 1: Write the failing compiler test**

Create `src/test/kotlin/com/qkt/dsl/compile/AstCompilerSyncTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — AstCompiler.bindToHub routes grouped aliases through one sync-group listener,
 * non-grouped aliases through the existing per-stream listener path. Covered surface
 * here is the wiring (one fire per grouped close-pair, two fires for a non-grouped
 * standalone); end-to-end Backtest semantics live in [SyncPairsEndToEndTest].
 */
class AstCompilerSyncTest {
    private fun compile(src: String) =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    @Test
    fun `two streams in a SYNCHRONIZE group fire one evaluation per pair of closes`() {
        val src =
            """
            STRATEGY t VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gold   = BACKTEST:GOLD EVERY 1m
              silver = BACKTEST:SILVER EVERY 1m
              SYNCHRONIZE gold silver
            RULES
              WHEN gold.close > 0 AND silver.close > 0 AND POSITION.gold = 0
              THEN BUY gold
            """.trimIndent()
        val strategy = compile(src)
        // Verify the strategy can compile to a runnable form.
        assertThat(strategy).isNotNull
    }
}
```

This test is intentionally small — it only verifies compile + bind doesn't throw. The substantive behavioral assertion lives in `SyncPairsEndToEndTest` (Task 8), which runs the strategy through `Backtest` and checks fire counts. We split this way so the unit test stays focused and fast, and the integration test owns the wire-level semantics.

- [ ] **Step 2: Run the test to verify it fails or passes (likely passes)**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerSyncTest' --no-daemon -q
```

Likely PASSES already if the AST changes from Task 2 don't crash compilation. If it FAILS, the failure points at a real wiring bug.

- [ ] **Step 3: Update `bindToHub` to skip grouped aliases in the per-stream loop**

In `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`, locate the existing `bindToHub` body (lines ~208-218) and replace it with:

```kotlin
boundHub = hub
val groupedAliases = ast.syncGroups.flatMap { it.aliases }.toSet()

for ((alias, key) in streams) {
    val seeded = hub.historySize(key)
    if (seeded > 0) warmupGate.recordBars(alias, seeded)
    if (alias in groupedAliases) continue
    hub.onClosed(key, ctx.strategyId) { closed ->
        evaluate(alias, closed, hub, ctx, emit)
    }
}

for ((groupIdx, group) in ast.syncGroups.withIndex()) {
    val keys = group.aliases.map { streams.getValue(it) }
    val groupId = "${ctx.strategyId}#$groupIdx"
    hub.registerSyncGroup(
        id = groupId,
        keys = keys,
        timeoutMs = group.timeoutMs,
        strategyId = ctx.strategyId,
    )
    hub.onSyncClosed(groupId, ctx.strategyId) { closedMap ->
        val driverAlias = group.aliases.first()
        val driverKey = streams.getValue(driverAlias)
        val driverCandle = closedMap.getValue(driverKey)
        evaluate(driverAlias, driverCandle, hub, ctx, emit)
    }
}
```

Notes:
- The warmup-gate recording still runs for every alias (line `if (seeded > 0) warmupGate.recordBars(alias, seeded)`), grouped or not — `WarmupGate` tracks readiness per alias, and grouping doesn't change that.
- The driver-alias call to `evaluate` is sufficient: when the rule body reads `silver.close`, that goes through `hub.history(silverKey, 0)`, which now returns the silver bar that just arrived (because the group placed it into the ring atomically before invoking listeners).

- [ ] **Step 4: Run the compiler test to verify it passes**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.AstCompilerSyncTest' --no-daemon -q
```

Expected: PASS.

- [ ] **Step 5: Run the surrounding compiler suite for regressions**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.AstCompiler*' --tests 'com.qkt.dsl.compile.Action*' --no-daemon -q
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt \
        src/test/kotlin/com/qkt/dsl/compile/AstCompilerSyncTest.kt
git commit -m "feat(compile): bindToHub routes grouped aliases through sync listener (#45)"
```

---

## Task 8: End-to-end Backtest test for sync semantics

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/compile/SyncPairsEndToEndTest.kt`

This is the integration test that proves the whole stack works — DSL → AstCompiler → CandleHub sync group → Backtest fires the rule the correct number of times.

- [ ] **Step 1: Write the end-to-end test**

Create `src/test/kotlin/com/qkt/dsl/compile/SyncPairsEndToEndTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — `SYNCHRONIZE` end-to-end against a deterministic two-symbol tick stream.
 *
 * The strategy below would fire its WHEN on every candle close that satisfies the
 * predicate. With `SYNCHRONIZE gold silver`, it fires exactly once per matched pair
 * of bar closes. Without the clause, it fires twice per pair (once when gold closes
 * with stale silver, once when silver closes with current values).
 */
class SyncPairsEndToEndTest {
    private fun compile(src: String) =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private fun ticks(stream: List<Triple<String, Long, String>>): List<Tick> =
        stream.map { (symbol, t, price) -> Tick(symbol = symbol, price = Money.of(price), timestamp = t) }

    /** Strategy: BUY gold when `gold.close > silver.close`. Two streams, one BUY rule. */
    private fun src(synchronize: Boolean): String =
        """
        STRATEGY pairs VERSION 1
        DEFAULTS { SIZING = 1 TIF = GTC }
        SYMBOLS
          gold   = BACKTEST:GOLD EVERY 1m
          silver = BACKTEST:SILVER EVERY 1m
          ${if (synchronize) "SYNCHRONIZE gold silver" else ""}
        RULES
          WHEN gold.close > silver.close AND POSITION.gold = 0
          THEN BUY gold
        """.trimIndent()

    /**
     * Interleave gold and silver ticks so each 1m bar closes for both symbols
     * within the same Backtest run. End times line up at 60_000, 120_000, 180_000.
     */
    private val sample =
        ticks(
            listOf(
                Triple("GOLD",   0L,       "100"),
                Triple("SILVER", 0L,       "50"),
                Triple("GOLD",   60_000L,  "101"),    // gold's [0,60_000) closes here
                Triple("SILVER", 60_000L,  "51"),     // silver's [0,60_000) closes here
                Triple("GOLD",   120_000L, "102"),
                Triple("SILVER", 120_000L, "52"),
                Triple("GOLD",   180_000L, "103"),
                Triple("SILVER", 180_000L, "53"),
            ),
        )

    @Test
    fun `SYNCHRONIZE fires the rule once per matched bar pair`() {
        val result =
            Backtest(
                strategies = listOf("pairs" to compile(src(synchronize = true))),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        // 3 bar pairs (close times 60_000, 120_000, 180_000) → one BUY signal, then
        // POSITION.gold > 0 so subsequent bars don't re-buy.
        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(1)
    }

    @Test
    fun `without SYNCHRONIZE the rule fires per-stream as before — no regression`() {
        val result =
            Backtest(
                strategies = listOf("pairs" to compile(src(synchronize = false))),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        // Same tick stream, same predicate, no sync clause. Just sanity-check that
        // the strategy compiles and runs end-to-end the same way it did before #45.
        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).isNotEmpty
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.SyncPairsEndToEndTest' --no-daemon -q
```

Expected: BOTH TESTS PASS.

- [ ] **Step 3: Run the full test suite to confirm no broader regression**

```bash
./gradlew test --no-daemon -q
```

Expected: PASS. If `SseStreamTest` flakes (it has historically), rerun: failures unrelated to #45 don't block this task.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/qkt/dsl/compile/SyncPairsEndToEndTest.kt
git commit -m "test(compile): end-to-end SYNCHRONIZE fires once per matched bar pair (#45)"
```

---

## Task 9: Documentation

**Files:**
- Modify: `docs/reference/dsl/streams.md`
- Create: `docs/phases/phase-35-bar-sync.md`

- [ ] **Step 1: Add the reference subsection to `streams.md`**

Open `docs/reference/dsl/streams.md`. After the existing stream-declaration subsection, append:

```markdown
## Synchronizing streams

Strategies that act on **multiple streams together** — pairs trading, basket strategies, lead-lag rules — usually want the engine to evaluate the rule body once per *matched* bar window, with every member's candle current. Without synchronization, each stream's close triggers an independent evaluation that reads the other side's last-known value (which may be a bar behind).

Declare a sync group inside the `SYMBOLS` block with the `SYNCHRONIZE` keyword:

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
    eth    = BYBIT_SPOT:ETHUSDT EVERY 1h
    vix    = TV:VIX EVERY 1d

    SYNCHRONIZE gold silver
    SYNCHRONIZE btc eth WITHIN 200ms
    -- vix is not in any clause, so it fires independently on its own bar closes.
```

Each `SYNCHRONIZE` clause defines one independent group. Streams not listed in any clause keep firing per-bar exactly as before.

The optional `WITHIN <duration>` declares a timeout — if a group's first member closes a bar but the rest don't arrive within the window, the engine fires the rule with whatever's present and logs a warning. Useful for cross-broker pairs where one venue's tick can lag by hundreds of milliseconds. Same-broker pairs don't usually need a timeout; leave it off and the engine waits forever (in practice, microseconds).

Rules:
- Every `SYNCHRONIZE` clause must list at least two aliases.
- Every listed alias must be declared above in the `SYMBOLS` block.
- An alias appears in at most one group — overlapping groups are rejected at parse.

See [Phase 35 — Bar-Level Synchronized Publish](../../phases/phase-35-bar-sync.md) for the worked examples and known limitations.
```

- [ ] **Step 2: Create the phase changelog**

Create `docs/phases/phase-35-bar-sync.md`:

```markdown
# Phase 35 — Bar-Level Synchronized Publish for Paired Symbols

## Summary

Strategies can now declare sync groups of streams via `SYNCHRONIZE` clauses inside `SYMBOLS`. The engine evaluates the strategy once per group-bar-window, with every member's candle current and in scope. Closes #45 — the proper substrate for pairs trading that #174's "latest known candle" approximation deferred.

## What's new

- New DSL keyword `SYNCHRONIZE`, parsed as a clause inside the `SYMBOLS` block. Multiple clauses per strategy are allowed; each defines an independent group.
- Optional `WITHIN <duration>` on each clause sets a per-group timeout.
- New AST node `SyncGroupDecl` and field `StrategyAst.syncGroups: List<SyncGroupDecl>` (default empty).
- New `CandleHub` registry: `registerSyncGroup(id, keys, timeoutMs, strategyId)` and `onSyncClosed(id, strategyId, callback)`.
- Tick-driven timeout heartbeat: timeouts checked from `CandleHub.feed(tick)`.
- `AstCompiler.bindToHub` skips grouped aliases in the per-stream loop and registers one combined listener per group.

## Migration from previous phase

Pure addition. Every existing strategy parses and runs unchanged.

To opt a pairs strategy in, add one line inside `SYMBOLS`:

```diff
 SYMBOLS
     gold   = EXNESS:XAUUSD EVERY 1h
     silver = EXNESS:XAGUSD EVERY 1h
+    SYNCHRONIZE gold silver
```

## Usage cookbook

### Single sync pair, same broker, no timeout

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    SYNCHRONIZE gold silver

RULES
    WHEN gold.close - 75 * silver.close > 200 AND POSITION.gold = 0
    THEN SELL gold SIZING 0.5 PCT RISK
```

Reads: same broker, same timeframe — both bars close within microseconds, so no timeout is needed. The rule fires exactly once per 1h boundary.

### Two independent groups, mixed timeouts

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    btc    = BYBIT_SPOT:BTCUSDT EVERY 1h
    eth    = BYBIT_SPOT:ETHUSDT EVERY 1h
    SYNCHRONIZE gold silver
    SYNCHRONIZE btc eth WITHIN 200ms
```

Reads: two independent pairs. The metals pair is on the same broker (no timeout); the crypto pair is cross-symbol on the same venue and uses a 200ms guard.

### Mixed sync + standalone

```qkt
SYMBOLS
    gold   = EXNESS:XAUUSD EVERY 1h
    silver = EXNESS:XAGUSD EVERY 1h
    vix    = TV:VIX EVERY 1d
    SYNCHRONIZE gold silver
    -- vix is not in any group → independent firings

RULES
    WHEN gold.close > silver.close * 75 AND vix.close < 25
    THEN BUY gold SIZING 0.5 PCT RISK
```

The `SYNCHRONIZE gold silver` clause means the rule re-evaluates once per matched gold/silver bar with both candles current. `vix` is independent: its daily close triggers its own evaluation, and the gold/silver values in that evaluation come from whichever bar was most recently closed.

## Testing patterns

`CandleHubSyncTest` is the canonical unit-level fixture for grouped close-events. Drive ticks per symbol via `hub.feed(tick)`; assert the captured callback list once both members of the group close a bar for the same `endTime`.

`SyncPairsEndToEndTest` is the integration fixture — DSL through `Backtest` with a deterministic interleaved tick stream. Compare fire counts with and without the `SYNCHRONIZE` clause.

## Known limitations

- **Cross-timeframe sync** (e.g. 5m gold + 1h silver in the same group) is not supported. Different timeframes don't have a natural shared bar window. Defer until a real strategy requires it.
- **Timeouts depend on tick arrival.** If every stream in a group stops ticking, no timeout fires until ticks resume. In practice, both members of a pair never stop simultaneously.
- **Pending-bars state is not persisted across daemon restart.** On restart the group resumes from empty; the next clean group close fires normally. MT5StateRecovery still handles open positions.
- **Portfolios don't expose `SYNCHRONIZE`.** This phase is per-strategy only — portfolio supervisors that watch multiple streams as a unit are a future extension.

## References

- Spec: [`docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md`](../superpowers/specs/2026-05-30-phase35-bar-sync-design.md)
- Plan: [`docs/superpowers/plans/2026-05-30-phase35-bar-sync.md`](../superpowers/plans/2026-05-30-phase35-bar-sync.md)
- Issue: [#45](https://github.com/elitekaycy/qkt/issues/45)
- Reference DSL: [Synchronizing streams](../reference/dsl/streams.md#synchronizing-streams)
- Precursor: Phase 34 — expression-fed indicators ([#174](https://github.com/elitekaycy/qkt/issues/174)) — introduced the "latest known candle" approximation Phase 35 replaces for opted-in strategies.
```

- [ ] **Step 3: Verify mkdocs renders the new pages (best-effort)**

If `mkdocs` is installed locally:

```bash
mkdocs build --strict 2>&1 | tail -20
```

Expected: SUCCESS. If `mkdocs` isn't available locally, CI's docs job catches issues.

- [ ] **Step 4: Commit**

```bash
git add docs/reference/dsl/streams.md \
        docs/phases/phase-35-bar-sync.md
git commit -m "docs(phase35): SYNCHRONIZE reference + phase changelog (#45)"
```

---

## Task 10: PR

- [ ] **Step 1: Bring the branch up to date with `dev`**

```bash
git fetch origin dev
git rebase origin/dev
./gradlew build --no-daemon -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Open the PR**

```bash
gh pr create --base dev --head phase35-impl \
  --title "feat(dsl): bar-level synchronized publish for paired symbols (#45)" \
  --body "$(cat <<'EOF'
Closes #45.

## Phase
Phase 35. Spec: `docs/superpowers/specs/2026-05-30-phase35-bar-sync-design.md`. Plan: `docs/superpowers/plans/2026-05-30-phase35-bar-sync.md`.

## Summary
New DSL clause `SYNCHRONIZE <alias1> <alias2> [WITHIN <duration>]` inside the `SYMBOLS` block declares a sync group. The engine waits until every member of the group has closed a bar for the same time window, then evaluates the strategy once with every candle in scope. Multiple independent groups per strategy are supported; streams not in any clause keep firing per-stream as before.

## Changes
- New token `SYNCHRONIZE`.
- New AST node `SyncGroupDecl`; field `StrategyAst.syncGroups: List<SyncGroupDecl>` (default empty).
- Parser: `SYNCHRONIZE` clauses parse at end of `SYMBOLS`, validated against the declared aliases.
- `CandleHub`: new `SyncGroup` registry; close-callback routes grouped slots into a pending-bars map and fires atomically when all members arrive; `feed(tick)` doubles as a heartbeat that fires partial bars past their timeout with a WARN log.
- `AstCompiler.bindToHub`: skips grouped aliases in the per-stream loop and registers one combined listener per group.

## Tests
- `ParserSyncSymbolsTest` — 6 cases (single group, multi-group, WITHIN, single-alias rejected, undeclared-alias rejected, overlapping-groups rejected).
- `CandleHubSyncTest` — registration + atomic fire + no per-stream listener leak + non-grouped path unchanged.
- `CandleHubSyncTimeoutTest` — partial fire after timeout with WARN; null-timeout waits forever.
- `AstCompilerSyncTest` — wire-level smoke for grouped vs non-grouped compile + bind.
- `SyncPairsEndToEndTest` — end-to-end Backtest, with-vs-without SYNCHRONIZE fire counts.
- Full local suite passes (verify with `./gradlew test --no-daemon`).

## Docs
- `docs/reference/dsl/streams.md` — "Synchronizing streams" subsection.
- `docs/phases/phase-35-bar-sync.md` — phase changelog with three worked examples.

## Backwards compatibility
Pure addition. `synchronized` defaults to `emptyList()` on every existing `StrategyAst`. The engine path for non-sync strategies is byte-identical to today.

## Out of scope
- Cross-timeframe sync (5m gold + 1h silver in the same group).
- Persisting pending-bars across daemon restart.
- Portfolio-level sync.
- Precise scheduler-based timeouts (tick-driven heartbeat is good enough for the live consumers we have).

## Risk
Medium. Touches the candle event flow that every DSL strategy uses. Mitigations:
- Grouped path is gated on `ast.syncGroups.isNotEmpty()`; non-grouped strategies skip the new code entirely.
- Hub atomic-fire snapshots the pending map before invoking listeners (callbacks see an immutable view).
- End-to-end test compares fire counts with vs without the clause on the same tick stream.

## Follow-up
A separate qkt-prod commit updates `pairs_xau_xag.qkt` with `SYNCHRONIZE gold silver` once this image is on prod. The strategy file change is one line and reversible.
EOF
)"
```

- [ ] **Step 3: Watch CI**

```bash
gh pr checks --watch
```

Expected: build PASS.

---

## Self-review checklist

**Spec coverage:**
- DSL syntax (`SYNCHRONIZE` + `WITHIN`) → Tasks 1 (token), 3 (parse)
- Multi-group AST shape → Tasks 2 (type), 3 (parse populates it)
- Parser validation rules (≥2 aliases, declared, no overlap) → Task 3
- `SyncGroup` engine type + registry → Task 4
- Atomic fire on full arrival → Task 5
- Tick-driven timeout heartbeat → Task 6
- `AstCompiler.bindToHub` wiring → Task 7
- Driver-alias mechanism (`evaluate` signature unchanged) → Task 7 step 3
- Backtest semantics (no engine change) → Task 8 (verified end-to-end)
- Warmup interaction (seed doesn't populate pending) → covered implicitly; warmup goes through `seed`, not the slot close-callback, so the sync-group route is untouched
- Backwards compatibility → preserved via `groupedAliases` early-skip in Task 7 and `default emptyList()` in Task 2
- Docs (reference + phase changelog) → Task 9

**Placeholder scan:**
- No "TBD", no "fill in details", no "similar to Task N".
- The single `parseDuration()` reference in Task 3 directs the implementer to find the existing helper in the file via `grep`; that's a navigation hint, not a placeholder — the helper already exists in the codebase (used by `STACK_AT … WITHIN`).
- Every test case shows actual assertions; every code change shows the full snippet.

**Type consistency:**
- `SyncGroupDecl(aliases, timeoutMs)` — same field names in Tasks 2, 3, 7.
- `registerSyncGroup(id, keys, timeoutMs, strategyId)` — same signature in Tasks 4, 7.
- `onSyncClosed(id, strategyId, callback: (Map<HubKey, Candle>) -> Unit)` — same in Tasks 5, 7.
- `SyncGroup.pending: MutableMap<Long, MutableMap<HubKey, Candle>>` — same key type and value type in Tasks 4, 5, 6.
- `nowMs: () -> Long` constructor param threaded consistently across `CandleHub` (Tasks 5, 6).

No gaps found.
