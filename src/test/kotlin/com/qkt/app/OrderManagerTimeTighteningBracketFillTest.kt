package com.qkt.app

import com.qkt.app.OrderManagerStopRatchetFixtures.fixture
import com.qkt.app.OrderManagerStopRatchetFixtures.tick
import com.qkt.broker.OrderTypeCapability
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTimeTighteningBracketFillTest {
    private val bracket =
        OrderRequest.Bracket(
            id = "bracket",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry =
                OrderRequest.Market(
                    id = "entry",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            takeProfit = Money.of("220"),
            stopLoss = StopLossSpec.TimeTighten(Money.of("60"), Money.of("10"), 900_000L, Money.of("20")),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    @Test
    fun `an attached bracket's time tightening stop is anchored on the entry's fill price and time`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(OrderTypeCapability.BRACKET, OrderTypeCapability.MARKET, OrderTypeCapability.POSITION_MODIFY),
                closeTicket = "entry",
            )
        fixture.tick("100", 0L)
        fixture.manager.submit(bracket)
        fixture.clock.advanceTo(7L)

        fixture.broker.emitFill(fixture.broker.submits.single(), Money.of("104"))

        val held = fixture.manager.getOrder("bracket-sl")!!
        val stop = held.request as OrderRequest.TimeTighteningStop
        assertThat(held.state).isEqualTo(OrderState.PENDING)
        assertThat(stop.entryPrice).isEqualByComparingTo("104")
        assertThat(stop.quantity).isEqualByComparingTo("1")
        assertThat(stop.timestamp).isEqualTo(7L)
        assertThat(stop.side).isEqualTo(Side.SELL)
        // The venue mirrors the stop at fill minus the initial distance: 104 - 60.
        assertThat(
            fixture.broker.modifyPositions
                .first()
                .sl,
        ).isEqualByComparingTo("44")
    }

    @Test
    fun `the anchored stop tightens by its step once an interval has passed since the fill`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(OrderTypeCapability.BRACKET, OrderTypeCapability.MARKET, OrderTypeCapability.POSITION_MODIFY),
                closeTicket = "entry",
            )
        fixture.tick("100", 0L)
        fixture.manager.submit(bracket)
        fixture.broker.emitFill(fixture.broker.submits.single(), Money.of("104"))

        fixture.tick("104", 900_000L)

        // One interval elapsed: the distance shrinks from 60 to 50, so the stop rises to 54.
        assertThat(
            fixture.broker.modifyPositions
                .last()
                .sl,
        ).isEqualByComparingTo("54")
    }
}
