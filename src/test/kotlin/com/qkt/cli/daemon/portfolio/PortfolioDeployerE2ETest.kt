package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StateDir
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
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
}
