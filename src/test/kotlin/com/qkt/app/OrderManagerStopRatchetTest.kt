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
import com.qkt.dsl.ast.SteppedStopAst
import com.qkt.dsl.ast.StopStepAst
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedOcoLeg
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerStopRatchetTest {
    private fun stepped(id: String = "step-sl") =
        OrderRequest.SteppedStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            initialDistance = Money.of("50"),
            steps =
                listOf(
                    StopLossSpec.Step(Money.of("30"), Money.ZERO),
                    StopLossSpec.Step(Money.of("70"), Money.of("40")),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private fun timeTighten(id: String = "time-sl") =
        OrderRequest.TimeTighteningStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            initialDistance = Money.of("60"),
            tightenBy = Money.of("10"),
            intervalMs = 900_000L,
            floorDistance = Money.of("20"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    private data class Fixture(
        val clock: FixedClock,
        val bus: EventBus,
        val broker: FakeBroker,
        val manager: OrderManager,
    )

    private fun fixture(
        capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET),
        persistor: NoopStatePersistor = NoopStatePersistor(),
        closeTicket: String? = null,
    ): Fixture {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, capabilities)
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                closeTicketFor = { _, _ -> closeTicket },
            )
        return Fixture(clock, bus, broker, manager)
    }

    private fun Fixture.tick(
        price: String,
        timestamp: Long,
    ) {
        clock.advanceTo(timestamp)
        bus.publish(TickEvent(Tick("X", Money.of(price), timestamp)))
    }

    @Test
    fun `one gap tick consumes every crossed step and locks the final target`() {
        val fixture = fixture()
        fixture.manager.submit(stepped())

        fixture.tick("170", 1L)
        fixture.tick("139", 2L)

        val close = fixture.broker.submits.single() as OrderRequest.Market
        assertThat(close.id).isEqualTo("step-sl")
        assertThat(close.side).isEqualTo(Side.SELL)
    }

    @Test
    fun `a later widening step is skipped`() {
        val fixture = fixture()
        val request =
            stepped().copy(
                steps =
                    listOf(
                        StopLossSpec.Step(Money.of("30"), Money.of("40")),
                        StopLossSpec.Step(Money.of("70"), Money.of("10")),
                    ),
            )
        fixture.manager.submit(request)

        fixture.tick("170", 1L)
        fixture.tick("120", 2L)

        assertThat(fixture.broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `stepped targets are direction relative for a short entry`() {
        val fixture = fixture()
        fixture.manager.submit(
            stepped().copy(
                side = Side.BUY,
                steps = listOf(StopLossSpec.Step(Money.of("30"), Money.ZERO)),
            ),
        )

        fixture.tick("70", 1L)
        fixture.tick("101", 2L)

        val close = fixture.broker.submits.single() as OrderRequest.Market
        assertThat(close.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `time tightening accrues intervals and clamps at its floor`() {
        val fixture = fixture()
        fixture.manager.submit(timeTighten())

        fixture.tick("100", 6 * 900_000L)
        fixture.tick("79", 6 * 900_000L + 1)

        assertThat(fixture.broker.submits.single()).isInstanceOf(OrderRequest.Market::class.java)
    }

    @Test
    fun `ratchet transition mirrors the tighter stop to a position-modify venue`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(
                        OrderTypeCapability.MARKET,
                        OrderTypeCapability.POSITION_MODIFY,
                    ),
                closeTicket = "ticket-42",
            )
        fixture.manager.submit(stepped())

        fixture.tick("130", 1L)
        fixture.tick("131", 2L)

        assertThat(fixture.broker.modifyPositions).hasSize(1)
        val modification = fixture.broker.modifyPositions.single()
        assertThat(modification.ticket).isEqualTo("ticket-42")
        assertThat(modification.sl).isEqualByComparingTo("100")
        assertThat(modification.tp).isNull()
    }

    @Test
    fun `attached bracket anchors then modifies its venue stop at a milestone`() {
        val fixture =
            fixture(
                capabilities =
                    setOf(
                        OrderTypeCapability.BRACKET,
                        OrderTypeCapability.MARKET,
                        OrderTypeCapability.POSITION_MODIFY,
                    ),
                closeTicket = "entry",
            )
        fixture.tick("100", 0L)
        val entry =
            OrderRequest.Market(
                id = "entry",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val bracket =
            OrderRequest.Bracket(
                id = "bracket",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                entry = entry,
                takeProfit = Money.of("220"),
                stopLoss =
                    StopLossSpec.SteppedStop(
                        Money.of("50"),
                        listOf(StopLossSpec.Step(Money.of("30"), Money.ZERO)),
                    ),
                stopLossAst =
                    ChildBy(
                        NumLit(Money.of("50")),
                        SteppedStopAst(
                            listOf(
                                StopStepAst(NumLit(Money.of("30")), NumLit(Money.ZERO)),
                            ),
                        ),
                    ),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            )

        fixture.manager.submit(bracket)
        fixture.broker.emitFill(fixture.broker.submits.single(), Money.of("100"))
        fixture.tick("130", 1L)

        assertThat(fixture.broker.modifyPositions).hasSize(2)
        assertThat(fixture.broker.modifyPositions[0].sl).isEqualByComparingTo("50")
        assertThat(fixture.broker.modifyPositions[1].sl).isEqualByComparingTo("100")
        assertThat(fixture.broker.modifyPositions[1].tp).isNull()
    }

    @Test
    fun `restart resumes a stepped stop at its persisted cursor and level`() {
        val persistor = NoopStatePersistor()
        val before = fixture(persistor = persistor)
        before.manager.submit(stepped())
        before.tick("130", 1L)

        val saved = persistor.loadTrailingStops("alpha").single()
        assertThat(saved.stepIndex).isEqualTo(1)
        assertThat(saved.stopLevel).isEqualByComparingTo("100")
        persistor.saveOcoLegs(
            "alpha",
            listOf(
                PersistedOcoLeg(
                    clientOrderId = "step-sl",
                    brokerOrderId = "step-sl",
                    strategyId = "alpha",
                    request = stepped(),
                    siblingIds = listOf("step-tp"),
                ),
            ),
        )

        val after = fixture(persistor = persistor, closeTicket = "ticket-42")
        after.manager.restore(listOf("alpha"))
        assertThat(after.broker.recovered).isEmpty()
        after.tick("170", 2L)
        after.tick("139", 3L)

        val close = after.broker.submits.single() as OrderRequest.Market
        assertThat(close.closesTicket).isEqualTo("ticket-42")
    }
}
