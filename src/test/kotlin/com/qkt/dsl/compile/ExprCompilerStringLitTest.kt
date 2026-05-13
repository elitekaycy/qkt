package com.qkt.dsl.compile

import com.qkt.dsl.ast.StringLit
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerStringLitTest {
    @Test
    fun `StringLit compiles to Value Str`() {
        val compiled = ExprCompiler().compile(StringLit("hello"))
        val ctx =
            EvalContext(
                candle =
                    Candle(
                        "BACKTEST:BTCUSDT",
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        0L,
                        1L,
                    ),
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val result = compiled.evaluate(ctx)
        assertThat(result).isEqualTo(Value.Str("hello"))
    }
}
