package com.qkt.dsl.parse

import com.qkt.dsl.ast.PortfolioAllocationMethod
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.RegimeConditionalState
import com.qkt.dsl.ast.RegimeDefaultState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioRegimeParseTest {
    private fun parsePortfolioText(src: String): PortfolioAst {
        val tokens = Lexer(src).tokenize()
        val result = Parser(tokens).parseFile()
        if (result is ParseResult.Failure) {
            error("parse failed: ${result.errors.joinToString { it.message }}")
        }
        val success = result as ParseResult.Success<ParsedFile>
        return (success.value as ParsedFile.PortfolioFile).ast
    }

    private fun parsePortfolioFailure(src: String): ParseResult.Failure<ParsedFile> {
        val tokens = Lexer(src).tokenize()
        val result = Parser(tokens).parseFile()
        return result as ParseResult.Failure<ParsedFile>
    }

    @Test
    fun `regimes and allocate block parse`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO rp VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'trend.qkt' AS trend
                IMPORT 'meanrev.qkt' AS meanrev
                REGIMES
                    NAME vol_regime
                    STATE high_vol WHEN percentile_rank(stddev(btc.close, 20), 252) > 0.7
                    STATE low_vol WHEN percentile_rank(stddev(btc.close, 20), 252) < 0.3
                    STATE normal DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    REBALANCE EVERY 24h
                    high_vol -> cash 1.0
                    low_vol -> trend 0.5, meanrev 0.5
                    normal -> trend 0.33, meanrev 0.33
                RULES
                    RUN trend
                    RUN meanrev
                """.trimIndent(),
            )

        assertThat(ast.capital).isEqualByComparingTo(BigDecimal("100000"))
        val regimes = ast.regimes
        assertThat(regimes).isNotNull
        assertThat(regimes!!.name).isEqualTo("vol_regime")
        assertThat(regimes.states).hasSize(3)
        assertThat(regimes.states[0]).isInstanceOf(RegimeConditionalState::class.java)
        assertThat(regimes.states[0].name).isEqualTo("high_vol")
        assertThat(regimes.states[2]).isInstanceOf(RegimeDefaultState::class.java)
        assertThat(regimes.states[2].name).isEqualTo("normal")

        val allocate = ast.allocate
        assertThat(allocate).isNotNull
        assertThat(allocate!!.method).isEqualTo(PortfolioAllocationMethod.REGIME_WEIGHTED)
        assertThat(allocate.rebalanceEveryDurationMs).isEqualTo(24L * 3_600_000L)
        assertThat(allocate.entries.keys).containsExactly("high_vol", "low_vol", "normal")
        assertThat(allocate.entries["low_vol"]).containsEntry("trend", BigDecimal("0.5"))
        assertThat(allocate.entries["low_vol"]).containsEntry("meanrev", BigDecimal("0.5"))
    }

    @Test
    fun `allocate without capital rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS a
                ALLOCATE
                    METHOD regime_weighted
                    flat -> a 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("CAPITAL is required when ALLOCATE")
    }

    @Test
    fun `allocate and per-RUN weight are mutually exclusive`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                ALLOCATE
                    METHOD regime_weighted
                    flat -> a 1.0
                RULES
                    RUN a WEIGHT 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("mutually exclusive")
    }

    @Test
    fun `regime_weighted without regimes rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                ALLOCATE
                    METHOD regime_weighted
                    flat -> a 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("requires a REGIMES block")
    }

    @Test
    fun `regime_weighted without default state rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE s WHEN btc.close > 100
                ALLOCATE
                    METHOD regime_weighted
                    s -> a 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("exactly one DEFAULT state")
    }

    @Test
    fun `duplicate regime state names rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE s WHEN btc.close > 100
                    STATE s DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    s -> a 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("regime state names must be unique")
    }

    @Test
    fun `regime weights summing over one rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                REGIMES
                    NAME r
                    STATE s DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    s -> a 0.7, b 0.5
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("weights sum to 1.2")
    }

    @Test
    fun `unknown alias in regime weights rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'a.qkt' AS a
                REGIMES
                    NAME r
                    STATE s DEFAULT
                ALLOCATE
                    METHOD regime_weighted
                    s -> x 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("unknown alias 'x'")
    }
}
