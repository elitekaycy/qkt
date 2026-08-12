package com.qkt.observe.insights

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DecisionOrderLink
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end on the qkt side: a real [LiveSession] trades on paper and the insights
 * sink ships the resulting bus events over real HTTP to a captured collector endpoint.
 */
class LiveSessionInsightsTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        // Every batch the sink sends gets a 200 ack.
        repeat(20) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private class BuyThenSell : DslCompiledStrategy {
        private var ticks = 0
        private var signalIndex = 0
        override val declaredStreams: Map<String, HubKey> = emptyMap()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks = PendingStacks()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) = Unit

        override fun onOrderSubmitted(
            signal: Signal,
            clientOrderId: String,
        ): DecisionOrderLink =
            DecisionOrderLink(
                decisionId = "roundtrip-decision-$signalIndex",
                ruleId = "roundtrip#0",
                signalIndex = signalIndex++,
                orderId = clientOrderId,
            )

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            ticks++
            if (ticks == 1) emit(Signal.Buy(tick.symbol, Money.of("1")))
            if (ticks == 2) emit(Signal.Sell(tick.symbol, Money.of("1")))
        }
    }

    private class TickThenHoldSource : MarketSource {
        override val name = "connected-but-frozen"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)
        val delivered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private val emitted = AtomicBoolean(false)

                override fun next(): Tick? {
                    if (emitted.compareAndSet(false, true)) {
                        delivered.countDown()
                        return Tick("X", Money.of("100"), 1L)
                    }
                    released.await()
                    return null
                }

                override fun close() {
                    released.countDown()
                }
            }
    }

    @Test
    fun `paper session streams signal, order lifecycle and trade to the collector`() {
        val now = Instant.parse("2024-01-15T15:00:00Z")
        val src = InMemoryMarketSource()
        src.seedLive(
            "X",
            listOf(
                Tick("X", Money.of("100"), now.toEpochMilli()),
                Tick("X", Money.of("101"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 1000,
            )
        val session =
            LiveSession(
                strategies = listOf("roundtrip" to BuyThenSell()),
                source = src,
                symbols = listOf("X"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = InsightsEventFamily.entries.toSet(),
                insightsStrategyMetadata =
                    mapOf(
                        "roundtrip" to
                            mapOf(
                                "sourcePath" to "/srv/qkt/strategies/roundtrip.qkt",
                                "dslVersion" to 1,
                                "runtimeMode" to "paper",
                                "symbols" to listOf("X"),
                            ),
                    ),
            )

        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(5))).isTrue()

        // Re-entrant bus dispatch means order.submit reaches the sink AFTER the nested
        // fill and trade, possibly in a later batch — collect until every marker landed.
        val markers =
            listOf(
                "\"type\":\"signal\"",
                "\"type\":\"decision.order_linked\"",
                "\"type\":\"order.submit\"",
                "\"type\":\"trade\"",
                "\"type\":\"fill.accounted\"",
                "\"type\":\"trade.closed\"",
                "\"type\":\"strategy.started\"",
            )
        val bodies = StringBuilder()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val req = server.takeRequest(250, TimeUnit.MILLISECONDS) ?: continue
            assertThat(req.getHeader("Authorization")).isEqualTo("Bearer secret")
            bodies.append(req.body.readUtf8())
            if (markers.all { bodies.contains(it) }) break
        }
        sink.close()

        val all = bodies.toString()
        assertThat(all).contains("\"instanceId\":\"qkt-test\"")
        assertThat(all).contains("\"type\":\"signal\"")
        assertThat(all).contains("\"type\":\"decision.order_linked\"")
        assertThat(all).contains("\"decisionId\":\"roundtrip-decision-0\"")
        assertThat(all).contains("\"type\":\"order.submit\"")
        assertThat(all).contains("\"type\":\"trade\"")
        assertThat(all).contains("\"type\":\"fill.accounted\"")
        assertThat(all).contains("\"sourceFillSequenceId\":")
        assertThat(all).contains("\"orderSchemaVersion\":1")
        assertThat(all).contains("\"type\":\"trade.closed\"")
        assertThat(all).contains("\"type\":\"strategy.started\"")
        assertThat(all).contains("\"sourcePath\":\"/srv/qkt/strategies/roundtrip.qkt\"")
        assertThat(all).contains("\"runtimeMode\":\"paper\"")
        assertThat(all).contains("\"realized\":")
        assertThat(all).contains("\"symbol\":\"X\"")
    }

    @Test
    fun `heartbeat emits per-symbol staleness while a position is open`() {
        val clock = FixedClock(1L)
        val source = TickThenHoldSource()
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 1000,
            )
        val entered = AtomicBoolean(false)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (entered.compareAndSet(false, true)) emit(Signal.Buy(tick.symbol, Money.of("1")))
                }
            }
        val handle =
            LiveSession(
                strategies = listOf("stale-position" to strategy),
                source = source,
                symbols = listOf("X"),
                clock = clock,
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
                scheduleHeartbeatIntervalMs = 5L,
            ).start()

        try {
            assertThat(source.delivered.await(2, TimeUnit.SECONDS)).isTrue()
            val positionDeadline = System.currentTimeMillis() + 2_000L
            while (handle.positionsFor("stale-position").isEmpty() && System.currentTimeMillis() < positionDeadline) {
                Thread.sleep(5L)
            }
            assertThat(handle.positionsFor("stale-position")).isNotEmpty

            clock.advanceTo(20_001L)
            val bodies = StringBuilder()
            val deadline = System.currentTimeMillis() + 2_000L
            while (!bodies.contains("\"type\":\"marketdata.stale\"") && System.currentTimeMillis() < deadline) {
                server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { bodies.append(it.body.readUtf8()) }
            }

            assertThat(bodies.toString()).contains("\"type\":\"marketdata.stale\"")
            assertThat(bodies.toString()).contains("\"source\":\"connected-but-frozen\"")
            assertThat(bodies.toString()).contains("\"symbols\":[\"X\"]")
        } finally {
            handle.stop()
            handle.awaitTermination(Duration.ofSeconds(2))
            sink.close()
        }
    }
}
