package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
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
 * Exness prints AAPL from 06:00 New York but refuses every order until the 09:30 open (MT5 retcode 10044). A backtest
 * over those prints evaluates the pre-market bars, as live does, but trades only in session: a market entry decided
 * at 06:01 is refused, and a trailing stop the pre-market drop crosses fires on the first in-session tick.
 */
class SessionGateBacktestTest {
    private val aapl =
        InstrumentMeta(
            qktSymbol = "EXNESS:AAPL",
            contractSize = BigDecimal.ONE,
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = BigDecimal(10),
            pointSize = BigDecimal("0.01"),
            digits = 2,
            tradeStopsLevelPoints = 0,
            category = "Stocks",
        )

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    /** One tick a minute at [mid] from [fromIso] for [minutes] minutes. */
    private fun ticks(
        fromIso: String,
        minutes: Int,
        mid: String,
    ): List<Tick> =
        (0 until minutes).map { m ->
            val p = Money.of(mid)
            Tick("EXNESS:AAPL", p, ms(fromIso) + m * 60_000L, bid = p - Money.of("0.02"), ask = p + Money.of("0.02"))
        }

    private fun run(
        rule: String,
        ticks: List<Tick>,
        broker: BrokerKind,
    ) = Backtest(
        strategies =
            listOf(
                "s" to
                    compile(
                        """
                        STRATEGY s VERSION 1
                        SYMBOLS
                          x = EXNESS:AAPL EVERY 1m
                        RULES
                          $rule
                        """.trimIndent(),
                    ),
            ),
        feed = com.qkt.marketdata.HistoricalTickFeed(ticks),
        symbols = listOf("EXNESS:AAPL"),
        candleWindow = TimeWindow.ONE_MINUTE,
        calendar = TradingCalendar.named("nyse[09:30-15:45]") ?: error("windowed nyse"),
        instruments =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String) = aapl.takeIf { qktSymbol == it.qktSymbol }
            },
        brokerKind = broker,
    ).run()

    @ParameterizedTest
    @EnumSource(value = BrokerKind::class, names = ["MT5_SIM", "PAPER"])
    fun `a pre-market entry is refused and the first in-session decision fills`(broker: BrokerKind) {
        // 2026-10-28: 06:00 New York is 10:00 UTC, the 09:30 open 13:30 UTC.
        val entryRule = "WHEN x.close > 0 AND POSITION.x = 0 THEN BUY x SIZING 0.01"
        val result = run(entryRule, ticks("2026-10-28T10:00:00Z", 240, "300"), broker)

        assertThat(result.rejections).isNotEmpty()
        assertThat(result.rejections).allSatisfy {
            assertThat(it.reason).contains("outside its trading session")
            assertThat(it.request.timestamp).isLessThan(ms("2026-10-28T13:30:00Z"))
        }
        val entry = result.trades.first().trade
        assertThat(entry.side).isEqualTo(Side.BUY)
        assertThat(entry.timestamp).isGreaterThanOrEqualTo(ms("2026-10-28T13:30:00Z"))
    }

    @ParameterizedTest
    @EnumSource(value = BrokerKind::class, names = ["MT5_SIM", "PAPER"])
    fun `a trailing stop crossed before the open fires on the first in-session tick`(broker: BrokerKind) {
        val tape =
            ticks("2026-10-27T13:30:00Z", 30, "300") +
                ticks("2026-10-28T10:00:00Z", 210, "290") +
                ticks("2026-10-28T13:30:00Z", 30, "295")
        val result =
            run(
                "WHEN x.close > 0 AND POSITION.x = 0 " +
                    "THEN BUY x SIZING 0.01 BRACKET { STOP LOSS TRAILING 3 AFTER MFE >= 0, TAKE PROFIT BY 50 }",
                tape,
                broker,
            )

        // The bar after the exit enters again: the rule only waits for a flat book.
        val (entry, exit) = result.trades.map { it.trade }
        assertThat(entry.timestamp).isLessThan(ms("2026-10-27T14:00:00Z"))
        assertThat(exit.side).isEqualTo(Side.SELL)
        assertThat(exit.timestamp).isEqualTo(ms("2026-10-28T13:30:00Z"))
        assertThat(exit.price).isBetween(Money.of("294.9"), Money.of("295.1"))
        assertThat(result.trades.count { it.trade.side == Side.SELL }).isEqualTo(1)
    }
}
