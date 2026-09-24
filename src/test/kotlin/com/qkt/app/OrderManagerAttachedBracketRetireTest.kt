package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.attachCaps
import com.qkt.broker.FakeBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.withLegIntent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.LegRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerAttachedBracketRetireTest {
    private val sid = "alpha"
    private val clock = FixedClock(time = 1_000L)
    private val persistor = NoopStatePersistor()
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, attachCaps)
    private val om =
        OrderManager(
            broker,
            bus,
            MarketPriceTracker(),
            clock,
            persistor,
            positionMode = { PositionAccountingMode.HEDGING },
        )

    private val entry =
        OrderRequest.Market(
            id = "L-entry",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("0.05"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
            strategyId = sid,
        )

    private val leg =
        OrderRequest
            .Bracket(
                id = "L",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("0.05"),
                entry = entry,
                takeProfit = Money.of("101"),
                stopLoss = StopLossSpec.Fixed(Money.of("85")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
                strategyId = sid,
            ).withLegIntent(LegIntent.Open("L", LegRole.STACK, "P"))

    private fun tickAt(ts: Long) {
        clock.time = ts
        bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
    }

    private fun pendingIds() = persistor.loadPendingOrders(sid).keys

    @Test
    fun `a stack leg rejected at the venue leaves no persisted pending entry`() {
        broker.rejectOrderIds += "L-entry"
        om.submit(leg)
        tickAt(2_000L)
        tickAt(3_000L)

        assertThat(pendingIds()).isEmpty()
    }

    @Test
    fun `a stack leg filled then closed on its take-profit leaves no persisted pending entry`() {
        om.submit(leg)
        clock.time = 5_000L
        broker.emitFill(entry, price = Money.of("100.3"))
        tickAt(5_500L)
        clock.time = 9_000L
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "L-entry",
                brokerOrderId = "tkt-1",
                symbol = "X",
                side = Side.SELL,
                price = Money.of("101"),
                quantity = Money.of("0.05"),
                timestamp = clock.now(),
                updatesOrderExecution = false,
            ),
        )
        tickAt(10_000L)
        tickAt(11_000L)

        assertThat(pendingIds()).isEmpty()
    }
}
