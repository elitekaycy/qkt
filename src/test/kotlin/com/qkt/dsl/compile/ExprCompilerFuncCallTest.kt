package com.qkt.dsl.compile

import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerFuncCallTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `ABS evaluates`() {
        val v =
            ExprCompiler().compile(FuncCall("ABS", listOf(NumLit(BigDecimal("-7")))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7")
    }

    @Test
    fun `MAX of three`() {
        val v =
            ExprCompiler().compile(
                FuncCall(
                    "MAX",
                    listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("9")), NumLit(BigDecimal("3"))),
                ),
            ).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("9")
    }

    @Test
    fun `Undefined arg makes the result Undefined`() {
        // StreamFieldRef("btc", "close") has no stream symbol mapping → throws.
        // Use a mismatched candle symbol instead which yields Undefined cleanly.
        val ec =
            EvalContext(
                candle = candle,
                streamSymbols = mapOf("btc" to "BTCUSDT"),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler().compile(
                FuncCall(
                    "MAX",
                    listOf(NumLit(BigDecimal("1")), StreamFieldRef("btc", "close")),
                ),
            ).evaluate(ec)
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
