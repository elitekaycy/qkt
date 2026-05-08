package com.qkt.dsl.compile

import com.qkt.dsl.ast.IndicatorCall
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerCompositionTest {
    private fun candle(price: String) =
        Candle(
            "BTCUSDT",
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal(price),
            BigDecimal.ZERO,
            0L,
            60_000L,
        )

    @Test
    fun `EMA over EMA composes`() {
        val bindings = IndicatorBinding.Bag()
        val inner = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val outer = IndicatorCall("EMA", listOf(inner, NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(outer)
        var ec: EvalContext? = null
        for (price in listOf("100", "110", "120", "130", "140", "150", "160")) {
            ec =
                EvalContext(
                    candle = candle(price),
                    streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                    lets = emptyMap(),
                    strategyContext = testStrategyContext(),
                )
            bindings.updateAll(ec)
        }
        val v = compiled.evaluate(ec!!)
        assertThat(v).isInstanceOf(Value.Num::class.java)
    }

    @Test
    fun `outer indicator stays Undefined while inner is warming`() {
        val bindings = IndicatorBinding.Bag()
        val inner = IndicatorCall("EMA", listOf(StreamFieldRef("btc", "close"), NumLit(BigDecimal("3"))))
        val outer = IndicatorCall("EMA", listOf(inner, NumLit(BigDecimal("3"))))
        val compiled = ExprCompiler(bindings).compile(outer)
        val ec =
            EvalContext(
                candle = candle("100"),
                streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
                lets = emptyMap(),
                strategyContext = testStrategyContext(),
            )
        bindings.updateAll(ec)
        assertThat(compiled.evaluate(ec)).isEqualTo(Value.Undefined)
    }
}
