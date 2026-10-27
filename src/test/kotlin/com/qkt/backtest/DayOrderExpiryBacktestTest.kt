package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A DAY limit placed during one stock-CFD session is gone by the next: Exness's 06:00-15:45 New York session, a limit
 * at 300 placed Monday with AAPL at 346, and Tuesday opening at 299. A GTC limit fills on Tuesday's open; a DAY limit
 * was cancelled at Monday's 19:45 UTC close. mt5-sim used to keep a DAY order resting like GTC.
 */
class DayOrderExpiryBacktestTest {
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
            category = "Stocks",
        )

    private fun day(
        date: String,
        mid: String,
    ): List<Tick> {
        val open = Instant.parse("${date}T10:00:00Z").toEpochMilli()
        return (0 until 585).map { m ->
            val p = Money.of(mid)
            Tick("EXNESS:AAPL", p, open + m * 60_000L, bid = p - Money.of("0.08"), ask = p + Money.of("0.08"))
        }
    }

    private fun run(
        tif: String,
        broker: BrokerKind,
    ) = Backtest(
        strategies =
            listOf(
                "day" to
                    compile(
                        """
                        STRATEGY day VERSION 1
                        SYMBOLS
                          x = EXNESS:AAPL EVERY 1m
                        RULES
                          WHEN x.close > 0 AND POSITION.x = 0
                          THEN BUY x SIZING 0.01 ORDER_TYPE = LIMIT AT 300 TIF $tif
                        """.trimIndent(),
                    ),
            ),
        feed = com.qkt.marketdata.HistoricalTickFeed(day("2026-10-26", "346") + day("2026-10-27", "299")),
        symbols = listOf("EXNESS:AAPL"),
        candleWindow = TimeWindow.ONE_MINUTE,
        calendar = TradingCalendar.named("nyse[06:00-15:45]") ?: error("windowed nyse"),
        instruments =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String) = aapl.takeIf { qktSymbol == it.qktSymbol }
            },
        brokerKind = broker,
    ).run()

    @ParameterizedTest
    @EnumSource(value = BrokerKind::class, names = ["MT5_SIM", "PAPER"])
    fun `a GTC limit fills on the next session and a DAY limit has expired by then`(broker: BrokerKind) {
        val gtc = run("GTC", broker)
        assertThat(gtc.trades).hasSize(1)
        assertThat(
            gtc.trades
                .single()
                .trade.timestamp,
        ).isGreaterThanOrEqualTo(Instant.parse("2026-10-27T10:00:00Z").toEpochMilli())
        assertThat(run("DAY", broker).trades).isEmpty()
    }
}
