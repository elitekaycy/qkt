package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeNotional
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizePositionFull
import com.qkt.dsl.ast.SizeQty
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.dsl.ast.SizingAst
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.marketdata.Candle
import com.qkt.strategy.QuoteToAccountRateProvider
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every sizing form skips the order while its operand is undefined, and refuses unknown streams and bad FX rates. */
class SizingUndefinedOperandTest {
    private val one = BigDecimal.ONE
    private val candle = Candle("BACKTEST:XAUUSD", one, one, one, one, BigDecimal.ZERO, 0L, 1L)
    private val streams = mapOf("g" to HubKey("BACKTEST", "XAUUSD", "1m"))

    /** The bar has no bid, so `g.bid` is undefined. */
    private val undefined = StreamFieldRef("g", "bid")

    private fun ctx(rate: QuoteToAccountRateProvider = QuoteToAccountRateProvider.IDENTITY) =
        EvalContext(candle, streams, emptyMap(), testStrategyContext(quoteToAccountRate = rate))

    private fun size(
        sizing: SizingAst,
        alias: String = "g",
        rate: QuoteToAccountRateProvider = QuoteToAccountRateProvider.IDENTITY,
    ): BigDecimal? =
        SizingCompiler(ExprCompiler())
            .compile(sizing, stopDistance = BigDecimal("2"), streamAlias = alias)
            .evaluate(ctx(rate), BigDecimal("100"), null)

    @Test
    fun `an undefined operand skips every sizing form`() {
        val forms =
            listOf(
                SizeQty(undefined),
                SizeNotional(undefined),
                SizeRiskAbs(undefined),
                SizePctEquity(undefined),
                SizePctBalance(undefined),
                SizeRiskFrac(undefined),
                SizeRiskFracOfBook(undefined),
            )

        assertThat(forms.map { size(it) }).containsOnlyNulls()
    }

    @Test
    fun `sizing by an undeclared stream's position fails`() {
        assertThatThrownBy { size(SizePositionFull("copper")) }.hasMessageContaining("Unknown stream alias: copper")
    }

    @Test
    fun `notional sizing on an undeclared stream fails`() {
        assertThatThrownBy { size(SizeNotional(NumLit(BigDecimal("100"))), alias = "copper") }
            .hasMessageContaining("Unknown stream alias: copper")
    }

    @Test
    fun `a non-positive quote-to-account rate is refused`() {
        val zero = QuoteToAccountRateProvider { _, _, _ -> BigDecimal.ZERO }

        assertThatThrownBy { size(SizeNotional(NumLit(BigDecimal("100"))), rate = zero) }
            .hasMessageContaining("quote-to-account rate must be positive for BACKTEST:XAUUSD: 0")
    }
}
