package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExpiryAction
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.StatePersistor
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * #1660: a resting entry's `EXIT AFTER` survives a restart taken while the entry is still working,
 * and its hold runs from the venue's fill time, not from when qkt heard the fill.
 */
class OrderManagerPendingTimeExitRestartTest {
    private val sid = "soak"
    private val clock = FixedClock(time = 1_000L)
    private val entry =
        OrderRequest.Limit(
            id = "e1",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            limitPrice = Money.of("99"),
            timeInForce = TimeInForce.DAY,
            timestamp = 1_000L,
            strategyId = sid,
        )

    private inner class Session(
        persistor: StatePersistor,
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
                openLegQuantity = { _, _ -> Money.of("1") },
            )

        fun tickAt(ts: Long) {
            clock.time = ts
            bus.publish(TickEvent(Tick("X", Money.of("100"), ts)))
        }

        // The venue filled the entry at [venueMs]; qkt hears it now.
        fun venueFill(venueMs: Long) =
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = entry.id,
                    brokerOrderId = entry.id,
                    symbol = "X",
                    side = Side.BUY,
                    price = Money.of("99"),
                    quantity = Money.of("1"),
                    strategyId = sid,
                    venueTimeMs = venueMs,
                ),
            )

        fun closes() = broker.submits.filterIsInstance<OrderRequest.Market>().filter { it.id.endsWith("-close") }
    }

    private val exit =
        OrderRequest.TimeExit(
            id = "e1-exit",
            symbol = "X",
            side = Side.BUY,
            quantity = Money.of("1"),
            target = entry,
            deadline = Instant.ofEpochMilli(91_000L),
            onExpiry = ExpiryAction.CLOSE_AT_MARKET,
            timeInForce = TimeInForce.DAY,
            timestamp = 1_000L,
            strategyId = sid,
            holdMs = 90_000L,
        )

    // Submits the resting entry with its exit, then stops the daemon before the entry fills.
    private fun restartWhileWorking(tmp: Path): Session {
        Session(FileStatePersistor(tmp)).om.submit(exit)
        val restarted = Session(FileStatePersistor(tmp))
        clock.time = 5_000L
        restarted.om.restore(listOf(sid))
        return restarted
    }

    @Test
    fun `a working entry's exit is persisted before the entry fills`(
        @TempDir tmp: Path,
    ) {
        Session(FileStatePersistor(tmp)).om.submit(exit)

        assertThat(FileStatePersistor(tmp).loadTimedExits(sid).map { it.id }).containsExactly("e1-exit")
    }

    @Test
    fun `an entry that fills after a restart arms its exit from the venue fill time`(
        @TempDir tmp: Path,
    ) {
        val restarted = restartWhileWorking(tmp)
        clock.time = 50_000L
        restarted.venueFill(venueMs = 10_000L)

        restarted.tickAt(99_999L)
        assertThat(restarted.closes()).isEmpty()
        restarted.tickAt(100_000L)
        assertThat(restarted.closes().single().closesLegId).isEqualTo("e1")
    }

    @Test
    fun `an entry fill learned after its hold passed closes the leg on the first tick`(
        @TempDir tmp: Path,
    ) {
        val restarted = restartWhileWorking(tmp)
        clock.time = 300_000L
        restarted.venueFill(venueMs = 10_000L)

        restarted.tickAt(300_001L)

        assertThat(restarted.closes().single().closesLegId).isEqualTo("e1")
    }

    @Test
    fun `a fill heard late without a restart is timed from the venue fill time`(
        @TempDir tmp: Path,
    ) {
        val session = Session(FileStatePersistor(tmp))
        session.om.submit(exit)
        clock.time = 50_000L
        session.venueFill(venueMs = 10_000L)

        assertThat(FileStatePersistor(tmp).loadTimedExits(sid).single().deadlineMs).isEqualTo(100_000L)
        session.tickAt(100_000L)
        assertThat(session.closes().single().closesLegId).isEqualTo("e1")
    }

    @Test
    fun `a restored entry cancelled before it fills clears its pending exit`(
        @TempDir tmp: Path,
    ) {
        val restarted = restartWhileWorking(tmp)
        restarted.bus.publish(BrokerEvent.OrderCancelled(entry.id, entry.id, "day ended", sid, timestamp = 6_000L))

        restarted.tickAt(7_000L)
        restarted.tickAt(200_000L)

        assertThat(restarted.closes()).isEmpty()
        assertThat(FileStatePersistor(tmp).loadTimedExits(sid)).isEmpty()
    }
}
