package com.qkt.cli.observe

import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GateEvaluatorPnlTest {
    private fun trade(
        ts: String,
        realized: String,
    ) = LogLine(Instant.parse(ts), "INFO", "trade SELL EXNESS:XAUUSD qty=0.2 px=2351 realized=$realized")

    @Test
    fun `reviews and reconciles when fills sum to status realized`() {
        val logs = listOf(trade("2026-06-04T08:00:00Z", "10.0"), trade("2026-06-04T08:30:00Z", "5.0"))
        val r = GateEvaluator.pnl(logs, statusRealized = BigDecimal("15.0"), statusUnrealized = BigDecimal("-1"))
        assertThat(r.status).isEqualTo(GateStatus.REVIEW)
        assertThat(r.detail).anyMatch { it.contains("consistent") }
        assertThat(r.detail).anyMatch { it.contains("2026-06-04") && it.contains("15") }
    }

    @Test
    fun `fails when fills do not reconcile with status realized`() {
        val logs = listOf(trade("2026-06-04T08:00:00Z", "10.0"))
        val r = GateEvaluator.pnl(logs, statusRealized = BigDecimal("999"), statusUnrealized = BigDecimal.ZERO)
        assertThat(r.status).isEqualTo(GateStatus.FAIL)
        assertThat(r.detail).anyMatch { it.contains("MISMATCH") }
    }
}
