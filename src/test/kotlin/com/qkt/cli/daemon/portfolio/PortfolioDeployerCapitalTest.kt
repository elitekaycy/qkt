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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A portfolio's book needs capital before its risk limits can apply; regime allocation deploys every child. */
class PortfolioDeployerCapitalTest {
    @TempDir lateinit var tmp: Path

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

    private fun child(name: String) =
        Files.writeString(
            tmp.resolve("$name.qkt"),
            "STRATEGY $name VERSION 1\nSYMBOLS\n    btc = BACKTEST:BTCUSDT EVERY 1h\n" +
                "RULES\n    WHEN btc.close > 0 THEN BUY btc SIZING 1\n",
        )

    private fun portfolio(text: String): Path {
        child("trend")
        child("meanrev")
        return tmp.resolve("book.qkt").also { Files.writeString(it, text.trimIndent()) }
    }

    private val uncapitalised =
        """
        PORTFOLIO book VERSION 1
        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1h
        IMPORT 'trend.qkt' AS trend
        RULES
            RUN trend
        """

    private val state by lazy { StateDir.resolve(tmp.resolve("state").toString()) }

    @Test
    fun `book risk without any capital is refused`() {
        val deployer =
            PortfolioDeployer(
                state,
                { IdleSource() },
                bookRiskConfig =
                    com.qkt.risk.book
                        .BookRiskConfig(),
            )

        assertThatThrownBy { deployer.deploy("book", PortfolioLoader.load(portfolio(uncapitalised))) }
            .hasMessageContaining("book_risk is configured but neither book_risk.capital nor portfolio CAPITAL is set")
    }

    @Test
    fun `drawdown limits without capital are refused`() {
        val deployer = PortfolioDeployer(state, { IdleSource() }, maxDrawdownPct = BigDecimal("10"))

        assertThatThrownBy { deployer.deploy("book", PortfolioLoader.load(portfolio(uncapitalised))) }
            .hasMessageContaining("portfolio drawdown limits require portfolio CAPITAL or book_risk.capital")
    }

    @Test
    fun `a regime weighted book with a rebalance clock deploys each child`() {
        val file =
            portfolio(
                """
                PORTFOLIO book VERSION 1 CAPITAL 10000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'trend.qkt' AS trend
                IMPORT 'meanrev.qkt' AS meanrev
                REGIMES
                    NAME market_regime
                    STATE up WHEN btc.close > 100
                    STATE range DEFAULT
                ALLOCATE
                    METHOD regime_weighted REBALANCE EVERY 1d
                    up -> trend 0.8, meanrev 0.2
                    range -> trend 0.2, cash 0.8
                RULES
                    RUN trend
                    RUN meanrev
                """,
            )

        val record = PortfolioDeployer(state, { IdleSource() }).deploy("book", PortfolioLoader.load(file))

        try {
            assertThat(record.children.map { it.name }).containsExactlyInAnyOrder("book/trend", "book/meanrev")
            assertThat(record.children.map { it.childMeta!!.parent }).containsOnly("book")
        } finally {
            record.supervisor.stop()
            record.children.forEach { runCatching { it.close() } }
        }
    }
}
