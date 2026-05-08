package com.qkt.dsl.compile

import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerStreamFieldTest {
    private val candle =
        Candle(
            symbol = "BTCUSDT",
            open = BigDecimal("100"),
            high = BigDecimal("110"),
            low = BigDecimal("90"),
            close = BigDecimal("105"),
            volume = BigDecimal("1.5"),
            startTime = 0L,
            endTime = 60_000L,
        )
    private val ctx =
        EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `close maps to candle close`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105")
    }

    @Test
    fun `open maps to candle open`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "open")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("100")
    }

    @Test
    fun `high low volume map correctly`() {
        val ec = ExprCompiler()
        assertThat((ec.compile(StreamFieldRef("btc", "high")).evaluate(ctx) as Value.Num).v)
            .isEqualByComparingTo("110")
        assertThat((ec.compile(StreamFieldRef("btc", "low")).evaluate(ctx) as Value.Num).v)
            .isEqualByComparingTo("90")
        assertThat((ec.compile(StreamFieldRef("btc", "volume")).evaluate(ctx) as Value.Num).v)
            .isEqualByComparingTo("1.5")
    }

    @Test
    fun `price is alias for close`() {
        val v = ExprCompiler().compile(StreamFieldRef("btc", "price")).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("105")
    }

    @Test
    fun `unknown field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(StreamFieldRef("btc", "wat")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown stream alias is rejected at evaluation time`() {
        val noStream =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        assertThatThrownBy {
            ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(noStream)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `mismatched candle symbol returns Undefined`() {
        val otherCandle = candle.copy(symbol = "GOLD")
        val otherCtx =
            EvalContext(
                candle = otherCandle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v = ExprCompiler().compile(StreamFieldRef("btc", "close")).evaluate(otherCtx)
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
