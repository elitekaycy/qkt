package com.qkt.dsl.parse

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.WhenRun
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserPortfolioTest {
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
    fun `simple PORTFOLIO with single IMPORT and bare RUN parses`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO p1 VERSION 1
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a
                """.trimIndent(),
            )
        assertThat(ast.name).isEqualTo("p1")
        assertThat(ast.version).isEqualTo(1)
        assertThat(ast.imports).containsExactly(
            ImportClause(path = "a.qkt", alias = "a", hold = false),
        )
        assertThat(ast.rules).containsExactly(AlwaysRun("a"))
    }

    @Test
    fun `PORTFOLIO with two imports and WHEN-RUN parses`() {
        val ast =
            parsePortfolioText(
                """
                PORTFOLIO p2 VERSION 1
                SYMBOLS
                    btc = BACKTEST:BTCUSDT EVERY 1h
                IMPORT 'trend.qkt' AS trend
                IMPORT 'range.qkt' AS range HOLD
                RULES
                    WHEN btc.close > 100 RUN trend
                    WHEN btc.close <= 100 RUN range
                """.trimIndent(),
            )
        assertThat(ast.imports).hasSize(2)
        assertThat(ast.imports[1].hold).isTrue
        assertThat(ast.rules).hasSize(2)
        assertThat(ast.rules[0]).isInstanceOf(WhenRun::class.java)
        assertThat((ast.rules[0] as WhenRun).alias).isEqualTo("trend")
        assertThat((ast.rules[1] as WhenRun).alias).isEqualTo("range")
    }

    @Test
    fun `PORTFOLIO duplicate alias rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS x
                IMPORT 'b.qkt' AS x
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("aliases must be unique")
    }

    @Test
    fun `PORTFOLIO with no imports rejected`() {
        val failure = parsePortfolioFailure("PORTFOLIO empty VERSION 1")
        assertThat(failure.errors.joinToString { it.message }).contains("at least one IMPORT")
    }

    @Test
    fun `PORTFOLIO with duplicate import path rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO dup VERSION 1
                IMPORT 'same.qkt' AS x
                IMPORT 'same.qkt' AS y
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("import paths must be unique")
    }

    @Test
    fun `PORTFOLIO unknown alias in RUN rejected`() {
        val failure =
            parsePortfolioFailure(
                """
                PORTFOLIO bad VERSION 1
                IMPORT 'a.qkt' AS x
                RULES
                    RUN y
                """.trimIndent(),
            )
        assertThat(failure.errors.joinToString { it.message }).contains("unknown alias")
    }
}
