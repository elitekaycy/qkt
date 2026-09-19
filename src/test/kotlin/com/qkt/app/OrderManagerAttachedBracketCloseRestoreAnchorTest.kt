package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.NoopStatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerAttachedBracketCloseRestoreAnchorTest {
    @Test
    fun `restoring a persisted attached armed-trail bracket before any quote anchors its stop on the replayed fill`() {
        // Live 2026-09-14 (pr-live-007 restart): a Market-entry bracket with an engine-managed
        // stop was persisted while its position was open. On restart the strategy failed to
        // deploy with "Cannot estimate entry price ... no last price" and retried forever,
        // because the quote only starts once the strategy is deployed.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val persistor = NoopStatePersistor()
        val request =
            OrderRequest.Bracket(
                id = "b1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry =
                    OrderRequest.Market(
                        id = "e1",
                        symbol = "X",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                        strategyId = "alpha",
                    ),
                takeProfit = Money.of("120"),
                stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        persistor.savePendingOrders("alpha", mapOf("b1" to request))
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock, persistor = persistor)

        om.restore(listOf("alpha"))
        assertThat(om.activeOrders().map { it.id }).contains("e1")

        // Venue recovery replays the open position as the entry's fill; the stop anchors on it.
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "e1",
                brokerOrderId = "tkt-1",
                symbol = "X",
                side = Side.BUY,
                price = Money.of("100"),
                quantity = Money.of("1"),
                strategyId = "alpha",
                timestamp = clock.now(),
            ),
        )
        assertThat(om.activeOrders().map { it.id }).contains("b1-sl")
    }
}
