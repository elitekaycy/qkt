package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
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

/**
 * Armed-trail brackets on a venue that can both attach SL/TP to an order and modify an open
 * position's SL/TP (BRACKET + POSITION_MODIFY) ship the bracket whole so the venue holds the
 * pre-arm SL + TP on the position — on a hedging account the venue closes that ticket instead
 * of a resting exit order opening a counter. The bracket is keyed under the ENTRY id so ticket
 * capture and close attribution still flow through the existing entry.id paths.
 */
class OrderManagerAttachedBracketTest {
    private fun newBus(clock: FixedClock): EventBus = EventBus(clock, MonotonicSequenceGenerator())

    private fun armedTrailBracket(): OrderRequest.Bracket {
        val entry =
            OrderRequest.Stop(
                id = "e1",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        return OrderRequest.Bracket(
            id = "b1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            entry = entry,
            takeProfit = Money.of("120"),
            stopLoss = StopLossSpec.ArmedTrail(trailDistance = Money.of("5"), mfeThreshold = Money.of("10")),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
        )
    }

    private val attachCaps =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.BRACKET,
            OrderTypeCapability.POSITION_MODIFY,
        )

    @Test
    fun `ships one native bracket keyed under the entry id`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())

        assertThat(broker.submits).hasSize(1)
        val shipped = broker.submits.single()
        assertThat(shipped).isInstanceOf(OrderRequest.Bracket::class.java)
        assertThat(shipped.id).isEqualTo("e1")
    }

    @Test
    fun `does not rest separate exit orders on the venue when the entry fills`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))

        // The TP/SL ride the venue order; the engine trail is held, not sent — so no extra
        // resting exit orders that would open counters on a hedging account.
        assertThat(broker.submits).hasSize(1)
    }

    @Test
    fun `engine trail still fires close-by-ticket at the tightened level`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closeTicketFor = { _, exitId -> if (exitId == "b1-sl") "tkt-1" else null },
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        // Arm (MFE 10 ≥ threshold → hwm 110, trail 110−5=105), then drop through (104 ≤ 105).
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        val fired = broker.submits.first { it.id == "b1-sl" } as OrderRequest.Market
        assertThat(fired.closesTicket).isEqualTo("tkt-1")
        assertThat(fired.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `plain bracket armed trail resolves the primary position ticket`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closePrimaryTicketFor = { _, symbol -> if (symbol == "X") "primary-7" else null },
                requireArmedTrailTicket = true,
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        val fired = broker.submits.first { it.id == "b1-sl" } as OrderRequest.Market
        assertThat(fired.closesTicket).isEqualTo("primary-7")
    }

    @Test
    fun `attached armed trail is cancelled when its venue position disappears`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        var ticket: String? = "primary-7"
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                closePrimaryTicketFor = { _, _ -> ticket },
                requireArmedTrailTicket = true,
            )

        om.submit(armedTrailBracket())
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        ticket = null
        bus.publish(TickEvent(Tick("X", Money.of("110"), 1L)))
        bus.publish(TickEvent(Tick("X", Money.of("104"), 2L)))

        assertThat(broker.submits.map { it.id }).doesNotContain("b1-sl")
    }

    @Test
    fun `fixed bracket on an attach venue ships native keyed under the entry id`() {
        // The orchestrator stack-tier shape: a market entry with a fixed SL/TP. Keying the venue
        // order under the entry id is what lets registerStackOpen (which keys on entry.id) match
        // the fill, so the STACK leg is tracked on a hedging venue — not just in backtest.
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val broker = FakeBroker(bus, clock, attachCaps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        val bracket =
            OrderRequest.Bracket(
                id = "stk-tier0",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry =
                    OrderRequest.Market(
                        id = "stk-tier0-entry",
                        symbol = "X",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                takeProfit = Money.of("130"),
                stopLoss = StopLossSpec.Fixed(Money.of("90")),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(bracket)

        assertThat(broker.submits).hasSize(1)
        val shipped = broker.submits.single()
        assertThat(shipped).isInstanceOf(OrderRequest.Bracket::class.java)
        assertThat(shipped.id).isEqualTo("stk-tier0-entry")
    }

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

    @Test
    fun `without POSITION_MODIFY it falls back to decomposed resting exits`() {
        val clock = FixedClock(0L)
        val bus = newBus(clock)
        val caps = setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT, OrderTypeCapability.STOP)
        val broker = FakeBroker(bus, clock, caps)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(armedTrailBracket())
        // Fallback submits the entry under its own id first...
        assertThat(broker.submits.single().id).isEqualTo("e1")
        broker.emitFill(broker.submits.single(), price = Money.of("100"))
        // ...then rests the TP limit (the SL ArmedTrailingStop is engine-held).
        assertThat(broker.submits.map { it::class.simpleName }).contains("Limit")
    }
}
