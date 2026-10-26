package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.store.DefaultDataStore
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * End-to-end: a backtest holding gold ticks plus a daily macro series (`MACRO:DFII10`) in one run,
 * over a self-contained fixture store. Gold ticks sit on both sides of the macro's publication instant,
 * across a federal holiday; the strategy only buys when the macro value is
 * published, so a non-zero, deterministic trade count proves the daily series flowed through the
 * source composition, the point-in-time feed, and the candle path into the strategy.
 */
class MacroSeriesBacktestTest {
    private val src =
        """
        STRATEGY macroE2E VERSION 1
        SYMBOLS
            gold    = BACKTEST:XAUUSD EVERY 5m
            real10y = MACRO:DFII10   EVERY 1d
        RULES
            WHEN real10y.value > 0 AND POSITION.gold = 0
            THEN BUY gold SIZING 0.01
        """.trimIndent()

    private fun seed(tmp: Path) {
        // Gold ticks on Mon 2024-01-15 and Tue 2024-01-16 at 21:35..21:59 UTC, spanning several 5m
        // buckets so candles close on both days.
        val goldDir = tmp.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(goldDir)
        for (day in listOf("2024-01-15", "2024-01-16")) {
            val rows =
                listOf("21:35", "21:41", "21:47", "21:53", "21:59").mapIndexed { i, hm ->
                    val ts = Instant.parse("${day}T$hm:00Z").toEpochMilli()
                    "$ts,XAUUSD,${2050 + i}.0,1.0,,,,"
                }
            Files.writeString(
                goldDir.resolve("$day.csv"),
                "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n" + rows.joinToString("\n") + "\n",
            )
        }
        // One FRED observation, Fri 2024-01-12. Monday is Martin Luther King Jr. Day, so the H.15
        // carrying it posts Tuesday 2024-01-16 at 4:15 p.m. New York and the value is visible from
        // 16:30 New York (21:30 UTC). The Monday gold candles must not see it.
        MacroSeriesStore(tmp).write("DFII10", listOf(MacroPoint(LocalDate.of(2024, 1, 12), BigDecimal("1.85"))))
    }

    private fun runOnce(tmp: Path): BacktestResult {
        val strategy = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)
        return Backtest
            .fromStore(
                strategies = listOf("macroE2E" to strategy),
                store = DefaultDataStore(root = tmp),
                request =
                    MarketRequest(
                        symbols = listOf("BACKTEST:XAUUSD", "MACRO:DFII10"),
                        from = Instant.parse("2024-01-12T00:00:00Z"),
                        to = Instant.parse("2024-01-17T00:00:00Z"),
                    ),
                candleWindow = TimeWindow.parse("5m"),
            ).run()
    }

    @Test
    fun `the strategy trades only after the macro value publishes, deterministically`(
        @TempDir tmp: Path,
    ) {
        seed(tmp)
        val a = runOnce(tmp)
        // The buy gate is `real10y.value > 0`; a fill means the published macro value reached the
        // strategy through the composed source + point-in-time feed + candle path.
        assertThat(a.global.tradeCount).isGreaterThan(0)
        val firstFill = a.trades.minOf { it.trade.timestamp }
        assertThat(firstFill).isGreaterThanOrEqualTo(Instant.parse("2024-01-16T21:30:00Z").toEpochMilli())
        val b = runOnce(tmp)
        assertThat(b.global.tradeCount).isEqualTo(a.global.tradeCount)
    }
}
