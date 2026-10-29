package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.dsl.StrategySource
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Every way a daemon starts a strategy sends its source text on `strategy.started`, once per sha. */
class StrategySourceInsightsTest {
    private class IdleSource : MarketSource {
        override val name: String = "Idle"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private val gate = CountDownLatch(1)

                override fun next(): Tick? {
                    gate.await(30, TimeUnit.SECONDS)
                    return null
                }

                override fun close() = gate.countDown()
            }
    }

    private val bodies = StringBuffer()
    private val server =
        MockWebServer().also {
            it.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        bodies.append(request.body.readUtf8())
                        return MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")
                    }
                }
            it.start(InetAddress.getByName("127.0.0.1"), 0)
        }
    private val sink =
        InsightsSink(
            url = "http://127.0.0.1:${server.port}/ingest",
            token = "secret",
            instanceId = "qkt-test",
            flushIntervalMs = 20L,
        )

    @AfterEach
    fun stop() {
        sink.close()
        server.shutdown()
    }

    private fun awaitBodies(until: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !until(bodies.toString())) Thread.sleep(20)
        return bodies.toString()
    }

    private fun jsonText(text: String) = text.replace("\n", "\\n")

    @Test
    fun `a deployed strategy states its source text and the sha of its file`(
        @TempDir tmp: Path,
    ) {
        val file = Path.of("src/test/resources/cli/valid_strategy.qkt")
        val source = StrategySource.read(file)!!
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.toString()),
                marketSourceProvider = { IdleSource() },
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
            )
        val handle = factory.create("alpha", file, false)
        try {
            val all = awaitBodies { it.contains("strategy.started") }
            assertThat(all).contains(""""sourceSha256":"${source.sha256}"""")
            assertThat(all).contains(""""sourceText":"${jsonText(Files.readString(file))}"""")
            assertThat(all).contains(""""sourceTruncated":false""")
        } finally {
            handle.close()
        }
    }

    @Test
    fun `portfolio children state their own files, and a redeploy at the same sha sends only the sha`(
        @TempDir tmp: Path,
    ) {
        val portfolio = Path.of("src/test/resources/dsl/portfolio_weighted.qkt")
        val childText = jsonText(Files.readString(Path.of("src/test/resources/dsl/weighted_child_a.qkt")))
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.toString()),
                marketSourceProvider = { IdleSource() },
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
            )

        val first = deployer.deploy("weighted_book", PortfolioLoader.load(portfolio))
        awaitBodies { it.split("\"sourceText\"").size > 2 }
        first.supervisor.stop()
        first.children.forEach { it.close() }
        val second = deployer.deploy("weighted_book", PortfolioLoader.load(portfolio))
        try {
            val all = awaitBodies { it.split("\"sourceTextOmitted\":true").size > 2 }
            assertThat(all.split(childText).size - 1).isEqualTo(1)
            assertThat(all.split("\"sourceTextOmitted\":true").size - 1).isEqualTo(2)
            assertThat(all.split("\"sourceText\":").size - 1).isEqualTo(2)
        } finally {
            second.supervisor.stop()
            second.children.forEach { it.close() }
        }
    }
}
