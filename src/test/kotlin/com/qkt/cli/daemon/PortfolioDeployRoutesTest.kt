package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Deploying and resyncing a portfolio over the control plane with the real [PortfolioDeployer]. */
class PortfolioDeployRoutesTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    private class IdleSource : MarketSource {
        override val name = "idle"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

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

    private fun deployer() = PortfolioDeployer(stateDir = stateDir, marketSourceProvider = { IdleSource() })

    private fun book(child: String = "child.qkt"): Path {
        Files.writeString(
            tmp.resolve("child.qkt"),
            """
            STRATEGY child VERSION 1
            SYMBOLS
                x = BACKTEST:X EVERY 1m
            RULES
                WHEN x.close > 0 THEN BUY x SIZING 1
            """.trimIndent(),
        )
        return tmp.resolve("book-${child.hashCode()}.qkt").also {
            Files.writeString(
                it,
                """
                PORTFOLIO book VERSION 1 CAPITAL 10000
                SYMBOLS
                    x = BACKTEST:X EVERY 1m
                IMPORT '$child' AS child
                RULES
                    RUN child WEIGHT 1
                """.trimIndent(),
            )
        }
    }

    private fun body(
        name: String,
        file: Path,
        dryRun: Boolean = false,
    ) = """{"file":"$file","name":"$name","dryRun":$dryRun}"""

    @Test
    fun `a portfolio deploys resyncs and stops through the control plane`() {
        val registry = registry(stateDir)
        val p = plane(registry, stateDir, portfolioDeployer = deployer())
        val file = book()

        val (code, deployed) = post(p, "/deploy", body("book", file))
        val (planCode, plan) = post(p, "/resync", body("book", file, dryRun = true))
        val (resyncCode, resynced) = post(p, "/resync", body("book", file))
        val (childCode, childStatus) = get(p, "/status/book/child")
        val (stopCode, _) = post(p, "/stop/book?flatten=false")

        assertThat(code).isEqualTo(200)
        assertThat(
            deployed,
        ).contains("\"kind\":\"portfolio\"", "\"alias\":\"child\"", "\"name\":\"book/child\"", "\"hold\":false")
        assertThat(planCode).isEqualTo(200)
        assertThat(plan).contains("\"state\":\"planned\"", "\"affected\":[\"book\"]")
        assertThat(resyncCode).isEqualTo(200)
        assertThat(resynced).contains("\"state\":\"running\"", "\"affected\":[\"book\",\"book/child\"]")
        assertThat(childCode).isEqualTo(200)
        assertThat(childStatus).contains("\"strategy\":\"book/child\"", "\"kind\":\"child\"", "\"parent\":\"book\"")
        assertThat(stopCode).isEqualTo(200)
        assertThat(registry.getPortfolio("book")).isNull()
    }

    @Test
    fun `a portfolio whose child cannot load is not registered`() {
        val registry = registry(stateDir)
        val p = plane(registry, stateDir, portfolioDeployer = deployer())

        val (code, reply) = post(p, "/deploy", body("book", book(child = "missing.qkt")))

        assertThat(code).isIn(400, 409, 500)
        assertThat(reply).contains("\"error\":")
        assertThat(registry.getPortfolio("book")).isNull()
    }

    @Test
    fun `a portfolio resync whose new version cannot load keeps answering an error`() {
        val registry = registry(stateDir)
        val p = plane(registry, stateDir, portfolioDeployer = deployer())
        post(p, "/deploy", body("book", book()))

        val (code, reply) = post(p, "/resync", body("book", book(child = "missing.qkt")))
        post(p, "/stop/book?flatten=false")

        assertThat(code).isIn(400, 409, 500)
        assertThat(reply).contains("\"error\":")
    }

    @Test
    fun `without a deployer portfolios are refused as not configured`() {
        val registry = registry(stateDir)
        registry.registerPortfolio(portfolioRecord(stateDir, mutableListOf(), "held", "a", 1))
        val p = plane(registry, stateDir)

        assertThat(post(p, "/deploy", body("book", book())))
            .isEqualTo(501 to """{"error":"portfolio deploy not configured on this daemon"}""")
        assertThat(post(p, "/resync", body("held", book())))
            .isEqualTo(501 to """{"error":"portfolio resync not configured on this daemon"}""")
    }

    @Test
    fun `a portfolio resync of a standalone or unknown name is refused`() {
        val registry = registry(stateDir).also { it.deploy("solo", tmp.resolve("s.qkt")) }
        val p = plane(registry, stateDir, portfolioDeployer = deployer())

        assertThat(post(p, "/resync", body("solo", book())))
            .isEqualTo(409 to """{"error":"strategy 'solo' is not a portfolio"}""")
        assertThat(post(p, "/resync", body("ghost", book()))).isEqualTo(
            404 to """{"error":"unknown portfolio: ghost"}""",
        )
    }
}
