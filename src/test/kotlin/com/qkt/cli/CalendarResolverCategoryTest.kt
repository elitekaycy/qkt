package com.qkt.cli

import com.qkt.common.TradingCalendar
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A backtest reads a stock's category from its instruments file, so it takes the session the live account trades. */
class CalendarResolverCategoryTest {
    private fun instruments(
        dir: Path,
        vararg entries: Pair<String, String?>,
    ): com.qkt.instrument.YamlInstrumentRegistry {
        val file = dir.resolve("instruments.yaml")
        val body =
            entries.joinToString("") { (symbol, category) ->
                "  - qktSymbol: $symbol\n    contractSize: 100\n    volumeStep: 0.01\n    volumeMin: 0.01\n" +
                    "    pointSize: 0.01\n    digits: 2\n    tradeStopsLevelPoints: 0\n" +
                    (category?.let { "    category: $it\n" } ?: "")
            }
        Files.writeString(file, "instruments:\n$body")
        return com.qkt.instrument.YamlInstrumentRegistry
            .load(file)
    }

    @Test
    fun `an Exness stock with its category takes the stock session, without one it keeps the old default`(
        @TempDir dir: Path,
    ) {
        val cfg = Config.load(dir.resolve("absent.yaml"))
        val registry = instruments(dir, "EXNESS:AAPL" to "Stocks", "EXNESS:MSFT" to null, "EXNESS:EURUSD" to "Forex")
        val calendars = cfg.backtestCalendars(null, registry)
        assertThat(calendars.calendarFor("EXNESS:AAPL").name).isEqualTo("nyse[09:40-15:45]")
        assertThat(calendars.choose("EXNESS:AAPL").source).isEqualTo("exness calendars 'category:Stocks'")
        assertThat(calendars.calendarFor("EXNESS:MSFT")).isSameAs(calendars.calendarFor("EXNESS:EURUSD"))
        assertThat(calendars.calendarFor("EXNESS:BTCUSD")).isEqualTo(TradingCalendar.crypto())
    }

    @Test
    fun `a config ticker rule still wins over the category`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("qkt.config.yaml")
        Files.writeString(
            file,
            "brokers:\n  exness:\n    type: mt5\n    gateway_url: http://gw:5001\n    magic: 7\n" +
                "    calendars:\n      \"AAPL\": nyse\n",
        )
        val registry = instruments(dir, "EXNESS:AAPL" to "Stocks", "EXNESS:NVDA" to "Stocks")
        val calendars = Config.load(file).backtestCalendars(null, registry)
        assertThat(calendars.calendarFor("EXNESS:AAPL")).isEqualTo(TradingCalendar.nyse())
        assertThat(calendars.calendarFor("EXNESS:NVDA").name).isEqualTo("nyse[09:40-15:45]")
    }
}
