package com.qkt.positions

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.persistence.NoopStatePersistor
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerPersistenceTest {
    @Test
    fun `applyFill persists the LegBook after a primary fill`() {
        val persistor = NoopStatePersistor()
        val tracker = StrategyPositionTracker(persistor)
        tracker.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "c-1",
                brokerOrderId = null,
                symbol = "XAUUSDm",
                side = Side.BUY,
                price = BigDecimal("4700"),
                quantity = BigDecimal("0.20"),
                strategyId = "hedge",
                timestamp = 1000L,
            ),
        )
        val persisted = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(persisted).isNotNull
        assertThat(persisted!!.legs).hasSize(1)
        assertThat(persisted.legs[0].role).isEqualTo(LegRole.PRIMARY)
        assertThat(persisted.legs[0].quantity.toPlainString()).isEqualTo("0.20")
    }

    @Test
    fun `addStackLeg persists the new STACK leg`() {
        val persistor = NoopStatePersistor()
        val tracker = StrategyPositionTracker(persistor)
        // First open a primary so the book exists.
        tracker.applyFill(
            BrokerEvent.OrderFilled(
                clientOrderId = "c-1",
                brokerOrderId = null,
                symbol = "XAUUSDm",
                side = Side.BUY,
                price = BigDecimal("4700"),
                quantity = BigDecimal("0.20"),
                strategyId = "hedge",
                timestamp = 1000L,
            ),
        )
        tracker.addStackLeg(
            "hedge",
            PositionLeg(
                legId = "leg-stack-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.06"),
                entryPrice = BigDecimal("4710"),
                openedAt = 2000L,
                role = LegRole.STACK,
                parentLegId = "leg-1",
            ),
        )
        val persisted = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(persisted!!.legs).hasSize(2)
        assertThat(persisted.legs.first { it.role == LegRole.STACK }.legId).isEqualTo("leg-stack-1")
    }

    @Test
    fun `closeLeg persists the now-empty book`() {
        val persistor = NoopStatePersistor()
        val tracker = StrategyPositionTracker(persistor)
        tracker.addStackLeg(
            "hedge",
            PositionLeg(
                legId = "leg-stack-1",
                symbol = "XAUUSDm",
                side = Side.BUY,
                quantity = BigDecimal("0.06"),
                entryPrice = BigDecimal("4710"),
                openedAt = 2000L,
                role = LegRole.STACK,
                parentLegId = "leg-1",
            ),
        )
        tracker.closeLeg("hedge", "XAUUSDm", "leg-stack-1")
        val persisted = persistor.loadLegBook("hedge", "XAUUSDm")
        assertThat(persisted!!.legs).isEmpty()
    }
}
