package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EquitySizingTest {
    private val candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            0L,
            1L,
        )

    private fun ctx(
        equityValue: BigDecimal,
        balanceValue: BigDecimal,
    ): EvalContext {
        val pnl =
            object : StrategyPnLView {
                override fun realized(): BigDecimal = BigDecimal.ZERO

                override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                override fun total(): BigDecimal = BigDecimal.ZERO

                override fun equity(): BigDecimal = equityValue

                override fun balance(): BigDecimal = balanceValue
            }
        return EvalContext(
            candle = candle,
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(pnl = pnl),
        )
    }

    @Test
    fun `pct of equity divides risked equity by entry price`() {
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizePctEquity(NumLit(BigDecimal("0.01"))), stopDistance = null)
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("10000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("1")
    }

    @Test
    fun `pct of balance divides risked balance by entry price`() {
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizePctBalance(NumLit(BigDecimal("0.05"))), stopDistance = null)
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("8000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("4")
    }

    @Test
    fun `risk fraction divides risked equity by stop distance`() {
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizeRiskFrac(NumLit(BigDecimal("0.01"))), stopDistance = BigDecimal("5"))
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("10000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("20")
    }

    @Test
    fun `risk fraction without stop distance errors at compile time`() {
        assertThatThrownBy {
            SizingCompiler(ExprCompiler()).compile(SizeRiskFrac(NumLit(BigDecimal("0.01"))), stopDistance = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
