package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Log
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeQty
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ActionCompilerExtensionsTest {
    private val candle =
        Candle("BTCUSDT", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val ctx =
        EvalContext(
            candle = candle,
            streamSymbols = mapOf("btc" to "BTCUSDT"),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )
    private val logger = LoggerFactory.getLogger("test")

    @Test
    fun `Log emits no signals`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Log("entered long"))
                .invoke(ctx)
        assertThat(sigs).isEmpty()
    }

    @Test
    fun `Buy still wraps single signal in list`() {
        val sigs =
            ActionCompiler(ExprCompiler(), logger)
                .compile(Buy("btc", ActionOpts(sizing = SizeQty(NumLit(BigDecimal.ONE)))))
                .invoke(ctx)
        assertThat(sigs).hasSize(1)
    }
}
