package com.qkt.dsl.portfolio

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioAllocationTest {
    private fun portfolioAst(src: String) =
        ((Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.PortfolioFile).ast

    @Test
    fun `weighted portfolio allocates capital times weight per alias`() {
        val ast =
            portfolioAst(
                """
                PORTFOLIO book VERSION 1 CAPITAL 100000
                IMPORT 'a.qkt' AS a
                IMPORT 'b.qkt' AS b
                RULES
                    RUN a WEIGHT 0.6
                    RUN b WEIGHT 0.4
                """.trimIndent(),
            )
        val alloc = capitalAllocations(ast)
        assertThat(alloc["a"]).isEqualByComparingTo(java.math.BigDecimal("60000"))
        assertThat(alloc["b"]).isEqualByComparingTo(java.math.BigDecimal("40000"))
    }

    @Test
    fun `portfolio without weights allocates nothing`() {
        val ast =
            portfolioAst(
                """
                PORTFOLIO plain VERSION 1
                IMPORT 'a.qkt' AS a
                RULES
                    RUN a
                """.trimIndent(),
            )
        assertThat(capitalAllocations(ast)).isEmpty()
    }
}
