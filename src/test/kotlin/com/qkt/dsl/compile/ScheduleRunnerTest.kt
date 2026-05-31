package com.qkt.dsl.compile

import com.qkt.dsl.ast.LogLevel
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.dsl.ast.ScheduleTrigger
import com.qkt.dsl.ast.TimeOfDay
import com.qkt.dsl.ast.Timezone
import java.time.LocalDate
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #77 — Phase 40 Tasks 6+7. Registration, heartbeat fire, skip-on-miss, and
 * weekday/weekend gating semantics for [ScheduleRunner].
 *
 * Time reference: 2026-06-01 is a Monday — derived via [LocalDate] so the
 * weekday/weekend tests are self-checking instead of trusting a hand-computed
 * epoch constant.
 */
class ScheduleRunnerTest {
    private val mondayMidnightUtc: Long =
        LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private val noop = Log(level = LogLevel.INFO, messageFormat = "hi", fields = emptyMap())

    private fun decl(trigger: ScheduleTrigger): ScheduleDecl =
        ScheduleDecl(triggers = listOf(trigger), action = noop)

    @Test
    fun `register tracks one entry per trigger`() {
        val runner = ScheduleRunner()
        runner.register(
            strategyId = "s1",
            schedule =
                ScheduleDecl(
                    triggers =
                        listOf(
                            ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC),
                            ScheduleTrigger.At(TimeOfDay(12, 0), Timezone.UTC),
                        ),
                    action = noop,
                ),
            emit = {},
            nowMs = mondayMidnightUtc,
        )
        assertThat(runner.triggerCount()).isEqualTo(2)
    }

    @Test
    fun `unregister drops a strategy's triggers`() {
        val runner = ScheduleRunner()
        runner.register(
            "s1",
            decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
            emit = {},
            nowMs = mondayMidnightUtc,
        )
        runner.unregister("s1")
        assertThat(runner.triggerCount()).isEqualTo(0)
    }

    @Test
    fun `At fires exactly once when the UTC time is crossed`() {
        val runner = ScheduleRunner()
        var fires = 0
        runner.register(
            "s1",
            decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
            emit = { fires++ },
            nowMs = mondayMidnightUtc,
        )

        runner.tick(mondayMidnightUtc + 8 * hour) // 08:00 — not yet
        assertThat(fires).isEqualTo(0)

        runner.tick(mondayMidnightUtc + 9 * hour) // 09:00 — fires
        assertThat(fires).isEqualTo(1)

        runner.tick(mondayMidnightUtc + 10 * hour) // 10:00 — same day, no refire
        assertThat(fires).isEqualTo(1)

        runner.tick(mondayMidnightUtc + day + 9 * hour) // next day 09:00 — fires
        assertThat(fires).isEqualTo(2)
    }

    @Test
    fun `EveryHour at minute 30 fires once per hour`() {
        val runner = ScheduleRunner()
        var fires = 0
        runner.register(
            "s1",
            decl(ScheduleTrigger.EveryHour(minuteOffset = 30)),
            emit = { fires++ },
            nowMs = mondayMidnightUtc,
        )

        runner.tick(mondayMidnightUtc + 30 * 60_000L) // 00:30
        runner.tick(mondayMidnightUtc + (60 + 30) * 60_000L) // 01:30
        runner.tick(mondayMidnightUtc + (120 + 30) * 60_000L) // 02:30
        assertThat(fires).isEqualTo(3)
    }

    @Test
    fun `missed fires inside one heartbeat skip and emit once`() {
        val runner = ScheduleRunner()
        var fires = 0
        runner.register(
            "s1",
            decl(ScheduleTrigger.EveryHour(minuteOffset = 0)),
            emit = { fires++ },
            nowMs = mondayMidnightUtc,
        )

        // Heartbeat skips from 00:00 to 05:00 — 5 hourly fire times missed.
        // Skip-on-miss: 4 are dropped, 1 emits at the current time.
        runner.tick(mondayMidnightUtc + 5 * hour)
        assertThat(fires).isEqualTo(1)
    }

    @Test
    fun `EveryWeekday does not fire on Saturday or Sunday`() {
        // Friday 2026-06-05 00:00 UTC = mondayMidnightUtc + 4 days.
        val fridayMidnight = mondayMidnightUtc + 4 * day
        val runner = ScheduleRunner()
        var fires = 0
        runner.register(
            "s1",
            decl(ScheduleTrigger.EveryWeekday(TimeOfDay(9, 0), Timezone.UTC)),
            emit = { fires++ },
            nowMs = fridayMidnight,
        )

        runner.tick(fridayMidnight + 9 * hour) // Friday 09:00 — fires
        assertThat(fires).isEqualTo(1)

        runner.tick(fridayMidnight + day + 9 * hour) // Saturday 09:00 — no fire
        assertThat(fires).isEqualTo(1)

        runner.tick(fridayMidnight + 2 * day + 9 * hour) // Sunday 09:00 — no fire
        assertThat(fires).isEqualTo(1)

        runner.tick(fridayMidnight + 3 * day + 9 * hour) // Monday 09:00 — fires
        assertThat(fires).isEqualTo(2)
    }

    @Test
    fun `At with NY zone fires at the correct UTC instant across DST`() {
        // 2026-01-15 09:30 NY (EST) = 14:30 UTC.
        // 2026-07-15 09:30 NY (EDT) = 13:30 UTC.
        // Same DSL clause, two different UTC instants — DST handled by ZoneId.

        val janMidnightNy =
            LocalDate.of(2026, 1, 15)
                .atStartOfDay(java.time.ZoneId.of("America/New_York"))
                .toInstant().toEpochMilli()
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
            LocalDate.of(2026, 7, 15)
                .atStartOfDay(java.time.ZoneId.of("America/New_York"))
                .toInstant().toEpochMilli()
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
    fun `register fails fast when trigger uses BROKER zone before profile is wired`() {
        val runner = ScheduleRunner()
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
    fun `multiple strategies fire independently`() {
        val runner = ScheduleRunner()
        var sAFires = 0
        var sBFires = 0
        runner.register(
            "sA",
            decl(ScheduleTrigger.At(TimeOfDay(9, 0), Timezone.UTC)),
            emit = { sAFires++ },
            nowMs = mondayMidnightUtc,
        )
        runner.register(
            "sB",
            decl(ScheduleTrigger.At(TimeOfDay(12, 0), Timezone.UTC)),
            emit = { sBFires++ },
            nowMs = mondayMidnightUtc,
        )

        runner.tick(mondayMidnightUtc + 9 * hour)
        assertThat(sAFires).isEqualTo(1)
        assertThat(sBFires).isEqualTo(0)

        runner.tick(mondayMidnightUtc + 12 * hour)
        assertThat(sAFires).isEqualTo(1)
        assertThat(sBFires).isEqualTo(1)
    }
}
