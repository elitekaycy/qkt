package com.qkt.dsl.compile

import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerIsNullTest {
    private val candle =
        Candle(
            symbol = "BACKTEST:BTCUSDT",
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
    fun `IS NULL on a NumLit returns false`() {
        val ast = IsNull(NumLit(BigDecimal.ONE), negated = false)
        val compiled = ExprCompiler().compile(ast)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(false))
    }

    @Test
    fun `IS NOT NULL on a NumLit returns true`() {
        val ast = IsNull(NumLit(BigDecimal.ONE), negated = true)
        val compiled = ExprCompiler().compile(ast)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(true))
    }

    @Test
    fun `IS NULL on an unquoted bid returns true`() {
        val ast = IsNull(StreamFieldRef("btc", "bid"), negated = false)
        val compiled = ExprCompiler().compile(ast)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(true))
    }

    @Test
    fun `IS NOT NULL on an unquoted bid returns false`() {
        val ast = IsNull(StreamFieldRef("btc", "bid"), negated = true)
        val compiled = ExprCompiler().compile(ast)
        assertThat(compiled.evaluate(ctx)).isEqualTo(Value.Bool(false))
    }
}
