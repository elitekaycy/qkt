package com.qkt.parity

import com.qkt.app.BrokerFactory
import com.qkt.broker.mt5.InstrumentSpec
import com.qkt.broker.mt5.MT5Broker
import com.qkt.broker.mt5.MT5DefaultProfiles
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end smoke test for the MT5 daemon path:
 *
 * 1. mt5-gateway is replaced with MockWebServer
 * 2. StrategyHandle.RealFactory is constructed with a broker factory map
 *    that maps "exness" → MT5Broker
 * 3. A strategy declaring `EXNESS:EURUSD` is deployed
 * 4. Ticks driving the strategy's BUY rule are fed via FakeSource
 * 5. We assert the gateway received the translated order and, in the round-trip
 *    case, the close-by-ticket request after the entry fill reached strategy state
 *
 * Validates Phase 17 (broker) + Phase 18 (typed dispatch) work end-to-end via the daemon.
 */
class MT5DaemonE2ETest {
    private lateinit var server: MockWebServer

    private class BoundedFeed(
        private val ticks: List<Tick>,
        private val delayMs: Long = 0L,
        private val endDelayMs: Long = 0L,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i > 0 && i < ticks.size && delayMs > 0L) Thread.sleep(delayMs)
            if (i == ticks.size && endDelayMs > 0L) Thread.sleep(endDelayMs)
            return if (i < ticks.size) ticks[i] else null
        }

        override fun close() = Unit
    }

    private class FakeSource(
        private val ticks: List<Tick>,
        private val delayMs: Long = 0L,
        private val endDelayMs: Long = 0L,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> =
            setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks, delayMs, endDelayMs)
    }

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // Route by path: startup issues a variable number of state reads (recovery,
        // poller seeds, reconcile-with-retry), so a fixed enqueue order is brittle.
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/order") && request.method == "POST" ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":42,"deal":2,"price":"1.1000","volume":"0.1","comment":"ok"}}""",
                            )
                        path.startsWith("/close_position") && request.method == "POST" ->
                            MockResponse().setBody(
                                """{"result":{"retcode":10009,"order":43,"deal":43,"price":"1.1011","volume":"0.1","comment":"closed"}}""",
                            )
                        path.startsWith("/history_deals_get") -> MockResponse().setBody("[]")
                        path.startsWith("/get_positions") || path.startsWith("/orders") ->
                            MockResponse().setBody("[]")
                        path.startsWith("/symbol_info/") ->
                            MockResponse().setBody(
                                """{"ask":"1.1","bid":"1.1","digits":5,"point":"0.00001",""" +
                                    """"trade_stops_level":0,"volume_min":"0.01","volume_step":"0.01",""" +
                                    """"trade_contract_size":"100000"}""",
                            )
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `daemon-deployed strategy with EXNESS prefix routes orders through MT5`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val ticks =
            (0 until 3).map {
                Tick(
                    symbol = "EXNESS:EURUSD",
                    price = BigDecimal("1.10").add(BigDecimal("0.0001").multiply(BigDecimal(it))),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }

        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides =
                    mapOf(
                        "EXNESS:EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 0,
                            ),
                    ),
            )
        val brokerFactories: Map<String, BrokerFactory> =
            mapOf(
                "exness" to { bus, clock, _, _, _ -> MT5Broker(profile, bus, clock) },
            )

        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(ticks) },
                brokerFactories = brokerFactories,
            )
        val file = Path.of("src/test/resources/parity/mt5_e2e_strategy.qkt")
        val handle = factory.create("smoke", file, false)
        try {
            // Wait briefly for ticks to drain through the live session
            Thread.sleep(500)

            // Startup issues a variable number of gateway polls (state recovery, position/pending
            // seeds, and the reconciliation getOpenPositions poll). Drain until the actual /order
            // request appears rather than assuming a fixed count.
            var found: okhttp3.mockwebserver.RecordedRequest? = null
            val deadline = System.currentTimeMillis() + 8_000L
            while (found == null && System.currentTimeMillis() < deadline) {
                val req = server.takeRequest(2, TimeUnit.SECONDS) ?: break
                if (req.path == "/order") found = req
            }
            val orderReq = found
            assertThat(orderReq).isNotNull
            assertThat(orderReq!!.path).isEqualTo("/order")
            val body = orderReq.body.readUtf8()
            assertThat(body).contains("\"symbol\":\"EURUSDm\"")
            assertThat(body).contains("\"magic\":10001")
            assertThat(body).contains("\"type\":\"BUY\"")
            assertThat(body).contains("\"volume\":0.1")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `daemon strategy completes signal order fill and close through MT5`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val baseTs = 1_705_276_800_000L
        val ticks =
            listOf(
                Tick("EXNESS:EURUSD", BigDecimal("1.1000"), baseTs),
                Tick("EXNESS:EURUSD", BigDecimal("1.1000"), baseTs + 60_000L),
                Tick("EXNESS:EURUSD", BigDecimal("1.1011"), baseTs + 120_000L),
                Tick("EXNESS:EURUSD", BigDecimal("1.1011"), baseTs + 180_000L),
            )
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
                instrumentOverrides =
                    mapOf(
                        "EXNESS:EURUSD" to
                            InstrumentSpec(
                                minVolume = BigDecimal("0.01"),
                                volumeStep = BigDecimal("0.01"),
                                pointSize = BigDecimal("0.00001"),
                                digits = 5,
                                tradeStopsLevelPoints = 0,
                            ),
                    ),
            )
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(ticks, delayMs = 250L, endDelayMs = 2_000L) },
                brokerFactories =
                    mapOf(
                        "exness" to { bus, clock, _, _, _ -> MT5Broker(profile, bus, clock) },
                    ),
            )
        val handle =
            factory.create(
                "round-trip",
                Path.of("src/test/resources/parity/mt5_e2e_round_trip.qkt"),
                false,
            )

        try {
            val deadline = System.currentTimeMillis() + 10_000L
            while (handle.tradeCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(25L)

            val requests = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
            while (true) {
                val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: break
                requests.add(request)
            }
            val order = requests.single { it.path == "/order" }
            val close = requests.single { it.path == "/close_position" }

            assertThat(order.body.readUtf8()).contains("\"symbol\":\"EURUSDm\"", "\"type\":\"BUY\"")
            assertThat(close.body.readUtf8()).isEqualTo("""{"position":{"ticket":42,"volume":0.10}}""")
            assertThat(handle.tradeCount).isEqualTo(2)
            assertThat(handle.live.positionsFor(handle.ast.name)).isEmpty()
            assertThat(handle.live.pnlSnapshot(handle.ast.name).realized).isEqualByComparingTo("11.00000000")
        } finally {
            handle.close()
        }
    }
}
