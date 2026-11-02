package com.qkt.parity.hub

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.hub.HubMarketSource
import com.qkt.marketdata.source.CompositeMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.marketdata.source.SymbolPattern
import com.qkt.parity.hub.HubFixtureStore.Field
import com.qkt.parity.hub.HubFixtureStore.Rec
import java.math.BigDecimal
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Reading a hub stream must not change how a run is measured. Equity is sampled when every traded
 * stream has closed its bar; a hub stream closes a bar only on days a record arrives, and once took
 * part in that wait: a strategy reading a release calendar sampled equity on release days alone and
 * reported a Sharpe three times that of the same trades without the calendar.
 */
class HubReportingTest {
    private val from = Instant.parse("2024-01-01T00:00:00Z")
    private val to = Instant.parse("2024-04-01T00:00:00Z")

    /** Hourly EURUSD on weekdays, trending with a weekly wobble, so the strategy wins and loses. */
    private class Tape : MarketSource {
        private val ticks =
            (0 until 91 * 24).mapNotNull { h ->
                val t = Instant.parse("2024-01-01T00:30:00Z").plusSeconds(h * 3600L)
                if (t.atZone(ZoneOffset.UTC).dayOfWeek in
                    setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                ) {
                    return@mapNotNull null
                }
                val p =
                    BigDecimal(
                        "1.08",
                    ).add(BigDecimal(h % 120 - 60).movePointLeft(4)).add(BigDecimal(h / 24).movePointLeft(4))
                Tick("BACKTEST:EURUSD", Money.of(p.toPlainString()), t.toEpochMilli())
            }

        override val name = "tape"
        override val capabilities = setOf(MarketSourceCapability.TICKS)

        override fun supports(symbol: String) = symbol == "BACKTEST:EURUSD"

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
        ): Sequence<Candle> = emptySequence()
    }

    private fun run(
        root: Path,
        readsHub: Boolean,
    ): BacktestResult {
        val gate = if (readsHub) "cal.value > -1000000 AND " else ""
        val text =
            """
            STRATEGY swing VERSION 1
            SYMBOLS
                eur = BACKTEST:EURUSD EVERY 1d
                ${if (readsHub) "cal = HUB:test.cal.USD EVERY 1d" else ""}
            RULES
                WHEN ${gate}eur.close > eur.open AND POSITION.eur = 0
                THEN BUY eur SIZING 1 EXIT AFTER 2d
            """.trimIndent()
        val symbols =
            listOf("BACKTEST:EURUSD") +
                if (readsHub) listOf("HUB:test.cal.USD/value", "HUB:test.cal.USD/known_at") else emptyList()
        return Backtest
            .fromSource(
                strategies = listOf("swing" to AstCompiler().compile((Dsl.parse(text) as ParseResult.Success).value)),
                calendar = TradingCalendar.fxDefault(),
                source =
                    CompositeMarketSource(
                        listOf(SymbolPattern.prefix("HUB:") to HubMarketSource(root)),
                        fallback = Tape(),
                    ),
                request = MarketRequest(symbols = symbols, from = from, to = to),
                candleWindow = TimeWindow.parse("1d"),
                startingBalance = BigDecimal("100000"),
            ).run()
    }

    @Test
    fun `a run reading a release calendar is measured exactly like the same trades without it`(
        @TempDir tmp: Path,
    ) {
        // Two records a month, like a release calendar: one before the run, then the 5th and 20th.
        val days = listOf("2023-12-20") + (1..3).flatMap { m -> listOf("2024-0$m-05", "2024-0$m-20") }
        val root =
            HubFixtureStore(tmp)
                .dataset(
                    "test.cal",
                    listOf(Field("value")),
                    days.map { Rec("USD", Instant.parse("${it}T13:00:30Z").toEpochMilli(), mapOf("value" to "1")) },
                ).write()
        val plain = run(root, readsHub = false)
        val withHub = run(root, readsHub = true)
        assertThat(withHub.trades.map { it.trade.timestamp to it.trade.side }).isEqualTo(
            plain.trades.map {
                it.trade.timestamp to
                    it.trade.side
            },
        )
        assertThat(plain.trades).isNotEmpty
        assertThat(withHub.dailyEquity).isEqualTo(plain.dailyEquity)
        assertThat(withHub.global.maxDailyDrawdown).isEqualByComparingTo(plain.global.maxDailyDrawdown)
        assertThat(withHub.global.sharpeRatio).isEqualByComparingTo(plain.global.sharpeRatio)
        assertThat(withHub.global.maxDrawdown).isEqualByComparingTo(plain.global.maxDrawdown)
        assertThat(withHub.global.annualizationFactor).isEqualByComparingTo(plain.global.annualizationFactor)
    }
}
