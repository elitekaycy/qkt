package com.qkt.dsl.compile

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizePctBalance
import com.qkt.dsl.ast.SizePctEquity
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.dsl.ast.SizeRiskFrac
import com.qkt.dsl.ast.SizeRiskFracOfBook
import com.qkt.marketdata.Candle
import com.qkt.pnl.BookBalanceView
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
        book: BookBalanceView? = null,
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
            strategyContext = testStrategyContext(pnl = pnl, book = book),
        )
    }

    @Test
    fun `pct of equity divides risked equity by entry price`() {
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizePctEquity(NumLit(BigDecimal("0.01"))), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("10000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("1")
    }

    @Test
    fun `pct of balance divides risked balance by entry price`() {
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizePctBalance(NumLit(BigDecimal("0.05"))), stopDistance = null, streamAlias = "btc")
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("8000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("4")
    }

    @Test
    fun `risk fraction divides risked equity by stop distance`() {
        // Default test registry uses contractSize = 1 so this degenerates to equity * frac / stopDistance.
        val s =
            SizingCompiler(ExprCompiler())
                .compile(SizeRiskFrac(NumLit(BigDecimal("0.01"))), stopDistance = BigDecimal("5"), streamAlias = "btc")
        assertThat(s.evaluate(ctx(BigDecimal("10000"), BigDecimal("10000")), entryPrice = BigDecimal("100")))
            .isEqualByComparingTo("20")
    }

    @Test
    fun `risk fraction without stop distance errors at compile time`() {
        assertThatThrownBy {
            SizingCompiler(ExprCompiler()).compile(
                SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                stopDistance = null,
                streamAlias = "btc",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every risk form skips the order when the runtime stop distance is not positive`() {
        // A stop that resolved onto the entry leaves nothing to divide by: null (skip), never a throw.
        val compiler = SizingCompiler(ExprCompiler())
        val book = BookBalanceView { BigDecimal("10000") }
        val forms =
            listOf(
                SizeRiskAbs(NumLit(BigDecimal("100"))),
                SizeRiskFrac(NumLit(BigDecimal("0.01"))),
                SizeRiskFracOfBook(NumLit(BigDecimal("0.01"))),
            )
        for (form in forms) {
            val s =
                compiler.compile(
                    form,
                    stopDistance = null,
                    streamAlias = "btc",
                    runtimeStopDistanceAvailable = true,
                )
            val ec = ctx(BigDecimal("10000"), BigDecimal("10000"), book)
            assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"), runtimeStopDistance = BigDecimal.ZERO))
                .describedAs(form.toString())
                .isNull()
            assertThat(s.evaluate(ec, entryPrice = BigDecimal("100"), runtimeStopDistance = BigDecimal("5")))
                .describedAs(form.toString())
                .isEqualByComparingTo("20")
        }
    }
}
