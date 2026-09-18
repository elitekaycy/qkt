package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `POSITION.<stream>` reads compiled by [PositionRefCompiler]. */
class PositionRefCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    @Test
    fun `POSITION reads signed quantity from positions view`() {
        val pos =
            object : com.qkt.positions.StrategyPositionView {
                override fun positionFor(symbol: String) =
                    if (symbol == "BACKTEST:BTCUSDT") {
                        com.qkt.positions.Position("BACKTEST:BTCUSDT", BigDecimal("2.5"), BigDecimal("100"))
                    } else {
                        null
                    }

                override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
            }
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(positions = pos),
            )
        val v =
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .PositionRef("btc"),
                ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("2.5")
    }

    @Test
    fun `POSITION on unknown symbol is zero`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        val v =
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .PositionRef("btc"),
                ).evaluate(ec) as Value.Num
        assertThat(v.v).isEqualByComparingTo("0")
    }

    @Test
    fun `POSITION on unknown stream alias errors at evaluation`() {
        val ec =
            EvalContext(
                candle = candle,
                streams = emptyMap(),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        assertThatThrownBy {
            ExprCompiler()
                .compile(
                    com.qkt.dsl.ast
                        .PositionRef("btc"),
                ).evaluate(ec)
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
