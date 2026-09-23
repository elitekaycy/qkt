package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketCloseFixtures.attachCaps
import com.qkt.broker.FakeBroker
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.isTerminal
import com.qkt.execution.withLegIntent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.LegRole
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Live run 004: a stack leg's fill-time SL/TP modify was refused (price already past the target), the
 * engine-held target closed the ticket — and the leg's bracket wrapper stayed live, persisted as a
 * pending order and counted as exposure until a restart retired it. The engine-held fallback exits
 * belong to the wrapper, so their fill completes it exactly as a venue-side close does.
 */
class OrderManagerAttachedFallbackCompletionTest {
    private val sid = "alpha"
    private val clock = FixedClock(time = 1_000L)
    private val persistor = NoopStatePersistor()
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val broker = FakeBroker(bus, clock, attachCaps).apply { rejectPositionModifications = true }
    private val prices = MarketPriceTracker()
    private val om =
        OrderManager(broker, bus, prices, clock, persistor, positionMode = { PositionAccountingMode.HEDGING })

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
                takeProfit = Money.of("100.1"),
                stopLoss = StopLossSpec.Fixed(Money.of("85")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1_000L,
                strategyId = sid,
                takeProfitAst = ChildBy(NumLit(BigDecimal("0.10"))),
                stopLossAst = ChildBy(NumLit(BigDecimal("15"))),
            ).withLegIntent(LegIntent.Open("L", LegRole.STACK, "P"))

    @Test
    fun `engine-held target closing the leg completes its wrapper and clears exposure`() {
        openLegWithRefusedModify()

        tick(bid = "100.45", ask = "100.55")
        closeByTicket("L-tp", price = "100.45")

        assertCompleted(survivor = "L-sl")
    }

    @Test
    fun `engine-held stop closing the leg completes its wrapper and clears exposure`() {
        openLegWithRefusedModify()

        tick(bid = "85.2", ask = "85.4")
        closeByTicket("L-sl", price = "85.2")

        assertCompleted(survivor = "L-tp")
    }

    private fun openLegWithRefusedModify() {
        tick(bid = "99.9", ask = "100.1")
        om.submit(leg)
        clock.time = 5_000L
        broker.emitFill(entry, price = Money.of("100.3"))
        assertThat(om.getOrder("L-tp")?.state).isEqualTo(OrderState.PENDING)
        assertThat(om.getOrder("L-sl")?.state).isEqualTo(OrderState.PENDING)
    }

    private fun tick(
        bid: String,
        ask: String,
    ) {
        clock.time += 500L
        val mid = (BigDecimal(bid) + BigDecimal(ask)).divide(BigDecimal(2))
        val tick = Tick("X", mid, clock.time, bid = Money.of(bid), ask = Money.of(ask))
        prices.update(tick)
        bus.publish(TickEvent(tick))
    }

    private fun closeByTicket(
        exitId: String,
        price: String,
    ) {
        assertThat(broker.submits.filterIsInstance<OrderRequest.Market>().map { it.id }).contains(exitId)
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = exitId,
                brokerOrderId = "L-entry",
                symbol = "X",
                side = Side.SELL,
                price = Money.of(price),
                quantity = Money.of("0.05"),
                strategyId = sid,
                timestamp = clock.now(),
            ),
        )
        tick(bid = price, ask = price)
    }

    private fun assertCompleted(survivor: String) {
        assertThat(om.getOrder("L")?.state?.isTerminal ?: true).isTrue()
        assertThat(om.getOrder(survivor)?.state?.isTerminal ?: true).isTrue()
        assertThat(om.quantityFor("X", Side.BUY, sid)).isEqualByComparingTo("0")
        assertThat(om.quantityFor("X", Side.SELL, sid)).isEqualByComparingTo("0")
        assertThat(persistor.loadPendingOrders(sid).keys).isEmpty()
    }
}
