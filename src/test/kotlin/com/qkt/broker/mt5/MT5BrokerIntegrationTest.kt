package com.qkt.broker.mt5

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qkt.broker.PositionAccountingMode
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class MT5BrokerIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker
    private lateinit var bus: EventBus
    private lateinit var prices: MarketPriceTracker

    // Placement is async (OkHttp dispatcher thread), so events land off the test thread.
    private val captured = java.util.concurrent.CopyOnWriteArrayList<BrokerEvent>()

    /** Poll until [predicate] holds or [timeoutMs] elapses — used to await async venue events. */
    private fun awaitCaptured(
        timeoutMs: Long = 2000,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !predicate()) Thread.sleep(5)
    }

    private fun recoveredPending(
        id: String,
        ticket: String,
    ) = ManagedOrder(
        id = id,
        request =
            OrderRequest.Stop(
                id = id,
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "alpha",
            ),
        state = OrderState.WORKING,
        brokerOrderId = ticket,
        createdAt = 0L,
        lastUpdatedAt = 0L,
    )

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // State recovery: empty positions.
        server.enqueue(MockResponse().setBody("[]"))
        // Position poller seed: empty positions.
        server.enqueue(MockResponse().setBody("[]"))
        // Pending-order poller seed (Phase 26d): empty /orders.
        server.enqueue(MockResponse().setBody("[]"))

        val clock = FixedClock(time = 1_700_000_000_000L)
        prices = MarketPriceTracker()
        bus = EventBus(clock, MonotonicSequenceGenerator())
        bus.subscribe<BrokerEvent.OrderFilled> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderAccepted> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> captured.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> captured.add(e) }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        broker =
            MT5Broker(
                profile,
                bus,
                clock,
                priceTracker = prices,
                recoveryReadAttempts = 3,
                recoveryReadBackoffMs = 1L,
            )
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    @Test
    fun `GTD expiry is venue-owned by the current gateway`() {
        assertThat(broker.supportsNativeGtd).isTrue()
    }

    @Test
    fun `venue margin mode selects gross hedging reconciliation`() {
        server.enqueue(MockResponse().setBody("""{"margin_mode":2}"""))

        assertThat(broker.positionAccountingMode("EXNESS:EURUSD")).isEqualTo(PositionAccountingMode.HEDGING)
    }

    @Test
    fun `missing venue margin mode does not assume netting`() {
        server.enqueue(MockResponse().setBody("{}"))

        assertThat(broker.positionAccountingMode("EXNESS:EURUSD")).isEqualTo(PositionAccountingMode.UNKNOWN)
    }

    @Test
    fun `recovery retries a failed read and converges a vanished ticket to cancelled`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("pending unavailable"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        val order = recoveredPending("ord-recovered", "9001")

        broker.recoverPendingOrders(listOf(order))

        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderCancelled>().map { it.clientOrderId })
            .containsExactly("ord-recovered")
    }

    @Test
    fun `recovery refuses to continue after persistent venue read failures`() {
        repeat(6) { server.enqueue(MockResponse().setResponseCode(500).setBody("unavailable")) }

        assertThatThrownBy { broker.recoverPendingOrders(listOf(recoveredPending("ord-recovered", "9001"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("failed 3 times")
    }

    @Test
    fun `submit market buy emits accepted plus filled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        awaitCaptured { captured.size >= 2 }
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(captured[1]).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        val filled = captured[1] as BrokerEvent.OrderFilled
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.price).isEqualByComparingTo("1.1234")
        // gateway received translated symbol — consume the 3 setup calls then the actual order
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed (Phase 26d)
        val recordedOrder = server.takeRequest()
        val body = recordedOrder.body.readUtf8()
        assertThat(body).contains("\"symbol\":\"EURUSDm\"")
        assertThat(body).contains("\"magic\":10001")
        assertThat(body).contains("\"comment\":\"ord-1\"")
        assertThat(body).contains("\"client_order_id\":\"mt5-10001-session-1700000000000-0\"")
        assertThat(recordedOrder.getHeader("Idempotency-Key"))
            .isEqualTo("mt5-10001-session-1700000000000-0")
    }

    @Test
    fun `partial market entry stays working through later fill slices`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10010,"order":7001,"deal":8001,"price":"1.1000","volume":"0.04","comment":"partial"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":8001,"order":7001,"position_id":9001,"symbol":"EURUSDm","type":0,"entry":0,"volume":"0.04","price":"1.1000","profit":"0","commission":"0","swap":"0","fee":"0","magic":10001,"comment":"ord-partial","time_msc":"1700000000000"}]""",
            ),
        )
        val request =
            OrderRequest.Market(
                id = "ord-partial",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )

        assertThat(broker.submit(request).accepted).isTrue
        awaitCaptured { captured.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().size == 1 }

        val initial = captured.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().single()
        assertThat(initial.clientOrderId).isEqualTo("ord-partial")
        assertThat(initial.brokerOrderId).isEqualTo("9001")
        assertThat(initial.quantity).isEqualByComparingTo("0.04")
        assertThat(initial.cumulativeFilled).isEqualByComparingTo("0.04")
        assertThat(initial.strategyId).isEqualTo("s1")
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()

        server.enqueue(MockResponse().setBody(entryPositionJson("0.04")))
        broker.poller.tick()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderPartiallyFilled>()).hasSize(1)

        server.enqueue(MockResponse().setBody(entryPositionJson("0.07")))
        broker.poller.tick()
        val partials = captured.filterIsInstance<BrokerEvent.OrderPartiallyFilled>()
        assertThat(partials).hasSize(2)
        assertThat(partials.last().quantity).isEqualByComparingTo("0.03")
        assertThat(partials.last().cumulativeFilled).isEqualByComparingTo("0.07")

        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":7001,"deal":0,"price":"1.1000","comment":"modified"}}""",
            ),
        )
        val modifyAck =
            broker.modify(
                "ord-partial",
                com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.1010")),
            )
        assertThat(modifyAck.accepted).isTrue()
        assertThat(modifyAck.brokerOrderId).isEqualTo("7001")

        server.enqueue(MockResponse().setBody(entryPositionJson("0.10")))
        broker.poller.tick()
        val finalFill = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(finalFill.clientOrderId).isEqualTo("ord-partial")
        assertThat(finalFill.brokerOrderId).isEqualTo("9001")
        assertThat(finalFill.quantity).isEqualByComparingTo("0.03")
        assertThat(finalFill.strategyId).isEqualTo("s1")

        server.enqueue(MockResponse().setBody("[]"))
        broker.pendingPoller.tickForTesting()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderCancelled>()).isEmpty()
    }

    @Test
    fun `restart reconstructs distinct residual and position tickets for partial entry`() {
        val request =
            OrderRequest.Market(
                id = "ord-restart-partial",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":7101,"symbol":"EURUSDm","type":"BUY","volume":"0.06","price_open":"1.1000","sl":"0","tp":"0","magic":10001,"time_setup":"1700000000000","comment":"ord-restart-partial"}]""",
            ),
        )
        server.enqueue(MockResponse().setBody(restartPositionJson("0.04")))
        captured.clear()

        broker.recoverPendingOrders(
            listOf(
                ManagedOrder(
                    id = request.id,
                    request = request,
                    state = OrderState.WORKING,
                    createdAt = 1L,
                    lastUpdatedAt = 1L,
                ),
            ),
        )

        val recoveredPartial = captured.filterIsInstance<BrokerEvent.OrderPartiallyFilled>().single()
        assertThat(recoveredPartial.brokerOrderId).isEqualTo("9101")
        assertThat(recoveredPartial.cumulativeFilled).isEqualByComparingTo("0.04")
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>().single().brokerOrderId)
            .isEqualTo("7101")

        server.enqueue(MockResponse().setBody(restartPositionJson("0.10")))
        broker.poller.tick()
        val finalFill = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(finalFill.brokerOrderId).isEqualTo("9101")
        assertThat(finalFill.quantity).isEqualByComparingTo("0.06")
    }

    private fun entryPositionJson(volume: String): String =
        """[{"ticket":"9001","symbol":"EURUSDm","type":"0","volume":"$volume","price_open":"1.1000","sl":"0","tp":"0","profit":"0","magic":"10001","time_msc":"1700000000000"}]"""

    private fun restartPositionJson(volume: String): String =
        """[{"ticket":"9101","symbol":"EURUSDm","type":"0","volume":"$volume","price_open":"1.1000","sl":"0","tp":"0","profit":"0","magic":"10001","time_msc":"1700000000000","comment":"ord-restart-partial"}]"""

    @Test
    fun `each placement gets a fresh gateway id even when the engine id repeats`() {
        repeat(2) { ticket ->
            server.enqueue(
                MockResponse().setBody(
                    """{"result":{"retcode":10009,"order":${ticket + 1},"deal":${ticket + 1},""" +
                        """"price":"1.1234","comment":"ok"}}""",
                ),
            )
        }
        val request =
            OrderRequest.Market(
                id = "replayed-engine-id",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )

        broker.submit(request)
        broker.submit(request)
        awaitCaptured { captured.filterIsInstance<BrokerEvent.OrderFilled>().size == 2 }

        repeat(3) { server.takeRequest() }
        val placementIds =
            List(2) { server.takeRequest().getHeader("Idempotency-Key") }
        assertThat(placementIds).containsExactlyInAnyOrder(
            "mt5-10001-session-1700000000000-0",
            "mt5-10001-session-1700000000000-1",
        )
    }

    @Test
    fun `gateway conflict resolves as filled from venue truth, not as a rejection`() {
        // POST /order returns 409 AFTER the order actually landed. A
        // synthetic rejection would make the strategy re-fire and double the position;
        // the broker must query the venue and emit the fill it finds (#378).
        val posts =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" -> {
                            posts.incrementAndGet()
                            MockResponse().setResponseCode(409).setBody("idempotency conflict")
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(
                                """[{"ticket":"4242","symbol":"EURUSDm","type":"0","volume":"0.10",""" +
                                    """"price_open":"1.1003","sl":"0","tp":"0","profit":"0","magic":"10001",""" +
                                    """"time_msc":"0","comment":"ord-amb-1",""" +
                                    """"client_order_id":"mt5-10001-session-1700000000000-0"}]""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker =
            MT5Broker(
                profile = fastProfile,
                bus = bus,
                clock = FixedClock(time = 1_700_000_000_000L),
                unknownResolveBackoffMs = 1L,
            )
        captured.clear()
        fastBroker.submit(
            OrderRequest.Market(
                id = "ord-amb-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        fastBroker.shutdown()

        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        val fill = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("ord-amb-1")
        assertThat(fill.price).isEqualByComparingTo("1.1003")
        // Exactly one POST /order — the unknown state blocked any duplicate send.
        assertThat(posts.get()).isEqualTo(1)
    }

    @Test
    fun `ambiguous send neither reattributes nor rejects with an existing same-prefix position`() {
        val posts = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" ->
                            if (posts.incrementAndGet() == 1) {
                                MockResponse().setBody(
                                    """{"result":{"retcode":10009,"order":111,"deal":111,"price":"1.1000","comment":"ok","volume":"0.10"}}""",
                                )
                            } else {
                                MockResponse().setResponseCode(500).setBody("gateway crashed mid-send")
                            }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") ->
                            if (posts.get() < 2) {
                                MockResponse().setBody("[]")
                            } else {
                                MockResponse().setBody(
                                    """[{"ticket":111,"symbol":"EURUSDm","type":0,"volume":"0.10",""" +
                                        """"price_open":"1.1000","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"time_msc":1700000000000,"comment":"dsl-hedge_stradd"}]""",
                                )
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker =
            MT5Broker(
                profile = fastProfile,
                bus = bus,
                clock = FixedClock(time = 1_700_000_000_000L),
                unknownResolveBackoffMs = 1L,
            )
        bus.subscribe<BrokerEvent.GatewayUnreachable> { captured.add(it) }
        captured.clear()
        val first =
            OrderRequest.Market(
                id = "dsl-hedge_straddle-a",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        fastBroker.submit(first)
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled && it.clientOrderId == first.id } }
        captured.clear()

        val second = first.copy(id = "dsl-hedge_straddle-b")
        fastBroker.submit(second)
        awaitCaptured { captured.any { it is BrokerEvent.GatewayUnreachable } }
        fastBroker.shutdown()

        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.GatewayUnreachable>()).hasSize(1)
        assertThat(fastBroker.ticketAttributions()).containsEntry("111", "s1")
    }

    @Test
    fun `multiple legacy comment candidates leave the send unresolved`() {
        val posts = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" -> {
                            posts.incrementAndGet()
                            MockResponse().setResponseCode(500).setBody("gateway crashed mid-send")
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") ->
                            if (posts.get() == 0) {
                                MockResponse().setBody("[]")
                            } else {
                                MockResponse().setBody(
                                    """[
                                      {"ticket":201,"symbol":"EURUSDm","type":0,"volume":"0.10","price_open":"1.1000","sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":1700000000000,"comment":"dsl-hedge_stradd"},
                                      {"ticket":202,"symbol":"EURUSDm","type":0,"volume":"0.10","price_open":"1.1001","sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":1700000000000,"comment":"dsl-hedge_stradd"}
                                    ]""",
                                )
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker =
            MT5Broker(
                profile = fastProfile,
                bus = bus,
                clock = FixedClock(time = 1_700_000_000_000L),
                unknownResolveBackoffMs = 1L,
            )
        bus.subscribe<BrokerEvent.GatewayUnreachable> { captured.add(it) }
        captured.clear()
        fastBroker.submit(
            OrderRequest.Market(
                id = "dsl-hedge_straddle-c",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.GatewayUnreachable } }
        fastBroker.shutdown()

        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.GatewayUnreachable>()).hasSize(1)
    }

    @Test
    fun `ambiguous send failure with clean venue reads and no match rejects`() {
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" ->
                            MockResponse().setResponseCode(500).setBody("gateway crashed mid-send")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker =
            MT5Broker(
                profile = fastProfile,
                bus = bus,
                clock = FixedClock(time = 1_700_000_000_000L),
                unknownResolveBackoffMs = 1L,
            )
        captured.clear()
        fastBroker.submit(
            OrderRequest.Market(
                id = "ord-amb-2",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.OrderRejected } }
        fastBroker.shutdown()

        val rejection = captured.filterIsInstance<BrokerEvent.OrderRejected>().single()
        assertThat(rejection.clientOrderId).isEqualTo("ord-amb-2")
        assertThat(rejection.reason).contains("unknown-state")
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
    }

    @Test
    fun `ambiguous send resolved from deals replays an already closed trade`() {
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" ->
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") -> MockResponse().setBody(closedAmbiguousDeals())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastBroker = newFastUnknownOutcomeBroker()
        captured.clear()

        fastBroker.submit(ambiguousMarket("ord-deal-closed"))
        awaitCaptured { captured.filterIsInstance<BrokerEvent.OrderFilled>().size == 2 }
        fastBroker.shutdown()

        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        val fills = captured.filterIsInstance<BrokerEvent.OrderFilled>()
        assertThat(fills.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(fills.map { it.strategyId }).containsOnly("s1")
        assertThat(fills.last().updatesOrderExecution).isFalse
    }

    @Test
    fun `ambiguous send waits through clean absence before a late deal appears`() {
        val historyReads = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" ->
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") ->
                            if (historyReads.incrementAndGet() == 1) {
                                MockResponse().setBody("[]")
                            } else {
                                MockResponse().setBody(closedAmbiguousDeals())
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastBroker = newFastUnknownOutcomeBroker()
        captured.clear()

        fastBroker.submit(ambiguousMarket("ord-deal-closed"))
        awaitCaptured { captured.filterIsInstance<BrokerEvent.OrderFilled>().size == 2 }
        fastBroker.shutdown()

        assertThat(historyReads.get()).isGreaterThanOrEqualTo(2)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
    }

    private fun newFastUnknownOutcomeBroker(periodicResolveMs: Long = 5_000L): MT5Broker =
        MT5Broker(
            profile =
                MT5DefaultProfiles.exness.copy(
                    gatewayUrl = server.url("/").toString().trimEnd('/'),
                    httpTimeoutMs = 2000,
                    retryAttempts = 0,
                    pollIntervalMs = 100_000,
                    instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
                ),
            bus = bus,
            clock = FixedClock(time = 1_700_000_000_000L),
            unknownResolveBackoffMs = 1L,
            unknownPeriodicResolveMs = periodicResolveMs,
        )

    private fun ambiguousMarket(id: String): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "EXNESS:EURUSD",
            side = Side.BUY,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
        )

    private fun closedAmbiguousDeals(): String =
        """[{"ticket":301,"order":201,"position_id":101,"symbol":"EURUSDm","type":0,"entry":0,""" +
            """"volume":"0.10","price":"1.1000","profit":"0","commission":"-0.1","swap":"0",""" +
            """"fee":"0","magic":10001,"comment":"ord-deal-closed","time_msc":1700000000000,""" +
            """"client_order_id":"mt5-10001-session-1700000000000-0"},{"ticket":302,"order":202,""" +
            """"position_id":101,"symbol":"EURUSDm","type":1,"entry":1,"volume":"0.10",""" +
            """"price":"1.1100","profit":"100","commission":"-0.1","swap":"0","fee":"0",""" +
            """"magic":10001,"comment":"tp","time_msc":1700000001000}]"""

    @Test
    fun `submit returns an optimistic ack without blocking on the venue round-trip`() {
        // The gateway response is delayed; submit must return immediately with an optimistic ack
        // (no broker order id yet) and publish events only once the delayed response lands.
        server.enqueue(
            MockResponse()
                .setBody("""{"result":{"retcode":10009,"order":9,"deal":9,"price":"1.1234","comment":"ok"}}""")
                .setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val req =
            OrderRequest.Market(
                id = "ord-async",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        // Returned before the venue responded: optimistic, no broker order id, no events yet.
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isNull()
        assertThat(captured).isEmpty()
        // The venue result arrives later, as events.
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
    }

    @Test
    fun `submit market with closesTicket closes the position by ticket`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":777,"price":"1.1050","volume":"0.10","comment":"ok"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":776,"order":1,"position_id":424242,"symbol":"EURUSDm","type":0,"entry":0,""" +
                    """"volume":"0.10","price":"1.1000","profit":"0","commission":"-0.70","swap":"0",""" +
                    """"fee":"0","magic":10001,"time_msc":1699999999000},""" +
                    """{"ticket":777,"order":2,"position_id":424242,"symbol":"EURUSDm","type":1,"entry":1,""" +
                    """"volume":"0.10","price":"1.1050","profit":"10","commission":"-0.70","swap":"-0.30",""" +
                    """"fee":"-0.10","magic":10001,"time_msc":1700000000000}]""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "close-1",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "424242",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("424242")
        // The close is async — the venue result arrives later, as events.
        awaitCaptured { captured.size >= 2 }
        assertThat(captured).hasSize(2)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        val filled = captured[1] as BrokerEvent.OrderFilled
        assertThat(filled.clientOrderId).isEqualTo("close-1")
        assertThat(filled.brokerOrderId).isEqualTo("424242")
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.side).isEqualTo(Side.SELL)
        assertThat(filled.price).isEqualByComparingTo("1.1050")
        assertThat(filled.quantity).isEqualByComparingTo("0.10")
        assertThat(filled.venueCosts).isEqualByComparingTo("1.80")
        // The gateway was hit at /close_position with the ticket — NOT /order.
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/close_position")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":{"ticket":424242,"volume":0.10}}""")
    }

    @Test
    fun `partial closesTicket uses the gateway partial-close route`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":778,"price":"1.1050","volume":"0.60","comment":"ok"}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("[]"))
        val req =
            OrderRequest.Market(
                id = "resize-shrink",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.60"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "424242",
                closesLegId = "primary",
                partialClose = true,
            )

        val ack = broker.submit(req)

        assertThat(ack.accepted).isTrue
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        val filled = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(filled.quantity).isEqualByComparingTo("0.60")
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":424242,"symbol":"EURUSDm","type":0,"volume":"1.00",""" +
                    """"price_open":"1.1000","sl":"1.0900","tp":"1.1200","profit":"0",""" +
                    """"magic":10001,"time_msc":0}]""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":424242,"symbol":"EURUSDm","type":0,"volume":"0.40",""" +
                    """"price_open":"1.1000","sl":"1.0900","tp":"1.1200","profit":"0",""" +
                    """"magic":10001,"time_msc":0}]""",
            ),
        )
        broker.poller.tick()
        broker.poller.tick()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/position_close_partial")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"ticket":424242,"volume":0.60}""")
    }

    @Test
    fun `closesTicket close failure surfaces as OrderRejected`() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":"Failed to close position"}"""),
        )
        val req =
            OrderRequest.Market(
                id = "close-x",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
                closesTicket = "999",
            )
        // Like async placement, the ack is optimistic; the venue's refusal lands as an event.
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        awaitCaptured { captured.any { it is BrokerEvent.OrderRejected } }
        assertThat(captured.any { it is BrokerEvent.OrderRejected }).isTrue
        assertThat(captured.none { it is BrokerEvent.OrderFilled }).isTrue
    }

    @Test
    fun `ambiguous close resolved from deals preserves close order attribution`() {
        val closeSent = AtomicBoolean(false)
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") -> {
                            closeSent.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") ->
                            MockResponse().setBody(if (closeSent.get()) closeDealHistory() else "[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastBroker = newFastUnknownOutcomeBroker()
        captured.clear()

        fastBroker.submit(ambiguousClose("close-ambiguous"))
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        fastBroker.shutdown()

        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        val fill = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("close-ambiguous")
        assertThat(fill.brokerOrderId).isEqualTo("999")
        assertThat(fill.price).isEqualByComparingTo("1.1050")
    }

    @Test
    fun `ambiguous close waits through clean open read before late close deal`() {
        val closeSent = AtomicBoolean(false)
        val historyReads = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") -> {
                            closeSent.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/get_positions") ->
                            if (closeSent.get() && historyReads.get() == 0) {
                                MockResponse().setBody(openPosition999())
                            } else {
                                MockResponse().setBody("[]")
                            }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") ->
                            if (historyReads.incrementAndGet() == 1) {
                                MockResponse().setBody("[]")
                            } else {
                                MockResponse().setBody(closeDealHistory())
                            }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastBroker = newFastUnknownOutcomeBroker()
        captured.clear()

        fastBroker.submit(ambiguousClose("close-late"))
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        fastBroker.shutdown()

        assertThat(historyReads.get()).isGreaterThanOrEqualTo(2)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>().single().clientOrderId).isEqualTo("close-late")
    }

    @Test
    fun `ambiguous close resolution outlasting marker ttl publishes one close fill`() {
        val localServer = MockWebServer().also { it.start() }
        val closeSent = AtomicBoolean(false)
        val nowMs = System.currentTimeMillis()
        localServer.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") -> {
                            closeSent.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(if (closeSent.get()) "[]" else openPosition999(nowMs))
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") ->
                            MockResponse().setBody(closeDealHistory(nowMs))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val localBus = EventBus(com.qkt.common.SystemClock(), MonotonicSequenceGenerator())
        val fills = java.util.concurrent.CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
        localBus.subscribe<BrokerEvent.OrderFilled>(fills::add)
        val delayedBroker =
            MT5Broker(
                profile =
                    MT5DefaultProfiles.exness.copy(
                        gatewayUrl = localServer.url("/").toString().trimEnd('/'),
                        httpTimeoutMs = 500,
                        retryAttempts = 0,
                        pollIntervalMs = 100,
                        instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
                    ),
                bus = localBus,
                clock = com.qkt.common.SystemClock(),
                unknownResolveBackoffMs = 700L,
                recoveryReadBackoffMs = 1L,
            )
        try {
            delayedBroker.submit(ambiguousClose("close-delayed"))
            val deadline = System.currentTimeMillis() + 5_000L
            while (fills.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(150L)

            assertThat(fills).hasSize(1)
            assertThat(fills.single().clientOrderId).isEqualTo("close-delayed")
            assertThat(fills.single().quantity).isEqualByComparingTo("0.10")
        } finally {
            delayedBroker.shutdown()
            localServer.shutdown()
        }
    }

    @Test
    fun `unresolved close succeeds on the scheduled resolution pass`() {
        val closeSent = AtomicBoolean(false)
        val positionReads = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") -> {
                            closeSent.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/get_positions") -> {
                            if (!closeSent.get()) return MockResponse().setBody("[]")
                            if (positionReads.incrementAndGet() <= 4) {
                                MockResponse().setResponseCode(500).setBody("gateway unavailable")
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") -> MockResponse().setBody(closeDealHistory())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val retryingBroker = newFastUnknownOutcomeBroker(periodicResolveMs = 1L)
        captured.clear()
        try {
            retryingBroker.submit(ambiguousClose("close-periodic"))
            awaitCaptured(timeoutMs = 3_000L) {
                captured.any { it is BrokerEvent.OrderFilled && it.clientOrderId == "close-periodic" }
            }

            assertThat(positionReads.get()).isGreaterThanOrEqualTo(5)
            assertThat(
                captured.filterIsInstance<BrokerEvent.OrderFilled>().filter {
                    it.clientOrderId == "close-periodic"
                },
            ).hasSize(1)
        } finally {
            retryingBroker.shutdown()
        }
    }

    @Test
    fun `volume-less partial close removes the marker so the poller books the delta`() {
        val localServer = MockWebServer().also { it.start() }
        val closeSent = AtomicBoolean(false)
        val nowMs = System.currentTimeMillis()
        localServer.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/position_close_partial") -> {
                            closeSent.set(true)
                            MockResponse().setBody(
                                """{"result":{"retcode":10010,"order":0,"deal":55,"price":"1.1050","comment":"partial"}}""",
                            )
                        }
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(
                                if (closeSent.get()) openPosition999(nowMs, "0.04") else openPosition999(nowMs),
                            )
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") ->
                            MockResponse().setBody(closeDealHistory(nowMs, "0.06"))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val localClock = FixedClock(time = 1_700_000_000_000L)
        val localBus = EventBus(localClock, MonotonicSequenceGenerator())
        val fills = java.util.concurrent.CopyOnWriteArrayList<BrokerEvent.OrderFilled>()
        localBus.subscribe<BrokerEvent.OrderFilled>(fills::add)
        val partialBroker =
            MT5Broker(
                profile =
                    MT5DefaultProfiles.exness.copy(
                        gatewayUrl = localServer.url("/").toString().trimEnd('/'),
                        httpTimeoutMs = 100,
                        retryAttempts = 0,
                        pollIntervalMs = 10,
                        instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
                    ),
                bus = localBus,
                clock = localClock,
                recoveryReadBackoffMs = 1L,
            )
        try {
            partialBroker.submit(ambiguousClose("close-partial-delta").copy(partialClose = true))
            val deadline = System.currentTimeMillis() + 3_000L
            while (fills.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)

            assertThat(fills).hasSize(1)
            assertThat(fills.single().quantity).isEqualByComparingTo("0.06")
            assertThat(fills.single().updatesOrderExecution).isFalse()
        } finally {
            partialBroker.shutdown()
            localServer.shutdown()
        }
    }

    @Test
    fun `ambiguous close rejects only after repeated verified non-execution`() {
        val closeSent = AtomicBoolean(false)
        val historyReads = AtomicInteger()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/close_position") -> {
                            closeSent.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway timed out")
                        }
                        path.startsWith("/get_positions") ->
                            MockResponse().setBody(if (closeSent.get()) openPosition999() else "[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/history_deals_get") -> {
                            historyReads.incrementAndGet()
                            MockResponse().setBody("[]")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastBroker = newFastUnknownOutcomeBroker()
        captured.clear()

        fastBroker.submit(ambiguousClose("close-not-executed"))
        awaitCaptured { captured.any { it is BrokerEvent.OrderRejected } }
        fastBroker.shutdown()

        assertThat(historyReads.get()).isEqualTo(4)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>().single().reason)
            .contains("verified retry window")
    }

    @Test
    fun `partial close without reported volume does not latch gateway outage`() {
        bus.subscribe<BrokerEvent.GatewayUnreachable> { captured.add(it) }
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10010,"order":0,"deal":55,"price":"1.1050","comment":"partial"}}""",
            ),
        )

        broker.submit(ambiguousClose("close-partial").copy(partialClose = true))
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }

        assertThat(captured.filterIsInstance<BrokerEvent.GatewayUnreachable>()).isEmpty()
        assertThat(captured.filterIsInstance<BrokerEvent.OrderRejected>()).isEmpty()
    }

    private fun ambiguousClose(id: String): OrderRequest.Market =
        OrderRequest.Market(
            id = id,
            symbol = "EXNESS:EURUSD",
            side = Side.SELL,
            quantity = BigDecimal("0.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 1L,
            strategyId = "s1",
            closesTicket = "999",
        )

    private fun openPosition999(): String =
        """[{"ticket":999,"symbol":"EURUSDm","type":0,"volume":"0.10","price_open":"1.1000",""" +
            """"sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":1700000000000}]"""

    private fun openPosition999(
        timeMs: Long,
        volume: String = "0.10",
    ): String =
        """[{"ticket":999,"symbol":"EURUSDm","type":0,"volume":"$volume","price_open":"1.1000",""" +
            """"sl":"0","tp":"0","profit":"0","magic":10001,"time_msc":$timeMs}]"""

    private fun closeDealHistory(): String =
        """[{"ticket":401,"order":301,"position_id":999,"symbol":"EURUSDm","type":1,"entry":1,""" +
            """"volume":"0.10","price":"1.1050","profit":"50","commission":"-0.1","swap":"0",""" +
            """"fee":"0","magic":10001,"comment":"close","time_msc":1700000000000}]"""

    private fun closeDealHistory(
        timeMs: Long,
        volume: String = "0.10",
    ): String =
        """[{"ticket":401,"order":301,"position_id":999,"symbol":"EURUSDm","type":1,"entry":1,""" +
            """"volume":"$volume","price":"1.1050","profit":"50","commission":"-0.1","swap":"0",""" +
            """"fee":"0","magic":10001,"comment":"close","time_msc":$timeMs}]"""

    @Test
    fun `modifyPosition posts to modify_sl_tp and reports accepted`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":0,"price":"0","comment":"ok"}}""",
            ),
        )
        val ack = broker.modifyPosition("424242", sl = BigDecimal("1.0950"), tp = BigDecimal("1.1100"))
        assertThat(ack.accepted).isTrue
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/modify_sl_tp")
        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.body.readUtf8()).isEqualTo("""{"position":424242,"sl":1.0950,"tp":1.1100}""")
    }

    @Test
    fun `modifyPositionAsync reports venue completion through callback`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":0,"deal":0,"price":"0","comment":"ok"}}""",
            ),
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        val accepted = AtomicBoolean(false)

        broker.modifyPositionAsync("424242", sl = BigDecimal("1.0950"), tp = BigDecimal("1.1100")) { ack ->
            accepted.set(ack.accepted)
            latch.countDown()
        }

        assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue
        assertThat(accepted.get()).isTrue
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        assertThat(server.takeRequest().path).isEqualTo("/modify_sl_tp")
    }

    @Test
    fun `bracket submit includes sl tp in payload`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
        val entry =
            OrderRequest.Market(
                id = "ent-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                entry = entry,
                takeProfit = BigDecimal("1.1500"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.0500")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(bracket)
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed (Phase 26d)
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"sl\":1.05")
        assertThat(body).contains("\"tp\":1.15")
    }

    @Test
    fun `submit pending stop emits accepted but defers filled`() {
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":42,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Stop(
                id = "stop-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isTrue
        awaitCaptured { captured.size >= 1 }
        // OrderAccepted but no OrderFilled — pending fills arrive via the position poller in Phase 26c.
        assertThat(captured).hasSize(1)
        assertThat(captured[0]).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
    }

    @Test
    fun `already-crossed BUY stop submits MARKET at the ask and logs the conversion`() {
        prices.update(
            Tick(
                symbol = "EXNESS:EURUSD",
                price = BigDecimal("1.1049"),
                timestamp = 1L,
                bid = BigDecimal("1.1048"),
                ask = BigDecimal("1.1051"),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1052","comment":"ok"}}""",
            ),
        )
        val logger = LoggerFactory.getLogger(MT5Broker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            broker.submit(
                OrderRequest.Stop(
                    id = "crossed-buy",
                    symbol = "EXNESS:EURUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.1"),
                    stopPrice = BigDecimal("1.1050"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "s1",
                ),
            )
            awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }

            repeat(3) { server.takeRequest() }
            val body = server.takeRequest().body.readUtf8()
            assertThat(body).contains("\"type\":\"BUY\"")
            assertThat(body).doesNotContain("BUY_STOP")
            assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
            assertThat(appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage })
                .anyMatch {
                    it.contains("crossed-buy") &&
                        it.contains("stop_price=1.1050") &&
                        it.contains("market_price=1.1051") &&
                        it.contains("MARKET")
                }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `already-crossed SELL stop submits MARKET at the bid`() {
        prices.update(
            Tick(
                symbol = "EXNESS:EURUSD",
                price = BigDecimal("1.1051"),
                timestamp = 1L,
                bid = BigDecimal("1.1049"),
                ask = BigDecimal("1.1052"),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1048","comment":"ok"}}""",
            ),
        )

        broker.submit(
            OrderRequest.Stop(
                id = "crossed-sell",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            ),
        )

        repeat(3) { server.takeRequest() }
        assertThat(server.takeRequest().body.readUtf8()).contains("\"type\":\"SELL\"")
    }

    @Test
    fun `uncrossed stop remains native pending`() {
        prices.update(
            Tick(
                symbol = "EXNESS:EURUSD",
                price = BigDecimal("1.1048"),
                timestamp = 1L,
                bid = BigDecimal("1.1047"),
                ask = BigDecimal("1.1049"),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":42,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )

        broker.submit(
            OrderRequest.Stop(
                id = "resting-buy",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            ),
        )

        repeat(3) { server.takeRequest() }
        assertThat(server.takeRequest().body.readUtf8()).contains("\"type\":\"BUY_STOP\"")
    }

    @Test
    fun `already-crossed StopLimit submits LIMIT at its limit price`() {
        prices.update(
            Tick(
                symbol = "EXNESS:EURUSD",
                price = BigDecimal("1.1051"),
                timestamp = 1L,
                bid = BigDecimal("1.1050"),
                ask = BigDecimal("1.1052"),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":43,"deal":0,"price":"1.1040","comment":"ok"}}""",
            ),
        )

        broker.submit(
            OrderRequest.StopLimit(
                id = "crossed-stop-limit",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                limitPrice = BigDecimal("1.1040"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                expiresAt = 1_800_000_000_000L,
            ),
        )

        repeat(3) { server.takeRequest() }
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"BUY_LIMIT\"")
        assertThat(body).contains("\"price\":1.104")
        assertThat(body).contains("\"expiration\":1800000000")
        assertThat(body).doesNotContain("BUY_STOP_LIMIT")
    }

    @Test
    fun `already-crossed bracket stop submits MARKET with protective legs intact`() {
        prices.update(
            Tick(
                symbol = "EXNESS:EURUSD",
                price = BigDecimal("1.1051"),
                timestamp = 1L,
                bid = BigDecimal("1.1050"),
                ask = BigDecimal("1.1052"),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1052","comment":"ok"}}""",
            ),
        )
        val entry =
            OrderRequest.Stop(
                id = "crossed-bracket-entry",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )

        broker.submit(
            OrderRequest.Bracket(
                id = "crossed-bracket",
                symbol = entry.symbol,
                side = entry.side,
                quantity = entry.quantity,
                entry = entry,
                takeProfit = BigDecimal("1.1080"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.1020")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }

        repeat(3) { server.takeRequest() }
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"type\":\"BUY\"")
        assertThat(body).contains("\"sl\":1.102")
        assertThat(body).contains("\"tp\":1.108")
        assertThat(body).doesNotContain("BUY_STOP")
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>()).hasSize(1)
    }

    @Test
    fun `failed cancel retains ticket attribution for a racing fill`() {
        broker.shutdown()
        val ticket = 7002L
        val positionOpened = AtomicBoolean(false)
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        request.method == "DELETE" && path == "/orders/$ticket" -> {
                            positionOpened.set(true)
                            MockResponse().setResponseCode(500).setBody("gateway restart")
                        }
                        request.method == "GET" && path.startsWith("/orders") ->
                            if (positionOpened.get()) {
                                MockResponse().setBody("[]")
                            } else {
                                MockResponse().setBody(
                                    """[{"ticket":$ticket,"symbol":"EURUSDm","type":"BUY_STOP","volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","magic":10001,""" +
                                        """"time_setup":1,"time_expiration":0,"comment":"cancel-race"}]""",
                                )
                            }
                        request.method == "GET" && path.startsWith("/get_positions") ->
                            if (positionOpened.get()) {
                                MockResponse().setBody(
                                    """[{"ticket":$ticket,"symbol":"EURUSDm","type":0,"volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"time_msc":1,"comment":"cancel-race"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        request.method == "POST" && path == "/order" ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 25,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1L))
        captured.clear()
        fastBroker.submit(
            OrderRequest.Stop(
                id = "cancel-race",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
        fastBroker.cancel("cancel-race")
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        fastBroker.shutdown()

        val fill = captured.filterIsInstance<BrokerEvent.OrderFilled>().single()
        assertThat(fill.clientOrderId).isEqualTo("cancel-race")
        assertThat(fill.strategyId).isEqualTo("s1")
        assertThat(captured.filterIsInstance<BrokerEvent.OrderCancelled>()).isEmpty()
    }

    @Test
    fun `confirmed cancel releases ticket tracking and publishes strategy attribution`() {
        val ticket = 7003L
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"0","comment":"placed"}}""",
            ),
        )
        broker.submit(
            OrderRequest.Stop(
                id = "cancel-confirmed",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            ),
        )
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
        server.enqueue(MockResponse().setBody("""{"message":"Order cancelled successfully"}"""))

        broker.cancel("cancel-confirmed")
        awaitCaptured { captured.any { it is BrokerEvent.OrderCancelled } }

        val cancelled = captured.filterIsInstance<BrokerEvent.OrderCancelled>().single()
        assertThat(cancelled.clientOrderId).isEqualTo("cancel-confirmed")
        assertThat(cancelled.brokerOrderId).isEqualTo(ticket.toString())
        assertThat(cancelled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `pending fill propagates via position poller (phase 26c)`() {
        // Use a fresh broker with a fast poll interval so the test can observe the open detection.
        broker.shutdown()

        // Route /positions and /orders independently so the two pollers don't race on a FIFO queue.
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":777,"symbol":"EURUSDm","type":0,"volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"time_msc":"1700000000","comment":"stop-26c"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":777,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1_700_000_000_000L))

        val req =
            OrderRequest.Stop(
                id = "stop-26c",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(req)
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)

        // Now flip the dispatcher so /positions returns the new position. The poller
        // observes the open and emits OrderFilled with the original clientOrderId.
        positionsHasFill = true

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "stop-26c" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "stop-26c" }
                ?: error("OrderFilled with clientOrderId=stop-26c never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo("777")
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.side).isEqualTo(Side.BUY)
        assertThat(filled.price).isEqualByComparingTo("1.1050")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `pending disappearing without becoming a position emits OrderCancelled (phase 26d)`() {
        broker.shutdown()

        // Use a path-routing dispatcher so /positions and /orders responses don't conflict.
        // The default queue is FIFO across all paths, which doesn't model the gateway.
        var ordersHasTicket = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> {
                            if (ordersHasTicket) {
                                MockResponse().setBody(
                                    """[{"ticket":999,"symbol":"EURUSDm","type":"BUY_STOP","volume":"0.1",""" +
                                        """"price_open":"1.1050","sl":"0","tp":"0","magic":10001,""" +
                                        """"time_setup":"1700000000","time_expiration":"0","comment":"stop-26d-cancel"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":999,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> captured.add(e) }
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1_700_000_000_000L))

        val req =
            OrderRequest.Stop(
                id = "stop-26d-cancel",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(req)
        // Pending poller will tick. First make /orders return the ticket so the poller's
        // snapshot picks it up. Wait one poll-cycle, then flip to empty so the next tick
        // sees the disappearance.
        ordersHasTicket = true
        Thread.sleep(300)
        ordersHasTicket = false

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderCancelled && it.clientOrderId == "stop-26d-cancel" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val cancelled =
            captured.filterIsInstance<BrokerEvent.OrderCancelled>().firstOrNull {
                it.clientOrderId == "stop-26d-cancel"
            }
                ?: error("OrderCancelled with clientOrderId=stop-26d-cancel never published; captured=$captured")
        assertThat(cancelled.brokerOrderId).isEqualTo("999")
        assertThat(cancelled.reason).contains("external or gtd-expired")
    }

    @Test
    fun `modify a working pending order sends new trigger price (phase 26d)`() {
        // Place a pending first so pendingTickets has an entry
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":555,"deal":0,"price":"1.1050","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Stop(
                id = "stop-modify",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(req)
        // Placement is async; wait for the accept so the venue ticket is registered before modify.
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }

        // Now modify
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":555,"deal":0,"price":"1.1075","comment":"ok"}}""",
            ),
        )
        val ack = broker.modify("stop-modify", com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.1075")))
        assertThat(ack.accepted).isTrue
        assertThat(ack.brokerOrderId).isEqualTo("555")

        // Consume setup + placement requests, then assert the modify wire shape
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        server.takeRequest() // POST /order placement
        val modifyRequest = server.takeRequest()
        assertThat(modifyRequest.path).isEqualTo("/orders/555")
        assertThat(modifyRequest.method).isEqualTo("PUT")
        assertThat(modifyRequest.body.readUtf8()).contains("\"price\":1.1075")
    }

    @Test
    fun `modify with unknown order id is rejected without HTTP call`() {
        val ack = broker.modify("unknown", com.qkt.broker.OrderModification(newStopPrice = BigDecimal("1.0")))
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("no working order")
    }

    @Test
    fun `OCO leg fill propagates via position poller with the leg's clientOrderId`() {
        // Regression test for the v0.26.6 fix. Before that fix, submitComposite never
        // registered per-leg tickets in pendingByTicket, so when an OCO leg filled the
        // position poller's onPendingPositionOpened callback silently returned and the
        // strategy never received the OrderFilled event.
        broker.shutdown()
        val leg1Ticket = 7001L
        val leg2Ticket = 7002L
        val sentOrders =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":$leg2Ticket,"symbol":"EURUSDm","type":1,"volume":"0.1",""" +
                                        """"price_open":"1.0950","sl":"0","tp":"0","profit":"0","magic":10001,""" +
                                        """"time_msc":"1700000000","comment":"oco:oco-leg-fill/sell-leg"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            val n = sentOrders.incrementAndGet()
                            val ticket = if (n == 1) leg1Ticket else leg2Ticket
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"1.0950","comment":"ok"}}""",
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1L))

        val buyLeg =
            OrderRequest.Stop(
                id = "buy-leg",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.1050"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val sellLeg =
            OrderRequest.Stop(
                id = "sell-leg",
                symbol = "EXNESS:EURUSD",
                side = Side.SELL,
                quantity = BigDecimal("0.1"),
                stopPrice = BigDecimal("1.0950"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco-leg-fill",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                leg1 = buyLeg,
                leg2 = sellLeg,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(oco)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)

        // Now flip the dispatcher so the SELL leg's position appears. The poller must
        // resolve ticket 7002 back to clientOrderId "sell-leg" — not the parent "oco-leg-fill".
        positionsHasFill = true

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "sell-leg" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "sell-leg" }
                ?: error("OrderFilled for sell-leg never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo(leg2Ticket.toString())
        assertThat(filled.side).isEqualTo(Side.SELL)
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `price fields are rounded to profile digits before placement`() {
        // XAUUSD has digits=3. An 8-decimal wire price like 4562.16412345 must hit
        // MT5 as 4562.164 — anything more precise gets retcode=10015 INVALID_PRICE.
        broker.shutdown()
        val placedBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") -> {
                            placedBodies.add(request.body.readUtf8())
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"4562.164","comment":"ok"}}""",
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val xauProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:XAUUSD" to TEST_XAUUSD_SPEC),
            )
        val xauBroker = MT5Broker(xauProfile, bus, FixedClock(time = 1L))
        val bracket =
            OrderRequest.Bracket(
                id = "br-xau",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "ent-stop",
                        symbol = "EXNESS:XAUUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("4562.16412345"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("4574.16412345"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4544.16412345")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        xauBroker.submit(bracket)
        // Placement is async; wait for the wire to land before shutting the broker down.
        awaitCaptured { placedBodies.isNotEmpty() }
        xauBroker.shutdown()
        assertThat(placedBodies).hasSize(1)
        val body = placedBodies[0]
        assertThat(body).contains("\"price\":4562.164")
        assertThat(body).contains("\"sl\":4544.164")
        assertThat(body).contains("\"tp\":4574.164")
        assertThat(body).doesNotContain("4562.16412345")
    }

    @Test
    fun `volume is quantized down to profile volumeStep before placement`() {
        // 0.1944... lots at step 0.01 must hit the wire as 0.19 — the pa-quant /
        // hedge-straddle sizing footgun that crashed live 02:55 / 09:55 placements.
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":11,"deal":0,"price":"1.1234","volume":"0.19","comment":"ok"}}""",
            ),
        )
        val req =
            OrderRequest.Market(
                id = "ord-quant",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.19444444"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        broker.submit(req)
        server.takeRequest() // state recovery
        server.takeRequest() // position poller seed
        server.takeRequest() // pending poller seed
        val body = server.takeRequest().body.readUtf8()
        assertThat(body).contains("\"volume\":0.19")
        assertThat(body).doesNotContain("0.1944")
        awaitCaptured { captured.any { it is BrokerEvent.OrderFilled } }
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>().single().quantity)
            .isEqualByComparingTo("0.19")
    }

    @Test
    fun `volume below volumeMin is rejected without HTTP placement`() {
        val req =
            OrderRequest.Market(
                id = "ord-tiny",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.005"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("below venue volumeMin")
        val rejection =
            captured
                .filterIsInstance<BrokerEvent.OrderRejected>()
                .firstOrNull { it.clientOrderId == "ord-tiny" }
        assertThat(rejection).isNotNull
    }

    @Test
    fun `volume above volumeMax is rejected without HTTP placement`() {
        val req =
            OrderRequest.Market(
                id = "ord-oversized",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("100.01"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )

        val ack = broker.submit(req)

        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("above venue volumeMax")
        assertThat(
            captured.filterIsInstance<BrokerEvent.OrderRejected>().any { it.clientOrderId == "ord-oversized" },
        ).isTrue()
    }

    @Test
    fun `bracket with SL too close to entry is rejected pre-placement`() {
        // Configure an override with tradeStopsLevelPoints=100 and pointSize=0.001.
        // Min SL distance: 100 × 0.001 = 0.1.
        // Entry 4561.000, SL 4560.95 → distance 0.05 → reject.
        broker.shutdown()
        val tightProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides =
                    mapOf(
                        "EXNESS:XAUUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.001"),
                                digits = 3,
                                tradeStopsLevelPoints = 100,
                            ),
                    ),
            )
        val tightBroker = MT5Broker(tightProfile, bus, FixedClock(time = 1L))
        val entry =
            OrderRequest.Stop(
                id = "ent",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                stopPrice = BigDecimal("4561.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val bracket =
            OrderRequest.Bracket(
                id = "br-tight",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry = entry,
                takeProfit = BigDecimal("4561.500"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("4560.950")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = tightBroker.submit(bracket)
        tightBroker.shutdown()
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).contains("sl too close to entry")
    }

    @Test
    fun `gateway symbol_info is fetched and cached when no override is configured`() {
        // Fresh broker WITHOUT instrumentOverrides so the broker has to call /symbol_info.
        broker.shutdown()
        var symbolInfoHits = 0
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/symbol_info/") -> {
                            symbolInfoHits++
                            MockResponse().setBody(
                                """{"ask":1.1,"bid":1.0999,"digits":5,"point":0.00001,""" +
                                    """"trade_stops_level":0,"volume_min":0.01,"volume_step":0.01}""",
                            )
                        }
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fetchProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        val fetchBroker = MT5Broker(fetchProfile, bus, FixedClock(time = 1L))
        repeat(3) { i ->
            fetchBroker.submit(
                OrderRequest.Market(
                    id = "f-$i",
                    symbol = "EXNESS:EURUSD",
                    side = Side.BUY,
                    quantity = BigDecimal("0.19444"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 1L,
                    strategyId = "s1",
                ),
            )
        }
        fetchBroker.shutdown()
        // Three placements but a single /symbol_info fetch — the cache held.
        assertThat(symbolInfoHits).isEqualTo(1)
    }

    @Test
    fun `pending-entry Bracket does not emit synchronous OrderFilled at submit`() {
        // Regression: hedge-straddle wraps each OCO leg in a Bracket over a STOP entry.
        // submitSingle previously treated ANY Bracket as instant-fill (Market-style),
        // publishing a phantom OrderFilled at placement time. That phantom fill marked
        // the OCO siblings FILLED before either was actually dispatched to MT5, so the
        // sibling-cancel path turned into a local state flip — MT5 was never told to
        // cancel the opposing leg and the strategy ran as a hedge rather than an OCO.
        broker.shutdown()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> MockResponse().setBody("[]")
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":9001,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val bracket =
            OrderRequest.Bracket(
                id = "br-pending",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "br-pending-entry",
                        symbol = "EXNESS:EURUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("1.1050"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("1.1080"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.1020")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val pendingBrokerProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val pendingBroker = MT5Broker(pendingBrokerProfile, bus, FixedClock(time = 1L))
        captured.clear()
        val ack = pendingBroker.submit(bracket)
        // Await the async accept before shutting the broker (and its HTTP client) down.
        awaitCaptured { captured.any { it is BrokerEvent.OrderAccepted } }
        pendingBroker.shutdown()

        assertThat(ack.accepted).isTrue
        assertThat(captured.filterIsInstance<BrokerEvent.OrderAccepted>()).hasSize(1)
        assertThat(captured.filterIsInstance<BrokerEvent.OrderFilled>())
            .withFailMessage(
                "pending-entry Bracket must NOT publish OrderFilled at placement — " +
                    "the gateway only acknowledged the pending order, not a fill",
            ).isEmpty()
    }

    @Test
    fun `pending-entry Bracket emits OrderFilled with bracket-id when position appears`() {
        // Once the pending STOP entry triggers on MT5, the position poller observes a
        // new ticket and the broker must surface that as an OrderFilled keyed by the
        // Bracket's clientOrderId — which is how the OCO sibling lookup in OrderManager
        // resolves the opposing leg to cancel.
        broker.shutdown()
        val ticket = 9101L
        var positionsHasFill = false
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> {
                            if (positionsHasFill) {
                                MockResponse().setBody(
                                    """[{"ticket":$ticket,"symbol":"EURUSDm","type":0,"volume":"0.10",""" +
                                        """"price_open":"1.1050","sl":"1.1020","tp":"1.1080","profit":"0",""" +
                                        """"magic":10001,"time_msc":"1700000000","comment":"br-pending"}]""",
                                )
                            } else {
                                MockResponse().setBody("[]")
                            }
                        }
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/order") ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":$ticket,"deal":0,"price":"1.1050","comment":"ok"}}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        val fastProfile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100,
                instrumentOverrides = mapOf("EXNESS:EURUSD" to TEST_EURUSD_SPEC),
            )
        val fastBroker = MT5Broker(fastProfile, bus, FixedClock(time = 1L))
        val bracket =
            OrderRequest.Bracket(
                id = "br-pending",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.10"),
                entry =
                    OrderRequest.Stop(
                        id = "br-pending-entry",
                        symbol = "EXNESS:EURUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.10"),
                        stopPrice = BigDecimal("1.1050"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1L,
                        strategyId = "s1",
                    ),
                takeProfit = BigDecimal("1.1080"),
                stopLoss = StopLossSpec.Fixed(BigDecimal("1.1020")),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        captured.clear()
        fastBroker.submit(bracket)
        // Drop anything produced at submit time — we want to assert the fill that arrives
        // AFTER the venue reports the pending triggered, not the historical phantom fill.
        captured.clear()
        positionsHasFill = true

        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline &&
            captured.none { it is BrokerEvent.OrderFilled && it.clientOrderId == "br-pending" }
        ) {
            Thread.sleep(50)
        }
        fastBroker.shutdown()

        val filled =
            captured.filterIsInstance<BrokerEvent.OrderFilled>().firstOrNull { it.clientOrderId == "br-pending" }
                ?: error("OrderFilled for bracket-id never published; captured=$captured")
        assertThat(filled.brokerOrderId).isEqualTo(ticket.toString())
        assertThat(filled.side).isEqualTo(Side.BUY)
        assertThat(filled.symbol).isEqualTo("EXNESS:EURUSD")
        assertThat(filled.strategyId).isEqualTo("s1")
    }

    @Test
    fun `IfTouched is rejected since DSL surface and translator both miss it`() {
        val req =
            OrderRequest.IfTouched(
                id = "it-1",
                symbol = "EXNESS:EURUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                triggerPrice = BigDecimal("1.1050"),
                onTrigger = com.qkt.execution.TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
                strategyId = "s1",
            )
        val ack = broker.submit(req)
        assertThat(ack.accepted).isFalse
        assertThat(ack.rejectReason).containsIgnoringCase("does not translate")
    }

    companion object {
        private val TEST_EURUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.00001"),
                digits = 5,
                tradeStopsLevelPoints = 0,
                maxVolume = BigDecimal("100"),
            )

        private val TEST_XAUUSD_SPEC =
            InstrumentSpec(
                minVolume = BigDecimal("0.01"),
                volumeStep = BigDecimal("0.01"),
                pointSize = BigDecimal("0.001"),
                digits = 3,
                tradeStopsLevelPoints = 0,
            )
    }
}
