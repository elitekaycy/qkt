package com.qkt.dsl.ast

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * #77 — Phase 40 Task 2. AST shapes for the `SCHEDULE` block: TimeOfDay
 * range checks, Trigger variants, ScheduleDecl invariants, and the new
 * `StrategyAst.schedules` default.
 */
class ScheduleAstTest {
    private val noopAction: ActionAst =
        Log(level = LogLevel.INFO, messageFormat = "hello", fields = emptyMap())

    @Test
    fun `TimeOfDay rejects out-of-range hour`() {
        assertThatThrownBy { TimeOfDay(hour = 24, minute = 0) }
            .hasMessageContaining("hour")
    }

    @Test
    fun `TimeOfDay rejects out-of-range minute`() {
        assertThatThrownBy { TimeOfDay(hour = 0, minute = 60) }
            .hasMessageContaining("minute")
    }

    @Test
    fun `TimeOfDay rejects out-of-range second`() {
        assertThatThrownBy { TimeOfDay(hour = 0, minute = 0, second = 60) }
            .hasMessageContaining("second")
    }

    @Test
    fun `TimeOfDay defaults second to zero`() {
        assertThat(TimeOfDay(9, 0).second).isEqualTo(0)
    }

    @Test
    fun `ScheduleDecl requires at least one trigger`() {
        assertThatThrownBy {
            ScheduleDecl(triggers = emptyList(), action = noopAction)
        }.hasMessageContaining("at least one trigger")
    }

    @Test
    fun `ScheduleTrigger At carries time and timezone`() {
        val t = ScheduleTrigger.At(time = TimeOfDay(9, 0), tz = Timezone.UTC)
        assertThat(t.time).isEqualTo(TimeOfDay(9, 0))
        assertThat(t.tz).isEqualTo(Timezone.UTC)
    }

    @Test
    fun `ScheduleTrigger EveryHour rejects out-of-range minute offset`() {
        assertThatThrownBy { ScheduleTrigger.EveryHour(minuteOffset = 60) }
            .hasMessageContaining("minuteOffset")
        assertThatThrownBy { ScheduleTrigger.EveryHour(minuteOffset = -1) }
            .hasMessageContaining("minuteOffset")
    }

    @Test
    fun `StrategyAst schedules defaults to empty list`() {
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
