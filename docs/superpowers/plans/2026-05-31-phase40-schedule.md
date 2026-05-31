# Phase 40 — `SCHEDULE` DSL action implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a clock-driven `SCHEDULE` block to the DSL so strategies can run actions at exact times of day, independent of tick cadence. Closes [#77](https://github.com/elitekaycy/qkt/issues/77).

**Architecture:** New top-level block parsed alongside `RULES`. `ScheduleRunner` tracks per-strategy triggers; a heartbeat called from `TradingPipeline.ingest(tick)` (plus a 1Hz timer in `LiveSession` for quiet markets) advances watermarks and fires actions. Backtest uses the simulated clock; live uses wall clock. Missed fires log WARN and skip — no late firing.

**Tech Stack:** Kotlin, existing parser/AST/compile infra. New tokens `SCHEDULE`, `HOUR`, `DAY`, `WEEKDAY`, `UTC` reflected into the lexer's `KEYWORDS` map automatically.

See [docs/superpowers/specs/2026-05-31-phase40-schedule-design.md](../specs/2026-05-31-phase40-schedule-design.md) for design context.

---

## Task 1: Add `SCHEDULE`, `HOUR`, `DAY`, `WEEKDAY`, `UTC` tokens

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt`

The `Lexer` derives its KEYWORDS map from `TokenKind.values()` minus a denylist. Adding the variants to `TokenKind` is enough — no lexer code change needed (#196 confirmed this pattern).

- [ ] **Step 1: Write the failing lexer tests**

Append to `LexerTest.kt`:

```kotlin
@Test
fun `SCHEDULE is tokenized as TokenKind SCHEDULE`() {
    val tokens = Lexer("SCHEDULE").tokenize()
    assertThat(tokens.first().kind).isEqualTo(TokenKind.SCHEDULE)
}

@Test
fun `HOUR DAY WEEKDAY UTC are tokenized as their TokenKind variants`() {
    val tokens = Lexer("HOUR DAY WEEKDAY UTC").tokenize()
    assertThat(tokens.map { it.kind }).startsWith(
        TokenKind.HOUR, TokenKind.DAY, TokenKind.WEEKDAY, TokenKind.UTC,
    )
}
```

- [ ] **Step 2: Run them to verify they fail**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.LexerTest' --no-daemon
```

Expected: FAIL with `Unresolved reference: SCHEDULE` (and HOUR/DAY/WEEKDAY/UTC).

- [ ] **Step 3: Add tokens**

In `TokenKind.kt`, place them where they sit alphabetically/semantically. `SCHEDULE` near `RULES` and `SYMBOLS`; `HOUR`/`DAY`/`WEEKDAY` near `EVERY`/`AFTER`/`WITHIN`; `UTC` near other unit-style tokens.

```kotlin
enum class TokenKind {
    STRATEGY,
    VERSION,
    DEFAULTS,
    SYMBOLS,
    SYNCHRONIZE,
    SCHEDULE,           // <-- added
    LET,
    RULES,
    // ...
    WITHIN,
    AFTER,
    HOUR,               // <-- added
    DAY,                // <-- added
    WEEKDAY,            // <-- added
    UTC,                // <-- added
    DURATION,
    // ...
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.LexerTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt src/test/kotlin/com/qkt/dsl/parse/LexerTest.kt
git commit -m "feat(dsl): add SCHEDULE/HOUR/DAY/WEEKDAY/UTC tokens (#77)"
```

---

## Task 2: AST — `TimeOfDay`, `Timezone`, `ScheduleTrigger`, `ScheduleDecl`, `StrategyAst.schedules`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt`
- Test: `src/test/kotlin/com/qkt/dsl/ast/ScheduleAstTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/ast/ScheduleAstTest.kt`:

```kotlin
package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScheduleAstTest {
    @Test
    fun `TimeOfDay rejects out-of-range hour minute second`() {
        assertThatThrownBy { TimeOfDay(hour = 24, minute = 0) }.hasMessageContaining("hour")
        assertThatThrownBy { TimeOfDay(hour = 0, minute = 60) }.hasMessageContaining("minute")
        assertThatThrownBy { TimeOfDay(hour = 0, minute = 0, second = 60) }.hasMessageContaining("second")
    }

    @Test
    fun `ScheduleDecl requires at least one trigger`() {
        assertThatThrownBy {
            ScheduleDecl(triggers = emptyList(), action = Log("hello", Log.Level.INFO))
        }.hasMessageContaining("at least one trigger")
    }

    @Test
    fun `ScheduleTrigger At parses to time + UTC`() {
        val t = ScheduleTrigger.At(time = TimeOfDay(9, 0), tz = Timezone.UTC)
        assertThat(t.time.hour).isEqualTo(9)
    }

    @Test
    fun `ScheduleTrigger EveryHour rejects out-of-range minute offset`() {
        assertThatThrownBy {
            ScheduleTrigger.EveryHour(minuteOffset = 60)
        }.hasMessageContaining("minuteOffset")
    }

    @Test
    fun `StrategyAst schedules defaults to empty`() {
        val ast =
            StrategyAst(
                name = "t",
                version = 1,
                streams = emptyList(),
                constants = emptyList(),
                lets = emptyList(),
                defaults = null,
                rules = emptyList(),
            )
        assertThat(ast.schedules).isEmpty()
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests 'com.qkt.dsl.ast.ScheduleAstTest' --no-daemon
```

