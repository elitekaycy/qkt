package com.qkt.notify

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotificationEventTest {
    @Test
    fun `OrderRejected carries strategy symbol side qty reason and is CRITICAL`() {
        val e =
            NotificationEvent.OrderRejected(
                strategyId = "hedge-straddle",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.24"),
                reason = "10015 invalid price",
                timestamp = 1L,
            )
        assertThat(e.strategyId).isEqualTo("hedge-straddle")
        assertThat(e.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(e.side).isEqualTo(Side.BUY)
        assertThat(e.quantity).isEqualByComparingTo(BigDecimal("0.24"))
        assertThat(e.reason).isEqualTo("10015 invalid price")
        assertThat(e.timestamp).isEqualTo(1L)
        assertThat(e.severity).isEqualTo(NotificationEvent.Severity.CRITICAL)
    }

    @Test
    fun `Halted global has null strategyId and is CRITICAL`() {
        val e = NotificationEvent.Halted(strategyId = null, reason = "MaxDrawdown", timestamp = 2L)
        assertThat(e.strategyId).isNull()
        assertThat(e.severity).isEqualTo(NotificationEvent.Severity.CRITICAL)
    }

    @Test
    fun `Halted per-strategy carries strategyId`() {
        val e = NotificationEvent.Halted(strategyId = "hedge-straddle", reason = "daily loss", timestamp = 2L)
        assertThat(e.strategyId).isEqualTo("hedge-straddle")
    }

    @Test
    fun `Resumed is INFO`() {
        val e = NotificationEvent.Resumed(strategyId = "x", timestamp = 3L)
        assertThat(e.severity).isEqualTo(NotificationEvent.Severity.INFO)
    }

    @Test
    fun `PositionReconciled is WARN and carries old new qty`() {
        val e =
            NotificationEvent.PositionReconciled(
                strategyId = "x",
                symbol = "EXNESS:XAUUSD",
                oldQty = BigDecimal("0.24"),
                newQty = BigDecimal.ZERO,
                reason = "external close",
                timestamp = 4L,
            )
        assertThat(e.severity).isEqualTo(NotificationEvent.Severity.WARN)
        assertThat(e.oldQty).isEqualByComparingTo(BigDecimal("0.24"))
        assertThat(e.newQty).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `StrategyStarted Stopped Error DaemonStarted construct with correct severities`() {
        assertThat(NotificationEvent.StrategyStarted("x", 5L).severity).isEqualTo(NotificationEvent.Severity.INFO)
        assertThat(NotificationEvent.StrategyStopped("x", flatten = true, timestamp = 6L).severity)
            .isEqualTo(NotificationEvent.Severity.INFO)
        assertThat(NotificationEvent.StrategyError("x", "boom", 7L).severity)
            .isEqualTo(NotificationEvent.Severity.CRITICAL)
        assertThat(NotificationEvent.DaemonStarted("0.27.0", listOf("x"), 8L).severity)
            .isEqualTo(NotificationEvent.Severity.INFO)
    }

    @Test
    fun `DailySummary carries asOfUtc and per-strategy rows`() {
        val s =
            StrategySummary(
                strategyId = "x",
                equity = BigDecimal("10000"),
                equityDeltaPct = BigDecimal("-0.5"),
                realizedToday = BigDecimal("23.40"),
                unrealized = BigDecimal.ZERO,
                tradesToday = 14,
                haltsToday = 0,
                positionsSummary = "flat",
            )
        val e = NotificationEvent.DailySummary(asOfUtc = "2026-05-17", strategies = listOf(s), timestamp = 9L)
        assertThat(e.asOfUtc).isEqualTo("2026-05-17")
        assertThat(e.strategies).hasSize(1)
        assertThat(e.severity).isEqualTo(NotificationEvent.Severity.INFO)
    }
}
