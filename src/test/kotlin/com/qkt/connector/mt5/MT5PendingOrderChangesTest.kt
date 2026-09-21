package com.qkt.connector.mt5

import com.qkt.broker.OrderModification
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PendingOrderChangesTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val changes =
        MT5PendingOrderChanges(
            profile,
            MT5Client(profile.gatewayUrl, profile.serverTimeZone, httpTimeoutMs = 500L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5PartialEntries(books, bus, clock),
        )

    @Test
    fun `re-pricing an order this session does not hold is refused by id`() {
        val ack = changes.modify("ghost", OrderModification(newStopPrice = BigDecimal("2401")))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.brokerOrderId).isNull()
        assertThat(ack.rejectReason).isEqualTo("modify: no working order with id=ghost")
    }

    @Test
    fun `a cancel the venue did not confirm reports failure and keeps the ticket attributable`() {
        val failures = CopyOnWriteArrayList<BrokerEvent.OrderCancelFailed>()
        val cancelled = CopyOnWriteArrayList<BrokerEvent.OrderCancelled>()
        val answered = CountDownLatch(1)
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancelled.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelFailed> { e ->
            failures.add(e)
            answered.countDown()
        }
        val meta = MT5TicketMeta("dsl-gold--1", "gold_trend")
        books.pendingBook.register(42L, meta)

        changes.cancel("dsl-gold--1")

        assertThat(answered.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(failures.single().brokerOrderId).isEqualTo("42")
        assertThat(failures.single().strategyId).isEqualTo("gold_trend")
        assertThat(cancelled).isEmpty()
        assertThat(books.pendingBook.stillIs("dsl-gold--1", 42L, meta)).isTrue()
    }
}
