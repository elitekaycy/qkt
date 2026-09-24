package com.qkt.connector.mt5

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * A dealer-desk venue acknowledges a close as DONE with price 0.0 and deal 0 before the fill price
 * exists (2026-09-24 incident: booked at 0, realized -4366 on a 0.01 lot, book halted). The fill
 * must carry the closing deal's price once history shows it, else the market price, never 0.
 */
class MT5UnpricedCloseAckTest {
    private val server = MockWebServer()
    private val clock = FixedClock(time = NOW)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val prices = MarketPriceTracker()
    private val fills = CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
    private val historyReads = AtomicInteger()
    private val logs = ListAppender<ILoggingEvent>().apply { start() }
    private val brokerLog = LoggerFactory.getLogger(MT5Broker::class.java) as Logger
    private lateinit var broker: MT5Broker

    @Volatile private var closeAck = ack(price = "0.0")

    @Volatile private var history: (read: Int) -> List<String> = { listOf(entryDeal) }

    @BeforeEach
    fun setup() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") || path.startsWith("/position_close_partial") ->
                            MockResponse().setBody(closeAck)
                        path.startsWith("/history_deals_get") ->
                            MockResponse().setBody(history(historyReads.incrementAndGet()).joinToString(",", "[", "]"))
                        path.startsWith("/get_positions") || path.startsWith("/orders") -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        server.start()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        brokerLog.addAppender(logs)
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:XAUUSD" to XAUUSD_SPEC),
            )
        broker =
            MT5Broker(profile, bus, clock, prices, unknownResolveBackoffMs = 1L, unknownPeriodicResolveMs = 30L)
    }

    @AfterEach
    fun teardown() {
        brokerLog.detachAppender(logs)
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `price 0 and deal 0 ack books the closing deal that appears on the third read`() {
        history = closingDealFromRead(3, closingDeal("4355.65"))

        broker.submit(closeLong())
        val fill = awaitFill()

        assertThat(fill.price).isEqualByComparingTo("4355.65")
        // Entry commission booked on the first read, closing commission on the third: none lost.
        assertThat(fill.venueCosts).isEqualByComparingTo("0.12")
        assertThat(historyReads.get()).isEqualTo(3)
        assertThat(errors()).isEmpty()
    }

    @Test
    fun `price 0 ack naming the close order matches its deal despite venue clock skew`() {
        closeAck = ack(price = "0.0", order = 301)
        history = closingDealFromRead(1, closingDeal("4355.65", timeMs = NOW - 30_000L))

        broker.submit(closeLong())

        assertThat(awaitFill().price).isEqualByComparingTo("4355.65")
        assertThat(historyReads.get()).isEqualTo(1)
    }

    @Test
    fun `closing deal never appears -- books the market bid as provisional, never 0`() {
        prices.update(
            Tick("EXNESS:XAUUSD", BigDecimal("4355.25"), NOW, bid = BigDecimal("4355.10"), ask = BigDecimal("4355.40")),
        )

        broker.submit(closeLong())
        val fill = awaitFill()

        assertThat(fill.price).isEqualByComparingTo("4355.10")
        assertThat(historyReads.get()).isEqualTo(4)
        assertThat(errors().single()).contains("PROVISIONAL").contains("4355.10")
    }

    @Test
    fun `no deal and no market price -- nothing booked until a later read finds the deal`() {
        history = closingDealFromRead(6, closingDeal("4355.65"))

        broker.submit(closeLong())
        val fill = awaitFill()

        assertThat(fill.price).isEqualByComparingTo("4355.65")
        assertThat(historyReads.get()).isEqualTo(6)
        assertThat(errors()).anyMatch { it.contains("NOT booked") }
    }

    @Test
    fun `priced ack books its own price with a single history read`() {
        closeAck = ack(price = "4355.30", deal = 401)
        history = closingDealFromRead(1, closingDeal("4355.31"))

        broker.submit(closeLong())
        val fill = awaitFill()
        Thread.sleep(100)

        assertThat(fill.price).isEqualByComparingTo("4355.30")
        assertThat(historyReads.get()).isEqualTo(1)
        assertThat(fills).hasSize(1)
    }

    @Test
    fun `unpriced partial close books its own deal, not an earlier partial on the ticket`() {
        val earlierPartial = closingDeal("4340.00", ticket = 400, timeMs = NOW - 600_000L)
        history = closingDealFromRead(2, closingDeal("4355.65"), alwaysVisible = listOf(entryDeal, earlierPartial))

        broker.submit(closeLong().copy(partialClose = true))
        val fill = awaitFill()

        assertThat(fill.price).isEqualByComparingTo("4355.65")
        assertThat(fill.quantity).isEqualByComparingTo("0.01")
    }

    /** Deal history in which [closing] first shows on read [firstRead]; [alwaysVisible] shows on every read. */
    private fun closingDealFromRead(
        firstRead: Int,
        closing: String,
        alwaysVisible: List<String> = listOf(entryDeal),
    ): (Int) -> List<String> = { read -> if (read >= firstRead) alwaysVisible + closing else alwaysVisible }

    private fun awaitFill(timeoutMs: Long = 3_000): BrokerEvent.OrderFilled {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (fills.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        return fills.first()
    }

    private fun errors(): List<String> = logs.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    private fun closeLong() =
        OrderRequest.Market(
            id = "dsl-xauusd--2",
            symbol = "EXNESS:XAUUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.01"),
            timeInForce = TimeInForce.GTC,
            timestamp = NOW,
            strategyId = "dsl-xauusd",
            closesTicket = "999",
        )

    private companion object {
        const val NOW = 1_700_000_000_000L

        val XAUUSD_SPEC = InstrumentSpec(BigDecimal("0.01"), BigDecimal("0.01"), BigDecimal("0.001"), 3, 0)

        fun ack(
            price: String,
            deal: Long = 0,
            order: Long = 0,
        ) = """{"result":{"retcode":10009,"order":$order,"deal":$deal,"volume":"0.01","price":"$price"}}"""

        fun deal(
            ticket: Long,
            entry: Int,
            price: String,
            commission: String,
            timeMs: Long,
        ) = """{"ticket":$ticket,"order":${ticket - 100},"position_id":999,"symbol":"XAUUSDm","type":$entry,""" +
            """"entry":$entry,"volume":"0.01","price":"$price","profit":"0","commission":"$commission",""" +
            """"swap":"0","fee":"0","magic":10001,"comment":"","time_msc":$timeMs}"""

        val entryDeal =
            deal(ticket = 390, entry = 0, price = "4366.43", commission = "-0.05", timeMs = NOW - 3_600_000L)

        fun closingDeal(
            price: String,
            ticket: Long = 401,
            timeMs: Long = NOW,
        ) = deal(ticket, entry = 1, price = price, commission = "-0.07", timeMs = timeMs)
    }
}
