package com.qkt.connector.mt5

import com.qkt.broker.OrderModification
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Moving the trigger of resting BUY_STOP 9001 (`entry-1`), as the venue answers. */
class MT5PendingOrderModifyTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(time = 1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val changes =
        MT5PendingOrderChanges(
            profile,
            MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5PartialEntries(books, bus, clock),
        )
    private val modified = mutableListOf<BrokerEvent.OrderModified>()
    private val newStop = OrderModification(newStopPrice = BigDecimal("2455"))

    init {
        bus.subscribe<BrokerEvent.OrderModified> { modified.add(it) }
        books.pendingBook.register(9001L, MT5TicketMeta("entry-1", "gold"))
    }

    @AfterEach
    fun stop() = gateway.close()

    @Test
    fun `a modify the gateway fails over HTTP is refused with the gateway's answer`() {
        gateway.respond("/orders/9001", 500 to "boom")

        val ack = changes.modify("entry-1", newStop)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.brokerOrderId).isEqualTo("9001")
        assertThat(ack.rejectReason).isEqualTo("HTTP 500: boom")
        assertThat(modified).isEmpty()
        val (line, body) = gateway.requests.single()
        assertThat(line).isEqualTo("PUT /orders/9001")
        assertThat(body).contains("2455")
    }

    @Test
    fun `a modify the venue rejects is refused with its retcode`() {
        gateway.on("/orders/9001", """{"result":{"retcode":10016,"order":9001,"deal":0,"price":0,"comment":""}}""")

        val ack = changes.modify("entry-1", newStop)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("modify rejected: retcode=10016")
    }

    @Test
    fun `an accepted modify is announced for the order's strategy`() {
        gateway.on("/orders/9001", """{"result":{"retcode":10009,"order":9001,"deal":0,"price":0,"comment":""}}""")

        val ack = changes.modify("entry-1", OrderModification(newLimitPrice = BigDecimal("2390")))

        assertThat(ack.accepted).isTrue()
        assertThat(modified.single().strategyId).isEqualTo("gold")
        assertThat(gateway.requests.single().second).contains("2390")
    }

    @Test
    fun `a modify of an order with no working ticket never reaches the venue`() {
        val ack = changes.modify("unknown", newStop)

        assertThat(ack.rejectReason).isEqualTo("modify: no working order with id=unknown")
        assertThat(gateway.requests).isEmpty()
    }
}
