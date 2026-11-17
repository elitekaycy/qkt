package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A portfolio deploy or resync that fails answers 409 for a conflict and 400 for an invalid request. */
class PortfolioDeployFailureRoutesTest : DaemonRouteFixture() {
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

    private fun deployer() =
        PortfolioDeployer(
            stateDir = stateDir,
            marketSourceProvider = { IdleSource() },
            maxDrawdownPct = BigDecimal("0.1"),
        )

    private fun writeChild() =
        Files.writeString(
            tmp.resolve("child.qkt"),
            "STRATEGY child VERSION 1\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 1\n",
        )

    private fun book(
        file: String,
        capital: String = "CAPITAL 10000",
    ): Path {
        writeChild()
        return tmp.resolve(file).also {
            Files.writeString(
                it,
                "PORTFOLIO book VERSION 1 $capital\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\n" +
                    "IMPORT 'child.qkt' AS child\nRULES\n    RUN child WEIGHT 1\n",
            )
        }
    }

    private fun body(
        name: String,
        file: Path,
    ) = """{"file":"$file","name":"$name"}"""

    @Test
    fun `a deploy the deployer refuses is a bad request`() {
        val p = plane(registry(stateDir), stateDir, portfolioDeployer = deployer())

        val (limitCode, limit) = post(p, "/deploy", body("book", book("nocap.qkt", capital = "")))

        assertThat(limitCode).isEqualTo(400)
        assertThat(limit).contains("portfolio drawdown limits require portfolio CAPITAL")
    }

    @Test
    fun `a resync the deployer refuses is a bad request`() {
        val registry = registry(stateDir)
        val p = plane(registry, stateDir, portfolioDeployer = deployer())
        post(p, "/deploy", body("book", book("book.qkt")))

        val (limitCode, limit) = post(p, "/resync", body("book", book("nocap.qkt", capital = "")))
        post(p, "/stop/book?flatten=false")

        assertThat(limitCode).isEqualTo(400)
        assertThat(limit).contains("portfolio drawdown limits require portfolio CAPITAL")
    }
}
