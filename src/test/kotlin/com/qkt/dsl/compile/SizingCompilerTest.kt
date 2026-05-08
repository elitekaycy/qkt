package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SizingCompilerTest {
    private val ec =
        EvalContext(
            candle =
                Candle(
                    "BTCUSDT",
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal("100"),
                    BigDecimal.ZERO,
                    0L,
                    1L,
                ),
            streams = mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            lets = emptyMap(),
            strategyContext = testStrategyContext(),
        )

    private fun compiler() = SizingCompiler(ExprCompiler())

    @Test
    fun `SizeQty returns the expression`() {
        val s = compiler().compile(SizeQty(NumLit(BigDecimal("3"))), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("3")
    }

    @Test
    fun `SizeNotional divides USD by entry price`() {
        val s = compiler().compile(SizeNotional(NumLit(BigDecimal("500"))), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("5")
    }

    @Test
    fun `SizeRiskAbs computes quantity from amount and stop distance`() {
        val s = compiler().compile(SizeRiskAbs(NumLit(BigDecimal("50"))), stopDistance = BigDecimal("5"))
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("10")
    }

    @Test
    fun `SizeRiskAbs without stop distance errors at compile time`() {
        assertThatThrownBy {
            compiler().compile(SizeRiskAbs(NumLit(BigDecimal("50"))), stopDistance = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `SizePositionFull returns absolute quantity when flat`() {
        val s = compiler().compile(SizePositionFull("btc"), stopDistance = null)
        assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"))).isEqualByComparingTo("0")
    }
}
