package com.qkt.notify

import com.qkt.common.Side
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MessageTemplateTest {
    // Fixed instant: 2026-05-17T19:55:03Z
    private val ts: Long =
        Instant.parse("2026-05-17T19:55:03Z").toEpochMilli()

    @Test
    fun `OrderRejected renders symbol side qty reason and timestamp`() {
        val e =
            NotificationEvent.OrderRejected(
                strategyId = "hedge-straddle",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.24"),
                reason = "10015 invalid price (price too far from market)",
                timestamp = ts,
            )
        val out = MessageTemplate.format(e)
        assertThat(out).contains("[CRITICAL] qkt order rejected")
        assertThat(out).contains("strategy: hedge-straddle")
        assertThat(out).contains("EXNESS:XAUUSD BUY 0.24 lots")
        assertThat(out).contains("reason: 10015 invalid price (price too far from market)")
        assertThat(out).contains("19:55:03 UTC")
    }

    @Test
    fun `Halted global renders global tag`() {
        val out = MessageTemplate.format(NotificationEvent.Halted(strategyId = null, reason = "MaxDrawdown 12.3% > 10.0%", timestamp = ts))
        assertThat(out).contains("[CRITICAL] qkt HALTED (global)")
        assertThat(out).contains("reason: MaxDrawdown 12.3% > 10.0%")
    }

    @Test
    fun `Halted per-strategy renders strategyId`() {
        val out = MessageTemplate.format(NotificationEvent.Halted(strategyId = "hedge-straddle", reason = "daily loss", timestamp = ts))
        assertThat(out).contains("[CRITICAL] qkt HALTED hedge-straddle")
    }

    @Test
    fun `Resumed per-strategy renders INFO and strategyId`() {
        val out = MessageTemplate.format(NotificationEvent.Resumed(strategyId = "hedge-straddle", timestamp = ts))
        assertThat(out).contains("[INFO] qkt resumed hedge-straddle")
    }

    @Test
    fun `PositionReconciled renders qty transition`() {
        val out =
            MessageTemplate.format(
                NotificationEvent.PositionReconciled(
                    strategyId = "hedge-straddle",
                    symbol = "EXNESS:XAUUSD",
                    oldQty = BigDecimal("0.24"),
                    newQty = BigDecimal("0.00"),
                    reason = "external close",
                    timestamp = ts,
                ),
            )
        assertThat(out).contains("[WARN] qkt position drift hedge-straddle")
        assertThat(out).contains("EXNESS:XAUUSD qty: 0.24 -> 0.00")
        assertThat(out).contains("reason: external close")
    }

    @Test
    fun `StrategyStarted Stopped Error render`() {
        assertThat(MessageTemplate.format(NotificationEvent.StrategyStarted("x", ts)))
            .contains("[INFO] qkt started x")
        assertThat(MessageTemplate.format(NotificationEvent.StrategyStopped("x", flatten = true, ts)))
            .contains("[INFO] qkt stopped x (flatten=true)")
        assertThat(MessageTemplate.format(NotificationEvent.StrategyError("x", "boom", ts)))
            .contains("[CRITICAL] qkt strategy error x")
    }

    @Test
    fun `DaemonStarted renders version and strategy list`() {
        val out = MessageTemplate.format(NotificationEvent.DaemonStarted("0.27.0", listOf("hedge-straddle", "test"), ts))
        assertThat(out).contains("[INFO] qkt 0.27.0 started")
        assertThat(out).contains("strategies: hedge-straddle, test")
    }

    @Test
    fun `DailySummary renders per-strategy block`() {
        val s =
            StrategySummary(
                strategyId = "hedge-straddle",
                equity = BigDecimal("10154.38"),
                equityDeltaPct = BigDecimal("-0.5"),
                realizedToday = BigDecimal("23.40"),
                unrealized = BigDecimal.ZERO,
                tradesToday = 14,
                haltsToday = 0,
                positionsSummary = "flat",
            )
        val out = MessageTemplate.format(NotificationEvent.DailySummary("2026-05-17", listOf(s), ts))
        assertThat(out).contains("[INFO] qkt daily summary 2026-05-17")
        assertThat(out).contains("hedge-straddle:")
        assertThat(out).contains("equity: \$10154.38 (-0.5% from yesterday)")
        assertThat(out).contains("realized today: +\$23.40")
        assertThat(out).contains("trades: 14")
        assertThat(out).contains("positions: flat")
    }
}
