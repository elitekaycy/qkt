package com.qkt.dsl.parse

import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.Timezone
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `SCHEDULE` timezone tags: every time of day must name its zone (#77). */
class ParserScheduleTimezoneTest {
    private fun parseStrategy(src: String) = Dsl.parse(src) as ParseResult.Success

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
