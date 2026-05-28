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
            streams = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    @Test
    fun `ABS evaluates`() {
        val v =
            ExprCompiler()
                .compile(FuncCall("ABS", listOf(NumLit(BigDecimal("-7")))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7")
    }

    @Test
    fun `MAX of three`() {
        val v =
            ExprCompiler()
                .compile(
                    FuncCall(
                        "MAX",
                        listOf(NumLit(BigDecimal("1")), NumLit(BigDecimal("9")), NumLit(BigDecimal("3"))),
                    ),
                ).evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("9")
    }

    @Test
    fun `SQRT evaluates`() {
        val v =
            ExprCompiler()
                .compile(FuncCall("SQRT", listOf(NumLit(BigDecimal("16")))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("4")
    }

    @Test
    fun `LOG evaluates and is Undefined on non-positive`() {
        val v1 =
            ExprCompiler()
                .compile(FuncCall("LOG", listOf(NumLit(BigDecimal("1")))))
                .evaluate(ctx) as Value.Num
        assertThat(v1.v).isEqualByComparingTo("0")

        val v2 =
            ExprCompiler()
                .compile(FuncCall("LOG", listOf(NumLit(BigDecimal("0")))))
                .evaluate(ctx)
        assertThat(v2).isEqualTo(Value.Undefined)
    }

    @Test
    fun `EXP evaluates and is Undefined on overflow`() {
        val v1 =
            ExprCompiler()
                .compile(FuncCall("EXP", listOf(NumLit(BigDecimal.ZERO))))
                .evaluate(ctx) as Value.Num
        assertThat(v1.v).isEqualByComparingTo("1")

        val v2 =
            ExprCompiler()
                .compile(FuncCall("EXP", listOf(NumLit(BigDecimal("10000")))))
                .evaluate(ctx)
        assertThat(v2).isEqualTo(Value.Undefined)
    }

    @Test
    fun `POW evaluates`() {
        val v =
            ExprCompiler()
                .compile(FuncCall("POW", listOf(NumLit(BigDecimal("2")), NumLit(BigDecimal("10")))))
                .evaluate(ctx) as Value.Num
        assertThat(v.v).isEqualByComparingTo("1024")
    }

    @Test
    fun `SQRT of negative is Undefined`() {
        val v =
            ExprCompiler()
                .compile(FuncCall("SQRT", listOf(NumLit(BigDecimal("-4")))))
                .evaluate(ctx)
        assertThat(v).isEqualTo(Value.Undefined)
    }

    @Test
    fun `Undefined arg makes the result Undefined`() {
        // StreamFieldRef("btc", "close") has no stream symbol mapping → throws.
        // Use a mismatched candle symbol instead which yields Undefined cleanly.
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
                    FuncCall(
                        "MAX",
                        listOf(NumLit(BigDecimal("1")), StreamFieldRef("btc", "close")),
                    ),
                ).evaluate(ec)
        assertThat(v).isEqualTo(Value.Undefined)
    }
}
