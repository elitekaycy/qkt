package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.common.FixedClock
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PositionReconciliationTest {
    @Test
    fun `a venue reconciliation on the bus books the venue position into the owning strategy's ledger`() {
        val clock = FixedClock(5_000L)
        val pipeline = PipelineFixtures.pipeline(clock, { bus -> PaperBroker(bus, clock, MarketPriceTracker()) })

        pipeline.bus.publish(
            BrokerEvent.PositionReconciled(
                symbol = "XAUUSD",
                oldQty = null,
                newQty = BigDecimal("0.30"),
                oldAvgPx = null,
                newAvgPx = BigDecimal("2010"),
                source = "venue",
                reason = "recovered",
                ticket = "9001",
                strategyId = "alpha",
            ),
        )

        val leg = pipeline.strategyPositions.allLegsFor("alpha").single()
        assertThat(leg.symbol).isEqualTo("XAUUSD")
        assertThat(leg.quantity).isEqualByComparingTo("0.30")
        assertThat(leg.entryPrice).isEqualByComparingTo("2010")
        assertThat(leg.brokerTicket).isEqualTo("9001")
    }
}
