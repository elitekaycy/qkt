package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LastTradingDayOfMonthEvalTest {
    private fun ec(clock: FixedClock): EvalContext =
        EvalContext(
            candle =
                Candle(
                    symbol = "BACKTEST:XAUUSD",
                    open = BigDecimal.ZERO,
                    high = BigDecimal.ZERO,
                    low = BigDecimal.ZERO,
                    close = BigDecimal.ZERO,
                    volume = BigDecimal.ZERO,
                    startTime = clock.now(),
                    endTime = clock.now() + 60_000L,
                ),
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(clock = clock),
        )

    /** Evaluate the predicate as if "now" were noon UTC on [isoDate] (e.g. "2026-06-30"). */
    private fun hits(isoDate: String): Boolean {
        val ms = Instant.parse("${isoDate}T12:00:00Z").toEpochMilli()
        return (ExprCompiler().compile(LastTradingDayOfMonth).evaluate(ec(FixedClock(time = ms))) as Value.Bool).v
    }

    @Test
    fun `last day is the last trading day when it is a weekday`() {
        assertThat(hits("2026-03-31")).isTrue // Tuesday
        assertThat(hits("2026-06-30")).isTrue // Tuesday
    }

    @Test
    fun `rolls back to Friday when the month ends on a Saturday`() {
        // Jan 2026 ends Sat the 31st → last trading day is Fri the 30th.
        assertThat(hits("2026-01-30")).isTrue
        assertThat(hits("2026-01-31")).isFalse
    }

    @Test
    fun `rolls back to Friday when the month ends on a Sunday`() {
        // May 2026 ends Sun the 31st → last trading day is Fri the 29th.
        assertThat(hits("2026-05-29")).isTrue
        assertThat(hits("2026-05-30")).isFalse // Saturday
        assertThat(hits("2026-05-31")).isFalse // Sunday
    }

    @Test
    fun `mid-month and the penultimate weekday are not the last trading day`() {
        assertThat(hits("2026-06-15")).isFalse
        assertThat(hits("2026-06-29")).isFalse // Monday, the weekday before the last
        assertThat(hits("2026-03-30")).isFalse // Monday, the weekday before the last
    }

    @Test
    fun `leap-year February uses the real last day`() {
        assertThat(hits("2024-02-29")).isTrue // Thursday, the leap day
        assertThat(hits("2024-02-28")).isFalse // Wednesday
        // Common-year February ends Sat the 28th → last trading day is Fri the 27th.
        assertThat(hits("2026-02-27")).isTrue
        assertThat(hits("2026-02-28")).isFalse
    }

    @Test
    fun `last_trading_day_of_month is usable as a WHEN condition and compiles`() {
        val src =
            """
            STRATEGY monthend VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gbp = BACKTEST:GBPUSD EVERY 1d
            RULES
              WHEN last_trading_day_of_month() AND POSITION.gbp = 0
              THEN BUY gbp
            """.trimIndent()
        val parsed = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        com.qkt.dsl.compile
            .AstCompiler()
            .compile((parsed as ParseResult.Success).value)
    }
}
