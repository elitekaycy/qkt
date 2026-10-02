package com.qkt.dsl.parse

import com.qkt.dsl.ast.StrategyAst
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ParserContinuousStreamTest {
    private fun parse(stream: String): ParseResult<StrategyAst> =
        Dsl.parse(
            """
            STRATEGY s VERSION 1
            SYMBOLS
                btc = $stream EVERY 15m
            RULES
                WHEN btc.close > 0 THEN LOG "x"
            """.trimIndent(),
        )

    @Test
    fun `a front selector stays inside the symbol`() {
        val ast = (parse("BINANCE_UM:BTCUSDT@front") as ParseResult.Success).value
        assertThat(ast.streams.single().symbol).isEqualTo("BTCUSDT@front")
        assertThat(ast.streams.single().qktSymbol).isEqualTo("BINANCE_UM:BTCUSDT@front")
    }

    @Test
    fun `a next selector parses`() {
        val ast = (parse("CME:ES@next") as ParseResult.Success).value
        assertThat(ast.streams.single().symbol).isEqualTo("ES@next")
    }

    @Test
    fun `an unknown selector is a parse error naming the valid ones`() {
        val failure = parse("CME:ES@third") as ParseResult.Failure
        assertThat(failure.errors.joinToString { it.message }).contains("third").contains("front").contains("next")
    }

    @Test
    fun `a selector needs a name after the at sign`() {
        assertThat(parse("CME:ES@")).isInstanceOf(ParseResult.Failure::class.java)
    }
}
