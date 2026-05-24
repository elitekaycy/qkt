package com.qkt.marketdata.source

import com.qkt.marketdata.CsvTickFeed
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplayMarketSourceTest {
    private fun writeCsv(
        path: Path,
        rows: List<String>,
    ) {
        val all = listOf(CsvTickFeed.EXPECTED_HEADER) + rows
        Files.writeString(path, all.joinToString("\n") + "\n")
    }

    private fun row(
        ts: Long,
        symbol: String,
        price: String,
    ): String = "$ts,$symbol,$price,,,,,"

    @Test
    fun `liveTicks streams matching symbol rows in CSV order`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("ticks.csv")
        writeCsv(
            path,
            listOf(
                row(1_000L, "BACKTEST:BTCUSDT", "100.00"),
                row(2_000L, "BACKTEST:BTCUSDT", "101.00"),
                row(3_000L, "BACKTEST:BTCUSDT", "102.00"),
            ),
        )

        val source = ReplayMarketSource(path)
        val feed = source.liveTicks(listOf("BACKTEST:BTCUSDT"))

        val prices = generateSequence { feed.next() }.map { it.price }.toList()
        feed.close()

        assertThat(prices).containsExactly(
            BigDecimal("100.00000000"),
            BigDecimal("101.00000000"),
            BigDecimal("102.00000000"),
        )
    }

    @Test
    fun `liveTicks filters out rows for symbols not requested`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("ticks.csv")
        writeCsv(
            path,
            listOf(
                row(1_000L, "BACKTEST:BTCUSDT", "100.00"),
                row(1_500L, "BACKTEST:ETHUSDT", "3000.00"),
                row(2_000L, "BACKTEST:BTCUSDT", "101.00"),
                row(2_500L, "BACKTEST:ETHUSDT", "3010.00"),
            ),
        )

        val source = ReplayMarketSource(path)
        val feed = source.liveTicks(listOf("BACKTEST:BTCUSDT"))

        val symbols = generateSequence { feed.next() }.map { it.symbol }.toList()
        feed.close()

        assertThat(symbols).containsOnly("BACKTEST:BTCUSDT").hasSize(2)
    }

    @Test
    fun `supports returns true for every symbol`() {
        val source = ReplayMarketSource(Path.of("/dev/null"))
        assertThat(source.supports("ANYTHING")).isTrue
        assertThat(source.supports("BACKTEST:BTCUSDT")).isTrue
    }

    @Test
    fun `capabilities is LIVE_TICKS only`() {
        val source = ReplayMarketSource(Path.of("/dev/null"))
        assertThat(source.capabilities).containsExactly(MarketSourceCapability.LIVE_TICKS)
    }
}
