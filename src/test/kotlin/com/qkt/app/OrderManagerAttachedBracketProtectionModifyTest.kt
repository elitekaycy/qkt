package com.qkt.app

import com.qkt.app.OrderManagerAttachedBracketFixtures.armedTrailBracket
import com.qkt.app.OrderManagerAttachedBracketFixtures.attachCaps
import com.qkt.app.OrderManagerAttachedBracketFixtures.newBus
import com.qkt.broker.FakeBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerAttachedBracketProtectionModifyTest {
    @Test
    fun `cancelling a partially filled residual leaves venue-attached protection intact`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())
        val attached = broker.submits.single() as OrderRequest.Bracket
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = attached.id,
                brokerOrderId = "position-1",
                symbol = attached.symbol,
                side = attached.side,
                price = Money.of("100"),
                quantity = Money.of("0.4"),
                cumulativeFilled = Money.of("0.4"),
            ),
        )

        om.cancel(attached.id)

        assertThat(broker.cancels).containsExactly(attached.id)
        assertThat(om.getOrder(attached.id)?.cumulativeFilledQuantity).isEqualByComparingTo("0.4")
        assertThat((attached.stopLoss as StopLossSpec.ArmedTrail).trailDistance).isEqualByComparingTo("5")
        assertThat(attached.takeProfit).isEqualByComparingTo("120")
        assertThat(broker.modifyPositions).isEmpty()
    }

    @Test
    fun `relative attached bracket modifies protection from the exact fill`() {
        data class Case(
            val side: Side,
            val fill: String,
            val expectedSl: String,
            val expectedTp: String,
        )

        listOf(
            Case(Side.BUY, fill = "105", expectedSl = "95", expectedTp = "125"),
            Case(Side.SELL, fill = "95", expectedSl = "105", expectedTp = "75"),
        ).forEach { case ->
            val clock = FixedClock(0L)
            val bus = newBus(clock)
            val broker = FakeBroker(bus, clock, attachCaps)
            val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
            val entryId = "${case.side.name.lowercase()}-entry"
            val entry =
                OrderRequest.Market(
                    id = entryId,
                    symbol = "X",
                    side = case.side,
                    quantity = Money.of("1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                )
            val bracket =
                OrderRequest.Bracket(
                    id = "${case.side.name.lowercase()}-bracket",
                    symbol = "X",
                    side = case.side,
                    quantity = Money.of("1"),
                    entry = entry,
                    takeProfit = Money.of(if (case.side == Side.BUY) "120" else "80"),
                    stopLoss = StopLossSpec.Fixed(Money.of(if (case.side == Side.BUY) "90" else "110")),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                    takeProfitAst = ChildBy(NumLit(Money.of("20"))),
                    stopLossAst = ChildBy(NumLit(Money.of("10"))),
                )

            om.submit(bracket)
            val shipped = broker.submits.single() as OrderRequest.Bracket
            assertThat(shipped.takeProfit).isEqualByComparingTo(bracket.takeProfit)
            assertThat((shipped.stopLoss as StopLossSpec.Fixed).price)
                .isEqualByComparingTo((bracket.stopLoss as StopLossSpec.Fixed).price)

            broker.emitFill(shipped, price = Money.of(case.fill))

            val modification = broker.modifyPositions.single()
            assertThat(modification.ticket).isEqualTo(entryId)
            assertThat(modification.sl).isEqualByComparingTo(case.expectedSl)
            assertThat(modification.tp).isEqualByComparingTo(case.expectedTp)
        }
    }

    @Test
    fun `rejected fill anchored fixed stop modify arms an engine held ticket close`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps).apply { rejectPositionModifications = true }
        val alerts = mutableListOf<String>()
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts += message },
            )
        val entry =
            OrderRequest.Market(
                id = "fill-entry",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "fill-bracket",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("120"),
                stopLoss = StopLossSpec.Fixed(Money.of("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
                takeProfitAst = ChildBy(NumLit(Money.of("20"))),
                stopLossAst = ChildBy(NumLit(Money.of("10"))),
            )

        om.submit(bracket)
        broker.emitFill(broker.submits.single(), price = Money.of("105"))
        assertThat((om.getOrder("fill-bracket-sl")?.request as OrderRequest.Stop).stopPrice)
            .isEqualByComparingTo("95")
        assertThat(alerts.single()).contains("engine-held stop armed at 95")

        bus.publish(TickEvent(Tick("X", Money.of("94"), 1L)))
        val close = broker.submits.last() as OrderRequest.Market
        assertThat(close.closesTicket).isEqualTo("fill-entry")
    }
}
