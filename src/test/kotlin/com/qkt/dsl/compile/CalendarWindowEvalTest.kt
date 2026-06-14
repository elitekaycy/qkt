package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CalendarWindowEvalTest {
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

    /** Evaluate [win] as if "now" were noon UTC on [isoDate] (e.g. "2026-09-01"). */
    private fun hits(
        win: CalendarWindow,
        isoDate: String,
    ): Boolean {
        val ms = Instant.parse("${isoDate}T12:00:00Z").toEpochMilli()
        return (ExprCompiler().compile(win).evaluate(ec(FixedClock(time = ms))) as Value.Bool).v
    }

    // Diwali season: Aug 15 - Oct 31 (does not wrap the year).
    private val diwali = CalendarWindow(startMonth = 8, startDay = 15, endMonth = 10, endDay = 31)

    // Chinese New Year restocking: Dec 1 - Jan 31 (wraps the year boundary).
    private val cny = CalendarWindow(startMonth = 12, startDay = 1, endMonth = 1, endDay = 31)

    @Test
    fun `non-wrapping window matches dates inside it`() {
        assertThat(hits(diwali, "2026-09-01")).isTrue
        assertThat(hits(diwali, "2026-10-15")).isTrue
    }

    @Test
    fun `non-wrapping window excludes dates outside it`() {
        assertThat(hits(diwali, "2026-08-14")).isFalse
        assertThat(hits(diwali, "2026-11-01")).isFalse
        assertThat(hits(diwali, "2026-01-15")).isFalse
    }

    @Test
    fun `non-wrapping window is inclusive of both boundary days`() {
        assertThat(hits(diwali, "2026-08-15")).isTrue
        assertThat(hits(diwali, "2026-10-31")).isTrue
    }

    @Test
    fun `wrapping window matches dates on both sides of the year boundary`() {
        assertThat(hits(cny, "2026-12-15")).isTrue
        assertThat(hits(cny, "2027-01-15")).isTrue
    }

    @Test
    fun `wrapping window excludes dates in the gap`() {
        assertThat(hits(cny, "2026-11-30")).isFalse
        assertThat(hits(cny, "2026-02-01")).isFalse
        assertThat(hits(cny, "2026-06-01")).isFalse
    }

    @Test
    fun `wrapping window is inclusive of both boundary days`() {
        assertThat(hits(cny, "2026-12-01")).isTrue
        assertThat(hits(cny, "2026-01-31")).isTrue
    }

    @Test
    fun `calendar_window is usable as a WHEN condition and compiles`() {
        val src =
            """
            STRATEGY seasonal VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS
              gold = BACKTEST:XAUUSD EVERY 1d
            RULES
              WHEN calendar_window(8, 15, 10, 31) AND POSITION.gold = 0
              THEN BUY gold
            """.trimIndent()
        val parsed = Parser(Lexer(src).tokenize()).parseStrategy()
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        // Compiling must not throw — proves the boolean primitive composes in a real rule.
        com.qkt.dsl.compile
            .AstCompiler()
            .compile((parsed as ParseResult.Success).value)
    }
}
