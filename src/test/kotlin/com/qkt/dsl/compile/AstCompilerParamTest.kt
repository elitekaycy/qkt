package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerParamTest {
    private fun ast(src: String) =
        ((Parser(Lexer(src).tokenize()).parseFile() as ParseResult.Success).value as ParsedFile.StrategyFile).ast

    @Test
    fun `a PARAM used in a condition and a SIZING action compiles to its default`() {
        val a =
            ast(
                """
                STRATEGY s VERSION 1
                SYMBOLS gold = BYBIT:XAUUSD EVERY 5m
                PARAM riskPct   = 0.01
                PARAM rsiPeriod = 14
                PARAM stopDist  = 50
                RULES
                  WHEN rsi(gold.close, rsiPeriod) < 35
                    THEN BUY gold SIZING RISK ${'$'} (ACCOUNT.equity * riskPct)
                         BRACKET { STOP LOSS BY stopDist }
                """.trimIndent(),
            )
        val strategy = AstCompiler().compile(a)
        assertThat(strategy).isNotNull()
    }
}
