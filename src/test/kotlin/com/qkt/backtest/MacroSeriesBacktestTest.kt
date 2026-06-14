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
 * over a self-contained fixture store. The gold ticks sit AFTER the macro's publication instant, so
 * a gold candle closes once the value is knowable; the strategy only buys when the macro value is
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
        // Gold ticks on Tue 2024-01-16 at 13:05..13:29 UTC, spanning several 5m buckets so candles
        // close during the run. They sit after the second macro release (13:00), which closes the
        // first daily macro candle — a daily candle only closes when the next day's value publishes,
        // so its value becomes readable then (conservative: never before it was knowable).
        val goldDir = tmp.resolve("symbols").resolve("XAUUSD")
        Files.createDirectories(goldDir)
        val rows =
            listOf("13:05", "13:11", "13:17", "13:23", "13:29").mapIndexed { i, hm ->
                val ts = Instant.parse("2024-01-16T$hm:00Z").toEpochMilli()
                "$ts,XAUUSD,${2050 + i}.0,1.0,,,,"
            }
        Files.writeString(
            goldDir.resolve("2024-01-16.csv"),
            "timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n" + rows.joinToString("\n") + "\n",
        )
        // Two FRED observations: Fri 2024-01-12 publishes Mon 2024-01-15 13:00; Mon 2024-01-15
        // publishes Tue 2024-01-16 13:00. The second release closes the first daily candle, making
        // its value (1.85) readable for the 2024-01-16 gold candle closes.
        MacroSeriesStore(tmp).write(
            "DFII10",
            listOf(
                MacroPoint(LocalDate.of(2024, 1, 12), BigDecimal("1.85")),
                MacroPoint(LocalDate.of(2024, 1, 15), BigDecimal("1.90")),
            ),
        )
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
                        from = Instant.parse("2024-01-15T00:00:00Z"),
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
        val b = runOnce(tmp)
        assertThat(b.global.tradeCount).isEqualTo(a.global.tradeCount)
    }
}
