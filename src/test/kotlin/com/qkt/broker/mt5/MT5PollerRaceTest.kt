package com.qkt.broker.mt5

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
import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Phase 26c follow-up (v0.28.5): when a pending stop transitions to a position on MT5,
 * the pending-order poller and the position poller observe the change on independent
 * threads. Before v0.28.5 a pending-poller win silently dropped the fill — strategy
 * never received [BrokerEvent.OrderFilled]. These tests exercise both poll orderings
 * deterministically by driving each poller's tick from the test thread.
 */
class MT5PollerRaceTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
    private val accepts = mutableListOf<BrokerEvent.OrderAccepted>()

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // State recovery + position-poller seed + pending-poller seed — all empty.
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> accepts.add(e) }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        broker = MT5Broker(profile, bus, clock)
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    private fun pendingBracket(
        id: String,
        side: Side,
        stop: String,
    ): OrderRequest.Bracket {
        val entry =
            OrderRequest.Stop(
                id = "$id-entry",
                symbol = "EXNESS:EURUSD",
                side = side,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal(stop),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        return OrderRequest.Bracket(
            id = id,
            symbol = "EXNESS:EURUSD",
            side = side,
            quantity = BigDecimal("0.10"),
            entry = entry,
            takeProfit = if (side == Side.BUY) BigDecimal("1.1300") else BigDecimal("1.1100"),
            stopLoss =
                com.qkt.execution.StopLossSpec.Fixed(
                    if (side == Side.BUY) BigDecimal("1.1150") else BigDecimal("1.1250"),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )
    }

    @Test
    fun `pending-poller wins race — fill is recovered, no phantom cancel`() {
        // Placement response: ticket 9001 returned for the pending BUY_STOP.
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-1", Side.BUY, "1.1200"))
        assertThat(accepts).hasSize(1)
        assertThat(fills).isEmpty()

        // Seed pending-poller's lastSnapshot with the BUY ticket.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"4","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Pending-poller wins the race: next tick sees /orders empty (BUY filled and moved
        // to /positions). Before the position-poller runs, the Fix A cross-check inside
        // onPendingDisappeared observes the ticket in /positions and synthesizes the fill.
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","open_time":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        assertThat(fills).hasSize(1)
        assertThat(fills[0].clientOrderId).isEqualTo("ord-1")
        assertThat(fills[0].brokerOrderId).isEqualTo("9001")
        assertThat(cancels).isEmpty()

        // Position-poller now ticks and sees the same ticket — must not double-fire.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","open_time":"0"}]""",
            ),
        )
        broker.poller.tick()
        assertThat(fills).hasSize(1)
        assertThat(cancels).isEmpty()
    }

    @Test
    fun `position-poller wins race — single fill, ttl marker suppresses pending-poller`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9002,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-2", Side.BUY, "1.1200"))

        // Seed pending-poller with the BUY ticket.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9002","symbol":"EURUSDm","type":"4","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Position-poller wins: sees the ticket appear in /positions before pending-poller
        // sees it disappear from /orders.
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9002","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","open_time":"0"}]""",
            ),
        )
        broker.poller.tick()
        assertThat(fills).hasSize(1)
        assertThat(fills[0].clientOrderId).isEqualTo("ord-2")

        // Pending-poller ticks: sees ticket gone from /orders, finds the TTL marker,
        // consumes it silently — no duplicate fill, no phantom cancel.
        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()
        assertThat(fills).hasSize(1)
        assertThat(cancels).isEmpty()
    }

    @Test
    fun `real external cancel — onPendingDisappeared still publishes OrderCancelled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":9003,"deal":0,"price":"1.1200","comment":"ok"}}""",
            ),
        )
        broker.submit(pendingBracket("ord-3", Side.BUY, "1.1200"))

        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":"9003","symbol":"EURUSDm","type":"4","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
            ),
        )
        broker.pendingPoller.tickForTesting()

        // Pending disappears from /orders, and the cross-check finds /positions empty
        // (the user cancelled the pending in MetaTrader, or GTD expired).
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()

        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
        assertThat(cancels[0].clientOrderId).isEqualTo("ord-3")
        assertThat(cancels[0].reason).contains("external or gtd-expired")
    }

    @Test
    fun `un-correlated venue position — WARN logged, no OrderFilled`() {
        val logger = LoggerFactory.getLogger(MT5Broker::class.java) as Logger
        val appender =
            ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            // Position-poller sees a brand-new ticket the broker never placed
            // (e.g. manual MT5 trade with the same magic).
            server.enqueue(
                MockResponse().setBody(
                    """[{"ticket":"7777","symbol":"EURUSDm","type":"1","volume":"0.05","price_open":"1.1234","sl":"0","tp":"0","profit":"0","magic":"10001","open_time":"0"}]""",
                ),
            )
            broker.poller.tick()

            assertThat(fills).isEmpty()
            val warning =
                appender.list.firstOrNull {
                    it.level.toString() == "WARN" && it.formattedMessage.contains("no qkt-side pending meta")
                }
            assertThat(warning).isNotNull
            assertThat(warning!!.formattedMessage).contains("7777")
            assertThat(warning.formattedMessage).contains("EURUSDm")
            assertThat(warning.formattedMessage).contains("SELL")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `pending-poller wins then position-poller re-observes — silent, no duplicate WARN`() {
        // Same shape as the pending-poller-wins race test, but explicitly asserts that
        // when the position-poller later sees the same ticket in its opened-delta, the
        // no-meta path is silent (positionMetaByTicket already holds it) — no duplicate
        // OrderFilled, no false-alarm WARN.
        val logger = LoggerFactory.getLogger(MT5Broker::class.java) as Logger
        val appender =
            ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"result":{"retcode":10009,"order":9100,"deal":0,"price":"1.1200","comment":"ok"}}""",
                ),
            )
            broker.submit(pendingBracket("ord-9100", Side.BUY, "1.1200"))

            server.enqueue(
                MockResponse().setBody(
                    """[{"ticket":"9100","symbol":"EURUSDm","type":"4","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","magic":"10001","time_setup":"0"}]""",
                ),
            )
            broker.pendingPoller.tickForTesting()

            // Pending-poller wins.
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(
                MockResponse().setBody(
                    """[{"ticket":"9100","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","open_time":"0"}]""",
                ),
            )
            broker.pendingPoller.tickForTesting()
            assertThat(fills).hasSize(1)

            // Position-poller now ticks; same ticket appears in its opened-delta.
            server.enqueue(
                MockResponse().setBody(
                    """[{"ticket":"9100","symbol":"EURUSDm","type":"0","volume":"0.10","price_open":"1.1200","sl":"1.1150","tp":"1.1300","profit":"0","magic":"10001","open_time":"0"}]""",
                ),
            )
            broker.poller.tick()

            assertThat(fills).hasSize(1)
            val warning =
                appender.list.firstOrNull {
                    it.level.toString() == "WARN" && it.formattedMessage.contains("no qkt-side pending meta")
                }
            assertThat(warning).isNull()
        } finally {
            logger.detachAppender(appender)
        }
    }

    companion object {
        private val TEST_EURUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
            )
    }
}
