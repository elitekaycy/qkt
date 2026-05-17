package com.qkt.notify

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventTranslatorTest {
    @Test
    fun `OrderRejected maps every field`() {
        val src =
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = "b1",
                reason = "10015 invalid price",
                strategyId = "hedge-straddle",
                timestamp = 1L,
            )
        val out =
            EventTranslator.fromBrokerRejected(
                event = src,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.24"),
            )
        assertThat(out.strategyId).isEqualTo("hedge-straddle")
        assertThat(out.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(out.side).isEqualTo(Side.BUY)
        assertThat(out.quantity).isEqualByComparingTo(BigDecimal("0.24"))
        assertThat(out.reason).isEqualTo("10015 invalid price")
        assertThat(out.timestamp).isEqualTo(1L)
    }

    @Test
    fun `RiskEvent Halted maps strategyId and reason`() {
        val out =
            EventTranslator.fromRiskHalted(
                RiskEvent.Halted(reason = "MaxDailyLoss", strategyId = "x", timestamp = 2L),
            )
        assertThat(out.strategyId).isEqualTo("x")
        assertThat(out.reason).isEqualTo("MaxDailyLoss")
    }

    @Test
    fun `RiskEvent Halted with null strategyId becomes global Halted`() {
        val out =
            EventTranslator.fromRiskHalted(
                RiskEvent.Halted(reason = "MaxDrawdown", strategyId = null, timestamp = 2L),
            )
        assertThat(out.strategyId).isNull()
        assertThat(out.reason).isEqualTo("MaxDrawdown")
    }

    @Test
    fun `RiskEvent Resumed maps strategyId`() {
        val out = EventTranslator.fromRiskResumed(RiskEvent.Resumed(strategyId = "x", timestamp = 3L))
        assertThat(out.strategyId).isEqualTo("x")
    }

    @Test
    fun `PositionReconciled maps with stripped EXNESS prefix preserved`() {
        val src =
            BrokerEvent.PositionReconciled(
                symbol = "EXNESS:XAUUSD",
                oldQty = BigDecimal("0.24"),
                newQty = BigDecimal.ZERO,
                oldAvgPx = BigDecimal("4500"),
                newAvgPx = BigDecimal("4510"),
                source = "venue-poller",
                reason = "external close detected",
                timestamp = 4L,
            )
        val out = EventTranslator.fromPositionReconciled(event = src, strategyId = "hedge-straddle")
        assertThat(out.strategyId).isEqualTo("hedge-straddle")
        assertThat(out.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(out.oldQty).isEqualByComparingTo(BigDecimal("0.24"))
        assertThat(out.newQty).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(out.reason).isEqualTo("external close detected")
    }
}
