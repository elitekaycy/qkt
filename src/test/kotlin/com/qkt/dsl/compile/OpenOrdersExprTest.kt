package com.qkt.dsl.compile

import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OpenOrdersExprTest {
    private val ec = ExprCompiler()

    @Test
    fun `OPEN_ORDERS rejects an unknown stream alias when evaluated`() {
        val compiled = ec.compile(StateAccessor(StateSource.OPEN_ORDERS, "btc"))
        val context =
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

        assertThatThrownBy {
            compiled.evaluate(context)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Unknown stream alias: btc")
    }
}
