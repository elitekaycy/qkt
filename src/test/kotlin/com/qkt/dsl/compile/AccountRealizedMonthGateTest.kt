package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `ACCOUNT.realized_month` gates entries for the rest of the UTC month and reopens on the 1st (#855). */
class AccountRealizedMonthGateTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    @Test
    fun `a monthly loss gate stops new entries until the next month`() {
        // Each day: buy at the first bar, close one bar later at a 10-point loss. The gate allows
        // entries while this month's closed P&L is above -25, so January trades 3 times
        // (-10, -20, -30 then blocked) and February opens again on its first day.
        val src =
            """
            STRATEGY month_gate VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1h
            RULES
              WHEN POSITION.b = 0 AND NOW.hour_utc = 1 AND ACCOUNT.realized_month > -25
              THEN BUY b SIZING 1
              WHEN POSITION.b != 0 AND NOW.hour_utc = 2
              THEN CLOSE b
            """.trimIndent()
        val days =
            listOf("2024-01-27", "2024-01-28", "2024-01-29", "2024-01-30", "2024-01-31", "2024-02-01")
        val ticks =
            days.flatMap { d ->
                val t0 = Instant.parse("${d}T00:00:00Z").toEpochMilli()
                listOf(
                    Tick("BACKTEST:BTCUSDT", Money.of("100"), t0 + 30 * 60_000L),
                    Tick("BACKTEST:BTCUSDT", Money.of("100"), t0 + 90 * 60_000L),
                    Tick("BACKTEST:BTCUSDT", Money.of("90"), t0 + 150 * 60_000L),
                    Tick("BACKTEST:BTCUSDT", Money.of("90"), t0 + 210 * 60_000L),
                )
            }
        val result =
            Backtest(
                strategies = listOf("month_gate" to compile(src)),
                ticks = ticks,
                candleWindow = TimeWindow.parse("1h"),
                startingBalance = Money.of("10000"),
            ).run()
        val buyDays =
            result.trades
                .filter { it.trade.side == Side.BUY }
                .map { Instant.ofEpochMilli(it.trade.timestamp).toString().substring(0, 10) }
        assertThat(buyDays).containsExactly("2024-01-27", "2024-01-28", "2024-01-29", "2024-02-01")
    }
}
