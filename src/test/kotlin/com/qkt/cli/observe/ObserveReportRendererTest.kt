package com.qkt.cli.observe

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObserveReportRendererTest {
    private val gates =
        listOf(
            GateResult("placement", GateStatus.PASS, listOf("06:55 FIRED")),
            GateResult("errors", GateStatus.PASS, listOf("no engine-side errors")),
            GateResult("pnl", GateStatus.REVIEW, listOf("cumulative realized=15")),
        )

    @Test
    fun `overall verdict is GO when gates 1-2 pass and pnl is not FAIL`() {
        assertThat(ObserveReportRenderer.verdict(gates)).isEqualTo("GO")
    }

    @Test
    fun `text render lists every gate and the verdict`() {
        val text = ObserveReportRenderer.text("hedge-straddle", "7d", gates)
        assertThat(text).contains("placement", "errors", "pnl", "GO")
    }

    @Test
    fun `verdict is NO-GO when a gate FAILs`() {
        val failed = gates.map { if (it.name == "errors") it.copy(status = GateStatus.FAIL) else it }
        assertThat(ObserveReportRenderer.verdict(failed)).isEqualTo("NO-GO")
    }
}
