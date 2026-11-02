package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.instrument.CorporateAction
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A stock CFD held into its ex-date session is paid the dividend, a short pays it, and one opened after the ex-date
 * session began gets nothing: AAPL 0.26 USD a share, ex 2026-10-27, on a 06:00-15:45 New York session window.
 */
class DividendBacktestTest {
    private val aapl =
        InstrumentMeta(
            qktSymbol = "EXNESS:AAPL",
            contractSize = BigDecimal(100),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal(10),
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            currency = "USD",
            category = "Stocks",
        )
    private val dividend = CorporateAction.Dividend(LocalDate.parse("2026-10-27"), BigDecimal("0.26"), "USD")

    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = aapl.takeIf { qktSymbol == it.qktSymbol }

            override fun corporateActions() =
                com.qkt.instrument.CorporateActionSource { if (it == "EXNESS:AAPL") listOf(dividend) else emptyList() }
        }

    private fun session(date: String): List<Tick> {
        val open = Instant.parse("${date}T10:00:00Z").toEpochMilli()
        return (0 until 585).map { m ->
            val p = Money.of("346")
            Tick("EXNESS:AAPL", p, open + m * 60_000L, bid = p - Money.of("0.08"), ask = p + Money.of("0.08"))
        }
    }

    private fun run(rules: String) =
        Backtest(
            strategies =
                listOf("div" to compile("STRATEGY div VERSION 1\nSYMBOLS\n  x = EXNESS:AAPL EVERY 1m\nRULES\n$rules")),
            feed = HistoricalTickFeed(session("2026-10-26") + session("2026-10-27")),
            symbols = listOf("EXNESS:AAPL"),
            candleWindow = TimeWindow.ONE_MINUTE,
            calendar = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse"),
            instruments = registry,
            startingBalance = BigDecimal(10_000),
            brokerKind = BrokerKind.MT5_SIM,
        ).run()

    @Test
    fun `a long held into the ex-date is credited one share's dividend, a short is debited it`() {
        val long = run("  WHEN x.close > 0 AND POSITION.x = 0 AND TRADES.today = 0\n  THEN BUY x SIZING 0.01")
        assertThat(long.global.dividendsPaid).isEqualByComparingTo("-0.26")
        val short = run("  WHEN x.close > 0 AND POSITION.x = 0 AND TRADES.today = 0\n  THEN SELL x SIZING 0.01")
        assertThat(short.global.dividendsPaid).isEqualByComparingTo("0.26")
    }

    @Test
    fun `a position opened after the ex-date session began is not paid`() {
        val late = run("  WHEN NOW.date_utc = 20753 AND x.close > 0 AND POSITION.x = 0\n  THEN BUY x SIZING 0.01")
        assertThat(late.trades).isNotEmpty
        assertThat(late.global.dividendsPaid).isEqualByComparingTo("0")
    }
}
