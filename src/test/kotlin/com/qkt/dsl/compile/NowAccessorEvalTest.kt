package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NowAccessorEvalTest {
    // 2026-05-11 13:45:00 UTC — a Monday
    private val mondayMs =
        java.time.Instant
            .parse("2026-05-11T13:45:00Z")
            .toEpochMilli()

    private fun ec(clock: FixedClock): EvalContext =
        EvalContext(
            candle =
                Candle(
                    symbol = "BACKTEST:BTCUSDT",
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

    @Test
    fun `NOW hour_utc returns the UTC hour from FixedClock`() {
        val ctx = ec(FixedClock(time = mondayMs))
        val compiled = ExprCompiler().compile(NowAccessor(NowField.HOUR_UTC))
        assertThat((compiled.evaluate(ctx) as Value.Num).v).isEqualByComparingTo("13")
    }

    @Test
    fun `each field returns the expected projection`() {
        val ctx = ec(FixedClock(time = mondayMs))
        val ec = ExprCompiler()
        assertThat((ec.compile(NowAccessor(NowField.HOUR_UTC)).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("13")
        assertThat(
            (ec.compile(NowAccessor(NowField.MINUTE_UTC)).evaluate(ctx) as Value.Num).v,
        ).isEqualByComparingTo("45")
        assertThat((ec.compile(NowAccessor(NowField.WEEKDAY)).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("0")
        assertThat((ec.compile(NowAccessor(NowField.MONTH)).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("5")
        assertThat((ec.compile(NowAccessor(NowField.DAY)).evaluate(ctx) as Value.Num).v).isEqualByComparingTo("11")
        assertThat((ec.compile(NowAccessor(NowField.EPOCH_MS)).evaluate(ctx) as Value.Num).v)
            .isEqualByComparingTo(BigDecimal.valueOf(mondayMs))
    }

    @Test
    fun `NOW plus 10m evaluates to clock now plus 600000ms`() {
        val ctx = ec(FixedClock(time = mondayMs))
        val src = "STRATEGY x VERSION 1\nLET deadline = NOW + 10m"
        val ast = (Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success).value
        val compiled = ExprCompiler().compile(ast.lets[0].expr)
        assertThat((compiled.evaluate(ctx) as Value.Num).v)
            .isEqualByComparingTo(BigDecimal.valueOf(mondayMs + 600_000L))
    }
}
