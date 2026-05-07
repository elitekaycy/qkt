package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExprCompilerStateTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)

    private fun ctxWithPnl(
        realized: BigDecimal = BigDecimal.ZERO,
        unrealizedTotal: BigDecimal = BigDecimal.ZERO,
    ): EvalContext {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = realized

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = unrealizedTotal

                override fun total(): BigDecimal = realized.add(unrealizedTotal)
            }
        return EvalContext(
            candle = candle,
            streamSymbols = emptyMap(),
            lets = emptyMap(),
            strategyContext = testStrategyContext(pnl = pnl),
        )
    }

    @Test
    fun `ACCOUNT realized_pnl reads from pnl view`() {
        val v =
            ExprCompiler().compile(AccountRef("realized_pnl"))
                .evaluate(ctxWithPnl(realized = BigDecimal("123.45"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("123.45")
    }

    @Test
    fun `ACCOUNT unrealized_pnl reads from pnl view`() {
        val v =
            ExprCompiler().compile(AccountRef("unrealized_pnl"))
                .evaluate(ctxWithPnl(unrealizedTotal = BigDecimal("7.5"))) as Value.Num
        assertThat(v.v).isEqualByComparingTo("7.5")
    }

    @Test
    fun `ACCOUNT total_pnl reads from pnl view`() {
        val v =
            ExprCompiler().compile(AccountRef("total_pnl"))
                .evaluate(
                    ctxWithPnl(realized = BigDecimal("10"), unrealizedTotal = BigDecimal("5")),
                ) as Value.Num
        assertThat(v.v).isEqualByComparingTo("15")
    }

    @Test
    fun `unsupported ACCOUNT field is rejected at compile time`() {
        assertThatThrownBy { ExprCompiler().compile(AccountRef("equity")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
