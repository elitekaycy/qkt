package com.qkt.observe.insights

import com.qkt.broker.AdjustmentKind
import com.qkt.broker.AdjustmentSource
import com.qkt.broker.Broker
import com.qkt.broker.BrokerAdjustment
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BrokerStatePollerAdjustmentTest : BrokerStatePollerFixture() {
    private val now = 1_700_000_000_000L

    private class AdjustingBroker(
        private val fake: FakeBroker,
        private val booked: List<BrokerAdjustment>,
    ) : Broker by fake,
        AdjustmentSource {
        override fun adjustments(
            from: Long,
            to: Long,
        ): List<BrokerAdjustment> = booked.filter { it.ts in from..to }
    }

    @Test
    fun `a venue that books adjustments has them sent as deals with their owner resolved`() {
        val dividend =
            BrokerAdjustment(
                "FAKE",
                "D1",
                AdjustmentKind.DIVIDEND,
                BigDecimal("0.27"),
                "FAKE:AAPL",
                null,
                null,
                "c",
                now - 1_000L,
            )
        val charge =
            BrokerAdjustment(
                "FAKE",
                "C1",
                AdjustmentKind.CHARGE,
                BigDecimal("-1.5"),
                null,
                "900",
                null,
                null,
                now - 1_000L,
            )
        val poller =
            BrokerStatePoller(
                brokers = listOf(AdjustingBroker(FakeBroker(), listOf(dividend, charge))),
                sink = sink,
                attribution = TicketAttribution(),
                deployedIds = { emptyList() },
                backfillDays = 1L,
                clock = { now },
            )

        poller.pollOnce()

        val bodies = collectBodies("deal-FAKE-D1", "deal-FAKE-C1")
        assertThat(
            bodies,
        ).contains("deal-FAKE-D1", "deal-FAKE-C1", "\"adjustment\":\"DIVIDEND\"", "\"adjustment\":\"CHARGE\"")
    }
}
