package com.qkt.dsl.compile

import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExprCompilerCrossesTest {
    private fun candleAt(
        symbol: String,
        close: String,
    ) = Candle(
        symbol,
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal(close),
        BigDecimal.ZERO,
        0L,
        60_000L,
    )

    private fun ctxFor(
        candle: Candle,
        streams: Map<String, HubKey> = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
    ): EvalContext =
        EvalContext(
            candle = candle,
            streams = streams,
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `CROSSES ABOVE fires only on the rising transition bar`() {
        val expr =
            Crosses(
                direction = CrossDir.ABOVE,
                lhs = StreamFieldRef("btc", "close"),
                rhs = NumLit(BigDecimal("100")),
            )
        val compiled = ExprCompiler().compile(expr)
        assertThat(compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99"))) as Value.Bool).v).isFalse()
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "101"))) as Value.Bool).v).isTrue()
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "102"))) as Value.Bool).v).isFalse()
    }

    @Test
    fun `CROSSES BELOW fires only on the falling transition bar`() {
        val expr =
            Crosses(
                direction = CrossDir.BELOW,
                lhs = StreamFieldRef("btc", "close"),
                rhs = NumLit(BigDecimal("100")),
            )
        val compiled = ExprCompiler().compile(expr)
        assertThat(compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "101")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "101"))) as Value.Bool).v).isFalse()
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99"))) as Value.Bool).v).isTrue()
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "98"))) as Value.Bool).v).isFalse()
    }

    @Test
    fun `Undefined operand does not corrupt prev state`() {
        val expr =
            Crosses(
                direction = CrossDir.ABOVE,
                lhs = StreamFieldRef("btc", "close"),
                rhs = NumLit(BigDecimal("100")),
            )
        val compiled = ExprCompiler().compile(expr)
        assertThat(compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99"))) as Value.Bool).v).isFalse()
        assertThat(compiled.evaluate(ctxFor(candleAt("OTHER", "200")))).isEqualTo(Value.Undefined)
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "101"))) as Value.Bool).v).isTrue()
    }
}
