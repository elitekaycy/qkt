package com.qkt.parity

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MacroMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.marketdata.store.macro.MacroPoint
import com.qkt.marketdata.store.macro.MacroSeriesStore
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A `MACRO:` series replays each observation at its publication instant in the `--bars` tier, as
 * tick mode and live do (#1503). Daily values on a stream declared `EVERY 7d` used to be smeared
 * across a synthetic week bar, with every later value in the week dropped as late.
 */
class MacroBarsTierTest {
    private val from = Instant.parse("2024-03-04T00:00:00Z")
    private val to = Instant.parse("2024-03-16T00:00:00Z")

    /** Hourly gold ticks, flat, with bars over the same ticks so the bar tier can replay them. */
    private class Tape : MarketSource {
        private val ticks =
            (0 until 12 * 24).map { h ->
                Tick(
                    "BACKTEST:XAUUSD",
                    Money.of("2050"),
                    Instant.parse("2024-03-04T00:30:00Z").toEpochMilli() + h * 3_600_000L,
                )
            }

        override val name = "tape"
        override val capabilities = setOf(MarketSourceCapability.TICKS, MarketSourceCapability.BARS)

        override fun supports(symbol: String) = symbol == "BACKTEST:XAUUSD"

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> =
            ticks.asSequence().filter {
                it.timestamp >= range.from.toEpochMilli() &&
                    it.timestamp < range.to.toEpochMilli()
            }

        override fun bars(
            symbol: String,
            window: TimeWindow,
            range: TimeRange,
        ): Sequence<Candle> =
            ticks(symbol, range)
                .groupBy { it.timestamp / window.durationMs * window.durationMs }
                .map { (start, group) ->
                    val p = group.first().price
                    Candle(symbol, p, p, p, p, Money.ZERO, start, start + window.durationMs)
                }.asSequence()
    }

    private val strategy =
        """
        STRATEGY realYieldFlip VERSION 1

        SYMBOLS
            gold    = BACKTEST:XAUUSD EVERY 1h
            real10y = MACRO:DFII10 EVERY 7d

        RULES
            WHEN real10y.value > 2 AND POSITION.gold = 0
            THEN BUY gold SIZING 1

            WHEN real10y.value < 2 AND POSITION.gold > 0
            THEN CLOSE gold
        """.trimIndent()

    private fun trades(
        root: Path,
        forceBars: Boolean,
    ): List<String> {
        val source =
            CompositeMarketSource(
                routes = listOf(SymbolPattern.prefix("MACRO:") to MacroMarketSource(MacroSeriesStore(root))),
                fallback = Tape(),
            )
        val result =
            Backtest
                .fromSource(
                    strategies =
                        listOf(
                            "s" to AstCompiler().compile((Dsl.parse(strategy) as ParseResult.Success).value),
                        ),
                    source = source,
                    request = MarketRequest(symbols = listOf("BACKTEST:XAUUSD", "MACRO:DFII10"), from = from, to = to),
                    candleWindow = TimeWindow.parse("1h"),
                    startingBalance = BigDecimal("100000"),
                    forceBars = forceBars,
                    barWindows =
                        if (forceBars) {
                            mapOf("BACKTEST:XAUUSD" to TimeWindow.parse("1h"), "MACRO:DFII10" to TimeWindow.parse("7d"))
                        } else {
                            emptyMap()
                        },
                ).run()
        // The hour bar each fill belongs to: the tiers fill on different ticks inside the bar after a
        // decision (the next recorded tick vs the next synthetic bar tick), never in a different bar.
        val hour = 3_600_000L
        return result.trades.map { "${Instant.ofEpochMilli(it.trade.timestamp / hour * hour)} ${it.trade.side}" }
    }

    @Test
    fun `daily values on a weekly stream are all seen, in the bar tier as in ticks`(
        @TempDir tmp: Path,
    ) {
        // The value crosses 2 on every business day of two weeks, so each publication flips the position.
        val days = (0L until 12L).map { LocalDate.of(2024, 3, 1).plusDays(it) }.filter { it.dayOfWeek.value <= 5 }
        MacroSeriesStore(tmp).write(
            "DFII10",
            days.mapIndexed { i, d -> MacroPoint(d, BigDecimal(if (i % 2 == 0) "2.10" else "1.90")) },
        )
        val ticks = trades(tmp, forceBars = false)
        // Eight publications (values dated Fri 1 Mar to Tue 12 Mar), each flipping the position on the
        // first gold bar after its 16:30 New York release.
        assertThat(ticks).hasSize(8)
        assertThat(ticks.first()).isEqualTo("2024-03-04T22:00:00Z BUY")
        assertThat(trades(tmp, forceBars = true)).isEqualTo(ticks)
    }
}
