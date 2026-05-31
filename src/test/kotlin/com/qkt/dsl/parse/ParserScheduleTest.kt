package com.qkt.dsl.parse

import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #77 — Phase 40 Tasks 3-5. Parser support for the `SCHEDULE` block: single
 * `AT` trigger, multi-time `AT` list, `EVERY HOUR/DAY/WEEKDAY` variants, UTC
 * required enforcement, and `StrategyAst.schedules` integration via the full
 * `Dsl.parse` flow.
 */
class ParserScheduleTest {
    private fun parseStrategy(src: String) = Dsl.parse(src) as ParseResult.Success

    @Test
    fun `single SCHEDULE AT 09 colon 00 UTC parses to one At trigger`() {
        val ast =
            parseStrategy(
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
        val at = ast.schedules[0].triggers[0] as ScheduleTrigger.At
        assertThat(at.time).isEqualTo(TimeOfDay(9, 0))
        assertThat(at.tz).isEqualTo(Timezone.UTC)
    }

    @Test
    fun `multi-time AT list parses to multiple At triggers in one clause`() {
        val ast =
            parseStrategy(
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
        assertThat(ast.schedules[0].triggers.map { (it as ScheduleTrigger.At).time })
            .containsExactly(TimeOfDay(9, 0), TimeOfDay(12, 0), TimeOfDay(14, 0))
    }

    @Test
    fun `HH colon MM colon SS parses with explicit second`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 19:55:30 UTC THEN LOG "55-30"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val at = ast.schedules[0].triggers[0] as ScheduleTrigger.At
        assertThat(at.time).isEqualTo(TimeOfDay(19, 55, 30))
    }

    @Test
    fun `EVERY HOUR AT colon 30 parses to EveryHour 30`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  EVERY HOUR AT :30 THEN LOG "half-past"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val t = ast.schedules[0].triggers[0] as ScheduleTrigger.EveryHour
        assertThat(t.minuteOffset).isEqualTo(30)
    }

    @Test
    fun `EVERY DAY AT 09 colon 00 UTC parses to EveryDay`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  EVERY DAY AT 09:00 UTC THEN LOG "daily"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val t = ast.schedules[0].triggers[0] as ScheduleTrigger.EveryDay
        assertThat(t.time).isEqualTo(TimeOfDay(9, 0))
    }

    @Test
    fun `EVERY WEEKDAY AT 14 colon 30 UTC parses to EveryWeekday`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  EVERY WEEKDAY AT 14:30 UTC THEN LOG "ny-open"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val t = ast.schedules[0].triggers[0] as ScheduleTrigger.EveryWeekday
        assertThat(t.time).isEqualTo(TimeOfDay(14, 30))
    }

    @Test
    fun `multiple SCHEDULE clauses with different trigger types parse independently`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:00 UTC THEN LOG "open"
                  EVERY HOUR AT :00 THEN LOG "hourly"
                  EVERY WEEKDAY AT 14:30 UTC THEN LOG "ny-open"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        assertThat(ast.schedules).hasSize(3)
    }

    @Test
    fun `strategy without SCHEDULE has empty schedules list`() {
        val ast =
            parseStrategy(
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

    @Test
    fun `missing UTC tag on AT trigger is rejected`() {
        val result =
            Dsl.parse(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:00 THEN LOG "morning"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(result.errors.first().message).containsIgnoringCase("UTC")
    }

    @Test
    fun `missing UTC tag on EVERY DAY trigger is rejected`() {
        val result =
            Dsl.parse(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  EVERY DAY AT 09:00 THEN LOG "morning"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ) as ParseResult.Failure
        assertThat(result.errors.first().message).containsIgnoringCase("timezone")
    }

    @Test
    fun `named IANA timezones parse to their Timezone variants`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:30 NY      THEN LOG "ny-open"
                  AT 08:00 LONDON  THEN LOG "ldn-open"
                  AT 09:00 TOKYO   THEN LOG "asia-open"
                  AT 10:00 SYDNEY  THEN LOG "syd-open"
                  AT 08:30 CHICAGO THEN LOG "cme-open"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val zones = ast.schedules.map { (it.triggers[0] as ScheduleTrigger.At).tz }
        assertThat(zones).containsExactly(
            Timezone.NY,
            Timezone.LONDON,
            Timezone.TOKYO,
            Timezone.SYDNEY,
            Timezone.CHICAGO,
        )
    }

    @Test
    fun `BROKER timezone parses but errors at zoneId resolution`() {
        val ast =
            parseStrategy(
                """
                STRATEGY t VERSION 1
                SYMBOLS
                  gold = EXNESS:XAUUSD EVERY 1m
                SCHEDULE
                  AT 09:00 BROKER THEN LOG "broker-open"
                RULES
                  WHEN gold.close > 0 THEN LOG "tick"
                """.trimIndent(),
            ).value
        val tz = (ast.schedules[0].triggers[0] as ScheduleTrigger.At).tz
        assertThat(tz).isEqualTo(Timezone.BROKER)
        org.assertj.core.api.Assertions
            .assertThatThrownBy { tz.zoneId }
            .hasMessageContaining("BROKER")
    }
}
