package com.qkt.dsl.compile

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionRangeTriggerDslTest {
    @Test
    fun `completed session low can trigger a later same-day fade`() {
        val strategy =
            compile(
                """
                STRATEGY session_low_fade VERSION 1
                DEFAULTS { SIZING = 1 }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 30m
                RULES
                  WHEN NOW.HOUR_UTC >= 21
                   AND gold.close CROSSES BELOW session_range_low(gold.candle, 13, 0, 21, 0)
                   AND ACCOUNT.trades_today < 1
                   AND POSITION.gold = 0
                  THEN BUY gold
                """.trimIndent(),
            )

        val captured = replaySession(strategy, breakClose = "89")

        assertThat(captured).containsExactly(Signal.Buy("BACKTEST:XAUUSD", BigDecimal.ONE))
    }

    @Test
    fun `completed session high can trigger a later same-day fade`() {
        val strategy =
            compile(
                """
                STRATEGY session_high_fade VERSION 1
                DEFAULTS { SIZING = 1 }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 30m
                RULES
                  WHEN NOW.HOUR_UTC >= 21
                   AND gold.close CROSSES ABOVE session_range_high(gold.candle, 13, 0, 21, 0)
                   AND ACCOUNT.trades_today < 1
                   AND POSITION.gold = 0
                  THEN SELL gold
                """.trimIndent(),
            )

        val captured = replaySession(strategy, breakClose = "111")

        assertThat(captured).containsExactly(Signal.Sell("BACKTEST:XAUUSD", BigDecimal.ONE))
    }

    private fun compile(source: String): DslCompiledStrategy {
        val parsed = Dsl.parse(source)
        require(parsed is ParseResult.Success) { "parse failed: ${(parsed as ParseResult.Failure).errors}" }
        return AstCompiler().compile(parsed.value) as DslCompiledStrategy
    }

    private fun replaySession(
        strategy: DslCompiledStrategy,
        breakClose: String,
    ): List<Signal> {
        val captured = mutableListOf<Signal>()
        val context = testStrategyContext()
        for (candle in sessionCandles(breakClose)) {
            strategy.onCandle(candle, context, captured::add)
        }
        return captured
    }

    private fun sessionCandles(breakClose: String): List<Candle> {
        val priorDay =
            (0 until 48).map { halfHour ->
                candle(
                    day = 0,
                    startHour = halfHour / 2,
                    startMinute = (halfHour % 2) * 30,
                    high = "100",
                    low = "100",
                    close = "100",
                )
            }
        val targetDay =
            listOf(
                candle(day = 1, startHour = 13, startMinute = 0, high = "105", low = "95", close = "100"),
                candle(day = 1, startHour = 20, startMinute = 30, high = "110", low = "90", close = "100"),
                candle(day = 1, startHour = 21, startMinute = 0, high = "109", low = "91", close = "100"),
                candle(
                    day = 1,
                    startHour = 21,
                    startMinute = 30,
                    high = if (breakClose == "111") "111" else "100",
                    low = if (breakClose == "89") "89" else "100",
                    close = breakClose,
                ),
            )
        return priorDay + targetDay
    }

    private fun candle(
        day: Int,
        startHour: Int,
        startMinute: Int,
        high: String,
        low: String,
        close: String,
    ): Candle {
        val startTimeMs = (day * 1_440L + startHour * 60L + startMinute) * 60_000L
        return Candle(
            symbol = "BACKTEST:XAUUSD",
            open = BigDecimal(close),
            high = BigDecimal(high),
            low = BigDecimal(low),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startTimeMs,
            endTime = startTimeMs + 30 * 60_000L,
        )
    }
}
