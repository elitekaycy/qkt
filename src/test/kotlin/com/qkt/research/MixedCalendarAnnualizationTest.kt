package com.qkt.research

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.cli.CalendarResolver
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The run's Sharpe is annualized by the samples its equity curve takes: a boundary is sampled once every traded
 * symbol has closed a bar, so EURUSD + BTCUSD samples on FX hours whichever stream is listed first, and an SPX
 * strategy annualizes on NYSE hours instead of stopping.
 */
class MixedCalendarAnnualizationTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    private fun ticks(symbols: List<String>): List<Tick> {
        val from = Instant.parse("2024-06-10T00:00:00Z").toEpochMilli()
        val calendars = CalendarResolver(null) { null }
        return (0 until 7 * 48)
            .flatMap { i ->
                val t = from + i * 1_800_000L
                symbols
                    .filter { calendars.calendarFor(it).isInSession(it, Instant.ofEpochMilli(t)) }
                    .map { Tick(it, Money.of(if (it.endsWith("BTCUSD")) "60000" else "1.10"), t) }
            }
    }

    private fun annualization(symbols: List<String>): BigDecimal? {
        val calendars = CalendarResolver(null) { null }
        val calendar = com.qkt.cli.strategyCalendar(symbols, null, calendars::calendarFor)
        return Backtest(
            strategies = listOf("s" to idle),
            feed = SequenceTickFeed(ticks(symbols).asSequence()),
            candleWindow = TimeWindow.ONE_HOUR,
            calendar = calendar,
            symbols = symbols,
            startingBalance = BigDecimal("10000"),
        ).run().global.annualizationFactor
    }

    @Test
    fun `fx and crypto annualize on fx hours whichever stream comes first`() {
        val fx = TradingCalendar.fxDefault().tradingPeriodsPerYear(TimeWindow.ONE_HOUR)
        assertThat(annualization(listOf("BACKTEST:EURUSD", "BACKTEST:BTCUSD"))).isEqualByComparingTo(fx)
        assertThat(annualization(listOf("BACKTEST:BTCUSD", "BACKTEST:EURUSD"))).isEqualByComparingTo(fx)
    }

    @Test
    fun `a single market keeps its own count`() {
        assertThat(annualization(listOf("BACKTEST:BTCUSD"))).isEqualByComparingTo("8766")
    }

    @Test
    fun `an spx strategy annualizes on nyse hours instead of stopping`() {
        assertThat(annualization(listOf("BACKTEST:SPX")))
            .isEqualByComparingTo(TradingCalendar.nyse().tradingPeriodsPerYear(TimeWindow.ONE_HOUR))
    }
}
