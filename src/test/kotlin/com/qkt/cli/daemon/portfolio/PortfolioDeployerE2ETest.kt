package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StateDir
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end coverage of [PortfolioDeployer] (#55).
 *
 * Drives the real deploy path with real components — `PortfolioLoader` parses two child
 * strategies, the deployer spins up per-child `LiveSession`s + observability servers, and
 * the test asserts each child shows up with its own port + log file, then verifies cascade
 * stop tears the whole thing down cleanly.
 */
class PortfolioDeployerE2ETest {
    private class BoundedFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val gate = CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks)
    }

    private class RoutedSource(
        private val ticks: List<Tick>,
        private val requestedLiveSymbols: MutableList<List<String>>,
    ) : MarketSource {
        override val name: String = "Routed"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = symbol.startsWith("BACKTEST:")

        override fun liveTicks(symbols: List<String>): TickFeed {
            requestedLiveSymbols.add(symbols)
            require(symbols.isNotEmpty()) { "symbols must not be empty" }
            require(symbols.all(::supports)) { "unrouted live tick symbol(s): $symbols" }
            return BoundedFeed(ticks)
        }
    }

    private class ManualFeed : TickFeed {
        private val queue = LinkedBlockingQueue<Tick>()

        fun offer(tick: Tick) {
            queue.put(tick)
        }

        override fun next(): Tick? {
            val tick = queue.take()
            return tick.takeUnless { it.symbol == CLOSE_SYMBOL }
        }

        override fun close() {
            queue.offer(Tick(CLOSE_SYMBOL, BigDecimal.ONE, Long.MIN_VALUE))
        }

        private companion object {
            const val CLOSE_SYMBOL = "__CLOSE__"
        }
    }

    private fun ticksFor(symbol: String): List<Tick> =
        (0 until 3).map {
            Tick(
                symbol = symbol,
                price = BigDecimal("100.0").add(BigDecimal(it)),
                timestamp = 1_705_276_800_000L + it * 60_000L,
            )
        }

    @Test
    fun `deploy spins up each child with its own port and log file, cascade stop tears down`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols ->
                    // Whichever symbol set the child asks for, route it to a bounded feed
                    // emitting that symbol's synthetic ticks. The portfolio supervisor's
                    // own marketSource is built per-portfolio-level streams; the children
                    // get one source each via this provider.
                    val symbol = symbols.first()
                    FakeSource(ticksFor(symbol))
                },
            )

        val portfolioPath = Path.of("src/test/resources/dsl/portfolio_two_children.qkt")
        val compiled = PortfolioLoader.load(portfolioPath)
        val record = deployer.deploy("two_children", compiled)

        try {
            // ── Each child has its own running session, own port, own log file ──
            assertThat(record.children).hasSize(2)
            val childrenByAlias = record.children.associateBy { it.childMeta?.alias }
            val childA = childrenByAlias["a"] ?: error("child 'a' missing from portfolio")
            val childB = childrenByAlias["b"] ?: error("child 'b' missing from portfolio")

            for (child in listOf(childA, childB)) {
                assertThat(child.isRunning()).`as`("${child.name} should be running").isTrue
                assertThat(child.port).`as`("${child.name} port").isGreaterThan(0)
                assertThat(Files.exists(child.logFile)).`as`("${child.name} log file").isTrue
                assertThat(child.childMeta?.parent).isEqualTo("two_children")
            }

            // ── Ports are distinct per child ──
            assertThat(childA.port).isNotEqualTo(childB.port)

            // ── Observability /status reachable on each child's port ──
            val http = OkHttpClient()
            for (child in listOf(childA, childB)) {
                val resp =
                    http
                        .newCall(Request.Builder().url("http://127.0.0.1:${child.port}/status").build())
                        .execute()
                try {
                    assertThat(resp.code).`as`("${child.name} /status").isEqualTo(200)
                    val body = resp.body!!.string()
                    assertThat(body).`as`("${child.name} /status body").contains("\"strategy\":\"${child.name}\"")
                } finally {
                    resp.close()
                }
            }

            // ── Portfolio supervisor is running ──
            assertThat(record.supervisor.running).isTrue
        } finally {
            // ── Cascade stop: stop supervisor, close every child, verify teardown ──
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }

        assertThat(record.supervisor.running).isFalse
        for (child in record.children) {
            assertThat(child.isRunning()).`as`("${child.name} should be stopped after cascade").isFalse
        }
    }

    @Test
    fun `always-run portfolio does not request a book-level market feed`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val sourceRequests = AtomicInteger(0)
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols ->
                    val request = sourceRequests.incrementAndGet()
                    check(request <= 2) { "unexpected supervisor market feed request for $symbols" }
                    FakeSource(ticksFor(symbols.first()))
                },
            )

        val compiled = PortfolioLoader.load(Path.of("src/test/resources/dsl/portfolio_two_children.qkt"))
        val record = deployer.deploy("two_children", compiled)

        try {
            assertThat(record.children).hasSize(2)
            assertThat(record.supervisor.running).isTrue
            assertThat(sourceRequests.get()).isEqualTo(2)
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }
    }

    @Test
    fun `portfolio child rejects an order above the configured quantity cap`(
        @TempDir tmp: Path,
    ) {
        val child = tmp.resolve("child.qkt")
        Files.writeString(
            child,
            """
            STRATEGY capped_child VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        val portfolio = tmp.resolve("book.qkt")
        Files.writeString(
            portfolio,
            """
            PORTFOLIO capped_book VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
                RUN child
            """.trimIndent(),
        )
        val server = MockWebServer().also { it.start() }
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 1000,
            )
        val feed = ManualFeed()
        val source =
            object : MarketSource {
                override val name = "Manual"
                override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

                override fun supports(symbol: String): Boolean = true

                override fun liveTicks(symbols: List<String>): TickFeed = feed
            }
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { source },
                maxOrderQty = BigDecimal("0.5"),
                marginFloorPct = BigDecimal.ZERO,
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.RISK),
            )
        val record = deployer.deploy("capped_book", PortfolioLoader.load(portfolio))
        ticksFor("BACKTEST:BTCUSDT").forEach(feed::offer)

        try {
            var body = ""
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && !body.contains("risk.rejected")) {
                body +=
                    server
                        .takeRequest(250, TimeUnit.MILLISECONDS)
                        ?.body
                        ?.readUtf8()
                        .orEmpty()
            }
            assertThat(body).contains("\"type\":\"risk.rejected\"")
            assertThat(body).contains("order qty 1 exceeds per-order cap 0.5")
        } finally {
            record.supervisor.stop()
            for (handle in record.children) runCatching { handle.close() }
            sink.close()
            server.shutdown()
        }
    }

    @Test
    fun `weighted portfolio allocates capital times weight to each child's equity`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols -> FakeSource(ticksFor(symbols.first())) },
            )

        val portfolioPath = Path.of("src/test/resources/dsl/portfolio_weighted.qkt")
        val compiled = PortfolioLoader.load(portfolioPath)
        val record = deployer.deploy("weighted_book", compiled)

        try {
            val childrenByAlias = record.children.associateBy { it.childMeta?.alias }
            val childA = childrenByAlias["a"] ?: error("child 'a' missing")
            val childB = childrenByAlias["b"] ?: error("child 'b' missing")

            // ACCOUNT.equity resolves through the same equityFor() that dailySummaryRows
            // reads; with no open position, each child's equity is exactly its allocation.
            val rowsA = childA.live.dailySummaryRows()
            val rowsB = childB.live.dailySummaryRows()
            assertThat(rowsA.first().equity).isEqualByComparingTo(BigDecimal("60000"))
            assertThat(rowsB.first().equity).isEqualByComparingTo(BigDecimal("40000"))
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }
    }

    @Test
    fun `portfolio children subscribe to live ticks with routed qkt symbols`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val requestedLiveSymbols = mutableListOf<List<String>>()
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols ->
                    assertThat(symbols).allMatch { it.startsWith("BACKTEST:") }
                    RoutedSource(ticksFor(symbols.first()), requestedLiveSymbols)
                },
            )

        val compiled = PortfolioLoader.load(Path.of("src/test/resources/dsl/portfolio_two_children.qkt"))
        val record = deployer.deploy("two_children", compiled)

        try {
            assertThat(record.children).hasSize(2)
            assertThat(requestedLiveSymbols)
                .contains(listOf("BACKTEST:BTCUSDT"), listOf("BACKTEST:ETHUSDT"))
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }
    }

    @Test
    fun `portfolio child lifecycle insights include parent alias and allocation metadata`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer().also { it.start() }
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
        val sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 20L,
                queueCapacity = 1000,
            )
        val stateDir = StateDir.resolve(tmp.toString())
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols -> FakeSource(ticksFor(symbols.first())) },
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
            )
        val record =
            deployer.deploy(
                "weighted_book",
                PortfolioLoader.load(
                    Path.of("src/test/resources/dsl/portfolio_weighted.qkt"),
                ),
            )

        try {
            val bodies = StringBuilder()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                val req = server.takeRequest(250, TimeUnit.MILLISECONDS) ?: continue
                bodies.append(req.body.readUtf8())
                if (bodies.contains("weighted_book:a") && bodies.contains("weighted_book:b")) break
            }
            sink.close()
            val all = bodies.toString()
            assertThat(all).contains("\"type\":\"strategy.started\"")
            assertThat(all).contains("\"strategyId\":\"weighted_book:a\"")
            assertThat(all).contains("\"strategyId\":\"weighted_book:b\"")
            assertThat(all).contains("\"kind\":\"portfolio_child\"")
            assertThat(all).contains("\"portfolioId\":\"weighted_book\"")
            assertThat(all).contains("\"portfolioAlias\":\"a\"")
            assertThat(all).contains("\"portfolioWeight\":0.6")
            assertThat(all).contains("\"allocatedCapital\":60000.00000000")
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
            sink.close()
            server.shutdown()
        }
    }

    @Test
    fun `book with drawdown config deploys, runs the risk heartbeat, and stays un-halted with no losses`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val deployer =
            PortfolioDeployer(
                stateDir = stateDir,
                marketSourceProvider = { symbols -> FakeSource(ticksFor(symbols.first())) },
                maxDrawdownPct = BigDecimal("0.08"),
                maxDailyDrawdownPct = BigDecimal("0.04"),
                riskIntervalMs = 20L,
            )

        val compiled = PortfolioLoader.load(Path.of("src/test/resources/dsl/portfolio_weighted.qkt"))
        val record = deployer.deploy("weighted_book", compiled)

        try {
            assertThat(record.supervisor.running).isTrue
            // The risk heartbeat is running (interval 20ms); with no fills the book is flat,
            // so it must not halt any child.
            Thread.sleep(100)
            for (child in record.children) {
                assertThat(child.isRunning()).`as`("${child.name} running").isTrue
                assertThat(child.live.isHalted()).`as`("${child.name} halted").isFalse
            }
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }

        assertThat(record.supervisor.running).isFalse
    }
}
