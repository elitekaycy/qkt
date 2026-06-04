package com.qkt.cli.observe

import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LogScanTest {
    private val raw =
        """
        2026-06-04T07:55:01.200 [INFO] submit Stop dsl-hedge_straddle--1 EXNESS:XAUUSD BUY stopPrice=2350 lastPrice=2349
        2026-06-04T07:55:01.260 [INFO] submit Stop dsl-hedge_straddle--2 EXNESS:XAUUSD SELL stopPrice=2340 lastPrice=2349
        2026-06-04T08:01:10.000 [INFO] trade SELL EXNESS:XAUUSD qty=0.20 px=2351 realized=12.50
        2026-06-04T08:02:00.000 [ERROR] broker rejected order code=10018
        not-a-log-line continuation
        """.trimIndent()

    @Test
    fun `parses level, timestamp, and message and ignores non-log lines`() {
        val lines = LogScan.parse(raw)
        assertThat(lines).hasSize(4)
        assertThat(lines[0].level).isEqualTo("INFO")
        assertThat(lines[0].timestamp).isEqualTo(Instant.parse("2026-06-04T07:55:01.200Z"))
        assertThat(lines[0].message).startsWith("submit Stop")
    }

    @Test
    fun `classifies submit, error, and trade-with-realized lines`() {
        val lines = LogScan.parse(raw)
        assertThat(lines.count { it.isSubmit }).isEqualTo(2)
        assertThat(lines.count { it.isError }).isEqualTo(1)
        val trade = lines.single { it.realized != null }
        assertThat(trade.realized).isEqualByComparingTo("12.50")
    }
}
