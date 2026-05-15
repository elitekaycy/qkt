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
 * 5. We assert the gateway received an order POST translated to broker-side
 *    symbol "EURUSDm" with the configured magic
 *
 * Validates Phase 17 (broker) + Phase 18 (typed dispatch) work end-to-end via the daemon.
 */
class MT5DaemonE2ETest {
    private lateinit var server: MockWebServer

    private class BoundedFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            return if (i < ticks.size) ticks[i] else null
        }

        override fun close() = Unit
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> =
            setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks)
    }

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        // recovery + poller seed: empty positions
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        // The order placement
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":1,"deal":2,"price":"1.1234","comment":"ok"}}""",
            ),
        )
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
                "exness" to { bus, clock, _ -> MT5Broker(profile, bus, clock) },
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

            // Drain the recovery + poller-seed requests (state, position seed, pending-order seed)
            server.takeRequest(2, TimeUnit.SECONDS)
            server.takeRequest(2, TimeUnit.SECONDS)
            server.takeRequest(2, TimeUnit.SECONDS)
            // The actual order request
            val orderReq = server.takeRequest(5, TimeUnit.SECONDS)
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
}
