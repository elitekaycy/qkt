package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** A cancel the venue confirms after its order was already replaced by another owner changes nothing. */
class MT5PendingCancelReplacedTest {
    private val arrived = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        arrived.countDown()
                        release.await(10, TimeUnit.SECONDS)
                        return MockResponse().setBody("""{"message":"order cancelled"}""")
                    }
                }
            start()
        }
    private val profile =
        MT5DefaultProfiles.exness.copy(gatewayUrl = server.url("/").toString().trimEnd('/'), retryAttempts = 0)
    private val clock = FixedClock(50_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val cancelled = CopyOnWriteArrayList<BrokerEvent.OrderCancelled>()
    private val changes =
        MT5PendingOrderChanges(
            profile,
            MT5Client(profile.gatewayUrl, profile.serverTimeZone, httpTimeoutMs = 5_000L, retryAttempts = 0),
            bus,
            clock,
            books,
            MT5PartialEntries(books, bus, clock),
        )

    @AfterEach
    fun stop() {
        release.countDown()
        server.shutdown()
    }

    @Test
    fun `a confirmed cancel does not drop an order whose ticket changed hands meanwhile`() {
        bus.subscribe<BrokerEvent.OrderCancelled> { cancelled.add(it) }
        books.pendingBook.register(42L, MT5TicketMeta("dsl-gold--1", "gold_trend"))

        changes.cancel("dsl-gold--1")
        assertThat(arrived.await(10, TimeUnit.SECONDS)).isTrue()
        val replacement = MT5TicketMeta("dsl-gold--1", "other_strategy")
        books.pendingBook.register(42L, replacement)
        release.countDown()
        awaitCallbackFinished()

        assertThat(cancelled).isEmpty()
        assertThat(books.pendingBook.stillIs("dsl-gold--1", 42L, replacement)).isTrue()
    }

    @Test
    fun `a confirmed cancel of a part-filled order forgets its partial entry`() {
        val done = CountDownLatch(1)
        bus.subscribe<BrokerEvent.OrderCancelled> {
            cancelled.add(it)
            done.countDown()
        }
        val meta = MT5TicketMeta("dsl-gold--1", "gold_trend", requestedQuantity = BigDecimal("0.10"))
        books.pendingBook.register(42L, meta)
        MT5PartialEntries(books, bus, clock).registerPartialEntry(
            PartialEntryState(
                meta = meta,
                residualTicket = 42L,
                positionTicket = 7000L,
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                requestedQuantity = BigDecimal("0.10"),
                cumulativeFilled = BigDecimal("0.04"),
                averageFillPrice = BigDecimal("2400"),
            ),
            openedAtMs = 1L,
        )
        release.countDown()

        changes.cancel("dsl-gold--1")

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(books.partialPositionByResidualTicket).isEmpty()
        assertThat(books.partialEntryByPositionTicket).isEmpty()
    }

    private fun awaitCallbackFinished() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            Thread.getAllStackTraces().values.any { s -> s.any { it.className.contains("MT5PendingOrderChanges") } }
        ) {
            Thread.onSpinWait()
        }
    }
}