Expected: FAIL (`Unresolved reference: TimeOfDay` and friends).

- [ ] **Step 3: Implement in `StrategyAst.kt`**

Append:

```kotlin
/**
 * Time of day for a SCHEDULE trigger. `09:00` parses to `TimeOfDay(9, 0)`;
 * `19:55:30` parses to `TimeOfDay(19, 55, 30)`.
 */
data class TimeOfDay(
    val hour: Int,
    val minute: Int,
    val second: Int = 0,
) {
    init {
        require(hour in 0..23) { "TimeOfDay.hour must be 0-23: $hour" }
        require(minute in 0..59) { "TimeOfDay.minute must be 0-59: $minute" }
        require(second in 0..59) { "TimeOfDay.second must be 0-59: $second" }
    }
}

/**
 * Timezone tag on a SCHEDULE trigger. `UTC` is the only variant in Phase 40;
 * `LOCAL` and IANA names (`NY`, `LONDON`) are reserved for a follow-up phase.
 */
sealed interface Timezone {
    object UTC : Timezone
}

/**
 * One trigger inside a SCHEDULE clause. The action body fires once per trigger
 * fire — see [com.qkt.dsl.compile.ScheduleRunner] for execution semantics.
 */
sealed interface ScheduleTrigger {
    data class At(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger
    data class EveryHour(val minuteOffset: Int) : ScheduleTrigger {
        init {
            require(minuteOffset in 0..59) {
                "EveryHour.minuteOffset must be 0-59: $minuteOffset"
            }
        }
    }
    data class EveryDay(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger
    data class EveryWeekday(val time: TimeOfDay, val tz: Timezone) : ScheduleTrigger
}

/**
 * One SCHEDULE clause: a list of triggers (the `AT 09:00, 12:00, 14:00 UTC`
 * list form parses to >1 trigger) and the action body to run on each fire.
 */
data class ScheduleDecl(
    val triggers: List<ScheduleTrigger>,
    val action: ActionAst,
) {
    init {
        require(triggers.isNotEmpty()) {
            "ScheduleDecl needs at least one trigger"
        }
    }
}
```

Add field to `StrategyAst`:

```kotlin
data class StrategyAst(
    // ... existing fields ...
    val syncGroups: List<SyncGroupDecl> = emptyList(),
    val schedules: List<ScheduleDecl> = emptyList(),  // <-- added
) { ... }
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.qkt.dsl.ast.ScheduleAstTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt src/test/kotlin/com/qkt/dsl/ast/ScheduleAstTest.kt
git commit -m "feat(dsl): add SCHEDULE AST nodes — TimeOfDay, Timezone, ScheduleTrigger, ScheduleDecl (#77)"
```

---

## Task 3: Parser — `parseTimeOfDay`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt`

- [ ] **Step 1: Write the failing test for time-of-day parsing**

Create `src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt`:

```kotlin
package com.qkt.dsl.parse

import com.qkt.dsl.ast.TimeOfDay
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParserScheduleTest {
    private fun parseTime(src: String): TimeOfDay {
        // Drive parser through a tiny SCHEDULE clause for now;
        // expand once parseSchedules exists.
        // Placeholder: assumes Parser exposes parseTimeOfDay() internally for tests.
        return Parser(Lexer(src).tokenize()).parseTimeOfDayForTest()
    }

    @Test
    fun `09 colon 00 parses to 9 0 0`() {
        assertThat(parseTime("09:00")).isEqualTo(TimeOfDay(9, 0))
    }

    @Test
    fun `19 colon 55 colon 30 parses to 19 55 30`() {
        assertThat(parseTime("19:55:30")).isEqualTo(TimeOfDay(19, 55, 30))
    }

    @Test
    fun `time out of range is rejected at parse time`() {
        assertThatThrownBy { parseTime("24:00") }
    }
}
```

