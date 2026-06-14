package com.qkt.dsl.compile

import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
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
    fun `CROSSES in a later CASE branch stays fresh when an earlier branch matches (DSL-17)`() {
        // CASE WHEN btc.close > 1000 THEN 1 WHEN btc.close CROSSES ABOVE 100 THEN 2 ELSE 0.
        // The earlier branch matches on the bar where the rise happens; the CROSSES in the later
        // branch must still see that bar so it records the crossing there, not a bar late.
        val expr =
            CaseWhen(
                branches =
                    listOf(
                        CmpOp(Cmp.GT, StreamFieldRef("btc", "close"), NumLit(BigDecimal("1000"))) to
                            NumLit(BigDecimal.ONE),
                        Crosses(CrossDir.ABOVE, StreamFieldRef("btc", "close"), NumLit(BigDecimal("100"))) to
                            NumLit(BigDecimal("2")),
                    ),
                elseExpr = NumLit(BigDecimal.ZERO),
            )
        val compiled = ExprCompiler().compile(expr)
        // bar1 99: below 100, no branch matches → else 0.
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "99"))) as Value.Num).v)
            .isEqualByComparingTo("0")
        // bar2 2000: matches branch 1 (> 1000) → 1. The CROSSES must still record the 99→2000 rise here.
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "2000"))) as Value.Num).v)
            .isEqualByComparingTo("1")
        // bar3 150: branch 1 false; price stays above 100 but the rise already happened on bar2, so
        // CROSSES ABOVE must NOT fire → else 0. Old short-circuit missed bar2 and falsely fired here (2).
        assertThat((compiled.evaluate(ctxFor(candleAt("BACKTEST:BTCUSDT", "150"))) as Value.Num).v)
            .isEqualByComparingTo("0")
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
