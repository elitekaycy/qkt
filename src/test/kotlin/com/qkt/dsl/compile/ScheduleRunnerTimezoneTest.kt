package com.qkt.dsl.compile

import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleRunnerTimezoneTest {
    private val mondayMidnightUtc: Long =
        LocalDate
            .of(2026, 6, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private val noop = Log(level = LogLevel.INFO, messageFormat = "hi", fields = emptyMap())

    private fun decl(trigger: ScheduleTrigger): ScheduleDecl = ScheduleDecl(triggers = listOf(trigger), action = noop)

    @Test
    fun `daily NY schedule keeps its local clock time across the spring DST transition`() {
        // 2024-03-10 is the US spring-forward: NY 09:00 is 14:00 UTC on the 9th (EST)
        // but 13:00 UTC on the 10th (EDT). Rolling the next fire by a fixed 24h of
        // epoch millis would fire at 14:00 UTC again — an hour late in local terms.
        val mar9 =
            LocalDate
                .of(2024, 3, 9)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        val runner = ScheduleRunner()
        var fires = 0
        runner.register(
            strategyId = "s1",
            schedule = decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.NY)),
            emit = { fires++ },
            nowMs = mar9,
        )
        runner.tick(mar9 + 14 * hour) // 2024-03-09T14:00Z = 09:00 EST
        assertThat(fires).isEqualTo(1)

        // Next local 09:00 is 2024-03-10T13:00Z (EDT). It must fire AT that instant.
        runner.tick(mar9 + day + 12 * hour) // 12:00Z — 08:00 EDT, too early
        assertThat(fires).isEqualTo(1)
        runner.tick(mar9 + day + 13 * hour) // 13:00Z = 09:00 EDT
        assertThat(fires).isEqualTo(2)
    }

    @Test
    fun `At with NY zone fires at the correct UTC instant across DST`() {
        // 2026-01-15 09:30 NY (EST) = 14:30 UTC.
        // 2026-07-15 09:30 NY (EDT) = 13:30 UTC.
        // Same DSL clause, two different UTC instants — DST handled by ZoneId.

        val janMidnightNy =
            LocalDate
                .of(2026, 1, 15)
                .atStartOfDay(java.time.ZoneId.of("America/New_York"))
                .toInstant()
                .toEpochMilli()
        val runnerWinter = ScheduleRunner()
        var winterFires = 0
        runnerWinter.register(
            "s1",
            decl(ScheduleTrigger.At(TimeOfDay(9, 30), Timezone.NY)),
            emit = { winterFires++ },
            nowMs = janMidnightNy,
        )
        runnerWinter.tick(janMidnightNy + 14 * hour + 30 * 60_000L) // 14:30 UTC
        assertThat(winterFires).isEqualTo(1)

        val julMidnightNy =
            LocalDate
                .of(2026, 7, 15)
                .atStartOfDay(java.time.ZoneId.of("America/New_York"))
                .toInstant()
                .toEpochMilli()
        val runnerSummer = ScheduleRunner()
        var summerFires = 0
        runnerSummer.register(
            "s1",
            decl(ScheduleTrigger.At(TimeOfDay(9, 30), Timezone.NY)),
            emit = { summerFires++ },
            nowMs = julMidnightNy,
        )
        runnerSummer.tick(julMidnightNy + 13 * hour + 30 * 60_000L) // 13:30 UTC
        assertThat(summerFires).isEqualTo(1)
    }

    @Test
    fun `register fails fast when BROKER zone used without a resolver configured`() {
        val runner = ScheduleRunner() // no brokerZoneIdFor
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                runner.register(
                    "s1",
                    decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.BROKER)),
                    emit = {},
                    nowMs = mondayMidnightUtc,
                )
            }.hasMessageContaining("BROKER")
    }

    @Test
    fun `BROKER zone resolves to ZoneId returned by the configured resolver`() {
        // Broker reports server time as GMT+2 (typical Exness EET).
        val brokerZone = java.time.ZoneOffset.ofHours(2)
        val runner = ScheduleRunner(brokerZoneIdFor = { _ -> brokerZone })
        var fires = 0
        runner.register(
            "s1",
            decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.BROKER)),
            emit = { fires++ },
            nowMs = mondayMidnightUtc, // 2026-06-01 00:00 UTC (Monday)
        )

        // 09:00 broker-local = 07:00 UTC (broker is UTC+2).
        runner.tick(mondayMidnightUtc + 6 * hour)
        assertThat(fires).isEqualTo(0)
        runner.tick(mondayMidnightUtc + 7 * hour)
        assertThat(fires).isEqualTo(1)
    }

    @Test
    fun `register fails when BROKER resolver returns null for the strategy`() {
        val runner = ScheduleRunner(brokerZoneIdFor = { _ -> null })
        org.assertj.core.api.Assertions
            .assertThatThrownBy {
                runner.register(
                    "s1",
                    decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.BROKER)),
                    emit = {},
                    nowMs = mondayMidnightUtc,
                )
            }.hasMessageContaining("server_time_zone")
    }
}