Note: this introduces a small testing seam (`parseTimeOfDayForTest`). The seam goes away in Task 5 when the full parser flow lands and tests can drive through `Dsl.parse(...)`.

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserScheduleTest' --no-daemon
```

Expected: FAIL (`Unresolved reference: parseTimeOfDayForTest`).

- [ ] **Step 3: Implement `parseTimeOfDay` in `Parser.kt`**

Add near `parseDuration`:

```kotlin
internal fun parseTimeOfDay(): TimeOfDay {
    val hour = expect(TokenKind.NUMBER, "expected hour")
    expect(TokenKind.COLON, "expected ':' after hour")
    val minute = expect(TokenKind.NUMBER, "expected minute")
    val second: Int =
        if (peek().kind == TokenKind.COLON) {
            advance()
            expect(TokenKind.NUMBER, "expected second").lexeme.toIntOrNull()
                ?: error("expected integer second")
        } else {
            0
        }
    return TimeOfDay(
        hour = hour.lexeme.toIntOrNull() ?: error("expected integer hour, got '${hour.lexeme}'"),
        minute = minute.lexeme.toIntOrNull() ?: error("expected integer minute, got '${minute.lexeme}'"),
        second = second,
    )
}

internal fun parseTimeOfDayForTest(): TimeOfDay = parseTimeOfDay()
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserScheduleTest' --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt
git commit -m "feat(dsl): parseTimeOfDay (#77)"
```

---

## Task 4: Parser — `parseScheduleTrigger`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt`

- [ ] **Step 1: Add failing tests for each trigger variant**

```kotlin
private fun parseTrigger(src: String): ScheduleTrigger =
    Parser(Lexer(src).tokenize()).parseScheduleTriggerForTest()

@Test
fun `AT 09 colon 00 UTC parses to At trigger`() {
    val t = parseTrigger("AT 09:00 UTC") as ScheduleTrigger.At
    assertThat(t.time).isEqualTo(TimeOfDay(9, 0))
    assertThat(t.tz).isEqualTo(Timezone.UTC)
}

@Test
fun `EVERY HOUR AT colon 30 parses to EveryHour 30`() {
    val t = parseTrigger("EVERY HOUR AT :30") as ScheduleTrigger.EveryHour
    assertThat(t.minuteOffset).isEqualTo(30)
}

@Test
fun `EVERY DAY AT 09 colon 00 UTC parses to EveryDay`() {
    val t = parseTrigger("EVERY DAY AT 09:00 UTC") as ScheduleTrigger.EveryDay
    assertThat(t.time.hour).isEqualTo(9)
}

@Test
fun `EVERY WEEKDAY AT 14 colon 30 UTC parses to EveryWeekday`() {
    val t = parseTrigger("EVERY WEEKDAY AT 14:30 UTC") as ScheduleTrigger.EveryWeekday
    assertThat(t.time.minute).isEqualTo(30)
}

@Test
fun `missing UTC tag on AT trigger is rejected`() {
    assertThatThrownBy { parseTrigger("AT 09:00") }
        .hasMessageContaining("UTC")
}
```

- [ ] **Step 2: Run to confirm failures**

Expected: every test fails.

- [ ] **Step 3: Implement**

```kotlin
internal fun parseScheduleTrigger(): ScheduleTrigger {
    return when (peek().kind) {
        TokenKind.AT -> {
            advance()
            val time = parseTimeOfDay()
            expect(TokenKind.UTC, "SCHEDULE trigger requires explicit UTC")
            ScheduleTrigger.At(time = time, tz = Timezone.UTC)
        }
        TokenKind.EVERY -> {
            advance()
            when (peek().kind) {
                TokenKind.HOUR -> {
                    advance()
                    expect(TokenKind.AT, "expected AT after EVERY HOUR")
                    expect(TokenKind.COLON, "expected ':' before minute offset")
                    val mNum = expect(TokenKind.NUMBER, "expected minute 0-59")
                    val m = mNum.lexeme.toIntOrNull() ?: error("expected integer minute")
                    ScheduleTrigger.EveryHour(minuteOffset = m)
                }
                TokenKind.DAY -> {
                    advance()
                    expect(TokenKind.AT, "expected AT after EVERY DAY")
                    val time = parseTimeOfDay()
                    expect(TokenKind.UTC, "EVERY DAY trigger requires explicit UTC")
                    ScheduleTrigger.EveryDay(time = time, tz = Timezone.UTC)
                }
                TokenKind.WEEKDAY -> {
                    advance()
                    expect(TokenKind.AT, "expected AT after EVERY WEEKDAY")
                    val time = parseTimeOfDay()
                    expect(TokenKind.UTC, "EVERY WEEKDAY trigger requires explicit UTC")
                    ScheduleTrigger.EveryWeekday(time = time, tz = Timezone.UTC)
                }
                else -> error("expected HOUR, DAY, or WEEKDAY after EVERY, got '${peek().lexeme}'")
            }
        }
        else -> error("expected AT or EVERY for SCHEDULE trigger, got '${peek().lexeme}'")
    }
}

internal fun parseScheduleTriggerForTest(): ScheduleTrigger = parseScheduleTrigger()
```

- [ ] **Step 4: Run tests**

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt
git commit -m "feat(dsl): parseScheduleTrigger for AT and EVERY variants (#77)"
```

---

## Task 5: Parser — `SCHEDULE` block + multi-time `AT` list + `StrategyAst.schedules`

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt`
- Test: `src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt`

