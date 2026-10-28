package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.instrument.EquityCosts
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A 100-share round trip on a per-share broker: $1 to buy, $1 plus the SEC fee and TAF to sell, in the report. */
class EquityCostBacktestTest {
    private val aapl =
        InstrumentMeta(
            qktSymbol = "ALPACA:AAPL",
            contractSize = BigDecimal.ONE,
            volumeStep = BigDecimal.ONE,
            volumeMin = BigDecimal.ONE,
            volumeMax = null,
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            currency = "USD",
            equityCosts =
                EquityCosts(
                    commissionPerShare = BigDecimal("0.005"),
                    minCommission = BigDecimal("1.00"),
                    secFeeRate = BigDecimal("0.0000278"),
                    tafPerShare = BigDecimal("0.000166"),
                    tafMax = BigDecimal("8.30"),
                ),
        )
    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String) = aapl.takeIf { qktSymbol == it.qktSymbol }
        }

    private val ticks: List<Tick> =
        (0 until 120).map { m ->
            val p = Money.of("346")
            Tick("ALPACA:AAPL", p, Instant.parse("2026-06-01T14:00:00Z").toEpochMilli() + m * 60_000L, bid = p, ask = p)
        }

    @Test
    fun `the round trip's commission and sale fees are what the broker bills`() {
        val result =
            Backtest(
                strategies =
                    listOf(
                        "eq" to
                            compile(
                                "STRATEGY eq VERSION 1\nSYMBOLS\n  x = ALPACA:AAPL EVERY 1m\nRULES\n" +
                                    "  WHEN POSITION.x = 0 AND TRADES.today = 0\n  THEN BUY x SIZING 100\n" +
                                    "  WHEN POSITION.x > 0 AND TRADES.today = 1\n  THEN CLOSE x",
                            ),
                    ),
                feed = HistoricalTickFeed(ticks),
                symbols = listOf("ALPACA:AAPL"),
                candleWindow = TimeWindow.ONE_MINUTE,
                calendar = TradingCalendar.named("nyse") ?: error("nyse"),
                instruments = registry,
                startingBalance = BigDecimal(100_000),
            ).run()
        assertThat(result.trades).hasSize(2)
        val sale = result.trades.last().trade
        val sec =
            sale.price
                .multiply(
                    BigDecimal(100),
                ).multiply(BigDecimal("0.0000278"))
                .setScale(2, RoundingMode.CEILING)
        assertThat(result.global.commissionPaid).isEqualByComparingTo(BigDecimal("2.02").add(sec))
    }
}
