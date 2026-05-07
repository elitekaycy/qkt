package com.qkt.dsl.compile

import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.NumLit
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerLiteralsTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle = candle, streamSymbols = emptyMap(), lets = emptyMap())

    @Test
    fun `numeric literal evaluates to its value`() {
        val compiled = ExprCompiler().compile(NumLit(BigDecimal("3.5")))
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Num(BigDecimal("3.5")))
    }

    @Test
    fun `boolean literal evaluates to its value`() {
        val compiled = ExprCompiler().compile(BoolLit(true))
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(true))
    }
}