- [ ] **Step 1: Add failing tests for the full block**

```kotlin
private fun parseFullStrategy(src: String) = Dsl.parse(src) as ParseResult.Success

@Test
fun `SCHEDULE block with single AT clause parses`() {
    val ast = parseFullStrategy(
        """
        STRATEGY t VERSION 1
        SYMBOLS
          gold = EXNESS:XAUUSD EVERY 1m
        SCHEDULE
          AT 09:00 UTC THEN LOG "morning"
        RULES
          WHEN gold.close > 0 THEN LOG "tick"
        """.trimIndent(),
    ).value
    assertThat(ast.schedules).hasSize(1)
    assertThat(ast.schedules[0].triggers).hasSize(1)
    assertThat((ast.schedules[0].triggers[0] as ScheduleTrigger.At).time)
        .isEqualTo(TimeOfDay(9, 0))
}

@Test
fun `AT list with multiple times parses to multiple triggers in one clause`() {
    val ast = parseFullStrategy(
        """
        STRATEGY t VERSION 1
        SYMBOLS
          gold = EXNESS:XAUUSD EVERY 1m
        SCHEDULE
          AT 09:00, 12:00, 14:00 UTC THEN LOG "checkpoint"
        RULES
          WHEN gold.close > 0 THEN LOG "tick"
        """.trimIndent(),
    ).value
    assertThat(ast.schedules).hasSize(1)
    assertThat(ast.schedules[0].triggers).hasSize(3)
}

@Test
fun `multiple SCHEDULE clauses with different triggers parse independently`() {
    val ast = parseFullStrategy(
        """
        STRATEGY t VERSION 1
        SYMBOLS
          gold = EXNESS:XAUUSD EVERY 1m
        SCHEDULE
          AT 09:00 UTC THEN BUY gold SIZING 0.1
          EVERY HOUR AT :00 THEN LOG "hourly"
          EVERY WEEKDAY AT 14:30 UTC THEN LOG "ny open"
        RULES
          WHEN gold.close > 0 THEN LOG "tick"
        """.trimIndent(),
    ).value
    assertThat(ast.schedules).hasSize(3)
}

@Test
fun `strategy without SCHEDULE has empty schedules`() {
    val ast = parseFullStrategy(
        """
        STRATEGY t VERSION 1
        SYMBOLS
          gold = EXNESS:XAUUSD EVERY 1m
        RULES
          WHEN gold.close > 0 THEN LOG "tick"
        """.trimIndent(),
    ).value
    assertThat(ast.schedules).isEmpty()
}
```

- [ ] **Step 2: Run to confirm failure**

Expected: every test fails (SCHEDULE block not yet parsed).

- [ ] **Step 3: Implement**

Add `parseSchedules` and integrate into `parseStrategy`:

```kotlin
internal fun parseSchedules(): List<ScheduleDecl> {
    expect(TokenKind.SCHEDULE, "expected SCHEDULE")
    val out = mutableListOf<ScheduleDecl>()
    while (peek().kind == TokenKind.AT || peek().kind == TokenKind.EVERY) {
        val triggers = mutableListOf<ScheduleTrigger>()
        if (peek().kind == TokenKind.AT) {
            // May be the "AT a, b, c UTC" list form OR a single "AT a UTC" trigger.
            advance() // AT
            val times = mutableListOf<TimeOfDay>()
            times.add(parseTimeOfDay())
            while (peek().kind == TokenKind.COMMA) {
                advance()
                times.add(parseTimeOfDay())
            }
            expect(TokenKind.UTC, "SCHEDULE AT requires explicit UTC")
            for (t in times) {
                triggers.add(ScheduleTrigger.At(time = t, tz = Timezone.UTC))
            }
        } else {
            triggers.add(parseScheduleTrigger())
        }
        expect(TokenKind.THEN, "expected THEN after SCHEDULE trigger(s)")
        val action = parseAction()
        out.add(ScheduleDecl(triggers = triggers, action = action))
    }
    return out
}
```

In `parseStrategy`, insert between `parseSymbols` and `parseRules` (or after `parseLet`, anywhere before `parseRules`):

```kotlin
val schedules =
    if (peek().kind == TokenKind.SCHEDULE) {
        tryParse { parseSchedules() } ?: emptyList()
    } else {
        emptyList()
    }
```

And pass through `StrategyAst(...)`:

```kotlin
return ParseResult.Success(
    StrategyAst(
        // ... existing args ...
        syncGroups = syncGroups,
        schedules = schedules,
    ),
)
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.qkt.dsl.parse.ParserScheduleTest' --no-daemon
```

Expected: every test PASSES. Also run the broader `parse.*` suite to check no regressions:

```bash
./gradlew test --tests 'com.qkt.dsl.parse.*' --no-daemon
```

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/parse/Parser.kt src/test/kotlin/com/qkt/dsl/parse/ParserScheduleTest.kt
git commit -m "feat(dsl): parse SCHEDULE block with multi-trigger and list-AT forms (#77)"
```

---

## Task 6: `ScheduleRunner` skeleton + registration API

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt`

