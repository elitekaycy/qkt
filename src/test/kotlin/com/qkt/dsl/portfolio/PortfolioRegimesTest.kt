package com.qkt.dsl.portfolio

import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.ExprCompiler
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** [PortfolioRegimes] picks the first matching regime and weights every import, cash excluded. */
class PortfolioRegimesTest {
    private val ast =
        (
            (
                Dsl.parseAny(
                    """
                    PORTFOLIO p VERSION 1 CAPITAL 100000
                    SYMBOLS
                        btc = BACKTEST:BTCUSDT EVERY 1m
                    IMPORT 'a.qkt' AS a
                    IMPORT 'b.qkt' AS b
                    REGIMES
                        NAME r
                        STATE high WHEN btc.close > 200
                        STATE flat DEFAULT
                    ALLOCATE
                        METHOD regime_weighted
                        high -> a 0.6, CASH 0.4
                        flat -> b 0.5
                    RULES
                        RUN a
                        RUN b
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value as ParsedFile.PortfolioFile
        ).ast

    private fun at(close: String): EvalContext {
        val c = BigDecimal(close)
        return EvalContext(
            Candle("BACKTEST:BTCUSDT", c, c, c, c, BigDecimal.ONE, 0L, 59_999L),
            mapOf("btc" to HubKey("BACKTEST", "BTCUSDT", "1m")),
            emptyMap(),
            testStrategyContext(),
        )
    }

    @Test
    fun `a matching regime weights its children and leaves the rest at zero`() {
        val (name, weights) = PortfolioRegimes(ast, ExprCompiler()).evaluate(at("250"))

        assertThat(name).isEqualTo("high")
        assertThat(weights).containsOnlyKeys("a", "b")
        assertThat(weights.getValue("a")).isEqualByComparingTo("0.6")
        assertThat(weights.getValue("b")).isEqualByComparingTo("0")
    }

    @Test
    fun `without a match the default regime applies`() {
        val (name, weights) = PortfolioRegimes(ast, ExprCompiler()).evaluate(at("100"))

        assertThat(name).isEqualTo("flat")
        assertThat(weights.getValue("b")).isEqualByComparingTo("0.5")
    }
}
