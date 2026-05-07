package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerIndicatorTest {
    @Test
    fun `EMA over close evaluates to Undefined when not warm`() {
        val bindings = IndicatorBinding.Bag()
        val expr = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(expr)
        val candle =
            Candle(
                "BTCUSDT",
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal("100"),
                BigDecimal.ZERO,
                0L,
                1L,
            )
        val ctx =
            EvalContext(candle = candle, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())
        bindings.updateAll(ctx)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `EMA over close yields a value once warm`() {
        val bindings = IndicatorBinding.Bag()
        val expr = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(expr)
        var ctx: EvalContext? = null
        for (price in listOf("100", "110", "120")) {
            val c =
                Candle(
                    "BTCUSDT",
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal(price),
                    BigDecimal.ZERO,
                    0L,
                    1L,
                )
            ctx = EvalContext(candle = c, streamSymbols = mapOf("btc" to "BTCUSDT"), lets = emptyMap())
            bindings.updateAll(ctx)
        }
        val v = compiled.evaluate(ctx!!) as Value.Num
        assertThat(v.v).isEqualByComparingTo("110")
    }

    @Test
    fun `unknown indicator name is rejected`() {
        assertThatThrownBy {
            ExprCompiler(IndicatorBinding.Bag())
                .compile(
                    IndicatorCall("UNKNOWN", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal.ONE))),
                )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `wrong arity is rejected`() {
        assertThatThrownBy {
            ExprCompiler(IndicatorBinding.Bag())
                .compile(IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"))))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