This task lands only the registry shapes (`register`, `unregister`, `triggerCount()`) — the heartbeat fire logic is Task 7.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone
import com.qkt.dsl.ast.Log
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleRunnerTest {
    private val clock = object : Clock { override fun now(): Long = 0L }
    private val calendar = TradingCalendar.crypto()

    private fun decl(trigger: ScheduleTrigger): ScheduleDecl =
        ScheduleDecl(triggers = listOf(trigger), action = Log("hi", Log.Level.INFO))

    @Test
    fun `register tracks triggers per strategy`() {
        val runner = ScheduleRunner(clock = clock, calendar = calendar)
        runner.register(
            strategyId = "s1",
            schedule = decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
            emit = {},
        )
        assertThat(runner.triggerCount()).isEqualTo(1)
    }

    @Test
    fun `unregister drops a strategy's triggers`() {
        val runner = ScheduleRunner(clock = clock, calendar = calendar)
        runner.register(
            strategyId = "s1",
            schedule = decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
            emit = {},
        )
        runner.unregister("s1")
        assertThat(runner.triggerCount()).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Expected: `Unresolved reference: ScheduleRunner`.

- [ ] **Step 3: Implement skeleton**

Create `src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.common.Clock
import com.qkt.common.TradingCalendar
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger

/**
 * Clock-driven runner for SCHEDULE blocks. Each registered trigger has a
 * `nextFireMs` watermark; [tick] advances them and emits actions when due.
 * Backtest determinism: the runner reads time from [Clock] only — the same
 * clock that drives the rest of the engine — so a backtest with a simulated
 * clock fires schedules in lockstep with the tick stream.
 *
 * Fire model: skip-on-miss. If multiple fire times elapse between calls
 * (engine paused, restarted, quiet market longer than the trigger interval),
 * the runner advances the watermark past all of them, logs a WARN per skipped
 * fire, and emits the action exactly once with the current clock value.
 */
internal class ScheduleRunner(
    private val clock: Clock,
    private val calendar: TradingCalendar,
) {
    private data class Registration(
        val strategyId: String,
        val schedule: ScheduleDecl,
        val emit: () -> Unit,
        var nextFireMs: Long,
    )

    private val regs: MutableList<Registration> = mutableListOf()

    fun register(
        strategyId: String,
        schedule: ScheduleDecl,
        emit: () -> Unit,
    ) {
        val now = clock.now()
        for (trigger in schedule.triggers) {
            regs.add(
                Registration(
                    strategyId = strategyId,
                    schedule = schedule,
                    emit = emit,
                    nextFireMs = computeNextFire(trigger, now),
                ),
            )
        }
    }

    fun unregister(strategyId: String) {
        regs.removeAll { it.strategyId == strategyId }
    }

    fun triggerCount(): Int = regs.size

    /** Task 7 — heartbeat advances watermarks and fires actions. */
    fun tick(nowMs: Long) {
        // implemented in Task 7
    }

    private fun computeNextFire(trigger: ScheduleTrigger, fromMs: Long): Long {
        // implemented in Task 7
        return Long.MAX_VALUE
    }
}
```

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt
git commit -m "feat(engine): ScheduleRunner registration API (#77)"
```

---

## Task 7: `ScheduleRunner.tick` heartbeat + missed-fire WARN

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt`

This task lands the fire logic and the watermark advancement.

- [ ] **Step 1: Write the failing tests**

Append to `ScheduleRunnerTest.kt`:

```kotlin
private fun runnerWithFakeClock(initialMs: Long): Pair<ScheduleRunner, () -> Long> {
    var nowMs = initialMs
    val clock = object : Clock { override fun now(): Long = nowMs }
    return ScheduleRunner(clock = clock, calendar = TradingCalendar.crypto()) to { nowMs }
}

@Test
fun `At fires once at the scheduled UTC time`() {
    // 2026-06-01 00:00:00 UTC = 1748736000000
    val baseUtc = 1748736000000L
    val (runner, _) = runnerWithFakeClock(baseUtc)
    var fires = 0
    runner.register(
        "s1",
        decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
        emit = { fires++ },
    )

    runner.tick(baseUtc + 8 * 3_600_000L)  // 08:00 UTC
    assertThat(fires).isEqualTo(0)

    runner.tick(baseUtc + 9 * 3_600_000L)  // 09:00 UTC
    assertThat(fires).isEqualTo(1)

    runner.tick(baseUtc + 10 * 3_600_000L) // 10:00 UTC — does not refire same day
    assertThat(fires).isEqualTo(1)

    runner.tick(baseUtc + (24 + 9) * 3_600_000L) // next day 09:00
    assertThat(fires).isEqualTo(2)
}

@Test
fun `EveryHour at minute 30 fires once per hour at HH 30`() {
    val baseUtc = 1748736000000L  // midnight
    val (runner, _) = runnerWithFakeClock(baseUtc)
    var fires = 0
    runner.register(
        "s1",
        decl(ScheduleTrigger.EveryHour(minuteOffset = 30)),
        emit = { fires++ },
    )

    runner.tick(baseUtc + 30 * 60_000L)         // 00:30
    runner.tick(baseUtc + (60 + 30) * 60_000L)  // 01:30
    runner.tick(baseUtc + (120 + 30) * 60_000L) // 02:30
    assertThat(fires).isEqualTo(3)
}

@Test
fun `missed fire skips and emits no late fire`() {
    val baseUtc = 1748736000000L
    val (runner, _) = runnerWithFakeClock(baseUtc)
    var fires = 0
    runner.register(
        "s1",
        decl(ScheduleTrigger.EveryHour(minuteOffset = 0)),
        emit = { fires++ },
    )

    // Heartbeat skips from midnight to 05:00 — 5 hours, 5 missed fires.
    runner.tick(baseUtc + 5 * 3_600_000L)
    // Skip-on-miss: 4 missed get dropped, 1 fires at the current time.
    assertThat(fires).isEqualTo(1)
}

@Test
fun `EveryWeekday skips weekend days per calendar`() {
    // 2026-06-06 = Saturday. Strategy registered at Friday midnight.
    // Trigger should NOT fire on Saturday or Sunday.
    // Verify by advancing through both and checking fire count.
    // ... (full test implementation in the task — uses TradingCalendar.standard())
}
```

- [ ] **Step 2: Run to confirm failure**

Expected: all tests fail (tick is a no-op).

- [ ] **Step 3: Implement `tick` + `computeNextFire`**

```kotlin
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

private val log = LoggerFactory.getLogger("com.qkt.dsl.compile.ScheduleRunner")

fun tick(nowMs: Long) {
    for (reg in regs) {
        while (nowMs >= reg.nextFireMs) {
            val missed = (nowMs - reg.nextFireMs) > 60_000L
            // Advance watermark past every fire that's elapsed.
            val nextAfter = computeNextFire(reg.schedule.triggers.first(), reg.nextFireMs + 1)
            if (missed) {
                log.warn(
                    "schedule {} missed at {} (heartbeat was {}ms late)",
                    reg.strategyId,
                    Instant.ofEpochMilli(reg.nextFireMs),
                    nowMs - reg.nextFireMs,
                )
            }
            // Only fire if we're caught up to the most recent eligible time.
            val isLatestEligible = nextAfter > nowMs
            reg.nextFireMs = nextAfter
            if (isLatestEligible) reg.emit()
        }
    }
}

private fun computeNextFire(trigger: ScheduleTrigger, fromMs: Long): Long {
    val fromInstant = Instant.ofEpochMilli(fromMs)
    return when (trigger) {
        is ScheduleTrigger.At -> {
            val t = trigger.time
            val today = fromInstant.atZone(ZoneOffset.UTC).toLocalDate()
            val candidate =
                today.atTime(LocalTime.of(t.hour, t.minute, t.second))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            if (candidate >= fromMs) candidate else candidate + 86_400_000L
        }
        is ScheduleTrigger.EveryHour -> {
            val zoned = fromInstant.atZone(ZoneOffset.UTC)
            val thisHour = zoned.withMinute(trigger.minuteOffset).withSecond(0).withNano(0)
            val candidate = thisHour.toInstant().toEpochMilli()
            if (candidate >= fromMs) candidate else candidate + 3_600_000L
        }
        is ScheduleTrigger.EveryDay -> {
            // same as At in this phase
            computeNextFire(ScheduleTrigger.At(trigger.time, trigger.tz), fromMs)
        }
        is ScheduleTrigger.EveryWeekday -> {
            var candidate = computeNextFire(ScheduleTrigger.At(trigger.time, trigger.tz), fromMs)
            // Skip weekends per calendar.
            while (!isWeekday(candidate)) candidate += 86_400_000L
            candidate
        }
    }
}

private fun isWeekday(epochMs: Long): Boolean {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
    return calendar.isTradingDay(date)
}
```

(Use whatever `TradingCalendar.isTradingDay(LocalDate)` API actually exists; adapt to the existing surface.)

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.ScheduleRunnerTest' --no-daemon
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/ScheduleRunner.kt src/test/kotlin/com/qkt/dsl/compile/ScheduleRunnerTest.kt
git commit -m "feat(engine): ScheduleRunner heartbeat fires schedules with skip-on-miss (#77)"
```

---

## Task 8: `AstCompiler.bindToHub` registers schedules

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/AstCompilerScheduleBindTest.kt`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/qkt/dsl/compile/AstCompilerScheduleBindTest.kt`:

```kotlin
package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.strategy.testStrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerScheduleBindTest {
    private fun compile(src: String): DslCompiledStrategy =
        AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value) as DslCompiledStrategy

    @Test
    fun `bindToHub registers SCHEDULE triggers with the runner`() {
        val s = compile(
            """
            STRATEGY t VERSION 1
            SYMBOLS
              gold = EXNESS:XAUUSD EVERY 1m
            SCHEDULE
              AT 09:00 UTC THEN LOG "morning"
              EVERY HOUR AT :00 THEN LOG "hourly"
            RULES
              WHEN gold.close > 0 THEN LOG "tick"
            """.trimIndent(),
        )
        val hub = CandleHub()
        val runner = ScheduleRunner(clock = ..., calendar = ...)  // pass test instances
        hub.register(s.declaredStreams.values.single(), retention = 5, strategyId = "test")
        s.bindToHub(hub, testStrategyContext(), scheduleRunner = runner) { /* emit */ }

        assertThat(runner.triggerCount()).isEqualTo(2)
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Expected: compile error — `bindToHub` doesn't take `scheduleRunner`.

- [ ] **Step 3: Implement the binding**

Extend `Strategy.bindToHub` signature to accept a `ScheduleRunner` (default null for backwards-compat callers; live and backtest pipelines will pass it). In `CompiledStrategy.bindToHub`:

```kotlin
override fun bindToHub(
    hub: CandleHub,
    ctx: StrategyContext,
    scheduleRunner: ScheduleRunner? = null,
    emit: (Signal) -> Unit,
) {
    // existing sync + stream wiring ...
    scheduleRunner?.let { runner ->
        for (schedule in schedules) {
            runner.register(
                strategyId = ctx.strategyId,
                schedule = schedule,
                emit = {
                    fireScheduledAction(schedule.action, ctx, hub, emit)
                },
            )
        }
    }
}

private fun fireScheduledAction(
    action: ActionAst,
    ctx: StrategyContext,
    hub: CandleHub,
    emit: (Signal) -> Unit,
) {
    val ec =
        EvalContext(
            candle = null, // schedule fires don't have a current candle
            streams = streams,
            lets = emptyMap(),
            strategyContext = ctx,
            snapshotStore = snapshotStore,
            hub = hub,
            currentAlias = null,
        )
    // Reuse the action-evaluation path that rules use. The plan-level
    // `compileAction` mapping built in `AstCompiler.compile` produces a
    // CompiledAction we can fire here.
    val signals = compiledActionForSchedule[action]?.fire(ec, ctx) ?: emptyList()
    for (sig in signals) emit(sig)
}
```

This task pulls in `EvalContext` and possibly action compilation to support null-candle path — see Task 9.

- [ ] **Step 4: Run tests**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt src/test/kotlin/com/qkt/dsl/compile/AstCompilerScheduleBindTest.kt
git commit -m "feat(engine): AstCompiler binds SCHEDULE clauses to ScheduleRunner (#77)"
```

---

## Task 9: `EvalContext` nullable-candle path + action gating

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/EvalContext.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/EvalContextScheduleTest.kt`

Schedule fires don't have a "current bar." `EvalContext.candle` must accept null, and expressions that need a current bar must error clearly when used in a SCHEDULE body.

- [ ] **Step 1: Make `EvalContext.candle` nullable**

Existing call sites that rely on a non-null candle (rule firing for a stream close) will keep passing one. Schedule fires pass null.

- [ ] **Step 2: In expression compilation, audit the cases that read `ec.candle` and either:**
  - Fall through to a hub ring lookup (most cross-stream reads already do this).
  - Throw a clear `IllegalStateException("expression requires a current bar; cannot be used inside SCHEDULE")` when fundamentally bar-anchored.

- [ ] **Step 3: Add tests for both paths**

  - Schedule action that uses `gold.close` (cross-stream-style read) — should resolve from the ring.
  - Schedule action that uses an indicator bound to the current alias — should either throw a clear error at fire time, or be detected at compile time.

- [ ] **Step 4: Run the full compile suite**

```bash
./gradlew test --tests 'com.qkt.dsl.compile.*' --no-daemon
```

Expected: all green; schedule-specific tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/dsl/compile/EvalContext.kt src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt src/test/kotlin/com/qkt/dsl/compile/EvalContextScheduleTest.kt
git commit -m "feat(engine): EvalContext.candle nullable + SCHEDULE action evaluation (#77)"
```

---

## Task 10: `TradingPipeline` + `LiveSession` heartbeat + end-to-end backtest test

**Files:**
- Modify: `src/main/kotlin/com/qkt/app/TradingPipeline.kt`
- Modify: `src/main/kotlin/com/qkt/app/LiveSession.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/ScheduleEndToEndTest.kt`

- [ ] **Step 1: Add `scheduleRunner` to `TradingPipeline`**

Construct a single `ScheduleRunner` in `TradingPipeline.start`. Pass it to every strategy's `bindToHub`. Call `scheduleRunner.tick(tick.timestamp)` from `ingest(tick)` after the engine + hub feed.

- [ ] **Step 2: Add 1Hz timer in `LiveSession`**

Only in live mode (not backtest), schedule a recurring timer task that calls `pipeline.scheduleHeartbeat(System.currentTimeMillis())` every 1000ms. Quiet markets get covered. Backtest doesn't need this because `feed(tick)` already drives the heartbeat per tick.

- [ ] **Step 3: Write the end-to-end backtest test**

Create `src/test/kotlin/com/qkt/dsl/compile/ScheduleEndToEndTest.kt`:

```kotlin
@Test
fun `SCHEDULE AT 09 colon 00 UTC fires the action exactly once per day`() {
    // Generate ticks spanning 2 simulated days, 1 tick per hour.
    // Compile a strategy with `SCHEDULE AT 09:00 UTC THEN BUY gold SIZING 0.1`.
    // Run through Backtest.
    // Assert: exactly 2 BUY signals (once per day at 09:00).
}

@Test
fun `SCHEDULE EVERY HOUR AT colon 00 fires once per hour`() {
    // 1 tick per minute for 4 hours.
    // Strategy: `EVERY HOUR AT :00 THEN LOG "hourly"`.
    // Assert: exactly 4 LOG signals.
}

@Test
fun `multi-time AT list fires once per listed time per day`() {
    // 1 tick per minute spanning a day.
    // Strategy: `AT 09:00, 12:00, 14:00 UTC THEN BUY gold SIZING 0.1`.
    // Assert: exactly 3 BUY signals.
}
```

- [ ] **Step 4: Run all tests**

```bash
./gradlew test --no-daemon
```

Expected: green. If `SseStreamTest` flakes, rerun — unrelated.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/qkt/app/TradingPipeline.kt src/main/kotlin/com/qkt/app/LiveSession.kt src/test/kotlin/com/qkt/dsl/compile/ScheduleEndToEndTest.kt
git commit -m "feat(engine): SCHEDULE heartbeat in pipeline + end-to-end backtest (#77)"
```

---

## Task 11: Documentation

**Files:**
- Create: `docs/reference/dsl/schedule.md`
- Create: `docs/phases/phase-40-schedule.md`
- Modify: `docs/reference/dsl/index.md` if it has a TOC for actions

DSL reference covers the keyword shape, every trigger variant, timezone behaviour, missed-fire semantics, and gotchas (quiet markets in live, action body cannot reference "current bar" expressions). Phase changelog covers the problem, what's new, migration, known limitations.

- [ ] **Step 1: Write `docs/reference/dsl/schedule.md`**
- [ ] **Step 2: Write `docs/phases/phase-40-schedule.md`** — modelled on `phase-35-bar-sync.md`
- [ ] **Step 3: Commit**

```bash
git add docs/reference/dsl/schedule.md docs/phases/phase-40-schedule.md
git commit -m "docs(phase40): SCHEDULE reference + phase 40 changelog (#77)"
```

---

## Task 12: Final PR

**Files:**
- None — branch ready for review.

- [ ] **Step 1: Push the branch**

```bash
git push -u origin phase40-schedule-impl
```

- [ ] **Step 2: Open the PR**

```bash
gh pr create --base dev --title "Phase 40: SCHEDULE DSL clock-driven action (#77)" --body "..."
```

Body covers: summary, every shipped task in one line, test plan checklist (Lexer, AST, Parser, ScheduleRunner unit tests, AstCompiler binding tests, EvalContext schedule tests, end-to-end backtest tests).

---

## Self-review checklist

After all tasks land:

1. **Spec coverage**: every section of the design doc maps to a task. ✓
2. **Tokens**: `SCHEDULE`, `HOUR`, `DAY`, `WEEKDAY`, `UTC` reflected. ✓
3. **AST**: `TimeOfDay`, `Timezone`, `ScheduleTrigger`, `ScheduleDecl`, `StrategyAst.schedules` default empty. ✓
4. **Parser**: single trigger, multi-time `AT` list, multiple clauses. UTC required. ✓
5. **Engine**: `ScheduleRunner` skeleton → heartbeat → `AstCompiler.bindToHub` integration → `TradingPipeline` heartbeat → `LiveSession` 1Hz timer. ✓
6. **EvalContext**: null candle path; bar-anchored expressions error inside SCHEDULE. ✓
7. **Tests**: unit (lexer/AST/parser/runner) → integration (AstCompiler bind) → e2e (backtest). ✓
8. **Docs**: reference + phase changelog. ✓
9. **Backwards compat**: `schedules` defaults `emptyList()`; existing strategies parse and run unchanged. ✓
10. **Missed-fire**: skip + WARN per the confirmed semantics. ✓
11. **Type consistency**: `ScheduleRunner.register(strategyId, schedule, emit)` signature consistent across tasks 6, 7, 8. ✓
12. **Names**: `Time of day` always `TimeOfDay`; `triggers` always plural list; `tz` consistent. ✓
