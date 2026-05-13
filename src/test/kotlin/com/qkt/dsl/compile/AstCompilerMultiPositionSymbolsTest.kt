package com.qkt.dsl.compile

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AstCompilerMultiPositionSymbolsTest {
    private fun compile(src: String): DslCompiledStrategy {
        val ast = (Parser(Lexer(src).tokenize()).parseStrategy() as ParseResult.Success).value
        return AstCompiler().compile(ast) as DslCompiledStrategy
    }

    @Test
    fun `strategy with one STACK_AT BUY exposes its symbol`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 0.1
                    STACK_AT MFE >= 50 WITHIN 30m SIZING 0.05
                        BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
            """.trimIndent()
        val s = compile(src)
        assertThat(s.multiPositionPerSymbolSymbols).containsExactlyInAnyOrder("BACKTEST:BTCUSDT")
    }

    @Test
    fun `strategy with no STACK_AT exposes an empty set`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 0.1
            """.trimIndent()
        val s = compile(src)
        assertThat(s.multiPositionPerSymbolSymbols).isEmpty()
    }

    @Test
    fun `OCO_ENTRY with STACK_AT on both legs collects both symbols`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:XAUUSD EVERY 1m
            RULES
                WHEN gold.close > 0 THEN OCO_ENTRY {
                    BUY  gold SIZING 0.1 BRACKET { STOP LOSS BY 10, TAKE PROFIT BY 20 }
                         STACK_AT MFE >= 5 WITHIN 30m SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 },
                    SELL gold SIZING 0.1 BRACKET { STOP LOSS BY 10, TAKE PROFIT BY 20 }
                         STACK_AT MFE >= 5 WITHIN 30m SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 4 }
                }
            """.trimIndent()
        val s = compile(src)
        assertThat(s.multiPositionPerSymbolSymbols).containsExactlyInAnyOrder("BACKTEST:XAUUSD")
    }

    @Test
    fun `multiple rules on different symbols all contribute`() {
        val src =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m,
                eth = BACKTEST:ETHUSDT EVERY 1m
            RULES
                WHEN btc.close > 0 THEN BUY btc SIZING 0.1
                    STACK_AT MFE >= 50 WITHIN 30m SIZING 0.05 BRACKET { STOP LOSS BY 2, TAKE PROFIT BY 20 }
                WHEN eth.close > 0 THEN SELL eth SIZING 0.2
                    STACK_AT MFE >= 5 WITHIN 30m SIZING 0.1 BRACKET { STOP LOSS BY 1, TAKE PROFIT BY 5 }
            """.trimIndent()
        val s = compile(src)
        assertThat(s.multiPositionPerSymbolSymbols).containsExactlyInAnyOrder("BACKTEST:BTCUSDT", "BACKTEST:ETHUSDT")
    }
}
