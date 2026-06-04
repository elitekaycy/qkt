package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorPlacementTest {
    private val schedule = PlacementSchedule(hours = setOf(6, 7), minute = 55)

    private fun submit(ts: String) = LogLine(Instant.parse(ts), "INFO", "submit Stop dsl-x--1 S BUY x")

    @Test
    fun `passes when every expected window has a submit near 55`() {
        val from = Instant.parse("2026-06-04T05:00:00Z")
        val to = Instant.parse("2026-06-04T08:00:00Z")
        val logs = listOf(submit("2026-06-04T06:55:02Z"), submit("2026-06-04T07:55:01Z"))
        val r = GateEvaluator.placement(logs, schedule, from, to)
        assertThat(r.status).isEqualTo(GateStatus.PASS)
        assertThat(r.detail.filter { it.contains("MISSED") }).isEmpty()
    }

    @Test
    fun `fails when an expected window has no submit`() {
        val from = Instant.parse("2026-06-04T05:00:00Z")
        val to = Instant.parse("2026-06-04T08:00:00Z")
        val logs = listOf(submit("2026-06-04T06:55:02Z")) // 07:55 missing
        val r = GateEvaluator.placement(logs, schedule, from, to)
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("07:55") && it.contains("MISSED") }
    }
}
