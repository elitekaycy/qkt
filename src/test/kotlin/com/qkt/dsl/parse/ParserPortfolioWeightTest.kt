package com.qkt.dsl.parse

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserPortfolioWeightTest {
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
    fun `PORTFOLIO with CAPITAL and per-RUN WEIGHT parses`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO book VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b WEIGHT 0.4
                """.trimIndent(),
            )
        assertThat(ast.capital).isEqualByComparingTo(java.math.BigDecimal("100000"))
        assertThat((ast.rules[0] as AlwaysRun).alias).isEqualTo("a")
        assertThat((ast.rules[0] as AlwaysRun).weight).isEqualByComparingTo(java.math.BigDecimal("0.6"))
        assertThat((ast.rules[1] as AlwaysRun).weight).isEqualByComparingTo(java.math.BigDecimal("0.4"))
    }

    @Test
    fun `PORTFOLIO weights summing over one rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.7
                    RUN b WEIGHT 0.5
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("must sum to <= 1.0")
    }

    @Test
    fun `PORTFOLIO weights summing under one allowed`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO reserve VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.5
                    RUN b WEIGHT 0.3
                """.trimIndent(),
            )
        assertThat(ast.capital).isEqualByComparingTo(java.math.BigDecimal("100000"))
    }

    @Test
    fun `PORTFOLIO partial WEIGHT rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("all-or-none")
    }

    @Test
    fun `PORTFOLIO WEIGHT without CAPITAL rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a WEIGHT 1.0
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("CAPITAL is required")
    }

    @Test
    fun `PORTFOLIO CAPITAL without any WEIGHT rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("no RUN carries WEIGHT")
    }

    @Test
    fun `PORTFOLIO WEIGHT out of range rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a WEIGHT 1.5
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("must be in (0, 1]")
    }
}
