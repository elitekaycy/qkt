package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorErrorsTest {
    private fun line(
        level: String,
        msg: String,
    ) = LogLine(Instant.parse("2026-06-04T08:00:00Z"), level, msg)

    @Test
    fun `passes when there are no error lines`() {
        val r =
            GateEvaluator.errors(
                listOf(line("INFO", "submit Stop x"), line("WARN", "MT5 exness seeding orphan ticket=1")),
            )
        assertThat(r.status).isEqualTo(GateStatus.PASS)
    }

    @Test
    fun `fails on an ERROR line and samples it`() {
        val r = GateEvaluator.errors(listOf(line("ERROR", "broker rejected order code=10018")))
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("broker rejected") }
    }
}
