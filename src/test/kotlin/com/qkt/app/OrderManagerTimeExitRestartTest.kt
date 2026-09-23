package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.execution.withLegIntent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.StatePersistor
import com.qkt.positions.LegRole
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OrderManagerTimeExitRestartTest {
    private val sid = "alpha"
    private val clock = FixedClock(time = 1_000L)

    private inner class Session(
        persistor: StatePersistor,
        var openQty: BigDecimal? = Money.of("1"),
    ) {
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET, OrderTypeCapability.LIMIT))
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor,
                closeTicketFor = { _, legId -> "T-$legId" },
                positionMode = { PositionAccountingMode.HEDGING },
                openLegQuantity = { _, _ -> openQty },
            )

        fun tickAt(ts: Long) {
            clock.time = ts
            bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
        }

        fun closes() = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.id.endsWith("-close") }
    }

    private fun market(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
            strategyId = sid,
        )

    private fun timeExit(target: OrderRequest) =
        OrderRequest.TimeExit(
            id = "${target.id}-exit",
            symbol = "X",
            side = target.side,
            quantity = target.quantity,
            target = target,
            deadline = Instant.ofEpochMilli(1_000L),
            onExpiry = ExpiryAction.CLOSE_AT_MARKET,
            timeInForce = TimeInForce.GTC,
            timestamp = 1_000L,
            strategyId = sid,
            holdMs = 90_000L,
        )

    // Seed fills at 10s with a 90s hold, so the deadline is 100s.
    private fun armSeed(persistor: StatePersistor): Session {
        val first = Session(persistor)
        first.om.submit(timeExit(market("e1")))
        clock.time = 10_000L
        first.broker.emitFill(market("e1"), price = Money.of("100"))
        first.tickAt(20_000L)
        return first
    }

    @Test
    fun `a restored exit fires at its original deadline and clears its record`(
        @TempDir tmp: Path,
    ) {
        armSeed(FileStatePersistor(tmp))
        val saved = FileStatePersistor(tmp).loadTimedExits(sid).single()
        assertThat(saved.legId to saved.ticket).isEqualTo("e1" to "T-e1")
        assertThat(saved.deadlineMs).isEqualTo(100_000L)
        val restarted = Session(FileStatePersistor(tmp))
        clock.time = 50_000L
        restarted.om.restore(listOf(sid))

        restarted.tickAt(99_999L)
        assertThat(restarted.closes()).isEmpty()

        restarted.tickAt(100_000L)
        val close = restarted.closes().single()
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.closesLegId).isEqualTo("e1")
        assertThat(close.closesTicket).isEqualTo("T-e1")
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).isEmpty()
    }

    @Test
    fun `a deadline that passed during downtime closes the leg on the first tick`(
        @TempDir tmp: Path,
    ) {
        armSeed(FileStatePersistor(tmp))
        val restarted = Session(FileStatePersistor(tmp))
        clock.time = 300_000L
        restarted.om.restore(listOf(sid))

        restarted.tickAt(300_001L)

        assertThat(restarted.closes().single().closesLegId).isEqualTo("e1")
    }

    @Test
    fun `a restored exit whose leg is gone at the venue is dropped without a close`(
        @TempDir tmp: Path,
    ) {
        armSeed(FileStatePersistor(tmp))
        val restarted = Session(FileStatePersistor(tmp), openQty = null)
        clock.time = 50_000L
        restarted.om.restore(listOf(sid))

        restarted.tickAt(50_001L)
        restarted.tickAt(200_000L)

        assertThat(restarted.closes()).isEmpty()
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).isEmpty()
    }

    @Test
    fun `a leg that exits first clears the persisted exit`(
        @TempDir tmp: Path,
    ) {
        val session = armSeed(FileStatePersistor(tmp))
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).hasSize(1)

        session.openQty = null
        session.broker.emitFill(market("tp-elsewhere"), price = Money.of("101"))
        session.tickAt(30_000L)
        session.tickAt(200_000L)

        assertThat(session.closes()).isEmpty()
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).isEmpty()
    }

    @Test
    fun `a normal fire clears the persisted exit`(
        @TempDir tmp: Path,
    ) {
        val session = armSeed(FileStatePersistor(tmp))

        session.tickAt(100_000L)

        assertThat(session.closes()).hasSize(1)
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).isEmpty()
    }

    @Test
    fun `a stack leg's exit persists with its bracket's protective orders`(
        @TempDir tmp: Path,
    ) {
        val persistor = FileStatePersistor(tmp)
        val session = Session(persistor)
        val leg =
            OrderRequest
                .Bracket(
                    id = "p1-tier0",
                    symbol = "X",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    entry = market("p1-tier0-entry"),
                    takeProfit = Money.of("110"),
                    stopLoss = StopLossSpec.Fixed(Money.of("90")),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1_000L,
                    strategyId = sid,
                ).withLegIntent(LegIntent.Open("p1-tier0", LegRole.STACK, "p1"))
        session.om.submit(timeExit(leg))
        clock.time = 10_000L
        session.broker.emitFill(market("p1-tier0-entry"), price = Money.of("100"))
        session.tickAt(20_000L)

        val saved = FileStatePersistor(tmp).loadTimedExits(sid).single()
        assertThat(saved.legId).isEqualTo("p1-tier0")
        assertThat(saved.protectiveIds).contains("p1-tier0", "p1-tier0-tp", "p1-tier0-sl")

        val restarted = Session(FileStatePersistor(tmp))
        clock.time = 50_000L
        restarted.om.restore(listOf(sid))
        restarted.tickAt(100_000L)
        assertThat(restarted.closes().single().closesLegId).isEqualTo("p1-tier0")
    }
}
