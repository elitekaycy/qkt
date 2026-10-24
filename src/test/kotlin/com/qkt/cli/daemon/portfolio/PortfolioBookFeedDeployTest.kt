package com.qkt.cli.daemon.portfolio

import com.qkt.cli.daemon.StateDir
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A book with no streams of its own samples its controller on its children's bars, as its backtest does (#1490). */
class PortfolioBookFeedDeployTest {
    private object Silent : MarketSource {
        override val name = "Silent"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                override fun next() = null
            }
    }

    private fun book(tmp: Path): Path {
        for ((name, symbol, tf) in listOf(
            Triple("fx", "BACKTEST:EURUSD", "4h"),
            Triple("btc", "BACKTEST:BTCUSDT", "1h"),
        )) {
            Files.writeString(
                tmp.resolve("$name.qkt"),
                "STRATEGY ${name}_child VERSION 1\nSYMBOLS\n    s = $symbol EVERY $tf\nRULES\n    WHEN s.close > 0 THEN BUY s SIZING 1\n",
            )
        }
        val book = tmp.resolve("book.qkt")
        Files.writeString(
            book,
            "PORTFOLIO kids VERSION 1 CAPITAL 10000\nIMPORT 'fx.qkt' AS fx\nIMPORT 'btc.qkt' AS btc\nRULES\n    RUN fx WEIGHT 0.5\n    RUN btc WEIGHT 0.5\n",
        )
        return book
    }

    private val calendars =
        mapOf(
            "BACKTEST:EURUSD" to TradingCalendar.fxDefault(),
            "BACKTEST:BTCUSDT" to TradingCalendar.crypto(),
        )

    @Test
    fun `the book feed is every child symbol at the first child's timeframe`(
        @TempDir tmp: Path,
    ) {
        val feed = PortfolioLoader.load(book(tmp)).bookFeed()
        assertThat(feed?.symbols).containsExactly("BACKTEST:EURUSD", "BACKTEST:BTCUSDT")
        assertThat(feed?.window?.canonicalSpec()).isEqualTo("4h")
    }

    @Test
    fun `a children-only book annualizes by the union of its children's sessions`(
        @TempDir tmp: Path,
    ) {
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { Silent },
                calendarFor = { calendars.getValue(it) },
            )
        // Union of FX and crypto at the first child's 4h window: the crypto count, 525960 / 240.
        assertThat(deployer.bookAnnualization(PortfolioLoader.load(book(tmp)))).isEqualByComparingTo("2191.5")
    }

    @Test
    fun `a children-only book with a controller subscribes its supervisor to every child symbol`(
        @TempDir tmp: Path,
    ) {
        val requested = CopyOnWriteArrayList<List<String>>()
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = { symbols ->
                    requested.add(symbols)
                    Silent
                },
                bookRiskConfig =
                    com.qkt.risk.book
                        .BookRiskConfig(
                            allocation =
                                com.qkt.risk.book
                                    .Allocation(),
                        ),
                calendarFor = { calendars.getValue(it) },
            )
        val record = deployer.deploy("kids", PortfolioLoader.load(book(tmp)))
        try {
            assertThat(requested).contains(listOf("BACKTEST:EURUSD", "BACKTEST:BTCUSDT"))
        } finally {
            record.supervisor.stop()
            for (child in record.children) runCatching { child.close() }
        }
    }
}
